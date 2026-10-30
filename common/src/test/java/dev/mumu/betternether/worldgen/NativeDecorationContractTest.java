package dev.mumu.betternether.worldgen;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 统一调度的三条硬约束：**原版条目是一个真实种类**、**它和本移植条目共用一套 index**、
 * **一个 step 里每条只跑一次**。
 *
 * <h2>每条断言对应的真实事故</h2>
 * <ol>
 *   <li><b>原版条目被当成「占位」</b>：{@code minecraft:spring_lava} / {@code *_mushroom_normal}
 *       这类条目在上游 {@code features[9]} 里本来就存在，旧实现把它们当空位跳过，于是原版那批
 *       装饰一条都没放；而数据包侧又保留了对它们的引用 —— 一旦有人「顺手」把 BN 侧也编译一份，
 *       就变成双放。现在它们有独立种类（{@link CompiledFeature#isNativePlaced()}），
 *       执行权唯一属于 {@link NativeDecorationBridge}。</li>
 *   <li><b>index 空间被拆成两套</b>：原版 {@code FeatureSorter} 是把所有群系的同 step 条目
 *       （原版 + 模组）合成**一个**列表再拓扑排序，{@code index} 就是这个列表的下标。
 *       分成「BN 一套、原版一套」会让每条的 {@code setFeatureSeed} 落在不同种子上 ——
 *       世界看起来还在生成，但一棵树都不在原位。</li>
 *   <li><b>跨群系重复</b>：同一个 placed feature 被两个群系列到时只能跑一次，
 *       否则它的随机流被消费两遍，之后这个 step 的内所有特征整体错位。</li>
 * </ol>
 */
class NativeDecorationContractTest {

    /**
     * 原版条目是独立种类：不放置（{@code feature == null}）但**不是**空位。
     *
     * <p>这两条必须同时成立：只看 {@code feature == null} 会把 native 误判成「另有 owner」，
     * 于是没人放；反过来把空位判成 native 会在注册表里查一个 BN id，然后记一条「注册表里没有」。
     */
    @Test
    void nativeEntryIsNotAnIndexPlaceholder() {
        CompiledFeature native9 = CompiledFeature.nativePlaced("minecraft:spring_lava", 9, 1);
        CompiledFeature skip9 = CompiledFeature.placeholder("betternether:cincinnasite_ore", 7, 15);

        assertTrue(native9.isNativePlaced(), "原版条目必须能被执行方认出来");
        assertFalse(native9.isIndexPlaceholder(), "原版条目不是空位：它有执行者");
        assertTrue(native9.hasStep());

        assertFalse(skip9.isNativePlaced(), "另有 owner 的条目不是原版条目");
        assertTrue(skip9.isIndexPlaceholder());
    }

    /**
     * 原版条目与本移植条目**共用**同一个 step 列表，index 就是列表下标。
     *
     * <p>数据取自上游 {@code gloomwood/features[9]} 的真实形态：原版三个条目夹在中间，
     * {@code tree_gloomwood} 必须还在第 5 位。挪一位，那棵树就换一个种子。
     */
    @Test
    void nativeEntriesShareTheIndexSpace() {
        Map<String, List<CompiledFeature>> data = new LinkedHashMap<>();
        data.put("betternether:gloomwood", List.of(
                compiled("betternether:wart_cap", 9, 0),
                CompiledFeature.nativePlaced("minecraft:spring_lava", 9, 1),
                CompiledFeature.nativePlaced("minecraft:brown_mushroom_normal", 9, 2),
                CompiledFeature.nativePlaced("minecraft:red_mushroom_normal", 9, 3),
                compiled("betternether:patch_gloomwisp_vine", 9, 4),
                compiled("betternether:tree_gloomwood", 9, 5)));

        DecorationScheduler.StepPlan step9 = DecorationScheduler.Plan.compile(data).step(9);

        assertEquals(6, step9.size(), "原版条目也占一个下标");
        assertEquals(0, step9.indexOf("betternether:wart_cap"));
        assertEquals(1, step9.indexOf("minecraft:spring_lava"), "原版条目的 index 必须留在计划里");
        assertEquals(5, step9.indexOf("betternether:tree_gloomwood"),
                "漏掉任一原版条目，后面每条 BN 特征的 index 都会前移");
        assertTrue(step9.order().get(1).isNativePlaced());
        assertFalse(step9.order().get(0).isNativePlaced());
    }

    /**
     * 两个群系都列了同一条原版条目：计划里只出现一次，取并集后下标也只出现一次。
     *
     * <p>这就是「一个 step 里每条 placed feature 每区块只跑一次」在这一层的保证 ——
     * 调度器按 index 集合执行，集合天然去重。
     */
    @Test
    void sharedNativeEntryRunsOncePerStep() {
        Map<String, List<CompiledFeature>> data = new LinkedHashMap<>();
        data.put("betternether:gloomwood", List.of(
                CompiledFeature.nativePlaced("minecraft:spring_lava", 9, 0),
                compiled("betternether:tree_gloomwood", 9, 1)));
        data.put("betternether:old_swampland", List.of(
                CompiledFeature.nativePlaced("minecraft:spring_lava", 9, 0)));

        DecorationScheduler.StepPlan step9 = DecorationScheduler.Plan.compile(data).step(9);

        assertEquals(2, step9.size());
        TreeSet<Integer> union = new TreeSet<>();
        for (String biome : List.of("betternether:gloomwood", "betternether:old_swampland")) {
            for (int index : step9.indicesOfBiome(biome)) {
                union.add(index);
            }
        }
        assertEquals(2, union.size(), "共用同一条原版条目不会产生两个下标");
        assertEquals(1L, step9.order().stream()
                .filter(feature -> feature.id().equals("minecraft:spring_lava"))
                .count(), "计划里这条原版条目只能有一个位置");
    }

    /**
     * 相位矩阵：谁跑结构、谁跑本移植条目、谁跑原版条目。
     *
     * <p>这张表是 step 7 能被切成「结构 / 原版条目 / BN 矿」三段的前提：若
     * {@code NATIVE_FEATURES} 误带 {@code compiled}，step 7 的 BN 地形条目会被放两遍
     * （调度器一遍、{@code OrePopulator} 一遍）。
     */
    @Test
    void phasesPartitionStructuresAndFeatureKinds() {
        assertPhase(DecorationScheduler.Phase.ALL, true, true, true);
        assertPhase(DecorationScheduler.Phase.STRUCTURES, true, false, false);
        assertPhase(DecorationScheduler.Phase.FEATURES, false, true, true);
        assertPhase(DecorationScheduler.Phase.NATIVE_FEATURES, false, false, true);
    }

    /**
     * 拿不到真实世界对象时必须返回 {@code null}（调用方据此降级并记账），而不是抛进去 ——
     * 装饰阶段抛异常会把整个区块生成打坏。
     *
     * <h2>这条断言为什么能在「没有服务端 jar」的测试类路径上跑</h2>
     * {@link NativeDecorationBridge#open(Object, int, int)} 的字节码里不含任何 NMS 类型：
     * {@code null} 在第一行就返回，真正的 {@code instanceof WorldGenLevel} 在那个方法
     * 转调用的 NMS 侧方法里 —— JVM 到**首次执行**那条指令时才解析类型。所以：
     * <ul>
     *   <li>{@code open(null, …)} → 直接返回 {@code null}，记 {@code native-bridge:null-level}；</li>
     *   <li>{@code open(普通对象, …)} → 本机测试类路径没有服务端，解析失败抛 {@link LinkageError}，
     *       被接住后返回 {@code null} 并记 {@code native-bridge:missing-nms}；</li>
     *   <li>有服务端时同一调用走另一条支路，记 {@code native-bridge:invalidtype}（实际类型）。</li>
     * </ul>
     * 这正是需要被测的**真实边界**：不是跳过、不是替换成按名字猜的假判据，
     * 而是让「类加载缺失」与「类型不对」两条降级路径都真的发生过一次并被记账。
     */
    @Test
    void openRejectsNonWorldLevelWithoutThrowing() {
        WorldgenWarnings.reset();

        // 两个入口的 null 都是合法降级，但语义不同，必须分别点名（否则会以为「没世界」只有一种原因）：
        //   (Object) null → WorldView#nativeWorldGenLevel 没给世界；
        //   (LimitedRegion) null → 连 populator 的区域都没有。
        // 强转是必需的：字面量 null 会优先匹配更具体的 LimitedRegion 重载。
        assertNull(NativeDecorationBridge.open((Object) null, 0, 0), "没有世界对象");
        assertNull(NativeDecorationBridge.open((org.bukkit.generator.LimitedRegion) null, 0, 0),
                "没有装饰区域");
        assertNull(NativeDecorationBridge.open(new Object(), 0, 0), "普通对象不是世界对象");
        assertNull(NativeDecorationBridge.open("not a world", 3, -7), "字符串不是世界对象");

        assertTrue(WorldgenWarnings.degraded().contains("native-bridge:null-level"),
                "null 世界要进降级账本（key 稳定，才能被运维搜索）");
        assertTrue(WorldgenWarnings.degraded().contains("native-bridge:no-region"),
                "null 区域要进降级账本");
        // 非 null 的那次调用，取决于类路径：无服务端 → missing-nms；有服务端 → invalidtype。
        // 两条都必须表达「没放成」这件事，所以断言的是「至少其中一条被记下」。
        boolean recorded = WorldgenWarnings.degraded().contains("native-bridge:missing-nms")
                || WorldgenWarnings.degraded().contains("native-bridge:invalidtype");
        assertTrue(recorded, "非世界对象要进降级账本，实际账本：" + WorldgenWarnings.degraded());

        // 三条理由的文本必须点名缺的是哪个类型、以及实际拿到的是什么 —— 只说「没生效」运维无从下手。
        // 这些是纯字符串方法（不碰 NMS 类型），所以两种类路径下都能断言。
        assertTrue(NativeDecorationBridge.reasonNull().contains("WorldGenLevel"));
        assertTrue(NativeDecorationBridge.reasonInvalidType(new Object()).contains("java.lang.Object"),
                "理由里要有实际类型，才能看出是谁走错了入口");
        assertTrue(NativeDecorationBridge.reasonInvalidType(new Object()).contains("WorldGenLevel"));
        assertTrue(NativeDecorationBridge.reasonMissingNms(new NoClassDefFoundError(
                        "net/minecraft/world/level/WorldGenLevel")).contains("WorldGenLevel"),
                "缺 NMS 的理由要点名缺的那个类型");
        assertTrue(NativeDecorationBridge.reasonMissingNms(new NoClassDefFoundError(
                        "net/minecraft/world/level/WorldGenLevel")).contains("NoClassDefFoundError"),
                "理由里要带异常类型作为来源线索");
    }

    /** 一次什么都没做的统计必须能被判出来（启动日志与诊断用）。 */
    @Test
    void emptyStatsIsObservable() {
        assertTrue(DecorationScheduler.Stats.EMPTY.isEmpty());
        DecorationScheduler.Stats onlyStructures =
                new DecorationScheduler.Stats(2, 0, 0, 0);
        assertFalse(onlyStructures.isEmpty(), "只放了结构不算「什么都没做」");
    }

    /**
     * 熔岩湖插入 step 4 之后，**每个条目的 {@code j} 具体变成多少** —— 三个精确数字。
     *
     * <h2>为什么必须断到数字，而不是「populator 顺序」</h2>
     * 本服 step 4 的注册表顺序（本移植里全是数据包条目，按 {@code Identifier} 排序）是：
     * <pre>
     * 0 altars, 1 gardens, 2 ghast_hive, 3 jungle_temples,
     * 4 pillars, 5 portals, 6 pyramid, 7 respawn_points, 8 spawn_altar_ladder,
     * 9.. minecraft:*
     * </pre>
     * 上游还有一条 {@code betternether:mega_lava_lake}（数据包 JSON，{@code step: surface_structures}），
     * 它按排序落在 **4**。插进去之后：
     * <ul>
     *   <li>{@code jungle_temples}（注册表 3）→ {@code j} 仍是 <b>3</b>（它之前的不动）；</li>
     *   <li>{@code pillars}（注册表 4）→ {@code j} 从 4 变成 <b>5</b>；</li>
     *   <li>第一条 {@code minecraft:*}（注册表 9）→ {@code j} 从 9 变成 <b>10</b>。</li>
     * </ul>
     * 这三个数就是「上游有那条结构 / 本移植没有」之间唯一的种子差异；算错一位，整条 step-4 结构流
     * 全部错开。所以断言落在这三个数字上（换算被抽到 {@link NativeDecorationSlots} 这个无 NMS 的
     * 纯算术类，正为了能在没有服务端的类路径上断它）。
     */
    @Test
    void insertingTheLakeShiftsOnlyTheEntriesAfterIt() {
        int lakeSlot = 4;

        assertEquals(3, NativeDecorationSlots.slot(3, lakeSlot), "lake 之前的条目 j 不变");
        assertEquals(5, NativeDecorationSlots.slot(4, lakeSlot), "pillars 从 4 → 5");
        assertEquals(10, NativeDecorationSlots.slot(9, lakeSlot), "第一条 minecraft 从 9 → 10");

        // 没有虚拟条目时（拿不到注册表 / 该 step 没有这类结构）必须原样不动。
        assertEquals(0, NativeDecorationSlots.slot(0, -1));
        assertEquals(4, NativeDecorationSlots.slot(4, -1));
        assertEquals(9, NativeDecorationSlots.slot(9, -1));

        // 槽位 0（虚拟条目排第一）也必须「全部 +1」，不能退化成 0 号不动。
        assertEquals(1, NativeDecorationSlots.slot(0, 0));
        assertEquals(10, NativeDecorationSlots.slot(9, 0));
    }

    // ---------------------------------------------------------------- 工具

    private static void assertPhase(DecorationScheduler.Phase phase, boolean structures,
                                   boolean compiled, boolean natives) {
        assertEquals(structures, phase.structures(), phase + " 的结构相位");
        assertEquals(compiled, phase.compiled(), phase + " 的本移植条目");
        assertEquals(natives, phase.natives(), phase + " 的原版条目");
        assertEquals(compiled || natives, phase.features(), phase + " 是否有特征相位");
    }

    private static CompiledFeature compiled(String id, int step, int index) {
        return new CompiledFeature(id, step, index,
                List.of(new PlacementModifier.InSquare()),
                new Feature.Custom("test", (world, point) -> true));
    }
}
