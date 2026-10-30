package dev.mumu.betternether.worldgen;

import com.google.gson.JsonObject;
import dev.mumu.betternether.terrain.CraftEngineLookup;
import org.bukkit.block.data.BlockData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 上游五棵树/巨菇的算法逐行移植：
 * {@code MushroomFirFeature}、{@code WartTreeFeature}、{@code WillowTreeFeature}、
 * {@code BigBrownMushroomFeature}，以及 {@code OldWillowTree}（远古柳）。
 *
 * <h2>为什么必须搬代码而不是拼 JSON</h2>
 * 上一版用「圆盘 / 圆柱 + 随机叶子」去逼近这四棵树，结论是：形状不同、随机数序列不同，
 * 同一个 seed 生成出的是另一片森林。这四棵树的形状全部由「先抽一个高度 → 沿高度分段换状态
 * → 每格单独判定分支」构成，没有任何一种 {@code random_patch} / {@code block_column}
 * 组合能等价表达：
 * <ul>
 *   <li>{@code mushroom_fir} 的分支是 <b>两段</b>（下半段 {@code side_big_*}、上半段
 *       {@code side_small_*}}），每个 y 层抽 <b>4 次</b> {@code nextBoolean} 决定四个侧面，
 *       而且「北面朝外的形状是 {@code SIDE_BIG_S}」这种交叉命名是上游原样；</li>
 *   <li>{@code wart_tree} 是 5×5 见方的实心柱 + 地下根 + 只能在「两条对角线以外」摆的伞盖，
 *       每格是否落种子由 {@code nextInt(8)==0} 决定，伞盖每格再抽 <b>4 次</b>
 *       {@code nextInt(6)}；</li>
 *   <li>{@code willow_tree} 是递归枝条生长（{@code level > 5} 截止），每次转向抽
 *       {@code nextInt(3)}，叶子朝向由「这次往哪个方向走」决定；</li>
 *   <li>{@code big_brown_mushroom} 只有一根柄 + 一个 3×3 的伞面，但**净空判定**是
 *       「柄顶那一层的 3×3 全是空气」，少了这条就会在洞里长出半顶菇。</li>
 * </ul>
 *
 * <h2>随机数：个数与顺序都必须是上游的</h2>
 * 每个 {@code world.random()} 调用的位置与上游源码一一对应，包括三种容易被「优化掉」的情况：
 * <ul>
 *   <li>{@code if (random.nextBoolean()) { if (empty) set(...) }} —— 抽签<b>先</b>发生，
 *       与「这里空不空」无关。写成 {@code if (empty && random.nextBoolean())} 会让同一个 seed
 *       产生另一棵树（{@code mushroom_fir} 的两段分支、{@code willow_tree} 的每一处）。</li>
 *   <li>{@code random.nextInt(3) > 0 ? preDir : random.nextBoolean() ? ... : ...} ——
 *       只有 {@code nextInt(3) == 0} 时才抽那次 {@code nextBoolean}，短路必须保留。</li>
 *   <li>{@code wart_tree} 的 {@code nextInt(8)} / {@code nextInt(6)} 只在该格可覆盖时抽，
 *       所以「先铺满再抽签」这种重排会改掉整串随机数。</li>
 * </ul>
 * 注：上游这条链上的随机源是 {@code WorldgenRandom} → {@code LegacyRandomSource}
 * → {@code BitRandomSource} 的默认方法，本移植用 {@link Random}。
 * **已用字节码逐条核对**（本机 {@code E:\我的世界插件\folia测试端\versions\26.3\mucore-26.3.jar}，
 * {@code javap -p -c}）：
 * <pre>
 * BitRandomSource.nextBoolean()  = next(1) != 0            // 消耗 1 bit
 * BitRandomSource.nextFloat()    = next(24) * 5.9604645E-8f // 消耗 24 bit
 * BitRandomSource.nextInt(bound) = next(31) + 2 的幂快路径 + 拒绝采样（与 JDK 同一算法）
 * BitRandomSource.nextInt()      = next(32)
 * LegacyRandomSource.next(int)   = (seed*25214903917+11 & 281474976710655) >>> (48-bits)
 * WorldgenRandom.next(int)       = count++ 后转投 LegacyRandomSource.next(bits)
 * </pre>
 * {@code java.util.Random} 的同名三个方法反汇编后**位数与公式完全相同**
 * （{@code nextBoolean} 同为 {@code next(1) != 0}、{@code nextFloat} 同为 {@code next(24) * 5.9604645E-8f}、
 * {@code nextInt(bound)} 同为 {@code next(31)} 起的同一套算法），LGC 常数也一致，
 * 所以同一个 seed 下 {@code nextInt/nextInt(bound)/nextBoolean/nextFloat} 逐次取值相同。
 *
 * <p><b>不要写「{@code nextBoolean} ≡ {@code nextFloat() < 0.5}」这种适配</b>：前者消耗 1 bit、
 * 后者消耗 24 bit，一次调用就会把整条随机流错位（这是本类每个 {@code nextBoolean} 都直接用
 * {@link Random#nextBoolean()} 的原因）。唯一**不**共享的是 {@code nextGaussian}
 * （上游走 {@code MarsagliaPolarGaussian}），本类的五棵树都不用它。
 *
 * <h2>{@code MAX_HEIGHT} 的换算</h2>
 * 上游 {@code ContextFeature#place} 传 {@code generator.getGenDepth()}（下界 0..127 → 128），
 * 这里取 {@code world.maxY() - world.minY() + 1}，本移植的生成器同样 {@code minY = 0}、
 * 天花板 {@code 127}，因此同样得 128。{@code mushroom_fir} 的
 * {@code RANDOM_BOUND = (int)(5 * MAX_HEIGHT / 128)} 直接吃这个数，按 256 算会得到 10 而不是 5。
 *
 * <h2>与 CraftEngine 的两处硬约束（每条都在使用处再标一次）</h2>
 * <ol>
 *   <li><b>状态属性</b>：上游这些方块的状态属性（{@code mushroom_fir_trunk} 的
 *       {@code shape}、{@code willow_trunk} 的 {@code shape}、{@code willow_leaves} 的
 *       {@code facing}/{@code persistent}、{@code wart_seed} 的 {@code facing}、
 *       {@code brown_large_mushroom} 的 {@code shape}、{@code nether_mycelium} 的
 *       {@code blue}）在当前 CE 包里多半**没有定义**。代码仍按上游属性名原样请求
 *       （{@link CraftEngineLookup#lookup(String, Map)} 不支持时退回默认状态），
 *       但**不再静默当它支持**：{@link #verify} 反查解析结果，属性没落地就记一条
 *       {@link #loadNotes()} 报告，写明「哪个方块的哪个属性丢了」。</li>
 *   <li><b>按方块身份比较（已精确）</b>：一律用 {@link WorldView#blockId} 比 CE 注册名，
 *       这就是上游 {@code state.getBlock() == X} 的逐字对应。CE 把自定义方块用**自己的 id**
 *       注册进原版方块注册表（{@code BukkitBlockManager#registerServerSideCustomBlocks} →
 *       {@code registerForHolder(BuiltInRegistries.BLOCK, identifier, customBlock)}），
 *       {@code getBukkitBlockData} 取的是 {@code customBlockState().minecraftState()}；
 *       {@code auto_state} 池只决定**发包时的外观**，不影响世界里的方块。所以
 *       {@code blockId} 读回 {@code betternether:wart_log} 而不是宿主 id。
 *       地面判据 = 原版真实 tag（{@code minecraft:base_stone_nether} /
 *       {@code minecraft:soul_fire_base_blocks} / {@code minecraft:nylium}）+ 自定义 id 集合；
 *       可替换性 = {@link BlockPredicates#replaceable}（真实 {@code canBeReplaced()}）+ 上游显式 id 集合。
 *       <b>问不到 id 时（越界 / CE 未就绪）一律按「不是」处理</b>，绝不退回材质猜测，
 *       也绝不把未知当实心。</li>
 * </ol>
 *
 * <h2>写区（{@code WriteZone}）怎么落到 {@link WorldView} 上</h2>
 * {@code OldWillowTree} 是唯一需要「把几何塞进写区」的一棵：它的树冠半径 {@code 10*branchSize}
 * 从树干最多伸出 14 格，而写区只有当前区块的 3×3。上游的做法（WorldWeaver {@code release/26.3}
 * {@code wover-feature-api/.../feature/api/WriteZone.java}，已实读）是：
 * <ul>
 *   <li>{@code WriteZone.of(level)}：非 {@code WorldGenRegion} → {@link Zone#UNBOUNDED}；
 *       否则<b>按区块探测</b>写半径（{@code isWithinWriteZone}，最多 8 区块），得到区块对齐的
 *       水平框；</li>
 *   <li>{@code headroom(cx,cz)} = 中心到框四边的最小距离（钳到 ≥0），
 *       {@code fitRadius(cx,cz,r,minR)} = {@code min(r, headroom)}，若 {@code headroom < minR} 返回
 *       {@code -1}（整根枝跳过）；</li>
 *   <li>{@code toBoundingBox(minY,maxY)} = 该水平框 + 调用方给的 y 范围，
 *       之后所有写/读都用 {@code isInside} 裁切。</li>
 * </ul>
 * 本移植把 {@code of(level)} 换成「探测 {@link WorldView#inRegion}」—— 那是本移植里
 * 「这个坐标可读可写吗」的<b>唯一</b>权威判据（{@code RegionView} → {@code LimitedRegion}），
 * 所以语义等价：两者都是「区块对齐的水平框」，探测粒度、上限（8 区块）与 {@code headroom}/
 * {@code fitRadius} 的算术逐行照抄。{@link WorldView} 不需要任何新方法。
 *
 * <h2>越界行为</h2>
 * 三档分开，与上游一一对应：
 * <ul>
 *   <li><b>写</b>：{@link #set} 先问 {@link WorldView#inRegion}，越界直接丢（{@code RegionView}
 *       里还会再判一次）；带 {@code bounds} 的写（树冠叶子、柳枝、挂墙植物）额外要过
 *       {@code Box#isInside}，即「y 也要在 {@code [minY+1, maxY-1]} 之内」。</li>
 *   <li><b>读</b>：{@link #empty}/{@link #materialAt} 对越界坐标返回「不空 / {@code null}」，
 *       绝不假装读到空气 —— 上游 26.3 会把越界读当成错误，读出来的空值会改变树形，
 *       所以「拒绝这个候选」才是保真的选择（{@code crown} 里那道
 *       {@code isInsideHorizontally} 守卫就是为了在<b>读之前</b>丢掉越界列）。</li>
 *   <li><b>拟合</b>：能塞下就塞（{@code fitRadius}），塞不下就整根枝跳过 —— 不做「先画再裁」，
 *       因为裁出来的树冠是平切的墙。</li>
 * </ul>
 *
 * <h2>仍未表达（不要当成已完成）</h2>
 * <ul>
 *   <li>上游 {@code setWithUpdate} / {@code setWithoutUpdate} 的「是否触发邻居更新」区别：
 *       {@link WorldView#setBlock} 只有一个写口，两者在这里等价。</li>
 *   <li><b>不调用 {@code would_survive}</b>：上游这五棵树都是直接 {@code setWithUpdate} 覆盖，
 *       没有 {@code canSurvive} 判定；加了会改变与上游一致的放置结果。</li>
 * </ul>
 *
 * <h2>接线约定</h2>
 * 本类不注册任何东西、不复制调度：{@code count} / {@code height_range} / {@code rarity_filter}
 * 全部由装载器按上游 {@code placed_feature} 的 JSON 执行。装载器按 configured-feature 的
 * {@code type} 询问 {@link #supportedTypes()}，命中就调 {@link #create(String, JsonObject)}。
 */
public final class UpstreamSimpleTreeFeatures {
    /**
     * 本类负责的 configured-feature type 全名（来源 {@code registry/NetherFeatures.java} 与
     * {@code src/main/generated/.../worldgen/feature/*.json} 的 {@code type} 字段）。
     */
    private static final Set<String> SUPPORTED = Set.of(
            "betternether:mushroom_fir",
            "betternether:wart_tree",
            "betternether:willow_tree",
            "betternether:old_willow_tree",
            "betternether:big_brown_mushroom"
    );

    /** 装载期发现的问题（方块缺失、属性未落地…）。装载器可以并入自己的 notes。 */
    private static final Set<String> NOTES = Collections.synchronizedSet(new LinkedHashSet<>());

    private UpstreamSimpleTreeFeatures() {
    }

    /** 本类支持的 type 全名。装载器据此决定是否继续 skip。 */
    public static Set<String> supportedTypes() {
        return SUPPORTED;
    }

    /** 装载期发现的问题。空表 = 每个方块状态都按上游属性名解析成功。 */
    public static List<String> loadNotes() {
        return List.copyOf(NOTES);
    }

    // ---------------------------------------------------------------- 入口

    /**
     * 建一个特征；{@code null} 表示这个 type 不归本类管（装载器应继续走自己的分支）。
     *
     * <p>{@code json} 是<b>原始</b> feature JSON：只有 {@code wart_tree} 需要读
     * {@code distance} / {@code natural}（上游 {@code NaturalTreeConfiguration} 的两个字段），
     * 另外三个是 {@code MapCodec.unit(...)}，配置是空的。
     */
    public static @Nullable Feature create(String configuredType, @Nullable JsonObject json) {
        return create(configuredType, json, UpstreamSimpleTreeFeatures::resolve);
    }

    /** 运行期解析：CE 自定义方块走 {@link CraftEngineLookup}，{@code minecraft:} 走原版材质。 */
    private static @Nullable BlockData resolve(String id, Map<String, String> properties) {
        return CraftEngineLookup.lookup(id, properties);
    }

    /**
     * 同上，但方块解析可注入 —— 单元测试用它避开 CraftEngine 与服务器注册表。
     *
     * <p>{@link UpstreamVegetationFeatures.BlockResolver} 是复用的：装载器只需要一个 resolver
     * 就能同时喂给植被与树这两套移植。
     */
    public static @Nullable Feature create(String configuredType, @Nullable JsonObject json,
                                           UpstreamVegetationFeatures.BlockResolver resolver) {
        Feature.Custom.Placer placer = switch (configuredType) {
            case "betternether:mushroom_fir" -> mushroomFir(resolver);
            case "betternether:wart_tree" -> wartTree(resolver, json);
            case "betternether:willow_tree" -> willowTree(resolver);
            case "betternether:old_willow_tree" -> oldWillowTree(resolver, json);
            case "betternether:big_brown_mushroom" -> bigBrownMushroom(resolver);
            default -> null;
        };
        return placer == null ? null : new Feature.Custom(configuredType, placer);
    }

    // ---------------------------------------------------------------- 方块状态解析 + 属性核验

    private static void note(String message) {
        NOTES.add(message);
    }

    /** 只发一次（{@link #NOTES} 是集合）：哪几棵树依赖「按材质认自定义方块」。 */

    /**
     * 解析 {@code id + 属性}。
     *
     * <p>{@code properties} 是 {@code 键, 值, 键, 值…} 的成对数组（调用点全是常量，不做 Map 分配）。
     * 返回 {@code null} 表示方块本身解析不出来 —— 调用方据此让整棵树放弃，并已记过一条报告。
     */
    private static @Nullable BlockData state(UpstreamVegetationFeatures.BlockResolver resolver, String id,
                                             String... properties) {
        Map<String, String> requested = properties(properties);
        BlockData data = resolver.resolve(id, requested);
        if (data == null) {
            note("方块无法解析：" + id + "（缺这个方块的那棵树会整棵不放置，而不是长成半截）");
            return null;
        }
        verify(resolver, data, id, requested);
        return data;
    }

    /**
     * 同 {@link #state}，但缺方块时**不**报告。
     *
     * <p>用于只拿来「比对身份」或「锦上添花」的可选方块（{@code wart_tree} 的可替换集合、灵魂地面的
     * tag 成员、{@code old_willow_tree} 的挂墙植物、清场用的空气）：少一个只会让判定变窄或让装饰
     * 缺席，不是这棵树缺材料，报成缺失会让运维去追一个并不存在的问题。属性核验仍然生效 ——
     * 属性没落地是必须报的。
     */
    private static @Nullable BlockData optionalState(UpstreamVegetationFeatures.BlockResolver resolver, String id,
                                                     String... properties) {
        Map<String, String> requested = properties(properties);
        BlockData data = resolver.resolve(id, requested);
        if (data == null) {
            return null;
        }
        verify(resolver, data, id, requested);
        return data;
    }

    /** {@code 键, 值, 键, 值…} → {@link LinkedHashMap}；顺序即核验顺序（报告里的先后要稳定）。 */
    private static Map<String, String> properties(String... properties) {
        if ((properties.length & 1) != 0) {
            throw new IllegalArgumentException("属性必须是 键/值 成对");
        }
        Map<String, String> requested = new LinkedHashMap<>();
        for (int i = 0; i < properties.length; i += 2) {
            requested.put(properties[i], properties[i + 1]);
        }
        return requested;
    }

    /**
     * 属性核验：CE 对「这个方块不认识的属性」是**静默退回默认状态**的
     * （见 {@link CraftEngineLookup#lookup(String, Map)}），于是 {@code shape=side_big_n}
     * 会无声地变成「整棵树都是同一个方块」，控制台一个字都没有。
     *
     * <p>判定走 {@link UpstreamVegetationFeatures.BlockResolver#missingProperties}，**不能**
     * 解析 {@code BlockData#getAsString()} —— 后者返回的是**原版方块状态**
     * （{@code craftengine:custom_8520}），CE 的内部属性名本来就不在里面，
     * 拿它比对会把每个属性都判成缺失（线上实测刷了 60 条假 WARN，而缓存里
     * {@code [shape=bottom] → 8520} 明明是对的）。
     *
     * <p>核验失败<b>不改</b>放置行为 —— 少一个属性只是外观退化，比整棵树消失好；
     * 但这条报告必须存在，否则就是「静默当它支持」。
     */
    private static void verify(UpstreamVegetationFeatures.BlockResolver resolver, BlockData data,
                               String id, Map<String, String> requested) {
        if (requested.isEmpty()) {
            return;
        }
        for (String missing : resolver.missingProperties(id, requested)) {
            note("属性未被该方块定义，已退回默认状态：" + id + "[" + missing + "="
                    + requested.getOrDefault(missing, "?") + "]"
                    + "（该档形状/朝向会丢失，需要在 CE 包里补上这个属性）");
        }
    }

    // ---------------------------------------------------------------- 共用工具

    /** 上游 {@code MAX_HEIGHT}：本生成器 {@code minY == 0}，所以等于 128。 */
    private static int depth(WorldView world) {
        return world.maxY() - world.minY() + 1;
    }

    /** 上游 {@code world.isEmptyBlock}。越界按「不空」处理：绝不在未知区域写方块。 */
    private static boolean empty(WorldView world, int x, int y, int z) {
        return world.inRegion(x, y, z) && world.isAir(x, y, z);
    }

    /**
     * 上游 {@code state.getBlock() == X} 的逐字对应：比较**真实身份** {@link WorldView#blockId}，
     * 问不到（越界 / CE 未就绪）时才退回已解析状态的材质比较。
     *
     * <p>为什么可行（源码级证据）：CE 把自定义方块注册进原版方块的注册表
     * （{@code BukkitBlockManager#registerServerSideCustomBlocks} 用 CE 自己的 id 调
     * {@code RegistryProxy.registerForHolder(BuiltInRegistries.BLOCK, identifier, customBlock)}），
     * {@code getBukkitBlockData} 取的是 {@code customBlockState().minecraftState()}；
     * {@code auto_state} 池只决定**发包时的外观**，不决定世界里的方块。所以自定义方块的
     * {@code blockId} 读回来就是 CE id（{@code betternether:wart_log}），不是宿主 id。
     */
    private static boolean isBlock(WorldView world, int x, int y, int z, String id) {
        return id.equals(world.blockId(x, y, z));
    }

    /** 同上，但问的是「属于这一组 id 吗」。{@code blockId} 问不到 → {@code false}（未知不当已知）。 */
    private static boolean isBlockIn(WorldView world, int x, int y, int z, Set<String> ids) {
        final String actual = world.blockId(x, y, z);
        return actual != null && ids.contains(actual);
    }

    private static void set(WorldView world, int x, int y, int z, BlockData data) {
        if (!world.inRegion(x, y, z)) {
            return;
        }
        world.setBlock(x, y, z, data);
    }

    /** 逐行照抄 {@code BlocksHelper.upRay}：从 {@code y} 往上数连续空气，最多 {@code maxDist} 次探测。 */
    private static int upRay(WorldView world, int x, int y, int z, int maxDist) {
        int length = 0;
        for (int j = 1; j < maxDist && empty(world, x, y + j, z); j++) {
            length++;
        }
        return length;
    }

    /**
     * 一个方块坐标，作用域仅限 {@link #cover} 的集合。
     *
     * <p>为什么不用 {@link Point}：{@code cover} 的随机数消耗顺序取决于 {@code HashSet} 的
     * <b>迭代顺序</b>，而迭代顺序由 {@code hashCode} 决定。上游用的是 {@code BlockPos}，
     * 它的 {@code hashCode} 是 {@code (y + z*31)*31 + x}；这里照抄，迭代顺序才与上游一致。
     */
    private record Pos(int x, int y, int z) {
        @Override
        public int hashCode() {
            return (y + z * 31) * 31 + x;
        }

        Pos relative(int dx, int dy, int dz) {
            return new Pos(x + dx, y + dy, z + dz);
        }

        Pos relative(Point.Direction direction) {
            return new Pos(x + direction.dx(), y + direction.dy(), z + direction.dz());
        }

        Pos above() {
            return new Pos(x, y + 1, z);
        }

        Pos above(int distance) {
            return new Pos(x, y + distance, z);
        }

        Pos below() {
            return new Pos(x, y - 1, z);
        }

        Pos below(int distance) {
            return new Pos(x, y - distance, z);
        }
    }

    /**
     * WorldWeaver {@code WriteZone}（{@code release/26.3}，
     * {@code wover-feature-api/src/main/java/de/ambertation/wover/feature/api/WriteZone.java}，已实读）
     * 的水平写区，算术逐行照抄。
     *
     * <h2>{@code of(level)} → {@link #of(WorldView, int, int, int)}</h2>
     * 上游在 {@code WorldGenRegion} 上<b>按区块探测</b>写半径（{@code isWithinWriteZone}，上限
     * 8 区块），得到「中心区块 ± radius」的区块对齐框；非 {@code WorldGenRegion}（骨粉长树、
     * 结构世界、测试）返回 {@code UNBOUNDED}，此时所有 fit/clip 助手都是恒等变换。
     * 这里把探测对象换成 {@link WorldView#inRegion} —— 本移植里「可读可写吗」的唯一权威判据，
     * 语义与「写区」完全一致；四个方向各自探测（上游只探 +X，因为它假定写区是正方形，
     * 分别探测对非正方形写区反而更正确，且对正方形给出同一个框）。
     *
     * <p>「探到极远处就当无限制」这一招是安全的：运行期 {@code RegionView.inRegion} 落到
     * {@code CraftLimitedRegion#isInRegion}，字节码就是 {@code BoundingBox.contains(x,y,z)}
     * （纯比较，不抛异常，也不分配），离原版边界 3000 万格的那个探针只会返回 {@code false}。
     */
    record Zone(boolean unbounded, int minX, int minZ, int maxX, int maxZ) {
        /** 上游 {@code WriteZone.UNBOUNDED}：{@code Integer.MIN_VALUE/2 … Integer.MAX_VALUE/2}。 */
        static final Zone UNBOUNDED = new Zone(true,
                Integer.MIN_VALUE / 2, Integer.MIN_VALUE / 2,
                Integer.MAX_VALUE / 2, Integer.MAX_VALUE / 2);

        /** 上游 {@code MAX_PROBED_RADIUS = 8}：任何 chunk step 的写半径都不会超过 8 区块。 */
        private static final int MAX_PROBED_RADII = 8;

        /** 远超原版世界边界（±29 999 984）的探针，用来判断「这里根本没有写区限制」。 */
        private static final int UNBOUNDED_PROBE = 30_000_000;

        static Zone of(WorldView world, int x, int y, int z) {
            if (world.inRegion(x + UNBOUNDED_PROBE, y, z)) {
                return UNBOUNDED;
            }
            final int chunkX = x >> 4;
            final int chunkZ = z >> 4;
            int west = 0;
            while (west < MAX_PROBED_RADII && chunkIn(world, chunkX - west - 1, chunkZ, y)) {
                west++;
            }
            int east = 0;
            while (east < MAX_PROBED_RADII && chunkIn(world, chunkX + east + 1, chunkZ, y)) {
                east++;
            }
            int north = 0;
            while (north < MAX_PROBED_RADII && chunkIn(world, chunkX, chunkZ - north - 1, y)) {
                north++;
            }
            int south = 0;
            while (south < MAX_PROBED_RADII && chunkIn(world, chunkX, chunkZ + south + 1, y)) {
                south++;
            }
            return new Zone(false,
                    (chunkX - west) << 4, (chunkZ - north) << 4,
                    ((chunkX + east) << 4) + 15, ((chunkZ + south) << 4) + 15);
        }

        private static boolean chunkIn(WorldView world, int chunkX, int chunkZ, int y) {
            return world.inRegion(chunkX << 4, y, chunkZ << 4);
        }

        /** 上游 {@code contains(int,int)}：只看 X/Z。 */
        boolean contains(int x, int z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }

        /**
         * 上游 {@code headroom(int,int)}：中心到框四边的最小距离，钳到 {@code >= 0}。
         * {@code UNBOUNDED} 时返回 {@code Float.MAX_VALUE / 2}。
         */
        float headroom(int cx, int cz) {
            if (unbounded) {
                return Float.MAX_VALUE / 2;
            }
            final float room = Math.min(
                    Math.min((float) cx - minX, (float) maxX - cx),
                    Math.min((float) cz - minZ, (float) maxZ - cz));
            return Math.max(room, 0.0F);
        }

        /**
         * 上游 {@code fitRadius(int,int,float,float)}：{@code room < minRadius} → {@code -1}
         * （整根枝跳过），否则 {@code min(radius, room)}（有富余时原样返回，绝大多数树冠因此
         * 与不裁剪时完全相同）。
         */
        float fitRadius(int cx, int cz, float radius, float minRadius) {
            final float room = headroom(cx, cz);
            if (room < minRadius) {
                return -1.0F;
            }
            return Math.min(radius, room);
        }

        /** 上游 {@code toBoundingBox(minY, maxY)}：水平框 + 调用方给的 y 范围。 */
        Box toBox(int minY, int maxY) {
            return new Box(minX, minY, minZ, maxX, maxY, maxZ);
        }

        /**
         * 上游 {@code fitSegment(Vector3f start, Vector3f end, BlockPos origin, float radius)}：
         * 把一段树枝沿自身方向**缩短**到「整段胶囊都落在写区内」。
         *
         * <p>源码语义（{@code WriteZone.java}，已实读，逐行照抄）：
         * <ul>
         *   <li>无写区限制 → 原样返回 {@code end}；</li>
         *   <li>把写区按 {@code radius} 内缩成一个盒子（胶囊圆心允许活动的范围），起点已在盒外 →
         *       原样返回 {@code start}（这根枝没救）；</li>
         *   <li>否则对两个水平轴各求「还能走多远」（slab clip），{@code t = min(1, tX, tZ)}，
         *       返回 {@code start + t*(end-start)}。</li>
         * </ul>
         * <b>只收长度、不动方向</b>：把枝条重新指向「空间最大」的方向会毁掉放射状分枝，
         * 而那正是这类树看起来像树的原因。
         *
         * <p>{@code origin} 是「段的坐标是相对谁的」的那个原点（上游用树干位置），
         * 所以返回值仍是相对坐标，调用方直接拿去做偏移即可。
         */
        Vec3 fitSegment(Vec3 start, Vec3 end, int originX, int originZ, float radius) {
            if (unbounded) {
                return end;
            }
            final float loX = minX + radius;
            final float hiX = maxX - radius;
            final float loZ = minZ + radius;
            final float hiZ = maxZ - radius;

            final float sx = originX + start.x();
            final float sz = originZ + start.z();
            if (sx < loX || sx > hiX || sz < loZ || sz > hiZ) {
                return start;
            }

            final float ex = originX + end.x();
            final float ez = originZ + end.z();
            final float t = Math.min(1.0F, Math.min(axisLimit(sx, ex, loX, hiX), axisLimit(sz, ez, loZ, hiZ)));
            if (t >= 1.0F) {
                return end;
            }
            if (t <= 0.0F) {
                return start;
            }
            return new Vec3(
                    start.x() + (end.x() - start.x()) * t,
                    start.y() + (end.y() - start.y()) * t,
                    start.z() + (end.z() - start.z()) * t);
        }

        /**
         * 上游 {@code WriteZone#axisLimit}：沿 {@code s -> e} 走多远才离开 {@code [lo, hi]}。
         * 已知 {@code s} 在区间内；不移动时返回 {@code 1}（该轴不构成限制）。
         */
        private static float axisLimit(float s, float e, float lo, float hi) {
            final float d = e - s;
            if (d > 0.0F) {
                return (hi - s) / d;
            }
            if (d < 0.0F) {
                return (lo - s) / d;
            }
            return 1.0F;
        }
    }

    /**
     * 一个相对坐标（上游用 {@code org.joml.Vector3f}）。
     *
     * <p>本移植不引入 joml：{@link Zone#fitSegment} 只用到 x/y/z 三个分量与「原样返回/线性插值」，
     * 一个三字段 record 足够，也不会顺手把向量库带进世界生成的依赖面。
     */
    record Vec3(float x, float y, float z) {
    }

    /** 上游 {@code BoundingBox} 在本移植里被用到的两个判据（{@code isInside}）。 */
    record Box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        boolean isInside(int x, int y, int z) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
        }

        /** 上游 {@code BlocksHelper.isInsideHorizontally}：忽略 Y。 */
        boolean isInsideHorizontally(int x, int z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }
    }

    /**
     * 逐行照抄 {@code BlocksHelper.cover}：把一片 {@code ground} 方块随机换成 {@code cover}。
     *
     * <p>{@code mushroom_fir} 用它把树根周围的 {@code nether_mycelium} 染成蓝色变种。
     * 三个不能动的细节：
     * <ul>
     *   <li>半径迭代里每个点对 27 个偏移各抽一次 {@code nextBoolean}，
     *       只有抽中才再抽一次判定「邻居也是 ground」—— 两次抽签是嵌套的；</li>
     *   <li>{@code points2} 每轮结束并入 {@code points} 后清空，即「一层一圈」的扩散；</li>
     *   <li>写回时<b>不</b>判空：直接把已有 ground 换成变种。</li>
     * </ul>
     */
    private static void cover(WorldView world, Pos center, String groundId,
                              @Nullable BlockData cover, int radius, Random random) {
        if (cover == null) {
            return;
        }
        if (!isBlock(world, center.x(), center.y(), center.z(), groundId)) {
            return;
        }
        Set<Pos> points = new HashSet<>();
        Set<Pos> points2 = new HashSet<>();
        points.add(center);
        points2.add(center);
        for (int i = 0; i < radius; i++) {
            for (Pos pos : points) {
                for (int[] offset : COVER_OFFSETS) {
                    if (!random.nextBoolean()) {
                        continue;
                    }
                    Pos neighbor = pos.relative(offset[0], offset[1], offset[2]);
                    if (random.nextBoolean()
                            && isBlock(world, neighbor.x(), neighbor.y(), neighbor.z(), groundId)
                            && !points.contains(neighbor)) {
                        points2.add(neighbor);
                    }
                }
            }
            points.addAll(points2);
            points2.clear();
        }
        for (Pos pos : points) {
            set(world, pos.x(), pos.y(), pos.z(), cover);
        }
    }

    /** {@code BlocksHelper.OFFSETS} 的 27 个邻域，顺序是 {@code x, y, z} 三层嵌套（原样照抄）。 */
    private static final int[][] COVER_OFFSETS = {
            {-1, -1, -1}, {-1, -1, 0}, {-1, -1, 1},
            {-1, 0, -1}, {-1, 0, 0}, {-1, 0, 1},
            {-1, 1, -1}, {-1, 1, 0}, {-1, 1, 1},

            {0, -1, -1}, {0, -1, 0}, {0, -1, 1},
            {0, 0, -1}, {0, 0, 0}, {0, 0, 1},
            {0, 1, -1}, {0, 1, 0}, {0, 1, 1},

            {1, -1, -1}, {1, -1, 0}, {1, -1, 1},
            {1, 0, -1}, {1, 0, 0}, {1, 0, 1},
            {1, 1, -1}, {1, 1, 0}, {1, 1, 1}
    };

    /**
     * 上游 {@code BlocksHelper.HORIZONTAL}（betternether 自己那份）：<b>北、南、东、西</b>。
     *
     * <p>与 bclib 的 {@code BlocksHelper.makeHorizontal()}（北、东、南、西，顺时针）**不同**，
     * 两个类同名但数组不同；{@code WartTreeFeature} 与 {@code OldWillowTree} 导入的都是
     * betternether 的 {@code org.betterx.betternether.BlocksHelper}，所以这里用 N/S/E/W。
     * 顺序会进随机流（挂墙植物每个方向抽一次 {@code nextInt(8)}），不能换成顺时针。
     */
    private static final Point.Direction[] HORIZONTAL = {
            Point.Direction.NORTH, Point.Direction.SOUTH, Point.Direction.EAST, Point.Direction.WEST
    };

    /**
     * 上游 {@code BlocksHelper.isNetherGround}：{@code NETHER_STONES ∪ 灵魂地面 ∪ 菌丝 ∪ nylium}。
     *
     * <p>原版那三支用**真实 tag** 问（{@link WorldView#inTag}），免疫 CE 的
     * deceive-bukkit-material：{@code minecraft:base_stone_nether} = netherrack/blackstone/basalt，
     * {@code minecraft:soul_fire_base_blocks} = soul_sand/soul_soil（上游 {@code SOUL_GROUND} 的基表），
     * {@code minecraft:nylium} = 两种菌岩。
     *
     * <p>{@code custom} 是「自定义方块那一半」，不能省：上游在注册时把这些方块塞进了原版 tag
     * （{@code NetherTerrainBlocks} 给 {@code jungle_grass} / {@code mushroom_grass} /
     * {@code sepia_mushroom_grass} / {@code swampland_grass} 都传了 {@code BlockTags.NYLIUM}，
     * 生成的 {@code minecraft:nylium} 里就有这四条；菌丝走 wover 的 {@code NETHER_MYCELIUM}）。
     * 本移植的 CE 包不声明这些 tag，所以 {@code inTag("minecraft:nylium")} 对它们是
     * **真答案的 false**（tag 存在 → 不记账、不降级），只能按装载期解析好的状态比材质。
     * 若哪天 CE 方块配置补了 {@code tags: [minecraft:nylium]}，这一支自然被 tag 覆盖，
     * 留着也不会错。
     */
    private static boolean netherGround(WorldView world, int x, int y, int z) {
        if (!world.inRegion(x, y, z) || world.isAir(x, y, z)) {
            return false;
        }
        final Point point = new Point(x, y, z);
        if (NETHER_STONES.test(world, point) || SOUL_GROUND.test(world, point) || NYLIUM.test(world, point)) {
            return true;
        }
        return isBlockIn(world, x, y, z, NETHER_CUSTOM_GROUND_IDS);
    }

    /**
     * 上游 {@code isNetherGround} 里的自定义成员（id 全部逐字来自上游生成数据，不是猜的）。
     *
     * <p>为什么不能只靠 tag：wover 的地面 tag 是「基表 + 上游增量」两层，而 CE 包不声明这些 tag，
     * 服务端注册表里也没有 {@code wover:surfaces/*} —— {@code inTag} 对它们返回 {@code null}
     * （记账、不降级），{@code minecraft:nylium} 则给出「真答案的 false」。所以自定义方块必须按
     * 装载期解析好的状态比材质。成员来源两处：
     * <ul>
     *   <li>{@code data/wover/tags/block/surfaces/nether/stones.json}（wover 基表为
     *       {@code #minecraft:base_stone_nether}，上游增量加了下面 7 个里的 7 条）；</li>
     *   <li>{@code data/wover/tags/block/surfaces/nether/mycelium.json}（只有
     *       {@code betternether:nether_mycelium}，基表是空数组）。</li>
     * </ul>
     * 另外 {@code data/minecraft/tags/block/nylium.json} 的增量是四种草，已被 stones 的增量覆盖
     * （jungle/mushroom/sepia/swampland 两边都有，按并集写，重复无害）。
     *
     * <p><b>为什么这里硬编码而不是走 {@code BlockPredicates.Blocks(tag, resolved)}</b>：
     * 那条路要装载器把上游 tag 展开成具体的 id 列表，我在本地只看到一份**旧的生成产物**
     * （{@code plugin/build/generated/bn/worldgen-upstream/manifest.json}），主代理重建之前它不代表
     * 脚本真值 —— 拿它判断「有没有接线」会得出错误结论，所以这里不做任何关于 manifest 内容的断言。
     * 硬编码本身与脚本真值一致：{@code stones} 为 {@code #minecraft:base_stone_nether}(3) + 本表前 7 条 = 10，
     * {@code nylium} 为 2 种原版菌岩 + 本表里的 4 种草 = 6。
     *
     * <p><b>收缩计划</b>：等装载器重建出可用的已解析 tag 列表后，这张表可以换成
     * {@code BlockPredicates.Blocks("wover:surfaces/nether/stones", resolved)} ∪
     * {@code Blocks("wover:surfaces/nether/mycelium", resolved)}；收缩前先核对实际内容，
     * 不要凭生成产物的旧快照动手。
     */
    private static final Set<String> NETHER_CUSTOM_GROUND_IDS = Set.of(
            "betternether:ceiling_mushrooms",
            "betternether:jungle_grass",
            "betternether:mushroom_grass",
            "betternether:netherrack_moss",
            "betternether:sepia_mushroom_grass",
            "betternether:swampland_grass",
            "betternether:veined_gloomsculk",
            "betternether:nether_mycelium"
    );

    /** 疣木地面 tag 的增量成员（{@code wover:surfaces/soul_ground}），按真实身份比。 */
    private static final Set<String> WART_GROUND_IDS = Set.of(
            "betternether:farmland",
            "betternether:veined_sand"
    );

    /** 上游 {@code isWart} 认的两种方块（原版疣块 + 疣木原木）。 */
    private static final Set<String> WART_IDS = Set.of(
            "minecraft:nether_wart_block",
            "betternether:wart_log"
    );

    /** 疣木自己的方块：上游都没有 {@code replaceable} 标记，任何时候都不该被覆盖。 */
    private static final Set<String> WART_OWN_IDS = Set.of(
            "betternether:wart_roots",
            "betternether:wart_log",
            "minecraft:nether_wart_block"
    );

    /** 上游 {@code WartTreeFeature#isReplaceable} 里显式列出的六个方块。 */
    private static final Set<String> WART_REPLACEABLE_IDS = Set.of(
            "betternether:wart_seed",
            "betternether:black_bush",
            "betternether:soul_vein",
            "betternether:soul_lily",
            "betternether:soul_lily_sapling",
            "minecraft:nether_wart"
    );

    /** 远古柳自己的方块：树冠/垂枝/树干都不该被自己再覆盖。 */
    private static final Set<String> WILLOW_OWN_IDS = Set.of(
            "betternether:willow_bark",
            "betternether:willow_log",
            "betternether:willow_leaves",
            "betternether:willow_branch"
    );

    /** 原版 tag {@code minecraft:base_stone_nether}：netherrack / blackstone / basalt。 */
    private static final BlockPredicates.Predicate NETHER_STONES =
            new BlockPredicates.InTag("minecraft:base_stone_nether");

    /** 原版 tag {@code minecraft:soul_fire_base_blocks}：soul_sand / soul_soil。 */
    private static final BlockPredicates.Predicate SOUL_GROUND =
            new BlockPredicates.InTag("minecraft:soul_fire_base_blocks");

    /** 原版 tag {@code minecraft:nylium}：crimson / warped nylium。 */
    private static final BlockPredicates.Predicate NYLIUM =
            new BlockPredicates.InTag("minecraft:nylium");

    /**
     * {@code MushroomFirFeature} 的全部状态。
     *
     * <p>上游的状态常量集中在类头（{@code SIDE_BIG_N} … {@code BOTTOM}），这里一一对应。
     * 注意四个「交叉」：<b>放在北侧</b>的是 {@code SIDE_BIG_S}、南侧是 {@code SIDE_BIG_N}、
     * 东侧是 {@code SIDE_BIG_W}、西侧是 {@code SIDE_BIG_E} —— 上游就是这么写的
     * （{@code context.POS.north()} 配 {@code SIDE_BIG_S}），这里保留原样，字段名按
     * 「放在哪一侧」命名以免再读错。
     */
    private static Feature.Custom.Placer mushroomFir(UpstreamVegetationFeatures.BlockResolver resolver) {
        BlockData bottom = state(resolver, "betternether:mushroom_fir_trunk", "shape", "bottom");
        BlockData middle = state(resolver, "betternether:mushroom_fir_trunk", "shape", "middle");
        BlockData top = state(resolver, "betternether:mushroom_fir_trunk", "shape", "top");
        BlockData end = state(resolver, "betternether:mushroom_fir_trunk", "shape", "end");
        BlockData northBig = state(resolver, "betternether:mushroom_fir_trunk", "shape", "side_big_s");
        BlockData southBig = state(resolver, "betternether:mushroom_fir_trunk", "shape", "side_big_n");
        BlockData eastBig = state(resolver, "betternether:mushroom_fir_trunk", "shape", "side_big_w");
        BlockData westBig = state(resolver, "betternether:mushroom_fir_trunk", "shape", "side_big_e");
        BlockData northSmall = state(resolver, "betternether:mushroom_fir_trunk", "shape", "side_small_s");
        BlockData southSmall = state(resolver, "betternether:mushroom_fir_trunk", "shape", "side_small_n");
        BlockData eastSmall = state(resolver, "betternether:mushroom_fir_trunk", "shape", "side_small_w");
        BlockData westSmall = state(resolver, "betternether:mushroom_fir_trunk", "shape", "side_small_e");
        BlockData mycelium = state(resolver, "betternether:nether_mycelium");
        // 上游 BLUE_MYCELIUM = NETHER_MYCELIUM.defaultBlockState().setValue(IS_BLUE, true)，
        // 属性名来自 BlockNetherMycelium.IS_BLUE = BooleanProperty.create("blue")。
        BlockData blueMycelium = state(resolver, "betternether:nether_mycelium", "blue", "true");
        return new MushroomFir(bottom, middle, top, end, northBig, southBig, eastBig, westBig,
                northSmall, southSmall, eastSmall, westSmall, mycelium, blueMycelium);
    }

    /**
     * {@code MushroomFirFeature#place} + {@code #grow}。
     *
     * <p>形状：一根竖直的柄（下半 {@code MIDDLE}、上半 {@code TOP}、根 {@code BOTTOM}、
     * 顶 {@code END}），下段每层最多四面挂 {@code side_big_*}，上段每层最多四面挂
     * {@code side_small_*}，最后把脚下那圈菌丝染成蓝色。
     */
    static final class MushroomFir implements Feature.Custom.Placer {
        private final BlockData bottom;
        private final BlockData middle;
        private final BlockData top;
        private final BlockData end;
        private final BlockData northBig;
        private final BlockData southBig;
        private final BlockData eastBig;
        private final BlockData westBig;
        private final BlockData northSmall;
        private final BlockData southSmall;
        private final BlockData eastSmall;
        private final BlockData westSmall;
        private final BlockData mycelium;
        private final BlockData blueMycelium;
        /** 任何一个状态缺失都整棵放弃：半棵树比没有树更难查。 */
        private final boolean ready;

        MushroomFir(BlockData bottom, BlockData middle, BlockData top, BlockData end,
                    BlockData northBig, BlockData southBig, BlockData eastBig, BlockData westBig,
                    BlockData northSmall, BlockData southSmall, BlockData eastSmall, BlockData westSmall,
                    BlockData mycelium, BlockData blueMycelium) {
            this.bottom = bottom;
            this.middle = middle;
            this.top = top;
            this.end = end;
            this.northBig = northBig;
            this.southBig = southBig;
            this.eastBig = eastBig;
            this.westBig = westBig;
            this.northSmall = northSmall;
            this.southSmall = southSmall;
            this.eastSmall = eastSmall;
            this.westSmall = westSmall;
            this.mycelium = mycelium;
            this.blueMycelium = blueMycelium;
            this.ready = bottom != null && middle != null && top != null && end != null
                    && northBig != null && southBig != null && eastBig != null && westBig != null
                    && northSmall != null && southSmall != null && eastSmall != null && westSmall != null
                    && mycelium != null && blueMycelium != null;
        }

        @Override
        public boolean place(WorldView world, Point point) {
            if (!ready) {
                return false;
            }
            // 上游两道门槛：脚下属于 MYCELIUM tag，且是 NetherMycelium 这个方块。
            // CE 下两者都只剩「材质等于 nether_mycelium」（tag 无法枚举、方块身份被材质映射），
            // 所以合并成一次比较；蓝色变种是同一方块，材质相同，与上游 getBlock() 相等判断一致。
            if (!isBlock(world, point.x(), point.y() - 1, point.z(), "betternether:nether_mycelium")) {
                return false;
            }
            return grow(world, point, depth(world) / 128.0F);
        }

        private boolean grow(WorldView world, Point pos, float scale) {
            final Random random = world.random();
            final int randomBound = (int) (5 * scale);
            if (randomBound < 1) {
                // 上游这里会抛 IllegalArgumentException（nextInt(0)）。世界矮于 26 层时不是
                // 「换一种树」，而是生成直接崩；这里显式放弃并留一条报告。
                note("世界高度不足 26 层：mushroom_fir 的 nextInt(5*scale) 会退化成 nextInt(0)（上游会抛异常），此处放弃");
                return false;
            }

            int h = 3 + random.nextInt(randomBound);
            for (int y = 1; y < h; y++) {
                if (!empty(world, pos.x(), pos.y() + y, pos.z())) {
                    h = y;
                    break;
                }
            }
            if (h < 3) {
                return false;
            }

            set(world, pos.x(), pos.y(), pos.z(), bottom);
            int h2 = (h + 1) >> 1;
            final int absTop = h + pos.y();
            final int absMid = h2 + pos.y();
            for (int y = pos.y() + 1; y < absMid; y++) {
                set(world, pos.x(), y, pos.z(), middle);
            }
            for (int y = absMid; y < absTop; y++) {
                set(world, pos.x(), y, pos.z(), top);
            }
            final int absH3 = (absMid + absTop) >> 1;

            // 下段：粗枝。每层固定 4 次抽签（北→南→东→西），抽中才判空再写。
            for (int y = absMid - 1; y < absH3; y++) {
                if (random.nextBoolean() && empty(world, pos.x(), y, pos.z() - 1)) {
                    set(world, pos.x(), y, pos.z() - 1, northBig);
                }
                if (random.nextBoolean() && empty(world, pos.x(), y, pos.z() + 1)) {
                    set(world, pos.x(), y, pos.z() + 1, southBig);
                }
                if (random.nextBoolean() && empty(world, pos.x() + 1, y, pos.z())) {
                    set(world, pos.x() + 1, y, pos.z(), eastBig);
                }
                if (random.nextBoolean() && empty(world, pos.x() - 1, y, pos.z())) {
                    set(world, pos.x() - 1, y, pos.z(), westBig);
                }
            }
            // 上段：细枝。同样是每层 4 次抽签，只是换成 side_small_*。
            for (int y = absH3; y < absTop; y++) {
                if (random.nextBoolean() && empty(world, pos.x(), y, pos.z() - 1)) {
                    set(world, pos.x(), y, pos.z() - 1, northSmall);
                }
                if (random.nextBoolean() && empty(world, pos.x(), y, pos.z() + 1)) {
                    set(world, pos.x(), y, pos.z() + 1, southSmall);
                }
                if (random.nextBoolean() && empty(world, pos.x() + 1, y, pos.z())) {
                    set(world, pos.x() + 1, y, pos.z(), eastSmall);
                }
                if (random.nextBoolean() && empty(world, pos.x() - 1, y, pos.z())) {
                    set(world, pos.x() - 1, y, pos.z(), westSmall);
                }
            }
            if (empty(world, pos.x(), absTop, pos.z())) {
                set(world, pos.x(), absTop, pos.z(), end);
            }

            cover(world, new Pos(pos.x(), pos.y() - 1, pos.z()),
                    "betternether:nether_mycelium", blueMycelium, 5, random);
            return true;
        }
    }

    // ---------------------------------------------------------------- wart_tree

    /**
     * {@code WartTreeFeature}：灵魂沙上的疣木，方形实心柱 + 地下根 + 对角镂空的伞盖。
     *
     * <p>{@code NaturalTreeConfiguration} 的两个字段来自 feature JSON：
     * {@code natural}（默认 {@code true}）与 {@code distance}（默认 {@code 7}，
     * {@code manDist = ceil(distance * 1.5)}）。{@code natural} 在{@code WartTreeFeature}
     * 的代码路径里<b>没有被读过</b>（只有 {@code OldWillowTree} 用它区分「玩家骨粉」与自然生成），
     * 这里照上游读进来并记一条报告，而不是假装它在起作用。
     */
    private static Feature.Custom.Placer wartTree(UpstreamVegetationFeatures.BlockResolver resolver,
                                                  @Nullable JsonObject json) {
        BlockData root = state(resolver, "betternether:wart_roots");
        BlockData log = state(resolver, "betternether:wart_log");
        BlockData head = state(resolver, "minecraft:nether_wart_block");
        BlockData seedDown = state(resolver, "betternether:wart_seed", "facing", "down");
        BlockData seedUp = state(resolver, "betternether:wart_seed", "facing", "up");
        BlockData seedNorth = state(resolver, "betternether:wart_seed", "facing", "north");
        BlockData seedSouth = state(resolver, "betternether:wart_seed", "facing", "south");
        BlockData seedEast = state(resolver, "betternether:wart_seed", "facing", "east");
        BlockData seedWest = state(resolver, "betternether:wart_seed", "facing", "west");

        boolean natural = boolField(json, "natural", true);
        int distance = intField(json, "distance", 7);
        if (!natural) {
            note("wart_tree 读到 natural=false：该字段在 WartTreeFeature 的代码路径里没有被使用"
                    + "（上游只有 OldWillowTree 用它区分骨粉生长），放置行为不变");
        }
        return new WartTree(root, log, head,
                new BlockData[]{seedDown, seedUp, seedNorth, seedSouth, seedEast, seedWest},
                distance);
    }

    /**
     * {@code WartTreeFeature#grow} + {@code PlaceRandomSeed}，
     * 以及 {@code NonOverlappingFeature#place} 的两道门槛（地面 + 附近不能已有疣木）。
     */
    static final class WartTree implements Feature.Custom.Placer {
        private final BlockData root;
        private final BlockData log;
        private final BlockData head;
        private final BlockData seedDown;
        private final BlockData seedUp;
        private final BlockData seedNorth;
        private final BlockData seedSouth;
        private final BlockData seedEast;
        private final BlockData seedWest;
        private final int distance;
        private final int manDist;
        private final boolean ready;

        WartTree(BlockData root, BlockData log, BlockData head, BlockData[] seeds,
                 int distance) {
            this.root = root;
            this.log = log;
            this.head = head;
            this.seedDown = seeds[0];
            this.seedUp = seeds[1];
            this.seedNorth = seeds[2];
            this.seedSouth = seeds[3];
            this.seedEast = seeds[4];
            this.seedWest = seeds[5];
            this.distance = distance;
            this.manDist = (int) Math.ceil(distance * 1.5);
            this.ready = root != null && log != null && head != null
                    && seedDown != null && seedUp != null && seedNorth != null
                    && seedSouth != null && seedEast != null && seedWest != null;
        }

        @Override
        public boolean place(WorldView world, Point point) {
            if (!ready) {
                return false;
            }
            if (!isGround(world, point.x(), point.y() - 1, point.z())) {
                return false;
            }
            if (!noObjNear(world, point)) {
                return false;
            }
            return grow(world, point);
        }

        /**
         * 上游 {@code isGround} → {@code BlocksHelper.isSoulSand}（{@code CommonBlockTags.SOUL_GROUND}）。
         *
         * <p>原版那一半（{@code soul_sand} / {@code soul_soil}）用真实 tag
         * {@code minecraft:soul_fire_base_blocks} 问，免疫材质伪装；tag 不存在时
         * {@link BlockPredicates.InTag} 会记一条降级并返回 false（不会把 null 当 false 静默吞掉）。
         * tag 的增量成员 {@code farmland} / {@code veined_sand} 是自定义方块，按材质比。
         */
        private boolean isGround(WorldView world, int x, int y, int z) {
            if (!world.inRegion(x, y, z)) {
                return false;
            }
            return SOUL_GROUND.test(world, new Point(x, y, z))
                    || isBlockIn(world, x, y, z, WART_GROUND_IDS);
        }

        /** {@code NonOverlappingFeature#noObjNear}：同层曼哈顿距离内不能再有一棵疣木。 */
        private boolean noObjNear(WorldView world, Point pos) {
            for (int x = pos.x() - distance; x <= pos.x() + distance; x++) {
                for (int z = pos.z() - distance; z <= pos.z() + distance; z++) {
                    if (Math.abs(x - pos.x()) + Math.abs(z - pos.z()) > manDist) {
                        continue;
                    }
                    if (isWart(world, x, pos.y(), z)) {
                        return false;
                    }
                }
            }
            return true;
        }

        private boolean grow(WorldView world, Point pos) {
            final Random random = world.random();
            final int x0 = pos.x();
            final int y0 = pos.y();
            final int z0 = pos.z();

            if (!empty(world, x0, y0 + 1, z0) || !empty(world, x0, y0 + 2, z0)) {
                return false;
            }
            // 净空：柱体上方第 2 层的四邻、第 3 层的二邻都必须是空气。
            if (!(empty(world, x0, y0 + 2, z0 - 1)
                    && empty(world, x0, y0 + 2, z0 + 1)
                    && empty(world, x0 + 1, y0 + 2, z0)
                    && empty(world, x0 - 1, y0 + 2, z0))) {
                // 逐行照抄：这道门失败时上游仍然 return true（「试过了」），不是 continue。
                return true;
            }
            if (!(empty(world, x0, y0 + 3, z0 - 2)
                    && empty(world, x0, y0 + 3, z0 + 2)
                    && empty(world, x0 + 2, y0 + 3, z0)
                    && empty(world, x0 - 2, y0 + 3, z0))) {
                return true;
            }

            int height = 5 + random.nextInt(5);
            int h2 = height - 1;
            int width = (height >>> 2) + 1;
            int offset = width >>> 1;
            List<Pos> seedBlocks = new ArrayList<>();

            // 柱体：width×width 见方，从 pos.y() 往上 height 格。
            for (int x = 0; x < width; x++) {
                int px = x + x0 - offset;
                for (int z = 0; z < width; z++) {
                    int pz = z + z0 - offset;
                    for (int y = 0; y < height; y++) {
                        int py = y + y0;
                        if (!replaceable(world, px, py, pz)) {
                            continue;
                        }
                        if (y == 0 && !replaceable(world, px, py - 1, pz)) {
                            set(world, px, py, pz, root);
                        } else if (y < h2) {
                            set(world, px, py, pz, log);
                        } else {
                            set(world, px, py, pz, head);
                        }
                        if (random.nextInt(8) == 0) {
                            Point.Direction dir = HORIZONTAL[random.nextInt(HORIZONTAL.length)];
                            seedBlocks.add(new Pos(px + dir.dx(), py + dir.dy(), pz + dir.dz()));
                        }
                    }
                }
            }

            // 地下根：同一柱体往下，遇到「下面也是可替换」就继续用 wart_log，否则收尾成根并停。
            for (int x = 0; x < width; x++) {
                int px = x + x0 - offset;
                for (int z = 0; z < width; z++) {
                    int pz = z + z0 - offset;
                    for (int y = 1; y < (height >> 1); y++) {
                        int py = y0 - y;
                        if (!replaceable(world, px, py, pz)) {
                            continue;
                        }
                        if (replaceable(world, px, py - 1, pz)) {
                            set(world, px, py, pz, log);
                        } else {
                            set(world, px, py, pz, root);
                            break;
                        }
                    }
                }
            }

            // 伞盖：width+2 见方，跳过两条对角线；每格只在空气里写，并抽 4 次 nextInt(6) 撒种子。
            int headWidth = width + 2;
            offset++;
            int headY = height - width - 1 + y0;
            for (int x = 0; x < headWidth; x++) {
                int px = x + x0 - offset;
                for (int z = 0; z < headWidth; z++) {
                    if (x == z || x == (headWidth - z - 1)) {
                        continue;
                    }
                    int pz = z + z0 - offset;
                    for (int y = 0; y < width; y++) {
                        int py = y + headY;
                        if (!empty(world, px, py, pz)) {
                            continue;
                        }
                        set(world, px, py, pz, head);
                        for (int i = 0; i < 4; i++) {
                            // 上游是 Direction.values()[nextInt(6)]，即 下、上、北、南、西、东。
                            Point.Direction dir = Point.Direction.values()[random.nextInt(6)];
                            seedBlocks.add(new Pos(px + dir.dx(), py + dir.dy(), pz + dir.dz()));
                        }
                    }
                }
            }

            for (Pos seed : seedBlocks) {
                placeRandomSeed(world, seed);
            }
            return true;
        }

        /** {@code WartTreeFeature#PlaceRandomSeed}：贴着疣木块的那一面决定 {@code facing}（含交叉）。 */
        private void placeRandomSeed(WorldView world, Pos pos) {
            if (!replaceable(world, pos.x(), pos.y(), pos.z())) {
                return;
            }
            BlockData seed = seedUp;
            if (isWart(world, pos.x(), pos.y() + 1, pos.z())) {
                seed = seedDown;
            } else if (isWart(world, pos.x(), pos.y() - 1, pos.z())) {
                seed = seedUp;
            } else if (isWart(world, pos.x(), pos.y(), pos.z() - 1)) {
                seed = seedSouth;
            } else if (isWart(world, pos.x(), pos.y(), pos.z() + 1)) {
                seed = seedNorth;
            } else if (isWart(world, pos.x() + 1, pos.y(), pos.z())) {
                seed = seedWest;
            } else if (isWart(world, pos.x() - 1, pos.y(), pos.z())) {
                seed = seedEast;
            }
            set(world, pos.x(), pos.y(), pos.z(), seed);
        }

        /**
         * 上游 {@code isWart}：{@code state == WART_BLOCK || block == MAT_WART.getLog()}。
         *
         * <p>{@code WART_BLOCK} 是原版 {@code nether_wart_block} —— 原版身份可以用
         * {@link WorldView#blockId} 精确问（免疫 CE 伪装）；{@code wart_log} 是自定义方块，
         * 它的 {@code blockId} 读回来是宿主 id，所以只能按解析好的材质比。
         */
        private boolean isWart(WorldView world, int x, int y, int z) {
            // 逐字对应上游 state == WART_BLOCK || state.getBlock() == MAT_WART.getLog()。
            // 未知（blockId 问不到）按「不是疣块」处理：未知不当已知。
            return isBlockIn(world, x, y, z, WART_IDS);
        }

        /**
         * 上游 {@code isReplaceable}：{@code canBeReplaced() || AIR || 那六个方块}。
         *
         * <p>{@code canBeReplaced()} 走**真实**通道 {@link BlockPredicates#replaceable}
         * （{@link WorldView#replaceable} → CE 的 {@code canBeReplaced()}）；问不出来时它自带
         * 降级并记一条 {@link BlockPredicates#degraded()}，不会静默。
         * 上游额外显式列出的六个方块仍然并进去 —— 它们里有不满足 {@code canBeReplaced()} 的
         * （例如 {@code minecraft:nether_wart}），漏掉会让树干把本该覆盖的位置跳过去。
         */
        private boolean replaceable(WorldView world, int x, int y, int z) {
            if (empty(world, x, y, z)) {
                return true;
            }
            final String id = world.blockId(x, y, z);
            if (id != null && WART_OWN_IDS.contains(id)) {
                // 这棵树自己的方块上游都没有 replaceable 标记（BlockWartRoots 是
                // Properties.ofFullCopy(NETHER_WART_BLOCK) 的实心方块），所以先排掉：
                // 否则种子会把刚落下的树根/树干吃掉。
                return false;
            }
            if (BlockPredicates.replaceable(world, x, y, z)) {
                return true;
            }
            return isBlockIn(world, x, y, z, WART_REPLACEABLE_IDS);
        }

    }


    private static int intField(@Nullable JsonObject json, String name, int fallback) {
        if (json == null || !json.has(name)) {
            return fallback;
        }
        try {
            return json.get(name).getAsInt();
        } catch (RuntimeException error) {
            note("feature JSON 的 " + name + " 不是整数，退回默认值 " + fallback + "：" + error);
            return fallback;
        }
    }

    private static boolean boolField(@Nullable JsonObject json, String name, boolean fallback) {
        if (json == null || !json.has(name)) {
            return fallback;
        }
        try {
            return json.get(name).getAsBoolean();
        } catch (RuntimeException error) {
            note("feature JSON 的 " + name + " 不是布尔，退回默认值 " + fallback + "：" + error);
            return fallback;
        }
    }

    // ---------------------------------------------------------------- willow_tree

    /**
     * {@code WillowTreeFeature}：一根柳木柄 + 四个方向的递归柳枝 + 从枝条上垂下去的柳枝方块。
     *
     * <h2>「同源参数」指什么</h2>
     * 上游柳树的<b>分支参数</b>与 {@code OldWillowTree} 出自同一套源码常量：
     * 每根枝的初始段长 {@code 3 + nextInt(2)}、递归层数上限 {@code level > 5}、
     * 每层步数 {@code length * length}、转向概率 {@code nextInt(3)}、垂枝长度
     * {@code 1 + nextInt(4)}、垂枝分段 {@code MIDDLE}/{@code END}（{@code WillowBranchShape}）。
     * 这里逐项照抄，不做「看起来差不多」的替换。
     *
     * <p>与它同名同材料、但几何完全不同的是 {@code OldWillowTree}（{@code betternether:old_willow_tree}）：
     * 那棵是「切向折线枝 + 压扁球壳树冠 + 挂墙植物」，由本类的 {@link OldWillow} 承担，见下方那一节。
     */
    private static Feature.Custom.Placer willowTree(UpstreamVegetationFeatures.BlockResolver resolver) {
        BlockData trunkBottom = state(resolver, "betternether:willow_trunk", "shape", "bottom");
        BlockData trunkMiddle = state(resolver, "betternether:willow_trunk", "shape", "middle");
        BlockData trunkTop = state(resolver, "betternether:willow_trunk", "shape", "top");
        BlockData branchMiddle = state(resolver, "betternether:willow_branch", "shape", "middle");
        BlockData branchEnd = state(resolver, "betternether:willow_branch", "shape", "end");

        // 上游有两处叶子状态：绝大多数带 persistent=true，而 branch() 收尾那一处**只设 facing**
        // （persistent 保持默认 false）。两档都要解析，否则那一处的语义会被悄悄改掉。
        // 注意 NATURAL 属性上游没碰，保持 registerDefaultState 的默认值 true，所以不在这里传。
        BlockData leavesUp = state(resolver, "betternether:willow_leaves", "facing", "up", "persistent", "true");
        BlockData leavesDown = state(resolver, "betternether:willow_leaves", "facing", "down", "persistent", "true");
        BlockData leavesNorth = state(resolver, "betternether:willow_leaves", "facing", "north", "persistent", "true");
        BlockData leavesSouth = state(resolver, "betternether:willow_leaves", "facing", "south", "persistent", "true");
        BlockData leavesEast = state(resolver, "betternether:willow_leaves", "facing", "east", "persistent", "true");
        BlockData leavesWest = state(resolver, "betternether:willow_leaves", "facing", "west", "persistent", "true");
        BlockData plainNorth = state(resolver, "betternether:willow_leaves", "facing", "north");
        BlockData plainSouth = state(resolver, "betternether:willow_leaves", "facing", "south");
        BlockData plainEast = state(resolver, "betternether:willow_leaves", "facing", "east");
        BlockData plainWest = state(resolver, "betternether:willow_leaves", "facing", "west");
        // 地面判据里「自定义方块那一半」（菌丝 + 被塞进 minecraft:nylium 的四种草）。
        return new Willow(trunkBottom, trunkMiddle, trunkTop, branchMiddle, branchEnd,
                leavesUp, leavesDown, leavesNorth, leavesSouth, leavesEast, leavesWest,
                plainNorth, plainSouth, plainEast, plainWest);
    }

    /** {@code WillowTreeFeature#grow} / {@code #branch} / {@code #vine}。 */
    static final class Willow implements Feature.Custom.Placer {
        /**
         * 上游 {@code HOR = HorizontalDirectionalBlock.FACING.getPossibleValues()}。
         *
         * <p>顺序取 <b>北、南、西、东</b>：这是 26.3 里 {@code Direction.Plane.HORIZONTAL} 的枚举序，
         * 依据是同仓库 {@code OldWillowTree.java} 的一行源码注释 ——
         * 「{@code BlocksHelper.HORIZONTAL} is N/S/E/W … {@code Direction.Plane.HORIZONTAL} is
         * N/S/W/E and would shift the random stream」。顺序只影响「第 i 根枝朝哪边」，
         * 不改变随机数个数，因此同一 seed 的树只会整体旋转。
         */
        private static final Point.Direction[] HOR = {
                Point.Direction.NORTH, Point.Direction.SOUTH, Point.Direction.WEST, Point.Direction.EAST
        };

        private final BlockData trunkBottom;
        private final BlockData trunkMiddle;
        private final BlockData trunkTop;
        private final BlockData branchMiddle;
        private final BlockData branchEnd;
        private final BlockData leavesUp;
        private final BlockData leavesDown;
        private final BlockData leavesNorth;
        private final BlockData leavesSouth;
        private final BlockData leavesEast;
        private final BlockData leavesWest;
        private final BlockData plainNorth;
        private final BlockData plainSouth;
        private final BlockData plainEast;
        private final BlockData plainWest;
        private final boolean ready;

        Willow(BlockData trunkBottom, BlockData trunkMiddle, BlockData trunkTop,
               BlockData branchMiddle, BlockData branchEnd,
               BlockData leavesUp, BlockData leavesDown, BlockData leavesNorth, BlockData leavesSouth,
               BlockData leavesEast, BlockData leavesWest,
               BlockData plainNorth, BlockData plainSouth, BlockData plainEast, BlockData plainWest) {
            this.trunkBottom = trunkBottom;
            this.trunkMiddle = trunkMiddle;
            this.trunkTop = trunkTop;
            this.branchMiddle = branchMiddle;
            this.branchEnd = branchEnd;
            this.leavesUp = leavesUp;
            this.leavesDown = leavesDown;
            this.leavesNorth = leavesNorth;
            this.leavesSouth = leavesSouth;
            this.leavesEast = leavesEast;
            this.leavesWest = leavesWest;
            this.plainNorth = plainNorth;
            this.plainSouth = plainSouth;
            this.plainEast = plainEast;
            this.plainWest = plainWest;
            this.ready = trunkBottom != null && trunkMiddle != null && trunkTop != null
                    && branchMiddle != null && branchEnd != null
                    && leavesUp != null && leavesDown != null && leavesNorth != null && leavesSouth != null
                    && leavesEast != null && leavesWest != null
                    && plainNorth != null && plainSouth != null && plainEast != null && plainWest != null;
        }

        @Override
        public boolean place(WorldView world, Point point) {
            if (!ready) {
                return false;
            }
            // 上游 BlocksHelper.isNetherGround：见 netherGround（真实 tag + 自定义方块状态比较）。
            if (!netherGround(world, point.x(), point.y() - 1, point.z())) {
                return false;
            }
            return grow(world, point);
        }

        private boolean grow(WorldView world, Point pos) {
            final Random random = world.random();
            final int x = pos.x();
            final int y = pos.y();
            final int z = pos.z();

            int h2 = 5 + random.nextInt(3);
            int mh = upRay(world, x, y + 1, z, h2);
            if (mh < 5) {
                return false;
            }
            h2 = Math.min(h2, mh);

            set(world, x, y, z, trunkBottom);
            for (int h = 1; h < h2; h++) {
                if (empty(world, x, y + h, z)) {
                    set(world, x, y + h, z, trunkMiddle);
                }
            }
            if (empty(world, x, y + h2, z)) {
                set(world, x, y + h2, z, trunkTop);
            }

            final int topY = y + h2;
            for (int i = 0; i < 4; i++) {
                Point.Direction dir = HOR[i];
                branch(world, new Point(x + dir.dx(), topY, z + dir.dz()),
                        3 + random.nextInt(2), dir, new Point(x, topY, z), 0);
            }

            set(world, x, topY + 1, z, leaves(Point.Direction.UP, true));
            for (int i = 0; i < 4; i++) {
                Point.Direction dir = HOR[i];
                set(world, x + dir.dx(), topY + 1, z + dir.dz(), leaves(dir, true));
            }
            return true;
        }

        /**
         * {@code WillowTreeFeature#branch}：从 {@code start} 起朝着 {@code direction} 铺叶子。
         *
         * <p>转向规则必须原样：{@code random.nextInt(3) > 0 ? preDir : (nextBoolean ? 顺时针 : 逆时针)}，
         * 即「三分之二直行」，而且 {@code nextBoolean} 只在 {@code nextInt(3) == 0} 时才被抽。
         */
        private void branch(WorldView world, Point start, int length, Point.Direction direction,
                            Point center, int level) {
            if (level > 5) {
                return;
            }
            final Random random = world.random();
            int bx = start.x();
            int by = start.y();
            int bz = start.z();

            set(world, bx, by, bz, leaves(direction, true));
            // 上游 1 + nextInt(1)：nextInt(1) 恒为 0，但**照样消耗一次抽取**，不能省。
            vine(world, bx, by - 1, bz, 1 + random.nextInt(1));

            Point.Direction preDir = direction;
            final int l2 = length * length;
            for (int i = 0; i < l2; i++) {
                Point.Direction dir = random.nextInt(3) > 0
                        ? preDir
                        : (random.nextBoolean() ? clockwise(preDir) : counterClockwise(preDir));
                int px = bx + dir.dx();
                int py = by + dir.dy();
                int pz = bz + dir.dz();
                if (!empty(world, px, py, pz)) {
                    continue;
                }
                bx = px;
                by = py;
                bz = pz;
                if (Math.abs(bx - center.x()) + Math.abs(by - center.y()) + Math.abs(bz - center.z()) > length) {
                    break;
                }
                set(world, bx, by, bz, leaves(dir, true));

                if (random.nextBoolean()) {
                    set(world, bx, by + 1, bz, leaves(Point.Direction.UP, true));
                }
                if (random.nextInt(3) == 0) {
                    // 上游 bpos.setY(bpos.getY() - 1)：这个下移是**持久的**，后面的位置都从它算起。
                    by = by - 1;
                    set(world, bx, by, bz, leaves(Point.Direction.DOWN, true));
                }
                if (random.nextBoolean()) {
                    vine(world, bx, by - 1, bz, 1 + random.nextInt(4));
                }
                if (random.nextBoolean()) {
                    Point.Direction right = clockwise(dir);
                    int rx = bx + right.dx();
                    int rz = bz + right.dz();
                    if (empty(world, rx, by, rz)) {
                        branch(world, new Point(rx, by, rz), length, right, center, level + 1);
                    }
                    right = right.opposite();
                    rx = bx + right.dx();
                    rz = bz + right.dz();
                    if (empty(world, rx, by, rz)) {
                        branch(world, new Point(rx, by, rz), length, right, center, level + 1);
                    }
                }

                Point.Direction dir2 = HOR[random.nextInt(4)];
                int sx = bx + dir2.dx();
                int sz = bz + dir2.dz();
                if (empty(world, sx, by, sz)) {
                    set(world, sx, by, sz, leaves(dir2, true));
                }
                preDir = dir;
            }

            if (random.nextBoolean()) {
                if (empty(world, bx, by, bz)) {
                    // 上游这一处只设 facing，**不设 persistent**（叶子因此会正常衰减判定）。
                    set(world, bx, by, bz, leaves(preDir, false));
                }
            }
        }

        /**
         * {@code WillowTreeFeature#vine}：从上往下挂一段柳枝，最后一节是 {@code END}。
         *
         * <p>注意上游<b>不</b>检查「这一格是不是空气」就写（只有入口检查了一次），
         * 也就是垂枝会覆盖落点上的东西 —— 照抄。
         */
        private void vine(WorldView world, int x, int y, int z, int length) {
            if (!empty(world, x, y, z)) {
                return;
            }
            for (int i = 0; i < length; i++) {
                int py = y - i;
                if (empty(world, x, py - 1, z)) {
                    set(world, x, py, z, branchMiddle);
                } else {
                    set(world, x, py, z, branchEnd);
                    return;
                }
            }
            set(world, x, y - length, z, branchEnd);
        }

        /** 叶子状态：{@code persistent=true} 的那一档，或（只在收尾处）不带 persistent 的那一档。 */
        private BlockData leaves(Point.Direction dir, boolean persistent) {
            if (persistent) {
                return switch (dir) {
                    case UP -> leavesUp;
                    case DOWN -> leavesDown;
                    case NORTH -> leavesNorth;
                    case SOUTH -> leavesSouth;
                    case EAST -> leavesEast;
                    case WEST -> leavesWest;
                };
            }
            return switch (dir) {
                case NORTH -> plainNorth;
                case SOUTH -> plainSouth;
                case EAST -> plainEast;
                case WEST -> plainWest;
                // 上游不带 persistent 的那一处永远是水平方向（preDir 来自 HOR），竖直只用带 persistent 的档。
                default -> leaves(dir, true);
            };
        }

        /** 原版 {@code Direction#getClockWise}：水平四向按 北→东→南→西 转，竖直返回自身。 */
        private static Point.Direction clockwise(Point.Direction dir) {
            return switch (dir) {
                case NORTH -> Point.Direction.EAST;
                case EAST -> Point.Direction.SOUTH;
                case SOUTH -> Point.Direction.WEST;
                case WEST -> Point.Direction.NORTH;
                default -> dir;
            };
        }

        /** 原版 {@code Direction#getCounterClockWise}。 */
        private static Point.Direction counterClockwise(Point.Direction dir) {
            return switch (dir) {
                case NORTH -> Point.Direction.WEST;
                case WEST -> Point.Direction.SOUTH;
                case SOUTH -> Point.Direction.EAST;
                case EAST -> Point.Direction.NORTH;
                default -> dir;
            };
        }
    }

    // ---------------------------------------------------------------- big_brown_mushroom

    /**
     * {@code BigBrownMushroomFeature}：一根棕巨菇柄 + 一朵 3×3 的伞面（四边 + 四角）。
     *
     * <p>上游没有地面判据（{@code patch_big_brown_mushroom} 的 placed_feature 里用
     * {@code wover:is} 判菌丝了），所以这里也不加 —— 多加一个门槛就会让原版能长的位置长不出来。
     */
    private static Feature.Custom.Placer bigBrownMushroom(UpstreamVegetationFeatures.BlockResolver resolver) {
        BlockData bottom = state(resolver, "betternether:brown_large_mushroom", "shape", "bottom");
        BlockData middle = state(resolver, "betternether:brown_large_mushroom", "shape", "middle");
        BlockData top = state(resolver, "betternether:brown_large_mushroom", "shape", "top");
        BlockData sideN = state(resolver, "betternether:brown_large_mushroom", "shape", "side_n");
        BlockData sideS = state(resolver, "betternether:brown_large_mushroom", "shape", "side_s");
        BlockData sideE = state(resolver, "betternether:brown_large_mushroom", "shape", "side_e");
        BlockData sideW = state(resolver, "betternether:brown_large_mushroom", "shape", "side_w");
        BlockData cornerN = state(resolver, "betternether:brown_large_mushroom", "shape", "corner_n");
        BlockData cornerW = state(resolver, "betternether:brown_large_mushroom", "shape", "corner_w");
        BlockData cornerE = state(resolver, "betternether:brown_large_mushroom", "shape", "corner_e");
        BlockData cornerS = state(resolver, "betternether:brown_large_mushroom", "shape", "corner_s");
        return new BigBrownMushroom(bottom, middle, top, sideN, sideS, sideE, sideW,
                cornerN, cornerW, cornerE, cornerS);
    }

    static final class BigBrownMushroom implements Feature.Custom.Placer {
        private final BlockData bottom;
        private final BlockData middle;
        private final BlockData top;
        private final BlockData sideN;
        private final BlockData sideS;
        private final BlockData sideE;
        private final BlockData sideW;
        private final BlockData cornerN;
        private final BlockData cornerW;
        private final BlockData cornerE;
        private final BlockData cornerS;
        private final boolean ready;

        BigBrownMushroom(BlockData bottom, BlockData middle, BlockData top,
                         BlockData sideN, BlockData sideS, BlockData sideE, BlockData sideW,
                         BlockData cornerN, BlockData cornerW, BlockData cornerE, BlockData cornerS) {
            this.bottom = bottom;
            this.middle = middle;
            this.top = top;
            this.sideN = sideN;
            this.sideS = sideS;
            this.sideE = sideE;
            this.sideW = sideW;
            this.cornerN = cornerN;
            this.cornerW = cornerW;
            this.cornerE = cornerE;
            this.cornerS = cornerS;
            this.ready = bottom != null && middle != null && top != null
                    && sideN != null && sideS != null && sideE != null && sideW != null
                    && cornerN != null && cornerW != null && cornerE != null && cornerS != null;
        }

        @Override
        public boolean place(WorldView world, Point point) {
            if (!ready) {
                return false;
            }
            final Random random = world.random();
            // 上游 bclib BlocksHelper.blockCount(world, pos, UP, 2+nextInt(3), isFree)：
            // 从 pos 起（含 pos）沿 +Y 连续数「可用」格，遇到第一个不可用就停下。
            final int size = blockCount(world, point, 2 + random.nextInt(3));
            if (size < 3) {
                return false;
            }

            // 净空：伞面那一层的 3×3 必须全是空气（上游把 9 个位置全部求值后再判）。
            boolean hasAir = true;
            for (int x = -1; x < 2; x++) {
                for (int z = -1; z < 2; z++) {
                    hasAir = hasAir && empty(world, point.x() + x, point.y() + size, point.z() + z);
                }
            }
            if (!hasAir) {
                return false;
            }

            set(world, point.x(), point.y(), point.z(), bottom);
            for (int y = 1; y < size; y++) {
                set(world, point.x(), point.y() + y, point.z(), middle);
            }
            final int capY = point.y() + size;
            set(world, point.x(), capY, point.z(), top);
            set(world, point.x(), capY, point.z() - 1, sideN);
            set(world, point.x(), capY, point.z() + 1, sideS);
            set(world, point.x() + 1, capY, point.z(), sideE);
            set(world, point.x() - 1, capY, point.z(), sideW);
            // 四角同样带交叉命名：北+东 是 CORNER_N，北+西 是 CORNER_W，南+东 是 CORNER_E，南+西 是 CORNER_S。
            set(world, point.x() + 1, capY, point.z() - 1, cornerN);
            set(world, point.x() - 1, capY, point.z() - 1, cornerW);
            set(world, point.x() + 1, capY, point.z() + 1, cornerE);
            set(world, point.x() - 1, capY, point.z() + 1, cornerS);
            return true;
        }
    }

    // ---------------------------------------------------------------- old_willow_tree

    /** 上游 {@code OldWillowTree#CURVE_X}：一根树枝 6 段的水平半径。 */
    private static final float[] CURVE_X = {9F, 7F, 1.5F, 0.5F, 3F, 7F};
    /** 上游 {@code OldWillowTree#CURVE_Y}：同一根树枝 6 段的高度。 */
    private static final float[] CURVE_Y = {20F, 17F, 12F, 4F, 0F, -2F};
    /**
     * 上游 {@code CROWN_SQUASH = 0.70710678F}：树冠壳判定是 {@code 2cx² + cy² + 2cz²}，
     * 水平方向被压扁 √2 倍，所以「留了多少空间」换算成半径要按 √2 折算。
     */
    private static final float CROWN_SQUASH = 0.70710678F;
    /** 上游 {@code BlockStalagnateSeed.MAX_SEARCH_LENGTH}：树干上方至少要这么高的净空。 */
    private static final int MAX_SEARCH_LENGTH = 25;

    /**
     * {@code OldWillowTree}（远古柳）：曲线枝干 + 双层壳树冠 + 垂下的柳枝 + 挂墙植物。
     *
     * <h2>与 {@code willow_tree} 的关系</h2>
     * 两者共用「柳木/柳叶/柳枝方块 + {@code WillowBranchShape}」这一套材料，但几何完全不同：
     * 小柳树是「一根直柄 + 四根递归枝」，远古柳是「{@code CURVE_X/CURVE_Y} 六段折线枝 +
     * 球壳树冠」，还要把树冠塞进写区。参数来自 {@code NaturalTreeConfiguration.naturalLarge()}
     * （{@code distance = 13 → manDist = 20}），JSON 里也写着 13。
     */
    private static Feature.Custom.Placer oldWillowTree(UpstreamVegetationFeatures.BlockResolver resolver,
                                                       @Nullable JsonObject json) {
        BlockData bark = state(resolver, "betternether:willow_bark");
        BlockData log = state(resolver, "betternether:willow_log");
        // 树冠叶子：上游 crown() 里是 .setValue(NATURAL, false)（facing 保持默认 up、
        // persistent 保持默认 false），与 willow_tree 里那套 persistent=true 的叶子不是一档。
        BlockData leaves = state(resolver, "betternether:willow_leaves", "natural", "false");
        // 垂枝：MAT_WILLOW.getBranch().defaultBlockState()（= shape middle）与 shape=end。
        // 上游写的是 defaultBlockState()，而 default 就是 MIDDLE；这里显式请求，
        // 好处是「哪一段是 MIDDLE」这件事在报告与测试里都是可见的。
        BlockData vine = state(resolver, "betternether:willow_branch", "shape", "middle");
        BlockData vineEnd = state(resolver, "betternether:willow_branch", "shape", "end");
        BlockData air = optionalState(resolver, "minecraft:air");

        // 上游 wallPlants = {WALL_MOSS, WALL_MOSS, WALL_MUSHROOM_BROWN, WALL_MUSHROOM_RED}，
        // 每一种再按四个水平方向 setValue(FACING, side)（属性名 facing，见 wall_moss 的 blockstate）。
        String[] plants = {
                "betternether:wall_moss",
                "betternether:wall_moss",
                "betternether:wall_mushroom_brown",
                "betternether:wall_mushroom_red"
        };
        BlockData[][] wallPlants = new BlockData[plants.length][HORIZONTAL.length];
        for (int plant = 0; plant < plants.length; plant++) {
            for (int side = 0; side < HORIZONTAL.length; side++) {
                wallPlants[plant][side] = optionalState(resolver, plants[plant],
                        "facing", HORIZONTAL[side].name().toLowerCase(java.util.Locale.ROOT));
            }
        }

        boolean natural = boolField(json, "natural", true);
        int distance = intField(json, "distance", 7);
        // 地面判据里「自定义方块那一半」（菌丝 + 被塞进 minecraft:nylium 的四种草）。
        return new OldWillow(bark, log, leaves, vine, vineEnd, air, wallPlants, natural, distance);
    }

    /** {@code OldWillowTree#place} / {@code #grow} / {@code #line} / {@code #sphere} / {@code #crown}。 */
    static final class OldWillow implements Feature.Custom.Placer {
        private final BlockData bark;
        private final BlockData log;
        private final BlockData leaves;
        private final BlockData vine;
        private final BlockData vineEnd;
        private final BlockData air;
        private final BlockData[][] wallPlants;
        private final boolean natural;
        private final int distance;
        private final int manDist;
        private final boolean ready;

        OldWillow(BlockData bark, BlockData log, BlockData leaves, BlockData vine, BlockData vineEnd,
                  BlockData air, BlockData[][] wallPlants, boolean natural, int distance) {
            this.bark = bark;
            this.log = log;
            this.leaves = leaves;
            this.vine = vine;
            this.vineEnd = vineEnd;
            this.air = air;
            this.wallPlants = wallPlants;
            this.natural = natural;
            this.distance = distance;
            this.manDist = (int) Math.ceil(distance * 1.5);
            this.ready = bark != null && log != null && leaves != null && vine != null && vineEnd != null;
        }

        @Override
        public boolean place(WorldView world, Point point) {
            if (!ready) {
                return false;
            }
            // 上游 OldWillowTree#place：树干正上方要有这么高的净空（stalagnate 搜索长度的同源常量）。
            final int length = upRay(world, point.x(), point.y(), point.z(), MAX_SEARCH_LENGTH + 2);
            if (length < MAX_SEARCH_LENGTH) {
                return false;
            }
            // 之后才轮到 NonOverlappingFeature#place 的两道门。
            if (!isGround(world, point.x(), point.y() - 1, point.z())) {
                return false;
            }
            if (!noObjNear(world, point)) {
                return false;
            }
            return grow(world, point);
        }

        /** {@code OldWillowTree#isGround} → {@code BlocksHelper.isNetherGround}（真实 tag + 菌丝状态）。 */
        private boolean isGround(WorldView world, int x, int y, int z) {
            return netherGround(world, x, y, z);
        }

        /** {@code NonOverlappingFeature#noObjNear}：同层曼哈顿距离内不能已有 {@code rubeus_log}。 */
        private boolean noObjNear(WorldView world, Point pos) {
            for (int x = pos.x() - distance; x <= pos.x() + distance; x++) {
                for (int z = pos.z() - distance; z <= pos.z() + distance; z++) {
                    if (Math.abs(x - pos.x()) + Math.abs(z - pos.z()) > manDist) {
                        continue;
                    }
                    if (isBlock(world, x, pos.y(), z, "betternether:rubeus_log")) {
                        return false;
                    }
                }
            }
            return true;
        }

        private boolean grow(WorldView world, Point pos) {
            final Random random = world.random();
            final int x = pos.x();
            final int y = pos.y();
            final int z = pos.z();
            // 上游 config.natural 为真（worldgen）→ airOnly 为假。
            final boolean airOnly = !natural;

            // 上游 context.BLOCKS = new HashSet<BlockPos>(2048)。**容量与 hashCode 都要一致**：
            // 下面最终写方块的循环按集合的迭代顺序抽 nextInt(8)，顺序变了就是另一棵树。
            final Set<Pos> blocks = new HashSet<>(2048);

            // 上游无条件把落点清成空气（自然生成时那里本来就是空气，骨粉时是被点的树苗）。
            if (air != null) {
                set(world, x, y, z, air);
            }

            final float scale = randRange(0.7F, 1.3F, random);
            final int minCount = scale < 1 ? 3 : 4;
            final int maxCount = scale < 1 ? 5 : 7;
            final int count = randRange(minCount, maxCount, random);

            // 写区：一次 place() 取一次（上游 WriteZone.of(world)）。
            final Zone zone = Zone.of(world, x, y, z);
            // 上游 BlocksHelper.decorationBounds(world, pos) = toBoundingBox(minY+1, maxY-1)。
            final Box bounds = zone.toBox(world.minY() + 1, world.maxY() - 1);

            for (int n = 0; n < count; n++) {
                final float branchSize = randRange(0.5F, 1F, random) * scale;
                final float angle = n * MHelper_PI2 / count;
                float radius = CURVE_X[0] * branchSize;
                int x1 = Math.round(x + radius * (float) Math.cos(angle)
                        + randRange(-2F, 2F, random) * branchSize);
                int y1 = Math.round(y + CURVE_Y[0] * branchSize
                        + randRange(-2F, 2F, random) * branchSize);
                int z1 = Math.round(z + radius * (float) Math.sin(angle)
                        + randRange(-2F, 2F, random) * branchSize);

                float crownR = 10 * branchSize;
                if (crownR < 1.5F) {
                    crownR = 1.5F;
                }
                // 树冠先「量体裁衣」：塞不下就整根枝跳过（上游也是这么做的，见 WriteZone 注释）。
                final float fittedR = zone.fitRadius(x1, z1, crownR * CROWN_SQUASH, 1.5F * CROWN_SQUASH);
                if (fittedR >= 0) {
                    crownR = Math.min(crownR, fittedR / CROWN_SQUASH);
                    crown(world, x1, y1 + 1, z1, crownR, random, bounds, airOnly);
                }

                boolean generate = true;
                for (int i = 1; i < CURVE_X.length && generate; i++) {
                    radius = CURVE_X[i] * branchSize;
                    final int x2 = Math.round(x + radius * (float) Math.cos(angle)
                            + randRange(-2F, 2F, random) * branchSize);
                    int y2 = Math.round(y + CURVE_Y[i] * branchSize
                            + (CURVE_Y[i] > 0 ? randRange(-2F, 2F, random) * branchSize : 0));
                    final int z2 = Math.round(z + radius * (float) Math.sin(angle)
                            + randRange(-2F, 2F, random) * branchSize);
                    int targetX = x2;
                    int targetZ = z2;
                    int targetY = y2;

                    // 往下垂的两段（CURVE_Y <= 0）必须落在「地面之上」，否则整根枝放弃
                    // （并让折线回到树干，这样形状闭合而不是留下一条断头枝）。
                    if (CURVE_Y[i] <= 0) {
                        if (!isGround(world, targetX, targetY, targetZ)) {
                            boolean noGround = true;
                            for (int d = 1; d < 3; d++) {
                                if (isGround(world, targetX, targetY - d, targetZ)) {
                                    targetY -= d;
                                    noGround = false;
                                    break;
                                }
                            }
                            if (noGround) {
                                targetX = x;
                                targetY = y;
                                targetZ = z;
                                generate = false;
                            }
                        }
                    }

                    line(x1, y1, z1, targetX, targetY, targetZ, y, blocks);
                    x1 = targetX;
                    y1 = targetY;
                    z1 = targetZ;
                }
            }

            for (Pos bpos : blocks) {
                final int bx = bpos.x();
                final int by = bpos.y();
                final int bz = bpos.z();
                if (!bounds.isInside(bx, by, bz)) {
                    continue;
                }
                if (!(airOnly
                        ? empty(world, bx, by, bz)
                        : (isGround(world, bx, by, bz) || replaceable(world, bx, by, bz)))) {
                    continue;
                }
                // 上下相邻都在集合里 = 树干内侧（log），否则是外皮（bark）。
                if (!blocks.contains(bpos.above()) || !blocks.contains(bpos.below())) {
                    set(world, bx, by, bz, bark);
                } else {
                    set(world, bx, by, bz, log);
                }
                if (random.nextInt(8) == 0) {
                    final int plant = random.nextInt(wallPlants.length);
                    for (int side = 0; side < HORIZONTAL.length; side++) {
                        // 每个方向都抽一次，抽不中就跳过 —— 短路掉这次抽样会改整串随机数。
                        if (random.nextInt(8) != 0) {
                            continue;
                        }
                        final Pos sidePos = bpos.relative(HORIZONTAL[side]);
                        if (!bounds.isInsideHorizontally(sidePos.x(), sidePos.z())) {
                            continue;
                        }
                        if (blocks.contains(sidePos)
                                || !empty(world, sidePos.x(), sidePos.y(), sidePos.z())) {
                            continue;
                        }
                        setIn(world, bounds, sidePos.x(), sidePos.y(), sidePos.z(), wallPlants[plant][side]);
                    }
                }
            }
            return true;
        }

        /** {@code OldWillowTree#line}：把一条折线扫成一串球（每段的半径随离地高度衰减）。 */
        private static void line(int x1, int y1, int z1, int x2, int y2, int z2, int startY, Set<Pos> blocks) {
            final int dx = x2 - x1;
            final int dy = y2 - y1;
            final int dz = z2 - z1;
            final int mx = Math.max(Math.max(Math.abs(dx), Math.abs(dy)), Math.abs(dz));
            // mx == 0 时这里是 0/0 = NaN，上游同样如此；mx 为 0 时下面的循环不执行，端点照收。
            final float fdx = (float) dx / mx;
            final float fdy = (float) dy / mx;
            final float fdz = (float) dz / mx;
            float px = x1;
            float py = y1;
            float pz = z1;

            blocks.add(new Pos(x1, y1, z1));
            blocks.add(new Pos(x2, y2, z2));

            for (int i = 0; i < mx; i++) {
                px += fdx;
                py += fdy;
                pz += fdz;
                final int sx = Math.round(px);
                final int sy = Math.round(py);
                final int sz = Math.round(pz);
                final double delta = sy - startY;
                sphere(sx, sy, sz, clamp(2.3 - Math.abs(delta) * (delta > 0 ? 0.1 : 0.3), 0.5, 2.3), blocks);
            }
        }

        /** {@code OldWillowTree#sphere}：把半径内的整块格子塞进集合（注意 {@code MHelper.floor} 的怪行为）。 */
        private static void sphere(int px, int py, int pz, double radius, Set<Pos> blocks) {
            final int x1 = mhelperFloor(px - radius);
            final int y1 = mhelperFloor(py - radius);
            final int z1 = mhelperFloor(pz - radius);
            final int x2 = mhelperFloor(px + radius + 1);
            final int y2 = mhelperFloor(py + radius + 1);
            final int z2 = mhelperFloor(pz + radius + 1);
            radius *= radius;
            for (int x = x1; x <= x2; x++) {
                int ox = x - px;
                ox *= ox;
                for (int z = z1; z <= z2; z++) {
                    int oz = z - pz;
                    oz *= oz;
                    for (int y = y1; y <= y2; y++) {
                        int oy = y - py;
                        oy *= oy;
                        if (ox + oz + oy <= radius) {
                            blocks.add(new Pos(x, y, z));
                        }
                    }
                }
            }
        }

        /**
         * {@code OldWillowTree#crown}：一个被压扁的球壳（{@code 2cx² + cy² + 2cz²} 落在
         * {@code [r², r²_inner]} 之间），壳上按 {@code nextBoolean} 决定挂一条柳枝还是补一片叶子。
         */
        private void crown(WorldView world, int cx, int cy, int cz, float radius, Random random,
                           Box bounds, boolean airOnly) {
            final float halfR = radius * 0.5F;
            final float r2 = radius * radius;
            final int start = (int) Math.floor(-radius);
            for (int oy = start; oy <= radius; oy++) {
                final int oyOuter = oy * oy;
                float oyInner = oy + halfR;
                oyInner *= oyInner;
                final int py = (int) (cy + oy - halfR);
                for (int ox = start; ox <= radius; ox++) {
                    final int ox2 = ox * ox * 2;
                    final int px = cx + ox;
                    for (int oz = start; oz <= radius; oz++) {
                        final int oz2 = oz * oz * 2;
                        if (!(ox2 + oyOuter + oz2 < r2 && ox2 + oyInner + oz2 > r2)) {
                            continue;
                        }
                        final int pz = cz + oz;
                        // 越界的列在读之前就丢掉：上游 26.3 把越界读当错误，而读出来的空值会改树形。
                        if (!bounds.isInsideHorizontally(px, pz)) {
                            continue;
                        }
                        if (!(airOnly ? empty(world, px, py, pz) : replaceable(world, px, py, pz))) {
                            continue;
                        }
                        // 世界生成（airOnly=false）先挂柳枝、最后才写叶子，这个顺序是上游实测调过的：
                        // 反过来会让柳枝在 shape 更新时自断。
                        if (airOnly) {
                            setIn(world, bounds, px, py, pz, leaves);
                        }
                        if (random.nextBoolean()) {
                            int length = downRay(world, px, py, pz, 12);
                            if (length >= 3) {
                                length = randRange(3, length, random);
                                for (int i = 1; i < length - 1; i++) {
                                    setIn(world, bounds, px, py - i, pz, vine);
                                }
                                setIn(world, bounds, px, py - (length - 1), pz, vineEnd);
                            }
                        } else if (random.nextBoolean()
                                && (airOnly ? empty(world, px, py - 1, pz) : replaceable(world, px, py - 1, pz))) {
                            setIn(world, bounds, px, py - 1, pz, leaves);
                        }
                        if (!airOnly) {
                            setIn(world, bounds, px, py, pz, leaves);
                        }
                    }
                }
            }
        }

        /**
         * 上游 {@code isReplaceable} 的兄弟版本：这里对应的是
         * {@code isNetherGround(state) || state.canBeReplaced()} 里后者那一半，
         * 口径与 {@code WartTree#replaceable} 相同（见那处注释）。
         */
        private boolean replaceable(WorldView world, int x, int y, int z) {
            if (empty(world, x, y, z)) {
                return true;
            }
            // 自己的方块永远不覆盖：上游按 Block 身份比，这里优先按真实身份
            // （{@link WorldView#blockId}），问不到才退回材质 —— 否则树冠会把刚写的树皮/叶子
            // 当成「可替换」再写一遍。
            if (isBlockIn(world, x, y, z, WILLOW_OWN_IDS)) {
                return false;
            }
            return BlockPredicates.replaceable(world, x, y, z);
        }
    }

    /** 上游 {@code MHelper.PI2 = (float)(Math.PI * 2)}。 */
    private static final float MHelper_PI2 = (float) (Math.PI * 2);

    /** 上游 {@code MHelper.randRange(int,int,RandomSource)} = {@code min + nextInt(max-min+1)}。 */
    private static int randRange(int min, int max, Random random) {
        return min + random.nextInt(max - min + 1);
    }

    /** 上游 {@code MHelper.randRange(float,float,RandomSource)} = {@code min + nextFloat()*(max-min)}。 */
    private static float randRange(float min, float max, Random random) {
        return min + random.nextFloat() * (max - min);
    }

    /** 上游 {@code MHelper.floor}：对负值先减一再取整，所以 {@code -3.0 → -4}（照抄，不改）。 */
    private static int mhelperFloor(double value) {
        return value < 0 ? (int) (value - 1) : (int) value;
    }

    /** 原版 {@code Mth.clamp(double,double,double)}（上游这里传的就是 double 表达式）。 */
    private static double clamp(double value, double min, double max) {
        return value < min ? min : Math.min(value, max);
    }

    /** 上游 {@code BlocksHelper.downRay}：从 {@code y-1} 往下数连续空气，最多 {@code maxDist-1} 次探测。 */
    private static int downRay(WorldView world, int x, int y, int z, int maxDist) {
        int length = 0;
        for (int j = 1; j < maxDist && empty(world, x, y - j, z); j++) {
            length++;
        }
        return length;
    }

    /** 上游 {@code BlocksHelper.setWithUpdate(level, pos, state, bounds)}：先过 3D bounds 再写。 */
    private static void setIn(WorldView world, Box bounds, int x, int y, int z, @Nullable BlockData data) {
        if (data == null || !bounds.isInside(x, y, z)) {
            return;
        }
        set(world, x, y, z, data);
    }

    /**
     * bclib {@code BlocksHelper.blockCount} 的**逐行**等价实现。
     *
     * <p>出处（已实读，{@code BCLib}/src/main/java/org/betterx/bclib/util/BlocksHelper.java）：
     * <pre>
     * public static int blockCount(LevelAccessor level, BlockPos startPos, Direction dir, int length,
     *                              Predicate&lt;BlockState&gt; freeSurface) {
     *     MutableBlockPos POS = startPos.mutable();
     *     for (int len = 0; len &lt; length; len++) {
     *         if (!freeSurface.test(level.getBlockState(POS))) return len;
     *         POS.move(dir, 1);
     *     }
     *     return length;
     * }
     * public static boolean isFree(BlockState state) { return state.isAir(); }
     * </pre>
     * 三点必须照抄：<b>从 {@code startPos} 自身开始数</b>（{@code len = 0} 先测起点）、
     * 上限是 {@code length}、判据是 {@code isFree} 即<b>只认空气</b>（不是 {@code canBeReplaced}）。
     *
     * <p>{@code BigBrownMushroomFeature} 的调用是
     * {@code blockCount(level, pos, Direction.UP, 2 + random.nextInt(3), BlocksHelper::isFree)}，
     * {@code size} 随后直接当「柄的格数」用（{@code pos} = {@code BOTTOM}，
     * {@code 1..size-1} = {@code MIDDLE}，伞面在 {@code pos.above(size)}），所以这条计数口径
     * 决定了柄长与伞面高度，不能用「可替换」放宽。
     */
    private static int blockCount(WorldView world, Point pos, int maxCount) {
        for (int len = 0; len < maxCount; len++) {
            if (!empty(world, pos.x(), pos.y() + len, pos.z())) {
                return len;
            }
        }
        return maxCount;
    }
}
