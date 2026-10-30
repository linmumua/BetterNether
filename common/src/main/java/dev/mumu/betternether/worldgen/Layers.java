package dev.mumu.betternether.worldgen;

import java.util.List;
import java.util.function.Consumer;

/**
 * 层扫描：逐行移植 wover {@code EveryLayer}（release/26.3，
 * {@code wover-feature-api/src/main/java/de/ambertation/wover/feature/api/placed/modifiers/EveryLayer.java}）。
 *
 * <pre>
 * for (int y = Math.min(MOTION_BLOCKING, max); y &gt;= min; y--) {
 *     if (top) {                                   // 地面
 *         if (isTerrain(pos.below()) &amp;&amp; isFreeOrFluid(pos) &amp;&amp; !pos.below().is(BEDROCK)) emit(pos);
 *     } else {                                     // 天花板
 *         if (isTerrain(pos) &amp;&amp; isFreeOrFluid(pos.below()) &amp;&amp; !pos.is(BEDROCK)) emit(pos.below());
 *     }
 * }
 * </pre>
 *
 * <h2>三处曾经错的地方</h2>
 * <ol>
 *   <li><b>判据不是「空气 + 邻居非空气」</b>，而是 {@code BlockPredicate.matchesTag(CommonBlockTags.TERRAIN)}
 *       （= tag {@code wover:surfaces/terrain}）加上 {@code isFreeOrFluid}。用「实心/非实心」近似会把
 *       菌岩、灵魂沙这些**地形**方块与建筑方块混为一谈，台地边缘的层数就不对了。</li>
 *   <li><b>天花板发射的是 {@code y-1}</b>（地形方块的下表面那一格），不是 {@code y}。</li>
 *   <li><b>自顶向下</b>，起点是 {@code min(MOTION_BLOCKING, max)}，不是世界顶层。</li>
 * </ol>
 *
 * <h2>为什么是 {@link Consumer} 而不是收集列表</h2>
 * 上游每一个 emit 都<b>同步</b>接着跑链上后面的 placed feature，跑完才回到下一层
 * （{@code do-while} 里 consumer 直接就位执行）。先收集成列表再统一放置会让随机流顺序整体错位，
 * 因此本类只提供回调版本，收集版 {@link #layers} 仅给「位置形态」的纯单元测试用。
 */
public final class Layers {
    /** wover 的 {@code CommonBlockTags.TERRAIN}。 */
    public static final String TERRAIN_TAG = "wover:surfaces/terrain";

    /**
     * {@code wover:surfaces/terrain} 展开后的方块 id（构建期从官方 tag 基表 + BN 增量递归算出）。
     *
     * <p>装在这里而不是每次问 {@link WorldView#inTag}：层扫描是每区块最热的路径之一，
     * 而 CE 侧的 tag 注册里**没有** wover 的 tag（只有原版 tag 会被注册），
     * 直接问会得到 null。装载期 {@link WorldgenLoader} 调 {@link #installTerrainIds} 注入，
     * 之后 {@link #isTerrain} 走纯集合命中 —— 与 ore owner 用的 {@code BlockIds} 同源同集，
     * 不会出现两套扫描。
     */
    private static volatile java.util.Set<String> terrainIds = java.util.Set.of();

    private Layers() {
    }

    /** 装载期注入（幂等；空集合等于「没装上」，此时退回 tag 查询 / 非空气近似）。 */
    public static void installTerrainIds(java.util.Set<String> ids) {
        terrainIds = ids == null ? java.util.Set.of() : java.util.Set.copyOf(ids);
    }

    public static java.util.Set<String> terrainIds() {
        return terrainIds;
    }

    /**
     * 按上游顺序把一列上的层**同步**交给 {@code out}（自顶向下）。
     *
     * @param top   {@code true}=地面层（发射 y），{@code false}=天花板层（发射 y-1）
     * @param minY  codec 里的 {@code min} **原值**（调用方不加减）。两个分支的扫描下界都是
     *              {@code minY + 1}（上游 {@code for (y = startY; y >= minHeight + 1; --y)}），
     *              差别只在发射时是否 {@code -1}，因此**最低发射层并不相同**：
     *              <ul>
     *                <li>地面（{@code top=true}，发 {@code y}）→ {@code minY + 1}</li>
     *                <li>天花板（{@code top=false}，发 {@code y-1}）→ {@code minY}</li>
     *              </ul>
     *              这条不对称是上游行为；把地面分支写成 {@code y >= minY} 会多发一层，
     *              把天花板分支写成 {@code y >= minY} 会多发 {@code minY - 1} 一层。
     */
    public static void emit(WorldView world, int x, int z, int minY, int maxY, boolean top,
                     Consumer<Point> out) {
        // `minY` 是 codec 的 `min` 原值，调用方不加减：上游 `y >= minHeight + 1` 的那个 +1
        // 属于循环条件，放在这里做一次即可。最低发射层因此是 minY+1（地面发 y）
        // 与 minY（天花板发 y-1）—— 不对称是上游行为，别为了看起来对称去抹平。
        int from = Math.max(minY, world.minY()) + 1;
        int to = Math.min(Math.min(maxY, world.maxY()), world.motionBlockingHeight(x, z));
        for (int y = to; y >= from; y--) {
            int belowY = y - 1;
            if (belowY < world.minY() || !world.inRegion(x, y, z)) {
                continue;
            }
            if (top) {
                if (isTerrain(world, x, belowY, z)
                        && isFreeOrFluid(world, x, y, z)
                        && !isBedrock(world, x, belowY, z)) {
                    out.accept(new Point(x, y, z));
                }
            } else {
                if (!world.inRegion(x, belowY, z)) {
                    continue;
                }
                if (isTerrain(world, x, y, z)
                        && isFreeOrFluid(world, x, belowY, z)
                        && !isBedrock(world, x, y, z)) {
                    out.accept(new Point(x, belowY, z));
                    // 上游每次 emit 后 `y = layerY - 1`；天花板模式的 layerY = y-1，
                    // 所以下一轮要从 emitted-1 = y-2 继续，而不是从 y-1 重扫一层地形。
                    y--;
                }
            }
        }
    }

