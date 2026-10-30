package dev.mumu.betternether.worldgen;

/**
 * 世界生成的告警出口。
 *
 * <h2>为什么不直接 {@code Bukkit.getLogger()}</h2>
 * 这个类被「精确语义不可用」的降级路径调用，而那些路径**也会在单元测试里跑到**
 * （测试注入的假世界天然答不出真实 canSurvive）。{@code Bukkit.getLogger()} 在服务端尚未
 * 初始化时是 {@code null} 解引用 —— 一条本该只是警告的代码会把整条放置链路打断。
 * 所以这里在服务端没起来时退回 JDK 日志，而不是抛异常。
 *
 * <p>刻意做成两层而不是「有就喊、没有就不喊」：降级必须是**看得见的**，
 * 哪怕只在测试输出里。
 */
final class WorldgenWarnings {
    private WorldgenWarnings() {
    }

    /**
     * 降级账本：哪些「真实语义」这次问不出来、于是用了近似。
     *
     * <p>放在这里而不是放在各自的实现类里，是因为**测试类路径上没有 NMS**（构建脚本把
     * serverJar 从 test classpath 里摘掉了），而降级恰恰是测试要断言的东西。这个类只碰
     * {@code org.bukkit} 与 JDK，任何测试都能安全加载。
     */
    private static final java.util.Set<String> DEGRADED =
            java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<>());

    /** 记一条降级并告警（同一个 key 只记一次）。 */
    static void degrade(String key, String message) {
        if (!DEGRADED.add(key)) {
            return;
        }
        warn(message);
    }

    /** 本次运行发生过的降级项；空集 = 全程走的都是真实语义。 */
    static java.util.Set<String> degraded() {
        return java.util.Set.copyOf(DEGRADED);
    }

    /** 测试用。 */
    static void reset() {
        DEGRADED.clear();
    }

    static void warn(String message) {
        org.bukkit.Server server = org.bukkit.Bukkit.getServer();
        if (server != null) {
            server.getLogger().warning(message);
            return;
        }
        java.util.logging.Logger.getLogger("BetterNether").warning(message);
    }
}
