package dev.mumu.betternether.terrain;

import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.libraries.nbt.CompoundTag;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.jetbrains.annotations.Nullable;

/**
 * Resolves a block id to {@link BlockData}, covering both CraftEngine blocks and vanilla materials.
 *
 * <p>Every caller resolves what it needs once, at world load, and then indexes an array or map. Two
 * reasons, both load-bearing:
 * <ul>
 *   <li>{@code CraftEngineBlocks.byId} is not meant to be called from worldgen workers. Resolving up
 *       front keeps the hot path free of registry work.</li>
 *   <li>CraftEngine may not have registered its pack yet. Failing loudly at world creation is the
 *       only way an operator learns about it before chunks are baked.</li>
 * </ul>
 *
 * <p>The {@code minecraft:} branch matters: most of our surface palette is vanilla (soul sand,
 * gravel, blackstone, bone block), and {@code CraftEngineBlocks.byId} answers {@code null} for
 * those rather than inventing a definition.
 */
public final class CraftEngineLookup {
    private CraftEngineLookup() {
    }

    /**
     * The block data for an id, or {@code null} when neither CraftEngine nor vanilla supplies it.
     *
     * <p>Never throws: a broken or absent CraftEngine API is reported as "missing" so the caller can
     * degrade one biome instead of taking down the whole generator.
     */
    public static @Nullable BlockData lookup(String id) {
        if (id.startsWith("minecraft:")) {
            Material material = Material.matchMaterial(id);
            return material == null ? null : material.createBlockData();
        }
        try {
            BlockDefinition definition = CraftEngineBlocks.byId(Key.of(id));
            return definition == null ? null : CraftEngineBlocks.getBukkitBlockData(definition.defaultState());
        } catch (RuntimeException | LinkageError error) {
            return null;
        }
    }

    /**
     * 按属性解析一个 CraftEngine 方块状态。
     *
     * <h2>为什么需要这一层</h2>
     * 上游的世界生成是按**带属性的状态**放置的：芦苇的 {@code top}、巨菇的 {@code shape}、
     * 墨丛的 {@code age}。用 {@code defaultState()} 会把它们全部塌成同一个状态，结果是
     * {@code patch_big_red_mushroom}（用 {@code block_column} 摆 shape 组合成蘑菇）变成一根方块柱。
     *
     * <p>API 来自 CraftEngine 本体（已 javap 核对，不是推测）：
     * {@code BlockDefinition.getBlockState(CompoundTag)} → {@code ImmutableBlockState}，
     * 再经 {@code CraftEngineBlocks.getBukkitBlockData(ImmutableBlockState)} 换成 BlockData。
     *
     * <p>属性名不被该方块支持时**只跳过这一个属性**（其余已支持属性照常应用）并记一次日志，
     * 绝不因此丢方块——也绝不因此把整个状态塌成默认态。
     */
    public static @Nullable BlockData lookup(String id, @Nullable java.util.Map<String, String> properties) {
        if (properties == null || properties.isEmpty()) {
            return lookup(id);
        }
        try {
            BlockDefinition definition = CraftEngineBlocks.byId(Key.of(id));
            if (definition == null) {
                return null;
            }
            // 「缺一个属性」不等于「整个状态都不可用」：CE 自己的
            // AbstractBlockDefinition#getBlockState 就是从 defaultState 出发、只应用**存在**的属性。
            // 所以这里也照做 —— 缺的 key 记一次日志后**跳过**，剩下的照常应用。
            //
            // 反例（改回提前 return 会立刻复现）：gloomwisp_vine 上游同时带
            // rotation/offset/persistent，其中只有 rotation 在 CE 里有对应（vine_dir，见
            // mapToCeProperties 的改名），offset/persistent 仍是 CE 侧缺失的属性；
            // 若提前 return，连已声明的 shape/spent 也会一起丢掉 → 整根鬼火藤塌成默认态。
            //
            // 标签类型必须保持 String：上游 JSON 的属性值是字符串，而 CE 的
            // BooleanProperty.unpack 只吃 ByteTag、IntegerProperty.unpack 只吃 NumericTag、
            // String/EnumProperty 只吃 StringTag。putString 与 blocks.py 的 `type: string` 声明配套，
            // 改成 putByte/putInt 会让那些属性反过来解包失败。
            CompoundTag tag = new CompoundTag();
            for (java.util.Map.Entry<String, String> entry
                    : mapToCeProperties(id, properties).entrySet()) {
                if (!definition.hasProperty(entry.getKey())) {
                    noteMissingProperty(id, entry.getKey());
                    continue;
                }
                tag.putString(entry.getKey(), entry.getValue());
            }
            ImmutableBlockState state = definition.getBlockState(tag);
            if (state == null) {
                return CraftEngineBlocks.getBukkitBlockData(definition.defaultState());
            }
            return CraftEngineBlocks.getBukkitBlockData(state);
        } catch (RuntimeException | LinkageError error) {
            return lookup(id);
        }
    }


