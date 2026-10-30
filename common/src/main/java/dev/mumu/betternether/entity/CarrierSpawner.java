package dev.mumu.betternether.entity;

import dev.mumu.betternether.vanilla.RegionSchedulerAdapter;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Spawns a {@link CarrierSpecies} on the thread that owns the target region, then keeps it there.
 *
 * <h2>Folia safety</h2>
 * Everything that touches a world, a block or an entity goes through exactly two schedulers:
 * <ul>
 *   <li>{@link RegionSchedulerAdapter#run(Plugin, Location, Runnable)} for the spawn itself — the
 *       location's owning region, from whatever thread the caller happens to be on;</li>
 *   <li>{@link Entity#getScheduler()} for the follow-up work (the ambient loop), so the task is
 *       retired with the entity instead of outliving it.</li>
 * </ul>
 * There is deliberately no {@code Bukkit.getScheduler()} / {@code runTask} call here: on Folia those
 * are either unavailable or land on a thread that does not own the entity, which is a hard error
 * there and a silent race on Paper. The same two calls work on both, so there is no branch.
 *
 * <h2>Opt-in only</h2>
 * Nothing in this class is called automatically. There is no chunk-populate hook and no listener
 * registration here, so a fresh install never puts a single new mob into the default world; spawning
 * happens only when a command, a config the operator wrote, or a test explicitly asks for it.
 *
 * <h2>What is approximated</h2>
 * The carrier keeps its vanilla model and AI. This class applies the upstream attribute values, a
 * translatable name, glow where the upstream mob is emissive, and — for carriers that are not already
 * fire immune — an infinite fire-resistance effect, because every BetterNether mob is
 * {@code fireImmune()}. None of that makes it the upstream mob; see {@link CarrierSpecies#note()}.
 */
public final class CarrierSpawner {

    /** How often the ambient sound repeats, in ticks. */
    private static final long AMBIENT_PERIOD_TICKS = 100L;

    private CarrierSpawner() {
    }

    /**
     * Spawns the carrier at {@code location}, on the region that owns it.
     *
     * @param onSpawned called with the finished entity <b>on the region thread</b>; may be {@code null}
     */
    public static void spawn(
            Plugin plugin,
            CarrierSpecies species,
            Location location,
            Consumer<LivingEntity> onSpawned
    ) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(species, "species");
        Objects.requireNonNull(location, "location");

        RegionSchedulerAdapter.run(plugin, location, () -> {
            World world = location.getWorld();
            if (world == null) {
                return;
            }
            Entity spawned = world.spawnEntity(location, species.carrier());
            if (!(spawned instanceof LivingEntity living)) {
                // 载体不是生物时不能留下半个实体：清掉并让调用方看得见。
                spawned.remove();
                plugin.getLogger().warning("载体 " + species.carrier() + " 不是生物，已取消生成 "
                        + species.upstreamId());
                return;
            }
            apply(plugin, species, living);
            if (onSpawned != null) {
                onSpawned.accept(living);
            }
        });
    }

    /** Convenience overload for callers that do not need the entity back. */
    public static void spawn(Plugin plugin, CarrierSpecies species, Location location) {
        spawn(plugin, species, location, null);
    }

    /** Applies upstream attributes, name, glow and fire immunity. Runs on the entity's owning thread. */
    private static void apply(Plugin plugin, CarrierSpecies species, LivingEntity living) {
        living.customName(Component.translatable(species.displayNameKey()));
        living.setCustomNameVisible(false);
        living.setPersistent(false);
        living.setRemoveWhenFarAway(true);
        living.setGlowing(species.glowing());

        for (CarrierSpecies.AttributeValue value : species.attributes()) {
            AttributeInstance instance = living.getAttribute(value.attribute());
            if (instance == null) {
                // 载体没有这个属性（例如悦灵没有攻击力）：跳过而不是让整次生成失败。
                continue;
            }
            instance.setBaseValue(value.value());
        }

        AttributeInstance health = living.getAttribute(org.bukkit.attribute.Attribute.MAX_HEALTH);
        if (health != null) {
            // 改完 MAX_HEALTH 后当前生命仍是旧上限，必须夹到新上限内，否则 0.5 血的水母会带着
            // 旧血量活着，一被打就出现"血条跳变"。
            living.setHealth(Math.min(health.getValue(), living.getHealth()));
        }

        if (species.needsFireResistance()) {
            living.addPotionEffect(new PotionEffect(
                    PotionEffectType.FIRE_RESISTANCE,
                    PotionEffect.INFINITE_DURATION,
                    0,
                    false,
                    false,
                    false));
        }

        if (species.ambientSound() != null) {
            scheduleAmbience(plugin, species, living);
        }
    }

    /**
     * Repeats the species' ambient sound while the entity lives.
     *
     * <p>Driven by {@link Entity#getScheduler()} rather than a region task so the loop is cancelled
     * when the entity is removed; a region task would keep firing at a dead entity's last location.
     */
    private static void scheduleAmbience(Plugin plugin, CarrierSpecies species, LivingEntity living) {
        living.getScheduler().runAtFixedRate(plugin, task -> {
            if (!living.isValid()) {
                task.cancel();
                return;
            }
            Location at = living.getLocation();
            World world = at.getWorld();
            if (world != null) {
                world.playSound(at, species.ambientSound(), SoundCategory.NEUTRAL, 0.6f, 1.0f);
            }
        }, null, AMBIENT_PERIOD_TICKS, AMBIENT_PERIOD_TICKS);
    }

    /**
     * Cancels a task obtained from this class. Exposed only so a caller that stores the handle from a
     * future overload does not have to reach for {@link ScheduledTask} itself.
     */
    public static void cancel(ScheduledTask task) {
        if (task != null) {
            task.cancel();
        }
    }
}
