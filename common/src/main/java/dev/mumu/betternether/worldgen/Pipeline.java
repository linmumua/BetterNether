package dev.mumu.betternether.worldgen;

import java.util.ArrayList;
import java.util.List;

/**
 * 修饰符链的执行器：一串 {@link PlacementModifier} 依次把位置集合变形。
 *
 * <p>与上游 {@code PlacedFeature.placeWithContext} 一致：从「起始位置」开始，每个修饰符读
 * 上一轮结果、写下一轮结果，最后逐个位置调用特征。用两个可复用的缓冲列表交替，
 * 避免每个修饰符都新分配一次列表 —— 这是每个区块都会走的路径，植被特征动辄
 * {@code count: 25} × {@code every_layer: 若干层}。
 */
public final class Pipeline {
    private Pipeline() {
    }

    /** 从 {@code origin} 这一个位置出发，跑完整条修饰符链。 */
    public static List<Point> run(List<PlacementModifier> modifiers, Point origin, WorldView world) {
        return run(modifiers, List.of(origin), world);
    }

    /**
     * 跑完整条修饰符链。
     *
     * @param modifiers 修饰符（可为空，表示原样返回入参）
     * @param origins   起始位置
     * @return 最终位置列表（不可变快照；执行期间的缓冲是私有的）
     */
    public static List<Point> run(List<PlacementModifier> modifiers, List<Point> origins,
                                  WorldView world) {
        if (modifiers.isEmpty() || origins.isEmpty()) {
            return List.copyOf(origins);
        }
        List<Point> current = new ArrayList<>(origins);
        List<Point> next = new ArrayList<>(origins.size());
        for (PlacementModifier modifier : modifiers) {
            next.clear();
            modifier.apply(current, next, world);
            if (next.isEmpty()) {
                return List.of();
            }
            List<Point> swap = current;
            current = next;
            next = swap;
        }
        return List.copyOf(current);
    }

    /**
     * 生产入口：把一条 placed feature 的修饰符链**逐位置就地**跑完并放置。
     *
     * <p>与 {@link #run} 的区别是随机流顺序，不是位置总量：上游 wover 走 {@code FeaturePlacer}
     * 的 stack DFS —— 每个被发射的位置<b>同步</b>跑完链上剩余修饰符和特征，才回到上一个修饰符的
     * 下一步。{@link #run} 是「每级修饰符批量变形整张位置表」，两级都做抽样时随机消耗顺序不同，
     * 落点因此不同（`in_square` 与 `count`/`every_layer` 混排时最明显）。
     *
     * <p>所以：生产只走这里；{@link #run} 保留为「位置形态」的纯单元测试通道。
     *
     * @return 特征返回 true 的次数（与上游 {@code placeWithContext} 的计数口径一致）
     */
    public static int placeChain(List<PlacementModifier> modifiers, Point origin, WorldView world,
                                 Feature feature) {
        int[] placed = {0};
        descend(modifiers, 0, origin, world, feature, placed);
        return placed[0];
    }

    private static void descend(List<PlacementModifier> modifiers, int index, Point point,
                                WorldView world, Feature feature, int[] placed) {
        if (index >= modifiers.size()) {
            if (feature.place(world, point)) {
                placed[0]++;
            }
            return;
        }
        modifiers.get(index).visit(point, world,
                next -> descend(modifiers, index + 1, next, world, feature, placed));
    }

    /** 在给定位置上放特征，返回成功放置的次数。 */
    public static int place(Feature feature, List<Point> points, WorldView world) {
        int placed = 0;
        for (Point point : points) {
            if (feature.place(world, point)) {
                placed++;
            }
        }
        return placed;
    }
}
