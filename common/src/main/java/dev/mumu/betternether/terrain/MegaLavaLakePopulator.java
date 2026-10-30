package dev.mumu.betternether.terrain;

import dev.mumu.betternether.biome.BiomePlacement;
import dev.mumu.betternether.worldgen.DecorationRandom;
import dev.mumu.betternether.worldgen.NativeDecorationBridge;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.LimitedRegion;
import org.bukkit.generator.WorldInfo;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The {@code betternether:mega_lava_lake} structure: a lava lake a few chunks across, sunk into the
 * floor of the gloomwood.
 *
 * <h2>What it mirrors upstream</h2>
 * A port of
 * {@code upstream/BetterNether/.../world/structures/lake/MegaLavaLakeStructure.java} (placement) and
 * {@code .../world/structures/piece/LavaLakePiece.java} (the bowl, the lava, the shore), standing in for
 * two generated JSON files that cannot be shipped: a vanilla {@code minecraft:structure} cannot express a
 * custom structure type, so vanilla's structure system could never run it.
 * <pre>
 *   worldgen/structure_set/mega_lava_lake.json  salt 353194681, separation 8, spacing 24, linear spread
 *   worldgen/structure/mega_lava_lake.json      biomes #betternether:has_structure/mega_lava_lake
 *                                               (= [betternether:gloomwood]), step surface_structures
 * </pre>
 * Those numbers live here as {@link #SPACING}/{@link #SEPARATION}/{@link #SALT}/{@link #REQUIRED_BIOME}.
 *
 * <h2>Per-chunk architecture, and why it is order independent</h2>
 * A {@link BlockPopulator} is called once per chunk with a {@link LimitedRegion}, so this class answers
 * the three questions vanilla's structure machinery would:
 * <ol>
 *   <li><b>Which lakes reach me?</b> {@link #candidatesNear} re-derives vanilla's
 *       {@code RandomSpreadStructurePlacement} for the nearby spread regions.</li>
 *   <li><b>What does a lake look like?</b> {@link #lakeAt} -- pure function of
 *       {@code (worldSeed, startChunk)}, same RNG draws upstream makes.</li>
 *   <li><b>Where is its lava level, and may it be there?</b> {@link #probe} reads the vanilla base
 *       column ({@link ColumnSource}), which no chunk generation can change.</li>
 * </ol>
 * Nothing is cached, persisted, or decided by whichever chunk arrives first: two chunks of a lake agree
 * by recomputation, not by agreement.
 *
 * <h2>Random streams -- structure draws exact, shore draws sourced</h2>
 * <b>Structure.</b> Radius, depth, the discarded piece depth and the noise seed come off vanilla's
 * {@code setLargeFeatureSeed(worldSeed, startChunk)} in upstream's order ({@link #lakeAt}).
 *
 * <p><b>Shore.</b> Upstream's piece draws from the {@code WorldgenRandom} vanilla hands
 * {@code StructureStart#placeInChunk}, and that random's seed is
 * {@code setFeatureSeed(decorationSeed, index, step)} where {@code index} counts the step's
 * <em>registry entries</em> (26.3 bytecode: {@code ChunkGenerator#addVanillaDecorations}, local variable
 * table {@code decorationSeed}/{@code stepIndex}/{@code index}). This port reproduces that formula
 * ({@link #structurePhaseSeed}) and consumes one stream per {@code (lake, chunk)} in upstream's column
 * order ({@link #paint}).
 *
 * <p><b>The index is the structure's own registry slot, read live.</b> Upstream ships the lake as a
 * datapack structure JSON, so its slot is the datapack's ordering (by {@code Identifier}, with
 * {@code betternether:} before {@code minecraft:}) -- index 4 among the step-4 entries, with the same
 * eleven {@code betternether:} files on both sides. WoVer's bootstrap runs after the datapack elements are
 * registered and leaves existing ids where they are, so it does not move the lake. {@link #structureIndexFor}
 * therefore looks the id up in the live registry ({@code structureSlot}) instead of deriving a count:
 * insert a step-4 structure sorting before {@code betternether:mega_lava_lake} and both sides shift
 * together. When the slot is unreadable the random-driven shore work is <b>skipped</b> -- bowl, lava, bed,
 * apron and the inner shore are painted, the outer band and the vegetation are not -- rather than seeded
 * from an invented index.
 *
 * <h2>Deviations and open items</h2>
 * <ul>
 *   <li><b>{@code findYDownward}.</b> Reproduced from WoVer's 26.3 source: walk from {@code startY - 1}
 *       while {@code y > minY + 4}, take the first y that is solid under air (the solid block, not the
 *       air cell), then require the single headroom probe at {@code found + REQUIRED_HEADROOM}; a miss
 *       is final, the search does not continue to a roomier ledge.</li>
 *   <li><b>Write clipping.</b> Upstream writes through {@code BlocksHelper.setWithoutUpdate(world, pos,
 *       state)} -- the three-argument overload, a bare {@code setBlock} with no bounds test -- and
 *       relies on its own loop being clipped to {@code blockBox ∩ boundingBox}. {@code WriteZone} never
 *       enters the lake (the piece is not a feature, and its clip is narrower than any zone). This port
 *       clips the same way and adds one guard upstream lacks: {@link RegionSink} refuses writes into the
 *       bedrock plates and outside the buildable band, so a future bug cannot open the floor or roof.</li>
 *   <li><b>Rim probe.</b> All eight rim columns are readable from the base column, so all eight are
 *       tested; nothing is skipped.</li>
 *   <li><b>Block states.</b> Written as {@code CraftEngineLookup.lookup(id[, properties])} resolved once
 *       at construction. The gloomwisp's {@code shape} is set per segment; its <b>direction is not</b>:
 *       upstream leaves {@code ROTATION} at {@code random} and the <em>client</em> resolves it per block
 *       position over a sixteen-model weighted list (verified in upstream's {@code NetherModels}). The
 *       server jar this module compiles against contains no client classes at all, so that pick cannot be
 *       reproduced here -- recorded as an open item, not replaced by an invented formula.</li>
 *   <li><b>Order.</b> Upstream places the lake in {@code surface_structures}, before
 *       {@code vegetal_decoration}; the global step ordering (owned outside this class) puts this
 *       populator ahead of the terrain-feature step. The slot in {@code getDefaultPopulators} is not
 *       load-bearing.</li>
 * </ul>
 *
 * <h2>Folia</h2>
 * {@code ChunkGenerator#getBaseColumn} allocates its own noise chunk and reads no chunk, so calling it
 * from the populator of the region this thread owns is safe. There is no shared mutable state beyond one
 * cached registry-slot integer.
 */
public final class MegaLavaLakePopulator extends BlockPopulator {
    /** Vanilla {@code RandomSpreadStructurePlacement} parameters from the upstream structure set. */
    static final int SPACING = 24;
    static final int SEPARATION = 8;
    static final int SALT = 353194681;

    /** {@code #betternether:has_structure/mega_lava_lake}, whose only entry upstream is this one. */
    static final String REQUIRED_BIOME = "gloomwood";

    /**
     * The id the lake would carry as a datapack structure -- upstream ships exactly this file. It is what
     * {@link #structureIndexFor} looks up in the live registry to find the piece stream's slot.
     */
    static final String LAKE_STRUCTURE_ID = "betternether:mega_lava_lake";

    static final int MIN_RADIUS = 10;
    static final int MAX_RADIUS = 18;

    /** How far past {@link #MAX_RADIUS} the rim columns are sampled, covering the union's reach. */
    static final double RIM_PROBE_REACH = 1.3;

    static final int MIN_DEPTH = 3;
    static final int MAX_DEPTH = 6;

    /** How far below the lava surface a rim column may still be open before the placement is rejected. */
    static final int MAX_RIM_DROP = 8;

    /** Air needed above the middle: WoVer's {@code airAtOffset}, a single probe, not a clear run. */
    static final int REQUIRED_HEADROOM = 5;

    /** The lake's middle is searched from this far below the top of the world, as upstream does. */
    static final int SURFACE_SEARCH_FROM_TOP = 20;

    /** WoVer stops the walk at {@code minY + 4}, exclusive. */
    static final int SEARCH_STOP_ABOVE_BOTTOM = 4;

    /** Upstream refuses a floor that lands this close to the bottom of the world. */
    static final int MIN_FLOOR_ABOVE_BOTTOM = 8;

    /** Width of the shore band, measured out from the rim of the lava. */
    static final int SHORE_WIDTH = 8;

    /** The fraction of the shore band that is forced flat and level with the lava. */
    static final double APRON = 0.4;

    /** How far the apron reaches down for solid ground; matched to {@link #MAX_RIM_DROP}. */
    static final int APRON_FILL_DEPTH = 8;

    /** Thickness of the sealing shell under the magma bed where the bowl cut into open air. */
    static final int BED_SHELL = 3;

    /** Air kept clear above the lava; also the cut-off between a bump and standing rock. */
    static final int HEADROOM = 5;

    /** Fraction of the outline radius the noise may push the edge in or out. */
    static final double OUTLINE_WOBBLE = 0.18;

    /** One shore block in this many grows a gloomwisp stalk instead of a single-block plant. */
    static final int WISP_ONE_IN = 20;
    static final int MIN_WISP_HEIGHT = 2;
    static final int MAX_WISP_HEIGHT = 4;

    /** The molten-gloomsculk patch gate: molten where the heat channel clears {@code AT + s * FADE}. */
    static final double MOLTEN_AT_WATER = 0.20;
    static final double MOLTEN_FADE = 0.40;

    /** Where the non-molten shore turns from bleached gloomsculk to vanilla sculk. */
    static final double SCULK_PATCH_THRESHOLD = 0.305;

    /** The magma flower's share of the cover at the waterline. */
    static final double MAX_FLOWER_SHARE = 0.3;

    /**
     * How many chunks away a lake start may be and still reach the current chunk.
     *
     * <p>At the largest radius the union's furthest lobe sits 23.4 blocks out, the wobble adds 3.24 and
     * the shore band reaches {@link #SHORE_WIDTH} past that: about 35 blocks, inside three chunks (48).
     */
    static final int REACH_CHUNKS = 3;

    /**
     * The step the lake generates in: {@code GenerationStep.Decoration.SURFACE_STRUCTURES}.
     *
     * <p>The ordinal is the enum's declaration position ({@code RAW_GENERATION 0, LAKES 1,
     * LOCAL_MODIFICATIONS 2, UNDERGROUND_STRUCTURES 3, SURFACE_STRUCTURES 4, ...}), which is what vanilla
     * groups structures by.
     */
    static final int SURFACE_STRUCTURES_STEP = 4;

    private static final Logger LOGGER = Logger.getLogger("BetterNether");

    /** Block ids this populator writes; every one resolves once, at construction. */
    private static final List<String> BLOCK_IDS = List.of(
            "minecraft:lava", "minecraft:magma_block", "minecraft:netherrack", "minecraft:cave_air",
            "minecraft:sculk", "betternether:molten_gloomsculk", "betternether:veined_gloomsculk",
            "betternether:bleached_gloomsculk", "betternether:gloomgrass",
            "betternether:pale_gloomgrass", "betternether:magma_flower",
            "betternether:gloomwisp_vine");

    private static final String LAVA = "minecraft:lava";
    private static final String MAGMA = "minecraft:magma_block";
    private static final String NETHERRACK = "minecraft:netherrack";
    private static final String CAVE_AIR = "minecraft:cave_air";
    private static final String SCULK = "minecraft:sculk";
    private static final String MOLTEN_GLOOMSCULK = "betternether:molten_gloomsculk";
    private static final String VEINED_GLOOMSCULK = "betternether:veined_gloomsculk";
    private static final String BLEACHED_GLOOMSCULK = "betternether:bleached_gloomsculk";
    private static final String GLOOMGRASS = "betternether:gloomgrass";
    private static final String PALE_GLOOMGRASS = "betternether:pale_gloomgrass";
    private static final String MAGMA_FLOWER = "betternether:magma_flower";
    private static final String GLOOMWISP_VINE = "betternether:gloomwisp_vine";

    /** Upstream's three segment shapes, in upstream's order. */
    private static final String[] WISP_SHAPES = {"bottom", "middle", "top"};

    private final Map<String, BlockData> resolved = new LinkedHashMap<>();

    private final BiomePlacement placement;

    /** Cached step-4 registry entry count = this lake's slot; see {@link #structureIndexFor}. */
    private volatile Integer structureIndex;

    private volatile boolean warnedAboutMissingBlocks;
    private volatile boolean warnedAboutBaseColumn;
    private volatile boolean warnedAboutStructureIndex;

    public MegaLavaLakePopulator(BiomePlacement placement) {
        this.placement = placement;
        for (String id : BLOCK_IDS) {
            BlockData data = CraftEngineLookup.lookup(id);
            if (data != null) {
                resolved.put(id, data);
            }
        }
        // The wisp segments, resolved through the shared lookup so the lake, the flora and the trees put
        // the same block in the same way. Shapes only: the direction is deliberately absent (see class
        // javadoc).
        for (int shape = 0; shape < WISP_SHAPES.length; shape++) {
            BlockData data = CraftEngineLookup.lookup(GLOOMWISP_VINE,
                    Map.of("shape", WISP_SHAPES[shape]));
            if (data != null) {
                resolved.put(wispId(shape), data);
            }
        }
    }

    /** The ids this populator wanted and the pack did not supply. Empty is the healthy answer. */
    public List<String> missingIds() {
        List<String> missing = new ArrayList<>();
        for (String id : BLOCK_IDS) {
            if (!resolved.containsKey(id)) {
                missing.add(id);
            }
        }
        return missing;
    }

    // ---------------------------------------------------------------- the populator entry point

    @Override
    public void populate(@NotNull WorldInfo worldInfo, @NotNull Random random, int chunkX, int chunkZ,
                         @NotNull LimitedRegion region) {
        if (!resolved.containsKey(LAVA) || !resolved.containsKey(MAGMA)) {
            warnMissingBlocksOnce();
            return;
        }
        RegionSink sink = new RegionSink(region, resolved, NetherTerrainGenerator.BEDROCK_FLOOR_MAX + 1,
                NetherTerrainGenerator.CEILING_Y);
        try {
            populateChunk(worldInfo.getSeed(), BaseColumnSource.of(region.getWorld()), chunkX, chunkZ,
                    placement, sink, structureIndexFor(region, chunkX, chunkZ));
        } catch (IllegalStateException error) {
            // The base column is the one input this populator cannot substitute: without it there is no
            // honest water level. Report once and let the rest of the chunk generate.
            warnBaseColumnFailureOnce(error);
        }
    }

    /**
     * Paints every lake slice that belongs to this chunk.
     *
     * <p>Order independent by construction: each chunk re-derives the shape and the lava level from
     * {@code (worldSeed, coordinates)} alone. Package-private and taking {@link ColumnSource} plus
     * {@link Sink} so the tests can run this exact method over a synthetic world in any chunk order.
     *
     * @param structureIndex the step-4 registry slot for the shore stream, or {@code null} when it cannot
     *                       be established -- in which case nothing that draws is painted
     */
    static void populateChunk(long worldSeed, ColumnSource columns, int chunkX, int chunkZ,
                              BiomePlacement placement, Sink sink, @Nullable Integer structureIndex) {
        int startY = columns.minY() + columns.genDepth() - SURFACE_SEARCH_FROM_TOP;
        int minY = columns.minY();
        for (LakeParams lake : candidatesNear(worldSeed, chunkX, chunkZ)) {
            Decision decision = probe(lake, placement, columns, startY, minY);
            if (!decision.accepted()) {
                continue;
            }
            paint(lake, decision.surfaceY(), chunkX, chunkZ, sink, worldSeed, structureIndex);
        }
    }

    /**
     * The step-4 registry slot the shore stream is seeded from, or {@code null} when it cannot be read.
     *
     * <p><b>What the slot is, and why it is a specific position rather than "the last one".</b> Vanilla
     * seeds a structure's pieces with {@code setFeatureSeed(decorationSeed, index, step)}, {@code index}
     * being the structure's position among that step's registry entries. Upstream ships the lake as an
     * ordinary datapack structure ({@code data/betternether/worldgen/structure/mega_lava_lake.json}, one
     * of eleven such files), so its position is the datapack's own: entries are ordered by
     * {@code Identifier} ({@code MultiPackResourceManager.listResources} returns a {@code TreeMap}),
     * {@code betternether:} sorts before {@code minecraft:}, and the eleven files are the same in upstream
     * and here. That puts the step-4 {@code betternether:} entries in the order {@code altars, gardens,
     * ghast_hive, jungle_temples, mega_lava_lake, ...} -- the lake at index <b>4</b>, in both.
     *
     * <p>WoVer's bootstrap does not move it: it runs after the datapack elements have been registered
     * ("{@code registerElements(...)} has already run", per {@code ResourceManagerRegistryLoadTaskMixin})
     * and its {@code register} returns the existing bound holder unchanged, so an id already in the
     * registry keeps its position.
     *
     * <p>So the number is read from the live registry for <em>this</em> id rather than derived from a
     * count: {@code structureSlot(region, 4, "betternether:mega_lava_lake")}. On a server with vanilla
     * 26.3 plus this port's eleven BN JSONs it evaluates to 4; add a step-4 structure whose path sorts
     * before {@code mega_lava_lake} and it moves -- which is exactly what upstream's would do too.
     * {@code -1} means the registry could not be read (no region, or no server on the classpath); the
     * caller then paints only what needs no draw instead of inventing an index.
     */
    private @Nullable Integer structureIndexFor(LimitedRegion region, int chunkX, int chunkZ) {
        Integer cached = structureIndex;
        if (cached != null) {
            return cached;
        }
        int index = NativeDecorationBridge.structureSlot(region, SURFACE_STRUCTURES_STEP, LAKE_STRUCTURE_ID);
        if (index < 0) {
            warnAboutStructureIndexOnce("结构槽位不可读（structureSlot 返回 -1）");
            return null;
        }
        structureIndex = index;
        return index;
    }

    // ---------------------------------------------------------------- the decision

    /**
     * Upstream's {@code findGenerationPoint}: the floor under the lake's middle, the biome gate, the rim
     * drop test.
     *
     * <p>Every read goes through {@link ColumnSource}, which in production is the chunk generator's
     * {@code getBaseColumn} -- the same call upstream makes, and the reason a multi-chunk lake can be
     * decided from any one chunk.
     */
    static Decision probe(LakeParams lake, BiomePlacement placement, ColumnSource columns, int startY,
                          int minY) {
        int x = lake.centerX();
        int z = lake.centerZ();

        int surfaceY = findSurfaceY(columns.at(x, z), startY, minY + SEARCH_STOP_ABOVE_BOTTOM,
                REQUIRED_HEADROOM);
        if (surfaceY == Integer.MIN_VALUE || surfaceY < minY + MIN_FLOOR_ABOVE_BOTTOM) {
            return Decision.REJECTED;
        }
        // Upstream's Structure#findValidGenerationPoint asks the biome at the returned stub: the lake's
        // own middle at floor level.
        if (!REQUIRED_BIOME.equals(placement.keyAt(x, surfaceY, z))) {
            return Decision.REJECTED;
        }
        int probeY = surfaceY - MAX_RIM_DROP;
        for (int[] column : rimColumns(x, z, lake.radius())) {
            if (columns.at(column[0], column[1]).isAir(probeY)) {
                return Decision.REJECTED;
            }
        }
        return new Decision(true, surfaceY);
    }

    /**
     * Eight points around the rim of the lava surface: a lake this wide can straddle a ravine running
     * diagonally between two corners and pass with both of them on solid ground.
     */
    static List<int[]> rimColumns(int x, int z, int lobeRadius) {
        int radius = (int) (lobeRadius * RIM_PROBE_REACH);
        int diagonal = (int) (radius * 0.7071F);
        return List.of(
                new int[]{x - radius, z}, new int[]{x + radius, z},
                new int[]{x, z - radius}, new int[]{x, z + radius},
                new int[]{x - diagonal, z - diagonal}, new int[]{x + diagonal, z - diagonal},
                new int[]{x - diagonal, z + diagonal}, new int[]{x + diagonal, z + diagonal});
    }

    /**
     * WoVer's {@code StructurePlacement#findYDownward} for a single column: the topmost solid block under
     * a block of air, or {@link Integer#MIN_VALUE}.
     *
     * <p>Exact behaviour, from the 26.3 source: the walk starts at {@code startY - 1} and continues while
     * {@code y > stopY} (WoVer passes {@code minY + 4}); the match is <b>air above, solid here</b> and it
     * returns the <em>solid</em> block; {@code airAtOffset}, when non-zero, is probed once at
     * {@code found + airAtOffset} and a miss is final -- the original breaks at the first match and never
     * looks for a deeper ledge with better headroom.
     *
     * @param stopY       exclusive lower bound for the walk
     * @param airAtOffset vertical offset the headroom probe is read at, or {@code 0} to skip it
     */
    static int findSurfaceY(Column column, int startY, int stopY, int airAtOffset) {
        for (int y = startY - 1; y > stopY; y--) {
            if (!column.isAir(y + 1) || !column.isSolid(y)) {
                continue;
            }
            if (airAtOffset != 0 && !column.isAir(y + airAtOffset)) {
                return Integer.MIN_VALUE;
            }
            return y;
        }
        return Integer.MIN_VALUE;
    }

    // ---------------------------------------------------------------- the piece

    /**
     * Paints this chunk's columns of one lake: bowl, lava, bed, shore.
     *
     * <p>A column-for-column port of {@code LavaLakePiece#postProcess}, with three adaptations: the loop
     * is clipped to the current chunk instead of the piece's bounding box intersected with it, the block
     * ids are symbolic (the {@link Sink} owns the {@link BlockData}), and the standing-rock and ground
     * tests work on {@link Kind} because CraftEngine's deceived materials make {@code canBeReplaced}
     * unanswerable.
     *
     * <p>Randomness: one sequential stream per {@code (lake, chunk)}, seeded exactly as vanilla seeds the
     * structure phase ({@link #structurePhaseSeed}) and consumed in upstream's column order. Because the
     * seed depends on {@code (worldSeed, chunk, registryIndex)} and the walk never leaves the chunk, the
     * stream a chunk consumes is fixed -- which keeps the lake identical whatever generated first, with no
     * cross-chunk state. With no index there is no stream, and the drawing parts are skipped.
     */
    static void paint(LakeParams lake, int surfaceY, int chunkX, int chunkZ, Sink sink, long worldSeed,
                      @Nullable Integer structureIndex) {
        LakeShape shape = new LakeShape(lake);
        int reach = shape.reach();
        int minX = Math.max(lake.centerX() - reach, chunkX << 4);
        int maxX = Math.min(lake.centerX() + reach, (chunkX << 4) + 15);
        int minZ = Math.max(lake.centerZ() - reach, chunkZ << 4);
        int maxZ = Math.min(lake.centerZ() + reach, (chunkZ << 4) + 15);

        // The decoration phase's random is `WorldgenRandom(new XoroshiroRandomSource(...))`, not the
        // 48-bit LCG the structure phase uses -- so the shore must draw from DecorationRandom, which is
        // bit-equivalent to that stream (verified against NMS on 252 cases in DecorationRandomTest).
        Random random = structureIndex == null
                ? null
                : shoreRandom(worldSeed, chunkX, chunkZ, structureIndex);
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                Shape s = shape.at(x, z);
                if (s.dOut() >= SHORE_WIDTH) {
                    continue;
                }
                if (!sink.readable(x, surfaceY, z)) {
                    continue;
                }
                // Standing rock is not touched at all, on either side of the rim.
                if (overburden(sink, x, z, surfaceY) > HEADROOM) {
                    continue;
                }
                if (s.t() < 1.0) {
                    carveBowl(sink, x, z, surfaceY, lake.depth(), s.t());
                } else {
                    buildShore(sink, shape, random, x, z, surfaceY, s.dOut() / SHORE_WIDTH);
                }
            }
        }
    }

    /**
     * How many blocks of unbroken rock sit directly above the lava level, counted up to
     * {@code HEADROOM + 1}: the caller only needs to know whether the column is a bump or a wall.
     */
    private static int overburden(Sink sink, int x, int z, int surfaceY) {
        for (int i = 1; i <= HEADROOM + 1; i++) {
            if (sink.kindAt(x, surfaceY + i, z) == Kind.AIR) {
                return i - 1;
            }
        }
        return HEADROOM + 1;
    }

    /**
     * One column inside the lake: a magma bed, lava on top of it up to the surrounding floor level, and
     * clear air above.
     *
     * @param t how deep into the nearest lobe this column sits -- {@code 0} at its middle, {@code 1} on
     *          its rim
     */
    private static void carveBowl(Sink sink, int x, int z, int surfaceY, int depth, double t) {
        int bowl = (int) Math.round(depth * (1 - t * t));
        int bedY = surfaceY - bowl;

        for (int y = surfaceY; y > bedY; y--) {
            sink.write(x, y, z, LAVA);
        }

        // Magma only ever goes under lava. At the very rim the paraboloid has run out of depth and there
        // is no lava above this block, so it takes the shore's material instead.
        sink.write(x, bedY, z, bowl > 0 ? MAGMA : MOLTEN_GLOOMSCULK);

        // Where the bowl cut into a cave the bed would hang over nothing. A shell rather than a fill:
        // closing a cave completely would cost the player the passage under the lake.
        for (int y = bedY - 1; y >= bedY - BED_SHELL; y--) {
            if (!sink.readable(x, y, z) || sink.kindAt(x, y, z) != Kind.AIR) {
                break;
            }
            sink.write(x, y, z, NETHERRACK);
        }

        clearAbove(sink, x, z, surfaceY);
    }

    /**
     * Takes the roof off a column. Only called where {@link #overburden} found a bump rather than a wall,
     * so this cannot leave rock hanging over the lake.
     */
    private static void clearAbove(Sink sink, int x, int z, int surfaceY) {
        for (int y = surfaceY + 1; y <= surfaceY + HEADROOM; y++) {
            if (!sink.readable(x, y, z) || sink.kindAt(x, y, z) == Kind.AIR) {
                continue;
            }
            sink.write(x, y, z, CAVE_AIR);
        }
    }

    /**
     * One column of the shore.
     *
     * @param s how far through the band this column sits: {@code 0} at the lava's edge, {@code 1} at the
     *          outer end
     */
    private static void buildShore(Sink sink, LakeShape shape, @Nullable Random random, int x, int z,
                                   int surfaceY, double s) {
        int groundY;
        if (s < APRON) {
            // The apron is what holds the lava in, so it is built rather than found.
            clearAbove(sink, x, z, surfaceY);
            if (!fillDownToGround(sink, x, z, surfaceY)) {
                return;
            }
            groundY = surfaceY;
        } else {
            // Outside the apron the terrain keeps its own shape; only its skin changes, and less and less
            // of it the further out. Without a structure index there is no real stream for the fade, and
            // inventing one is not an option -- so the outer band is left alone.
            if (random == null) {
                return;
            }
            double fade = (s - APRON) / (1 - APRON);
            if (random.nextDouble() < fade) {
                return;
            }
            groundY = findSurface(sink, x, z, surfaceY + 3, surfaceY - 3);
            if (groundY == Integer.MIN_VALUE) {
                return;
            }
        }

        String ground = shape.groundAt(x, z, s);
        if (!sink.write(x, groundY, z, ground)) {
            return;
        }
        // Lush at the waterline, thinning to about a quarter at the outer edge of the band.
        if (random != null && random.nextDouble() < 1.0 - 0.75 * s) {
            vegetate(sink, x, groundY, z, ground, random, s);
        }
    }

    /**
     * Makes a column solid from {@code fromY} downwards, reaching at most {@link #APRON_FILL_DEPTH} for
     * existing ground. Netherrack with one veined gloomsculk layer under the skin, which is how the biome
     * builds its own floor.
     *
     * @return {@code false} when there was no ground within reach; the column is then left untouched
     */
    private static boolean fillDownToGround(Sink sink, int x, int z, int fromY) {
        int groundY = Integer.MIN_VALUE;
        for (int y = fromY - 1; y >= fromY - APRON_FILL_DEPTH; y--) {
            if (!sink.readable(x, y, z)) {
                break;
            }
            if (sink.kindAt(x, y, z) == Kind.GROUND) {
                groundY = y;
                break;
            }
        }
        if (groundY == Integer.MIN_VALUE) {
            return false;
        }
        for (int y = fromY - 2; y > groundY; y--) {
            sink.write(x, y, z, NETHERRACK);
        }
        if (fromY - 1 > groundY) {
            sink.write(x, fromY - 1, z, VEINED_GLOOMSCULK);
        }
        return true;
    }

    /** The highest solid block of a column within a window, or {@link Integer#MIN_VALUE}. */
    private static int findSurface(Sink sink, int x, int z, int topY, int bottomY) {
        for (int y = topY; y >= bottomY; y--) {
            if (!sink.readable(x, y, z)) {
                return Integer.MIN_VALUE;
            }
            if (sink.kindAt(x, y, z) == Kind.GROUND) {
                return y;
            }
        }
        return Integer.MIN_VALUE;
    }

    /**
     * One plant on top of a shore block: gloomgrass and the occasional gloomwisp stalk, with magma flowers
     * mixed through the part of the band nearest the lava. Which gloomgrass grows is decided by the block
     * under it, the same way the biome's two grass features are split by their ground filters.
     */
    private static void vegetate(Sink sink, int x, int groundY, int z, String ground, Random random,
                                 double s) {
        int above = groundY + 1;
        if (!sink.readable(x, above, z) || sink.kindAt(x, above, z) != Kind.AIR) {
            return;
        }
        if (random.nextInt(WISP_ONE_IN) == 0 && placeWisp(sink, x, above, z, random)) {
            return;
        }
        String plant;
        if (random.nextDouble() < MAX_FLOWER_SHARE * (1.0 - s)) {
            plant = MAGMA_FLOWER;
        } else {
            plant = BLEACHED_GLOOMSCULK.equals(ground) ? PALE_GLOOMGRASS : GLOOMGRASS;
        }
        sink.write(x, above, z, plant);
    }

    /**
     * A gloomwisp stalk. The headroom is checked first, because a stalk whose head is cut off reads as
     * broken; then each segment gets upstream's shape (TOP for the head, MIDDLE under it, BOTTOM below).
     */
    private static boolean placeWisp(Sink sink, int x, int baseY, int z, Random random) {
        int height = MIN_WISP_HEIGHT + random.nextInt(MAX_WISP_HEIGHT - MIN_WISP_HEIGHT + 1);
        for (int i = 0; i < height; i++) {
            int y = baseY + i;
            if (!sink.readable(x, y, z) || sink.kindAt(x, y, z) != Kind.AIR) {
                return false;
            }
        }
        for (int i = 0; i < height; i++) {
            int shapeIndex = i == height - 1 ? 2 : i == height - 2 ? 1 : 0;
            sink.write(x, baseY + i, z, wispId(shapeIndex));
        }
        return true;
    }

    /**
     * A wisp segment id carrying its shape, and <b>no direction</b>.
     *
     * <p>Upstream's worldgen writes {@code SHAPE} and leaves {@code ROTATION} at its default
     * {@code random}, which the <em>client</em> resolves per block position over a weighted list of
     * sixteen models -- four blink phases x four quarter turns, order verified in upstream's
     * {@code NetherModels} ({@code tipAt}: {@code for (int y : new int[]{0, 90, 180, 270})}). The server
     * jar this module compiles against contains no client classes at all (zero, and no
     * {@code WeightedVariants}/{@code MultiVariant}), so that pick cannot be reproduced here. Recorded as
     * an open item rather than approximated: the port writes the same state upstream writes.
     */
    static String wispId(int shapeIndex) {
        return GLOOMWISP_VINE + "[shape=" + WISP_SHAPES[shapeIndex] + "]";
    }

    // ---------------------------------------------------------------- the vanilla base column

    /**
     * The vanilla base column of any block column in the world: pure noise, before carvers, before the
     * surface rule, before decoration -- exactly what upstream reads, and the only reason a multi-chunk
     * lake can be decided from a single chunk with no shared state.
     *
     * <p>An interface rather than the server type so the tests can decide lakes over a synthetic world;
     * production is {@link BaseColumnSource}.
     */
    interface ColumnSource {
        Column at(int x, int z);

        /** Upstream's {@code generator.getGenDepth()}. */
        int genDepth();

        /** Upstream's {@code generator.getMinY()}. */
        int minY();
    }

    /** One column of the base terrain, with upstream's two predicates. */
    interface Column {
        /** {@code state.isAir()}. */
        boolean isAir(int y);

        /** {@code WORLD_SURFACE_WG.isOpaque().test(state) && !state.liquid()}, as upstream tests it. */
        boolean isSolid(int y);
    }

    /**
     * The production {@link ColumnSource}: the world's own chunk generator, asked for its base column.
     *
     * <p>{@code ChunkGenerator#getBaseColumn(int, int, LevelHeightAccessor, RandomState)} has no Bukkit
     * or Paper equivalent ({@link LimitedRegion} answers only inside its own 16-block buffer, and
     * {@link WorldInfo} carries no terrain at all), so this is the one place the port steps below the
     * API. The chain is public in the server jar this module compiles against:
     * <pre>
     *   CraftWorld#getHandle() -&gt; ServerLevel
     *   ServerLevel#getChunkSource() -&gt; ServerChunkCache
     *   ServerChunkCache#getGenerator() -&gt; ChunkGenerator   (CustomChunkGenerator)
     *   ServerChunkCache#randomState() -&gt; RandomState
     *   ChunkGenerator#getBaseColumn(x, z, level, randomState) -&gt; NoiseColumn
     * </pre>
     * {@code CustomChunkGenerator#getBaseColumn} delegates unconditionally to the vanilla
     * {@code NoiseBasedChunkGenerator} (bytecode-checked), whose {@code iterateNoiseColumn} allocates a
     * full {@code BlockState[sizeY]} column -- i.e. the noise terrain this port's surface pass paints on.
     */
    static final class BaseColumnSource implements ColumnSource {
        /** Upstream's solid test, taken from the heightmap type it names. */
        private static final Predicate<BlockState> OPAQUE = Heightmap.Types.WORLD_SURFACE_WG.isOpaque();

        private final ChunkGenerator generator;
        private final RandomState randomState;
        private final LevelHeightAccessor heightAccessor;
        private final int genDepth;
        private final int minY;

        private BaseColumnSource(ChunkGenerator generator, RandomState randomState,
                                 LevelHeightAccessor heightAccessor) {
            this.generator = generator;
            this.randomState = randomState;
            this.heightAccessor = heightAccessor;
            this.genDepth = generator.getGenDepth();
            this.minY = generator.getMinY();
            if (genDepth <= 0) {
                throw new IllegalStateException("世界生成器的 getGenDepth() 为 " + genDepth
                        + "，没有可读的原版地形列，巨型熔岩湖无法定位水位。");
            }
        }

        /**
         * Binds to a world's generator.
         *
         * @throws IllegalStateException when the world is not a server world this API can reach
         */
        static BaseColumnSource of(World world) {
            if (!(world instanceof CraftWorld craftWorld)) {
                throw new IllegalStateException("巨型熔岩湖需要服务端世界，实际是 "
                        + world.getClass().getName());
            }
            ServerLevel level = craftWorld.getHandle();
            ServerChunkCache chunkSource = level.getChunkSource();
            return new BaseColumnSource(chunkSource.getGenerator(), chunkSource.randomState(), level);
        }

        @Override
        public int genDepth() {
            return genDepth;
        }

        @Override
        public int minY() {
            return minY;
        }

        /**
         * The base column, verified to be a real one.
         *
         * <p>Not defensive noise: {@code NoiseColumn#getBlock} returns {@code Blocks.AIR} for any y
         * outside its array (26.3 bytecode). A generator answering with a short or empty column would
         * therefore read as "air everywhere" and every lake would silently find no floor -- a failure
         * indistinguishable from "the feature does not generate". The bottom of a generated column is
         * never air, so a null column or an air bottom is thrown.
         *
         * @throws IllegalStateException when the generator does not supply a usable base column
         */
        @Override
        public Column at(int x, int z) {
            NoiseColumn column = generator.getBaseColumn(x, z, heightAccessor, randomState);
            if (column == null) {
                throw new IllegalStateException("世界生成器对 (" + x + ", " + z
                        + ") 返回了 null 的 base column，巨型熔岩湖无法定位水位。");
            }
            if (column.getBlock(minY).isAir()) {
                throw new IllegalStateException("世界生成器对 (" + x + ", " + z + ") 返回的 base column 在 y="
                        + minY + " 读到空气：该列不可用（越界读数会静默返回空气，所以这里明确失败）。");
            }
            return new Column() {
                @Override
                public boolean isAir(int y) {
                    return column.getBlock(y).isAir();
                }

                @Override
                public boolean isSolid(int y) {
                    BlockState state = column.getBlock(y);
                    // upstream writes `state.liquid()`, deprecated in 26.3; the fluid state answers the
                    // same question for the blocks a noise column contains.
                    return OPAQUE.test(state) && state.getFluidState().isEmpty();
                }
            };
        }
    }

    // ---------------------------------------------------------------- shape of a lake

    /**
     * One lake as a pure function of {@code (worldSeed, startChunk)}.
     *
     * @param centerX {@code startChunkX * 16 + 8}, vanilla's chunk middle
     * @param centerZ {@code startChunkZ * 16 + 8}
     * @param radius  radius of the main lobe
     * @param depth   depth of the bowl at its middle
     * @param noiseSeed seed of the outline and shore noise, and of the lobe layout
     */
    record LakeParams(int startChunkX, int startChunkZ, int centerX, int centerZ, int radius, int depth,
                      int noiseSeed) {
    }

    /** Whether a lake may generate, and at which height its lava sits. */
    record Decision(boolean accepted, int surfaceY) {
        static final Decision REJECTED = new Decision(false, 0);
    }

    /** Which chunk starts the lake in one spread region. */
    record StartChunk(int x, int z) {
    }

    /** One disc of the union: its middle relative to the lake's centre, and its own radius. */
    private record Lobe(double offsetX, double offsetZ, double radius) {
        double reach() {
            return Math.sqrt(offsetX * offsetX + offsetZ * offsetZ) + radius;
        }
    }

    /** A column resolved against the union. */
    record Shape(double t, double dOut) {
    }

    /**
     * The union of the lake's lobes plus its noise: everything about the outline that does not depend on
     * the world.
     *
     * <p>Two or three overlapping discs rather than one, which is what gives the bean and kidney
     * silhouettes: where two of them meet at an angle the outline pinches into a waist, and the noise is
     * then only roughening an edge that already has a shape. Every column reduces to {@code t} (distance
     * to the nearest lobe's middle over that lobe's radius) and {@code dOut} (blocks beyond the outline).
     *
     * <p>Lobes are derived from the noise seed and read in world coordinates, so every chunk of the lake
     * computes the same edge without consulting its neighbours.
     */
    static final class LakeShape {
        private final LakeParams lake;
        private final OpenSimplexNoise noise;
        private final Lobe[] lobes;
        private final int reach;

        LakeShape(LakeParams lake) {
            this.lake = lake;
            this.noise = new OpenSimplexNoise(lake.noiseSeed());
            this.lobes = lobes(lake.radius(), lake.noiseSeed());
            double furthest = 0;
            for (Lobe lobe : lobes) {
                furthest = Math.max(furthest, lobe.reach());
            }
            this.reach = (int) Math.ceil(furthest * (1 + OUTLINE_WOBBLE)) + SHORE_WIDTH + 1;
        }

        /**
         * The discs whose union is the lake. The second is pushed out far enough to clear the first one's
         * edge but not so far that it detaches, so the two always overlap and the outline pinches where
         * they meet -- which is the bean. The third turns up half the time and makes it a clover.
         */
        private static Lobe[] lobes(int radius, int seed) {
            Random random = new Random(seed);
            double angle = random.nextDouble() * Math.PI * 2;

            List<Lobe> out = new ArrayList<>(3);
            out.add(new Lobe(0, 0, radius));

            double d1 = radius * (0.45 + random.nextDouble() * 0.15);
            double r1 = radius * (0.55 + random.nextDouble() * 0.15);
            out.add(new Lobe(Math.cos(angle) * d1, Math.sin(angle) * d1, r1));

            if (random.nextBoolean()) {
                double a2 = angle + Math.PI * (0.5 + random.nextDouble() * 0.5);
                double d2 = radius * (0.4 + random.nextDouble() * 0.15);
                double r2 = radius * (0.45 + random.nextDouble() * 0.15);
                out.add(new Lobe(Math.cos(a2) * d2, Math.sin(a2) * d2, r2));
            }
            return out.toArray(new Lobe[0]);
        }

        /** Half-extent of the lake's bounding box, in blocks. */
        int reach() {
            return reach;
        }

        /**
         * Resolves one column against the union. The nearest lobe wins, measured in its own radii -- which
         * is what makes the union a union. One wobble value for all lobes: sampled per lobe, the edges
         * would ripple independently and the waist where they cross would come apart.
         */
        Shape at(int x, int z) {
            double wobble = noise.eval(x * 0.07, z * 0.07) * lake.radius() * OUTLINE_WOBBLE;
            double bestT = Double.MAX_VALUE;
            double bestRadius = lake.radius();
            for (Lobe lobe : lobes) {
                double dx = x - lake.centerX() - lobe.offsetX();
                double dz = z - lake.centerZ() - lobe.offsetZ();
                double t = (Math.sqrt(dx * dx + dz * dz) - wobble) / lobe.radius();
                if (t < bestT) {
                    bestT = t;
                    bestRadius = lobe.radius();
                }
            }
            return new Shape(bestT, (bestT - 1.0) * bestRadius);
        }

        /**
         * What the shore is made of at one column: the gloomwood's own three floor blocks and nothing
         * else. Noise channels rather than a random roll, so materials come out as patches that hold
         * together across chunk seams instead of as speckle; the molten patches are gated on {@code s} as
         * well, which turns "hot at the edge, cool further out" into a gradient rather than a ring.
         */
        String groundAt(int x, int z, double s) {
            double heat = noise.eval(x * 0.09, z * 0.09, 60);
            if (heat > MOLTEN_AT_WATER + s * MOLTEN_FADE) {
                return MOLTEN_GLOOMSCULK;
            }
            return noise.eval(x * 0.12, z * 0.12, -60) > SCULK_PATCH_THRESHOLD
                    ? SCULK
                    : BLEACHED_GLOOMSCULK;
        }
    }

    // ---------------------------------------------------------------- placement

    /**
     * Vanilla's {@code WorldgenRandom#setLargeFeatureSeed(levelSeed, chunkX, chunkZ)} -- the seed of the
     * random a structure's {@code findGenerationPoint} draws from.
     *
     * <p>Bytecode (26.3): {@code setSeed(levelSeed)}, draw two {@code nextLong}s, then
     * {@code setSeed((chunkX * a) ^ (chunkZ * b) ^ levelSeed)}. Note both the {@code XOR} and the absence
     * of the {@code |1} the decoration seed applies -- this is a <em>different</em> formula from
     * {@code setLargeFeatureWithSalt}, which is plain addition of the two magic constants and is what the
     * spread placement uses ({@link #potentialStart}). Using one for the other silently moves every lake
     * onto a different chunk/shape, so both are spelled out separately here.
     *
     * <p>The source is a {@code LegacyRandomSource} ({@code Structure.GenerationContext#makeRandom} builds
     * {@code new WorldgenRandom(new LegacyRandomSource(0L))}), i.e. the 48-bit LCG, so a plain
     * {@link Random} is the correct stream here -- unlike the decoration phase, which is Xoroshiro.
     */
    static long largeFeatureSeed(long worldSeed, int chunkX, int chunkZ) {
        Random random = new Random(worldSeed);
        long a = random.nextLong();
        long b = random.nextLong();
        return ((long) chunkX * a) ^ ((long) chunkZ * b) ^ worldSeed;
    }

    /**
     * The chunk a spread region starts its lake in, reproducing vanilla's
     * {@code RandomSpreadStructurePlacement} for a linear spread.
     *
     * <p>Vanilla computes {@code setLargeFeatureWithSalt(worldSeed, regionX, regionZ, salt)} -- i.e.
     * {@code regionX * 341873128712 + regionZ * 132897987541 + worldSeed + salt} -- and draws
     * {@code nextInt(spacing - separation)} twice. {@code LegacyRandomSource} is a 48-bit LCG seeded
     * exactly like {@link Random}, and its {@code nextInt} on a power-of-two bound is
     * {@code java.util.Random}'s, so one {@link Random} reproduces it without NMS. {@code 24 - 8} is a
     * power of two, which is why bit-level agreement matters.
     */
    static StartChunk potentialStart(long worldSeed, int regionX, int regionZ) {
        long seed = (long) regionX * 341873128712L + (long) regionZ * 132897987541L + worldSeed + SALT;
        Random random = new Random(seed);
        int offsetX = random.nextInt(SPACING - SEPARATION);
        int offsetZ = random.nextInt(SPACING - SEPARATION);
        return new StartChunk(regionX * SPACING + offsetX, regionZ * SPACING + offsetZ);
    }

    /**
     * The lake a start chunk holds, drawn from the same stream upstream's structure uses.
     *
     * <p>Upstream draws {@code randRange(10, 18)} for the radius and {@code randRange(3, 6)} for the
     * depth before reading the world, then its piece constructor draws the piece's {@code genDepth} and
     * its noise seed -- four {@code nextInt} calls in a fixed order, reproduced here (the third is drawn
     * and discarded: a populator has no piece to carry a generation depth). The stream's seed is
     * vanilla's structure seed, {@code setLargeFeatureSeed(worldSeed, startChunkX, startChunkZ)} --
     * reproduced by {@link #largeFeatureSeed}, which is <em>not</em> the same formula as the spread
     * placement's {@link #potentialStart} seed.
     */
    static LakeParams lakeAt(long worldSeed, int startChunkX, int startChunkZ) {
        Random random = new Random(largeFeatureSeed(worldSeed, startChunkX, startChunkZ));
        int radius = MIN_RADIUS + random.nextInt(MAX_RADIUS - MIN_RADIUS + 1);
        int depth = MIN_DEPTH + random.nextInt(MAX_DEPTH - MIN_DEPTH + 1);
        random.nextInt(); // the piece's generation depth; unused here
        int noiseSeed = random.nextInt();
        return new LakeParams(startChunkX, startChunkZ, (startChunkX << 4) + 8, (startChunkZ << 4) + 8,
                radius, depth, noiseSeed);
    }

    /** The lakes whose start chunk is close enough to reach this chunk. */
    static List<LakeParams> candidatesNear(long worldSeed, int chunkX, int chunkZ) {
        int minRegionX = Math.floorDiv(chunkX - REACH_CHUNKS, SPACING);
        int maxRegionX = Math.floorDiv(chunkX + REACH_CHUNKS, SPACING);
        int minRegionZ = Math.floorDiv(chunkZ - REACH_CHUNKS, SPACING);
        int maxRegionZ = Math.floorDiv(chunkZ + REACH_CHUNKS, SPACING);

        List<LakeParams> out = new ArrayList<>(4);
        for (int regionX = minRegionX; regionX <= maxRegionX; regionX++) {
            for (int regionZ = minRegionZ; regionZ <= maxRegionZ; regionZ++) {
                StartChunk start = potentialStart(worldSeed, regionX, regionZ);
                if (Math.abs(start.x() - chunkX) > REACH_CHUNKS
                        || Math.abs(start.z() - chunkZ) > REACH_CHUNKS) {
                    continue;
                }
                out.add(lakeAt(worldSeed, start.x(), start.z()));
            }
        }
        return out;
    }

    /**
     * Vanilla's {@code WorldgenRandom#setDecorationSeed(worldSeed, blockX, blockZ)}.
     *
     * <p>Delegates to {@link DecorationRandom}, which reproduces the real
     * {@code WorldgenRandom(new XoroshiroRandomSource(...))} stream bit for bit (verified against NMS on
     * 252 cases, anchored in {@code DecorationRandomTest}). It is deliberately <b>not</b> hand-written
     * here: the decoration seed is produced by drawing two {@code nextLong}s from an <em>Xoroshiro</em>
     * source, so a 48-bit-LCG reimplementation would silently produce a different seed -- and the tests
     * would then pin the wrong number.
     */
    static long decorationSeed(long worldSeed, int blockX, int blockZ) {
        return DecorationRandom.decorationSeed(worldSeed, blockX, blockZ);
    }

    /**
     * The value {@code setFeatureSeed(decorationSeed, index, step)} feeds to {@code setSeed}:
     * {@code decorationSeed + index + 10000L * step}.
     *
     * <p>The additive step is vanilla's own arithmetic (26.3 {@code WorldgenRandom#setFeatureSeed}
     * bytecode); the {@code decorationSeed} half comes from {@link #decorationSeed}, i.e. from the shared
     * Xoroshiro reproduction.
     *
     * @param structureIndex the lake's step-4 registry slot; see {@link #structureIndexFor}
     */
    static long structurePhaseSeed(long worldSeed, int chunkX, int chunkZ, int structureIndex) {
        return decorationSeed(worldSeed, chunkX << 4, chunkZ << 4)
                + structureIndex + 10000L * SURFACE_STRUCTURES_STEP;
    }

    /**
     * The shore's random: the decoration phase's own stream for this chunk and this structure slot.
     *
     * <p>{@code DecorationRandom.forFeature(decorationSeed, index, step)} mirrors
     * {@code WorldgenRandom#setFeatureSeed}, and the instance it returns is the Xoroshiro stream vanilla
     * would hand {@code StructureStart#placeInChunk} -- which is what {@code addVanillaDecorations}
     * builds ({@code new WorldgenRandom(new XoroshiroRandomSource(...))}, bytecode-checked). The structure
     * phase, by contrast, uses {@code new WorldgenRandom(new LegacyRandomSource(...))}
     * ({@code Structure.GenerationContext#makeRandom}), which is why {@link #lakeAt} and
     * {@link #potentialStart} legitimately keep a plain {@link Random}.
     */
    static DecorationRandom shoreRandom(long worldSeed, int chunkX, int chunkZ, int structureIndex) {
        return DecorationRandom.forFeature(decorationSeed(worldSeed, chunkX << 4, chunkZ << 4),
                structureIndex, SURFACE_STRUCTURES_STEP);
    }

    // ---------------------------------------------------------------- the region

    /** What a cell is, reduced to the three distinctions the lake's algorithm makes. */
    enum Kind {
        /** Empty space: upstream's {@code state.isAir()}. */
        AIR,
        /** Solid, non-liquid: what a bowl carves and a shore is skinned onto. */
        GROUND,
        /** A fluid: something the bowl neither seals against nor builds on. */
        LIQUID
    }

    /**
     * Where the lake may read and write. Production is a {@link LimitedRegion}; the tests drive the same
     * code with an in-memory implementation.
     *
     * <p>Symbolic block ids rather than {@link BlockData}: the populator resolves each id once, and a test
     * can assert the id sequence without a server or a CraftEngine pack.
     */
    interface Sink {
        boolean readable(int x, int y, int z);

        Kind kindAt(int x, int y, int z);

        boolean write(int x, int y, int z, String blockId);
    }

    /** The real sink: a region slice, the resolved blocks, and the plates the lake must not erase. */
    private static final class RegionSink implements Sink {
        private final LimitedRegion region;
        private final Map<String, BlockData> blocks;
        private final int lowestY;
        private final int highestY;

        private RegionSink(LimitedRegion region, Map<String, BlockData> blocks, int lowestY,
                           int highestY) {
            this.region = region;
            this.blocks = blocks;
            this.lowestY = lowestY;
            this.highestY = highestY;
        }

        @Override
        public boolean readable(int x, int y, int z) {
            return region.isInRegion(x, y, z);
        }

        @Override
        public Kind kindAt(int x, int y, int z) {
            return kindOf(region.getBlockData(x, y, z).getMaterial());
        }

        /**
         * Refuses four kinds of write: outside this region, outside the band between the bedrock plates,
         * an id the pack does not supply, and anything that would overwrite bedrock. The plates are the
         * one thing a lake must never carve away.
         */
        @Override
        public boolean write(int x, int y, int z, String blockId) {
            if (y < lowestY || y > highestY) {
                return false;
            }
            if (!region.isInRegion(x, y, z)) {
                return false;
            }
            BlockData data = blocks.get(blockId);
            if (data == null && blockId.indexOf('[') > 0) {
                // A property-carrying id whose properties did not resolve: write the plain block rather
                // than nothing.
                data = blocks.get(blockId.substring(0, blockId.indexOf('[')));
            }
            if (data == null) {
                return false;
            }
            if (region.getBlockData(x, y, z).getMaterial() == Material.BEDROCK) {
                return false;
            }
            region.setBlockData(x, y, z, data);
            return true;
        }
    }

    /**
     * The three-way classification of a material.
     *
     * <p>An explicit air set rather than {@link Material#isAir()}: that resolves through {@code Registry},
     * which needs a live server. Everything {@link Material#isSolid()} is ground, anything else is empty
     * space -- upstream's {@code isAir() || canBeReplaced()}. CraftEngine maps custom blocks onto
     * deceiving vanilla materials, so naming materials would miss every BetterNether surface block.
     */
    static Kind kindOf(Material material) {
        if (material == Material.AIR || material == Material.CAVE_AIR
                || material == Material.VOID_AIR) {
            return Kind.AIR;
        }
        if (material == Material.LAVA || material == Material.WATER) {
            return Kind.LIQUID;
        }
        return material.isSolid() ? Kind.GROUND : Kind.AIR;
    }

    // ---------------------------------------------------------------- diagnostics

    private void warnMissingBlocksOnce() {
        if (warnedAboutMissingBlocks) {
            return;
        }
        warnedAboutMissingBlocks = true;
        LOGGER.warning("巨型熔岩湖缺少方块，已跳过：" + missingIds());
    }

    private void warnBaseColumnFailureOnce(IllegalStateException error) {
        if (warnedAboutBaseColumn) {
            return;
        }
        warnedAboutBaseColumn = true;
        LOGGER.log(Level.SEVERE, "巨型熔岩湖无法读取原版 base column，已跳过（后续不再重复报告）："
                + error.getMessage(), error);
    }

    private void warnAboutStructureIndexOnce(String reason) {
        if (warnedAboutStructureIndex) {
            return;
        }
        warnedAboutStructureIndex = true;
        LOGGER.warning("巨型熔岩湖拿不到 step " + SURFACE_STRUCTURES_STEP + " 的结构槽位（" + reason
                + "）：本次只生成岩浆/湖底/围裙等确定性部分，湖岸随机装饰跳过（不编造随机流）。");
    }
}
