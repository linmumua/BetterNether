package dev.mumu.betternether.worldgen;

import java.util.List;

/**
 * 一条已编译的 placed feature：修饰符链 + 特征 + 它在上游数据里的位置。
 *
 * <p>{@code id} 保留下来是为了日志与测试能指名道姓：一条特征在三处出错（多了、少了、
 * 放错地方）时，只有 id 能让人从日志回到那个 JSON。
 *
 * <h2>为什么需要 {@code step} 与 {@code index}</h2>
 * 上游装饰不是「一个群系一张清单」按顺序跑，而是按
 * {@code GenerationStep.Decoration} 的 11 个 step 分轮执行，且**每条 placed feature 的随机流
 * 由它在 step 里的位置决定**：
 * {@code setFeatureSeed(decorationSeed, featureIndex, step)} →
 * {@code setSeed(decorationSeed + featureIndex + 10000L * step)}。
 *
 * <p>所以「这条特征属于哪个 step、在 step 里排第几」是**数据**，不是可以事后猜的排序细节：
 * step 写错一位，整条流错开 10000 个种子；index 错一位，就换了一条完全不同的流。
 * 两个字段都由构建期清单从上游 {@code features[N]} 数组直接读出（{@code N} = step），
 * 运行期不再推断。
 *
 * <h2>三种条目，三种归属</h2>
 * <ol>
 *   <li><b>本移植编译的特征</b>（{@code feature != null}）：修饰符链与特征算法都在本项目里，
 *       由 {@link #placeAt} 走自己的管线放置。</li>
 *   <li><b>原版 placed feature</b>（{@link #isNativePlaced}）：上游 {@code features[step]} 里的
 *       {@code minecraft:} 条目。它们**不需要手抄**——服务端注册表里本来就有那条
 *       {@code PlacedFeature}，运行期由 {@link NativeDecorationBridge} 用原版
 *       {@code FeaturePlacer#placeWithBiomeCheck} 真实跑一次。{@code placement}/{@code feature}
 *       为 {@code null} 是刻意的：本类不持有它的修饰符链，那是注册表的事。</li>
 *   <li><b>只占下标的空位</b>（{@link #isIndexPlaceholder}）：另有 owner 编译并放置的条目
 *       （矿归 {@code OrePopulator}，地形算法归 {@code UpstreamTerrainFeatures}）。
 *       它**必须留在计划里**，否则后面每条特征的 featureIndex 都会前移。</li>
 * </ol>
 *
 * <h2>{@code index} 与调度器算出来的「全局下标」不是同一个东西</h2>
 * <ul>
 *   <li>{@code index}（本记录）= 该群系 {@code features[step]} 数组内部的原始位置，即**上游注入顺序**。
 *       它只用于诊断与「同一群系内相对顺序」的核对。</li>
 *   <li>真正进种子的两个数字由 {@link DecorationScheduler} 计算：{@code step} 与
 *       **该 step 最终有序列表里的位置**。原版 {@code FeatureSorter} 会把所有群系的同 step
 *       条目合并成一个全局列表（带相邻链约束的拓扑排序），
 *       {@code StepFeatureData#indexMapping()} 就是那个列表的下标。</li>
 * </ul>
 *
 * @param id           placed feature id，形如 {@code betternether:patch_stalactite}
 * @param step         {@code GenerationStep.Decoration} 的 ordinal（上游 {@code features[N]} 的 N）
 * @param index        该群系 {@code features[step]} 数组内的位置
 * @param placement    修饰符链（已按 JSON 顺序编译）；原版条目为 {@code null}
 * @param feature      特征本体；原版条目与空位条目为 {@code null}
 * @param nativePlaced 是否由服务端注册表里的真实 {@code PlacedFeature} 执行
 */
