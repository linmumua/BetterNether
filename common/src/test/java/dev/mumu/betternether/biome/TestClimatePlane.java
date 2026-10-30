package dev.mumu.betternether.biome;

/**
 * 测试夹具：一个<b>合成</b>的气候平面，只给单元测试用。
 *
 * <h2>这不是生产气候</h2>
 * 生产路径上唯一合法的气候来源是 {@code NetherClimateSampler} —— 原版
 * {@code RandomState#createClimateSampler} 给出的 {@code minecraft:nether/temperature} 与
 * {@code minecraft:nether/vegetation} 噪声。本类存在的理由只有一个：{@link BiomePlacement} 的
 * 选择规则（最近邻、offset 成本、quart 换算、并列顺序）是纯算术，可以在<b>任意</b>给定的目标点上
 * 验证；而服务端跑不在单元测试里（{@code common/build.gradle.kts} 把 serverJar 从测试运行期类路径
 * 剔除）。
 *
 * <p>因此：任何测试都可以用 {@link #fixed} 把某个气候点精确钉住，或用 {@link #seeded} 得到一片
 * 平滑的、覆盖整个气候矩形的场。把本类搬进 main 源集就等于自己造了一张气候图 —— 那正是本轮要
 * 删掉的东西。
 */
public final class TestClimatePlane {
    /** 整片场的波长（方块）：一个周期内走完 {@code -1..1}。约等于原版 0.25 xz_scale 的数量级。 */
    private static final double WAVELENGTH = 512.0;

    private TestClimatePlane() {
    }

    /** 处处相同的目标点。用来把"某个气候点归谁"钉死。 */
    public static ClimateSampler fixed(float temperature, float humidity) {
        return fixed(TargetPoint.ofClimate(temperature, humidity));
    }

    /** 处处相同的目标点。 */
    public static ClimateSampler fixed(TargetPoint target) {
        return (quartX, quartY, quartZ) -> target;
    }

    /**
     * 一片平滑的合成场：温度与湿度各是一条相位不同的正弦，覆盖 {@code [-1,1]} 整个矩形。
     *
     * <p>它<b>不是</b> Upstream 的气候，也不试图像；它只保证"世界够大就能采到表里的大部分群系"，
     * 让地形/populator 的测试有一个会变化的列群系可用。
     *
     * @param seed 相位种子，不同种子给出不同布局
     */
    public static ClimateSampler seeded(long seed) {
        double phaseT = (seed * 0x9E3779B97F4A7C15L >>> 11) / (double) (1L << 53) * Math.PI * 2.0;
        double phaseH = (seed * 0xC2B2AE3D27D4EB4FL >>> 11) / (double) (1L << 53) * Math.PI * 2.0;
        return (quartX, quartY, quartZ) -> {
            // quart → 方块：原版 QuartPos.toBlock 是左移 2 位，负 quart 同样成立。
            double bx = quartX * 4.0;
            double bz = quartZ * 4.0;
            float temperature = (float) Math.sin(bx / WAVELENGTH * Math.PI * 2.0 + phaseT);
            float humidity = (float) Math.sin((bz / WAVELENGTH + bx / (WAVELENGTH * 4.0))
                    * Math.PI * 2.0 + phaseH);
            return TargetPoint.ofClimate(temperature, humidity);
        };
    }
}
