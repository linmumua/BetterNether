package dev.mumu.betternether.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import java.util.List;

/**
 * The lines {@code /betternether} and {@code /betternether help} print: the command list, as
 * clickable text.
 *
 * <p><b>Why this is a class of its own.</b> Help is the one screen with no state behind it, which
 * makes it the easiest place for hard-coded strings to drift away from {@code messages.yml}. Here it
 * is a fixed, ordered list rendered from {@code help.*}: a bare {@code /betternether} and an explicit
 * {@code help} render identically, because two different usage screens teach nothing.
 *
 * <p><b>Why the click only suggests.</b> {@code /betternether reload} reads and may rewrite the
 * configuration files, so the row binds {@code suggestCommand} in code, separately from the visual
 * template. Clicking fills the chat input and leaves the decision -- and the enter key -- to the
 * operator. Do not change this to {@code runCommand}: a reload must be a deliberate act.
 *
 * <p>Bukkit-free apart from Adventure's component types; the caller decides who receives the lines.
 */
public final class HelpReport {
    /** The one command that changes anything; the row's click target is bound to it below. */
    public static final String RELOAD_COMMAND = "/betternether reload";
    /** The command that renders this screen, offered on its own row. */
    public static final String HELP_COMMAND = "/betternether help";

    private HelpReport() {
    }

    /**
     * The whole help screen: a header, one row per command, a footer.
     *
     * @param messages the plugin's text; a missing key renders as the key name and never throws
     * @return exactly four lines, in order
     */
    public static List<Component> lines(Messages messages) {
        return List.of(
                messages.get("help.header"),
                messages.get("help.row-reload",
                                Placeholder.unparsed("command", RELOAD_COMMAND))
                        .clickEvent(ClickEvent.suggestCommand(RELOAD_COMMAND)),
                messages.get("help.row-help",
                                Placeholder.unparsed("command", HELP_COMMAND))
                        .clickEvent(ClickEvent.suggestCommand(HELP_COMMAND)),
                messages.get("help.footer"));
    }
}
