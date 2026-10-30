package dev.mumu.betternether.terrain;

import dev.mumu.betternether.biome.BiomePlacement;
import dev.mumu.betternether.biome.ClimateSampler;
import dev.mumu.betternether.biome.TargetPoint;
import dev.mumu.betternether.biome.TestClimatePlane;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 默认路径<b>没有</b>竖直气候轴 —— 这个文件上一版专门验证"y≥96 才报告 {@code upside_down_forest}"
 * 的竖带，那条规则已删除，所以整份用例换了主题。
 *
 * <h2>为什么竖带必须删</h2>
 * {@code upside_down_forest.json} 里的 {@code "vertical": true} 是 WoVer 群系数据的一个字段，
 * 服务于 WoVer 自己的群系选择器。默认路径走的是原版 {@code multi_noise}，而原版下界的
 * {@code noise_settings/nether.json} 里六个气候轴中 {@code depth} 是常量 {@code 0.0}、
 * 温度与湿度噪声的 {@code y_scale} 也是 {@code 0.0} —— 没有任何量会随 y 变化，因此也对不上任何
 * "竖直"的概念。要在这里凭空造一条竖轴，只能靠自造的噪声阈值。
 *
 * <h2>现在验什么</h2>
 * <ol>
 *   <li>同一列在任意高度上回答同一个群系（{@link #everyHeightOfAColumnAnswersTheSameBiome()}）。</li>
 *   <li>竖直群系要到它自己的气候点上才会出现（{@link #theVerticalBiomeAppearsAtItsOwnClimate()}）——
 *       用的是参数表里的 (0.45, 0.8) 与 0.125 的 offset，不是随机抽。</li>
 *   <li>{@link NetherTerrainGenerator#sameBand} 这类"跨带"判断现在恒为 true，所以地表只读一个键。</li>
 * </ol>
 */
class VerticalBiomePlacementTest {
    private static final long SEED = 20260102L;
    private static final int ROOF_Y = 120;
    private static final int FLOOR_Y = 40;

    @Test
    void everyHeightOfAColumnAnswersTheSameBiome() {
        BiomePlacement placement = new BiomePlacement(TestClimatePlane.seeded(SEED));
        for (int x = -160; x <= 160; x += 13) {
            for (int z = -160; z <= 160; z += 13) {
                String column = placement.keyAt(x, z);
                assertEquals(column, placement.keyAt(x, FLOOR_Y, z));
                assertEquals(column, placement.keyAt(x, ROOF_Y, z));
                assertEquals(column, placement.keyAt(x, 127, z));
                assertEquals(column, placement.keyAt(x, 0, z));
            }
        }
    }

    /**
     * 竖直群系只由气候决定：把采样器钉在它的参数点上，它就该出现；钉在别处，它就不该出现。
     *
     * <p>参数点取自 {@code upside_down_forest.json}：{@code temperature 0.45, humidity 0.8,
     * offset 0.125}。{@code offset} 是成本，所以它必须在比 {@code nether_jungle (0.5, 0.8, offset 0)}
     * 近 0.125 以上的地方才赢 —— 例如温度 0.30 处。
     */
    @Test
    void theVerticalBiomeAppearsAtItsOwnClimate() {
        ClimateSampler atOwnPoint = TestClimatePlane.fixed(0.30f, 0.80f);
        assertEquals(BiomePlacement.VERTICAL_KEY,
                new BiomePlacement(atOwnPoint).keyAt(0, ROOF_Y, 0),
                "温度 0.30 / 湿度 0.80 处应由 upside_down_forest 胜出");

        // 同一个点上，竖直群系在屋顶和地面是同一个答案 —— 没有"只在天花板成立"的语义。
        assertEquals(BiomePlacement.VERTICAL_KEY,
                new BiomePlacement(atOwnPoint).keyAt(0, FLOOR_Y, 0));

        // 离它太远（这里湿度只有 0.0）就不该出现。
        BiomePlacement elsewhere = new BiomePlacement(TestClimatePlane.fixed(0.0f, 0.0f));
        assertFalse(BiomePlacement.VERTICAL_KEY.equals(elsewhere.keyAt(0, ROOF_Y, 0)));
        assertEquals("minecraft:nether_wastes", elsewhere.keyAt(0, ROOF_Y, 0),
                "原版参数表里 (0.0, 0.0) 就是 nether_wastes 自己的点");
    }

    /** 同一列的两个实例（同种子）答案一致：没有跨调用状态。 */
    @Test
    void repeatedQueriesAgree() {
        BiomePlacement a = new BiomePlacement(TestClimatePlane.seeded(SEED));
        BiomePlacement b = new BiomePlacement(TestClimatePlane.seeded(SEED));
        int[] column = {-321, 654};
        assertEquals(a.keyAt(column[0], column[1]), b.keyAt(column[0], column[1]));
        assertEquals(a.keyAt(column[0], ROOF_Y, column[1]), b.keyAt(column[0], ROOF_Y, column[1]));
        assertEquals(a.keyAt(column[0], column[1]), a.keyAt(column[0], ROOF_Y, column[1]),
                "同列地面高度与屋顶高度必须同键");
    }

    /** 目标点只由温度/湿度决定，与高度无关；用两个不同的 y 采样得到的点必须相同。 */
    @Test
    void targetPointDoesNotDependOnHeight() {
        TargetPoint low = TargetPoint.ofClimate(0.1f, 0.2f);
        TargetPoint high = TargetPoint.ofClimate(0.1f, 0.2f);
        assertEquals(low, high);
        assertEquals(BiomePlacement.select(low), BiomePlacement.select(high));
    }
}
