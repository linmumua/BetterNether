package dev.mumu.betternether.worldgen;

import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 单元测试用的假世界：一列列的「实心/空气」，加上可选的群系与可写区域。
 *
 * <h2>为什么需要它</h2>
 * 放置管线的每一步都是纯函数（只读 {@link WorldView}），但这些规则的正确性只有在「一列里有
 * 多层地面 / 天花板上有实心」这样的几何上才看得出来。真起一个 {@code LimitedRegion} 会变成
 * 在测试 Bukkit，而不是测试这里的规则。
 *
 * <h2>为什么 BlockData 用动态代理</h2>
 * {@code Material.createBlockData()} 需要服务端注册表，单元测试里没有。而放置逻辑只用到
 * {@link BlockData#getMaterial()}（CE 的 deceive-bukkit-material 让「这是哪种方块」本来就
 * 不可靠，代码里也只比较材质），所以一个只实现 {@code getMaterial()} 的代理就够了 ——
 * 这样测试里可以不碰注册表，也不必给生产代码加一层「方块抽象」。
 */
final class FakeWorld implements WorldView {
    private final Map<Long, Material> blocks = new HashMap<>();
    private final int minY;
    private final int maxY;
    private final Random random;
    private final String decoratedBiome;
    private final Map<Long, String> biomes = new HashMap<>();
    /** 未显式建模的坐标报什么群系。刻意不是 {@link #decoratedBiome()}，否则「外来」永远相等。 */
    private String unmappedBiome = "nether_wastes_unmapped";
    /** {@code null} = 不覆盖（由 BlockPredicates 走真实/降级路径）。 */
    private Boolean wouldSurviveOverride;
    /** 最后一次传给 {@code wouldSurvive} 的方块状态，用于断言「先设 FACING 再判存活」。 */
    private BlockData lastSurvivalProbe;
    /** 被要求 tick 的流体位置（泉眼放在 scheduleTick 后必须流动）。 */
    private final java.util.List<long[]> fluidTicks = new java.util.ArrayList<>();
    /**
     * 显式建模的「碰撞形状填满整格」：有记录就照记录回答，没记录返回 {@code null}
     * （= 「这一格我没建模」，让 {@link BlockPredicates} 走降级并记账，而不是被假世界蒙成 true）。
     */
    private final Map<Long, Boolean> fullShapes = new HashMap<>();
    /** 被要求做后处理的坐标（{@code wover:mark_postprocessing} / 幽匿脉）。 */
    private final java.util.List<int[]> postProcessed = new java.util.ArrayList<>();
    /**
     * 建模的下界城市**piece 包围盒**（{@code [minX,minY,minZ,maxX,maxY,maxZ]}）。
     * {@code null} = 这座城市的数据不在手里（→ {@link #netherCityWithin} 返回 {@code null}）。
     */
    private int[] cityBox;

    /**
     * 测试桩的 terrain 定义：与 {@link Layers} 的严格判据配套。
     *
     * <p>{@code Layers.isTerrain} 现在没有「非空气」这条退化了，没安装 id 集就报错；桩里显式给出
     * 一份与 {@code blockId} 反推结果对得上的集合（原版下界地形方块），语义仍然精确。
     */
    private static final Set<String> TEST_TERRAIN_IDS = Set.of(
            "minecraft:stone",
            "minecraft:bedrock",
            "minecraft:netherrack",
            "minecraft:basalt",
            "minecraft:blackstone",
            "minecraft:soul_sand",
            "minecraft:soul_soil",
            "minecraft:warped_nylium",
            "minecraft:crimson_nylium");

    FakeWorld(int minY, int maxY, long seed) {
        this.minY = minY;
        this.maxY = maxY;
        this.random = new Random(seed);
        this.decoratedBiome = null;
        Layers.installTerrainIds(TEST_TERRAIN_IDS);
    }

    FakeWorld(int minY, int maxY, long seed, String decoratedBiome) {
        this.minY = minY;
        this.maxY = maxY;
        this.random = new Random(seed);
        this.decoratedBiome = decoratedBiome;
        Layers.installTerrainIds(TEST_TERRAIN_IDS);
    }

    // ---------------------------------------------------------------- 建模

    /** 把 {@code [from, to]} 这一整段设为实心。 */
    FakeWorld fill(int x, int z, int from, int to, Material material) {
        for (int y = from; y <= to; y++) {
            blocks.put(key(x, y, z), material);
        }
        return this;
    }

    /** 只把一个坐标设为实心。 */
    FakeWorld solid(int x, int y, int z, Material material) {
        blocks.put(key(x, y, z), material);
        return this;
    }

    FakeWorld biome(String key) {
        for (int y = minY; y <= maxY; y++) {
            biomes.put(key(0, y, 0), key);
        }
        return this;
    }

    FakeWorld wouldSurvive(boolean value) {
        this.wouldSurviveOverride = value;
        return this;
    }

    /**
     * 显式声明某个坐标的「碰撞形状是否填满整格」（{@code wover:full_shape} 的输入）。
     *
     * <p>测试里用 {@code Material.STONE}(true) / {@code Material.STONE_STAIRS}(false) 这样的组合
     * 表达「整块 vs 台阶/栅栏」：真实判据是碰撞形状的体积，假世界没法算形状，所以由测试显式
     * 声明，断言的是**谓词有没有问这个通道、问到之后照不照它判**。
     */
    FakeWorld fullShape(int x, int y, int z, boolean value) {
        fullShapes.put(key(x, y, z), value);
        return this;
    }

    /** 建模一座下界城市：它的 piece 包围盒（全高由 {@link #minY}/{@link #maxY} 夹住）。 */
    FakeWorld cityBox(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        this.cityBox = new int[]{minX, minY, minZ, maxX, maxY, maxZ};
        return this;
    }

    /** 被要求做后处理的坐标，按调用顺序。 */
    java.util.List<int[]> postProcessed() {
        return postProcessed;
    }

    java.util.List<long[]> fluidTicks() {
        return fluidTicks;
    }

    @Override
    public void scheduleFluidTick(int x, int y, int z) {
        fluidTicks.add(new long[]{x, y, z});
    }

    BlockData lastSurvivalProbe() {
        return lastSurvivalProbe;
    }

    /** 某个坐标上的材质；没设过就是空气。 */
    Material materialAt(int x, int y, int z) {
        return blocks.getOrDefault(key(x, y, z), Material.AIR);
    }

    /**
     * 方块身份（真实实现问 CE id / 原版注册名；测试桩按材质反推原版 id）。
     *
     * <p>{@link Layers} 的 {@code isTerrain} 现在**严格**：没有身份就报错，不再退化成
     * 「非空气」。测试因此必须能回答这一问 —— 用材质名反推 `minecraft:<key>` 与
     * {@code Layers.installTerrainIds(Set.of("minecraft:stone"))} 对得上即可。
     */
    @Override
    public String blockId(int x, int y, int z) {
        // 空气也要给身份（`minecraft:air`），**不能**返回 null：`Layers.isTerrain` 现在严格，
        // 拿不到身份会直接抛 IllegalStateException —— 而层扫描恰恰会问空气格
        // （天花板模式问 `isTerrain(now)`、地面模式问 `isFreeOrFluid(now)`），
        // 空气返回 null 等于让「这里就是空气」这种最普通的情况变成异常。
        Material material = materialAt(x, y, z);
        return "minecraft:" + material.getKey().getKey();
    }

    /** 这个坐标上被写成什么（用于断言「放了什么」）。 */
    BlockData placed(int x, int y, int z) {
        Material material = blocks.get(key(x, y, z));
        return material == null ? null : blockData(material);
    }

    static long key(int x, int y, int z) {
        return ((long) (x & 0xFFFFF) << 40) | ((long) (y & 0xFFFFF) << 20) | (z & 0xFFFFF);
    }

    // ---------------------------------------------------------------- WorldView

    @Override
    public boolean inRegion(int x, int y, int z) {
        return y >= minY && y <= maxY;
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
        return materialAt(x, y, z) == Material.AIR;
    }

    @Override
    public @Nullable BlockData blockAt(int x, int y, int z) {
        if (!inRegion(x, y, z)) {
            return null;
        }
        return blockData(materialAt(x, y, z));
    }

    @Override
    public void setBlock(int x, int y, int z, BlockData data) {
        assertNotNull(data, "放置管线不该把 null 传给 setBlock");
        blocks.put(key(x, y, z), data.getMaterial());
    }

    @Override
    public String biomeAt(int x, int y, int z) {
        return biomes.getOrDefault(key(x, y, z), unmappedBiome);
    }

    @Override
    public String decoratedBiome() {
        return decoratedBiome;
    }

    @Override
    public Boolean wouldSurvive(BlockData data, int x, int y, int z) {
        lastSurvivalProbe = data;
        return wouldSurviveOverride;
    }

    /** 没建模的坐标返回 {@code null}：把「不知道」如实交出去，而不是冒充 false/true。 */
    @Override
    public Boolean fullShape(int x, int y, int z) {
        if (!inRegion(x, y, z)) {
            return null;
        }
        return fullShapes.get(key(x, y, z));
    }

    @Override
    public void markForPostProcessing(int x, int y, int z) {
        postProcessed.add(new int[]{x, y, z});
    }

    /**
     * 城市相交判定的假实现：{@code [x-reach, z-reach]..[x+reach, z+reach]} 与建模的城市盒子
     * 相交即为真（y 用本世界的 {@code minY..maxY}，与生产实现同一口径）。
     *
     * <p>刻意用**整数**比较、不做任何 {@code >>4}/{@code &15} 换算：负坐标下的区块换算最容易
     * 写出「-1 被截成 0」的错，这条假实现要把「坐标原样传进来」这件事钉住。
     */
    @Override
    public Boolean netherCityWithin(int x, int z, int reach, boolean includeCave) {
        if (cityBox == null) {
            return null;
        }
        int radius = Math.max(reach, 0);
        boolean overlapsX = x - radius <= cityBox[3] && x + radius >= cityBox[0];
        boolean overlapsZ = z - radius <= cityBox[5] && z + radius >= cityBox[2];
        boolean overlapsY = minY <= cityBox[4] && maxY >= cityBox[1];
        return overlapsX && overlapsZ && overlapsY;
    }

    @Override
    public Random random() {
        return random;
    }

    // ---------------------------------------------------------------- BlockData 代理

    /**
     * 一个只回答 {@code getMaterial()} 的 BlockData。
     *
     * <p>{@code matches()} 也实现了，因为谓词比较会用到它；其余方法返回 {@code null}/{@code 0}
     * 之类的默认值 —— 本项目的放置代码不碰它们，碰了就说明生产代码依赖了服务端状态。
     */
    /** 代理上的 FACING 存储（弱引用，避免测试里泄漏）。 */
    private static final java.util.Map<BlockData, org.bukkit.block.BlockFace> FACING =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    static BlockData blockData(Material material) {
        return (BlockData) Proxy.newProxyInstance(
                FakeWorld.class.getClassLoader(),
                new Class<?>[]{BlockData.class, org.bukkit.block.data.Directional.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getFacing" -> FACING.get(proxy);
                    case "setFacing" -> {
                        FACING.put((BlockData) proxy, (org.bukkit.block.BlockFace) args[0]);
                        yield null;
                    }
                    case "getFaces" -> java.util.Set.of(org.bukkit.block.BlockFace.NORTH);
                    case "getMaterial" -> material;
                    case "matches" -> args != null && args.length == 1
                            && ((BlockData) args[0]).getMaterial() == material;
                    case "clone", "copy" -> proxy;
                    case "getAsString" -> material.getKey().toString();
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> material.hashCode();
                    case "toString" -> "FakeBlockData[" + material + "]";
                    default -> null;
                });
    }
}
