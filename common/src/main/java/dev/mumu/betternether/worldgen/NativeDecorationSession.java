package dev.mumu.betternether.worldgen;

import java.util.Set;

/**
 * 「原版装饰执行器」的**无 NMS 接口**。
 *
 * <h2>为什么要有这一层</h2>
 * 本工程的单测类路径**刻意不含服务端 jar**，而实现类的字段与字节码全是 NMS 类型
 * （{@code WorldGenLevel}、{@code StructureManager}、{@code FeaturePlacer}…）。实测过：
 * 那样的类在无服务端类路径上**连加载都会失败**（{@code NoClassDefFoundError:
 * net/minecraft/util/RandomSource}），因为 JVM 在链接期校验方法栈帧时就要解析这些类型。
 * 于是「传入 null / 传错对象会怎样」这条最该被测的降级路径根本跑不起来 —— 测到的是类加载错误。
 *
 * <p>所以入口与实现分成两半：
 * <ul>
 *   <li>{@link NativeDecorationBridge}（门面，**只依赖 JDK 与 Bukkit**）：判空、转调、接
 *       {@link LinkageError} 并记账，返回值就是本接口 —— 它的字节码里没有任何 NMS 符号；</li>
 *   <li>{@code NmsDecorationSession}（实现，包内可见）：所有真实 NMS 调用都在那里。</li>
 * </ul>
 * 门面的返回值用本接口而不是 {@code Object}：调用方（{@link DecorationScheduler}）拿到的就是
 * 一组签名干净的方法，不需要强转，也不会把 NMS 类型带进自己的字节码。
 *
 * <h2>方法语义</h2>
 * 全部方法只在「本区块」范围内成立，且都要求先由 {@link NativeDecorationBridge} 成功建出实例。
 */
public interface NativeDecorationSession {

    /**
     * 该区块的 decorationSeed（原版 {@code WorldgenRandom#setDecorationSeed} 的返回值）。
     *
     * <p>本移植自己编译的特征也用它 —— 两条路径的种子必须同源，否则同一个世界会长出两种东西。
     */
    long decorationSeed();

    /**
     * 跑某个 step 的**结构相位**：把该 step 下每个结构已生成的 start 放进这个区块。
     *
     * @return 放进本区块的 start 数量（统计用）
     */
    int placeStructures(int step);

    /**
     * 只跑该 step 注册表顺序里 {@code [fromIndex, toIndexExclusive)} 这一段，
     * 并可按「插入了一个虚拟条目」修正 {@code j}。
     *
     * <h2>为什么需要「一段」和「虚拟条目」</h2>
     * 上游 {@code betternether:mega_lava_lake} **是数据包里的结构**（
     * {@code src/main/generated/data/betternether/worldgen/structure/mega_lava_lake.json}，
     * {@code step: surface_structures}），而 wover 的 bootstrap 在数据包元素注册**之后**才跑，
     * 且对已存在的 key 直接 {@code return holder}（不改 id、不挪位置）。所以它在 step 4 里的槽位是
     * **按路径排序的位置**（altars, gardens, ghast_hive, jungle_temples, **mega_lava_lake**, pillars…），
     * 它之后的每个条目都因此 +1。
     *
     * <p>本移植注册不了这个结构（structure type 是 wover 的），所以只能：结构相位在它的槽位处
     * **让位**（这一段放到它前面、下一段放到它后面），并在计算 {@code j} 时把那个虚拟条目算进去。
     * 这样每个真实结构的 {@code j} 与上游逐位相同，虚拟条目自己的 {@code j} 由
     * {@link #structureSlot(int, String)} 给出。
     *
     * @param fromIndex        注册表顺序里的起点（含）
     * @param toIndexExclusive 终点（不含）
     * @param virtualSlot     虚拟条目在该 step 的槽位；{@code < 0} 表示没有虚拟条目（{@code j} = 下标）
     * @return 放进本区块的 start 数量（统计用）
     */
    int placeStructures(int step, int fromIndex, int toIndexExclusive, int virtualSlot);

    /**
     * 某个结构在该 step 的**真实槽位**（{@code j}）。
     *
     * <p>若这个 id 真在注册表里，返回它的下标；否则返回「按注册表排序时它会插入的位置」
     * （= 排在它前面的条目数）。后者正是上游给数据包结构的槽位口径。
     *
     * @return {@code -1} = 该 step 没有条目，或拿不到注册表
     */
    int structureSlot(int step, String structureId);

    /**
     * 该 step 的注册表条目数（诊断用；也是「末尾插入点」）。
     *
     * <p>原版 {@code j} 计的是**该 step 的注册表条目**（{@code j} 从 0 起、每条 +1），
     * 不是「本维度有 start 的结构数」。条目占 {@code 0..size-1}。
     *
     * <p><b>注意</b>：对「本移植注册不了、但上游存在于数据包里的结构」（熔岩湖），
     * 它的槽位**不是**这个数 —— 那是按路径排序的位置，见 {@link #structureSlot(int, String)}。
     */
    int nextStructureIndex(int step);

    /**
     * 跑某一条原版 placed feature，随机流由 {@code (step, index)} 决定。
     *
     * @return {@code true} = 注册表里有这条且已执行；{@code false} = 没有这条 id（已记账）
     */
    boolean placeFeature(int step, int index, String placedFeatureId);

    /**
     * 跑某个 step 里全部**原版**条目（按 {@code (step, index)} 升序）。
     *
     * @return 真正执行过的条数
     */
    int placeNativeFeatures(int step, DecorationScheduler.Plan plan);

    /**
     * 本区块邻域（3×3 区块）里出现过的群系 key，取自真实区块的群系调色板。
     *
     * <p>这是原版 {@code addVanillaDecorations} 决定「哪些群系算在本区块内」的**唯一**口径。
     */
    Set<String> biomeKeys();

    /** 收尾：原版在整道装饰末尾会把 {@code currentlyGenerating} 清空。 */
    void close();
}
