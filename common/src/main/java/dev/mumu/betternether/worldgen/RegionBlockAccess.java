package dev.mumu.betternether.worldgen;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.bukkit.util.BlockStateUtils;
import net.momirealms.craftengine.core.block.BlockStateWrapper;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.WorldAccessor;
import net.momirealms.craftengine.core.world.WorldHeight;
import org.bukkit.block.data.BlockData;
import org.bukkit.generator.LimitedRegion;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

/**
 * {@link LimitedRegion} ↔ CraftEngine {@link WorldAccessor} 的适配器，以及四处「问真实语义」的入口。
 *
 * <h2>为什么需要它</h2>
 * CE 的方块状态自带三个我们一直在近似的东西：{@code replaceable()}、
 * {@code canSurvive(WorldAccessor, BlockPos)}、{@code hasTag(Key)}。它们是**方块状态自己的方法**，
 * 只要能把「世界」包成 CE 认识的 {@link WorldAccessor}，就能拿到真实答案，不用猜材质名单。
 *
 * <h2>为什么这个适配器是精确的</h2>
 * {@code canSurvive} 需要读邻居。它不是读我们自己的 {@link WorldView}，而是读一个真正的
 * {@code LevelReader}。这里拿到的正是**本区块正在生成的那个 {@code WorldGenLevel}**：
 * <pre>
 * LimitedRegion → org.bukkit.craftbukkit.generator.CraftLimitedRegion#getHandle()
 *               → net.minecraft.world.level.WorldGenLevel     （javap 实测，public）
 * </pre>
 * 也就是说功能读取的是 staging 区块本身（我们这一遍刚写进去的方块它立刻看得见），
 * 而不是已提交的 {@code ServerLevel}。这一点很关键：换成 {@code ServerLevel} 会读到上一版地形，
 * 同一遍里放下的方块对 {@code canSurvive} 不可见。
 *
 * <h2>不做什么</h2>
 * <ul>
 *   <li><b>不用反射</b>：全部是 public 的 CER/Papper-NMS 调用（CE jar 与 mucore-26.3.jar 的
 *       {@code javap} 输出为准，见各方法注释）。</li>
 *   <li><b>不猜</b>：任何一个环节拿不到答案就返回 {@code null}，由 {@link BlockPredicates} 记进
 *       降级账本并告警；这里绝不返回一个「看起来合理」的默认值。</li>
 * </ul>
 *
 * <h2>方块身份：可读，而且给出的是 CE id（源码级证据）</h2>
 * 早先我按「CE 借原版宿主状态」推断「deceive 模式下读不回 CE 身份」，那是**错的**。
 * CE 26.10 的真实结构是「**服务端留自定义方块，客户端才看借用状态**」：
 * <pre>
 * core/…/block/AbstractBlockManager.java:  DelegatingBlock[] customBlocks;  DelegatingBlockState[] customBlockStates;
 *                                          ImmutableBlockState[] immutableBlockStates;  visualBlockStateAllocator;
 * bukkit/…/block/BukkitBlockManager.java#registerServerSideCustomBlocks:
 *     DelegatingBlock customBlock = BlockGenerator.generateBlock(customBlockId);
 *     RegistryProxy.registerForHolder(BuiltInRegistries.BLOCK, identifier, customBlock);   // 用 CE 的 id 注册进原版 BLOCK 注册表
 *     DelegatingBlockState newBlockState = (DelegatingBlockState) BlockProxy.getDefaultBlockState(customBlock);
 *     IdMapperProxy.add(BlockProxy.BLOCK_STATE_REGISTRY, newBlockState);                   // 自定义状态有全局状态 id
 * </pre>
 * 所以 {@code BlockStateUtils.getBlockOwnerIdFromState(state)}（= {@code BuiltInRegistries.BLOCK.getKey(state.getBlock())}）
 * 对自定义方块返回的就是 **CE id**；{@code getOptionalCustomBlockState} 的
 * {@code instanceof DelegatingBlockState} 分支在真实世界里也成立（世界里存的是自定义方块，
 * 借用状态只在发包时用）。{@code CraftEngineBlocks#getBukkitBlockData} 取的是
 * {@code customBlockState().minecraftState()}，即自定义状态本身。
 * 结论：{@link #stateId} 对 CE 方块与原版方块都是精确身份，可以直接按 id 比较。
 */
