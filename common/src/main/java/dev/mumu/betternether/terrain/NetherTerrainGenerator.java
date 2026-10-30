package dev.mumu.betternether.terrain;

import dev.mumu.betternether.biome.BiomeEdges;
import dev.mumu.betternether.biome.BiomePlacement;
import dev.mumu.betternether.biome.SurfaceRules;
import dev.mumu.betternether.biome.NetherBiomeProvider;
import dev.mumu.betternether.biome.NetherBiomes;
import dev.mumu.betternether.biome.NetherClimateSampler;
import dev.mumu.betternether.biome.NmsBiomeSelector;
import dev.mumu.betternether.config.NetherConfig;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.Noises;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.synth.Noise;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.World.Environment;
import org.bukkit.block.Biome;
import org.bukkit.block.data.BlockData;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.generator.CustomChunkGenerator;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.IntPredicate;
import java.util.function.UnaryOperator;
import java.util.logging.Logger;

/**
 * The BetterNether world generator for a NETHER world.
 *
 * <h2>Where the terrain comes from</h2>
 * Nowhere in this project, and nowhere in the mod either. Upstream's generator class
 * ({@code world/BNWorldGenerator}) is an empty shell -- a mod-init hook and a thread context, no
 * noise of its own. BetterNether keeps <em>vanilla's</em> Nether and layers two things on top of it:
 * a per-biome surface rule, and features (plants, ores, structures).
 *
 * <p>So this generator deliberately generates no terrain. Vanilla's Nether noise stays the structural
 * base -- the cavern shapes, the lava sea at y=31 and the bedrock floor/ceiling that players expect
 * from a Nether -- and the port takes over the surface rule here (per-biome materials) plus ores,
 * flora and structures in populators. Keeping {@code shouldGenerateNoise = true} is therefore not a
 * placeholder for later work: it is the faithful behaviour.
 *
 * <p>{@link #generateSurface} paints every exposed top face in a column, plus the roof's underside,
 * so ledges, steps and cave floors each carry their own biome's materials rather than only the
 * highest one. The bedrock plates are not painted -- they are stamped by {@link #generateBedrock},
 * which is where vanilla's own bedrock comes from in the first place (the surface rule, not the
 * noise pass).
 *
 * <h2>The two surfaces are read at their own heights</h2>
 * {@link BiomePlacement} puts a vertical biome in the band above
 * {@link BiomePlacement#CEILING_BAND_FLOOR}, which only {@code keyAt(x, y, z)} can report. A column's
 * floor and ceiling sit far apart vertically, so they are looked up separately -- the floor at its own
 * y, the ceiling at {@link #roofBottom}; see {@link #surfaceBiomes}. Reading the column once at ground
 * level and reusing that key for the roof is what made the vertical biome a label with no terrain
 * behind it: players were told {@code upside_down_forest} overhead while the rock above them kept the
 * neighbouring biome's palette.
 *
 * <h2>Two rule sets, one pass</h2>
 * The 23 upstream files cover the mod's own biomes; the five vanilla nether biomes this port's biome
 * source can also produce are painted by vanilla's own rule, transcribed into
 * {@link SurfaceRules.Book#vanilla()}. A column whose key has no upstream rule set falls back to that
 * one instead of to "paint nothing" -- which is what used to leave {@code soul_sand_valley} and
 * {@code basalt_deltas} as bare netherrack. Both sets are evaluated by the same column walk, so a
 * block is still decided once, by one rule, from the base terrain.
 *
 * <h2>Flag set, and why</h2>
 * <ul>
 *   <li>{@code shouldGenerateNoise = true}: let vanilla build the Nether mass and its caverns.</li>
 *   <li>{@code shouldGenerateSurface = false}, with {@link #generateSurface} overridden: the flag
 *       selects whether <em>vanilla</em> runs its surface rule, and our own method is called either
 *       way. See that override for the bytecode this was read from -- vanilla's nether rule would
 *       otherwise paint its own materials underneath ours, and upstream replaces that rule set
 *       outright.</li>
 *   <li>{@code shouldGenerateBedrock = true}, with {@link #generateBedrock} overridden: the plates are
 *       the surface rule's work, so taking the surface phase over means taking the bedrock with
 *       it.</li>
 *   <li>{@code shouldGenerateCaves = true}: keep the {@code minecraft:nether_cave} carver.</li>
 *   <li>{@code shouldGenerateDecorations = false}: vanilla's decoration pass does structures
 *       <em>and</em> placed features in one method, so it is taken over wholesale -- structures are
 *       re-placed and the {@code minecraft:} placed features are executed natively by
 *       {@code DecorationScheduler}, in step order with the mod's own features interleaved. See that
 *       override for the bytecode.</li>
 *   <li>{@code shouldGenerateStructures = true}: keep vanilla fortresses and bastions; our own
 *       structures are placed by the datapack's structure_set, which only runs when this is true.</li>
 *   <li>{@code shouldGenerateMobs = true}: vanilla Nether spawns.</li>
 *   <li>{@code getBaseHeight} is intentionally NOT overridden, so structure placement and spawn
 *       selection read vanilla's own heightmap instead of a plugin guess.</li>
 * </ul>
 */
public final class NetherTerrainGenerator extends ChunkGenerator {
    /**
     * The Nether's highest y: vanilla seeds its bedrock roof over y=123..127.
     *
     * <p>This is the <em>top</em> of the roof, not its thickness. {@link #roofBottom} walks down from
     * here for as long as the column stays solid, so the roof's real depth never has to be assumed --
     * and where the noise hangs netherrack off the roof, that netherrack is found instead.
     */
    public static final int CEILING_Y = 127;
    /**
     * Vanilla Nether lava sea level.
     *
     * <p>Nothing here needs to special-case the sea: lava is not a solid material, so
     * {@link #snapshot} never marks it solid and the surface pass can never paint over it. The floor
     * scan
     * simply passes through the lava down to the sea bed, exactly as upstream's floor rules keep
     * applying to submerged terrain.
     */
    public static final int LAVA_LEVEL = 31;
    /**
     * Highest y of the bedrock floor plate.
     *
     * <p>Terrain at or below this is structural and is never painted. Vanilla's surface rules only
     * ever replace stone, and the mod inherits that: painting a biome block into the bedrock plate
     * would both break the floor of the world and replace an unbreakable block with a breakable one.
     */
    public static final int BEDROCK_FLOOR_MAX = 4;
    /**
     * Lowest y of the bedrock roof plate.
     *
     * <p>Both plates are vanilla's own, and upstream re-declares them verbatim
     * ({@code NetherBiomeBuilder.java:117-127}): {@code BEDROCK_BOTTOM = verticalGradient("bedrock_floor",
     * bottom(), aboveBottom(5))} and {@code BEDROCK_TOP = verticalGradient("bedrock_roof",
     * belowTop(5), top())}, both painted as {@code BEDROCK} at a priority above every biome rule
     * ({@code NetherBiomeConfig.java:80-83}). A vertical gradient is 1 at its first anchor and 0 at
     * its second, so the floor plate covers y=0..4 (1.0 at y=0, 0.2 at y=4, 0 at y=5) and the roof
     * plate y=123..127 (0.2 at y=123, 1.0 at y=127, 0 at y=122). Outside those two bands the vanilla
     * rule places no bedrock at all, which is what lets the painter treat them as immovable.
     *
     * <p>Checked against the shipped 26.1.2 server jar rather than from memory: {@code SurfaceRuleData}
     * emits exactly those two gradients ({@code bedrock_floor} = {@code bottom()} ..
     * {@code aboveBottom(5)}, {@code bedrock_roof} = {@code belowTop(5)} .. {@code top()}), and
     * {@code NoiseBasedChunkGenerator} contains no bedrock reference at all -- so the plate is the
     * <em>surface rule's</em> work, not the noise pass's. See {@link #generateBedrock}.
     */
    public static final int BEDROCK_ROOF_MIN = CEILING_Y - 4;

    /**
     * Lowest y the surface pass may write: one above the bedrock floor plate.
     *
     * <p>The two bands {@code [0, BEDROCK_FLOOR_MAX]} and {@code [BEDROCK_ROOF_MIN, CEILING_Y]} are
     * never painted. That is not caution for its own sake: both are places where vanilla (and
     * upstream, which copies the same gradients) puts bedrock, and a surface rule can only replace
     * stone -- painting a biome block there would trade an unbreakable block for a breakable one at
     * the top or bottom of the world.
     */
    static final int PAINT_MIN_Y = BEDROCK_FLOOR_MAX + 1;

    /** Highest y the surface pass may write: one below the bedrock roof plate. */
    static final int PAINT_MAX_Y = BEDROCK_ROOF_MIN - 1;

    /** Blocks per column, y=0..{@link #CEILING_Y} inclusive. */
    static final int COLUMN_HEIGHT = CEILING_Y + 1;

    /**
     * The block the noise stage leaves everywhere in the Nether's rock: {@code NoiseGeneratorSettings}'
     * {@code default_block} for {@code minecraft:nether}.
     *
     * <p>It is what makes the deep-write filter safe. Past the depth budget the base terrain is this
     * block, so a rule that decides it again is a no-op and the write is skipped. Anything else — the
     * {@code soul_sand}/{@code soul_soil} pockets of the swamplands and wart forests, the
     * {@code deepslate} of {@code flooded_deltas} — is a real material difference and is written.
     */
    static final String BASE_TERRAIN_ID = "minecraft:netherrack";

    /**
     * {@code getType} calls the surface pass spends on one chunk: one per block, exactly once.
     *
     * <p>This is the observable bound {@link #generateSurface} is built to. It reads the whole column
     * into a snapshot before deciding anything ({@link #snapshot}), because Paper's {@code ChunkData}
     * is a live view of the chunk being built -- {@code CraftChunkData} holds the chunk itself, not a
     * staging copy -- so a read after a write sees the pass's own output and every face test becomes
     * order-dependent. Reading 128 blocks once per column costs 32,768 reads per chunk; the previous
     * per-y face tests cost three to four reads per y (~110k per chunk) and still gave answers that
     * changed under them.
     */
    static final int SNAPSHOT_READS_PER_CHUNK = 16 * 16 * COLUMN_HEIGHT;

    /** The id the floor's remainder resolves to; {@link #surface} also falls back to it. */
    private static final String NETHERRACK_ID = "minecraft:netherrack";

