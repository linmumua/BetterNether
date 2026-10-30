package dev.mumu.betternether.worldgen;

import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「精确优先、降级可见」这套规则的测试。
 *
 * <h2>要守的两件事</h2>
 * <ol>
 *   <li><b>问得到就用真的</b>：{@link WorldView#replaceable} / {@link WorldView#wouldSurvive} /
 *       {@link WorldView#inTag} 只要给出非 null 答案，谓词就必须照它判，**不能**再拿材质
 *       或「非空气」去覆盖它。</li>
 *   <li><b>问不到必须喊</b>：退回近似时要在 {@link BlockPredicates#degraded()} 里留一条记录。
 *       这条断言是防「悄悄退回近似」的唯一机械保证 —— 没有它，某个世界类型下静默失去
 *       精确判定的事故不会有人发现。</li>
 * </ol>
 *
 * <p>生产实现 {@code RegionView} 需要真实的 CraftEngine + 服务端，所以这里用
 * {@link StubWorld}：它按坐标回答「这次问得出什么」，正好覆盖两条分支。
 */
class BlockPredicatesPrecisionTest {
    @BeforeEach
    void resetLedgers() {
        BlockPredicates.resetForTests();
    }

    @Test
    void replaceableUsesTheRealAnswerWhenAvailable() {
        StubWorld world = new StubWorld();
        // 岩浆：近似判据会说是「可替换」。
        world.putSolid(0, 10, 0, Material.LAVA, "minecraft:lava");
        // 但真实 canBeReplaced 说不 —— 精确答案必须赢。
        world.replaceable.put(StubWorld.key(0, 10, 0), Boolean.FALSE);

        assertFalse(BlockPredicates.replaceable(world, 0, 10, 0));
        assertTrue(BlockPredicates.degraded().isEmpty(), "用了真实答案就不该记降级：" + BlockPredicates.degraded());
    }

    @Test
    void replaceableDegradesLoudly() {
        StubWorld world = new StubWorld();
        world.putSolid(0, 10, 0, Material.LAVA, "minecraft:lava");

        // 问不到真实答案 → 退回「空气或液体」，并且必须留痕。
        assertTrue(BlockPredicates.replaceable(world, 0, 10, 0));
        assertTrue(BlockPredicates.degraded().contains("predicate:replaceable"),
                "降级必须记账：" + BlockPredicates.degraded());
    }

    @Test
    void surfacePredicatePrefersRealTagOverTheBedrockHeuristic() {
        StubWorld world = new StubWorld();
        // 基岩：降级判据会说是「不是地面」。
        world.putSolid(0, 5, 0, Material.BEDROCK, "minecraft:bedrock");
        // 但真实 tag 说它属于这个 surfaces tag（wow：换成真的 tag 时就该相信 tag）。
        world.tags.put("wover:surfaces/nether/terrain", Boolean.TRUE);

        assertTrue(new BlockPredicates.Surface("wover:surfaces/nether/terrain")
                        .test(world, new Point(0, 5, 0)),
                "真实 tag 命中时不该被『非基岩』启发式否掉");

        BlockPredicates.resetForTests();
        StubWorld noTag = new StubWorld();
        noTag.putSolid(0, 5, 0, Material.BEDROCK, "minecraft:bedrock");
        assertFalse(new BlockPredicates.Surface("wover:surfaces/nether/terrain")
                .test(noTag, new Point(0, 5, 0)), "问不到 tag 时退回原判据（基岩不是地面）");
    }

    @Test
    void missingTagIsLoudInsteadOfSilentlyFalse() {
        StubWorld world = new StubWorld();
        world.putSolid(0, 7, 0, Material.NETHERRACK, "minecraft:netherrack");
        // 服务端没有这个 tag（wover 的数据包没加载），StubWorld 返回 null。

        assertFalse(new BlockPredicates.InTag("wover:surfaces/nether/terrain")
                .test(world, new Point(0, 7, 0)));
        assertTrue(BlockPredicates.degraded().contains("predicate:in_tag:wover:surfaces/nether/terrain"),
                "tag 问不到必须记账，否则整类地面会静默失效：" + BlockPredicates.degraded());
    }

    @Test
    void realTagAnswerWinsInTagPredicate() {
        StubWorld world = new StubWorld();
        world.putSolid(0, 7, 0, Material.NETHERRACK, "minecraft:netherrack");
        world.tags.put("minecraft:base_stone_nether", Boolean.TRUE);

        assertTrue(new BlockPredicates.InTag("minecraft:base_stone_nether")
                .test(world, new Point(0, 7, 0)));
        assertTrue(BlockPredicates.degraded().isEmpty());
    }

    @Test
    void wouldSurviveAsksTheRealCanSurvive() {
        StubWorld world = new StubWorld();
        BlockData data = StubWorld.blockData(Material.TORCH);
        world.surviving.put(StubWorld.key(1, 2, 3), Boolean.FALSE);

        assertFalse(BlockPredicates.wouldSurvive(world, data, 1, 2, 3));
        assertFalse(new BlockPredicates.WouldSurvive(data).test(world, new Point(1, 2, 3)));
        assertFalse(BlockPredicates.degraded().contains("predicate:would_survive"),
                "用了真实 canSurvive 就不该记降级：" + BlockPredicates.degraded());
    }

    @Test
    void wouldSurviveDegradesToTrueAndSaysSo() {
        StubWorld world = new StubWorld();
        BlockData data = StubWorld.blockData(Material.TORCH);

        assertTrue(BlockPredicates.wouldSurvive(world, data, 1, 2, 3), "问不到时维持旧行为（恒真）");
        assertTrue(BlockPredicates.degraded().contains("predicate:would_survive"));
    }

    @Test
    void faceSturdyUsesTheRealAnswer() {
        StubWorld world = new StubWorld();
        world.putSolid(0, 20, 0, Material.STONE, "minecraft:stone");
        // 石材是实心，但「朝上这一面」未必结实（上游判据就是带面的）。
        world.sturdy.put(StubWorld.key(0, 20, 0) + ":UP", Boolean.FALSE);

        assertFalse(BlockPredicates.faceSturdy(world, 0, 20, 0, Point.Direction.UP));
        assertFalse(BlockPredicates.degraded().contains("predicate:face_sturdy"),
                "用了真实 isFaceSturdy 就不该记降级：" + BlockPredicates.degraded());
    }

    @Test
    void faceSturdyDegradesToNonAir() {
        StubWorld world = new StubWorld();
        world.putSolid(0, 20, 0, Material.STONE, "minecraft:stone");

        assertTrue(BlockPredicates.faceSturdy(world, 0, 20, 0, Point.Direction.UP));
        assertTrue(BlockPredicates.degraded().contains("predicate:face_sturdy"),
                "丢掉『朝哪一面』这个维度必须记账");
    }

    @Test
    void waterSourceUsesTheRealFluidState() {
        StubWorld world = new StubWorld();
        // 材质不是水，但真实流体是水源（含水方块的情形）。
        world.putSolid(0, 20, 0, Material.STONE, "minecraft:stone");
        world.water.put(StubWorld.key(0, 20, 0), Boolean.TRUE);

        assertTrue(BlockPredicates.waterSource(world, 0, 20, 0));
        assertFalse(BlockPredicates.degraded().contains("predicate:water_source"));
    }

    @Test
    void waterSourceDegradesToMaterial() {
        StubWorld world = new StubWorld();
        world.putSolid(0, 20, 0, Material.WATER, "minecraft:water");

        assertTrue(BlockPredicates.waterSource(world, 0, 20, 0));
        assertTrue(BlockPredicates.degraded().contains("predicate:water_source"));
    }

    // ---------------------------------------------------------------- liquidAt（含水 ≠ 液体）

    @Test
    void waterloggedSolidIsNotLiquidSoLayersWontTreatItAsPassable() {
        // wover: BlockHelper.isFreeOrFluid = state.isAir() || state.liquid()
        // 含水台阶：getFluidState() 是水源，但它**是实心方块**，liquid() == false。
        StubWorld world = new StubWorld();
        world.putSolid(0, 12, 0, Material.STONE, "minecraft:stone_stairs");
        world.water.put(StubWorld.key(0, 12, 0), Boolean.TRUE);      // waterSource
        world.liquid.put(StubWorld.key(0, 12, 0), Boolean.FALSE);    // liquid()

        assertEquals(Boolean.TRUE, world.waterSource(0, 12, 0));
        assertEquals(Boolean.FALSE, world.liquidAt(0, 12, 0));
        boolean freeOrFluid = world.isAir(0, 12, 0) || Boolean.TRUE.equals(world.liquidAt(0, 12, 0));
        assertFalse(freeOrFluid,
                "含水实心方块不能被判成『可通行』—— 这正是用 fluidAt != empty 会犯的错");
    }

    @Test
    void liquidAtIsExplicitlyUnknownWithoutAnImplementation() {
        FakeWorld world = new FakeWorld(0, 127, 1L);
        world.solid(0, 12, 0, Material.WATER);
        assertEquals(null, world.liquidAt(0, 12, 0),
                "没有实现时必须返回 null（未知），不能返回 false 冒充答案");
    }

    // ---------------------------------------------------------------- scheduleFluidTick

    @Test
    void scheduleFluidTickDegradesLoudlyByDefault() {
        // 用不覆盖 scheduleFluidTick 的实现，才能测到「默认实现」这条路径。
        FakeWorld world = new FakeWorld(0, 127, 1L);
        world.scheduleFluidTick("minecraft:lava", 0, 8, 0, 0);
        assertTrue(BlockPredicates.degraded().contains("fluid_tick:minecraft:lava"),
                "默认实现必须记账，流体不结算不能静默：" + BlockPredicates.degraded());
    }

    @Test
    void scheduleFluidTickReachesAnImplementingWorld() {
        StubWorld world = new StubWorld();
        world.scheduleFluidTick("minecraft:lava", 3, 4, 5, 0);

        assertEquals(List.of("minecraft:lava@3,4,5+0"), world.fluidTickCalls);
        assertTrue(BlockPredicates.degraded().isEmpty(), "走真实通道不该记账");
    }

    @Test
    void threeArgFormReadsTheFluidTypeFromTheBlock() {
        // 上游 level.scheduleTick(pos, state.getType(), 0)：类型取自刚放下的方块，delay 恒为 0。
        StubWorld world = new StubWorld();
        world.putSolid(1, 2, 3, Material.LAVA, "minecraft:lava");
        world.fluids.put(StubWorld.key(1, 2, 3), "minecraft:lava");

        world.scheduleFluidTick(1, 2, 3);
        assertEquals(List.of("minecraft:lava@1,2,3+0"), world.fluidTickCalls,
                "3 参重载应读 fluidAt 并固定 delay=0");
    }

    // ---------------------------------------------------------------- BlockIds：按身份 id 命中

    @Test
    void twoCeBlocksSharingAMaterialNeverHitEachOther() {
        // 关键回归：两个不同的 CE 方块用了同一个材质（材质注入不可用时的真实情形）。
        StubWorld world = new StubWorld();
        world.putSolid(0, 9, 0, Material.STONE, "betternether:first");
        // 期望的是另一个方块，但它的「已解析状态」材质相同 —— 材质比较会说「命中」。
        List<BlockData> resolved = List.of(StubWorld.blockData(Material.STONE));

        BlockPredicates.BlockIds predicate = new BlockPredicates.BlockIds(
                "wover:surfaces/nether/stones", Set.of("betternether:second"), resolved);
        assertFalse(predicate.test(world, new Point(0, 9, 0)),
                "身份不同就不该命中，哪怕材质一样");
        assertFalse(BlockPredicates.degraded().contains("predicate:block_ids:wover:surfaces/nether/stones"),
                "走了真实 id 通道就不该记降级：" + BlockPredicates.degraded());
    }

    @Test
    void sameIdMatchesRegardlessOfStateProperties() {
        // 上游 tag / state.is(...) 是方块级语义：同一方块换个 age/facing 仍然命中。
        StubWorld world = new StubWorld();
        world.putSolid(0, 9, 0, Material.STONE, "betternether:wart_log");
        // 已解析状态故意给成「另一种材质」，代表另一套属性组合：id 通道不该看它。
        List<BlockData> resolved = List.of(StubWorld.blockData(Material.CRIMSON_STEM));

        BlockPredicates.BlockIds predicate = new BlockPredicates.BlockIds(
                "betternether:wart_logs", Set.of("betternether:wart_log"), resolved);
        assertTrue(predicate.test(world, new Point(0, 9, 0)),
                "同一 id、不同属性组合必须命中（上游是方块级比较）");
        assertTrue(BlockPredicates.degraded().isEmpty());
    }

    @Test
    void blockIdsDegradesLoudlyWithoutIdentity() {
        StubWorld world = new StubWorld();
        world.putSolid(0, 9, 0, Material.STONE, null);   // 没有身份 id
        BlockPredicates.BlockIds predicate = new BlockPredicates.BlockIds(
                "wover:surfaces/nether/stones", Set.of("betternether:second"), List.of());

        assertFalse(predicate.test(world, new Point(0, 9, 0)));
        assertTrue(BlockPredicates.degraded()
                        .contains("predicate:block_ids:wover:surfaces/nether/stones"),
                "问不到 id 必须记账，且不能把材质碰撞当身份证据");
    }

    // ---------------------------------------------------------------- 身份 id 优先

    @Test
    void wartCapWallTestUsesBlockIdNotMaterial() {
        // 一个「材质像下界砖、身份不是下界砖」的自定义方块：旧代码会认成墙，新代码不会。
        StubWorld impostor = new StubWorld();
        impostor.putSolid(0, 40, -1, Material.NETHER_BRICKS, "betternether:not_a_wall");
        Feature feature = wartCapFeature();
        assertFalse(feature.place(impostor, new Point(0, 40, 0)),
                "身份不是下界砖时不该当成墙（deceive 模式会伪装材质）");

        // 反过来：身份是下界砖、材质不是 —— 旧代码会漏，新代码不会。
        StubWorld realWall = new StubWorld();
        realWall.putSolid(0, 40, -1, Material.STONE, "minecraft:nether_bricks");
        assertTrue(feature.place(realWall, new Point(0, 40, 0)),
                "身份是下界砖就该当墙，哪怕材质被伪装成别的");
    }

    @Test
    void soulLilyUsesBlockIdForItsSoulSandFloor() {
        StubWorld world = new StubWorld();
        // 材质是灵魂沙、身份不是 → 旧代码会长，新代码不长。
        world.putSolid(0, 31, 0, Material.SOUL_SAND, "betternether:fake_soul_sand");
        Feature feature = soulLilyFeature();
        assertFalse(feature.place(world, new Point(0, 32, 0)));
        assertEquals(Material.AIR, world.materialAt(0, 32, 0));
    }

    // ---------------------------------------------------------------- 夹具

    private static Feature wartCapFeature() {
        return UpstreamVegetationFeatures.create("betternether:wart_cap", null, (id, properties) -> {
            if (id.equals("minecraft:nether_wart_block")) {
                return StubWorld.blockData(Material.NETHER_WART_BLOCK);
            }
            if (id.equals("minecraft:red_mushroom_block")) {
                return StubWorld.blockData(Material.RED_MUSHROOM_BLOCK);
            }
            return null;
        });
    }

    private static Feature soulLilyFeature() {
        return UpstreamVegetationFeatures.create("betternether:soul_lily", null,
                (id, properties) -> id.equals("betternether:soul_lily")
                        ? StubWorld.blockData(Material.DANDELION) : null);
    }

    /**
     * 可配置的假世界：默认对所有「精确通道」回答 {@code null}（= 问不出来），
     * 测试按需把某一个坐标的答案填进去。
     */
    private static final class StubWorld implements WorldView {
        private final Map<Long, Material> materials = new HashMap<>();
        private final Map<Long, String> ids = new HashMap<>();
        final Map<Long, Boolean> replaceable = new HashMap<>();
        final Map<Long, Boolean> surviving = new HashMap<>();
        final Map<Long, String> fluids = new HashMap<>();
        final Map<Long, Boolean> liquid = new HashMap<>();
        final List<String> fluidTickCalls = new java.util.ArrayList<>();
        final Map<String, Boolean> sturdy = new HashMap<>();
        final Map<Long, Boolean> water = new HashMap<>();
        final Map<String, Boolean> tags = new HashMap<>();

        void putSolid(int x, int y, int z, Material material, String id) {
            materials.put(key(x, y, z), material);
            ids.put(key(x, y, z), id);
        }

        Material materialAt(int x, int y, int z) {
            return materials.getOrDefault(key(x, y, z), Material.AIR);
        }

        @Override
        public boolean inRegion(int x, int y, int z) {
            return y >= minY() && y <= maxY();
        }

        @Override
        public int minY() {
            return 0;
        }

        @Override
        public int maxY() {
            return 127;
        }

        @Override
        public boolean isAir(int x, int y, int z) {
            return materialAt(x, y, z) == Material.AIR;
        }

        @Override
        public @Nullable BlockData blockAt(int x, int y, int z) {
            return inRegion(x, y, z) ? blockData(materialAt(x, y, z)) : null;
        }

        @Override
        public void setBlock(int x, int y, int z, BlockData data) {
            materials.put(key(x, y, z), data.getMaterial());
            ids.remove(key(x, y, z));
        }

        @Override
        public String biomeAt(int x, int y, int z) {
            return "minecraft:nether_wastes";
        }

        @Override
        public @Nullable String decoratedBiome() {
            return null;
        }

        @Override
        public Random random() {
            return RANDOM;
        }

        @Override
        public @Nullable String blockId(int x, int y, int z) {
            return ids.get(key(x, y, z));
        }

        @Override
        public @Nullable Boolean replaceable(int x, int y, int z) {
            return replaceable.get(key(x, y, z));
        }

        @Override
        public @Nullable Boolean wouldSurvive(BlockData data, int x, int y, int z) {
            return surviving.get(key(x, y, z));
        }

        @Override
        public @Nullable Boolean inTag(String tag, int x, int y, int z) {
            return tags.get(tag);
        }

        @Override
        public @Nullable Boolean liquidAt(int x, int y, int z) {
            return liquid.get(key(x, y, z));
        }

        @Override
        public @Nullable String fluidAt(int x, int y, int z) {
            return fluids.get(key(x, y, z));
        }

        @Override
        public void scheduleFluidTick(String fluidId, int x, int y, int z, int delay) {
            fluidTickCalls.add(fluidId + "@" + x + "," + y + "," + z + "+" + delay);
        }

        @Override
        public @Nullable Boolean faceSturdy(int x, int y, int z, Point.Direction face) {
            return sturdy.get(key(x, y, z) + ":" + face.name());
        }

        @Override
        public @Nullable Boolean waterSource(int x, int y, int z) {
            return water.get(key(x, y, z));
        }

        static long key(int x, int y, int z) {
            return ((long) (x & 0xFFFFF) << 40) | ((long) (y & 0xFFFFF) << 20) | (z & 0xFFFFF);
        }

        /** 每个测试用同一条随机流，保证确定性。 */
        private static final Random RANDOM = new Random(1234L);

        static BlockData blockData(Material material) {
            return FakeWorld.blockData(material);
        }
    }
}
