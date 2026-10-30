package dev.mumu.betternether.worldgen;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 地形算法特征的形状与随机数顺序。
 *
 * <h2>这里在锁什么</h2>
 * 上游地形特征之所以不能写成 JSON，是因为它们的形状是代码：{@code wover:pillar} 按
 * {@code transform} 逐格改属性、{@code wover:condition} 先跑一个修饰符再分叉、
 * {@code betternether:obsidian_crystal} 逐格算十二面体 SDF。这些都不是「看起来差不多就行」的
 * 观感问题 —— 少一次 {@code nextInt} 或把 {@code max_height} / {@code min_height} 的采样顺序
 * 调换，同一个 seed 就会生成另一个世界，而这种偏差在游戏里根本看不出来。
 *
 * <h2>为什么不启服务器</h2>
 * 放置逻辑只认 {@link WorldView} 与 {@link UpstreamTerrainFeatures.WorldgenAccess} 两个接口，
 * 所以测试注入 {@link FakeWorld} 与 {@link RecordingAccess}：
 * 前者是「一列列实心/空气」，后者把 {@code id + properties} 记下来并映射成可断言的材质。
 * 于是「第 3 格是 size=4 的状态」这种断言可以直接写出来，不依赖 CraftEngine，也不依赖注册表。
 */
class UpstreamTerrainFeaturesTest {
    /** {@code size} 0..7 → 八种可断言的羊毛；索引即属性值。 */
    private static final Material[] SIZES = {
            Material.WHITE_WOOL, Material.ORANGE_WOOL, Material.MAGENTA_WOOL,
            Material.LIGHT_BLUE_WOOL, Material.YELLOW_WOOL, Material.LIME_WOOL,
            Material.PINK_WOOL, Material.GRAY_WOOL
    };
    private static final Material BOTTOM_FALSE = Material.LIGHT_GRAY_WOOL;
    private static final Material BOTTOM_TRUE = Material.BLACK_WOOL;
    private static final Material SHAPE_BOTTOM = Material.RED_WOOL;
    private static final Material SHAPE_MIDDLE = Material.BLUE_WOOL;
    private static final Material SHAPE_TOP = Material.GREEN_WOOL;
    /** 没有属性的状态（晶体的调色板、简单方块）。 */
    private static final Material PLAIN = Material.STONE;

    // ---------------------------------------------------------------- wover:pillar

    /**
     * {@code transform: size_decrease}：尖端 {@code size=0}，往下逐格变粗，
     * 且公式是 {@code clamp(tip - height, 0, 7)}（{@code tip = max_height - 1}）。
     */
    @Test
    void sizeDecreaseWalksFromTheTipDown() {
        RecordingAccess access = new RecordingAccess();
        Feature feature = UpstreamTerrainFeatures.create("wover:pillar",
                json("""
                        {"type":"wover:pillar","direction":"up",
                         "min_height":0,"max_height":5,
                         "allowed_placement":{"type":"minecraft:true"},
                         "state":{"id":"betternether:netherrack_stalactite",
                                  "properties":{"size":"0"}},
                         "transform":"size_decrease"}"""), access.access());
        assertNotNull(feature, "wover:pillar 必须由本注册表接管（loader 里已没有它的 case）");

        FakeWorld world = new FakeWorld(0, 127, 1L);
        assertTrue(feature.place(world, new Point(0, 60, 0)));

        // max=5 → tip=4 → height 0..4 的 size 分别是 4,3,2,1,0
        Material[] expected = {SIZES[4], SIZES[3], SIZES[2], SIZES[1], SIZES[0]};
        for (int height = 0; height < expected.length; height++) {
            assertEquals(expected[height], world.materialAt(0, 60 + height, 0),
                    "第 " + height + " 格的 size 与 clamp(tip-height, 0, 7) 不一致");
        }
        assertTrue(world.isAir(0, 65, 0), "柱高必须正好是 max_height(5) 格");
    }

    /**
     * 采样顺序：上游先 {@code max_height} 再 {@code min_height}。
     *
     * <p>用两个上界不同的区间把顺序暴露出来（{@code uniform(3,6)} → {@code nextInt(4)}，
     * {@code uniform(0,1)} → {@code nextInt(2)}）。反过来实现会得到 {@code [2,4]}，世界就变了。
     */
    @Test
    void heightsAreSampledMaxFirst() {
        RecordingAccess access = new RecordingAccess();
        Feature feature = UpstreamTerrainFeatures.create("wover:pillar",
                json("""
                        {"type":"wover:pillar","direction":"up",
                         "min_height":{"type":"minecraft:uniform","min_inclusive":0,"max_inclusive":1},
                         "max_height":{"type":"minecraft:uniform","min_inclusive":3,"max_inclusive":6},
                         "allowed_placement":{"type":"minecraft:true"},
                         "state":{"id":"betternether:basalt_stalactite"},
                         "transform":"size_increase"}"""), access.access());
        assertNotNull(feature);

        RecordingRandom random = new RecordingRandom(11L);
        WorldView world = new RandomView(new FakeWorld(0, 127, 1L), random);
        assertTrue(feature.place(world, new Point(0, 60, 0)));
        assertEquals(List.of(4, 2), random.bounds,
                "必须先 sample max_height(上界 4) 再 sample min_height(上界 2)");
    }

