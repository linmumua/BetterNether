package dev.mumu.betternether.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

/**
 * {@link Pipeline#placeChain} 与 {@link Pipeline#run} 的**随机流顺序**差异。
 *
 * <h2>为什么要专门测「顺序」</h2>
 * 上游 wover 走 {@code FeaturePlacer} 的 stack DFS：一个位置被发出来后，**立刻**跑完链上
 * 剩余修饰符和特征，才回到上一个修饰符的下一步。而 `run` 是「每级修饰符批量变形整张位置表」。
 * 两级都做抽样时（例如 `count_on_every_layer` 后面接 `in_square`），两者位置总量相同，
 * 落点却不同 —— 因为 `count_on_every_layer` 的 {@code nextInt(16)} 与 `in_square` 的
 * {@code nextInt(16)} 的<b>交错次序</b>变了。
 *
 * <p>所以下面这条断言是「反批量」的：只要有人把 `placeChain` 退回「先收完全部位置再放置」，
 * 两条路径的放置顺序就会重新变得一模一样，测试立刻变红。
 */
class PipelineChainTest {
    private static final int MIN_Y = 0;
    private static final int MAX_Y = 127;

    /**
     * 记录特征被放置的顺序。
     *
     * <p>{@link Feature} 是 {@code sealed}（permits 只含同文件的嵌套类型），所以测试不能自己
     * {@code implements Feature}；用生产的 {@link Feature.Custom} 承载同样的记录动作，
     * 不为测试去动生产的 permits 列表。
     */
    private static Feature recorder(List<Point> hits) {
        return new Feature.Custom("test-recorder", (world, point) -> {
            hits.add(point);
            return true;
        });
    }

    /**
     * 一列**两层**地面：y=39 与 y=29 是石头，其余空气，于是地面层是 y=40 与 y=30。
     *
     * <p>`count_on_every_layer` 的迭代加深需要「第 0 层和第 1 层都存在」才能跑第二轮，
     * 只有一层的话两种执行顺序会退化成等价。
     */
    private static FakeWorld twoLayerWorld(long seed) {
        FakeWorld world = new FakeWorld(MIN_Y, MAX_Y, seed);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                world.solid(x, 39, z, Material.STONE);
                world.solid(x, 29, z, Material.STONE);
            }
        }
        // Layers 现在严格：没有安装 terrain id 集会直接报错（不再退化成「非空气」近似）。
        Layers.installTerrainIds(Set.of("minecraft:stone"));
        return world;
    }

    @Test
    void placeChainRunsDownstreamDrawsPerPositionInsteadOfBatching() {
        List<PlacementModifier> chain = List.of(
                new PlacementModifier.CountOnEveryLayer(new IntProvider.Constant(2)),
                new PlacementModifier.InSquare());

        List<Point> batched = new ArrayList<>();
        FakeWorld batchWorld = twoLayerWorld(4242L);
        Pipeline.place(recorder(batched), Pipeline.run(chain, new Point(0, 0, 0), batchWorld), batchWorld);

        List<Point> chained = new ArrayList<>();
        FakeWorld chainWorld = twoLayerWorld(4242L);
        int placed = Pipeline.placeChain(chain, new Point(0, 0, 0), chainWorld, recorder(chained));

        assertEquals(chained.size(), placed,
                "placeChain 的返回值就该等于特征返回 true 的次数");
        assertTrue(batched.size() > 0 && chained.size() > 0,
                "两层地面 + count=2 应该两条路径都放出东西，否则这条测试什么都没证明");

        assertNotEquals(batched, chained,
                "DFS 逐位置执行与批量执行必须给出不同的放置顺序（随机流交错次序不同）；"
                        + "两者相同说明 placeChain 实际退回了批量语义");
    }

    /**
     * `placeChain`：每个被发射的层**就地**把下游整条链跑完，跑完才回到上一层。
     *
     * <p>下游用 {@code count=4 → in_square}：{@link PlacementModifier.Count} 会把一个位置
     * 复制成 4 份，{@link PlacementModifier.InSquare} 再各自随机平移一次（1:1，不改 y）。
     * 所以「每次 emit 完整扩成 4 个落点」是可观察的：落点必须按 **4 个一组、且同组的 y 相同**
     * 排列 —— 这正是「就地执行」的形态；批量执行会把所有层先抽完再统一扩，分组就会跨层。
     */
    @Test
    void eachEmittedLayerExpandsDownstreamImmediately() {
        List<PlacementModifier> chain = List.of(
                new PlacementModifier.CountOnEveryLayer(new IntProvider.Constant(2)),
                new PlacementModifier.Count(new IntProvider.Constant(4)),
                new PlacementModifier.InSquare());

        List<Point> chained = new ArrayList<>();
        FakeWorld world = twoLayerWorld(77L);
        Pipeline.placeChain(chain, new Point(0, 0, 0), world, recorder(chained));

        assertTrue(chained.size() >= 8,
                "两层地面 × count=4 ⇒ 至少 8 个落点：" + chained);
        assertEquals(0, chained.size() % 4,
                "每个 emit 完整扩 4 份，总数必须是 4 的倍数：" + chained.size());
        for (int i = 0; i < chained.size(); i += 4) {
            int y = chained.get(i).y();
            for (int j = 1; j < 4; j++) {
                assertEquals(y, chained.get(i + j).y(),
                        "同一组的 4 个落点必须来自**同一次** emit（就地执行），"
                                + "跨层说明下游被批量延后了：" + chained);
            }
        }
    }
}
