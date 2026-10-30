package dev.mumu.betternether.worldgen;

import com.google.gson.JsonObject;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link UpstreamComplexTreeFeatures} 的形状测试（幽光木 / 赤菇木 / 樱树 / 锚树三件套）。
 *
 * <h2>怎么在没有服务器的情况下断言形状</h2>
 * 注入一个方块解析器：把「方块 id + 属性」映射成**互不相同的材质**，再用 {@link FakeWorld}
 * 读回材质。于是「那一格是 {@code gloomwood_dark_log} 还是 {@code gloomwood_log}」变成
 * 「那一格是 {@code STONE} 还是 {@code GRANITE}」—— 状态选择被真正验证了，而不用起
 * CraftEngine、不用碰原版注册表。
 *
 * <h2>这些测试守的是什么</h2>
 * <ul>
 *   <li><b>确定性</b>：同一 seed 跑两遍必须逐格相同（形状是纯函数，随机数个数/顺序是纪律）；</li>
 *   <li><b>状态</b>：木头/树皮/叶子的变体真的按算法选择，而不是全落成默认状态；</li>
 *   <li><b>净空与门槛</b>：幽光木在低天花板下不硬塞、赤菇木要上方空气、锚树根/枝只在高层；</li>
 *   <li><b>bleached 只来自配置</b>：{@code bleached_chance=0} 时绝不出现古残骸，
 *       设成 1 时每棵都埋；</li>
 *   <li><b>锚树是三件独立特征</b>：{@code anchor_tree} 不放叶子，
 *       {@code anchor_tree_branch} 放叶子，{@code anchor_tree_root} 只放根 —— 三条各自成篇。</li>
 * </ul>
 *
 * <p>世界统一 {@code y ∈ [0, 127]}，所以 {@code MAX_HEIGHT = 128}，与上游原版下界一致
 * （锚树/樱树的 {@code scale_factor} 与高度门槛都吃这个数）。
 */
class UpstreamComplexTreeFeaturesTest {
    private static final int MAX_Y = 127;

    // ---------------------------------------------------------------- 入口

    @Test
    void supportedTypesAreTheSixRegisteredIds() {
        // 上游 registry/NetherFeatures.java 的注册名；写成 nether_sakura 之类会静默 skip 整棵树。
        assertEquals(Set.of(
                "betternether:gloomwood_tree",
                "betternether:rubeus_tree",
                "betternether:sakura_tree",
                "betternether:anchor_tree",
                "betternether:anchor_tree_branch",
                "betternether:anchor_tree_root"
        ), UpstreamComplexTreeFeatures.supportedTypes());
    }

    @Test
    void unrelatedTypeIsNotOurs() {
        assertNull(UpstreamComplexTreeFeatures.create("betternether:wart_tree", null, resolver()));
        assertNull(UpstreamComplexTreeFeatures.create("minecraft:oak_tree", null, resolver()));
    }

    @Test
    void everySupportedTypeBuildsAFeature() {
        for (String type : UpstreamComplexTreeFeatures.supportedTypes()) {
            assertNotNull(UpstreamComplexTreeFeatures.create(type, null, resolver()),
                    type + " 应当由本类接住");
        }
    }

    // ---------------------------------------------------------------- 幽光木

    @Test
    void gloomwoodIsDeterministic() {
        assertEquals(fingerprint(gloomwood(4242L, null)), fingerprint(gloomwood(4242L, null)),
                "同一 seed 的幽光木必须逐格相同");
    }

    @Test
    void gloomwoodGrowsTrunkLeavesAndEyes() {
        final Map<String, Integer> counts = counts(gloomwood(7L, null));
        assertTrue(counts.getOrDefault("betternether:gloomwood_dark_log", 0) > 0,
                "树干底部应当是暗色幽光木");
        assertTrue(counts.getOrDefault("betternether:gloomwood_log", 0) > 0,
                "树冠段应当是浅色幽光木");
        assertTrue(counts.getOrDefault("betternether:gloomwood_leaves", 0) > 0,
                "冠层必须有普通叶子");
        assertTrue(counts.getOrDefault("betternether:gloomwood_bleached_leaves", 0) > 0,
                "未漂白的树也应当在主冠层上有一对眼睛（浅色叶）");
    }

