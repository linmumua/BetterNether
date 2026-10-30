package dev.mumu.betternether.config;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T4 — config parsing degrades instead of aborting, except where it must not.
 *
 * <p>The rule the parser implements, and this class pins: a value that is <em>absent</em> silently
 * takes its default (so an old config.yml still loads), a value that is <em>present but invalid</em>
 * takes its default <em>and</em> warns (so the operator finds out), and a value that reaches the
 * record with an impossible combination throws (because that is a bug in the parser, not in the
 * operator's file). Silent defaulting and silent acceptance are the two failure modes here, and both
 * are tested from the outside.
 */
class NetherConfigParserTest {
    private static final Function<String, Object> ABSENT = key -> null;

    @Test
    void absentKeysYieldExactlyTheDefaults() {
        NetherConfig parsed = NetherConfigParser.parse(ABSENT, problem -> {
            throw new AssertionError("缺键不应产生告警，实际：" + problem);
        });
        assertEquals(NetherConfig.DEFAULTS, parsed,
                "空配置必须与 DEFAULTS 完全相等；否则新装的服务器和注释里写的行为不一致");
    }

    @Test
    void outOfRangeNumberFallsBackAndWarns() {
        List<String> warnings = new ArrayList<>();
        NetherConfig parsed = NetherConfigParser.parse(
                values("ore.veins-per-chunk", 999), warnings::add);

        assertEquals(NetherConfig.DEFAULTS.oreVeinCount(), parsed.oreVeinCount(),
                "999 超出 0..64，必须回退默认值");
        assertFalse(warnings.isEmpty(), "回退必须留痕：操作员要能看出配置没生效");
        assertTrue(warnings.stream().anyMatch(w -> w.contains("ore.veins-per-chunk")),
                "告警必须点名出问题的键，实际：" + warnings);
    }

    @Test
    void nonNumericStringFallsBackAndWarns() {
        List<String> warnings = new ArrayList<>();
        NetherConfig parsed = NetherConfigParser.parse(
                values("ore.veins-per-chunk", "abc"), warnings::add);

        assertEquals(NetherConfig.DEFAULTS.oreVeinCount(), parsed.oreVeinCount(),
                "\"abc\" 不是整数，必须回退默认值");
        assertFalse(warnings.isEmpty(), "非数字值必须告警，不能静默吞掉");
        assertTrue(warnings.stream().anyMatch(w -> w.contains("ore.veins-per-chunk")),
                "告警必须点名出问题的键，实际：" + warnings);
    }

    @Test
    void droppedBooleanKeysAreIgnoredWithoutWarnings() {
        // 老服务器磁盘上的 config.yml 仍带着这三个键；本构建不再读它们，于是
        // (a) 不能因此告警（那会让操作员去找一个不存在的键），
        // (b) 也不能影响任何取值 —— 语义已恒定化（天花板植被恒开、资源包恒释放并补齐）。
        Map<String, Object> map = new HashMap<>();
        map.put("flora.ceiling", false);
        map.put("craftengine.install-bundled-pack", false);
        map.put("craftengine-resources.auto-completion", false);

        List<String> warnings = new ArrayList<>();
        NetherConfig parsed = NetherConfigParser.parse(map::get, warnings::add);

        assertEquals(NetherConfig.DEFAULTS, parsed, "已删除的键不得影响任何取值");
        assertEquals(List.of(), warnings, "已删除的键不得告警：" + warnings);
    }

    @Test
    void configuredSeedOverridesTheWorldSeed() {
        Map<String, Object> map = new HashMap<>();
        map.put("generator.seed", 12345);

        NetherConfig parsed = NetherConfigParser.parse(map::get, problem -> {
            throw new AssertionError("合法 seed 不应告警：" + problem);
        });

        assertEquals(12345L, parsed.seed(), "generator.seed=12345 必须原样进入配置");
        assertEquals(12345L, parsed.seedFor(999L),
                "非零 seed 是覆盖值，必须无视世界种子");
    }

    @Test
    void zeroSeedMeansFollowTheWorld() {
        Map<String, Object> map = new HashMap<>();
        map.put("generator.seed", 0);

        NetherConfig parsed = NetherConfigParser.parse(map::get, problem -> {
            throw new AssertionError("seed=0 是合法值，不应告警：" + problem);
        });

        assertEquals(0L, parsed.seed());
        assertEquals(999L, parsed.seedFor(999L),
                "seed=0 的契约是「用世界种子」，不是「用 0」");
    }

    @Test
    void parserNeverConstructsAnImpossibleConfig() {
        // 越界值一律被回退，因此 parse() 不允许把非法值交到构造器手里。
        assertDoesNotThrow(() -> NetherConfigParser.parse(values("ore.vein-size", 0), problem -> {
        }));
        assertDoesNotThrow(() -> NetherConfigParser.parse(values("flora.per-chunk", 100_000), problem -> {
        }));
        assertDoesNotThrow(() -> NetherConfigParser.parse(values("ore.veins-per-chunk", -1), problem -> {
        }));
    }

    @Test
    void constructorRejectsOutOfRangeValuesLoudly() {
        // 构造器是最后一道闸：绕开 parse() 的代码（或 parse() 自身的 bug）必须在这里炸掉。
        assertThrows(IllegalArgumentException.class,
                () -> new NetherConfig(0L, 100, 9, 28, 9, 1, 100, 4),
                "ore.veins-per-chunk 上限是 64");
        assertThrows(IllegalArgumentException.class,
                () -> new NetherConfig(0L, 12, 0, 28, 9, 1, 100, 4),
                "ore.vein-size 下限是 1");
        assertThrows(IllegalArgumentException.class,
                () -> new NetherConfig(0L, 12, 9, 257, 9, 1, 100, 4),
                "flora.per-chunk 上限是 256");
        assertThrows(IllegalArgumentException.class,
                () -> new NetherConfig(0L, -1, 9, 28, 9, 1, 100, 4),
                "负数矿脉数必须被拒绝");
    }

    @Test
    void constructorAcceptsTheBoundariesInclusive() {
        assertDoesNotThrow(() -> new NetherConfig(0L, 0, 1, 0, 9, 1, 100, 4));
        assertDoesNotThrow(() -> new NetherConfig(0L, 64, 64, 256, 9, 1, 100, 4));
    }

    /** A values function backed by exactly one key, mimicking the flattened YAML view. */
    private static Function<String, Object> values(String key, Object value) {
        return requested -> key.equals(requested) ? value : null;
    }
}
