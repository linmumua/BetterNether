package dev.mumu.betternether.vanilla;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.mumu.betternether.vanilla.BlockShapeTable.Entry;
import dev.mumu.betternether.vanilla.BlockShapeTable.Rule;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 运行期形状表与生成期产物的一致性。
 *
 * <h2>为什么必须逐条比对</h2>
 * 运行期表是**手抄**进 Java 的（{@code reports/**} 不进 jar，理由见
 * {@link BlockBreakdownTable} 的类注释，同一套约束）。抄错的后果是静默的，而且有两种：
 * <ul>
 *   <li>id 抄错 → 那个方块永远拿不到正确形态，玩家只会觉得「这个方块坏了」；</li>
 *   <li>属性名或取值抄错 → {@code valueByName} 查不到值，我们放弃写入，同样静默
 *       （真实实现里只留一条 WARNING，见 {@link BlockShapeOnPlaceListener}）。</li>
 * </ul>
 * 所以这里对着两份生成期产物核对：
 * <ul>
 *   <li>{@code reports/block-manifest.json} —— 这个方块**真的发布了**（没发布的 id 放进表里
 *       等于一行死代码）；</li>
 *   <li>{@code reports/plant-states.json} —— 属性名、属性类型、声明过的取值集，
 *       以及规则会写出的每一个值是否都在 {@code appearances} 里（写一个不存在的值，
 *       CE 那边凑不出状态，等于白写）。</li>
 * </ul>
 * 报告不存在时（clean 构建、只跑 {@code :common:test}）{@code assumeTrue} 跳过，不静默通过：
 * 表自身的条数与规则语义由 {@link BlockShapeReshaperTest} 在无报告时仍然守住。
 */
class BlockShapeTableTest {

    /**
     * 表里每一行都必须真的发布。
     *
     * <p>这条会挡住「照着上游凭记忆写 id」的错误 —— 上游有的方块本包不一定发布。
     */
    @Test
    void everyEntryIsActuallyPublished() throws IOException {
        Optional<JsonArray> manifest = readArray("block-manifest.json", "blocks");
        Assumptions.assumeTrue(manifest.isPresent(),
                "未找到 plugin/build/generated/bn/reports/block-manifest.json"
                        + "（由资源包生成流水线产出）；跳过「是否已发布」的核对。");

        Set<String> published = new HashSet<>();
        for (JsonElement element : manifest.get()) {
            JsonObject block = element.getAsJsonObject();
            if (block.has("id")) {
                published.add(block.get("id").getAsString());
            }
        }
        assertTrue(published.size() > 100, "清单小得不像话：" + published.size());

        List<String> missing = new ArrayList<>();
        for (String id : BlockShapeTable.builtin().ids()) {
            if (!published.contains(id)) {
                missing.add(id);
            }
        }
        assertEquals(List.of(), missing, "这些 id 在清单里不存在（没发布 / 抄错）：" + missing);
    }

    /**
     * 属性名、类型与取值集都要和生成出来的 CE 配置一致。
     *
     * <p>取值集不是「差不多就行」：规则写出的每个字面量都必须出现在该方块的
     * {@code appearances} 里，否则 {@code setProperty} 会凑不出合法状态而放弃写入 ——
     * 症状是「有些方块变了、有些没变」，最难查。
     */
    @Test
    void propertiesAndValueSetsMatchTheGeneratedConfig() throws IOException {
        Optional<JsonArray> states = readArray("plant-states.json", "blocks");
        Assumptions.assumeTrue(states.isPresent(),
                "未找到 plugin/build/generated/bn/reports/plant-states.json；跳过属性与取值集核对。");

        java.util.Map<String, JsonObject> byId = new java.util.LinkedHashMap<>();
        for (JsonElement element : states.get()) {
            JsonObject block = element.getAsJsonObject();
            byId.put(block.get("id").getAsString(), block);
        }

        for (String id : BlockShapeTable.builtin().ids()) {
            Entry entry = BlockShapeTable.builtin().entry(id);
            JsonObject block = byId.get(id);
            assertTrue(block != null, id + " 不在 plant-states.json 里，无法核对属性：" + id);

            JsonObject property = null;
            for (JsonElement candidate : block.getAsJsonArray("properties")) {
                JsonObject asObject = candidate.getAsJsonObject();
                if (entry.property().equals(asObject.get("name").getAsString())) {
                    property = asObject;
                }
            }
            assertTrue(property != null, id + " 的 CE 配置里没有属性 " + entry.property());

            String type = property.get("type").getAsString();
            assertEquals("string", type,
                    id + " 的形态属性应当是 string 类型（本表只处理字符串形态）");

            Set<String> values = appearanceValues(block.getAsJsonArray("appearances"), entry.property());
            for (String expected : expectedValues(entry)) {
                assertTrue(values.contains(expected),
                        id + " 的 appearances 里没有 " + entry.property() + "=" + expected
                                + "（现有：" + values + "）");
            }
        }
    }

    /** 表自身的结构：id 唯一、属性名非空、规则不是判定不出东西的占位值。 */
    @Test
    void tableIsWellFormed() {
        BlockShapeTable table = BlockShapeTable.builtin();
        assertEquals(3, table.size(), "表里应当正好 3 条已发布方块（lumabus/blooming/golden）");
        for (String id : table.ids()) {
            assertTrue(id.startsWith("betternether:"), "本表只该有本插件的方块：" + id);
            Entry entry = table.entry(id);
            assertTrue(!entry.property().isBlank(), id + " 的属性名不能为空");
            assertNull(table.entry("minecraft:stone"), "原版方块不在表里");
            assertNull(table.entry("betternether:definitely_not_a_shape_block"));
        }
    }

    /**
     * 明确不处理的方块必须在表里**不存在**。
     *
     * <p>这条是给未来的自己看的护栏：{@code brown_large_mushroom} 的
     * {@code side_*}/{@code corner_*} 无法从上游确证（它的 {@code updateShape} 根本不重算形状），
     * 一旦有人凭直觉把它加进表里，这里会红。
     */
    @Test
    void blocksWithUnprovableRulesAreNotInTheTable() {
        BlockShapeTable table = BlockShapeTable.builtin();
        for (String excluded : List.of(
                "betternether:brown_large_mushroom",
                "betternether:red_large_mushroom",
                "betternether:giant_mold",
                "betternether:anchor_tree_vine",
                "betternether:willow_branch",
                "betternether:cincinnasite_pillar",
                "betternether:stalagnate_trunk",
                "betternether:mushroom_fir_trunk",
                "betternether:nether_reed_stem")) {
            assertNull(table.entry(excluded),
                    excluded + " 的上游规则无法确证，按「不猜」原则不进表");
        }
    }

    /** 规则会写出的全部取值。 */
    private static List<String> expectedValues(Entry entry) {
        return switch (entry.rule()) {
            case HANGING_VINE -> List.of(
                    BlockShapeTable.BOTTOM, BlockShapeTable.MIDDLE, BlockShapeTable.TOP);
            case BOTTOM_FLAG -> List.of(BlockShapeTable.TRUE, BlockShapeTable.FALSE);
        };
    }

    /**
     * 从 {@code appearances} 的名字里抠出某个属性的取值。
     *
     * <p>appearance 名就是「所有属性值用下划线连起来」（{@code shape_top_spent_false_vine_dir_east}），
     * 所以按 {@code 属性名_取值} 找前缀比解析更稳：只认「出现在名字开头的那个属性」即可，
     * 因为本表里的形态属性永远是第一个（{@code shape_*} / {@code bottom_*} / {@code facing_*}）。
     */
    private static Set<String> appearanceValues(JsonArray appearances, String property) {
        Set<String> values = new HashSet<>();
        String prefix = property + "_";
        for (JsonElement element : appearances) {
            String name = element.getAsJsonObject().get("name").getAsString();
            if (!name.startsWith(prefix)) {
                continue;
            }
            String rest = name.substring(prefix.length());
            // 钟乳石的 size 是数字（`size_0`..`size_7`），不在下面那张方位词表里，
            // 单独放行；否则这一类永远解析成空集，测试会误判成「没有该取值」。
            if (rest.matches("\\d+")) {
                values.add(rest);
                continue;
            }
            for (String known : List.of(
                    BlockShapeTable.BOTTOM, BlockShapeTable.MIDDLE, BlockShapeTable.TOP,
                    BlockShapeTable.TRUE, BlockShapeTable.FALSE,
                    "up", "down", "north", "south", "east", "west")) {
                if (rest.equals(known) || rest.startsWith(known + "_")) {
                    values.add(known);
                }
            }
        }
        return values;
    }

    /** 报告里的数组段：从测试工作目录往上找（Gradle 用 common 作 cwd，IDE 可能在别处）。 */
    private static Optional<JsonArray> readArray(String file, String field) throws IOException {
        Path current = Path.of("").toAbsolutePath();
        for (; current != null; current = current.getParent()) {
            Path candidate = current.resolve("plugin/build/generated/bn/reports/" + file);
            if (Files.isRegularFile(candidate)) {
                JsonObject json = JsonParser.parseString(
                        Files.readString(candidate, StandardCharsets.UTF_8)).getAsJsonObject();
                return Optional.of(json.getAsJsonArray(field));
            }
        }
        return Optional.empty();
    }
}
