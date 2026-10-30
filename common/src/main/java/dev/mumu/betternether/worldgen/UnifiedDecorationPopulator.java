package dev.mumu.betternether.worldgen;

import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.LimitedRegion;
import org.bukkit.generator.WorldInfo;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 唯一的装饰 populator 外壳：**它自己不排顺序、不抽随机数、不判群系**，
 * 只声明「我负责哪些 step 的哪个相位」，然后把执行交给 {@link DecorationScheduler}。
 *
 * <h2>为什么只有一个类、却注册多个实例</h2>
 * 原版 {@code ChunkGenerator#addVanillaDecorations} 是一个循环：step 0..10 升序，
 * 每个 step 内**先结构后特征**（字节码：{@code 237} 取
 * {@code StructureManager.shouldGenerateStructures()}，{@code 244..407} 是 per-structure 循环，
 * {@code 413} 之后才是该 step 的 placed feature）。本移植必须在这条骨架上夹进两个外部 owner：
 * <ul>
 *   <li>{@code MegaLavaLakePopulator} —— 上游身份是 step 4 的**结构**
 *       （{@code NetherStructures.java:107-108} {@code .step(SURFACE_STRUCTURES)}），
 *       必须在「step 4 的结构之后、step 4 的特征之前」；</li>
 *   <li>{@code OrePopulator} —— step 7 的 BN 矿，必须在「step 7 的结构之后」。</li>
 * </ul>
 * 两个类的文件都不归本调度器，也不能从注册表里丢掉。所以「顺序」只能体现在
 * {@code getDefaultPopulators} 返回列表的次序上，而能表达「只跑某半步」的最小单元就是本类：
 * {@link DecorationScheduler.Phase#STRUCTURES} / {@link DecorationScheduler.Phase#FEATURES} /
 * {@link DecorationScheduler.Phase#NATIVE_FEATURES} / {@link DecorationScheduler.Phase#ALL}。
 *
 * <h2>注册顺序（= 原版 step 升序，step 内先结构后特征）</h2>
 * <pre>
 * ALL            0,1,2,3     step 0..3 的结构 + 全部特征
 * STRUCTURES     4           step 4 的结构（堡垒/化石/传送门废墟这类）
 * MegaLavaLakePopulator      BN 的 step 4 结构（手动放置，另一 owner）
 * FEATURES       4           step 4 的特征（尖锥、石笋、骨堆…）
 * ALL            5,6
 * STRUCTURES     7           step 7 的结构
 * NATIVE_FEATURES 7          step 7 的**原版**条目（矿那批的原版前缀）
 * OrePopulator               step 7 的 BN 矿（另一 owner）
 * ALL            8,9,10
 * </pre>
 * 早期实现把整道原版装饰放在最前面（{@code addVanillaDecorations} 先跑完），BN 再补一遍 ——
 * 那样 step 1 的岩浆坑会晚于 step 4 的尖锥，也与「step 内先结构后特征」不符；本类就是为了让这条
 * 顺序能按 step 逐段表达而存在。
 *
 * <h2>中间那两段不是注册表结构，它们的种子由谁给</h2>
 * {@code MegaLavaLakePopulator} 与 {@code OrePopulator} 的东西**不在**原版结构注册表里
 * （lake 的 structure type 是 wover 的，26.3 注册不了；BN 矿是 placed feature），所以
 * {@code StructureManager} 永远不会替它们调 {@code setFeatureSeed(deco, j, step)}。
 * 它们必须自己按同一口径播种，取数入口是 {@link NativeDecorationSession}：
 * <ul>
 *   <li>{@link NativeDecorationSession#decorationSeed()} —— 本区块的 decorationSeed（与特征侧同源）；</li>
 *   <li>{@link NativeDecorationSession#nextStructureIndex(int)} —— 该 step 的注册表结构条目数。
 *       对熔岩湖来说这**不是**权宜数：wover 把 code 条目追加在数据包条目之后
 *       （{@code ResourceManagerRegistryLoadTaskMixin#wover_bootstrap}），所以上游 step 4 的最后
 *       一个条目就是它，{@code j} 正是这个条目数（细节与「整数随数据包集合变」的差异见该方法注释）。</li>
 * </ul>
 */
public final class UnifiedDecorationPopulator extends BlockPopulator {

    private final DecorationScheduler scheduler;
    private final DecorationScheduler.Phase phase;
    private final int[] steps;
    /** 结构相位只跑注册表的一段（{@code null} = 整段）。见 {@link DecorationScheduler.StructureWindow}。 */
    private final DecorationScheduler.StructureWindow window;

    public UnifiedDecorationPopulator(@NotNull DecorationScheduler scheduler,
                                      @NotNull DecorationScheduler.Phase phase, int... steps) {
        this(scheduler, phase, null, steps);
    }

    /**
     * @param window 结构相位的窗口；给「上游是数据包结构、本移植注册不了」的条目（巨型熔岩湖）
     *               让出它的槽位用 —— 前一段跑 {@code [0, slot)}，那个 owner 的 populator 放在
     *               两者之间，后一段跑 {@code [slot, size)} 且 {@code j} 整体 +1
     */
    public UnifiedDecorationPopulator(@NotNull DecorationScheduler scheduler,
                                      @NotNull DecorationScheduler.Phase phase,
                                      @Nullable DecorationScheduler.StructureWindow window,
                                      int... steps) {
        this.scheduler = scheduler;
        this.phase = phase;
        this.window = window;
        this.steps = steps.clone();
    }

    @Override
    public void populate(@NotNull WorldInfo worldInfo, @NotNull java.util.Random random,
                         int chunkX, int chunkZ, @NotNull LimitedRegion region) {
        // Bukkit 传进来的 random 不用：每条 placed feature 有自己的流
        // （原版 setFeatureSeed(decorationSeed, featureIndex, step)），结构与原版特征也一样。
        // 用它会把「一条流跑完全部特征」那个已修掉的错误重新引回来。
        scheduler.decorate(worldInfo, chunkX, chunkZ, region, phase, window, steps);
    }

    /** 本实例负责的相位（启动日志与测试用）。 */
    public DecorationScheduler.Phase phase() {
        return phase;
    }

    /** 本实例负责的 step（升序副本）。 */
    public int[] steps() {
        return steps.clone();
    }

    /** 计划摘要（启动日志）。 */
    public List<String> describePlan() {
        return scheduler.describe();
    }
}
