package dev.mumu.betternether.worldgen;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link UpstreamVegetationFeatures} 的形状测试。
 *
 * <h2>怎么在没有服务器的情况下断言「形状」</h2>
 * 这里注入一个 {@link UpstreamVegetationFeatures.BlockResolver}：把"方块 id + 属性"映射成
 * <b>互不相同的材质</b>，再用 {@link FakeWorld} 读回材质。于是"那个位置上是
 * {@code big_top_side_n} 那一档"就变成"那个位置是 {@code ROSE_BUSH}" —— 选状态的逻辑被真正
 * 验证了，而且不用起 CraftEngine、不用碰原版注册表。
 *
 * <h2>这些测试守的是什么</h2>
 * 上游这几个类的价值全在"形状 + 门槛"上，所以断言只落在三件事：
 * <ul>
 *   <li><b>几何</b>：椭球半径就是 {@code 3+nextInt(3)}、最外圈与顶层是表皮、中心是内芯；</li>
 *   <li><b>覆盖规则</b>：只写空气（sakura 还写可替换），黑曜石/下界砖绝不被吃掉；</li>
 *   <li><b>门槛</b>：没有下界砖墙就没有 wart_cap，没有下界岩就没有 lucis，没有灵魂沙就没有
 *       灵魂兰 —— 判断错了整个群系会多或少一种植被。</li>
 * </ul>
 * 随机分支（叶子球半径、{@code nextBoolean} 的两段）不做"猜这个 seed 会怎样"的断言，
 * 而是遍历一批 seed 断言两个分支都被走到过、且每种结果自身自洽。
 *
 * <p>世界统一 {@code 0..127}：上游原版下界 128 层，{@code wart_cap}/{@code soul_lily} 的高度
 * 门槛直接吃这个数（见 {@code MAX_HEIGHT} 的换算说明）。
 */
class UpstreamVegetationFeaturesTest {
    private static final int MAX_Y = 127;

    // ---------------------------------------------------------------- 入口

    @Test
    void supportedTypesAreExactlyTheEightRegisteredIds() {
        // 上游注册名就是这些（registry/NetherFeatures.java）；写成 nether_sakura_bush 会静默 skip。
        assertEquals(
                Set.of("betternether:wart_cap", "betternether:rubeus_bush", "betternether:sakura_bush",
                        "betternether:wart_bush", "betternether:willow_bush", "betternether:lucis",
                        "betternether:soul_lily", "betternether:jellyfish_mushroom"),
                UpstreamVegetationFeatures.supportedTypes());
    }

    @Test
    void unrelatedTypeIsNotOurs() {
        assertNull(UpstreamVegetationFeatures.create("betternether:wart_tree", null, resolver(Map.of())));
        assertNull(UpstreamVegetationFeatures.create("minecraft:simple_block", null, resolver(Map.of())));
    }

    // ---------------------------------------------------------------- wart_cap

