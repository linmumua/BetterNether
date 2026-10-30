package dev.mumu.betternether.worldgen;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.MultipleFacing;
import org.bukkit.block.data.Waterlogged;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code minecraft:multiface_growth}（幽匿脉）的方向与扩散。
 *
 * <h2>为什么单独一份测试</h2>
 * 这条特征的「对不对」不在方块数量上，而在**哪一面被点亮**：原版
 * {@code MultifaceBlock#getStateForPlacement(state, level, pos, dir)} 写的是
 * {@code setValue(getFaceProperty(dir), true)}，{@code dir} 来自「可依附的邻居方向」；
 * 扩散的三条路径（{@code SAME_POSITION} / {@code SAME_PLANE} / {@code WRAP_AROUND}）各自
 * 落在不同的坐标与面上，还有一条 sculk 专有的「绕角且背面实心就不长」的否决。
 * 这些都必须能被逐格断言，所以这里用能记 {@link BlockData} 的假世界与能改面的假状态，
 * 而不是只记材质的 {@link FakeWorld}。
 *
 * <h2>几何与期望值怎么来的</h2>
 * 全部由 {@code javap -p -c} 反汇编推出来（见 {@link UpstreamTerrainFeatures.SculkVein} 的
 * 类文档），不是「试出来的」：
 * <ul>
 *   <li>贴地放置 → {@code DOWN} 面；</li>
 *   <li>{@code SAME_PLANE} 需要「目标再往前一格」挡住那一面 → 才长出新的一格；</li>
 *   <li>{@code SAME_POSITION} 只给**已有**的那一格加面（{@code DOWN} 已存在时直接失败）；</li>
 *   <li>{@code WRAP_AROUND} 被「曼哈顿距离 2 且背面实心」否决 → 脉不往地里长。</li>
 * </ul>
 */
class SculkVeinFeatureTest {
    /** 与 {@code data/betternether/worldgen/feature/sculk_vein.json} 同形的配置。 */
    private static String json(float chance, boolean floor, boolean ceiling, boolean wall) {
        return """
                {"type":"minecraft:multiface_growth",
                 "block":"minecraft:sculk_vein",
                 "can_be_placed_on":"#wover:surfaces/sculk_like",
                 "can_place_on_ceiling":%s,"can_place_on_floor":%s,"can_place_on_wall":%s,
                 "chance_of_spreading":%s}""".formatted(ceiling, floor, wall, chance);
    }

    private static JsonObject parse(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    /** 贴地放置必须点亮 {@code DOWN} 面。 */
    @Test
    void attachesToTheFaceItFound() {
        Feature feature = feature(json(0.0F, true, true, true));
        StateWorld world = new StateWorld(0, 127, 3L).floor(59, -2, 2);

        assertTrue(feature.place(world, new Point(0, 60, 0)));
        BlockData placed = world.dataAt(0, 60, 0);
        assertNotNull(placed, "地面上必须放下一条脉");
        assertEquals(Set.of(BlockFace.DOWN), faces(placed), "贴地放置只该有 DOWN 面");
        assertEquals(1, world.postProcessingCount(),
                "原版写完方块就登记后处理标记（markPosForPostProcessing），这里必须被调到");
    }

    /**
     * 扩散：{@code SAME_PLANE} 在「目标再往前一格实心」时沿那一面长出一格新脉。
     *
     * <p>几何：y=59 整层地面；只有 {@code (2,60,0)} 实心。脉在 {@code (0,60,0)}（{@code DOWN}），
     * {@code chance_of_spreading = 1.0}。唯一可行的扩散是
     * {@code SAME_PLANE EAST} → 新脉在 {@code (1,60,0)} 且面为 {@code EAST}。
     * {@code SAME_POSITION} 全灭（{@code DOWN} 已有面、其余邻格空气），
     * {@code WRAP_AROUND} 被「绕角且背面实心」否决。
     */
    @Test
    void spreadsAlongAnOccludingFace() {
        Feature feature = feature(json(1.0F, true, true, true));
        StateWorld world = new StateWorld(0, 127, 5L)
                .floor(59, -2, 2)
                .solid(2, 60, 0, Material.NETHERRACK);

        assertTrue(feature.place(world, new Point(0, 60, 0)));

        assertEquals(Set.of(BlockFace.DOWN), faces(world.dataAt(0, 60, 0)));
        BlockData spread = world.dataAt(1, 60, 0);
        assertNotNull(spread, "被 (2,60,0) 挡住的那一面必须让脉长到 (1,60,0)");
        assertEquals(Set.of(BlockFace.EAST), faces(spread),
                "SAME_PLANE 的面就是扩散方向本身");
        assertEquals(2, world.veinCount(), "chance=1.0 时一条 placed feature 只多长一格");
    }

    /**
     * 平坦地面上不扩散。
     *
     * <p>{@code WRAP_AROUND} 的候选落在 {@code pos + spreadDirection + fromFace}，对贴地的脉就是
     * 「地面那一格」，{@code stateCanBeReplaced} 里「曼哈顿距离 2 且背面实心」直接否决；
     * {@code SAME_PLANE} 又要求目标外一格挡住，平坦地面上没有。所以原地只留一条脉 ——
     * 这就是原版脉贴着台阶/墙角长、却不会在平地上摊开一片的原因。
     */
    @Test
    void flatGroundDoesNotSpread() {
        Feature feature = feature(json(1.0F, true, true, true));
        StateWorld world = new StateWorld(0, 127, 9L).floor(59, -3, 3);

        assertTrue(feature.place(world, new Point(0, 60, 0)));
        assertEquals(1, world.veinCount(), "平坦地面上不该多长");
        assertEquals(Set.of(BlockFace.DOWN), faces(world.dataAt(0, 60, 0)));
    }

    /** 关掉 floor 后贴地候选一个都不放；只留 ceiling 时天花板下必须点亮 {@code UP}。 */
    @Test
    void honoursTheFaceSwitches() {
        Feature ceilingOnly = feature(json(0.0F, false, true, false));

        StateWorld floor = new StateWorld(0, 127, 7L).floor(59, -1, 1);
        assertFalse(ceilingOnly.place(floor, new Point(0, 60, 0)),
                "只允许天花板面时，贴地的位置不该被放置");
        assertEquals(0, floor.veinCount());

        StateWorld ceiling = new StateWorld(0, 127, 7L).ceiling(61, -1, 1);
        assertTrue(ceilingOnly.place(ceiling, new Point(0, 60, 0)));
        assertEquals(Set.of(BlockFace.UP), faces(ceiling.dataAt(0, 60, 0)));
    }

    /**
     * `canAttachTo` 的**第二分支**：支撑面不满、但碰撞面整面填满时也要能贴。
     *
     * <p>上游是 `isFaceFull(getBlockSupportShape(...), dir) || isFaceFull(getCollisionShape(...), dir)`；
     * 只接第一分支会让这类方块上的脉整片消失（而且不会报错）。
     */
    @Test
    void attachesThroughTheCollisionShapeBranch() {
        Feature feature = feature(json(0.0F, true, true, true));
        StateWorld world = new StateWorld(0, 127, 17L)
                .floor(59, -1, 1)
                .replace(0, 59, 0, StateWorld.COLLISION_ONLY);

        assertTrue(feature.place(world, new Point(0, 60, 0)),
                "支撑面不满但碰撞面满的方块也必须能依附");
        assertEquals(Set.of(BlockFace.DOWN), faces(world.dataAt(0, 60, 0)));
    }

    /** 四周全空：没有可依附的面，一条都不放，返回 false。 */
    @Test
    void needsSomethingToAttachTo() {
        Feature feature = feature(json(1.0F, true, true, true));
        StateWorld empty = new StateWorld(0, 127, 11L);
        assertFalse(feature.place(empty, new Point(0, 60, 0)));
        assertEquals(0, empty.veinCount());
    }

    /** 落点本身已经被占用（非空气非水）→ 直接放弃，符合 {@code place} 的第一个判据。 */
    @Test
    void refusesAnOccupiedOrigin() {
        Feature feature = feature(json(1.0F, true, true, true));
        StateWorld world = new StateWorld(0, 127, 13L)
                .floor(59, -1, 1)
                .solid(0, 60, 0, Material.NETHERRACK);
        assertFalse(feature.place(world, new Point(0, 60, 0)));
        assertEquals(0, world.veinCount());
    }

    // ---------------------------------------------------------------- 夹具

    private static Feature feature(String text) {
        SculkAccess access = new SculkAccess();
        Feature feature = UpstreamTerrainFeatures.create("minecraft:multiface_growth",
                parse(text), new UpstreamTerrainFeatures.WorldgenAccess(
                        access::block, access::predicate, access::placed));
        assertNotNull(feature, "multiface_growth 必须由本注册表接管");
        return feature;
    }

    private static Set<BlockFace> faces(BlockData data) {
        if (data == null) {
            return Set.of();
        }
        return data instanceof MultipleFacing facing ? facing.getFaces() : Set.of();
    }

    /**
     * 最小的装载器替身：幽匿脉给一个可改面的状态，谓词按「实心非基岩」判（对应
     * {@code wover:surfaces/sculk_like} 在本项目里的解析口径）。
     */
    static final class SculkAccess {
        BlockData block(String id, JsonObject properties) {
            return "minecraft:sculk_vein".equals(id) ? VeinState.create() : null;
        }

        BlockPredicates.Predicate predicate(JsonObject json) {
            return new BlockPredicates.Surface("wover:surfaces/sculk_like");
        }

        Feature placed(JsonElement element) {
            return null;
        }
    }

    /**
     * 只回答「材质 / 是否遮蔽 / 是否同一方块」的普通状态。
     *
     * <p>{@link FakeWorld#blockData} 的代理对 {@code isOccluding()} 返回 {@code null}
     * （它只服务「按材质比较」的断言），而幽匿脉要按**状态**问遮蔽，所以这里自带一个。
     */
    static BlockData simple(Material material, boolean occluding) {
        return (BlockData) Proxy.newProxyInstance(
                SculkVeinFeatureTest.class.getClassLoader(),
                new Class<?>[]{BlockData.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getMaterial" -> material;
                    case "isOccluding" -> occluding;
                    case "matches" -> args[0] instanceof BlockData other
                            && other.getMaterial() == material;
                    case "clone", "copy" -> proxy;
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> material.hashCode();
                    case "getAsString" -> material.getKey().toString();
                    case "toString" -> "State[" + material + "]";
                    default -> null;
                });
    }

    private static boolean isSolid(Material material) {
        return material != Material.AIR && material != Material.WATER && material != Material.LAVA;
    }

    /** 能记面、能 clone 的 {@code minecraft:sculk_vein} 状态。 */
    static final class VeinState implements InvocationHandler {
        private final Set<BlockFace> faces = new LinkedHashSet<>();
        private boolean waterlogged;

        static BlockData create() {
            return (BlockData) Proxy.newProxyInstance(
                    SculkVeinFeatureTest.class.getClassLoader(),
                    new Class<?>[]{BlockData.class, MultipleFacing.class, Waterlogged.class},
                    new VeinState());
        }

        private static VeinState handlerOf(BlockData data) {
            return (VeinState) Proxy.getInvocationHandler(data);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "getMaterial" -> Material.SCULK_VEIN;
                case "isOccluding" -> false;   // 幽匿脉不是整面方块
                case "getFaces" -> new LinkedHashSet<>(faces);
                case "getAllowedFaces" -> EnumSet.allOf(BlockFace.class);
                case "hasFace" -> faces.contains((BlockFace) args[0]);
                case "setFace" -> {
                    if ((Boolean) args[1]) {
                        faces.add((BlockFace) args[0]);
                    } else {
                        faces.remove((BlockFace) args[0]);
                    }
                    yield null;
                }
                case "isWaterlogged" -> waterlogged;
                case "setWaterlogged" -> {
                    this.waterlogged = (Boolean) args[0];
                    yield null;
                }
                case "clone", "copy" -> {
                    BlockData copy = create();
                    VeinState handler = handlerOf(copy);
                    handler.faces.addAll(faces);
                    handler.waterlogged = waterlogged;
                    yield copy;
                }
                case "matches" -> args[0] instanceof BlockData other
                        && other.getMaterial() == Material.SCULK_VEIN;
                case "getAsString" -> "minecraft:sculk_vein" + faces;
                case "equals" -> proxy == args[0];
                case "hashCode" -> faces.hashCode();
                case "toString" -> "VeinState" + faces;
                default -> null;
            };
        }
    }

    /**
     * 按 {@link BlockData} 记账的假世界。
     *
     * <p>{@link FakeWorld} 只存材质；脉的正确性在「哪一面」，所以这里另存整个状态。
     */
    static final class StateWorld implements WorldView {
        /** 「支撑形状不满、碰撞形状满」的代表方块（真机上是灵魂沙这类）。 */
        static final Material COLLISION_ONLY = Material.SOUL_SAND;
        private static final BlockData AIR = simple(Material.AIR, false);
        private final Map<Long, BlockData> blocks = new HashMap<>();
        private final int minY;
        private final int maxY;
        private final Random random;
        private final Set<Long> postProcessing = new LinkedHashSet<>();

        StateWorld(int minY, int maxY, long seed) {
            this.minY = minY;
            this.maxY = maxY;
            this.random = new Random(seed);
        }

        /** y 这一层、x∈[fromX,toX]、z 从 -toX 到 toX 的地面。 */
        StateWorld floor(int y, int fromX, int toX) {
            for (int x = fromX; x <= toX; x++) {
                for (int z = fromX; z <= toX; z++) {
                    blocks.put(FakeWorld.key(x, y, z), simple(Material.NETHERRACK, true));
                }
            }
            return this;
        }

        /** y 这一层的天花板。 */
        StateWorld ceiling(int y, int fromX, int toX) {
            return floor(y, fromX, toX);
        }

        /** 覆盖某一格（与 {@link #solid} 同义，读起来更贴近用例）。 */
        StateWorld replace(int x, int y, int z, Material material) {
            return solid(x, y, z, material);
        }

        StateWorld solid(int x, int y, int z, Material material) {
            blocks.put(FakeWorld.key(x, y, z), simple(material, isSolid(material)));
            return this;
        }

        BlockData dataAt(int x, int y, int z) {
            return blocks.get(FakeWorld.key(x, y, z));
        }

        int veinCount() {
            int count = 0;
            for (BlockData data : blocks.values()) {
                if (data.getMaterial() == Material.SCULK_VEIN) {
                    count++;
                }
            }
            return count;
        }

        @Override
        public boolean inRegion(int x, int y, int z) {
            return y >= minY && y <= maxY;
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
            BlockData data = blockAt(x, y, z);
            return data == null || data.getMaterial() == Material.AIR;
        }

        @Override
        public BlockData blockAt(int x, int y, int z) {
            if (!inRegion(x, y, z)) {
                return null;
            }
            return blocks.getOrDefault(FakeWorld.key(x, y, z), AIR);
        }

        @Override
        public void setBlock(int x, int y, int z, BlockData data) {
            blocks.put(FakeWorld.key(x, y, z), data);
        }

        @Override
        public String biomeAt(int x, int y, int z) {
            return "test";
        }

        @Override
        public String decoratedBiome() {
            return "test";
        }

        @Override
        public Random random() {
            return random;
        }

        // ---- 真实判据的测试替身（生产实现见 RegionView，零反射接 NMS）----

        /**
         * 整块实心 = 六面都够实；脉自己任何面都不够实。
         *
         * <p>特意让 {@link #COLLISION_ONLY}（灵魂沙）代表「支撑形状不满、碰撞形状满」的方块：
         * 它的 {@code faceSturdy} 为 false、{@code faceFullCollision} 为 true —— 用来验证
         * {@code canAttachTo} 的**第二分支**真的被走到。
         */
        @Override
        public Boolean faceSturdy(int x, int y, int z, Point.Direction face) {
            BlockData data = blockAt(x, y, z);
            if (data == null) {
                return null;
            }
            if (data.getMaterial() == COLLISION_ONLY || data.getMaterial() == Material.SCULK_VEIN) {
                return Boolean.FALSE;
            }
            return isSolid(data.getMaterial());
        }

        @Override
        public Boolean faceFullCollision(int x, int y, int z, Point.Direction face) {
            BlockData data = blockAt(x, y, z);
            if (data == null) {
                return null;
            }
            // 整块实心的碰撞箱就是整面填满；COLLISION_ONLY 也满；脉自己不是
            return data.getMaterial() == Material.SCULK_VEIN ? Boolean.FALSE
                    : isSolid(data.getMaterial());
        }

        @Override
        public Boolean waterSource(int x, int y, int z) {
            BlockData data = blockAt(x, y, z);
            return data != null && data.getMaterial() == Material.WATER;
        }

        @Override
        public String fluidAt(int x, int y, int z) {
            BlockData data = blockAt(x, y, z);
            if (data == null) {
                return null;
            }
            return switch (data.getMaterial()) {
                case WATER -> "minecraft:water";
                case LAVA -> "minecraft:lava";
                default -> "minecraft:empty";
            };
        }

        /** 记一次调用，用来断言「放置后确实登记了后处理标记」。 */
        @Override
        public void markForPostProcessing(int x, int y, int z) {
            postProcessing.add(FakeWorld.key(x, y, z));
        }

        int postProcessingCount() {
            return postProcessing.size();
        }
    }
}
