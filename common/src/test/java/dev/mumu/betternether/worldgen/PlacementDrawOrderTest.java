package dev.mumu.betternether.worldgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

/**
 * 两条「随机流 / 坐标基准」的精确回归，钉住 26.3 字节码的行为：
 *
 * <ol>
 *   <li>{@code RarityFilter} 是 {@code nextFloat() < 1.0F / chance}，且 {@code chance == 1}
 *       时**仍然抽一次**（不是 {@code nextInt(chance) == 0}，也不能被「必然通过」优化掉）；</li>
 *   <li>{@code CountOnEveryLayerPlacement#modify} 的随机偏移基准是**输入位置本身**
 *       （{@code pos.getX() + nextInt(16)}），不是「第一个输入点的区块原点」——
 *       存在区块外的 origin（例如 x=100 属于区块原点 96）时这两者会给出不同结果。</li>
 * </ol>
 */
class PlacementDrawOrderTest {
    private static final int MIN_Y = 0;
    private static final int MAX_Y = 127;

    /**
     * {@code chance = 1} 也必须消耗一次 {@code nextFloat()}。
     *
     * <p>用同一个 seed 的参考 {@link Random} 逐次对齐：如果实现把 {@code chance <= 1} 直接放行，
     * 世界随机流会比参考少走一步，这条断言立刻不一致。
     */
    @Test
    void rarityFilterWithChanceOneStillConsumesOneDraw() {
        long seed = 123L;
        FakeWorld world = new FakeWorld(MIN_Y, MAX_Y, seed);
        List<Point> out = new ArrayList<>();
        new PlacementModifier.RarityFilter(1).apply(List.of(new Point(0, 1, 0)), out, world);

        assertEquals(1, out.size(), "chance=1 必然保留");

        Random reference = new Random(seed);
        reference.nextFloat();                        // chance=1 也必须抽掉的那一次
        assertEquals(reference.nextFloat(), world.random().nextFloat(),
                "消费掉 1 次 nextFloat() 之后，世界 RNG 必须与参考同步");
    }

    /**
     * {@code chance = n} 的比较口径是 {@code nextFloat() < 1/n}，与参考 {@link Random} 逐步一致。
     */
    @Test
    void rarityFilterUsesFloatThresholdNotIntegerModulo() {
        long seed = 99L;
        int chance = 4;
        List<Point> points = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            points.add(new Point(i, 40, 0));
        }

        FakeWorld world = new FakeWorld(MIN_Y, MAX_Y, seed);
        List<Point> out = new ArrayList<>();
        new PlacementModifier.RarityFilter(chance).apply(points, out, world);

        Random reference = new Random(seed);
        List<Point> expected = new ArrayList<>();
        float threshold = 1.0F / chance;
        for (Point point : points) {
            if (reference.nextFloat() < threshold) {
                expected.add(point);
            }
        }
        assertEquals(expected, out, "必须逐点 nextFloat() < 1/chance，且比较算子是 <");
    }

    /**
     * 区块外 origin：偏移基准是 origin 本身，不是它所在区块的原点。
     *
     * <p>origin 取 {@code x=100}（区块原点 96）。若实现退化成「取区块原点再 +nextInt(16)」，
     * 落点会出现在 96..111 里，即可能出现 {@code x < 100}；正解只能落在 100..115。
     */
    @Test
    void countOnEveryLayerOffsetsFromTheOriginNotTheChunkBase() {
        int originX = 100;
        int originZ = 100;
        FakeWorld world = new FakeWorld(MIN_Y, MAX_Y, 7L);
        for (int x = 96; x < 128; x++) {
            for (int z = 96; z < 128; z++) {
                world.solid(x, 39, z, Material.NETHERRACK);
            }
        }

        List<Point> out = new ArrayList<>();
        new PlacementModifier.CountOnEveryLayer(new IntProvider.Constant(4))
                .apply(List.of(new Point(originX, 0, originZ)), out, world);

        assertFalse(out.isEmpty(), "地板上方应当能产出位置");
        for (Point point : out) {
            assertTrue(point.x() >= originX && point.x() < originX + 16,
                    "x 必须落在 [origin, origin+16)，区块原点口径会给出更小的值：" + point);
            assertTrue(point.z() >= originZ && point.z() < originZ + 16,
                    "z 必须落在 [origin, origin+16)：" + point);
        }
    }

    /**
     * 多个输入位置必须**逐个**处理（每个输入各自抽偏移、各自迭代加深），不能只看第一个。
     */
    @Test
    void countOnEveryLayerProcessesEveryInputPosition() {
        FakeWorld world = new FakeWorld(MIN_Y, MAX_Y, 11L);
        for (int x = 96; x < 128; x++) {
            for (int z = 96; z < 128; z++) {
                world.solid(x, 39, z, Material.NETHERRACK);
            }
        }

        List<Point> out = new ArrayList<>();
        new PlacementModifier.CountOnEveryLayer(new IntProvider.Constant(2)).apply(
                List.of(new Point(100, 0, 100), new Point(120, 0, 120)), out, world);

        assertTrue(out.stream().anyMatch(p -> p.x() >= 100 && p.x() < 116 && p.z() >= 100 && p.z() < 116),
                "第一个输入位置应当产出落点：" + out);
        assertTrue(out.stream().anyMatch(p -> p.x() >= 120 && p.x() < 136 && p.z() >= 120 && p.z() < 136),
                "第二个输入位置也必须各自产出落点（不能被 in.get(0) 吞掉）：" + out);
    }
}
