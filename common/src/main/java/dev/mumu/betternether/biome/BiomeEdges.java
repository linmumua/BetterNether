package dev.mumu.betternether.biome;

/**
 * 交界处的<b>地表浅层材质掺混</b>：只在两个群系的边界附近，把一部分列的<b>暴露地板表层</b>换成
 * 相邻群系的材质，让过渡变成互相咬合的舌状带，而不是一条直边。
 *
 * <h2>为什么不是改 {@link BiomePlacement}</h2>
 * 群系分布本身是上游精确逻辑（原版 {@code multi_noise} 最近邻）。宽化气候阈值或加大噪声 scale
 * 都会改分布：23 个可达群系的份额、稀有群系的位置、{@code NetherBiomeProvider} 报给玩家的群系名
 * 全都跟着变。所以这一层刻意<b>放在选择之外</b>：
 * <ul>
 *   <li>{@link BiomePlacement#keyAt(int, int)} / {@code select} 一个字节都不改；</li>
 *   <li>本类只回答"这一列的<b>地表材质规则</b>该用哪个群系的"，换出来的键永远是这一列附近
 *       已经存在的那个键（见 {@link #nearest}），不凭空引入第三方群系；</li>
 *   <li>因此 23 个群系的可达性、稀有群系的落点、竖直群系的气候点都不会移动 —— 只有边界上
 *       几个方块宽的一层材质被交换。</li>
 * </ul>
 *
 * <h2>为什么只换浅层（这条是本轮修的主题）</h2>
 * 早先的实现是<b>整列</b>替换：噪声决定交换之后，这一列从地板到天花板、从表层到深处全部改用邻居
 * 的规则。后果是可见的竖切面 —— 一座山从顶到底换了一整套材质，深层岩体与天花板（倒挂群系的菌盖、
 * 垂根）跟着变成了隔壁群系的语义。
 *
 * <p>现在掺混只作用于<b>距上方空气不超过 {@link #SKIN} 格、且上方确有空气</b>的地板表层。判据是
 * 原版自己的 {@code stoneDepthAbove}（1 = 直接暴露，2 = 表层下一格 …），由调用方在
 * {@link ColumnKey#keyAt(int, int, int)} 里传进来；因此：
 * <ul>
 *   <li><b>暴露地板表层</b>（{@code depthAbove ∈ [1, SKIN]}）可以换；</li>
 *   <li><b>深层岩体</b>（{@code depthAbove > SKIN}）保持本列群系的规则；</li>
 *   <li><b>天花板</b>（上方是整块岩体，{@code depthAbove} 很大）因此也不可能被换 —— 倒挂群系的
 *       底面始终是它自己的材质，这是"各群系语义不被搬走"的直接结果，不是额外的分支。</li>
 * </ul>
 * 液体覆盖的地板（熔岩海床）{@code depthAbove} 也会很大（原版把流体算作覆盖），所以同样保持原样。
 *
 * <h2>陡边与孤点的根因</h2>
 * <ol>
 *   <li><b>陡边</b>：{@code keyAt} 是气候空间里的最近邻，落到 (x,z) 上就是一张 Voronoi 图；
 *       地表材质按 {@code book.of(key)} 整列切换，于是两种材质在图上一条<b>没有过渡</b>的折线两
 *       侧直接对撞。</li>
 *   <li><b>孤点</b>：Voronoi 边界附近会出现宽度只有一个 quart（4 方块）的窄楔子，甚至单 quart 的
 *       孤岛；整列材质跟着它跳一次，视觉上就是一颗孤立的材质斑。</li>
 * </ol>
 * 两者的共同修法就是"往边缘渗一层"：用一条<b>空间相干</b>的噪声决定哪些列改用邻居的材质。
 * 相干（而不是每格一次哈希）是关键 —— 噪声的波长是 {@value #WAVELENGTH} 方块，所以被换掉的列
 * 连成 8 方块量级的团块，是"舌状咬合"，不是椒盐噪点。
 *
 * <h2>衰减：越靠近边界换得越多</h2>
 * 旧实现的阈值是一个固定的 0.5，于是混合带内外没有强弱之分：紧贴边界的一格和最外侧的一格各有
 * 一半的列被换掉，边界只是"变宽"，不是"变淡"。现在阈值随<b>该列到最近异群系 quart 的方块距离</b>
 * 线性衰减（见 {@link #threshold(int)}）：
 *
 * <pre>
 *   d = 1 格 → 0.500      d = 5 格 → 0.250
 *   d = 2 格 → 0.4375     d = 6 格 → 0.1875
 *   d = 4 格 → 0.3125     d = 8 格 → 0.0625
 * </pre>
 *
 * <p>距离是<b>逐坐标</b>的，不是按 quart 粗分：{@code keyAt} 在每个 quart（4 方块）内恒定，所以
 * 邻接 quart 的那条边界的方块距离取决于本列在 quart 内的偏移，本类把这个偏移算进去（
 * {@link #QUART}），同一 quart 里 4 列的阈值因此各不相同。混合带最宽 {@link #REACH} 格。
 *
 * <h2>确定性</h2>
 * 全部输入只有 {@code (世界种子, x, z, 气候)}：没有 {@code java.util.Random}、没有跨调用状态、
 * 没有按调用顺序推进的流。同种子同坐标必得同一答案，跨 JVM 也一致（只用整型运算与
 * {@code double} 四则，见 {@link #hash01}）。种子在 {@code NetherTerrainGenerator} 里取自
 * {@code World#getSeed()}，因此不同世界咬合花纹不同。
 *
 * <h2>代价</h2>
 * 每列 1 次本列采样；探针按 quart 由近到远，最近一环命中即停：紧贴边界的列只花 4 次探针，
 * 深处不在任何边界附近的列要问满 {@link #BAND} 环、共 8 次。也就是说地表 pass 的群系查询从
 * 固定 5 次变成 5~9 次（不随列高增长）；列内每个被评估的方块仍是原来的 1 次。
 *
 * <h2>与 biome provider 的有意不一致</h2>
 * {@code NetherBiomeProvider} 报出的群系名仍是未混合的 {@code keyAt}。因此边界 8 方块内可能
 * 出现"F3 说 A、地面铺的是 B 的材质"。这是刻意的：provider 改了就改了群系分布（刷怪、音效、
 * 成就判定全跟着走），而"材质过渡"只该影响观感。
 */
