package dev.mumu.betternether.vanilla;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.mumu.betternether.vanilla.BlockBreakdownTable.Depends;
import dev.mumu.betternether.vanilla.BlockBreakdownTable.Side;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 运行期清单表与生成期报告的一致性，以及方位判定的语义。
 *
 * <h2>为什么要有「和报告逐条比对」这条测试</h2>
 * 运行期表是手抄进 Java 的（报告不进 jar，见 {@link BlockBreakdownTable} 的类注释），
 * 抄错一条的后果是静默的：那一个方块从此失去支撑也不掉落，或者反过来在不该掉的时候掉。
 * 两种都不会报错、不会进日志，只能靠逐条比对抓。报告文件不存在时跳过（{@code assumeTrue}），
 * 不允许静默通过。
 */
class BlockBreakdownTableTest {

    /** 报告里的一条：{@code betternether:hook_mushroom} 吊在顶上。 */
    private static final String HANGING_UP = "betternether:hook_mushroom";

    @Test
    void builtinTableMatchesThePublishFilteredReport() {
        BlockBreakdownTable table = BlockBreakdownTable.builtin();

        // 38 = 上游判据里「本包真的发布」的那些；未发布的 anchor_tree_vine / black_vine /
        // gloomsculk_vine / golden_lumabus_vine / whispering_gourd_vine / willow_branch 等
        // 在生成期就被过滤掉，不该出现在运行期表里（否则装载器会报
        // 「包缺少 N 个世界生成方块 id」，而它们本来就不发布）。
        assertEquals(38, table.size(), "运行期表必须与已发布清单逐条一致");
        assertTrue(table.ids().stream().allMatch(id -> id.startsWith("betternether:")),
                "本表只该有本插件的方块");
    }

    /**
     * 抽查五条，把「方向」这件事钉在具体方块上。
     *
     * <p>选的这几条各自代表一种判据：吊在顶上的藤蔓（up）、地面植物（down）、
     * 上下都判的芦苇（both）、墙上的水晶（facing）、以及混向的 {@code bone_mushroom}（mixed）。
     */
    @Test
    void knownBlocksKeepTheirDocumentedDirection() {
        BlockBreakdownTable table = BlockBreakdownTable.builtin();

        assertEquals(Depends.UP, table.depends(HANGING_UP), "钩菇吊在上方");
        assertEquals(Depends.DOWN, table.depends("betternether:agave"), "龙舌兰站在地面");
        assertEquals(Depends.BOTH, table.depends("betternether:nether_reed_stem"), "芦苇上下都判");
        // `willow_torch` 未发布，改用仍发布的按贴面判据样本（gloomsculk_crystal 挂面）。
        assertEquals(Depends.FACING, table.depends("betternether:gloomsculk_crystal"), "按贴面判据");
        // wart_seed 未发布；改用仍发布的唯一混向样本 bone_mushroom。
        assertEquals(Depends.MIXED, table.depends("betternether:bone_mushroom"), "骨菇的判据混向");
        assertNull(table.depends("minecraft:soul_sand"), "原版方块不在清单里");
        assertNull(table.depends("betternether:definitely_not_a_plant"));
    }

    /**
     * 方向判定表：依赖哪一侧、被挖掉的是哪一侧，决定拆不拆。
     *
     * <p>{@code FACING} 与 {@code MIXED} 的「不猜」也在这里被钉住 —— 它们的返回值不是「大概是这个
     * 方向」，而是明确的 {@code false}，把决定权留给 CE。
     */
    @Test
    void breaksWhenTableIsExactForEveryDirection() {
        // 依赖上方：只有「上方没了」成立。
        assertTrue(BlockBreakdownTable.breaksWhen(Depends.UP, Side.ABOVE, null));
        assertFalse(BlockBreakdownTable.breaksWhen(Depends.UP, Side.BELOW, null));
        assertFalse(BlockBreakdownTable.breaksWhen(Depends.UP, Side.NORTH, null));

        // 依赖下方：对称。
        assertTrue(BlockBreakdownTable.breaksWhen(Depends.DOWN, Side.BELOW, null));
        assertFalse(BlockBreakdownTable.breaksWhen(Depends.DOWN, Side.ABOVE, null));

        // 上下都判。
        assertTrue(BlockBreakdownTable.breaksWhen(Depends.BOTH, Side.ABOVE, null));
        assertTrue(BlockBreakdownTable.breaksWhen(Depends.BOTH, Side.BELOW, null));
        assertFalse(BlockBreakdownTable.breaksWhen(Depends.BOTH, Side.EAST, null));

        // 贴面：只认自己声明的那一侧；读不到朝向一律不拆。
        assertTrue(BlockBreakdownTable.breaksWhen(Depends.FACING, Side.EAST, Side.EAST));
        assertFalse(BlockBreakdownTable.breaksWhen(Depends.FACING, Side.EAST, Side.WEST));
        assertFalse(BlockBreakdownTable.breaksWhen(Depends.FACING, Side.EAST, null));

        // 混向：任何方向都不由本层判定。
        for (Side side : Side.values()) {
            assertFalse(BlockBreakdownTable.breaksWhen(Depends.MIXED, side, side),
                    "mixed 不能映射成某个单一方向：" + side);
        }
    }

