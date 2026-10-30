package dev.mumu.betternether.worldgen;

import dev.mumu.betternether.biome.BiomePlacement;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.synth.Noise;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.bukkit.block.data.BlockData;
import org.bukkit.generator.LimitedRegion;

import java.util.Random;

/**
 * {@link WorldView} 的唯一运行期实现：包一层 {@link LimitedRegion}。
 *
 * <h2>Folia 的两条硬规则</h2>
 * <ul>
 *   <li><b>越界读会抛异常</b>，所以每次读/写之前都先问 {@link LimitedRegion#isInRegion}。
 *       读和写会去问两次（一次在本类，一次在调用点），这不算浪费：调用点的守卫是
 *       「这个方块该不该放」的语义判断，本类的守卫是「这次访问安全吗」的安全判断。</li>
 *   <li><b>区域之外一个方块都不许写</b>。所以 {@link #setBlock} 在越界时直接返回，
 *       而不是抛异常 —— 一条跨越区块边界的树干不该让整个区块生成失败，只该被裁掉
 *       （与 TreePopulator 的边界裁切同一口径）。</li>
 * </ul>
 *
 * <h2>群系是 2D 的：传 y 只是保持签名一致</h2>
 * {@link BiomePlacement#keyAt(int, int, int)} 现在恒等于两参版本 —— 下界的
 * {@code temperature}/{@code vegetation} 噪声都是 {@code y_scale = 0}，**没有竖直气候轴**，
 * 旧的 {@code CEILING_BAND_FLOOR} 竖带规则已废弃（见 {@link BiomePlacement} 的 deprecated 说明）。
 * 「挂在天花板还是长在地上」由放置修饰符（高度区间 / 上下表面谓词）决定，不是群系。
 * 保留 y 参数是为了将来真出现竖直气候轴时不用改签名。
 */
public final class RegionView implements WorldView {
    private final LimitedRegion region;
    private final BiomePlacement placement;
    private final Random random;
    private final int minY;
    private final int maxY;
    private final String decoratedBiome;
    /** CE/NMS 适配器；为 {@code null} 表示这次拿不到真实语义（已告警一次）。 */
    private final RegionBlockAccess access;

    public RegionView(LimitedRegion region, BiomePlacement placement, Random random,
               int minY, int maxY, String decoratedBiome) {
        this.region = region;
        this.placement = placement;
        this.random = random;
        this.minY = minY;
        this.maxY = maxY;
        this.decoratedBiome = decoratedBiome;
        this.access = RegionBlockAccess.create(region, minY, maxY - minY + 1);
    }

