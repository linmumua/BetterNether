package dev.mumu.betternether.biome;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * T5 — {@link NetherBiomes#resolve()} must never claim success without a server.
 *
 * <p>Why this is a contract and not a formality: the whole design refuses to fall back. If
 * resolution were ever able to return {@link NetherBiomes.Resolved} outside a bootstrapped server,
 * that mapping would be built from an empty or half-loaded registry and the generator would bake
 * vanilla biomes into region files permanently. Both permitted outcomes — a {@code Failure} naming
 * the missing ids, or an {@link IllegalStateException} — are loud; a {@code Resolved} is silent, so
 * it is the one outcome that fails here.
 */
class NetherBiomesResolveTest {

    @Test
    void resolveNeverReportsSuccessWithoutAServer() {
        NetherBiomes.Resolution resolution;
        try {
            resolution = NetherBiomes.resolve();
        } catch (IllegalStateException expectedWithoutServer) {
            // 允许的分支之二：注册表未就绪时直接抛出。
            return;
        }

        NetherBiomes.Failure failure = assertInstanceOf(NetherBiomes.Failure.class, resolution,
                "无服务器环境下 resolve() 不得返回 Resolved —— 那会把原版群系永久写进区域文件");
        assertNotNull(failure.reason(), "Failure 必须带一条可展示给操作员的原因");
        assertFalse(failure.reason().isBlank(), "Failure.reason() 不能为空串");
        assertFalse(failure.missing().contains(""),
                "missing 列表不应包含空字符串：" + failure.missing());
    }

    @Test
    void displayNameOnlyAnswersForOurKeys() {
        if (NetherBiomes.displayName(NetherBiomes.ALL.getFirst().key()) == null) {
            fail("displayName 必须认得自家群系 key");
        }
        if (NetherBiomes.displayName("minecraft:nether_wastes") != null) {
            fail("displayName 不应对非本模组 key 编造名字");
        }
    }
}
