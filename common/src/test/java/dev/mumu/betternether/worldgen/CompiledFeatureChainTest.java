package dev.mumu.betternether.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

/**
 * 生产入口 {@link CompiledFeature#placeAt} 与 {@link Feature.Wrapped} 的执行语义。
 *
 * <h2>为什么这两个入口要单独钉住</h2>
 * 它们都是「把一条修饰符链落到世界里」的<b>最终</b>出口：{@code CompiledFeature.placeAt} 由
 * {@link DecorationScheduler} 直接调用，{@link Feature.Wrapped} 是 {@code random_patch} /
 * {@code overlay} / {@code random_selector} 子项的唯一承载方式。两者此前都是
 * 「先 {@link Pipeline#run} 收齐位置、再 {@link Pipeline#place} 逐个放」的批量写法，与上游
 * {@code FeaturePlacer} 的栈式 DFS 随机流不同 —— 位置总量一样，落点不一样，而且不会报错。
 *
 * <p>{@link PipelineChainTest} 只钉住了 {@link Pipeline#placeChain} 本身；只要生产入口仍然
 * 调用 {@code run} + {@code place}，那条测试是绿的，世界仍然是错的。所以这里对**生产入口**
 * 再断言一次：
 * <ol>
 *   <li>与一份**独立写的** DFS 参考实现给出<b>逐点相同</b>的放置顺序；</li>
 *   <li>与批量写法给出<b>不同</b>的顺序（反过来证明参考实现没被写成批量语义）；</li>
 *   <li>返回值是「成功放置次数」，不是「被发射的位置数」；</li>
 *   <li>链把位置过滤空时，特征一次都不被调用、返回 0 / {@code false}。</li>
 * </ol>
 */
class CompiledFeatureChainTest {
    private static final int MIN_Y = 0;
    private static final int MAX_Y = 127;

    /**
     * 记录每次 {@code place} 的落点。
     *
     * <p>{@link Feature} 是 {@code sealed}（permits 只含同文件的嵌套类型），测试不能自己实现它，
     * 所以用生产的 {@link Feature.Custom} 承载记录动作 —— 不为测试去动生产的 permits 列表。
     */
    private static Feature recorder(List<Point> hits) {
        return new Feature.Custom("test-recorder", (world, point) -> {
            hits.add(point);
            return true;
        });
    }

