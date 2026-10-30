package dev.mumu.betternether.worldgen;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 清单里 {@code owner} 字段的**读取规则**：它只是类别标注，条目仍然**可执行**。
 *
 * <h2>为什么要单独钉一条</h2>
 * 运行期只有一个装饰计划（{@code NetherTerrainGenerator} 里单一
 * {@code DecorationScheduler.load(manifest)}，按 step 拆阶段执行），它不 load
 * {@code terrain.json}。所以清单作者如果怕「没人执行」而给地形条目补上
 * {@code "skip": true}，{@link WorldgenLoader#compileBiomes} 会照字面把它做成纯占位 ——
 * 27 条地形特征（69 个槽位）在世界里直接消失，而且日志只会说「按 skip 显式占位」，
 * 看起来完全正常。
 *
 * <p>本类锁住两点：
 * <ol>
 *   <li>{@code {"id": …, "owner": "terrain"}} 走**可执行**分支：会尝试编译，缺文件时被
 *       点名成「可执行 placed feature 编译失败」，而不会被当成显式占位；</li>
 *   <li>{@code owner} 与 {@code skip} 同时出现时，装载器按 skip 处理 —— 这正是发布侧
 *       绝不允许出现那种组合的原因（发布侧由 {@code scripts/tests/test_worldgen_nested_refs.py}
 *       对真实产物断言）。</li>
 * </ol>
 *
 * <h2>为什么不带资源也能测</h2>
 * 与 {@link WorldgenManifestSkipContractTest} 同一手法：{@code :common} 的测试类路径上没有
 * {@code worldgen-upstream/}，所以这里断言的是「读到 owner 后走哪条分支」，而「文件确实存在」
 * 由 Python 侧的合并产物测试负责。两半合起来才是完整契约。
 */
class WorldgenManifestOwnerEntryTest {

    /** owner 条目必须是可执行条目：会去编译、缺文件时点名，而不是被当作显式占位。 */
    @Test
    void ownerEntryTakesTheExecutablePath() {
        WorldgenLoader loader = new WorldgenLoader();
        Map<String, List<CompiledFeature>> compiled = loader.compileBiomes(biomes("""
                {"betternether:test_biome":{"1":[
                    "betternether:first_slot",
                    {"id":"betternether:terrain_owned_thing","owner":"terrain"}
                ]}}
                """));

        List<CompiledFeature> plan = compiled.get("betternether:test_biome");
        assertNotNull(plan, "群系必须出现在计划里");
        assertEquals(2, plan.size(), "owner 条目也必须占住自己的槽位");
        assertEquals(1, plan.get(1).index(), "owner 条目的下标不能挪");
        assertEquals(1, plan.get(1).step());
        assertFalse(plan.get(1).isNativePlaced(), "地形类别不该走原版执行路径");

        assertTrue(loader.notes().stream().anyMatch(note -> note.contains("缺少 placed_feature")
                        && note.contains("betternether:terrain_owned_thing")),
                "owner 条目必须走「去编译」这条分支（缺文件时才点名）：" + loader.notes());
        assertTrue(loader.notes().stream().anyMatch(note -> note.contains("编译失败")
                        && note.contains("betternether:terrain_owned_thing")),
                "owner 条目失败要算进可执行条目的失败汇总，不能被当成正常占位：" + loader.notes());
        assertFalse(loader.notes().stream().anyMatch(note -> note.contains("skip:true")),
                "owner 条目不是显式占位，不该出现在 skip 账里：" + loader.notes());
    }

    /** {@code owner} + {@code skip} 同时出现 → 装载器按 skip 处理（发布侧绝不能这么写）。 */
    @Test
    void ownerWithSkipDegradesToAPlaceholder() {
        WorldgenLoader loader = new WorldgenLoader();
        Map<String, List<CompiledFeature>> compiled = loader.compileBiomes(biomes("""
                {"betternether:test_biome":{"1":[
                    {"id":"betternether:terrain_owned_thing","owner":"terrain","skip":true}
                ]}}
                """));

        CompiledFeature entry = compiled.get("betternether:test_biome").get(0);
        assertTrue(entry.isIndexPlaceholder(),
                "owner 带上 skip 就变成纯占位 —— 这条特征不会放置，槽位只剩 index 意义");
        assertFalse(loader.notes().stream().anyMatch(note -> note.contains("缺少 placed_feature")),
                "显式占位不该去读文件：" + loader.notes());
        assertTrue(loader.notes().stream().anyMatch(note -> note.contains("skip:true")),
                "这种情况至少要有一条 skip 账，让人能看见它是被跳过的：" + loader.notes());
    }

    private static JsonObject biomes(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }
}
