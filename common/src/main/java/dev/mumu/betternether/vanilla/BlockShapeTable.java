package dev.mumu.betternether.vanilla;

import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 「这一格方块的竖向形态该是什么」的运行时清单：方块 id → 属性名 → 判定规则。
 *
 * <h2>这个表解决的是什么</h2>
 * 上游这些方块的 {@code SHAPE} 不是玩家放置时定下来的，而是**由上下邻居算出来的**：
 * 单放一格是末端（tip），接上一格之后上面那格变成中间段，再往上接才出现顶端。CraftEngine
 * 没有任何 behavior 能按上下邻居重算任意 string 属性 —— {@code multi_high_block} 只吃 int，
 * {@code stairs_block} 只认 stairs 的 shape，{@code stackable_block} 只认 int 堆叠计数，
 * {@code VineCropHeadBlockBehavior} / {@code VineCropBodyBlockBehavior} 是洞穴藤蔓的
 * age+berries 生长模型。所以玩家放下来永远拿到 YAML 里的 {@code default}，一整串全是同一个形态。
 * 补在 Java 侧是唯一办法，这里就是那张表。
 *
 * <h2>规则从哪来（每条都对着上游源码核过）</h2>
 * <ul>
 *   <li><b>{@link Rule#HANGING_VINE}</b> —— BCLib 的 {@code AbstractVineBlock.updateShape}：
 *       「下方不同类 → BOTTOM；否则上方不同类 → TOP；否则 MIDDLE」。<b>先判下方</b>是关键：
 *       单独立一格（上下一格同类都没有）因此落在 BOTTOM。BCLib 源码不在本仓库，是按上游依赖
 *       {@code quiqueck/BCLib} master 的 {@code AbstractVineBlock} / {@code BaseVineBlock} 核对的；
 *       仓库内可交叉验证的旁证有两处：
 *       ① {@code BlockWhisperingGourdVine}（上游 {@code BlockWhisperingGourdVine.java:54-75}）
 *       覆写时写的是「下方不是同类 → makeBottomState」；
 *       ② {@code VineFeatureDataProvider.java:39} 给 lumabus 用的是
 *       {@code addTripleShapeUpsideDown}，而 {@code VegetationFeatureDataProvider.java:296-299}
 *       的注释写明 {@code addTripleShape} 的顺序是「BOTTOM, MIDDLE × n, TOP」，
 *       即 TOP 永远在链的另一头 —— 吊藤的 TOP 是贴着天花板的那一格。</li>
 *   <li><b>{@link Rule#BOTTOM_FLAG}</b> —— BCLib {@code BaseSimpleVineBlock} 的布尔属性
 *       {@code BOTTOM}（{@code makeBottomState} 置 true，{@code makeMiddleState}/{@code makeTopState}
 *       置 false）。叠回 {@code AbstractVineBlock.updateShape} 之后语义等价于
 *       「下方不同类 → true，否则 false」。</li>
 * </ul>
 *
 * <h2>故意不处理的方块，以及为什么</h2>
 * <ul>
 *   <li><b>{@code brown_large_mushroom}</b>（shape 有 11 个取值：{@code side_*} / {@code corner_*} /
 *       {@code top} / {@code middle} / {@code bottom}）：上游
 *       {@code BlockBrownLargeMushroom.updateShape}（{@code BlockBrownLargeMushroom.java:157-186}）
 *       **完全不重算形状**，它只在对应的邻格不再是同类时把自己变成空气（BOTTOM 直接原样返回，
 *       其余按 {@code SIDE_*} / {@code CORNER_*} 各看一侧）。也就是说 11 个取值里那些
 *       {@code side_*} / {@code corner_*} 全部只由蘑菇盖的生成算法给出，没有一个能从上下邻居推出来。
 *       规则无法从上游确证，按用户要求「不猜」，整块跳过。
 *   <li><b>{@code anchor_tree_vine}</b>、{@code willow_branch}、{@code giant_mold}、
 *       {@code red_large_mushroom}、{@code cincinnasite_pillar}、{@code stalagnate_trunk}、
 *       {@code mushroom_fir_trunk}、{@code nether_reed_stem}、{@code stalagnate_seed}：
 *       同样在生成期只靠 worldgen 写入形态、上游方块自身不按邻居重算（例如
 *       {@code BlockAnchorTreeVine.updateShape} 只判「上方还是不是同类/天花板」，
 *       通过就 {@code return state} 原样返回，不碰 SHAPE）。本次不扩到它们：
 *       每一个都要单独取证一次，而用户报的现象只覆盖上面这张表。哪天真要补，往
 *       {@link #BUILTIN} 里加一行并在这里写清依据即可。
 * </ul>
 *
 * <h2>维护约束</h2>
 * 表里的 id 必须**真的发布**（{@code reports/block-manifest.json} 里有）。报告不进 jar，
 * 所以运行期只能手抄；{@code BlockShapeTableTest} 在报告存在时逐条比对 id 集合与属性名，
 * 报告不存在时 {@code assumeTrue} 跳过而不是静默通过。
 */
public final class BlockShapeTable {

    /** 判定规则的种类。每个值对应上面类注释里的一段依据，别随手加值。 */
    public enum Rule {
        /** BCLib 吊藤：下无同类→bottom；上无同类→top；否则 middle。见类注释。 */
        HANGING_VINE,
        /** 布尔 tip 标记：下无同类→true；否则 false。 */
        BOTTOM_FLAG
    }

    /** 一条：方块 id、承载形态的属性名、判定规则。取值集本身由规则固定，不单独存。 */
    public record Entry(String id, String property, Rule rule) {
        @Override
        public String toString() {
            return id + " " + property + " " + rule;
        }
    }

    /**
     * 判定一格只需要这三个邻居（世界绝对坐标里的同列上下文）。
     *
     * <p>{@code above2} 目前没有规则使用，保留在入参里是为了不改动 {@code AFFECTED_OFFSETS}
     * 的窗口语义；传 {@code null} 也可以。
     */
    public record Neighbours(@Nullable String above, @Nullable String above2, @Nullable String below) {
    }

    /** {@code shape} 三段取值的字面量，与 CE 配置里的 {@code values} 一致。 */
    public static final String BOTTOM = "bottom";
    public static final String MIDDLE = "middle";
    public static final String TOP = "top";
    /** {@link Rule#BOTTOM_FLAG} 的取值（CE 里是 string 属性，值是 "true"/"false"）。 */
    public static final String TRUE = "true";
    public static final String FALSE = "false";

    /**
     * 一格变化之后，需要重新算形态的格子的 y 偏移（相对变化点）。
     *
     * <p>取并集的理由：不同规则看的邻格不同 —— 看下方的规则会波及**上方**一格，
     * 看上方（含上方第二格）的规则会波及**下方**两格。多算的格子是幂等的空操作
     * （判定只依赖相邻格的 id，而我们从不改 id），所以没必要按规则分派两套窗口。
     */
    public static final int[] AFFECTED_OFFSETS = {1, 0, -1, -2};

    /**
     * 已发布且规则确证过的方块。
     *
     * <p>每一条后面都写着依据；改这里等于改玩法，请连依据一起改。
     */
    private static final Entry[] BUILTIN = {
            // 吊藤（BCLib BaseVineBlock）：上游 BlockLumabusVine.java:22 明确 extends BaseVineBlock，
            // BOTTOM 是发光的果（:38-40 lightLevel 只在 BOTTOM 给 15），也就是链尾。
            new Entry("betternether:lumabus_vine", "shape", Rule.HANGING_VINE),
            // 简单藤（bottom 布尔）：BlockBlackVine.java:8 extends BaseSimpleVineBlock；
            // blooming_vine 在 NetherVineBlocks.java:107 注册成 BlockBlackVine。
            new Entry("betternether:blooming_vine", "bottom", Rule.BOTTOM_FLAG),
            // 金藤：BlockGoldenVine.java extends BaseSimpleVineBlock（构造参数 29/0）。
            new Entry("betternether:golden_vine", "bottom", Rule.BOTTOM_FLAG)
    };

    private final Map<String, Entry> entries;

    private BlockShapeTable(Map<String, Entry> entries) {
        this.entries = entries;
    }

    /** 运行期用的表：{@link #BUILTIN} 那一份。 */
    public static BlockShapeTable builtin() {
        return parse(BUILTIN);
    }

    /** 测试用的入口：同样的构造方式，但表由调用方给。 */
    public static BlockShapeTable parse(Entry... rows) {
        Map<String, Entry> map = new LinkedHashMap<>();
        for (Entry row : rows) {
            if (map.put(row.id(), row) != null) {
                throw new IllegalArgumentException("方块在形状表里重复出现：" + row.id());
            }
        }
        return new BlockShapeTable(map);
    }

    /** 表里的条数。启动日志用一次，好让「表是空的」在日志里看得出来。 */
    public int size() {
        return entries.size();
    }

    /** 表里所有 id，按声明顺序。 */
    public Collection<String> ids() {
        return entries.keySet();
    }

    /** 这一格要不要管；不管返回 {@code null}。 */
    public @Nullable Entry entry(@Nullable String blockId) {
        return blockId == null ? null : entries.get(blockId);
    }

    /**
     * 算出这一格「应该」是什么属性值。
     *
     * <p>纯函数：只看同类 id 的位置关系，不看任何别人的形态 ——
     * 因此对同一个世界状态，先算哪一格、先写哪一格都不影响结果。
     *
     * @param self    这一格的方块 id（调用方已确认它在表里）
     * @return 目标值；{@code null} 表示「保持原样」，调用方不应写
     */
    public static @Nullable String evaluate(Rule rule, String self, Neighbours neighbours) {
        boolean sameAbove = self.equals(neighbours.above());
        boolean sameBelow = self.equals(neighbours.below());
        return switch (rule) {
            // 先判下方，再判上方，最后才轮到中间。
            case HANGING_VINE -> !sameBelow ? BOTTOM : !sameAbove ? TOP : MIDDLE;
            case BOTTOM_FLAG -> !sameBelow ? TRUE : FALSE;
        };
    }
}
