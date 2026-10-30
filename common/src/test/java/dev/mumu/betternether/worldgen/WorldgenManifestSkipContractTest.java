package dev.mumu.betternether.worldgen;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 清单条目契约：{@code skip: true} 是**显式占位**，可执行条目编译失败必须点名。
 *
 * <h2>为什么要这条契约</h2>
 * {@code features[step]} 里每条条目都占一个下标，而这个下标进随机流
 * （{@code setFeatureSeed(seed, featureIndex, step)}），所以「条目在不在」是语义，
 * 「条目由谁执行」只是归属问题。两种完全不同的情况以前在代码里长得一模一样：
 * <ul>
 *   <li>矿 / 已移除的上游条目 —— 本来就不该由本移植执行，占位即可；</li>
 *   <li>本该由本移植执行、却因为数据缺失编译不出来 —— 这是一条特征**静默消失**。</li>
 * </ul>
 * 现在前者由清单作者写 {@code "skip": true} 明说，后者则在 notes 里被点名。两者的可观测差别
 * 就是本测试要钉住的东西。
 *
 * <h2>为什么能不带资源直接测</h2>
 * {@link WorldgenLoader#compileBiomes(JsonObject)} 只吃一份解析好的 {@code biomes} 段
 * （{@code worldgen-upstream/manifest.json} 是 {@code :plugin} 的构建产物，{@code :common}
 * 测试类路径上不存在）。于是这里的清单是合成的最小语料，断言的是**读取规则**本身。
 */
class WorldgenManifestSkipContractTest {

    /** 三个条目都必须留在原位置：skip 占位、native、以及编译失败的普通 id。 */
    @Test
    void skipEntriesKeepTheirSlotAlongsideNativeAndFailedOnes() {
        WorldgenLoader loader = new WorldgenLoader();
        JsonObject biomes = biomes("""
                {"betternether:test_biome":{"0":[
                    {"id":"betternether:removed_thing","skip":true},
                    {"id":"minecraft:some_native_feature","native":true},
                    "betternether:missing_plain_id"
                ]}}
                """);

        Map<String, List<CompiledFeature>> compiled = loader.compileBiomes(biomes);
        List<CompiledFeature> plan = compiled.get("betternether:test_biome");
        assertNotNull(plan, "群系必须出现在计划里");
        assertEquals(3, plan.size(), "三条条目一条都不能少（删一条后面 featureIndex 全前移）");

        assertEquals("betternether:removed_thing", plan.get(0).id());
        assertTrue(plan.get(0).isIndexPlaceholder(), "skip:true 是占位，不编译也不执行");
        assertEquals(0, plan.get(0).index(), "skip 条目的下标必须原样保留");

        assertTrue(plan.get(1).isNativePlaced(), "native:true 走原版执行路径");
        assertEquals(1, plan.get(1).index());

        assertEquals("betternether:missing_plain_id", plan.get(2).id());
        assertTrue(plan.get(2).isIndexPlaceholder(), "编译失败也要占住槽位");
        assertEquals(2, plan.get(2).index(), "失败条目的下标同样不能挪");
    }

    /** {@code skip:true} 不计入失败，也不该被报成「缺 placed_feature」。 */
    @Test
    void explicitSkipIsNotReportedAsAFailure() {
        WorldgenLoader loader = new WorldgenLoader();
        loader.compileBiomes(biomes("""
                {"betternether:test_biome":{"0":[
                    {"id":"betternether:ore_only_entry","skip":true}
                ]}}
                """));

        assertTrue(loader.notes().stream().anyMatch(note -> note.contains("skip:true")),
                "显式占位必须有一条账：" + loader.notes());
        assertFalse(loader.notes().stream().anyMatch(note -> note.contains("缺少 placed_feature")),
                "skip 条目没有 JSON 是正常的，不该报缺文件：" + loader.notes());
        assertFalse(loader.notes().stream().anyMatch(
                        note -> note.contains("可执行 placed feature 编译失败")),
                "skip 条目不该计入失败（失败汇总行只统计没有 skip 标记的条目）：" + loader.notes());
    }

    /** 可执行条目编译失败必须**点名**，并且给出具体 id。 */
    @Test
    void failedExecutableEntryIsNamedInTheNotes() {
        WorldgenLoader loader = new WorldgenLoader();
        loader.compileBiomes(biomes("""
                {"betternether:test_biome":{"0":[
                    "betternether:should_have_compiled"
                ]}}
                """));

        assertTrue(loader.notes().stream().anyMatch(note -> note.contains("编译失败")
                        && note.contains("betternether:should_have_compiled")),
                "失败汇总行必须带 id，不能只有一个数字：" + loader.notes());
        assertTrue(loader.notes().stream().anyMatch(note -> note.contains("缺少 placed_feature")
                        && note.contains("betternether:should_have_compiled")),
                "还必须有「缺哪份 JSON」这一条：" + loader.notes());
    }

    /** {@code skip} 与 {@code native} 同时出现时按 skip 处理（不执行），并留一条账。 */
    @Test
    void skipWinsOverNativeAndSaysSo() {
        WorldgenLoader loader = new WorldgenLoader();
        Map<String, List<CompiledFeature>> compiled = loader.compileBiomes(biomes("""
                {"betternether:test_biome":{"0":[
                    {"id":"minecraft:conflicting_entry","native":true,"skip":true}
                ]}}
                """));

        CompiledFeature entry = compiled.get("betternether:test_biome").get(0);
        assertTrue(entry.isIndexPlaceholder(), "矛盾清单按「不执行」处理，绝不两边都算");
        assertFalse(entry.isNativePlaced());
        assertTrue(loader.notes().stream().anyMatch(note -> note.contains("conflicting_entry")
                        && note.contains("skip")),
                "矛盾必须能被看见：" + loader.notes());
    }

    /** {@code skip} 不是布尔值 → 抛，不猜「该不该编译」。 */
    @Test
    void nonBooleanSkipIsRejected() {
        for (String skip : List.of("\"yes\"", "1", "{}", "[true]")) {
            WorldgenLoader loader = new WorldgenLoader();
            assertThrows(IllegalStateException.class,
                    () -> loader.compileBiomes(biomes(
                            "{\"b\":{\"0\":[{\"id\":\"betternether:x\",\"skip\":" + skip + "}]}}")),
                    "skip = " + skip + " 不是布尔值，必须显式拒绝");
        }
    }

    /** step 与 index 全部原样保留（含跳号 step 与多群系）。 */
    @Test
    void stepsAndIndicesArePreservedAcrossBiomes() {
        WorldgenLoader loader = new WorldgenLoader();
        Map<String, List<CompiledFeature>> compiled = loader.compileBiomes(biomes("""
                {"biome_a":{"4":[{"id":"betternether:a0","skip":true},"betternether:a1"],
                    "9":[{"id":"betternether:a2","skip":true}]},
                 "biome_b":{"9":[{"id":"minecraft:native0","native":true}]}}
                """));

        List<CompiledFeature> a = compiled.get("biome_a");
        assertEquals(3, a.size());
        assertEquals(4, a.get(0).step());
        assertEquals(0, a.get(0).index());
        assertEquals(4, a.get(1).step());
        assertEquals(1, a.get(1).index());
        assertEquals(9, a.get(2).step());
        assertEquals(0, a.get(2).index(), "每个 step 的下标各自从 0 开始");

        List<CompiledFeature> b = compiled.get("biome_b");
        assertEquals(1, b.size());
        assertEquals(9, b.get(0).step());
        assertEquals(0, b.get(0).index());
    }

    private static JsonObject biomes(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }
}
