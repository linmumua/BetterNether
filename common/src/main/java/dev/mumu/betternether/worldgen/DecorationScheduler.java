package dev.mumu.betternether.worldgen;

import dev.mumu.betternether.biome.BiomePlacement;
import dev.mumu.betternether.biome.NetherBiomes;
import org.bukkit.block.data.BlockData;
import org.bukkit.generator.LimitedRegion;
import org.bukkit.generator.WorldInfo;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 唯一的装饰调度器：把上游「每个群系的 {@code features[step]} 数组」合并成一张
 * **全 step 有序计划**，再按原版 {@code ChunkGenerator#addVanillaDecorations} 的口径执行。
 *
 * <h2>它修的是什么</h2>
 * 旧实现有两个各自独立、又互相叠加的错误：
 * <ol>
 *   <li><b>按群系跑一份</b>：一个区块涉及两个群系时，同一条 placed feature 会被跑两遍
 *       （它的 {@code minecraft:biome} 修饰符各自放行自己那部分位置，看起来「没重复」，
 *       但随机流被消费了两次，之后所有特征整体偏移）。原版用 {@code IntSet} 收集 index，
 *       <b>同一个 step 里每条 placed feature 只能跑一次</b>。</li>
 *   <li><b>共用一条 Bukkit Random</b>：原版每条 placed feature 一条独立流
 *       （{@code setFeatureSeed(decorationSeed, featureIndex, step)}）。共用一条流意味着
 *       前面那条多抽一个数，后面全部平移。</li>
 * </ol>
 *
 * <h2>执行顺序 = 原版 {@code FeatureSorter#buildFeaturesPerStep}</h2>
 * 不是 registry 顺序、不是 per-biome 下标、也不是按 id 排序，而是一个带相邻链约束的
 * **拓扑排序**。逐行对照 {@code javap -p -c} 的 {@code FeatureSorter} 后落成四步：
 * <pre>
 * 1. 全局注册序号：按群系顺序遍历，群系内按 step 升序、step 内按数组顺序；
 *    同一个 placed feature 只拿第一个序号（computeIfAbsent）。
 * 2. 链约束：每个群系摊平后的列表上，第 i 个 → 第 i+1 个（后继作为「依赖」）。
 * 3. DFS 后序：从 (注册序号, step) 排序后的各顶点出发，先递归依赖再收自己。
 * 4. reverse，再按 step 分组 —— 分组后的**列表下标**就是种子里的 featureIndex。
 *    依据：{@code StepFeatureData#indexMapping()} 是
 *    {@code Util.createIndexIdentityLookup(features)}，即该 step 列表的下标，
 *    不是注册序号（注册序号只用于第 3 步的迭代顺序）。
 * </pre>
 *
 * <h2>执行顺序 = 原版 {@code GenerationStep.Decoration} 的 step 升序，step 内先结构后特征</h2>
 * 这是 {@code ChunkGenerator#addVanillaDecorations} 的循环体结构（mucore-26.3 字节码实录，
 * 见 {@link NmsDecorationSession} 的类注释）：外层 step 0..10，每个 step 里先放该 step 的
 * 结构 start（{@code j} 下标从 0 重数），再放该 step 的 placed feature。本移植保留这条骨架，
 * 只是把「该 step 的 placed feature」扩成两边合并的一张表：
 * <ul>
 *   <li><b>原版条目</b>（{@code minecraft:…}）：服务端注册表里就有那条 {@code PlacedFeature}，
 *       由 {@link NativeDecorationSession} 用原版 {@code FeaturePlacer#placeWithBiomeCheck} 真跑；</li>
 *   <li><b>本移植条目</b>（{@code betternether:…}）：走本项目的修饰符链 + 特征实现。</li>
 * </ul>
 * 两边的 {@code featureIndex} 都取自同一张 {@link Plan}（= 上游 {@code features[step]} 数组口径），
 * 所以 {@code setFeatureSeed(decorationSeed, index, step)} 对谁都是同一个数。
 *
 * <h2>为什么必须自己跑结构和原版特征</h2>
 * {@code shouldGenerateDecorations() = false} 会让 {@code CustomChunkGenerator} 把
 * {@code addVanillaDecorations} 整段跳过 —— 结构与特征**一起**没有。所以本调度器接手之后
 * 必须自己把结构放进区块（否则世界里只剩空气结构），也必须自己执行原版 placed feature
 * （否则原版那 414 条引用一条都没人放）。两件事都走真实 NMS 入口，不重写、不近似。
 *
 * <h2>为什么拆成几次 populate 调用</h2>
 * step 4 的巨型熔岩湖（结构）与 step 7 的矿（{@code OrePopulator}）都另有 owner、文件不归本调度器。
 * 它们只能在 {@code getDefaultPopulators} 的注册顺序里被「夹」进正确的位置，所以同一个调度器
 * 被包成几个 {@link Populator} 实例，各自执行 {@link Phase#STRUCTURES} / {@link Phase#FEATURES} /
 * {@link Phase#ALL} 的某几段 step。实例共享同一张计划，所以 step 9 的 index 不会因为被拆开而变。
 *
 * <h2>区块涉及哪些群系</h2>
 * 原版不是「采几个点」，而是读 3×3 区块里**每个 section 的群系调色板** —— 那是「这片区域里
 * 出现过的所有群系」的精确集合，天然不漏边。所以生产路径就读真实调色板
 * （{@link NativeDecorationSession#biomeKeys()}，并照原版 {@code retainAll(possibleBiomes())}）。
 * 只有拿不到真实 {@code WorldGenLevel}（单测 / 非服务端）时才退回本项目的气候采样器
 * （3×3 区块 × 每区块 4×4 个 quart 列）。**两者不取并集**：多算一个群系会让本移植自己编译的
 * 特征真的往世界里写方块（它们的群系判据是「该坐标的气候群系是否列了这条特征」，
 * 不是原版的 {@code BiomeFilter}），那是近似而不是保守。详见 {@code biomeKeys}。
 *
 * <h2>两套群系键空间：调色板是注册表键，计划是裸名</h2>
 * 内部约定（装载清单、{@link BiomePlacement#keyAt}、{@link #chunkBiomes}）用**裸名**表示本包群系
 * （{@code gloomwood}），原版群系保持带命名空间的键（{@code minecraft:nether_wastes}）；
 * 而真实调色板报的是**注册表键**（{@code betternether:gloomwood}）。所以读调色板之后、进入内部
 * 计划之前必须过一道 {@link #internalBiomeKey}，否则 {@code indicesOfBiome} 恒查不到，
 * 一个区块里一条本包特征都不会放 —— 那种世界看起来完全正常，只是植物全没了。
 * 转换只去掉本包前缀：{@code minecraft:} 与外部命名空间原样保留，NMS 注册表里的键身份不改。
 */
public final class DecorationScheduler {

    /**
     * {@code minecraft:biome} 修饰符要比对的标记串。
     *
     * <p>用 {@code \u0000} 开头是因为群系 key 不可能以它开头，
     * 所以它不会与真实群系 key 相等（见 {@link BiomeSetView}）。
     */
    private static final String DECORATED_MARKER = "\u0000decorated";

    /** 采样邻域半径（区块数）：原版 {@code ChunkPos.rangeClosed(pos, 1)}。 */
    private static final int NEIGHBOUR_CHUNKS = 1;

    /** 每区块每轴的 quart 列数（16 / 4）。 */
    private static final int QUARTS_PER_AXIS = 4;

    private final Plan plan;
    private final BiomePlacement placement;
    private final Map<String, List<CompiledFeature>> byBiome;

    public DecorationScheduler(@NotNull BiomePlacement placement,
                               @NotNull Map<String, List<CompiledFeature>> byBiome) {
        this.placement = placement;
        this.byBiome = Collections.unmodifiableMap(new LinkedHashMap<>(byBiome));
        this.plan = Plan.compile(byBiome);
    }

    /**
     * 从 jar 内清单装载装饰计划。
     *
     * <p>数据不完整时**不猜**：清单里每条 placed feature 都必须带真实 step 归属
     * （构建期从上游 {@code features[step]} 数组读出）。缺了就是空计划 + 一条 SEVERE 日志，
     * 世界照常生成但没有任何上游装饰 —— 这比「按群系近似放一遍」诚实：
     * 后者会产出与上游不同的世界，而且不会有任何日志。
     *
     * <p>不抛异常是刻意的：本方法在 {@code getDefaultPopulators} 里被调用，
     * 异常会从世界创建流程冒出去把整个世界建坏。
     *
     * <p>装载缺口（缺方块、缺模板、无法编译的 placed feature）只存在于
     * {@link WorldgenLoader#notes()} 里：不打出来，「世界里少了东西」就没有任何线索，
     * 而且这些缺口每一项都会被静默跳过。走现有 logger（服务端未起来时退回 JDK 日志），
     * 不另开出口。缺口内容由装载器按类别聚合，这里逐条转述，不逐方块刷屏。
     */
    public static DecorationScheduler load(@NotNull BiomePlacement placement) {
        try {
            WorldgenLoader loader = new WorldgenLoader();
            Map<String, List<CompiledFeature>> data = loader.load();
            // 统计（原版分工 / 显式 skip / 占位数量）走 INFO：它们不是缺口，是账本。
            // 真缺口仍在下面走 WARN —— 这条分界线不能混，否则 WARN 会被日常噪声淹没。
            if (!loader.stats().isEmpty()) {
                org.bukkit.Bukkit.getLogger().info("[BetterNether] 装饰特征：" + String.join("，", loader.stats()));
            }
            for (String note : loader.notes()) {
                WorldgenWarnings.warn("[BetterNether] 装饰特征：" + note);
            }
            return new DecorationScheduler(placement, data);
        } catch (IllegalStateException error) {
            org.bukkit.Bukkit.getLogger().severe(
                    "世界装饰计划装载失败，本世界不会放置任何上游装饰：" + error.getMessage());
            return new DecorationScheduler(placement, Map.of());
        }
    }

    /** 全 step 计划（诊断与测试用）。 */
    public Plan plan() {
        return plan;
    }

    /** 群系数量（启动日志用）。 */
    public int biomeCount() {
        return byBiome.size();
    }

    /** 某群系在给定 step 里的 placed feature 数量；未知群系返回 0。 */
    public int featureCount(String biome, int... steps) {
        List<CompiledFeature> features = byBiome.get(biome);
        if (features == null) {
            return 0;
        }
        int count = 0;
        for (CompiledFeature feature : features) {
            for (int step : steps) {
                if (feature.step() == step) {
                    count++;
                    break;
                }
            }
        }
        return count;
    }

    /** 在给定 step 里有特征的群系 key，按输入顺序。 */
    public List<String> biomesWith(int... steps) {
        List<String> keys = new ArrayList<>();
        for (Map.Entry<String, List<CompiledFeature>> entry : byBiome.entrySet()) {
            boolean found = false;
            for (CompiledFeature feature : entry.getValue()) {
                for (int step : steps) {
                    if (feature.step() == step) {
                        found = true;
                        break;
                    }
                }
                if (found) {
                    break;
                }
            }
            if (found) {
                keys.add(entry.getKey());
            }
        }
        return List.copyOf(keys);
    }

    /**
     * 跑指定 step 的整道装饰（结构相位 + 特征相位，等价于 {@link Phase#ALL}）。
     *
     * <p>调用方是 {@code UnifiedDecorationPopulator} 的几个分段实例（{@code 0..3}、{@code 4}、
     * {@code 5,6}、{@code 7}、{@code 8,9,10}）—— 它们共用同一个调度器实例，所以 index 空间是
     * 整 step 共享的，不会因为被拆成几次执行而变化。
     *
     * <p>参数是**显式的 step 列表**而不是区间，因为 step 7（{@code underground_decoration}）的
     * 特征归 {@code OrePopulator} 独占：区间写法会让这个空隙藏在算术里，列表写法让「谁跳过哪一步」
     * 在调用点上就能看见。
     *
     * @param steps {@code GenerationStep.Decoration} 的 ordinal（顺序不敏感，内部会按升序排）
     */
    public Stats decorate(@NotNull WorldInfo worldInfo, int chunkX, int chunkZ,
                          @NotNull LimitedRegion region, int... steps) {
        return decorate(worldInfo, chunkX, chunkZ, region, Phase.ALL, steps);
    }

    /**
     * 跑指定 step 的指定相位。
     *
     * <h2>为什么需要一个「相位」参数而不是再多几个 populator 类</h2>
     * 原版 {@code addVanillaDecorations} 的循环体是「外层 step，内层先结构后特征」。本移植要在
     * 同一个 step 内部再插两件外部的东西：
     * <ul>
     *   <li>step 4 的巨型熔岩湖（上游身份是**结构**，由 {@code MegaLavaLakePopulator} 手动放，
     *       它的文件不归本调度器，注册也不能丢）→ 必须落在「step 4 的结构之后、特征之前」；</li>
     *   <li>step 7 的 BN 矿（{@code OrePopulator}）→ 结构相位之后、该 step 的原版条目之后。</li>
     * </ul>
     * 所以结构相位与特征相位要能分开点名，顺序由 {@code getDefaultPopulators} 的注册顺序决定 ——
     * 那是唯一能看见「谁夹在谁中间」的地方。
     *
     * <p>把所有 step 的**结构**都放在特征之前的写法是错的：上游是「step 内先结构后特征，
     * 再进下一个 step」，把结构整段提前会让 step 4 的尖锥先于 step 1 的岩浆坑长出来。
     */
    public Stats decorate(@NotNull WorldInfo worldInfo, int chunkX, int chunkZ,
                          @NotNull LimitedRegion region, @NotNull Phase phase, int... steps) {
        return decorate(worldInfo, chunkX, chunkZ, region, phase, null, steps);
    }

    /**
     * 结构相位里「只跑注册表的一段，并为插入的虚拟条目让位」。
     *
     * <p>为什么要它：上游 {@code betternether:mega_lava_lake} 是**数据包里的结构**，所以它在
     * step 4 里拿的是**排序位置**（本项目 = 4：altars, gardens, ghast_hive, jungle_temples,
     * **mega_lava_lake**, pillars …），它之后的每个条目 {@code j} 都 +1。本移植注册不了这个结构
     * （structure type 是 wover 的），所以结构相位在它的槽位处断开：前一段跑
     * {@code [0, slot)}，外部 populator 在那个槽位上放湖，后一段跑 {@code [slot, size)} 且
     * {@code j} 整体 +1。
     */
    public record StructureWindow(int from, int toExclusive, int virtualSlot) {
    }

    /**
     * 同 {@link #decorate(WorldInfo, int, int, LimitedRegion, Phase, int...)}，但结构相位只跑
     * {@code window} 指定的那一段（{@code window == null} = 整段，保持原行为）。
     */
    public Stats decorate(@NotNull WorldInfo worldInfo, int chunkX, int chunkZ,
                          @NotNull LimitedRegion region, @NotNull Phase phase,
                          @Nullable StructureWindow window, int... steps) {
        // 会话的所有权在这里：开一次、关一次。下面那个重载只管跑，绝不 close ——
        // 双 close 会把真实会话的 currentlyGenerating 清两遍，也会把测试注入的会话意外关掉。
        NativeDecorationSession session = NativeDecorationBridge.open(region, chunkX, chunkZ);
        try {
            return decorate(worldInfo, chunkX, chunkZ, region, phase, window, session, steps);
        } finally {
            if (session != null) {
                // 原版在整道装饰末尾把 currentlyGenerating 清空（字节码 789..791）。
                session.close();
            }
        }
    }

    /**
     * 会话由调用方开好的版本：{@code session == null} 表示「没有真实 {@code WorldGenLevel}」（降级）。
     *
     * <p><b>本方法不关闭 {@code session}</b> —— 所有权在调用方。生产入口
     * {@link #decorate(WorldInfo, int, int, LimitedRegion, Phase, StructureWindow, int...)}
     * 用 try/finally 关它；包内可见是为了让单测能注入一个假会话，真正跑完整条
     * {@code decorate}（否则「调色板键 → 计划键」的转换只能靠直接调 helper 断言，
     * 那条链最容易在中间被人改断）。
     */
    Stats decorate(@NotNull WorldInfo worldInfo, int chunkX, int chunkZ,
                   @NotNull LimitedRegion region, @NotNull Phase phase,
                   @Nullable StructureWindow window, @Nullable NativeDecorationSession session,
                   int... steps) {
        return decorate(worldInfo, chunkX, chunkZ, region, phase, window, session, null, steps);
    }

    /**
     * 同上，外加「怎么造这次放置用的视图」。
     *
     * <p>为什么视图要能注入：生产视图 {@link RegionView} 的方法签名引用了 NMS 类型，
     * 链接它需要服务端 jar，而本工程的单测类路径刻意不含服务端 jar —— 于是 {@code decorate}
     * 本身在测试里根本进不去，只能测到它周边的 helper。注入点让整条链路（调色板 → 计划 →
     * 过滤 → 放置 → 写块）在没有服务端时也能被真实执行；生产入口传 {@code null}，
     * 走的就是 {@link RegionView}。
     */
    Stats decorate(@NotNull WorldInfo worldInfo, int chunkX, int chunkZ,
                   @NotNull LimitedRegion region, @NotNull Phase phase,
                   @Nullable StructureWindow window, @Nullable NativeDecorationSession session,
                   @Nullable ViewFactory viewFactory, int... steps) {
        if (steps.length == 0) {
            return Stats.EMPTY;
        }
        boolean wantsFeatures = phase.features() && !plan.isEmpty();
        if (!phase.structures() && !wantsFeatures) {
            return Stats.EMPTY;
        }
        int[] ordered = steps.clone();
        java.util.Arrays.sort(ordered);

        int structuresPlaced = 0;
        int nativePlaced = 0;
        int compiledPlaced = 0;
        int nativeMissing = 0;

        // 群系集合只在特征相位用得到：结构相位按 step×结构跑，不看群系。
        Set<String> biomes = wantsFeatures ? biomeKeys(chunkX, chunkZ, session) : Set.of();
        // 一个区块一个 decorationSeed，两条路径（原版条目 / 本移植条目）共用同一个值：
        // 有真实 WorldGenLevel 时直接取 NMS WorldgenRandom#setDecorationSeed 的返回值，
        // 没有时才退回 DecorationRandom（与之一一对应，见 DecorationRandomTest）。
        long decorationSeed = session != null
                ? session.decorationSeed()
                : DecorationRandom.decorationSeed(region.getWorld().getSeed(),
                        chunkX << 4, chunkZ << 4);
        int minY = worldInfo.getMinHeight();
        int maxY = worldInfo.getMaxHeight() - 1;
        int baseX = chunkX << 4;
        int baseZ = chunkZ << 4;

        for (int step : ordered) {
            if (step < 0) {
                continue;
            }
            // ① 结构相位：不依赖本项目的计划，只看服务端结构注册表里该 step 有哪些结构。
            if (phase.structures()) {
                if (session != null) {
                    structuresPlaced += window == null
                            ? session.placeStructures(step)
                            : session.placeStructures(step, window.from(),
                                    window.toExclusive(), window.virtualSlot());
                } else {
                    WorldgenWarnings.degrade("native-structures",
                            "拿不到真实 WorldGenLevel：已生成的结构不会放进区块（会出现空气结构）。");
                }
            }
            if (!wantsFeatures || step > plan.maxStep()) {
                continue;
            }
            // ② 特征相位：index 来自本项目的计划（与原版 FeatureSorter 同口径），
            //    原版条目与原版一样只跑一次、且都按 (deco, index, step) 播种。
            StepPlan stepPlan = plan.step(step);
            if (stepPlan.size() == 0) {
                continue;
            }
            // 原版：IntSet 收集 index → toIntArray → Arrays.sort → 逐个放。
            // TreeSet 天然去重：同一 step 里每条 placed feature 每个区块只跑一次。
            TreeSet<Integer> indices = indicesForStep(stepPlan, biomes);
            for (int index : indices) {
                CompiledFeature feature = stepPlan.order().get(index);
                if (feature.isNativePlaced()) {
                    if (!phase.natives()) {
                        continue;
                    }
                    if (session == null || !session.placeFeature(step, index, feature.id())) {
                        // 注册表里没有这条 id（已记账），或根本没有真实 WorldGenLevel。
                        nativeMissing++;
                    } else {
                        nativePlaced++;
                    }
                    continue;
                }
                if (!phase.compiled() || feature.isIndexPlaceholder()) {
                    // 空位条目另有 owner（矿 / 地形算法）：下标必须留在计划里，这里不放置。
                    continue;
                }
                DecorationRandom featureRandom = DecorationRandom.forFeature(
                        decorationSeed, index, step);
                // minecraft:biome 要比对的是「该坐标的群系是否在这一步列了这条特征」，
                // 不是「该坐标属于某一个固定群系」。用一层视图把这个集合判断喂给那次 equals。
                WorldView saw = new BiomeSetView(
                        newView(viewFactory, region, featureRandom, minY, maxY),
                        stepPlan.biomesOf(feature.id()));
                if (placeCompiled(feature, saw, baseX, minY, baseZ)) {
                    compiledPlaced++;
                }
            }
        }
        return new Stats(structuresPlaced, nativePlaced, compiledPlaced, nativeMissing);
    }

    /**
     * 造这次放置用的视图（见 {@link ViewFactory}）。
     *
     * <p>默认就是 {@link RegionView}，而且**只能在这里**惰性构造：{@code RegionView} 的签名引用
     * NMS 类型，链接它要服务端 jar；把它挪到字段/常量里会让本类在测试类路径上直接加载失败。
     */
    private WorldView newView(@Nullable ViewFactory viewFactory, @NotNull LimitedRegion region,
                              @NotNull java.util.Random random, int minY, int maxY) {
        if (viewFactory != null) {
            return viewFactory.create(region, random, minY, maxY);
        }
        return new RegionView(region, placement, random, minY, maxY, DECORATED_MARKER);
    }

    /**
     * 「怎么造视图」的口子：生产传 {@code null}（用 {@link RegionView}），单测注入同语义的实现。
     *
     * <p>参数就是 {@link RegionView} 构造所需的那几件，注入实现必须保持同样的语义
     * （{@code biomeAt} 走内部键空间、{@code inRegion} 走区域判定），否则测到的是另一条链。
     */
    @FunctionalInterface
    interface ViewFactory {
        WorldView create(@NotNull LimitedRegion region, @NotNull java.util.Random random,
                         int minY, int maxY);
    }

    /**
     * 本区块邻域涉及的群系 key：**有真实调色板就只用真实调色板**，没有（测试 / 非服务端）才退回气候采样。
     *
     * <h2>为什么不能取并集</h2>
     * 原版条目还好说 —— 它们自带原版 {@code BiomeFilter}，多尝试一个群系只会被筛掉。
     * 但**本移植编译的特征**不适用：它们的群系判据是
     * {@link BiomeSetView#biomeAt}（「这个坐标的气候群系是否列了这条特征」），
     * 于是「本区块实际不含群系 B」这件事完全挡不住 B 名的条目：只要采样点落在 B 上，
     * 那些特征就会真的往世界里写方块。而「哪些群系算在内」正是原版用**区块调色板**决定的，
     * 多添一个群系就等于多放一批原版不会放的装饰 —— 这是近似，不是保守。
     *
     * <h2>为什么还要一条兜底</h2>
     * 调色板需要真实 {@code WorldGenLevel}（{@link NativeDecorationSession#biomeKeys()}）。
     * 测试与任何非服务端起不来时没有它，此时退回本项目的气候采样器（同一邻域、
     * 3×3 区块 × 4×4 quart），这是「拿不到真实数据时唯一还能用的口径」，不是并列的第二来源。
     */
    private Set<String> biomeKeys(int chunkX, int chunkZ, NativeDecorationSession session) {
        // 一个区块会被**好几个** populator 实例依次处理（结构相位 / step 4 / 5-6 / 7 / 8-10），
        // 而调色板扫描是 O(9 区块 × section 数 × 4096)，原版一个区块只付一次。这里按
        // (chunkX, chunkZ) 缓存最后一次结果，让多次调用也只付一次。
        // 为什么缓存是安全的：群系在本区块装饰期间不会变（特征只写方块、不写群系），
        // 而线程本地缓存只服务同一个线程上连续的同一个区块 —— 不存在跨区块的陈旧值。
        long key = ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
        BiomeCache cache = biomeCache.get();
        if (cache.chunk == key) {
            return cache.keys;
        }
        Set<String> keys;
        if (session != null) {
            // 注册表键 → 内部键空间。缓存里放的也是转换后的值：缓存命中与未命中必须同口径。
            Set<String> palette = session.biomeKeys();
            keys = internalBiomeKeys(palette);
            reportBiomeKeyDrift(palette, keys);
        } else {
            keys = new LinkedHashSet<>(chunkBiomes(placement, chunkX, chunkZ));
        }
        Set<String> frozen = Set.copyOf(keys);
        cache.chunk = key;
        cache.keys = frozen;
        return frozen;
    }

    /**
     * 本包群系在**注册表**里的命名空间前缀（内部计划键是裸名，见类注释「两套群系键空间」）。
     */
    private static final String BIOME_NAMESPACE_PREFIX = NetherBiomes.NAMESPACE + ":";

    /**
     * 注册表键 → 内部计划键：**只**去掉本包前缀，{@code minecraft:} 与外部命名空间原样保留。
     *
     * <p>这一步不改任何注册表条目，只是把「查计划用的那张表」的键空间对齐。所以它必须发生在
     * 读调色板的位置（{@link #biomeKeys}），而不是改 {@code NmsDecorationSession} 报出的键 ——
     * 那里的键是 NMS 的身份，改了就只是把漂移藏起来。
     */
    static String internalBiomeKey(@NotNull String registryKey) {
        return registryKey.startsWith(BIOME_NAMESPACE_PREFIX)
                ? registryKey.substring(BIOME_NAMESPACE_PREFIX.length())
                : registryKey;
    }

    /** 调色板键集合 → 内部计划键集合（保持出现顺序）。 */
    static Set<String> internalBiomeKeys(@NotNull Set<String> registryKeys) {
        Set<String> internal = new LinkedHashSet<>(Math.max(4, registryKeys.size() * 2));
        for (String key : registryKeys) {
            if (key != null) {
                internal.add(internalBiomeKey(key));
            }
        }
        return internal;
    }

    /** 内部键空间的调色板里，**计划中不存在**的群系（诊断与测试用）。 */
    List<String> unmatchedBiomes(@NotNull Set<String> internalKeys) {
        List<String> unmatched = new ArrayList<>();
        for (String key : internalKeys) {
            if (!byBiome.containsKey(key)) {
                unmatched.add(key);
            }
        }
        return unmatched.isEmpty() ? List.of() : List.copyOf(unmatched);
    }

    /**
     * 调色板与计划对不上时记账（每类只记一次）。
     *
     * <h2>为什么不能只判「完全不相交」</h2>
     * 真实调色板里总有原版群系（上游 23 个群系并不铺满整个世界），而计划里通常没有原版群系的条目。
     * 只要计划里也恰好有一条同名键，「整体不相交」就为假 —— 于是一整片本包群系匹配失败会被
     * **一次巧合命中**盖掉，日志干净而植物全无。所以这里按「本包群系」逐条判：
     * 调色板里每个 {@code betternether:*} 都要能在计划里找到裸名，找不到就点名。
     *
     * <h2>为什么原版/外部群系不点名</h2>
     * 「这块地是原版群系，所以没有本包装饰」是正常情形，逐条点名会让告警变成每次开服都刷的噪声，
     * 真正需要看见的那条就被淹没了。只有「调色板里一个本包群系都没有、且与计划完全不相交」
     * 才值得怀疑（命名空间整片漂移），那时才记一条，并把两种可能都写出来。
     */
    void reportBiomeKeyDrift(@NotNull Set<String> registryKeys, @NotNull Set<String> internalKeys) {
        List<String> packBiomes = new ArrayList<>();
        for (String registryKey : registryKeys) {
            if (registryKey != null && registryKey.startsWith(BIOME_NAMESPACE_PREFIX)) {
                packBiomes.add(internalBiomeKey(registryKey));
            }
        }
        if (packBiomes.isEmpty()) {
            if (byBiome.isEmpty() || !Collections.disjoint(internalKeys, byBiome.keySet())) {
                return;
            }
            WorldgenWarnings.degrade("biome-key-space",
                    "本区块调色板里的群系（" + internalKeys + "）与装饰计划里的群系键（"
                            + byBiome.keySet() + "）没有任何交集，其中也没有 "
                            + NetherBiomes.NAMESPACE + ": 前缀的群系。要么这一片确实全是原版群系，"
                            + "要么群系命名空间与上游数据包不一致 —— 后者会让整片本包植被消失。");
            return;
        }
        List<String> unmatched = unmatchedBiomes(new LinkedHashSet<>(packBiomes));
        if (unmatched.isEmpty()) {
            return;
        }
        if (unmatched.size() == packBiomes.size()) {
            WorldgenWarnings.degrade("biome-key-space",
                    "本区块的 " + packBiomes.size() + " 个本包群系（" + packBiomes
                            + "）在装饰计划里一个都没有对应键：本区块不会放置任何本包特征。"
                            + "计划里的键：" + byBiome.keySet());
            return;
        }
        WorldgenWarnings.degrade("biome-key-space:partial",
                "本区块调色板里这些本包群系在装饰计划里没有对应键（它们这一趟不会被放置，"
                        + "其余命中的群系照常）：" + unmatched + "。计划里的键：" + byBiome.keySet());
    }

    /**
     * 本区块该 step 要跑的下标：调色板里每个群系（**内部键空间**）在这个 step 列到的下标取并集。
     *
     * <p>去重是语义的一部分（TreeSet）：同一条 placed feature 被两个群系列着时只能跑一次，
     * 跑两遍会让随机流被消费两次。查表键必须是 {@link #internalBiomeKey} 转换后的裸名 ——
     * 这里是「转换没做」与「植物全没了」之间唯一的那道接缝。
     */
    TreeSet<Integer> indicesForStep(@NotNull StepPlan stepPlan, @NotNull Set<String> internalBiomes) {
        TreeSet<Integer> indices = new TreeSet<>();
        for (String biome : internalBiomes) {
            for (int index : stepPlan.indicesOfBiome(biome)) {
                indices.add(index);
            }
        }
        return indices;
    }

    /**
     * 跑一条本移植条目，返回「它是否**真的写出了方块**」。
     *
     * <p>{@code placeAt} 返回成功次数：修饰符链/谓词把所有位置筛掉、或特征自己拒绝放置时是
     * {@code 0}，那一趟什么都没发生。把它一律记成「放置成功」等于让「植物没长」在日志与
     * 统计里消失 —— 所以只有 {@code > 0} 才算。
     */
    static boolean placeCompiled(@NotNull CompiledFeature feature, @NotNull WorldView world,
                                 int x, int y, int z) {
        return feature.placeAt(world, x, y, z) > 0;
    }


    /** 每线程最近一个区块的群系集合（见 {@link #biomeKeys}）。 */
    private final ThreadLocal<BiomeCache> biomeCache = ThreadLocal.withInitial(BiomeCache::new);

    private static final class BiomeCache {
        private long chunk = Long.MIN_VALUE;
        private Set<String> keys = Set.of();
    }

    /** 启动日志用：每个有内容的 step 有多少条 placed feature。 */
    public List<String> describe() {
        List<String> lines = new ArrayList<>();
        for (StepPlan step : plan.allSteps()) {
            if (step.size() > 0) {
                lines.add("step " + step.step() + ": " + step.size() + " 条 placed feature");
            }
        }
        return List.copyOf(lines);
    }

    /**
     * 一次 {@link #decorate} 的观测量。
     *
     * <p>为什么要有它：装饰这条链上「什么都没发生」有三种完全不同的原因 ——
     * 计划里这条特征不属于本区块的群系、注册表里没有这个 id、根本没有真实
     * {@code WorldGenLevel}。三者都表现为「世界里少了一批东西」，而它们要修的地方完全不同。
     * 把计数交出来，测试与启动日志才能分辨。
     *
     * @param structures        放进区块的结构 start 数（{@code StructureStart#placeInChunk}）
     * @param nativeFeatures    真实执行过的原版 placed feature 数
     * @param compiledFeatures  **放置成功的特征条目数**：`placeAt` 报告成功的条目才算一条。
     *                          注意成功 ≠ 写出方块 —— {@code wover:mark_postprocessing} 这类条目
     *                          本来就只做后处理标记然后返回 {@code true}；被谓词筛掉或特征自己
     *                          拒绝放置的则不计数（否则「植物没长」会被计数掩盖）
     * @param nativeMissing     想跑但跑不了的条目数（注册表没有该 id / 没有真实 WorldGenLevel）
     */
    public record Stats(int structures, int nativeFeatures, int compiledFeatures, int nativeMissing) {
        public static final Stats EMPTY = new Stats(0, 0, 0, 0);

        /** 是否什么都没做（诊断用）。 */
        public boolean isEmpty() {
            return structures == 0 && nativeFeatures == 0 && compiledFeatures == 0;
        }
    }

    /**
     * 一次调用跑哪一半：原版 {@code addVanillaDecorations} 的循环体是「外层 step，内层先结构后特征」，
     * 而本移植要在同一个 step 内部再夹进两个外部 owner 的东西（见
     * {@link #decorate(WorldInfo, int, int, LimitedRegion, Phase, int...)} 的注释）。
     */
    public enum Phase {
        /** 结构相位 + 特征相位（原版条目与本移植条目都放）。 */
        ALL(true, true, true),
        /** 只跑结构相位（把该 step 已生成结构放进区块）。 */
        STRUCTURES(true, false, false),
        /** 只跑特征相位。 */
        FEATURES(false, true, true),
        /** 只跑特征相位里的**原版**条目（本移植条目另有 owner 时用，例如 step 7 的矿）。 */
        NATIVE_FEATURES(false, false, true);

        private final boolean structures;
        private final boolean compiled;
        private final boolean natives;

        Phase(boolean structures, boolean compiled, boolean natives) {
            this.structures = structures;
            this.compiled = compiled;
            this.natives = natives;
        }

        /** 这个相位是否跑结构。 */
        public boolean structures() {
            return structures;
        }

        /** 这个相位是否跑本移植编译的条目。 */
        public boolean compiled() {
            return compiled;
        }

        /** 这个相位是否跑原版 placed feature。 */
        public boolean natives() {
            return natives;
        }

        /** 这个相位是否跑任何一个特征（编译的或原版的）。 */
        public boolean features() {
            return compiled || natives;
        }
    }

    // ---------------------------------------------------------------- 计划

    /** 全 step 计划：{@code steps[i]} 是 step {@code i} 的最终有序列表。 */
    public static final class Plan {
        private final List<StepPlan> steps;
        private final int maxStep;

        private Plan(List<StepPlan> steps, int maxStep) {
            this.steps = List.copyOf(steps);
            this.maxStep = maxStep;
        }

        /** step 编号 → 该 step 的计划；越界返回空计划（不是 null）。 */
        public StepPlan step(int step) {
            return step >= 0 && step < steps.size() ? steps.get(step) : StepPlan.EMPTY;
        }

        /** 所有 step（下标 0..maxStep）。 */
        public List<StepPlan> allSteps() {
            return steps;
        }

        public int maxStep() {
            return maxStep;
        }

        public boolean isEmpty() {
            for (StepPlan step : steps) {
                if (step.size() > 0) {
                    return false;
                }
            }
            return true;
        }

        /**
         * 某条 placed feature 在它那个 step 里的全局下标；不在计划里返回 {@code -1}。
         *
         * <p>给独立 owner 的 populator（例如 ore）取「与上游同源」的 featureIndex 用：
         * 拿到 {@code -1} 表示这条特征不在这张计划里，此时应当 fail loud，
         * 不要退回「自己规则表里的下标」—— 那等于换一条随机流。
         */
        public int featureIndex(@NotNull String placedFeatureId, int step) {
            return step(step).indexOf(placedFeatureId);
        }

        /**
         * 原版 {@code FeatureSorter#buildFeaturesPerStep} 的等价实现。
         *
         * @param byBiome 群系 key → 该群系的 placed feature（每条自带 step/index），
         *                顺序必须是「step 升序，step 内上游数组顺序」
         * @throws IllegalStateException 有特征缺 step 元数据，或链约束成环 ——
         *                               两种都必须报出来，静默降级等于换一条随机流
         */
        public static Plan compile(@NotNull Map<String, List<CompiledFeature>> byBiome) {
            return compileMerged(byBiome);
        }

        private static Plan compileMerged(@NotNull Map<String, List<CompiledFeature>> byBiome) {
            Map<String, Integer> registration = new LinkedHashMap<>();
            Map<String, Integer> stepOf = new LinkedHashMap<>();
            int counter = 0;
            int maxStep = 0;

            // 1) 全局注册序号 + 每个群系摊平后的节点序列（链约束的输入）
            //
            // 群系遍历顺序就是注册序号的分配顺序，而注册序号决定下面 TreeMap/TreeSet 的迭代顺序
            // （即 DFS 的根顺序），最终会改变同一个 step 内的排列 —— 也就改变了种子。
            // 上游是 datapack 注册表：条目按资源路径升序装载（RegistryDataLoader 遍历
            // ResourceManager.listResources 的有序结果），BN 的 23 个群系同属一个命名空间，
            // 所以「群系 key 升序」就是上游顺序。这里**自己排**而不是信调用方给的 Map 顺序：
            // 让清单文件的书写顺序变成一个不影响结果的东西。
            Map<String, List<Node>> chains = new TreeMap<>();
            for (Map.Entry<String, List<CompiledFeature>> entry : byBiome.entrySet()) {
                List<CompiledFeature> ordered = byStep(entry.getValue(), entry.getKey());
                List<Node> chain = new ArrayList<>(ordered.size());
                Set<String> seenInChain = new LinkedHashSet<>();
                for (CompiledFeature feature : ordered) {
                    // 同一群系同一 step 里重复列同一条，原版会形成自环（DFS 直接报 cycle）。
                    // 上游数据不会这样；这里按「只算一次」收敛，重复本身留给装载期的 supported 清单。
                    if (!seenInChain.add(feature.step() + "|" + feature.id())) {
                        continue;
                    }
                    Integer index = registration.get(feature.id());
                    if (index == null) {
                        index = counter++;
                        registration.put(feature.id(), index);
                        stepOf.put(feature.id(), feature.step());
                    } else {
                        Integer previous = stepOf.get(feature.id());
                        if (previous != null && previous != feature.step()) {
                            throw new IllegalStateException("placed feature " + feature.id()
                                    + " 在两个群系里属于不同 step（" + previous + " vs "
                                    + feature.step() + "）：上游数据不该这样，拒绝猜一个");
                        }
                    }
                    maxStep = Math.max(maxStep, feature.step());
                    chain.add(new Node(index, feature.step(), feature));
                }
                chains.put(entry.getKey(), chain);
            }

            // 2) 链约束：群系摊平列表里第 i 个 → 第 i+1 个（后继算「依赖」）
            NodeOrder order = new NodeOrder();
            Map<Node, Set<Node>> edges = new TreeMap<>(order);
            for (List<Node> chain : chains.values()) {
                for (int i = 0; i < chain.size(); i++) {
                    Set<Node> dependencies = edges.computeIfAbsent(chain.get(i),
                            key -> new TreeSet<>(order));
                    if (i < chain.size() - 1) {
                        dependencies.add(chain.get(i + 1));
                    }
                }
            }

            // 3) DFS 后序（Graph#depthFirstSearch 的等价物）
            Set<Node> visited = new TreeSet<>(order);
            Set<Node> inProgress = new TreeSet<>(order);
            List<Node> postOrder = new ArrayList<>(edges.size());
            for (Node vertex : edges.keySet()) {
                if (!inProgress.isEmpty()) {
                    throw new IllegalStateException("装饰顺序的 DFS 迭代结束时仍有未收敛顶点："
                            + inProgress);
                }
                if (!visited.contains(vertex)
                        && depthFirstSearch(edges, visited, inProgress, postOrder, vertex)) {
                    throw new IllegalStateException("placed feature 顺序出现环"
                            + "（原版 Feature order cycle），涉及顶点：" + inProgress
                            + "：上游数据或链约束有问题，拒绝猜一个顺序");
                }
            }

            // 4) reverse + 按 step 分组；分组后的下标就是种子里的 featureIndex
            Collections.reverse(postOrder);
            List<List<CompiledFeature>> grouped = new ArrayList<>(maxStep + 1);
            for (int step = 0; step <= maxStep; step++) {
                grouped.add(new ArrayList<>());
            }
            for (Node node : postOrder) {
                grouped.get(node.step()).add(node.feature());
            }

            // 每个 step 的「哪些群系列了这条」（biome 过滤要用）
            List<Map<String, Set<String>>> biomesOf = new ArrayList<>(maxStep + 1);
            for (int step = 0; step <= maxStep; step++) {
                biomesOf.add(new LinkedHashMap<>());
            }
            for (Map.Entry<String, List<Node>> entry : chains.entrySet()) {
                for (Node node : entry.getValue()) {
                    biomesOf.get(node.step())
                            .computeIfAbsent(node.feature().id(), key -> new LinkedHashSet<>())
                            .add(entry.getKey());
                }
            }

            List<StepPlan> steps = new ArrayList<>(maxStep + 1);
            for (int step = 0; step <= maxStep; step++) {
                steps.add(new StepPlan(step, grouped.get(step), biomesOf.get(step)));
            }
            return new Plan(steps, maxStep);
        }

        /** 群系内按 step 升序排好；同 step 内保持上游 {@code features[step]} 的原始顺序。 */
        private static List<CompiledFeature> byStep(List<CompiledFeature> features, String biome) {
            List<CompiledFeature> copy = new ArrayList<>(features);
            for (CompiledFeature feature : copy) {
                if (!feature.hasStep()) {
                    throw new IllegalStateException("群系 " + biome + " 的 placed feature "
                            + feature.id() + " 没有 step 归属（CompiledFeature.step == "
                            + CompiledFeature.UNKNOWN_STEP + "）。"
                            + "step 直接参与种子（setFeatureSeed 里 10000L*step），"
                            + "猜一个等于换一条随机流：构建期清单必须按 "
                            + "biomes[biome][step] = [placed feature id…] 输出真实下标。");
                }
            }
            // 稳定排序（List.sort 是稳定排序）：上游数组顺序是 index，不能被排序打乱
            copy.sort(java.util.Comparator.comparingInt(CompiledFeature::step));
            return copy;
        }

        /** {@code Graph#depthFirstSearch}：返回 {@code true} 表示发现环。 */
        private static boolean depthFirstSearch(Map<Node, Set<Node>> graph, Set<Node> visited,
                                                Set<Node> inProgress, List<Node> postOrder,
                                                Node vertex) {
            if (visited.contains(vertex)) {
                return false;
            }
            if (inProgress.contains(vertex)) {
                return true;
            }
            inProgress.add(vertex);
            for (Node dependency : graph.getOrDefault(vertex, Set.of())) {
                if (depthFirstSearch(graph, visited, inProgress, postOrder, dependency)) {
                    return true;
                }
            }
            inProgress.remove(vertex);
            visited.add(vertex);
            postOrder.add(vertex);
            return false;
        }
    }

    /** 一个 step 的最终有序计划。 */
    public static final class StepPlan {
        static final StepPlan EMPTY = new StepPlan(-1, List.of(), Map.of());

        private final int step;
        private final List<CompiledFeature> order;
        private final Map<String, Integer> indexById;
        private final Map<String, Set<String>> biomesById;
        private final Map<String, int[]> indicesByBiome;

        private StepPlan(int step, List<CompiledFeature> order,
                         Map<String, Set<String>> biomesById) {
            this.step = step;
            this.order = List.copyOf(order);
            Map<String, Integer> indices = new LinkedHashMap<>();
            for (int i = 0; i < order.size(); i++) {
                indices.put(order.get(i).id(), i);
            }
            this.indexById = Collections.unmodifiableMap(indices);

            Map<String, Set<String>> biomes = new LinkedHashMap<>();
            for (Map.Entry<String, Set<String>> entry : biomesById.entrySet()) {
                biomes.put(entry.getKey(), Collections.unmodifiableSet(entry.getValue()));
            }
            this.biomesById = Collections.unmodifiableMap(biomes);

            // 反查表：群系 → 本 step 里它列到的下标（升序）。每个区块都会查，先算好。
            Map<String, List<Integer>> perBiome = new LinkedHashMap<>();
            for (int i = 0; i < this.order.size(); i++) {
                for (String biome : biomesOf(this.order.get(i).id())) {
                    perBiome.computeIfAbsent(biome, key -> new ArrayList<>()).add(i);
                }
            }
            Map<String, int[]> arrays = new LinkedHashMap<>();
            for (Map.Entry<String, List<Integer>> entry : perBiome.entrySet()) {
                int[] values = new int[entry.getValue().size()];
                for (int i = 0; i < values.length; i++) {
                    values[i] = entry.getValue().get(i);
                }
                arrays.put(entry.getKey(), values);
            }
            this.indicesByBiome = Collections.unmodifiableMap(arrays);
        }

        public int step() {
            return step;
        }

        /** 最终有序列表；**下标即种子里的 featureIndex**。 */
        public List<CompiledFeature> order() {
            return order;
        }

        public int size() {
            return order.size();
        }

        public int indexOf(String placedFeatureId) {
            Integer index = indexById.get(placedFeatureId);
            return index == null ? -1 : index;
        }

        /** 在该 step 的 {@code features} 数组里列了这条 placed feature 的群系。 */
        public Set<String> biomesOf(String placedFeatureId) {
            Set<String> biomes = biomesById.get(placedFeatureId);
            return biomes == null ? Set.of() : biomes;
        }

        /** 该群系在这个 step 里列到的下标（升序，空数组表示没有）。 */
        public int[] indicesOfBiome(String biome) {
            int[] values = indicesByBiome.get(biome);
            return values == null ? EMPTY_INDICES : values;
        }

        private static final int[] EMPTY_INDICES = new int[0];
    }

    // ---------------------------------------------------------------- 执行

    // 执行入口见 {@link #decorate}：一个调度器被两个 populator 按 step 区间共享。

    // ---------------------------------------------------------------- 群系采样

    /**
     * 这个区块邻域里出现过的群系（3×3 区块 × 每区块 4×4 个 quart 列）。
     *
     * <p>与旧实现的差别：旧版只在**本区块内**取 3×3 个点，邻区块的群系完全看不到 ——
     * 跨区块边界的植被（树、尖锥）因此整片缺失。原版读的是 3×3 区块的群系调色板，
     * 这里用同一邻域的完整 quart 网格逼近它。
     */
    public static List<String> chunkBiomes(BiomePlacement placement, int chunkX, int chunkZ) {
        Set<String> keys = new LinkedHashSet<>();
        int minChunkX = chunkX - NEIGHBOUR_CHUNKS;
        int minChunkZ = chunkZ - NEIGHBOUR_CHUNKS;
        int side = NEIGHBOUR_CHUNKS * 2 + 1;
        for (int cx = 0; cx < side; cx++) {
            for (int cz = 0; cz < side; cz++) {
                int baseX = (minChunkX + cx) << 4;
                int baseZ = (minChunkZ + cz) << 4;
                for (int qx = 0; qx < QUARTS_PER_AXIS; qx++) {
                    for (int qz = 0; qz < QUARTS_PER_AXIS; qz++) {
                        keys.add(placement.keyAt(baseX + (qx << 2), baseZ + (qz << 2)));
                    }
                }
            }
        }
        return List.copyOf(keys);
    }

    // ---------------------------------------------------------------- 视图

    /**
     * 把「这条 placed feature 被哪些群系列过」编码成 {@code minecraft:biome} 能比较的形式。
     *
     * <p>{@link PlacementModifier.Biome} 的实现是
     * 「{@code decoratedBiome().equals(biomeAt(pos))}」。原版语义是
     * 「{@code pos} 的群系的生成设置里**是否列了这条 placed feature**」——
     * 一个集合判断，不是字符串相等。这里让 {@code biomeAt} 在「真实群系属于集合」时返回标记串，
     * 否则返回真实群系，于是那一次 {@code equals} 恰好就是集合成员判断，
     * 而 {@code PlacementModifier} 与 {@code RegionView} 都不需要改。
     *
     * <p>被装饰的「真实群系」来自 {@link WorldView#biomeAt}，生产路径就是 {@code RegionView} 的
     * {@code placement.keyAt} —— 与计划同属**内部键空间**（本包裸名 / {@code minecraft:}）。
     * 所以这里的 {@code allowed} 直接用计划里的键即可：调色板那道转换与这层过滤的唯一关系是
     * 「两边停在同一个键空间」，缺任何一边都会让 {@code minecraft:biome} 把位置全判掉。
     *
     * <h2>这是「包一层」而不是「换一个世界」：其余每一问都必须原样转发</h2>
     * {@link WorldView} 的默认实现是**降级**（返回 {@code null} 或记一条 {@code degraded}），
     * 所以漏转发任何一条，都等于在装饰期把那个能力悄悄关掉，而且**只对本移植条目生效** ——
     * 同一个世界里原版条目没事、本移植条目少东西，这是最难从现象反推的一类差异。
     * 具体代价（每一条都对应上游真实调用点）：
     * <ul>
     *   <li>{@link WorldView#markForPostProcessing}：漏了则幽匿脉写完方块后相邻面不重算、
     *       {@code wover:mark_postprocessing} 变成空操作；</li>
     *   <li>{@link WorldView#fullShape}：漏了 {@code wover:full_shape} 全 {@code null}，
     *       {@code is_basin} 判据整体失效（岩浆会被灌到台阶围出来的坑里）；</li>
     *   <li>{@link WorldView#netherCityWithin}：漏了避城判定拿不到答案，城市脚下会照常长植被；</li>
     *   <li>流体/贴面/属性/水位/原生 level：同上，全部只对本移植条目降级。</li>
     * </ul>
     * 所以这里除了 {@link #biomeAt} / {@link #decoratedBiome} 这两条**故意改写**的以外，
     * 一律直接转发，不留默认实现。
     */
    static final class BiomeSetView implements WorldView {
        private final WorldView delegate;
        private final Set<String> allowed;

        BiomeSetView(WorldView delegate, Set<String> allowed) {
            this.delegate = delegate;
            this.allowed = allowed;
        }

        @Override
        public String biomeAt(int x, int y, int z) {
            String real = delegate.biomeAt(x, y, z);
            return real != null && allowed.contains(real) ? DECORATED_MARKER : real;
        }

        @Override
        public String decoratedBiome() {
            return DECORATED_MARKER;
        }

        @Override
        public boolean inRegion(int x, int y, int z) {
            return delegate.inRegion(x, y, z);
        }

        @Override
        public int minY() {
            return delegate.minY();
        }

        @Override
        public int maxY() {
            return delegate.maxY();
        }

        @Override
        public boolean isAir(int x, int y, int z) {
            return delegate.isAir(x, y, z);
        }

        @Override
        public BlockData blockAt(int x, int y, int z) {
            return delegate.blockAt(x, y, z);
        }

        @Override
        public void setBlock(int x, int y, int z, BlockData data) {
            delegate.setBlock(x, y, z, data);
        }

        @Override
        public java.util.Random random() {
            return delegate.random();
        }

        @Override
        public double noise(String noiseId, double x, double y, double z) {
            return delegate.noise(noiseId, x, y, z);
        }

        @Override
        public int motionBlockingHeight(int x, int z) {
            return delegate.motionBlockingHeight(x, z);
        }

        @Override
        public String blockId(int x, int y, int z) {
            return delegate.blockId(x, y, z);
        }

        @Override
        public Boolean replaceable(int x, int y, int z) {
            return delegate.replaceable(x, y, z);
        }

        @Override
        public Boolean wouldSurvive(BlockData data, int x, int y, int z) {
            return delegate.wouldSurvive(data, x, y, z);
        }

        @Override
        public Boolean inTag(String tag, int x, int y, int z) {
            return delegate.inTag(tag, x, y, z);
        }

        @Override
        public String fluidAt(int x, int y, int z) {
            return delegate.fluidAt(x, y, z);
        }

        @Override
        public Boolean liquidAt(int x, int y, int z) {
            return delegate.liquidAt(x, y, z);
        }

        @Override
        public Boolean faceSturdy(int x, int y, int z, Point.Direction face) {
            return delegate.faceSturdy(x, y, z, face);
        }

        @Override
        public Boolean faceFullCollision(int x, int y, int z, Point.Direction face) {
            return delegate.faceFullCollision(x, y, z, face);
        }

        @Override
        public String propertyAt(String property, int x, int y, int z) {
            return delegate.propertyAt(property, x, y, z);
        }

        @Override
        public Object nativeWorldGenLevel() {
            return delegate.nativeWorldGenLevel();
        }

        @Override
        public Boolean waterSource(int x, int y, int z) {
            return delegate.waterSource(x, y, z);
        }

        @Override
        public void scheduleFluidTick(String fluidId, int x, int y, int z, int delay) {
            delegate.scheduleFluidTick(fluidId, x, y, z, delay);
        }

        @Override
        public void scheduleFluidTick(int x, int y, int z) {
            delegate.scheduleFluidTick(x, y, z);
        }

        @Override
        public void markForPostProcessing(int x, int y, int z) {
            delegate.markForPostProcessing(x, y, z);
        }

        @Override
        public Boolean fullShape(int x, int y, int z) {
            return delegate.fullShape(x, y, z);
        }

        @Override
        public Boolean netherCityWithin(int x, int z, int reach, boolean includeCave) {
            return delegate.netherCityWithin(x, z, reach, includeCave);
        }
    }

    // ---------------------------------------------------------------- 内部类型

    /** FeatureSorter 的顶点：注册序号 + step + 特征。比较顺序就是 TreeMap/TreeSet 的迭代顺序。 */
    private record Node(int registration, int step, CompiledFeature feature) {
    }

    /** {@code Comparator.comparingInt(index).thenComparingInt(step)}。 */
    private static final class NodeOrder implements java.util.Comparator<Node> {
        @Override
        public int compare(Node left, Node right) {
            int byRegistration = Integer.compare(left.registration(), right.registration());
            return byRegistration != 0
                    ? byRegistration
                    : Integer.compare(left.step(), right.step());
        }
    }
}