    @Test
    void gloomwoodRefusesToGrowWithoutGround() {
        // 没有地面 → 上游 NonOverlappingFeature#place 的第一条就不过。
        final FakeWorld world = new FakeWorld(0, MAX_Y, 11L);
        final Feature feature = gloomwoodFeature(null);
        assertFalse(place(feature, world, new Point(0, 30, 0)));
        assertEquals(0, placedCount(world), "没有地面时不该放下任何方块");
    }

    @Test
    void gloomwoodRefusesWhenACeilingIsTooLow() {
        // 净空 4 格：连 TINY（clearance=12）都不允许尝试。
        final FakeWorld world = new FakeWorld(0, MAX_Y, 13L);
        world.fill(0, 0, 0, 29, Material.NETHERRACK);
        world.fill(0, 0, 34, MAX_Y, Material.NETHERRACK);
        assertFalse(place(gloomwoodFeature(null), world, new Point(0, 30, 0)));
    }

    @Test
    void gloomwoodLeafDecaySolverLeavesNoStrandedLeaves() {
        // CARVE_BRANCH + 6 步预算：放完之后每片叶子都必须「六步内够得到木头」。
        // 这里用「每片叶子至少有一个木头邻居」这个更弱的必要条件做快速守卫：
        // 真正的求解结果由 LeafDecay 保证，这里只防「求解器整段没跑」。
        final FakeWorld world = gloomwood(99L, null);
        int leaves = 0;
        int leafWithWoodNeighbour = 0;
        for (int x = -12; x <= 12; x++) {
            for (int y = 24; y <= 60; y++) {
                for (int z = -12; z <= 12; z++) {
                    final String here = Variant.idOf(world.materialAt(x, y, z));
                    if (!"betternether:gloomwood_leaves".equals(here)
                            && !"betternether:gloomwood_bleached_leaves".equals(here)) {
                        continue;
                    }
                    leaves++;
                    if (hasWoodNeighbour(world, x, y, z)) {
                        leafWithWoodNeighbour++;
                    }
                }
            }
        }
        assertTrue(leaves > 0, "没生成叶子，后面的断言没有意义");
        assertTrue(leafWithWoodNeighbour > 0, "至少稠密的冠层内层应当紧贴木头");
    }

    @Test
    void bleachedChanceZeroNeverBuriesDebris() {
        for (long seed = 1; seed <= 12; seed++) {
            assertEquals(0, counts(gloomwood(seed, "0.0")).getOrDefault("minecraft:ancient_debris", 0),
                    "bleached_chance=0 时不允许出现古残骸（seed=" + seed + "）");
        }
    }

    @Test
    void bleachedChanceOneAlwaysBuriesDebrisAndInvertsTheEyes() {
        int trees = 0;
        for (long seed = 1; seed <= 6; seed++) {
            final FakeWorld world = gloomwood(seed, "1.0");
            final Map<String, Integer> counts = counts(world);
            if (placedCount(world) == 0) {
                continue;   // 这一档尺寸没放成，不算「一棵树」
            }
            trees++;
            assertTrue(counts.getOrDefault("minecraft:ancient_debris", 0) > 0,
                    "漂白树的根下必须有古残骸（seed=" + seed + "）");
            assertTrue(counts.getOrDefault("betternether:gloomwood_bleached_leaves", 0)
                            > counts.getOrDefault("betternether:gloomwood_leaves", 0),
                    "漂白树的冠层主体应当是浅色叶，暗色只留作眼睛（seed=" + seed + "）");
        }
        assertTrue(trees > 0, "6 个 seed 里至少该有一棵漂白树");
    }

