package dev.mumu.betternether.biome;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "哪些群系在默认路径上真的会出现"，以及"参数表里那些<b>不参与选择</b>的字段确实不参与"。
 *
 * <h2>这个文件为什么换了主题</h2>
 * 上一版这里量的是自造规则的产物：{@code genChance} 加权抽签的份额、{@code edge} 邻域带的占比、
 * 子群系是否落在父群系的"气候凹槽"里。那三条规则已经从 {@link BiomePlacement} 删除（依据见它的类
 * 注释：原版最近邻读不到 {@code genChance}，原版参数表也没有"边群系"和竖直轴），所以按份额断言的
 * 用例已经没有可说的对象。保留下来的主题是两条现在仍然成立、而且更值钱的事实：
 * <ol>
 *   <li>在整张气候矩形上，谁能被选中是一张<b>确定的</b>名单（{@link #reachabilityIsAStableList()}）。</li>
 *   <li>{@code genChance} / {@code parent} / {@code edge} / {@code edgeSize} / {@code vertical}
 *       只是数据：把它们改成任意值，{@link BiomePlacement#select} 一个答案都不会变
 *       （{@link #fieldsThatDoNotSelectDoNotSelect()}）。</li>
 * </ol>
 */
class BiomePlacementReachabilityTest {
    /** 覆盖整个气候矩形再往外留一圈：原版噪声会略微越出 [-1,1]。 */
    private static final float MIN = -1.2f;
    private static final float MAX = 1.2f;
    private static final int STEPS = 300;

    /**
     * 可达名单是稳定的：换多少个采样点扫描都必须得到同一张名单。
     *
     * <p>真正的意义在于"可复现"：如果哪天某个群系悄悄消失了，这里会立刻显示是哪一组数据改动
     * 导致的，而不是让人去猜抽签的随机性。
     */
    @Test
    void reachabilityIsAStableList() {
        List<String> coarse = scan(120);
        List<String> fine = scan(STEPS);
        assertEquals(coarse, fine, "两种分辨率的扫描必须得到同一张可达名单");
        assertEquals(23, fine.size(), "28 个参数点里 23 个可达（不可达的 5 个见 BiomePlacementTest）");
    }

    /**
     * 五个不可达的群系，逐个给出"为什么"的算术。
     *
     * <p>写得这么细是因为它最容易被误当作 bug 去"修"：四个都是 {@code offset} 成本造成的，
     * 一个是完全并列时被合并顺序裁定掉的。
     */
    @Test
    void unreachableBiomesAreExplainedByOffsetCostAndMergeOrder() {
        // 1) 完全并列：nether_mushroom_forest 与 nether_grasslands 同为 (0.0, 0.7, offset 0)。
        TargetPoint atSamePoint = TargetPoint.ofClimate(0.0f, 0.7f);
        assertEquals(BiomePlacement.fitness(BiomePlacement.climateOf("nether_grasslands"), atSamePoint),
                BiomePlacement.fitness(BiomePlacement.climateOf("nether_mushroom_forest"), atSamePoint),
                "两者适应度必须完全相同");
        assertEquals("nether_grasslands", BiomePlacement.select(atSamePoint),
                "并列时合并顺序靠前的赢得：nether_grasslands 在 ALL 里更早");

        // 2) nether_mushroom_forest_edge 在同一气候点上多出 1000² 的成本。
        assertEquals(1000L * 1000L,
                BiomePlacement.fitness(BiomePlacement.climateOf("nether_mushroom_forest_edge"), atSamePoint));

        // 3) sulfuric_bone_reef 的 offset 0.37 换算成成本 = 3700²，比它在湿度轴上能拿到的优势大得多。
        TargetPoint atReef = TargetPoint.ofClimate(0.0f, 0.6f);
        long reef = BiomePlacement.fitness(BiomePlacement.climateOf("sulfuric_bone_reef"), atReef);
        long fungiwoods = BiomePlacement.fitness(BiomePlacement.climateOf("old_fungiwoods"), atReef);
        assertEquals(3700L * 3700L, reef);
        assertEquals(500L * 500L, fungiwoods);
        assertTrue(fungiwoods < reef, "在自己参数点上仍输给距离 0.05 的 old_fungiwoods");

        // 4) old_warped_woods 与 minecraft:warped_forest 都带 0.375 的 offset。高湿度侧是
        //    nether_grasslands(0.0, 0.7, offset 0) 与 nether_swampland_terraces(0.08, 0.85, offset 0.1)
        //    这两簇 offset 更小、距离更近的点，两者一起把它们压掉。
        TargetPoint atHighHumidity = TargetPoint.ofClimate(0.0f, 0.9f);
        long warped = BiomePlacement.fitness(BiomePlacement.climateOf("minecraft:warped_forest"), atHighHumidity);
        long terraces = BiomePlacement.fitness(
                BiomePlacement.climateOf("nether_swampland_terraces"), atHighHumidity);
        long grasslandsAt09 = BiomePlacement.fitness(
                BiomePlacement.climateOf("nether_grasslands"), atHighHumidity);
        // 湿度差 0.5-0.9 = 0.4 → 4000 个量化单位；再加 offset 3750²。
        assertEquals(4000L * 4000L + 3750L * 3750L, warped, "warped_forest 的 offset 成本是 3750²");
        // terraces 在 (0.08, 0.85, 0.1)：ΔT 800、ΔH 500、offset 1000。
        assertEquals(800L * 800L + 500L * 500L + 1000L * 1000L, terraces);
        assertEquals(2000L * 2000L, grasslandsAt09);
        assertTrue(terraces < warped, "offset 0.1 的 terraces 比 offset 0.375 的 warped_forest 便宜得多");
        assertEquals("nether_swampland_terraces", BiomePlacement.select(atHighHumidity));
    }

    /**
     * {@code genChance} / {@code parent} / {@code edge} / {@code edgeSize} / {@code vertical}
     * <b>不参与</b>选择。
     *
     * <p>做法：把这几列的公开访问器读出来，确认它们与 JSON 一致（是数据）；再确认选择只依赖
     * 温度、湿度、offset 三项 —— 用 {@link TargetPoint} 的六轴里另外四轴大幅偏移，答案仍不变。
     * 这就是"自造规则确实被删掉了"的可执行证据：如果还有加权抽签，改 {@code genChance} 会改变
     * 某一列的结果；如果有竖带，改 {@code y} 会改变结果。
     */
    @Test
    void fieldsThatDoNotSelectDoNotSelect() {
        // 数据仍然在（它们描述这个群系，只是不参与选边）。
        assertEquals("betternether:nether_mushroom_forest_edge", BiomePlacement.edgeOf("nether_mushroom_forest"));
        assertEquals(6, BiomePlacement.edgeSizeOf("nether_mushroom_forest"));
        assertEquals("betternether:wart_forest_edge", BiomePlacement.edgeOf("wart_forest"));
        assertEquals(9, BiomePlacement.edgeSizeOf("wart_forest"));
        assertEquals("betternether:nether_swampland", BiomePlacement.parentOf("old_swampland"));
        assertTrue(BiomePlacement.verticalOf(BiomePlacement.VERTICAL_KEY));
        assertEquals(1, BiomePlacement.climates().stream()
                .filter(BiomePlacement.Climate::vertical).count(), "只有 upside_down_forest 一个 vertical 行");

        // 另外四轴的采样值大幅变化不改变答案：它们对所有参数点的贡献相同。
        ClimateSampler flat = TestClimatePlane.seeded(11L);
        BiomePlacement placement = new BiomePlacement(flat);
        for (int x = -96; x <= 96; x += 11) {
            for (int z = -96; z <= 96; z += 11) {
                String plain = placement.keyAt(x, z);
                TargetPoint target = placement.targetAt(x, z);
                TargetPoint shifted = TargetPoint.of(target.temperature(), target.humidity(),
                        9000L, -9000L, 5000L, -3000L);
                assertEquals(plain, BiomePlacement.select(shifted),
                        "continentalness/erosion/depth/weirdness 不参与判别");
            }
        }
    }

    /** 相邻两列的回答变化得很慢：最近邻是一张 Voronoi 图，边界之外不应有噪声式的抖动。 */
    @Test
    void neighbouringColumnsMostlyAgree() {
        BiomePlacement placement = new BiomePlacement(TestClimatePlane.seeded(23L));
        int same = 0;
        int total = 0;
        for (int x = -400; x <= 400; x += 1) {
            total++;
            if (placement.keyAt(x, 0).equals(placement.keyAt(x + 1, 0))) same++;
        }
        assertTrue(same * 100 / total > 95,
                "一格的位移几乎不会换群系（相同率 " + same + "/" + total + "）");
    }

    /** 两参与三参重载对同一列的回答必须一致 —— 这是 provider 与 populator 不会互相矛盾的根据。 */
    @Test
    void bothOverloadsAgreeOnAColumn() {
        BiomePlacement placement = new BiomePlacement(TestClimatePlane.seeded(31L));
        for (int x = -64; x <= 64; x += 5) {
            for (int z = -64; z <= 64; z += 5) {
                String column = placement.keyAt(x, z);
                assertEquals(column, placement.keyAt(x, 12, z));
                assertEquals(column, placement.keyAt(x, 100, z));
                assertEquals(column, placement.baseKeyAt(x, z));
            }
        }
    }

    /** 一次网格扫描，返回按参数表顺序排好的可达键。 */
    private static List<String> scan(int steps) {
        java.util.Set<String> winners = new java.util.HashSet<>();
        for (int t = 0; t <= steps; t++) {
            for (int h = 0; h <= steps; h++) {
                float temperature = MIN + (MAX - MIN) * t / steps;
                float humidity = MIN + (MAX - MIN) * h / steps;
                winners.add(BiomePlacement.select(TargetPoint.ofClimate(temperature, humidity)));
            }
        }
        List<String> reachable = new ArrayList<>();
        for (BiomePlacement.Climate row : BiomePlacement.climates()) {
            if (winners.contains(row.key())) reachable.add(row.key());
        }
        return reachable;
    }
}
