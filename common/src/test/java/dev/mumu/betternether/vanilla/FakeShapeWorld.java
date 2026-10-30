package dev.mumu.betternether.vanilla;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link BlockShapeReshaper.World} 的假实现：坐标 → (方块 id, 属性值) 两张表，写入记进 writes。
 *
 * <h2>为什么要假世界</h2>
 * 这些结论只依赖 {@link BlockShapeReshaper.World} 的五个方法。真去起一台带 CraftEngine 的
 * Folia 服务端验证「放下第二段之后上面那格是不是 middle」，既跑不动，也说不清失败的是算法
 * 还是环境（CE 没加载、资源包没发、玩家手速不对……）。
 *
 * <h2>刻意复刻的两件事</h2>
 * <ul>
 *   <li><b>放置后的初始值</b>：真实服务端放下方块拿到的是 YAML 的 {@code default}
 *       （这些方块在 CE 配置里全是 {@code default: bottom} / {@code "false"} / {@code up}），
 *       所以测试里用 {@link #place} 把「刚放下」的状态写成默认值，而不是写成一个已经正确的值 ——
 *       否则这些用例证明不了任何事。</li>
 *   <li><b>读与写分开记账</b>：{@link #writes()} 只记真的改了值的格子，
 *       {@link #reads()} 记 {@code blockId} 的查询次数 —— 「不在表里的方块一格都不碰」这条
 *       要靠写入数为 0 来断言，而不是靠「结果看起来对」。</li>
 * </ul>
 */
final class FakeShapeWorld implements BlockShapeReshaper.World {

    /** 一格坐标。{@code toString} 就是 "x,y,z"，测试里的断言直接写它。 */
    record Pos(int x, int y, int z) {
        Pos above() {
            return new Pos(x, y + 1, z);
        }

        Pos below() {
            return new Pos(x, y - 1, z);
        }

        @Override
        public String toString() {
            return x + "," + y + "," + z;
        }
    }

    private final Map<Pos, String> ids = new HashMap<>();
    private final Map<Pos, Map<String, String>> properties = new HashMap<>();
    private final Set<Pos> foreign = new HashSet<>();
    private final List<String> writes = new ArrayList<>();
    private final List<String> handedOff = new ArrayList<>();
    private final Set<Pos> failing = new HashSet<>();

    private int reads;
    private int foreignReads;

    /**
     * 在世界里放一格，属性取给定值（对「刚放下」的场景就是 CE 配置里的 {@code default}）。
     */
    FakeShapeWorld put(int x, int y, int z, String id, String property, String value) {
        Pos pos = new Pos(x, y, z);
        ids.put(pos, id);
        properties.computeIfAbsent(pos, ignored -> new LinkedHashMap<>()).put(property, value);
        return this;
    }

    /** 放下一个 {@code shape} 方块，取值是 CE 配置里的 {@code default: bottom}。 */
    FakeShapeWorld placeShape(int x, int y, int z, String id) {
        return put(x, y, z, id, "shape", BlockShapeTable.BOTTOM);
    }

    /** 放下一个 {@code bottom} 布尔方块，取值是 CE 配置里的 {@code default: "false"}。 */
    FakeShapeWorld placeBottomFlag(int x, int y, int z, String id) {
        return put(x, y, z, id, "bottom", BlockShapeTable.FALSE);
    }

    /** 这一格归另一个区域线程管。 */
    FakeShapeWorld foreign(int x, int y, int z) {
        foreign.add(new Pos(x, y, z));
        return this;
    }

    /** 写入会被拒绝（CE 拒绝、或方块已经不是那个方块了）。 */
    FakeShapeWorld failing(int x, int y, int z) {
        failing.add(new Pos(x, y, z));
        return this;
    }

    @Override
    public @Nullable String blockId(int x, int y, int z) {
        Pos pos = new Pos(x, y, z);
        reads++;
        if (foreign.contains(pos)) {
            foreignReads++;
        }
        return ids.get(pos);
    }

    @Override
    public @Nullable String propertyValue(int x, int y, int z, String property) {
        Pos pos = new Pos(x, y, z);
        if (foreign.contains(pos)) {
            foreignReads++;
        }
        Map<String, String> values = properties.get(pos);
        return values == null ? null : values.get(property);
    }

    @Override
    public boolean setProperty(int x, int y, int z, String property, String value) {
        Pos pos = new Pos(x, y, z);
        if (failing.contains(pos) || !ids.containsKey(pos)) {
            return false;
        }
        properties.computeIfAbsent(pos, ignored -> new LinkedHashMap<>()).put(property, value);
        writes.add(pos + " " + property + "=" + value);
        return true;
    }

    @Override
    public boolean ownedByCurrentRegion(int x, int y, int z) {
        return !foreign.contains(new Pos(x, y, z));
    }

    @Override
    public boolean handOff(int x, int y, int z) {
        handedOff.add(new Pos(x, y, z).toString());
        return true;
    }

    /** 这一格某个属性现在的值。 */
    @Nullable
    String valueAt(int x, int y, int z, String property) {
        Map<String, String> values = properties.get(new Pos(x, y, z));
        return values == null ? null : values.get(property);
    }

    /** 这一格 {@code shape} 现在的值。 */
    @Nullable
    String shapeAt(int x, int y, int z) {
        return valueAt(x, y, z, "shape");
    }

    /** 改写过的格子，按发生顺序，形如 {@code "0,7,0 shape=bottom"}。 */
    List<String> writes() {
        return List.copyOf(writes);
    }

    /** 转交出去的坐标，按发生顺序。 */
    List<String> handedOff() {
        return List.copyOf(handedOff);
    }

    /** {@code blockId} 被调用的次数。 */
    int reads() {
        return reads;
    }

    /** 落在「不归当前区域」坐标上的读取次数：必须为 0（跨区域一个字节都不读）。 */
    int foreignReads() {
        return foreignReads;
    }
}