    /** Snapshot flag: the base terrain left a non-air block at this y (fluids count, as vanilla does). */
    private static final byte FLAG_FILLED = 1;
    /**
     * Snapshot flag: that block is stone, i.e. {@code !isAir && fluidState.isEmpty()} -- vanilla's own
     * {@code MaterialSystem.isStone}, which is what {@code stoneDepthBelow} counts down through.
     */
    private static final byte FLAG_SOLID = 2;
    /** Snapshot flag: that block is bedrock, which no surface rule may replace. */
    private static final byte FLAG_BEDROCK = 4;

    /**
     * How deep below {@link #CEILING_Y} the roof scan may go before giving up.
     *
     * <p>The vanilla roof is ~5 blocks of bedrock, so the scan normally ends after 6 reads. The cap
     * only exists for the pathological column that is solid from the roof all the way down -- there
     * the "ceiling" and the "floor" are one mass with no surface to paint, and stopping early is
     * better than walking 120 layers to discover that.
     */
    private static final int CEILING_SCAN_MAX = 32;

    /**
     * The salt that used to seed this port's own per-column draw for {@code secondary_depth_range}.
     *
     * <p>Gone as a mechanism, kept as a note: vanilla's {@code secondary_depth_range} consumes
     * {@code MaterialSystem.getSurfaceSecondary(x, z)}, i.e. the world's {@code minecraft:surface_secondary}
     * {@code NormalNoise} read at {@code (x, 0, z)}, mapped through
     * {@code Mth.map(value, -1, 1, 0, range)}. A hash of {@code (x, z)} was a different field with a
     * different distribution -- and, because it was a {@code [0,1)} hash multiplied by {@code range + 1},
     * a different range as well. The real field is injected through
     * {@link SurfaceRules.Vanilla#surfaceSecondary(int, int)}.
     */
    private final NetherConfig config;
    private final NetherBiomes.Resolved biomes;
    /**
     * The biome field actually in use.
     *
     * <p>Bukkit gives a generator no world: {@code getDefaultWorldGenerator(worldName, id)} runs before
     * the world exists, and the {@link WorldInfo} handed to {@code getDefaultBiomeProvider} is a
     * {@code CraftWorldInfo} snapshot (javap: {@code MinecraftServer} builds it, calls this hook, and
     * only then runs {@code new ServerLevel(...)}). So the layout is bound exactly once, on the first
     * call that has a live world: the first {@code getBiome} or {@code getDefaultPopulators}.
     *
     * <h2>{@code generator.seed} no longer reaches the biome map</h2>
     * The biome layout is the vanilla climate: {@code NetherClimateSampler} reads the world's own
     * {@code RandomState}, which is seeded from the <b>world seed</b> and is not configurable from a
     * plugin. There is no honest way to make {@code generator.seed} override that -- doing so would
     * mean computing a climate field of our own, which is exactly what this port must not do.
     * So the contract is now:
     * <ul>
     *   <li>biome layout: the <b>world seed</b>, always;</li>
     *   <li>ores / trees / features: still {@link NetherConfig#seedFor(long)}, i.e. an explicit
     *       {@code generator.seed} does override those.</li>
     * </ul>
     * {@code SeedContractTest} and the config documentation must say that split; the previous
     * "explicit seed overrides the biome map too" promise is gone, deliberately.
     */
    private volatile BiomePlacement placement;
    /**
     * 交界处材质窄带混合，与 {@link #placement} 同时绑定、同一个世界种子。
     *
     * <p>只作用在 {@link #generateSurface} 的材质选择上：它包住 {@code keyAt}，在边界窄带内把一部分
     * 列的<b>暴露地板表层</b>换成邻列群系的那些，交换概率随到边界的距离衰减，深层与天花板仍是本列
     * 群系的材质（见 {@link BiomeEdges} 的类注释：选择规则本身不动，所以群系分布、可达性与 biome
     * provider 报出的名字都不变）。
     */
    private volatile BiomeEdges edges;
    /**
     * The compiled upstream surface rules for the bound world.
     *
     * <p>Bound together with {@link #placement}, from the same resolved seed, so the rules a column is
     * painted with can never disagree with the biome it is painted for.
     *
     * <p>Null only before the first {@link WorldInfo} arrives. Nothing reads it before
     * {@link #bindTo} has run, and {@code bindTo} assigns it <em>before</em> {@code placement}, so a
     * thread that observes the rebound placement is guaranteed to observe the rules too.
     */
    private volatile SurfaceRules.Book rules;
    /**
     * Block data per palette slot, resolved once from {@link #rules}.
     *
     * <p>An array, not a map: the surface pass asks for one block per column block, and the palette is
     * shared by every biome, so the hot path is an array read. Entries whose id the pack does not ship
     * hold netherrack.
     */
    private volatile BlockData[] palette;
    /**
     * The vanilla half of the rules for this generator's world, bound on first use.
     *
     * <p>Not part of {@link #rules}: the compiled trees are world-independent, while the noise they
     * read is the world's. See {@link RandomStateSurface} and {@link #vanilla(WorldInfo)}.
     */
    private volatile SurfaceRules.Vanilla vanilla;

    private final BlockData netherrack;
    /** The plate {@link #generateBedrock} stamps; resolved once, like every other block here. */
    private final BlockData bedrock;
    private final Logger logger;
    /**
     * The UUID of the world {@link #placement} / {@link #rules} / {@link #palette} are bound to,
     * or {@code null} before the first successful bind.
     *
     * <p>A {@code boolean} flag was not enough: it could not tell "already bound to this world" from
     * "bound to a different world", so a generator instance reused across worlds (a {@code /reload},
     * or two worlds sharing one generator spec) would silently keep the first world's layout. Storing
     * the UUID makes that case loud instead. The old {@code -1L} seed sentinel went away with it —
     * the layout no longer takes a seed at all (see the {@code placement} field's javadoc).
     */
    private volatile java.util.UUID boundWorld;

    /**
     * Resolves every surface block up front, on the thread that loads the world.
     *
     * @throws IllegalStateException when a biome cannot be resolved at all; the caller must then
     *                               refuse the world rather than bake vanilla Nether into it
     */
    public NetherTerrainGenerator(NetherConfig config, NetherBiomes.Resolved biomes, Logger logger) {
        this.config = config;
        this.biomes = biomes;
        this.logger = logger;
        // Bound to the world seed on first use (see placement(WorldInfo)); this provisional instance
        // is only what a caller could observe before any world asked for a column.
        // 还没有 World，就还没有原版气候采样器。放一个"一读就抛"的占位，而不是造一片假气候：
        // 任何在 bindTo 之前发生的列查询都必须是可见的缺陷，不能变成一张和上游无关的群系图。
        this.placement = new BiomePlacement((quartX, quartY, quartZ) -> {
            throw new IllegalStateException("群系布局尚未绑定世界：bindTo 之前不应有列被查询");
        });
        this.netherrack = Material.NETHERRACK.createBlockData();
        this.bedrock = Material.BEDROCK.createBlockData();

        // The surface palette is not resolved here: the rule set is per world (its noise is seeded
        // from the world seed), so it arrives with the seed in bindTo.
    }

    /**
     * Resolves every block the compiled rules can emit, once, into a slot-indexed array.
     *
     * <p>Up front rather than per block for the same two reasons as before: {@code CraftEngineBlocks}
     * is not meant to be called from worldgen workers, and a pack that has not registered a block must
     * be reported at world load instead of silently producing plain netherrack chunks.
     *
     * <p>A missing block degrades to netherrack (one biome looks plain, the world still generates) and
     * is named in one warning, so the gap is visible without being fatal.
     */
    private BlockData[] resolvePalette(SurfaceRules.Book book) {
        List<String> ids = book.palette().ids();
        BlockData[] blocks = new BlockData[ids.size()];
        List<String> missing = new ArrayList<>();
        for (int slot = 0; slot < blocks.length; slot++) {
            String id = ids.get(slot);
            BlockData data = resolveBlock(id);
            if (data == null) {
                missing.add(id);
                data = netherrack;
            }
            blocks[slot] = data;
        }
        if (!missing.isEmpty()) {
            logger.warning("以下地表方块在 CraftEngine 中未注册，相关群系将回退为下界岩："
                    + String.join("、", missing));
        }
        for (String note : book.notes()) {
            logger.info("地表规则：" + note);
        }
        return blocks;
    }

    /**
     * One palette entry as block data, or {@code null} when nothing can supply it.
     *
     * <p>Every {@code minecraft:} id is answered by {@link org.bukkit.Bukkit#createBlockData(String)},
     * which is the only source that knows vanilla's blocks, states and fluids: the vanilla nether rule
     * paints {@code minecraft:lava} into the holes under the sea, and the nylium/blackstone/basalt
     * family are plain vanilla ids with no properties at all. Everything else is a CustomEngine block,
     * which {@code CraftEngineLookup} resolves by id alone.
     *
     * <p>The one state in the 23 upstream files that carries properties
     * ({@code betternether:soul_sandstone[up=false]}) is flattened to its id when the rule is compiled,
     * so a bracketed key can only arrive for a vanilla id.
     */
    private static BlockData resolveBlock(String id) {
        if (id.startsWith("minecraft:")) {
            try {
                return org.bukkit.Bukkit.createBlockData(id);
            } catch (IllegalArgumentException error) {
                return null;
            }
        }
        return CraftEngineLookup.lookup(id);
    }

    /**
     * Binds the biome layout to a world seed, once.
     *
     * <p>Called from the two entry points that receive {@link WorldInfo} (the surface pass and the
     * biome provider). Both must see the same layout, and both may arrive on different threads, so the
     * first caller wins and later calls are no-ops. A caller that somehow resolved a different seed
     * for the same generator is reported rather than silently ignored.
     */
    private BiomePlacement placement(WorldInfo world) {
        return bindTo(world);
    }

    /**
     * The one place the layout is created. Idempotent: the first caller fixes the seed, every later
     * caller (surface pass, biome provider, populators) receives the same instance.
     */

    /**
     * {@link WorldInfo} → {@link World}，只为了让气候采样器拿到 {@code ServerLevel}。
     *
     * <p>Bukkit 的 {@code World extends WorldInfo}，所以真实的调用永远是 {@code World}；这里仍显式
     * 检查一次，因为 {@code WorldInfo} 是一个接口，测试替身可能不是世界 —— 那时必须响，而不是回退到
     * 一片自造的气候场。
     */
    private static World asWorld(WorldInfo world) {
        if (world instanceof World real) {
            return real;
        }
        // javap 已核实：getDefaultBiomeProvider 收到的是 CraftWorldInfo —— 一个只实现 WorldInfo
        // 的快照，永远不是 World。它被构造于 new ServerLevel(...) 之前，所以那个时刻连
        // Bukkit.getWorld(name) 都是 null。绑定时（首个 getBiome / getDefaultPopulators）世界已经
        // 注册，按名字取回来即可。
        World registered = Bukkit.getWorld(world.getName());
        if (registered != null) {
            return registered;
        }
        throw new IllegalStateException("群系选择需要世界「" + world.getName() + "」已注册，"
                + "以便读取原版 RandomState；当前拿到的只有 " + world.getClass().getName()
                + "，说明绑定发生在世界注册完成之前。");
    }

