package dev.mumu.betternether.biome;

/**
 * 一个气候采样点，六个轴各一个量化后的 {@code long} —— 与
 * {@code net.minecraft.world.level.biome.Climate$TargetPoint} 一一对应。
 *
 * <h2>为什么是 long 而不是 float</h2>
 * 原版先把手里的浮点气候量经过 {@code Climate.quantizeCoord(float)} 变成 {@code long}
 * （{@code (long) (value * 10000.0F)}，<b>截断</b>而非四舍五入），之后所有距离都在整数域里算。
 * 距离与适应度对截断很敏感：{@code 0.175F * 10000.0F} 在 float 里是 {@code 1749.99997…}，
 * 截断成 {@code 1749} 而不是 {@code 1750}。用 {@code double} 复算会得到 {@code 1750}，
 * 于是同一个参数点在不同实现下会有 1 个量化单位的偏差。所以这里<b>只存量化后的 long</b>，
 * 浮点只出现在 {@link #quantize(float)} 这一处，且用与原版逐字相同的表达式。
 *
 * <h2>{@code y} 为什么不参与</h2>
 * 原版 {@code data/minecraft/worldgen/noise_settings/nether.json} 的 {@code noise_router} 里，
 * {@code temperature} 与 {@code vegetation}（也就是湿度轴）都是
 * {@code minecraft:noise}，且 {@code y_scale = 0.0}；{@code continents}/{@code erosion}/
 * {@code depth}/{@code ridges} 全是常量 {@code 0.0}。因此下界这套设置的采样结果与 {@code y}
 * 无关。这一点见 {@code BiomePlacementTest#thisClassAddsNoHeightDependentRule()} 与
 * {@code theTwoOverloadsDivergeWhenTheRouterDependsOnHeight()}：前者证明本工程不引入竖直规则，
 * 后者证明「与 y 无关」是那个设置的属性而不是本类的保证。
 */
public record TargetPoint(
        long temperature,
        long humidity,
        long continentalness,
        long erosion,
        long depth,
        long weirdness) {

    /** 原版的量化：{@code (long) (value * 10000.0F)}，截断。 */
    public static long quantize(float value) {
        return (long) (value * 10000.0F);
    }

    /** 原版 {@code Climate.unquantizeCoord(long)}：{@code value / 10000.0F}。 */
    public static float unquantize(long value) {
        return value / 10000.0F;
    }

    /**
     * 只用温度与湿度构造一个采样点，其余四轴为 0。
     *
     * <p>这不是简化：下界的原版参数表里 28 个参数点的 continentalness/erosion/depth/weirdness
     * <b>全部</b>是 {@code 0.0}，而采样点（见类注释）的这四轴也恒为 0，所以它们对
     * {@link BiomePlacement#fitness} 的贡献对所有候选点完全相同，不影响谁胜出。
     */
    public static TargetPoint ofClimate(float temperature, float humidity) {
        return new TargetPoint(quantize(temperature), quantize(humidity), 0L, 0L, 0L, 0L);
    }

    /** 六轴全给，用于测试逐轴核对（例如验证 depth 不改变结论）。 */
    public static TargetPoint of(long temperature, long humidity, long continentalness, long erosion,
                                 long depth, long weirdness) {
        return new TargetPoint(temperature, humidity, continentalness, erosion, depth, weirdness);
    }

    @Override
    public String toString() {
        return "TargetPoint[T=" + unquantize(temperature) + ", H=" + unquantize(humidity)
                + ", C=" + unquantize(continentalness) + ", E=" + unquantize(erosion)
                + ", D=" + unquantize(depth) + ", W=" + unquantize(weirdness) + "]";
    }
}
