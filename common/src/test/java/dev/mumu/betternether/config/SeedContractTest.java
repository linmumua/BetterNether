package dev.mumu.betternether.config;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The seed contract, which two real defects were caught on.
 *
 * <h2>Why these assertions matter</h2>
 * {@code generator.seed: 0} is documented as "follow each world's own seed". The first cut of the
 * pipeline parsed the seed as an {@code int} and never called {@code seedFor} at all, so every world
 * on the server shared one identical biome map -- and every seed outside {@code 0..2^31-1} (including
 * every negative seed, which Minecraft allows) was silently replaced by the default. Both are
 * invisible in game: the world simply looks like "that's how the Nether is".
 */
class SeedContractTest {
    private static Map<String, Object> values(Object... pairs) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    private static NetherConfig parse(Map<String, Object> raw, Consumer<String> warn) {
        return NetherConfigParser.parse(raw::get, warn);
    }

    @Test
    void zeroSeedFollowsTheWorldSeed() {
        NetherConfig config = parse(values("generator.seed", 0), message -> { });
        assertEquals(0L, config.seed(), "0 必须保留「跟随世界种子」的含义");
        assertEquals(999L, config.seedFor(999L));
        assertEquals(-1234567890123L, config.seedFor(-1234567890123L),
                "负世界种子必须原样保留，不能被替换");
    }

    @Test
    void explicitSeedOverridesTheWorldSeed() {
        NetherConfig config = parse(values("generator.seed", 12345), message -> { });
        assertEquals(12345L, config.seed());
        assertEquals(12345L, config.seedFor(999L), "显式种子必须覆盖世界种子");
    }

    @Test
    void negativeSeedIsAcceptedNotClamped() {
        NetherConfig config = parse(values("generator.seed", -777), message -> { });
        assertEquals(-777L, config.seed(), "负种子是合法的 Minecraft 种子，不能被回退成默认值");
    }

    @Test
    void fullSixtyFourBitSeedSurvives() {
        long big = 4_611_686_018_427_387_904L;
        NetherConfig config = parse(values("generator.seed", big), message -> { });
        assertEquals(big, config.seed(), "超出 int 范围的 64 位种子不得被截断");
    }

    @Test
    void nonNumericSeedFallsBackAndWarns() {
        StringBuilder warned = new StringBuilder();
        NetherConfig config = parse(values("generator.seed", "not-a-number"), warned::append);
        assertEquals(NetherConfig.DEFAULTS.seed(), config.seed());
        assertTrue(warned.toString().contains("generator.seed"), "非法种子必须被报告，不能静默回退");
    }
}
