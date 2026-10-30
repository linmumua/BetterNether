package dev.mumu.betternether.worldgen;

import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 方块谓词：上游 JSON 里 {@code block_predicate_filter} / {@code wover:is} 的判据。
 *
 * <h2>精确优先，降级可见</h2>
 * 这些判据曾经只能近似（Bukkit 没有 {@code canBeReplaced} / {@code canSurvive}）。现在
 * {@link WorldView} 提供了「问真的」通道（{@link WorldView#replaceable}、
 * {@link WorldView#wouldSurvive}、{@link WorldView#inTag}、{@link WorldView#blockId}），
 * 生产实现是 {@code RegionView}（CE 的 {@code BlockStateWrapper} + 本区块的
 * {@code WorldGenLevel}）。所以这里的规则是：
 * <ol>
 *   <li>先问 {@link WorldView} 的精确通道；拿到非 {@code null} 就用它，**不再看材质**。</li>
 *   <li>拿不到（测试假世界、非服务端世界、CE 未就绪）才退回近似，并且**记一次降级账本 +
 *       一条警告**（{@link #degraded()}），绝不静默。</li>
 * </ol>
 *
 * <h2>剩下的近似，逐条说明</h2>
 * <ul>
 *   <li>{@code minecraft:solid} → 「非空气」。原版 solid 是「有碰撞形状」，服务端没有对应的
 *       单点查询；上游只把它用在筛掉空气上，所以这条近似不改变结果。</li>
 *   <li>{@code minecraft:replaceable} → 降级时退回「空气或液体」。精确通道下是真实的
 *       {@code canBeReplaced()}（例如草、雪、菌索都算可替换）。</li>
 *   <li>{@code wover:surfaces/*} → 降级时退回「非空气且非基岩」。精确通道是
 *       {@link InTag}（真实 tag）；但 wover 的 tag 来自上游数据包，本移植没有加载，
 *       所以实际上仍会走「已解析的 id 列表」({@link Blocks})。</li>
 *   <li>{@code minecraft:would_survive} → 以前恒真；现在是 {@link WouldSurvive}，问真实的
 *       {@code canSurvive}。</li>
 * </ul>
 */
public final class BlockPredicates {
    private BlockPredicates() {
    }

    /**
     * 本次运行中因「问不出真实语义」而降级的判据；空集 = 全程走的都是真实语义。
     *
     * <p>账本本体在 {@link WorldgenWarnings}（测试类路径上没有 NMS，账本必须能被离线加载）。
     */
    public static java.util.Set<String> degraded() {
        return WorldgenWarnings.degraded();
    }

    /** 测试用：清空降级账本。 */
    static void resetForTests() {
        WorldgenWarnings.reset();
    }

    private static void degradeOnce(String key, String message) {
        WorldgenWarnings.degrade("predicate:" + key,
                "方块谓词降级（" + key + "）：" + message + " —— 这次用的是近似判据。");
    }

    /**
     * 地面判据的**降级**实现：非空气且非基岩。
     *
     * <p>上游 {@code data/wover/tags/block/surfaces/nether/terrain.json} 是**增量**文件（只列了
     * 5 个 betternether 方块，netherrack 在 wover 库的基表里），本移植没有加载那些数据包 tag，
     * 所以 {@link Surface} 会先问 {@link WorldView#inTag}（拿到 null）再落到这里。
     *
     * <p>要更精确有两条现成的路，都不是「猜材质名单」：
     * <ul>
     *   <li>{@link InTag} + 原版真实 tag（{@code minecraft:base_stone_nether} 覆盖
     *       netherrack / blackstone / basalt）；</li>
     *   <li>装载器已从 manifest 解析好的 {@link Blocks}（逐 id 比较）。</li>
     * </ul>
     */
    public static boolean surface(@NotNull WorldView world, int x, int y, int z) {
        if (!world.inRegion(x, y, z) || world.isAir(x, y, z)) {
            return false;
        }
        BlockData data = world.blockAt(x, y, z);
        if (data == null) {
            return false;
        }
        // 基岩不是地面：它是地板板体与屋顶，贴在它上面的植物只会被永久方块包住。
        return data.getMaterial() != Material.BEDROCK;
    }

    /** {@code minecraft:solid} 的近似：非空气。 */
    public static boolean solid(@NotNull WorldView world, int x, int y, int z) {
        return world.inRegion(x, y, z) && !world.isAir(x, y, z);
    }

    /**
     * {@code minecraft:replaceable}：优先真实的 {@code canBeReplaced()}，拿不到才退回「空气或液体」。
     *
     * <p>为什么这条值得精确：上游的 {@code canBeReplaced()} 还包含草、雪、菌索、藤蔓这些
     * 「可以被顶掉但看起来是方块」的东西。降级版本会把它们当成实心，于是植被永远不肯长在
     * 上面 —— 这是肉眼可见的密度差。
     */
    public static boolean replaceable(@NotNull WorldView world, int x, int y, int z) {
        Boolean precise = world.replaceable(x, y, z);
        if (precise != null) {
            return precise;
        }
        degradeOnce("replaceable", "WorldView 没有真实 canBeReplaced，退回『空气或液体』");
        if (!world.inRegion(x, y, z)) {
            return false;
        }
        if (world.isAir(x, y, z)) {
            return true;
        }
        BlockData data = world.blockAt(x, y, z);
        if (data == null) {
            return false;
        }
        Material material = data.getMaterial();
        return material == Material.LAVA || material == Material.WATER;
    }

    /**
     * {@code minecraft:would_survive}：把 {@code data} 放在这里会不会被立刻移除。
     *
     * <p>{@code null}（问不出来）时按「能活」处理，与旧行为一致，但会记一次降级。
     */
    public static boolean wouldSurvive(@NotNull WorldView world, @Nullable BlockData data,
                                       int x, int y, int z) {
        Boolean precise = data == null ? null : world.wouldSurvive(data, x, y, z);
        if (precise != null) {
            return precise;
        }
        degradeOnce("would_survive", "WorldView 没有真实 canSurvive，退回『恒真』");
        return true;
    }

    /**
     * 该位置朝 {@code face} 这一面是否结实（真实 {@code isFaceSturdy}）。
     *
     * <p>这是「能不能往这一面贴东西」的判据：幽匿脉/发光地衣的贴面、{@code wover:place_block}
     * 的挂墙都该问它。降级时退回「非空气」并记账 —— 那条近似丢掉了「朝哪一面」这个维度，
     * 草/火把所在的面本来不结实。
     */
    public static boolean faceSturdy(@NotNull WorldView world, int x, int y, int z,
                                     @Nullable Point.Direction face) {
        Boolean precise = face == null ? null : world.faceSturdy(x, y, z, face);
        if (precise != null) {
            return precise;
        }
        degradeOnce("face_sturdy", "WorldView 没有真实 isFaceSturdy，退回『非空气』");
        return solid(world, x, y, z);
    }

    /**
     * 该位置的 collision 形状在 {@code face} 这一面是否整面填满（真实 {@code isFaceFull}）。
     *
     * <p>降级时退回 {@link #faceSturdy}：上游 {@code canAttachTo} 是
     * {@code isFaceFull(supportShape) || isFaceFull(collisionShape)}，支撑形状由 `faceSturdy`
     * 覆盖，所以退回它只会丢「支撑面不满、碰撞面满」那少数情形。
     */
    public static boolean faceFullCollision(@NotNull WorldView world, int x, int y, int z,
                                            @Nullable Point.Direction face) {
        Boolean precise = face == null ? null : world.faceFullCollision(x, y, z, face);
        if (precise != null) {
            return precise;
        }
        degradeOnce("face_full_collision", "WorldView 没有真实 isFaceFull(collision)，退回 faceSturdy");
        return faceSturdy(world, x, y, z, face);
    }

    /** 该位置是否水源（真实 {@code isSourceOfType(Fluids.WATER)}）；降级退回「材质是水」。 */
    public static boolean waterSource(@NotNull WorldView world, int x, int y, int z) {
        Boolean precise = world.waterSource(x, y, z);
        if (precise != null) {
            return precise;
        }
        degradeOnce("water_source", "WorldView 没有真实流体水源判定，退回『材质是水』");
        if (!world.inRegion(x, y, z)) {
            return false;
        }
        BlockData data = world.blockAt(x, y, z);
        return data != null && data.getMaterial() == Material.WATER;
    }

    /**
     * 该位置的**碰撞形状是否填满整个方块**（{@code wover:full_shape}，真实
     * {@code BlockState#isCollisionShapeFullBlock}）。
     *
     * <h2>为什么不能退回「非空气」</h2>
     * 上游只用它一处（{@code flooded_lava_pit_surface} 的
     * {@code is_basin(any_of(matching_blocks lava, full_shape))}）：坑口被整块地形盖住时走
     * {@code lava_pit}，坑口只是被台阶/楼梯/栅栏围住时**不该**灌岩浆。把「非空气」当成
     * full shape，等于把台阶算成盖板，结果就是岩浆坑多灌一地 —— 这也是它以前「恒真」时
     * 表现出来的同一个毛病。
     *
     * <h2>降级</h2>
     * 问不到真实碰撞形状时**判假**并记账（{@code predicate:full_shape}），与
     * {@code noise_filter}/{@link InTag} 同一口径：宁可少灌，也不静默多灌。
     */
    public static boolean fullShape(@NotNull WorldView world, int x, int y, int z) {
        if (!world.inRegion(x, y, z)) {
            return false;
        }
        Boolean precise = world.fullShape(x, y, z);
        if (precise != null) {
            return precise;
        }
        degradeOnce("full_shape",
                "WorldView 没有真实 isCollisionShapeFullBlock，wover:full_shape 判假"
                        + "（台阶/楼梯这类非整块形状不能被当成盖满）");
        return false;
    }

    /**
     * 一个可编译的谓词。实现全部是纯函数（只读 {@link WorldView}），因此可以用假世界测试。
     */
    public sealed interface Predicate
            permits Air, Surface, Solid, Replaceable, Blocks, BlockIds, MatchingFluids, InTag,
            WouldSurvive, FaceSturdy, WaterSource, FullShape, AllOf, AnyOf, Not, Constant, Offset {

        boolean test(WorldView world, Point point);
    }

    /** {@code matching_block_tag minecraft:air}：唯一在 CE 下完全可靠的判据。 */
    public record Air() implements Predicate {
        @Override
        public boolean test(WorldView world, Point point) {
            return world.inRegion(point.x(), point.y(), point.z())
                    && world.isAir(point.x(), point.y(), point.z());
        }
    }

    /**
     * {@code wover:surfaces/*}：先问真实 tag（{@link WorldView#inTag}），问不到再退回
     * 「非空气且非基岩」。
     *
     * <p>注意：wover 的 {@code surfaces/*} tag 来自上游数据包，本移植没有加载，所以
     * {@link WorldView#inTag} 会返回 {@code null}，实际仍走降级分支 —— 除非哪天把那些
     * tag 打进数据包。真正的精确地面判据是 {@link InTag}（原版 tag，如
     * {@code minecraft:base_stone_nether}）或装载器已解析好的 {@link Blocks}。
     */
    public record Surface(String tag) implements Predicate {
        @Override
        public boolean test(WorldView world, Point point) {
            Boolean tagged = world.inTag(tag, point.x(), point.y(), point.z());
            if (tagged != null) {
                // wover 的 surfaces tag 语义是「这是可生长地面」，与 tag 归属一致。
                return tagged;
            }
            return surface(world, point.x(), point.y(), point.z());
        }
    }

    /**
     * 按**方块身份 id** 命中（{@code world.blockId} ∈ {@code ids}）。
     *
     * <h2>为什么这条比 {@link Blocks} 好</h2>
     * 上游的 {@code matching_block_tag} / {@code state.is(tag)} 是**方块级**语义：同一个方块的
     * 不同属性（{@code age=2}、{@code facing=north}）都算命中。{@link Blocks} 拿 BlockData 比材质
     * 只能近似；这条比 CE/原版**注册名**，方块级语义与上游逐字对齐，也不受材质注入是否可用影响。
     *
     * <h2>降级</h2>
     * {@code world.blockId(...)} 返回 {@code null}（越界 / 非服务端世界 / CE 未就绪）时，
     * **明确记一次降级**（{@code predicate:block_ids:<tag>}）再退回 {@code resolved} 的材质比较；
     * 若调用方没给 {@code resolved}，则判 false 并同样记账 —— 不静默、也不把材质碰撞当成身份证据。
     *
     * @param tag      上游 tag 名，仅用于日志/账本
     * @param ids      该 tag 解析出的方块 id 集合
     * @param resolved 装载期解析好的 BlockData（降级时按材质比较）；可为空表
     */
    public record BlockIds(String tag, java.util.Set<String> ids, List<BlockData> resolved)
            implements Predicate {
        @Override
        public boolean test(WorldView world, Point point) {
            String id = world.blockId(point.x(), point.y(), point.z());
            if (id != null) {
                return ids.contains(id);
            }
            degradeOnce("block_ids:" + tag,
                    "WorldView 问不到方块身份 id，退回『已解析状态的材质比较』");
            if (resolved.isEmpty()) {
                return false;
            }
            BlockData here = world.blockAt(point.x(), point.y(), point.z());
            if (here == null) {
                return false;
            }
            for (BlockData candidate : resolved) {
                if (here.getMaterial() == candidate.getMaterial()) {
                    return true;
                }
            }
            return false;
        }
    }

    /** 真实方块 tag 命中（{@code minecraft:base_stone_nether} / {@code minecraft:nylium} …）。 */
    public record InTag(String tag) implements Predicate {
        @Override
        public boolean test(WorldView world, Point point) {
            Boolean tagged = world.inTag(tag, point.x(), point.y(), point.z());
            if (tagged != null) {
                return tagged;
            }
            degradeOnce("in_tag:" + tag, "WorldView 问不到 tag " + tag
                    + "（不是服务端真实 tag，或 CE 未就绪）");
            return false;
        }
    }

    /** {@code minecraft:would_survive}：问真实 {@code canSurvive}。 */
    public record WouldSurvive(BlockData data) implements Predicate {
        @Override
        public boolean test(WorldView world, Point point) {
            return wouldSurvive(world, data, point.x(), point.y(), point.z());
        }
    }

    /** 朝某一面是否结实（真实 {@code isFaceSturdy}）。 */
    public record FaceSturdy(Point.Direction face) implements Predicate {
        @Override
        public boolean test(WorldView world, Point point) {
            return faceSturdy(world, point.x(), point.y(), point.z(), face);
        }
    }

    /** 是否是水源（真实 {@code isSourceOfType(WATER)}）。 */
    public record WaterSource() implements Predicate {
        @Override
        public boolean test(WorldView world, Point point) {
            return waterSource(world, point.x(), point.y(), point.z());
        }
    }

    public record Solid() implements Predicate {
        @Override
        public boolean test(WorldView world, Point point) {
            return solid(world, point.x(), point.y(), point.z());
        }
    }

    public record Replaceable() implements Predicate {
        @Override
        public boolean test(WorldView world, Point point) {
            return replaceable(world, point.x(), point.y(), point.z());
        }
    }

    /**
     * {@code wover:full_shape}：碰撞形状填满整格。
     *
     * <p>上游 {@code IsFullShape#test} 读的是 {@code pos.offset(offset)} 的**状态**，但把
     * **未偏移**的 {@code blockPos} 传给 {@code isCollisionShapeFullBlock}。本移植的可选
     * {@code offset} 由装载器的通用 {@link Offset} 包装处理（读的坐标整体平移），与上游
     * 「读哪一格」一致；上游那个「判定用未偏移 pos」的细节只在「形状依赖周围方块」的方块上
     * 才有差别，而这类方块的形状本来就不是整格，两者都判假。数据里 {@code offset} 也没出现过。
     */
    public record FullShape() implements Predicate {
        @Override
        public boolean test(WorldView world, Point point) {
            return fullShape(world, point.x(), point.y(), point.z());
        }
    }

    /**
     * {@code matching_block_tag <具体 tag>}：与解析出来的方块逐个比较。
     *
     * <p>比较双方都是我们自己解析出来的 BlockData（tag 内容来自构建期清单），所以不受 CE
     * 的 Material 映射影响。比较**只看材质、忽略属性**：上游 tag 里的方块往往带属性
     * （{@code age=2} 的墨丛），而 CE 的默认状态未必是那个属性值；按完整 BlockData 比较会让
     * 整条谓词几乎永不成立。
     */
    public record Blocks(String tag, List<BlockData> accepted) implements Predicate {
        @Override
        public boolean test(WorldView world, Point point) {
            BlockData here = world.blockAt(point.x(), point.y(), point.z());
            if (here == null) {
                return false;
            }
            for (BlockData candidate : accepted) {
                if (here.getMaterial() == candidate.getMaterial()) {
                    return true;
                }
            }
            return false;
        }
    }

    /** {@code matching_fluids}：只看材质是不是那种液体。 */
    public record MatchingFluids(List<Material> fluids) implements Predicate {
        @Override
        public boolean test(WorldView world, Point point) {
            BlockData here = world.blockAt(point.x(), point.y(), point.z());
            if (here == null) {
                return false;
            }
            for (Material fluid : fluids) {
                if (here.getMaterial() == fluid) {
                    return true;
                }
            }
            return false;
        }
    }

    public record AllOf(List<Predicate> predicates) implements Predicate {
        @Override
        public boolean test(WorldView world, Point point) {
            for (Predicate predicate : predicates) {
                if (!predicate.test(world, point)) {
                    return false;
                }
            }
            return true;
        }
    }

    public record AnyOf(List<Predicate> predicates) implements Predicate {
        @Override
        public boolean test(WorldView world, Point point) {
            for (Predicate predicate : predicates) {
                if (predicate.test(world, point)) {
                    return true;
                }
            }
            return false;
        }
    }

    public record Not(Predicate inner) implements Predicate {
        @Override
        public boolean test(WorldView world, Point point) {
            return !inner.test(world, point);
        }
    }

    /** {@code minecraft:true} 与 {@code would_survive}（见类注释里的缺口说明）。 */
    public record Constant(boolean value) implements Predicate {
        @Override
        public boolean test(WorldView world, Point point) {
            return value;
        }
    }

    /** 谓词 + 偏移：{@code wover:is} 与 {@code matching_block_tag} 的 {@code offset} 字段。 */
    public record Offset(Point shift, Predicate inner) implements Predicate {
        @Override
        public boolean test(WorldView world, Point point) {
            return inner.test(world, point.offset(shift.x(), shift.y(), shift.z()));
        }
    }

    /**
     * 占位用的恒真谓词。
     *
     * <p><b>已经被 {@link WouldSurvive} 取代</b>：装载器解析 {@code minecraft:would_survive} 时
     * 应该用 {@code json} 里的 {@code state} 造一个 {@link WouldSurvive}，本方法只留给「
     * 拿不到目标状态」的调用点，且它返回的谓词在 {@link #degraded()} 里查不到 ——
     * 因为它并不读世界，只是显式表达了「这一步不检查」。
     */
    public static @Nullable Predicate unverifiedSurvive() {
        return new Constant(true);
    }
}