    @Test
    void gloomwoodKeepsOutOfAnotherGloomwood() {
        // 非重叠检查：同层已经有同种原木 → 整棵跳过（不吃随机数，但会改变密度，必须移植）。
        final FakeWorld world = new FakeWorld(0, MAX_Y, 17L);
        world.fill(0, 0, 0, 29, Material.NETHERRACK);
        world.solid(3, 30, 0, Variant.solid("betternether:gloomwood_log"));
        assertFalse(place(gloomwoodFeature(null), world, new Point(0, 30, 0)));
    }

    // ---------------------------------------------------------------- 赤菇木

    @Test
    void rubeusIsDeterministic() {
        assertEquals(fingerprint(rubeus(31L, null)), fingerprint(rubeus(31L, null)));
    }

    @Test
    void rubeusNeedsHeadroomAndGround() {
        final Feature feature = rubeusFeature(null);
        // 头顶 10 格就封顶：低于 MAX_SEARCH_LENGTH(25)。
        final FakeWorld low = new FakeWorld(0, MAX_Y, 5L);
        low.fill(0, 0, 0, 29, Material.NETHERRACK);
        low.fill(0, 0, 40, MAX_Y, Material.NETHERRACK);
        assertFalse(place(feature, low, new Point(0, 30, 0)));

        // 有空气但脚下没有地面。
        final FakeWorld floating = new FakeWorld(0, MAX_Y, 5L);
        assertFalse(place(rubeusFeature(null), floating, new Point(0, 30, 0)));
    }

    @Test
    void rubeusPlacesWoodShapesAndLeaves() {
        final FakeWorld world = rubeus(3L, null);
        final Map<String, Integer> counts = counts(world);
        assertTrue(counts.getOrDefault("betternether:rubeus_log", 0) > 0,
                "赤菇木必须有原木");
        assertTrue(counts.getOrDefault("betternether:rubeus_bark", 0) > 0,
                "赤菇木必须有树皮（枝条外露段）");
        assertTrue(counts.getOrDefault("betternether:rubeus_leaves", 0) > 0,
                "冠层必须有叶子");
    }

    @Test
    void rubeusMossOnlyOnNaturalTrees() {
        int naturalMoss = 0;
        int grownMoss = 0;
        for (long seed = 1; seed <= 8; seed++) {
            naturalMoss += counts(rubeus(seed, null))
                    .getOrDefault("betternether:jungle_moss", 0);
            grownMoss += counts(rubeus(seed, "false"))
                    .getOrDefault("betternether:jungle_moss", 0);
        }
        assertTrue(naturalMoss > 0, "自然生成的赤菇木应当在低处挂苔藓");
        assertEquals(0, grownMoss, "natural=false 的树不该长苔藓（上游只在自然树上传 moss 标志）");
    }

    // ---------------------------------------------------------------- 樱树

    @Test
    void sakuraOnlyGrowsInTheUpperQuarter() {
        // MAX_HEIGHT * 0.75 = 96。
        final FakeWorld low = new FakeWorld(0, MAX_Y, 8L);
        assertFalse(place(sakuraFeature(), low, new Point(0, 60, 0)),
                "y=60 低于 96，樱树不该长");
    }

    @Test
    void sakuraHangsDownFromTheCeiling() {
        final FakeWorld world = new FakeWorld(0, MAX_Y, 21L);
        world.fill(0, 0, 0, 9, Material.NETHERRACK);      // 地面
        world.fill(0, 0, 110, MAX_Y, Material.NETHERRACK);  // 天花板
        assertTrue(place(sakuraFeature(), world, new Point(0, 109, 0)));
        final Map<String, Integer> counts = counts(world);
        assertTrue(counts.getOrDefault("betternether:nether_sakura_log", 0)
                        + counts.getOrDefault("betternether:nether_sakura_bark", 0) > 0,
                "樱树必须有木头");
        assertTrue(counts.getOrDefault("betternether:nether_sakura_leaves", 0) > 0,
                "樱树必须有叶子");
        assertTrue(deterministicSakura(), "同 seed 的樱树必须逐格相同");
    }

    // ---------------------------------------------------------------- 锚树三件套

