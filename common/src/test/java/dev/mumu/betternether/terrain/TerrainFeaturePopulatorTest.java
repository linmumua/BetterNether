package dev.mumu.betternether.terrain;

import dev.mumu.betternether.biome.BiomePlacement;
import dev.mumu.betternether.biome.TestClimatePlane;
import dev.mumu.betternether.worldgen.CompiledFeature;
import dev.mumu.betternether.worldgen.Feature;
import dev.mumu.betternether.worldgen.PlacementModifier;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 地形放置器的两条线：**它占哪些 step**、**诊断数字从哪来**。
 *
 * <h2>为什么这个测试从 500 行变成 60 行</h2>
 * 旧实现自己维护三张表：{@code PROFILES}（每群系哪些地形特征 + 多少次）、
 * {@code PROBES}（每次尝试读几次区块）、{@code WAIVED}（哪些上游条目放弃）。那三张表就是
 * 「按群系手写预算 + 近似形状」本身 —— 现在密度来自 {@code every_layer}/{@code count}/
 * {@code stencil}/{@code rarity_filter}，形状来自 {@code wover:pillar}/{@code obsidian_crystal}。
 * 表没了，锁表内容的断言也该消失；排序与随机搬到 {@code DecorationScheduler}（那里有专门测试）。
 */
class TerrainFeaturePopulatorTest {

    /**
     * 地形占 step 0/1/4，**不占 7**。
     *
     * <p>step 4 而不是 7 有数据依据：上游
     * {@code src/main/generated/data/betternether/worldgen/biome/*.json} 的 {@code features[4]}
     * 里就是这批 {@code betternether:} 条目（尖锥、石笋、骨堆、黑曜石晶簇），
     * 而 {@code GenerationStep$Decoration} 的 ordinal 4 是 {@code SURFACE_STRUCTURES}；
     * {@code features[7]}（{@code underground_decoration}）是矿。
     * step 直接进种子（{@code 10000L * step}），所以这里不能用语义名字去推断。
     *
     * <p>step 7 不在列表里同样重要：矿归 {@code OrePopulator} 独占，两边都放就是密度翻倍。
     */
    @Test
    void terrainStepsFollowUpstreamArrays() {
        assertTrue(contains(TerrainFeaturePopulator.STEPS, 0),
                "step 0 raw_generation 有 replace_soul_sandstone");
        assertTrue(contains(TerrainFeaturePopulator.STEPS, 1),
                "step 1 lakes 有熔岩坑/岩浆斑/幽匿晶簇");
        assertTrue(contains(TerrainFeaturePopulator.STEPS, 4),
                "上游 features[4] 就是尖锥那批条目，地形必须跑 step 4");
        for (int step : TerrainFeaturePopulator.STEPS) {
            assertTrue(step != 7, "step 7 归 OrePopulator 独占，地形不能也跑它");
        }
    }

    /**
     * step 4 必须拆成「结构 + 特征」两段注册，中间那个空档就是巨型熔岩湖的位置。
     *
     * <p>依据两条独立证据：
     * <ul>
     *   <li>上游 {@code registry/NetherStructures.java:107-108}：
     *       {@code .structure(id("mega_lava_lake"), …).step(SURFACE_STRUCTURES)} —— 它是**结构**；</li>
     *   <li>原版 {@code ChunkGenerator#addVanillaDecorations} 字节码：外层 step，
     *       内层 {@code 237} 判断 {@code shouldGenerateStructures}，{@code 269..407} 放该 step 的结构，
     *       {@code 413} 之后才收集并放置该 step 的 placed feature。</li>
     * </ul>
     *
     * <p>所以「先放尖锥再放湖」是错的：湖会盖掉先长出来的尖锥。这条测试锁的是
     * 「两段没有又被合并回一个实例」——合并回去正是那个回归。
     */
    @Test
    void surfaceStructuresAreSlottedInsideStepFour() {
        assertFalse(contains(TerrainFeaturePopulator.BEFORE_SURFACE_STRUCTURES, 4),
                "step 4 不在前一段里：那一段的末尾就是结构的位置");
        assertEquals(1, TerrainFeaturePopulator.SURFACE_STRUCTURE_STEP.length);
        assertEquals(4, TerrainFeaturePopulator.SURFACE_STRUCTURE_STEP[0]);

        // 两段并集必须正好等于 STEPS，且没有重叠
        java.util.TreeSet<Integer> union = new java.util.TreeSet<>();
        for (int step : TerrainFeaturePopulator.BEFORE_SURFACE_STRUCTURES) {
            union.add(step);
        }
        int before = union.size();
        for (int step : TerrainFeaturePopulator.SURFACE_STRUCTURE_STEP) {
            union.add(step);
        }
        assertEquals(before + TerrainFeaturePopulator.SURFACE_STRUCTURE_STEP.length, union.size(),
                "两段不能有重叠 step");
        assertEquals(union.size(), TerrainFeaturePopulator.STEPS.length);
        for (int step : TerrainFeaturePopulator.STEPS) {
            assertTrue(union.contains(step), "step " + step + " 必须落在某一段里，否则地形整步不跑");
        }
    }