final class RegionBlockAccess implements WorldAccessor {
    /** tag 是否存在查过一次就缓存：注册表在运行期不会变。 */
    private static final java.util.Map<String, Boolean> TAG_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    private final LimitedRegion region;
    private final WorldGenLevel level;
    private final WorldHeight worldHeight;

    private RegionBlockAccess(LimitedRegion region, WorldGenLevel level, WorldHeight worldHeight) {
        this.region = region;
        this.level = level;
        this.worldHeight = worldHeight;
    }

    /**
     * 建适配器；拿不到 {@code WorldGenLevel} 时返回 {@code null}（调用方据此降级并告警）。
     *
     * @param minY   世界最低 y
     * @param height 世界层数（{@code maxY - minY + 1}）
     */
    static @Nullable RegionBlockAccess create(@Nullable LimitedRegion region, int minY, int height) {
        if (region == null) {
            reportOnce("region-null", "放置管线没有拿到 LimitedRegion");
            return null;
        }
        WorldGenLevel level;
        try {
            if (!(region instanceof org.bukkit.craftbukkit.generator.CraftLimitedRegion craftRegion)) {
                reportOnce("region-not-craft",
                        "LimitedRegion 不是 CraftLimitedRegion（" + region.getClass().getName()
                                + "），无法取得 staging WorldGenLevel");
                return null;
            }
            // javap（mucore-26.3.jar）：public net.minecraft.world.level.WorldGenLevel getHandle()
            level = craftRegion.getHandle();
        } catch (RuntimeException | LinkageError error) {
            reportOnce("region-handle", "无法取得 LimitedRegion 的 WorldGenLevel：" + error);
            return null;
        }
        if (level == null) {
            reportOnce("region-handle-null", "CraftLimitedRegion#getHandle() 返回 null");
            return null;
        }
        // javap：WorldHeight.create(int, int) → WorldHeightImpl(int minBuildHeight, int height)
        return new RegionBlockAccess(region, level, WorldHeight.create(minY, height));
    }

    /** 本适配器背后的 staging level；{@code motionBlockingHeight} 也从这里问。 */
    WorldGenLevel level() {
        return level;
    }

    // ---------------------------------------------------------------- WorldAccessor

    /**
     * 读一个位置的状态。直接委托给 staging level：原版 {@code WorldGenRegion#getBlockState}
     * 对「不可写/未加载」的邻居返回空气，这正是上游 {@code canSurvive} 看到的东西。
     * 自己写一套越界裁剪反而会和原版不一致。
     */
    @Override
    public BlockStateWrapper getBlockState(int x, int y, int z) {
        return BlockStateUtils.toBlockStateWrapper(level.getBlockState(new net.minecraft.core.BlockPos(x, y, z)));
    }

    @Override
    public Key getNoiseBiome(int x, int y, int z) {
        // canSurvive 的少数实现会问群系；这里给的是世界真实群系。
        try {
            net.minecraft.world.level.biome.Biome biome =
                    level.getBiome(new net.minecraft.core.BlockPos(x, y, z)).value();
            Identifier id = level.registryAccess().lookupOrThrow(Registries.BIOME).getKey(biome);
            return Key.of(id == null ? "minecraft:the_nether" : id.toString());
        } catch (RuntimeException | LinkageError error) {
            reportOnce("noise-biome", "无法读取群系：" + error);
            return Key.of("minecraft:the_nether");
        }
    }