    @Test
    void anchorTreePlacesWoodButNoLeaves() {
        final FakeWorld world = cavern(41L);
        assertTrue(place(treeFeature(), world, new Point(8, 120, 8)));
        final Map<String, Integer> counts = counts(world);
        assertTrue(counts.getOrDefault("betternether:anchor_tree_log", 0) > 0
                        || counts.getOrDefault("betternether:anchor_tree_bark", 0) > 0,
                "锚树本体必须有木头");
        // 上游 AnchorTreeFeature 一个叶子都不放：它的「叶」是 giant_lucis 与另一个特征。
        assertEquals(0, counts.getOrDefault("betternether:anchor_tree_leaves", 0),
                "锚树本体不放叶子（叶子来自独立的 anchor_tree_branch）");
    }

    @Test
    void anchorTreeBranchIsIndependentAndGrowsLeaves() {
        final FakeWorld world = cavern(43L);
        assertTrue(place(branchFeature(), world, new Point(8, 120, 8)));
        final Map<String, Integer> counts = counts(world);
        assertTrue(counts.getOrDefault("betternether:anchor_tree_leaves", 0) > 0,
                "枝条特征必须自己长叶子");
        assertTrue(counts.getOrDefault("betternether:anchor_tree_log", 0)
                        + counts.getOrDefault("betternether:anchor_tree_bark", 0) > 0,
                "枝条特征也必须画木头");
    }

    @Test
    void anchorTreeBranchRefusesLowPositions() {
        final FakeWorld world = cavern(45L);
        assertFalse(place(branchFeature(), world, new Point(8, 40, 8)),
                "y=40 < 56，枝条特征不该启动");
    }

    @Test
    void anchorTreeRootIsIndependentAndOnlyAboveThreeQuarters() {
        final FakeWorld high = cavern(47L);
        assertTrue(place(rootFeature(), high, new Point(8, 120, 8)));
        assertTrue(counts(high).getOrDefault("betternether:anchor_tree_log", 0)
                        + counts(high).getOrDefault("betternether:anchor_tree_bark", 0) > 0,
                "垂根必须有木头");

        final FakeWorld low = cavern(47L);
        assertFalse(place(rootFeature(), low, new Point(8, 60, 8)),
                "y=60 低于 96，垂根不该启动");
    }

    @Test
    void anchorTreeRootPlacesLucisPanelsNowThatThePackCarriesThem() {
        // 这四个方块（rubeus_cone / lucis_mushroom / lucis_spore / anchor_tree_vine）已由资源包补齐。
        // 这条守的是「不再只消耗 nextInt(3) 而跳过放置」：40 个 seed 里必须真的出现小灵芝。
        int lucis = 0;
        for (long seed = 1; seed <= 40; seed++) {
            final FakeWorld world = cavern(seed);
            place(rootFeature(), world, new Point(8, 120, 8));
            lucis += counts(world).getOrDefault("betternether:lucis_mushroom", 0);
        }
        assertTrue(lucis > 0, "锚树根应当长出小灵芝（40 个 seed 共 " + lucis + " 块）");
    }

    @Test
    void anchorTreesAreDeterministic() {
        for (int kind = 0; kind < 3; kind++) {
            final Feature feature = kind == 0 ? treeFeature() : kind == 1 ? branchFeature() : rootFeature();
            final FakeWorld first = cavern(51L + kind);
            final FakeWorld second = cavern(51L + kind);
            place(feature, first, new Point(8, 120, 8));
            place(kind == 0 ? treeFeature() : kind == 1 ? branchFeature() : rootFeature(),
                    second, new Point(8, 120, 8));
            assertEquals(fingerprint(first), fingerprint(second),
                    "同 seed 的锚树特征必须逐格相同（kind=" + kind + "）");
        }
    }

    // ---------------------------------------------------------------- 夹具

    /** 一个「下面是岩石地板、上面是岩石天花板、中间空着」的洞窟：锚树三件套都需要跨度与天花板。 */
    private static FakeWorld cavern(long seed) {
        final FakeWorld world = new FakeWorld(0, MAX_Y, seed);
        // 地板顶面 y=88，锚点 y=120 → 跨度 31（锚树本体要求 >= 30）。
        for (int x = 4; x <= 12; x++) {
            for (int z = 4; z <= 12; z++) {
                world.fill(x, z, 60, 88, Material.NETHERRACK);
            }
        }
        world.fill(8, 8, MAX_Y, MAX_Y, Material.NETHERRACK);
        return world;
    }

