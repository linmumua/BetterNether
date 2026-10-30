package dev.mumu.betternether.biome;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.block.Biome;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The 23 BetterNether biomes, and the one place that turns their ids into real {@link Biome}s.
 *
 * <h2>Why resolution is strict</h2>
 * Chunks are generated once and persisted, so a world built while the biomes were unresolved keeps
 * vanilla biomes forever. {@link #resolve()} therefore returns either a complete mapping or a
 * {@link Failure} naming the missing ids; there is deliberately no fallback path to call by accident.
 */
public final class NetherBiomes {
    /**
     * Namespace the datapack registers under.
     *
     * <p>This is the **only** place the resource namespace lives on the Java side; the Python
     * generators read the same value from {@code scripts/bnlib/namespace.py}. It is deliberately not
     * the upstream `betternether`: this port publishes its content under `mumu`, so a server can run
     * the real mod and this plugin side by side without one shadowing the other's ids.
     *
     * <p>Note this is separate from the Java package name ({@code dev.mumu.betternether}), which is
     * free to differ — renaming a package would touch every import for no runtime benefit.
     */
    public static final String NAMESPACE = "betternether";

    /**
     * The 23 biome keys, in the order upstream declares them
     * ({@code registry/NetherBiomes.java}), each with a Chinese display name.
     *
     * <p>Order is load-bearing: {@link BiomePlacement} maps its noise output onto this list, so
     * reordering it reshuffles every world ever generated. Append only.
     */
    public static final List<Entry> ALL = List.of(
            new Entry("bone_reef", "骨礁"),
            new Entry("crimson_glowing_woods", "绯红荧光林"),
            new Entry("crimson_pinewood", "绯红松林"),
            new Entry("flooded_deltas", "淹没三角洲"),
            new Entry("gloomwood", "幽暗林"),
            new Entry("gravel_desert", "砂砾荒漠"),
            new Entry("magma_land", "岩浆之地"),
            new Entry("nether_grasslands", "下界草原"),
            new Entry("nether_jungle", "下界丛林"),
            new Entry("nether_mushroom_forest", "下界蘑菇林"),
            new Entry("nether_mushroom_forest_edge", "下界蘑菇林边缘"),
            new Entry("nether_swampland", "下界沼泽"),
            new Entry("nether_swampland_terraces", "下界沼泽梯田"),
            new Entry("old_fungiwoods", "远古菌木林"),
            new Entry("old_swampland", "远古沼泽"),
            new Entry("old_warped_woods", "远古诡异森林"),
            new Entry("poor_nether_grasslands", "贫瘠下界草原"),
            new Entry("soul_plain", "灵魂平原"),
            new Entry("sulfuric_bone_reef", "硫磺骨礁"),
            new Entry("upside_down_forest", "倒挂森林"),
            new Entry("upside_down_forest_cleared", "倒挂森林空地"),
            new Entry("wart_forest", "疣森林"),
            new Entry("wart_forest_edge", "疣森林边缘")
    );

    /**
     * The five vanilla nether biomes, in the order the vanilla {@code minecraft:nether} multi-noise
     * preset declares them.
     *
     * <p>They are here because the default upstream path <em>keeps</em> them: BetterNether's 23 climate
     * points are appended to the vanilla preset (Fabric {@code NetherBiomes#addNetherBiome}), so the
     * nearest-neighbour winner is chosen from 28 points and a vanilla biome wins wherever its climate
     * point is closer. {@link BiomePlacement} reproduces exactly that, so a provider that could only
     * answer the 23 would be painting a different world than upstream.
     *
     * <p>This does not reintroduce a "fall back to vanilla" path: these five are resolved from the
     * registry like everything else, and {@link #resolve()} still refuses to hand out a partial map.
     */
    public static final List<String> VANILLA_NETHER = List.of(
            "minecraft:nether_wastes",
            "minecraft:soul_sand_valley",
            "minecraft:crimson_forest",
            "minecraft:warped_forest",
            "minecraft:basalt_deltas");

    /** One biome: its datapack key, its Chinese name, and its vanilla-tag fallback category. */
    public record Entry(String key, String chineseName) {
        public String namespaced() {
            return NAMESPACE + ":" + key;
        }
    }

    /** The outcome of {@link #resolve()}: either a complete mapping, or the ids that were missing. */
    public sealed interface Resolution permits Resolved, Failure {
    }

    /** A complete mapping from datapack key to live {@link Biome}. Immutable and thread safe. */
    public record Resolved(Map<String, Biome> byKey, List<Biome> declared) implements Resolution {
        /** The biome for a key; never null because every key was resolved before this was built. */
        public Biome get(String key) {
            Biome biome = byKey.get(key);
            if (biome == null) {
                // Unreachable by construction; throwing beats returning null into worldgen.
                throw new IllegalStateException("群系键未解析：" + key + "（这是构造缺陷，不是运行时状态）");
            }
            return biome;
        }
    }

    /** Missing ids, formatted for an operator-facing log line and for the enable failure. */
    public record Failure(List<String> missing, String reason) implements Resolution {
    }

    private NetherBiomes() {
    }

    /**
     * Resolves every biome through {@link RegistryKey#BIOME}.
     *
     * @return {@link Resolved} when all 23 ids exist, otherwise {@link Failure}
     */
    public static Resolution resolve() {
        Registry<Biome> registry;
        try {
            registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.BIOME);
        } catch (IllegalStateException | IllegalArgumentException error) {
            // Paper marks BIOME delayed and throws until the datapack load has built it.
            return new Failure(List.of(), "群系注册表尚不可用（" + error.getClass().getSimpleName()
                    + "）：数据包未在服务器启动时加载。");
        }

        Map<String, Biome> byKey = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        // Vanilla first: BiomePlacement's merged point order puts them there, and a missing vanilla
        // biome means a broken server rather than a missing datapack, so it is reported the same way.
        for (String id : VANILLA_NETHER) {
            NamespacedKey key = NamespacedKey.fromString(id);
            Biome found = key == null ? null : registry.get(key);
            if (found == null) {
                missing.add(id);
            } else {
                byKey.put(id, found);
            }
        }
        for (Entry entry : ALL) {
            NamespacedKey key = NamespacedKey.fromString(entry.namespaced());
            Biome found = key == null ? null : registry.get(key);
            if (found == null) {
                missing.add(entry.namespaced());
            } else {
                byKey.put(entry.key(), found);
            }
        }

        if (!missing.isEmpty()) {
            return new Failure(missing,
                    "缺少 " + missing.size() + " 个下界群系：" + String.join("、", missing)
                            + "（" + VANILLA_NETHER.size() + " 个原版 + " + ALL.size()
                            + " 个 BetterNether）。插件拒绝生成世界，以免把错误的群系永久烘焙进区域文件。"
                            + "请确认插件 jar 完整，并用一次完整的服务器启动（而不是热加载或 /reload）加载本插件。");
        }

        // Contract: getBiomes() must name every biome getBiome() can return.
        return new Resolved(Map.copyOf(byKey), List.copyOf(byKey.values()));
    }

    /** The display name for a key, or the key itself when it is not one of ours. */
    public static @Nullable String displayName(String key) {
        for (Entry entry : ALL) {
            if (entry.key().equals(key)) {
                return entry.chineseName();
            }
        }
        return null;
    }
}
