package dev.mumu.betternether.terrain;

import dev.mumu.betternether.biome.BiomePlacement;
import dev.mumu.betternether.config.NetherConfig;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.LimitedRegion;
import org.bukkit.generator.WorldInfo;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Places BetterNether's trees: the trunk-and-canopy species that give each biome its third
 * dimension, as opposed to {@link FloraPopulator}'s plants and {@link TerrainFeaturePopulator}'s
 * spikes.
 *
 * <h2>Which species, and in which biomes</h2>
 * The per-biome table ({@link #PROFILES}) is transcribed from the {@code .feature(...)} calls in
 * upstream's {@code world/biomes/*.java}, and each species names the upstream feature it comes
 * from. Nothing here invents a tree in a biome that does not list it: the tree biomes are exactly
 * the twelve upstream gives a tree to, and the other eleven carry an empty profile on purpose so a
 * missing entry can never be mistaken for "no trees yet".
 *
 * <h2>Geometry: transcribed, or explicitly approximated</h2>
 * Upstream builds trees two ways, and this class does not pretend they are the same:
 * <ul>
 *   <li><b>Java features</b> -- {@code gloomwood_tree}, {@code rubeus_tree}, {@code willow_tree},
 *       {@code old_willow_tree}, {@code wart_tree}, {@code anchor_tree}, {@code sakura_tree},
 *       {@code mushroom_fir}. Their shape parameters (trunk height ranges, canopy radii, the wart
 *       tree's square column, the willow's four hanging branches) are transcribed from the feature
 *       class named on each species constant. They are <em>shapes</em>, not the upstream spline
 *       code: the trunk is a line with a lean, not a {@code Spline} through six control points, so
 *       a gloomwood here is straighter than upstream's.</li>
 *   <li><b>NBT 模板（已移交）</b> -- {@code tree_crimson_glowing}, {@code tree_crimson_pine},
 *       {@code big_warped_tree}, {@code old_red_mushroom}, {@code old_brown_mushroom},
 *       {@code patch_big_red_mushroom} 不再由本类近似重建。上游这些是 {@code wover:template}
 *       结构（{@code data/betternether/structure/trees/*.nbt}），现在由 FloraPopulator
 *       解释上游 JSON、按真模板逐个方块放置 —— 两边都放就会在同一位置叠两棵树。
 *       本类只保留**无法**用 JSON 表达的那一个：{@code patch_big_brown_mushroom}
 *       （自定义特征 {@code betternether:big_brown_mushroom}），仍是近似。</li>
 * </ul>
 * Two upstream decorations are dropped rather than faked, because the blocks do not exist in the
 * pack: {@code wall_moss}, {@code wall_mushroom_red}/{@code _brown} (gloomwood, old willow, anchor
 * tree, wart tree) and {@code black_vine}/{@code golden_vine} (the big mushrooms, the warped tree).
 * {@code giant_mold} is registered but its block is absent, so the species degrades to "not placed"
 * and the id is reported through {@link #missingIds()} instead of being stood in for by a cube that
 * would read as the wrong plant.
 *
 * <h2>Bounds: every read and every write is checked</h2>
 * All access goes through {@link Clip}, which asks {@link LimitedRegion#isInRegion} before each
 * read and each write -- {@code getBlockData} throws on a foreign coordinate, so an unchecked read
 * is a crash rather than a glitch.
 *
 * <p>Writes are additionally clipped to <em>this</em> chunk's 16×16 column. A tree whose crown
 * would cross the border is therefore cut at the border instead of reaching into the neighbour.
 * That is a deliberate trade: writing into a neighbour from a populator forces that chunk to be
 * generated and populated while this one is still being built, which is the cascade this project
 * avoids everywhere else. The cost is visible -- a tree standing within a crown's width of a chunk
 * edge loses the part that would have hung over -- and {@link Clip#put} is the single place to
 * change if the trade is ever revisited.
 *
 * <p>Only air is replaced. Terrain, lava and other trees are never overwritten, so a tree can leave
 * a notch where a crown meets a wall, but it can never eat the world.
 *
 * <h2>Determinism: one seed per tree, never a shared stream</h2>
 * {@link #populate} does not draw from the {@link Random} Bukkit passes in. Each attempt derives
 * its own generator from {@link #treeSeed} -- a pure function of the effective world seed, the
 * chunk, the species and the attempt index -- so a tree's geometry is fixed by its own seed and
 * cannot shift because a different species, or a different populator, happened to draw first.
 * Adding a species changes only that species' trees.
 *
 * <p>The seed is mixed with SplitMix64, so neighbouring chunks and adjacent attempt indices do not
 * produce correlated trees.
 */
public final class TreePopulator extends BlockPopulator {
    /**
     * The y band a tree may start in: above the bedrock plate, below the bedrock roof. Same span
     * {@link TerrainFeaturePopulator} uses, and the same one upstream expresses as
     * {@code above_bottom: 4} .. {@code below_top: 4}.
     */
    private static final int MIN_Y = NetherTerrainGenerator.BEDROCK_FLOOR_MAX + 2;
    private static final int MAX_Y = NetherTerrainGenerator.CEILING_Y - 2;

    /**
     * How many neighbouring positions one attempt may probe before giving up.
     *
     * <p>Upstream gets its hit rate from {@code count_on_every_layer} over the whole column, which
     * a {@link BlockPopulator} cannot express. Probing a few adjacent cells per attempt reaches a
     * comparable hit rate for a fixed, small cost -- the same trade
     * {@link TerrainFeaturePopulator#PROBES} makes.
     */
    private static final int PROBES = 3;

    /** Whether a species grows up from the floor or hangs down from the roof. */
    enum Anchor {
        FLOOR,
        CEILING;

        /** The support cell a tree of this anchor stands on or hangs from, given its first cell. */
        int supportY(int y) {
            return this == FLOOR ? y - 1 : y + 1;
        }
    }

    /**
     * The trees this populator can build.
     *
     * <p>Each constant carries the block ids it writes, in the order its {@link #grow} body indexes
     * them, and names the upstream feature it transcribes or approximates. The palette is resolved
     * once, in the constructor; a species with an unresolved id is not placed at all (see
     * {@link #missingIds()}).
     *
     * <p>Package-private so a test can assert the palette and the shape without a server: the
     * {@link Brush} it writes through has no Bukkit types in it.
     */
    enum Species {
        /**
         * 幽暗木 -- transcribes {@code GloomwoodTreeFeature}.
         *
         * <p>Upstream's {@code Size} enum gives clearances 12..24, a dark trunk, and a bleached
         * crown a third of the time ({@code tree_gloomwood_solitary}'s {@code bleached_chance}).
         * The spline trunk becomes a line with one lean; the crown is three ellipsoids.
         */
        GLOOMWOOD(Anchor.FLOOR,
                "betternether:gloomwood_log", "betternether:gloomwood_bark",
                "betternether:gloomwood_dark_log", "betternether:gloomwood_leaves",
                "betternether:gloomwood_bleached_leaves",
                "betternether:gloomwood_transition_log") {
            @Override
            void grow(Brush brush, Random random, int x, int y, int z) {
                final int LOG = 0, BARK = 1, DARK = 2, LEAVES = 3, BLEACHED = 4, TRANSITION = 5;
                int height = 12 + random.nextInt(13);
                boolean bleached = random.nextInt(3) == 0;
                int leanX = 0;
                int leanZ = 0;
                for (int i = 0; i < height; i++) {
                    if (i == height / 3) {
                        leanX = random.nextInt(3) - 1;
                        leanZ = random.nextInt(3) - 1;
                    }
                    int palette = i < 2 ? DARK : i > height - 3 ? TRANSITION : LOG;
                    brush.put(x + leanX, y + i, z + leanZ, palette);
                }
                int crownX = x + leanX;
                int crownZ = z + leanZ;
                int top = y + height - 1;
                int leaves = bleached ? BLEACHED : LEAVES;
                blob(brush, random, crownX, top, crownZ, 3 + random.nextInt(2), 2, leaves, 2);
                blob(brush, random, crownX, top - 4, crownZ, 4 + random.nextInt(2), 1, leaves, 3);
                brush.put(x, y, z, DARK);
                for (int i = 0; i < 4; i++) {
                    int dx = random.nextInt(3) - 1;
                    int dz = random.nextInt(3) - 1;
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    brush.put(x + dx, y, z + dz, DARK);
                    brush.put(x + dx, y - 1, z + dz, BARK);
                }
            }
        };

        private final Anchor anchor;
        private final List<String> ids;

        Species(Anchor anchor, String... ids) {
            this.anchor = anchor;
            this.ids = List.of(ids);
        }

        Anchor anchor() {
            return anchor;
        }

        /** The block ids this species writes, in palette-index order. */
        List<String> ids() {
            return ids;
        }

        /**
         * Builds the tree whose first cell is {@code (x, y, z)} -- the cell just above the support
         * for a {@link Anchor#FLOOR} tree, just below it for a {@link Anchor#CEILING} one.
         *
         * <p>Every write goes through {@link Brush#put}, which decides whether the cell may be
         * replaced, so a shape may describe cells that fall outside the region, inside terrain or
         * into a neighbour chunk and simply lose them.
         */
        abstract void grow(Brush brush, Random random, int x, int y, int z);

        /** The dome two mushroom species share; {@code cap} is the palette index of the cap block. */
        static void mushroom(Brush brush, Random random, int x, int y, int z, int cap) {
            final int STEM = 0, LIGHT = 2;
            int height = 4 + random.nextInt(4);
            for (int i = 0; i < height; i++) {
                brush.put(x, y + i, z, STEM);
            }
            int top = y + height;
            int radius = 3 + random.nextInt(2);
            disc(brush, random, x, top, z, radius, cap, 1);
            disc(brush, random, x, top + 1, z, radius - 1, cap, 2);
            disc(brush, random, x, top + 2, z, Math.max(1, radius - 2), cap, 2);
            brush.put(x, top + 1, z, LIGHT);
        }
    }

    /**
     * Attempts per chunk, per biome, keyed by the 23 {@link dev.mumu.betternether.biome.NetherBiomes}
     * keys.
     *
     * <p>Transcribed from upstream's {@code .feature(...)} calls; the count is the flattened
     * per-chunk budget of that feature's placement modifiers ({@code count_on_every_layer} ×
     * {@code rarity_filter}), reduced to what a populator can spend. Every entry is traceable:
     * <ul>
     *   <li>{@code crimson_glowing_woods} -- 空：{@code tree_crimson_glowing} 现在由
     *       FloraPopulator 按上游模板放置；</li>
     *   <li>{@code crimson_pinewood} -- 空：同上（{@code tree_crimson_pine}）；</li>
     *   <li>{@code gloomwood} -- {@code tree_gloomwood}, {@code _edge}, {@code _solitary}, which
     *       share one noise field upstream and are one density here;</li>
     *   <li>{@code nether_jungle} -- {@code tree_rubeus};</li>
     *   <li>{@code nether_mushroom_forest} -- {@code tree_mushroom_fir},
     *       {@code patch_big_brown_mushroom}（其余两个已由 JSON 解释）；</li>
     *   <li>{@code nether_swampland} -- {@code tree_willow};</li>
     *   <li>{@code old_fungiwoods} -- {@code patch_big_brown_mushroom}（{@code old_red_mushroom} /
     *       {@code old_brown_mushroom} / {@code patch_big_red_mushroom} 已由 JSON 解释）；</li>
     *   <li>{@code old_swampland} -- {@code tree_old_willow}, {@code tree_willow};</li>
     *   <li>{@code old_warped_woods} -- 空：{@code big_warped_tree} 由 FloraPopulator 按模板放置；</li>
     *   <li>{@code upside_down_forest} -- {@code anchor_tree}, {@code tree_sakura} (plus the
     *       branch and root features, which are part of the same tree here);</li>
     *   <li>{@code upside_down_forest_cleared} -- {@code anchor_tree_sparse};</li>
     *   <li>{@code wart_forest} -- {@code tree_wart}.</li>
     * </ul>
     * The other eleven biomes list no tree upstream, so their profile is empty on purpose: a missing
     * key would be indistinguishable from an unauthored one in
     * {@link #unauthoredBiomes}.
     */
    private static final Map<String, Map<Species, Integer>> PROFILES = new LinkedHashMap<>();

    private static void put(String biome, Map<Species, Integer> trees) {
        PROFILES.put(biome, trees);
    }

    static {
        // ⚠ 已知**无人承担**的一条：`betternether:old_willow_tree`（placed_feature
        // `tree_old_willow`）现在既不在 worldgen 支持集里（UpstreamSimpleTreeFeatures 故意不声明，
        // 它需要 WriteZone#fitRadius / decorationBounds / 双层壳 crown / wallPlants），也不再有
        // 本类的近似 species（原 OLD_WILLOW 已删）。结果是 old_swampland 少掉这一类古柳。
        // 两条出路：(1) 由树 agent 移植 OldWillowTree；(2) 显式恢复本类近似并接受它不再是
        // JSON 调度。**不要**为了「表看起来完整」而悄悄加回去 —— 那正是双放/近似的老问题。
        //
        // 树已几乎全部迁移到 worldgen JSON 调度（bnlib/worldgen.py 分类 → WorldgenLoader 编译 →
        // UpstreamSimpleTreeFeatures / UpstreamComplexTreeFeatures 提供形状）。本类只剩一个物种：
        // gloomwood —— 它的三条 placed_feature 带 {@code wover:noise_filter}，该修饰符尚未实现
        // （需要 NMS RandomState 的噪声场，语义已从 wover release/26.3 源码核实）。noise_filter
        // 一旦落地，本类即可整体删除。
        put("bone_reef", Map.of());
        put("crimson_glowing_woods", Map.of());
        put("crimson_pinewood", Map.of());
        put("flooded_deltas", Map.of());
        // gloomwood 也不再由本类放置：wover:noise_filter 已实现（WorldView#noise →
        // NMS RandomState），它的三条 placed_feature 现在由 worldgen JSON 调度。
        put("gloomwood", Map.of());
        put("gravel_desert", Map.of());
        put("magma_land", Map.of());
        put("nether_grasslands", Map.of());
        put("nether_jungle", Map.of());
        put("nether_mushroom_forest", Map.of());
        put("nether_mushroom_forest_edge", Map.of());
        put("nether_swampland", Map.of());
        put("nether_swampland_terraces", Map.of());
        put("old_fungiwoods", Map.of());
        put("old_swampland", Map.of());
        put("old_warped_woods", Map.of());
        put("poor_nether_grasslands", Map.of());
        put("soul_plain", Map.of());
        put("sulfuric_bone_reef", Map.of());
        put("upside_down_forest", Map.of());
        put("upside_down_forest_cleared", Map.of());
        put("wart_forest", Map.of());
        put("wart_forest_edge", Map.of());
    }

    /**
     * The largest density any biome asks for, per species: the per-chunk attempt budget a species
     * gets, thinned down for biomes that want it less often. Computed once, so the hot path is a
     * lookup rather than a scan of the table.
     */
    private static final Map<Species, Integer> PEAK = new EnumMap<>(Species.class);

    static {
        for (Map<Species, Integer> profile : PROFILES.values()) {
            for (Map.Entry<Species, Integer> entry : profile.entrySet()) {
                PEAK.merge(entry.getKey(), entry.getValue(), Math::max);
            }
        }
    }

    private final NetherConfig config;
    private final BiomePlacement placement;
    /** Resolved palette per species; {@code null} when any of its ids is missing. */
    private final Map<Species, BlockData[]> palettes = new EnumMap<>(Species.class);
    /** Ids that did not resolve, reported once so a pack gap is visible. */
    private final List<String> missing = new ArrayList<>();

    /**
     * @param config    supplies the seed override ({@link NetherConfig#seedFor}); it holds no tree
     *                  density setting, so nothing else here reads it
     * @param placement the same layout the surface pass and the other populators use, so a tree
     *                  lands in the biome whose floor is under it
     */
    public TreePopulator(NetherConfig config, BiomePlacement placement) {
        this.config = config;
        this.placement = placement;
        for (Species species : Species.values()) {
            List<String> ids = species.ids();
            BlockData[] palette = new BlockData[ids.size()];
            boolean complete = true;
            for (int i = 0; i < ids.size(); i++) {
                BlockData data = CraftEngineLookup.lookup(ids.get(i));
                palette[i] = data;
                if (data == null) {
                    complete = false;
                    if (!missing.contains(ids.get(i))) {
                        missing.add(ids.get(i));
                    }
                }
            }
            // A species is available only when its whole palette resolved. Placing half a tree, or
            // standing a cube in for a missing plant, would be a silent wrong world; the id list in
            // missingIds() is the report instead.
            palettes.put(species, complete ? palette : null);
        }
    }

    /** The pack ids this populator wanted and did not get; empty on a complete pack. */
    public List<String> missingIds() {
        return List.copyOf(missing);
    }

    /** Whether every species' palette resolved, i.e. whether any tree can be placed at all. */
    public boolean complete() {
        return missing.isEmpty();
    }

    /**
     * The biome keys in {@code allBiomeKeys} that have no profile here.
     *
     * <p>Same contract as {@link FloraPopulator#unauthoredBiomes}: the table is append-only, and a
     * biome added to {@link dev.mumu.betternether.biome.NetherBiomes} without an entry here would
     * generate with no trees and no other symptom. Nothing calls this yet; it exists so a coverage
     * check has one call to make.
     */
    public static List<String> unauthoredBiomes(List<String> allBiomeKeys) {
        List<String> missing = new ArrayList<>();
        for (String key : allBiomeKeys) {
            if (!PROFILES.containsKey(key)) {
                missing.add(key);
            }
        }
        return missing;
    }

    /** The species a biome grows, and how often. Empty for the eleven biomes upstream gives no tree. */
    static Map<Species, Integer> profileOf(String biome) {
        return PROFILES.getOrDefault(biome, Map.of());
    }

    /**
     * The tree profile of the biome covering a block position.
     *
     * <p>Takes a {@code y} because the answer depends on it: the ceiling band's biome is only
     * visible through {@code keyAt(x, y, z)}, which is what puts an anchor tree in the upside-down
     * forest rather than in the floor's biome. One call per attempt, never per block.
     *
     * @return the biome's tree table, or {@code null} when no biome covers the position
     */
    static Map<Species, Integer> profileAt(BiomePlacement placement, int x, int y, int z) {
        return PROFILES.get(placement.keyAt(x, y, z));
    }

    /**
     * The seed one tree's generator is built from.
     *
     * <p>SplitMix64 over the effective world seed, the chunk, the species and the attempt index.
     * Pure, so the same world always grows the same tree at the same place -- and because nothing
     * else feeds it, adding a species or a populator cannot move a tree that already existed.
     */
    static long treeSeed(long worldSeed, int chunkX, int chunkZ, Species species, int attempt) {
        long h = mix(worldSeed + 0x9E3779B97F4A7C15L);
        h = mix(h ^ ((long) chunkX << 32 ^ (chunkZ & 0xFFFFFFFFL)));
        h = mix(h ^ ((long) species.ordinal() + 1) * 0xD1B54A32D192ED03L);
        return mix(h + attempt);
    }

    private static long mix(long z) {
        z += 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    @Override
    public void populate(@NotNull WorldInfo worldInfo, @NotNull Random random, int chunkX, int chunkZ,
                         @NotNull LimitedRegion region) {
        // `random` is deliberately unused: every tree below derives its own generator from
        // treeSeed, so a tree's shape never depends on how many draws another feature made first.
        long worldSeed = config.seedFor(worldInfo.getSeed());
        Region view = of(region);

        for (Species species : Species.values()) {
            BlockData[] palette = palettes.get(species);
            int budget = PEAK.getOrDefault(species, 0);
            if (palette == null || budget == 0) {
                continue;
            }
            // The palette is resolved per species, so the sink -- not the clip -- is what turns an
            // index into a block. That keeps Clip free of Bukkit types and therefore testable. The
            // range check is defensive: every shape is pinned inside its own palette by
            // TreePopulatorTest, and a future shape that slipped out would rather place nothing
            // than abort a chunk's generation with an ArrayIndexOutOfBounds.
            Sink sink = (x, y, z, index) -> {
                if (index >= 0 && index < palette.length) {
                    region.setBlockData(x, y, z, palette[index]);
                }
            };
            for (int attempt = 0; attempt < budget; attempt++) {
                attempt(species, sink, view, new Random(
                        treeSeed(worldSeed, chunkX, chunkZ, species, attempt)),
                        chunkX, chunkZ);
            }
        }
    }

    /**
     * One attempt at one tree: find a supported, open cell, ask the biome at that support whether it
     * wants this species at this density, then build.
     *
     * <p>The biome is read at the support cell -- the block the tree stands on, or the one it hangs
     * from -- because that is the height the species belongs to. Reading the column at ground level
     * instead would put an anchor tree in the floor's biome, which is the bug
     * {@link TerrainFeaturePopulator} documents for its own features.
     */
    private void attempt(Species species, Sink sink, Region view, Random random,
                         int chunkX, int chunkZ) {
        Clip clip = new Clip(view, sink, chunkX, chunkZ);
        int startY = MIN_Y + random.nextInt(MAX_Y - MIN_Y + 1);

        for (int probe = 0; probe < PROBES; probe++) {
            int x = (chunkX << 4) + random.nextInt(16);
            int z = (chunkZ << 4) + random.nextInt(16);
            int y = startY + random.nextInt(3) - 1;
            int supportY = species.anchor().supportY(y);

            if (!clip.air(x, y, z) || !clip.support(x, supportY, z)) {
                continue;
            }
            Map<Species, Integer> profile = profileAt(placement, x, supportY, z);
            if (profile == null) {
                continue;
            }
            int budget = PEAK.getOrDefault(species, 0);
            int density = profile.getOrDefault(species, 0);
            // Thin a shared budget down to this biome's density; the ratio is in (0, 1], so the
            // densest biome keeps every attempt.
            if (density <= 0 || budget <= 0 || random.nextInt(budget) >= density) {
                return;
            }
            species.grow(clip, random, x, y, z);
            return;
        }
    }

    /**
     * Adapts a {@link LimitedRegion} to the three questions the shapes ask.
     *
     * <p>This is the only place in the class that touches a {@link Material}, and the support rule
     * lives here rather than in {@link Clip} for one reason: {@code Material} cannot be initialised
     * without a running server, so a {@code Clip} that named it could not be exercised by a unit
     * test. The rule itself is the same one {@link TerrainFeaturePopulator} uses.
     */
    private static Region of(LimitedRegion region) {
        return new Region() {
            @Override
            public boolean isInRegion(int x, int y, int z) {
                return region.isInRegion(x, y, z);
            }

            @Override
            public boolean isAir(int x, int y, int z) {
                return region.getBlockData(x, y, z).getMaterial().isAir();
            }

            @Override
            public boolean isSupport(int x, int y, int z) {
                // "not air, not lava, not bedrock" rather than a block list, for the reason
                // FloraPopulator documents: CraftEngine maps custom blocks onto deceiving vanilla
                // materials, so naming materials would miss every BetterNether surface block.
                Material material = region.getBlockData(x, y, z).getMaterial();
                return !material.isAir() && material != Material.LAVA && material != Material.BEDROCK;
            }
        };
    }

    /**
     * The block view a tree writes through.
     *
     * <p>Deliberately Bukkit-free: the shapes are pure geometry, and the test suite has no server to
     * build {@link BlockData} with. Palette indices keep them independent of the block registry;
     * {@link Clip} is the only implementation that touches a region.
     */
    interface Brush {
        /** Whether this cell is air and inside the region. */
        boolean air(int x, int y, int z);

        /** Whether this cell is solid ground a tree may stand on or hang from. */
        boolean support(int x, int y, int z);

        /** Writes palette index {@code index} if the cell may be replaced; returns whether it wrote. */
        boolean put(int x, int y, int z, int index);
    }

    /**
     * The region questions a tree asks, and the write it makes.
     *
     * <p>Bukkit-free on purpose: the shapes are pure geometry, and the test suite has no server to
     * build {@link Material} or {@link BlockData} with. {@link #of} is the only adapter, and it is
     * the only place in this class that names a {@link Material}.
     */
    interface Region {
        boolean isInRegion(int x, int y, int z);

        /** Whether this cell is air. Only called after {@link #isInRegion} said yes. */
        boolean isAir(int x, int y, int z);

        /** Whether this cell is ground a tree may stand on or hang from. */
        boolean isSupport(int x, int y, int z);
    }

    /** Turns a species' palette index into a block and writes it. The adapter owns the palette. */
    interface Sink {
        void set(int x, int y, int z, int index);
    }

    /**
     * The only place that decides whether a write happens.
     *
     * <p>Three rules, enforced here rather than by convention at each call site:
     * <ol>
     *   <li>{@link Region#isInRegion} is asked before every read and every write -- an unchecked read
     *       of a {@link LimitedRegion} throws rather than returning something wrong;</li>
     *   <li>a write must fall in this chunk's own 16×16 column, so no populator run ever writes into
     *       a neighbour (see the class javadoc for the trade this makes);</li>
     *   <li>a write must land in air, so terrain, lava and other trees are never overwritten.</li>
     * </ol>
     */
    static final class Clip implements Brush {
        private final Region region;
        private final Sink sink;
        private final int minX;
        private final int maxX;
        private final int minZ;
        private final int maxZ;

        Clip(Region region, Sink sink, int chunkX, int chunkZ) {
            this.region = region;
            this.sink = sink;
            this.minX = chunkX << 4;
            this.maxX = minX + 15;
            this.minZ = chunkZ << 4;
            this.maxZ = minZ + 15;
        }

        @Override
        public boolean air(int x, int y, int z) {
            return region.isInRegion(x, y, z) && region.isAir(x, y, z);
        }

        @Override
        public boolean support(int x, int y, int z) {
            if (y <= NetherTerrainGenerator.BEDROCK_FLOOR_MAX
                    || y >= NetherTerrainGenerator.CEILING_Y) {
                return false;
            }
            return region.isInRegion(x, y, z) && region.isSupport(x, y, z);
        }

        @Override
        public boolean put(int x, int y, int z, int index) {
            if (x < minX || x > maxX || z < minZ || z > maxZ) {
                return false;
            }
            if (y <= NetherTerrainGenerator.BEDROCK_FLOOR_MAX
                    || y >= NetherTerrainGenerator.CEILING_Y) {
                return false;
            }
            if (!region.isInRegion(x, y, z) || !region.isAir(x, y, z)) {
                return false;
            }
            sink.set(x, y, z, index);
            return true;
        }
    }

    /**
     * A horizontal disc of radius {@code radius} at {@code y}.
     *
     * <p>{@code thin} is a one-in-N keep chance per cell, which is what keeps a canopy from becoming
     * a solid plate: 1 fills, 2 halves, 3 keeps a third.
     */
    private static void disc(Brush brush, Random random, int cx, int y, int cz, int radius,
                             int index, int thin) {
        if (radius <= 0) {
            return;
        }
        int r2 = radius * radius;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > r2) {
                    continue;
                }
                if (thin > 1 && random.nextInt(thin) != 0) {
                    continue;
                }
                brush.put(cx + dx, y, cz + dz, index);
            }
        }
    }

    /** A vertical stack of discs, narrowing towards the ends: the crowns' shape. */
    private static void blob(Brush brush, Random random, int cx, int cy, int cz, int rx, int ry,
                             int index, int thin) {
        for (int dy = -ry; dy <= ry; dy++) {
            double shrink = 1.0 - (double) Math.abs(dy) / (ry + 1.0);
            disc(brush, random, cx, cy + dy, cz, (int) Math.round(rx * shrink), index, thin);
        }
    }
}