    @Test
    void wartCapBuildsShellWithUpstreamRadius() {
        FakeWorld world = new FakeWorld(0, MAX_Y, 7L);
        int y = 40; // 128*0.25 = 32 ≤ 40 ≤ 57.6 = 128*0.45
        world.solid(0, y, -1, Material.NETHER_BRICKS);

        Feature feature = UpstreamVegetationFeatures.create("betternether:wart_cap", null,
                resolver(Map.of(
                        "minecraft:red_mushroom_block", Material.RED_MUSHROOM_BLOCK,
                        "minecraft:nether_wart_block", Material.NETHER_WART_BLOCK)));
        assertNotNull(feature);
        assertTrue(feature.place(world, new Point(0, y, 0)));

        Set<Point> skin = new HashSet<>();
        Set<Point> inside = new HashSet<>();
        int maxAbsX = 0;
        int maxAbsZ = 0;
        int maxDy = 0;
        for (int dx = -6; dx <= 6; dx++) {
            for (int dy = 0; dy <= 5; dy++) {
                for (int dz = -6; dz <= 6; dz++) {
                    Material material = world.materialAt(dx, y + dy, dz);
                    if (material != Material.NETHER_WART_BLOCK
                            && material != Material.RED_MUSHROOM_BLOCK) {
                        continue;
                    }
                    Point cell = new Point(dx, dy, dz);
                    if (material == Material.NETHER_WART_BLOCK) {
                        skin.add(cell);
                    } else {
                        inside.add(cell);
                    }
                    maxAbsX = Math.max(maxAbsX, Math.abs(dx));
                    maxAbsZ = Math.max(maxAbsZ, Math.abs(dz));
                    maxDy = Math.max(maxDy, dy);
                }
            }
        }
        assertFalse(skin.isEmpty(), "伞面必须有表皮");
        assertFalse(inside.isEmpty(), "伞内部必须留着 red_mushroom_block 内芯");
        // 半径 = 3 + nextInt(3)；x/z 两个方向的最大偏移必须相等（同一颗球）。
        assertEquals(maxAbsX, maxAbsZ, "球体在 x/z 上应当同半径");
        assertTrue(maxAbsX >= 3 && maxAbsX <= 5, "半径应落在 3..5：" + maxAbsX);
        // 高度不是 radius>>1，而是被 6y² 这一项压扁：d = x²+6y²+z² ≤ r² ⇒ |y| ≤ r/√6。
        // 半径 3/4/5 分别只剩 1/1/2 层，这正是伞"扁"而不是"球"的原因；系数写错这里立刻红。
        assertEquals((int) Math.sqrt(maxAbsX * maxAbsX / 6.0), maxDy, "伞的高度应是 r/√6 向下取整");
        assertTrue(maxDy <= (maxAbsX >> 1), "高度不超过 y1 = radius>>1：" + maxDy);

        Set<Point> all = new HashSet<>(skin);
        all.addAll(inside);
        for (Point cell : all) {
            // 墙在 z=-1 那一列，所以只对 x 做镜像断言（那一列被墙占住，本来就不该被写）。
            assertTrue(all.contains(new Point(-cell.x(), cell.y(), cell.z())),
                    "x 方向不对称：" + cell);
            if (Math.abs(cell.x()) == maxAbsX || Math.abs(cell.z()) == maxAbsZ || cell.y() == maxDy) {
                assertTrue(skin.contains(cell), "外圈/顶层必须是表皮：" + cell);
            }
        }
        assertTrue(inside.contains(new Point(0, 0, 0)), "正中心被六面围住，必然是内芯");
        assertEquals(Material.NETHER_BRICKS, world.materialAt(0, y, -1), "下界砖墙不能被伞吃掉");
    }

    @Test
    void wartCapRefusesWithoutWallOrOutsideTheHeightWindow() {
        Feature feature = wartCapFeature();

        FakeWorld noWall = new FakeWorld(0, MAX_Y, 1L);
        assertFalse(feature.place(noWall, new Point(0, 40, 0)), "没有下界砖墙不该长伞");
        assertEquals(Material.AIR, noWall.materialAt(0, 40, 0));

        FakeWorld tooLow = new FakeWorld(0, MAX_Y, 2L);
        tooLow.solid(0, 10, -1, Material.NETHER_BRICKS);
        assertFalse(feature.place(tooLow, new Point(0, 10, 0)), "低于 MAX_HEIGHT*0.25 不该长伞");

        FakeWorld tooHigh = new FakeWorld(0, MAX_Y, 3L);
        tooHigh.solid(0, 70, -1, Material.NETHER_BRICKS);
        assertFalse(feature.place(tooHigh, new Point(0, 70, 0)), "高于 MAX_HEIGHT*0.45 不该长伞");

        FakeWorld blockedBelow = new FakeWorld(0, MAX_Y, 4L);
        blockedBelow.solid(0, 40, -1, Material.NETHER_BRICKS);
        blockedBelow.solid(0, 37, 0, Material.OBSIDIAN);
        assertFalse(feature.place(blockedBelow, new Point(0, 40, 0)), "脚下 3 格堵塞不该长伞");
        assertEquals(Material.AIR, blockedBelow.materialAt(0, 40, 0));
    }

    // ---------------------------------------------------------------- bush 三兄弟

