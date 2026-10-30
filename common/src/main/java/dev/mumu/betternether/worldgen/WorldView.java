package dev.mumu.betternether.worldgen;

import java.util.List;
import java.util.Random;

/**
 * 一块候选位置的只读/可写视图，以及放置管线需要的全部上下文。
 *
 * <h2>为什么要这层抽象</h2>
 * 放置逻辑（修饰符链、方块谓词、特征）是从上游 JSON 解释出来的纯逻辑，必须能在没有服务器、
 * 没有 CraftEngine 的情况下被单元测试驱动。所以「读一个方块」「写一个方块」「这个世界多高」
 * 「这个坐标属于哪个群系」都抽在接口上，运行期实现是 {@link RegionView}（唯一会碰
 * {@link org.bukkit.generator.LimitedRegion} 的类）。
 *
 * <h2>CraftEngine 的欺骗与这里的判据</h2>
 * CE 把自定义方块映射到某个原版 Material 上（deceive-bukkit-material），所以「这是什么方块」
 * 在 Bukkit 视角下不可靠。这里的判据分两档：
 * <ul>
 *   <li>{@link #isAir} —— 诚实：CE 不会让空气看起来像实心，也不会让实心看起来像空气。</li>
 *   <li>{@link #blockAt} —— 只在「与已知 BlockData 比较」时使用（
 *       {@code matching_blocks} / 具体 id 的 {@code matching_block_tag}），此时比较双方
 *       都是我们自己解析出来的 BlockData，不受映射影响。</li>
 * </ul>
 */
public interface WorldView {
    /** 这个坐标是否在可写区域内。Folia 下越界读会抛异常，所以每次访问前都要问。 */
    boolean inRegion(int x, int y, int z);

    /** 这个世界的最低 y（含）。 */
    int minY();

    /** 这个世界的最高 y（含）。 */
    int maxY();

    /** 是否是空气。唯一在 CE 下仍然可靠的方块判断。 */
    boolean isAir(int x, int y, int z);

    /** 当前方块的 BlockData，越界返回 {@code null}。 */
    org.bukkit.block.data.BlockData blockAt(int x, int y, int z);

    /** 放一个方块。调用方必须已经用 {@link #inRegion} 判过。 */
    void setBlock(int x, int y, int z, org.bukkit.block.data.BlockData data);

    /**
     * 这个坐标属于哪个群系。
     *
     * <p>本移植的群系是 **2D climate**：{@code keyAt(x,y,z)} 恒等于 {@code keyAt(x,z)}
     * （下界 {@code temperature}/{@code vegetation} 的 {@code y_scale = 0}）。旧的竖带
     * （{@code CEILING_BAND_FLOOR}）已废弃 —— 「天花板植被 vs 地面植被」由高度区间与
     * 上下表面谓词区分，不是群系。
     */
    String biomeAt(int x, int y, int z);

    /**
     * 本次装饰的群系 key，即 {@code minecraft:biome} 修饰符要比对的对象。
     *
     * <p>{@code null} 表示「不筛选」（测试或非群系化的调用）。
     */
    String decoratedBiome();

    /** 一次放置尝试的随机源。同一 seed + 同一区块必须产出同一结果。 */
    Random random();

    /**
     * 世界种子化的噪声采样，给 {@code wover:noise_filter} 用。
     *
     * <p>语义必须与 wover 一致：噪声来自**该 level 的 {@code RandomState}**
     * （{@code ctx.getLevel().getLevel().getChunkSource().randomState().getOrCreateNoise(key)}），
     * 而不是任何静态缓存 —— wover 的注释专门写了这条：静态缓存的场取决于「本进程哪个区块先被生成」，
     * 重启前后区块会沿接缝错位。
     *
     * @param noiseId 形如 {@code minecraft:netherrack} 的噪声资源 id（不带 {@code #}）
     * @return 该点的噪声值；**没有实现时返回 {@link Double#NaN}**，让 {@code noise_filter} 的
     *         区间比较自然判假（而不是悄悄全部通过）
     */
    default double noise(String noiseId, double x, double y, double z) {
        return Double.NaN;
    }

