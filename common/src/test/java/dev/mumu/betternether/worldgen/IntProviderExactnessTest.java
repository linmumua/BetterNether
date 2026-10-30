package dev.mumu.betternether.worldgen;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 整数提供器的**对照测试**：拿「按 vanilla 字节码逐条抄写的参考实现」与生产实现比较
 * **完整随机序列**，而不是只比较取值范围。
 *
 * <h2>为什么必须比序列</h2>
 * 这类偏差在游戏里看不出来，但会让同一个 seed 生成出另一个世界：
 * <ul>
 *   <li>{@code biased_to_bottom} 少/多一次 {@code nextInt} —— 后续所有修饰符的随机数全部错位；</li>
 *   <li>{@code clamped_normal} 用 {@code Math.round} 而不是 {@code f2i} 截断 —— 每个值系统性偏大；</li>
 *   <li>{@code trapezoid} 只实现一条分支 —— 对称区间（{@code min == -max}）走的是另一条，分布与消耗个数都不同。</li>
 * </ul>
 * 参考实现**写在测试里**（独立于生产代码），所以生产实现改错时测试会失败；如果两边共用一个
 * 实现，这个测试就什么也抓不住。
 *
 * <p>参考实现的来源：服务端 jar 的 {@code javap -p -c}
 * （{@code net.minecraft.util.valueproviders.BiasedToBottomInt / ClampedNormalInt / TrapezoidInt}）。
 */
class IntProviderExactnessTest {
    private static final long SEED = 20260101L;
    private static final int SAMPLES = 500;

    // ---------------------------------------------------------------- 参考实现（照抄字节码）

    /** {@code BiasedToBottomInt#sample}：{@code min + nextInt(nextInt(range+1)+1)}。 */
    private static int referenceBiasedToBottom(Random random, int min, int max) {
        int range = max - min;
        if (range <= 0) {
            return min;
        }
        return min + random.nextInt(random.nextInt(range + 1) + 1);
    }

    /** {@code ClampedNormalInt#sample}：{@code (int) clamp(Mth.normal(...), min, max)}，截断而非四舍五入。 */
    private static int referenceClampedNormal(Random random, double mean, double deviation,
                                              int min, int max) {
        float value = (float) mean + (float) random.nextGaussian() * (float) deviation;
        float clamped = Math.max(min, Math.min(max, value));
        return (int) clamped;
    }

    /** {@code TrapezoidInt#sample} 的三条分支，逐条照抄。 */
    private static int referenceTrapezoid(Random random, int min, int max, int plateau) {
        int range = max - min;
        if (range <= 0) {
            return min;
        }
        if (plateau == 0 && max == -min) {
            return random.nextInt(max + 1) - random.nextInt(max + 1);
        }
        if (plateau >= range) {
            return min + random.nextInt(range + 1);
        }
        int a = (range - plateau) / 2;
        int b = range - a;
        return min + random.nextInt(b + 1) + random.nextInt(a + 1);
    }

    // ---------------------------------------------------------------- 对照

    @Test
    void biasedToBottomMatchesTheReferenceStream() {
        Random production = new Random(SEED);
        Random reference = new Random(SEED);
        IntProvider provider = new IntProvider.BiasedToBottom(3, 13);
        for (int i = 0; i < SAMPLES; i++) {
            assertEquals(referenceBiasedToBottom(reference, 3, 13), provider.sample(production),
                    "biased_to_bottom 第 " + i + " 次采样与参考实现不一致（随机流已错位）");
        }
    }

    @Test
    void clampedNormalMatchesTheReferenceStream() {
        Random production = new Random(SEED);
        Random reference = new Random(SEED);
        IntProvider provider = new IntProvider.ClampedNormal(12.0, 2.3, 3, 16);
        for (int i = 0; i < SAMPLES; i++) {
            assertEquals(referenceClampedNormal(reference, 12.0, 2.3, 3, 16),
                    provider.sample(production),
                    "clamped_normal 第 " + i + " 次采样与参考实现不一致");
        }
    }

    @Test
    void trapezoidSymmetricBranchMatchesTheReferenceStream() {
        Random production = new Random(SEED);
        Random reference = new Random(SEED);
        // minecraft:offset 的 x/z 就是这种对称区间（trapezoid min=-5 max=5 plateau=0）。
        IntProvider provider = new IntProvider.Trapezoid(-5, 5, 0);
        for (int i = 0; i < SAMPLES; i++) {
            assertEquals(referenceTrapezoid(reference, -5, 5, 0), provider.sample(production),
                    "trapezoid 的对称分支第 " + i + " 次不一致");
        }
    }

