package dev.mumu.betternether.worldgen;

import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * {@link UpstreamSimpleTreeFeatures} 的测试世界 + 假 CraftEngine 解析器。
 *
 * <h2>为什么不直接用同目录的 {@link FakeWorld}</h2>
 * 那个 FakeWorld 只存 {@link Material}，而<b>这四棵树的形状就是状态差异</b>：
 * {@code mushroom_fir_trunk} 的十二档 {@code shape}、{@code willow_leaves} 的
 * {@code facing}/{@code persistent}、{@code brown_large_mushroom} 的 {@code shape}、
 * {@code wart_seed} 的 {@code facing}。只比材质的话，「上段写成了下段形状」
 * 这种错误一条都抓不到。所以这里存的是<b>完整状态字符串</b>
 * （{@code "betternether:mushroom_fir_trunk[shape=side_big_n]"}），
 * 材质只是从状态字符串里的 id 查表得来。
 *
 * <h2>{@code getAsString()} 为什么重要</h2>
 * 生产代码的属性核验（{@link UpstreamSimpleTreeFeatures#loadNotes()} 里的
 * 「属性未被该方块定义」）<b>就是读状态字符串</b>的：CE 对不认识的属性静默退回默认状态，
 * 只有把解析结果反着读一遍才能发现。这个假实现因此必须真的实现 {@code getAsString()}，
 * 而不是像已有 FakeWorld 那样只回答材质。
 */
final class UpstreamSimpleTreeFeaturesFakeWorld implements WorldView {
    /**
     * id → 宿主材质。刻意让会被代码互相比较的 id 拿到**不同**材质
     * （{@code wart_log} / {@code nether_wart_block}、{@code nether_mycelium} / 灵魂地面）：
     * CE 下「这是不是同一种方块」只剩材质比较，材质撞了测试就会假装通过。
     */
    private static final Map<String, Material> MATERIALS = buildMaterials();

    private final Map<Long, String> cells = new HashMap<>();
    private final int minY;
    private final int maxY;
    private final Random random;
    private final int chunkRadius;
    /** 写区无限制（相当于上游「level 不是 WorldGenRegion」那种情形：骨粉长树、结构世界）。 */
    private boolean unbounded;

    UpstreamSimpleTreeFeaturesFakeWorld(int minY, int maxY, long seed) {
        this(minY, maxY, seed, 32);
    }

    /** 没有写区限制的世界：{@code inRegion} 只受 y 约束。 */
    static UpstreamSimpleTreeFeaturesFakeWorld unboundedWorld(int minY, int maxY, long seed) {
        final UpstreamSimpleTreeFeaturesFakeWorld world =
                new UpstreamSimpleTreeFeaturesFakeWorld(minY, maxY, seed, 32);
        world.unbounded = true;
        return world;
    }

    /**
     * @param chunkRadius 可写区域按**区块**给出（{@code chunkX/chunkZ ∈ [-chunkRadius, chunkRadius]}），
     *                    因为真实的 {@code LimitedRegion} 就是区块对齐的，而
     *                    {@link UpstreamSimpleTreeFeatures.Zone} 的写区探测也是区块粒度。
     *                    用「方块半径」建模会让那套探测算出一个自相矛盾的框。
     */
    UpstreamSimpleTreeFeaturesFakeWorld(int minY, int maxY, long seed, int chunkRadius) {
        this.minY = minY;
        this.maxY = maxY;
        this.random = new Random(seed);
        this.chunkRadius = chunkRadius;
    }

    private static Map<String, Material> buildMaterials() {
        Map<String, Material> table = new LinkedHashMap<>();
        table.put("minecraft:air", Material.AIR);
        table.put("minecraft:cave_air", Material.CAVE_AIR);
        // 地面
        table.put("betternether:nether_mycelium", Material.MYCELIUM);
        table.put("betternether:veined_sand", Material.RED_SAND);
        table.put("betternether:farmland", Material.FARMLAND);
        table.put("minecraft:soul_sand", Material.SOUL_SAND);
        table.put("minecraft:soul_soil", Material.SOUL_SOIL);
        table.put("minecraft:netherrack", Material.NETHERRACK);
        table.put("minecraft:bedrock", Material.BEDROCK);
        table.put("minecraft:blackstone", Material.BLACKSTONE);
        table.put("minecraft:obsidian", Material.OBSIDIAN);
        table.put("minecraft:tall_grass", Material.TALL_GRASS);
        // 上游塞进 minecraft:nylium 的四种草（地面判据的自定义成员），各占一个材质。
        table.put("betternether:mushroom_grass", Material.WARPED_NYLIUM);
        table.put("betternether:jungle_grass", Material.CRIMSON_NYLIUM);
        table.put("betternether:sepia_mushroom_grass", Material.BASALT);
        table.put("betternether:swampland_grass", Material.WARPED_HYPHAE);
        // wover stones 增量里另外三个自定义地面（成片出现在地形里）。
        table.put("betternether:netherrack_moss", Material.MOSS_BLOCK);
        table.put("betternether:veined_gloomsculk", Material.SCULK);
        table.put("betternether:ceiling_mushrooms", Material.RED_MUSHROOM_BLOCK);
        // 「同材质不同 id」对照物：CE 下身份按 id 判，这三对必须不被混淆。
        table.put("betternether:lookalike_moss", Material.MOSS_BLOCK);
        table.put("betternether:lookalike_soul", Material.SOUL_SAND);
        table.put("betternether:lookalike_wart_log", Material.CRIMSON_STEM);
        // mushroom_fir
        table.put("betternether:mushroom_fir_trunk", Material.WARPED_STEM);
        // wart_tree
        table.put("betternether:wart_log", Material.CRIMSON_STEM);
        table.put("betternether:wart_roots", Material.CRIMSON_ROOTS);
        table.put("betternether:wart_seed", Material.WARPED_ROOTS);
        table.put("minecraft:nether_wart_block", Material.NETHER_WART_BLOCK);
        table.put("betternether:black_bush", Material.DEAD_BUSH);
        table.put("betternether:soul_vein", Material.WEEPING_VINES);
        table.put("betternether:soul_lily", Material.LILY_PAD);
        table.put("betternether:soul_lily_sapling", Material.OAK_SAPLING);
        table.put("minecraft:nether_wart", Material.NETHER_WART);
        // willow_tree
        table.put("betternether:willow_trunk", Material.OAK_LOG);
        table.put("betternether:willow_branch", Material.OAK_FENCE);
        table.put("betternether:willow_leaves", Material.OAK_LEAVES);
        // old_willow_tree：树皮/树干必须拿到**不同**材质，否则「内侧是 log、外侧是 bark」这条断言
        // 根本立不住；挂墙植物与 rubeus_log 也各占一个。
        table.put("betternether:willow_bark", Material.STRIPPED_OAK_LOG);
        table.put("betternether:willow_log", Material.STRIPPED_OAK_WOOD);
        table.put("betternether:rubeus_log", Material.STRIPPED_CRIMSON_STEM);
        table.put("betternether:wall_moss", Material.VINE);
        table.put("betternether:wall_mushroom_brown", Material.BROWN_MUSHROOM);
        table.put("betternether:wall_mushroom_red", Material.RED_MUSHROOM);
        // big_brown_mushroom
        table.put("betternether:brown_large_mushroom", Material.BROWN_MUSHROOM_BLOCK);
        return table;
    }

    // ---------------------------------------------------------------- 假 CE 解析器

    /**
     * 一个假的 {@code CraftEngineLookup}。
     *
     * @param keepProperties {@code false} 时<b>丢掉所有属性</b>，模拟「CE 包里没定义这个属性」，
     *                       用来验证生产代码会为这种情况留下报告，而不是静默当它支持
     * @param missingId      这个 id 解析为 {@code null}（模拟方块根本不在包里）
     */
    static UpstreamVegetationFeatures.BlockResolver resolver(boolean keepProperties, @Nullable String missingId) {
        return new UpstreamVegetationFeatures.BlockResolver() {
            @Override
            public BlockData resolve(String id, Map<String, String> properties) {
                if (id.equals(missingId)) {
                    return null;
                }
                return dataOf(stateString(id, keepProperties ? properties : Map.of()));
            }

            @Override
            public java.util.List<String> missingProperties(String id, Map<String, String> properties) {
                // `keepProperties=false` 就是「CE 包里没定义这些属性」，如实报出来。
                // 生产实现查的是 CE 方块定义；这里没有 CE，所以按注入的开关模拟。
                if (!keepProperties && !id.equals(missingId)) {
                    return java.util.List.copyOf(properties.keySet());
                }
                return java.util.List.of();
            }
        };
    }

    static UpstreamVegetationFeatures.BlockResolver resolver() {
        return resolver(true, null);
    }

    static String stateString(String id, Map<String, String> properties) {
        if (properties.isEmpty()) {
            return id;
        }
        StringBuilder builder = new StringBuilder(id).append('[');
        boolean first = true;
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            if (!first) {
                builder.append(',');
            }
            builder.append(entry.getKey()).append('=').append(entry.getValue());
            first = false;
        }
        return builder.append(']').toString();
    }

    /** 完整状态字符串 → {@link BlockData}（只实现放置逻辑真正会用到的那几个方法）。 */
    static BlockData dataOf(String stateString) {
        String id = idOf(stateString);
        Material material = MATERIALS.getOrDefault(id, Material.BARRIER);
        return (BlockData) Proxy.newProxyInstance(
                UpstreamSimpleTreeFeaturesFakeWorld.class.getClassLoader(),
                new Class<?>[]{BlockData.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getMaterial" -> material;
                    case "getAsString", "toString" -> stateString;
                    case "matches" -> args != null && args.length == 1
                            && args[0] instanceof BlockData other
                            && stateString.equals(other.getAsString());
                    case "equals" -> args != null && args.length == 1
                            && args[0] instanceof BlockData other
                            && stateString.equals(other.getAsString());
                    case "hashCode" -> stateString.hashCode();
                    case "clone", "copy" -> proxy;
                    default -> null;
                });
    }

    static String idOf(String stateString) {
        int bracket = stateString.indexOf('[');
        return bracket < 0 ? stateString : stateString.substring(0, bracket);
    }

    /** 某个 id 在这份小抄里映射到的宿主材质（用于断言「同材质不同 id」确实同材质）。 */
    static Material materialOf(String id) {
        return MATERIALS.getOrDefault(id, Material.BARRIER);
    }

    static boolean isAirState(String id) {
        return "minecraft:air".equals(id) || "minecraft:cave_air".equals(id)
                || "minecraft:void_air".equals(id);
    }

    // ---------------------------------------------------------------- 建模

    /** 一列地面：{@code [fromY, toY]} 全是这个 id。 */
    UpstreamSimpleTreeFeaturesFakeWorld fill(int x, int z, int fromY, int toY, String id) {
        for (int y = fromY; y <= toY; y++) {
            cells.put(key(x, y, z), id);
        }
        return this;
    }

    /** 把一个坐标设成某个状态。 */
    UpstreamSimpleTreeFeaturesFakeWorld put(int x, int y, int z, String stateString) {
        cells.put(key(x, y, z), stateString);
        return this;
    }

    /** 该坐标的完整状态字符串；空气返回 {@code null}。 */
    @Nullable
    String state(int x, int y, int z) {
        return cells.get(key(x, y, z));
    }

    /** 该坐标的方块 id；空气返回 {@code null}。 */
    @Nullable
    String idAt(int x, int y, int z) {
        String state = state(x, y, z);
        return state == null ? null : idOf(state);
    }

    /** 该坐标某个属性的值；没有这个属性返回 {@code null}。 */
    @Nullable
    String propAt(int x, int y, int z, String name) {
        String state = state(x, y, z);
        if (state == null) {
            return null;
        }
        int bracket = state.indexOf('[');
        if (bracket < 0) {
            return null;
        }
        String body = state.substring(bracket + 1, state.length() - 1);
        for (String pair : body.split(",")) {
            int equals = pair.indexOf('=');
            if (equals > 0 && pair.substring(0, equals).equals(name)) {
                return pair.substring(equals + 1);
            }
        }
        return null;
    }

    /** 世界里出现过的全部方块 id（用来断言「没有多余的东西被写进来」）。 */
    Set<String> ids() {
        Set<String> ids = new HashSet<>();
        for (String state : cells.values()) {
            ids.add(idOf(state));
        }
        return ids;
    }

    /** 非空气格的数量。 */
    int placedCount() {
        return cells.size();
    }

    /** 整份世界快照，用来断言「同 seed 两次放置完全一致」。 */
    Map<Long, String> snapshot() {
        return new HashMap<>(cells);
    }

    static long key(int x, int y, int z) {
        return ((long) (x & 0xFFFFF) << 40) | ((long) (y & 0xFFFFF) << 20) | (z & 0xFFFFF);
    }

    // ---------------------------------------------------------------- WorldView

    @Override
    public boolean inRegion(int x, int y, int z) {
        if (y < minY || y > maxY) {
            return false;
        }
        if (unbounded) {
            return true;
        }
        return Math.abs(x >> 4) <= chunkRadius && Math.abs(z >> 4) <= chunkRadius;
    }

    @Override
    public int minY() {
        return minY;
    }

    @Override
    public int maxY() {
        return maxY;
    }

    @Override
    public boolean isAir(int x, int y, int z) {
        return cells.get(key(x, y, z)) == null;
    }

    @Override
    public @Nullable BlockData blockAt(int x, int y, int z) {
        if (!inRegion(x, y, z)) {
            return null;
        }
        String state = cells.get(key(x, y, z));
        return dataOf(state == null ? "minecraft:air" : state);
    }

    @Override
    public void setBlock(int x, int y, int z, BlockData data) {
        if (data == null) {
            throw new AssertionError("放置管线不该把 null 传给 setBlock");
        }
        final String state = data.getAsString();
        // 写空气 = 清掉这一格（真实世界就是这样）。OldWillowTree 开头会把落点清成空气。
        if (isAirState(idOf(state))) {
            cells.remove(key(x, y, z));
            return;
        }
        cells.put(key(x, y, z), state);
    }

    @Override
    public String biomeAt(int x, int y, int z) {
        return "betternether:wart_forest";
    }

    @Override
    public @Nullable String decoratedBiome() {
        return null;
    }

    @Override
    public Random random() {
        return random;
    }

    // ---------------------------------------------------------------- 真实语义通道

    /**
     * 服务端真实 tag 的小抄。**只放原版 tag**：wover 的 {@code surfaces/*} 来自上游数据包，
     * 本移植没加载它们，真实通道会返回 {@code null}，所以这里也不该假装有。
     */
    private static final Map<String, Set<String>> TAGS = Map.of(
            "minecraft:base_stone_nether", Set.of(
                    "minecraft:netherrack", "minecraft:blackstone", "minecraft:basalt"),
            "minecraft:soul_fire_base_blocks", Set.of(
                    "minecraft:soul_sand", "minecraft:soul_soil"),
            "minecraft:nylium", Set.of(
                    "minecraft:crimson_nylium", "minecraft:warped_nylium"));

    /** {@code canBeReplaced()} 为真的方块 id（原版那一批；自定义方块由 CE 的 trait 决定，见生产注释）。 */
    private static final Set<String> REPLACEABLE_IDS = Set.of(
            "minecraft:air", "minecraft:cave_air",
            "minecraft:fire", "minecraft:soul_fire",
            "minecraft:crimson_roots", "minecraft:warped_roots", "minecraft:nether_sprouts",
            "minecraft:short_grass", "minecraft:tall_grass", "minecraft:lava", "minecraft:water");

    private int blockIdCalls;
    private int replaceableCalls;
    private int inTagCalls;

    /** 真实身份 id（CE 下自定义方块会伪装成宿主 id，这里的假世界直接回答它自己的 id）。 */
    @Override
    public @Nullable String blockId(int x, int y, int z) {
        blockIdCalls++;
        if (!inRegion(x, y, z)) {
            return null;
        }
        final String state = cells.get(key(x, y, z));
        return state == null ? "minecraft:air" : idOf(state);
    }

    /** 真实 {@code canBeReplaced()}；{@code null} 表示「问不出来」（这里总能回答）。 */
    @Override
    public @Nullable Boolean replaceable(int x, int y, int z) {
        replaceableCalls++;
        if (!inRegion(x, y, z)) {
            return null;
        }
        final String state = cells.get(key(x, y, z));
        return state == null || REPLACEABLE_IDS.contains(idOf(state));
    }

    /** 真实 tag 命中；tag 不在表里 → {@code null}（**不是** false）。 */
    @Override
    public @Nullable Boolean inTag(String tag, int x, int y, int z) {
        inTagCalls++;
        final Set<String> members = TAGS.get(tag);
        if (members == null || !inRegion(x, y, z)) {
            return null;
        }
        final String state = cells.get(key(x, y, z));
        return members.contains(state == null ? "minecraft:air" : idOf(state));
    }

    int blockIdCalls() {
        return blockIdCalls;
    }

    int replaceableCalls() {
        return replaceableCalls;
    }

    int inTagCalls() {
        return inTagCalls;
    }
}