    @Test
    void rubeusBushPlacesTrunkCapAndNeverEatsSolidBlocks() {
        FakeWorld world = new FakeWorld(0, MAX_Y, 11L);
        int y = 40;
        world.solid(1, y, 0, Material.OBSIDIAN);

        Feature feature = UpstreamVegetationFeatures.create("betternether:rubeus_bush", null,
                resolver(Map.of(
                        "betternether:rubeus_bark", Material.CRIMSON_STEM,
                        "betternether:rubeus_leaves", Material.OAK_LEAVES)));
        assertTrue(feature.place(world, new Point(0, y, 0)));

        assertEquals(Material.CRIMSON_STEM, world.materialAt(0, y, 0), "树心");
        assertEquals(Material.OAK_LEAVES, world.materialAt(0, y + 1, 0), "上方帽子");
        assertEquals(Material.OAK_LEAVES, world.materialAt(0, y, -1), "北侧帽子");
        assertEquals(Material.OAK_LEAVES, world.materialAt(0, y, 1), "南侧帽子");
        assertEquals(Material.OAK_LEAVES, world.materialAt(-1, y, 0), "西侧帽子");
        // 东侧被黑曜石占住：setIfAir 不该动它。
        assertEquals(Material.OBSIDIAN, world.materialAt(1, y, 0), "实心方块不能被叶子覆盖");
    }

    @Test
    void rubeusBushRefusesWhenHeadroomIsBlocked() {
        FakeWorld world = new FakeWorld(0, MAX_Y, 5L);
        world.solid(0, 40 + 15, 0, Material.OBSIDIAN);
        Feature feature = UpstreamVegetationFeatures.create("betternether:rubeus_bush", null,
                resolver(Map.of(
                        "betternether:rubeus_bark", Material.CRIMSON_STEM,
                        "betternether:rubeus_leaves", Material.OAK_LEAVES)));
        assertFalse(feature.place(world, new Point(0, 40, 0)), "15 格高处有方块说明是天花板");
        assertEquals(Material.AIR, world.materialAt(0, 40, 0));
    }

    @Test
    void sakuraBushReplacesLiquidsWhileRubeusDoesNot() {
        // 上游差别就在 setIfAir 的判据：sakura 用 canBeReplaced()（含岩浆/水），rubeus 用 isEmptyBlock()。
        // 取斜角 (-1,-1)：那里不是帽子位，只可能被叶子球写到，才能看出"覆盖规则"本身的差别。
        int replaced = 0;
        int kept = 0;
        for (int index = 0; index < 64; index++) {
            long seed = scattered(index);
            FakeWorld sakura = new FakeWorld(0, MAX_Y, seed);
            sakura.solid(-1, 40, -1, Material.LAVA);
            Feature feature = UpstreamVegetationFeatures.create("betternether:sakura_bush", null,
                    resolver(Map.of(
                            "betternether:nether_sakura_bark", Material.WARPED_STEM,
                            "betternether:nether_sakura_leaves", Material.BIRCH_LEAVES)));
            assertTrue(feature.place(sakura, new Point(0, 40, 0)));
            Material after = sakura.materialAt(-1, 40, -1);
            if (after == Material.BIRCH_LEAVES) {
                replaced++;
            } else {
                assertEquals(Material.LAVA, after, "除了换成叶子，岩浆不该变成别的东西");
                kept++;
            }

            FakeWorld rubeus = new FakeWorld(0, MAX_Y, seed);
            rubeus.solid(-1, 40, -1, Material.LAVA);
            Feature other = UpstreamVegetationFeatures.create("betternether:rubeus_bush", null,
                    resolver(Map.of(
                            "betternether:rubeus_bark", Material.CRIMSON_STEM,
                            "betternether:rubeus_leaves", Material.OAK_LEAVES)));
            assertTrue(other.place(rubeus, new Point(0, 40, 0)));
            assertEquals(Material.LAVA, rubeus.materialAt(-1, 40, -1), "rubeus 只覆盖空气");
        }
        assertTrue(replaced > 0, "sakura 应当至少有一次把岩浆换成叶子（canBeReplaced）");
        assertTrue(kept > 0, "叶子只长在球体范围内，范围外自然还是岩浆");
    }

