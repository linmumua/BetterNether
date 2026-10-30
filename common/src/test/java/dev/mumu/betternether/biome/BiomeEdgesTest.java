package dev.mumu.betternether.biome;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 交界材质窄带混合（{@link BiomeEdges}）的可执行证据。
 *
 * <h2>这份用例要锁住的东西</h2>
 * <ol>
 *   <li><b>选择没被碰</b>：带外的列与 {@link BiomePlacement#keyAt} 逐位相同，带内也只换成"邻列
 *       本来就出现的群系"（{@link #blendingOnlyEverBorrowsANeighbouringBiome()}）。</li>
 *   <li><b>只换暴露地板表层</b>：同一列里 {@code depthAbove ≤ SKIN} 跟着表层走，
 *       {@code depthAbove > SKIN}（深层岩体）和上方被整块岩体压住的位置（天花板）
 *       <b>一律保持本群系</b>（{@link #deepRockAndCeilingsKeepTheColumnsOwnBiome()}）。这条是
 *       "不整列置换"的直接锁。</li>
 *   <li><b>随距离衰减</b>：交换比例按 {@link BiomeEdges#threshold(int)} 从边界向外线性下降，实测
 *       比例必须落在阈值附近，且近处的比例严格高于远处
 *       （{@link #exchangeRateFollowsTheDistanceThreshold()}）。</li>
 *   <li><b>陡边真的变成过渡</b>：一条笔直的群系边界两侧都出现对方的材质
 *       （{@link #straightBoundaryIsInterlockedOnBothSides()}）。</li>
 *   <li><b>不是椒盐噪点</b>：交换与否由一条相干噪声决定，所以沿边界是 8 方块量级的连续段；用例
 *       直接量切换次数与最长连续段（{@link #swapsFormCoherentRunsAlongTheBoundary()}），并量噪声
 *       自身的逐格差分（{@link #noiseIsContinuousBetweenNeighbouringColumns()}）。</li>
 *   <li><b>种子可复现</b>：同种子逐位相同、换种子花纹不同
 *       （{@link #sameSeedReplaysIdenticallyAndAnotherSeedDoesNot()}）。</li>
 *   <li><b>稀有群系不丢</b>：均匀区域里混合一次都不触发，竖直群系在它自己的气候点上仍然原样出现
 *       （{@link #uniformRegionsAreNeverTouched()}、{@link #theRareVerticalBiomeIsNotStolen()}）。</li>
 *   <li><b>邻接稳定</b>：相邻两列的答案仍然几乎总是一致
 *       （{@link #neighbouringColumnsStillMostlyAgree()}）。</li>
 * </ol>
 */
class BiomeEdgesTest {
    private static final long SEED = 20260210L;
    private static final int Y = 40;

    /** 一条笔直的气候边界：quart x &lt; 0 全是 -1.0，其余全是 +1.0。 */
    private static ClimateSampler twoSides(float leftTemperature, float rightTemperature) {
        return (quartX, quartY, quartZ) ->
                TargetPoint.ofClimate(quartX < 0 ? leftTemperature : rightTemperature, 0.0f);
    }

    /** 只有一个 quart 是 {@code islandTemperature}，其余全是 {@code seaTemperature}。 */
    private static ClimateSampler oneQuartIsland(float seaTemperature, float islandTemperature) {
        return (quartX, quartY, quartZ) -> TargetPoint.ofClimate(
                quartX == 0 && quartZ == 0 ? islandTemperature : seaTemperature, 0.0f);
    }

    /**
     * 一个方块的地表材质键：{@code depthAbove = 1} 就是"正上方是空气"的那个方块。
     *
     * <p>{@code depthBelow} 今天不参与判定（掺混只做地板方向），仍然显式写成 1：地板方块的
     * 正下方是岩体，测试把这两个数摆在规则树会看到的位置，而不是靠默认值。
     */
    private static String surface(BiomeEdges edges, int x, int z) {
        return edges.keyAt(x, Y, z, 1, 1);
    }

    /** 同一列在指定石面深度上的键。{@code depthAbove} 是原版 {@code stoneDepthAbove} 的口径。 */
    private static String at(BiomeEdges edges, int x, int z, int depthAbove) {
        return edges.keyAt(x, Y, z, depthAbove, 1);
    }

    /** 混合带在 {@code ±x} 上的最后一个被混合的方块 x（含）。 */
    private static final int BAND_EDGE = BiomeEdges.BAND * BiomeEdges.QUART - 1;

    // ---------------------------------------------------------------- 选择没被碰

    /**
     * 均匀区域：{@code REACH} 格内都是同一列，没有"邻居"可借，所以混合一次都不该触发。
     *
     * <p>{@link TestClimatePlane#fixed} 让整个平面都落在同一个气候点上，于是整张图的键都该与
     * {@link BiomePlacement} 自己给出的完全一样 —— 这是"这一层不改变群系分布"的最强形式。
     */
    @Test
    void uniformRegionsAreNeverTouched() {
        BiomePlacement placement = new BiomePlacement(TestClimatePlane.fixed(0.35f, 0.10f));
        BiomeEdges edges = new BiomeEdges(placement, SEED);
        int columns = 0;
        for (int x = -64; x <= 64; x += 3) {
            for (int z = -64; z <= 64; z += 3) {
                for (int y = 0; y <= 127; y += 31) {
                    for (int depthAbove : new int[]{1, 2, BiomeEdges.SKIN, BiomeEdges.SKIN + 1, 64}) {
                        assertEquals(placement.keyAt(x, y, z), edges.keyAt(x, y, z, depthAbove, 1),
                                "均匀区域里混合不应触发：" + x + "," + y + "," + z
                                        + " depthAbove=" + depthAbove);
                    }
                }
                columns++;
            }
        }
        assertTrue(columns > 1000, "检查的列数应足够多，实际 " + columns);
    }

    /**
     * 混合只会借"邻列本来就在的那个群系"。
     *
     * <p>这是本类唯一的不变量：换出来的键必须出现在这一列 {@link BiomeEdges#REACH} 格内的探针里。
     * 它同时挡住了两类错误 —— 凭空造出第三方群系（把半个大陆的材质搬到边界上），以及借到自己都
     * 不相邻的键。
     */
    @Test
    void blendingOnlyEverBorrowsANeighbouringBiome() {
        BiomePlacement placement = new BiomePlacement(TestClimatePlane.seeded(23L));
        BiomeEdges edges = new BiomeEdges(placement, SEED);
        int swapped = 0;
        for (int x = -400; x <= 400; x += 2) {
            for (int z = -400; z <= 400; z += 2) {
                String base = placement.keyAt(x, Y, z);
                String blended = surface(edges, x, z);
                assertNotNull(blended, "混合不能产生 null 键");
                if (base.equals(blended)) {
                    continue;
                }
                swapped++;
                Set<String> probes = new HashSet<>();
                for (int quarte = 1; quarte <= BiomeEdges.BAND; quarte++) {
                    int step = quarte * BiomeEdges.QUART;
                    probes.add(placement.keyAt(x + step, Y, z));
                    probes.add(placement.keyAt(x - step, Y, z));
                    probes.add(placement.keyAt(x, Y, z + step));
                    probes.add(placement.keyAt(x, Y, z - step));
                }
                probes.remove(base);
                assertTrue(probes.contains(blended),
                        "换出来的键必须来自 " + BiomeEdges.REACH + " 格内的探针：" + base + " -> "
                                + blended + " @" + x + "," + z + " 探针 " + probes);
            }
        }
        assertTrue(swapped > 200, "这片场上有边界，应发生足够多的交换，实际 " + swapped);
    }

    // ---------------------------------------------------------------- 只换暴露地板表层

    /**
     * <b>"不整列置换"的锁</b>：同一列里，表层以下的方块仍然属于本列群系。
     *
     * <p>旧行为是整列替换 —— 噪声决定交换之后，从地板到天花板全用邻居的规则，于是深层岩体换了
     * 材质、倒挂群系的天花板被搬走。这里取边界上真的被交换过的列，逐条断言：
     * <ul>
     *   <li>{@code depthAbove = 1..SKIN}：跟着表层一起换成邻居（表层本身要一致，否则就是"表面换了、
     *       下面没换"的半截过渡）；</li>
     *   <li>{@code depthAbove = SKIN + 1}：立刻回到本群系；</li>
     *   <li>{@code depthAbove = 64}（天花板、被液体盖住的地板都是这个量级）：同样是本群系。</li>
     * </ul>
     */
    @Test
    void deepRockAndCeilingsKeepTheColumnsOwnBiome() {
        BiomePlacement placement = new BiomePlacement(twoSides(-1.0f, 1.0f));
        BiomeEdges edges = new BiomeEdges(placement, SEED);
        String centre = placement.keyAt(0, Y, 0);
        String partner = placement.keyAt(-1, Y, 0);
        assertNotEquals(centre, partner, "夹具必须给出两个不同的群系");

        int swapped = 0;
        for (int z = -400; z <= 400; z++) {
            String blended = surface(edges, 0, z);
            if (blended.equals(centre)) {
                continue; // 这一列没被交换，没有可断言的东西
            }
            swapped++;
            assertEquals(partner, blended, "交换出来的必须是邻居的键");
            for (int depthAbove = 1; depthAbove <= BiomeEdges.SKIN; depthAbove++) {
                assertEquals(partner, at(edges, 0, z, depthAbove),
                        "表层第 " + depthAbove + " 格必须与地表一致（同一列同一次决定）：z=" + z);
            }
            assertEquals(centre, at(edges, 0, z, BiomeEdges.SKIN + 1),
                    "表层以下一格就必须回到本群系：z=" + z);
            assertEquals(centre, at(edges, 0, z, 64),
                    "天花板（上方是整块岩体）必须保持本群系：z=" + z);
        }
        assertTrue(swapped > 100, "边界上应发生足够多的交换，实际 " + swapped);
    }

    // ---------------------------------------------------------------- 随距离衰减

    /**
     * 交换与否<b>逐列逐坐标</b>等于"噪声 &lt; 该距离的阈值"，而且比例确实随距离下降。
     *
     * <p>距离由夹具自己的几何<b>独立</b>算出来（笔直边界在方块 0：右侧 x 的距离是 x+1，左侧 x 的
     * 距离是 |x|），不去重复 {@code BiomeEdges} 内部的探针算式。于是这条断言把三件事绑在一起：
     * <ol>
     *   <li>每一列算出的<b>距离</b>是对的（距离错了，阈值就错，逐格对照立刻炸）；</li>
     *   <li>阈值真的作用在相干噪声上（{@code swap <=> noise01 < threshold(distance)} 逐格成立）；</li>
     *   <li>换出来的键就是边界对面那个群系。</li>
     * </ol>
     *
     * <p>末尾再量一次"比例随距离下降"。这里刻意<b>不</b>断言"比例 ≈ 阈值"：值噪声
     * （smoothstep 插值的格点哈希）的分布在 0.5 附近最密、两端最稀，所以比例只在阈值靠近 0.5 时
     * 接近阈值本身。要钉的是阈值与逐格行为的对应，而不是噪声的边缘分布。
     */
    @Test
    void swapsFollowTheDistanceThresholdAndDecayOutwards() {
        BiomePlacement placement = new BiomePlacement(twoSides(-1.0f, 1.0f));
        BiomeEdges edges = new BiomeEdges(placement, SEED);
        String left = placement.keyAt(-8, Y, 0);
        String right = placement.keyAt(8, Y, 0);
        assertNotEquals(left, right, "夹具必须给出两个不同的群系");

        int[] swapped = new int[BiomeEdges.REACH + 1];
        int[] total = new int[BiomeEdges.REACH + 1];
        for (int z = -512; z < 512; z++) {
            for (int x = 0; x <= BAND_EDGE; x++) {
                int distance = x + 1;
                String key = surface(edges, x, z);
                boolean expected = BiomeEdges.noise01(SEED, x, z) < BiomeEdges.threshold(distance);
                assertEquals(expected, !key.equals(right),
                        "x=" + x + "," + z + "：距离 " + distance + " 格的交换决定必须等于噪声与阈值之比较");
                if (!key.equals(right)) {
                    assertEquals(left, key, "换出来的必须是边界对面的群系：" + x + "," + z);
                    swapped[distance]++;
                }
                total[distance]++;
            }
            for (int x = -BAND_EDGE - 1; x <= -1; x++) {
                int distance = -x;
                String key = surface(edges, x, z);
                boolean expected = BiomeEdges.noise01(SEED, x, z) < BiomeEdges.threshold(distance);
                assertEquals(expected, !key.equals(left),
                        "x=" + x + "," + z + "：距离 " + distance + " 格的交换决定必须等于噪声与阈值之比较");
                if (!key.equals(left)) {
                    assertEquals(right, key, "换出来的必须是边界对面的群系：" + x + "," + z);
                    swapped[distance]++;
                }
                total[distance]++;
            }
        }

        double near = swapped[1] / (double) total[1];
        double middle = swapped[4] / (double) total[4];
        double far = swapped[BiomeEdges.REACH] / (double) total[BiomeEdges.REACH];
        assertTrue(near > 0.4 && near < 0.65,
                "紧贴边界的一格应有一半上下被交换（阈值 " + BiomeEdges.MIX + "），实际 " + near);
        assertTrue(near > middle && middle > far,
                "交换比例必须随距离递减：1 格 " + near + "，4 格 " + middle
                        + "，" + BiomeEdges.REACH + " 格 " + far);
        assertTrue(far < 0.05,
                BiomeEdges.REACH + " 格处只剩零星交换（阈值 " + BiomeEdges.threshold(BiomeEdges.REACH)
                        + "），实际 " + far);
    }

    // ---------------------------------------------------------------- 陡边 → 过渡

    /**
     * 笔直边界两侧都出现对方的材质：这就是"陡边"被换成了互相咬合的窄带。
     *
     * <p>边界在方块 x=0（quart -1 | quart 0）。混合带是两侧各 {@link BiomeEdges#BAND} 个 quart，
     * 即 {@code x ∈ [-8, 7]}：再往外的列探针全部落在自己这一侧，{@link BiomeEdges} 不该碰它们。
     */
    @Test
    void straightBoundaryIsInterlockedOnBothSides() {
        BiomePlacement placement = new BiomePlacement(twoSides(-1.0f, 1.0f));
        BiomeEdges edges = new BiomeEdges(placement, SEED);
        String left = placement.keyAt(-8, Y, 0);
        String right = placement.keyAt(8, Y, 0);
        assertNotEquals(left, right, "这条夹具的两侧必须是两个不同的群系");

        boolean rightSideTookLeft = false;
        boolean leftSideTookRight = false;
        for (int z = -200; z <= 200; z++) {
            for (int x = 0; x <= BAND_EDGE; x++) {
                if (surface(edges, x, z).equals(left)) {
                    rightSideTookLeft = true;
                }
            }
            for (int x = -BAND_EDGE - 1; x <= -1; x++) {
                if (surface(edges, x, z).equals(right)) {
                    leftSideTookRight = true;
                }
            }
        }
        assertTrue(rightSideTookLeft, "右侧带内必须出现左侧群系的材质（互相咬合，不是单边蚕食）");
        assertTrue(leftSideTookRight, "左侧带内必须出现右侧群系的材质");

        // 带外一个方块都不动。
        for (int z = -200; z <= 200; z++) {
            for (int x = BAND_EDGE + 1; x <= BAND_EDGE + 32; x++) {
                assertEquals(right, surface(edges, x, z), "带外不应被混合：" + x + "," + z);
            }
            for (int x = -BAND_EDGE - 32; x <= -BAND_EDGE - 2; x++) {
                assertEquals(left, surface(edges, x, z), "带外不应被混合：" + x + "," + z);
            }
        }
    }

    /**
     * 孤点被"稀释"而不是被擦掉，同时向邻域溢出一层。
     *
     * <p>一个 quart 的孤岛（4×4 方块）在旧行为里是一颗 4 方块的材质斑，四面都是硬边。混合之后：
     * 孤岛内部有一部分列改用周围群系的材质（斑被咬开），周围也有一部分列改用孤岛的材质（斑长出
     * 舌状边缘）。两者都断言，因为只做前者会把孤岛整块抹平，只做后者会把斑放大。
     */
    @Test
    void aSingleQuartIslandIsDilutedInsteadOfErasedOrGrown() {
        float sea = 0.35f;
        float island = -0.45f;
        BiomePlacement placement = new BiomePlacement(oneQuartIsland(sea, island));
        BiomeEdges edges = new BiomeEdges(placement, SEED);
        String seaKey = placement.keyAt(40, Y, 40);
        String islandKey = placement.keyAt(0, Y, 0);
        assertNotEquals(seaKey, islandKey, "夹具必须给出两个不同的群系");

        int stillIsland = 0;
        int becameSea = 0;
        for (int x = 0; x <= 3; x++) {
            for (int z = 0; z <= 3; z++) {
                if (surface(edges, x, z).equals(islandKey)) {
                    stillIsland++;
                } else {
                    becameSea++;
                }
            }
        }
        assertTrue(stillIsland > 0, "孤岛不能被整块抹平（那就是改分布了）");
        assertTrue(becameSea > 0, "孤岛内部必须被咬开，否则没有过渡：" + becameSea);

        // 溢出的那一层：紧贴孤岛外侧的列里应出现孤岛的材质，这就是"长出来的舌"。
        boolean spilled = false;
        for (int x = -BiomeEdges.REACH; x <= BAND_EDGE + BiomeEdges.REACH && !spilled; x++) {
            for (int z = -BiomeEdges.REACH; z <= BAND_EDGE + BiomeEdges.REACH && !spilled; z++) {
                boolean inside = x >= 0 && x <= 3 && z >= 0 && z <= 3;
                if (!inside && surface(edges, x, z).equals(islandKey)) {
                    spilled = true;
                }
            }
        }
        assertTrue(spilled, "孤岛边缘应向外溢出至少一列，形成舌状过渡");
    }

    // ---------------------------------------------------------------- 相干，不是椒盐

    /**
     * 沿边界方向：交换决定是连续段，不是逐格跳变。
     *
     * <p>量的是 x=0 这一列（笔直边界上距离最近的一列，阈值 {@link BiomeEdges#MIX}）：切换次数与
     * 最长连续段能区分相干噪声与逐格哈希 —— 逐格哈希在 256 列里会有约 128 次切换，相干噪声
     * （波长 16 方块）只有十几次；前者的最长连续段是 1~2，后者是 8 左右。
     */
    @Test
    void swapsFormCoherentRunsAlongTheBoundary() {
        BiomePlacement placement = new BiomePlacement(twoSides(-1.0f, 1.0f));
        BiomeEdges edges = new BiomeEdges(placement, SEED);
        String left = placement.keyAt(-8, Y, 0);

        int x = 0; // 边界右侧第一列，与边界平行地走一遍
        boolean[] swapped = new boolean[256];
        for (int i = 0; i < swapped.length; i++) {
            swapped[i] = surface(edges, x, i).equals(left);
        }
        int transitions = 0;
        int run = 0;
        int longest = 0;
        for (int i = 0; i < swapped.length; i++) {
            if (i > 0 && swapped[i] != swapped[i - 1]) {
                transitions++;
            }
            run = (i > 0 && swapped[i] == swapped[i - 1]) ? run + 1 : 1;
            longest = Math.max(longest, run);
        }
        assertTrue(transitions > 0, "这条线上必须真的发生过交换，否则这个用例什么也没量到");
        assertTrue(transitions < 64,
                "256 列里的切换次数应远低于逐格随机的约 128 次，实际 " + transitions);
        assertTrue(longest >= 4,
                "交换应连成至少 4 列的段（相干噪声），实际最长 " + longest);
    }

    /** 噪声本身在相邻列之间连续：一阶差分远小于 1，所以阈值切出来的区域必然成片。 */
    @Test
    void noiseIsContinuousBetweenNeighbouringColumns() {
        double maxStep = 0.0;
        for (int x = -300; x <= 300; x++) {
            for (int z = -20; z <= 20; z++) {
                double here = BiomeEdges.noise01(SEED, x, z);
                assertTrue(here >= 0.0 && here < 1.0, "噪声值必须落在 [0,1)：" + here);
                maxStep = Math.max(maxStep, Math.abs(BiomeEdges.noise01(SEED, x + 1, z) - here));
                maxStep = Math.max(maxStep, Math.abs(BiomeEdges.noise01(SEED, x, z + 1) - here));
            }
        }
        // smoothstep 的最大斜率 1.5，格宽 WAVELENGTH 方块，故逐格差分上界 1.5 / 16 ≈ 0.094。
        assertTrue(maxStep < 0.1, "逐格差分应小于 0.1（相干），实际最大 " + maxStep);
    }

    // ---------------------------------------------------------------- 可复现

    /**
     * 同种子逐位相同；换种子确实换花纹 —— 但只在交界处换，群系分布本身不变。
     *
     * <p>后半句是关键：如果换种子能改变一条"带外"的列，那就说明混合不是在处理边界，而是在往图里
     * 撒东西。
     */
    @Test
    void sameSeedReplaysIdenticallyAndAnotherSeedDoesNot() {
        BiomePlacement placement = new BiomePlacement(twoSides(-1.0f, 1.0f));
        BiomeEdges a = new BiomeEdges(placement, SEED);
        BiomeEdges b = new BiomeEdges(placement, SEED);
        BiomeEdges other = new BiomeEdges(placement, SEED + 1);

        int differences = 0;
        for (int z = -200; z <= 200; z++) {
            for (int x = -12; x <= 12; x++) {
                String fromA = surface(a, x, z);
                assertEquals(fromA, surface(b, x, z), "同种子必须逐位相同：" + x + "," + z);
                if (!fromA.equals(surface(other, x, z))) {
                    differences++;
                }
                if (x > BAND_EDGE || x < -BAND_EDGE - 1) {
                    assertEquals(placement.keyAt(x, Y, z), surface(other, x, z),
                            "带外换种子也不该有任何差别：" + x + "," + z);
                }
            }
        }
        assertTrue(differences > 20, "换种子应换出不同的咬合花纹，实际差别 " + differences);
    }

    /** 生产路径走列级缓存（{@code column}），测试走逐点（{@code keyAt}）：两者必须一致。 */
    @Test
    void columnFunctionAgreesWithPointQueries() {
        BiomePlacement placement = new BiomePlacement(TestClimatePlane.seeded(77L));
        BiomeEdges edges = new BiomeEdges(placement, SEED);
        int[] depths = {1, 2, BiomeEdges.SKIN, BiomeEdges.SKIN + 1, 64};
        for (int x = -200; x <= 200; x += 7) {
            for (int z = -200; z <= 200; z += 7) {
                BiomeEdges.ColumnKey column = edges.column(x, z);
                for (int y = 0; y <= 127; y += 17) {
                    for (int depthAbove : depths) {
                        assertEquals(edges.keyAt(x, y, z, depthAbove, 1), column.keyAt(y, depthAbove, 1),
                                "列级缓存与逐点查询必须同答：" + x + "," + y + "," + z
                                        + " depthAbove=" + depthAbove);
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- 稀有 / 稳定

    /**
     * 竖直群系在它自己的气候点上仍是它自己：混合不抢均匀区域的列。
     *
     * <p>参数点与 {@code VerticalBiomePlacementTest} 同一个夹具：(0.30, 0.80) 处
     * {@code upside_down_forest} 以 0.125 的 offset 胜出。它只有气候上的一块地盘，所以任何"顺手
     * 换掉一点"都会让这个本来就稀有的群系更少。
     */
    @Test
    void theRareVerticalBiomeIsNotStolen() {
        BiomePlacement placement = new BiomePlacement(TestClimatePlane.fixed(0.30f, 0.80f));
        BiomeEdges edges = new BiomeEdges(placement, SEED);
        for (int x = -64; x <= 64; x += 5) {
            for (int z = -64; z <= 64; z += 5) {
                assertEquals(BiomePlacement.VERTICAL_KEY, placement.keyAt(x, Y, z),
                        "夹具本身应先给出竖直群系");
                assertEquals(BiomePlacement.VERTICAL_KEY, surface(edges, x, z),
                        "均匀的竖直群系区域不能被混合换走");
            }
        }
    }

    /** 相邻两列仍然几乎总是同键：混合不能把图变成棋盘。 */
    @Test
    void neighbouringColumnsStillMostlyAgree() {
        BiomePlacement placement = new BiomePlacement(TestClimatePlane.seeded(23L));
        BiomeEdges edges = new BiomeEdges(placement, SEED);
        int same = 0;
        int total = 0;
        for (int x = -400; x <= 400; x++) {
            total++;
            if (surface(edges, x, 0).equals(surface(edges, x + 1, 0))) {
                same++;
            }
        }
        assertTrue(same * 100 / total >= 95,
                "一格的位移几乎不会换群系（相同率 " + same + "/" + total + "）");
    }

    /** 边界连续性：沿一条穿过边界的直线走，键的变化次数与纯 {@code keyAt} 同量级，不会激增。 */
    @Test
    void crossingABoundaryStaysAsStableAsWithoutBlending() {
        BiomePlacement placement = new BiomePlacement(TestClimatePlane.seeded(23L));
        BiomeEdges edges = new BiomeEdges(placement, SEED);
        int baseChanges = 0;
        int blendedChanges = 0;
        for (int x = -400; x < 400; x++) {
            if (!placement.keyAt(x, Y, 0).equals(placement.keyAt(x + 1, Y, 0))) {
                baseChanges++;
            }
            if (!surface(edges, x, 0).equals(surface(edges, x + 1, 0))) {
                blendedChanges++;
            }
        }
        assertTrue(baseChanges > 0, "这条线上必须真的穿过边界");
        // 混合带每侧 8 格，带内每列至多变一次；给足余量。
        int band = 2 * BiomeEdges.REACH;
        assertTrue(blendedChanges <= baseChanges * 3 + band * 2,
                "混合不应把穿越边界的次数放大到不可控（原 " + baseChanges
                        + "，混合后 " + blendedChanges + "）");
    }
}
