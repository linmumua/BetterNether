package dev.mumu.betternether.biome;

import com.mojang.datafixers.util.Pair;
import net.minecraft.world.level.biome.Climate;

import java.util.ArrayList;
import java.util.List;

/**
 * 生产实现：把 28 个参数点塞进原版 {@code Climate.ParameterList<String>}，用它的
 * {@code findValue} 裁决 —— 与运行时同一个类、同一棵树、同一套并列规则。
 *
 * <h2>构造方式（全部原版公开入口，javap 已核）</h2>
 * <ul>
 *   <li>{@code Climate.Parameter.point(float)} —— 内部走 {@code Climate.quantizeCoord}，
 *       所以量化由原版完成，本类不重复实现一遍截断。</li>
 *   <li>{@code Climate.ParameterPoint(Parameter,Parameter,Parameter,Parameter,Parameter,Parameter,long)}
 *       —— 七个分量对应 JSON 的 {@code temperature/humidity/continentalness/erosion/depth/weirdness/offset}。</li>
 *   <li>{@code Climate.ParameterList(List<Pair<ParameterPoint,T>>)} —— 构造时建好最近邻树。</li>
 *   <li>{@code findValue(Climate.TargetPoint)} —— 26.3 Paper 里它被替换成 Moonrise 的
 *       {@code ChunkGenRTree.moonrise$findNearest}，这正是运行时用的那一个。</li>
 * </ul>
 *
 * <h2>顺序</h2>
 * 条目顺序 = {@link BiomePlacement#climates()} 的顺序 = 原版 5 个在前、BetterNether 23 个按
 * {@link NetherBiomes#ALL} 在后。这就是 Fabric {@code NetherBiomes#addNetherBiome} 把参数点注入
 * 原版 {@code minecraft:nether} 预设后得到的顺序。
 *
 * <h2>为什么不在测试里跑</h2>
 * 本类引用 {@code net.minecraft.*}，而测试运行期类路径刻意不含 serverJar。测试用
 * {@link BiomePlacement#select} 这个线性参考实现；两者的等价性是在仓库外用真服务端 jar
 * 逐点量出来的（401×401 网格 0 不一致，见 {@link BiomeSelector} 的类注释）。
 */
public final class NmsBiomeSelector implements BiomeSelector {
    /** 28 个点的树只建一次；{@code ParameterList} 构造后不可变。 */
    private static final class Holder {
        private static final Climate.ParameterList<String> LIST = build();

        private static Climate.ParameterList<String> build() {
            List<Pair<Climate.ParameterPoint, String>> entries =
                    new ArrayList<>(BiomePlacement.climates().size());
            for (BiomePlacement.Climate row : BiomePlacement.climates()) {
                entries.add(Pair.of(point(row), row.key()));
            }
            return new Climate.ParameterList<>(entries);
        }

        private static Climate.ParameterPoint point(BiomePlacement.Climate row) {
            return new Climate.ParameterPoint(
                    Climate.Parameter.point(row.temperature()),
                    Climate.Parameter.point(row.humidity()),
                    // 23 个 biome_data 文件与 5 个原版点在这四轴上都写 0.0（见 BiomePlacement 表注释）。
                    Climate.Parameter.point(0.0f),
                    Climate.Parameter.point(0.0f),
                    Climate.Parameter.point(0.0f),
                    Climate.Parameter.point(0.0f),
                    Climate.quantizeCoord(row.offset()));
        }
    }

    private static final NmsBiomeSelector SHARED = new NmsBiomeSelector();

    private NmsBiomeSelector() {
    }

    /** 进程内共用一个实例；底下的树是不可变的。 */
    public static NmsBiomeSelector shared() {
        return SHARED;
    }

    @Override
    public String select(TargetPoint target) {
        return Holder.LIST.findValue(new Climate.TargetPoint(
                target.temperature(),
                target.humidity(),
                target.continentalness(),
                target.erosion(),
                target.depth(),
                target.weirdness()));
    }
}
