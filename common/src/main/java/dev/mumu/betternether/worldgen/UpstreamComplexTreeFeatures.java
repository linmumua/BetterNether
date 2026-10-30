package dev.mumu.betternether.worldgen;

import com.google.gson.JsonObject;
import dev.mumu.betternether.terrain.CraftEngineLookup;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 上游五棵「复杂树」的算法移植入口：幽光木、赤菇木（rubeus）、下界樱、锚树（含 branches / roots 两个独立 feature）。
 *
 * <h2>为什么这五棵必须移植算法而不是拼 JSON</h2>
 * 上游 {@code org.betterx.betternether.world.features} 里这五个类不是配置而是程序：
 * <ul>
 *   <li>{@code GloomwoodTreeFeature} 完全建立在 {@code world/tree} 这个 SDF 树库上：
 *       骨架（{@code ForkingTrunk}）→ 体素光栅化（{@code TreeBuilder}）→ 叶子衰减求解
 *       （{@code LeafDecay}）→ 一次性提交。冠层是「圆柱 + 椭球穹顶 平滑并 后减去椭球裙摆」，
 *       这个减集形状在既有 {@link Feature} 类型里根本不可表达（原版的 foliage placer 全是加法）。</li>
 *   <li>{@code RubeusTreeFeature} / {@code NetherSakuraFeature} 是「曲线控制点 + 逐格
 *       {@code nextFloat()}」的旧式生成器，木块形状（{@code shape=bottom/middle/top}）取决于
 *       邻居判定。</li>
 *   <li>{@code AnchorTreeFeature} / {@code AnchorTreeBranchFeature} /
 *       {@code AnchorTreeRootFeature} 是 26.3 里三个**独立注册**的 feature（同一群系里各占一条
 *       placed_feature），形状由 {@code circleLinesEnds} 的递归圆环、{@code lerpCos} 的余弦插值
 *       与固定 seed 的噪声共同决定。</li>
 * </ul>
 *
 * <h2>随机消耗：个数与顺序照抄</h2>
 * 每个几何类里 {@code world.random().nextXxx()} 的位置与上游源码一一对应（含「先抽、后判定」这种
 * 上游刻意保留的写法 —— 见 {@link GloomwoodGeometry} 里 moltenRoot / ancientDebrisRoots 的注释）。
 * 集合遍历顺序同样照抄：上游用 {@code HashSet<BlockPos>} 遍历待写方块，而抽样的「次数」由几何决定、
 * 「哪一格拿到第几次」由遍历顺序决定。所以这里不用 {@code HashSet<Point>}，而是
 * {@link BlockPosOrderedSet} —— 把 {@code Vec3i#hashCode}/{@code equals}/{@code compareTo} 放进一个
 * wrapper，容器本身仍是 JDK 的 {@code HashSet}（容量取上游的 16 / 2048），所以扩容与红黑树化
 * 都由原版实现负责，不是自造。
 *
 * <h2>两个外部依赖的核实结论（都读了原文，不是推测）</h2>
 * <ul>
 *   <li>写区 {@code de.ambertation.wover.feature.api.WriteZone}
 *       （{@code WorldWeaver/release/26.3},
 *       {@code wover-feature-api/src/main/java/de/ambertation/wover/feature/api/WriteZone.java}）：
 *       复用同包 {@link UpstreamSimpleTreeFeatures.Zone}（逐行照抄的同一份算术），
 *       只有「探测对象」换成 {@link WorldView#inRegion}。</li>
 *   <li>噪声 {@code de.ambertation.wover.math.api.noise.OpenSimplexNoise}
 *       （{@code wover-math-api/src/main/java/de/ambertation/wover/math/api/noise/OpenSimplexNoise.java}）：
 *       与本仓库 {@code terrain/OpenSimplexNoise} 的 2D/3D {@code eval}、permutation 生成与
 *       {@code gradients3D} 表**逐 token 相同**（脚本比对官方原文；本地只少 4D）。</li>
 * </ul>
 *
 * <h2>方块 id 一律写全 {@code betternether:xxx} 字面量</h2>
 * 为了让 {@code scripts/} 下的资源包扫描（blocks.py 一类的收录器）能直接读到，本文件与三个
 * 几何类里没有任何拼接出来的 id，也没有从注册表反查名字。
 *
 * <h2>本类不注册任何东西</h2>
 * 装载器按 configured-feature 的 {@code type} 问 {@link #supportedTypes()}，命中就调
 * {@link #create(String, JsonObject)}；step 的调度（count / every_layer / height_range）仍由上游
 * placed_feature JSON 提供，属于 {@code WorldgenLoader}/{@code TreePopulator} 的职责。
 */
public final class UpstreamComplexTreeFeatures {
    /**
     * 上游 {@code registry/NetherFeatures.java} 里注册的 type 全名，逐字对应。
     *
     * <p>{@code anchor_tree_branch} 与 {@code anchor_tree_root} 是**独立 feature**，不是
     * {@code anchor_tree} 的子步骤：上游在倒置森林群系里三条 placed_feature 并排挂，缺一条
     * 就会少掉「叶子」或「垂根」。所以这里三个都单列，装载器可以各自注册。
     */
    public static final Set<String> SUPPORTED = Set.of(
            "betternether:gloomwood_tree",
            "betternether:rubeus_tree",
            "betternether:sakura_tree",
            "betternether:anchor_tree",
            "betternether:anchor_tree_branch",
            "betternether:anchor_tree_root"
    );

    /** 装载期发现的问题（缺方块、属性退回默认、已知缺口）。空表 = 一切照预期解析。 */
    private static final List<String> NOTES = Collections.synchronizedList(new ArrayList<>());

    private UpstreamComplexTreeFeatures() {
    }

    public static Set<String> supportedTypes() {
        return SUPPORTED;
    }

    public static List<String> loadNotes() {
        return List.copyOf(NOTES);
    }

    // ---------------------------------------------------------------- 入口

    /** 方块解析：{@code id + 属性} → 方块状态。测试注入假实现，运行期走 CraftEngine。 */
    public interface BlockResolver {
        @Nullable
        BlockData resolve(String id, Map<String, String> properties);
    }

    /**
     * 建一棵树；{@code null} 表示这个 type 不归本类管。
     *
     * <p>{@code json} 只有幽光木读一个字段：{@code bleached_chance}。它**只能**来自配置
     * （{@code GloomwoodTreeConfiguration.bleachedChance}，上游只有孤植那一条 content entry 给非零值）。
     * 缺字段时默认 0：反过来「没配置也按 1/3 漂白」会把每一片幽光木都改成另一副调色板，
     * 这不是移植而是新增设定。
     */
    public static @Nullable Feature create(String configuredType, @Nullable JsonObject json) {
        return create(configuredType, json, UpstreamComplexTreeFeatures::resolve);
    }

    /** 同上，方块解析可注入 —— 单元测试用它避开 CraftEngine 与服务器注册表。 */
    public static @Nullable Feature create(String configuredType, @Nullable JsonObject json,
                                           BlockResolver resolver) {
        Feature.Custom.Placer placer = switch (configuredType) {
            case "betternether:gloomwood_tree" ->
                    new GloomwoodGeometry(resolver, bleachedChance(json), distance(json, 9));
            case "betternether:rubeus_tree" ->
                    new RubeusGeometry(resolver, distance(json, 7), natural(json));
            case "betternether:sakura_tree" -> new NetherSakuraGeometry(resolver);
            case "betternether:anchor_tree" -> new AnchorTreeGeometry(resolver, AnchorTreeGeometry.Kind.TREE);
            case "betternether:anchor_tree_branch" ->
                    new AnchorTreeGeometry(resolver, AnchorTreeGeometry.Kind.BRANCH);
            case "betternether:anchor_tree_root" ->
                    new AnchorTreeGeometry(resolver, AnchorTreeGeometry.Kind.ROOT);
            default -> null;
        };
        return placer == null ? null : new Feature.Custom(configuredType, placer);
    }

    private static float bleachedChance(@Nullable JsonObject json) {
        if (json == null || !json.has("bleached_chance")) {
            return 0.0F;
        }
        try {
            return json.get("bleached_chance").getAsFloat();
        } catch (RuntimeException error) {
            note("gloomwood bleached_chance 不是数字，按 0 处理：" + json.get("bleached_chance"));
            return 0.0F;
        }
    }

    /**
     * 上游 {@code NaturalTreeConfiguration.distance}：非重叠检查用的间距，缺省值按各树自己的
     * {@code SPACING}（幽光木 9，其余 7）。
     */
    private static int distance(@Nullable JsonObject json, int fallback) {
        if (json == null || !json.has("distance")) {
            return fallback;
        }
        try {
            return json.get("distance").getAsInt();
        } catch (RuntimeException error) {
            note("distance 不是整数，按 " + fallback + " 处理");
            return fallback;
        }
    }

    /**
     * 上游 {@code NonOverlappingFeature#noObjNear}：以 {@code pos} 所在的那一层扫一个
     * 「曼哈顿距离 ≤ manDist」的菱形，碰到同种树的原木就不放。
     *
     * <p>{@code distance} 只决定扫描的方形外框，真正判定用 {@code manDist = ceil(distance*1.5)}；
     * 两个都要，少一个就会把「刚好在方框角上」的那棵树漏掉。
     *
     * <p>这个检查不吃随机数，但会**跳过**整棵树 —— 所以它必须移植，否则密度会变。
     */
    static boolean noObjectNear(WorldView world, Point pos, int distance, int manDist,
                                Set<String> structureIds, Set<BlockData> structures) {
        if (structures.isEmpty() && structureIds.isEmpty()) {
            return true;
        }
        for (int x = pos.x() - distance; x <= pos.x() + distance; x++) {
            for (int z = pos.z() - distance; z <= pos.z() + distance; z++) {
                final int dx = x - pos.x();
                final int dz = z - pos.z();
                if (Math.abs(dx) + Math.abs(dz) > manDist) {
                    continue;
                }
                // id 优先（CE 下精确），问不到才退回状态比对。
                if (isAnyOfIds(world, x, pos.y(), z, structureIds, structures)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** 上游 {@code NaturalTreeConfiguration.natural}，缺省 true（上游所有 placed feature 都是自然生成）。 */
    private static boolean natural(@Nullable JsonObject json) {
        if (json == null || !json.has("natural")) {
            return true;
        }
        try {
            return json.get("natural").getAsBoolean();
        } catch (RuntimeException error) {
            note("natural 不是布尔值，按 true 处理");
            return true;
        }
    }

    /** 上游 {@code manDist = (int) Math.ceil(distance * 1.5)}。 */
    static int manhattan(int distance) {
        return (int) Math.ceil(distance * 1.5);
    }

    // ---------------------------------------------------------------- 共享工具

    /**
     * 上游 {@code ContextFeature#place} 收到的 {@code MAX_HEIGHT} = {@code generator.getGenDepth()}，
     * 原版下界是 128。这里按世界高度换算：{@code maxY - minY + 1}。
     *
     * <p>锚树 / 樱树 / branch / root 四处的门槛与 {@code scale_factor = MAX_HEIGHT / 128}
     * 都吃这个数，写成 256 会把门槛整体推到两倍高度（就是"处处为真/处处为假"的静默错位）。
     */
    static int maxHeight(WorldView world) {
        return world.maxY() - world.minY() + 1;
    }

    /**
     * 「能长树的地面」判据的注入点：上游 {@code BlocksHelper.isNetherGround}
     * = {@code wover:surfaces/nether/stones} ∪ 灵魂沙 ∪ 菌丝 ∪ nylium，幽光木额外允许
     * {@code wover:surfaces/sculk_like}。
     *
     * <h2>为什么做成注入点</h2>
     * wover 的那几个 tag 来自上游数据包，**服务端 tag 注册表里没有**，所以
     * {@link WorldView#inTag} 对它们只会返回 {@code null}（它诚实地表示「问不出来」）。
     * 真正能回答的是装载器在装载期解析好的 id 列表
     * （{@code WorldgenLoader#tagPredicate(String)}）。装载器已经在
     * {@code WorldgenLoader#load(String)} 返回前调 {@link #installGroundTags}，注入的是
     * {@code nether/stones} ∪ {@code nether/mycelium} ∪ {@code minecraft:nylium} ∪ 灵魂沙土
     * （= 上游 {@code isNetherGround}）与 {@code sculk_like}。
     *
     * <p>若某次运行没有注入（单元测试、只加载部分资源），退回 {@link Approximate}：
     * 先问原版真 tag，再退回「实心且非基岩」。这条退路**不会**写进 {@link #loadNotes()} ——
     * 「是否注入」由装载器一侧的事实决定，不在这里重复记账。
     */
    public interface GroundTags {
        /** 上游 {@code BlocksHelper.isNetherGround}。 */
        boolean netherGround(WorldView world, int x, int y, int z);

        /** 上游 {@code BlocksHelper.isSculkLike}（只有幽光木用）。 */
        boolean sculkLike(WorldView world, int x, int y, int z);

        /**
         * 缺省实现：**原版真实 tag 优先**，问不到才退回「实心且非基岩」。
         *
         * <p>原版 tag（{@code minecraft:base_stone_nether} / {@code minecraft:nylium} /
         * {@code minecraft:soul_fire_base_blocks}）在服务端注册表里是真的，{@link WorldView#inTag}
         * 能回答；只有 wover 的那些数据包 tag 回答不了。所以缺省实现比纯近似更准一档，
         * 剩下那部分差异（BN 自己的菌丝/草地变体）要靠 {@link #installGroundTags} 补。
         */
        GroundTags APPROXIMATE = new Approximate();
    }

    /** {@link GroundTags#APPROXIMATE} 的实现：先问原版真 tag，再退回地面近似。 */
    static final class Approximate implements GroundTags {
        @Override
        public boolean netherGround(WorldView world, int x, int y, int z) {
            if (inAnyTag(world, x, y, z, "minecraft:base_stone_nether", "minecraft:nylium",
                    "minecraft:soul_fire_base_blocks")) {
                return true;
            }
            return BlockPredicates.surface(world, x, y, z);
        }

        @Override
        public boolean sculkLike(WorldView world, int x, int y, int z) {
            return inAnyTag(world, x, y, z, "minecraft:sculk_replaceable");
        }

        /**
         * 任一 tag 命中即真；全都没命中再退回「实心且非基岩」。
         *
         * <p>为什么命中失败还要退回：上游的白名单里有 BN 自己的地面方块（
         * {@code netherrack_moss} / {@code swampland_grass} 之类），它们不在任何**原版** tag 里，
         * 而 wover 的 tag 又不可能在服务端注册表里 —— 只按原版 tag 判会把 BN 地表整体判成「不是地面」，
         * 那是比「多接受岩浆块」严重得多的错。精确路径是装载器注入 {@link GroundTags}
         * （{@code WorldgenLoader#tagPredicate} 解析出的真 id 列表）。
         */
        private static boolean inAnyTag(WorldView world, int x, int y, int z, String... tags) {
            for (String tag : tags) {
                final Boolean tagged = world.inTag(tag, x, y, z);
                if (tagged != null && tagged) {
                    return true;
                }
            }
            return BlockPredicates.surface(world, x, y, z);
        }
    }

    private static volatile GroundTags groundTags = GroundTags.APPROXIMATE;

    /**
     * 装载器注入真实 tag 判据（{@code null} = 退回 {@link GroundTags#APPROXIMATE}）。
     *
     * <p>装载期调用一次，之后 worldgen worker 只读这个字段 —— 与
     * {@code CraftEngineLookup}「一次解析、热路径不查注册表」的口径一致。
     */
    public static void installGroundTags(@Nullable GroundTags tags) {
        groundTags = tags == null ? GroundTags.APPROXIMATE : tags;
    }

    /** 上游 {@code BlocksHelper.isNetherGround}。 */
    static boolean ground(WorldView world, int x, int y, int z) {
        return groundTags.netherGround(world, x, y, z);
    }

    /** 幽光木的地面：普通下界地面 ∪ 幽光木群系自己的 sculk 地板。 */
    static boolean sculkGround(WorldView world, int x, int y, int z) {
        return groundTags.netherGround(world, x, y, z) || groundTags.sculkLike(world, x, y, z);
    }

    /** 上游 {@code canBeReplaced()}：本世界里可替换的只有空气、岩浆、水。 */
    static boolean replaceable(WorldView world, int x, int y, int z) {
        return BlockPredicates.replaceable(world, x, y, z);
    }

    /** 上游 {@code world.isEmptyBlock(pos)}：唯一在 CE 下仍然诚实的判据。 */
    static boolean air(WorldView world, int x, int y, int z) {
        return world.inRegion(x, y, z) && world.isAir(x, y, z);
    }

    /**
     * 上游 {@code BlocksHelper.downRay}：从 {@code y} 往下数连续的空气格，最多 {@code maxDist - 1} 格。
     *
     * <p>照抄的是那个别扭的循环边界（{@code j < maxDist}），因为各处门槛（净空、樱树落地、
     * 锚树地板）都是拿它的返回值直接比较的。
     */
    static int downRay(WorldView world, int x, int y, int z, int maxDist) {
        int length = 0;
        for (int j = 1; j < maxDist && air(world, x, y - j, z); j++) {
            length++;
        }
        return length;
    }

    /** 上游 {@code BlocksHelper.upRay}：同 {@link #downRay}，方向朝上。 */
    static int upRay(WorldView world, int x, int y, int z, int maxDist) {
        int length = 0;
        for (int j = 1; j < maxDist && air(world, x, y + j, z); j++) {
            length++;
        }
        return length;
    }

    static Map<String, String> props(String... pairs) {
        if (pairs.length == 0) {
            return Map.of();
        }
        Map<String, String> result = new HashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            result.put(pairs[i], pairs[i + 1]);
        }
        return result;
    }

    /**
     * 解析一个方块；{@code null} 记一条 note。
     *
     * <p>刻意返回 {@code null} 而不抛：缺方块（本例里 {@code rubeus_cone}、
     * {@code lucis_mushroom}、{@code anchor_tree_vine} 当前资源包没收录）应当让那一小段装饰落空，
     * 而不是让整棵树、整个区块生成失败。
     */
    static @Nullable BlockData block(BlockResolver resolver, String id, Map<String, String> properties) {
        BlockData data = resolver.resolve(id, properties);
        if (data == null) {
            note("CraftEngine 未注册方块 " + id + "（相关放置会落空）");
        }
        return data;
    }

    static void note(String message) {
        if (!NOTES.contains(message)) {
            NOTES.add(message);
        }
    }

    /** 运行期解析：与 {@code UpstreamVegetationFeatures} 同一口径。 */
    static @Nullable BlockData resolve(String id, Map<String, String> properties) {
        if (id.startsWith("minecraft:")) {
            return vanilla(id, properties);
        }
        return CraftEngineLookup.lookup(id, properties);
    }

    private static @Nullable BlockData vanilla(String id, Map<String, String> properties) {
        Material material = Material.matchMaterial(id);
        if (material == null) {
            note("原版材质不存在：" + id);
            return null;
        }
        if (properties == null || properties.isEmpty()) {
            return material.createBlockData();
        }
        StringBuilder spec = new StringBuilder(id).append('[');
        boolean first = true;
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            if (!first) {
                spec.append(',');
            }
            spec.append(entry.getKey()).append('=').append(entry.getValue());
            first = false;
        }
        try {
            return org.bukkit.Bukkit.createBlockData(spec.append(']').toString());
        } catch (RuntimeException | LinkageError error) {
            note("原版方块状态拼不出来：" + spec + "（用默认状态）");
            return material.createBlockData();
        }
    }

    /**
     * 位置上的方块是否属于某个 **id 集合**：优先问 {@link WorldView#blockId}。
     *
     * <h2>为什么 id 优先</h2>
     * 已由 CE 源码证实：{@code auto_state} 只影响 visual，世界里存的 {@code DelegatingBlock}
     * 自带 CE 注册 id，{@code CraftEngineBlocks.getBukkitBlockData} 返回的是
     * {@code state.customBlockState.minecraftState}（不是 visual）。所以 {@code blockId} 对 CE
     * 方块是**精确身份**，而 {@code BlockData#getMaterial()} 只是借用宿主材质 —— 多个自定义方块
     * 会共用同一个宿主，按材质判身份会把它们混为一谈。
     *
     * <p>{@code blockId} 返回 {@code null}（越界、非服务端世界、CE 未就绪、单元测试的假世界）
     * 时才退回 {@link #isAnyOf(BlockData, Set)} 的状态/材质比对 —— 退路存在，但正常路径不靠它。
     */
    static boolean isAnyOfIds(WorldView world, int x, int y, int z,
                             Set<String> ids, Set<BlockData> fallback) {
        final String id = world.blockId(x, y, z);
        if (id != null) {
            return ids.contains(id);
        }
        return isAnyOf(world.blockAt(x, y, z), fallback);
    }

    /**
     * 状态级判据（用于「手里已经有一个 BlockData」的地方，例如准备写入的新状态）。
     *
     * <p>按实例身份或 {@link BlockData#matches} 比对：调用方的候选集全都来自同一个
     * {@code lookup} 结果，比较双方都受同一个 CE 映射支配，所以这条对**世界读回的**方块不可靠
     * （那要用 {@link #isAnyOfIds}），对自己解析出来的状态是可用的。
     */
    static boolean isAnyOf(@Nullable BlockData data, Set<BlockData> candidates) {
        if (data == null) {
            return false;
        }
        for (BlockData candidate : candidates) {
            if (candidate == data || data.matches(candidate)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 一个遍历顺序与上游 {@code HashSet<BlockPos>} 相同的集合。
     *
     * <h2>做法：把「BlockPos 的语义」放进 wrapper，剩下的全部交给 JDK</h2>
     * 上游是 {@code HashSet<BlockPos>}，顺序由 JDK 的 {@code HashMap} 决定（桶序、扩容、
     * 必要时红黑树化）。所以这里**不自己实现 HashMap**：把三个已核实的方法塞进
     * {@link BlockPosKey}，然后 {@code new HashSet<>(上游容量)} —— 扩容与树化都是原版那一套。
     * <ul>
     *   <li>{@code hashCode} = {@code (y + z*31)*31 + x}（{@code net.minecraft.core.Vec3i#hashCode}，
     *       {@code javap -p -c} 反汇编 {@code folia测试端/versions/26.3/mucore-26.3.jar} 得到）；</li>
     *   <li>{@code equals} = 三个坐标相等；</li>
     *   <li>{@code compareTo} = 先 y、再 z、最后 x（同一份反汇编里 {@code Vec3i#compareTo} 的分支顺序），
     *       这是 {@code HashMap} 树化成红黑树后的排序键 —— 写错它会改变桶内顺序。</li>
     * </ul>
     *
     * <p>为什么用 wrapper 而不是直接用 {@link Point}：{@code Point} 是 record，它的
     * {@code hashCode} 与 {@code BlockPos} 不同，顺序就与上游无关。为什么不直接用 NMS 的
     * {@code BlockPos}：单元测试环境没有 NMS，而这一层要的只是「同样的 hash/equals/compareTo」。
     * 对外仍然是 {@link Point}（坐标），需要 long 的调用方用 {@link BlockPosKey#packed()}。
     */
    static final class BlockPosOrderedSet extends java.util.AbstractSet<Point> {
        private final java.util.HashSet<BlockPosKey> keys;

        BlockPosOrderedSet(int initialCapacity) {
            // 容量与上游 StructureGeneratorThreadContext 一致（16 / 2048）。
            this.keys = new java.util.HashSet<>(initialCapacity);
        }

        @Override
        public boolean add(Point point) {
            return keys.add(new BlockPosKey(point.x(), point.y(), point.z()));
        }

        @Override
        public boolean contains(Object other) {
            return other instanceof Point point
                    && keys.contains(new BlockPosKey(point.x(), point.y(), point.z()));
        }

        @Override
        public boolean remove(Object other) {
            return other instanceof Point point
                    && keys.remove(new BlockPosKey(point.x(), point.y(), point.z()));
        }

        @Override
        public int size() {
            return keys.size();
        }

        @Override
        public void clear() {
            keys.clear();
        }

        /** 直接转发 JDK 迭代器（含 {@code remove()}）：顺序、扩容、树化都与上游同一个实现。 */
        @Override
        public java.util.Iterator<Point> iterator() {
            final java.util.Iterator<BlockPosKey> delegate = keys.iterator();
            return new java.util.Iterator<>() {
                @Override
                public boolean hasNext() {
                    return delegate.hasNext();
                }

                @Override
                public Point next() {
                    final BlockPosKey key = delegate.next();
                    return new Point(key.x(), key.y(), key.z());
                }

                @Override
                public void remove() {
                    delegate.remove();
                }
            };
        }
    }

    /**
     * 上游 {@code BlockPos}（{@code Vec3i}）的 hash / equals / 排序语义，逐项按反汇编实现。
     *
     * <p>三个方法与 {@code BlockPos} 完全一致，因此把它放进 {@code HashSet} 得到的遍历顺序
     * 与上游 {@code HashSet<BlockPos>} 相同；红黑树化后的桶内顺序也相同（{@code compareTo} 一致）。
     * 同时提供 20/20/20 位打包，给「要 long 的调用方」与测试用。
     */
    record BlockPosKey(int x, int y, int z) implements Comparable<BlockPosKey> {
        @Override
        public int hashCode() {
            // Vec3i#hashCode
            return (y + z * 31) * 31 + x;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof BlockPosKey key && key.x == x && key.y == y && key.z == z;
        }

        /** {@code Vec3i#compareTo}：y 优先，其次 z，最后 x。 */
        @Override
        public int compareTo(BlockPosKey other) {
            if (y == other.y) {
                if (z == other.z) {
                    return x - other.x;
                }
                return z - other.z;
            }
            return y - other.y;
        }

        /** 坐标打包成 long（20/20/20 位，与各假世界与世界读写用的打包一致）。 */
        long packed() {
            return pack(x, y, z);
        }

        static long pack(int x, int y, int z) {
            return ((long) (x & 0xFFFFF) << 40) | ((long) (y & 0xFFFFF) << 20) | (z & 0xFFFFF);
        }
    }

    /** 一个可写的局部坐标集合（对应上游 {@code StructureGeneratorThreadContext} 的集合部分）。 */
    static final class Scratch {
        /** 上游 {@code POINTS = new HashSet<BlockPos>()}：默认容量 16。 */
        final Set<Point> points = new BlockPosOrderedSet(16);
        /** 上游 {@code MIDDLE = new HashSet<BlockPos>()}。 */
        final Set<Point> middle = new BlockPosOrderedSet(16);
        /** 上游 {@code TOP = new HashSet<BlockPos>()}。 */
        final Set<Point> top = new BlockPosOrderedSet(16);
        /** 上游 {@code BLOCKS = new HashSet<BlockPos>(2048)}。 */
        final Set<Point> blocks = new BlockPosOrderedSet(2048);
        /** 上游 {@code LOGS_DIST = new HashMap<>(1024)}：逐 key 写，遍历顺序不影响方块。 */
        final Map<Point, Integer> logsDist = new HashMap<>(1024);

        void clear() {
            points.clear();
            middle.clear();
            top.clear();
            blocks.clear();
            logsDist.clear();
        }
    }

    /** 上游 {@code MHelper.randRange(int, int, random)}：闭区间。 */
    static int randRange(int min, int max, Random random) {
        return min + random.nextInt(max - min + 1);
    }

    static float randRange(float min, float max, Random random) {
        return min + random.nextFloat() * (max - min);
    }

    static final float PI2 = (float) (Math.PI * 2);

    /**
     * 上游 {@code MHelper.floor}：向负无穷取整。
     *
     * <p>与 {@code (int)} 强转的差别只在负数上，而树是从某一格往上下长，y 完全可能是负的
     * （雪原之外的世界高度是负的），所以必须用 floor。
     */
    static int floor(double value) {
        return (int) Math.floor(value);
    }

    static Set<BlockData> identities(BlockData... states) {
        Set<BlockData> result = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (BlockData state : states) {
            if (state != null) {
                result.add(state);
            }
        }
        return result;
    }

}
