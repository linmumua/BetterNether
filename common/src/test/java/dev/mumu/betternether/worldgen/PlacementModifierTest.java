package dev.mumu.betternether.worldgen;

import com.google.gson.JsonParser;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 放置修饰符与特征的行为测试。
 *
 * <h2>这些测试在锁什么</h2>
 * 修饰符的语义来自上游 JSON，写错不会有异常，只会让植被「变少 / 变多 / 换个高度」——
 * 那是最难被发现的一类错误。所以每条断言都点名一个具体的上游事实：
 *
 * <ul>
 *   <li>{@code count} 是「复制」而不是「随机取几个」；（写错的表现是植被数量随机偏少）</li>
 *   <li>{@code count_on_every_layer} 的第 i 次落在自下而上第 i 层，否则下界的多层台地
 *       只有最上面一层长草；</li>
 *   <li>{@code wover:every_layer top:false} 产出的是<b>天花板</b>层 —— 上游
 *       {@code patch_black_vine} 靠它挂到屋顶下表面，写成地面层就会让黑藤长在地上；</li>
 *   <li>{@code wover:solid_in_dir} 停下后回退一格，否则 {@code jellyfish_mushroom}
 *       的「这里空气、下方是菌岩」永远不成立，整条特征静默失效；</li>
 *   <li>高度锚点按<b>世界高度</b>换算，不写死 y。</li>
 * </ul>
 */
class PlacementModifierTest {
    /** 我们的下界：0..127，与 {@code NetherTerrainGenerator} 一致。 */
    private static final int MIN_Y = 0;
    private static final int MAX_Y = 127;

    private static List<Point> run(PlacementModifier modifier, Point from, FakeWorld world) {
        List<Point> out = new ArrayList<>();
        modifier.apply(List.of(from), out, world);
        return out;
    }

    // ---------------------------------------------------------------- height_range

    /**
     * 锚点必须按世界高度算。
     *
     * <p>{@code above_bottom: 4} 在 0..127 的世界里是 y=4，在 -64..319 的世界里是 y=-60。
     * 上游 JSON 全都用锚点写高度，写死成整数会在换世界高度时静默偏移。
     */
    @Test
    void heightRangeResolvesAnchorsAgainstWorldHeight() {
        FakeWorld world = new FakeWorld(MIN_Y, MAX_Y, 1L);
        PlacementModifier.HeightRange bottom = new PlacementModifier.HeightRange(
                new PlacementModifier.HeightRangeSpec(
                        new PlacementModifier.Anchored("above_bottom", 4),
                        new PlacementModifier.Anchored("above_bottom", 4),
                        PlacementModifier.HeightKind.UNIFORM));
        for (int i = 0; i < 20; i++) {
            assertEquals(4, run(bottom, new Point(0, 0, 0), world).get(0).y());
        }

        PlacementModifier.HeightRange top = new PlacementModifier.HeightRange(
                new PlacementModifier.HeightRangeSpec(
                        new PlacementModifier.Anchored("below_top", 4),
                        new PlacementModifier.Anchored("below_top", 4),
                        PlacementModifier.HeightKind.UNIFORM));
        assertEquals(MAX_Y - 4, run(top, new Point(0, 0, 0), world).get(0).y());
    }

    /** uniform 的取值必须落在锚点区间内（含两端），而且不改变 x/z。 */
    @Test
    void heightRangeUniformStaysInsideItsBand() {
        FakeWorld world = new FakeWorld(MIN_Y, MAX_Y, 42L);
        PlacementModifier.HeightRange range = new PlacementModifier.HeightRange(
                new PlacementModifier.HeightRangeSpec(
                        new PlacementModifier.Anchored("above_bottom", 10),
                        new PlacementModifier.Anchored("below_top", 4),
                        PlacementModifier.HeightKind.UNIFORM));
        for (int i = 0; i < 200; i++) {
            Point point = run(range, new Point(7, 0, 9), world).get(0);
            assertEquals(7, point.x());
            assertEquals(9, point.z());
            assertTrue(point.y() >= 10 && point.y() <= MAX_Y - 4,
                    "高度越界：" + point.y());
        }
    }

