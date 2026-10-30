package dev.mumu.betternether.biome;

/**
 * 一个气候采样点坐标 → 气候目标点。签名与
 * {@code net.minecraft.world.level.biome.Climate$Sampler#sample(int, int, int)} 逐字一致：
 * <b>三个参数都是 quart 坐标</b>，不是方块坐标。
 *
 * <h2>为什么这是一个接口</h2>
 * 目标点必须来自原版自己：{@code RandomState#createClimateSampler} 拿世界种子与该维度的
 * {@code NoiseGeneratorSettings} 生成 {@code net.minecraft:nether/temperature} /
 * {@code minecraft:nether/vegetation} 两个噪声的采样器。这条链路只有服务端有，单元测试不能启
 * 服务端（{@code common/build.gradle.kts} 把 serverJar 从测试运行期类路径里剔除）。所以这里只
 * 定义契约，生产实现是 {@code NetherClimateSampler}（引用 NMS，测试不会加载它），测试实现是
 * {@code common/src/test/.../TestClimateSampler}。
 *
 * <p>本接口<b>不做</b>任何气候计算 —— 它只搬运原版的采样结果。插件侧自己造一个气候场是
 * 禁止的：那样的场再平滑也只会得到一张和上游无关的群系图。
 *
 * <h2>线程安全</h2>
 * Paper/Folia 的 {@code BiomeProvider} 契约要求完全线程安全。原版 {@code Climate.Sampler} 是
 * 六个不变量采样器的 record，{@code sample} 无共享可变状态，因此实现方只需保证自己持有的字段
 * 是 final 的。
 */
@FunctionalInterface
public interface ClimateSampler {
    /**
     * 在原版的 quart 坐标上采样一次。
     *
     * @param quartX quart 坐标（方块坐标 {@code >> 2}，向负无穷取整）
     * @param quartY quart 坐标；下界的温度/湿度噪声 {@code y_scale = 0.0}，该值不影响结果
     * @param quartZ quart 坐标
     * @return 该处的六个量化气候坐标
     */
    TargetPoint sample(int quartX, int quartY, int quartZ);
}