    /**
     * 非下界世界直接拒绝。
     *
     * <p>本工程画的是 23 个 BetterNether 下界群系 + 5 个原版下界群系，地表规则、矿物与植被全部
     * 按 y=0..127 的下界布局写。把这些材料铺到主世界或末地不是"效果差"，是一张错的图，而且会被
     * 永久写进区域文件。所以这里在绑定时就抛，而不是生成几千个区块之后再发现。
     *
     * <p>放在 {@link #bindTo} 里而不是 {@code getDefaultWorldGenerator} 里，是因为构造 generator
     * 时还拿不到世界类型（Bukkit 只给名字和 id）；绑定是第一处能读到 {@link Environment} 的地方。
     */
    private static void requireNether(WorldInfo world) {
        Environment environment = world.getEnvironment();
        if (environment != Environment.NETHER) {
            throw new IllegalStateException("BetterNether 生成器只能用于 NETHER 世界，世界「"
                    + world.getName() + "」的环境是 " + environment
                    + "。请用 generator=BetterNether:betternether 创建 NETHER 世界。");
        }
    }

    private BiomePlacement bindTo(WorldInfo world) {
        java.util.UUID uuid = world.getUID();
        if (!uuid.equals(boundWorld)) {
            synchronized (this) {
                if (!uuid.equals(boundWorld)) {
                    if (boundWorld != null) {
                        throw new IllegalStateException("本生成器实例已经绑定到世界 "
                                + boundWorld + "，现在被要求绑定到 " + uuid
                                + "；一个实例跨世界复用会让第二个世界沿用第一张群系图。");
                    }
                    World live = asWorld(world);
                    // generator.seed 不再影响群系表（它仍然是矿物/树木/地表噪声的种子）。这条必须
                    // 说出来：默默失效的配置项比报错更难查。
                    if (config.seed() != 0L) {
                        logger.warning("generator.seed = " + config.seed()
                                + " 只作用于矿物、树木/特征与地表规则噪声；群系分布由世界"
                                + "「" + live.getName() + "」自己的种子经原版气候决定，"
                                + "该配置项不会改变群系图。要让群系不同请换世界种子。");
                    }
                    // Rules first, placement second: see the fields' own comment. Both are
                    // irreversible for the life of the generator, so a world can never be generated
                    // half from one seed's materials and half from another's. Compiling 23 rule trees
                    // is a one-off cost on the first chunk, not per-chunk work.
                    //
                    // The rules take no seed: upstream's trees carry none. wover builds its noise from
                    // the JSON's own `seed` field, so the patch pattern is identical in every world;
                    // what varies with the world is the vanilla noise and the positional randoms, and
                    // those come from the world's RandomState at evaluation time (see
                    // RandomStateSurface).
                    SurfaceRules.Book compiled = SurfaceRules.Book.loadBundled(
                            getClass().getClassLoader());
                    palette = resolvePalette(compiled);
                    rules = compiled;
                    // 气候只能来自原版：NetherClimateSampler 走 RandomState#createClimateSampler，
                    // 拿到的就是玩家真正看到的下界群系所用的那一个采样器。注意这里**没有**用
                    // config.seedFor(...)：群系表由世界种子经原版气候决定，generator.seed 无法也
                    // 不应介入（见 placement 字段的注释）。
                    // 裁决器用原版的：NmsBiomeSelector 把 28 个参数点塞进
                    // Climate.ParameterList<String> 再调 findValue，与运行时同一棵树、同一套并列
                    // 规则。BiomePlacement 自己那份线性实现只作为无 NMS 依赖的等价参考（401×401
                    // 网格实测 0 处不同）。
                    placement = new BiomePlacement(NetherClimateSampler.forWorld(live, logger),
                            NmsBiomeSelector.shared());
                    // 交界材质混合的相干噪声只吃世界种子：同一个世界每块边界的咬合花纹固定，
                    // 换世界换花纹。它是 placement 外面的一层，改不了上面那行选出来的群系。
                    edges = new BiomeEdges(placement, live.getSeed());
                    boundWorld = uuid;
                }
            }
        }
        return placement;
    }

    /**
     * The compiled surface rules for this world, binding the seed on the way if this is the first
     * call.
     *
     * <p>The binding is what creates the rules, so the value returned here is never null: this is the
     * only reader, and it always binds first.
     */
    private SurfaceRules.Book rules(WorldInfo world) {
        bindTo(world);
        return rules;
    }

    /**
     * The edge-blend layer for this world, bound on the way if this is the first call.
     *
     * <p>Not null after {@link #bindTo}: the binding creates it. Read by the surface pass only -- the
     * biome provider deliberately keeps the unblended layout (see {@link BiomeEdges}).
     */
    private BiomeEdges edges(WorldInfo world) {
        bindTo(world);
        return edges;
    }

    /**
     * The vanilla half of the rule tree for this world: its {@code RandomState} noise and randoms.
     *
     * <p>Bound on first use and then fixed, because a generator belongs to exactly one world. Nothing
     * here is a per-block cost: the two {@code NormalNoise} fields and the positional factories are
     * resolved once, and only {@code get}/{@code at} run per read.
     *
     * @throws IllegalStateException when the world is not a server world this API can reach, rather
     *                               than substituting a noise of this port's own -- see
     *                               {@link SurfaceRules.Vanilla#ABSENT}
     */
    private SurfaceRules.Vanilla vanilla(WorldInfo world, SurfaceRules.Book book) {
        SurfaceRules.Vanilla bound = vanilla;
        if (bound == null) {
            synchronized (this) {
                bound = vanilla;
                if (bound == null) {
                    bound = RandomStateSurface.of(asWorld(world), book);
                    vanilla = bound;
                }
            }
        }
        return bound;
    }

    /**
     * How many wrappers {@link #peel} walks before giving up.
     *
     * <p>One hop covers Bukkit's {@code CustomChunkGenerator}; the rest is headroom for another plugin
     * wrapping this one's world, and the cap is also what stops a delegate that answers with itself
     * from looping forever.
     */
    static final int MAX_WRAPPER_HOPS = 4;

    /**
     * Walks a generator wrapper chain looking for the node that carries what the caller wants.
     *
     * <p>Deliberately free of server types -- {@code behind} is "the delegate of this wrapper, or
     * {@code null} if this code cannot see through it", {@code carrier} is "this node has the value" --
     * so the policy is assertable without a server: production passes {@code instanceof} tests over
     * {@code net.minecraft} generators, the tests pass fake nodes. Returning {@code null} rather than
     * throwing on an opaque wrapper is the point: it is what lets {@code settingsOf} switch to the
     * level stem instead of failing on CraftEngine's wrapper.
     *
     * <p>Out here rather than inside {@link RandomStateSurface} because the test runtime deliberately
     * has no {@code net.minecraft.*} on its classpath, and initialising the nested class would need
     * them; this method resolves no server type.
     *
     * @return the value of the first node {@code carrier} accepts, or {@code null} when the chain ends
     *         at a wrapper that exposes no delegate
     */
    static <N, V> V peel(N start, UnaryOperator<N> behind, Function<N, Optional<V>> carrier) {
        N current = start;
        for (int hop = 0; hop <= MAX_WRAPPER_HOPS; hop++) {
            if (current == null) {
                return null;
            }
            Optional<V> found = carrier.apply(current);
            if (found.isPresent()) {
                return found.get();
            }
            current = behind.apply(current);
        }
        return null;
    }

    /**
     * The production {@link SurfaceRules.Vanilla}: vanilla's own surface noise, read off the world's
     * {@code RandomState}.
     *
     * <h2>Which four calls, and why they are the real thing</h2>
     * 26.3 computes a column's surface data in {@code MaterialSystem}, whose two accessors are
     * {@code protected}, so they cannot be called from here; the disassembly of
     * {@code net.minecraft.world.level.levelgen.material.MaterialSystem} gives what they do, and this
     * class repeats it with the same fields:
     * <pre>
     *   surfaceNoise          = randomState.getOrCreateNoise(Noises.SURFACE)
     *   surfaceSecondaryNoise = randomState.getOrCreateNoise(Noises.SURFACE_SECONDARY)
     *   noiseRandom           = the world's root positional factory, i.e.
     *                           WorldgenRandom.Algorithm.newInstance(randomState.seed()).forkPositional()
     *
     *   getSurfaceDepth(x, z)     = (int) (surfaceNoise.get(x, 0.0, z) * 2.75 + 3.0
     *                                    + noiseRandom.at(x, 0, z).nextDouble() * 0.25)
     *   getSurfaceSecondary(x, z) = surfaceSecondaryNoise.get(x, 0.0, z)
     * </pre>
     * Two notes on why this is not a guess:
     * <ul>
     *   <li>{@code MaterialSystem}'s constructor takes that positional factory as its fifth argument and
     *       {@code RandomState} passes its own {@code random} field, which it built with
     *       {@code Algorithm.newInstance(seed).forkPositional()}. Which algorithm that is comes from
     *       {@code NoiseGeneratorSettings.useLegacyRandomSource()} -- {@code RandomState.create(HolderGetter,
     *       long, NoiseGeneratorSettings)} hands exactly that method's result to the constructor as its
     *       boolean -- so this class reads it from the world's own settings rather than assuming one.
     *       The field holding the factory is private, which is why the factory is rebuilt here from the
     *       same two inputs (algorithm, seed); the algorithm itself is not guessed.</li>
     *   <li>The two noise keys are the ones {@code MaterialSystem} itself looks up, so a datapack that
     *       replaces {@code minecraft:surface} changes this port the same way it changes vanilla.</li>
     * </ul>
     *
     * <p>The other three methods are plain {@code RandomState} calls: a noise by its registry id (what
     * {@code wover:rough_noise_condition} and {@code minecraft:noise_threshold} name), a positional
     * factory by name (what {@code vertical_gradient}'s {@code random_name} names), and the wover
     * roughness stream.
     */
    static final class RandomStateSurface implements SurfaceRules.Vanilla {
        /** {@code NoiseParameterManager.ROUGHNESS_NOISE}: {@code LibWoverMath.C.id("roughness_noise")}. */
        private static final Identifier ROUGHNESS = Identifier.parse("wover:roughness_noise");

