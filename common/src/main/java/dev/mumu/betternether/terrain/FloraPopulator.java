package dev.mumu.betternether.terrain;

import dev.mumu.betternether.biome.BiomePlacement;
import dev.mumu.betternether.worldgen.CompiledFeature;
import dev.mumu.betternether.worldgen.DecorationScheduler;
import dev.mumu.betternether.worldgen.WorldgenLoader;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.LimitedRegion;
import org.bukkit.generator.WorldInfo;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * 植被装饰的 populator 外壳：**它自己不排任何顺序、不抽任何随机数**，只声明「植被占哪些 step」
 * 然后把整段执行交给 {@link DecorationScheduler}。
 *
 * <h2>为什么这里只剩这么点代码</h2>
 * 上一版按群系遍历自己那份清单，并用 Bukkit 传进来的**同一条** {@code Random} 跑所有
 * placed feature。原版是「一个 step 里每条 placed feature 只跑一次 + 每条一条独立流
 * （{@code setFeatureSeed(decorationSeed, featureIndex, step)}）」，两处都不是风格差异，
 * 而是「同 seed 同世界」这条性质的基础。所以排序与种子只有一处实现。
 *
 * <h2>它占哪几步</h2>
 * {@code 5}、{@code 6}、{@code 8}、{@code 9 vegetal_decoration}、{@code 10}。
 *
 * <p>{@code 7 underground_decoration} **故意不在列表里**：那一步归 {@code OrePopulator}
 * 独占（上游 {@code features[7]} 是矿脉）。区间写法会把「跳过哪一步」藏进算术，
 * 所以 {@link DecorationScheduler#decorate} 收的是显式 step 列表。
 *
 * <h2>配置语义</h2>
 * 早先 {@code flora.per-chunk} 在这里当开关用（{@code 0} 就整段不跑），另有一个
 * {@code flora.ceiling} 会把 {@code wover:every_layer(top:false)} 的天花板分支整段裁掉。
 * 两个都是本移植自造的旋钮：上游没有「关掉某一步」「关掉天花板植被」的概念，
 * 用它们裁掉上游装饰等于替上游做决定。{@code flora.ceiling} 已彻底删除，
 * {@code flora.per-chunk} 只在 {@link DecorationScheduler} 那一层当总开关看。
 */
public final class FloraPopulator extends BlockPopulator {

    /**
     * 植被装饰覆盖的全部 step。
     *
     * <p>step 7 缺失是**有意的**（矿归 {@code OrePopulator}）。
     */
    static final int[] STEPS = {5, 6, 8, 9, 10};

    /**
     * step 7 之前那几步：{@code 5}、{@code 6}。
     *
     * <p>必须单独注册在 {@code OrePopulator} **之前**：执行顺序是 step 升序，
     * 把 {@code {5,6,8,9,10}} 整包排在 ore(7) 之后，等于让 step 5/6 晚于 step 7 执行 ——
     * 只要 5/6 里有内容，顺序就是错的（上游是「一个 step 全员跑完再进下一个」）。
     */
    static final int[] BEFORE_ORE_STEP = {5, 6};

    /** step 7 之后那几步：{@code 8}、{@code 9}、{@code 10}。 */
    static final int[] AFTER_ORE_STEP = {8, 9, 10};

    /** {@code OrePopulator} 独占的 step（{@code underground_decoration}，上游 {@code features[7]} 是矿）。 */
    static final int ORE_STEP = 7;

    private final int[] steps;
    private final DecorationScheduler scheduler;

    /** 执行全部植被 step（{@code 5,6,8,9,10}）—— 只在不需要把 ore 夹进中间时使用。 */
    public FloraPopulator(@NotNull DecorationScheduler scheduler) {
        this(scheduler, STEPS);
    }

    /** 只执行给定的 step —— 用来把 ore(step 7) 夹进 step 序里。 */
    FloraPopulator(@NotNull DecorationScheduler scheduler, int[] steps) {
        this.scheduler = scheduler;
        this.steps = steps.clone();
    }

    @Override
    public void populate(@NotNull WorldInfo worldInfo, @NotNull java.util.Random random,
                         int chunkX, int chunkZ, @NotNull LimitedRegion region) {
        // 传进来的 random 不再使用：每条 placed feature 有自己的流（见 DecorationRandom）。
        scheduler.decorate(worldInfo, chunkX, chunkZ, region, steps);
    }

    /**
     * 这个区块邻域涉及的群系 key，按首次出现顺序。
     *
     * <p>只是 {@link DecorationScheduler#chunkBiomes} 的转发，保留在这里是因为它原本就是
     * 本类的公开职责，且测试直接调它。
     */
    static List<String> biomesOfChunk(BiomePlacement placement, int chunkX, int chunkZ) {
        return DecorationScheduler.chunkBiomes(placement, chunkX, chunkZ);
    }

    /** 装载期的诊断（缺方块、缺模板、无法编译的特征），由插件入口打一次日志。 */
    public static List<String> loadNotes() {
        WorldgenLoader loader = new WorldgenLoader();
        loader.load();
        return loader.notes();
    }

    /** 已编译数据的群系数量（启动日志与测试）。 */
    public int compiledBiomeCount() {
        return scheduler.biomeCount();
    }

    /** 某个群系已编译的植被 placed feature 数量；未知群系返回 0。 */
    public int featureCount(String biome) {
        return scheduler.featureCount(biome, STEPS);
    }

    /** 已编译数据里有植被的群系 key。 */
    public List<String> biomesWithFlora() {
        return scheduler.biomesWith(STEPS);
    }

    /** 计划摘要（启动日志）。 */
    public List<String> describePlan() {
        return scheduler.describe();
    }

    /** 测试用：直接注入已编译数据，绕开 jar 资源与 CraftEngine。 */
    static FloraPopulator of(BiomePlacement placement,
                             java.util.Map<String, List<CompiledFeature>> data) {
        return new FloraPopulator(new DecorationScheduler(placement, data));
    }
}