    @Override
    public void setBlockState(int x, int y, int z, BlockStateWrapper wrapper, int flags) {
        // flags 没有对应物：LimitedRegion 只有 setBlockData（= 原版 flag 3 的语义，
        // 与上游世界生成时的 setBlock(..., SET_SILENT) 效果一致 —— 都不触发方块更新）。
        region.setBlockData(x, y, z, (BlockData) wrapper.platformState());
    }

    @Override
    public WorldHeight worldHeight() {
        return worldHeight;
    }

    @Override
    public Object minecraftWorld() {
        return level;
    }

    @Override
    public Object generatingWorld() {
        // CE 的 BlockStateWrapper#canSurvive 优先用 generatingWorld()，回退 minecraftWorld()。
        // 世界生成阶段两者就是同一个 staging level。
        return level;
    }

    // ---------------------------------------------------------------- 四处真实语义

    /**
     * 方块身份 id：自定义方块给 CE id，原版给原版注册名。**两条分支都精确**（见类注释的证据）。
     */
    static @Nullable String stateId(@Nullable BlockData data) {
        if (data == null) {
            return null;
        }
        try {
            ImmutableBlockState custom = CraftEngineBlocks.getCustomBlockState(data);
            if (custom != null && custom.customBlockState() != null) {
                return custom.customBlockState().ownerId().asString();
            }
            return BlockStateUtils.getBlockOwnerIdFromData(data).asString();
        } catch (RuntimeException | LinkageError error) {
            reportOnce("state-id", "无法解析方块身份：" + error);
            return null;
        }
    }

    /**
     * 读 CE 自定义方块的属性值（序列化名）。
     *
     * <p>链路（javap 实测）：{@code CraftEngineBlocks#getCustomBlockState(BlockData)} →
     * {@code ImmutableBlockState#getProperty(String)}（不存在返回 null）→
     * {@code ImmutableBlockState#get(Property)} → {@code Property#valueName(value)}。
     * 原版方块返回 {@code null} —— 不拿 NMS 属性表去凑。
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static @Nullable String propertyAt(@Nullable BlockData data, @Nullable String property) {
        if (data == null || property == null || property.isEmpty()) {
            return null;
        }
        try {
            ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(data);
            if (state == null) {
                return null;
            }
            // 泛型上界是 <T extends Comparable<T>>，属性名运行时才知道，只能走 raw Property。
            net.momirealms.craftengine.core.block.property.Property raw = state.getProperty(property);
            if (raw == null) {
                return null;
            }
            Object value = state.get(raw);
            return value == null ? null : raw.valueName((Comparable) value);
        } catch (RuntimeException | LinkageError error) {
            reportOnce("property", "无法读取方块属性：" + error);
            return null;
        }
    }

    /** 真实 {@code canBeReplaced()}。    /** 真实 {@code canBeReplaced()}。{@code null} = 问不出来。 */
    static @Nullable Boolean replaceable(@Nullable BlockData data) {
        if (data == null) {
            return null;
        }
        try {
            return BlockStateUtils.toBlockStateWrapper(data).replaceable();
        } catch (RuntimeException | LinkageError error) {
            reportOnce("replaceable", "无法查询 canBeReplaced：" + error);
            return null;
        }
    }

    /**
     * 真实 {@code canSurvive}：把 {@code data} 放在 {@code (x,y,z)} 会不会被立刻移除。
     *
     * <p>三个前置条件缺一不可：有适配器（= 能拿到 staging level）、能解析出目标状态、
     * CE 里有对应的行为实现。任何一环缺失都返回 {@code null}，不返回「能活」。
     */
    static @Nullable Boolean wouldSurvive(@Nullable RegionBlockAccess access, @Nullable BlockData data,
                                          int x, int y, int z) {
        if (access == null || data == null) {
            return null;
        }
        try {
            BlockStateWrapper target = intendedState(data);
            if (target == null) {
                return null;
            }
            return target.canSurvive(access, new BlockPos(x, y, z));
        } catch (RuntimeException | LinkageError error) {
            reportOnce("can-survive", "无法查询 canSurvive：" + error);
            return null;
        }
    }