    @Test
    void willowBushLeafStateFollowsItsDirection() {
        Map<String, Material> table = willowTable();
        FakeWorld world = new FakeWorld(0, MAX_Y, 21L);
        int y = 40;
        Feature feature = UpstreamVegetationFeatures.create("betternether:willow_bush", null, resolver(table));
        assertTrue(feature.place(world, new Point(0, y, 0)));

        assertEquals(Material.MANGROVE_LOG, world.materialAt(0, y, 0));
        // 每片叶子必须是「该方向那一档」：要么是球体叶（facing=该方向 + persistent），
        // 要么是帽子（facing=该方向 + distance=1）。写成别的方向就是方位算错了。
        assertSide(world.materialAt(0, y + 1, 0), Material.CHERRY_LEAVES, Material.OAK_PLANKS, "上");
        assertSide(world.materialAt(0, y, -1), Material.AZALEA_LEAVES, Material.SPRUCE_PLANKS, "北");
        assertSide(world.materialAt(0, y, 1), Material.FLOWERING_AZALEA_LEAVES, Material.BIRCH_PLANKS, "南");
        assertSide(world.materialAt(1, y, 0), Material.DARK_OAK_LEAVES, Material.JUNGLE_PLANKS, "东");
        assertSide(world.materialAt(-1, y, 0), Material.ACACIA_LEAVES, Material.ACACIA_PLANKS, "西");
        // 球体只长在 y..y+i 上（i=0 那层就在 y），所以上方那格必然是 distance=1 的帽子。
        assertEquals(Material.OAK_PLANKS, world.materialAt(0, y + 1, 0), "上方是帽子而非球叶");
    }

    @Test
    void willowFacingPicksTheLongestAxisInXYZOrder() {
        // 三轴相等时上游走 X 轴、dx 不大于 0 → west；改顺序会让树心那片叶子换朝向。
        assertEquals("west", UpstreamVegetationFeatures.maxAxis(0, 0, 0));
        assertEquals("east", UpstreamVegetationFeatures.maxAxis(2, 0, 0));
        assertEquals("west", UpstreamVegetationFeatures.maxAxis(-2, 0, 0));
        assertEquals("up", UpstreamVegetationFeatures.maxAxis(0, 2, 0));
        assertEquals("south", UpstreamVegetationFeatures.maxAxis(0, 0, 3));
        assertEquals("north", UpstreamVegetationFeatures.maxAxis(0, 0, -3));
        // X 与 Y 打平时 X 先手。
        assertEquals("east", UpstreamVegetationFeatures.maxAxis(1, 1, 0));
        // X 落后时交给 Y。
        assertEquals("down", UpstreamVegetationFeatures.maxAxis(0, -1, 1));
    }

    // ---------------------------------------------------------------- wart_bush

    @Test
    void wartBushPlacesBodyAndFiveFacingSeeds() {
        Map<String, Material> table = new HashMap<>();
        table.put("minecraft:nether_wart_block", Material.NETHER_WART_BLOCK);
        put(table, "betternether:wart_seed", Material.RED_WOOL, "facing", "up");
        put(table, "betternether:wart_seed", Material.ORANGE_WOOL, "facing", "north");
        put(table, "betternether:wart_seed", Material.YELLOW_WOOL, "facing", "south");
        put(table, "betternether:wart_seed", Material.LIME_WOOL, "facing", "east");
        put(table, "betternether:wart_seed", Material.GREEN_WOOL, "facing", "west");

        FakeWorld world = new FakeWorld(0, MAX_Y, 31L);
        int y = 40;
        Feature feature = UpstreamVegetationFeatures.create("betternether:wart_bush", null, resolver(table));
        assertTrue(feature.place(world, new Point(0, y, 0)));

        assertEquals(Material.NETHER_WART_BLOCK, world.materialAt(0, y, 0));
        assertEquals(Material.RED_WOOL, world.materialAt(0, y + 1, 0), "上");
        assertEquals(Material.ORANGE_WOOL, world.materialAt(0, y, -1), "北");
        assertEquals(Material.YELLOW_WOOL, world.materialAt(0, y, 1), "南");
        assertEquals(Material.LIME_WOOL, world.materialAt(1, y, 0), "东");
        assertEquals(Material.GREEN_WOOL, world.materialAt(-1, y, 0), "西");
    }

