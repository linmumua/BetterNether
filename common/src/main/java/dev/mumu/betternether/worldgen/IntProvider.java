package dev.mumu.betternether.worldgen;

import java.util.Random;

/**
 * 整数提供器：上游 JSON 里 {@code IntProvider} 的那些类型。
 *
 * <h2>算法来源：vanilla 字节码，不是推测</h2>
 * 下面每个 {@code sample} 都是对着服务端 jar 的 {@code javap -p -c} 反汇编逐条抄的
 * （{@code net.minecraft.util.valueproviders.*}）。随机数的**个数与顺序**都必须一致 ——
 * 少一次 {@code nextInt} 或换个先后，同一个 seed 生成的世界就整体变了，
 * 而这种偏差在游戏里看不出来，只能靠算法对照发现。
 *
 * <h2>锚点（{@code above_bottom} / {@code below_top} / {@code absolute}）</h2>
 * 上游的高度几乎都写成「距世界底部/顶部多少格」。锚点必须保留到放置时再按世界高度换算：
 * 折算成绝对 y 会在换世界高度时静默偏移。
 */
public sealed interface IntProvider {
    /** 在 {@code [min, max]} 里取一个值（含两端）。 */
    int sample(Random random);

    int min();

    int max();

    /** 常量。 */
    record Constant(int value) implements IntProvider {
        @Override
        public int sample(Random random) {
            return value;
        }

        @Override
        public int min() {
            return value;
        }

        @Override
        public int max() {
            return value;
        }
    }

    /** {@code minecraft:uniform}：{@code Mth.randomBetweenInclusive} = {@code nextInt(max-min+1)+min}。 */
    record Uniform(int min, int max) implements IntProvider {
        @Override
        public int sample(Random random) {
            if (max <= min) {
                return min;
            }
            return min + random.nextInt(max - min + 1);
        }
    }

    /**
     * {@code minecraft:trapezoid}：照抄 vanilla {@code TrapezoidInt#sample} 的三条分支。
     *
     * <ol>
     *   <li>对称特例 {@code plateau == 0 && max == -min} →
     *       {@code nextInt(max+1) - nextInt(max+1)}；</li>
     *   <li>{@code plateau >= range} → {@code nextInt(range+1) + min}（原版分支条件是
     *       {@code plateau == range}，构造器已把 plateau 夹进 {@code [0, range]}）；</li>
     *   <li>否则 {@code a = (range - plateau) / 2}, {@code b = range - a} →
     *       {@code min + nextInt(b+1) + nextInt(a+1)}（先 b 后 a，与原版随机流顺序一致）。</li>
     * </ol>
     */
    record Trapezoid(int min, int max, int plateau) implements IntProvider {
        Trapezoid(int min, int max) {
            this(min, max, 0);
        }

        @Override
        public int sample(Random random) {
            int range = max - min;
            if (range <= 0) {
                return min;
            }
            int clampedPlateau = Math.max(0, Math.min(plateau, range));
            if (clampedPlateau == 0 && max == -min) {
                return random.nextInt(max + 1) - random.nextInt(max + 1);
            }
            if (clampedPlateau >= range) {
                return min + random.nextInt(range + 1);
            }
            int a = (range - clampedPlateau) / 2;
            int b = range - a;
            return min + random.nextInt(b + 1) + random.nextInt(a + 1);
        }
    }

    /**
     * {@code minecraft:clamped_normal}：照抄 {@code ClampedNormalInt#sample}。
     *
     * <p>{@code Mth.normal(random, mean, dev)} → {@code Mth.clamp(float, min, max)} → {@code f2i}。
     * 两处与直觉不同、且我此前写错的地方：
     * <ul>
     *   <li>夹取发生在**浮点**上，再截断；</li>
     *   <li>是 {@code f2i}（向零截断），**不是** {@code Math.round}。</li>
     * </ul>
     * {@code Mth.normal} 也已字节码核对：
     * {@code mean + (float) nextGaussian() * deviation}，**全程 float 运算**。
     * 所以这里必须用 float，不能图省事用 double —— 双精度会让边界值偏移 1，
     * 而相邻的两次采样又会连带后面的随机流（同一个 seed 的世界就不同了）。
     */
    record ClampedNormal(double mean, double deviation, int min, int max) implements IntProvider {
        @Override
        public int sample(Random random) {
            float value = (float) mean + (float) random.nextGaussian() * (float) deviation;
            float clamped = Math.max((float) min, Math.min((float) max, value));
            return (int) clamped;
        }
    }

    /**
     * {@code minecraft:biased_to_bottom}：照抄 {@code BiasedToBottomInt#sample}。
     *
     * <p>{@code min + nextInt(nextInt(range+1) + 1)}。嵌套取值：外层上界被内层压小，
     * 所以分布强烈偏下；换成「两次均匀取小」会同时改变分布与随机数消耗个数。
     */
    record BiasedToBottom(int min, int max) implements IntProvider {
        @Override
        public int sample(Random random) {
            int range = max - min;
            if (range <= 0) {
                return min;
            }
            return min + random.nextInt(random.nextInt(range + 1) + 1);
        }
    }

    /**
     * {@code minecraft:very_biased_to_bottom}：照抄 {@code VeryBiasedToBottomInt#sample}
     * （javap -c 实录：三段嵌套 {@code nextInt}）。
     *
     * <p>{@code min + nextInt(nextInt(nextInt(range+1)+1)+1)} —— 比
     * {@code biased_to_bottom} 多一层嵌套，因此消耗 **3** 个随机数、分布更集中在底部。
     * 春泉（{@code spring_lava}）的 height_range 用它。
     */
    record VeryBiasedToBottom(int min, int max) implements IntProvider {
        @Override
        public int sample(Random random) {
            int range = max - min;
            if (range <= 0) {
                return min;
            }
            return min + random.nextInt(random.nextInt(random.nextInt(range + 1) + 1) + 1);
        }
    }

    /**
     * 把「锚点 + 偏移」换算成绝对 y。
     *
     * @param anchor   锚点名（{@code above_bottom} / {@code below_top} / {@code absolute}）
     * @param offset   锚点偏移
     * @param worldMin 世界最低 y
     * @param worldMax 世界最高 y（含）
     */
    static int anchor(String anchor, int offset, int worldMin, int worldMax) {
        return switch (anchor) {
            case "above_bottom" -> worldMin + offset;
            case "below_top" -> worldMax - offset;
            case "absolute" -> offset;
            default -> throw new IllegalArgumentException("未知高度锚点：" + anchor);
        };
    }
}