    /**
     * 真实 tag 归属；tag 在服务端注册表里**不存在**时返回 {@code null}。
     *
     * <p>为什么必须区分「不存在」和「不在 tag 里」：wover 的
     * {@code wover:surfaces/nether/terrain} 之类来自上游数据包，本移植没有加载。
     * 如果把它们当作「不在 tag 里」返回 false，一整类地面就会静默失效。
     */
    static @Nullable Boolean inTag(@Nullable RegionBlockAccess access, @Nullable BlockData data,
                                   @Nullable String tag) {
        if (access == null || data == null || tag == null || tag.isEmpty()) {
            return null;
        }
        try {
            if (!tagExists(access.level, tag)) {
                return null;
            }
            return BlockStateUtils.isTag(data, Key.of(tag));
        } catch (RuntimeException | LinkageError error) {
            reportOnce("in-tag", "无法查询方块 tag：" + error);
            return null;
        }
    }

    /**
     * tag 在运行期注册表里是否真的存在。
     *
     * <p>{@code Registry#getTags()} 返回注册表里全部已加载的 tag（javap 实测），
     * 用它判断存在性；不能用 {@code getTagOrEmpty(...).iterator().hasNext()} ——
     * 「存在但为空」与「不存在」会混为一谈。
     */
    private static boolean tagExists(WorldGenLevel level, String tag) {
        Boolean cached = TAG_CACHE.get(tag);
        if (cached != null) {
            return cached;
        }
        boolean exists;
        try {
            Registry<Block> blocks = level.registryAccess().lookupOrThrow(Registries.BLOCK);
            TagKey<Block> key = TagKey.create(Registries.BLOCK, Identifier.parse(tag));
            try (Stream<net.minecraft.core.HolderSet.Named<Block>> tags = blocks.getTags()) {
                exists = tags.anyMatch(named -> named.key().equals(key));
            }
        } catch (RuntimeException | LinkageError error) {
            // 这次问不出来就别缓存，下个区块可能就好了。
            reportOnce("tag-exists", "无法判断 tag 是否存在：" + error);
            return false;
        }
        TAG_CACHE.put(tag, exists);
        return exists;
    }

    /** 要放置的那个状态：自定义方块走 CE 的自定义状态（它带自己的 canSurvive 行为），否则走原版状态。 */
    private static @Nullable BlockStateWrapper intendedState(BlockData data) {
        ImmutableBlockState custom = CraftEngineBlocks.getCustomBlockState(data);
        if (custom != null && custom.customBlockState() != null) {
            return custom.customBlockState();
        }
        return BlockStateUtils.toBlockStateWrapper(data);
    }

    /**
     * 真实流体身份（{@code minecraft:empty} / {@code water} / {@code lava}）。
     *
     * <p>比「材质是不是 LAVA/WATER」精确：含水方块的真实流体是水，但它的材质是别的方块。
     */
    static @Nullable String fluidId(@Nullable BlockData data) {
        if (data == null) {
            return null;
        }
        try {
            return BlockStateUtils.toBlockStateWrapper(data).fluidState().asString();
        } catch (RuntimeException | LinkageError error) {
            reportOnce("fluid", "无法查询流体状态：" + error);
            return null;
        }
    }

    /**
     * 真实 {@code isFaceSturdy}：{@code face} 这一面能不能贴东西。
     *
     * <p>直接调 NMS —— 这一步不需要 CE：{@code BlockStateBase#isFaceSturdy(BlockGetter,
     * BlockPos, Direction)}（javap 实测），而 {@link WorldGenLevel} 本身就是 {@code BlockGetter}，
     * 读的是本区块 staging 数据。
     */
    static @Nullable Boolean faceSturdy(@Nullable RegionBlockAccess access, int x, int y, int z,
                                        Point.Direction face) {
        if (access == null || face == null) {
            return null;
        }
        try {
            net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(x, y, z);
            return access.level.getBlockState(pos).isFaceSturdy(
                    access.level, pos, net.minecraft.core.Direction.valueOf(face.name()));
        } catch (RuntimeException | LinkageError error) {
            reportOnce("sturdy", "无法查询 isFaceSturdy：" + error);
            return null;
        }
    }

