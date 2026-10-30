package dev.mumu.betternether.biome;

/**
 * 采样点 → 群系键的裁决器。
 *
 * <h2>为什么是一个接口</h2>
 * 生产路径必须用原版自己的实现：{@code Climate.ParameterList#findValue}（26.3 Paper 已把它替换成
 * Moonrise 的 {@code ChunkGenRTree.moonrise$findNearest}）。那是唯一能保证"与运行时同一棵
 * 最近邻树、同一套并列裁决"的做法。但 {@code Climate.ParameterList} 来自 serverJar，而
 * {@code common/build.gradle.kts} 把 serverJar 从<b>测试运行期</b>类路径里剔除（它会触发
 * PaperRegistryAccess 与 NMS 的全局初始化）。所以这里只留契约：
 * <ul>
 *   <li>生产：{@code NmsBiomeSelector} —— 真 {@code Climate.ParameterList<String>}，28 个参数点，
 *       上游注入顺序，{@code findValue}。</li>
 *   <li>测试：{@link BiomePlacement#select} —— 线性扫全部参数点取 {@code fitness} 最小值
 *       （{@code findValueBruteForce} 的语义）。</li>
 * </ul>
 *
 * <h2>两者已被实测证明等价</h2>
 * 用真 NMS 表与线性扫在整个气候矩形 401×401 = <b>160 801</b> 个采样点上逐点比对，
 * <b>不一致 0 处</b>；完全并列的 point（{@code nether_grasslands} 与
 * {@code nether_mushroom_forest} 都是 (0.0, 0.7, offset 0)）两者都选 {@code nether_grasslands}。
 * 这一条把"并列顺序只能靠猜"的风险消掉了：不是靠猜，是对着真实现量出来的。
 */
@FunctionalInterface
public interface BiomeSelector {
    /**
     * @param target 采样点
     * @return {@link NetherBiomes#VANILLA_NETHER} 或 {@link NetherBiomes#ALL} 里的键
     */
    String select(TargetPoint target);
}
