package dev.mumu.betternether.terrain;

import dev.mumu.betternether.biome.BiomePlacement;
import dev.mumu.betternether.biome.NetherBiomes;
import dev.mumu.betternether.biome.TestClimatePlane;
import dev.mumu.betternether.worldgen.CompiledFeature;
import dev.mumu.betternether.worldgen.Feature;
import dev.mumu.betternether.worldgen.PlacementModifier;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 植被放置器的两条线：**它占哪些 step**、**区块的群系怎么算**。
 *
 * <h2>为什么这个测试这么小</h2>
 * 排序与随机都搬到 {@code DecorationScheduler} 了（那里有专门测试）。留在这里的只有属于本类的东西：
 * step 归属与群系采样。手写清单、每区块预算、探针那些旧表已随上游 {@code placed_feature} 数据消失，
 * 对着已删除的常量写断言只会锁住死亡代码。
 */
class FloraPopulatorTest {

    /**
     * 植被不能碰 step 7。
     *
     * <p>{@code 7 underground_decoration} 是矿的 step（上游 {@code features[7]} 是
     * {@code minecraft:ore} 那批）。如果植被这一步也跑，同一条矿会被放两遍 ——
     * 密度翻倍，且运行期零报错。
     */
    @Test
    void floraLeavesOreStepToTheOrePopulator() {
        for (int step : FloraPopulator.STEPS) {
            assertTrue(step != 7,
                    "step 7 归 OrePopulator 独占，FloraPopulator 不能也跑它：当前 STEPS 含 7");
        }
        assertTrue(contains(FloraPopulator.STEPS, 9),
                "植被本体在 step 9（vegetal_decoration），漏了它整个世界没有植被");
    }

    /**
     * step 5/6 必须与 8/9/10 分开注册，中间夹 ore(7)。
     *
     * <p>执行顺序是 step 升序。整包 {@code {5,6,8,9,10}} 注册在 ore 之后时，
     * step 5/6 就晚于 step 7 执行 —— 只要 5/6 里将来有内容，顺序立刻是错的，
     * 而「谁先谁后」在运行期完全不会报错。
     */
    @Test
    void floraIsSplitAroundTheOreStep() {
        for (int step : FloraPopulator.BEFORE_ORE_STEP) {
            assertTrue(step < FloraPopulator.ORE_STEP,
                    "step " + step + " 必须早于 ore 的 " + FloraPopulator.ORE_STEP);
        }
        for (int step : FloraPopulator.AFTER_ORE_STEP) {
            assertTrue(step > FloraPopulator.ORE_STEP,
                    "step " + step + " 必须晚于 ore 的 " + FloraPopulator.ORE_STEP);
        }
        assertEquals(FloraPopulator.STEPS.length,
                FloraPopulator.BEFORE_ORE_STEP.length + FloraPopulator.AFTER_ORE_STEP.length,
                "两段并集必须正好等于 STEPS，不能漏也不能重叠");
        for (int step : FloraPopulator.STEPS) {
            assertTrue(step != FloraPopulator.ORE_STEP, "step 7 归 ore，植被不能也跑它");
        }
    }

    /** 每个群系 key 都能在某处被采到；采到的集合不能出现未声明的 key、也不能重复。 */
    @Test
    void everyChunkBiomeIsDeclared() {
        BiomePlacement placement = new BiomePlacement(TestClimatePlane.seeded(1234L));
        List<String> declared = new ArrayList<>(NetherBiomes.VANILLA_NETHER);
        for (NetherBiomes.Entry entry : NetherBiomes.ALL) {
            declared.add(entry.key());
        }
        for (int chunkX = -6; chunkX <= 6; chunkX++) {
            for (int chunkZ = -6; chunkZ <= 6; chunkZ++) {
                List<String> keys = FloraPopulator.biomesOfChunk(placement, chunkX, chunkZ);
                assertFalse(keys.isEmpty(), "区块 " + chunkX + "," + chunkZ + " 没有采到任何群系");
                for (String key : keys) {
                    assertTrue(declared.contains(key), "出现了未声明的群系 key：" + key);
                }
                assertEquals(keys.size(), keys.stream().distinct().count(),
                        "同一个群系不该重复出现");
            }
        }
    }

