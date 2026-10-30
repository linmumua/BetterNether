package dev.mumu.betternether.worldgen;

import com.google.gson.JsonParser;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 三个闭合的 terrain 子特征语义：{@code wover:full_shape}、{@code wover:mark_postprocessing}、
 * {@code betternether:avoid_nether_city}。
 *
 * <h2>为什么要专门锁它们</h2>
 * 这三条以前都没有真实实现，而它们的「没有实现」都有**看得见的后果**：
 * <ul>
 *   <li>{@code wover:full_shape} 落到装载器的「未知谓词按恒真处理」分支，于是
 *       {@code flooded_lava_pit_surface} 的 {@code is_basin(any_of(lava, full_shape))} 永远成立 ——
 *       岩浆坑被灌到所有盆地里，连「坑口只是台阶/楼梯围出来」的也灌；</li>
 *   <li>{@code wover:mark_postprocessing} 没有 case，{@code nether_terrain:mark} 整条 placed
 *       feature 编译不出来，灌完岩浆的格子不会重新结算；</li>
 *   <li>{@code betternether:avoid_nether_city} 没有 case，整条 `forest_litter` 被跳过（装载器的
 *       「未实现的修饰符 → 整条跳过」规则），或者退一步「恒通过」时落叶直接落进城市建筑。</li>
 * </ul>
 *
 * <h2>各自的真实语义（上游源码为准）</h2>
 * <ul>
 *   <li>{@code wover:full_shape} —— WorldWeaver {@code IsFullShape#test}：
 *       {@code getBlockState(pos.offset(offset)).isCollisionShapeFullBlock(level, pos)}，
 *       即**碰撞形状占满整格**，不是「非空气」；</li>
 *   <li>{@code wover:mark_postprocessing} —— {@code MarkPostProcessingFeature#place}：
 *       {@code level.getChunk(x>>4, z>>4).markPosForPostProcessing(局部坐标)}，返回 {@code true}，
 *       不写方块；</li>
 *   <li>{@code betternether:avoid_nether_city} —— {@code AvoidNetherCity#shouldPlace}：
 *       {@code reach} 内够得着城市（建筑，或 {@code include_cave} 时连洞穴）就丢弃该位置。</li>
 * </ul>
 *
 * <h2>这里能证明什么、不能证明什么</h2>
 * 生产实现（{@code RegionBlockAccess}）里的 NMS 跳（{@code isCollisionShapeFullBlock}、
 * {@code getAllReferences}/{@code getStartForStructure}）需要真服务端，单元测试不碰。
 * 这里锁的是**上层**：装载器是否真的接上了、判定是否照着 {@link WorldView} 的回答走、
 * 以及「问不出来」时是否 fail loud —— 那三条正是以前出问题的地方。
 */
class TerrainSubFeatureSemanticsTest {

    // ---------------------------------------------------------------- wover:full_shape

    /** 装载器必须把它编译成真实谓词，而不是「未知 → 恒真」。 */
    @Test
    void fullShapeCompilesToTheRealPredicate() {
        WorldgenLoader loader = new WorldgenLoader();

        BlockPredicates.Predicate compiled = loader.predicate(
                JsonParser.parseString("{\"type\":\"wover:full_shape\"}").getAsJsonObject());

        assertInstanceOf(BlockPredicates.FullShape.class, compiled,
                "wover:full_shape 必须是 FullShape —— 恒真的 Constant 会让岩浆坑多灌一地");

        // 上游的 IsFullShape 带可选 offset（Vec3i.offsetCodec(16)）；装载器的通用 Offset 包装即对应语义。
        BlockPredicates.Predicate shifted = loader.predicate(JsonParser.parseString(
                "{\"type\":\"wover:full_shape\",\"offset\":[0,-1,0]}").getAsJsonObject());
        BlockPredicates.Offset offset = assertInstanceOf(BlockPredicates.Offset.class, shifted,
                "声明了 offset 就必须包一层");
        assertEquals(new Point(0, -1, 0), offset.shift());
        assertInstanceOf(BlockPredicates.FullShape.class, offset.inner());
    }

    /**
     * 整块 vs 台阶 / 栅栏：判据是「碰撞形状占满整格」，所以台阶、栅栏都**不是** full shape。
     *
     * <p>假世界不能算形状，所以形状结果由测试显式声明；这里断言的是「谓词问了通道、
     * 并且照答案判」，而不是退回「非空气」。
     */
    @Test
    void fullShapeSeparatesCubesFromStairsAndFences() {
        BlockPredicates.resetForTests();
        FakeWorld world = new FakeWorld(0, 127, 21L)
                .solid(0, 60, 0, Material.NETHERRACK)
                .fullShape(0, 60, 0, true)
                .solid(1, 60, 0, Material.NETHER_BRICK_STAIRS)
                .fullShape(1, 60, 0, false)
                .solid(2, 60, 0, Material.NETHER_BRICK_FENCE)
                .fullShape(2, 60, 0, false);

        BlockPredicates.Predicate predicate = new BlockPredicates.FullShape();
        assertTrue(predicate.test(world, new Point(0, 60, 0)), "整块地形的碰撞形状填满整格");
        assertFalse(predicate.test(world, new Point(1, 60, 0)), "台阶不是整块：不能当成坑口盖板");
        assertFalse(predicate.test(world, new Point(2, 60, 0)), "栅栏不是整块：不能当成坑口盖板");
        assertTrue(BlockPredicates.degraded().isEmpty(),
                "精确通道可用时不许记账降级，实际记了：" + BlockPredicates.degraded());
    }

    /** 问不出真实碰撞形状时必须判假并记账 —— 宁可少灌岩浆，也不静默当成「盖满」。 */
    @Test
    void unknownFullShapeFailsClosedAndIsRecorded() {
        BlockPredicates.resetForTests();
        // 没声明 fullShape 的坐标 = 「这个假世界不知道它的碰撞形状」
        FakeWorld world = new FakeWorld(0, 127, 22L).solid(0, 60, 0, Material.NETHERRACK);

        assertFalse(new BlockPredicates.FullShape().test(world, new Point(0, 60, 0)),
                "问不出来时不能返回 true（恒通过就是这次要修的那个毛病）");
        assertTrue(BlockPredicates.degraded().contains("predicate:full_shape"),
                "降级必须记账，实际记了：" + BlockPredicates.degraded());
    }

    /**
     * {@code flooded_lava_pit_surface} 的真实决策：{@code is_basin(any_of(lava, full_shape))} ——
     * 也就是「下 + 东南西北五个位置**每一个**都是岩浆或整块地形」。
     *
     * <p>三个位置：
     * <ol>
     *   <li>坑里灌满岩浆 → 通过（上游第一条分支）；</li>
     *   <li>坑底是台阶（非岩浆、非整块），四周是整块地狱岩 → **不通过**：以前 {@code full_shape}
     *       恒真时这里会通过，于是岩浆被灌进「台阶围出来的坑」；</li>
     *   <li>坑底是整块地狱岩 → 通过（第二条分支：坑口被整块地形盖住）。</li>
     * </ol>
     * 用真的 {@link BlockPredicates} 与真的 {@link PlacementModifier.IsBasin}，只把世界换成假世界。
     */
    @Test
    void lavaPitBasinNeedsLavaOrAFullCap() {
        BlockPredicates.resetForTests();
        BlockPredicates.Predicate lava = new BlockPredicates.Blocks("minecraft:lava",
                List.of(blockData(Material.LAVA)));
        BlockPredicates.Predicate filter = new BlockPredicates.AnyOf(
                List.of(lava, new BlockPredicates.FullShape()));
        PlacementModifier basin = new PlacementModifier.IsBasin(filter, null);

        // 1) 坑里灌满岩浆：五个位置都是岩浆
        FakeWorld flooded = new FakeWorld(0, 127, 23L)
                .solid(0, 59, 0, Material.LAVA).fullShape(0, 59, 0, false)
                .solid(1, 60, 0, Material.LAVA).fullShape(1, 60, 0, false)
                .solid(-1, 60, 0, Material.LAVA).fullShape(-1, 60, 0, false)
                .solid(0, 60, 1, Material.LAVA).fullShape(0, 60, 1, false)
                .solid(0, 60, -1, Material.LAVA).fullShape(0, 60, -1, false);
        assertTrue(kept(basin, flooded), "坑里是岩浆：必须通过（哪怕岩浆不是整块形状）");

        // 2) 坑底是台阶、四周是整块地狱岩：台阶既不是岩浆也不是整块 → 拒绝
        FakeWorld stairs = new FakeWorld(0, 127, 24L)
                .solid(0, 59, 0, Material.NETHER_BRICK_STAIRS).fullShape(0, 59, 0, false)
                .solid(1, 60, 0, Material.NETHERRACK).fullShape(1, 60, 0, true)
                .solid(-1, 60, 0, Material.NETHERRACK).fullShape(-1, 60, 0, true)
                .solid(0, 60, 1, Material.NETHERRACK).fullShape(0, 60, 1, true)
                .solid(0, 60, -1, Material.NETHERRACK).fullShape(0, 60, -1, true);
        assertFalse(kept(basin, stairs),
                "坑底只是台阶：既不是岩浆也不是整块，必须拒绝（这就是「岩浆坑多灌」的回归）");

        // 3) 坑底是整块地狱岩：通过
        FakeWorld capped = new FakeWorld(0, 127, 25L)
                .solid(0, 59, 0, Material.NETHERRACK).fullShape(0, 59, 0, true)
                .solid(1, 60, 0, Material.NETHERRACK).fullShape(1, 60, 0, true)
                .solid(-1, 60, 0, Material.NETHERRACK).fullShape(-1, 60, 0, true)
                .solid(0, 60, 1, Material.NETHERRACK).fullShape(0, 60, 1, true)
                .solid(0, 60, -1, Material.NETHERRACK).fullShape(0, 60, -1, true);
        assertTrue(kept(basin, capped), "坑底是整块地形：必须通过");
        assertTrue(BlockPredicates.degraded().isEmpty(),
                "三种位置都走了精确通道，不该有降级：" + BlockPredicates.degraded());
    }

    // ---------------------------------------------------------------- wover:mark_postprocessing

    /**
     * {@code wover:mark_postprocessing}：把原点放进后处理队列，返回 {@code true}，不写方块。
     *
     * <p>{@code nether_terrain:mark} 的 placement 只有 {@code matching_blocks lava}，
     * 所以「标记的就是岩浆那一格」—— 坐标必须原样传出（负坐标不许被 {@code >>4}/{@code &15}
     * 之类的换算吃掉，那是区块内局部坐标与全局坐标最容易搞混的地方）。
     */
    @Test
    void markPostProcessingMarksTheOriginAndWritesNothing() {
        Feature feature = UpstreamTerrainFeatures.create("wover:mark_postprocessing",
                JsonParser.parseString("{\"type\":\"wover:mark_postprocessing\"}").getAsJsonObject(),
                new UpstreamTerrainFeatures.WorldgenAccess(null, null, null));
        assertNotNull(feature, "wover:mark_postprocessing 必须由地形注册表接管");

        FakeWorld world = new FakeWorld(0, 127, 26L);
        assertTrue(feature.place(world, new Point(-37, 33, 21)), "上游的 place() 恒返回 true");

        assertEquals(1, world.postProcessed().size(), "必须真的标记了一次后处理");
        assertArrayEquals(new int[]{-37, 33, 21}, world.postProcessed().get(0),
                "标记的坐标必须原样是特征原点");
        assertTrue(world.isAir(-37, 33, 21), "这个特征不写任何方块");
    }

    /**
     * 默认实现（没有桥接的 {@link WorldView}）必须**记账**而不是静默吞掉后处理标记。
     */
    @Test
    void markPostProcessingWithoutBridgeIsRecorded() {
        BlockPredicates.resetForTests();
        FakeWorld fake = new FakeWorld(0, 127, 27L);
        // 只覆写核心读写、**不**覆写 markForPostProcessing：走 WorldView 的默认实现。
        WorldView bare = new WorldView() {
            @Override
            public boolean inRegion(int x, int y, int z) {
                return fake.inRegion(x, y, z);
            }

            @Override
            public int minY() {
                return fake.minY();
            }

            @Override
            public int maxY() {
                return fake.maxY();
            }

            @Override
            public boolean isAir(int x, int y, int z) {
                return fake.isAir(x, y, z);
            }

            @Override
            public BlockData blockAt(int x, int y, int z) {
                return fake.blockAt(x, y, z);
            }

            @Override
            public void setBlock(int x, int y, int z, BlockData data) {
                fake.setBlock(x, y, z, data);
            }

            @Override
            public String biomeAt(int x, int y, int z) {
                return fake.biomeAt(x, y, z);
            }

            @Override
            public String decoratedBiome() {
                return fake.decoratedBiome();
            }

            @Override
            public java.util.Random random() {
                return fake.random();
            }

        };

        bare.markForPostProcessing(3, 40, 5);

        assertTrue(BlockPredicates.degraded().contains("post_process"),
                "没有桥接时必须记进降级账本，实际记了：" + BlockPredicates.degraded());
        assertTrue(fake.postProcessed().isEmpty(), "默认实现当然没有真的标记");
    }

    // ---------------------------------------------------------------- avoid_nether_city

    /** {@code reach} 与 {@code include_cave} 必须从 JSON 原样读出（reach 是必填的 0..64）。 */
    @Test
    void avoidNetherCityCompilesWithItsReachAndCaveFlag() {
        WorldgenLoader loader = new WorldgenLoader();
        List<PlacementModifier> modifiers = loader.placement(JsonParser.parseString("""
                [{"type":"betternether:avoid_nether_city","reach":8,"include_cave":true}]
                """).getAsJsonArray());

        assertNotNull(modifiers, "合法的 avoid_nether_city 不该让整条链被跳过");
        PlacementModifier.AvoidNetherCity filter = assertInstanceOf(
                PlacementModifier.AvoidNetherCity.class, modifiers.get(0));
        assertEquals(8, filter.reach());
        assertTrue(filter.includeCave(), "include_cave=true 必须读出来（forest_litter 用的就是 true）");
    }

    /** 缺 {@code reach}（或越界）必须抛：猜默认值会让落叶静默落进建筑里。 */
    @Test
    void avoidNetherCityWithoutReachIsRejected() {
        WorldgenLoader loader = new WorldgenLoader();
        assertThrows(IllegalStateException.class, () -> loader.placement(
                        JsonParser.parseString("[{\"type\":\"betternether:avoid_nether_city\"}]").getAsJsonArray()),
                "上游是 intRange(0,64) 的必填字段");
        assertThrows(IllegalStateException.class, () -> loader.placement(JsonParser.parseString(
                        "[{\"type\":\"betternether:avoid_nether_city\",\"reach\":65}]").getAsJsonArray()),
                "越界的 reach 必须拒绝");
    }

    /**
     * 够得着城市的位置被丢弃，够不着的保留；{@code reach} 参与判定；**负坐标**同样正确。
     *
     * <p>城市建模成 {@code x/z ∈ [-40,-10] × [-30,-5]}（真实数据里城市可以出现在任意象限，
     * 负坐标下的区块换算是这类代码最容易写错的地方，所以这里专挑负数）。
     */
    @Test
    void avoidNetherCityDropsPositionsThatCanReachTheCity() {
        BlockPredicates.resetForTests();
        FakeWorld world = new FakeWorld(0, 127, 28L).cityBox(-40, 32, -30, -10, 64, -5);
        PlacementModifier filter = new PlacementModifier.AvoidNetherCity(8, true);

        assertFalse(kept(filter, world, new Point(-20, 50, -18)), "城市正上方：必须丢弃");
        assertTrue(kept(filter, world, new Point(-100, 50, -18)), "离城市很远：必须保留");
        assertFalse(kept(filter, world, new Point(-45, 50, -18)),
                "距离城市边缘 5 格 < reach=8：必须丢弃（reach 就是「够不够得着」）");
        assertTrue(kept(filter, world, new Point(-60, 50, -18)),
                "距离城市边缘 20 格 > reach=8：必须保留");
        assertTrue(BlockPredicates.degraded().isEmpty(),
                "结构数据在手里时不记账，实际记了：" + BlockPredicates.degraded());
    }

    /** 问不到结构 starts 时**按「在城里」丢弃并记账**：宁可少放落叶，也不塞进建筑。 */
    @Test
    void avoidNetherCityFailsClosedWithoutStructureData() {
        BlockPredicates.resetForTests();
        FakeWorld withoutCity = new FakeWorld(0, 127, 29L);   // 没建模城市 = 问不到
        PlacementModifier filter = new PlacementModifier.AvoidNetherCity(8, false);

        assertFalse(kept(filter, withoutCity, new Point(0, 50, 0)), "问不出来时必须丢弃");
        assertTrue(BlockPredicates.degraded().contains("modifier:avoid_nether_city"),
                "降级必须记账，实际记了：" + BlockPredicates.degraded());
    }

    // ---------------------------------------------------------------- 夹具

    /** 单个位置过一遍修饰符：留下了 = true。 */
    private static boolean kept(PlacementModifier modifier, WorldView world) {
        return kept(modifier, world, new Point(0, 60, 0));
    }

    private static boolean kept(PlacementModifier modifier, WorldView world, Point point) {
        List<Point> out = new ArrayList<>();
        modifier.apply(List.of(point), out, world);
        if (out.size() > 1) {
            throw new AssertionError("判定类修饰符不该扩容：in=1, out=" + out);
        }
        return out.size() == 1;
    }

    /** 只回答 {@code getMaterial()} 的方块状态（与 {@code FakeWorld} 同一套代理）。 */
    private static BlockData blockData(Material material) {
        return FakeWorld.blockData(material);
    }
}
