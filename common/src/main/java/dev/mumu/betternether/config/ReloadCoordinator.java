package dev.mumu.betternether.config;

import net.kyori.adventure.text.Component;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * One {@code /betternether reload}, from the command thread to the reply.
 *
 * <p><b>Why this is a class, and why it holds the seams.</b> The two properties that decide whether a
 * reload is safe cannot be checked by looking at the plugin: whether two reloads can overlap, and in
 * which order the results are published. Both are decided here, so both are testable without a
 * server, a CraftEngine install or a scheduler -- the file reads, the readiness probe and the
 * off-thread hand-off arrive as the interfaces below, and the production wiring in
 * {@code BetterNetherPlugin} passes the real ones.
 *
 * <p><b>Single flight.</b> A reload re-reads two YAML files and may rewrite them, so a second request
 * while one is running is refused and told so. The gate is a CAS released in a {@code finally}
 * spanning the asynchronous body, and also on the two paths where that body never starts --
 * scheduling itself throwing, and the plugin disabling mid-flight -- because a gate that could stay
 * set would answer "already running" to every later reload forever.
 *
 * <p><b>Publication order.</b> Config and messages are read and validated BEFORE anything is
 * published; only then is the immutable {@link Live} triple (messages, config, last read values)
 * swapped in with one volatile write, so a reader on a region thread sees either the old triple or
 * the new one and never a mixture. Nothing here writes a resource pack or asks CraftEngine to reload
 * one: this class has no opinion about the pack, and the report says so rather than implying a
 * resource file was handled.
 *
 * <p><b>Startup values are never replaced.</b> {@code startupValues} is what the running server was
 * built from, so it stays the baseline the restart-required list is computed against. {@link
 * Live#lastReadValues()} only records what the latest read saw, so the next reload can report what it
 * backfilled. Comparing against a reload-updated baseline instead would let a second reload announce
 * "no restart needed" for a change that never reached the terrain.
 *
 * <p>Bukkit-free on purpose: everything platform-specific is injected, so a test exercises this class
 * rather than a stand-in for it.
 */
public final class ReloadCoordinator {
    /**
     * The runtime values a successful reload may replace, as one immutable triple so they are read
     * together or not at all.
     *
     * @param messages       the text in force, replaced only when messages.yml parsed
     * @param config         the parsed configuration in force; the generator a world is created with
     *                       is built from this, which is why a reload applies to worlds created after
     *                       it and never to terrain already generated
     * @param lastReadValues the flattened values this reload read, i.e. the baseline the next reload
     *                       reports backfilled keys against
     */
    public record Live(Messages messages, NetherConfig config, Map<String, Object> lastReadValues) {
        public Live {
            Objects.requireNonNull(messages, "messages");
            Objects.requireNonNull(config, "config");
            Objects.requireNonNull(lastReadValues, "lastReadValues");
            lastReadValues = Collections.unmodifiableMap(new LinkedHashMap<>(lastReadValues));
        }
    }

    /**
     * A config read, plus the facts the report needs about the file itself.
     *
     * @param released  the file was missing and has just been written back out
     * @param problems  values the parser replaced with their built-in default; empty for a clean read.
     *                  Carried here because a replaced value is a setting that did NOT take effect as
     *                  written, and the report must say so rather than print a clean "reload complete".
     */
    public record ConfigRead(NetherConfig config, Map<String, Object> values, int bundledVersion,
                             boolean released, List<String> problems) {
        public ConfigRead {
            Objects.requireNonNull(config, "config");
            Objects.requireNonNull(values, "values");
            values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
            problems = List.copyOf(problems);
        }
    }

    /**
     * Reads, seeds and backfills config.yml, then parses it.
     *
     * @throws IOException          the file could not be read or written
     * @throws IllegalArgumentException a value the parser/record rejects; nothing is published
     */
    @FunctionalInterface
    public interface ConfigReader {
        ConfigRead read() throws IOException;
    }

    /** Reads, seeds and backfills messages.yml. Any failure keeps the running text in place. */
    @FunctionalInterface
    public interface MessagesReader {
        Messages read() throws IOException;
    }

    /** Whether the content the world cannot open without is registered right now. */
    @FunctionalInterface
    public interface ContentProbe {
        boolean ready();
    }

    /**
     * Releases the bundled CraftEngine pack, repairing it if it is missing or incomplete.
     *
     * <p>Called on every reload, before the readiness probe: if an operator (or CraftEngine itself)
     * deleted {@code plugins/CraftEngine/resources/betternether/}, a reload must put it back rather
     * than reporting "everything is fine" against a pack that is no longer on disk.
     *
     * @return a one-line, human-readable outcome, or {@code null} when nothing was written
     * @throws java.io.IOException when the release failed outright
     */
    @FunctionalInterface
    public interface PackReleaser {
        String release() throws java.io.IOException;
    }

    /** Hands the body to an off-thread scheduler; throwing it means "nothing was scheduled". */
    @FunctionalInterface
    public interface AsyncRunner {
        void execute(Runnable work);
    }

    private final Map<String, Object> startupValues;
    private final AtomicReference<Live> live;
    private final ConfigReader configReader;
    private final MessagesReader messagesReader;
    private final ContentProbe contentProbe;
    private final PackReleaser packReleaser;
    private final AsyncRunner asyncRunner;
    private final AtomicBoolean inFlight = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    /** Only publication and shutdown take this short lock; no file I/O or scheduler waits inside it. */
    private final Object lifecycle = new Object();

    /**
     * @param startupValues the snapshot the running server was built from; kept for the life of this
     *                      coordinator and never replaced by a reload
     * @param live          the runtime triple this coordinator publishes into, owned by the caller so
     *                      the hot readers can consult it
     */
    public ReloadCoordinator(Map<String, Object> startupValues,
                             AtomicReference<Live> live,
                             ConfigReader configReader,
                             MessagesReader messagesReader,
                             ContentProbe contentProbe,
                             PackReleaser packReleaser,
                             AsyncRunner asyncRunner) {
        this.startupValues = Collections.unmodifiableMap(new LinkedHashMap<>(startupValues));
        this.live = Objects.requireNonNull(live, "live");
        this.configReader = Objects.requireNonNull(configReader, "configReader");
        this.messagesReader = Objects.requireNonNull(messagesReader, "messagesReader");
        this.contentProbe = Objects.requireNonNull(contentProbe, "contentProbe");
        this.packReleaser = Objects.requireNonNull(packReleaser, "packReleaser");
        this.asyncRunner = Objects.requireNonNull(asyncRunner, "asyncRunner");
    }

    /**
     * Starts a reload, or refuses because one is already running.
     *
     * <p>Called on the command thread. Everything slow -- both file reads, the readiness probe --
     * happens inside {@code asyncRunner}, never here, so no region or global thread is blocked on I/O
     * and no future is awaited: this method either schedules the work or reports why it did not.
     *
     * @param reply receives the finished report exactly once, on the async thread; delivering it to a
     *              human is the caller's business (it owns the entity/global scheduling)
     */
    public void reload(Consumer<List<Component>> reply) {
        Objects.requireNonNull(reply, "reply");
        Live snapshot = live.get();
        if (closed.get()) {
            // Disabled between dispatch and this call: there is nobody to read for, and a scheduler
            // that would refuse the task anyway. Nothing runs and nothing is claimed.
            return;
        }
        if (!inFlight.compareAndSet(false, true)) {
            reply.accept(List.of(snapshot.messages().get("command.reload.busy")));
            return;
        }
        try {
            asyncRunner.execute(() -> runOnAsync(reply));
        } catch (RuntimeException | LinkageError refused) {
            // Nothing was scheduled, so nothing else will ever release the gate -- and nothing was
            // read, so this is exactly the "stopped before applying anything" report, not a claim
            // about a reload that half ran.
            inFlight.set(false);
            reply.accept(ReloadReport.rejected(snapshot.messages(), false, reason(refused)));
        }
    }

    /** Closes publication before disabling queued tasks; already-started file I/O is not rolled back. */
    public void shutdown() {
        synchronized (lifecycle) {
            closed.set(true);
            inFlight.set(false);
        }
    }

    /** True while a reload is running. Exposed so a caller can assert the gate is free again. */
    public boolean inFlight() {
        return inFlight.get();
    }

    private void runOnAsync(Consumer<List<Component>> reply) {
        List<Component> lines;
        try {
            if (closed.get()) return;
            try {
                lines = runStages();
            } catch (RuntimeException | LinkageError error) {
                System.getLogger(ReloadCoordinator.class.getName()).log(
                        System.Logger.Level.ERROR, "BetterNether reload failed", error);
                lines = ReloadReport.failed(live.get().messages(), reason(error));
            }
        } finally {
            inFlight.set(false);
        }
        if (closed.get() || lines.isEmpty()) return;
        try {
            reply.accept(lines);
        } catch (RuntimeException error) {
            System.getLogger(ReloadCoordinator.class.getName()).log(
                    System.Logger.Level.WARNING, "BetterNether reload reply failed", error);
        }
    }

    /** The reload proper, in execution order. Never touches the caller's thread. */
    private List<Component> runStages() {
        if (closed.get()) return List.of();
        Live before = live.get();

        // Stage 1 -- config. Fatal, and first: an unusable file must leave every running value alone.
        ConfigRead read;
        try {
            read = configReader.read();
        } catch (IllegalArgumentException invalid) {
            return ReloadReport.rejected(before.messages(), true, reason(invalid));
        } catch (IOException unreadable) {
            return ReloadReport.rejected(before.messages(), false, reason(unreadable));
        }
        if (closed.get()) return List.of();

        // Stage 2 -- messages. Soft: a broken text file keeps the running text rather than stopping.
        Messages texts = before.messages();
        String messagesFailure = null;
        try {
            texts = messagesReader.read();
        } catch (IOException | RuntimeException broken) {
            messagesFailure = reason(broken);
        }

        List<String> added = ReloadDiff.addedKeys(before.lastReadValues(), read.values());
        // Against what the running server was BUILT from, not against the last read: a value changed
        // by an earlier reload still has not reached already-generated terrain, so it must keep being
        // reported.
        List<String> restart = ReloadDiff.restartOnlyChanges(startupValues, read.values());

        Live published = new Live(texts, read.config(), read.values());
        synchronized (lifecycle) {
            if (closed.get()) return List.of();
            live.set(published);
        }
        if (closed.get()) return List.of();

        // Stage 3 -- release the CraftEngine pack, then probe whether its content is registered.
        //
        // Order matters. The release comes first because the probe asks CraftEngine whether specific
        // blocks exist, and a missing/partial pack on disk is the most common reason they do not. The
        // release is also idempotent and never overwrites an edited file (PackInstaller's contract),
        // so running it on every reload repairs what is gone without touching what the operator owns.
        //
        // Its failure is reported but does not abort the reload: config and messages have already
        // been published, and the readiness line below will say the content is not live. Aborting
        // here would leave the operator with text they cannot see applied and no explanation.
        String packLine;
        try {
            packLine = packReleaser.release();
        } catch (IOException | RuntimeException failure) {
            packLine = "资源包释放失败：" + (failure.getMessage() == null
                    ? failure.getClass().getSimpleName() : failure.getMessage());
        }

        boolean ready = contentReady();

        return ReloadReport.lines(texts, new ReloadReport.Facts(
                read.released(), read.bundledVersion(), added, restart,
                read.problems(), messagesFailure == null, messagesFailure, ready, packLine));
    }

    /**
     * Whether the critical content is registered, as things stand right now. A probe that cannot
     * answer at all counts as "not ready": the report warns instead of claiming the content is live.
     */
    private boolean contentReady() {
        try {
            return contentProbe.ready();
        } catch (RuntimeException | LinkageError unavailable) {
            return false;
        }
    }

    /** The diagnostic text of a failure, for a message's {@code <reason>} placeholder. */
    private static String reason(Throwable error) {
        String text = error.getMessage();
        return text == null || text.isBlank() ? error.getClass().getSimpleName() : text;
    }
}