    // ---------------------------------------------------------------- 精确查询
    //
    // 下面五个是「能精确就问真的」的通道：生产实现（{@link RegionView}）走 CE + NMS 的真实
    // 判定，测试实现可以只覆盖需要的那一个。**返回值刻意用可空类型**：null 明确表示
    // 「这次问不出来」，而不是伪装成 false —— 调用方（{@link BlockPredicates}）会把 null
    // 记进降级账本并告警，绝不让「不知道」悄悄变成一个判定结果。

    /**
     * 该列 {@code MOTION_BLOCKING} 高度图的值（原版
     * {@code PlacementContext#getHeight(Heightmap.Types.MOTION_BLOCKING, x, z)}）。
     *
     * <p>{@code count_on_every_layer} 的扫描起点就是它，用 {@link #maxY()} 顶替会让
     * 「屋顶被挖开 / 地形很低」的列整体后移。测试实现返回 {@link #maxY()} 即可，
     * 生产实现必须是真实高度图。
     *
     * @return 该列最高遮挡方块之上那一格的 y
     */
    default int motionBlockingHeight(int x, int z) {
        return maxY();
    }

    /**
     * 该位置的方块**身份** id，形如 {@code minecraft:netherrack} 或
     * {@code betternether:mushroom_grass}。
     *
     * <p>这是 CE 下唯一诚实的「这是什么方块」：{@code getMaterial()} 拿到的只是被借用的
     * 原版宿主（deceive-bukkit-material），身份必须问 CE 的 {@code BlockStateWrapper#ownerId}。
     *
     * @return {@code null} = 问不出来（越界、非服务端世界、CE 未就绪）
     */
    default String blockId(int x, int y, int z) {
        return null;
    }

    /**
     * 该位置是否可被替换（原版 {@code BlockState#canBeReplaced()}，无参版本）。
     *
     * @return {@code null} = 问不出来。**不要**把 null 当成 false。
     */
    default Boolean replaceable(int x, int y, int z) {
        return null;
    }

    /**
     * 把 {@code data} 放到该位置后会不会被立刻移除（原版
     * {@code BlockBehaviour.BlockStateBase#canSurvive(LevelReader, BlockPos)}）。
     *
     * <p>这是 {@code would_survive} 谓词与 {@code wover:place_block} 的真实判据。
     *
     * @return {@code null} = 问不出来
     */
    default Boolean wouldSurvive(org.bukkit.block.data.BlockData data, int x, int y, int z) {
        return null;
    }

    /**
     * 该位置的状态是否属于某个**服务端真实存在**的方块 tag（如 {@code minecraft:nylium}、
     * {@code minecraft:base_stone_nether}）。
     *
     * <p>注意：只有服务端 tag 注册表里真的有的 tag 才能回答。manifest 里那些 wover 标签
     * （{@code wover:surfaces/nether/terrain} 之类）来自上游数据包，本移植没有加载，
     * 这里会返回 {@code null} 而不是 false —— 让调用方回退到「已解析的 id 列表」比较。
     *
     * @param tag 带命名空间的 tag id，**不带** {@code #}
     * @return {@code null} = tag 不存在或问不出来
     */
    default Boolean inTag(String tag, int x, int y, int z) {
        return null;
    }

    /**
     * 该位置**流动介质**的身份（{@code minecraft:empty} / {@code minecraft:water} /
     * {@code minecraft:lava}）。
     *
     * <p>原版 {@code BlockState#getFluidState()} 的真实结果，问的是 CE 的
     * {@code BlockStateWrapper#fluidState}。比「材质是不是 LAVA/WATER」精确：含水方块
     * （水草、含水台阶）的真实流体是水，而材质不是水桶方块。
     *
     * @return {@code null} = 问不出来
     */
    default String fluidAt(int x, int y, int z) {
        return null;
    }

