package dev.mumu.betternether.worldgen;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 装饰计划必须与上游 {@code FeatureSorter} 等价，并且**一个 step 里每条 placed feature 只跑一次**。
 *
 * <h2>这里锁的四件事，每一条都对应一个真实事故模式</h2>
 * <ol>
 *   <li><b>同一个 feature 被两个群系列到 → 只跑一次。</b>
 *       旧实现按群系各跑一份，{@code minecraft:biome} 让它在两个群系里各放自己那半边，
 *       「看起来没重复」，但随机流被消费两遍，之后所有特征全部错位 —— 运行期零报错。</li>
 *   <li><b>种子里的 index 是「该 step 最终有序列表的下标」。</b>
 *       原版 {@code StepFeatureData#indexMapping()} 是
 *       {@code Util.createIndexIdentityLookup(features)}，即列表下标；
 *       换成注册序号或 per-biome 下标都会让整条流错开。</li>
 *   <li><b>同群系内的相对顺序不被打乱。</b>
 *       {@code FeatureSorter} 的链约束保证「坑先于岩浆斑」这类同 step 依赖成立；
 *       链上的顶点顺序若被反掉，长出来的东西会因为地面还没被改而全灭。</li>
 *   <li><b>缺 step 元数据必须报错，不能默认成 0。</b>
 *       step 参与 {@code 10000L * step}，猜一个等于换一条随机流。</li>
 * </ol>
 */
class DecorationSchedulerTest {

    /**
     * 两个群系都列了同一条 placed feature：计划里它只出现一次，两个群系都指向同一个 index。
     *
     * <p>这正是「每唯一 placed feature 每区块一次」的数据基础：区块的群系集合取并集后，
     * step 里被取到的 index 集合天然去重。
     */
    @Test
    void sharedFeatureRunsOncePerStep() {
        Map<String, List<CompiledFeature>> data = new LinkedHashMap<>();
        data.put("biome_a", List.of(feature("betternether:shared", 9, 0),
                feature("betternether:only_a", 9, 1)));
        data.put("biome_b", List.of(feature("betternether:shared", 9, 0)));

        DecorationScheduler.Plan plan = DecorationScheduler.Plan.compile(data);
        DecorationScheduler.StepPlan step9 = plan.step(9);

        assertEquals(2, step9.size(), "step 9 里应只有 2 条**唯一** placed feature");
        assertEquals(1, occurrences(step9, "betternether:shared"),
                "跨群系共用的 placed feature 在计划里只能出现一次");
        assertTrue(step9.biomesOf("betternether:shared").containsAll(List.of("biome_a", "biome_b")),
                "共用同一条特征的两个群系都要记在它名下，否则 biome 过滤会把其中一半位置判掉");

        // 两个群系取并集后的 index 集合：shared 只出现一次
        int[] fromA = step9.indicesOfBiome("biome_a");
        int[] fromB = step9.indicesOfBiome("biome_b");
        assertEquals(2, union(fromA, fromB).size(),
                "两个群系的 index 并集必须是 2 个，重复取会让同一条特征跑两遍");
    }

    /**
     * 种子里的 index 是「该 step 最终有序列表的下标」。
     *
     * <p>构造一个必须重排的例子：群系 A 认为 {@code x} 在 {@code y} 前，群系 B 认为
     * {@code y} 在 {@code z} 前。链约束要求三者保持这个相对顺序，拓扑排序把它们排成
     * {@code x, y, z}，于是 index 分别是 0 / 1 / 2 —— 而不是它们各自群系里的原始位置。
     */
    @Test
    void indexIsPositionInTheFinalStepList() {
        Map<String, List<CompiledFeature>> data = new LinkedHashMap<>();
        // A：y 原本在 A 里是第 0 个，但 x 必须排它前面
        data.put("biome_a", List.of(feature("betternether:x", 9, 0),
                feature("betternether:y", 9, 1)));
        data.put("biome_b", List.of(feature("betternether:y", 9, 0),
                feature("betternether:z", 9, 1)));

        DecorationScheduler.StepPlan step9 = DecorationScheduler.Plan.compile(data).step(9);

        assertEquals(List.of("betternether:x", "betternether:y", "betternether:z"), ids(step9));
        assertEquals(0, step9.indexOf("betternether:x"));
        assertEquals(1, step9.indexOf("betternether:y"));
        assertEquals(2, step9.indexOf("betternether:z"));
        // y 在 biome_b 里原本是第 0 个：如果实现拿 per-biome 下标当种子 index，这里会等于 0
        assertEquals(1, step9.indexOf("betternether:y"),
                "index 不能退回某群系数组里的原始位置");
    }

    /** 不同 step 各自独立编号：step 1 的 index 从 0 开始，不接在 step 0 后面。 */
    @Test
    void indicesRestartPerStep() {
        Map<String, List<CompiledFeature>> data = new LinkedHashMap<>();
        data.put("biome_a", List.of(feature("betternether:lake_a", 1, 0),
                feature("betternether:lake_b", 1, 1),
                feature("betternether:sandstone", 0, 0)));

        DecorationScheduler.Plan plan = DecorationScheduler.Plan.compile(data);

        assertEquals(1, plan.step(0).size());
        assertEquals(0, plan.step(0).indexOf("betternether:sandstone"));
        assertEquals(2, plan.step(1).size());
        assertEquals(0, plan.step(1).indexOf("betternether:lake_a"));
        assertEquals(1, plan.step(1).indexOf("betternether:lake_b"));
        assertEquals(1, plan.maxStep());
    }

    /** 群系内同 step 的相对顺序必须原样保留（「先挖坑再在坑边长岩浆斑」靠这个）。 */
    @Test
    void withinStepOrderIsPreserved() {
        Map<String, List<CompiledFeature>> data = new LinkedHashMap<>();
        data.put("biome_a", List.of(feature("betternether:pit", 1, 0),
                feature("betternether:magma_blob", 1, 1),
                feature("betternether:terrace", 1, 2)));

        DecorationScheduler.StepPlan step1 = DecorationScheduler.Plan.compile(data).step(1);

        assertEquals(List.of("betternether:pit", "betternether:magma_blob",
                "betternether:terrace"), ids(step1));
    }

    /**
     * 缺 step 归属必须报错。
     *
     * <p>装成 0 会让整条随机流错开，而运行期一切正常 —— 只有这条断言能挡住。
     */
    @Test
    void missingStepFailsLoud() {
        Map<String, List<CompiledFeature>> data = new LinkedHashMap<>();
        data.put("biome_a", List.of(new CompiledFeature("betternether:x",
                List.of(new PlacementModifier.InSquare()),
                new Feature.Custom("test", (world, point) -> true))));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> DecorationScheduler.Plan.compile(data));
        assertTrue(error.getMessage().contains("betternether:x"),
                "错误信息必须点名是哪条特征，实际：" + error.getMessage());
        assertTrue(error.getMessage().contains("step"),
                "错误信息必须说清缺的是 step，实际：" + error.getMessage());
    }

    /** 同一条 placed feature 出现在两个不同 step：上游数据不该这样，必须报出来而不是让它两头跑。 */
    @Test
    void featureInTwoStepsFailsLoud() {
        Map<String, List<CompiledFeature>> data = new LinkedHashMap<>();
        data.put("biome_a", List.of(feature("betternether:x", 4, 0)));
        data.put("biome_b", List.of(feature("betternether:x", 9, 0)));

        assertThrows(IllegalStateException.class, () -> DecorationScheduler.Plan.compile(data));
    }

    /** 空计划是合法的（装载期数据缺失时），但不能把空的 step 当成有内容。 */
    @Test
    void emptyPlanReportsEmpty() {
        DecorationScheduler.Plan plan = DecorationScheduler.Plan.compile(new LinkedHashMap<>());
        assertTrue(plan.isEmpty());
        assertEquals(0, plan.step(9).size());
        assertEquals(-1, plan.featureIndex("betternether:x", 9));
        assertFalse(plan.step(9).biomesOf("betternether:x").iterator().hasNext());
    }

    /**
     * 原版（{@code minecraft:}）条目必须占住它的下标，即使我们放不出它。
     *
     * <p>上游 {@code gloomwood} 的 {@code features[9]} 是
     * {@code [betternether:wart_cap, minecraft:spring_lava,
     * minecraft:brown_mushroom_normal, minecraft:red_mushroom_normal, …, tree_gloomwood, …]}。
     * 若把原版那几项从计划里删掉，{@code tree_gloomwood} 的 index 会从 6 变成 3，
     * 种子随之改变 —— 表现是「树形都对，每棵树都长在别处」。
     */
    @Test
    void vanillaEntriesReserveTheirIndex() {
        Map<String, List<CompiledFeature>> data = new LinkedHashMap<>();
        data.put("gloomwood", List.of(
                feature("betternether:wart_cap", 9, 0),
                CompiledFeature.placeholder("minecraft:spring_lava", 9, 1),
                CompiledFeature.placeholder("minecraft:brown_mushroom_normal", 9, 2),
                CompiledFeature.placeholder("minecraft:red_mushroom_normal", 9, 3),
                feature("betternether:patch_gloomwisp_vine", 9, 4),
                feature("betternether:tree_gloomwood", 9, 5)));

        DecorationScheduler.StepPlan step9 = DecorationScheduler.Plan.compile(data).step(9);

        assertEquals(6, step9.size(), "占位条目也要占住下标");
        assertEquals(0, step9.indexOf("betternether:wart_cap"));
        assertEquals(1, step9.indexOf("minecraft:spring_lava"));
        assertEquals(5, step9.indexOf("betternether:tree_gloomwood"),
                "原版条目被删掉会让后面每条 BN 特征的 index 前移");
        assertTrue(step9.order().get(1).isIndexPlaceholder());
        assertFalse(step9.order().get(0).isIndexPlaceholder());
    }

    // ---------------------------------------------------------------- 键空间与真实写入

    /**
     * 注册表键 → 内部键空间：**只**去本包前缀，原版与外部命名空间原样保留。
     *
     * <p>内部约定用裸名表示本包群系（装载清单、{@code BiomePlacement#keyAt} 都是这么写的），
     * 而真实群系调色板报的是注册表键 {@code betternether:<名>}。少这一步转换，
     * {@code indicesOfBiome} 永远查不到 —— 一个区块里一条本包特征都不会放。
     */
    @Test
    void paletteKeysAreMappedIntoTheInternalBareNameSpace() {
        assertEquals("gloomwood", DecorationScheduler.internalBiomeKey("betternether:gloomwood"));
        assertEquals("minecraft:nether_wastes",
                DecorationScheduler.internalBiomeKey("minecraft:nether_wastes"),
                "原版键本来就是内部键空间里的样子，不能被改名");
        assertEquals("thirdparty:nether_biome",
                DecorationScheduler.internalBiomeKey("thirdparty:nether_biome"),
                "外部命名空间的键必须原样保留");

        assertEquals(Set.of("gloomwood", "minecraft:nether_wastes"),
                DecorationScheduler.internalBiomeKeys(
                        Set.of("betternether:gloomwood", "minecraft:nether_wastes")));
    }

    /**
     * 带前缀的真实调色板必须命中裸名计划，而且那一步要**真的把方块写进世界**。
     *
     * <p>断言的顺序就是事故的顺序：先证明「未转换时 index 一个都查不到」（这是植被全灭的成因），
     * 再证明「转换后 index 查得到」，最后回读被写下的方块 —— 只断言字符串相等不足以说明
     * 世界里真的长了东西。
     */
    @Test
    void prefixedPaletteReachesBarePlanAndWritesBlocks() {
        Map<String, List<CompiledFeature>> data = new LinkedHashMap<>();
        data.put("gloomwood", List.of(new CompiledFeature("betternether:patch_gloomwisp_vine",
                9, 0, List.of(new PlacementModifier.Biome()),
                new Feature.SimpleBlock(new Feature.BlockProvider.Single(
                        FakeWorld.blockData(Material.CRIMSON_ROOTS))))));
        // placement 传 null：本用例只走「计划 → 群系过滤 → 放置」，不进 decorate
        //（那一步需要真实 LimitedRegion，而真实调色板只有真实 WorldGenLevel 才有）。
        DecorationScheduler scheduler = new DecorationScheduler(null, data);
        DecorationScheduler.StepPlan step9 = scheduler.plan().step(9);

        Set<String> palette = Set.of("betternether:gloomwood", "minecraft:nether_wastes");
        assertArrayEquals(new int[0], step9.indicesOfBiome("betternether:gloomwood"),
                "注册表键不是计划里的键：未转换时这条 placed feature 根本不会被选中");
        assertEquals(0, scheduler.indicesForStep(step9, palette).size(),
                "没做转换就等于「一个区块里一条 BN 特征都不放」，这正是植被全灭的成因");

        Set<String> internal = DecorationScheduler.internalBiomeKeys(palette);
        assertEquals(Set.of("gloomwood", "minecraft:nether_wastes"), internal);
        // 计划里没有原版群系，所以那条原版键确实「计划里没有」；关键是本包那条必须命中。
        assertEquals(List.of("minecraft:nether_wastes"), scheduler.unmatchedBiomes(internal),
                "转换后的本包群系必须命中计划，剩下的只该是计划里没有的原版群系");
        assertFalse(scheduler.unmatchedBiomes(internal).contains("gloomwood"));

        TreeSet<Integer> indices = scheduler.indicesForStep(step9, internal);
        assertEquals(1, indices.size(), "转换之后 index 才查得到，而且只查出一条");

        // 只有 (0,0) 这一列有群系与地面：Biome 过滤读的是被装饰坐标自己的群系。
        FakeWorld world = new FakeWorld(0, 127, 7L);
        world.biome("gloomwood");
        world.fill(0, 0, 30, 40, Material.NETHERRACK);

        CompiledFeature feature = step9.order().get(indices.first());
        WorldView saw = new DecorationScheduler.BiomeSetView(world, step9.biomesOf(feature.id()));
        assertTrue(DecorationScheduler.placeCompiled(feature, saw, 0, 41, 0),
                "地面之上的空气应当被放上植物");
        assertEquals(Material.CRIMSON_ROOTS, world.materialAt(0, 41, 0),
                "必须回读到真实写下的方块，而不是只看返回值");
    }

    /**
     * 调色板里混着原版群系时，「本包群系在计划里没有对应键」不能被掩盖。
     *
     * <p>真实调色板里总有原版群系。只要计划里也恰好有一条同名键，「整体不相交」就为假 ——
     * 于是一整片本包群系匹配失败会被一次巧合命中原版键盖掉，日志干净而植物全无。
     */
    @Test
    void vanillaKeyInPaletteDoesNotMaskAMissingCustomBiome() {
        Map<String, List<CompiledFeature>> data = new LinkedHashMap<>();
        data.put("gloomwood", List.of(feature("betternether:patch_gloomwisp_vine", 9, 0)));
        // 计划里也有一条原版键：调色板里那一条会命中，于是「整体不相交」的判断失效。
        data.put("minecraft:nether_wastes", List.of(feature("betternether:wart_cap", 9, 1)));
        DecorationScheduler scheduler = new DecorationScheduler(null, data);

        Set<String> registryKeys = new LinkedHashSet<>(List.of(
                "betternether:gloomwood", "betternether:wart_forest", "minecraft:nether_wastes"));
        Set<String> internal = DecorationScheduler.internalBiomeKeys(registryKeys);
        assertEquals(List.of("wart_forest"), scheduler.unmatchedBiomes(internal),
                "原版键命中不能把这条本包群系的缺口吃掉");

        WorldgenWarnings.reset();
        try {
            scheduler.reportBiomeKeyDrift(registryKeys, internal);
            Set<String> degraded = WorldgenWarnings.degraded();
            assertTrue(degraded.contains("biome-key-space:partial"),
                    "有命中的群系时，缺口的记账必须是「部分」而不是整片故障：" + degraded);
            assertFalse(degraded.contains("biome-key-space"),
                    "不能把单点缺口说成「本包群系全都没有计划」");
        } finally {
            WorldgenWarnings.reset();
        }
    }

    /** 本包群系一个都没命中计划：那才是整片不匹配，要单独一条。 */
    @Test
    void noPackBiomeInPaletteMatchesThePlan() {
        Map<String, List<CompiledFeature>> data = new LinkedHashMap<>();
        data.put("gloomwood", List.of(feature("betternether:patch_gloomwisp_vine", 9, 0)));
        DecorationScheduler scheduler = new DecorationScheduler(null, data);

        Set<String> registryKeys = new LinkedHashSet<>(List.of(
                "betternether:wart_forest", "minecraft:nether_wastes"));
        Set<String> internal = DecorationScheduler.internalBiomeKeys(registryKeys);

        WorldgenWarnings.reset();
        try {
            scheduler.reportBiomeKeyDrift(registryKeys, internal);
            Set<String> degraded = WorldgenWarnings.degraded();
            assertTrue(degraded.contains("biome-key-space"),
                    "本包群系全都没计划时必须是整片告警：" + degraded);
            assertFalse(degraded.contains("biome-key-space:partial"),
                    "整片不匹配不能被降级成「部分缺口」");
        } finally {
            WorldgenWarnings.reset();
        }
    }

    /**
     * 调色板里只有原版群系、且计划里也有它的条目时，一条告警都不该有。
     *
     * <p>这是反噪声的一半：「这块地是原版群系所以没有本包装饰」是正常情形，
     * 每次都点名会让真正的缺口被淹没。
     */
    @Test
    void vanillaOnlyPaletteThatMatchesThePlanIsSilent() {
        Map<String, List<CompiledFeature>> data = new LinkedHashMap<>();
        data.put("gloomwood", List.of(feature("betternether:patch_gloomwisp_vine", 9, 0)));
        data.put("minecraft:nether_wastes", List.of(feature("betternether:wart_cap", 9, 1)));
        DecorationScheduler scheduler = new DecorationScheduler(null, data);

        Set<String> registryKeys = new LinkedHashSet<>(List.of("minecraft:nether_wastes"));
        Set<String> internal = DecorationScheduler.internalBiomeKeys(registryKeys);

        WorldgenWarnings.reset();
        try {
            scheduler.reportBiomeKeyDrift(registryKeys, internal);
            assertTrue(WorldgenWarnings.degraded().isEmpty(),
                    "命中了就不该记账：" + WorldgenWarnings.degraded());
        } finally {
            WorldgenWarnings.reset();
        }
    }

    /**
     * 完整放置链跑在 FakeWorld 上：有支撑才长、没支撑一个方块都不写、相同 seed 逐格一致。
     *
     * <p>「尝试过」与「放成功」是两件事：{@code placeAt} 返回的是**报告成功的位置数**
     * （{@code Pipeline.place} 数的是 {@code feature.place(...) == true}，不是写了几格方块 ——
     * {@code wover:mark_postprocessing} 这类条目成功但一格不写）。谓词把所有位置筛掉时它是 0，
     * {@code Stats.compiledFeatures} 靠这个数，所以这里锁住它 —— 否则「植物没长」会表现为
     * 「放置了一批」，日志与统计都看不出来。
     */
    @Test
    void placementNeedsSupportAndRepeatsIdenticallyForTheSameSeed() {
        // 链：16×16 里随机取点 → 要求脚下就是下界岩（走真实方块身份判定）→ 放一株随机挑的根。
        List<PlacementModifier> chain = List.of(
                new PlacementModifier.InSquare(),
                new PlacementModifier.Is(new Point(0, -1, 0),
                        new BlockPredicates.BlockIds("test:ground",
                                Set.of("minecraft:netherrack"), List.of())));
        Feature plant = new Feature.SimpleBlock(new Feature.BlockProvider.Weighted(
                List.of(FakeWorld.blockData(Material.CRIMSON_ROOTS),
                        FakeWorld.blockData(Material.WARPED_ROOTS)),
                new int[]{1, 1}, 2));
        CompiledFeature patch = new CompiledFeature("betternether:test_patch", 9, 0, chain, plant);

        FakeWorld first = ground(4242L);
        assertTrue(DecorationScheduler.placeCompiled(patch, first, 0, 41, 0),
                "有地面的列上必须真的长出东西");
        List<String> grown = plants(first);
        assertFalse(grown.isEmpty(), "回读：世界里必须有写下去的方块");

        FakeWorld second = ground(4242L);
        DecorationScheduler.placeCompiled(patch, second, 0, 41, 0);
        assertEquals(grown, plants(second),
                "相同 seed、相同链必须逐格一致（重跑不能漂移）");

        // 没有地面（整列空气）：谓词筛掉所有位置 → 不写，也不该被算成「放置成功」。
        FakeWorld hollow = new FakeWorld(0, 127, 4242L);
        assertFalse(DecorationScheduler.placeCompiled(patch, hollow, 0, 41, 0),
                "没有支撑时 placeAt 必须返回 0 —— 这就是「尝试过」与「放成功」的分界");
        assertTrue(plants(hollow).isEmpty(), "没有支撑时一个方块都不许写");
    }

    // ---------------------------------------------------------------- BiomeSetView 转发

    /**
     * {@link DecorationScheduler.BiomeSetView} 的每一条新能力都必须**转发到底层**，并跑出真实结果。
     *
     * <h2>为什么这是一条行为断言，而不是「getter 转发了吗」</h2>
     * {@link WorldView} 的默认实现是降级：{@code fullShape}/{@code netherCityWithin} 返回
     * {@code null}、{@code markForPostProcessing} 只记一条账。所以只要漏转发一条，装饰期那条能力
     * 就**只对本移植条目**悄悄关掉：{@code wover:full_shape} 全判假、避城判定把位置全丢、
     * 后处理标记变空操作 —— 而原版条目走的是原版代码，一点异常都看不出来。
     *
     * <p>所以这里用一条真实放置链把三件事串起来：{@code AvoidNetherCity}（问不到就丢弃位置）、
     * {@code Is(+0,-1,0, full_shape)}（问不到就判假）、特征自己写块前调
     * {@code markForPostProcessing}。任何一条没转发，位置就会消失或方块不会被写出，
     * 而且降级账本里会出现对应的 key。
     */
    @Test
    void biomeSetViewForwardsEveryCapabilityToItsDelegate() {
        WorldgenWarnings.reset();
        try {
            ProbeWorld probe = new ProbeWorld(0, 127, 21L);
            probe.fill(0, 0, 30, 40, Material.NETHERRACK);
            probe.biome("gloomwood");

            List<PlacementModifier> chain = List.of(
                    new PlacementModifier.Biome(),
                    new PlacementModifier.AvoidNetherCity(8, false),
                    new PlacementModifier.Is(new Point(0, -1, 0), new BlockPredicates.FullShape()));
            Feature markAndPlant = new Feature.Custom("test_mark_and_plant", (world, point) -> {
                world.markForPostProcessing(point.x(), point.y(), point.z());
                world.setBlock(point.x(), point.y(), point.z(),
                        FakeWorld.blockData(Material.CRIMSON_ROOTS));
                return true;
            });
            CompiledFeature feature = new CompiledFeature(
                    "betternether:patch_gloomwisp_vine", 9, 0, chain, markAndPlant);

            WorldView saw = new DecorationScheduler.BiomeSetView(probe, Set.of("gloomwood"));
            assertTrue(DecorationScheduler.placeCompiled(feature, saw, 0, 41, 0),
                    "三条能力都转发时才可能放成功（避城丢位置、full_shape 判假都会让这里变 false）");
            assertEquals(Material.CRIMSON_ROOTS, probe.materialAt(0, 41, 0), "回读：植物必须真的写下去");

            assertTrue(probe.fullShapeAsked, "full_shape 必须转发到底层");
            assertTrue(probe.cityAsked, "netherCityWithin 必须转发到底层");
            assertTrue(probe.postProcessAsked, "markForPostProcessing 必须转发到底层");

            Set<String> degraded = WorldgenWarnings.degraded();
            for (String key : List.of("post_process", "modifier:avoid_nether_city",
                    "predicate:full_shape")) {
                assertFalse(degraded.contains(key), "转发之后不该出现降级记录 " + key + "：" + degraded);
            }
        } finally {
            WorldgenWarnings.reset();
        }
    }

    // ---------------------------------------------------------------- 完整 decorate

    /**
     * 真正跑一遍 {@code decorate}：带命名空间的调色板 → 裸名计划 → 放置 → 回读方块。
     *
     * <p>要点有三：
     * <ul>
     *   <li><b>注入假会话</b>（包内重载）：生产入口仍然自己 {@code open}，所以「调色板键 → 计划键」
     *       这条接缝可以在没有服务端的类路径上被真实执行，而不是只串 helper；</li>
     *   <li><b>调色板里混着原版键</b>（{@code minecraft:nether_wastes}）：它不该让本包那条消失；</li>
     *   <li><b>会话所有权在调用方</b>：这个重载不许 close，否则真实会话会被关两次。</li>
     * </ul>
     */
    @Test
    void decorateRunsTheBarePlanFromANamespacedPalette() {
        Map<String, List<CompiledFeature>> data = new LinkedHashMap<>();
        data.put("gloomwood", List.of(new CompiledFeature("betternether:patch_gloomwisp_vine",
                9, 0, List.of(new PlacementModifier.Biome()),
                new Feature.SimpleBlock(new Feature.BlockProvider.Single(
                        FakeWorld.blockData(Material.CRIMSON_ROOTS))))));
        // selector 恒返回裸名 "gloomwood"：RegionView#biomeAt 与计划同属内部键空间。
        DecorationScheduler scheduler = new DecorationScheduler(
                new dev.mumu.betternether.biome.BiomePlacement(
                        dev.mumu.betternether.biome.TestClimatePlane.fixed(0.35f, 0.10f),
                        target -> "gloomwood"),
                data);

        FakeWorld world = new FakeWorld(0, 127, 11L);
        FakeSession session = new FakeSession(new LinkedHashSet<>(List.of(
                "betternether:gloomwood", "minecraft:nether_wastes")));

        DecorationScheduler.Stats stats = scheduler.decorate(
                worldInfo(0, 128, 11L), 0, 0, region(world, 11L),
                DecorationScheduler.Phase.FEATURES, null, session,
                (delegate, random, minY, maxY) -> new RegionViewStub(delegate, random, "gloomwood"),
                9);

        assertEquals(1, stats.compiledFeatures(),
                "带前缀的调色板转换后必须真的跑出一条本移植特征（原版键混在里面不影响）");
        assertEquals(Material.CRIMSON_ROOTS, world.materialAt(0, 0, 0),
                "回读：植物必须真的写进区块");
        assertEquals(0, session.closes, "会话所有权在调用方：这个重载不许 close");
    }

    /**
     * 调色板没有任何本包前缀时，{@code decorate} 一条本移植特征都不该跑（这是转换没生效的样子）。
     *
     * <p>与上一条互为对照：同一个计划、同一个世界，只有调色板换成了「另一个命名空间」。
     */
    @Test
    void decoratePlacesNothingWhenThePaletteNamespaceDoesNotMatch() {
        Map<String, List<CompiledFeature>> data = new LinkedHashMap<>();
        data.put("gloomwood", List.of(new CompiledFeature("betternether:patch_gloomwisp_vine",
                9, 0, List.of(new PlacementModifier.Biome()),
                new Feature.SimpleBlock(new Feature.BlockProvider.Single(
                        FakeWorld.blockData(Material.CRIMSON_ROOTS))))));
        DecorationScheduler scheduler = new DecorationScheduler(
                new dev.mumu.betternether.biome.BiomePlacement(
                        dev.mumu.betternether.biome.TestClimatePlane.fixed(0.35f, 0.10f),
                        target -> "gloomwood"),
                data);

        FakeWorld world = new FakeWorld(0, 127, 11L);
        FakeSession session = new FakeSession(new LinkedHashSet<>(List.of("other:gloomwood")));

        DecorationScheduler.Stats stats = scheduler.decorate(
                worldInfo(0, 128, 11L), 0, 0, region(world, 11L),
                DecorationScheduler.Phase.FEATURES, null, session,
                (delegate, random, minY, maxY) -> new RegionViewStub(delegate, random, "gloomwood"),
                9);

        assertEquals(0, stats.compiledFeatures(), "键空间对不上时不该放任何东西");
        assertEquals(Material.AIR, world.materialAt(0, 0, 0));
    }

    // ---------------------------------------------------------------- 工具

    /**
     * 记录「这条能力有没有真的转发到底层」的 WorldView。
     *
     * <p>只覆盖本用例会用到的几问，其余委托给 {@link FakeWorld}；三条新能力必须给出**非 null**
     * 的答案 —— 默认实现返回 {@code null}，那就是「没转发」的样子。
     */
    private static final class ProbeWorld implements WorldView {
        private final FakeWorld world;
        private boolean fullShapeAsked;
        private boolean cityAsked;
        private boolean postProcessAsked;

        private ProbeWorld(int minY, int maxY, long seed) {
            this.world = new FakeWorld(minY, maxY, seed);
        }

        private ProbeWorld fill(int x, int z, int from, int to, Material material) {
            world.fill(x, z, from, to, material);
            return this;
        }

        private ProbeWorld biome(String key) {
            world.biome(key);
            return this;
        }

        private Material materialAt(int x, int y, int z) {
            return world.materialAt(x, y, z);
        }

        @Override
        public Boolean fullShape(int x, int y, int z) {
            fullShapeAsked = true;
            return Boolean.TRUE;
        }

        @Override
        public Boolean netherCityWithin(int x, int z, int reach, boolean includeCave) {
            cityAsked = true;
            return Boolean.FALSE;
        }

        @Override
        public void markForPostProcessing(int x, int y, int z) {
            postProcessAsked = true;
        }

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
            return "unused";
        }

        @Override
        public java.util.Random random() {
            return world.random();
        }
    }

    /**
     * 与生产 {@code RegionView} 同语义的最小视图：方块读写都经过真实 {@code LimitedRegion} 接口。
     *
     * <p>为什么不能直接用 {@code RegionView}：它的方法签名引用 NMS 类型，链接需要服务端 jar，
     * 而单测类路径刻意不含服务端 jar。这里只保留 {@code decorate} 用到的那几问，
     * 并把群系固定成本包裸名（与 {@code RegionView#biomeAt → placement.keyAt} 同一键空间）。
     */
    private static final class RegionViewStub implements WorldView {
        private final org.bukkit.generator.LimitedRegion region;
        private final java.util.Random random;
        private final String biome;

        private RegionViewStub(org.bukkit.generator.LimitedRegion region,
                               java.util.Random random, String biome) {
            this.region = region;
            this.random = random;
            this.biome = biome;
        }

        @Override
        public boolean inRegion(int x, int y, int z) {
            return region.isInRegion(x, y, z);
        }

        @Override
        public int minY() {
            return 0;
        }

        @Override
        public int maxY() {
            return 127;
        }

        @Override
        public boolean isAir(int x, int y, int z) {
            // 直接比 Material.AIR：{@code Material#isAir()} 会去问 Paper 的 RegistryAccess，
            // 单测类路径上没有服务端，那一下就抛。生产 RegionView 用真身是安全的。
            return region.isInRegion(x, y, z)
                    && region.getBlockData(x, y, z).getMaterial() == Material.AIR;
        }

        @Override
        public org.bukkit.block.data.BlockData blockAt(int x, int y, int z) {
            return region.isInRegion(x, y, z) ? region.getBlockData(x, y, z) : null;
        }

        @Override
        public void setBlock(int x, int y, int z, org.bukkit.block.data.BlockData data) {
            if (region.isInRegion(x, y, z)) {
                region.setBlockData(x, y, z, data);
            }
        }

        @Override
        public String biomeAt(int x, int y, int z) {
            return biome;
        }

        @Override
        public String decoratedBiome() {
            return biome;
        }

        @Override
        public java.util.Random random() {
            return random;
        }
    }

    /** 假的原版装饰会话：只交出「调色板 + seed」，结构与原版条目一律不参与。 */
    private static final class FakeSession implements NativeDecorationSession {
        private final Set<String> palette;
        private int closes;

        private FakeSession(Set<String> palette) {
            this.palette = palette;
        }

        @Override
        public long decorationSeed() {
            return 1234L;
        }

        @Override
        public int placeStructures(int step) {
            return 0;
        }

        @Override
        public int placeStructures(int step, int fromIndex, int toIndexExclusive, int virtualSlot) {
            return 0;
        }

        @Override
        public int structureSlot(int step, String structureId) {
            return -1;
        }

        @Override
        public int nextStructureIndex(int step) {
            return 0;
        }

        @Override
        public boolean placeFeature(int step, int index, String placedFeatureId) {
            return false;
        }

        @Override
        public int placeNativeFeatures(int step, DecorationScheduler.Plan plan) {
            return 0;
        }

        @Override
        public Set<String> biomeKeys() {
            return palette;
        }

        @Override
        public void close() {
            closes++;
        }
    }

    /** {@code decorate} 只读 {@code getMinHeight} / {@code getMaxHeight}。 */
    private static org.bukkit.generator.WorldInfo worldInfo(int minY, int maxY, long seed) {
        return (org.bukkit.generator.WorldInfo) java.lang.reflect.Proxy.newProxyInstance(
                FakeWorld.class.getClassLoader(),
                new Class<?>[]{org.bukkit.generator.WorldInfo.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getMinHeight" -> minY;
                    case "getMaxHeight" -> maxY;
                    case "getSeed" -> seed;
                    case "getName" -> "fake";
                    default -> null;
                });
    }

    /**
     * 用动态代理做的 {@link org.bukkit.generator.LimitedRegion}：读写都落在 {@link FakeWorld} 上。
     *
     * <p>只有区域判定与真实语义一致（{@code isInRegion} 判 y 范围），因为放置路径真的会问它。
     */
    private static org.bukkit.generator.LimitedRegion region(FakeWorld world, long seed) {
        org.bukkit.World bukkitWorld = (org.bukkit.World) java.lang.reflect.Proxy.newProxyInstance(
                FakeWorld.class.getClassLoader(), new Class<?>[]{org.bukkit.World.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getSeed" -> seed;
                    case "getName" -> "fake";
                    default -> null;
                });
        return (org.bukkit.generator.LimitedRegion) java.lang.reflect.Proxy.newProxyInstance(
                FakeWorld.class.getClassLoader(),
                new Class<?>[]{org.bukkit.generator.LimitedRegion.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "isInRegion" -> world.inRegion((Integer) args[0], (Integer) args[1],
                            (Integer) args[2]);
                    case "getBlockData" -> world.blockAt((Integer) args[0], (Integer) args[1],
                            (Integer) args[2]);
                    case "setBlockData" -> {
                        world.setBlock((Integer) args[0], (Integer) args[1], (Integer) args[2],
                                (org.bukkit.block.data.BlockData) args[3]);
                        yield null;
                    }
                    case "getWorld" -> bukkitWorld;
                    case "getMinHeight" -> world.minY();
                    case "getMaxHeight" -> world.maxY() + 1;
                    default -> null;
                });
    }

    /** 16×16 的下界岩地面（顶面 y=40），上方全空气。 */
    private static FakeWorld ground(long seed) {
        FakeWorld world = new FakeWorld(0, 127, seed);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                world.fill(x, z, 30, 40, Material.NETHERRACK);
            }
        }
        return world;
    }

    /** y=41 上真正被写下的植物（坐标 + 材质），按坐标排序 —— 用于逐格比较。 */
    private static List<String> plants(FakeWorld world) {
        List<String> found = new ArrayList<>();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                Material material = world.materialAt(x, 41, z);
                if (material == Material.CRIMSON_ROOTS || material == Material.WARPED_ROOTS) {
                    found.add(x + "," + z + "=" + material);
                }
            }
        }
        found.sort(String::compareTo);
        return found;
    }

    private static CompiledFeature feature(String id, int step, int index) {
        return new CompiledFeature(id, step, index, List.of(new PlacementModifier.InSquare()),
                new Feature.Custom("test", (world, point) -> true));
    }

    private static List<String> ids(DecorationScheduler.StepPlan step) {
        return step.order().stream().map(CompiledFeature::id).toList();
    }

    private static int occurrences(DecorationScheduler.StepPlan step, String id) {
        int count = 0;
        for (CompiledFeature feature : step.order()) {
            if (feature.id().equals(id)) {
                count++;
            }
        }
        return count;
    }

    private static java.util.TreeSet<Integer> union(int[] left, int[] right) {
        java.util.TreeSet<Integer> set = new java.util.TreeSet<>();
        for (int value : left) {
            set.add(value);
        }
        for (int value : right) {
            set.add(value);
        }
        return set;
    }
}