    /** 唯一需要改名的方块：上游 {@code rotation} 在 CE 侧叫 {@code vine_dir}。 */
    private static final String GLOOMWISP_VINE = "betternether:gloomwisp_vine";

    /**
     * 上游属性名 → CE 属性名的映射。
     *
     * <h2>为什么只有这一条</h2>
     * 上游 {@code BlockGloomwispVine.ROTATION} 是 {@code EnumProperty<WispRotation>}，取值
     * {@code random/north/east/south/west}（{@code WispRotation} 的 {@code getSerializedName()}）；
     * CE 侧 {@code betternether:gloomwisp_vine} 声明的 {@code vine_dir} 是 string 属性，取值集合
     * {@code random/east/north/south/west} —— 完全一致。所以这是一次**纯改名**：值原样透传，不做
     * 角度换算、不猜方向。
     *
     * <p>其余方块的上游属性名与 CE 侧同名，故意不在这里加「通用」规则：{@code rotation} 在别的方块上
     * 未必指头部朝向（例如模型/展示实体的旋转），凭名字全局改写成 {@code vine_dir} 会静默改写它们。
     *
     * @param id               方块 id（含命名空间，与上游 JSON 一致）
     * @param upstreamProperty 上游属性名
     * @return 该方块在 CE 侧对应的属性名；无映射时原样返回
     */
    static String cePropertyName(String id, String upstreamProperty) {
        if (GLOOMWISP_VINE.equals(id) && "rotation".equals(upstreamProperty)) {
            return "vine_dir";
        }
        return upstreamProperty;
    }

    /**
     * 把上游属性表改写成 CE 属性表（顺序保留，值原样透传）。
     *
     * <p>改名后撞车时**先出现的那个赢**：{@code putIfAbsent} 保证上游若同时给了
     * {@code rotation} 和 {@code vine_dir}，后来的那个不会覆盖已经写进去的值——宁可丢一个可疑的
     * 重复键，也不让两个来源互相打架。
     */
    static java.util.LinkedHashMap<String, String> mapToCeProperties(
            String id, java.util.Map<String, String> properties) {
        java.util.LinkedHashMap<String, String> mapped = new java.util.LinkedHashMap<>();
        for (java.util.Map.Entry<String, String> entry : properties.entrySet()) {
            mapped.putIfAbsent(cePropertyName(id, entry.getKey()), entry.getValue());
        }
        return mapped;
    }

    /**
     * 上游属性里哪些**没有被 CE 方块定义**（按 CE 侧属性名比对）。
     *
     * <p>存在的理由：判定「属性有没有落地」只能问 CE 的定义，不能看
     * {@code BlockData#getAsString()} —— 那返回的是**原版方块状态**（形如
     * {@code craftengine:custom_8520}），CE 的内部属性名（{@code shape=bottom}）本来就不会出现在里面。
     * 用它做核验会把每一个属性都判成「未定义」，对每个方块刷一条假 WARN（实测 60 条）。
     *
     * @param id         方块 id
     * @param properties 上游属性表（未改名）
     * @return 缺失的属性名（CE 侧名）；方块本身不存在时返回全部
     */
    public static java.util.List<String> missingProperties(
            String id, java.util.Map<String, String> properties) {
        java.util.List<String> missing = new java.util.ArrayList<>();
        if (properties == null || properties.isEmpty()) {
            return missing;
        }
        try {
            BlockDefinition definition = CraftEngineBlocks.byId(Key.of(id));
            if (definition == null) {
                missing.addAll(mapToCeProperties(id, properties).keySet());
                return missing;
            }
            for (String name : mapToCeProperties(id, properties).keySet()) {
                if (!definition.hasProperty(name)) {
                    missing.add(name);
                }
            }
        } catch (RuntimeException | LinkageError error) {
            // CE 缺席或 API 变动：不谎报「属性缺失」，交给调用方的解析失败分支处理。
            return java.util.List.of();
        }
        return missing;
    }

