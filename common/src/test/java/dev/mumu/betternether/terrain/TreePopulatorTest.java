package dev.mumu.betternether.terrain;

import dev.mumu.betternether.biome.NetherBiomes;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TreePopulator} 现在是**惰性的兼容外壳**：树的形状与调度都迁到了 worldgen JSON
 * （{@code bnlib/worldgen.py} 分类 → {@code WorldgenLoader} 编译 →
 * {@code UpstreamSimpleTreeFeatures} / {@code UpstreamComplexTreeFeatures} 提供形状）。
 *
 * <h2>这个测试在锁什么</h2>
 * 它锁的是「**不要再从这里放树**」这一条：
 * <ul>
 *   <li>23 个群系仍然都有 profile（缺一条会被 {@link TreePopulator#unauthoredBiomes} 当成
 *       未授权群系）；</li>
 *   <li>但每条 profile 必须**为空** —— 一旦有人往表里填回物种，同一位置就会由
 *       FloraPopulator 与 TreePopulator 各放一棵，表现为「叠在一起的树」，而这在游戏里
 *       完全不会报错。</li>
 * </ul>
 * 不引用任何具体 {@code Species} 常量是刻意的：物种枚举会随迁移继续收缩，
 * 断言「表为空」比「表里是哪几个物种」稳定得多。
 */
class TreePopulatorTest {

    private static List<String> biomeKeys() {
        List<String> keys = new ArrayList<>();
        for (NetherBiomes.Entry entry : NetherBiomes.ALL) {
            keys.add(entry.key());
        }
        return keys;
    }

    /** 每个群系都必须有一条 profile，否则它会被当成「未授权群系」而不生成树。 */
    @Test
    void everyBiomeHasAProfile() {
        List<String> keys = biomeKeys();
        assertEquals(23, keys.size(), "维度应有 23 个群系");
        assertEquals(List.of(), TreePopulator.unauthoredBiomes(keys),
                "以下群系没有 profile，会被当成未授权：");
    }

    /** 未声明的 key 必须被报出来，而不是被静默当作「有 profile」。 */
    @Test
    void unauthoredBiomesReportsUnknownKeys() {
        List<String> keys = new ArrayList<>(biomeKeys());
        keys.add("some_future_biome");
        assertEquals(List.of("some_future_biome"), TreePopulator.unauthoredBiomes(keys));
    }

    /**
     * **核心断言**：任何群系的 profile 都不得请求任何物种。
     *
     * <p>这就是「无重复派发」的语义：表全空 ⇒ 本类一个方块都不写 ⇒ 不会与 JSON 调度叠加。
     * 失败信息会点出是哪几个群系被填回了物种，便于直接回退那次改动。
     */
    @Test
    void noProfileDispatchesAnySpecies() {
        List<String> offenders = new ArrayList<>();
        for (String key : biomeKeys()) {
            Map<?, ?> profile = TreePopulator.profileOf(key);
            if (!profile.isEmpty()) {
                offenders.add(key + "=" + profile);
            }
        }
        assertTrue(offenders.isEmpty(),
                "树已全部迁移到 worldgen JSON；这些群系的地形 profile 仍请求物种，会与 "
                        + "FloraPopulator 双放：" + offenders);
    }

    /** 所有 profile 的并集为空 —— 与上一条互补，直接表达「本类不再派发」。 */
    @Test
    void theUnionOfAllProfilesIsEmpty() {
        int dispatched = 0;
        for (String key : biomeKeys()) {
            dispatched += TreePopulator.profileOf(key).size();
        }
        assertEquals(0, dispatched,
                "TreePopulator 应当是惰性外壳；实际有 " + dispatched + " 条物种派发");
    }

    /** 未知群系的 profile 必须是空表，而不是抛异常（populator 可能在边界区块被问到）。 */
    @Test
    void unknownBiomeYieldsAnEmptyProfile() {
        assertTrue(TreePopulator.profileOf("this_biome_does_not_exist").isEmpty());
        assertFalse(TreePopulator.profileOf("gloomwood") == null,
                "已知群系也必须返回非 null（哪怕是空表）");
    }
}