    /**
     * 该位置的方块是否是**流体方块本体**（原版 {@code BlockState#liquid()}）。
     *
     * <p>与 {@link #waterSource} 的区别是关键：含水方块（waterlogged 的台阶/栅栏）的
     * {@code getFluidState()} 是水源，但 {@code liquid()} 为 {@code false} —— 它是实心方块。
     * wover 的 {@code BlockHelper.isFreeOrFluid = state.isAir() || state.liquid()}，
     * 所以 {@code Layers} 这类「这一格能不能通行」的判据**必须**用本条；
     * 用 {@code fluidAt(...) != "minecraft:empty"} 会把含水实心方块误判成可通行。
     *
     * @return {@code null} = 问不出来（越界 / 非服务端世界）
     */
    default Boolean liquidAt(int x, int y, int z) {
        return null;
    }

    /**
     * 该位置的方块在 {@code face} 这一面**是否结实**（原版
     * {@code BlockState#isFaceSturdy(BlockGetter, BlockPos, Direction)}）。
     *
     * <p>这是「能不能往这一面贴东西」的真实判据（比「非空气」严：火把、草、藤蔓所在的面
     * 不结实）。{@code wover:place_block} 的贴墙语义、树上挂载的判据都该问它。
     *
     * @param face 要检查的那一面（{@code Point.Direction}）
     * @return {@code null} = 问不出来
     */
    default Boolean faceSturdy(int x, int y, int z, Point.Direction face) {
        return null;
    }

    /**
     * 该位置的 **collision 形状**在 {@code face} 这一面是否整面填满（原版
     * {@code Block.isFaceFull(Block#getCollisionShape(...), direction)}）。
     *
     * <p>与 {@link #faceSturdy} 的区别：`faceSturdy` 问的是「这一面结不结实」（支撑形状，
     * 大多数方块走这条），这一条问的是「碰撞形状是否满面」。上游
     * {@code MultifaceBlock#canAttachTo} 两条分支都要：{@code isFaceFull(supportShape)} 已由
     * `faceSturdy` 覆盖，这是剩下的 collision 那一支。
     *
     * @return {@code null} = 问不出来
     */
    default Boolean faceFullCollision(int x, int y, int z, Point.Direction face) {
        return null;
    }

    /**
     * 读该位置方块的**属性值**（CE 自定义方块的 {@code ImmutableBlockState} 属性，序列化名形式）。
     *
     * <p>用于「这个方块朝哪边」这类判断（例如孢子的 {@code facing}）。链路（全部 public、无反射）：
     * {@code CraftEngineBlocks#getCustomBlockState(BlockData)} → {@code ImmutableBlockState#getProperty(name)}
     * → {@code ImmutableBlockState#get(Property)} → {@code Property#valueName(value)}。
     *
     * @param property 属性名，如 {@code facing} / {@code shape} / {@code age}
     * @return 属性值的序列化名（{@code "north"} / {@code "true"} / {@code "2"}）；方块不是 CE 自定义方块、
     *         没有该属性、或问不出来时返回 {@code null}（不猜）
     */
    default String propertyAt(String property, int x, int y, int z) {
        return null;
    }

    /**
     * 本次生成所用的**原生世界对象**（{@code net.minecraft.world.level.WorldGenLevel}）。
     *
     * <p>为「让原版 placed feature 原生跑一次」提供入口：拿到它之后
     * {@code PlacedFeature#place(WorldGenLevel, ChunkGenerator, RandomSource, BlockPos)} 这类原版入口
     * 才可调用，不必把 vanilla 特征在本项目里重写一遍。
     *
     * <p>返回 {@code Object} 是刻意的：{@link WorldView} 必须能在**没有 NMS 的测试类路径**上加载。
     * 生产实现（{@link RegionView}）返回真正的 {@code WorldGenLevel}；测试/非服务端返回 {@code null}。
     *
     * <p><b>缺的两样得由调用方自备</b>：{@code ChunkGenerator} 与 {@code RandomSource} 都无法从
     * {@code java.util.Random} 忠实重建（不同 PRNG、不同种子流），必须用调用点手上真实的那两个，
     * 否则同一 seed 的世界会变。这里只交出「世界」这一件。
     */
    default Object nativeWorldGenLevel() {
        return null;
    }