    @Test
    void wartBushRefusesOccupiedPositionAndSkipsOccupiedNeighbour() {
        Map<String, Material> table = new HashMap<>();
        table.put("minecraft:nether_wart_block", Material.NETHER_WART_BLOCK);
        put(table, "betternether:wart_seed", Material.RED_WOOL, "facing", "up");
        put(table, "betternether:wart_seed", Material.ORANGE_WOOL, "facing", "north");
        put(table, "betternether:wart_seed", Material.YELLOW_WOOL, "facing", "south");
        put(table, "betternether:wart_seed", Material.LIME_WOOL, "facing", "east");
        put(table, "betternether:wart_seed", Material.GREEN_WOOL, "facing", "west");
        Feature feature = UpstreamVegetationFeatures.create("betternether:wart_bush", null, resolver(table));

        FakeWorld occupied = new FakeWorld(0, MAX_Y, 32L);
        occupied.solid(0, 40, 0, Material.OBSIDIAN);
        assertFalse(feature.place(occupied, new Point(0, 40, 0)), "位置上已有方块就不该长");
        assertEquals(Material.OBSIDIAN, occupied.materialAt(0, 40, 0));

        FakeWorld partial = new FakeWorld(0, MAX_Y, 33L);
        partial.solid(0, 40, 1, Material.OBSIDIAN);
        assertTrue(feature.place(partial, new Point(0, 40, 0)));
        assertEquals(Material.OBSIDIAN, partial.materialAt(0, 40, 1), "被占的方向不写疣芽");
        assertEquals(Material.NETHER_WART_BLOCK, partial.materialAt(0, 40, 0));
    }

    // ---------------------------------------------------------------- lucis

    @Test
    void lucisNeedsNetherrackNextToItAndNeverEatsIt() {
        Map<String, Material> table = lucisTable();
        Feature feature = UpstreamVegetationFeatures.create("betternether:lucis", null, resolver(table));

        FakeWorld barren = new FakeWorld(0, MAX_Y, 41L);
        assertFalse(feature.place(barren, new Point(0, 40, 0)), "旁边没有下界岩不该长");
        assertEquals(Material.AIR, barren.materialAt(0, 40, 0));

        FakeWorld wall = new FakeWorld(0, MAX_Y, 42L);
        wall.solid(0, 40, -1, Material.NETHERRACK);
        wall.solid(0, 40, 1, Material.OBSIDIAN);
        wall.solid(1, 40, 0, Material.OBSIDIAN);
        assertTrue(feature.place(wall, new Point(0, 40, 0)));
        Material here = wall.materialAt(0, 40, 0);
        assertTrue(here == Material.GLOWSTONE || here == Material.REDSTONE_LAMP,
                "原点应落一株蘑菇（两个分支分别落 center / 一角）：" + here);
        assertEquals(Material.NETHERRACK, wall.materialAt(0, 40, -1), "下界岩不能被蘑菇覆盖");
        assertEquals(Material.OBSIDIAN, wall.materialAt(0, 40, 1), "实心方块不能被蘑菇覆盖");
        assertEquals(Material.OBSIDIAN, wall.materialAt(1, 40, 0), "实心方块不能被蘑菇覆盖");
    }

    @Test
    void lucisRefusesWhenOnlyDiagonalNetherrackExists() {
        // 上游只看四个正交邻居：斜角有下界岩不算。
        Feature feature = UpstreamVegetationFeatures.create("betternether:lucis", null,
                resolver(lucisTable()));
        FakeWorld world = new FakeWorld(0, MAX_Y, 44L);
        world.solid(1, 40, -1, Material.NETHERRACK);
        assertFalse(feature.place(world, new Point(0, 40, 0)));
    }

    // ---------------------------------------------------------------- soul_lily