public final class BiomeEdges {
    /**
     * 一个 quart 的方块数。原版 {@code QuartPos.fromBlock(i) = i >> 2}，{@code keyAt} 因此
     * <b>在每个 quart 内恒定</b> —— 群系边界只可能落在 quart 边上，这也是下面用 quart 找邻居、
     * 再用 quart 内偏移换算方块距离的原因。
     */
    static final int QUART = 4;

    /**
     * 混合带宽度（quart 格）：边界两侧各探这么多个 quart。两格即 8 方块，正好是
     * {@link #WAVELENGTH} 的一半 —— 再宽就会让"舌"长过一个噪声周期，花纹开始读成条纹。
     */
    static final int BAND = 2;

    /** 混合带最远的方块距离：{@link #BAND} 个 quart。{@link #threshold(int)} 在这里降到 < 0.07。 */
    static final int REACH = BAND * QUART;

    /** 紧贴边界（距异群系 quart 只有 1 格）的列的交换阈值；越远越低，见 {@link #threshold(int)}。 */
    static final double MIX = 0.5;

    /** 相干噪声波长（方块）：决定被交换的列连成多大一团。 */
    static final double WAVELENGTH = 16.0;

    /**
     * 掺混只到暴露地板表层这么深（原版 {@code stoneDepthAbove} 的口径，1 = 地表本身）。
     *
     * <p>6 不是随手取的：上游 23 个文件里最深的<b>地板向</b> {@code stone_depth} 是 6
     * （WartForest 的 {@code 1 + offset 4 + secondary 1}，由 {@code SurfaceRulesTest} 钉住
     * {@code maxStoneDepth() >= 6}）。表层就是玩家看得见的那一层，交界过渡也只该发生在这里。
     *
     * <p>不要因此认为"深层没有规则在写"：恰恰相反，实测有 8 个文件在兜底之前挂着<b>不受
     * {@code stone_depth} 约束</b>的分支，会在岩体深处画出 {@code soul_sand}/{@code soul_soil}/
     * {@code deepslate}（由 {@code NetherTerrainGeneratorSurfaceTest} 钉住）。正因为深层有它自己的、
     * 与边界无关的语义，掺混才必须在这里停手 —— 把邻居的材质铺进埋死岩体或倒挂群系的天花板，
     * 换掉的是另一套规则的结果，不是"过渡"。
     *
     * <p>{@code depthBudget = maxStoneDepth + surfaceDepth + 1} 恒大于 6，所以"表层"永远严格浅于
     * "预算"，两者不会互相干扰（同一条不变量由 {@code NetherTerrainGeneratorSurfaceTest} 断言）。
     */
    public static final int SKIN = 6;

