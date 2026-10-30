package dev.mumu.betternether.vanilla;

import dev.mumu.betternether.vanilla.BlockShapeTable.Entry;
import dev.mumu.betternether.vanilla.BlockShapeTable.Neighbours;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * 一格被放下之后，把这一列上该重算的形态重算掉。
 *
 * <h2>为什么不止算被放下的那一格</h2>
 * 上游的 {@code updateShape} 是邻居驱动的：接上一格时，**上面那一格**要跟着从末端变成中间段，
 * 否则一串藤永远只有最末端那格是对的、其余全是末端 —— 这正是用户报的现象之一
 * （「放第二段时第一段不会从 BOTTOM 变成 MIDDLE，永远连不成串」）。所以放下之后要重算的是
 * {@link BlockShapeTable#AFFECTED_OFFSETS} 给出的那一小段列，而不是一格。
 *
 * <h2>为什么可以先算完再写、不必按方向排序</h2>
 * 判定只看**相邻格的 id 是不是同一个方块**（以及葫芦藤自己当前的值），从不看邻居的形态；
 * 而本类从不改任何一格的 id。于是每一格的结论与写入顺序无关 —— 这也是为什么这里没有
 * 级联破坏那边「由深到浅」的讲究。
 *
 * <h2>幂等</h2>
 * 目标值与当前值相同时不写。所以「多算几格」「跨区域转交之后再算一遍」「同一 tick 里被
 * 两个事件各算一次」都不会产生额外写入，也不会抖动。
 *
 * <h2>Folia</h2>
 * 每一次读或写之前都先问 {@link World#ownedByCurrentRegion}：不归当前区域的格子一个字节都不读，
 * 而是把这一格交给 {@link World#handOff}，由那边的区域线程重跑一遍完整判定。
 * 老实说，Folia 的区域是按 XZ 列划分的，**竖直方向的邻格永远同属一个区域**，所以真实服务器上
 * 这条护栏基本不会触发；保留它是为了让实现不依赖「我记得区域是竖的」这个前提 ——
 * 一旦哪天规则变成要看水平邻居，这里不用重新设计。测试里也能把它逼出来。
 *
 * <h2>不做的事</h2>
 * <ul>
 *   <li>不动不在表里的方块（{@link BlockShapeTable} 之外一格都不碰）；</li>
 *   <li>不动掉落、不动断链逻辑（那是 {@link BlockBreakdownListener} 的活）；</li>
 *   <li>不做「放到哪都自动补成整段柱状」—— 只按邻居算，绝不凭空加长或缩短；</li>
 *   <li>不猜 {@code side_*} / {@code corner_*} 这类复杂形状（见 {@link BlockShapeTable} 类注释）。</li>
 * </ul>
 */
public final class BlockShapeReshaper {

    /**
     * 重算要用到的世界最小视图。
     *
     * <p>与 {@link BlockBreakdownCascade.World} 同样刻意不传 {@code Block}：它是活句柄，
     * 跨区域持有可能失效，单元测试里也构造不出来。
     */
    public interface World {
        /** 这一格在 CraftEngine 里的方块 id；空气 / 原版方块 / 读不到时返回 {@code null}。 */
        @Nullable
        String blockId(int x, int y, int z);

        /** 这一格某个 string 属性当前的值；没有这个属性、或读不到时返回 {@code null}。 */
        @Nullable
        String propertyValue(int x, int y, int z, String property);

        /**
         * 把这一格的某个属性改成给定值，其余属性与方块身份保持不变。
         *
         * @return 是否真的写成功
         */
        boolean setProperty(int x, int y, int z, String property, String value);

        /** 该坐标是否归当前区域线程（Folia）。原版 Paper 上恒为 {@code true}。 */
        boolean ownedByCurrentRegion(int x, int y, int z);

        /**
         * 该坐标不归当前区域：交给拥有它的区域线程，由那边重新判定。
         *
         * @return 是否已转交；{@code false} 表示放弃这一格
         */
        boolean handOff(int x, int y, int z);
    }

    /**
     * 一次重算的结果，只为日志与测试。
     *
     * @param examined  命中表、被算过的格数
     * @param written   真的改写了形态的格数
     * @param handedOff 转交给别的区域线程的格数
     * @param refused   该写但写不进去的格数（CE 拒绝了、方块已经不是那个方块等）
     */
    public record Result(int examined, int written, int handedOff, int refused) {

        /** 空结果：事件里那一格连表都没进时的常态。 */
        public static final Result NONE = new Result(0, 0, 0, 0);

        Result plus(Result other) {
            return new Result(
                    examined + other.examined,
                    written + other.written,
                    handedOff + other.handedOff,
                    refused + other.refused);
        }
    }

    private final BlockShapeTable table;
    private final World world;

    public BlockShapeReshaper(BlockShapeTable table, World world) {
        this.table = table;
        this.world = world;
    }

    /**
     * 一次放置的入口：重算这一列上所有该重算的格子（含被放下那一格自己）。
     *
     * @return 汇总结果
     */
    public Result reshapeAround(int x, int y, int z) {
        Run run = new Run(x, z);
        Result total = Result.NONE;
        for (int offset : BlockShapeTable.AFFECTED_OFFSETS) {
            total = total.plus(reshapeCell(run, x, y + offset, z));
            if (total.handedOff() > 0) {
                // 这一列已经交给别的区域线程重做了：本线程继续算只会和那边打架。
                // 真实 Folia 上不可能发生（竖直邻格同区域），见类注释。
                break;
            }
        }
        return total;
    }

    /** 单格重算。{@link World#handOff} 回来时也走这里（换成拥有它的线程再调一次）。 */
    public Result reshapeCell(int x, int y, int z) {
        return reshapeCell(new Run(x, z), x, y, z);
    }

    private Result reshapeCell(Run run, int x, int y, int z) {
        // 归属先问：不归当前区域的格子**一个字节都不读**（见类注释的 Folia 段）。
        if (!owned(x, y, z)) {
            return handOver(x, y, z);
        }
        String self = run.idAt(y);
        Entry entry = table.entry(self);
        if (entry == null) {
            return Result.NONE;
        }
        String current = run.propertyAt(y, entry.property());
        Neighbours neighbours = new Neighbours(run.idAt(y + 1), run.idAt(y + 2), run.idAt(y - 1));
        String desired = BlockShapeTable.evaluate(entry.rule(), self, neighbours);
        if (desired == null || desired.equals(current)) {
            return new Result(1, 0, 0, 0);
        }
        return write(run, x, y, z, entry, desired);
    }

    private boolean owned(int x, int y, int z) {
        return world.ownedByCurrentRegion(x, y, z);
    }

    private Result handOver(int x, int y, int z) {
        return world.handOff(x, y, z) ? new Result(1, 0, 1, 0) : Result.NONE;
    }

    private Result write(Run run, int x, int y, int z, Entry entry, String value) {
        if (value.equals(run.propertyAt(y, entry.property()))) {
            return new Result(1, 0, 0, 0);
        }
        if (!world.setProperty(x, y, z, entry.property(), value)) {
            return new Result(1, 0, 0, 1);
        }
        // 缓存跟着更新：同一轮里若还有格子读这一格的值，看到的必须是写进去的那个。
        run.remember(y, entry.property(), value);
        return new Result(1, 1, 0, 0);
    }

    /**
     * 一次重算内按 y 缓存方块 id 与属性值。
     *
     * <p>必要性：相邻的几个候选格共享邻格（{@code y-2} 那一格的 {@code above2} 就是
     * {@code y} 那一格自己），不缓存的话同一个坐标要被 CE 反查好几次。缓存里允许 {@code null}
     * （空气/读不到也是一种结论），所以用 {@code containsKey} 判断而不是 {@code get}。
     */
    private final class Run {
        private final int x;
        private final int z;
        private final Map<Integer, String> ids = new HashMap<>();
        private final Map<String, String> properties = new HashMap<>();

        Run(int x, int z) {
            this.x = x;
            this.z = z;
        }

        @Nullable
        String idAt(int y) {
            if (!ids.containsKey(y)) {
                ids.put(y, world.blockId(x, y, z));
            }
            return ids.get(y);
        }

        @Nullable
        String propertyAt(int y, String property) {
            String key = y + "/" + property;
            if (!properties.containsKey(key)) {
                properties.put(key, world.propertyValue(x, y, z, property));
            }
            return properties.get(key);
        }

        void remember(int y, String property, @Nullable String value) {
            properties.put(y + "/" + property, value);
        }
    }
}