public record CompiledFeature(String id, int step, int index,
                              List<PlacementModifier> placement, Feature feature,
                              boolean nativePlaced) {

    /** 未知 step 的哨兵值：装载器没给出这一步的归属时必须让它显形，而不是默认成 0。 */
    public static final int UNKNOWN_STEP = -1;

    /**
     * 五参构造：本移植编译的特征。
     *
     * <p>保留下来是因为「是不是原版条目」不是调用点要关心的事 —— 装载器读到一个能编译的
     * JSON 就直接建它，{@code nativePlaced} 恒为 {@code false}。
     */
    public CompiledFeature(String id, int step, int index,
                           List<PlacementModifier> placement, Feature feature) {
        this(id, step, index, placement, feature, false);
    }

    /**
     * 兼容构造：迁移期（装载器还没输出 step 时）仍可编译。
     *
     * <p>step/index 都是 {@link #UNKNOWN_STEP}，{@link DecorationScheduler} 会在建计划时
     * **明确报错**而不是把它们塞进某个 step —— 猜一个 step 等于换一条随机流。
     */
    public CompiledFeature(String id, List<PlacementModifier> placement, Feature feature) {
        this(id, UNKNOWN_STEP, UNKNOWN_STEP, placement, feature, false);
    }

    /** 是否带了可信的 step/index 归属。 */
    public boolean hasStep() {
        return step >= 0;
    }

    /**
     * 是否是**原版 placed feature**：运行期按 {@link #id} 从服务端注册表取真实
     * {@code PlacedFeature}，用原版 {@code FeaturePlacer#placeWithBiomeCheck} 执行。
     *
     * <p>为什么必须有这一类（而不是给原版条目也编译一份）：上游 23 个群系的
     * {@code features[step]} 里共有 414 条 {@code minecraft:} 引用，手抄它们的
     * {@code placed_feature}/{@code feature} JSON 既没有必要（服务端注册表里就有），
     * 又会把「上游用的原版数据版本」固定在抄写那一刻。走注册表，版本跟着服务端走。
     *
     * <p>它的随机流与下标口径同第 2 类：{@code setFeatureSeed(decorationSeed, index, step)}，
     * {@code index} 仍是该 step 全局有序列表里的位置（原版 {@code FeatureSorter} 的口径），
     * 所以两种条目混排在同一个 step 里时，谁都不会因为「另一种条目存在」而错位。
     */
    public boolean isNativePlaced() {
        return nativePlaced;
    }

    /**
     * 是否是「只占 step 里的一个下标、当前不在本类里放置」的条目。
     *
     * <h2>为什么需要占位</h2>
     * 上游 {@code features[step]} 数组里混着大量 {@code minecraft:} 的原版 placed feature ——
     * 实测 23 个群系共 733 条引用，其中 **414 条是 {@code minecraft:}**。
     * 种子里的 {@code featureIndex} 是**整条数组在全局有序列表里的下标**，所以只要漏掉其中一条，
     * 后面所有 BN 特征的 index 都会前移。举例（用上游 JSON 实算）：
     * {@code betternether:cincinnasite_ore} 的真实 index 是 **15**（前面压着 15 条原版条目），
     * {@code betternether:tree_gloomwood} 是 **36** —— 都不是「从 0 开始数 BN 条目」能得到的位置。
     *
     * <p>原版条目现在**不再是**占位：它们走 {@link #isNativePlaced()} 那条真实执行路径。
     * 仍留在占位语义里的，只有「另有一个 owner 会放、本调度器不该碰」的条目
     * （矿 / 地形算法）—— 这类条目只解决 index，不产出方块，其放置由那个 owner 负责。
     */
    public boolean isIndexPlaceholder() {
        return feature == null && !nativePlaced;
    }

    /** 过渡占位条目：只占住上游 {@code features[step]} 里那个下标，本身不放置任何东西。 */
    public static CompiledFeature placeholder(String id, int step, int index) {
        return new CompiledFeature(id, step, index, List.of(), null, false);
    }

    /**
     * 原版 placed feature 条目：只带 id 与 (step, index) 归属，执行权交给
     * {@link NativeDecorationBridge}（服务端注册表里的真实 {@code PlacedFeature}）。
     *
     * <p>装载器的用法只有一行：manifest 里 {@code {"id":"minecraft:xxx","native":true}} →
     * {@code CompiledFeature.nativePlaced(id, step, index)}。
     */
    public static CompiledFeature nativePlaced(String id, int step, int index) {
        return new CompiledFeature(id, step, index, List.of(), null, true);
    }

    /**
     * 从区块里的某个位置执行整条链（只对 {@link #isNativePlaced} 为 {@code false} 的条目有效）。
     *
     * <p><b>必须走 {@link Pipeline#placeChain}</b>（逐位置 DFS），不能「先 {@code run} 收完全部位置、
     * 再 {@code place} 逐个放」：两者的位置总量相同，但随机数消耗的<b>交错次序</b>不同。
     * 上游 wover 走 {@code FeaturePlacer} 的 stack DFS —— 一个位置被发出后立刻跑完链上剩余
     * 修饰符和特征，才回到上一个修饰符的下一步。任何「先批量变形、后批量放置」的实现都会在
     * 两级都抽样时（例如 {@code count_on_every_layer} 后接 {@code in_square}）产出另一批落点，
     * 也就是另一个世界。
     *
     * @return 放置成功的次数（只用于统计与测试）
     */
    public int placeAt(WorldView world, int x, int y, int z) {
        return Pipeline.placeChain(placement, new Point(x, y, z), world, feature);
    }
}
