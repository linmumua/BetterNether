package dev.mumu.betternether.config;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Turns the flattened YAML values into a validated {@link NetherConfig}.
 *
 * <p>Every read has a hard-coded default, so a config.yml that predates a key still works. A value
 * that is present but invalid is reported through {@code warn} and replaced by its default rather
 * than aborting the load -- except where {@link NetherConfig}'s own constructor rejects the
 * combination, which is a programming error and must surface.
 */
public final class NetherConfigParser {
    private NetherConfigParser() {
    }

    public static NetherConfig parse(Function<String, Object> values, Consumer<String> warn) {
        NetherConfig defaults = NetherConfig.DEFAULTS;
        List<String> problems = new ArrayList<>();

        // Minecraft seeds are 64-bit. Parsing them as int would silently truncate any seed outside
        // 0..2^31-1 -- and worse, silently clamp a perfectly valid negative seed to the default,
        // which changes the world layout without telling anyone. So this one reads a long.
        long seed = longValue(values, problems, "generator.seed", defaults.seed());
        int oreVeins = integer(values, problems, "ore.veins-per-chunk", defaults.oreVeinCount(), 0, 64);
        int oreSize = integer(values, problems, "ore.vein-size", defaults.oreVeinSize(), 1, 64);
        int flora = integer(values, problems, "flora.per-chunk", defaults.floraPerChunk(), 0, 256);
        // 悬挂藤蔓向下延伸的运行期参数。CE 侧的 max_height 是构建期常量（32），
        // 这里才是真正生效的上限；0 长度没有意义，所以下限是 1。
        int vineMaxLength = integer(values, problems, "vine.max-length",
                defaults.vineMaxLength(), 1, 32);
        int vineGrowthChance = integer(values, problems, "vine.growth-chance",
                defaults.vineGrowthChance(), 0, 100);
        int vineInterval = integer(values, problems, "vine.growth-interval-ticks",
                defaults.vineGrowthIntervalTicks(), 1, 12000);
        int vineAttempts = integer(values, problems, "vine.growth-attempts",
                defaults.vineGrowthAttempts(), 0, 64);

        for (String problem : problems) {
            warn.accept(problem);
        }

        return new NetherConfig(seed, oreVeins, oreSize, flora,
                vineMaxLength, vineGrowthChance, vineInterval, vineAttempts);
    }

    /**
     * Reads a 64-bit seed. Accepts the full signed range; {@code 0} keeps its documented meaning of
     * "follow each world's own seed".
     */
    private static long longValue(Function<String, Object> values, List<String> problems, String key,
                                  long fallback) {
        Object raw = values.apply(key);
        if (raw == null) {
            return fallback;
        }
        if (raw instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(raw).trim());
        } catch (NumberFormatException error) {
            problems.add(key + " 不是整数（当前 " + raw + "），已回退默认值 " + fallback);
            return fallback;
        }
    }

    private static int integer(Function<String, Object> values, List<String> problems, String key,
                               int fallback, int min, int max) {
        Object raw = values.apply(key);
        if (raw == null) {
            return fallback;
        }
        int value;
        if (raw instanceof Number number) {
            value = number.intValue();
        } else {
            try {
                value = Integer.parseInt(String.valueOf(raw).trim());
            } catch (NumberFormatException error) {
                problems.add(key + " 不是整数（当前 " + raw + "），已回退默认值 " + fallback);
                return fallback;
            }
        }
        if (value < min || value > max) {
            problems.add(key + " 必须在 " + min + ".." + max + "（当前 " + value + "），已回退默认值 "
                    + fallback);
            return fallback;
        }
        return value;
    }
}