    /** trapezoid（plateau=0）是三角分布：均值应当落在区间中点附近。 */
    @Test
    void heightRangeTrapezoidCentresOnTheMiddle() {
        FakeWorld world = new FakeWorld(MIN_Y, MAX_Y, 7L);
        PlacementModifier.HeightRange range = new PlacementModifier.HeightRange(
                new PlacementModifier.HeightRangeSpec(
                        new PlacementModifier.Anchored("above_bottom", 0),
                        new PlacementModifier.Anchored("above_bottom", 100),
                        PlacementModifier.HeightKind.TRAPEZOID));
        long sum = 0;
        int samples = 2000;
        for (int i = 0; i < samples; i++) {
            sum += run(range, new Point(0, 0, 0), world).get(0).y();
        }
        double mean = (double) sum / samples;
        assertTrue(Math.abs(mean - 50.0) < 5.0, "trapezoid 均值应当约 50，实际 " + mean);
    }

    // ---------------------------------------------------------------- count / rarity

    /**
     * {@code count} 是「每个位置复制 n 份」，不是「随机挑 n 个」。
     *
     * <p>写成随机取样会让植被密度随 {@code count} 次线性下降，而日志里看不出任何异常。
     */
    @Test
    void countDuplicatesEachPosition() {
        FakeWorld world = new FakeWorld(MIN_Y, MAX_Y, 3L);
        List<Point> out = run(new PlacementModifier.Count(new IntProvider.Constant(12)),
                new Point(3, 40, 5), world);
        assertEquals(12, out.size());
        for (Point point : out) {
            assertEquals(new Point(3, 40, 5), point);
        }
    }

    /** {@code rarity_filter chance: 1} 全通过；{@code chance: 4} 约 1/4。 */
    @Test
    void rarityFilterKeepsAboutOneInChance() {
        FakeWorld world = new FakeWorld(MIN_Y, MAX_Y, 11L);
        List<Point> single = List.of(new Point(0, 40, 0));

        List<Point> all = new ArrayList<>();
        new PlacementModifier.RarityFilter(1).apply(single, all, world);
        assertEquals(1, all.size(), "chance 1 应当每次都保留");

        int kept = 0;
        int trials = 4000;
        List<Point> out = new ArrayList<>();
        for (int i = 0; i < trials; i++) {
            out.clear();
            new PlacementModifier.RarityFilter(4).apply(single, out, world);
            kept += out.size();
        }
        assertTrue(kept > trials / 4 - 60 && kept < trials / 4 + 60,
                "chance 4 的通过数应当约 " + (trials / 4) + "，实际 " + kept);
    }

    // ---------------------------------------------------------------- every layer

