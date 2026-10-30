package dev.mumu.betternether.config;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * What a reload changes, computed from two flattened config snapshots -- no Bukkit, no I/O, so the
 * interesting half of {@code /betternether reload} is unit-testable.
 *
 * <p><b>Why this exists at all.</b> Nearly all of {@code config.yml} is read once, at plugin load,
 * into the snapshot a world's generator was constructed from: {@code generator.seed} seeds the biome
 * field, {@code ore.*} and {@code flora.*} are baked into the populators when the generator is built.
 * Re-reading those and reporting "reloaded" would be a lie an operator only discovers as a seam along
 * a chunk border -- so the reload must say, by key, "this one changed and needs a restart". That list
 * is what {@link #restartOnlyChanges} produces.
 *
 * <p>Three keys this build no longer has -- {@code flora.ceiling} and the two {@code craftengine*}
 * switches -- are listed in {@link #OBSOLETE_KEYS} and ignored here: an old server's {@code config.yml}
 * still carries them, and reporting a leftover line the plugin does not read as "needs a restart"
 * would be pure noise. {@code config-version} is bookkeeping and is never reported.
 */
public final class ReloadDiff {
    /**
     * The keys the terrain generator is built from. A change to any of them affects terrain that has
     * not been generated yet, and cannot affect chunks already on disk at all.
     */
    public static final Set<String> WORLDGEN_KEYS = Set.of(
            "generator.seed",
            "ore.veins-per-chunk",
            "ore.vein-size",
            "flora.per-chunk");

    /**
     * Keys a previous build parsed and this one does not.
     *
     * <p>Deliberately ignored by {@link #restartOnlyChanges}: a server upgraded from an older jar
     * still has them on disk, and they change nothing here, so neither "restart required" nor
     * "unknown key" is an honest verdict for them. Ignoring rather than deleting them from the file
     * keeps the rule that the plugin never rewrites values it does not understand.
     */
    public static final Set<String> OBSOLETE_KEYS = Set.of(
            "flora.ceiling",
            "craftengine.install-bundled-pack",
            "craftengine-resources.auto-completion");

    private ReloadDiff() {
    }

    /**
     * Keys {@code after} has and {@code before} did not: what a backfill just added to the file.
     *
     * <p>These do not imply a restart. A key that was absent ran on its built-in default, and a
     * backfill writes that same default, so the running behaviour is unchanged by definition.
     *
     * @param before the values the running server is using
     * @param after  the values just read from disk
     * @return sorted dotted keys, empty when the file already had everything
     */
    public static List<String> addedKeys(Map<String, Object> before, Map<String, Object> after) {
        Set<String> added = new TreeSet<>(after.keySet());
        added.removeAll(before.keySet());
        return List.copyOf(added);
    }

    /**
     * Keys that are startup-only AND whose effective value differs between the two snapshots.
     *
     * <p>Errors every way it can, and deliberately: a key the owner changed is reported, and so is a
     * key they <em>deleted</em> -- the running snapshot still holds the old value while the new file
     * no longer supplies it, so unless that value happened to equal the default (unknowable here,
     * and cheap to over-report) the server would behave differently after a restart. Reporting a
     * needless restart costs one restart; staying silent about a terrain change costs a world.
     *
     * <p>A key this build does not know is reported too, for the same reason: over-reporting a
     * restart is recoverable, under-reporting one is not -- with the one exception of
     * {@link #OBSOLETE_KEYS}, keys this build deliberately dropped.
     *
     * <p>{@code 300} and {@code 300.0} are the same value: snakeyaml hands back an Integer for the
     * first and a Double for the second, and a bare number edit that changed no meaning must not
     * produce a restart warning.
     *
     * @param before the values the running server is using
     * @param after  the values just read from disk
     * @return sorted dotted keys that need a full restart to take effect
     */
    public static List<String> restartOnlyChanges(Map<String, Object> before, Map<String, Object> after) {
        return changedKeys(before, after, OBSOLETE_KEYS);
    }

    /** Every key whose effective value differs, minus {@code config-version} and {@code ignored}. */
    private static List<String> changedKeys(Map<String, Object> before, Map<String, Object> after,
                                            Set<String> ignored) {
        Set<String> changed = new TreeSet<>();
        Set<String> union = new TreeSet<>(before.keySet());
        union.addAll(after.keySet());
        for (String key : union) {
            if (ConfigStore.VERSION_KEY.equals(key) || ignored.contains(key)) {
                continue;
            }
            if (!after.containsKey(key)) {
                // Deleted by the owner: the running snapshot still holds the old value, the new file
                // no longer supplies one, so the next start would use the built-in default. Report it.
                changed.add(key);
                continue;
            }
            if (!before.containsKey(key)) {
                // Backfilled by ConfigStore with the bundled default, which is what an absent key was
                // already running on -- see addedKeys(). Reporting every newly added key as "needs a
                // restart" is exactly the noise that teaches an operator to ignore the warning.
                continue;
            }
            if (!sameValue(before.get(key), after.get(key))) {
                changed.add(key);
            }
        }
        return List.copyOf(changed);
    }

    /** Numbers compare by value ({@code 300} vs {@code 300.0}); everything else by equals. */
    private static boolean sameValue(Object before, Object after) {
        if (before instanceof Number left && after instanceof Number right) {
            return left.doubleValue() == right.doubleValue();
        }
        return Objects.equals(before, after);
    }
}