    /** {@code max_height < min_height} 时整根放弃，一格都不写。 */
    @Test
    void tooShortAPillarPlacesNothing() {
        RecordingAccess access = new RecordingAccess();
        Feature feature = UpstreamTerrainFeatures.create("wover:pillar",
                json("""
                        {"type":"wover:pillar","direction":"up",
                         "min_height":3,"max_height":1,
                         "allowed_placement":{"type":"minecraft:true"},
                         "state":{"id":"betternether:bone_stalactite"},
                         "transform":"size_decrease"}"""), access.access());
        assertNotNull(feature);
        FakeWorld world = new FakeWorld(0, 127, 2L);
        assertFalse(feature.place(world, new Point(0, 60, 0)));
        assertTrue(world.isAir(0, 60, 0), "放弃时必须一格都不写");
    }

    /**
     * {@code transform: triple_shape_fill} 的额外前提：尖端上方那格**不能**还能放
     * （{@code canPlace = !allow.test(aboveTop)}）。空间不够时整根不出现。
     */
    @Test
    void tripleShapeFillRequiresRoomAbove() {
        RecordingAccess access = new RecordingAccess();
        Feature feature = UpstreamTerrainFeatures.create("wover:pillar",
                json("""
                        {"type":"wover:pillar","direction":"up",
                         "min_height":0,"max_height":3,
                         "allowed_placement":{"type":"minecraft:air"},
                         "state":{"id":"betternether:stalagnate"},
                         "transform":"triple_shape_fill"}"""), access.access());
        assertNotNull(feature);

        // 上方全是空气 → aboveTop 也满足 allowed_placement → 空间不够 → 不放置
        FakeWorld open = new FakeWorld(0, 127, 3L);
        assertFalse(feature.place(open, new Point(0, 60, 0)));
        assertTrue(open.isAir(0, 60, 0));

        // 第 3 格（aboveTop）是实心 → 空间正好 → 三格 shape 依次 bottom/middle/top
        FakeWorld capped = new FakeWorld(0, 127, 4L).solid(0, 63, 0, Material.NETHERRACK);
        assertTrue(feature.place(capped, new Point(0, 60, 0)));
        assertEquals(SHAPE_BOTTOM, capped.materialAt(0, 60, 0));
        assertEquals(SHAPE_MIDDLE, capped.materialAt(0, 61, 0));
        assertEquals(SHAPE_TOP, capped.materialAt(0, 62, 0));
    }

    // ---------------------------------------------------------------- wover:sequence / condition

    /** {@code wover:sequence}：每一条子 placed feature 都在同一位置上跑一次。 */
    @Test
    void sequencePlacesEveryChild() {
        RecordingAccess access = new RecordingAccess();
        access.placed.put("first", painter(Material.GOLD_BLOCK));
        access.placed.put("second", painter(Material.IRON_BLOCK));
        Feature feature = UpstreamTerrainFeatures.create("wover:sequence",
                json("""
                        {"type":"wover:sequence","features":["betternether:first",
                                                              "betternether:second"]}"""), access.access());
        assertNotNull(feature);
        FakeWorld world = new FakeWorld(0, 127, 5L);
        assertTrue(feature.place(world, new Point(1, 50, 1)));
        // 后一条覆盖前一条：两条都必须真的跑过（顺序 = 列表顺序）
        assertEquals(Material.IRON_BLOCK, world.materialAt(1, 50, 1));
        assertEquals(List.of("first", "second"), access.placedOrder);
    }

