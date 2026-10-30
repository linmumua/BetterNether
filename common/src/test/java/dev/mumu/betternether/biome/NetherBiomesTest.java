package dev.mumu.betternether.biome;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T1 — the biome table is complete, well-shaped, and every reference it makes is real.
 *
 * <p>Why these assertions matter: {@link NetherBiomes#ALL} is the single ordering that every
 * {@link BiomePlacement} index maps onto, and each biome's upstream surface rule is what the
 * generator writes into the world. A wrong key here is not a crash — it is a world that generates
 * once, silently, and can never be regenerated because chunks are persisted. So the table is
 * pinned: the count, the id shape, uniqueness, one-to-one coverage with the compiled surface
 * rules, and the existence of every custom block id those rules name.
 */
class NetherBiomesTest {
    /** A datapack id: {@code namespace:path}, with Minecraft's own charset for each half. */
    private static final Pattern RESOURCE_LOCATION = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./]+");

    /**
     * Keys are only ever compared against {@code <namespace>:<key>}; anything else is a typo.
     *
     * <p>The namespace is read from the constant, never hard-coded: the port publishes under `mumu`
     * while upstream uses `betternether`, and a test that spelled the prefix out would fail (or, worse,
     * silently pass) the day that constant changes.
     */
    private static final Pattern KEY_NAMESPACED =
            Pattern.compile(NetherBiomes.NAMESPACE + ":[a-z0-9_]+");

    /** The upstream registry declares exactly 23 Nether biomes; the port must not drift. */
    private static final int EXPECTED_COUNT = 23;

    @Test
    void declaresExactlyTheTwentyThreeUpstreamBiomes() {
        assertEquals(EXPECTED_COUNT, NetherBiomes.ALL.size(),
                "NetherBiomes.ALL 必须与上游 registry/NetherBiomes.java 一致，共 23 个");
        assertEquals("betternether", NetherBiomes.NAMESPACE,
                "命名空间改变意味着所有数据包 JSON 都要重写");
    }

    @Test
    void everyKeyIsAWellFormedNamespacedId() {
        for (NetherBiomes.Entry entry : NetherBiomes.ALL) {
            String namespaced = entry.namespaced();
            assertTrue(KEY_NAMESPACED.matcher(namespaced).matches(),
                    "群系 id 必须形如 " + NetherBiomes.NAMESPACE + ":<key> 且 key 只含 [a-z0-9_]：" + namespaced);
            assertFalse(entry.chineseName().isBlank(),
                    "群系 " + entry.key() + " 缺少中文显示名（操作员日志会只剩英文 id）");
        }
    }

    @Test
    void keysAreUniqueSoTheOrderingStaysIndexable() {
        List<String> keys = NetherBiomes.ALL.stream().map(NetherBiomes.Entry::key).toList();
        assertEquals(keys.size(), new HashSet<>(keys).size(),
                "重复 key 会让 BiomePlacement 的索引与 resolve() 的映射互相矛盾：" + keys);
    }

    @Test
    void everyKeyHasASurfaceRuleAndNoOrphanRules() {
        SurfaceRules.Book book = book();
        for (NetherBiomes.Entry entry : NetherBiomes.ALL) {
            assertNotNull(book.of(entry.key()),
                    "群系 " + entry.key() + " 没有地表规则；它会在世界里被留成纯下界岩");
        }
        assertEquals(NetherBiomes.ALL.size(), book.size(),
                "上游 surface_rules 里有本工程不认识的群系；多余规则永远不会被执行");
    }

    @Test
    void everySurfaceMaterialIsAValidResourceLocation() {
        SurfaceRules.Book book = book();
        for (NetherBiomes.Entry entry : NetherBiomes.ALL) {
            for (String state : book.of(entry.key()).materials()) {
                String id = idOf(state);
                assertTrue(RESOURCE_LOCATION.matcher(id).matches(),
                        "群系 " + entry.key() + " 的地表材质不是合法资源位置：" + state);
            }
        }
    }

    @Test
    void everyCustomBlockIdExistsInTheGeneratedBlockManifest() throws IOException {
        Optional<Path> manifest = findBlockManifest();
        Assumptions.assumeTrue(manifest.isPresent(),
                "未找到 plugin/build/generated/bn/reports/block-manifest.json"
                        + "（该文件由资源包生成流水线产出）；跳过自定义方块 id 的存在性核对。"
                        + "若本机应有此文件，请先运行资源生成脚本。");

        SurfaceRules.Book book = book();
        Set<String> custom = new LinkedHashSet<>();
        for (NetherBiomes.Entry entry : NetherBiomes.ALL) {
            for (String state : book.of(entry.key()).materials()) {
                String id = idOf(state);
                if (id.startsWith(NetherBiomes.NAMESPACE + ":")) {
                    custom.add(id);
                }
            }
        }
        Assumptions.assumeFalse(custom.isEmpty(),
                "地表规则里没有任何 " + NetherBiomes.NAMESPACE + ": 方块 id，无需核对清单");

        Set<String> declaredIds = readManifestIds(manifest.get());
        assertFalse(declaredIds.isEmpty(), "方块清单为空：" + manifest.get());

        Set<String> unknown = new TreeSet<>(custom);
        unknown.removeAll(declaredIds);
        assertTrue(unknown.isEmpty(),
                "地表规则引用了清单里不存在的方块 id（会在地形生成时静默变成空气/报错）：" + unknown);
    }

    /** The compiled rules, read from the upstream folder that the copy step mirrors into the jar. */
    private static SurfaceRules.Book book() {
        Optional<Path> rules = findRulesDirectory();
        Assumptions.assumeTrue(rules.isPresent(),
                "未找到上游 surface_rules 目录；跳过与真实规则文件的对照。");
        return SurfaceRules.Book.loadDirectory(rules.get());
    }

    /** Strips a {@code [properties]} suffix, which only vanilla states in these files carry. */
    private static String idOf(String state) {
        int bracket = state.indexOf('[');
        return bracket < 0 ? state : state.substring(0, bracket);
    }

    /** The upstream rules folder, found the same way as the manifest: walk up, deterministically. */
    private static Optional<Path> findRulesDirectory() {
        Path current = Path.of("").toAbsolutePath();
        for (; current != null; current = current.getParent()) {
            Path candidate = current.resolve(
                    "upstream/BetterNether/src/main/generated/data/betternether/wover/worldgen/surface_rules");
            if (Files.isDirectory(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /**
     * Locates the manifest by walking up from the test working directory.
     *
     * <p>Gradle runs {@code :common:test} with the {@code common} project directory as cwd, so the
     * file lives two levels up; but an IDE run may start elsewhere. Walking up is deterministic and
     * needs no system properties.
     */
    private static Optional<Path> findBlockManifest() {
        Path current = Path.of("").toAbsolutePath();
        for (; current != null; current = current.getParent()) {
            Path candidate = current.resolve("plugin/build/generated/bn/reports/block-manifest.json");
            if (Files.isRegularFile(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private static Set<String> readManifestIds(Path manifest) throws IOException {
        Set<String> ids = new HashSet<>();
        try (Reader reader = Files.newBufferedReader(manifest, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            for (JsonElement element : root.getAsJsonArray("blocks")) {
                ids.add(element.getAsJsonObject().get("id").getAsString());
            }
        }
        return ids;
    }
}