    @Test
    void soulLilyGrowsTheBigShapeWhenTheSpaceIsFree() {
        Feature feature = soulLilyFeature();
        FakeWorld world = new FakeWorld(0, MAX_Y, 51L);
        world.fill(0, 0, 0, 31, Material.SOUL_SAND);

        // RANDOM_BOUND = 6*128/128 = 6，落点从 [32,37] 往下找；这一列只有 y=32 是
        // 「脚下灵魂沙 + 自己是空气」，所以形状与位置都是确定的。
        assertTrue(feature.place(world, new Point(0, 32, 0)));
        assertEquals(Material.OXEYE_DAISY, world.materialAt(0, 32, 0), "big_bottom");
        assertEquals(Material.CORNFLOWER, world.materialAt(0, 33, 0), "big_middle");
        assertEquals(Material.SUNFLOWER, world.materialAt(0, 34, 0), "big_top_center");
        // 四个侧瓣是交叉的：north 用 _s、south 用 _n、east 用 _w、west 用 _e（上游原样）。
        assertEquals(Material.PEONY, world.materialAt(0, 34, -1), "north → big_top_side_s");
        assertEquals(Material.ROSE_BUSH, world.materialAt(0, 34, 1), "south → big_top_side_n");
        assertEquals(Material.TALL_GRASS, world.materialAt(1, 34, 0), "east → big_top_side_w");
        assertEquals(Material.LILAC, world.materialAt(-1, 34, 0), "west → big_top_side_e");
    }

    @Test
    void soulLilyFallsBackToMediumAndSmall() {
        Feature feature = soulLilyFeature();

        FakeWorld medium = new FakeWorld(0, MAX_Y, 52L);
        medium.fill(0, 0, 0, 31, Material.SOUL_SAND);
        medium.solid(0, 34, 0, Material.OBSIDIAN); // 第二格被堵 → 中号
        assertTrue(feature.place(medium, new Point(0, 32, 0)));
        assertEquals(Material.POPPY, medium.materialAt(0, 32, 0), "medium_bottom");
        assertEquals(Material.BLUE_ORCHID, medium.materialAt(0, 33, 0), "medium_top");

        FakeWorld small = new FakeWorld(0, MAX_Y, 53L);
        small.fill(0, 0, 0, 31, Material.SOUL_SAND);
        small.solid(0, 33, 0, Material.OBSIDIAN); // 头顶被堵 → 小号
        assertTrue(feature.place(small, new Point(0, 32, 0)));
        assertEquals(Material.DANDELION, small.materialAt(0, 32, 0), "small");
        assertEquals(Material.OBSIDIAN, small.materialAt(0, 33, 0));
    }

    @Test
    void soulLilyRefusesWithoutSoulSandBelow() {
        Feature feature = soulLilyFeature();
        FakeWorld world = new FakeWorld(0, MAX_Y, 54L);
        world.fill(0, 0, 0, 31, Material.NETHERRACK);
        assertFalse(feature.place(world, new Point(0, 32, 0)));
        assertEquals(Material.AIR, world.materialAt(0, 32, 0));
    }

    // ---------------------------------------------------------------- jellyfish_mushroom

