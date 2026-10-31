package dev.mumu.betternether.vanilla;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 「这一格植物依赖哪一侧」的运行时清单：失去支撑时该不该跟着掉落，只看这一张表。
 *
 * <h2>为什么要在 Java 里复制一份清单</h2>
 * 真值是 {@code plugin/build/generated/bn/reports/plant-breakdown.json}（59 条，生成期产出）。
 * 它**不进 jar** —— {@code plugin/build.gradle.kts} 里明确写了 {@code exclude("reports/**")}，
 * 因为它是给维护者看的构建期报告，不是运行期资源。所以运行期要么读不到它，要么就得把整份报告
 * 塞进产物里（那是别人的构建脚本，不该为了这一个监听去改）。
 *
 * <p>于是这里只保留运行时真正用得到的**两列**：方块 id，以及它判的是哪一侧。其余字段
 * （{@code criterion} / {@code whitelist} / {@code blocked} / {@code ce_behavior} / {@code notes}）
 * 是给「CE 配置能不能表达」这个问题用的，运行期不需要，故意不复制 —— 复制越多，漂移面越大。
 *
 * <h2>怎么保证这份副本不漂</h2>
 * {@code BlockBreakdownTableTest} 在报告文件存在时逐条比对 id 集合与方向，一条不一致就失败。
 * 报告不存在（clean 构建、只有 :common 的单元测试）时该测试 {@code assumeTrue} 跳过，不静默通过。
 *
 * <h2>方向的含义</h2>
 * 来自报告自己的 {@code convention} 段，不是猜的：
 * <ul>
 *   <li>{@code up} = 依赖上方（吊在顶上的藤蔓、眼球）——上方那格没了就该掉；</li>
 *   <li>{@code down} = 依赖下方（地面上的一格植物）——下方那格没了就该掉；</li>
 *   <li>{@code both} = 上下都判（芦苇、钟乳石链）；</li>
 *   <li>{@code facing} = 按贴面方向判（墙上/顶上的晶簇、火把）；</li>
 *   <li>{@code mixed} = 同一判据里混了不同方向（本类一支 + 基类一支）。</li>
 * </ul>
 *
 * <p>{@code facing} 需要读方块自己声明的朝向才能判断，{@code mixed} 连「一个方向」都说不出来，
 * 两者在 {@link #breaksWhen(Depends, Side, Side)} 里的处理方式见该方法的注释。
 */
public final class BlockBreakdownTable {

    /** 相对某一格的一个方位。级联只需要这六向，不需要完整 {@code BlockFace} 自面朝向。 */
    public enum Side {
        ABOVE, BELOW, NORTH, EAST, SOUTH, WEST;

        /** 相反方位：{@code ABOVE} ↔ {@code BELOW}，其余四向两两相对。 */
        public Side opposite() {
            return switch (this) {
                case ABOVE -> BELOW;
                case BELOW -> ABOVE;
                case NORTH -> SOUTH;
                case SOUTH -> NORTH;
                case EAST -> WEST;
                case WEST -> EAST;
            };
        }
    }

    /** 清单里的 {@code breakdown_direction}。 */
    public enum Depends {
        /** 依赖上方。 */
        UP,
        /** 依赖下方。 */
        DOWN,
        /** 上下都判。 */
        BOTH,
        /** 按方块自己声明的贴面方向。 */
        FACING,
        /** 判据里混了两个方向，无法用单一方向表达。 */
        MIXED,

        /**
         * 按**锚点方向**判定：只有背向锚点的那一侧连锁（原版钟乳石/石笋的语义）。
         *
         * <p>与 {@link #BOTH} 的区别：{@code BOTH} 是「两侧都塌」，一根立在地面上的柱子
         * 打掉中间一段，下面的也会跟着掉 —— 那不是原版行为。这一档要由级联在运行期扫描
         * 柱子两端、找出哪一端是真的锚点（见 {@code BlockBreakdownCascade#judge}）。
         */
        SUPPORTED;

        static @Nullable Depends of(String raw) {
            return switch (raw.toLowerCase(Locale.ROOT)) {
                case "up" -> UP;
                case "down" -> DOWN;
                case "both" -> BOTH;
                case "facing" -> FACING;
                case "mixed" -> MIXED;
                case "supported" -> SUPPORTED;
                default -> null;
            };
        }
    }

    /**
     * 从清单文本建表。
     *
     * <p>格式：每行 {@code <方块 id> <方向>}，空白分隔，{@code #} 开头与空行忽略。用文本而不是
     * 59 个 {@code put} 调用，是因为这张表要和报告逐行对读 —— 一行一条，diff 时看得见改了什么。
     */
    public static BlockBreakdownTable parse(String text) {
        Map<String, Depends> rules = new LinkedHashMap<>();
        for (String raw : text.split("\n")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.split("\\s+");
            if (parts.length != 2) {
                throw new IllegalArgumentException("清单行必须形如 `<id> <方向>`：" + line);
            }
            Depends depends = Depends.of(parts[1]);
            if (depends == null) {
                throw new IllegalArgumentException("清单行方向不合法（只允许 up/down/both/facing/mixed）：" + line);
            }
            Depends previous = rules.put(parts[0], depends);
            if (previous != null) {
                throw new IllegalArgumentException("清单里重复的方块 id：" + parts[0]);
            }
        }
        return new BlockBreakdownTable(rules);
    }

    /**
     * 从 {@code plant-breakdown.json} 的解析结果建表。
     *
     * <p>只读 {@code blocks.<id>.breakdown_direction}：其余字段运行期不用（见类注释），
     * 而 {@code drops_on_breakdown} 在报告里 59/59 全为 true（用户明确要求掉落），
     * 所以这里不为它留一个永远为真的开关。
     */
    public static BlockBreakdownTable parseReport(JsonObject report) {
        JsonObject blocks = report.getAsJsonObject("blocks");
        if (blocks == null) {
            throw new IllegalArgumentException("报告缺少 blocks 段");
        }
        Map<String, Depends> rules = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : blocks.entrySet()) {
            JsonElement direction = entry.getValue().getAsJsonObject().get("breakdown_direction");
            if (direction == null) {
                throw new IllegalArgumentException(entry.getKey() + " 缺少 breakdown_direction");
            }
            Depends depends = Depends.of(direction.getAsString());
            if (depends == null) {
                throw new IllegalArgumentException(entry.getKey() + " 的方向不合法：" + direction.getAsString());
            }
            rules.put(entry.getKey(), depends);
        }
        return new BlockBreakdownTable(rules);
    }

    /**
     * 内置表：与 {@code reports/plant-breakdown.json} 逐条一致（由测试钉住）。
     *
     * <p>只含**本包真的发布**的方块。报告在生成期就按已发布集合过滤，所以这里不再出现
     * {@code anchor_tree_vine}、{@code black_vine}、{@code gloomsculk_vine} 之类的未发布 id
     * —— 它们曾被抄进这张表，运行期拿它去核对 CE 包时报「包缺少 N 个世界生成方块 id」，
     * 而那批方块本来就不发布。
     *
     * <p>反过来，4 种钟乳石与 {@code glowstone_stalactite}/{@code ink_bush_seed} 现在**是**
     * 发布方块（合成原料需要它们），所以必须留在表里，否则被打掉顶端时不会连锁掉落。
     */
    /**
     * 由「锚点方向」判定的方块：**有意偏离上游**，按用户要求改成原版钟乳石/石笋的行为。
     *
     * <p>上游 {@code BlockStalactite.destroy()} 是两个方向都递归拆同类（报告里记的就是
     * {@code both}），于是一根从地面往上长的柱体，打掉**上面**那段时下面的也会一起掉 ——
     * 用户 2026-10-31 实测指出这不对，要求「和原版钟乳石一样」。
     *
     * <p>所以这里不做成改报告（报告要保持上游真值、并有测试钉住），而是在查表时把这几条
     * 换成 {@link Depends#SUPPORTED}，由级联在运行期看柱子哪一端是锚点再决定拆哪一侧。
     */
    private static final java.util.Set<String> SUPPORT_DIRECTED = java.util.Set.of(
            "betternether:basalt_stalactite",
            "betternether:blackstone_stalactite",
            "betternether:bone_stalactite",
            "betternether:glowstone_stalactite",
            "betternether:netherrack_stalactite",
            // 幽光藤：CE 侧是 `sturdy_base_block{direction: down, stackable: true,
            // max_height: 255}` —— 支撑来自**下方**（或下方同类型堆叠），也就是从地面往上长。
            // 表里记的是 `both`，于是打掉上面那段时下面的也跟着塌，而它们的下方支撑
            // （更下面那格、或最终的地面）根本没变。用户 2026-10-31 实测报的就是这个。
            "betternether:gloomwisp_vine");

    private static final String DATA = """
            betternether:agave down
            betternether:barrel_cactus down
            betternether:basalt_stalactite both
            betternether:black_apple down
            betternether:black_bush down
            betternether:blackstone_stalactite both
            betternether:blooming_vine up
            betternether:bone_mushroom mixed
            betternether:bone_stalactite both
            betternether:brown_large_mushroom down
            betternether:egg_plant down
            betternether:eye_vine both
            betternether:eyeball up
            betternether:eyeball_small up
            betternether:feather_fern down
            betternether:giant_mold down
            betternether:gloomsculk_crystal facing
            betternether:gloomwisp_vine both
            betternether:glowstone_stalactite both
            betternether:golden_vine up
            betternether:gray_mold down
            betternether:hook_mushroom up
            betternether:ink_bush down
            betternether:ink_bush_seed down
            betternether:jellyfish_mushroom both
            betternether:lumabus_vine up
            betternether:magma_flower down
            betternether:moss_cover down
            betternether:nether_cactus both
            betternether:nether_reed_stem both
            betternether:netherrack_stalactite both
            betternether:orange_mushroom down
            betternether:red_large_mushroom down
            betternether:red_mold down
            betternether:rubeus_cone up
            betternether:soul_lily_sapling down
            betternether:soul_vein down
            betternether:stalagnate_bowl down
            """;

    private static BlockBreakdownTable builtin;

    /** 内置表。首次调用时解析，之后复用；不可变，线程安全。 */
    public static synchronized BlockBreakdownTable builtin() {
        if (builtin == null) {
            builtin = parse(DATA);
        }
        return builtin;
    }

    private final Map<String, Depends> rules;

    private BlockBreakdownTable(Map<String, Depends> rules) {
        this.rules = Map.copyOf(rules);
    }

    /** 该方块依赖哪一侧；不在清单里返回 {@code null}（= 一律不动）。 */
    public @Nullable Depends depends(String blockId) {
        if (SUPPORT_DIRECTED.contains(blockId)) {
            return Depends.SUPPORTED;
        }
        return rules.get(blockId);
    }

    /** 清单条数。 */
    public int size() {
        return rules.size();
    }

    /** 清单里的全部 id，按解析顺序。 */
    public java.util.Set<String> ids() {
        return rules.keySet();
    }

    /**
     * 失去某一侧的邻居之后，这个方块该不该跟着掉。
     *
     * @param depends      方块自己的依赖方向
     * @param removedSide  **被移除的那一格**相对这个方块的方位（{@code ABOVE} = 它上方那格没了）
     * @param attachedSide 仅 {@link Depends#FACING} 用：方块自己声明的贴面方向，读不到时 {@code null}
     * @return 是否级联破坏
     */
    public static boolean breaksWhen(Depends depends, Side removedSide, @Nullable Side attachedSide) {
        return switch (depends) {
            // 依赖上方 → 只有「上方那格没了」才塌。下方被挖穿不该动它（它靠的是上面）。
            case UP -> removedSide == Side.ABOVE;
            // 依赖下方 → 对称。
            case DOWN -> removedSide == Side.BELOW;
            case BOTH -> removedSide == Side.ABOVE || removedSide == Side.BELOW;
            // 贴面方块（墙上/顶上的晶簇、火把）：支撑就在它声明的那一侧。
            // 读不到朝向就**不猜**——宁可不级联交给 CE 处理，也不用错误的方向去拆方块。
            case FACING -> attachedSide != null && attachedSide == removedSide;
            // 报告只说「两个方向混用」，说不出是哪一侧。单一方向无法判定 → 不级联。
            // 这条是已知缺口，写在 BlockBreakdownListener 的类注释里。
            case MIXED -> false;
            // 要靠「哪一端是锚点」才能判，纯方位判断给不出结论 —— 交给级联在运行期扫柱子。
            // 走到这里说明调用方没做锚点扫描，保守地不拆。
            case SUPPORTED -> false;
        };
    }

    /**
     * 把方块状态里声明的朝向（{@code facing} / {@code attachment} 之类属性的取值）译成方位。
     *
     * @return 不认识就 {@code null} —— 绝不回落到某个「看起来合理」的方向
     */
    public static @Nullable Side sideOf(String facingValue) {
        if (facingValue == null) {
            return null;
        }
        return switch (facingValue.toLowerCase(Locale.ROOT)) {
            case "up", "above", "top" -> Side.ABOVE;
            case "down", "below", "bottom" -> Side.BELOW;
            case "north" -> Side.NORTH;
            case "east" -> Side.EAST;
            case "south" -> Side.SOUTH;
            case "west" -> Side.WEST;
            default -> null;
        };
    }
}