    /**
     * 该位置的流体是不是**水源**（原版
     * {@code BlockState#getFluidState().isSourceOfType(Fluids.WATER)}）。
     *
     * <p>{@code Material == WATER} 分不出「水源 / 流动水 / waterlogged 的非水源」，而
     * 幽匿脉/发光地衣的 {@code getStateForPlacement} 正是靠 {@code isSourceOfType(WATER)}
     * 决定要不要 {@code WATERLOGGED=true}。
     *
     * @return {@code null} = 问不出来
     */
    default Boolean waterSource(int x, int y, int z) {
        return null;
    }

    /**
     * 让该位置的流体在 {@code delay} tick 后重新结算（原版
     * {@code LevelAccessor#scheduleTick(BlockPos, Fluid, int)}，经由
     * {@code ScheduledTickAccess}）。
     *
     * <p>上游的 {@code SpringFeature} 在写下流体后会调它 —— 少了这一步，泉水在生成阶段
     * 不会立刻铺开，要等玩家靠近才慢慢流。
     *
     * <p><b>为什么参数是流体类型 id 而不是整段状态</b>：上游传的是
     * {@code state.getType()}，也就是**流体类型**（{@code minecraft:lava}）；JSON 里的
     * {@code falling: true} 之类的属性属于状态的另一维，不影响这里。所以
     * {@code "minecraft:lava"} 无论流动与否都映射到同一个 {@code Fluids.LAVA}。
     *
     * <p>默认实现**记账**（{@code fluid_tick:<id>}）而不是静默丢弃：流体不结算属于
     * 「看得见但不一定立刻察觉」的失真，必须让运维能在降级账本里看到。
     */
    default void scheduleFluidTick(String fluidId, int x, int y, int z, int delay) {
        WorldgenWarnings.degrade("fluid_tick:" + fluidId,
                "WorldView 不支持 scheduleFluidTick，流体 " + fluidId + " 的更新被丢弃");
    }

    /**
     * 上游最常用的那种调用：把「该位置现在的方块」的流体类型、{@code delay = 0} 立刻排一次。
     *
     * <p>对应 {@code level.scheduleTick(pos, state.getType(), 0)} —— {@code state} 就是刚写下去的
     * 那个方块，所以流体类型从 {@link #fluidAt} 读（真实 {@code getFluidState()}），**不靠猜**。
     * 非流体（{@code minecraft:empty}）会落到 {@link #scheduleFluidTick(String, int, int, int, int)}
     * 的「未知流体」分支并记账 —— 与上游对 EMPTY 排一次 tick 的效果一致（原版也会忽略）。
     */
    default void scheduleFluidTick(int x, int y, int z) {
        String fluidId = fluidAt(x, y, z);
        if (fluidId == null) {
            WorldgenWarnings.degrade("fluid_tick:unknown",
                    "读不到该位置的流体类型，流体 tick 被丢弃（" + x + "," + y + "," + z + "）");
            return;
        }
        scheduleFluidTick(fluidId, x, y, z, 0);
    }