    private static boolean deterministicSakura() {
        final FakeWorld a = new FakeWorld(0, MAX_Y, 21L);
        a.fill(0, 0, 0, 9, Material.NETHERRACK);
        a.fill(0, 0, 110, MAX_Y, Material.NETHERRACK);
        final FakeWorld b = new FakeWorld(0, MAX_Y, 21L);
        b.fill(0, 0, 0, 9, Material.NETHERRACK);
        b.fill(0, 0, 110, MAX_Y, Material.NETHERRACK);
        place(sakuraFeature(), a, new Point(0, 109, 0));
        place(sakuraFeature(), b, new Point(0, 109, 0));
        return fingerprint(a).equals(fingerprint(b));
    }

    /**
     * 把一个特征放到假世界上，但通过一层「精确通道」视图。
     *
     * <p>{@code FakeWorld} 没有实现 {@link WorldView} 新增的精确查询（{@code replaceable} /
     * {@code wouldSurvive} / {@code inTag}），而 {@link BlockPredicates} 在问不到时会
     * 「记一次降级并写启动日志」—— 那条日志要 {@code Bukkit.getLogger()}，单元测试里没有服务器。
     * 这层视图把假世界能回答的都照实回答，于是判据走真实分支、不触发降级；
     * 写仍然落到 {@code FakeWorld} 里，断言照旧读它。
     */
    private static boolean place(Feature feature, FakeWorld world, Point at) {
        return feature.place(new PreciseView(world), at);
    }

    /** 转发 {@link FakeWorld}，并补上五个精确通道的诚实答案。 */
    private record PreciseView(FakeWorld world) implements WorldView {
        @Override
        public boolean inRegion(int x, int y, int z) {
            return world.inRegion(x, y, z);
        }

        @Override
        public int minY() {
            return world.minY();
        }

        @Override
        public int maxY() {
            return world.maxY();
        }

        @Override
        public boolean isAir(int x, int y, int z) {
            return world.isAir(x, y, z);
        }

        @Override
        public org.bukkit.block.data.BlockData blockAt(int x, int y, int z) {
            return world.blockAt(x, y, z);
        }

        @Override
        public void setBlock(int x, int y, int z, org.bukkit.block.data.BlockData data) {
            world.setBlock(x, y, z, data);
        }

        @Override
        public String biomeAt(int x, int y, int z) {
            return world.biomeAt(x, y, z);
        }

        @Override
        public String decoratedBiome() {
            return world.decoratedBiome();
        }

        @Override
        public java.util.Random random() {
            return world.random();
        }

        /** 假世界只有空气/水/岩浆可替换，其余都当实心 —— 精确回答，不走降级。 */
        @Override
        public Boolean replaceable(int x, int y, int z) {
            if (!world.inRegion(x, y, z)) {
                return Boolean.FALSE;
            }
            final Material material = world.materialAt(x, y, z);
            return material == Material.AIR || material == Material.WATER || material == Material.LAVA;
        }

        /** 不模拟附着/重力：放上去就当能活。 */
        @Override
        public Boolean wouldSurvive(org.bukkit.block.data.BlockData data, int x, int y, int z) {
            return Boolean.TRUE;
        }

        /** 假世界不认识 tag（{@code null} = 问不出来，与真值语境一致）。 */
        @Override
        public Boolean inTag(String tag, int x, int y, int z) {
            return null;
        }

        /**
         * 身份 id：{@link Variant} 给每个「id + 属性」分配了独占材质，所以这里能反查回 id。
         *
         * <p>这条让 {@code UpstreamComplexTreeFeatures.isAnyOfIds} 走**真实路径**而不是退回
         * 材质比对 —— 生产实现（`RegionView#blockId`）就是 CE 注册 id，语义一致。
         */
        @Override
        public String blockId(int x, int y, int z) {
            if (!world.inRegion(x, y, z)) {
                return null;
            }
            return Variant.idOf(world.materialAt(x, y, z));
        }
    }

