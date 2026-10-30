package dev.mumu.betternether.biome;

import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;

import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;

import java.util.logging.Logger;

/**
 * {@link ClimateSampler} 的生产实现：把原版自己的气候采样器接过来，一格气候都不自己算。
 *
 * <h2>链路</h2>
 * <pre>
 * CraftWorld#getHandle()            → net.minecraft.server.level.ServerLevel
 * level#getChunkSource()            → ServerChunkCache
 * chunkCache#randomState()          → net.minecraft.world.level.levelgen.RandomState
 * randomState#createClimateSampler  → net.minecraft.world.level.biome.Climate$Sampler（六个 Bound）
 * sampler#sample(quartX, quartY, quartZ) → Climate$TargetPoint
 * </pre>
 * 这三步都是原版自己的公开入口（{@code RandomState#createClimateSampler} 与
 * {@code Climate$Sampler#sample} 的签名已用 javap 在 {@code mucore-26.3.jar} 上核准：
 * {@code sample(int,int,int)} 先把三个 quart 用 {@code QuartPos.toBlock} 换算成方块坐标
 * ——即 {@code i << 2} —— 再喂给六个 Bound）。
 *
 * <p>{@code RandomState} 来自该世界的 {@code NoiseGeneratorSettings}，对下界维度就是
 * {@code minecraft:nether}，其 {@code noise_router} 的 {@code temperature}/{@code vegetation}
 * 是 {@code xz_scale 0.25, y_scale 0.0} 的噪声（见 {@link BiomePlacement} 的类注释）。
 * 于是这里得到的目标点与玩家真正看到的下界群系用的是<b>同一个</b>采样器。
 *
 * <h2>为什么不在本类里留任何回退</h2>
 * 拿不到世界（不是 {@code CraftWorld}）时直接抛异常。给一个"猜"的气候场只会生成一张与上游毫无
 * 关系的群系图，而且错误会被写进区域文件；抛异常至少会在世界加载时被看见。
 */
public final class NetherClimateSampler implements ClimateSampler {
    /** 竖直无关性自检用的两个方块高度（下界的可行走区间内、且分处低/高半区）。 */
    static final int VERTICAL_PROBE_LOW = 40;
    static final int VERTICAL_PROBE_HIGH = 120;

    private final Climate.Sampler sampler;

    private NetherClimateSampler(Climate.Sampler sampler) {
        this.sampler = sampler;
    }

    /**
     * 绑定一个世界的原版气候采样器。
     *
     * @param world 服务端世界
     * @return 采样器
     * @throws IllegalStateException 当入参不是服务端世界（本 API 够不到 NMS 层）
     */
    public static NetherClimateSampler forWorld(World world) {
        if (!(world instanceof CraftWorld craftWorld)) {
            throw new IllegalStateException("下界群系选择需要服务端世界，实际是 "
                    + world.getClass().getName());
        }
        ServerLevel level = craftWorld.getHandle();
        ServerChunkCache chunkCache = level.getChunkSource();
        RandomState randomState = chunkCache.randomState();
        // EMPTY_UNCACHED 是该上下文里"不做跨调用缓存"的那一个；气候采样每次只读六个噪声值，
        // 不需要密度缓冲，所以这是最省的那档，也保证多线程同时采样时没有共享可变状态。
        return new NetherClimateSampler(
                randomState.createClimateSampler(SamplerContext.EMPTY_UNCACHED));
    }

    /**
     * 绑定并顺手做一次<b>竖直无关性自检</b>。
     *
     * <p>本工程的 {@code keyAt(x, z)} 用固定的 quart y（{@code SAMPLE_QUART_Y = 0}），只有当该世界的
     * {@code NoiseGeneratorSettings} 的气温/湿度噪声 {@code y_scale == 0} 时才与按高度采样的
     * provider 一致。26.3 原版 {@code noise_settings/nether.json} 满足这一点，但一个 datapack 可以
     * 用别的设置；那时地表/populator 画的群系会与区块上标的群系分叉。
     *
     * <p>这里在绑定时直接用真采样器比一次 y=40 与 y=120 的目标点。不相等只 WARN 不抛：世界仍能
     * 生成，只是偏离上游，操作员需要知道这件事，而不是被一个异常挡在世界外面。
     *
     * @param world  服务端世界
     * @param logger 用来抛出那条 WARN
     * @return 采样器
     */
    public static NetherClimateSampler forWorld(World world, Logger logger) {
        NetherClimateSampler sampler = forWorld(world);
        sampler.checkVerticalIndependence(logger);
        return sampler;
    }

    /** 见 {@link #forWorld(World, Logger)}。返回 {@code false} 表示该世界有竖直气候轴。 */
    boolean checkVerticalIndependence(Logger logger) {
        int lowY = BiomePlacement.quart(VERTICAL_PROBE_LOW);
        int highY = BiomePlacement.quart(VERTICAL_PROBE_HIGH);
        int[] phase = {0, 8, -8, 16, -16};
        for (int x : phase) {
            for (int z : phase) {
                TargetPoint low = sample(x, lowY, z);
                TargetPoint high = sample(x, highY, z);
                if (!low.equals(high)) {
                    if (logger != null) {
                        logger.warning("本世界的 NoiseGeneratorSettings 有竖直气候轴：同一列在 y="
                                + VERTICAL_PROBE_LOW + " 与 y=" + VERTICAL_PROBE_HIGH
                                + " 的 climate target 不同（" + low + " vs " + high + "）。"
                                + "配对的 28 个参数点在 C/E/D/W 四轴都是 0，所以四轴不影响结论，"
                                + "但温度/湿度随高度变化意味着 keyAt(x, z) 与按高度采样的 provider 会分叉，"
                                + "地表/populator 画的群系可能和区块上标的群系不一致。");
                    }
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public TargetPoint sample(int quartX, int quartY, int quartZ) {
        Climate.TargetPoint target = sampler.sample(quartX, quartY, quartZ);
        return new TargetPoint(
                target.temperature(),
                target.humidity(),
                target.continentalness(),
                target.erosion(),
                target.depth(),
                target.weirdness());
    }
}
