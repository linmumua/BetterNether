package dev.mumu.betternether.terrain;

import dev.mumu.betternether.terrain.CraftEngineLookup.MissingPropertyDiagnosis;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉住 {@link CraftEngineLookup} 的两件纯逻辑：属性改名与「缺属性」的定级/文案。
 *
 * <p>两条都刻意不经过 {@code CraftEngineBlocks}：这里断言的是结论本身（哪个属性该改什么名、哪种缺失
 * 该按 INFO 还是 WARN 报），不是寄存器里的真实方块。用真实 CE 注册表来测，测试就只能跑在服务端里，
 * 而这正是这些结论在出问题前最需要被 cheap 地钉住的地方。
 *
 * <p>每条断言都对应一个真实的误判：
 * <ul>
 *   <li><b>rotation 被丢掉</b>：上游 {@code BlockGloomwispVine.ROTATION} 是
 *       {@code WispRotation}（random/north/east/south/west），CE 侧叫 {@code vine_dir}，取值集合一模一样。
 *       不改名就等于把鬼火藤头的朝向整个丢掉，而日志只说「没有属性 rotation」，没人能从中看出该补什么。</li>
 *   <li><b>把纯运行时属性当形状缺口</b>：{@code persistent}、{@code distance} 这类不参与模型，
 *       报 WARN 会把人赶去改本来就对的 CE 配置。</li>
 *   <li><b>把未移植说成等价</b>：{@code offset} 的 XZ 抖动、{@code giant_lucis} 的六向菌盖确实没移植，
 *       静默降级会让世界「看起来没事」而细节永久缺失。</li>
 *   <li><b>树叶 id 写漏了后缀</b>：清单里曾把 {@code anchor_tree}/{@code gloomwood}/…（树干 id）
 *       当成树叶，于是真正的树叶缺失仍然报 WARN，清单等于白写。所以每种树叶 id 都逐条断言。</li>
 *   <li><b>日志谎报整个状态</b>：旧文案写「世界生成将退回默认状态」，而实现只跳过那一个属性。</li>
 * </ul>
 */
class CraftEngineLookupPropertyMappingTest {

    private static final String GLOOMWISP_VINE = "betternether:gloomwisp_vine";

    /** 上游 {@code WispRotation} 的取值，CE {@code vine_dir} 的取值集合与之逐字相同。 */
    private static final List<String> WISP_ROTATIONS = List.of("random", "north", "east", "south", "west");

    /**
     * 六种树叶的**实际**方块 id —— 全部带 {@code _leaves} 后缀，与 CE 配置
     * （{@code betternether:anchor_tree_leaves} 等）和用户日志里的 id 一致。
     */
    private static final List<String> LEAF_BLOCKS = List.of(
            "betternether:anchor_tree_leaves",
            "betternether:gloomwood_leaves",
            "betternether:gloomwood_bleached_leaves",
            "betternether:rubeus_leaves",
            "betternether:nether_sakura_leaves",
            "betternether:willow_leaves");

    private static void assertRuntimeOnly(String id, String property) {
        assertEquals(MissingPropertyDiagnosis.IGNORED_RUNTIME_ONLY,
                CraftEngineLookup.diagnoseMissingProperty(id, property),
                id + "[" + property + "] 是已核实的纯运行时属性，缺了只该记 INFO");
    }

    // ------------------------------------------------------------------ 1. rotation → vine_dir

    @Test
    void gloomwispRotationBecomesVineDirAndEverythingElseSurvives() {
        Map<String, String> upstream = new LinkedHashMap<>();
        upstream.put("offset", "true");
        upstream.put("persistent", "false");
        upstream.put("rotation", "random");
        upstream.put("shape", "top");
        upstream.put("spent", "false");

        Map<String, String> mapped = CraftEngineLookup.mapToCeProperties(GLOOMWISP_VINE, upstream);

        // 改名本身
        assertFalse(mapped.containsKey("rotation"), "rotation 不该作为属性名留在 CE 侧");
        assertEquals("random", mapped.get("vine_dir"));
        // 其余属性一字不改地留下——上游 patch_gloomwisp_vine 的每一层都带这五个键
        assertEquals("true", mapped.get("offset"));
        assertEquals("false", mapped.get("persistent"));
        assertEquals("top", mapped.get("shape"));
        assertEquals("false", mapped.get("spent"));
        assertEquals(5, mapped.size(), "改名不该丢键也不该多键");
        // 顺序保留：CE 的 getBlockState 无所谓顺序，但保持上游顺序让日志可读
        assertEquals(List.of("offset", "persistent", "vine_dir", "shape", "spent"), List.copyOf(mapped.keySet()));
    }

