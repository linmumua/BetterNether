package dev.mumu.betternether.biome;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 决定一列属于 23 个 BetterNether 群系中的哪一个 —— 用的是<b>上游默认路径的真实算法</b>：
 * 原版 {@code multi_noise} 的最近邻，参数表 = 5 个原版下界点 + 23 个 BetterNether 点。
 *
 * <h2>证据链</h2>
 * <ol>
 *   <li>{@code upstream/.../world/biomes/util/NetherBiomeBuilder.java:194}
 *       {@code registerBiomeData} 把每个群系注册成
 *       {@code new NetherBiome(..., new BiomeGenerationDataContainer(parameters, intendedPlacement),
 *       terrainHeight, genChance * GEN_CHANCE_FACTOR, edgeSize, vertical, edge, parent)}。也就是说
 *       {@code genChance}/{@code edge}/{@code vertical} 是<b>数据</b>，交给 WoVer 的群系数据容器，
 *       不是一个选择公式。</li>
 *   <li>每个群系的 {@code addNetherClimate(temperature, humidity, offset)}（例：
 *       {@code MagmaLand.java:36} {@code .addNetherClimate(0.8f, -0.7f, 0)}）落到
 *       {@code biome_data/*.json} 的 {@code generation_data.parameter_points[0]}，七个字段为
 *       {@code continentalness, depth, erosion, humidity, offset, temperature, weirdness} —— 这正是
 *       原版 {@code Climate.ParameterPoint} 的七个分量（javap：
 *       {@code ParameterPoint(Parameter,Parameter,Parameter,Parameter,Parameter,Parameter,long)}，
 *       最后一个 {@code long} 就是 {@code offset}）。{@code NetherBiomeBuilder.java:49} 的
 *       {@code GEN_CHANCE_FACTOR = 1.25f} 只是把权重乘大，说明这些权重是给 WoVer 自己的选择器用的。</li>
 *   <li>默认路径不经过 WoVer 的选择器：这些参数点经 Fabric
 *       {@code NetherBiomes#addNetherBiome} 追加进原版 {@code minecraft:nether} 预设，之后由原版
 *       {@code MultiNoiseBiomeSource} 挑选。所以本类实现的是原版规则，不是 WoVer 的 Voronoi/hex
 *       规则；WoVer 的自定义 preset 不在本默认路径上。</li>
 * </ol>
 *
 * <h2>原版规则，逐字</h2>
 * <ul>
 *   <li>参数点与采样点都先量化成 {@code long}：{@code Climate.quantizeCoord(f) = (long)(f * 10000.0F)}，
 *       <b>截断</b>。见 {@link TargetPoint#quantize(float)}。</li>
 *   <li>单点参数的距离：{@code Parameter.distance(v)}，{@code min == max == p} 时结果是
 *       {@code |v - p|}（javap 的字节码是
 *       {@code above = v - max; below = min - v; return above > 0 ? above : max(below, 0)}）。</li>
 *   <li>适应度：{@code ParameterPoint.fitness(t) = Σ_{六轴} distance² + offset²}
 *       （javap：六个 {@code Mth.square(distance)} 累加，末尾
 *       {@code ladd Mth.square(offset)}）。<b>{@code offset} 是成本，不是距离轴</b>，取得越大越吃亏。</li>
 *   <li>胜者 = 适应度最小的参数点（原版 {@code Climate.ParameterList#findValue}；本类做的是同一个
 *       argmin，即 {@code findValueBruteForce} 的语义）。</li>
 * </ul>
 *
 * <h2>{@code y} 不进选择</h2>
 * 原版 {@code data/minecraft/worldgen/noise_settings/nether.json} 的 {@code noise_router}：
 * {@code temperature} = {@code minecraft:noise(nether/temperature, xz_scale 0.25, y_scale 0.0)}，
 * {@code vegetation}（湿度轴）= {@code minecraft:noise(nether/vegetation, xz_scale 0.25, y_scale 0.0)}，
 * {@code continents} / {@code erosion} / {@code depth} / {@code ridges} 全是常量 {@code 0.0}。
 * 因此目标点与 {@code y} 无关，而 28 个参数点的另外四轴又全是 0 —— 于是<b>只有温度与湿度参与判别</b>。
 * {@link #keyAt(int, int)} 与 {@link #keyAt(int, int, int)} 因而必然一致，这不是近似。
 *
 * <h2>被删掉的三条自造规则</h2>
 * 上一版这里有一套与上游无关的东西：{@code genChance} 加权抽签、{@code edge}/{@code edgeSize}
 * 邻域带、以及 {@code "vertical": true} 的天花板竖带。三条都不是原版 {@code multi_noise} 的一部分：
 * <ul>
 *   <li>{@code genChance} 只喂 WoVer 自己的选择器，原版最近邻根本读不到它。</li>
 *   <li>{@code edge}/{@code edgeSize} 同理，是 WoVer 群系数据的字段；原版参数表里没有"边群系"概念。</li>
 *   <li>{@code vertical} 需要一个竖直气候轴，而下界的 {@code depth} 是常量 {@code 0.0}，
 *       所以默认真实行为里没有竖带。{@link #VERTICAL_KEY} 与 {@link #CEILING_BAND_FLOOR} 保留只是
 *       为了不打断仍在引用它们的地形侧代码，<b>已经不再影响任何选择</b>。</li>
 * </ul>
 *
 * <h2>{@code offset} 的实际后果（如实记录）</h2>
 * 因为 {@code offset} 是平方后加进适应度的成本，一个 {@code offset} 为 0.1 的参数点必须比对手近
 * 0.1 个气候单位以上才能赢；{@code offset} 为 0.375 的必须在 0.375 以上。{@code nether_mushroom_forest_edge}
 * 的气温/湿度与 {@code nether_grasslands}、{@code nether_mushroom_forest} 完全相同（0.0, 0.7），
 * 而它带 0.1 的 offset —— 所以在这套参数表下它<b>在任何位置都赢不了</b>。
 * {@code BiomePlacementTest#climateUnreachableBiomesAreOnlyTheOffsetShadowedTwin} 把这条锁住并列出
 * 全部不可达群系；这是上游数据 + 原版规则推出来的结果，本类不替它"修好"。
 *
 * <h2>线程安全</h2>
 * 构造后不可变；{@link ClimateSampler} 由调用方保证线程安全。选择过程不分配、不加锁。
 */
public final class BiomePlacement {
    /**
     * {@code upside_down_forest} 的键 —— 17 个 JSON 里唯一带 {@code "vertical": true} 的那个。
     *
     * @deprecated 默认路径（原版 {@code multi_noise}）没有竖直气候轴，这个常量不再影响选择。
     *             留着是为了不打断仍把它当"键常量"用的地形/植被代码。
     */
    @Deprecated(forRemoval = true)
    public static final String VERTICAL_KEY = "upside_down_forest";

    /**
     * 旧的竖带下界（y=96）。
     *
     * @deprecated 竖带已删除：{@link #keyAt(int, int, int)} 现在恒等于 {@link #keyAt(int, int)}。
     *             留着只为让仍在引用它的代码继续编译，任何判断逻辑都应改掉。
     */
    @Deprecated(forRemoval = true)
    public static final int CEILING_BAND_FLOOR = 96;

    /**
     * 两参 {@code keyAt} 采样时用的 quart y。
     *
     * <p>0 是精确的而不是任取的：下界 {@code noise_router} 的 {@code temperature}/{@code vegetation}
     * 都是 {@code y_scale = 0.0} 的噪声，采样结果与 y 无关（见类注释）。
     */
    public static final int SAMPLE_QUART_Y = 0;

    /** 一个群系的 {@code biome_data} 数据，浮点字段与 JSON 逐字一致。 */
    public record Climate(
            String key,
            float temperature,
            float humidity,
            float offset,
            float genChance,
            String parent,
            String edge,
            int edgeSize,
            boolean vertical) {

        /** 该行的量化参数点（温度、湿度来自本行，其余四轴为 0，与 JSON 一致）。 */
        public TargetPoint point() {
            return new TargetPoint(TargetPoint.quantize(temperature),
                    TargetPoint.quantize(humidity), 0L, 0L, 0L, 0L);
        }

        /** {@code offset} 的量化值。原版把量化后的 {@code offset} 平方后加进适应度。 */
        public long quantizedOffset() {
            return TargetPoint.quantize(offset);
        }

        /** 这一行是否属于原版 5 个下界群系（而不是 23 个 BetterNether 群系之一）。 */
        public boolean vanilla() {
            return key.indexOf(':') >= 0;
        }
    }

    /** 合并后的参数点顺序：原版 5 个在前，BetterNether 23 个按 {@link NetherBiomes#ALL} 在后。 */
    private static final List<Climate> TABLE = table();

    private static final Map<String, Climate> BY_KEY = byKey();

    /** 与 {@link #TABLE} 同下标的键，热路径用数组避免每次 {@code get(i).key()}。 */
    private static final String[] KEYS = keys();

    private static final long[] POINT_TEMPERATURE = axis(Axis.TEMPERATURE);
    private static final long[] POINT_HUMIDITY = axis(Axis.HUMIDITY);
    private static final long[] OFFSET_SQUARED = offsetSquared();

    private static final int POINT_COUNT = KEYS.length;

    private final ClimateSampler sampler;
    private final BiomeSelector selector;

    /**
     * 用线性参考裁决器（{@link #select}）。
     *
     * <p>这条路径给测试与"手边没有 serverJar 的场景"。生产走
     * {@link #BiomePlacement(ClimateSampler, BiomeSelector)} + {@code NmsBiomeSelector}，直接用原版
     * {@code Climate.ParameterList#findValue}。两者已被实测证明在整个气候矩形上逐点一致
     * （401×401 = 160 801 个采样点，0 处不同），见 {@link BiomeSelector}。
     *
     * @param sampler 原版气候采样器
     */
    public BiomePlacement(ClimateSampler sampler) {
        this(sampler, BiomePlacement::select);
    }

    /**
     * @param sampler  原版气候采样器；生产实现是 {@code NetherClimateSampler}
     * @param selector 裁决器；生产实现是 {@code NmsBiomeSelector}
     */
    public BiomePlacement(ClimateSampler sampler, BiomeSelector selector) {
        if (sampler == null) {
            throw new NullPointerException("ClimateSampler 不能为 null：群系选择不允许回退到自造气候");
        }
        if (selector == null) {
            throw new NullPointerException("BiomeSelector 不能为 null：23+5 个参数点必须由某条规则裁决");
        }
        this.sampler = sampler;
        this.selector = selector;
    }

    // ---------------------------------------------------------------- 采样

    /**
     * 该列的群系键。两参重载，服务地表遍历、populator 与诊断。
     *
     * @param x 方块 x
     * @param z 方块 z
     * @return {@link NetherBiomes#VANILLA_NETHER} 或 {@link NetherBiomes#ALL} 里的键
     */
    public String keyAt(int x, int z) {
        return selector.select(sampler.sample(quart(x), SAMPLE_QUART_Y, quart(z)));
    }

    /**
     * 该方块位置的群系键。
     *
     * <p>与 {@link #keyAt(int, int)} 相同：默认路径没有竖直气候轴（见类注释）。仍然把 {@code y}
     * 传进采样器，是因为原版 {@code Climate.Sampler#sample} 收三个 quart 坐标；若将来换了带竖直轴
     * 的 {@code NoiseGeneratorSettings}，这里不需要改。
     *
     * @param x 方块 x
     * @param y 方块 y
     * @param z 方块 z
     * @return {@link NetherBiomes#VANILLA_NETHER} 或 {@link NetherBiomes#ALL} 里的键
     */
    public String keyAt(int x, int y, int z) {
        return selector.select(sampler.sample(quart(x), quart(y), quart(z)));
    }

    /**
     * 该列的群系键，不带任何后处理。
     *
     * @deprecated {@code edge} 层已删除，这个方法与 {@link #keyAt(int, int)} 完全相同。
     */
    @Deprecated(forRemoval = true)
    public String baseKeyAt(int x, int z) {
        return keyAt(x, z);
    }

    /** 该列的气候目标点（诊断与测试用）。 */
    public TargetPoint targetAt(int x, int z) {
        return sampler.sample(quart(x), SAMPLE_QUART_Y, quart(z));
    }

    /**
     * 该列的气候 {温度, 湿度}，单位与 JSON 相同。
     *
     * @deprecated 换成 {@link #targetAt(int, int)}：那一个给出六轴量化值，不会因为这里返回
     *             {@code double} 而与原版的 {@code float} 截断产生偏差。
     */
    @Deprecated(forRemoval = true)
    public double[] climateAt(int x, int z) {
        TargetPoint target = targetAt(x, z);
        return new double[]{TargetPoint.unquantize(target.temperature()),
                TargetPoint.unquantize(target.humidity())};
    }

    // ---------------------------------------------------------------- 原版规则

    /**
     * 方块坐标 → quart 坐标。原版 {@code QuartPos.fromBlock(i) = i >> 2}，<b>向负无穷取整</b>。
     *
     * <p>{@code -1 >> 2 == -1}，而 {@code -1 / 4 == 0}：用除法会把 x/z 属于 {@code [-4, 4)} 的
     * 全部并进 quart 0，负半轴的群系边界整体错一格。{@code quartConversionFloorsTowardNegativeInfinity}
     * 锁住这一点。
     */
    public static int quart(int block) {
        return block >> 2;
    }

    /**
     * 一个参数点的适应度：{@code Σ distance² + offset²}，逐字对应
     * {@code Climate.ParameterPoint#fitness}。
     *
     * @param climate 参数点
     * @param target  采样点
     * @return 适应度，越小越优先
     */
    public static long fitness(Climate climate, TargetPoint target) {
        TargetPoint point = climate.point();
        long offset = climate.quantizedOffset();
        return square(distance(point.temperature(), target.temperature()))
                + square(distance(point.humidity(), target.humidity()))
                + square(distance(point.continentalness(), target.continentalness()))
                + square(distance(point.erosion(), target.erosion()))
                + square(distance(point.depth(), target.depth()))
                + square(distance(point.weirdness(), target.weirdness()))
                + offset * offset;
    }

    /**
     * 采样点 → 群系键：28 个参数点里适应度最小的那一个。
     *
     * <p>这是<b>线性参考实现</b>（{@code findValueBruteForce} 的语义）。生产路径不调它：生产用
     * {@code NmsBiomeSelector}，直接走原版 {@code Climate.ParameterList#findValue}（26.3 Paper 里
     * 是 Moonrise 的 {@code ChunkGenRTree.moonrise$findNearest}，javap 实读）。两者的等价性已用真
     * 服务端 jar 量过：401×401 = 160 801 个采样点，<b>0 处不一致</b>，并列点也一致。
     * 保留本方法是因为线性的、无 NMS 依赖的版本才是可单测的那个。
     * <ul>
     *   <li><b>并列</b>：树的胜者由遍历顺序决定，无法从本仓库证明。这里用「严格小于才替换」，
     *       于是并列时取合并顺序里靠前的那个（原版 5 个先于 BetterNether 23 个，23 个内部按
     *       {@link NetherBiomes#ALL}）。并列只可能发生在量化后恰好等距的采样值上，影响是边界上一条
     *       极窄的缝合带。</li>
     *   <li><b>剪枝</b>：RTree 用「节点包围盒到采样点的距离下界」剪枝。该下界刻意忽略
     *       {@code offset²}（{@code offset² ≥ 0}，忽略后仍是合法下界），所以剪枝不会剔掉真胜者，
     *       非并列情形与暴力扫的结果相同。</li>
     * </ul>
     * 因此"与 Paper 逐位一致"这句话成立的前提，是生产路径真的用 NMS 的 {@code findValue} 裁决
     * 这 28 个点 —— 那正是 {@code NmsBiomeSelector} 做的事；本方法只是不依赖 NMS 的等价参考。
     *
     * @param target 采样点
     * @return 键
     */
    public static String select(TargetPoint target) {
        long targetTemperature = target.temperature();
        long targetHumidity = target.humidity();
        long common = square(distance(0L, target.continentalness()))
                + square(distance(0L, target.erosion()))
                + square(distance(0L, target.depth()))
                + square(distance(0L, target.weirdness()));

        int best = 0;
        long bestFitness = square(distance(POINT_TEMPERATURE[0], targetTemperature))
                + square(distance(POINT_HUMIDITY[0], targetHumidity))
                + common + OFFSET_SQUARED[0];
        for (int i = 1; i < POINT_COUNT; i++) {
            long fitness = square(distance(POINT_TEMPERATURE[i], targetTemperature))
                    + square(distance(POINT_HUMIDITY[i], targetHumidity))
                    + common + OFFSET_SQUARED[i];
            // 严格小于：并列时保留先出现的那个，即合并顺序决定并列胜者。
            if (fitness < bestFitness) {
                best = i;
                bestFitness = fitness;
            }
        }
        return KEYS[best];
    }

    /** 原版 {@code Parameter#distance} 在单点上化简出的绝对值。 */
    private static long distance(long point, long target) {
        long above = target - point;
        return above > 0 ? above : Math.max(point - target, 0L);
    }

    /** 原版 {@code Mth.square(long)}。 */
    private static long square(long value) {
        return value * value;
    }

    // ---------------------------------------------------------------- 数据访问

    /** 合并后的 28 个参数点（原版 5 + BetterNether 23）。只读。 */
    public static List<Climate> climates() {
        return TABLE;
    }

    /** 一行，或 {@code null}（键不属于这 28 个）。 */
    public static Climate climateOf(String key) {
        return BY_KEY.get(key);
    }

    /** 上游授权的 {@code genChance}；<b>不参与选择</b>，只作为数据供报告与测试核对。 */
    public static Double weightOf(String key) {
        Climate climate = BY_KEY.get(key);
        return climate == null ? null : (double) climate.genChance();
    }

    /** 23 个 BetterNether 行 {@code genChance} 之和；归一化用途，不是概率。 */
    public static double totalGenChance() {
        double total = 0.0;
        for (Climate climate : TABLE) {
            if (!climate.vanilla()) total += climate.genChance();
        }
        return total;
    }

    /** 上游 {@code parent} 字段，原样返回；<b>不参与选择</b>。 */
    public static String parentOf(String key) {
        Climate climate = BY_KEY.get(key);
        return climate == null ? null : climate.parent();
    }

    /** 上游 {@code edge} 字段，原样返回；<b>不参与选择</b>。 */
    public static String edgeOf(String key) {
        Climate climate = BY_KEY.get(key);
        return climate == null ? null : climate.edge();
    }

    /** 上游 {@code edgeSize} 字段，原样返回；<b>不参与选择</b>。 */
    public static int edgeSizeOf(String key) {
        Climate climate = BY_KEY.get(key);
        return climate == null ? 0 : climate.edgeSize();
    }

    /** 上游 {@code vertical} 字段，原样返回；<b>不参与选择</b>。 */
    public static boolean verticalOf(String key) {
        Climate climate = BY_KEY.get(key);
        return climate != null && climate.vertical();
    }

    // ---------------------------------------------------------------- 参数表

    private enum Axis {TEMPERATURE, HUMIDITY}

    private static List<Climate> table() {
        Map<String, Climate> rows = new LinkedHashMap<>();
        // 原版 minecraft:nether 预设的 5 个点。不是抄来的常数：它们由
        // MultiNoiseBiomeSourceParameterList.Preset.NETHER.provider() 直接读出
        // （见 BiomePlacementTest#tableHoldsTheFiveVanillaNetherPresetPoints，它把这张表与
        // 原版预设逐点比对）。
        rows.put("minecraft:nether_wastes", new Climate("minecraft:nether_wastes",
                0.0f, 0.0f, 0.0f, 0.0f, null, null, 0, false));
        rows.put("minecraft:soul_sand_valley", new Climate("minecraft:soul_sand_valley",
                0.0f, -0.5f, 0.0f, 0.0f, null, null, 0, false));
        rows.put("minecraft:crimson_forest", new Climate("minecraft:crimson_forest",
                0.4f, 0.0f, 0.0f, 0.0f, null, null, 0, false));
        rows.put("minecraft:warped_forest", new Climate("minecraft:warped_forest",
                0.0f, 0.5f, 0.375f, 0.0f, null, null, 0, false));
        rows.put("minecraft:basalt_deltas", new Climate("minecraft:basalt_deltas",
                -0.5f, 0.0f, 0.175f, 0.0f, null, null, 0, false));

        // 23 行，逐字来自 upstream/BetterNether/src/main/generated/data/betternether/wover/
        // worldgen/biome_data/*.json；注释里的文件名就是那一行的出处。
        put(rows, "bone_reef", -0.01f, -0.05f, 0.0f, 1.25f, null, null, 0, false);
        put(rows, "crimson_glowing_woods", 0.4f, -0.05f, 0.0f, 0.375f, "minecraft:crimson_forest", null, 0, false);
        put(rows, "crimson_pinewood", 0.35f, 0.1f, 0.0f, 0.375f, "minecraft:crimson_forest", null, 0, false);
        put(rows, "flooded_deltas", -0.3f, -0.01f, 0.175f, 0.375f, "minecraft:basalt_deltas", null, 0, false);
        put(rows, "gloomwood", -0.25f, -0.35f, 0.0f, 0.375f, null, null, 0, false);
        put(rows, "gravel_desert", -0.1f, 0.1f, 0.2f, 1.25f, null, null, 0, false);
        put(rows, "magma_land", 0.8f, -0.7f, 0.0f, 1.25f, null, null, 0, false);
        put(rows, "nether_grasslands", 0.0f, 0.7f, 0.0f, 1.25f, null, null, 0, false);
        put(rows, "nether_jungle", 0.5f, 0.8f, 0.0f, 1.25f, null, null, 0, false);
        put(rows, "nether_mushroom_forest", 0.0f, 0.7f, 0.0f, 1.25f, null,
                "betternether:nether_mushroom_forest_edge", 6, false);
        put(rows, "nether_mushroom_forest_edge", 0.0f, 0.7f, 0.1f, 1.25f, null, null, 0, false);
        put(rows, "nether_swampland", 0.1f, 0.8f, 0.1f, 1.25f, null, null, 0, false);
        put(rows, "nether_swampland_terraces", 0.08f, 0.85f, 0.1f, 1.25f,
                "betternether:nether_swampland", null, 0, false);
        put(rows, "old_fungiwoods", 0.0f, 0.65f, 0.0f, 0.375f, "betternether:nether_mushroom_forest", null, 0, false);
        put(rows, "old_swampland", 0.1f, 0.75f, 0.1f, 1.25f, "betternether:nether_swampland", null, 0, false);
        put(rows, "old_warped_woods", 0.0f, 0.55f, 0.375f, 1.25f, "minecraft:warped_forest", null, 0, false);
        put(rows, "poor_nether_grasslands", 0.0f, 0.3f, 0.0f, 0.375f, "betternether:nether_grasslands", null, 0, false);
        put(rows, "soul_plain", 0.01f, -0.5f, 0.0f, 1.25f, "minecraft:soul_sand_valley", null, 0, false);
        put(rows, "sulfuric_bone_reef", 0.0f, 0.6f, 0.37f, 0.375f, "betternether:bone_reef", null, 0, false);
        put(rows, "upside_down_forest", 0.45f, 0.8f, 0.125f, 0.3125f, null, null, 0, true);
        put(rows, "upside_down_forest_cleared", 0.45f, 0.3f, 0.125f, 0.625f,
                "betternether:upside_down_forest", null, 0, false);
        put(rows, "wart_forest", -0.5f, 0.5f, 0.0f, 1.25f, null, "betternether:wart_forest_edge", 9, false);
        put(rows, "wart_forest_edge", -0.5f, 0.45f, 0.0f, 1.25f, null, null, 0, false);

        // 双向完整性：5 个原版点齐全、23 个群系各有且仅有一行、没有多余行。
        if (rows.size() != NetherBiomes.VANILLA_NETHER.size() + NetherBiomes.ALL.size()) {
            throw new IllegalStateException("参数表条目数与群系表不一致：" + rows.size() + " vs "
                    + (NetherBiomes.VANILLA_NETHER.size() + NetherBiomes.ALL.size()));
        }
        // 两侧都必须 remove：最后那个"没有多余行"的检查是靠清空 rows 来做的。上一版这里用 get，
        // 于是 5 个原版行永远留在 Map 里，类初始化阶段就抛"参数表里有未声明的群系"，整批测试跟着炸。
        List<Climate> ordered = new ArrayList<>(rows.size());
        for (String key : NetherBiomes.VANILLA_NETHER) {
            Climate climate = rows.remove(key);
            if (climate == null) {
                throw new IllegalStateException("缺少原版下界群系参数：" + key);
            }
            ordered.add(climate);
        }
        for (NetherBiomes.Entry entry : NetherBiomes.ALL) {
            Climate climate = rows.remove(entry.key());
            if (climate == null) {
                throw new IllegalStateException("群系缺少 biome_data 参数：" + entry.key());
            }
            ordered.add(climate);
        }
        for (String key : rows.keySet()) {
            throw new IllegalStateException("参数表里有未声明的群系：" + key);
        }
        return Collections.unmodifiableList(ordered);
    }

    private static void put(Map<String, Climate> rows, String key, float temperature, float humidity,
                            float offset, float genChance, String parent, String edge, int edgeSize,
                            boolean vertical) {
        if (genChance <= 0.0f) {
            throw new IllegalStateException("genChance 必须为正：" + key + " = " + genChance);
        }
        if (edgeSize < 0) {
            throw new IllegalStateException("edgeSize 不能为负：" + key + " = " + edgeSize);
        }
        if ((edgeSize > 0) != (edge != null)) {
            throw new IllegalStateException("edge 与 edgeSize 必须同时出现：" + key);
        }
        Climate previous = rows.put(key, new Climate(key, temperature, humidity, offset, genChance,
                parent, edge, edgeSize, vertical));
        if (previous != null) {
            throw new IllegalStateException("群系参数表出现重复键：" + key);
        }
    }

    private static Map<String, Climate> byKey() {
        Map<String, Climate> map = new LinkedHashMap<>();
        for (Climate climate : TABLE) {
            map.put(climate.key(), climate);
        }
        return Collections.unmodifiableMap(map);
    }

    private static String[] keys() {
        String[] keys = new String[TABLE.size()];
        for (int i = 0; i < keys.length; i++) {
            keys[i] = TABLE.get(i).key();
        }
        return keys;
    }

    private static long[] axis(Axis axis) {
        long[] values = new long[TABLE.size()];
        for (int i = 0; i < values.length; i++) {
            Climate climate = TABLE.get(i);
            values[i] = TargetPoint.quantize(axis == Axis.TEMPERATURE
                    ? climate.temperature() : climate.humidity());
        }
        return values;
    }

    private static long[] offsetSquared() {
        long[] values = new long[TABLE.size()];
        for (int i = 0; i < values.length; i++) {
            long offset = TABLE.get(i).quantizedOffset();
            values[i] = offset * offset;
        }
        return values;
    }
}