    @Test
    void jellyfishMushroomNeedsNyliumAndPicksOneOrTwoSegments() {
        Map<String, Material> table = new HashMap<>();
        for (String visual : new String[]{"normal", "sepia", "poor"}) {
            Material bottom = switch (visual) {
                case "normal" -> Material.SLIME_BLOCK;
                case "sepia" -> Material.HONEY_BLOCK;
                default -> Material.MAGMA_BLOCK;
            };
            Material middle = switch (visual) {
                case "normal" -> Material.SHROOMLIGHT;
                case "sepia" -> Material.GLOWSTONE;
                default -> Material.SEA_LANTERN;
            };
            Material top = switch (visual) {
                case "normal" -> Material.OCHRE_FROGLIGHT;
                case "sepia" -> Material.PEARLESCENT_FROGLIGHT;
                default -> Material.VERDANT_FROGLIGHT;
            };
            put(table, "betternether:jellyfish_mushroom", bottom, "shape", "bottom", "visual", visual);
            put(table, "betternether:jellyfish_mushroom", middle, "shape", "middle", "visual", visual);
            put(table, "betternether:jellyfish_mushroom", top, "shape", "top", "visual", visual);
        }
        // 两种菌草在包里另有材质；它们按上游 tag 也算 nylium，且决定 visual。
        table.put("betternether:mushroom_grass", Material.MOSS_BLOCK);
        table.put("betternether:sepia_mushroom_grass", Material.MYCELIUM);
        Feature feature = UpstreamVegetationFeatures.create(
                "betternether:jellyfish_mushroom", null, resolver(table));

        FakeWorld barren = new FakeWorld(0, MAX_Y, 61L);
        barren.solid(0, 39, 0, Material.NETHERRACK);
        assertFalse(feature.place(barren, new Point(0, 40, 0)), "非菌岩地面不该长");
        assertEquals(Material.AIR, barren.materialAt(0, 40, 0));

        int oneSegment = 0;
        int twoSegments = 0;
        for (int index = 0; index < 32; index++) {
            FakeWorld world = new FakeWorld(0, MAX_Y, scattered(index));
            world.solid(0, 39, 0, Material.CRIMSON_NYLIUM);
            assertTrue(feature.place(world, new Point(0, 40, 0)));
            Material bottom = world.materialAt(0, 40, 0);
            Material top = world.materialAt(0, 41, 0);
            if (top == Material.AIR) {
                assertEquals(Material.MAGMA_BLOCK, bottom, "单段应是 poor 的 bottom");
                oneSegment++;
            } else {
                assertEquals(Material.SEA_LANTERN, bottom, "两段的下半应是 middle");
                assertEquals(Material.VERDANT_FROGLIGHT, top, "两段的上半应是 top");
                twoSegments++;
            }
        }
        assertTrue(oneSegment > 0 && twoSegments > 0, "nextBoolean 两个分支都应该出现过");

        // 身份优先，不再按材质放宽：脚下 id 是 minecraft:moss_block（不是菌岩）→ 拒绝。
        // 生产里 betternether:mushroom_grass 的 id 就是它自己（CE 注册名），会命中 id 分支；
        // 但 FakeWorld 只能按材质反推 "minecraft:<key>"，表达不了 CE id，所以这里钉的是「严格性」。
        FakeWorld notNylium = new FakeWorld(0, MAX_Y, 62L);
        notNylium.solid(0, 39, 0, Material.MOSS_BLOCK);
        assertFalse(feature.place(notNylium, new Point(0, 40, 0)),
                "身份不是菌岩就该拒绝 —— 不能退回材质比较放宽");
        assertEquals(Material.AIR, notNylium.materialAt(0, 40, 0));
    }

    // ---------------------------------------------------------------- 夹具

    private static Feature wartCapFeature() {
        return UpstreamVegetationFeatures.create("betternether:wart_cap", null,
                resolver(Map.of(
                        "minecraft:red_mushroom_block", Material.RED_MUSHROOM_BLOCK,
                        "minecraft:nether_wart_block", Material.NETHER_WART_BLOCK)));
    }

    private static Map<String, Material> willowTable() {
        Map<String, Material> table = new HashMap<>();
        table.put("betternether:willow_bark", Material.MANGROVE_LOG);
        put(table, "betternether:willow_leaves", Material.CHERRY_LEAVES, "facing", "up", "persistent", "true");
        put(table, "betternether:willow_leaves", Material.AZALEA_LEAVES, "facing", "north", "persistent", "true");
        put(table, "betternether:willow_leaves", Material.FLOWERING_AZALEA_LEAVES,
                "facing", "south", "persistent", "true");
        put(table, "betternether:willow_leaves", Material.DARK_OAK_LEAVES, "facing", "east", "persistent", "true");
        put(table, "betternether:willow_leaves", Material.ACACIA_LEAVES, "facing", "west", "persistent", "true");
        put(table, "betternether:willow_leaves", Material.OAK_PLANKS, "facing", "up", "distance", "1");
        put(table, "betternether:willow_leaves", Material.SPRUCE_PLANKS, "facing", "north", "distance", "1");
        put(table, "betternether:willow_leaves", Material.BIRCH_PLANKS, "facing", "south", "distance", "1");
        put(table, "betternether:willow_leaves", Material.JUNGLE_PLANKS, "facing", "east", "distance", "1");
        put(table, "betternether:willow_leaves", Material.ACACIA_PLANKS, "facing", "west", "distance", "1");
        return table;
    }