    @Test
    void everyWispRotationValuePassesThroughUnchanged() {
        for (String rotation : WISP_ROTATIONS) {
            Map<String, String> mapped = CraftEngineLookup.mapToCeProperties(
                    GLOOMWISP_VINE, Map.of("rotation", rotation));
            assertEquals(rotation, mapped.get("vine_dir"),
                    "rotation=" + rotation + " 必须原样落到 vine_dir，不做角度换算");
        }
    }

    @Test
    void rotationIsNotRenamedForAnyOtherBlock() {
        // 别的方块上的 rotation 未必指头部朝向，全局改名会静默改写它们
        for (String id : List.of("betternether:egg_plant", "betternether:giant_lucis", "minecraft:grass_block")) {
            assertEquals("rotation", CraftEngineLookup.cePropertyName(id, "rotation"),
                    id + " 的 rotation 不该被改写成 vine_dir");
        }
        assertEquals("vine_dir", CraftEngineLookup.cePropertyName(GLOOMWISP_VINE, "rotation"));
        // 已同名的属性在任何方块上都不该被碰
        assertEquals("shape", CraftEngineLookup.cePropertyName(GLOOMWISP_VINE, "shape"));
        assertEquals("vine_dir", CraftEngineLookup.cePropertyName(GLOOMWISP_VINE, "vine_dir"));
    }

    @Test
    void firstWriterWinsWhenUpstreamSendsBothNames() {
        Map<String, String> upstream = new LinkedHashMap<>();
        upstream.put("rotation", "random");
        upstream.put("vine_dir", "west");

        Map<String, String> mapped = CraftEngineLookup.mapToCeProperties(GLOOMWISP_VINE, upstream);

        assertEquals(1, mapped.size(), "两个键改名后是同一个 CE 属性，只能留一个");
        assertEquals("random", mapped.get("vine_dir"), "先出现的那个赢，后到的重复键不许覆盖");
    }

    // --------------------------------------------------- 2. 缺属性定级：INFO / WARN 的精确边界

    @Test
    void confirmedRuntimeOnlyPropertiesAreInfoNotWarn() {
        // 用户核实过的「纯运行时、不参与方块状态与模型」清单，逐条钉住
        assertRuntimeOnly("betternether:gloomwisp_vine", "persistent");
        assertRuntimeOnly("betternether:egg_plant", "age");
        assertRuntimeOnly("betternether:eye_vine", "shape");
        // 六种树叶 × distance/persistent：每一种**实际** id 都单独断言，不能只挑 willow_leaves
        for (String leaves : LEAF_BLOCKS) {
            assertRuntimeOnly(leaves, "distance");
            assertRuntimeOnly(leaves, "persistent");
        }
    }

    @Test
    void leafPropertiesAreNotAttributedToTheWoodIds() {
        // 回归守卫：曾把 anchor_tree / gloomwood / gloomwood_bleached / rubeus / nether_sakura
        // 当成树叶 id（漏了 _leaves 后缀）。后果是双向的——树干上根本不存在的 distance/persistent
        // 被当成已核实结论放行，而真正缺这两个属性的树叶反而继续报 WARN，等于清单全部失效。
        for (String leaves : LEAF_BLOCKS) {
            String wood = leaves.substring(0, leaves.length() - "_leaves".length());
            assertEquals(MissingPropertyDiagnosis.UNKNOWN,
                    CraftEngineLookup.diagnoseMissingProperty(wood, "distance"),
                    wood + "[distance] 不是树叶属性，没有任何结论");
            assertEquals(MissingPropertyDiagnosis.UNKNOWN,
                    CraftEngineLookup.diagnoseMissingProperty(wood, "persistent"),
                    wood + "[persistent] 不是树叶属性，没有任何结论");
        }
    }