    /**
     * 缺一个属性时该按什么级别报。
     *
     * <p>这三个分支是**证据等级**，不是猜测：已核实「纯运行时、不参与模型变体」的属性只值得一条
     * INFO（世界看起来完全正确）；已核实「未移植」的属性要给 WARN，因为上游的视觉/行为确实没有等价物。
     */
    enum MissingPropertyDiagnosis {
        /** 上游的纯运行时开关：忽略它不改变形状，记 INFO 即可。 */
        IGNORED_RUNTIME_ONLY,
        /** 已确认未移植：上游行为没有等价物，**不能**当作等价，记 WARN。 */
        UNPORTED,
        /** 没有结论：默认按「可能与上游不同」记 WARN。 */
        UNKNOWN
    }

    /**
     * 已核实为「纯运行时、不参与模型变体」的属性（{@code id[属性]}）。
     *
     * <p>范围刻意收得很窄，一条一条核实过的才进来。多写一条就会把真实的形状缺口降级成 INFO，
     * 那比不写更糟：最难看出来的缺口正是「看起来正常但形状不对」。
     */
    private static final java.util.Set<String> RUNTIME_ONLY_PROPERTIES = java.util.Set.of(
            "betternether:gloomwisp_vine[persistent]",        // 剪过就不长：只影响随机刻生长
            "betternether:egg_plant[age]",                    // 只影响孵化计时
            "betternether:eye_vine[shape]",                   // CE 用单状态+模型表达同一形状
            "betternether:anchor_tree_leaves[distance]",      // 树叶生成距离标记，不进模型
            "betternether:anchor_tree_leaves[persistent]",
            "betternether:gloomwood_leaves[distance]",
            "betternether:gloomwood_leaves[persistent]",
            "betternether:gloomwood_bleached_leaves[distance]",
            "betternether:gloomwood_bleached_leaves[persistent]",
            "betternether:rubeus_leaves[distance]",
            "betternether:rubeus_leaves[persistent]",
            "betternether:nether_sakura_leaves[distance]",
            "betternether:nether_sakura_leaves[persistent]",
            "betternether:willow_leaves[distance]",
            "betternether:willow_leaves[persistent]");

    /**
     * 已核实为「未移植」、且**明确不是等价物**的属性（{@code id[属性]}）。
     *
     * <p>留在这里的每一条都要说清楚原话：
     * <ul>
     *   <li>{@code gloomwisp_vine[offset]}：上游按方块位置做 XZ 抖动，把成片鬼火藤从网格上错开
     *       （{@code BlockGloomwispVine#stateAwareOffset}）。CE 侧没有这个偏移函数，鬼火藤会整齐地
     *       落在方块中心——视觉上能看出来，所以不能降级成 INFO。</li>
     *   <li>{@code giant_lucis} 的六个朝向：上游 {@code BlockGiantLucis} 直接继承
     *       {@code HugeMushroomBlock}，{@code north/east/south/west/up/down} 逐面决定该面是否用「面贴图」。
     *       CE 侧这条没有移植，所以无法表达这种逐面纹理差异，绝不是「等价」。</li>
     * </ul>
     */
    private static final java.util.Set<String> UNPORTED_PROPERTIES = java.util.Set.of(
            "betternether:gloomwisp_vine[offset]",
            "betternether:giant_lucis[north]",
            "betternether:giant_lucis[east]",
            "betternether:giant_lucis[south]",
            "betternether:giant_lucis[west]",
            "betternether:giant_lucis[up]",
            "betternether:giant_lucis[down]");

