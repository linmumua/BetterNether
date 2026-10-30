package dev.mumu.betternether.worldgen;

/**
 * 结构相位里 {@code j}（{@code setFeatureSeed} 的下标）与注册表下标的换算 —— **纯算术，无 NMS**。
 *
 * <h2>为什么单独一个类</h2>
 * 这段换算是「本移植注册不了、但上游存在于数据包里的结构」唯一会改变**种子**的地方：
 * 上游 {@code betternether:mega_lava_lake} 是数据包结构（
 * {@code generated/data/betternether/worldgen/structure/mega_lava_lake.json}，
 * {@code step: surface_structures}），数据包元素按 {@code Identifier} 排序先进表，wover 的 bootstrap
 * 之后才跑且对已存在 key 只 {@code return holder}（不改 id）。所以它占的是**排序位**，
 * 而本移植的注册表里没有它 —— 结构相位必须在它那里断开，它之后的每个条目 {@code j} 都要 +1。
 *
 * <p>把这段算术放在一个不依赖服务端的类里，是为了能**在纯 JVM 单测里断言那几个具体数字**
 * （{@code 3→3, 4→5, 9→10}）。只断言「populator 顺序」是不够的：顺序对但换算错，全世界种子偏移 1。
 */
final class NativeDecorationSlots {

    private NativeDecorationSlots() {
    }

    /**
     * 注册表下标 → 该条目实际拿到的 {@code j}。
     *
     * <p>虚拟条目（熔岩湖）占了 {@code virtualSlot} 这个槽位，所以
     * <ul>
     *   <li>{@code index < virtualSlot}：不受影响，{@code j = index}；</li>
     *   <li>{@code index >= virtualSlot}：整体后移一位，{@code j = index + 1}。</li>
     * </ul>
     *
     * @param index       该条目在本 step 注册表顺序里的下标
     * @param virtualSlot 虚拟条目的槽位；{@code < 0} = 没有虚拟条目（{@code j = index}）
     */
    static int slot(int index, int virtualSlot) {
        return virtualSlot >= 0 && index >= virtualSlot ? index + 1 : index;
    }
}
