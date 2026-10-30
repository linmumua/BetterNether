package dev.mumu.betternether.biome;

import org.bukkit.block.Biome;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.WorldInfo;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 用 {@link BiomePlacement} 画出 28 个下界群系（原版 5 + BetterNether 23）。
 *
 * <h2>为什么构造是惰性的</h2>
 * {@link BiomePlacement} 需要一个原版气候采样器，而采样器要走到
 * {@code ServerLevel#getChunkSource()#randomState()}。世界注册流程里
 * {@code getDefaultBiomeProvider(WorldInfo)} 属于"创建 level stem 的输入"，此时
 * {@code ServerLevel} / {@code ServerChunkCache} / {@code RandomState} 都还没构造完，而且
 * {@code WorldInfo} 也可能只是一个只覆盖 seed/min/max 的快照而不是 {@code World} 本体。
 *
 * <p>所以本类接受一个 {@link Function}{@code <WorldInfo, BiomePlacement>}，<b>只有第一次真正被问
 * 群系时</b>才把它解析出来；解析失败（世界还没就绪）不缓存，下一次调用会重试。这样
 * {@code getDefaultBiomeProvider} 就是纯构造，不会在世界创建阶段抛异常把整个世界建坏。
 *
 * <p>解析用的是 {@code getBiome} <b>当次收到的那个</b> {@link WorldInfo}，不是构造时捕获的快照：
 * 世界创建阶段拿到的是 {@code CraftWorldInfo}（只实现 {@code WorldInfo}），而运行期的
 * {@code getBiome} 拿到的是真正注册好的世界。用当次的那个才不会把快照的"世界还没注册"状态带进
 * 采样器绑定。
 *
 * <p>解析成功后 {@link BiomePlacement} 不可变，之后每次调用都只是一次 volatile 读 ——
 * 满足 {@link BiomeProvider} 的"完全线程安全"契约，也满足 Folia 的跨线程调用。
 *
 * <h2>{@code y} 为什么被转发</h2>
 * 原版 {@code Climate.Sampler#sample} 收三个 quart 坐标，所以这里照传。答案是否与 {@code y}
 * 无关取决于该世界实际用的 {@code NoiseGeneratorSettings}：26.3 原版
 * {@code data/minecraft/worldgen/noise_settings/nether.json} 的 {@code temperature} /
 * {@code vegetation} 是 {@code y_scale: 0.0} 的噪声、{@code depth} 等四轴是常量 {@code 0.0}，
 * 在那个设置下才与 {@code y} 无关。这不是本类的保证，见
 * {@code BiomePlacementTest#theTwoOverloadsDivergeWhenTheRouterDependsOnHeight()}。
 *
 * <h2>原版群系也会出现，这是上游行为</h2>
 * 上游把 23 个参数点追加进原版 {@code minecraft:nether} 预设，于是在原版点更近的地方仍然是原版
 * 群系。{@link #getBiomes(WorldInfo)} 因此声明 28 个 —— {@code BiomeProvider} 的契约是
 * "getBiome 能返回的每个群系都必须在 getBiomes 里"，少声明一个会让服务端在写入时丢掉它。
 *
 * <p>构造仍然是严格的：它要一个已经 {@link NetherBiomes.Resolved} 的映射，所以没有"退回原版"
 * 的代码路径；数据包没加载时 {@code NetherTerrainGenerator} 根本不会构造它。
 */
public final class NetherBiomeProvider extends BiomeProvider {
    private final Function<WorldInfo, BiomePlacement> placementSource;
    private final NetherBiomes.Resolved resolved;
    private final List<Biome> declared;
    /** 首次成功解析后的实例；解析抛异常时保持 null，让下一次调用重试。 */
    private volatile BiomePlacement bound;

    /**
     * @param placementSource 惰性来源，入参是当次请求的世界；第一次被问群系时才调用，允许它抛
     *                        "世界还没就绪"
     * @param resolved        已解析的 28 个群系
     */
    public NetherBiomeProvider(Function<WorldInfo, BiomePlacement> placementSource,
                               NetherBiomes.Resolved resolved) {
        this.placementSource = placementSource;
        this.resolved = resolved;
        // Contract: getBiomes() must name every biome getBiome() can return.
        this.declared = resolved.declared();
    }

    /** 不关心世界的来源（测试，或已经确定只有一个世界）。 */
    public NetherBiomeProvider(Supplier<BiomePlacement> placementSource,
                               NetherBiomes.Resolved resolved) {
        this(world -> placementSource.get(), resolved);
    }

    /** 已经拿到 {@link BiomePlacement} 的调用方（测试、或世界已就绪的场景）。 */
    public NetherBiomeProvider(BiomePlacement placement, NetherBiomes.Resolved resolved) {
        this(world -> placement, resolved);
    }

    @Override
    public @NotNull Biome getBiome(@NotNull WorldInfo world, int x, int y, int z) {
        // 用当次传入的 world 去解析，而不是构造时捕获的快照。
        return resolved.get(placement(world).keyAt(x, y, z));
    }

    @Override
    public @NotNull List<Biome> getBiomes(@NotNull WorldInfo world) {
        return declared;
    }

    /**
     * 该列的群系键，给需要名字而不是 {@link Biome} 的地形/populator 侧。
     *
     * <p>只能在 {@link #getBiome} 至少成功调用过之后使用：这条路径拿不到世界，无法首次解析。
     *
     * @throws IllegalStateException 还没有任何一次 {@code getBiome} 成功解析过
     */
    public String keyAt(int x, int z) {
        BiomePlacement current = bound;
        if (current == null) {
            throw new IllegalStateException("群系布局尚未解析：本方法没有世界可传，"
                    + "必须先有一次 getBiome 调用把布局绑起来");
        }
        return current.keyAt(x, z);
    }

    /**
     * 用给定的世界显式解析一次（包内可见，给同包测试与诊断用）。
     *
     * <p>生产路径只会经过 {@link #getBiome}，这是为了让测试能在没有
     * {@code NetherBiomes.Resolved#get} 所需的真 {@code Biome} 的情况下，仍然验证"惰性 + 只解析一次"。
     */
    BiomePlacement resolveFor(WorldInfo world) {
        return placement(world);
    }

    /**
     * 解析一次，之后只读 volatile 字段。
     *
     * <p>解析失败时<b>不缓存 null</b>：世界还没注册好是暂时状态，下一次调用必须能重试。
     */
    private BiomePlacement placement(WorldInfo world) {
        BiomePlacement current = bound;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (bound == null) {
                bound = placementSource.apply(world);
            }
            return bound;
        }
    }
}