    /** 探针的高度：与 {@link BiomePlacement#keyAt(int, int)} 的固定 quart y 对齐。 */
    private static final int PROBE_Y = BiomePlacement.SAMPLE_QUART_Y << 2;

    /**
     * 一列在某个高度、给定石面深度时的材质键。
     *
     * <p>这是本类对外的<b>唯一</b>接口形态：深度必须由调用方提供，因为"是不是表层"不是坐标能推出
     * 来的（同一列里每个岩架都有自己的表层）。生产调用方是
     * {@code NetherTerrainGenerator#planColumn}，它用原版自己的 {@code stoneDepthAbove/Below} 填
     * 这两个参数，与规则树读到的数字逐位相同。
     */
    @FunctionalInterface
    public interface ColumnKey {
        /**
         * @param y           方块 y
         * @param depthAbove  原版 {@code stoneDepthAbove}：1 = 正上方是空气/流体外的东西，
         *                    递增到最近的上方空腔；与规则树同口径
         * @param depthBelow  原版 {@code stoneDepthBelow}：1 = 正下方不是石头
         * @return {@link NetherBiomes#ALL} 或 {@link NetherBiomes#VANILLA_NETHER} 里的键
         */
        String keyAt(int y, int depthAbove, int depthBelow);
    }

    private final BiomePlacement field;
    private final long seed;

    /**
     * @param field 这一世界正在用的群系布局（{@link BiomePlacement} 实例）
     * @param seed  世界种子；只喂给本类的相干噪声，不影响群系选择
     */
    public BiomeEdges(BiomePlacement field, long seed) {
        if (field == null) {
            throw new NullPointerException("BiomeEdges 需要一个真实的群系布局：没有它就没有可问的邻列");
        }
        this.field = field;
        this.seed = seed;
    }