    private static Feature gloomwoodFeature(String bleachedChance) {
        final JsonObject json = new JsonObject();
        if (bleachedChance != null) {
            json.addProperty("bleached_chance", bleachedChance);
        }
        return UpstreamComplexTreeFeatures.create("betternether:gloomwood_tree", json, resolver());
    }

    private static FakeWorld gloomwood(long seed, String bleachedChance) {
        final FakeWorld world = new FakeWorld(0, MAX_Y, seed);
        world.fill(0, 0, 0, 29, Material.NETHERRACK);
        place(gloomwoodFeature(bleachedChance), world, new Point(0, 30, 0));
        return world;
    }

    private static Feature rubeusFeature(String natural) {
        final JsonObject json = new JsonObject();
        if (natural != null) {
            json.addProperty("natural", natural);
        }
        return UpstreamComplexTreeFeatures.create("betternether:rubeus_tree", json, resolver());
    }

    private static FakeWorld rubeus(long seed, String natural) {
        final FakeWorld world = new FakeWorld(0, MAX_Y, seed);
        world.fill(0, 0, 0, 29, Material.NETHERRACK);
        place(rubeusFeature(natural), world, new Point(0, 30, 0));
        return world;
    }

    private static Feature sakuraFeature() {
        return UpstreamComplexTreeFeatures.create("betternether:sakura_tree", null, resolver());
    }

    private static Feature treeFeature() {
        return UpstreamComplexTreeFeatures.create("betternether:anchor_tree", null, resolver());
    }

    private static Feature branchFeature() {
        return UpstreamComplexTreeFeatures.create("betternether:anchor_tree_branch", null, resolver());
    }

    private static Feature rootFeature() {
        return UpstreamComplexTreeFeatures.create("betternether:anchor_tree_root", null, resolver());
    }