    /**
     * 让该位置进入区块的「放置后处理」队列（原版
     * {@code ChunkAccess#markPosForPostProcessing(BlockPos)}）。
     *
     * <p>两个上游调用点都要它：
     * <ul>
     *   <li>幽匿脉（{@code MultifaceGrowthFeature}）写完方块后 —— 否则相邻面不会立刻重算；</li>
     *   <li>{@code wover:mark_postprocessing} 特征本体（上游
     *       {@code MarkPostProcessingFeature#place} 只做这一件事，然后返回 {@code true}）。</li>
     * </ul>
     *
     * <p>生产实现在 {@link RegionView}（→ {@code RegionBlockAccess}，真实
     * {@code WorldGenLevel#getChunk(BlockPos).markPosForPostProcessing(pos)}）。
     *
     * <p>默认实现**记账**（{@code post_process}）而不是静默丢弃：拿不到桥接时这个世界会缺后处理，
     * 属于「看得见但不一定立刻察觉」的失真，必须能在降级账本里查到（与
     * {@link #scheduleFluidTick(String, int, int, int, int)} 的默认实现同一口径）。
     */
    default void markForPostProcessing(int x, int y, int z) {
        WorldgenWarnings.degrade("post_process",
                "WorldView 不支持 markForPostProcessing，后处理标记被丢弃（" + x + "," + y + "," + z + "）");
    }

    /**
     * 该位置的**碰撞形状是否填满整个方块**（原版
     * {@code BlockState#isCollisionShapeFullBlock(BlockGetter, BlockPos)}，
     * 即 {@code Block.isShapeFullBlock(getCollisionShape(...))}）。
     *
     * <p>这是 {@code wover:full_shape} 的真实语义（WoVer
     * {@code IsFullShape#test}：{@code level.getBlockState(pos.offset(offset)).isCollisionShapeFullBlock(level, pos)}）。
     * 判据是**碰撞形状的体积**，不是「有没有碰撞」：
     * <ul>
     *   <li>整块石头/地狱岩 → {@code true}；</li>
     *   <li>台阶、楼梯、栅栏、墙、灵魂沙（15/16 高）→ {@code false}。</li>
     * </ul>
     * 所以它**不能**退化成 {@link #isAir} 的反面或「非空气」：在熔岩坑口，台阶与楼梯必须算「没填满」，
     * 否则 {@code flooded_lava_pit_surface} 的 {@code is_basin} 会把岩浆灌到台阶围出来的坑里。
     *
     * @return {@code null} = 问不出来（越界 / 非服务端世界 / 形状读邻居时越界）
     */
    default Boolean fullShape(int x, int y, int z) {
        return null;
    }

    /**
     * 以 {@code (x,z)} 为中心、水平半径 {@code reach} 的**全高**柱体里，是否与一座下界城市的
     * 结构 pieces 相交（上游 {@code betternether:avoid_nether_city}）。
     *
     * <p>盒子的 y 范围就是 {@link #minY()}..{@link #maxY()}（上游 {@code CityStructure#buildingsWithin}
     * 用的正是 {@code level.getMinY()..getMaxY()}），并且**必须先按可读区域裁剪**：本移植不许为了
     * 判定去加载区域外的区块，所以只读这一圈里真正在手里的区块的结构引用（与上游
     * {@code WorldGenRegion#hasChunk} 那道守卫同一口径）。
     *
     * @param reach      特征从这个位置最多能横向写出多远（上游 {@code 0..64}）
     * @param includeCave {@code true} = 连城市的洞穴一起避开；{@code false} = 只避建筑。
     *                    本移植的城市是数据包 jigsaw（{@code mumu:nether_city} /
     *                    legacy {@code betternether:nether_city}），它的 piece 全都是城市本身，
     *                    没有单独的洞穴 piece，因此两种取值在这里等价 —— 这是**结构数据**的结论，
     *                    不是判定上的近似。
     *                    <p>「城市」只认这两个结构 id；其它命名空间里同名但无关的结构不算城市。
     * @return {@code true} = 会撞上城市（调用方丢弃该位置）；{@code false} = 不会；
     *         {@code null} = 问不出来（不是 {@code WorldGenRegion}、结构注册表不可用等）
     */
    default Boolean netherCityWithin(int x, int z, int reach, boolean includeCave) {
        return null;
    }
}