    @Test
    void facingValuesTranslateOrRefuseInsteadOfGuessing() {
        assertEquals(Side.ABOVE, BlockBreakdownTable.sideOf("up"));
        assertEquals(Side.BELOW, BlockBreakdownTable.sideOf("down"));
        assertEquals(Side.NORTH, BlockBreakdownTable.sideOf("north"));
        assertEquals(Side.EAST, BlockBreakdownTable.sideOf("east"));
        assertNull(BlockBreakdownTable.sideOf("some_unknown_direction"));
        assertNull(BlockBreakdownTable.sideOf(null));
    }

    /** 手抄表的两种真实手误必须在加载时就炸，而不是运行期才对不上。 */
    @Test
    void malformedTableTextIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> BlockBreakdownTable.parse("betternether:a up\nbetternether:a down\n"),
                "重复 id 会让后一条静默覆盖前一条");
        assertThrows(IllegalArgumentException.class,
                () -> BlockBreakdownTable.parse("betternether:a sideways\n"),
                "不认识的方向必须报错，不能被当作 up/down 收下");
        assertThrows(IllegalArgumentException.class,
                () -> BlockBreakdownTable.parse("betternether:a\n"),
                "少一列必须报错");
    }

    /** 注释与空行必须被忽略，否则在表里写备注就会把整张表读坏。 */
    @Test
    void commentsAndBlankLinesAreIgnored() {
        BlockBreakdownTable table = BlockBreakdownTable.parse("""
                # 说明
                betternether:a up

                betternether:b down
                """);

        assertEquals(2, table.size());
        assertEquals(Depends.UP, table.depends("betternether:a"));
        assertEquals(Depends.DOWN, table.depends("betternether:b"));
    }

    /**
     * 与生成期报告逐条比对：id 集合与方向必须完全一致。
     *
     * <p>报告是 {@code :plugin} 的构建产物，{@code clean} 之后 {@code :common:test} 可能先跑，
     * 那时文件不存在 —— 于是跳过并说明原因（{@code :plugin:extractBnResources} 建了任务顺序依赖，
     * 正常构建里会先产出）。跳过不等于通过：{@link #builtinTableMatchesThePublishFilteredReport}
     * 仍然能在没有报告时守住条数。
     */
    @Test
    void builtinTableMatchesTheGeneratedReport() throws IOException {
        Optional<Path> report = findReport();
        Assumptions.assumeTrue(report.isPresent(),
                "未找到 plugin/build/generated/bn/reports/plant-breakdown.json"
                        + "（由资源包生成流水线产出）；跳过与真实清单的逐条比对。");
        assertTrue(Files.isRegularFile(report.get()), "报告路径不是文件：" + report.get());

        JsonObject json = JsonParser.parseString(
                Files.readString(report.get(), StandardCharsets.UTF_8)).getAsJsonObject();
        BlockBreakdownTable fromReport = BlockBreakdownTable.parseReport(json);
        BlockBreakdownTable builtin = BlockBreakdownTable.builtin();

        assertFalse(fromReport.ids().isEmpty(), "报告里一条方块都没有：" + report.get());
        assertEquals(new TreeSet<>(fromReport.ids()), new TreeSet<>(builtin.ids()),
                "运行期表与报告的方块集合不一致（多抄/漏抄会让某个方块静默不掉落）");
        for (String id : fromReport.ids()) {
            assertEquals(fromReport.depends(id), builtin.depends(id),
                    id + " 的方向与报告不一致");
        }
    }

    /**
     * 报告里 {@code drops_on_breakdown} 全是 true —— 这正是「用户要求掉落」这件事的记录。
     *
     * <p>运行期表不为它留开关（见 {@link BlockBreakdownTable} 的类注释），所以这条断言的意义是：
     * 如果哪天报告里出现 {@code false}，说明需求变了，而运行期表不会跟着变 —— 得有人来看。
     */
    @Test
    void everyReportedBlockIsStillSupposedToDrop() throws IOException {
        Optional<Path> report = findReport();
        Assumptions.assumeTrue(report.isPresent(),
                "未找到 plugin/build/generated/bn/reports/plant-breakdown.json；跳过掉落开关核对。");

        JsonObject blocks = JsonParser.parseString(
                        Files.readString(report.get(), StandardCharsets.UTF_8))
                .getAsJsonObject().getAsJsonObject("blocks");
        List<String> silent = blocks.entrySet().stream()
                .filter(entry -> !entry.getValue().getAsJsonObject()
                        .get("drops_on_breakdown").getAsBoolean())
                .map(java.util.Map.Entry::getKey)
                .toList();

        assertEquals(List.of(), silent,
                "这些方块在报告里被标成「不掉落」，而运行期监听对清单内方块一律掉落：" + silent);
    }

    /** 报告路径：从测试工作目录往上找（Gradle 用 common 作 cwd，IDE 可能在别处）。 */
    private static Optional<Path> findReport() {
        Path current = Path.of("").toAbsolutePath();
        for (; current != null; current = current.getParent()) {
            Path candidate = current.resolve("plugin/build/generated/bn/reports/plant-breakdown.json");
            if (Files.isRegularFile(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /** 表自己的 id 集合应当是 {@code Set} 语义 —— 顺序不重要，去重才重要。 */
    @Test
    void idsAreUnique() {
        Set<String> ids = BlockBreakdownTable.builtin().ids();
        assertEquals(BlockBreakdownTable.builtin().size(), ids.size());
    }
}