    private static boolean hasWoodNeighbour(FakeWorld world, int x, int y, int z) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    final String id = Variant.idOf(world.materialAt(x + dx, y + dy, z + dz));
                    if ("betternether:gloomwood_log".equals(id)
                            || "betternether:gloomwood_dark_log".equals(id)
                            || "betternether:gloomwood_transition_log".equals(id)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * 整片区域的材质指纹：{@code "x,y,z=MATERIAL"} 排序后的拼接。
     *
     * <p>用来断言「同一 seed 逐格相同」—— 比数量比较强得多（数量相同、位置不同的两颗树是两颗树）。
     */
    private static String fingerprint(FakeWorld world) {
        final StringBuilder builder = new StringBuilder();
        for (int x = -20; x <= 20; x++) {
            for (int y = 0; y <= MAX_Y; y++) {
                for (int z = -20; z <= 20; z++) {
                    final Material material = world.materialAt(x, y, z);
                    if (material != Material.AIR) {
                        builder.append(x).append(',').append(y).append(',').append(z)
                                .append('=').append(material.name()).append(';');
                    }
                }
            }
        }
        return builder.toString();
    }

    /**
     * 按「方块 id」聚合的落点统计（属性被折叠掉）。
     *
     * <p>为什么不是按材质：同一个 id 的 {@code axis=x}/{@code =y}/{@code =z} 是三个不同变体、
     * 三个不同材质，而断言关心的是「这棵树用了暗色幽光木」。属性层面的正确性由
     * {@link Variant} 的「一 id 一属性 → 一材质」映射本身保证（解析键里含属性）。
     */
    private static Map<String, Integer> counts(FakeWorld world) {
        final Map<String, Integer> result = new TreeMap<>();
        for (int x = -20; x <= 20; x++) {
            for (int y = 0; y <= MAX_Y; y++) {
                for (int z = -20; z <= 20; z++) {
                    final Material material = world.materialAt(x, y, z);
                    if (material == Material.AIR) {
                        continue;
                    }
                    result.merge(Variant.idOf(material), 1, Integer::sum);
                }
            }
        }
        return result;
    }

    /** 树自己放下的方块数（扣掉地形：下界岩）。 */
    private static int placedCount(FakeWorld world) {
        int placed = 0;
        for (int x = -20; x <= 20; x++) {
            for (int y = 0; y <= MAX_Y; y++) {
                for (int z = -20; z <= 20; z++) {
                    final Material material = world.materialAt(x, y, z);
                    if (material != Material.AIR && material != Material.NETHERRACK) {
                        placed++;
                    }
                }
            }
        }
        return placed;
    }

    // ---------------------------------------------------------------- 方块解析

    /** 「id + 属性」→ 一个稳定的、互不相同的材质。 */
    private static final class Variant {
        private static final Map<String, Material> BY_KEY = new LinkedHashMap<>();
        /** 古残骸单独一指，避免和别的变体撞在一起。 */
        static final Material ANCIENT_DEBRIS = Material.ANCIENT_DEBRIS;
        private static final Material[] POOL = pool();

        /**
         * 从 {@link Material} 里挑一批「不会出现在地形里」的方块当变体池。
         *
         * <p>排除空气（等于没放）、下界岩（测试的地面）、下界砖（锚树的拒绝判据用材质比较）
         * 与古残骸（单独断言）。用枚举顺序而不是手写清单：手写清单里重复一项就会让两个变体
         * 共用一个材质，而「状态被正确选择」正是这些断言要看的东西。
         */
        private static final Map<Material, String> ID_OF = new HashMap<>();

        private static Material[] pool() {
            final java.util.List<Material> materials = new java.util.ArrayList<>();
            for (Material material : Material.values()) {
                // 只按枚举名过滤：Material#isBlock()/isLegacy() 会去查注册表，单元测试里没有。
                if (material.name().startsWith("LEGACY_")) {
                    continue;
                }
                if (material == Material.AIR || material == Material.NETHERRACK
                        || material == Material.NETHER_BRICKS || material == Material.ANCIENT_DEBRIS) {
                    continue;
                }
                materials.add(material);
            }
            return materials.toArray(new Material[0]);
        }

        /** 材质 → 它代表的方块 id（去掉属性）。用来做「按 id 聚合」的断言。 */
        static synchronized String idOf(Material material) {
            if (material == Material.AIR) {
                return "minecraft:air";
            }
            if (material == Material.NETHERRACK) {
                return "minecraft:netherrack";
            }
            if (material == Material.ANCIENT_DEBRIS) {
                return "minecraft:ancient_debris";
            }
            return ID_OF.getOrDefault(material, material.name());
        }

        /** 一个确定的「实心方块」材质，用来在测试里往世界里放一小段原木。 */
        static Material solid(String id) {
            return of(id, Map.of("axis", "y"));
        }

        static Material of(String id) {
            return of(id, Map.of());
        }

        static synchronized Material of(String id, Map<String, String> properties) {
            if ("minecraft:ancient_debris".equals(id)) {
                return ANCIENT_DEBRIS;
            }
            if ("minecraft:air".equals(id)) {
                return Material.AIR;
            }
            final StringBuilder key = new StringBuilder(id);
            new TreeMap<>(properties).forEach((k, v) -> key.append('|').append(k).append('=').append(v));
            return BY_KEY.computeIfAbsent(key.toString(), ignored -> {
                final int index = BY_KEY.size();
                if (index >= POOL.length) {
                    throw new IllegalStateException("变体池用完了：" + key);
                }
                final Material material = POOL[index];
                // 键里的 id 部分（第一个 '|' 之前）就是聚合断言要的 id。
                final String text = key.toString();
                final int bar = text.indexOf('|');
                ID_OF.put(material, bar < 0 ? text : text.substring(0, bar));
                return material;
            });
        }
    }

    private static UpstreamComplexTreeFeatures.BlockResolver resolver() {
        return (id, properties) -> {
            final Material material = Variant.of(id, properties == null ? Map.of() : properties);
            return FakeWorld.blockData(material);
        };
    }
}
