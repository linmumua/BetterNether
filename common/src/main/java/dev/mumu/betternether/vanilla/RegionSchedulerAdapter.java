package dev.mumu.betternether.vanilla;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Keeps world and entity work on the owner region on Folia, and on the equivalent thread on Paper.
 *
 * <p><b>Why this exists.</b> Folia replaces the single main thread with one thread per region plus a
 * global thread; touching a world, an entity or a block from the wrong one is a hard error there and
 * a silent race on Paper. {@code /betternether reload} produces its report on the async pool -- it
 * must, or the tick thread would block on disk -- so the reply has to be handed back to whichever
 * thread owns the sender. Every one of those hand-offs goes through here instead of being written out
 * at each call site, so the scheduler choice is made once.
 *
 * <p>The three reply paths exist because a command sender can be any of three things, and they are
 * genuinely different:
 * <ul>
 *   <li>an {@link Entity} (a player, or a command block with an entity source) -- its own owning
 *       region, via {@link Entity#getScheduler()}, which also retires cleanly if it is gone;</li>
 *   <li>a {@code BlockCommandSender} -- addressed by the block's {@link Location}, since a block is
 *       owned by the region that holds its chunk;</li>
 *   <li>the console, an RCON client, or the global server -- no region owns them, so the global
 *       thread is the only safe place to send a message from.</li>
 * </ul>
 *
 * <p>Plain Bukkit/Paper API calls with no state of their own, so there is nothing to unit-test here;
 * the decisions worth testing live in {@code ReloadCoordinator}.
 */
public final class RegionSchedulerAdapter {
    private RegionSchedulerAdapter() {
    }

    /** Runs {@code task} on the thread that owns {@code location}. */
    public static void run(Plugin plugin, Location location, Runnable task) {
        Bukkit.getRegionScheduler().run(plugin, location, ignored -> task.run());
    }

    /** Runs {@code task} on the thread that owns {@code entity}. */
    public static void run(Plugin plugin, Entity entity, Runnable task) {
        entity.getScheduler().run(plugin, ignored -> task.run(), null);
    }

    /** Runs {@code task} on the global thread, for work no region owns. */
    public static void runGlobal(Plugin plugin, Runnable task) {
        Bukkit.getGlobalRegionScheduler().run(plugin, ignored -> task.run());
    }

    /**
     * Runs {@code task} off every tick thread: Folia's async pool, which no region and no global
     * thread waits on. Used for file reads and writes a command asks for, so the tick thread that
     * dispatched the command is not blocked on disk.
     */
    public static void runAsync(Plugin plugin, Runnable task) {
        Bukkit.getAsyncScheduler().runNow(plugin, ignored -> task.run());
    }

    /**
     * Delivers {@code task} on the thread that owns {@code entity}, or runs {@code retired} instead
     * when that entity is already gone -- the sender logged off before the reply was ready.
     *
     * @param retired what to do when the entity is retired; {@code null} means drop the reply
     */
    public static void reply(Plugin plugin, Entity entity, Runnable task, @Nullable Runnable retired) {
        entity.getScheduler().run(plugin, ignored -> task.run(), retired);
    }

    /**
     * Delivers {@code task} on the global thread, for a sender that no region owns: the console, a
     * remote console, or a global command. Block messages must not be sent from an arbitrary thread
     * either, so this is the safe side of the same choice.
     */
    public static void replyGlobal(Plugin plugin, @NotNull Runnable task) {
        Bukkit.getGlobalRegionScheduler().run(plugin, ignored -> task.run());
    }
}