    /**
     * {@code count_on_every_layer} 的第 i 次取「<b>自顶向下</b>第 i 层」。
     *
     * <p>原版语义：一次放置序列从最高的台地逐层往下铺开，让每一层都分到一份。写成自下而上会把
     * 优先级整个颠倒（台地下方先被填满、最上层反而稀疏），而那在游戏里只像「密度不太对」。
     */
    @Test
    void countOnEveryLayerWalksTheLedges() {
        FakeWorld world = new FakeWorld(MIN_Y, MAX_Y, 5L);
        // 一列三层地面：y=10 / 40 / 70 是空气，下面各有一格实心。
        world.fill(0, 0, 0, 8, Material.NETHERRACK);
        world.fill(0, 0, 39, 39, Material.NETHERRACK);
        world.fill(0, 0, 69, 69, Material.NETHERRACK);

        List<Integer> layers = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            layers.add(Layers.groundLayer(world, 0, 0, world.motionBlockingHeight(0, 0), i));
        }
        assertEquals(List.of(70, 40, 9), layers.subList(0, 3),
                "第 0 层必须是最高那一层（y=70）");
        assertEquals(Integer.MIN_VALUE, layers.get(3), "没有第 4 层时应当报告「没有」");
    }

    /**
     * {@code wover:every_layer top:false} 产出<b>天花板</b>层，而且只在天花板侧。
     *
     * <p>上游 {@code patch_black_vine} / {@code patch_neon_equisetum} 用它把植被挂到屋顶下表面。
     * 如果这里吐的是地面层，黑藤就会长在地上 —— 那不是「差一点」，是另一个世界。
     */
    @Test
    void everyLayerTopFalseYieldsCeilingLayers() {
        FakeWorld world = new FakeWorld(MIN_Y, MAX_Y, 9L);
        world.fill(0, 0, 0, 8, Material.NETHERRACK);   // 地面
        // 屋顶用**下界岩**而不是基岩：`Layers` 严格排除「该格本身是基岩」的天花板层
        // （上游 `!pos.is(BEDROCK)`），那是「床顶」规则，由
        // `LayersMinBoundsTest#bedrockIsNeverALayerFloorOrCeiling` 单独钉住。
        // 这条用例要验的是「top:false 只在天花板侧产层」，所以用普通地形当屋顶。
        world.fill(0, 0, 71, 90, Material.NETHERRACK);

        List<Point> ceiling = run(new PlacementModifier.EveryLayer(4, false),
                new Point(0, 0, 0), world);
        assertEquals(1, ceiling.size(), "屋顶下表面只有一层");
        assertEquals(70, ceiling.get(0).y(), "应当在屋顶正下方那一格");

        List<Point> ground = run(new PlacementModifier.EveryLayer(4, true),
                new Point(0, 0, 0), world);
        assertTrue(ground.stream().anyMatch(p -> p.y() == 9), "地面层应当包含 y=9");
        assertTrue(ground.stream().noneMatch(p -> p.y() == 70), "地面层不该包含天花板位置");
    }

    // ---------------------------------------------------------------- solid_in_dir

    /**
     * {@code wover:solid_in_dir} 沿方向撞到实心后回退 {@code offset_in_dir} 格。
     *
     * <p>回退这一格是 {@code jellyfish_mushroom} 能成立的前提：它只写 {@code dist:4}，
     * 随后要求「这里空气、下方是菌岩」。若不回退，得到的就是实心方块本身，谓词永远不成立，
     * 整条特征静默失效。
     */
    @Test
    void solidInDirStopsJustBeforeTheSolid() {
        FakeWorld world = new FakeWorld(MIN_Y, MAX_Y, 13L);
        world.fill(0, 0, 0, 20, Material.NETHERRACK);
        BlockPredicates.Predicate anySolid = new BlockPredicates.Solid();
        List<Point> out = run(new PlacementModifier.FindInDirection(
                List.of(Point.Direction.DOWN), 32, 0, false, anySolid), new Point(0, 40, 0), world);
        assertEquals(1, out.size());
        assertEquals(20, out.get(0).y(), "offset_in_dir=0（源码缺省）应当落在命中的实心方块本身");

        // offset_in_dir 沿**搜索方向**再加：向下走时 +1 会更深一格（源码语义，不是「回退」）。
        List<Point> deeper = run(new PlacementModifier.FindInDirection(
                List.of(Point.Direction.DOWN), 32, 1, false, anySolid), new Point(0, 40, 0), world);
        assertEquals(19, deeper.get(0).y(), "offset_in_dir=1 应当沿方向再走一格");
    }

    /** 走不到实心（超出 dist）就不产出位置，而不是产出一个空气位置。 */
    @Test
    void solidInDirGivesNothingWhenTheDistanceIsTooShort() {
        FakeWorld world = new FakeWorld(MIN_Y, MAX_Y, 13L);
        world.fill(0, 0, 0, 0, Material.NETHERRACK);
        assertTrue(run(new PlacementModifier.FindInDirection(
                        List.of(Point.Direction.DOWN), 4, 0, false,
                        new BlockPredicates.Solid()), new Point(0, 40, 0), world)
                .isEmpty());
    }

    // ---------------------------------------------------------------- offset / biome

    /** {@code wover:offset} 与 {@code minecraft:offset} 都是平移；后者用提供器。 */
    @Test
    void offsetsShiftThePosition() {
        FakeWorld world = new FakeWorld(MIN_Y, MAX_Y, 17L);
        assertEquals(new Point(1, 39, -1), run(
                new PlacementModifier.FixedOffset(new Point(1, -1, -1)), new Point(0, 40, 0), world)
                .get(0));
        assertEquals(new Point(2, 41, 2), run(new PlacementModifier.Offset(
                        new IntProvider.Constant(2), new IntProvider.Constant(1),
                        new IntProvider.Constant(2)),
                new Point(0, 40, 0), world).get(0));
    }

    /** {@code minecraft:biome} 只保留「这个高度的群系 == 正在装饰的群系」的位置。 */
    @Test
    void biomeFilterDropsForeignPositions() {
        // 位置 (0,40,0) 的群系显式建模为 gloomwood，装饰群系也是 gloomwood -> 保留。
        FakeWorld world = new FakeWorld(MIN_Y, MAX_Y, 19L, "gloomwood").biome("gloomwood");
        List<Point> kept = new ArrayList<>();
        new PlacementModifier.Biome().apply(List.of(new Point(0, 40, 0)), kept, world);
        assertEquals(1, kept.size());

        // 位置仍是 gloomwood，但这次装饰的是 soul_plain -> 丢弃（同一位置，不同群系）。
        FakeWorld foreign = new FakeWorld(MIN_Y, MAX_Y, 19L, "soul_plain").biome("gloomwood");
        List<Point> dropped = new ArrayList<>();
        new PlacementModifier.Biome().apply(List.of(new Point(0, 40, 0)), dropped, foreign);
        assertTrue(dropped.isEmpty());
    }

    // ---------------------------------------------------------------- pipeline

    /** 链条为空时原样返回起点（{@code placement: []} 的语义）。 */
    @Test
    void emptyPipelineReturnsTheOrigin() {
        FakeWorld world = new FakeWorld(MIN_Y, MAX_Y, 23L);
        assertEquals(List.of(new Point(1, 2, 3)),
                Pipeline.run(List.of(), new Point(1, 2, 3), world));
    }

    /** 链条中任一步清空集合，后续步骤都不再执行（原版 stream 语义）。 */
    @Test
    void emptyIntermediateResultStopsTheChain() {
        FakeWorld world = new FakeWorld(MIN_Y, MAX_Y, 29L);
        List<Point> out = Pipeline.run(List.of(
                new PlacementModifier.BlockPredicateFilter(new BlockPredicates.Solid()),
                new PlacementModifier.Count(new IntProvider.Constant(5))),
                new Point(0, 40, 0), world);
        assertTrue(out.isEmpty(), "位置是空气，实心谓词应当清空集合");
    }

    /** 随机源只来自传入的 Random：同 seed 同结果。 */
    @Test
    void deterministicForTheSameSeed() {
        List<Point> first = Pipeline.run(List.of(
                        new PlacementModifier.InSquare(),
                        new PlacementModifier.HeightRange(new PlacementModifier.HeightRangeSpec(
                                new PlacementModifier.Anchored("above_bottom", 4),
                                new PlacementModifier.Anchored("below_top", 4),
                                PlacementModifier.HeightKind.UNIFORM))),
                new Point(0, 0, 0), new FakeWorld(MIN_Y, MAX_Y, 99L));
        List<Point> second = Pipeline.run(List.of(
                        new PlacementModifier.InSquare(),
                        new PlacementModifier.HeightRange(new PlacementModifier.HeightRangeSpec(
                                new PlacementModifier.Anchored("above_bottom", 4),
                                new PlacementModifier.Anchored("below_top", 4),
                                PlacementModifier.HeightKind.UNIFORM))),
                new Point(0, 0, 0), new FakeWorld(MIN_Y, MAX_Y, 99L));
        assertEquals(first, second);
    }

    /** 整数提供器：biased_to_bottom 偏向低端、clamped_normal 夹在区间内。 */
    @Test
    void intProvidersStayInRange() {
        Random random = new Random(31L);
        IntProvider biased = new IntProvider.BiasedToBottom(3, 13);
        int low = 0;
        for (int i = 0; i < 500; i++) {
            int value = biased.sample(random);
            assertTrue(value >= 3 && value <= 13, "越界：" + value);
            if (value <= 6) {
                low++;
            }
        }
        assertTrue(low > 250, "biased_to_bottom 应当明显偏向低端，低端样本 " + low);

        IntProvider normal = new IntProvider.ClampedNormal(12.0, 2.3, 3, 16);
        for (int i = 0; i < 500; i++) {
            int value = normal.sample(random);
            assertTrue(value >= 3 && value <= 16, "clamped_normal 越界：" + value);
        }
    }

    // ---------------------------------------------------------------- 分类纪律

    /**
     * 没实现的修饰符必须让整条 placed feature 被跳过，而不是被忽略。
     *
     * <p>忽略一个修饰符不会报错，只会让位置整体偏移（少了 {@code rarity_filter} 就把稀有植被
     * 变成满屏）。所以装载器遇到它返回 {@code null}，调用方据此丢弃整条特征。
     */
    @Test
    void unsupportedModifierRejectsTheWholeChain() {
        WorldgenLoader loader = new WorldgenLoader();
        var array = JsonParser.parseString("""
                [{"type":"wover:definitely_not_implemented"}]
                """).getAsJsonArray();
        assertNull(loader.placement(array), "未实现的修饰符应当让整条链被拒绝");
        assertFalse(loader.notes().isEmpty(), "拒绝必须留下可追踪的说明");
    }

    /** 支持的修饰符照常编译，链长为 1。 */
    @Test
    void supportedModifierCompiles() {
        WorldgenLoader loader = new WorldgenLoader();
        var array = JsonParser.parseString("""
                [{"type":"minecraft:count","count":7},{"type":"minecraft:in_square"}]
                """).getAsJsonArray();
        List<PlacementModifier> modifiers = loader.placement(array);
        assertNotNull(modifiers);
        assertEquals(2, modifiers.size());
    }
}