        private final RandomState randomState;
        private final Noise surfaceNoise;
        private final Noise surfaceSecondaryNoise;
        private final PositionalRandomFactory noiseRandom;
        /** Every noise the compiled rules name, resolved on the loading thread; see the constructor. */
        private final java.util.Map<String, Noise> noises = new java.util.HashMap<>();

        /**
         * Binds the two surface noises, the root positional factory, and every noise id the book names.
         *
         * <h2>The random algorithm is read, not assumed</h2>
         * {@code RandomState}'s root positional factory is built from
         * {@code WorldgenRandom.Algorithm.newInstance(seed).forkPositional()}, and the algorithm is
         * chosen by {@code NoiseGeneratorSettings.useLegacyRandomSource()} -- verified in
         * {@code RandomState.create(HolderGetter, long, NoiseGeneratorSettings)}'s bytecode, which passes
         * exactly that method's result as the {@code boolean}. The field that holds the chosen factory
         * is private, so the factory is rebuilt here from the same two inputs, and the boolean is taken
         * from the world's own settings instead of being assumed: {@code ServerChunkCache.getGenerator()}
         * exposes the NMS generator, {@code CustomChunkGenerator.getDelegate()} the noise generator behind
         * it, and {@code NoiseBasedChunkGenerator.generatorSettings()} the settings. A wrapper this class
         * cannot see through (CraftEngine's) falls back to the dimension's own {@code LevelStem}; see
         * {@link #settingsOf}.
         *
         * <p>The other public path to that seed is {@code RandomState.seed()}, which is deprecated in
         * 26.3; the warning is suppressed here rather than left to hide a real one elsewhere.
         *
         * <h2>Why every noise is resolved here</h2>
         * {@code RandomState.getOrCreateNoise} caches through {@code ConcurrentHashMap.computeIfAbsent}
         * -- read out of the 26.3 constructor bytecode, which builds {@code noiseInstances} as a
         * {@code ConcurrentHashMap} -- so a lazy fill from a worldgen worker would be safe but would pay
         * for {@code NormalNoise}'s octave tables on whichever chunk first asked. Resolving the book's
         * whole list on the world-loading thread makes the hot path one map hit.
         */
        @SuppressWarnings("deprecation")
        private RandomStateSurface(RandomState randomState, SurfaceRules.Book book,
                                  NoiseGeneratorSettings settings) {
            this.randomState = randomState;
            this.surfaceNoise = randomState.getOrCreateNoise(Noises.SURFACE);
            this.surfaceSecondaryNoise = randomState.getOrCreateNoise(Noises.SURFACE_SECONDARY);
            WorldgenRandom.Algorithm algorithm = settings.useLegacyRandomSource()
                    ? WorldgenRandom.Algorithm.LEGACY
                    : WorldgenRandom.Algorithm.XOROSHIRO;
            this.noiseRandom = algorithm.newInstance(randomState.seed()).forkPositional();
            for (String noiseId : book.noiseIds()) {
                noises.put(noiseId, randomState.getOrCreateNoise(key(noiseId)));
            }
            for (String name : book.gradientNames()) {
                randomState.getOrCreateRandomFactory(Identifier.parse(name));
            }
        }

        /**
         * Binds to a world's {@code RandomState} and the settings that seed its random algorithm.
         *
         * @throws IllegalStateException when the world is not a server world, or when its noise
         *                               settings cannot be reached -- never by falling back to an
         *                               assumed algorithm
         */
        static SurfaceRules.Vanilla of(World world, SurfaceRules.Book book) {
            if (!(world instanceof CraftWorld craftWorld)) {
                throw new IllegalStateException("地表规则需要服务端世界，实际是 " + world.getClass().getName());
            }
            ServerLevel level = craftWorld.getHandle();
            return new RandomStateSurface(level.getChunkSource().randomState(), book, settingsOf(level));
        }

        /**
         * The noise settings this world generates with.
         *
         * <h2>Two sources, in that order, and no third</h2>
         * <ol>
         *   <li>The live generator. {@code CustomChunkGenerator} is what wraps a Bukkit generator, and
         *       {@code getDelegate()} is the vanilla noise generator it was installed over -- for a
         *       Nether world, the one holding {@code minecraft:nether}'s settings. Both hops are public
         *       API of the server jar this module compiles against.</li>
         *   <li>The world's own dimension definition, i.e. the {@code LevelStem} the server registered
         *       for {@code level.getTypeKey()}. Only reached when the live chain ends in a wrapper this
         *       code cannot see through -- CraftEngine's {@code InjectedCustomChunkGenerator} is one:
         *       it holds the world's generator in a {@code private final target} with no accessor at all
         *       (checked with javap on {@code craft-engine-paper-plugin-26.10-SNAPSHOT}'s
         *       {@code nms.v26_3} implementation, and {@code InjectedChunkGenerator} is an empty marker
         *       interface). What it hides is exactly {@code stem.generator()}: a Bukkit wrapper is
         *       installed <em>over</em> the level stem's generator, never in place of it. Read out of
         *       {@code ServerLevel}'s constructor bytecode in {@code mucore-26.3.jar}: it takes
         *       {@code LevelStem.generator()} as its generator, and only then -- {@code ifnull} on the
         *       Bukkit generator argument -- replaces it with
         *       {@code new CustomChunkGenerator(this, levelStemGenerator, bukkitGenerator)}. So this
         *       source answers the very object the wrapper chain is holding, for any wrapper, without
         *       reading anyone's private field.</li>
         * </ol>
         * Neither source answering is reported, never papered over: an assumed random algorithm would
         * silently desync the surface noise from vanilla's, which is unrecoverable once chunks are
         * written.
         */
        private static NoiseGeneratorSettings settingsOf(ServerLevel level) {
            NoiseGeneratorSettings settings =
                    settingsBehind(level.getChunkSource().getGenerator());
            LevelStem stem = null;
            if (settings == null) {
                // Registry access the same way every other NMS reader in this project does it.
                stem = level.getServer().registryAccess()
                        .lookupOrThrow(Registries.LEVEL_STEM)
                        .getValue(level.getTypeKey());
                if (stem != null) {
                    settings = settingsBehind(stem.generator());
                }
            }
            if (settings == null) {
                throw new IllegalStateException("取不到下界的 NoiseGeneratorSettings（维度 "
                        + level.getTypeKey().identifier() + " 的世界生成器是 "
                        + level.getChunkSource().getGenerator().getClass().getName()
                        + "，该维度的 LevelStem 生成器是 "
                        + (stem == null ? "缺失" : stem.generator().getClass().getName())
                        + "）：地表随机算法无法确定，不做假定");
            }
            return settings;
        }

        /** The settings behind a generator wrapper chain, or {@code null} when the chain is opaque. */
        private static NoiseGeneratorSettings settingsBehind(
                net.minecraft.world.level.chunk.ChunkGenerator generator) {
            // Fully qualified above: this class extends Bukkit's ChunkGenerator, so the simple name is taken.
            return peel(generator,
                    current -> current instanceof CustomChunkGenerator custom
                            ? custom.getDelegate() : null,
                    current -> current instanceof NoiseBasedChunkGenerator noise
                            ? Optional.of(noise.generatorSettings().value()) : Optional.empty());
        }

        @Override
        public double noise2d(String noiseId, int x, int z) {
            return noise(noiseId).get(x, 0.0, z);
        }

        @Override
        public double noise3d(String noiseId, int x, int y, int z) {
            return noise(noiseId).get(x, y, z);
        }

        /** The world's own parameters for that noise id; resolved up front by the constructor. */
        private Noise noise(String noiseId) {
            Noise noise = noises.get(noiseId);
            if (noise == null) {
                throw new IllegalStateException("地表规则引用了未预热的噪声 " + noiseId
                        + "（Book.noiseIds() 与已编译的规则不一致）");
            }
            return noise;
        }

        private static ResourceKey<NormalNoise> key(String noiseId) {
            return ResourceKey.create(Registries.NOISE, Identifier.parse(noiseId));
        }

        @Override
        public int surfaceDepth(int x, int z) {
            double value = surfaceNoise.get(x, 0.0, z);
            return (int) (value * 2.75 + 3.0 + noiseRandom.at(x, 0, z).nextDouble() * 0.25);
        }

        @Override
        public double surfaceSecondary(int x, int z) {
            return surfaceSecondaryNoise.get(x, 0.0, z);
        }

        @Override
        public float gradientNextFloat(String randomName, int x, int y, int z) {
            return randomState.getOrCreateRandomFactory(Identifier.parse(randomName))
                    .at(x, y, z)
                    .nextFloat();
        }

        @Override
        public SurfaceRules.Cursor roughnessCursor() {
            // RoughNoiseConditionImpl.compile: one source per chunk and per condition, seeded from the
            // identifier alone, so every chunk starts the same sequence.
            RandomSource source = randomState.getOrCreateRandomFactory(ROUGHNESS)
                    .fromHashOf(ROUGHNESS);
            return source::nextFloat;
        }
    }

