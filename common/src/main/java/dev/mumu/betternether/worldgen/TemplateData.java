package dev.mumu.betternether.worldgen;

import org.bukkit.block.data.BlockData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 一个已经压平的结构模板：一串「相对坐标 + 方块」。
 *
 * <h2>为什么在构建期把 NBT 转成 JSON</h2>
 * 运行期只需要「哪个坐标放哪个状态」，不需要一个完整的 NBT 读取器。构建期
 * （{@code scripts/bnlib/worldgen.py}）把上游 29 个 gzip 结构模板压成三列平行数组，
 * 运行期只做一次 Gson 解析 —— 服务端启动路径上不该出现压缩流与 NBT 标签解析。
 *
 * <h2>为什么空气在装载期就被剔掉</h2>
 * 模板里的空气占绝大多数（一棵 21×24×21 的巨型蘑菇有 925 个非空气方块、上万格体积）。
 * 只保留非空气方块，数组小一个数量级，运行期也不必逐个判断「这是不是空气」。
 * 无法解析的方块 id 同样在这里剔除，原因汇总到诊断里（不静默）。
 */
public final class TemplateData {
    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final int[] xs;
    private final int[] ys;
    private final int[] zs;
    private final @Nullable BlockData[] states;

    /** 不带尺寸的构造（测试用）：尺寸为 0 时居中偏移为 0，等价于原地放置。 */
    TemplateData(int @NotNull [] xs, int @NotNull [] ys, int @NotNull [] zs,
                 @Nullable BlockData @NotNull [] states) {
        this(0, 0, 0, xs, ys, zs, states);
    }

    TemplateData(int sizeX, int sizeY, int sizeZ,
                 int @NotNull [] xs, int @NotNull [] ys, int @NotNull [] zs,
                 @Nullable BlockData @NotNull [] states) {
        if (xs.length != ys.length || ys.length != zs.length || zs.length != states.length) {
            throw new IllegalArgumentException("模板坐标数组长度不一致");
        }
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        this.xs = xs;
        this.ys = ys;
        this.zs = zs;
        this.states = states;
    }

    /** 模板在 x 方向的尺寸（未旋转的原始尺寸）。 */
    public int sizeX() {
        return sizeX;
    }

    /** 模板在 y 方向的尺寸。 */
    public int sizeY() {
        return sizeY;
    }

    /** 模板在 z 方向的尺寸。 */
    public int sizeZ() {
        return sizeZ;
    }

    /** 非空气方块数量。 */
    public int size() {
        return xs.length;
    }

    public int x(int index) {
        return xs[index];
    }

    public int y(int index) {
        return ys[index];
    }

    public int z(int index) {
        return zs[index];
    }

    /** 该位置的方块；未解析的 id 在转换时已剔除，这里不会返回 null。 */
    public @Nullable BlockData state(int index) {
        return states[index];
    }
}