    /**
     * 真实 {@code BlockState#liquid()}（javap 实测：{@code BlockBehaviour$BlockStateBase#liquid()}）。
     *
     * <p>直接问 NMS 状态，**不是** {@code getFluidState()}：含水实心方块（waterlogged 台阶/栅栏）
     * 的流体是水源，但 {@code liquid()} 为 false。wover 的
     * {@code BlockHelper.isFreeOrFluid = isAir() || liquid()} 就以这一条为准。
     *
     * <p><b>已核实的两点</b>（javap）：① 字节码是 {@code return this.liquid;}，即方块构造时缓存的
     * 「这个方块是不是流体方块」标志（方块级、非状态级）；② 该方法在本版本带 {@code @Deprecated}
     * （与 {@code isSolid()} 一起），但**上游 wover 用的正是它**，语义仍成立，所以照上游使用并在
     * 此处记录，不用别的近似替代。
     */
    static @Nullable Boolean liquidAt(@Nullable RegionBlockAccess access, int x, int y, int z) {
        if (access == null) {
            return null;
        }
        try {
            return access.level.getBlockState(new net.minecraft.core.BlockPos(x, y, z)).liquid();
        } catch (RuntimeException | LinkageError error) {
            reportOnce("liquid", "无法查询 BlockState#liquid()：" + error);
            return null;
        }
    }

    /**
     * 真实 {@code isFaceFull(collisionShape, dir)}。
     *
     * <p>{@code Block.isFaceFull(VoxelShape, Direction)} 与
     * {@code BlockStateBase#getCollisionShape(BlockGetter, BlockPos)} 都是 javap 实测的公开入口；
     * 形状按本区块 staging 数据求，半砖/楼梯这类「支撑面不满、碰撞面满」的方块才与
     * {@code faceSturdy} 有区别。
     */
    static @Nullable Boolean faceFullCollision(@Nullable RegionBlockAccess access, int x, int y, int z,
                                               Point.Direction face) {
        if (access == null || face == null) {
            return null;
        }
        try {
            net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(x, y, z);
            net.minecraft.core.Direction direction = net.minecraft.core.Direction.valueOf(face.name());
            return net.minecraft.world.level.block.Block.isFaceFull(
                    access.level.getBlockState(pos).getCollisionShape(access.level, pos), direction);
        } catch (RuntimeException | LinkageError error) {
            reportOnce("face-full", "无法查询 isFaceFull(collision)：" + error);
            return null;
        }
    }

    /**
     * 真实水源判定：{@code getFluidState().isSourceOfType(Fluids.WATER)}（javap 实测）。
     *
     * <p>注意与 {@link #fluidId} 的区别：流动水与水源的 fluid id 都是 {@code minecraft:water}，
     * 只有 {@code isSource()} 这一维能区分。
     */
    static @Nullable Boolean waterSource(@Nullable RegionBlockAccess access, int x, int y, int z) {
        if (access == null) {
            return null;
        }
        try {
            net.minecraft.world.level.material.FluidState fluids =
                    access.level.getBlockState(new net.minecraft.core.BlockPos(x, y, z)).getFluidState();
            return fluids.isSourceOfType(net.minecraft.world.level.material.Fluids.WATER);
        } catch (RuntimeException | LinkageError error) {
            reportOnce("water-source", "无法查询流体水源状态：" + error);
            return null;
        }
    }

