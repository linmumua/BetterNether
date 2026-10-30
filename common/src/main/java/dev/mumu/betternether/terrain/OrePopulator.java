package dev.mumu.betternether.terrain;

import dev.mumu.betternether.biome.BiomePlacement;
import dev.mumu.betternether.config.NetherConfig;
import dev.mumu.betternether.worldgen.DecorationRandom;
import dev.mumu.betternether.worldgen.DecorationScheduler;
import dev.mumu.betternether.worldgen.Layers;
import dev.mumu.betternether.worldgen.Point;
import dev.mumu.betternether.worldgen.RegionView;
import dev.mumu.betternether.worldgen.WorldView;
import org.bukkit.HeightMap;
import org.bukkit.block.data.BlockData;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.LimitedRegion;
import org.bukkit.generator.WorldInfo;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 放置 BetterNether 的 <b>8 条</b>矿物 placed feature（下界）。
 *
 * <h2>为什么是 8 条而不是 7 条</h2>
 * {@code betternether:gloomwood_ceiling_debris} 也是 {@code minecraft:ore}：上游
 * {@code registry/features/placed/NetherOresPlaced.java} 把它和另外 7 条一起注册到
 * {@code UNDERGROUND_DECORATION}，{@code feature/gloomwood_ceiling_debris.json} 是
 * {@code type: minecraft:ore}、size 2、两个 target（sculk/netherrack → ancient debris）。
 * 它只是链更长（{@code wover:every_layer top:false} + 尾部 {@code rarity_filter chance 5}），
 * 不是植被。
 *
 * <h2>为什么用 {@link LimitedRegion} 而不是 {@code World}</h2>
 * 重写的是 {@link BlockPopulator#populate} 的 {@code (WorldInfo, Random, int, int, LimitedRegion)}
 * 形式。{@code (World, Random, Chunk)} 那个看起来简单但在 Folia 上是错的：通过
 * {@code world.getBlockAt(...)} 写方块会碰到「另一个 region 拥有的坐标」，而本线程只拥有自己的
 * region。{@link LimitedRegion} 把读写限制在服务端为本 populator 预留的邻域，越界写会抛异常 ——
 * 正是我们想要的（裁掉跨界的矿团，绝不跨区写）。
 *
 * <p>原版装饰此时已经跑过（{@code shouldGenerateDecorations = true}），所以矿团会覆盖原版的石英与
 * 金矿。这个顺序就是矿必须放在这里、而不是放在 {@code generateSurface} 的原因。
 *
 * <h2>执行顺序：逐字按 {@code placement} 数组，而且是深度优先</h2>
 * 每条 placed feature 的链都由 {@link NetherConfig.OreStep} 列表描述，按数组次序执行：一步的结果
 * 交给下一步，<b>并且每个到达链尾的位置立刻跑一次特征</b>。这一点和原版
 * {@code FeaturePlacer.place} 的栈式深度优先一致：它弹出的位置若已无修饰符剩余，就当场
 * {@code feature.place(...)}，然后才继续下一个位置。写成「先把所有位置算出来、再统一放」会改变
 * 随机数的消费次序 —— 特征自己也要抽 {@code nextFloat}/{@code nextInt}/{@code nextDouble}，
 * 交错顺序变了，同样种子就得到不同的矿。
 *
 * <h2>{@code wover:every_layer} 依据的是 wover 真实源码，不是推断</h2>
 * 已用 {@code gh api} 读到 release/26.3 的原始文件：
 * {@code repos/quiqueck/WorldWeaver/contents/wover-feature-api/src/main/java/de/ambertation/wover/feature/api/placed/modifiers/EveryLayer.java}。
 * 实现与 {@code EveryLayer.modify} + {@code findUnderGroundYPosition} 逐行一致：
 * <ul>
 *   <li>{@code startY = min(MOTION_BLOCKING 高度, max)}，本表 JSON 只写 {@code min}，故 {@code max} 取
 *       codec 的缺省 {@code Integer.MAX_VALUE}；{@code minHeight = max(ctx.getMinY(), min)}。</li>
 *   <li><b>自顶向下</b>扫；{@code top:false} 时条件为
 *       {@code isTerrain(now) && isFreeOrFluid(below) && !now.is(BEDROCK)}，<b>发射 {@code y-1}</b>
 *       （地形方块下面那一格）。</li>
 *   <li>每发射一层就<b>同步</b>跑完链上后面的步骤，然后 {@code y = layerY - 1} 继续，直到
 *       {@code Integer.MAX_VALUE} 哨兵。</li>
 *   <li>{@code isTerrain} = {@code BlockHelper.isTerrain} = {@code state.is(CommonBlockTags.TERRAIN)}
 *       = tag {@code wover:surfaces/terrain}（{@code makeWorldWeaverTag("surfaces/terrain")}），
 *       <b>不是</b>「实心」也不是 {@code wover:surfaces/nether/terrain}。</li>
 *   <li>{@code isFreeOrFluid} = {@code state.isAir() || state.liquid()}（{@code BlockHelper.isFluid}），
 *       <b>不含</b> waterlogged 那种「有 fluidState 但不 liquid」的方块。</li>
 * </ul>
 *
 * <h2>{@code every_layer} 与植被共用同一份扫描</h2>
 * 层扫描<b>不</b>在本类里：{@code CEILING_LAYERS} 直接调 {@code worldgen.Layers#emit}，也就是植被那条线
 * 逐行移植 wover {@code EveryLayer} 的那一份（自顶向下、{@code startY = min(MOTION_BLOCKING, max)}、
 * {@code isTerrain(now) && isFreeOrFluid(y-1) && !now.is(BEDROCK)}、发射 {@code y-1}、
 * 同步 Consumer、{@code layerY-1} 续扫）。地形判据因此也只剩一份：
 * {@code Layers#terrainIds}（装载期由 {@code WorldgenLoader#readBlockTags} 注入的真实
 * {@code wover:surfaces/terrain} id 集），本类既不自建 netherrack/blackstone 名单，也不用
 * 「非空气」近似，更不用 {@code wover:surfaces/nether/terrain}。
 *
 * <p>下界的语义由 {@code Layers#emit} 统一规定为「允许发射的最低层」（天花板模式内部把扫描下界抬高
 * 一格，因为那一步发射的是 {@code y-1}），对应 wover 的
 * {@code for (y = startY; y >= minHeight + 1; --y) emit(y-1)}。本类直接传
 * {@code max(getMinY(), JSON 的 min)}，不再自己加偏移。
 *
 * <h2>为什么是精确移植而不是调用真实 {@code OreFeature}</h2>
 * 先纠正一个曾经写在这里的错误前提：本项目<b>拿得到</b>矿方块的真实 NMS 状态。CraftEngine 的
 * 自定义方块在世界里是 {@code DelegatingBlock} 注册的真实物理状态，
 * {@code CraftEngineBlocks.getBukkitBlockData(defaultState())} 给出的就是 {@code customBlockState}，
 * 因此 {@code net.minecraft.world.level.levelgen.feature.BlockReplacement(RuleTest, BlockState)}
 * （26.3 的 {@code OreFeature} 构造参数）是喂得进去的。也就是说，委托给真实 NMS
 * {@code OreFeature#place} 在**技术上可行**，这条路并没有被 CE 堵死。
 *
 * <p>之所以仍然按 {@code mucore-26.3.jar} 的 {@code OreFeature}/{@code AbstractOreFeature} 字节码
 * 逐条复刻，是因为两条本项目自己的硬约束：
 * <ul>
 *   <li><b>必须能在没有服务端的情况下回归。</b>{@code common/build.gradle.kts} 把服务端 jar 从测试
 *       类路径里摘掉了，而这条几何（球链、嵌球剔除、{@code BitSet}、discard 的随机消费次序）恰恰
 *       是最容易写错、最需要逐格断言的部分。委托给 NMS 就只能靠起服实测。</li>
 *   <li><b>写方块的入口必须是 Folia 安全的那个。</b>{@code OreFeature} 直接
 *       {@code LevelChunkSection#setBlockState}，绕开了 {@link LimitedRegion} 的越界守卫；本项目
 *       的规则是「region 之外一个方块都不许写」，写入统一走
 *       {@code LimitedRegion#setBlockData}。</li>
 * </ul>
 * 复刻的结果与字节码等价：随机消费的次序与次数一致，写入仍走 CraftEngine 解析出来的真实
 * {@link BlockData}，替换判据走<b>方块身份 id</b> 而不是 {@code Material}（见
 * {@link OreRegion#blockId}）。
 *
 * <h2>种子的现状（已知缺口，不是等价）</h2>
 * 原版给每条 placed feature 一条独立流：{@code setFeatureSeed(decorationSeed, featureIndex, step)}，
 * 其中 {@code step} 是 {@link NetherConfig#ORE_DECORATION_STEP}，{@code featureIndex} 是**该 step
 * 全局有序列表里的下标**。那个下标由 {@code FeatureSorter} 的拓扑序决定，只有全局装饰调度器
 * （{@link DecorationScheduler}）算得出来。所以：
 * <ul>
 *   <li>调度器把 {@link DecorationScheduler.Plan} 交进来（{@link #useDecorationPlan}）后，本类用
 *       {@link DecorationRandom#forFeature} 取真实流；</li>
 *   <li>拿不到 plan、或 plan 里没有这条 feature（返回 -1）时，<b>不编造下标</b>，而是明确告警一次并
 *       退回服务端传进来的那个 {@link Random}。此时一个区块内 8 条 feature 顺序共用一条流，与
 *       上游逐条 featureSeed 不等价 —— 这是缺口，不是等价实现。</li>
 * </ul>
 *
 * <p><b>即便 plan 已接上，下标仍与上游有偏移。</b>上游每个 BN 群系的 {@code features[7]} 在 BN 矿
 * <b>之前</b>还有一批 {@code minecraft:} 条目（{@code gloomwood} 的实测前缀是
 * {@code spring_open / patch_fire / patch_soul_fire / glowstone_extra / glowstone /
 * brown_mushroom_nether / red_mushroom_nether / ore_magma / spring_closed / ore_gravel_nether /
 * ore_blackstone / ore_gold_nether / ore_quartz_nether / ore_ancient_debris_large /
 * ore_debris_small}，之后才是 BN 矿），而当前的全局计划只含 BN 的条目。因此
 * {@code setSeed(deco + index + 10000*7)} 里的 {@code index} 比上游小一截，矿的数量与位置仍会漂。
 * 消掉它需要把整个 {@code features[7]} 数组按序导出成占位条目（只占下标、不放置），那是构建期清单
 * 的事，不在本类里。**不要把当前状态写成「与上游逐位等价」。**
 */
public final class OrePopulator extends BlockPopulator {
    /** 3.1415927f：{@code OreFeature#place} 里那个 float 常量。 */
    private static final float PI = (float) Math.PI;
    /** {@code Math.sin(1.0)} 的表步长倒数：{@code Mth.sin}/{@code Mth.cos} 的查表常数。 */
    private static final double SIN_SCALE = 10430.378350470453;
    /** {@code Mth.SIN} 的长度，也是查表掩码。 */
    private static final int SIN_MASK = 65535;
    /** {@code Mth.cos} 相对 {@code Mth.sin} 的四分之一周期偏移。 */
    private static final double COS_OFFSET = 16384.0;

    /**
     * {@code Mth.SIN} 的复刻。
     *
     * <p>为什么不直接调用 {@code net.minecraft.util.Mth}：单元测试的类路径刻意剔除了服务端 jar
     * （见 {@code common/build.gradle.kts} 里 {@code classpath.minus(serverJar)}），而这条几何必须有
     * 纯单元回归。表的构造与 {@code Mth.lambda$static$0} 一致：
     * {@code SIN[i] = (float) Math.sin(i / 10430.378350470453)}。
     */
    private static final float[] SIN = new float[SIN_MASK + 1];

    static {
        for (int i = 0; i <= SIN_MASK; i++) {
            SIN[i] = (float) Math.sin(i / SIN_SCALE);
        }
    }

    private final NetherConfig config;
    private final BiomePlacement placement;
    /**
     * 每个 target 解析好的方块状态，按 {@code rule.targets()} 的顺序对齐。
     *
     * <p>key 是 placed feature id（ruby 三条变体共用同一个方块，不能用方块 id 做 key）。
     * 只要有一条 target 的 state 解析不出来，整条 placed feature 就跳过 —— 半条链比没有更糟。
     */
    private final Map<String, List<BlockData>> resolved = new LinkedHashMap<>();
    /** 全局装饰调度器算出来的 step 有序表；{@code null} 表示还没接线（见类注释的种子缺口）。 */
    private volatile DecorationScheduler.Plan plan;
    /** 种子缺口只喊一次；进程级即可，与实例无关。 */
    private static volatile boolean seedGapReported;

    /**
     * 告警出口。
     *
     * <p>用 JDK 日志而不是 {@code Bukkit.getLogger()}：这条告警会在单元测试里跑到（测试注入的假区域
     * 天然答不出真实方块身份），而服务端尚未初始化时 Bukkit 的 logger 是 {@code null}。
     */
    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger("BetterNether");

    public OrePopulator(NetherConfig config, BiomePlacement placement) {
        this.config = config;
        this.placement = placement;
        for (NetherConfig.OreRule rule : NetherConfig.UPSTREAM_ORE_RULES) {
            if (resolved.containsKey(rule.placedFeatureId())) {
                continue;
            }
            List<BlockData> states = new ArrayList<>(rule.targets().size());
            boolean complete = true;
            for (NetherConfig.OreTargetState target : rule.targets()) {
                BlockData data = CraftEngineLookup.lookup(target.stateId());
                if (data == null) {
                    complete = false;
                    break;
                }
                states.add(data);
            }
            if (complete) {
                resolved.put(rule.placedFeatureId(), List.copyOf(states));
            }
        }
    }

    /** 是否至少解析出一条完整的矿；插件把它当作「资源部分可用」的信号。 */
    public boolean anyResolved() {
        return !resolved.isEmpty();
    }

    /**
     * 接上全局装饰调度器的 step 表，让每条矿拿到自己的 {@code featureSeed}。
     *
     * <p>{@code null} 或不含某条 feature 时退回服务端传入的 {@link Random} 并告警一次。
     */
    public void useDecorationPlan(@Nullable DecorationScheduler.Plan decorationPlan) {
        this.plan = decorationPlan;
    }

    @Override
    public void populate(@NotNull WorldInfo worldInfo, @NotNull Random random, int chunkX, int chunkZ,
                         @NotNull LimitedRegion region) {
        List<NetherConfig.OreRule> rules = config.oreRules();
        if (rules.isEmpty() || resolved.isEmpty()) {
            return;
        }
        int minY = worldInfo.getMinHeight();
        int maxY = worldInfo.getMaxHeight() - 1;
        OreRegion view = new RegionOreRegion(region, placement, random, resolved, minY, maxY);
        List<String> chunkBiomes = DecorationScheduler.chunkBiomes(placement, chunkX, chunkZ);
        // generator.seed 的语义沿用本项目的约定（0 = 世界自己的种子），缺省时就是原版的世界种子。
        placeChunk(rules, resolved.keySet(), chunkBiomes, random, config.seedFor(worldInfo.getSeed()),
                chunkX, chunkZ, plan, view);
    }

    /**
     * 一个区块的矿。拆出 {@link #populate} 是为了让放置规则能在没有服务端的情况下被单元测试驱动：
     * {@link OreRegion} 是唯一知道 Bukkit 的东西。
     *
     * @param rules       按表顺序的 placed feature
     * @param resolved    已经解析出全部 target 状态的 placed feature id；缺项跳过
     * @param chunkBiomes 这个区块邻域出现过的群系 key（{@code DecorationScheduler.chunkBiomes}）
     * @param random      服务端给的流；只在拿不到 {@code featureSeed} 时才会用到（见类注释）
     * @param worldSeed   世界种子，用来算 {@code decorationSeed}
     * @param plan        全局装饰调度器算出的 step 表；{@code null} = 未接线
     */
    static void placeChunk(List<NetherConfig.OreRule> rules, Set<String> resolved,
                           List<String> chunkBiomes, Random random, long worldSeed,
                           int chunkX, int chunkZ, @Nullable DecorationScheduler.Plan plan,
                           OreRegion view) {
        for (NetherConfig.OreRule rule : rules) {
            if (!resolved.contains(rule.placedFeatureId())) {
                continue;
            }
            // 原版只跑「本区块（3×3 邻域）群系里列过的 placed feature」。整条都不在这个邻域里时
            // 一次随机都不该消费 —— 否则 swampland 区块会白白推进随机流，把它后面的矿全推错位。
            if (!listedInAnyBiome(rule, chunkBiomes)) {
                continue;
            }
            Random stream = streamFor(rule, plan, random, worldSeed, chunkX, chunkZ);
            Point origin = new Point(chunkX << 4, view.minY(), chunkZ << 4);
            walk(rule, 0, origin, stream, view,
                    (x, y, z) -> placeOre(rule, stream, x, y, z, view));
        }
    }

    /**
     * 只跑修饰符链、不跑特征，把链尾的位置收集出来。
     *
     * <p>给单元测试用：这样「count 不消费随机」「rarity 每次只抽一个 nextFloat」「every_layer 展开成
     * 哪些层」这些性质可以直接断言，而不必从方块布局反推。
     */
    static List<Point> placementTargets(NetherConfig.OreRule rule, Random random, int chunkX, int chunkZ,
                                        OreRegion view) {
        List<Point> out = new ArrayList<>();
        walk(rule, 0, new Point(chunkX << 4, view.minY(), chunkZ << 4), random, view,
                (x, y, z) -> out.add(new Point(x, y, z)));
        return out;
    }

    /** 链尾的位置回调；{@code placeChunk} 与 {@code placementTargets} 只在这里分叉。 */
    @FunctionalInterface
    interface OrePlacementCallback {
        void at(int x, int y, int z);
    }

    /**
     * 深度优先执行 {@code placement} 链的第 {@code index} 步。
     *
     * <p>DFS 而不是 BFS：原版 {@code FeaturePlacer} 用的是栈，一个位置走完整条链、当场放特征，
     * 再回到下一个位置。用 BFS（先把所有位置算完再放）会让特征消费的随机插到别处。
     */
    private static void walk(NetherConfig.OreRule rule, int index, Point point, Random random,
                             OreRegion view, OrePlacementCallback at) {
        List<NetherConfig.OreStep> steps = rule.placement();
        if (index >= steps.size()) {
            at.at(point.x(), point.y(), point.z());
            return;
        }
        NetherConfig.OreStep step = steps.get(index);
        switch (step.kind()) {
            // minecraft:count：常量次数，复制位置，不消费随机（ConstantInt#sample 直接返回 value）。
            case COUNT -> {
                for (int i = 0; i < step.value(); i++) {
                    walk(rule, index + 1, point, random, view, at);
                }
            }
            // minecraft:rarity_filter：每个位置 nextFloat() < 1.0f / chance 才保留。
            case RARITY -> {
                if (random.nextFloat() < 1.0f / step.value()) {
                    walk(rule, index + 1, point, random, view, at);
                }
            }
            // minecraft:in_square：先抽 x 偏移，再抽 z 偏移（字节码次序）。
            case IN_SQUARE -> {
                int x = point.x() + random.nextInt(16);
                int z = point.z() + random.nextInt(16);
                walk(rule, index + 1, new Point(x, point.y(), z), random, view, at);
            }
            // minecraft:height_range：换掉 y，不换 x/z。
            case HEIGHT_RANGE -> walk(rule, index + 1,
                    new Point(point.x(), sampleHeight(rule, random, view), point.z()), random, view, at);
            // minecraft:biome：位置的群系必须列出这条 placed feature，否则位置被淘汰。
            case BIOME -> {
                if (rule.allowsBiome(view.biomeAt(point.x(), point.y(), point.z()))) {
                    walk(rule, index + 1, point, random, view, at);
                }
            }
            // wover:every_layer top:false —— 直接调用植被那条线已经逐行移植好的
            // {@link Layers#emit}（同一个 wover EveryLayer 源码、同一份装载期注入的 terrain id 集）。
            // 参数就是 EveryLayer 的字段：min = JSON 的 min（本表为 4）、max = codec 缺省 MAX_VALUE、
            // top = false（发射地形方块下面那一格）。emit 是**同步** Consumer：这一层放完才扫下一层。
            //
            // 下界直接传「允许发射的最低层」= max(getMinY(), JSON 的 min)：Layers.emit 现在两种模式
            // 同义（天花板模式内部会把扫描下界抬高 1，因为那一步发射的是 y-1），与 wover
            // `for (y = startY; y >= minHeight + 1; --y) emit(y-1)` 的最低发射层对齐。
            case CEILING_LAYERS -> Layers.emit(view.worldView(), point.x(), point.z(),
                    Math.max(view.minY(), step.value()), Integer.MAX_VALUE, false,
                    layer -> walk(rule, index + 1, layer, random, view, at));
            default -> throw new IllegalStateException("未处理的修饰符：" + step.kind());
        }
    }

    private static boolean listedInAnyBiome(NetherConfig.OreRule rule, List<String> chunkBiomes) {
        for (String biome : chunkBiomes) {
            if (rule.allowsBiome(biome)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 这条 placed feature 在这个区块的随机流。
     *
     * <p>能拿到全局 step 下标时走原版口径（{@link DecorationRandom#forFeature}）；拿不到就退回
     * {@code fallback} 并告警一次 —— 不自己造种子，也不拿「规则表下标」冒充全局下标。
     */
    private static Random streamFor(NetherConfig.OreRule rule, @Nullable DecorationScheduler.Plan plan,
                                    Random fallback, long worldSeed, int chunkX, int chunkZ) {
        if (plan != null) {
            int index = plan.featureIndex(rule.placedFeatureId(), NetherConfig.ORE_DECORATION_STEP);
            if (index >= 0) {
                long decorationSeed = DecorationRandom.decorationSeed(worldSeed, chunkX << 4, chunkZ << 4);
                return DecorationRandom.forFeature(decorationSeed, index, NetherConfig.ORE_DECORATION_STEP);
            }
        }
        reportSeedGapOnce(rule.placedFeatureId());
        return fallback;
    }

    private static void reportSeedGapOnce(String placedFeatureId) {
        if (seedGapReported) {
            return;
        }
        seedGapReported = true;
        LOG.warning("矿物拿不到 step " + NetherConfig.ORE_DECORATION_STEP + " 的全局 featureIndex"
                + "（如 " + placedFeatureId + "）：装饰调度器还没接上，矿改为共用服务端传入的随机流。"
                + "布局仍然确定，但与上游的逐 feature 种子不等价。");
    }

    /**
     * {@code height_range} 的一个高度。
     *
     * <p>{@code uniform} 是 {@code Mth.randomBetweenInclusive}（一次 {@code nextInt(span)}）；
     * {@code trapezoid} 是 {@code TrapezoidHeightProvider#sample} 在 {@code plateau = 0} 时的形态：
     * {@code min + randInt(0, range - range/2) + randInt(0, range/2)}，两次 {@code nextInt}，
     * <b>先 spread 后 half</b>（Java 的左到右求值顺序，字节码也是这个次序）。
     */
    static int sampleHeight(NetherConfig.OreRule rule, Random random, OreRegion view) {
        int minY = rule.resolveMinY(view.minY(), view.maxY() + 1);
        int maxY = rule.resolveMaxY(view.minY(), view.maxY() + 1);
        if (minY > maxY) {
            // UniformHeight/TrapezoidHeight 打印 "Empty height range" 后返回 min 且**不消费随机**。
            return minY;
        }
        int range = maxY - minY;
        if (rule.shape() == NetherConfig.OreShape.UNIFORM) {
            return minY + random.nextInt(range + 1);
        }
        // TrapezoidHeight：plateau(0) >= range 只在 range <= 0 时成立，那时退化成一次区间采样。
        if (range <= 0) {
            return minY + random.nextInt(range + 1);
        }
        int half = range / 2;
        int spread = range - half;
        return minY + random.nextInt(spread + 1) + random.nextInt(half + 1);
    }

    /**
     * {@code OreFeature#place} 的精确移植，返回是否真的写入了方块。
     *
     * <p>要点（全部来自 {@code mucore-26.3.jar} 的 {@code OreFeature} 字节码）：
     * <ul>
     *   <li>{@code nextFloat() * π} 只抽一次，决定整条矿团在 xz 上的方向；</li>
     *   <li>两端点用 {@code Math.sin}/{@code Math.cos}（JDK，不是 {@code Mth} 的查表版）乘
     *       {@code size / 8.0f}；</li>
     *   <li>两端点的 y 各自加一次 {@code nextInt(3) - 2} 的抖动；</li>
     *   <li>外包盒 {@code x/z: 2*(ceil(g)+r)}、{@code y: 2*(2+r)}，
     *       其中 {@code g = size/8.0f}、{@code r = ceil((size/16.0f*2+1)/2)}；</li>
     *   <li>先在外包盒的每一列问一次 {@code OCEAN_FLOOR_WG} 高度，第一列满足
     *       {@code pos.y - 2 - r <= height} 就调用一次 doPlace 并返回它的结果。</li>
     * </ul>
     */
    static boolean placeOre(NetherConfig.OreRule rule, Random random,
                            int originX, int originY, int originZ, OreRegion view) {
        int size = Math.max(1, rule.size());
        float angle = random.nextFloat() * PI;
        float spreadRadius = size / 8.0f;
        int boxRadius = ceil((size / 16.0f * 2.0f + 1.0f) / 2.0f);

        double x1 = originX + Math.sin(angle) * spreadRadius;
        double x2 = originX - Math.sin(angle) * spreadRadius;
        double z1 = originZ + Math.cos(angle) * spreadRadius;
        double z2 = originZ - Math.cos(angle) * spreadRadius;
        double y1 = originY + random.nextInt(3) - 2;
        double y2 = originY + random.nextInt(3) - 2;

        int minX = originX - ceil(spreadRadius) - boxRadius;
        int minY = originY - 2 - boxRadius;
        int minZ = originZ - ceil(spreadRadius) - boxRadius;
        int xzSize = 2 * (ceil(spreadRadius) + boxRadius);
        int ySize = 2 * (2 + boxRadius);

        for (int x = minX; x <= minX + xzSize; x++) {
            for (int z = minZ; z <= minZ + xzSize; z++) {
                if (minY <= view.oceanFloorHeight(x, z)) {
                    return doPlace(rule, random, x1, x2, z1, z2, y1, y2,
                            minX, minY, minZ, xzSize, ySize, view);
                }
            }
        }
        return false;
    }

    /** {@code OreFeature#doPlace}：球链 + 嵌球剔除 + {@code BitSet} 去重。 */
    private static boolean doPlace(NetherConfig.OreRule rule, Random random,
                                   double x1, double x2, double z1, double z2, double y1, double y2,
                                   int minX, int minY, int minZ, int xzSize, int ySize, OreRegion view) {
        int size = Math.max(1, rule.size());
        int placed = 0;
        // BitSet 的大小与下标公式都照抄：index = (x-minX) + (y-minY)*xzSize + (z-minZ)*xzSize*ySize
        BitSet written = new BitSet(xzSize * ySize * xzSize);
        double[] layers = new double[size * 4];

        for (int index = 0; index < size; index++) {
            float progress = (float) index / (float) size;
            double centerX = lerp(progress, x1, x2);
            double centerY = lerp(progress, y1, y2);
            double centerZ = lerp(progress, z1, z2);
            double roll = random.nextDouble() * size / 16.0;
            // 半径用 Mth.sin（查表）而不是 Math.sin：两者不等值，差在最后一个 ulp 上。
            double radius = (((double) (sin(PI * progress) + 1.0f)) * roll + 1.0) / 2.0;
            layers[index * 4] = centerX;
            layers[index * 4 + 1] = centerY;
            layers[index * 4 + 2] = centerZ;
            layers[index * 4 + 3] = radius;
        }

        // 嵌球剔除：一个球被另一个球完全包住时，把它的半径置 -1（内侧那个被外面的吞掉）。
        for (int outer = 0; outer < size - 1; outer++) {
            if (layers[outer * 4 + 3] <= 0.0) {
                continue;
            }
            for (int inner = outer + 1; inner < size; inner++) {
                if (layers[inner * 4 + 3] <= 0.0) {
                    continue;
                }
                double dx = layers[outer * 4] - layers[inner * 4];
                double dy = layers[outer * 4 + 1] - layers[inner * 4 + 1];
                double dz = layers[outer * 4 + 2] - layers[inner * 4 + 2];
                double dr = layers[outer * 4 + 3] - layers[inner * 4 + 3];
                if (dr * dr <= dx * dx + dy * dy + dz * dz) {
                    continue;
                }
                if (dr > 0.0) {
                    layers[inner * 4 + 3] = -1.0;
                } else {
                    layers[outer * 4 + 3] = -1.0;
                }
            }
        }

        for (int index = 0; index < size; index++) {
            double radius = layers[index * 4 + 3];
            if (radius < 0.0) {
                continue;
            }
            double centerX = layers[index * 4];
            double centerY = layers[index * 4 + 1];
            double centerZ = layers[index * 4 + 2];
            int lowX = Math.max(floor(centerX - radius), minX);
            int lowY = Math.max(floor(centerY - radius), minY);
            int lowZ = Math.max(floor(centerZ - radius), minZ);
            int highX = Math.max(floor(centerX + radius), lowX);
            int highY = Math.max(floor(centerY + radius), lowY);
            int highZ = Math.max(floor(centerZ + radius), lowZ);
            for (int x = lowX; x <= highX; x++) {
                double nx = ((double) x + 0.5 - centerX) / radius;
                double nx2 = nx * nx;
                if (nx2 >= 1.0) {
                    continue;
                }
                for (int y = lowY; y <= highY; y++) {
                    double ny = ((double) y + 0.5 - centerY) / radius;
                    double nxy2 = nx2 + ny * ny;
                    if (nxy2 >= 1.0) {
                        continue;
                    }
                    for (int z = lowZ; z <= highZ; z++) {
                        double nz = ((double) z + 0.5 - centerZ) / radius;
                        if (nxy2 + nz * nz >= 1.0) {
                            continue;
                        }
                        if (y < view.minY() || y > view.maxY()) {
                            // LevelReader#isOutsideBuildHeight：世界之外一个方块都不许写。
                            continue;
                        }
                        int bit = (x - minX) + (y - minY) * xzSize + (z - minZ) * xzSize * ySize;
                        if (written.get(bit)) {
                            continue;
                        }
                        written.set(bit);
                        if (!view.inRegion(x, y, z)) {
                            continue;
                        }
                        if (canPlaceOre(rule, random, x, y, z, view)) {
                            placed++;
                        }
                    }
                }
            }
        }
        return placed > 0;
    }

    /**
     * {@code AbstractOreFeature#canPlaceOre} 的 {@code doPlace} 循环体：按 {@code targets} 顺序逐个试。
     *
     * <p>次序是逐位重要的：{@code nextFloat} 只在 {@code 0 < discard < 1} 时消费，而且只在目标已经
     * 匹配之后消费。把顺序调换或省略 discard 都会让后续随机错位。
     */
    static boolean canPlaceOre(NetherConfig.OreRule rule, Random random,
                               int x, int y, int z, OreRegion view) {
        String blockId = view.blockId(x, y, z);
        for (int index = 0; index < rule.targets().size(); index++) {
            NetherConfig.OreTargetState target = rule.targets().get(index);
            if (!target.matchId().equals(blockId)) {
                continue;
            }
            if (!shouldSkipAirCheck(random, rule.discardChance()) && isAdjacentToAir(view, x, y, z)) {
                continue;
            }
            view.write(x, y, z, rule.placedFeatureId(), index);
            return true;
        }
        return false;
    }

    /**
     * {@code AbstractOreFeature#shouldSkipAirCheck}：{@code chance <= 0} 恒跳过检查，
     * {@code chance >= 1} 恒不跳过，中间才抽一次 {@code nextFloat} 并要求 {@code nextFloat >= chance}。
     */
    static boolean shouldSkipAirCheck(Random random, float chance) {
        if (chance <= 0.0f) {
            return true;
        }
        if (chance >= 1.0f) {
            return false;
        }
        return random.nextFloat() >= chance;
    }

    /** {@code AbstractOreFeature#isAdjacentToAir}：六向任一为空气。 */
    private static boolean isAdjacentToAir(OreRegion view, int x, int y, int z) {
        return isAirAt(view, x, y - 1, z) || isAirAt(view, x, y + 1, z)
                || isAirAt(view, x, y, z - 1) || isAirAt(view, x, y, z + 1)
                || isAirAt(view, x - 1, y, z) || isAirAt(view, x + 1, y, z);
    }

    /** 越界读会抛，所以先问再读；读不到就当作「不是空气」（宁可少丢，不可凭空丢）。 */
    private static boolean isAirAt(OreRegion view, int x, int y, int z) {
        return view.inRegion(x, y, z) && view.isAir(x, y, z);
    }

    // ---------------------------------------------------------------- Mth 的复刻

    /** {@code Mth.sin(double)}：{@code SIN[(int)(d * 10430.378350470453) & 65535]}。 */
    static float sin(double value) {
        return SIN[(int) (((long) (value * SIN_SCALE)) & SIN_MASK)];
    }

    /** {@code Mth.cos(double)}：同一个表，加四分之一周期。 */
    static float cos(double value) {
        return SIN[(int) (((long) (value * SIN_SCALE + COS_OFFSET)) & SIN_MASK)];
    }

    /** {@code Mth.ceil(float)} = {@code (int) Math.ceil((double) f)}。 */
    static int ceil(float value) {
        return (int) Math.ceil(value);
    }

    /** {@code Mth.floor(double)} = {@code (int) Math.floor(d)}。 */
    static int floor(double value) {
        return (int) Math.floor(value);
    }

    /** {@code Mth.lerp(delta, start, end)} = {@code start + delta * (end - start)}。 */
    static double lerp(double delta, double start, double end) {
        return start + delta * (end - start);
    }

    /**
     * 一个矿团可以写到哪里。
     *
     * <p>生产实现是 {@link RegionOreRegion}（唯一碰 {@link LimitedRegion} 的类），测试用它自己的
     * 内存实现，于是整套几何可以在没有服务端的情况下回归。
     */
    interface OreRegion {
        /** 世界最低的方块 y（含）。 */
        int minY();

        /** 世界最高的方块 y（含）。 */
        int maxY();

        /** 该坐标是否在可写区域内。 */
        boolean inRegion(int x, int y, int z);

        /** 该列 {@code OCEAN_FLOOR_WG} 高度图的值（{@code OreFeature#place} 用的就是这一张）。 */
        int oceanFloorHeight(int x, int z);

        /**
         * 该列 {@code MOTION_BLOCKING} 高度图的值 —— {@code wover:every_layer} 展开的上界。
         *
         * <p>与 {@link dev.mumu.betternether.worldgen.WorldView#motionBlockingHeight} 同一个语义，
         * 因为天花板层展开必须和 {@code worldgen.Layers.layers(..., top=false)} 给出同一个集合，
         * 否则同一条 {@code every_layer} 在矿和植被两边会算出不同的层。
         */
        int motionBlockingHeight(int x, int z);

        /**
         * 该坐标方块的<b>真实身份 id</b>，形如 {@code minecraft:netherrack}。
         *
         * <p>不能用 {@code Material} 顶替：CE 的 deceive 模式把自定义方块借到原版宿主上，
         * 按材质比较会把「长得像 netherrack 的自定义方块」当成 netherrack 吃掉。
         *
         * @return {@code null} = 问不出来（越界、CE 未就绪）
         */
        @Nullable
        String blockId(int x, int y, int z);

        /** 该坐标是否空气。 */
        boolean isAir(int x, int y, int z);

        /**
         * 同一个区域的 {@link WorldView} 视角，交给 {@link Layers#emit} 做层扫描。
         *
         * <p>{@code every_layer} 是唯一一步「要读一整列」的修饰符，而植被与矿必须看到**同一份**
         * terrace/tag 判定，所以这里不自己扫，直接把运行期的 {@code RegionView} 递过去。
         */
        WorldView worldView();

        /** 该坐标属于哪个群系（{@code minecraft:biome} 要比对的对象）。 */
        String biomeAt(int x, int y, int z);

        /**
         * 写一个方块。调用方必须已经用 {@link #inRegion} 判过。
         *
         * <p>参数是 placed feature id + target 下标，而不是 {@link BlockData}：单元测试的类路径上
         * 没有服务端，{@code Material#createBlockData} 在那里造不出对象。生产实现自己把
         * {@code (id, index)} 换成解析好的方块。
         */
        void write(int x, int y, int z, String placedFeatureId, int targetIndex);
    }

    /** 生产实现：{@link LimitedRegion} 的一层薄包装。 */
    private static final class RegionOreRegion implements OreRegion {
        private final LimitedRegion region;
        /** 借用 worldgen 层已经建好的「真实身份」通道，不另开一条近似判据。 */
        private final WorldView world;
        private final Map<String, List<BlockData>> ores;
        private final int minY;
        private final int maxY;
        private boolean heightWarned;
        private boolean idWarned;

        private RegionOreRegion(LimitedRegion region, BiomePlacement placement, Random random,
                                Map<String, List<BlockData>> ores, int minY, int maxY) {
            this.region = region;
            this.ores = ores;
            this.minY = minY;
            this.maxY = maxY;
            // RegionView 的 random() 本类不用（矿的流由 featureSeed 决定），但它的构造要求一个。
            this.world = new RegionView(region, placement, random, minY, maxY, null);
        }

        @Override
        public int minY() {
            return minY;
        }

        @Override
        public int maxY() {
            return maxY;
        }

        @Override
        public boolean inRegion(int x, int y, int z) {
            return region.isInRegion(x, y, z);
        }

        @Override
        public int oceanFloorHeight(int x, int z) {
            if (!region.isInRegion(x, minY, z)) {
                if (!heightWarned) {
                    heightWarned = true;
                    LOG.warning("矿物高度图读不到 (" + x + "," + z + ")：该列按「地形过低」处理，"
                            + "这条矿团不会被放置。");
                }
                return Integer.MIN_VALUE;
            }
            try {
                // 与原版 OreFeature 同一个调用：WorldGenLevel#getHeight(OCEAN_FLOOR_WG, x, z)。
                return region.getHighestBlockYAt(x, z, HeightMap.OCEAN_FLOOR_WG);
            } catch (RuntimeException | LinkageError error) {
                if (!heightWarned) {
                    heightWarned = true;
                    LOG.warning("矿物高度图读不到（" + error + "）：该列按「地形过低」处理。");
                }
                return Integer.MIN_VALUE;
            }
        }

        @Override
        public int motionBlockingHeight(int x, int z) {
            return world.motionBlockingHeight(x, z);
        }

        @Override
        public @Nullable String blockId(int x, int y, int z) {
            if (!region.isInRegion(x, y, z)) {
                return null;
            }
            String id = world.blockId(x, y, z);
            if (id == null && !idWarned) {
                idWarned = true;
                LOG.warning("矿物拿不到方块身份 id：不按宿主材质猜测，这片区域不会生成矿。");
            }
            return id;
        }

        @Override
        public boolean isAir(int x, int y, int z) {
            return world.isAir(x, y, z);
        }

        @Override
        public WorldView worldView() {
            return world;
        }

        @Override
        public String biomeAt(int x, int y, int z) {
            return world.biomeAt(x, y, z);
        }

        @Override
        public void write(int x, int y, int z, String placedFeatureId, int targetIndex) {
            List<BlockData> states = ores.get(placedFeatureId);
            if (states == null || targetIndex < 0 || targetIndex >= states.size()) {
                return;
            }
            if (y < minY || y > maxY || !region.isInRegion(x, y, z)) {
                return;
            }
            region.setBlockData(x, y, z, states.get(targetIndex));
        }
    }
}