    @Test
    void runtimeOnlyClassificationIsScopedToTheBlockThatWasVerified() {
        // 同名的 distance 在别的方块上没有任何结论，必须落回 WARN
        assertEquals(MissingPropertyDiagnosis.UNKNOWN,
                CraftEngineLookup.diagnoseMissingProperty("betternether:willow_log", "distance"));
        assertEquals(MissingPropertyDiagnosis.UNKNOWN,
                CraftEngineLookup.diagnoseMissingProperty("betternether:giant_lucis", "age"));
        // 命名空间必须完整匹配
        assertEquals(MissingPropertyDiagnosis.UNKNOWN,
                CraftEngineLookup.diagnoseMissingProperty("gloomwood", "distance"));
    }

    @Test
    void unportedPropertiesStayWarnBecauseThereIsNoEquivalent() {
        // gloomwisp 的 offset 是 XZ 抖动：CE 没有偏移函数，鬼火藤会整齐排在方块中心
        assertEquals(MissingPropertyDiagnosis.UNPORTED,
                CraftEngineLookup.diagnoseMissingProperty("betternether:gloomwisp_vine", "offset"));
        // giant_lucis 继承 HugeMushroomBlock，六向决定哪些面保留菌盖贴图
        for (String face : List.of("north", "east", "south", "west", "up", "down")) {
            assertEquals(MissingPropertyDiagnosis.UNPORTED,
                    CraftEngineLookup.diagnoseMissingProperty("betternether:giant_lucis", face),
                    "giant_lucis 的 " + face + " 未移植，不能当作等价");
        }
        // 未移植 ≠ 纯运行时：这两条绝不能互相串门
        assertTrue(CraftEngineLookup.diagnoseMissingProperty("betternether:gloomwisp_vine", "offset")
                        != MissingPropertyDiagnosis.IGNORED_RUNTIME_ONLY,
                "未移植的属性一律不许降级成 INFO");
    }

    @Test
    void unknownPropertiesFallBackToWarn() {
        assertEquals(MissingPropertyDiagnosis.UNKNOWN,
                CraftEngineLookup.diagnoseMissingProperty("betternether:gloomwisp_vine", "vine_dir"));
        assertEquals(MissingPropertyDiagnosis.UNKNOWN,
                CraftEngineLookup.diagnoseMissingProperty("minecraft:grass_block", "snowy"));
    }

    // --------------------------------------------------------- 3. 文案：只忽略该属性，不谎报整体

    @Test
    void everyDiagnosisSaysOnlyThisPropertyIsIgnored() {
        for (MissingPropertyDiagnosis diagnosis : MissingPropertyDiagnosis.values()) {
            String message = CraftEngineLookup.missingPropertyMessage(
                    "betternether:gloomwisp_vine", "vine_dir", diagnosis);
            assertTrue(message.contains("仅忽略该属性，其他已支持属性仍照常应用"),
                    diagnosis + " 的文案必须说明只有这一个属性被跳过：" + message);
            // getBlockState 会应用其余属性，因此「退回默认状态」是与实现相反的谎报
            assertFalse(message.contains("退回默认状态"),
                    diagnosis + " 的文案不许说整个状态退回默认：" + message);
        }
    }

    @Test
    void unportedMessageSaysItIsNotEquivalent() {
        String message = CraftEngineLookup.missingPropertyMessage(
                "betternether:gloomwisp_vine", "offset", MissingPropertyDiagnosis.UNPORTED);
        assertTrue(message.contains("尚未移植"), "未移植要写明白：" + message);
        assertTrue(message.contains("等价"), "必须点明不能当作等价：" + message);
    }

    @Test
    void runtimeOnlyMessageExplainsWhyShapeIsUnaffected() {
        String message = CraftEngineLookup.missingPropertyMessage(
                "betternether:anchor_tree", "distance", MissingPropertyDiagnosis.IGNORED_RUNTIME_ONLY);
        assertTrue(message.contains("纯运行时"), "INFO 文案要说清它是运行时开关：" + message);
        assertTrue(message.contains("不改变形状"), "INFO 文案要给出「形状不受影响」的结论：" + message);
    }
}
