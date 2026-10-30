package dev.mumu.betternether.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.util.ArrayList;
import java.util.List;

/**
 * The lines {@code /betternether reload} prints, composed from what the reload actually did.
 *
 * <p><b>Why this is a class of its own.</b> A reload is only useful if its report is trustworthy: the
 * one thing it must never do is imply that a startup-only value took effect. That is a property of
 * the <em>wording</em>, so the wording is built here -- from facts, in execution order -- instead of
 * being interleaved with the file I/O in the plugin. Written this way the contract is unit-testable:
 * "a changed terrain key is reported as needing a restart", "a broken messages.yml is not announced
 * as reloaded", "a resource file on disk is never described as a CraftEngine reload".
 *
 * <p>Every sentence still comes from {@code messages.yml} (see {@link Messages}); this class only
 * chooses which keys are used and in what order. Bukkit-free apart from Adventure's component types.
 *
 * <p>Three outcomes are kept apart on purpose, because they are three different verdicts:
 * <ol>
 *   <li>{@code config.yml} / {@code messages.yml} -- read, validated, and in force for the running
 *       server. Reported as applied.</li>
 *   <li>the CraftEngine resource pack -- <b>not</b> written and <b>not</b> re-read by this command.
 *       That is stated on its own line every time, so nobody can read the report as "the pack is
 *       live now".</li>
 *   <li>world-generation parameters -- in force only for worlds created after this reload; terrain
 *       already generated is untouched, which is why changed keys are listed under "restart".</li>
 * </ol>
 */
public final class ReloadReport {
    /**
     * What a reload found and did. Deliberately facts rather than prose, and deliberately complete:
     * every field is something the operator is entitled to see.
     *
     * @param configReleased    config.yml was missing and has just been written back out
     * @param bundledVersion    the {@code config-version} of the file that was re-released
     * @param addedKeys         keys this reload backfilled into the file
     * @param restartKeys       startup-only keys that changed; already-generated terrain keeps the old
     *                          values, so a world regenerated from scratch is what applies them
     * @param configProblems    values the parser replaced with their built-in default; each one is a
     *                          setting that did NOT take effect as written
     * @param messagesReloaded  messages.yml was re-read and the new wording is in force
     * @param messagesFailure   why messages.yml could not be re-read, or {@code null}; the previous
     *                          wording stays in force
     * @param ceContentReady    CraftEngine currently has the blocks world generation needs registered
     *                          in memory. Files on disk are not that: writing a resource file is
     *                          neither a CraftEngine re-read nor a change to generated chunks.
     */
    public record Facts(
            boolean configReleased,
            int bundledVersion,
            List<String> addedKeys,
            List<String> restartKeys,
            List<String> configProblems,
            boolean messagesReloaded,
            String messagesFailure,
            boolean ceContentReady,
            /** What the CraftEngine pack release did this pass; {@code null} when it was not attempted. */
            String packReleaseLine
    ) {
        public Facts {
            addedKeys = List.copyOf(addedKeys);
            restartKeys = List.copyOf(restartKeys);
            configProblems = List.copyOf(configProblems);
        }
    }

    private ReloadReport() {
    }

    /**
     * The whole report, in execution order: what was released, what was backfilled, what was applied,
     * what the pack step did not do, and -- last, so it is the line the operator reads -- what still
     * needs a restart.
     */
    public static List<Component> lines(Messages messages, Facts facts) {
        List<Component> out = new ArrayList<>();
        out.add(messages.get("command.reload.header"));

        if (facts.configReleased()) {
            out.add(messages.get("command.reload.config-released",
                    Placeholder.unparsed("version", String.valueOf(facts.bundledVersion()))));
        }

        out.add(messages.get(facts.addedKeys().isEmpty() ? "command.reload.keys-none" : "command.reload.keys-added",
                keys(facts.addedKeys())));

        // A value the parser could not use was replaced by its built-in default. That is a setting the
        // owner wrote which is NOT in force, so it gets its own line -- and it counts as a failure
        // below, because "reload complete" underneath it is exactly the reassurance that hides it.
        if (!facts.configProblems().isEmpty()) {
            out.add(messages.get("command.reload.config-warnings",
                    Placeholder.unparsed("count", String.valueOf(facts.configProblems().size())),
                    keys(facts.configProblems())));
        }

        // The second file a reload re-reads, on its own line so a failure cannot be read as "the
        // wording was reloaded too": the previous text stays in force, and this line is the only place
        // that says so. A failure wins over a success -- both cannot be true.
        if (facts.messagesFailure() != null) {
            out.add(messages.get("command.reload.messages-failed",
                    Placeholder.unparsed("reason", facts.messagesFailure())));
        } else if (facts.messagesReloaded()) {
            out.add(messages.get("command.reload.messages-reloaded"));
        }

        // What the pack release actually did. Replaces the old unconditional "this command does not
        // touch the pack" line: the command now does release it, so claiming otherwise would be a lie.
        if (facts.packReleaseLine() != null) {
            out.add(messages.get("command.reload.ce-pack-result",
                    Placeholder.unparsed("result", facts.packReleaseLine())));
        } else {
            out.add(messages.get("command.reload.ce-pack"));
        }

        if (!facts.ceContentReady()) {
            out.add(messages.get("command.reload.ce-not-ready"));
        }

        boolean failed = facts.messagesFailure() != null || !facts.ceContentReady()
                || !facts.configProblems().isEmpty();
        if (!facts.restartKeys().isEmpty()) {
            out.add(messages.get("command.reload.restart-required", keys(facts.restartKeys())));
        } else if (!failed) {
            // Only when nothing above can need one. "Nothing needs a restart" printed under a
            // CraftEngine that has not registered our blocks is exactly the reassurance that teaches
            // an operator to stop reading this report.
            out.add(messages.get("command.reload.restart-none"));
        }

        if (!failed) {
            // A failure already had its own line; following it with "reload complete" would be the
            // small lie that makes every other line worth less.
            out.add(messages.get("command.reload.success"));
        }
        return out;
    }

    /**
     * A report that stops before applying anything: the file could not be read, or it is invalid.
     *
     * <p>Only these two lines, because that is the whole truth -- nothing was released, nothing was
     * backfilled, no value changed. Saying "reload complete" here would be the small lie that teaches
     * an operator to stop reading the output.
     */
    public static List<Component> rejected(Messages messages, boolean invalid, String reason) {
        return List.of(
                messages.get("command.reload.header"),
                messages.get(invalid ? "command.reload.invalid" : "command.reload.io-error",
                        Placeholder.unparsed("reason", reason)));
    }

    /** One line, for a reload that never started: no report header, because nothing began. */
    public static List<Component> failed(Messages messages, String reason) {
        return List.of(messages.get("command.reload.failed", Placeholder.unparsed("reason", reason)));
    }

    private static TagResolver keys(List<String> keys) {
        return Placeholder.unparsed("keys", keys.isEmpty() ? "（无）" : String.join(", ", keys));
    }
}