    /** {@code wover:condition}：{@code is_basin} 成立走 pass，否则走 fail。 */
    @Test
    void conditionBranchesOnTheBasinFilter() {
        RecordingAccess access = new RecordingAccess();
        access.predicate = new BlockPredicates.Solid();
        access.placed.put("pass", painter(Material.LAVA));
        access.placed.put("fail", painter(Material.BASALT));
        Feature feature = UpstreamTerrainFeatures.create("wover:condition",
                json("""
                        {"type":"wover:condition",
                         "filter":{"type":"wover:is_basin",
                                   "predicate":{"type":"minecraft:solid"}},
                         "filter_pass":"betternether:pass",
                         "filter_fail":"betternether:fail"}"""), access.access());
        assertNotNull(feature);

        // 盆地：下 + 东南西北 全是实心
        FakeWorld basin = new FakeWorld(0, 127, 6L)
                .solid(0, 59, 0, Material.NETHERRACK)
                .solid(1, 60, 0, Material.NETHERRACK)
                .solid(-1, 60, 0, Material.NETHERRACK)
                .solid(0, 60, 1, Material.NETHERRACK)
                .solid(0, 60, -1, Material.NETHERRACK);
        assertTrue(feature.place(basin, new Point(0, 60, 0)));
        assertEquals(Material.LAVA, basin.materialAt(0, 60, 0), "盆地里必须走 filter_pass");

        FakeWorld ledge = new FakeWorld(0, 127, 7L).solid(0, 59, 0, Material.NETHERRACK);
        assertTrue(feature.place(ledge, new Point(0, 60, 0)));
        assertEquals(Material.BASALT, ledge.materialAt(0, 60, 0), "不是盆地必须走 filter_fail");
    }

    /** 没有实现过的 filter 类型：整条失败（而不是「条件永远成立」把熔岩灌到所有地方）。 */
    @Test
    void unknownConditionFilterFailsClosed() {
        RecordingAccess access = new RecordingAccess();
        access.placed.put("pass", painter(Material.LAVA));
        Feature feature = UpstreamTerrainFeatures.create("wover:condition",
                json("""
                        {"type":"wover:condition",
                         "filter":{"type":"wover:not_a_filter"},
                         "filter_pass":"betternether:pass"}"""), access.access());
        assertNull(feature, "未知 filter 必须让这条 placed feature 整条跳过");
        assertTrue(UpstreamTerrainFeatures.loadNotes().stream()
                        .anyMatch(note -> note.contains("wover:not_a_filter")),
                "跳过的原因必须出现在 notes 里，不能静默");
    }

    // ---------------------------------------------------------------- 晶体

    /**
     * {@code betternether:obsidian_crystal}：先向下找地面，再以它为中心长一个十二面体。
     *
     * <p>断言三件事：真的写了方块、全部落在维度内（{@code minY < y < maxHeight-2}）、
     * 用的都是调色板里的材质。外加「同 seed 同结果」—— 形状是逐格 SDF，不写死就会漂。
     */
    @Test
    void obsidianCrystalGrowsAroundTheSurface() {
        RecordingAccess access = new RecordingAccess();
        Feature feature = UpstreamTerrainFeatures.create("betternether:obsidian_crystal",
                json("{\"type\":\"betternether:obsidian_crystal\"}"), access.access());
        assertNotNull(feature);

        FakeWorld world = new FakeWorld(0, 127, 42L).fill(0, 0, 0, 40, Material.NETHERRACK);
        assertTrue(feature.place(world, new Point(0, 60, 0)));

        int written = 0;
        for (int y = 1; y <= 125; y++) {
            for (int x = -12; x <= 12; x++) {
                for (int z = -12; z <= 12; z++) {
                    Material material = world.materialAt(x, y, z);
                    if (material == Material.NETHERRACK || material == Material.AIR) {
                        continue;
                    }
                    written++;
                    assertTrue(material == PLAIN, "晶体只能写调色板材质，出现了 " + material);
                }
            }
        }
        assertTrue(written > 0, "在实心地面之上必须长出晶体");

        // 同 seed、同一份数据 → 同一批方块（逐格 SDF + 固定噪声）
        FakeWorld again = new FakeWorld(0, 127, 42L).fill(0, 0, 0, 40, Material.NETHERRACK);
        Feature repeat = UpstreamTerrainFeatures.create("betternether:obsidian_crystal",
                json("{\"type\":\"betternether:obsidian_crystal\"}"), access.access());
        assertNotNull(repeat);
        assertTrue(repeat.place(again, new Point(0, 60, 0)));
        for (int y = 1; y <= 125; y++) {
            for (int x = -12; x <= 12; x++) {
                for (int z = -12; z <= 12; z++) {
                    assertEquals(world.materialAt(x, y, z), again.materialAt(x, y, z),
                            "同 seed 的晶体形状必须一致（" + x + "," + y + "," + z + "）");
                }
            }
        }
    }

    /** 找不到地面就不放（不消耗任何随机数的前提下返回 false）。 */
    @Test
    void obsidianCrystalNeedsASurface() {
        RecordingAccess access = new RecordingAccess();
        Feature feature = UpstreamTerrainFeatures.create("betternether:obsidian_crystal",
                json("{\"type\":\"betternether:obsidian_crystal\"}"), access.access());
        assertNotNull(feature);
        FakeWorld empty = new FakeWorld(0, 127, 8L);
        assertFalse(feature.place(empty, new Point(0, 60, 0)));
    }

    // ---------------------------------------------------------------- 注册表边界