    /**
     * 真实 {@code BlockState#isCollisionShapeFullBlock(BlockGetter, BlockPos)}（{@code wover:full_shape}）。
     *
     * <p>等价于 {@code Block.isShapeFullBlock(getCollisionShape(level, pos))}：**碰撞形状的体积**
     * 是否盖满整格。整块石头 true，台阶/楼梯/栅栏/墙/灵魂沙 false —— 这是上游
     * {@code IsFullShape#test} 的判据，不是「有没有碰撞」。
     *
     * <p>有些形状会读邻居（栅栏、墙、楼梯连成一体的判定），所以那条调用可能摸到 region 之外；
     * 越界时抛出的异常在这里被捕获并记账，返回 {@code null} 让谓词降级判假（不猜成「满」）。
     */
    static @Nullable Boolean fullShape(@Nullable RegionBlockAccess access, int x, int y, int z) {
        if (access == null) {
            return null;
        }
        try {
            net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(x, y, z);
            return access.level.getBlockState(pos).isCollisionShapeFullBlock(access.level, pos);
        } catch (RuntimeException | LinkageError error) {
            reportOnce("full-shape", "无法查询 isCollisionShapeFullBlock：" + error);
            return null;
        }
    }

    /**
     * {@code betternether:avoid_nether_city} 的真实判定：以 {@code (x,z)} 为中心、水平半径
     * {@code reach}、y 覆盖整个世界的柱体里，是否与下界城市的结构 piece 相交。
     *
     * <h2>链路（全部 public，无反射；javap 实测 mucore-26.3.jar）</h2>
     * <pre>
     * WorldGenLevel                        （= CraftLimitedRegion#getHandle() 的 staging level）
     *   └─ instanceof WorldGenRegion       （不是就当「问不出来」——ServerLevel 上取 chunk 会生成区块）
     *        hasChunk(cx, cz)              只碰手里的区块
     *        getChunk(cx, cz, STRUCTURE_REFERENCES).getAllReferences()
     *        getChunk(sx, sz, STRUCTURE_STARTS).getStartForStructure(structure)
     *        StructureStart#isValid / getPieces → StructurePiece#getBoundingBox#intersects(footprint)
     * </pre>
     *
     * <h2>与上游逐条对齐的点</h2>
     * <ul>
     *   <li>盒子：{@code [x-reach, minY, z-reach] .. [x+reach, maxY, z+reach]}，y 用世界的
     *       {@code getMinY()/getMaxY()}（{@code CityStructure#buildingsWithin} 同款）；</li>
     *   <li>**只读可读区域**：先问 {@code hasChunk}，再取引用；引用指向的 start 区块同样先问
     *       {@code hasChunk}（上游注释写明「邻居可能引用一个比 region 手里更远一格的 start」，
     *       那里它也是跳过，而不是去加载）；</li>
     *   <li>同一个 {@code StructureStart} 只判一次（{@code IdentityHashMap} 去重），因为多个区块的
     *       引用会指向同一个 start。</li>
     * </ul>
     *
     * <h2>城市身份</h2>
     * **只认两个 id**：{@code mumu:nether_city}（本移植注册的新城市）与
     * {@code betternether:nether_city}（legacy 数据包）。不按 path 通配其它命名空间，
     * 否则别的数据包里同名但无关的结构也会被当成城市（理由见 {@link #NETHER_CITY_IDS}）。
     *
     * <h2>include_cave</h2>
     * 上游用它区分 {@code CityPiece}（建筑）与整座城市（含洞穴）。本移植的城市是数据包
     * jigsaw，它的 piece 全部属于城市本身（没有单独的洞穴 piece 类型），所以两种取值在这里
     * 等价 —— 这是**结构数据**的结论，不是判定上的近似。
     *
     * @return {@code true} 会撞上城市；{@code false} 不会；{@code null} 问不出来
     */
    static @Nullable Boolean netherCityWithin(@Nullable RegionBlockAccess access, int x, int z,
                                              int reach, boolean includeCave) {
        if (access == null) {
            return null;
        }
        if (!(access.level instanceof net.minecraft.server.level.WorldGenRegion region)) {
            // ServerLevel 上 getChunk(x, z, status) 会**生成**区块，而 Folia 也不许这么读。
            reportOnce("city-not-region",
                    "WorldGenLevel 不是 WorldGenRegion（" + access.level.getClass().getName()
                            + "），无法在不加载区块的前提下读结构 starts");
            return null;
        }
        try {
            int radius = Math.max(reach, 0);
            int minY = region.getMinY();
            int maxY = region.getMaxY();
            net.minecraft.world.level.levelgen.structure.BoundingBox footprint =
                    new net.minecraft.world.level.levelgen.structure.BoundingBox(
                            x - radius, minY, z - radius, x + radius, maxY, z + radius);
            int minChunkX = net.minecraft.core.SectionPos.blockToSectionCoord(x - radius);
            int maxChunkX = net.minecraft.core.SectionPos.blockToSectionCoord(x + radius);
            int minChunkZ = net.minecraft.core.SectionPos.blockToSectionCoord(z - radius);
            int maxChunkZ = net.minecraft.core.SectionPos.blockToSectionCoord(z + radius);

            Set<net.minecraft.world.level.levelgen.structure.StructureStart> seen =
                    java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                    if (!region.hasChunk(chunkX, chunkZ)) {
                        continue;
                    }
                    net.minecraft.world.level.chunk.ChunkAccess chunk = region.getChunk(
                            chunkX, chunkZ,
                            net.minecraft.world.level.chunk.status.ChunkStatus.STRUCTURE_REFERENCES);
                    if (chunk == null) {
                        continue;
                    }
                    for (java.util.Map.Entry<net.minecraft.world.level.levelgen.structure.Structure,
                            it.unimi.dsi.fastutil.longs.LongSet> entry
                            : chunk.getAllReferences().entrySet()) {
                        if (!isNetherCity(access, entry.getKey())) {
                            continue;
                        }
                        for (long reference : entry.getValue()) {
                            net.minecraft.world.level.ChunkPos startChunk =
                                    net.minecraft.world.level.ChunkPos.unpack(reference);
                            if (!region.hasChunk(startChunk.x(), startChunk.z())) {
                                continue;
                            }
                            net.minecraft.world.level.chunk.ChunkAccess startChunkAccess =
                                    region.getChunk(startChunk.x(), startChunk.z(),
                                            net.minecraft.world.level.chunk.status.ChunkStatus.STRUCTURE_STARTS);
                            net.minecraft.world.level.levelgen.structure.StructureStart start =
                                    startChunkAccess == null ? null
                                            : startChunkAccess.getStartForStructure(entry.getKey());
                            if (start == null || !start.isValid() || !seen.add(start)) {
                                continue;
                            }
                            // includeCave 见方法注释：本地城市的 piece 全是城市本身（没有单独的
                            // 洞穴 piece 类型），所以「只避建筑」与「连洞穴一起避」判的是同一批 piece。
                            for (net.minecraft.world.level.levelgen.structure.StructurePiece piece
                                    : start.getPieces()) {
                                if (piece.getBoundingBox().intersects(footprint)) {
                                    return true;
                                }
                            }
                        }
                    }
                }
            }
            return false;
        } catch (RuntimeException | LinkageError error) {
            reportOnce("city-query", "无法读取结构 starts/引用：" + error);
            return null;
        }
    }

    /**
     * 该结构是不是我们要避的下界城市。
     *
     * <p>**只认这两个 id**：{@code mumu:nether_city}（本移植注册的新城市）与
     * {@code betternether:nether_city}（legacy 数据包）。刻意**不**按「path 等于 nether_city」
     * 通配所有命名空间：别的数据包（或别的模组搬运）完全可能注册一个同名但形状/尺度不同的
     * 结构，用通配会把它们的区域也当成城市，于是落叶在正常区域被大面积丢弃 —— 这是「多认一个」
     * 才会出现的错，比「少认一个」更难发现。
     */
    private static final java.util.Set<String> NETHER_CITY_IDS = java.util.Set.of(
            "mumu:nether_city",
            "betternether:nether_city");

    private static boolean isNetherCity(RegionBlockAccess access,
                                        net.minecraft.world.level.levelgen.structure.Structure structure) {
        Identifier id = access.level.registryAccess()
                .lookupOrThrow(Registries.STRUCTURE).getKey(structure);
        return id != null && NETHER_CITY_IDS.contains(id.toString());
    }

    /**
     * 真实 {@code ChunkAccess#markPosForPostProcessing(BlockPos)}。
     *
     * <p>两个前提（已在 {@code RegionView} 里保证）：位置必须在 region 内（越界 chunk 会抛），
     * 且只能在 populator 的同步窗口里用 —— {@code CraftLimitedRegion} 的 level 是
     * {@code WeakReference}，{@code breakLink()} 之后失效，所以这里**不缓存** chunk。
     */
    static boolean markForPostProcessing(@Nullable RegionBlockAccess access, int x, int y, int z) {
        if (access == null) {
            return false;
        }
        try {
            net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(x, y, z);
            access.level.getChunk(pos).markPosForPostProcessing(pos);
            return true;
        } catch (RuntimeException | LinkageError error) {
            reportOnce("post-process", "无法标记后处理：" + error);
            return false;
        }
    }

    /**
     * 真实 {@code LevelAccessor#scheduleTick(BlockPos, Fluid, int)}（经 {@code ScheduledTickAccess}）。
     *
     * <p>生命周期与 {@link #markForPostProcessing} 完全一样：位置必须在 region 内（越界会抛），
     * 且只能在 populator 的同步窗口里调 —— {@code CraftLimitedRegion} 的 level 是
     * {@code WeakReference}，{@code breakLink()} 之后失效，所以这里**不缓存** level。
     *
     * <p>只认流体**类型** id（与上游 {@code state.getType()} 一致）：{@code falling: true}
     * 这类属性是状态的另一维，不改变类型。别的一律返回 false 并记账，不猜。
     */
    static boolean scheduleFluidTick(@Nullable RegionBlockAccess access, @Nullable String fluidId,
                                     int x, int y, int z, int delay) {
        if (access == null) {
            return false;
        }
        net.minecraft.world.level.material.Fluid fluid = fluidOf(fluidId);
        if (fluid == null) {
            reportOnce("fluid-tick:" + fluidId, "未知流体类型 id：" + fluidId + "（只认 lava/water）");
            return false;
        }
        try {
            access.level.scheduleTick(new net.minecraft.core.BlockPos(x, y, z), fluid, delay);
            return true;
        } catch (RuntimeException | LinkageError error) {
            reportOnce("fluid-tick-call", "无法调度流体 tick：" + error);
            return false;
        }
    }

    /** {@code minecraft:lava} → {@code Fluids.LAVA}；其它返回 null（上游只会有这两种）。 */
    private static net.minecraft.world.level.material.@Nullable Fluid fluidOf(@Nullable String fluidId) {
        if (fluidId == null || fluidId.isEmpty()) {
            return null;
        }
        return switch (fluidId) {
            case "minecraft:lava" -> net.minecraft.world.level.material.Fluids.LAVA;
            case "minecraft:water" -> net.minecraft.world.level.material.Fluids.WATER;
            default -> null;
        };
    }

    // ---------------------------------------------------------------- 告警

    static void reportOnce(String key, String message) {
        WorldgenWarnings.degrade("nms:" + key,
                "真实方块语义不可用（" + key + "）：" + message + " —— 相关谓词会退回近似判据。");
    }

    /** 已报过的问题（与谓词降级共用同一本账，key 带 {@code nms:} 前缀）。 */
    static Set<String> reported() {
        return WorldgenWarnings.degraded();
    }
}
