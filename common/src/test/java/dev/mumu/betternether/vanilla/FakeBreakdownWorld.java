package dev.mumu.betternether.vanilla;

import dev.mumu.betternether.vanilla.BlockBreakdownTable.Side;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link BlockBreakdownCascade.World} 的假实现：坐标 → 方块 id 一张表，移除记进 drops。
 *
 * <h2>为什么要模拟 CraftEngine 的同步支撑行为</h2>
 * 这是整个功能最容易出错的现场：CE 的支撑行为在 {@code delay: 0} 时是**同步**的 —— 我们一拆掉
 * 一格的方块，它的邻居立刻在 {@code updateShape} 里被判成「不满足支撑」并静默改成空气（不掉落）。
 * 如果假世界不模拟这件事，那么「边拆边读」的实现也能一路绿灯，测试等于什么也没证明
 * （{@link BlockBreakdownCascadeTest#cePreemptionModelIsReal} 专门验证这个模拟不是摆设）。
 *
 * <p>模型刻意做窄，只复刻与本案有关的那一条规则：**被移除一格的邻居里，那些把这一格当作支撑、
 * 且自身被标记为 reactive 的方块，会被静默清空，并继续向它们的邻居传播**。真实 CE 还会做别的事
 * （音效、粒子、掉落表），这里一概不管。
 *
 * <h2>其他开关</h2>
 * <ul>
 *   <li>{@code sticky}：移除成功但世界仍报告那个方块（模拟被别的插件原地改回来）。</li>
 *   <li>{@code failing}：移除被拒绝。</li>
 *   <li>{@code foreign}：这一格归别的区域线程管（Folia 跨区域）。</li>
 * </ul>
 */
final class FakeBreakdownWorld implements BlockBreakdownCascade.World {

    /** 一格坐标。{@code toString} 就是 "x,y,z"，测试里的断言直接写它。 */
    record Pos(int x, int y, int z) {
        Pos relative(Side side) {
            return switch (side) {
                case ABOVE -> new Pos(x, y + 1, z);
                case BELOW -> new Pos(x, y - 1, z);
                case EAST -> new Pos(x + 1, y, z);
                case WEST -> new Pos(x - 1, y, z);
                case SOUTH -> new Pos(x, y, z + 1);
                case NORTH -> new Pos(x, y, z - 1);
            };
        }

        @Override
        public String toString() {
            return x + "," + y + "," + z;
        }
    }

    static final Side[] SIDES = Side.values();

    private final Map<Pos, String> ids = new HashMap<>();
    private final Map<Pos, Side> attached = new HashMap<>();
    /** 能当锚点的格子（实心方块）。只有 {@link Depends#SUPPORTED} 的柱子才会问它。 */
    private final Set<Pos> anchors = new HashSet<>();
    /** reactive 方块：它依赖 supportSides 那一侧，那一侧空了就会被 CE 静默清空。 */
    private final Map<Pos, Side> supportSides = new HashMap<>();
    private final Set<Pos> foreign = new HashSet<>();
    private final Set<Pos> sticky = new HashSet<>();
    private final Set<Pos> failing = new HashSet<>();
    private final Set<Pos> preempted = new HashSet<>();
    private final Map<Pos, Side> handedOff = new LinkedHashMap<>();
    private final List<Pos> drops = new ArrayList<>();
    private final List<Pos> silentClears = new ArrayList<>();

    private int reads;
    private int foreignReads;

    /** 普通方块：移除后世界就不再报告它。 */
    FakeBreakdownWorld put(int x, int y, int z, String id) {
        ids.put(new Pos(x, y, z), id);
        return this;
    }

    /** 移除成功但世界仍报告它（模拟被别的插件原地改回来）。 */
    FakeBreakdownWorld putSticky(int x, int y, int z, String id) {
        ids.put(new Pos(x, y, z), id);
        sticky.add(new Pos(x, y, z));
        return this;
    }

    /**
     * 带 CE 支撑行为的方块：{@code supportSide} 那一侧空了就会被 CE 静默清空（不掉落）。
     *
     * <p>{@code supportSide} 用「这个方块依赖哪一侧」表达，与清单的
     * {@code breakdown_direction} 同义。
     */
    FakeBreakdownWorld putReactive(int x, int y, int z, String id, Side supportSide) {
        Pos pos = new Pos(x, y, z);
        ids.put(pos, id);
        supportSides.put(pos, supportSide);
        return this;
    }

    FakeBreakdownWorld attach(int x, int y, int z, Side side) {
        attached.put(new Pos(x, y, z), side);
        return this;
    }

    /** 这一格归另一个区域线程管。 */
    FakeBreakdownWorld foreign(int x, int y, int z) {
        foreign.add(new Pos(x, y, z));
        return this;
    }

    /** 移除会被拒绝（CE 拒绝、或方块已不再属于本插件）。 */
    FakeBreakdownWorld failing(int x, int y, int z) {
        failing.add(new Pos(x, y, z));
        return this;
    }

    /**
     * 这一格会在执行阶段被 CE 抢先静默清空。
     *
     * <p>模拟一个我们无法确定性复现的竞态（CE 在收集与执行之间动了手）。它的用途是把
     * 「被抢先」这条分支逼出来：只记一次、不重复掉落。
     */
    FakeBreakdownWorld preemptOnAnyDrop(int x, int y, int z) {
        preempted.add(new Pos(x, y, z));
        return this;
    }

    @Override
    public String blockId(int x, int y, int z) {
        Pos pos = new Pos(x, y, z);
        reads++;
        if (foreign.contains(pos)) {
            foreignReads++;
        }
        return ids.get(pos);
    }

    @Override
    public boolean anchoredAt(int x, int y, int z) {
        return anchors.contains(new Pos(x, y, z));
    }

    /** 在这一格放一块「实心方块」，它可以作为柱子的锚点（但不是同类方块）。 */
    FakeBreakdownWorld anchor(int x, int y, int z) {
        anchors.add(new Pos(x, y, z));
        return this;
    }

    @Override
    public Side attachedSide(int x, int y, int z) {
        return attached.get(new Pos(x, y, z));
    }

    @Override
    public boolean removeWithDrops(int x, int y, int z) {
        Pos pos = new Pos(x, y, z);
        if (failing.contains(pos)) {
            return false;
        }
        if (!ids.containsKey(pos)) {
            // 与真实 CE 一致：方块状态已经空了就返回 false（不可能掉落）。
            return false;
        }
        drops.add(pos);
        if (!sticky.contains(pos)) {
            ids.remove(pos);
        }
        // 「被抢先」的格子：在本次掉落之后再清空，模拟 CE 挤在我们处理它之前。
        for (Pos gone : List.copyOf(preempted)) {
            if (ids.remove(gone) != null) {
                silentClears.add(gone);
                preempted.remove(gone);
            }
        }
        ceReactAround(pos);
        return true;
    }

    /**
     * CE 的同步支撑行为：被移除一格的邻居里，把这一格当支撑且自身 reactive 的方块被静默清空，
     * 并向它们的邻居继续传播。清空**不进 drops**——这正是上游的「静默消失」。
     */
    private void ceReactAround(Pos removed) {
        Deque<Pos> queue = new ArrayDeque<>();
        for (Side side : SIDES) {
            queue.add(removed.relative(side));
        }
        Set<Pos> seen = new HashSet<>();
        while (!queue.isEmpty()) {
            Pos candidate = queue.poll();
            if (!seen.add(candidate)) {
                continue;
            }
            Side support = supportSides.get(candidate);
            if (support == null) {
                continue;
            }
            // 支撑那一侧还有方块 → 它不塌。
            if (ids.containsKey(candidate.relative(support))) {
                continue;
            }
            if (ids.remove(candidate) != null) {
                silentClears.add(candidate);
                for (Side side : SIDES) {
                    queue.add(candidate.relative(side));
                }
            }
        }
    }

    @Override
    public boolean ownedByCurrentRegion(int x, int y, int z) {
        return !foreign.contains(new Pos(x, y, z));
    }

    @Override
    public boolean handOff(int x, int y, int z, Side removedSide) {
        Pos pos = new Pos(x, y, z);
        handedOff.put(pos, removedSide);
        return true;
    }

    /** 掉落式移除过的坐标，按发生顺序。 */
    List<String> drops() {
        return drops.stream().map(Pos::toString).toList();
    }

    /** 转交出去的坐标 → 交出去时「哪一侧的支撑没了」。 */
    Map<String, Side> handedOff() {
        Map<String, Side> result = new LinkedHashMap<>();
        handedOff.forEach((pos, side) -> result.put(pos.toString(), side));
        return result;
    }

    /** 被 CE 静默清空（没有掉落）的坐标，按发生顺序。 */
    List<String> silentClears() {
        return silentClears.stream().map(Pos::toString).toList();
    }

    /** {@code blockId} 被调用的次数。 */
    int reads() {
        return reads;
    }

    /** 其中落在「不归当前区域」坐标上的次数：必须为 0（跨区域一个字节都不读）。 */
    int foreignReads() {
        return foreignReads;
    }

    /** 这一格现在还是不是那个方块。 */
    String idAt(int x, int y, int z) {
        return ids.get(new Pos(x, y, z));
    }
}