    /** 别人的 type 必须返回 null，否则 loader 的注册表链会把植被/树截胡。 */
    @Test
    void foreignTypesAreNotClaimed() {
        RecordingAccess access = new RecordingAccess();
        for (String type : List.of("minecraft:simple_block", "wover:random_patch",
                "betternether:wart_cap", "minecraft:ore")) {
            assertNull(UpstreamTerrainFeatures.create(type, json("{\"type\":\"" + type + "\"}"), access.access()),
                    "本类不该接管 " + type);
        }
        assertTrue(UpstreamTerrainFeatures.supportedTypes().contains("wover:pillar"));
    }

    // ---------------------------------------------------------------- 工具

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    /** 一个只写固定材质的 placer，用来验证 sequence 的顺序。 */
    private static Feature painter(Material material) {
        return new Feature.Custom("test", (world, point) -> {
            world.setBlock(point.x(), point.y(), point.z(), FakeWorld.blockData(material));
            return true;
        });
    }

    /**
     * 假的 {@link UpstreamTerrainFeatures.WorldgenAccess}：记下每次方块解析请求，
     * 并按属性值给出可断言的材质。
     */
    static final class RecordingAccess {
        final Map<String, Feature> placed = new LinkedHashMap<>();
        final List<String> placedOrder = new ArrayList<>();
        final List<String> blockRequests = new ArrayList<>();
        BlockPredicates.Predicate predicate = new BlockPredicates.Air();

        /** 三个方法引用打包成装载器用的那一个 record。 */
        UpstreamTerrainFeatures.WorldgenAccess access() {
            return new UpstreamTerrainFeatures.WorldgenAccess(this::block, this::predicate, this::placed);
        }

        BlockData block(String id, JsonObject properties) {
            blockRequests.add(id + (properties == null ? "" : properties.toString()));
            return FakeWorld.blockData(materialFor(id, properties));
        }

        BlockPredicates.Predicate predicate(JsonObject json) {
            return predicate;
        }

        Feature placed(JsonElement element) {
            if (element == null || !element.isJsonPrimitive()) {
                return null;
            }
            String id = element.getAsString();
            String shortName = id.contains(":") ? id.split(":", 2)[1] : id;
            placedOrder.add(shortName);
            return placed.get(shortName);
        }

        /** {@code size}/{@code bottom}/{@code shape} → 各自的羊毛，其它 → {@link #PLAIN}。 */
        private static Material materialFor(String id, JsonObject properties) {
            if (properties == null || properties.isEmpty()) {
                return PLAIN;
            }
            if (properties.has("size")) {
                return SIZES[Math.max(0, Math.min(7, properties.get("size").getAsInt()))];
            }
            if (properties.has("bottom")) {
                return properties.get("bottom").getAsBoolean() ? BOTTOM_TRUE : BOTTOM_FALSE;
            }
            if (properties.has("shape")) {
                return switch (properties.get("shape").getAsString()) {
                    case "bottom" -> SHAPE_BOTTOM;
                    case "middle" -> SHAPE_MIDDLE;
                    default -> SHAPE_TOP;
                };
            }
            return PLAIN;
        }
    }

    /**
     * 把 {@link FakeWorld} 的随机源换成可记录的 {@link Random}。
     *
     * <p>不直接给 {@code FakeWorld} 加构造器：那是几个测试共用的夹具，这里只需要换一个方法。
     */
    static final class RandomView implements WorldView {
        private final WorldView delegate;
        private final Random random;

        RandomView(WorldView delegate, Random random) {
            this.delegate = delegate;
            this.random = random;
        }

        @Override
        public boolean inRegion(int x, int y, int z) {
            return delegate.inRegion(x, y, z);
        }

        @Override
        public int minY() {
            return delegate.minY();
        }

        @Override
        public int maxY() {
            return delegate.maxY();
        }

        @Override
        public boolean isAir(int x, int y, int z) {
            return delegate.isAir(x, y, z);
        }

        @Override
        public BlockData blockAt(int x, int y, int z) {
            return delegate.blockAt(x, y, z);
        }

        @Override
        public void setBlock(int x, int y, int z, BlockData data) {
            delegate.setBlock(x, y, z, data);
        }

        @Override
        public String biomeAt(int x, int y, int z) {
            return delegate.biomeAt(x, y, z);
        }

        @Override
        public String decoratedBiome() {
            return delegate.decoratedBiome();
        }

        @Override
        public Random random() {
            return random;
        }
    }

    /** 记录 {@code nextInt(bound)} 的上界序列，用来锁「先 max 再 min」。 */
    static final class RecordingRandom extends Random {
        final List<Integer> bounds = new ArrayList<>();

        RecordingRandom(long seed) {
            super(seed);
        }

        @Override
        public int nextInt(int bound) {
            bounds.add(bound);
            return super.nextInt(bound);
        }
    }
}
