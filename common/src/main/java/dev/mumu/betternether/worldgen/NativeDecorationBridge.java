package dev.mumu.betternether.worldgen;

import org.bukkit.generator.LimitedRegion;

/**
 * 原版装饰执行的**无 NMS 门面**：判空 → 转调 → 接 {@link LinkageError} 并记账。
 *
 * <h2>为什么必须是「门面 + 实现」两层（这是实测结论，不是设计偏好）</h2>
 * 本工程的单测类路径**刻意不含服务端 jar**。而「把 NMS 调用放进 try 里」并不够：
 * 只要**类本身**的方法栈帧引用了 NMS 类型（{@code WorldgenRandom} 之类），JVM 在链接期
 * 校验栈帧时就要解析它们，于是类加载直接失败 ——
 * 实测 {@code Class.forName(NativeDecorationBridge)} 在无服务端类路径上抛
 * {@code NoClassDefFoundError: net/minecraft/util/RandomSource}，
 * 而同一路径上 {@link DecorationScheduler}（只在方法体里引用别人类）能正常加载。
 *
 * <p>所以两层是必须的：
 * <ul>
 *   <li><b>本类</b>：只碰 JDK 与 Bukkit 类型（{@code Object}、{@link LimitedRegion}、
 *       {@link NativeDecorationSession}）。{@code null} 分支在这里返回，
 *       「类路径上缺服务端」在这里被 {@link LinkageError} 接住 —— 两条路径都能在无服务端
 *       类路径上真实执行到，因此可测；</li>
 *   <li><b>{@code NmsDecorationSession}</b>（包内可见）：全部 NMS 逻辑。它只在本类的
 *       {@code try} 里被 {@code invokestatic}，解析失败就抛 {@link LinkageError}。</li>
 * </ul>
 *
 * <h2>三条出口都只是「记账 + 返回 {@code null}」</h2>
 * 装饰阶段抛异常会把整个区块生成打坏，所以失败必须变成「这一批原版东西没放」的降级记录，
 * 而不是异常。三个 key 稳定、各记一次：
 * {@code native-bridge:no-region} / {@code native-bridge:null-level} /
 * {@code native-bridge:missing-nms}（另有实现侧的 {@code invalidtype} 等）。
 */
public final class NativeDecorationBridge {

    private NativeDecorationBridge() {
    }

    /**
     * 为这个区块开一次会话：入口是 Bukkit populator 收到的区域。
     *
     * @return {@code null} = 拿不到真实 {@code WorldGenLevel}（已记账一次），调用方必须降级
     */
    public static NativeDecorationSession open(LimitedRegion region, int chunkX, int chunkZ) {
        if (region == null) {
            WorldgenWarnings.degrade("native-bridge:no-region",
                    "没有装饰区域（LimitedRegion 为 null）："
                            + "原版结构与原版 placed feature 都无法真实执行。");
            return null;
        }
        try {
            // 这一行才要求解析 CraftLimitedRegion 等 NMS 类型；缺类时抛 LinkageError，被下面接住。
            return NmsDecorationSession.openRegionTyped(region, chunkX, chunkZ);
        } catch (LinkageError error) {
            WorldgenWarnings.degrade("native-bridge:missing-nms", reasonMissingNms(error));
            return null;
        }
    }

    /**
     * 同上，入口是 {@link WorldView#nativeWorldGenLevel()} 交出来的 {@code Object}。
     *
     * <p>参数是 {@code Object} 是刻意的：{@link WorldView} 必须能在没有 NMS 的类路径上加载，
     * 所以它只能交出 {@code Object}。判定用的是**真实的** {@code instanceof WorldGenLevel}
     * （在实现侧，不按类型名猜、不做反射）。
     */
    public static NativeDecorationSession open(Object nativeLevel, int chunkX, int chunkZ) {
        if (nativeLevel == null) {
            WorldgenWarnings.degrade("native-bridge:null-level", reasonNull());
            return null;
        }
        try {
            return NmsDecorationSession.openTyped(nativeLevel, chunkX, chunkZ);
        } catch (LinkageError error) {
            WorldgenWarnings.degrade("native-bridge:missing-nms", reasonMissingNms(error));
            return null;
        }
    }