    /** 收集版：只给纯位置测试用；生产路径必须走 {@link #emit}。 */
    public static void layers(WorldView world, int x, int z, int minY, int maxY, boolean top,
                       List<Point> out) {
        emit(world, x, z, minY, maxY, top, out::add);
    }

    /**
     * <b>自顶向下</b>第 {@code index} 个「原版地面层」的 y，没有则返回 {@link Integer#MIN_VALUE}。
     *
     * <p><b>这不是 wover 的 {@code EveryLayer}</b>，判据完全不同，不能共用
     * {@link #emit}：原版 {@code CountOnEveryLayerPlacement#findOnGroundYPosition} 是
     *
     * <pre>
     * for (int y = startY; y &gt;= minBuildHeight + 1; y--) {
     *     if ( isEmpty(below) ) continue;        // 下方必须是「实地」
     *     if ( !isEmpty(now) )  continue;        // 当前格必须是空/水/岩浆
     *     if ( below.is(BEDROCK) ) continue;     // 床顶排除
     *     if (found++ == offset) return y;
     * }
     * </pre>
     *
     * <p>其中 {@code isEmpty(state) = state.isAir() || state.is(WATER) || state.is(LAVA)}
     * —— <b>水与岩浆算空</b>（可以往里放），这与 {@link #emit} 的
     * {@code isFreeOrFluid = isAir || liquid} 恰好相反：那边问的是「这一格是不是流体方块本体」，
     * 这边问的是「能不能替换」。没有 terrain tag，也没有 `isTerrain` 严格性。
     *
     * @param startY 扫描起点（通常是该列的 {@code MOTION_BLOCKING}）
     */
    public static int groundLayer(WorldView world, int x, int z, int startY, int index) {
        if (index < 0) {
            return Integer.MIN_VALUE;
        }
        int found = 0;
        int from = Math.min(Math.min(startY, world.maxY()), world.motionBlockingHeight(x, z));
        for (int y = from; y >= world.minY() + 1; y--) {
            if (vanillaIsEmpty(world, x, y - 1, z)) {
                continue;
            }
            if (!vanillaIsEmpty(world, x, y, z)) {
                continue;
            }
            if ("minecraft:bedrock".equals(world.blockId(x, y - 1, z))) {
                continue;
            }
            if (found == index) {
                return y;
            }
            found++;
        }
        return Integer.MIN_VALUE;
    }

    /** 原版 {@code isEmpty}：空气、水、岩浆都算「可以往里放」。 */
    private static boolean vanillaIsEmpty(WorldView world, int x, int y, int z) {
        if (world.isAir(x, y, z)) {
            return true;
        }
        String id = world.blockId(x, y, z);
        return "minecraft:water".equals(id) || "minecraft:lava".equals(id);
    }

    /** {@code BlockHelper.isTerrain}：tag {@code wover:surfaces/terrain}。 */
    private static boolean isTerrain(WorldView world, int x, int y, int z) {
        java.util.Set<String> ids = terrainIds;
        if (!ids.isEmpty()) {
            String id = world.blockId(x, y, z);
            if (id != null) {
                return ids.contains(id);
            }
            Boolean inTag = world.inTag(TERRAIN_TAG, x, y, z);
            if (inTag != null) {
                return inTag;
            }
            throw new IllegalStateException(
                    "Layers: 有 terrain id 集但问不出 (" + x + "," + y + "," + z + ") 的方块身份"
                            + "（blockId 与 inTag 都返回 null）—— 不猜，见 WorldView#blockId");
        }
        // 没有安装 id 集 = 装载期没注入 manifest 的 parsed tag ids，属于装配错误，不能
        // 悄悄退化成「非空气」（菌岩/灵魂沙与建筑方块会被混为一谈，层数就错了）。
        throw new IllegalStateException(
                "Layers: wover:surfaces/terrain 未安装（WorldgenLoader.readBlockTags 应在装载期"
                        + "调用 Layers.installTerrainIds）；测试请显式注入真实 id 集");
    }

    /**
     * {@code BlockHelper.isFreeOrFluid(state)} = {@code state.isAir() || BlockHelper.isFluid(state)}，
     * 而 {@code BlockHelper.isFluid(state)} = {@code state.liquid()}。
     *
     * <p>**不能**用「流体状态非空」近似：含水（waterlogged）实心方块的 fluidState 非空但
     * {@code liquid() == false}，那样会把含水方块当成可放置的空位。{@link WorldView#liquidAt}
     * 答不出来（null）时**只认空气**，不猜。
     */
    private static boolean isFreeOrFluid(WorldView world, int x, int y, int z) {
        if (world.isAir(x, y, z)) {
            return true;
        }
        return Boolean.TRUE.equals(world.liquidAt(x, y, z));
    }

    private static boolean isBedrock(WorldView world, int x, int y, int z) {
        return "minecraft:bedrock".equals(world.blockId(x, y, z));
    }
}