    @Override
    public void generateSurface(@NotNull WorldInfo world, @NotNull Random random,
                                int chunkX, int chunkZ, @NotNull ChunkData chunk) {
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;
        // Bind once, from this world's own seed, so distinct worlds get distinct layouts. The rules
        // themselves carry no seed (see bindTo); the vanilla noise and positional randoms they read do,
        // and those arrive through `vanilla`, bound to this world's RandomState.
        // 交界处的材质混合层与布局一起绑定：它只换材质、不换群系（见 BiomeEdges）。
        BiomeEdges blend = edges(world);
        SurfaceRules.Book book = rules(world);
        SurfaceRules.Vanilla vanilla = vanilla(world, book);
        // Read the volatile once per chunk, not per block.
        BlockData[] blocks = palette;

        // Two column-sized scratch arrays for the whole chunk, reused rather than reallocated 256
        // times: the solidity snapshot, and the plan built from it. Nothing is written to the chunk
        // until the plan is complete, so no decision can be made from this pass's own output -- see
        // snapshot() for why that matters on Paper.
        byte[] state = new byte[COLUMN_HEIGHT];
        int[] plan = new int[COLUMN_HEIGHT];
        // Bounds-checked because the walk starts one above the topmost block: vanilla's own loop reads
        // y = height + 1, which can be CEILING_Y + 1, and treating that as air is what it does.
        IntPredicate filledAt = y -> y >= 0 && y < COLUMN_HEIGHT && (state[y] & FLAG_FILLED) != 0;
        IntPredicate solidAt = y -> y >= 0 && y < COLUMN_HEIGHT && (state[y] & FLAG_SOLID) != 0;
        IntPredicate bedrockAt = y -> y >= 0 && y < COLUMN_HEIGHT && (state[y] & FLAG_BEDROCK) != 0;
        // One context for the whole chunk: it is mutable by design, and this thread owns it. Its
        // roughness streams are per chunk, exactly as wover's are (RoughNoiseConditionImpl.compile).
        SurfaceRules.Context context = new SurfaceRules.Context();
        context.beginChunk(vanilla, book.cursorSlots());

        forEachColumn((dx, dz) -> {
            int worldX = baseX + dx;
            int worldZ = baseZ + dz;
            snapshot(chunk, dx, dz, state);

            // Below the vertical band the column key answers for every y, so it is read once; the
            // band itself may hand back the hanging biome and is looked up per layer.
            // 边界窄带内的列由 blend 换成邻列群系的材质，但只限暴露地板表层：键函数收的是原版自己的
            // 石面深度（planColumn 每格传进去），所以深层与天花板仍是本列群系的材质。带外的列与
            // field.keyAt 逐位相同。
            BiomeEdges.ColumnKey keyAt = blend.column(worldX, worldZ);

            // Vanilla evaluates both of these once per column (MaterialRuleContext caches them
            // against its XZ counter), so the column pays for them once here too.
            int surfaceDepth = vanilla.surfaceDepth(worldX, worldZ);
            double surfaceSecondary = vanilla.surfaceSecondary(worldX, worldZ);

            // How far from an air gap a block can still be decided by a rule: the deepest
            // stone_depth reach in the book (vanilla's `1 + offset + secondary`), plus the column's
            // own surfaceDepth where a rule adds it, plus one so that a block *at* the deepest
            // reachable depth is still evaluated. Past that, every stone_depth test is false and
            // the only rule left is the file's trailing fallback -- see planColumn.
            int depthBudget = book.maxStoneDepth()
                    + (book.usesSurfaceDepth() ? Math.max(0, surfaceDepth) : 0) + 1;

            Arrays.fill(plan, PAINT_MIN_Y, PAINT_MAX_Y + 1, SurfaceRules.Rule.UNDECIDED);
            planColumn(book, context, solidAt, filledAt, bedrockAt, keyAt, worldX, worldZ,
                    depthBudget, surfaceDepth, surfaceSecondary, plan);

            for (int y = PAINT_MIN_Y; y <= PAINT_MAX_Y; y++) {
                int slot = plan[y];
                if (slot >= 0) {
                    chunk.setBlock(dx, y, dz, blocks[slot]);
                }
            }
        });
    }