    /**
     * 该 step 的结构注册表条目数 = 「排在它们之后的结构会拿到的 {@code j}」——**免会话**版本。
     *
     * <p>给「不在注册表里、却要按结构相位播种」的调用方（本移植的巨型熔岩湖）用：它每区块只需要
     * 这一个整数，为此建一整个会话（{@code ChunkAccess}、{@code FeaturePlacer}、
     * {@code WorldgenRandom}、可写区、调色板扫描的前置）是白花的。这里只读一次结构注册表。
     *
     * <p>语义与 {@link NativeDecorationSession#nextStructureIndex(int)} 完全相同，且**已经被核实为
     * 上游的位置**：wover 的 {@code ResourceManagerRegistryLoadTaskMixin#wover_bootstrap} 把 code
     * 条目追加在数据包条目（按 {@code Identifier} 排序）之后，所以上游 step 4 的最后一个条目就是
     * {@code mega_lava_lake}，它的 {@code j} 正是「该 step 已有的条目数」。可复现的是**位置语义**；
     * 整数本身随数据包集合变（26.3 的 vanilla step-4 是 44 条 + 我们以数据包形式发的 9 条）。
     *
     * <p>原版 {@code j} 的计数口径：条目占 {@code 0..size-1}，追加在它们之后的结构拿到的正是
     * {@code size} —— 不是 {@code size-1}，也不含调用方自身。
     *
     * @return {@code -1} = 拿不到（没有区域 / 类路径上没有服务端）。**调用方不得编造一个下标**：
     *         这个数的意义就是「与注册表同源」，猜一个等于换一条流。
     */
    public static int nextStructureIndex(LimitedRegion region, int step) {
        if (region == null) {
            WorldgenWarnings.degrade("native-bridge:no-region",
                    "没有装饰区域（LimitedRegion 为 null）："
                            + "无法读出该 step 的结构注册表条目数。");
            return -1;
        }
        try {
            return NmsDecorationSession.countStructuresInStep(region, step);
        } catch (LinkageError error) {
            WorldgenWarnings.degrade("native-bridge:missing-nms", reasonMissingNms(error));
            return -1;
        }
    }

    /**
     * 某结构在该 step 的真实槽位 {@code j}（{@code -1} = 拿不到）。
     *
     * <p>上游 {@code betternether:mega_lava_lake} **是数据包结构**
     * （{@code src/main/generated/data/betternether/worldgen/structure/mega_lava_lake.json}，
     * {@code step: surface_structures}）：数据包元素先注册，wover 的 bootstrap 后跑，且
     * {@code makeContext(...).register(...)} 对**已存在且已绑定**的 key 只 {@code return holder}
     * （不换 id、不挪位置）。所以它的槽位是**按路径排序的位置**（本项目 = 4），它之后的每个
     * step-4 条目都因此 +1。
     */
    public static int structureSlot(LimitedRegion region, int step, String structureId) {
        if (region == null) {
            WorldgenWarnings.degrade("native-bridge:no-region",
                    "没有装饰区域（LimitedRegion 为 null）：无法计算结构槽位。");
            return -1;
        }
        try {
            return NmsDecorationSession.structureSlot(region, step, structureId);
        } catch (LinkageError error) {
            WorldgenWarnings.degrade("native-bridge:missing-nms", reasonMissingNms(error));
            return -1;
        }
    }

    /** 同上，世界创建期从 Bukkit 世界查询（此时注册表已冻结，算一次即可）。 */
    public static int structureSlot(org.bukkit.World world, int step, String structureId) {
        if (world == null) {
            return -1;
        }
        try {
            return NmsDecorationSession.structureSlot(world, step, structureId);
        } catch (LinkageError error) {
            WorldgenWarnings.degrade("native-bridge:missing-nms", reasonMissingNms(error));
            return -1;
        }
    }

    /** 降级理由：null 入参。 */
    static String reasonNull() {
        return "原版装饰入口拿到 null（WorldView#nativeWorldGenLevel 没有世界对象）："
                + "原版结构与原版 placed feature 都不会执行。";
    }
    /** 降级理由：对象不是世界（类型能解析，但类型不对）。 */
    static String reasonInvalidType(Object candidate) {
        return "原版装饰入口拿到的是 " + candidate.getClass().getName()
                + "，不是 net.minecraft.world.level.WorldGenLevel："
                + "原版结构与原版 placed feature 都不会执行。";
    }

    /**
     * 降级理由：类路径上没有服务端（{@code WorldGenLevel} 解析不了）。
     *
     * <p>这条最容易被误读成「插件有 bug」，所以理由里必须写清**来源**：本工程单测的类路径
     * 刻意不含服务端 jar，所以它在那条路径上是预期结果；生产环境出现它才是装配问题。
     */
    static String reasonMissingNms(Throwable error) {
        return "运行期类路径上缺少服务端类，无法解析 net.minecraft.world.level.WorldGenLevel"
                + "（" + error.getClass().getName() + ": " + error.getMessage() + "）："
                + "原版结构与原版 placed feature 都不会执行。"
                + "生产环境出现这条请检查服务端 jar 是否在插件可见的类路径上；"
                + "单测类路径按设计不含服务端 jar，在那里出现属于预期。";
    }
}