    /**
     * 一列**两层**地面（y=39 与 y=29 是石头，于是地面层是 y=40 与 y=30）。
     *
     * <p>两层是必需的：{@link PlacementModifier.CountOnEveryLayer} 的迭代加深要能跑第二轮，
     * 只有一层时「逐位置就地执行」与「批量变形」会退化成等价，测试也就什么都没证明。
     */
    private static FakeWorld twoLayerWorld(long seed) {
        FakeWorld world = new FakeWorld(MIN_Y, MAX_Y, seed);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                world.solid(x, 39, z, Material.STONE);
                world.solid(x, 29, z, Material.STONE);
            }
        }
        return world;
    }

    /**
     * 测试内的**独立 DFS 参考实现**：一个位置交给一个修饰符后，它的每个产物<b>立刻</b>深入下一级，
     * 走完才回到本修饰符的下一个产物 —— 即上游 {@code FeaturePlacer} 的栈式顺序。
     *
     * <p>刻意不调用 {@link Pipeline#placeChain}（否则只是把被测代码抄一遍）。这里按
     * {@link PlacementModifier#visit} 的<b>契约</b>展开：{@code visit} 就是「逐位置就地」的
     * 定义本身，参考实现只负责把叶子位置收进列表。
     *
     * <p>注意为什么不能写成「先用 {@code apply} 展开整级、再逐个递归」：那样一级里的所有产物会
     * 先算完，下游的随机消耗全部被推到后面，于是它与批量管线同序 —— 这正是
     * {@code CountOnEveryLayer#visit} 必须惰性化的原因，也是本测试要区分的两种语义。
     */
    private static List<Point> referenceDfs(List<PlacementModifier> chain, Point origin,
                                            WorldView world) {
        List<Point> leaves = new ArrayList<>();
        descendReference(chain, 0, origin, world, leaves);
        return leaves;
    }

    private static void descendReference(List<PlacementModifier> chain, int index, Point point,
                                         WorldView world, List<Point> leaves) {
        if (index >= chain.size()) {
            leaves.add(point);
            return;
        }
        chain.get(index).visit(point, world,
                next -> descendReference(chain, index + 1, next, world, leaves));
    }

    /** 两条抽样级混排：上游 {@code count_on_every_layer} 后接 {@code in_square}。 */
    private static List<PlacementModifier> twoSamplingLevels() {
        return List.of(
                new PlacementModifier.CountOnEveryLayer(new IntProvider.Constant(2)),
                new PlacementModifier.InSquare());
    }

    // ------------------------------------------------------------ CompiledFeature.placeAt

    /**
     * 生产入口的放置顺序必须与 DFS 参考实现**逐点相同**，且与批量写法不同。
     *
     * <p>两个世界用同一个 seed：参考实现与生产入口各自从同一个随机流起点开始，所以「顺序相同」
     * 才有可比性 —— 若任一实现先抽完所有位置再放置，随机数交错次序就会变，落点随之不同。
     */
    @Test
    void placeAtFollowsDfsReferenceNotTheBatchPipeline() {
        List<PlacementModifier> chain = twoSamplingLevels();
        Point origin = new Point(0, 0, 0);

        List<Point> reference = referenceDfs(chain, origin, twoLayerWorld(4242L));
        assertTrue(reference.size() > 1,
                "两层地面 + count=2 至少要放出多个位置，否则这条测试什么都没证明：" + reference);

        List<Point> recorded = new ArrayList<>();
        int placed = new CompiledFeature("betternether:test", 9, 0, chain, recorder(recorded))
                .placeAt(twoLayerWorld(4242L), origin.x(), origin.y(), origin.z());

        assertEquals(reference, recorded,
                "placeAt 的放置顺序必须等于 DFS 参考实现（逐位置就地执行）");
        assertEquals(recorded.size(), placed,
                "placeAt 的返回值就该等于特征返回 true 的次数");

        List<Point> batched = new ArrayList<>();
        FakeWorld batchWorld = twoLayerWorld(4242L);
        Pipeline.place(recorder(batched), Pipeline.run(chain, origin, batchWorld), batchWorld);
        assertNotEquals(batched, recorded,
                "生产入口与批量管线必须给出不同的放置顺序；相同说明 placeAt 退回了 run+place 的批量语义");
    }

    /**
     * 返回值是「成功放置次数」，不是「被发射的位置数」。
     *
     * <p>用「第 1、3、5… 次成功」的特征制造部分失败：只要实现按 {@code feature.place} 的布尔结果
     * 计数，返回值就严格小于尝试次数；若实现把 {@code Pipeline.place} 的入参个数当成功数，两者会相等。
     */
    @Test
    void placeAtCountsSuccessfulPlacementsOnly() {
        List<PlacementModifier> chain = twoSamplingLevels();
        List<Point> attempts = new ArrayList<>();
        Feature alternate = new Feature.Custom("test-alternate", (world, point) -> {
            attempts.add(point);
            return attempts.size() % 2 == 1;   // 1、3、5… 次成功
        });

        int placed = new CompiledFeature("betternether:test", 9, 0, chain, alternate)
                .placeAt(twoLayerWorld(77L), 0, 0, 0);

        assertTrue(attempts.size() >= 2, "至少要有两次尝试，否则无法区分「部分成功」：" + attempts);
        assertEquals((attempts.size() + 1) / 2, placed,
                "只该数成功的那些，不能把尝试次数当成功数：" + attempts.size());
        assertTrue(placed < attempts.size(),
                "这条测试要求确实存在失败的那一半：" + placed + "/" + attempts.size());
    }

    /**
     * 链把位置过滤空：特征一次都不被调用，{@code placeAt} 返回 0。
     *
     * <p>「过滤全空」和「放了但没写进世界」在返回值上都是 0，区别只在于特征有没有被调用 ——
     * 上游 {@code placeWithContext} 在位置表空时根本不进 {@code Feature#place}，所以这里也必须
     * 是「零调用」，而不是「调用后被特征自己拒绝」。
     */
    @Test
    void placeAtPlacesNothingWhenChainFiltersEveryPosition() {
        List<PlacementModifier> chain = List.of(
                new PlacementModifier.CountOnEveryLayer(new IntProvider.Constant(2)),
                new PlacementModifier.BlockPredicateFilter(new BlockPredicates.Constant(false)));

        List<Point> attempts = new ArrayList<>();
        int placed = new CompiledFeature("betternether:test", 9, 0, chain, recorder(attempts))
                .placeAt(twoLayerWorld(9L), 0, 0, 0);

        assertEquals(0, placed, "整条链被过滤空时不该有任何成功放置");
        assertTrue(attempts.isEmpty(),
                "位置表空时特征不该被调用（上游根本不进 place）：" + attempts);
    }

    // ---------------------------------------------------------------- Feature.Wrapped

    /**
     * 内层 placed feature 的修饰符链同样要逐位置 DFS：{@link Feature.Wrapped} 是
     * {@code random_patch} / {@code overlay} / {@code random_selector} 子项的唯一执行路径，
     * 它一旦退回批量写法，外层特征（例如岩浆湖的 {@code wover:sequence} 子项）会静默换位。
     */
    @Test
    void wrappedRunsInnerChainAsDfsNotAsBatch() {
        List<PlacementModifier> inner = twoSamplingLevels();
        // 原点取 0：`CountOnEveryLayer` 的 x/z 偏移是 0..15，原点非 0 会把一部分抽样点推到
        // 测试世界的实心区之外（那里没有地面层），种子稍差就一个位置都发不出来。
        Point origin = new Point(0, 0, 0);

        List<Point> reference = referenceDfs(inner, origin, twoLayerWorld(2024L));
        assertTrue(reference.size() > 1,
                "两层地面 + count=2 至少要放出多个位置，否则这条测试什么都没证明：" + reference);

        List<Point> recorded = new ArrayList<>();
        boolean placed = new Feature.Wrapped(inner, recorder(recorded))
                .place(twoLayerWorld(2024L), origin);

        assertTrue(placed, "内层链放出了位置且特征恒成功，Wrapped 必须返回 true");
        assertEquals(reference, recorded,
                "Wrapped 必须先跑内层修饰符再放特征，且顺序等于 DFS 参考实现");

        List<Point> batched = new ArrayList<>();
        FakeWorld batchWorld = twoLayerWorld(2024L);
        Pipeline.place(recorder(batched), Pipeline.run(inner, origin, batchWorld), batchWorld);
        assertNotEquals(batched, recorded,
                "Wrapped 与批量管线必须给出不同的顺序；相同说明内层链退回了 run+place");
    }

    /** 内层链过滤空时，{@link Feature.Wrapped} 必须返回 false（上游 {@code PlacedFeature#place} 的计数口径）。 */
    @Test
    void wrappedReturnsFalseWhenInnerChainFiltersEveryPosition() {
        List<PlacementModifier> inner = List.of(
                new PlacementModifier.CountOnEveryLayer(new IntProvider.Constant(2)),
                new PlacementModifier.BlockPredicateFilter(new BlockPredicates.Constant(false)));

        List<Point> attempts = new ArrayList<>();
        boolean placed = new Feature.Wrapped(inner, recorder(attempts))
                .place(twoLayerWorld(11L), new Point(0, 0, 0));

        assertFalse(placed, "内层一个位置都没放成时 Wrapped 必须返回 false");
        assertTrue(attempts.isEmpty(), "位置表空时内层特征不该被调用：" + attempts);
    }
}
