package dev.mumu.betternether.vanilla;

import dev.mumu.betternether.vanilla.BlockBreakdownTable.Depends;
import dev.mumu.betternether.vanilla.BlockBreakdownTable.Side;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 一格失去支撑后，把「依赖它」的植物沿链拆下去，并且**掉落**。
 *
 * <h2>为什么必须分成两个阶段</h2>
 * 直觉写法是「边走边拆」：发现一格依赖刚消失的方块，立刻把它拆掉并继续往下走。这条路在
 * CraftEngine 上会**只掉第一层**，而且看不出错：
 * <ol>
 *   <li>我们拆掉第 1 层；</li>
 *   <li>这次移除会触发邻居更新，CE 的支撑行为（{@code delay: 0}，同步）立刻把第 2 层
 *       判成 {@code canSurvive == false} 并**静默改成空气**（这是它本来的语义，它不负责掉落）；</li>
 *   <li>等我们回头处理第 2 层时，那里已经是空气，只能跳过 —— 于是第 2 层、第 3 层……全部静默消失。</li>
 * </ol>
 * 所以顺序必须是：**先把整条链一次性读出来（全程不碰任何方块，CE 就没有机会动手），
 * 再逐格掉落式移除**。
 *
 * <h2>为什么执行顺序是「由深到浅」</h2>
 * 每拆一格都会触发邻居更新。由浅到深拆时，拆掉第 1 层会让第 2 层立刻失去支撑被 CE 抢走
 * （又会静默消失）；由深到浅拆时，先消失的是链尾，而链上每一格的支撑仍留在它靠的那一侧
 * （{@code up} 链的支撑在上方、{@code down} 链的支撑在下方、{@code both} 链只要任一侧还在就成立），
 * 所以谁都不会在轮到它之前被别人带走。
 *
 * <h2>三条护栏</h2>
 * <ul>
 *   <li><b>已访问集合</b>：每个坐标一次运行内最多进计划一次。三维网格里的链条本身走不成环，
 *       这条真正防的是「同一格被它自己的邻居反复发现」——例如移除被别的插件拦下、方块被原地
 *       改回来时，没有它就会在两格之间弹到深度上限，而且每一轮都真掉一次东西。</li>
 *   <li><b>深度上限</b> {@link #MAX_DEPTH}：一层算一格。单次事件里最多收集 64 层，给一个 tick
 *       的工作量封顶；截断会如实报告（{@link Result#truncated()}），不是安静取舍。</li>
 *   <li><b>执行前再确认</b>：每一格开拆之前重读一次 id —— 变成别的东西就放过（不归我们拆），
 *       已经空了就只记一次 {@link Result#preempted()}（CE 抢在了前面）。</li>
 * </ul>
 *
 * <h2>不做的事</h2>
 * <ul>
 *   <li>不判断「这一格原本该不该在那」：只认清单 + 方位，别的方块一律不动。</li>
 *   <li>不直接跨区域读方块：收集阶段遇到不归当前区域的邻居，**一个字节都不读**，整格交给
 *       {@link World#handOff}，并把它自己的方位一并交代过去 —— 那边必须重新判定一次，不能
 *       沿用这里的结论。</li>
 *   <li>不用 {@code setType(AIR)}：唯一出口是 {@link World#removeWithDrops}。掉落正是本功能与
 *       上游「静默消失」的差别，接缝上不留一条不掉落的路。</li>
 * </ul>
 */
public final class BlockBreakdownCascade {

    /**
     * 单次级联的最大层数（一层 = 一格）。
     *
     * <p>这是一个**有代价的选择**，不是「够用就行」的数字：
     * <ul>
     *   <li>上限太低，长链的链尾就不掉 —— 而链确实可能很长：报告里 {@code anchor_tree_vine} 的
     *       CE 支撑声明写着 {@code max_height: 255}，一条吊满整段的藤蔓就是这个量级；</li>
     *   <li>上限太高，最坏情况会在一个 tick 里做几百次方块查询与移除，而那是在区域线程上，
     *       下一个 tick 的服务器所有人一起等。</li>
     * </ul>
     * 取 64 是这两者之间明确、可解释的折中，并且截断会被如实报告。
     */
    public static final int MAX_DEPTH = 64;

    /** 世界可建高度下界（1.18+ 主世界/下界共用 {@code -64..319}）。 */
    private static final int MIN_BUILD_Y = -64;
    /** 世界可建高度上界。 */
    private static final int MAX_BUILD_Y = 319;

    /**
     * 级联要用到的世界最小视图。
     *
     * <p>所有坐标都是世界绝对坐标（与 {@code Block#getX/Y/Z} 一致），实现方负责把开箱即用的
     * Bukkit 对象翻译成它。故意不传 {@code Block}：{@code Block} 是活句柄，跨区域持有可能失效，
     * 而且在单元测试里没法构造。
     */
    public interface World {
        /** 这一格在 CraftEngine 里的方块 id；空气 / 原版方块 / 已经没有自定义方块时返回 {@code null}。 */
        @Nullable String blockId(int x, int y, int z);

        /**
         * 这一格能不能当**锚点**：即它是实心方块、可以把一串同类方块吊住/撑住。
         *
         * <p>为什么单独一个方法：{@link #blockId} 对「空气」和「实心原版方块」**都返回
         * {@code null}**（它只认 CraftEngine 自定义方块），没法区分。而钟乳石/石笋要不要跟着
         * 塌，恰恰取决于柱子尽头是空气还是实心方块 —— 只有原版方块或自定义方块才算锚点。
         *
         * @return 该格有方块（非空气）时返回 {@code true}
         */
        boolean anchoredAt(int x, int y, int z);

        /**
         * 这一格方块自己声明的贴面方向（{@code facing} 之类属性），读不到返回 {@code null}。
         *
         * <p>只有 {@link Depends#FACING} 会问它。读不到就不猜，见
         * {@link BlockBreakdownTable#breaksWhen}。
         */
        @Nullable Side attachedSide(int x, int y, int z);

        /**
         * 掉落式移除这一格。
         *
         * <p>实现必须走「像玩家挖掉」的那条路径（CraftEngine 自己算掉落），而不是写空气。
         *
         * @return 是否真的移除了
         */
        boolean removeWithDrops(int x, int y, int z);

        /** 该坐标是否归当前区域线程（Folia）。原版 Paper 上恒为 {@code true}。 */
        boolean ownedByCurrentRegion(int x, int y, int z);

        /**
         * 该坐标不归当前区域：交给拥有它的区域线程，由那边**重新判定并处理**。
         *
         * <p>对方区域拿到的信息只有「这个坐标」和「它哪一侧的支撑没了」，剩下的判断（是不是清单
         * 里的方块、方向对不对、要不要掉落）必须自己重做 —— 父区域的结论在下一个 tick 可能已经
         * 过时了。
         *
         * @param removedSide 消失的支撑相对这一格的方位
         * @return 是否已转交；{@code false} 表示放弃这一格
         */
        boolean handOff(int x, int y, int z, Side removedSide);
    }

    /**
     * 一次级联的结果，只为日志与测试。
     *
     * @param removed   实际掉落式移除的格数
     * @param preempted 计划里有、执行时已经是空气的格数（CE 抢在前面静默拆掉了）
     * @param depth     收集阶段用到的层数
     * @param handedOff 转交给别的区域线程的格数
     * @param truncated 是否因深度上限而截断（链还没走完就到了上限）
     */
    public record Result(int removed, int preempted, int depth, int handedOff, boolean truncated) {
    }

    /** 计划里的一格：坐标 + 收集时读到的 id（执行时要靠它认出「换人了」）。 */
    private record Step(int x, int y, int z, String id) {
    }

    /** 只读收集的产物：按层组织的拆除计划（第 0 层离断口最近）。 */
    private record Plan(List<List<Step>> levels, int handedOff, boolean truncated) {
    }

    private static final Side[] SIDES = Side.values();

    private final BlockBreakdownTable table;
    private final World world;
    private final int maxDepth;

    public BlockBreakdownCascade(BlockBreakdownTable table, World world) {
        this(table, world, MAX_DEPTH);
    }

    /** 测试用：允许把深度上限压到很小，好把「上限生效」测出来。 */
    public BlockBreakdownCascade(BlockBreakdownTable table, World world, int maxDepth) {
        this.table = table;
        this.world = world;
        this.maxDepth = Math.max(1, maxDepth);
    }

    /**
     * 从「刚刚消失的那一格」开始，把依赖它的链拆完。
     *
     * @param x 刚消失的方块的 X（玩家挖掉的那格、或爆炸清单里的某一格）
     * @param y 同上
     * @param z 同上
     * @return 本次做了多少事；不会抛，异常由调用方（事件处理）兜
     */
    public Result run(int x, int y, int z) {
        return run(x, y, z, null);
    }

    /**
     * 同上，但起点自己还在世界里，需要先判定它该不该被拆。
     *
     * <p>这条入口是给跨区域转交用的：另一个区域线程拿到「这一格的某一侧支撑没了」之后，
     * 必须自己重新判定一次（是不是清单方块、方向对不对），判定通过才拆，然后才从它继续往下走。
     *
     * @param removedSide 消失的支撑相对起点的方位；{@code null} 表示起点已经不在了
     */
    public Result run(int x, int y, int z, @Nullable Side removedSide) {
        Plan plan = collect(x, y, z, removedSide);
        int removed = 0;
        int preempted = 0;

        // 由深到浅：先消失链尾，链上每一格的支撑都还在它靠的那一侧，谁也抢不到谁。
        for (int level = plan.levels().size() - 1; level >= 0; level--) {
            for (Step step : plan.levels().get(level)) {
                String current = world.blockId(step.x(), step.y(), step.z());
                if (current != null && !current.equals(step.id())) {
                    // 这一格被换成了别的东西（另一个插件的方块、或者刚被放下）：不归我们拆。
                    continue;
                }
                if (world.removeWithDrops(step.x(), step.y(), step.z())) {
                    removed++;
                } else if (current == null) {
                    // 收集时它还在，执行时已经空了 —— CE 的同步支撑行为抢在了前面。
                    preempted++;
                }
            }
        }

        return new Result(removed, preempted, plan.levels().size(), plan.handedOff(),
                plan.truncated());
    }

    /**
     * 只读阶段：把整条依赖链读出来，**一个方块都不改**。
     *
     * <p>「不改」是这一整套东西成立的前提：CE 的支撑行为是同步的，我们一动手它就会把下一层
     * 静默改成空气，而那时我们还没来得及读它。
     */
    private Plan collect(int x, int y, int z, @Nullable Side removedSide) {
        Set<Long> visited = new HashSet<>();
        visited.add(key(x, y, z));

        List<List<Step>> levels = new ArrayList<>();
        List<Step> frontier = new ArrayList<>();
        int handedOff = 0;
        boolean truncated = false;

        if (removedSide == null) {
            // 起点已经不在了：它只作为「支撑」出现在判定里，不进计划。
            frontier.add(new Step(x, y, z, ""));
        } else {
            if (!world.ownedByCurrentRegion(x, y, z)) {
                // 调度器本该把我们放在它的区域上。拿不到归属就不猜，整格放弃。
                return new Plan(List.of(), 0, false);
            }
            Step root = judge(x, y, z, removedSide);
            if (root == null) {
                return new Plan(List.of(), 0, false);
            }
            levels.add(List.of(root));
            frontier.add(root);
        }

        while (!frontier.isEmpty()) {
            if (levels.size() >= maxDepth) {
                truncated = true;
                break;
            }
            List<Step> next = new ArrayList<>();
            for (Step origin : frontier) {
                for (Side side : SIDES) {
                    int nx = origin.x() + offsetX(side);
                    int ny = origin.y() + offsetY(side);
                    int nz = origin.z() + offsetZ(side);
                    if (!visited.add(key(nx, ny, nz))) {
                        continue;
                    }
                    // origin 是刚消失（或即将消失）的那一格，它相对候选方块的方位是 side 的反面。
                    Side removedSideOfCandidate = side.opposite();
                    if (!world.ownedByCurrentRegion(nx, ny, nz)) {
                        // 跨区域：一个字节都不读，连「该不该拆」的判断也一并交出去。
                        if (world.handOff(nx, ny, nz, removedSideOfCandidate)) {
                            handedOff++;
                        }
                        continue;
                    }
                    Step step = judge(nx, ny, nz, removedSideOfCandidate);
                    if (step != null) {
                        next.add(step);
                    }
                }
            }
            if (next.isEmpty()) {
                break;
            }
            levels.add(List.copyOf(next));
            frontier = next;
        }

        return new Plan(List.copyOf(levels), handedOff, truncated);
    }

    /**
     * 单格判定：这一格在 {@code removedSide} 那一侧的支撑没了之后该不该拆。
     *
     * @return 该拆时返回它的计划条目；不该拆（空气/清单外/方向不符）返回 {@code null}
     */
    private @Nullable Step judge(int x, int y, int z, Side removedSide) {
        String id = world.blockId(x, y, z);
        if (id == null) {
            // 空气、原版方块，或 CE 已经把它 setAir 掉了 —— 都不做掉落。
            return null;
        }
        Depends depends = table.depends(id);
        if (depends == null) {
            // 清单外的方块：一律不动，避免改变其它玩法。
            return null;
        }
        Side attachedSide = depends == Depends.FACING ? world.attachedSide(x, y, z) : null;
        if (depends == Depends.SUPPORTED) {
            // 原版钟乳石/石笋语义：只有**背向锚点**那一侧才塌。沿「远离断口」的方向找到
            // 这根柱子的尽头 —— 尽头是实心方块，说明这一格仍被撑住，不动它；尽头是空气，
            // 说明它和它外侧的一串都已经悬空，拆。
            //
            // 这一档是为了满足用户「和原版钟乳石一样」的要求，**有意偏离上游**
            // （上游 `BlockStalactite.destroy()` 两个方向都拆），见 BlockBreakdownTable
            // 的 SUPPORT_DIRECTED。
            if (anchoredAway(x, y, z, removedSide, id)) {
                return null;
            }
            return new Step(x, y, z, id);
        }
        if (!BlockBreakdownTable.breaksWhen(depends, removedSide, attachedSide)) {
            return null;
        }
        return new Step(x, y, z, id);
    }

    /**
     * 从这一格沿「远离断口」的方向找柱子尽头，判断尽头是不是锚点。
     *
     * @param removedSide 断口（被拆掉的那格）相对这一格的方位；反方向就是柱子外侧
     * @return 外侧尽头是实心方块时 {@code true}（= 这一格还被撑着，不该塌）
     */
    private boolean anchoredAway(int x, int y, int z, Side removedSide, String id) {
        Side away = removedSide.opposite();
        int dx = offsetX(away);
        int dy = offsetY(away);
        int dz = offsetZ(away);
        if (dy == 0) {
            // 水平方向不是柱体该有的形态：保守不动，宁可少拆也不要按错误方向拆。
            return true;
        }
        for (int step = 1; step <= MAX_DEPTH; step++) {
            int ny = y + dy * step;
            if (ny < MIN_BUILD_Y || ny > MAX_BUILD_Y) {
                // 出世界上下界：当作锚点，别把整根柱子拆穿。
                return true;
            }
            int nx = x + dx * step;
            int nz = z + dz * step;
            if (id.equals(world.blockId(nx, ny, nz))) {
                continue;   // 还是同类，继续往尽头找
            }
            return world.anchoredAt(nx, ny, nz);
        }
        // 超过深度上限还没走完：保守不动。
        return true;
    }

    /** 坐标打包成 set 的键：三维世界坐标在 int 范围内，一个 long 装得下。 */
    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (y & 0xFFF) << 26) | (z & 0x3FFFFFF);
    }

    private static int offsetX(Side side) {
        return switch (side) {
            case EAST -> 1;
            case WEST -> -1;
            default -> 0;
        };
    }

    private static int offsetY(Side side) {
        return switch (side) {
            case ABOVE -> 1;
            case BELOW -> -1;
            default -> 0;
        };
    }

    private static int offsetZ(Side side) {
        return switch (side) {
            case SOUTH -> 1;
            case NORTH -> -1;
            default -> 0;
        };
    }
}
