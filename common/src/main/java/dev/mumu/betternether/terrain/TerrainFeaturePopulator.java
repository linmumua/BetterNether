package dev.mumu.betternether.terrain;

import dev.mumu.betternether.biome.BiomePlacement;
import dev.mumu.betternether.worldgen.CompiledFeature;
import dev.mumu.betternether.worldgen.DecorationScheduler;
import dev.mumu.betternether.worldgen.UpstreamTerrainFeatures;
import dev.mumu.betternether.worldgen.WorldgenLoader;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.LimitedRegion;
import org.bukkit.generator.WorldInfo;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/**
 * 地形装饰的 populator 外壳：**它自己不排任何顺序、不抽任何随机数**，只声明「地形占哪些 step」
 * 然后把整段执行交给 {@link DecorationScheduler}。
 *
 * <h2>为什么这里只剩这么点代码</h2>
 * 上一版这个类按群系遍历自己那份 terrain 清单，用 Bukkit 传进来的**同一条** {@code Random}
 * 跑所有 placed feature。两个问题都不是「实现不完整」，而是语义错的：
 * <ul>
 *   <li>同一条特征在多个群系里各跑一遍 —— 原版一个 step 里每条只跑一次；</li>
 *   <li>一条流跑完全部特征 —— 原版每条特征一条独立流
 *       （{@code setFeatureSeed(decorationSeed, featureIndex, step)}）。</li>
 * </ul>
 * 这两点决定了「同样的 seed 长出同样的世界」，所以排序与种子必须只有一处实现，
 * 也就是 {@link DecorationScheduler}。本类只保留**归属声明**与**诊断**。
 *
 * <h2>它占哪几步</h2>
 * {@code 0 raw_generation}（{@code replace_soul_sandstone}）、{@code 1 lakes}（熔岩坑/岩浆斑/
 * 幽匿晶簇）、{@code 4 surface_structures}（尖锥、石笋、骨堆、黑曜石晶簇、贴面补丁）。
 *
 * <p>step 4 而不是 7 有数据依据：上游
 * {@code src/main/generated/data/betternether/worldgen/biome/*.json} 的
 * {@code features[4]} 里就是这批 {@code betternether:} 条目；而 {@code GenerationStep$Decoration}
 * 的 ordinal 4 是 {@code SURFACE_STRUCTURES}。step 直接进种子（{@code 10000L * step}），
 * 按语义名字猜成 {@code underground_decoration}(7) 会让整条随机流错开。
 *
 * <p>矿（{@code features[7]}，{@code underground_decoration}）不在这里：
 * 它们由 {@code OrePopulator} 独占，两边都放就会翻倍。
 */
public final class TerrainFeaturePopulator extends BlockPopulator {

    /** 地形装饰覆盖的全部 step：0 raw_generation、1 lakes、2、3、4 surface_structures。 */
    static final int[] STEPS = {0, 1, 2, 3, 4};

    /**
     * step 4 之前那几步：{@code 0}～{@code 3}。
     *
     * <p>分出来是因为 step 4 里要在**特征之前**插一个结构（巨型熔岩湖）。
     */
    static final int[] BEFORE_SURFACE_STRUCTURES = {0, 1, 2, 3};

    /**
     * step 4 的 placed feature 单独一段。
     *
     * <h3>为什么必须和 {@link #BEFORE_SURFACE_STRUCTURES} 拆开注册</h3>
     * 原版 {@code ChunkGenerator#addVanillaDecorations} 是「外层 step，内层先结构后特征」：
     * 字节码里 {@code 237} 取 {@code StructureManager.shouldGenerateStructures()}，
     * {@code 269..407} 是 per-structure 循环（各自 {@code setFeatureSeed(deco, j, step)}），
     * {@code 413} 之后才开始收集并放置该 step 的 placed feature。
     *
     * <p>上游的巨型熔岩湖是**结构**而不是 placed feature：
     * {@code upstream/BetterNether/.../registry/NetherStructures.java:107-108}
     * {@code .structure(id("mega_lava_lake"), MegaLavaLakeStructure::new).step(SURFACE_STRUCTURES)}。
     * 所以它必须排在 step 4 的尖锥那批**之前**，而不是之后 —— 否则「湖」会覆盖掉先长出来的尖锥。
     */
    static final int[] SURFACE_STRUCTURE_STEP = {4};

    /** 本实例执行的 step（上面的三段是它的取值）。 */
    private final int[] steps;
    private final DecorationScheduler scheduler;

    /** 执行 step 4 的全部地形特征（{@code 0,1,2,3,4}）。 */
    public TerrainFeaturePopulator(@NotNull DecorationScheduler scheduler) {
        this(scheduler, STEPS);
    }

    /** 只执行给定的 step —— 用来把结构插进 step 4 内部。 */
    TerrainFeaturePopulator(@NotNull DecorationScheduler scheduler, int[] steps) {
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
     * 装载期的诊断（缺方块、缺模板、无法编译的特征），由插件入口打一次日志。
     *
     * <p>含 {@code UpstreamTerrainFeatures.loadNotes()}：地形那几种算法特征（pillar 的属性变体、
     * 晶体的调色板、condition 的 filter）解析不到时，原因只能从那里看到。
     */
    public static List<String> loadNotes() {
        WorldgenLoader loader = new WorldgenLoader();
        loader.load();
        List<String> notes = new ArrayList<>(loader.notes());
        notes.addAll(UpstreamTerrainFeatures.loadNotes());
        return List.copyOf(notes);
    }

    /** 已编译数据的群系数量（启动日志与测试）。 */
    public int compiledBiomeCount() {
        return scheduler.biomeCount();
    }

    /** 某个群系已编译的地形 placed feature 数量；未知群系返回 0。 */
    public int featureCount(String biome) {
        return scheduler.featureCount(biome, STEPS);
    }

    /** 已编译数据里有地形特征的群系 key。 */
    public List<String> biomesWithTerrain() {
        return scheduler.biomesWith(STEPS);
    }

    /** 计划摘要（启动日志）。 */
    public List<String> describePlan() {
        return scheduler.describe();
    }

    /** 测试用：直接注入已编译数据，绕开 jar 资源与 CraftEngine。 */
    static TerrainFeaturePopulator of(BiomePlacement placement,
                                      java.util.Map<String, List<CompiledFeature>> data) {
        return new TerrainFeaturePopulator(new DecorationScheduler(placement, data));
    }
}