    /** 缺属性时的定级；纯函数，便于单测钉住每一条结论。 */
    static MissingPropertyDiagnosis diagnoseMissingProperty(String id, String property) {
        String key = id + "[" + property + "]";
        if (RUNTIME_ONLY_PROPERTIES.contains(key)) {
            return MissingPropertyDiagnosis.IGNORED_RUNTIME_ONLY;
        }
        if (UNPORTED_PROPERTIES.contains(key)) {
            return MissingPropertyDiagnosis.UNPORTED;
        }
        return MissingPropertyDiagnosis.UNKNOWN;
    }

    /**
     * 缺属性时的日志正文。
     *
     * <p>三种级别都必须说同一句实话：**只忽略这一个属性**，其他已支持属性照常应用。旧文案写的是
     * 「世界生成将退回默认状态」，与代码行为相反（{@code getBlockState} 会应用其余属性），
     * 会让人误以为整根鬼火藤都塌了而去改根本不该改的配置。
     */
    static String missingPropertyMessage(String id, String property, MissingPropertyDiagnosis diagnosis) {
        String applied = "仅忽略该属性，其他已支持属性仍照常应用。";
        return switch (diagnosis) {
            case IGNORED_RUNTIME_ONLY -> "CraftEngine 方块 " + id + " 没有属性 " + property
                    + "：该属性是上游的纯运行时属性，不参与模型变体；忽略它不改变形状，"
                    + "但不代表已复刻其生长或衰减行为。"
                    + applied;
            case UNPORTED -> "CraftEngine 方块 " + id + " 没有属性 " + property
                    + "：该属性尚未移植，上游这一项的行为/外观没有等价物，不能当作等价处理。"
                    + applied;
            case UNKNOWN -> "CraftEngine 方块 " + id + " 没有属性 " + property
                    + "：尚未核实它是否影响形状，补齐 CE 配置前请按「可能与上游不同」看待。"
                    + applied;
        };
    }

    /** 已报告过的「id+属性」组合，避免每个区块刷一行日志。 */
    private static final java.util.Set<String> REPORTED_PROPERTIES =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * CE 配置里缺某个属性时记一次。
     *
     * <p>为什么必须可见：worldgen 放置是按**带属性的状态**走的（芦苇 top、巨菇 shape、藤蔓 bottom）。
     * 属性不存在时那一条会被跳过，世界会「看起来正常但形状不对」，而这正是最难被发现的一类缺口。
     * 一条日志就够，不必每区块刷屏。
     *
     * <p>级别由 {@link #diagnoseMissingProperty} 决定：核实过的纯运行时属性记 INFO，未移植/未知记 WARN。
     */
    private static void noteMissingProperty(String id, String property) {
        String key = id + "[" + property + "]";
        if (!REPORTED_PROPERTIES.add(key)) {
            return;
        }
        MissingPropertyDiagnosis diagnosis = diagnoseMissingProperty(id, property);
        String message = missingPropertyMessage(id, property, diagnosis);
        try {
            if (diagnosis == MissingPropertyDiagnosis.IGNORED_RUNTIME_ONLY) {
                org.bukkit.Bukkit.getLogger().info(message);
            } else {
                org.bukkit.Bukkit.getLogger().warning(message);
            }
        } catch (RuntimeException | LinkageError ignored) {
            // 没有服务端实例（单元测试）时不刷日志，但 REPORTED 已记录，可被测试读取。
        }
    }

    /** 已经报告过的「id[属性]」组合；供启动日志与测试断言。 */
    public static java.util.List<String> reportedMissingProperties() {
        return java.util.List.copyOf(REPORTED_PROPERTIES);
    }
    /**
     * The block data for an id that the world cannot be generated without.
     *
     * @throws IllegalStateException when the id does not resolve
     */
    public static BlockData require(String id) {
        BlockData data = lookup(id);
        if (data == null) {
            throw new IllegalStateException("CraftEngine 未注册方块 " + id
                    + "（或该 id 不是原版方块），世界生成被拒绝，以免把原版地形永久烘焙进区域文件。");
        }
        return data;
    }

    /** Whether a block id currently resolves. Used by readiness gates. */
    public static boolean resolves(String id) {
        return lookup(id) != null;
    }
}