    @Test
    void trapezoidGeneralBranchMatchesTheReferenceStream() {
        Random production = new Random(SEED);
        Random reference = new Random(SEED);
        IntProvider provider = new IntProvider.Trapezoid(8, 40, 0);
        for (int i = 0; i < SAMPLES; i++) {
            assertEquals(referenceTrapezoid(reference, 8, 40, 0), provider.sample(production),
                    "trapezoid 的一般分支第 " + i + " 次不一致");
        }
    }

    @Test
    void trapezoidPlateauBranchMatchesTheReferenceStream() {
        Random production = new Random(SEED);
        Random reference = new Random(SEED);
        IntProvider provider = new IntProvider.Trapezoid(0, 10, 4);
        for (int i = 0; i < SAMPLES; i++) {
            assertEquals(referenceTrapezoid(reference, 0, 10, 4), provider.sample(production),
                    "trapezoid 的 plateau 分支第 " + i + " 次不一致");
        }
    }

    @Test
    void uniformMatchesRandomBetweenInclusive() {
        Random production = new Random(SEED);
        Random reference = new Random(SEED);
        IntProvider provider = new IntProvider.Uniform(-7, 7);
        for (int i = 0; i < SAMPLES; i++) {
            assertEquals(-7 + reference.nextInt(15), provider.sample(production),
                    "uniform 与 Mth.randomBetweenInclusive 不等价");
        }
    }

    // ---------------------------------------------------------------- 层方向

    /**
     * 「第 N 层」必须**自顶向下**数。
     *
     * <p>{@code count_on_every_layer} 的第 i 次尝试落在自顶向下第 i 层，因此一次放置序列会从
     * 最高的台地逐层往下铺开。写成自下而上会把优先级整个颠倒：台地下方先被填满、最上层反而稀疏 ——
     * 这个错误在游戏里看起来只像「密度不太对」。
     */
    @Test
    void groundLayersAreCountedFromTheTop() {
        FakeWorld world = new FakeWorld(0, 127, 1L);
        world.fill(0, 0, 0, 8, Material.NETHERRACK);    // 地面层 y=9
        world.fill(0, 0, 39, 39, Material.NETHERRACK);  // 地面层 y=40
        world.fill(0, 0, 69, 69, Material.NETHERRACK);  // 地面层 y=70

        assertEquals(70, Layers.groundLayer(world, 0, 0, world.motionBlockingHeight(0, 0), 0), "第 0 层应当是最高那一层");
        assertEquals(40, Layers.groundLayer(world, 0, 0, world.motionBlockingHeight(0, 0), 1));
        assertEquals(9, Layers.groundLayer(world, 0, 0, world.motionBlockingHeight(0, 0), 2));
        assertEquals(Integer.MIN_VALUE, Layers.groundLayer(world, 0, 0, world.motionBlockingHeight(0, 0), 3),
                "没有第 4 层时必须报告「没有」，而不是回绕到某一层");
    }

    /**
     * 紧贴地板的下一格**必须**算一层（没有自造的 floor-plate 偏移）。
     *
     * <p>地板用下界岩：基岩会被「床顶排除」（见下一条）挡掉，那样这条用例就测不到边界了。
     */
    @Test
    void theLayerDirectlyAboveTheFloorPlateCounts() {
        FakeWorld world = new FakeWorld(0, 127, 2L);
        world.fill(0, 0, 0, 0, Material.NETHERRACK);

        List<Point> layers = new java.util.ArrayList<>();
        Layers.layers(world, 0, 0, world.minY(), world.maxY(), true, layers);

        assertEquals(List.of(new Point(0, 1, 0)), layers,
                "y=1（紧贴地板那一格）必须是地面层；不该被自造的 minY+1 偏移吃掉");
    }

    /**
     * 基岩上方**不算**地面层：上游 {@code !pos.below().is(BEDROCK)}（床顶排除）。
     *
     * <p>世界底那块基岩板是实心的，但「站在床顶上」不是可放置的地面层。
     */
    @Test
    void bedrockFloorIsNotAGroundLayer() {
        FakeWorld world = new FakeWorld(0, 127, 2L);
        world.fill(0, 0, 0, 0, Material.BEDROCK);

        List<Point> layers = new java.util.ArrayList<>();
        Layers.layers(world, 0, 0, world.minY(), world.maxY(), true, layers);

        assertEquals(List.of(), layers, "基岩板之上不该算地面层（!below.is(BEDROCK)）");
    }
}