    /**
     * 倒挂森林的区块必须把 {@code upside_down_forest} 采进列表。
     *
     * <p>它的悬挂植被（黑藤、霓虹木贼、垂泪藤）只在区块的群系列表里含这个键时才会被放置，
     * 漏掉时日志里没有任何异常。
     */
    @Test
    void theVerticalBiomeIsCollectedWhereTheClimatePutsIt() {
        BiomePlacement placement = new BiomePlacement(TestClimatePlane.fixed(0.30f, 0.80f));
        List<String> keys = FloraPopulator.biomesOfChunk(placement, 0, 0);
        assertTrue(keys.contains(BiomePlacement.VERTICAL_KEY),
                "气候落在 upside_down_forest 的参数点上时，区块必须采到它");
    }

    /**
     * 采样必须落到 **3×3 区块邻域**，而不是只采本区块。
     *
     * <p>这条锁的是一个真实缺口：旧版只在**本区块内**取 3×3 个点，于是跨区块边界的植被
     * （树冠、尖锥）因为邻区块的群系看不到而整片缺失。原版读的是 3×3 区块的群系调色板。
     * 这里用一个记录型采样器直接断言「采样坐标确实越出了本区块」。
     */
    @Test
    void samplingReachesNeighbouringChunks() {
        int[] minX = {Integer.MAX_VALUE};
        int[] maxX = {Integer.MIN_VALUE};
        int[] minZ = {Integer.MAX_VALUE};
        int[] maxZ = {Integer.MIN_VALUE};
        BiomePlacement placement = new BiomePlacement((quartX, quartY, quartZ) -> {
            int blockX = quartX << 2;
            int blockZ = quartZ << 2;
            minX[0] = Math.min(minX[0], blockX);
            maxX[0] = Math.max(maxX[0], blockX);
            minZ[0] = Math.min(minZ[0], blockZ);
            maxZ[0] = Math.max(maxZ[0], blockZ);
            return dev.mumu.betternether.biome.TargetPoint.ofClimate(0.0f, 0.0f);
        });

        FloraPopulator.biomesOfChunk(placement, 0, 0);

        // 本区块是方块 0..15；邻域应是 -16..31（3 个区块）
        assertTrue(minX[0] <= -16, "采样没有越到西侧邻区块，最小 x = " + minX[0]);
        assertTrue(maxX[0] >= 28, "采样没有越到东侧邻区块，最大 x = " + maxX[0]);
        assertTrue(minZ[0] <= -16, "采样没有越到北侧邻区块，最小 z = " + minZ[0]);
        assertTrue(maxZ[0] >= 28, "采样没有越到南侧邻区块，最大 z = " + maxZ[0]);
    }

    /** 采样是确定性的：同一 seed、同一区块必须给出同一份列表（否则生成不可复现）。 */
    @Test
    void chunkBiomesAreDeterministic() {
        List<String> first = FloraPopulator.biomesOfChunk(
                new BiomePlacement(TestClimatePlane.seeded(7L)), 3, -5);
        List<String> second = FloraPopulator.biomesOfChunk(
                new BiomePlacement(TestClimatePlane.seeded(7L)), 3, -5);
        assertEquals(first, second);
    }

    /** 诊断方法只看植被那几步：step 4 的地形条目不能算进"有植被的群系"。 */
    @Test
    void diagnosticsOnlyCountFloraSteps() {
        Map<String, List<CompiledFeature>> data = new LinkedHashMap<>();
        data.put("magma_land", List.of(feature("betternether:patch_stalactite", 4, 0),
                feature("betternether:wart_seed", 9, 0)));
        data.put("bone_reef", List.of(feature("betternether:patch_bone_stalagmite_on_ground", 4, 0)));

        FloraPopulator populator = FloraPopulator.of(null, data);

        assertEquals(2, populator.compiledBiomeCount());
        assertEquals(1, populator.featureCount("magma_land"));
        assertEquals(0, populator.featureCount("bone_reef"),
                "只列了 step 4 地形条目的群系不该被算成有植被");
        assertEquals(List.of("magma_land"), populator.biomesWithFlora());
    }

    private static boolean contains(int[] values, int wanted) {
        for (int value : values) {
            if (value == wanted) {
                return true;
            }
        }
        return false;
    }

    private static CompiledFeature feature(String id, int step, int index) {
        return new CompiledFeature(id, step, index, List.of(new PlacementModifier.InSquare()),
                new Feature.Custom("test", (world, point) -> true));
    }
}