    /**
     * 一列的地表材质键函数：{@code (y, depthAbove, depthBelow) -> 该位置的键}。
     *
     * <p>这是生产路径用的入口 —— 每列的探针、距离与混合判定只算一次，列内每个方块只多一次深度比较
     * 和一次相等比较。混合判定为否时直接返回 {@code field.keyAt}，与没有这一层时逐位一致。
     *
     * <p>返回的键只在"本高度的键仍等于本列的参考键"时才会被换成邻居：群系目前与 y 无关
     * （见 {@link BiomePlacement} 类注释），这一条是给将来真出现竖直气候轴时留的保险 —— 那时
     * 在高度上分叉的列不做混合，绝不会把第三个群系的材质铺到不该铺的高度。
     *
     * @param x 方块 x
     * @param z 方块 z
     * @return 该列的键函数
     */
    public ColumnKey column(int x, int z) {
        String centre = field.keyAt(x, z);
        Neighbour neighbour = nearest(x, PROBE_Y, z, centre);
        if (neighbour == null) {
            return (y, depthAbove, depthBelow) -> field.keyAt(x, y, z);
        }
        double threshold = threshold(neighbour.distance);
        double swap = noise01(seed, x, z);
        String chosen = neighbour.key;
        return (y, depthAbove, depthBelow) -> {
            String key = field.keyAt(x, y, z);
            if (!centre.equals(key)) {
                return key;
            }
            if (depthAbove < 1 || depthAbove > SKIN) {
                // 深层岩体、天花板与被液体盖住的地板：保持本列群系的材质。
                return key;
            }
            return swap < threshold ? chosen : key;
        };
    }

    /**
     * 该方块的材质键，不带列级缓存。
     *
     * <p>与 {@link #column(int, int)} 的结果相同（测试锁住这条），方便逐点断言与诊断；生产路径
     * 走 {@code column}，以免每列重复算探针。
     *
     * @param x          方块 x
     * @param y          方块 y
     * @param z          方块 z
     * @param depthAbove 原版 {@code stoneDepthAbove}，与 {@link ColumnKey#keyAt(int, int, int)} 同口径
     * @param depthBelow 原版 {@code stoneDepthBelow}
     * @return {@link NetherBiomes#ALL} 或 {@link NetherBiomes#VANILLA_NETHER} 里的键
     */
    public String keyAt(int x, int y, int z, int depthAbove, int depthBelow) {
        String centre = field.keyAt(x, y, z);
        if (depthAbove < 1 || depthAbove > SKIN) {
            return centre;
        }
        Neighbour neighbour = nearest(x, y, z, centre);
        if (neighbour == null) {
            return centre;
        }
        return noise01(seed, x, z) < threshold(neighbour.distance) ? neighbour.key : centre;
    }

    /** 本实例的混合种子。 */
    public long seed() {
        return seed;
    }

    /**
     * 距边界 {@code distance} 格处的交换阈值：边界上一格是 {@link #MIX}，到 {@link #REACH} 时降到
     * {@code MIX / REACH}。
     *
     * <p>线性而不是阶梯：阈值本身是"这一列被换掉的概率"，线性衰减让它从边界向外看不出台阶
     * （阶梯只会在 {@code 4/8} 这类整数处出现一圈整齐的界线，而那正是要消掉的东西）。
     *
     * @param distance 该列到最近异群系 quart 的方块距离，取值 1..{@link #REACH}
     */
    static double threshold(int distance) {
        return MIX * (REACH - distance + 1) / REACH;
    }

    // ---------------------------------------------------------------- 邻域

    /**
     * 这一列最近的异群系邻居，或 {@code null}（{@link #REACH} 格内都是自己，说明不在边界附近）。
     *
     * <p>按 quart 由近到远问，四个方向各一次。{@code keyAt} 在每个 quart 内恒定，所以
     * "第 k 个 quart 上的那一列是不是别的群系"就是"隔着 k 条 quart 边界有没有异群系"；命中一环
     * 就可以停：{@code k + 1} 环的方块距离最小也是 {@code 4(k + 1) - 3}，必然大于 {@code k} 环的
     * 最大距离 {@code 4k}，不可能更近。
     *
     * <p>方块距离要算上本列在 quart 内的偏移 {@code x & 3}：{@code +x} 方向的异群系 quart 从
     * {@code 4(q + k)} 开始，距离 {@code 4k - offsetX}；{@code -x} 方向的到
     * {@code 4(q - k) + 3} 为止，距离 {@code 4k + offsetX - 3}。同一 quart 内的 4 列因此拿到 4 个
     * 不同的距离，这就是"逐坐标"的来源。
     */
    private Neighbour nearest(int x, int y, int z, String centre) {
        int offsetX = x & 3;
        int offsetZ = z & 3;
        for (int quarte = 1; quarte <= BAND; quarte++) {
            Neighbour best = null;
            best = nearer(best, field.keyAt(x + quarte * QUART, y, z), centre, quarte * QUART - offsetX);
            best = nearer(best, field.keyAt(x - quarte * QUART, y, z), centre,
                    quarte * QUART + offsetX - 3);
            best = nearer(best, field.keyAt(x, y, z + quarte * QUART), centre, quarte * QUART - offsetZ);
            best = nearer(best, field.keyAt(x, y, z - quarte * QUART), centre,
                    quarte * QUART + offsetZ - 3);
            if (best != null) {
                return best;
            }
        }
        return null;
    }