    /**
     * The pass's traversal over a chunk: {@code dx} outer, {@code dz} inner -- vanilla's own nesting in
     * {@code MaterialSystem.buildSurface} ({@code iinc 23} is the x counter and {@code iinc 24} the z
     * one, with 24 nested inside 23).
     *
     * <p>Extracted rather than inlined because the per-chunk random streams make the <em>order</em> part
     * of the result: a {@code wover:rough_noise_condition} consumes one draw per evaluation, so which
     * block gets which draw depends on this nesting. A test that replayed its own order would prove
     * nothing, so both the painter and the test call this.
     */
    static void forEachColumn(java.util.function.BiConsumer<Integer, Integer> body) {
        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                body.accept(dx, dz);
            }
        }
    }

    /**
     * Reads a column's base terrain once, into {@code state}, before anything is decided.
     *
     * <p><b>Why a snapshot and not a live predicate.</b> Paper hands the surface phase a
     * {@code ChunkData} that <em>is</em> the chunk being built: {@code CraftChunkData} keeps the
     * {@code ChunkAccess} itself (a weak reference to it, no staging buffer), and its {@code getType}
     * reads that chunk's block states directly. So {@code setBlock} followed by {@code getType}
     * observes this pass's own write, and a face test can be decided by a block the pass just placed
     * rather than by the terrain it was supposed to describe. Reading the column into a snapshot
     * first makes every later decision a function of the base terrain alone, and bounds the cost at
     * one {@code getType} per block -- {@link #SNAPSHOT_READS_PER_CHUNK}.
     *
     * <p>Three facts are kept per y, because between them they answer everything downstream asks:
     * whether the block is non-air (vanilla's {@code stoneDepthAbove} counts non-air blocks, fluids
     * included, so the lava sea counts as cover), whether it is stone
     * ({@code !isAir && fluidState.isEmpty()}, which is what vanilla's {@code stoneDepthBelow} counts
     * down through), and whether it is bedrock (which the painter must never replace).
     */
    private static void snapshot(ChunkData chunk, int x, int z, byte[] state) {
        for (int y = 0; y < COLUMN_HEIGHT; y++) {
            Material type = chunk.getType(x, y, z);
            byte flags = 0;
            if (type != null && !type.isAir()) {
                flags = FLAG_FILLED;
                if (type == Material.BEDROCK) {
                    flags |= FLAG_SOLID | FLAG_BEDROCK;
                } else if (type.isSolid()) {
                    flags |= FLAG_SOLID;
                }
            }
            state[y] = flags;
        }
    }

    /**
     * Decides every block of one column from its snapshot, before any of them is written.
     *
     * <p>Pure: it reads only the snapshot, the biome lookup and the compiled rules it is handed, so
     * the whole surface rule is assertable without a server or a {@code ChunkData}.
     *
     * <h2>What is decided, and why it is now one rule per block</h2>
     * The port used to paint from a table: one material at each exposed top face, one at the roof's
     * underside, and a sub-layer of a fixed depth under each floor. Upstream's own rules are a tree
     * evaluated per block, and three things the table could not express fall out of evaluating it
     * that way:
     *
     * <ul>
     *   <li><b>Sub-layers at a stone depth</b> -- {@code stone_depth} conditions count from the
     *       exposed face, so WartForest's soul sandstone lands at depths 4..5 and GravelDesert's
     *       gravel reaches 4, instead of a single {@code underDepth} guessed per biome.</li>
     *   <li><b>Overhangs</b> -- {@code stone_depth(..., CEILING)} is a purely geometric test, so it
     *       matches the underside of every ledge and cave roof, not only the world's roof. The table
     *       could only paint the roof mass, which left hanging biomes with plain stone under them.</li>
     *   <li><b>Patchiness and exact shares</b> -- the noise conditions decide per position, so a
     *       material appears in regions and in the share its threshold states.</li>
     * </ul>
     *
     * <p>One consequence worth stating: a block is written once, by whichever rule decides first at
     * its own position. There is no "floor wins over ceiling" tie-break any more, because a single
     * shared y is a floor for one rule and a ceiling for another only in the table's model -- here the
     * rules themselves are ordered in the JSON, and that ordering is upstream's.
     *
     * <h2>The two things the painter still refuses</h2>
     * <ol>
     *   <li><b>Bedrock.</b> y=0..4 and y=123..127 are the plates ({@link #BEDROCK_FLOOR_MAX},
     *       {@link #BEDROCK_ROOF_MIN}) and are skipped, as is any bedrock block: the plates are
     *       {@link #generateBedrock}'s work, and upstream's own bedrock rules are dropped when the
     *       files are compiled.</li>
     *   <li><b>Deep rock is written only where a rule actually names something else.</b> Past
     *       {@code depthBudget} from every air gap every {@code stone_depth} test is false, so only the
     *       rules upstream leaves <em>ungated</em> can still decide. In most of the 23 files that is
     *       the trailing {@code minecraft:netherrack} fallback, which is the block the noise stage
     *       already put there — writing it would change nothing, so that write is skipped.
     *       <p>It is <b>not</b> true for every file, and this painter used to assume it was.
     *       {@code nether_swampland}, {@code *_swampland}, {@code soul_plain} and {@code wart_forest*}
     *       carry an ungated {@code sequence} that paints {@code soul_sand}/{@code soul_soil} pockets
     *       wherever its {@code threshold_condition} matches, and {@code flooded_deltas} has an
     *       ungated {@code switch_rule} that paints {@code deepslate}: upstream really does put those
     *       blocks deep inside the rock (invisible until a carver opens it). So the skip is now
     *       conditioned on the decided block being the base terrain
     *       ({@link #BASE_TERRAIN_ID}) instead of assuming every deep decision is.
     *       <p>The whole column <em>is</em> still evaluated, deliberately: the per-chunk roughness
     *       streams make the set of evaluations part of the result (see the traversal note above), so
     *       pruning the walk would move every later draw onto a different block. The cost that buys is
     *       one rule walk per stone block, which is what vanilla pays too — and the only {@code
     *       setBlock} calls this filter removes are the ones that would write the block already
     *       there.</li>
     * </ol>
     *
     * <h2>The key function is asked per block, with the depths the rules see</h2>
     * {@code keyAt} is called at every evaluated block with that block's own {@code stoneDepthAbove /
     * Below}. That is what lets the edge-blend layer ({@code BiomeEdges}) keep its replacement to the
     * exposed floor skin: it can see the depth, so the deep rock and every ceiling underside keep the
     * column's own biome. This painter stays neutral -- it neither blends nor knows about blending; it
     * only stops hiding the depths from the key function.
     *
     * @param keyAt            the biome key for one block; {@code depthAbove/depthBelow} are the same
     *                         1-based {@code stone_depth} numbers the rules are handed
     * @param depthBudget      how deep from an air gap a rule can still reach; see {@code maxStoneDepth}
     * @param surfaceDepth     vanilla's {@code MaterialSystem.getSurfaceDepth(x, z)} for this column
     * @param surfaceSecondary vanilla's {@code MaterialSystem.getSurfaceSecondary(x, z)} for this column
     * @param plan             filled with a palette slot per y, {@link SurfaceRules.Rule#UNDECIDED} for
     *                         "paint nothing"
     */
    static void planColumn(SurfaceRules.Book book, SurfaceRules.Context context,
                           IntPredicate solidAt, IntPredicate filledAt, IntPredicate bedrockAt,
                           BiomeEdges.ColumnKey keyAt, int x, int z, int depthBudget,
                           int surfaceDepth, double surfaceSecondary, int[] plan) {
        // Vanilla's own traversal, exactly. MaterialSystem.buildSurface walks
        //   for (y = chunk.getHeight(WORLD_SURFACE_WG, x, z) + 1; y >= minY; y--)
        // and its first test is on the block: air jumps straight to the loop's decrement, and a block
        // whose fluid state is non-empty does the same after noting the fluid surface. Neither reaches
        // `updateY` or `ruleEvaluator.tryApply`, so the domain is exactly "non-air and non-fluid" --
        // stone, as `isStone` defines it -- from the top of the column down to the bottom.
        //
        // That domain is part of the result, not an optimisation: wover's rough_noise_condition draws
        // from a per-chunk stream, so which block gets which draw is a function of the order and the
        // *set* of these evaluations. Walking the same blocks in the same direction is what makes that
        // assignment upstream's.
        int startY = topSolid(solidAt);
        for (int y = startY; y >= 0; y--) {
            if (!solidAt.test(y)) {
                continue;
            }
            boolean bedrock = bedrockAt.test(y);
            int above = depthAbove(filledAt, y, depthBudget);
            int below = depthBelow(solidAt, y, depthBudget);
            // 键函数拿到的就是规则树读到的这两个深度：交界掺混靠它们把替换限制在暴露地板表层
            // （见 BiomeEdges），深层与天花板因此保持本列群系的语义。
            String key = keyAt.keyAt(y, above, below);
            // The 23 upstream files cover the mod's own biomes; every other column -- the five vanilla
            // nether biomes this port's biome source can produce -- is vanilla's own rule. Falling
            // through to "paint nothing" is what left soul_sand_valley and basalt_deltas as bare
            // netherrack, so the fallback is explicit rather than an absent entry.
            SurfaceRules.Set set = key == null ? null : book.of(key);
            if (set == null) {
                set = book.vanilla();
            }
            if (set == null) {
                continue;
            }
            context.set(x, y, z, above, below, surfaceDepth, surfaceSecondary);
            context.setBiome(key);
            int slot = set.decide(context);
            if (slot < 0) {
                continue;
            }
            // A rule may still name bedrock (the plates are recognised structurally and dropped, but a
            // future file could state one elsewhere); bedrock is never painted over.
            String id = set.id(slot);
            if (id == null || SurfaceRules.BEDROCK_ID.equals(id)) {
                continue;
            }
            // The write is still filtered, and each filter is a no-op for the picture rather than a
            // shortcut:
            //   - never into bedrock (a rule may decide there; replacing an unbreakable block is not
            //     what any of these rules means);
            //   - never inside the plates, which generateBedrock stamps;
            //   - past the depth budget, every stone_depth test is false and only the files' *ungated*
            //     rules can still decide. Where that decision is the trailing netherrack fallback the
            //     block is already there and the write is skipped; where it is not -- soul_sand,
            //     soul_soil and deepslate are real upstream outcomes deep inside the rock -- the write
            //     happens, because skipping it would be a terrain change, not a shortcut.
            if (bedrock) {
                continue;
            }
            if (y < PAINT_MIN_Y || y > PAINT_MAX_Y) {
                continue;
            }
            if (above >= depthBudget && below >= depthBudget && BASE_TERRAIN_ID.equals(id)) {
                continue;
            }
            plan[y] = slot;
        }
    }

    /**
     * The highest y that holds a block vanilla evaluates -- non-air and non-fluid, i.e. stone.
     *
     * <p>Vanilla starts its column walk at {@code chunk.getHeight(WORLD_SURFACE_WG, x, z) + 1} and its
     * first test is {@code block.isAir()}, which skips to the loop's decrement; a fluid block does the
     * same. So the first evaluation is the topmost stone block, and everything above it is untouched.
     * The snapshot the painter reads is taken before any write, so this is the same number.
     *
     * @return the topmost stone y, or {@code -1} for a column with none
     */
    static int topSolid(IntPredicate solidAt) {
        for (int y = CEILING_Y; y >= 0; y--) {
            if (solidAt.test(y)) {
                return y;
            }
        }
        return -1;
    }

    /**
     * Vanilla's {@code stoneDepthAbove}, capped.
     *
     * <p>Vanilla's own count, in {@code MaterialSystem.buildSurface}: a counter that is reset to 0 on
     * air and incremented on every non-air block as the column walks down, so it is <b>1-based</b> -- 1 on
     * the exposed block, 2 on the one below it. Fluids count as cover: the lava sea gives the block
     * under it a depth, because the walk only resets on {@code isAir}.
     *
     * <p>That 1 is the whole reason {@code stone_depth(0, false, 0, FLOOR)} -- upstream's
     * {@code ON_FLOOR} -- matches the surface block: vanilla tests {@code depth <= 1 + offset}.
     *
     * <p>The cap is what bounds the cost, and the world's top is what keeps the probe inside the
     * snapshot: above y=127 there is no column to read.
     */
    static int depthAbove(IntPredicate filledAt, int y, int cap) {
        for (int depth = 1; depth <= cap; depth++) {
            int probe = y + depth - 1;
            if (probe > CEILING_Y || !filledAt.test(probe)) {
                return depth - 1;
            }
        }
        return cap;
    }

    /**
     * Vanilla's {@code stoneDepthBelow}, capped: blocks down to the first block that is not stone,
     * this one included, so 1 on an exposed underside.
     *
     * <p>"Stone" is vanilla's {@code MaterialSystem.isStone}: {@code !isAir && fluidState.isEmpty()}.
     * The walk stops at the first non-stone block and reports {@code y - stop + 1}, which is the same
     * number this loop produces.
     */
    static int depthBelow(IntPredicate solidAt, int y, int cap) {
        for (int depth = 1; depth <= cap; depth++) {
            int probe = y - depth + 1;
            if (probe < 0 || !solidAt.test(probe)) {
                return depth - 1;
            }
        }
        return cap;
    }

    /**
     * The biome keys that paint a column's two surfaces, each read at its own height.
     *
     * <p>The two surfaces are far apart vertically, and {@link BiomePlacement} puts its vertical
     * biome in the band above {@link BiomePlacement#CEILING_BAND_FLOOR} -- a key only
     * {@code keyAt(x, y, z)} can return. So the floor is read at {@code floorTop} and the ceiling at
     * {@code ceilingBottom}, instead of reading the column once at ground level and reusing that
     * answer for the roof.
     *
     * <p><b>Cost is bounded structurally, not by convention:</b> at most two field reads per column,
     * and one when both heights fall on the same side of the band, because the field's vertical layer
     * is a function of {@code (x, z)} alone -- two heights inside the band must resolve identically
     * (see {@link #sameBand}). This never runs per block; the whole 16×16 column loop is the only
     * caller.
     *
     * <p>A {@code null} key means "that surface is not painted": the surface is at or below
     * {@link #BEDROCK_FLOOR_MAX}, or the column has no such surface at all.
     *
     * <p>Pure apart from the field, so the reading rule is assertable without a server.
     *
     * @param field         the layout in use for this world
     * @param x             block x of the column
     * @param z             block z of the column
     * @param floorTop      the column's floor surface y, as found by {@code isFloorSurface}
     * @param ceilingBottom the column's roof underside y, as found by {@link #roofBottom}
     */
    static SurfaceBiomes surfaceBiomes(BiomePlacement field, int x, int z,
                                       int floorTop, int ceilingBottom) {
        String floorKey = floorTop > BEDROCK_FLOOR_MAX ? field.keyAt(x, floorTop, z) : null;
        if (ceilingBottom <= BEDROCK_FLOOR_MAX) {
            return new SurfaceBiomes(floorKey, null);
        }
        // Reuse the floor's answer only when the two heights sit on the same side of the band: then
        // the field would return exactly the same key, and the second read would buy nothing.
        String ceilingKey = floorKey != null && sameBand(floorTop, ceilingBottom)
                ? floorKey
                : field.keyAt(x, ceilingBottom, z);
        return new SurfaceBiomes(floorKey, ceilingKey);
    }

    /**
     * Whether two heights are on the same side of {@link BiomePlacement#CEILING_BAND_FLOOR}.
     *
     * <p>That is exactly the condition under which two heights resolve to the same biome in a given
     * column, because the vertical layer's own draw depends on {@code (x, z)} only. It is what lets
     * {@link #surfaceBiomes} -- and the populators, which face the same question -- spend one field
     * read where they would otherwise spend two.
     */
    static boolean sameBand(int a, int b) {
        // 竖带已删除：默认路径（原版 multi_noise）没有竖直气候轴，任意两个高度都在同一个"带"里。
        // 保留这个方法是为了让地表侧的两读（地面 / 天花板）继续走同一条路径。
        return true;
    }

    /**
     * The biome keys that paint a column's two surfaces; either is {@code null} when not painted.
     *
     * @param floor   the key for the floor surface, or {@code null}
     * @param ceiling the key for the ceiling surface, or {@code null}
     */
    record SurfaceBiomes(String floor, String ceiling) {
    }

    /**
     * Whether the block at {@code y} is the top face of a solid run -- a floor surface.
     *
     * <p>This is the port of vanilla's {@code stoneDepthCheck(0, false, FLOOR)}, which upstream
     * reuses as {@code NetherBiomeBuilder.ON_FLOOR}. The distinction matters because the Nether is
     * multi-level: one column can hold several ledges, each with its own top face, and every one of
     * them is a place the surface rules run.
     *
     * <p>{@code y + 1} has to be inside the world. Above {@link #CEILING_Y} the chunk answers
     * {@code VOID_AIR}, so without this guard the topmost block of the world -- the bedrock roof at
     * y=127 -- reads as having air above it and is classified as an exposed floor, which then hands
     * the roof a floor material.
     */
    static boolean isFloorSurface(IntPredicate solidAt, int y) {
        return y < CEILING_Y && solidAt.test(y) && !solidAt.test(y + 1);
    }

    /**
     * Whether the block at {@code y} is the underside of a solid run -- a ceiling surface.
     *
     * <p>The port of {@code stoneDepthCheck(0, false, CEILING)} ({@code NetherBiomeBuilder.ON_CEILING}).
     * Overhangs, ledge undersides and the bedrock roof all answer true, which is what makes hanging
     * flora and the upside-down biomes paint everywhere they should.
     */
    static boolean isCeilingSurface(IntPredicate solidAt, int y) {
        return solidAt.test(y) && !solidAt.test(y - 1);
    }

    /**
     * The lowest y of the roof mass -- the contiguous solid run that starts at the top of the world --
     * or {@code -1} when there is no roof to paint.
     *
     * <p>The roof has to be identified explicitly. A naive "first solid from the top" answers y=127 on
     * every column, which is the bedrock plate rather than a surface, and a ceiling rule applied there
     * would paint the top of the world. So the walk follows the <em>contiguous</em> run downwards from
     * {@link #CEILING_Y} and stops at the first gap: the y it lands on is the roof's exposed underside.
     *
     * <p>Two cases yield {@code -1}: the column's top is air (the roof was carved open), and the run
     * reaching deeper than {@link #CEILING_SCAN_MAX}. The second is the pathological column that is
     * solid from the roof straight down -- one mass with no roof underside to speak of, where the cap
     * is a cheaper way to say "not a roof skin" than walking to the plate.
     *
     * <p>Cost is now bounded by the snapshot rather than by this walk: the column was already read
     * once, so the walk itself costs no {@code getType} calls at all, and it stops after ~6 steps on a
     * vanilla column (the bedrock band, then the gap).
     */
    static int roofBottom(IntPredicate solidAt) {
        int bottom = -1;
        int limit = Math.max(PAINT_MIN_Y, CEILING_Y - CEILING_SCAN_MAX);
        for (int y = CEILING_Y; y >= limit; y--) {
            if (!solidAt.test(y)) {
                return bottom;
            }
            bottom = y;
        }
        return -1;
    }

    @Override
    public @NotNull List<BlockPopulator> getDefaultPopulators(@NotNull World world) {
        // Populators must read the same layout as the surface pass, so they get the bound instance.
        //
        // 这里可以立即绑定，不是猜的：javap 实读 MinecraftServer 的 createWorld 一段，顺序是
        // 202 new CraftWorldInfo → 279 <init> → 298 getDefaultBiomeProvider → 316 new ServerLevel
        // （其构造里 681 才 putfield chunkSource）→ 611 initWorld(ServerLevel, WorldCreator)；
        // initWorld 内部才调 CraftWorld.getPopulators() 与 generator.getDefaultPopulators(World)。
        // 也就是说本方法拿到的 ServerLevel 已经构造完成，getChunkSource().randomState() 一定可用。
        // 所以这里不需要惰性化，链条上最早的合法绑定点就是它。
        BiomePlacement field = bindTo(world);
        // 装饰的唯一数据源：一份带 (biome, step) 归属的编译清单。
        // 两个装饰 populator 共用同一个调度器实例 —— index 空间是整 step 共享的，
        // 拆成两次执行不能各自重算一遍（否则 step 9 的 featureIndex 会与上游错开）。
        // 矿的 placed feature 也并进同一张计划（只占 index，不在这里执行）：
        // 它们由 OrePopulator 自己放，但 featureIndex 必须来自同一份全局排序。
        dev.mumu.betternether.worldgen.DecorationScheduler scheduler =
                dev.mumu.betternether.worldgen.DecorationScheduler.load(field);
        OrePopulator ore = new OrePopulator(config, field);
        // 把同一份计划交给矿：它只取 featureIndex（种子），放置仍由它自己做。
        // 没有这一步，矿会退回「整个 populator 共用一条随机流」—— 与上游逐条 setFeatureSeed 不等价。
        ore.useDecorationPlan(scheduler.plan());
        // 熔岩湖在 step 4 的真实槽位：上游它是**数据包结构**
        // （generated/data/betternether/worldgen/structure/mega_lava_lake.json, surface_structures），
        // 数据包元素先注册、wover 的 bootstrap 后跑且对已存在 key 只 return holder（不换 id），
        // 所以它拿的是**按路径排序的位置**（本项目 4），它之后的条目 j 全部 +1。
        // 本移植注册不了这个结构，只能在结构相位里为它让位。
        int lakeSlot = dev.mumu.betternether.worldgen.NativeDecorationBridge.structureSlot(
                world, 4, "betternether:mega_lava_lake");
        return decorationPopulators(scheduler, new MegaLavaLakePopulator(field), ore, lakeSlot);
    }

    /**
     * 装饰 populator 的注册顺序：**唯一**能看见「谁夹在谁中间」的地方。
     *
     * <h2>为什么单独抽出来</h2>
     * 顺序本身就是语义（原版 {@code addVanillaDecorations} 是「外层 step，内层先结构后特征」），
     * 而它只体现在这个列表的次序上。抽成静态方法后可以**在没有服务端的情况下**用单测锁住它 ——
     * 否则这个问题只能靠「进游戏看世界像不像」，而顺序错了的世界看起来完全正常，
     * 只是树长在别处、坑没挖到矿。
     *
     * <h2>骨架</h2>
     * <pre>
     * ALL             0,1,2,3   结构 + 特征
     * STRUCTURES      4         该 step 的结构
     * megaLavaLake              上游身份是 step 4 的**结构**（另 owner，注册不能丢）
     * FEATURES        4         该 step 的特征
     * ALL             5,6
     * STRUCTURES      7
     * NATIVE_FEATURES 7         该 step 的原版条目（矿那批的原版前缀，index 与 BN 条目同源）
     * ore                       step 7 的 BN 矿（另 owner）
     * ALL             8,9,10
     * </pre>
     *
     * @param megaLavaLake 传入而不是在这里 new，是为了让单测能看见「它在第 3 位」
     * @param ore          同上；它必须排在 step 7 的结构与原版条目**之后**
     */
    static List<BlockPopulator> decorationPopulators(
            dev.mumu.betternether.worldgen.DecorationScheduler scheduler,
            BlockPopulator megaLavaLake, BlockPopulator ore, int lakeSlot) {
        dev.mumu.betternether.worldgen.DecorationScheduler.Phase ALL =
                dev.mumu.betternether.worldgen.DecorationScheduler.Phase.ALL;
        dev.mumu.betternether.worldgen.DecorationScheduler.Phase STRUCTURES =
                dev.mumu.betternether.worldgen.DecorationScheduler.Phase.STRUCTURES;
        dev.mumu.betternether.worldgen.DecorationScheduler.Phase FEATURES =
                dev.mumu.betternether.worldgen.DecorationScheduler.Phase.FEATURES;

        List<BlockPopulator> populators = new ArrayList<>();
        // step 0 raw_generation / 1 lakes / 2 / 3（结构与特征都跑）。
        populators.add(new dev.mumu.betternether.worldgen.UnifiedDecorationPopulator(
                scheduler, ALL, 0, 1, 2, 3));
        // step 4 的结构相位被熔岩湖的槽位切成两段（slot 未知时不分段，按整段跑）。
        // 依据：上游 mega_lava_lake 是数据包结构，占 step 4 的**排序位置**，它之后的条目 j 全部 +1；
        // 本移植注册不了它，只能让位，否则每个 step-4 结构的 j 都会与上游差 1。
        if (lakeSlot >= 0) {
            populators.add(new dev.mumu.betternether.worldgen.UnifiedDecorationPopulator(
                    scheduler, STRUCTURES,
                    new dev.mumu.betternether.worldgen.DecorationScheduler.StructureWindow(
                            0, lakeSlot, lakeSlot), 4));
            // 巨型熔岩湖的上游身份是**结构**：
            // upstream/.../registry/NetherStructures.java + generated/data/.../structure/mega_lava_lake.json
            // 它由 MegaLavaLakePopulator 手动放置（几何归另一 owner），注册不能丢。
            populators.add(megaLavaLake);
            populators.add(new dev.mumu.betternether.worldgen.UnifiedDecorationPopulator(
                    scheduler, STRUCTURES,
                    new dev.mumu.betternether.worldgen.DecorationScheduler.StructureWindow(
                            lakeSlot, Integer.MAX_VALUE, lakeSlot), 4));
        } else {
            populators.add(new dev.mumu.betternether.worldgen.UnifiedDecorationPopulator(
                    scheduler, STRUCTURES, 4));
            populators.add(megaLavaLake);
        }
        // step 4 surface_structures 的特征（尖锥、石笋、骨堆、黑曜石晶簇、贴面补丁）。
        populators.add(new dev.mumu.betternether.worldgen.UnifiedDecorationPopulator(
                scheduler, FEATURES, 4));
        // step 5 / 6 必须排在 ore(7) 之前：执行顺序就是 step 升序。
        populators.add(new dev.mumu.betternether.worldgen.UnifiedDecorationPopulator(
                scheduler, ALL, 5, 6));
        // step 7 underground_decoration：结构 → 原版条目（矿那批的原版前缀）→ BN 矿。
        populators.add(new dev.mumu.betternether.worldgen.UnifiedDecorationPopulator(
                scheduler, STRUCTURES, 7));
        // 原版条目由调度器按同一张计划真实执行（index 与 BN 条目同源），
        // 这样 BN 矿只负责自己那几条，不会因为「原版条目没人放」而整段消失。
        populators.add(new dev.mumu.betternether.worldgen.UnifiedDecorationPopulator(
                scheduler, dev.mumu.betternether.worldgen.DecorationScheduler.Phase.NATIVE_FEATURES, 7));
        populators.add(ore);                                        // 7 underground_decoration
        // step 8 / 9 vegetal_decoration / 10（树、植被、地表矿石回收等）。
        populators.add(new dev.mumu.betternether.worldgen.UnifiedDecorationPopulator(
                scheduler, ALL, 8, 9, 10));
        // TreePopulator 不注册：它的 23 个群系 profile 已全空，树由 step 9 的
        // placed feature 数据驱动。类的死代码保留不动，免得和「哪些 profile 还该留」纠缠。
        return populators;
    }

    @Override
    public @Nullable BiomeProvider getDefaultBiomeProvider(@NotNull WorldInfo worldInfo) {
        // 纯构造，不绑定。javap 核实：MinecraftServer 先 new CraftWorldInfo(...) → 调本方法 →
        // 才 new ServerLevel(...)；ServerLevel 构造里 527 处才 new CustomWorldChunkManager(...)，
        // 681 处才 putfield chunkSource。也就是说此刻既没有 ServerLevel、也没有
        // ServerChunkCache.randomState()，连 Bukkit.getWorld(name) 都是 null。在这里绑定必然抛，
        // 而异常会从世界创建流程冒出去，把整个世界建坏。
        //
        // 所以把 worldInfo 包进 Supplier：第一次 getBiome 时世界已经注册，那时再绑定。provider
        // 与地表/populator 仍然共用同一个 BiomePlacement 实例（bindTo 幂等）。
        // 门禁放在这里：CraftWorldInfo 自带 getEnvironment()，是纯读，不需要活世界；而且本方法
        // 每个世界只被调一次，非法环境报一条可诊断的错误，不会变成每 quart 一条异常。
        requireNether(worldInfo);
        return new NetherBiomeProvider(this::bindTo, biomes);
    }

    @Override
    public boolean shouldGenerateNoise() {
        // The Nether's entire terrain: the cavern skeleton, the y=31 lava sea, the bedrock roof and
        // floor plate. Upstream BetterNether has no noise of its own (its generator class is an empty
        // shell), so vanilla noise IS the mod's terrain -- turning this off would not be a trade-off,
        // it would be a world with no Nether in it.
        return true;
    }

    @Override
    public boolean shouldGenerateSurface() {
        // False: this generator owns the surface rule, and vanilla's must not also run.
        //
        // The previous comment here claimed the opposite ("returning true routes the phase to our
        // override, and vanilla's own surface pass still does not run"). That is not what CraftBukkit
        // does. Read out of the shipped server jar (26.1.2, the only implementation available on this
        // machine -- 26.3 is not on disk, so the exact 26.3 jar is unverified and this is the nearest
        // evidence), CustomChunkGenerator.buildSurface is:
        //
        //     if (generator.shouldGenerateSurface(worldInfo, random, chunkX, chunkZ)) {
        //         delegate.buildSurface(region, structures, randomState, chunk);   // vanilla SurfaceSystem
        //     }
        //     CraftChunkData data = new CraftChunkData(world, chunk);
        //     generator.generateSurface(worldInfo, random, chunkX, chunkZ, data);   // always
        //     generator.generateBedrock(worldInfo, random, chunkX, chunkZ, data);   // always
        //
        // So the flag selects whether *vanilla* runs; our own generateSurface is called either way.
        // Returning true therefore ran the vanilla nether surface rule underneath our paint, and its
        // non-bedrock materials (soul sand, gravel, magma, blackstone) showed wherever the painter did
        // not write. Upstream replaces the nether rule set outright and re-emits the bedrock gradients
        // itself (NetherBiomeConfig.surface()), which is the model followed here: one owner, not two.
        return false;
    }

    /**
     * Stamps the bedrock plates, which the surface phase now owns.
     *
     * <p>Vanilla's nether bedrock is not part of the noise pass -- {@code NoiseBasedChunkGenerator}
     * contains no bedrock reference at all -- it is the surface rule: {@code SurfaceRuleData} emits
     * {@code bedrock_floor} = {@code verticalGradient(bottom(), aboveBottom(5))} and
     * {@code bedrock_roof} = {@code not(verticalGradient(belowTop(5), top()))}, both painted as
     * BEDROCK. Upstream re-declares the same two gradients verbatim
     * ({@code NetherBiomeBuilder.java:117-127}) and paints them at a priority above every biome rule.
     * So with {@link #shouldGenerateSurface()} returning false, nothing else would place the plates:
     * this method has to, or the world loses its floor and its roof.
     *
     * <p>A vertical gradient is 1 at its first anchor and 0 at its second, which gives the floor plate
     * 1.0 at y=0 down to 0.2 at y=4, and the roof plate 0.2 at y=123 up to 1.0 at y=127. Those are the
     * bands {@link #BEDROCK_FLOOR_MAX} and {@link #BEDROCK_ROOF_MIN} name, and the reason the surface
     * painter never writes inside them.
     *
     * <p>Blocks are only ever <em>replaced</em>, never created: a cave carved into the roof band stays
     * open. Whether vanilla's own rule fills such a cell could not be verified from the available jar,
     * so the conservative reading is kept and flagged rather than guessed.
     */
    @Override
    public void generateBedrock(@NotNull WorldInfo world, @NotNull Random random,
                                int chunkX, int chunkZ, @NotNull ChunkData chunk) {
        for (int dx = 0; dx < 16; dx++) {
            for (int dz = 0; dz < 16; dz++) {
                for (int y = 0; y <= BEDROCK_FLOOR_MAX; y++) {
                    // Vanilla's anchor is aboveBottom(5), i.e. y=5 is the zero end of the ramp, so the
                    // ramp runs 0..5 rather than 0..4: y=4 keeps a 20% share and y=5 has none.
                    stampBedrock(chunk, dx, y, dz, random, bedrockChance(y, 0, PAINT_MIN_Y));
                }
                for (int y = BEDROCK_ROOF_MIN; y <= CEILING_Y; y++) {
                    // The fully-bedrock end of the roof ramp is the top of the world, so that anchor
                    // comes first: 1.0 at y=127 down to 0.2 at y=123.
                    stampBedrock(chunk, dx, y, dz, random,
                            bedrockChance(y, CEILING_Y, PAINT_MAX_Y));
                }
            }
        }
    }

    /**
     * The share of columns a plate places bedrock at, as a linear ramp between the plate's two ends.
     *
     * <p>Vanilla's {@code verticalGradient} is exactly this: 1 at the anchor nearest the world edge,
     * 0 at the other, linear in between. {@code from} is the end that is fully bedrock, {@code to}
     * the end that is not.
     */
    static double bedrockChance(int y, int from, int to) {
        int span = Math.abs(to - from);
        if (span == 0) {
            return y == from ? 1.0 : 0.0;
        }
        double travelled = Math.abs(y - from);
        return Math.max(0.0, 1.0 - travelled / span);
    }

    /**
     * Places bedrock at one position, if the base terrain left something there to replace.
     *
     * <p>The draw happens before the type check and unconditionally, so the random sequence depends
     * only on the position and the chunk seed -- never on what the terrain happens to contain.
     */
    private void stampBedrock(ChunkData chunk, int x, int y, int z, Random random, double chance) {
        if (random.nextDouble() >= chance) {
            return;
        }
        Material type = chunk.getType(x, y, z);
        if (type == null || type == Material.BEDROCK || type.isAir()) {
            return;
        }
        chunk.setBlock(x, y, z, bedrock);
    }

    @Override
    public boolean shouldGenerateBedrock() {
        // True so that a platform build which does consult this flag still calls generateBedrock.
        // The 26.1.2 implementation never reads it (0 references in CustomChunkGenerator) and the API
        // default is false, so leaving it alone would be a silent no-op on such a build.
        return true;
    }

    @Override
    public boolean shouldGenerateCaves() {
        // Keep the minecraft:nether_cave carver: the caves are part of the base terrain the mod
        // inherits. Note Paper runs carvers AFTER the surface phase, so a carve that eats a painted
        // top block leaves bare stone in that column until something else decorates it. That ordering
        // is the platform's, not a choice here, and it is still closer to the mod than no caves.
        //
        // 26.3 的字节码把这条顺序钉死了（javap NoiseBasedChunkGenerator#lambda$buildTerrain$1，
        // mucore-26.3.jar）：shouldGenerateNoise → fillFromNoise → shouldGenerateSurface →
        // （原版 buildSurface，本项目返回 false 跳过）→ CustomChunkGenerator.buildSurface（就是这里的
        // generateSurface + generateBedrock）→ shouldGenerateCaves → generateCarvers →
        // CustomChunkGenerator.applyCarvers。所以雕刻一定发生在本 pass 之后。
        // 雕刻后补绘新暴露的顶面这次没有加：离线证明不了它在真实生成里占多少比例，而一旦加了
        // 就会改到已批准的上游语义（含埋死岩体里的 soul_sand/deepslate 口袋，见
        // NetherTerrainGeneratorSurfaceTest#deepWritesAreOnlyANoOpWhereTheFilesAlsoDecideNetherrack）。
        // 需要现场生成一块雕刻密集区域复现后再决定。
        return true;
    }

    @Override
    public boolean shouldGenerateDecorations() {
        // False 之后，原版那一整道装饰 pass 不再跑 —— 结构与原版 placed feature **两件都**由
        // UnifiedDecorationPopulator / DecorationScheduler 接手，不是「把原版装饰删掉」。
        //
        // 依据是 CraftChunkGenerator 的两段字节码（mucore-26.3.jar，javap -c -p）：
        //
        // 1) CustomChunkGenerator#applyBiomeDecoration 把本方法的返回值当第 4 参传下去：
        //      InternalChunkGenerator.applyBiomeDecoration(level, chunk, manager,
        //              generator.shouldGenerateDecorations(worldInfo, random, chunkX, chunkZ));
        //
        // 2) ChunkGenerator#applyBiomeDecoration(level, chunk, manager, boolean)：
        //      0: iload 4 / 2: ifeq 12        ← false 就跳过（只跳这一步）
        //      5..9: addVanillaDecorations(level, chunk, manager)
        //     12..: new CraftLimitedRegion(level, chunk.getPos())，然后逐个跑 Bukkit populators
        //
        // 也就是说 false 只砍掉 addVanillaDecorations，populator 照跑。而
        // addVanillaDecorations 一个方法里同时做了两件事：
        //   * 每个 step 的结构 start 放方块（237→413 的 per-structure 循环）
        //   * 每个 step 的原版 placed feature（413 之后，含 BiomeFilter）
        // 所以「关掉它再自己跑一遍」必须两件都补，只补特征会得到**空气结构**
        // （start 生成了但没有任何方块落进区块）。这两件由 NativeDecorationBridge 用真实
        // WorldGenLevel / WorldGenRegion#structureManager / FeaturePlacer 逐段复刻，见其类注释。
        //
        // 为什么不干脆保持 true 再补 BN：那样原版 pass 会先整道跑完（step 0..10 一次到底），
        // BN 的 step 1 岩浆坑就只能紧跟其后，而 step 4 的尖锥反而在它之前长出来 ——
        // 与上游「一个 step 全员跑完再进下一个」相反；同时上游 features[step] 里那 414 条
        // minecraft: 条目会在原版 pass 与本项目计划里**各跑一次**（双放）。
        return false;
    }

    @Override
    public boolean shouldGenerateMobs() {
        // Vanilla Nether spawns. The mod adds to the spawn table; it does not replace it.
        return true;
    }

    @Override
    public boolean shouldGenerateStructures() {
        // Keep vanilla fortresses and bastions. Our own structures ride the datapack's structure_set,
        // which only runs when this phase is enabled.
        return true;
    }

    // getBaseHeight is deliberately NOT overridden. Paper then answers with vanilla's own heightmap,
    // which is what structure placement, spawn selection and locate all read; a plugin-side guess
    // would put fortresses and bastions at the wrong height without any way to notice.

    /**
     * The column biome at {@code (x, z)}, for diagnostics: what the floor is painted with below the
     * vertical band.
     *
     * <p>Deliberately the two-argument form. A column's <em>identity</em> is still 2D -- that is the
     * part Bukkit's {@code BiomeProvider} and the populators can agree on -- and the ceiling layer is
     * a paint on top of it. Use {@link #biomeKeyAt(int, int, int)} for a specific block.
     */
    public String biomeKeyAt(int x, int z) {
        return placement.keyAt(x, z);
    }

    /**
     * The biome at a block position, for diagnostics -- including the vertical band above
     * {@link BiomePlacement#CEILING_BAND_FLOOR}, which is what the surface pass and the populators
     * now paint and plant with.
     */
    public String biomeKeyAt(int x, int y, int z) {
        return placement.keyAt(x, y, z);
    }

    /** The declared biomes, for the provider and for diagnostics. */
    public List<Biome> declaredBiomes() {
        return biomes.declared();
    }
}
