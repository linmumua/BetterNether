package dev.mumu.betternether.worldgen;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code blocks} 字段的两种互不相干的含义，必须各走各的解析入口。
 *
 * <h2>要钉住的回归</h2>
 * 「坐标」在两个地方都叫 {@code blocks}，但只有一处真是坐标：
 * <ul>
 *   <li>{@code minecraft:matching_blocks.blocks} 是**方块 id**——字符串，或字符串数组
 *       （真实数据里 {@code feature/patch_nether_cactus.json} 正好是三个 id）；</li>
 *   <li>{@code wover:offset.blocks} 是 Vec3i 坐标向量（{@code [0,-1,0]}）。</li>
 * </ul>
 * 旧实现把两者混在一个 {@code offset(json)} 里：取不到 {@code offset} 就回退读
 * {@code blocks}，于是 {@code matching_blocks} 的 id 数组被当坐标喂给 {@code getAsInt}，
 * 启动期直接抛。这里的第一组测试就是「三个 id 的数组不得被当向量」，第二组是「声明了的
 * offset / blocks 向量仍然照常生效」，第三组是「畸形向量必须显式拒绝而不是静默忽略」。
 *
 * <h2>为什么不启动服务端</h2>
 * 与 {@code FakeWorld} 同一套办法：{@code Material.createBlockData()} 需要服务端注册表，
 * 而这里的断言只关心「解析分支走对了没有」，所以方块解析结果用只实现
 * {@code getMaterial()} 的代理桩掉（直接写进装载器的 {@code blockCache}）。
 * tag 展开同样不重算：真实清单里的 {@code blockTags} 直接注入 {@code tagIds}。
 *
 * <h2>为什么不直接跑 {@code WorldgenLoader#load()}</h2>
 * {@code load()} 从**类路径**读 {@code /worldgen-upstream/...}，而该目录是 {@code :plugin}
 * 的构建产物，{@code :common} 的测试类路径上没有它（本模块没有 test resources）。所以这里
 * 改为：把构建期生成的**真实 JSON 语料**逐个节点喂给装载器里真正做解析的那两个包级方法
 * （{@link WorldgenLoader#predicate} / {@link WorldgenLoader#placement}）。语料缺失时跳过，
 * 不会静默当成通过。
 */
class WorldgenLoaderPredicateOffsetTest {

    /** 原版方块谓词的全部类型（{@link WorldgenLoader#predicate} 认的那张表）。 */
    private static final Set<String> PREDICATE_TYPES = Set.of(
            "minecraft:matching_blocks",
            "minecraft:matching_block_tag",
            "minecraft:matching_fluids",
            "minecraft:solid",
            "minecraft:replaceable",
            "minecraft:would_survive",
            "minecraft:true",
            "minecraft:all_of",
            "minecraft:any_of",
            "minecraft:not",
            // wover 的扩展谓词：flooded_lava_pit_surface 的 is_basin(any_of(lava, full_shape))。
            "wover:full_shape");

    /** 带坐标向量的 wover 修饰符：{@code wover:offset} 用 {@code blocks}，另两个用 {@code offset}。 */
    private static final Set<String> VECTOR_MODIFIERS =
            Set.of("wover:offset", "wover:is", "wover:is_next_to");

    // ---------------------------------------------------------------- 回归：blocks 不是坐标

    /**
     * {@code minecraft:matching_blocks.blocks} 写成字符串时是方块 id，不是向量。
     *
     * <p>真实数据里 7 处就是这个形态（例如 {@code patch_basalt_stalactite.json}）。
     */
    @Test
    void matchingBlocksStringIsABlockIdNotACoordinate() {
        WorldgenLoader loader = stubbedLoader("minecraft:basalt");
        JsonObject json = json("""
                {"type":"minecraft:matching_blocks","blocks":"minecraft:basalt"}
                """);

        BlockPredicates.Predicate compiled = loader.predicate(json);

        BlockPredicates.Blocks blocks = assertInstanceOf(BlockPredicates.Blocks.class, compiled,
                "'blocks' 是方块 id，不该被当成 offset 包一层");
        assertEquals(1, blocks.accepted().size(), "唯一声明的方块 id 应当被解析进 accepted");
    }

    /**
     * 第三个、第四个…… 个 id 的数组同样不是向量。
     *
     * <p>这是本次回归的**触发点**：真实数据 {@code feature/patch_nether_cactus.json} 里
     * {@code matching_blocks.blocks} 恰好是三个 id，旧实现的「取不到 {@code offset} 就读
     * {@code blocks}」正好把这三个 id 当成了 {@code (x,y,z)}。
     */
    @Test
    void matchingBlocksWithThreeIdsIsNotReadAsAVector() {
        WorldgenLoader loader = stubbedLoader(
                "minecraft:gravel", "minecraft:sand", "minecraft:red_sand");
        JsonObject json = json("""
                {"type":"minecraft:matching_blocks",
                 "blocks":["minecraft:gravel","minecraft:sand","minecraft:red_sand"]}
                """);

        // 修复前：这里对 "minecraft:gravel" 调 getAsInt()，直接抛。
        BlockPredicates.Predicate compiled = loader.predicate(json);

        assertFalse(compiled instanceof BlockPredicates.Offset,
                "三个方块 id 不是坐标，绝不能包一层 Offset");
        BlockPredicates.Blocks blocks = assertInstanceOf(BlockPredicates.Blocks.class, compiled);
        assertEquals(3, blocks.accepted().size(), "三个 id 都应当保留");
    }

    // ---------------------------------------------------------------- 合法向量仍然生效

    /**
     * 谓词自己声明 {@code offset} 时照旧包一层 {@link BlockPredicates.Offset}。
     *
     * <p>真实数据确实用这个形态（{@code placed_feature/vegetation_bone_reef.json} 等的
     * {@code matching_block_tag} 带 {@code offset: [0,-1,0]}），所以修复不能是「谓词永远不读
     * offset」——那会把脚下的判定挪到脚上。
     */
    @Test
    void declaredPredicateOffsetStillWrapsTheInnerPredicate() {
        WorldgenLoader loader = stubbedLoader("minecraft:gravel");
        JsonObject json = json("""
                {"type":"minecraft:matching_blocks","blocks":["minecraft:gravel"],"offset":[0,-1,1]}
                """);

        BlockPredicates.Offset offset = assertInstanceOf(BlockPredicates.Offset.class,
                loader.predicate(json), "声明了 offset 就必须包一层");

        assertEquals(new Point(0, -1, 1), offset.shift(), "offset 必须按 JSON 原样读");
        assertInstanceOf(BlockPredicates.Blocks.class, offset.inner(), "内层仍是 original 谓词");
    }

    /** {@code wover:offset} 的 {@code blocks} 是向量：真实数据全是三整数（11 处都是 {@code [0,-1,0]}）。 */
    @Test
    void woverOffsetModifierReadsTheBlocksVector() {
        WorldgenLoader loader = stubbedLoader();
        List<PlacementModifier> modifiers = loader.placement(jsonArray("""
                [{"type":"wover:offset","blocks":[0,-1,0]}]
                """));

        assertNotNull(modifiers, "合法向量不该让整条链被拒绝");
        assertEquals(1, modifiers.size(), "单节点链编译出一个修饰符");
        PlacementModifier.FixedOffset fixed = assertInstanceOf(PlacementModifier.FixedOffset.class,
                modifiers.get(0));
        assertEquals(new Point(0, -1, 0), fixed.shift(), "blocks 向量必须原样成为偏移");
    }

    /** {@code wover:is} 的字段名是 {@code offset}（不是 {@code blocks}）。 */
    @Test
    void woverIsModifierReadsTheOffsetVector() {
        WorldgenLoader loader = stubbedLoader();
        List<PlacementModifier> modifiers = loader.placement(jsonArray("""
                [{"type":"wover:is","offset":[0,1,0],
                  "predicate":{"type":"minecraft:solid"}}]
                """));

        assertNotNull(modifiers);
        assertEquals(1, modifiers.size());
        PlacementModifier.Is is = assertInstanceOf(PlacementModifier.Is.class, modifiers.get(0));
        assertEquals(new Point(0, 1, 0), is.offset(), "wover:is 的 offset 必须原样读出来");
    }

    // ---------------------------------------------------------------- 畸形向量必须喊

    /**
     * 声明了 {@code offset} 但形态不对 → 抛，**不**静默当成「没有 offset」。
     *
     * <p>静默忽略会让谓词判错位置（例如脚下变脚上），而这种错误在生成结果里几乎看不出来。
     */
    @Test
    void malformedDeclaredPredicateOffsetIsRejected() {
        WorldgenLoader loader = stubbedLoader();
        for (String offset : List.of("\"0,-1,0\"", "[0,-1]", "[\"0\",\"-1\",\"0\"]", "{}")) {
            JsonObject json = json("{\"type\":\"minecraft:solid\",\"offset\":" + offset + "}");
            assertThrows(IllegalStateException.class, () -> loader.predicate(json),
                    "offset = " + offset + " 是畸形向量，必须显式拒绝");
        }
    }

    /**
     * {@code wover:offset.blocks} 不是三整数向量 → 抛，也不许反过来把 id 数组当坐标。
     *
     * <p>第二条样本 {@code ["a","b","c"]} 正是「matching_blocks 形态」被丢进坐标字段的样子：
     * 两边都必须拒绝，谁也不能替谁兜底。
     */
    @Test
    void malformedBlocksVectorIsRejected() {
        WorldgenLoader loader = stubbedLoader();
        for (String blocks : List.of("[\"a\",\"b\",\"c\"]", "[1,2]", "\"minecraft:basalt\"", "[]")) {
            JsonArray array = jsonArray("[{\"type\":\"wover:offset\",\"blocks\":" + blocks + "}]");
            assertThrows(IllegalStateException.class, () -> loader.placement(array),
                    "blocks = " + blocks + " 是畸形向量，必须显式拒绝");
        }
    }

    // ---------------------------------------------------------------- 真实语料遍历

    /**
     * 真实语料里的每一个方块谓词都能编译，且只有**声明了** {@code offset} 的才包 Offset。
     *
     * <p>这是「第一次真正跑上游清单」时漏过的那类缺陷的机械防线：只要某个
     * {@code matching_blocks} 的 id 数组又被当坐标读，这里立刻炸。
     */
    @Test
    void everyRealPredicateCompilesAndOnlyDeclaredOffsetsWrap() throws IOException {
        Path root = corpus().orElse(null);
        Assumptions.assumeTrue(root != null,
                "未找到构建期生成的 worldgen-upstream 语料；跳过真实清单遍历。");
        WorldgenLoader loader = preparedLoader(root);

        List<Node> predicates = nodes(root, PREDICATE_TYPES);
        assertFalse(predicates.isEmpty(), "语料里一个方块谓词都没有，遍历没有意义");

        int matchingBlocks = 0;
        int withThreeIds = 0;
        for (Node node : predicates) {
            Point declared = declaredVector(node.json(), "offset");
            BlockPredicates.Predicate compiled;
            try {
                compiled = loader.predicate(node.json());
            } catch (RuntimeException error) {
                throw new AssertionError(node.file() + " 的谓词 " + node.type() + " 编译失败", error);
            }
            assertNotNull(compiled, node.file() + " 的谓词编译成 null");
            if (declared == null) {
                assertFalse(compiled instanceof BlockPredicates.Offset,
                        node.file() + " 的 " + node.type() + " 没有 offset 字段，却被包了一层 Offset");
            } else {
                BlockPredicates.Offset offset = assertInstanceOf(BlockPredicates.Offset.class, compiled,
                        node.file() + " 的 " + node.type() + " 声明了 offset，却没包 Offset");
                assertEquals(declared, offset.shift(), node.file() + " 的 offset 读错");
            }
            if ("minecraft:matching_blocks".equals(node.type())) {
                matchingBlocks++;
                JsonElement blocks = node.json().get("blocks");
                if (blocks != null && blocks.isJsonArray() && blocks.getAsJsonArray().size() == 3) {
                    withThreeIds++;
                }
            }
        }

        // 14 → 8：与上面 {@link #nestedExportClosureAddsMatchingBlocksAndOneOffset()} 同源，
        // 少掉的 6 个在「因方块被用户移除而停发」的特征里（terrain.json 的 excluded 有清单）。
        // 覆盖量下降是产物缩小的必然结果，不是解析漏读 —— 这 8 个仍逐个编译并比对了向量值。
        assertEquals(8, matchingBlocks,
                "语料里的 matching_blocks 节点数变了：请确认新形态也被覆盖，别只改这个数字");
        assertTrue(withThreeIds >= 1,
                "语料里已经没有『三个 id 的 blocks 数组』（patch_nether_cactus 曾提供这条回归样本）；"
                        + "本测试的触发条件没了，需要换一处真实样本");
    }

    /**
     * 真实语料里的每个 {@code wover:offset} / {@code wover:is} / {@code wover:is_next_to}
     * 都按自己那个字段名读出向量，值与 JSON 一致。
     */
    @Test
    void everyRealPlacementVectorIsParsedFromItsOwnField() throws IOException {
        Path root = corpus().orElse(null);
        Assumptions.assumeTrue(root != null,
                "未找到构建期生成的 worldgen-upstream 语料；跳过真实清单遍历。");
        List<Node> vectorNodes = nodes(root, VECTOR_MODIFIERS);
        assertFalse(vectorNodes.isEmpty(), "语料里没有任何带向量的 wover 修饰符");
        WorldgenLoader loader = preparedLoader(root);

        Map<String, Integer> counts = new TreeMap<>();
        List<String> withoutVector = new ArrayList<>();
        for (Node node : vectorNodes) {
            counts.merge(node.type(), 1, Integer::sum);
            // wover:offset 的坐标叫 blocks，另两个叫 offset —— 与谓词里的 blocks 毫无关系。
            String field = "wover:offset".equals(node.type()) ? "blocks" : "offset";
            Point declared = declaredVector(node.json(), field);
            if (declared == null) {
                withoutVector.add(node.file() + " " + node.type());
                continue;
            }
            JsonArray single = new JsonArray();
            single.add(node.json());
            List<PlacementModifier> modifiers = loader.placement(single);
            assertNotNull(modifiers, node.file() + " 的 " + node.type() + " 让整条链被拒绝了");
            assertEquals(1, modifiers.size(), node.file() + " 的单节点链应当编译出一个修饰符");
            assertEquals(declared, shiftOf(modifiers.get(0)),
                    node.file() + " 的 " + node.type() + " 向量解析与 JSON 不一致");
        }

        // 108 → 100：同源缩减 —— 因方块被用户移除而停发的特征带走了 17 个 wover:is
        // （上游 117，产物 100，缺的 17 全部来自 49 份不再发布的上游特征文件，
        // 对账零误差）。剩余 100 个仍逐个比对过向量值。
        // wover:offset 11 → 12：嵌套导出闭合补进的 placed_feature/extend_basalt.json 带一个
        // {blocks:[0,-1,0]}（上游 EXTEND_BASALT 的 .offset(DOWN)）；它和其余 11 个一样被逐节点
        // 比对过向量值，来源由 {@link #nestedExportClosureAddsMatchingBlocksAndOneOffset()} 钉住。
        assertEquals(Map.of("wover:is", 100, "wover:is_next_to", 2, "wover:offset", 12), counts,
                "上游语料的修饰符数量变了：确认新形态是否也走对了字段，再更新这里的期望值");
        // WoVer 的 is_next_to 缺少 offset 时以当前位置为锚点，即零向量；其他修饰符仍必须声明向量。
        assertEquals(List.of("placed_feature/molten_gloomsculk_near_lava.json wover:is_next_to"),
                withoutVector, "出现新的『缺坐标向量』节点：先判断它是上游缺省值还是数据损坏");
    }

    /**
     * 上游唯一缺少 offset 的 is_next_to 使用当前位置作为锚点，即零向量。
     * 声明了但形态错误的 offset 仍由严格向量解析拒绝。
     */
    @Test
    void realIsNextToWithoutOffsetUsesZeroAnchor() throws IOException {
        Path root = corpus().orElse(null);
        Assumptions.assumeTrue(root != null,
                "未找到构建期生成的 worldgen-upstream 语料；跳过真实清单遍历。");
        WorldgenLoader loader = new WorldgenLoader();
        injectRealTags(loader, root);

        Node gap = null;
        for (Node node : nodes(root, Set.of("wover:is_next_to"))) {
            if (!node.json().has("offset")) {
                gap = node;
            }
        }
        Assumptions.assumeTrue(gap != null, "语料里已经没有缺 offset 的 is_next_to，缺省值回归无需执行。");

        JsonArray single = new JsonArray();
        single.add(gap.json());
        List<PlacementModifier> modifiers = loader.placement(single);
        assertEquals(1, modifiers.size(), gap.file() + " 缺 offset 时仍应编译");
        assertEquals(new Point(0, 0, 0), shiftOf(modifiers.get(0)),
                gap.file() + " 缺省 offset 应以零向量为锚点");
    }

    /**
     * 嵌套导出闭合新增的四份 placed_feature 到底带来了什么：**恰好** 3 个
     * {@code matching_blocks}、1 个 {@code wover:offset}，并各带上游那两个新语义节点。
     *
     * <h2>为什么不能只改数字</h2>
     * 两个遍历测试里的 {@code 11 → 14 → 8} / {@code 11 → 12} 是**结果**，不是依据。这条把增量钉在
     * 「文件 + type + 字段形态」上，于是三类反例都会当场失败：
     * <ul>
     *   <li>新文件里的 {@code matching_blocks.blocks} 从单个 id 字符串变成三 id 数组 ——
     *       那会把「三 id 数组」的样本数从 1 顶上去，把真正的回归样本（patch_nether_cactus）
     *       淹掉；</li>
     *   <li>{@code extend_basalt} 的 {@code wover:offset} 换了向量、或把坐标写成谓词那套
     *       {@code offset} 字段（{@code wover:offset} 的坐标字段叫 {@code blocks}）；</li>
     *   <li>某个文件里的谓词被删/被换类型 —— 文件级断言直接指到那个文件。</li>
     * </ul>
     *
     * <h2>必要行为</h2>
     * 语料里现存的 matching_blocks 与那个 offset 由上面两个遍历测试逐节点编译并比对值；
     * 这里另外补上这两处**语义闭合**的检查（它们才是这两份文件存在的理由）：
     * {@code wover:full_shape} 不能落到「恒真」，{@code wover:mark_postprocessing} 必须被地形
     * 注册表认领 —— 否则 {@code flooded_lava_pit_surface} 会多灌岩浆、{@code nether_terrain:mark}
     * 整条 placed feature 编译不出来。
     *
     * <p>名字里的「三份」在 2026-10-28 变成两份：第三份 {@code placed_feature/terracotta_clump.json}
     * 只经由 {@code patch_basalt_stalagmite} 到达，而后者因方块被用户移除已停发。</p>
     */
    @Test
    void nestedExportClosureAddsMatchingBlocksAndOneOffset() throws IOException {
        Path root = corpus().orElse(null);
        Assumptions.assumeTrue(root != null,
                "未找到构建期生成的 worldgen-upstream 语料；跳过嵌套导出闭合的核对。");
        WorldgenLoader loader = preparedLoader(root);

        // ---- matching_blocks：两份新文件各一个，且都是「单个 id 字符串」形态
        //
        // 14 → 8（2026-10-28）：用户移除 stalagmite/stalactite/smoker 系列方块后，terrain.json
        // 的 excluded 里 11 条特征不再发布，连带它们引用的子特征一起离开语料。实测对账：
        //   上游 feature/ 88 份、placed_feature/ 131 份；
        //   产物 feature/ 65 份、placed_feature/ 105 份；
        //   缺的 49 份贡献 wover:is 17 + wover:offset 1 + matching_blocks 10。
        //   上游总计 117 / 13 / 18，减掉这些正好是产物的 100 / 12 / 8 —— 零误差，
        //   说明少掉的都是「不再发布」，不是解析漏读。
        // 因此本轮新增的两份仍在语料里（flooded_lava_pit_surface、mark）；
        // placed_feature/terracotta_clump.json 当年是第三份，它只经由
        // patch_basalt_stalagmite 的 sequence 到达，后者已停发，所以它也不再产出。
        Map<String, List<String>> expectedNew = new LinkedHashMap<>();
        expectedNew.put("placed_feature/flooded_lava_pit_surface.json", List.of("minecraft:lava"));
        expectedNew.put("placed_feature/mark.json", List.of("minecraft:lava"));

        List<Node> matchingBlocks = nodes(root, Set.of("minecraft:matching_blocks"));
        assertEquals(8, matchingBlocks.size(),
                "语料里的 matching_blocks 节点数变了：先看是哪份文件多/少了（terrain.json 的 excluded "
                        + "列着当前不发布的特征），再核对下面的来源表");
        Map<String, List<String>> perFile = new LinkedHashMap<>();
        for (Node node : matchingBlocks) {
            JsonElement blocks = node.json().get("blocks");
            assertNotNull(blocks, node.file() + " 的 matching_blocks 没有 blocks 字段");
            // 数组形态原样记进字符串，失败信息里能一眼看出「三 id 数组」和「单 id」的差别。
            perFile.computeIfAbsent(node.file(), key -> new ArrayList<>())
                    .add(blocks.isJsonPrimitive() ? blocks.getAsString() : blocks.toString());
        }
        for (Map.Entry<String, List<String>> entry : expectedNew.entrySet()) {
            assertEquals(entry.getValue(), perFile.get(entry.getKey()),
                    entry.getKey() + " 的 matching_blocks 变了（字段形态或 id）：闭合来源对不上");
        }
        assertEquals(6, perFile.size() - expectedNew.size(),
                "其余 6 份文件（patch_nether_cactus、两处 vegetation_gloomwood_* 的 feature/placed_feature "
                        + "副本、replace_soul_sandstone）的 matching_blocks 保持原样");
        assertEquals(8, perFile.size(), "8 个 matching_blocks 分布在 8 份文件里（各自一份）");

        // 新增的三个都是单 id 字符串，所以「三 id 数组」样本依旧只有 patch_nether_cactus 一处。
        int withThreeIds = 0;
        for (Node node : matchingBlocks) {
            JsonElement blocks = node.json().get("blocks");
            if (blocks != null && blocks.isJsonArray() && blocks.getAsJsonArray().size() == 3) {
                withThreeIds++;
                assertEquals("feature/patch_nether_cactus.json", node.file(),
                        "三 id 数组样本换了来源：把新样本也写进回归说明，别让它悄悄变成第二个样本");
            }
        }
        assertEquals(1, withThreeIds, "三 id 数组样本应当只有 patch_nether_cactus 一处");

        // ---- wover:offset：新增的恰好是 extend_basalt 的 [0,-1,0]
        List<Node> offsets = nodes(root, Set.of("wover:offset"));
        assertEquals(12, offsets.size(), "语料里的 wover:offset 节点数变了");
        Node extendBasalt = null;
        for (Node node : offsets) {
            if ("placed_feature/extend_basalt.json".equals(node.file())) {
                extendBasalt = node;
            }
        }
        assertNotNull(extendBasalt, "placed_feature/extend_basalt.json 不再带 wover:offset：闭合来源对不上");
        assertEquals(new Point(0, -1, 0), declaredVector(extendBasalt.json(), "blocks"),
                "extend_basalt 的 wover:offset 必须是 blocks=[0,-1,0]（上游 EXTEND_BASALT 的 .offset(DOWN)）");
        assertNull(extendBasalt.json().get("offset"),
                "wover:offset 的坐标字段叫 blocks；出现 offset 说明这份数据换了形态");
        JsonArray singleOffset = new JsonArray();
        singleOffset.add(extendBasalt.json());
        List<PlacementModifier> compiledOffset = loader.placement(singleOffset);
        assertNotNull(compiledOffset, "extend_basalt 的 wover:offset 让整条链被拒绝了");
        PlacementModifier.FixedOffset fixed = assertInstanceOf(PlacementModifier.FixedOffset.class,
                compiledOffset.get(0), "wover:offset 必须编译成固定位移");
        assertEquals(new Point(0, -1, 0), fixed.shift(), "新 offset 的位移与 JSON 不一致");

        // ---- 语义闭合：这两处节点是这四份文件存在的理由
        List<Node> fullShapes = nodes(root, Set.of("wover:full_shape"));
        assertFalse(fullShapes.isEmpty(),
                "语料里没有 wover:full_shape 了：flooded_lava_pit_surface 的语义闭合失去真实样本");
        for (Node node : fullShapes) {
            assertEquals("placed_feature/flooded_lava_pit_surface.json", node.file(),
                    "wover:full_shape 出现在预期之外的文件：" + node.file());
            assertInstanceOf(BlockPredicates.FullShape.class, loader.predicate(node.json()),
                    node.file() + " 的 wover:full_shape 编译结果不是 FullShape"
                            + "（恒真会让 flooded_lava_pit_surface 把岩浆灌满所有盆地）");
        }

        List<Node> marks = nodes(root, Set.of("wover:mark_postprocessing"));
        assertEquals(1, marks.size(), "wover:mark_postprocessing 应当只有 nether_terrain:mark 一处");
        assertEquals("placed_feature/mark.json", marks.get(0).file());
        assertNotNull(UpstreamTerrainFeatures.create("wover:mark_postprocessing", marks.get(0).json(),
                        new UpstreamTerrainFeatures.WorldgenAccess(null, null, null)),
                "wover:mark_postprocessing 没有被地形注册表认领：mark 会整条编译不出来");
    }

    // ---------------------------------------------------------------- 语料

    /** 语料里的一个带 type 的 JSON 节点，附上来源文件（相对语料根）。 */
    private record Node(String file, String type, JsonObject json) {
    }

    /** 上游世界生成的构建产物；{@code scripts/bn/worldgen.py} 的 OUTPUT_DIR 镜像。 */
    private static Optional<Path> corpus() {
        Path current = Path.of("").toAbsolutePath();
        for (; current != null; current = current.getParent()) {
            for (String candidate : new String[]{
                    "plugin/build/generated/bn/worldgen-upstream",
                    "plugin/build/resources/main/worldgen-upstream"}) {
                Path path = current.resolve(candidate);
                if (Files.isRegularFile(path.resolve("manifest.json"))) {
                    return Optional.of(path);
                }
            }
        }
        return Optional.empty();
    }

    private static List<Path> corpusFiles(Path root) throws IOException {
        List<Path> files = new ArrayList<>();
        for (String folder : new String[]{"feature", "placed_feature"}) {
            Path directory = root.resolve(folder);
            if (!Files.isDirectory(directory)) {
                continue;
            }
            try (Stream<Path> stream = Files.list(directory)) {
                stream.filter(path -> path.getFileName().toString().endsWith(".json"))
                        .sorted()
                        .forEach(files::add);
            }
        }
        return files;
    }

    private static List<Node> nodes(Path root, Set<String> types) throws IOException {
        List<Node> found = new ArrayList<>();
        for (Path file : corpusFiles(root)) {
            JsonElement parsed;
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                parsed = JsonParser.parseReader(reader);
            }
            String name = root.relativize(file).toString().replace('\\', '/');
            walk(parsed, node -> {
                JsonElement type = node.get("type");
                if (type != null && type.isJsonPrimitive() && type.getAsJsonPrimitive().isString()
                        && types.contains(type.getAsString())) {
                    found.add(new Node(name, type.getAsString(), node));
                }
            });
        }
        return found;
    }

    private static void walk(JsonElement element, Consumer<JsonObject> visitor) {
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            visitor.accept(object);
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                walk(entry.getValue(), visitor);
            }
        } else if (element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) {
                walk(item, visitor);
            }
        }
    }

    /** 节点里声明的三个整数向量；没有该字段（或 JSON null）返回 {@code null}。 */
    private static Point declaredVector(JsonObject json, String field) {
        JsonElement element = json.get(field);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        assertTrue(element.isJsonArray() && element.getAsJsonArray().size() == 3,
                "语料里的 " + field + " 不是三整数数组：" + element);
        JsonArray array = element.getAsJsonArray();
        return new Point(array.get(0).getAsInt(), array.get(1).getAsInt(), array.get(2).getAsInt());
    }

    private static Point shiftOf(PlacementModifier modifier) {
        if (modifier instanceof PlacementModifier.FixedOffset fixed) {
            return fixed.shift();
        }
        if (modifier instanceof PlacementModifier.Is is) {
            return is.offset();
        }
        if (modifier instanceof PlacementModifier.IsNextTo nextTo) {
            return nextTo.offset();
        }
        return null;
    }

    // ---------------------------------------------------------------- 桩

    /** 造一个方块解析被桩掉的装载器（测试里没有服务端注册表）。 */
    private static WorldgenLoader stubbedLoader(String... blockIds) {
        WorldgenLoader loader = new WorldgenLoader();
        Map<String, BlockData> cache = blockCache(loader);
        BlockData stub = stubState();
        for (String id : blockIds) {
            cache.put(id, stub);
        }
        return loader;
    }

    /**
     * 语料测试用的装载器：真实 tag + 语料里出现过的方块 id 全部预置成桩。
     *
     * <p>语料里的谓词会同时出现 {@code matching_block_tag}（要 tag）与 {@code matching_blocks}
     * （要方块状态），两者都必须脱离服务端才能编译。
     */
    private static WorldgenLoader preparedLoader(Path root) throws IOException {
        WorldgenLoader loader = new WorldgenLoader();
        injectRealTags(loader, root);
        stubCorpusBlocks(loader, nodes(root, PREDICATE_TYPES));
        return loader;
    }

    /**
     * 语料里所有 {@code matching_blocks.blocks} 的 id 都预置进缓存。
     *
     * <p>预置之后 {@link WorldgenLoader#block} 直接命中缓存，不会走到
     * {@code Material.createBlockData()}（那需要服务端注册表）。
     */
    private static void stubCorpusBlocks(WorldgenLoader loader, List<Node> predicates) {
        Map<String, BlockData> cache = blockCache(loader);
        BlockData stub = stubState();
        for (Node node : predicates) {
            JsonElement blocks = node.json().get("blocks");
            if (blocks == null || blocks.isJsonNull()) {
                continue;
            }
            Iterable<JsonElement> ids = blocks.isJsonArray()
                    ? blocks.getAsJsonArray() : List.of(blocks);
            for (JsonElement id : ids) {
                if (id.isJsonPrimitive() && id.getAsJsonPrimitive().isString()) {
                    cache.put(id.getAsString(), stub);
                }
            }
        }
    }

    /** 只实现放置逻辑会用到的那几个方法；材质固定为石头（这里的断言不看材质）。 */
    private static BlockData stubState() {
        return (BlockData) Proxy.newProxyInstance(
                WorldgenLoaderPredicateOffsetTest.class.getClassLoader(),
                new Class<?>[]{BlockData.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getMaterial" -> Material.STONE;
                    case "matches" -> false;
                    case "clone", "copy" -> proxy;
                    case "getAsString" -> "minecraft:stone";
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "stub";
                    default -> null;
                });
    }

    /**
     * 真实清单的 {@code blockTags} 直接注入 {@code tagIds}。
     *
     * <p>不重算构建期的 tag 展开（那需要 CE 与构建产物），只借用**同一份真实 id 集**，好让
     * {@code matching_block_tag} 谓词能编译——本测试关心的是 {@code offset}/{@code blocks}
     * 的解析分支，不是 tag 展开本身。{@code resolved} 留空表：{@code tagIds} 非空时
     * {@code tagPredicate} 走按身份比较的 {@code BlockIds}，用不到那份材质兜底。
     */
    private static void injectRealTags(WorldgenLoader loader, Path root) throws IOException {
        JsonObject manifest;
        try (Reader reader = Files.newBufferedReader(root.resolve("manifest.json"), StandardCharsets.UTF_8)) {
            manifest = JsonParser.parseReader(reader).getAsJsonObject();
        }
        JsonObject blockTags = manifest.getAsJsonObject("blockTags");
        assertNotNull(blockTags, "真实清单缺少 blockTags 段");

        Map<String, Set<String>> tagIds = tagIds(loader);
        Map<String, List<BlockData>> tags = tags(loader);
        for (Map.Entry<String, JsonElement> entry : blockTags.entrySet()) {
            Set<String> ids = new LinkedHashSet<>();
            for (JsonElement id : entry.getValue().getAsJsonArray()) {
                ids.add(id.getAsString());
            }
            if (ids.isEmpty()) {
                // 真实清单里 minecraft:air 是空集：tagPredicate 对它有一条硬编码分支（→ Air），
                // 从不查表，所以这里也不需要注入。
                continue;
            }
            tagIds.put(entry.getKey(), Set.copyOf(ids));
            tags.put(entry.getKey(), List.of());
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, BlockData> blockCache(WorldgenLoader loader) {
        return (Map<String, BlockData>) field(loader, "blockCache");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Set<String>> tagIds(WorldgenLoader loader) {
        return (Map<String, Set<String>>) field(loader, "tagIds");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, List<BlockData>> tags(WorldgenLoader loader) {
        return (Map<String, List<BlockData>>) field(loader, "tags");
    }

    private static Object field(WorldgenLoader loader, String name) {
        try {
            Field field = WorldgenLoader.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(loader);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("WorldgenLoader 的 " + name + " 字段不在了：这几个测试靠它桩掉服务端依赖", error);
        }
    }

    // ---------------------------------------------------------------- JSON 小工具

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private static JsonArray jsonArray(String text) {
        return JsonParser.parseString(text).getAsJsonArray();
    }
}