    private static Map<String, Material> lucisTable() {
        Map<String, Material> table = new HashMap<>();
        put(table, "betternether:lucis_mushroom", Material.GLOWSTONE, "shape", "center");
        for (String facing : new String[]{"north", "south", "east", "west"}) {
            put(table, "betternether:lucis_mushroom", Material.SHROOMLIGHT, "shape", "side", "facing", facing);
            put(table, "betternether:lucis_mushroom", Material.REDSTONE_LAMP, "shape", "corner", "facing", facing);
        }
        return table;
    }

    private static Feature soulLilyFeature() {
        Map<String, Material> table = new HashMap<>();
        table.put("betternether:soul_lily", Material.DANDELION);
        put(table, "betternether:soul_lily", Material.POPPY, "shape", "medium_bottom");
        put(table, "betternether:soul_lily", Material.BLUE_ORCHID, "shape", "medium_top");
        put(table, "betternether:soul_lily", Material.OXEYE_DAISY, "shape", "big_bottom");
        put(table, "betternether:soul_lily", Material.CORNFLOWER, "shape", "big_middle");
        put(table, "betternether:soul_lily", Material.SUNFLOWER, "shape", "big_top_center");
        put(table, "betternether:soul_lily", Material.ROSE_BUSH, "shape", "big_top_side_n");
        put(table, "betternether:soul_lily", Material.PEONY, "shape", "big_top_side_s");
        put(table, "betternether:soul_lily", Material.LILAC, "shape", "big_top_side_e");
        put(table, "betternether:soul_lily", Material.TALL_GRASS, "shape", "big_top_side_w");
        return UpstreamVegetationFeatures.create("betternether:soul_lily", null, resolver(table));
    }

    /**
     * 打散后的 seed。
     *
     * <p>直接拿 0,1,2… 当 seed 不行：{@code new Random(0..31)} 的**第一次**抽样几乎相同
     * （实测 {@code nextFloat()} 全落在 0.7309 附近、{@code nextBoolean()} 全为 true），
     * 于是"遍历一批 seed 看两个分支是否都走到"的断言会永远失败。用下一条随机流产生的
     * {@code long} 当 seed 才能真正铺开。
     */
    private static long scattered(int index) {
        return new Random(index).nextLong();
    }

    /** 注入式解析：用「id + 排序后的属性」查表，让每个上游状态都能有独立材质。 */
    private static UpstreamVegetationFeatures.BlockResolver resolver(Map<String, Material> table) {
        return (id, properties) -> {
            Material material = table.get(key(id, properties));
            if (material == null) {
                material = table.get(id);
            }
            return material == null ? null : FakeWorld.blockData(material);
        };
    }

    private static void put(Map<String, Material> table, String id, Material material,
                            String... keysAndValues) {
        Map<String, String> properties = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
            properties.put(keysAndValues[i], keysAndValues[i + 1]);
        }
        table.put(key(id, properties), material);
    }

    private static String key(String id, Map<String, String> properties) {
        if (properties.isEmpty()) {
            return id;
        }
        StringBuilder builder = new StringBuilder(id).append('[');
        for (Map.Entry<String, String> entry : new TreeMap<>(properties).entrySet()) {
            builder.append(entry.getKey()).append('=').append(entry.getValue()).append(',');
        }
        return builder.append(']').toString();
    }

    /** 一个方位的叶子只能是「该方位的球叶」或「该方位的帽子」，别的方向就是方位算错了。 */
    private static void assertSide(Material actual, Material blob, Material cap, String where) {
        assertTrue(actual == blob || actual == cap,
                where + "侧叶子状态不对：" + actual + "（期望 " + blob + " 或 " + cap + "）");
    }
}