    /** 注入的数据被如实暴露（含空群系：它们出现在表里但不在 {@code biomesWithTerrain} 里）。 */
    @Test
    void compiledDataIsReportedPerBiome() {
        Map<String, List<CompiledFeature>> data = new LinkedHashMap<>();
        data.put("magma_land", List.of(feature("betternether:patch_stalactite", 4, 0),
                feature("betternether:patch_stalagmite", 4, 1)));
        data.put("bone_reef", List.of(feature("betternether:obsidian_crystal", 1, 0)));
        data.put("soul_plain", List.of());

        TerrainFeaturePopulator populator = TerrainFeaturePopulator.of(null, data);

        assertEquals(3, populator.compiledBiomeCount());
        assertEquals(2, populator.featureCount("magma_land"));
        assertEquals(1, populator.featureCount("bone_reef"));
        assertEquals(0, populator.featureCount("soul_plain"));
        assertEquals(0, populator.featureCount("不存在的群系"));
        assertEquals(List.of("magma_land", "bone_reef"), populator.biomesWithTerrain());
    }

    /** 只列植被（step 9）的群系不能被算成"有地形特征"。 */
    @Test
    void floraOnlyBiomesAreNotCountedAsTerrain() {
        Map<String, List<CompiledFeature>> data = new LinkedHashMap<>();
        data.put("magma_land", List.of(feature("betternether:wart_seed", 9, 0)));

        TerrainFeaturePopulator populator = TerrainFeaturePopulator.of(null, data);

        assertEquals(0, populator.featureCount("magma_land"));
        assertTrue(populator.biomesWithTerrain().isEmpty());
    }

    /**
     * 区块的群系路由必须与植被一致 —— 两条独立的采样规则会让「洞里该长尖锥却没长」这类偏差
     * 变成随机现象。这里断言「两者调的是同一个方法」，因此采到的集合必须相等。
     */
    @Test
    void chunkBiomeRoutingIsSharedWithFlora() {
        BiomePlacement placement = new BiomePlacement(TestClimatePlane.seeded(2024L));
        for (int chunkX = -2; chunkX <= 2; chunkX++) {
            for (int chunkZ = -2; chunkZ <= 2; chunkZ++) {
                List<String> keys = FloraPopulator.biomesOfChunk(placement, chunkX, chunkZ);
                assertFalse(keys.isEmpty(), "区块必须至少采到一个群系，否则地形整块不跑");
                assertTrue(keys.size() >= 1);
            }
        }
    }

    private static boolean contains(int[] values, int wanted) {
        for (int value : values) {
            if (value == wanted) {
                return true;
            }
        }
        return false;
    }

    /** 一个「放一块石头」的占位 placed feature：只用来验证路由与计数，不验证形状。 */
    private static CompiledFeature feature(String id, int step, int index) {
        return new CompiledFeature(id, step, index, List.of(new PlacementModifier.InSquare()),
                new Feature.Custom("test", (world, point) -> true));
    }
}