    @Override
    public boolean inRegion(int x, int y, int z) {
        return region.isInRegion(x, y, z);
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
    public boolean isAir(int x, int y, int z) {
        if (!region.isInRegion(x, y, z)) {
            return false;
        }
        // 只问「是不是空气」：CE 的 deceive-bukkit-material 会把自定义方块映射到某个原版
        // Material，问「这是哪种方块」不可靠；空气不会被伪装，所以这条判断是诚实的。
        return region.getBlockData(x, y, z).getMaterial().isAir();
    }

    @Override
    public BlockData blockAt(int x, int y, int z) {
        if (!region.isInRegion(x, y, z)) {
            return null;
        }
        return region.getBlockData(x, y, z);
    }

    @Override
    public void setBlock(int x, int y, int z, BlockData data) {
        if (y < minY || y > maxY || !region.isInRegion(x, y, z)) {
            return;
        }
        region.setBlockData(x, y, z, data);
    }

    @Override
    public String biomeAt(int x, int y, int z) {
        return placement.keyAt(x, y, z);
    }

    @Override
    public String decoratedBiome() {
        return decoratedBiome;
    }

    /**
     * 该 level 的 {@code RandomState} 噪声采样，供 {@code wover:noise_filter} 使用。
     *
     * <p>链路与 {@code NetherClimateSampler}、{@code NetherTerrainGenerator.RandomStateSurface}
     * 完全一致（同一套直接 NMS 调用，不用反射）：
     * <pre>
     * CraftWorld#getHandle()      → net.minecraft.server.level.ServerLevel
     * level#getChunkSource()      → ServerChunkCache
     * chunkCache#randomState()    → net.minecraft.world.level.levelgen.RandomState
     * randomState#getOrCreateNoise(ResourceKey&lt;NormalNoise&gt;) → Noise（接口，javap 实测）
     * noise#get(x, y, z)
     * </pre>
     * 噪声必须来自该世界的 {@code RandomState}（世界种子化、按世界缓存）。wover 的
     * {@code NoiseFilter} 注释专门记录过这个坑：早先它用静态缓存，噪声场取决于「本进程哪个
     * 区块先被生成」，重启前后区块会沿接缝错位。
     *
     * <p>失败（例如非服务端世界）只告警一次并返回 {@link Double#NaN}：
     * {@code noise_filter} 的区间比较会把 NaN 判假，表现为「该特征一条都不放」，
     * 而不是静默全部通过。
     */
    @Override
    public double noise(String noiseId, double x, double y, double z) {
        if (!(region.getWorld() instanceof org.bukkit.craftbukkit.CraftWorld craftWorld)) {
            warnNoiseOnce("世界不是服务端世界：" + region.getWorld().getClass().getName());
            return Double.NaN;
        }
        ServerLevel level = craftWorld.getHandle();
        ServerChunkCache chunkCache = level.getChunkSource();
        RandomState randomState = chunkCache.randomState();
        // javap 实测（mucore-26.3.jar）：
        //   RandomState#getOrCreateNoise(ResourceKey<NormalNoise>) 返回 **Noise**（不是 NormalNoise），
        //   `Noise` 接口才有 `float get(double,double,double)`；`NormalNoise` 只是它的提供者
        //   （`NormalNoise#create(RandomSource)` → Noise）。按接口取，别按具体类取。
        Noise noise = randomState.getOrCreateNoise(noiseKey(noiseId));
        return noise.get(x, y, z);
    }

    private static volatile boolean noiseWarned;

    private static void warnNoiseOnce(String reason) {
        if (noiseWarned) {
            return;
        }
        noiseWarned = true;
        WorldgenWarnings.warn(
                "wover:noise_filter 无法采样噪声（" + reason + "）：该修饰符下的位置将全部被拒绝。");
    }

    /** {@code minecraft:foo} → {@code ResourceKey<NormalNoise>}；26.3 的类名是 {@code Identifier}。 */
    private static ResourceKey<NormalNoise> noiseKey(String noiseId) {
        return ResourceKey.create(Registries.NOISE, Identifier.parse(noiseId));
    }

    @Override
    public Random random() {
        return random;
    }

    // ---------------------------------------------------------------- 精确查询
    //
    // 这四处都是「问真的」：CE 的方块状态自己带 replaceable / canSurvive / hasTag，
    // 高度图问 staging level 的原版 Heightmap。拿不到就返回 null（或按注释里的说明降级），
    // 由 BlockPredicates 记进降级账本并告警 —— 不是静默近似。

    /**
     * 该列真实 {@code MOTION_BLOCKING} 高度。
     *
     * <p>用本区块的 {@code WorldGenLevel#getHeight(...)}（javap 实测：
     * {@code LevelReader#getHeight(Heightmap.Types, int, int)}），与
     * {@code CraftRegionAccessor#getHighestBlockYAt(int,int,HeightMap)} 走的是同一跳，
     * 因此包含本次生成已经写进 staging 区块的方块。
     *
     * <p>列不在可写区域内时无法问（越界读会抛），退回 {@link #maxY()} 并告警一次：
     * 这是唯一一处刻意保留的降级，因为 {@code count_on_every_layer} 的列宽可能跨出 region 缓冲。
     */
    @Override
    public int motionBlockingHeight(int x, int z) {
        if (access == null) {
            return maxY;
        }
        try {
            return access.level().getHeight(
                    net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z);
        } catch (RuntimeException | LinkageError error) {
            RegionBlockAccess.reportOnce("heightmap", "无法读取 MOTION_BLOCKING 高度图：" + error);
            return maxY;
        }
    }

    @Override
    public String blockId(int x, int y, int z) {
        if (!region.isInRegion(x, y, z)) {
            return null;
        }
        return RegionBlockAccess.stateId(region.getBlockData(x, y, z));
    }

    @Override
    public Boolean replaceable(int x, int y, int z) {
        if (!region.isInRegion(x, y, z)) {
            return null;
        }
        return RegionBlockAccess.replaceable(region.getBlockData(x, y, z));
    }

    @Override
    public Boolean wouldSurvive(BlockData data, int x, int y, int z) {
        return RegionBlockAccess.wouldSurvive(access, data, x, y, z);
    }

    @Override
    public String propertyAt(String property, int x, int y, int z) {
        if (!region.isInRegion(x, y, z)) {
            return null;
        }
        return RegionBlockAccess.propertyAt(region.getBlockData(x, y, z), property);
    }

    /** 供「原版 placed feature 原生执行」用的 staging {@code WorldGenLevel}。 */
    @Override
    public Object nativeWorldGenLevel() {
        return access == null ? null : access.level();
    }

    @Override
    public Boolean faceSturdy(int x, int y, int z, Point.Direction face) {
        return RegionBlockAccess.faceSturdy(access, x, y, z, face);
    }

    @Override
    public String fluidAt(int x, int y, int z) {
        if (!region.isInRegion(x, y, z)) {
            return null;
        }
        return RegionBlockAccess.fluidId(region.getBlockData(x, y, z));
    }

    @Override
    public Boolean faceFullCollision(int x, int y, int z, Point.Direction face) {
        return RegionBlockAccess.faceFullCollision(access, x, y, z, face);
    }

    @Override
    public Boolean liquidAt(int x, int y, int z) {
        if (!region.isInRegion(x, y, z)) {
            return null;
        }
        return RegionBlockAccess.liquidAt(access, x, y, z);
    }

    @Override
    public Boolean waterSource(int x, int y, int z) {
        return RegionBlockAccess.waterSource(access, x, y, z);
    }

    @Override
    public void scheduleFluidTick(String fluidId, int x, int y, int z, int delay) {
        if (!region.isInRegion(x, y, z)) {
            return;
        }
        RegionBlockAccess.scheduleFluidTick(access, fluidId, x, y, z, delay);
    }

    @Override
    public void markForPostProcessing(int x, int y, int z) {
        if (!region.isInRegion(x, y, z)) {
            return;
        }
        RegionBlockAccess.markForPostProcessing(access, x, y, z);
    }

    /** {@code wover:full_shape}：真实的 {@code isCollisionShapeFullBlock}（台阶/楼梯不是整块）。 */
    @Override
    public Boolean fullShape(int x, int y, int z) {
        if (!region.isInRegion(x, y, z)) {
            return null;
        }
        return RegionBlockAccess.fullShape(access, x, y, z);
    }

    /**
     * {@code betternether:avoid_nether_city}：真实的 structure starts 判定。
     *
     * <p>不在这里做任何区域裁剪 —— 裁剪发生在 {@code RegionBlockAccess} 里，因为「哪些区块在手里」
     * 只能从 staging 的 {@code WorldGenRegion#hasChunk} 问（{@link LimitedRegion#isInRegion}
     * 只回答单个坐标，给不出区块级视野）。所以这里只转交。
     */
    @Override
    public Boolean netherCityWithin(int x, int z, int reach, boolean includeCave) {
        return RegionBlockAccess.netherCityWithin(access, x, z, reach, includeCave);
    }

    @Override
    public Boolean inTag(String tag, int x, int y, int z) {
        if (!region.isInRegion(x, y, z)) {
            return null;
        }
        return RegionBlockAccess.inTag(access, region.getBlockData(x, y, z), tag);
    }
}