    /**
     * 更近的那个邻居；平票（三群系交角）保留探针顺序里靠前的那个 —— 顺序固定，所以平票也是
     * 确定性的，不需要再抽一次噪声。
     *
     * <p>"只换成邻居"是本类的核心不变量：返回的键一定来自本列某一环探针，所以混合永远不会把一个
     * 在这附近根本没出现的群系材质搬过来。
     */
    private static Neighbour nearer(Neighbour best, String key, String centre, int distance) {
        if (key == null || key.equals(centre)) {
            return best;
        }
        if (best != null && best.distance <= distance) {
            return best;
        }
        return new Neighbour(key, distance);
    }

    /** 一个可借的邻居：换成谁，以及它在第几格。 */
    private record Neighbour(String key, int distance) {
    }

    // ---------------------------------------------------------------- 相干噪声

    /**
     * 本条切线用的相干场：{@code [0,1)} 上的平滑值噪声，波长 {@link #WAVELENGTH} 方块。
     *
     * <p>值噪声（格点哈希 + smoothstep 双线性插值）而不是逐格哈希，正是"相干"的来源：相邻方块的
     * 取值只差 {@code 1.5 / WAVELENGTH ≈ 0.09}，所以拿阈值切出来的区域是连成片的团块。
     * smoothstep 而不是线性插值，是为了让格点处没有折线（一阶导连续），阈值切出的边界因此不会
     * 出现网格状的直线段。
     */
    static double noise01(long seed, int x, int z) {
        double fx = x / WAVELENGTH;
        double fz = z / WAVELENGTH;
        int x0 = (int) Math.floor(fx);
        int z0 = (int) Math.floor(fz);
        double tx = smoothstep(fx - x0);
        double tz = smoothstep(fz - z0);
        double v00 = hash01(seed, x0, z0);
        double v10 = hash01(seed, x0 + 1, z0);
        double v01 = hash01(seed, x0, z0 + 1);
        double v11 = hash01(seed, x0 + 1, z0 + 1);
        return lerp(lerp(v00, v10, tx), lerp(v01, v11, tx), tz);
    }

    /**
     * 格点哈希 → {@code [0,1)}。
     *
     * <p>只用 64 位整型运算（SplitMix64 的混合常数），所以结果与 JVM、平台、调用顺序都无关；
     * 这跟 {@code SurfaceRules.LegacyRandom} 用 LCG 复现原版是同一个理由 —— 世界生成的东西不能
     * 有一处依赖运行时。
     */
    static double hash01(long seed, int x, int z) {
        long h = mix(mix(seed ^ (x * 0x9E3779B97F4A7C15L)) ^ (z * 0xC2B2AE3D27D4EB4FL));
        return (h >>> 11) * 0x1.0p-53;
    }

    private static long mix(long value) {
        long h = value;
        h ^= h >>> 30;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 27;
        h *= 0x94D049BB133111EBL;
        return h ^ (h >>> 31);
    }

    private static double smoothstep(double t) {
        return t * t * (3.0 - 2.0 * t);
    }

    private static double lerp(double from, double to, double t) {
        return from + (to - from) * t;
    }
}
