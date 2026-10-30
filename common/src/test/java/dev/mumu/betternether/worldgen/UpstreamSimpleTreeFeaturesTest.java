package dev.mumu.betternether.worldgen;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link UpstreamSimpleTreeFeatures} 四个形状的测试。
 *
 * <h2>这些测试守的是什么</h2>
 * 移植的价值全在「形状 + 门槛 + 随机数顺序」上，所以断言只落在四件事：
 * <ul>
 *   <li><b>几何</b>：{@code mushroom_fir} 的柄从上到下是
 *       {@code bottom → middle → top → end}，分支只挂在侧面；
 *       {@code big_brown_mushroom} 的柄是 {@code bottom → middle…}、伞面是
 *       {@code top} 居中 + 四边 + 四角；{@code wart_tree} 的中轴底格是 {@code wart_roots}。</li>
 *   <li><b>状态（shape/facing）</b>：断言的是<b>状态字符串里的属性</b>，不是材质。
 *       「上段被写成 {@code side_big_n}」这类错误只比材质是抓不到的。</li>
 *   <li><b>净空拒绝</b>：往树上塞一个方块，必须整棵放弃 —— 上面半截树比不长树更难查；
 *       并且 {@code wart_tree} 那道「试过了但什么都没长」的 {@code return true} 要保留
 *       （上游就是这么写的，见 {@code WartTreeFeature} 返回结构）。</li>
 *   <li><b>不附加原版没有的东西</b>：四棵树写下的方块必须全部落在自己的调色板里 ——
 *       没有 shroomlight（上游这四棵树都不放它），也不会自己铺菌丝
 *       （{@code mushroom_fir} 只把**已有**的菌丝染蓝，不凭空造菌丝）。</li>
 * </ul>
 * 随机分支不做「猜这个 seed 会怎样」的断言，而是<b>遍历一批 seed</b> 断言每种结果自身自洽，
 * 再单独用「同 seed 两个世界放置后快照完全相同」验证确定性。
 *
 * <p>世界统一 {@code 0..127}：上游原版下界 128 层，{@code MAX_HEIGHT} 由它算出，
 * {@code mushroom_fir} 的 {@code nextInt(5*MAX_HEIGHT/128)} 直接吃这个数。
 */
class UpstreamSimpleTreeFeaturesTest {
    private static final int MIN_Y = 0;
    private static final int MAX_Y = 127;
    private static final int GROUND_Y = 63;
    private static final Point ORIGIN = new Point(0, GROUND_Y + 1, 0);

    private static final Set<String> MUSHROOM_FIR_SHAPES = Set.of(
            "bottom", "middle", "top", "end",
            "side_big_n", "side_big_s", "side_big_e", "side_big_w",
            "side_small_n", "side_small_s", "side_small_e", "side_small_w");

    // ---------------------------------------------------------------- 入口

    @Test
    void supportedTypesAreExactlyTheFivePortedTrees() {
        // 上游注册名（registry/NetherFeatures.java）。写成别的名字会静默 skip。
        assertEquals(
                Set.of("betternether:mushroom_fir", "betternether:wart_tree",
                        "betternether:willow_tree", "betternether:old_willow_tree",
                        "betternether:big_brown_mushroom"),
                UpstreamSimpleTreeFeatures.supportedTypes());
    }

    @Test
    void oldWillowTreeIsClaimedByItsOwnImplementation() {
        // OldWillowTree 的几何（曲线枝 + 球壳树冠 + 挂墙植物 + 写区拟合）由 OldWillow 承担，
        // 不是「靠近似混过去」：认领它 + 下面那些形状/写区用例一起构成这条声明。
        assertTrue(UpstreamSimpleTreeFeatures.supportedTypes().contains("betternether:old_willow_tree"));
        assertNotNull(UpstreamSimpleTreeFeatures.create("betternether:old_willow_tree",
                oldWillowJson(13, true), UpstreamSimpleTreeFeaturesFakeWorld.resolver()));
    }

    @Test
    void unrelatedTypeIsNotOurs() {
        assertNull(UpstreamSimpleTreeFeatures.create("betternether:wart_cap", null,
                UpstreamSimpleTreeFeaturesFakeWorld.resolver()));
    }

    // ---------------------------------------------------------------- mushroom_fir

    @Test
    void mushroomFirGrowsTrunkWithPerSegmentShapes() {
        int placedCount = 0;
        for (long seed = 1; seed <= 24; seed++) {
            final UpstreamSimpleTreeFeaturesFakeWorld world = myceliumWorld(seed);
            if (!place(feature("betternether:mushroom_fir"), world)) {
                assertAllAir(world, "mushroom_fir 失败时不该写任何方块");
                continue;
            }
            placedCount++;

            // 根：pos 上是 bottom（上游无条件写 BOTTOM）。
            assertEquals("betternether:mushroom_fir_trunk", world.idAt(0, GROUND_Y + 1, 0));
            assertEquals("bottom", world.propAt(0, GROUND_Y + 1, 0, "shape"));

            // 中轴：只有 middle / top / end，而且 end 之上不该再有柄。
            boolean sawMiddle = false;
            boolean sawTop = false;
            boolean sawEnd = false;
            for (int y = GROUND_Y + 2; y <= MAX_Y; y++) {
                String shape = world.propAt(0, y, 0, "shape");
                if (shape == null) {
                    break;
                }
                assertTrue(MUSHROOM_FIR_SHAPES.contains(shape), "非法 shape：" + shape);
                assertTrue(shape.equals("middle") || shape.equals("top") || shape.equals("end"),
                        "中轴上出现了侧枝形状：" + shape);
                sawMiddle |= shape.equals("middle");
                sawTop |= shape.equals("top");
                sawEnd |= shape.equals("end");
                if (shape.equals("end")) {
                    break;
                }
            }
            assertTrue(sawMiddle, "3 格以上的树必须有 middle 段");
            assertTrue(sawTop, "柄的上半段必须是 top");
            assertTrue(sawEnd, "柄顶必须是 end");

            // 侧枝只挂在紧贴中轴的四邻上，不允许出现在对角或更远。
            for (int y = GROUND_Y + 1; y <= MAX_Y; y++) {
                for (int dx = -2; dx <= 2; dx++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        if (Math.abs(dx) + Math.abs(dz) == 0) {
                            continue;
                        }
                        String id = world.idAt(dx, y, dz);
                        if (id == null) {
                            continue;
                        }
                        assertEquals("betternether:mushroom_fir_trunk", id);
                        if (Math.abs(dx) + Math.abs(dz) > 1) {
                            throw new AssertionError("侧枝跑到了对角/远处：" + dx + "," + y + "," + dz);
                        }
                        assertTrue(world.propAt(dx, y, dz, "shape").startsWith("side_"),
                                "四邻上只能是 side_* 形状");
                    }
                }
            }
        }
        assertTrue(placedCount > 12, "24 个 seed 里只长成 " + placedCount + " 棵，形状逻辑有问题");
    }

    @Test
    void mushroomFirUsesBothBigAndSmallBranchesWithCrossedNames() {
        Map<String, Boolean> seen = new HashMap<>();
        for (long seed = 1; seed <= 48; seed++) {
            final UpstreamSimpleTreeFeaturesFakeWorld world = myceliumWorld(seed);
            if (!place(feature("betternether:mushroom_fir"), world)) {
                continue;
            }
            for (int y = GROUND_Y + 1; y <= MAX_Y; y++) {
                // 上游把 north 位置写成 SIDE_*_S、south 写成 SIDE_*_N，交叉命名必须保留：
                // 只要「北面出现过 side_big_s」这条成立，就说明没有被人「顺手修正」。
                String north = world.propAt(0, y, -1, "shape");
                if (north != null) {
                    seen.put(north, Boolean.TRUE);
                    assertTrue(north.endsWith("_s"), "北面的形状必须以 _s 结尾（上游交叉命名），实际 " + north);
                }
                String south = world.propAt(0, y, 1, "shape");
                if (south != null) {
                    assertTrue(south.endsWith("_n"), "南面的形状必须以 _n 结尾，实际 " + south);
                }
            }
        }
        assertTrue(seen.containsKey("side_big_s"), "从没出现过大枝（只有细枝说明两段写反了）");
        boolean small = seen.keySet().stream().anyMatch(s -> s.startsWith("side_small"));
        assertTrue(small, "从没出现过细枝");
    }

    @Test
    void mushroomFirRefusesWithoutClearance() {
        final UpstreamSimpleTreeFeaturesFakeWorld world = myceliumWorld(7);
        // 在柄的第二个格子放一块实心：上游会把 h 降到 2，然后 h < 3 直接失败。
        world.put(0, GROUND_Y + 2, 0, "minecraft:obsidian");
        final Map<Long, String> before = world.snapshot();
        assertFalse(place(feature("betternether:mushroom_fir"), world));
        assertEquals(before, world.snapshot(), "净空不足时不该写任何方块");
    }

    @Test
    void mushroomFirRefusesOnNonMyceliumGround() {
        final UpstreamSimpleTreeFeaturesFakeWorld world = new UpstreamSimpleTreeFeaturesFakeWorld(MIN_Y, MAX_Y, 3);
        fillFloor(world, "minecraft:netherrack");
        assertFalse(place(feature("betternether:mushroom_fir"), world));
    }

    @Test
    void mushroomFirNeverSpreadsMyceliumOrShroomlight() {
        int recoloured = 0;
        for (long seed = 1; seed <= 16; seed++) {
            final UpstreamSimpleTreeFeaturesFakeWorld world = myceliumWorld(seed);
            final int floorBefore = myceliumCells(world);
            if (!place(feature("betternether:mushroom_fir"), world)) {
                continue;
            }
            // 调色板之外一个方块都不许有：shroomlight 不是这棵树放的，菌丝也不许「新长」。
            for (String id : world.ids()) {
                assertTrue(id.equals("betternether:mushroom_fir_trunk")
                                || id.equals("betternether:nether_mycelium"),
                        "mushroom_fir 写了调色板之外的方块：" + id);
            }
            assertEquals(floorBefore, myceliumCells(world), "菌丝数量变了：cover 只该染色，不该造新菌丝");
            for (int y = GROUND_Y + 1; y <= MAX_Y; y++) {
                for (int dx = -8; dx <= 8; dx++) {
                    for (int dz = -8; dz <= 8; dz++) {
                        assertFalse("betternether:nether_mycelium".equals(world.idAt(dx, y, dz)),
                                "树上方出现了菌丝（自己铺的）：" + dx + "," + y + "," + dz);
                    }
                }
            }
            // 蓝色变种：cover 的半径是 5，正常情况至少会染到脚下这一格。
            if ("true".equals(world.propAt(0, GROUND_Y, 0, "blue"))) {
                recoloured++;
            }
        }
        assertTrue(recoloured > 0, "cover 一次都没把菌丝染成蓝色（半径/集合逻辑可能写坏了）");
    }

    // ---------------------------------------------------------------- wart_tree

    @Test
    void wartTreeBuildsSquareColumnWithRootsAndCap() {
        int placedCount = 0;
        for (long seed = 1; seed <= 24; seed++) {
            final UpstreamSimpleTreeFeaturesFakeWorld world = soulSandWorld(seed);
            if (!place(feature("betternether:wart_tree", wartJson(7, true)), world)) {
                assertAllAir(world, "wart_tree 失败时不该写任何方块");
                continue;
            }
            placedCount++;
            // y == 0 且脚下不可替换 → 根。
            assertEquals("betternether:wart_roots", world.idAt(0, GROUND_Y + 1, 0));
            // 柱体宽度 = (height >>> 2) + 1，height ∈ [5,9] → 宽度 2 或 3，所以柱体覆盖
            // x,z ∈ {-1,0}（宽 2）或 {-1,0,1}（宽 3）；两种情况下 (-1,-1) 这个角都在里面。
            assertNotNull(world.idAt(-1, GROUND_Y + 1, -1), "柱体是方形实心，角上不能空");
            // 伞盖：只在两条对角线以外，所以对角位置永远不会是伞面方块
            // （伞面上可以挂着种子，所以这里判的是「不是 nether_wart_block」，不是「空气」）。
            int capY = GROUND_Y + 1 + 3;
            for (int y = capY - 2; y <= capY + 4; y++) {
                assertFalse("minecraft:nether_wart_block".equals(world.idAt(2, y, 2)),
                        "伞盖跳过对角线，对角不该是伞面方块");
                assertFalse("minecraft:nether_wart_block".equals(world.idAt(-2, y, -2)));
            }
            // 调色板：疣木方块 + 疣木种子 + 原版疣块，别的一律不许有（包括 shroomlight）。
            for (String id : world.ids()) {
                assertTrue(Set.of("betternether:wart_log", "betternether:wart_roots",
                                "minecraft:nether_wart_block", "betternether:wart_seed",
                                "minecraft:soul_sand").contains(id),
                        "wart_tree 写了调色板之外的方块：" + id);
                assertFalse(id.contains("shroomlight"), "wart_tree 不放 shroomlight");
            }
        }
        assertTrue(placedCount > 12, "24 个 seed 里只长成 " + placedCount + " 棵");
    }

    @Test
    void wartTreeSeedsAlwaysFaceAWartBlockOrFallBackToUp() {
        Set<String> facings = new HashSet<>();
        boolean sawSeed = false;
        for (long seed = 1; seed <= 32; seed++) {
            final UpstreamSimpleTreeFeaturesFakeWorld world = soulSandWorld(seed);
            if (!place(feature("betternether:wart_tree", wartJson(7, true)), world)) {
                continue;
            }
            for (int y = GROUND_Y - 4; y <= GROUND_Y + 20; y++) {
                for (int dx = -4; dx <= 4; dx++) {
                    for (int dz = -4; dz <= 4; dz++) {
                        if (!"betternether:wart_seed".equals(world.idAt(dx, y, dz))) {
                            continue;
                        }
                        sawSeed = true;
                        String facing = world.propAt(dx, y, dz, "facing");
                        assertNotNull(facing, "种子必须带 facing（上游始终 setValue(FACING, …)）");
                        assertTrue(Set.of("up", "down", "north", "south", "east", "west").contains(facing),
                                "非法朝向：" + facing);
                        facings.add(facing);
                    }
                }
            }
        }
        assertTrue(sawSeed, "32 个 seed 里一颗疣木种子都没落（nextInt(8) 分支没走到？）");
        assertTrue(facings.size() >= 2, "种子朝向只出现过 " + facings + "，贴面判定可能是死的");
    }

    @Test
    void wartTreeRefusesWithoutVerticalClearance() {
        final UpstreamSimpleTreeFeaturesFakeWorld world = soulSandWorld(5);
        world.put(0, GROUND_Y + 2, 0, "minecraft:obsidian");
        final Map<Long, String> before = world.snapshot();
        assertFalse(place(feature("betternether:wart_tree", wartJson(7, true)), world),
                "第 2 格被堵住时 WartTreeFeature 返回 false");
        assertEquals(before, world.snapshot());
    }

    @Test
    void wartTreeKeepsUpstreamReturnTrueWhenOnlyOuterClearanceFails() {
        // 上游结构：外层 (above(1)/above(2) 是空气) 成立后，内层净空失败仍然 return true，
        // 一个方块都不写。这条「怪」行为是上游的，改成 continue 会让放置统计与日志失真。
        final UpstreamSimpleTreeFeaturesFakeWorld world = soulSandWorld(5);
        world.put(2, GROUND_Y + 4, 0, "minecraft:obsidian");
        final Map<Long, String> before = world.snapshot();
        assertTrue(place(feature("betternether:wart_tree", wartJson(7, true)), world));
        assertEquals(before, world.snapshot(), "内层净空失败时不该写任何方块");
    }

    @Test
    void wartTreeReadsDistanceFromJson() {
        // distance 只影响 NonOverlappingFeature 的扫描半径（manDist = ceil(1.5*d)）：
        // 7 → 11，13 → 20。所以在 (12, y, 0) 放一根疣木原木，
        // distance=7 时不该挡住，distance=13 时必须挡住。
        final UpstreamSimpleTreeFeaturesFakeWorld near = soulSandWorld(11);
        near.put(12, GROUND_Y + 1, 0, "betternether:wart_log");
        assertTrue(place(feature("betternether:wart_tree", wartJson(7, true)), near),
                "distance=7（manDist=11）时 12 格外的那根不该挡住");

        final UpstreamSimpleTreeFeaturesFakeWorld far = soulSandWorld(11);
        far.put(12, GROUND_Y + 1, 0, "betternether:wart_log");
        assertFalse(place(feature("betternether:wart_tree", wartJson(13, true)), far),
                "distance=13（manDist=20）时 12 格外的那根必须挡住");
    }

    @Test
    void wartTreeRefusesOnNonSoulGround() {
        final UpstreamSimpleTreeFeaturesFakeWorld world = new UpstreamSimpleTreeFeaturesFakeWorld(MIN_Y, MAX_Y, 3);
        fillFloor(world, "minecraft:netherrack");
        assertFalse(place(feature("betternether:wart_tree", wartJson(7, true)), world));
    }

    // ---------------------------------------------------------------- willow_tree

    @Test
    void willowTreeGrowsTrunkAndLeaves() {
        int placedCount = 0;
        for (long seed = 1; seed <= 24; seed++) {
            final UpstreamSimpleTreeFeaturesFakeWorld world = netherrackWorld(seed);
            if (!place(feature("betternether:willow_tree"), world)) {
                assertAllAir(world, "willow_tree 失败时不该写任何方块");
                continue;
            }
            placedCount++;

            assertEquals("betternether:willow_trunk", world.idAt(0, GROUND_Y + 1, 0));
            assertEquals("bottom", world.propAt(0, GROUND_Y + 1, 0, "shape"));

            boolean sawMiddle = false;
            int topY = -1;
            for (int y = GROUND_Y + 2; y <= MAX_Y; y++) {
                String shape = world.propAt(0, y, 0, "shape");
                if (shape == null) {
                    break;
                }
                if (shape.equals("top")) {
                    topY = y;
                    break;
                }
                assertEquals("middle", shape, "柄上只该有 middle，直到 top");
                sawMiddle = true;
            }
            assertTrue(sawMiddle);
            assertTrue(topY > 0, "柄顶必须存在（h2 >= 5）");

            // 柄顶上一格是朝上的叶子，四邻各一片水平叶子（上游写死这 5 个位置）。
            assertEquals("betternether:willow_leaves", world.idAt(0, topY + 1, 0));
            assertEquals("up", world.propAt(0, topY + 1, 0, "facing"));
            assertEquals("true", world.propAt(0, topY + 1, 0, "persistent"));
            int horizontal = 0;
            for (int[] dir : new int[][]{{0, -1}, {0, 1}, {-1, 0}, {1, 0}}) {
                if ("betternether:willow_leaves".equals(world.idAt(dir[0], topY + 1, dir[1]))) {
                    horizontal++;
                }
            }
            assertEquals(4, horizontal, "柄顶那一层的四邻必须各有一片叶子");

            // 调色板：柳木柄、柳枝、柳叶。
            for (String id : world.ids()) {
                assertTrue(Set.of("betternether:willow_trunk", "betternether:willow_branch",
                                "betternether:willow_leaves", "minecraft:netherrack").contains(id),
                        "willow_tree 写了调色板之外的方块：" + id);
            }
        }
        assertTrue(placedCount > 12, "24 个 seed 里只长成 " + placedCount + " 棵");
    }

    @Test
    void willowTreeGrowsBranchesAndHangingVines() {
        boolean sawBranch = false;
        for (long seed = 1; seed <= 32; seed++) {
            final UpstreamSimpleTreeFeaturesFakeWorld world = netherrackWorld(seed);
            if (!place(feature("betternether:willow_tree"), world)) {
                continue;
            }
            if (world.ids().contains("betternether:willow_branch")) {
                sawBranch = true;
                // 柳枝要么 MIDDLE 要么 END，不能有别的形状/朝向属性。
                for (int y = GROUND_Y; y <= MAX_Y; y++) {
                    for (int dx = -8; dx <= 8; dx++) {
                        for (int dz = -8; dz <= 8; dz++) {
                            if (!"betternether:willow_branch".equals(world.idAt(dx, y, dz))) {
                                continue;
                            }
                            String shape = world.propAt(dx, y, dz, "shape");
                            assertTrue("middle".equals(shape) || "end".equals(shape),
                                    "柳枝形状只能是 middle/end，实际 " + shape);
                        }
                    }
                }
            }
        }
        assertTrue(sawBranch, "32 个 seed 里柳枝一次都没出现（vine() 可能被写死成不执行）");
    }

    @Test
    void willowTreeRefusesWithoutHeadroom() {
        final UpstreamSimpleTreeFeaturesFakeWorld world = netherrackWorld(3);
        // upRay 从 pos.above(1) 起算，block 在 y+5 会让 mh <= 3，达不到 5。
        world.put(0, GROUND_Y + 5, 0, "minecraft:obsidian");
        final Map<Long, String> before = world.snapshot();
        assertFalse(place(feature("betternether:willow_tree"), world));
        assertEquals(before, world.snapshot());
    }

    @Test
    void willowTreeRefusesOnBedrockGround() {
        final UpstreamSimpleTreeFeaturesFakeWorld world = new UpstreamSimpleTreeFeaturesFakeWorld(MIN_Y, MAX_Y, 3);
        fillFloor(world, "minecraft:bedrock");
        assertFalse(place(feature("betternether:willow_tree"), world),
                "BlockPredicates.surface 把基岩排除在地面之外");
    }

    // ---------------------------------------------------------------- old_willow_tree

    @Test
    void oldWillowTreeWritesBarkOutsideAndLogInside() {
        boolean sawBark = false;
        boolean sawLog = false;
        int placedCount = 0;
        for (long seed = 1; seed <= 24; seed++) {
            final UpstreamSimpleTreeFeaturesFakeWorld world = netherrackWorld(seed);
            if (!place(feature("betternether:old_willow_tree", oldWillowJson(13, true)), world)) {
                assertAllAir(world, "old_willow_tree 失败时不该写任何方块");
                continue;
            }
            placedCount++;
            sawBark |= world.ids().contains("betternether:willow_bark");
            sawLog |= world.ids().contains("betternether:willow_log");

            // 调色板：柳木树皮/树干/柳叶/柳枝 + 三种挂墙植物，别的一律不许有。
            for (String id : world.ids()) {
                assertTrue(Set.of("betternether:willow_bark", "betternether:willow_log",
                                "betternether:willow_leaves", "betternether:willow_branch",
                                "betternether:wall_moss", "betternether:wall_mushroom_brown",
                                "betternether:wall_mushroom_red", "minecraft:netherrack").contains(id),
                        "old_willow_tree 写了调色板之外的方块：" + id);
            }

            // 树冠叶子：上游 crown() 写的是 natural=false（facing 保持默认 up、persistent 保持默认），
            // 与 willow_tree 那套 persistent=true 的叶子不是同一档。
            for (int y = MIN_Y; y <= MAX_Y; y++) {
                for (int dx = -28; dx <= 28; dx++) {
                    for (int dz = -28; dz <= 28; dz++) {
                        if (!"betternether:willow_leaves".equals(world.idAt(dx, y, dz))) {
                            continue;
                        }
                        assertEquals("false", world.propAt(dx, y, dz, "natural"),
                                "远古柳的树冠叶子必须是 natural=false");
                        assertNull(world.propAt(dx, y, dz, "persistent"),
                                "crown() 不设 persistent，别自作主张加上");
                    }
                }
            }
        }
        assertTrue(placedCount > 12, "24 个 seed 里只长成 " + placedCount + " 棵");
        assertTrue(sawBark, "从没出现过 willow_bark（外侧树皮）");
        assertTrue(sawLog, "从没出现过 willow_log（树干内侧）—— bark/log 分派可能反了");
    }

    @Test
    void oldWillowTreeHangsBranchesAndWallPlants() {
        boolean sawVine = false;
        boolean sawWallPlant = false;
        final Set<String> facings = new HashSet<>();
        for (long seed = 1; seed <= 32; seed++) {
            final UpstreamSimpleTreeFeaturesFakeWorld world = netherrackWorld(seed);
            if (!place(feature("betternether:old_willow_tree", oldWillowJson(13, true)), world)) {
                continue;
            }
            for (int y = MIN_Y; y <= MAX_Y; y++) {
                for (int dx = -28; dx <= 28; dx++) {
                    for (int dz = -28; dz <= 28; dz++) {
                        final String id = world.idAt(dx, y, dz);
                        if ("betternether:willow_branch".equals(id)) {
                            sawVine = true;
                            final String shape = world.propAt(dx, y, dz, "shape");
                            assertTrue("middle".equals(shape) || "end".equals(shape),
                                    "垂枝形状只能是 middle/end，实际 " + shape);
                        } else if (id != null && id.startsWith("betternether:wall_")) {
                            sawWallPlant = true;
                            final String facing = world.propAt(dx, y, dz, "facing");
                            assertNotNull(facing, "挂墙植物必须带 facing");
                            facings.add(facing);
                        }
                    }
                }
            }
        }
        assertTrue(sawVine, "32 个 seed 里垂枝一次都没出现（crown 的挂枝分支没走到？）");
        assertTrue(sawWallPlant, "32 个 seed 里挂墙植物一次都没出现（nextInt(8) 分支没走到？）");
        assertTrue(facings.size() >= 2, "挂墙植物朝向只出现过 " + facings);
    }

    @Test
    void oldWillowTreeRequiresTwentyFiveBlocksOfHeadroom() {
        // 上游 OldWillowTree#place：upRay(pos, 25+2) 必须 >= 25 才继续。
        final UpstreamSimpleTreeFeaturesFakeWorld world = netherrackWorld(3);
        world.put(0, GROUND_Y + 16, 0, "minecraft:obsidian");
        final Map<Long, String> before = world.snapshot();
        assertFalse(place(feature("betternether:old_willow_tree", oldWillowJson(13, true)), world));
        assertEquals(before, world.snapshot(), "净空不足时不该写任何方块");
    }

    @Test
    void oldWillowTreeRefusesOnBedrockGround() {
        final UpstreamSimpleTreeFeaturesFakeWorld world = new UpstreamSimpleTreeFeaturesFakeWorld(MIN_Y, MAX_Y, 3);
        fillFloor(world, "minecraft:bedrock");
        assertFalse(place(feature("betternether:old_willow_tree", oldWillowJson(13, true)), world));
    }

    /**
     * {@code WriteZone} 的算术（{@code headroom} / {@code fitRadius}）逐条对齐上游。
     *
     * <p>这三个数决定了「树冠是被缩小还是整根枝跳过」，是远古柳最容易被写成近似的部分，
     * 所以直接对 {@code Zone} 断言，而不是从树形反推。
     */
    @Test
    void writeZoneFitRadiusMatchesUpstreamArithmetic() {
        // chunkRadius = 0 → 写区就是区块 (0,0)，即 x/z ∈ [0,15]，与真实的 1×1 写区同形。
        final UpstreamSimpleTreeFeaturesFakeWorld world =
                new UpstreamSimpleTreeFeaturesFakeWorld(MIN_Y, MAX_Y, 1, 0);
        final UpstreamSimpleTreeFeatures.Zone zone = UpstreamSimpleTreeFeatures.Zone.of(world, 6, GROUND_Y, 6);

        assertFalse(zone.unbounded());
        assertEquals(0, zone.minX());
        assertEquals(15, zone.maxX());
        assertEquals(0, zone.minZ());
        assertEquals(15, zone.maxZ());
        assertEquals(6.0F, zone.headroom(6, 6), 0.0F);
        // 有富余 → 夹到 room；本来就够小 → 原样；放不下 → -1（整根枝跳过）。
        assertEquals(6.0F, zone.fitRadius(6, 6, 10.0F, 1.5F), 0.0F);
        assertEquals(3.0F, zone.fitRadius(6, 6, 3.0F, 1.5F), 0.0F);
        assertEquals(-1.0F, zone.fitRadius(15, 6, 10.0F, 1.5F), 0.0F);
    }

    @Test
    void writeZoneIsUnboundedWhenThereIsNoWriteRestriction() {
        final UpstreamSimpleTreeFeatures.Zone zone = UpstreamSimpleTreeFeatures.Zone.of(
                UpstreamSimpleTreeFeaturesFakeWorld.unboundedWorld(MIN_Y, MAX_Y, 1), 0, GROUND_Y, 0);
        assertTrue(zone.unbounded());
        assertEquals(Float.MAX_VALUE / 2, zone.headroom(0, 0), 0.0F);
        assertEquals(12.0F, zone.fitRadius(0, 0, 12.0F, 1.5F), 0.0F, "无写区限制时半径原样返回");
        // 无限制时 fitSegment 也原样返回 end（上游同样直接 return new Vector3f(end)）。
        final UpstreamSimpleTreeFeatures.Vec3 end = new UpstreamSimpleTreeFeatures.Vec3(40.0F, 1.0F, 2.0F);
        assertEquals(end, zone.fitSegment(new UpstreamSimpleTreeFeatures.Vec3(0.0F, 0.0F, 0.0F), end,
                0, 0, 2.0F));
    }

    /**
     * 3×3 区块的写区：框是 {@code -16..31}，**不是**对称的 {@code ±24}。
     * 这条与「complex 树」那边对拍过（同一份 {@code Zone}），两边数字必须一致。
     */
    @Test
    void writeZoneBoxesAreChunkAlignedAroundTheProbingChunk() {
        final UpstreamSimpleTreeFeaturesFakeWorld world =
                new UpstreamSimpleTreeFeaturesFakeWorld(MIN_Y, MAX_Y, 1, 1);
        final UpstreamSimpleTreeFeatures.Zone zone = UpstreamSimpleTreeFeatures.Zone.of(world, 8, GROUND_Y, 8);

        assertEquals(-16, zone.minX());
        assertEquals(31, zone.maxX());
        assertEquals(-16, zone.minZ());
        assertEquals(31, zone.maxZ());
        // 右边只到 31，所以 room 是 23 而不是 24 —— 也就是「树冠在中轴右边」会比左边少一格余地。
        assertEquals(23.0F, zone.headroom(8, 8), 0.0F);
        assertEquals(3.0F, zone.fitRadius(28, 8, 4.0F, 2.0F), 0.0F);
        assertEquals(-1.0F, zone.fitRadius(30, 8, 4.0F, 2.0F), 0.0F, "room 1 < minRadius 2 → 整根枝跳过");
    }

    /**
     * {@code fitSegment}：只收长度、保方向。
     *
     * <p>圆心 (20,y,0)、{@code radius = 2} → 胶囊圆心可活动范围 {@code x ∈ [-14, 29]}；
     * 段 {@code (0,0,0) → (20,0,0)} 的 slab clip 给出 {@code t = (29-20)/20 = 0.45}，
     * 于是终点被拉到 {@code x = 9}（方向没变，长度变成 45%）。
     */
    @Test
    void fitSegmentOnlyShortensAndKeepsTheDirection() {
        final UpstreamSimpleTreeFeaturesFakeWorld world =
                new UpstreamSimpleTreeFeaturesFakeWorld(MIN_Y, MAX_Y, 1, 1);
        final UpstreamSimpleTreeFeatures.Zone zone = UpstreamSimpleTreeFeatures.Zone.of(world, 8, GROUND_Y, 8);

        final UpstreamSimpleTreeFeatures.Vec3 start = new UpstreamSimpleTreeFeatures.Vec3(0.0F, 0.0F, 0.0F);
        final UpstreamSimpleTreeFeatures.Vec3 fitted =
                zone.fitSegment(start, new UpstreamSimpleTreeFeatures.Vec3(20.0F, 0.0F, 0.0F), 20, 0, 2.0F);
        assertEquals(9.0F, fitted.x(), 0.0001F);
        assertEquals(0.0F, fitted.y(), 0.0001F);
        assertEquals(0.0F, fitted.z(), 0.0001F);

        // 本来就装得下 → 原样返回 end（绝大多数枝条因此与不裁剪时完全相同）。
        final UpstreamSimpleTreeFeatures.Vec3 shortEnd = new UpstreamSimpleTreeFeatures.Vec3(3.0F, 0.0F, 0.0F);
        assertEquals(shortEnd, zone.fitSegment(start, shortEnd, 20, 0, 2.0F));

        // 起点已经在写区外 → 原样返回 start（这根枝没救，不重新指向）。
        final UpstreamSimpleTreeFeatures.Vec3 outside =
                new UpstreamSimpleTreeFeatures.Vec3(0.0F, 0.0F, -30.0F);
        assertEquals(outside,
                zone.fitSegment(outside, new UpstreamSimpleTreeFeatures.Vec3(5.0F, 0.0F, -30.0F), 20, 0, 2.0F));
    }

    @Test
    void oldWillowTreeNeverWritesOutsideTheWriteZone() {
        // 单区块写区 + 树干贴着区块中心：树冠中心只要落到区外就必须整根枝跳过，
        // 而不是把叶子平切在边界上。
        int placed = 0;
        for (long seed = 1; seed <= 16; seed++) {
            final UpstreamSimpleTreeFeaturesFakeWorld world =
                    new UpstreamSimpleTreeFeaturesFakeWorld(MIN_Y, MAX_Y, seed, 0);
            for (int x = 0; x <= 15; x++) {
                for (int z = 0; z <= 15; z++) {
                    world.put(x, GROUND_Y, z, "minecraft:netherrack");
                }
            }
            final Point origin = new Point(8, GROUND_Y + 1, 8);
            if (!feature("betternether:old_willow_tree", oldWillowJson(13, true)).place(world, origin)) {
                continue;
            }
            placed++;
            for (Map.Entry<Long, String> entry : world.snapshot().entrySet()) {
                final long key = entry.getKey();
                final int x = (int) ((key >> 40) & 0xFFFFF);
                final int y = (int) ((key >> 20) & 0xFFFFF);
                final int z = (int) (key & 0xFFFFF);
                assertTrue(x >= 0 && x <= 15 && z >= 0 && z <= 15,
                        "写到了写区之外：" + x + "," + y + "," + z + "=" + entry.getValue());
                assertTrue(y > MIN_Y && y < MAX_Y, "写到了 y 边界之外：" + x + "," + y + "," + z);
            }
        }
        assertTrue(placed > 8, "16 个 seed 里只有 " + placed + " 棵在单区块写区里长成");
    }

    // ---------------------------------------------------------------- big_brown_mushroom

    @Test
    void bigBrownMushroomBuildsStemAndThreeByThreeCap() {
        int placedCount = 0;
        int rejectedBySize = 0;
        for (long seed = 1; seed <= 32; seed++) {
            final UpstreamSimpleTreeFeaturesFakeWorld world = openWorld(seed);
            if (!place(feature("betternether:big_brown_mushroom"), world)) {
                rejectedBySize++;
                assertAllAir(world, "巨菇失败时不该写任何方块");
                continue;
            }
            placedCount++;

            assertEquals("betternether:brown_large_mushroom", world.idAt(0, GROUND_Y + 1, 0));
            assertEquals("bottom", world.propAt(0, GROUND_Y + 1, 0, "shape"));

            // 找柄顶（top），它上面的 3×3 就是伞面。
            int capY = -1;
            for (int y = GROUND_Y + 2; y <= MAX_Y; y++) {
                String shape = world.propAt(0, y, 0, "shape");
                if (shape == null) {
                    break;
                }
                if (shape.equals("top")) {
                    capY = y;
                    break;
                }
                assertEquals("middle", shape, "柄上只该有 middle，直到 top");
            }
            assertTrue(capY > 0, "伞面必须存在");

            assertEquals("side_n", world.propAt(0, capY, -1, "shape"));
            assertEquals("side_s", world.propAt(0, capY, 1, "shape"));
            assertEquals("side_e", world.propAt(1, capY, 0, "shape"));
            assertEquals("side_w", world.propAt(-1, capY, 0, "shape"));
            // 四角是交叉命名：北+东 → corner_n，北+西 → corner_w，南+东 → corner_e，南+西 → corner_s。
            assertEquals("corner_n", world.propAt(1, capY, -1, "shape"));
            assertEquals("corner_w", world.propAt(-1, capY, -1, "shape"));
            assertEquals("corner_e", world.propAt(1, capY, 1, "shape"));
            assertEquals("corner_s", world.propAt(-1, capY, 1, "shape"));

            for (String id : world.ids()) {
                assertEquals("betternether:brown_large_mushroom", id,
                        "big_brown_mushroom 只放自己（不放 shroomlight/菌丝）：" + id);
            }
        }
        assertTrue(placedCount > 12, "32 个 seed 里只长成 " + placedCount + " 朵");
        assertTrue(rejectedBySize > 0,
                "一次都没被 size < 3 拒掉？说明 blockCount 的计数口径可能变了（上游 1/3 会拒）");
    }

    @Test
    void bigBrownMushroomRefusesWhenCapLayerIsBlocked() {
        final UpstreamSimpleTreeFeaturesFakeWorld world = openWorld(9);
        // 在任何可能的伞面层放满 3×3：size ∈ [3,4] → 伞面在 y+3 或 y+4。
        for (int y = GROUND_Y + 4; y <= GROUND_Y + 5; y++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    world.put(dx, y, dz, "minecraft:obsidian");
                }
            }
        }
        final Map<Long, String> before = world.snapshot();
        assertFalse(place(feature("betternether:big_brown_mushroom"), world));
        assertEquals(before, world.snapshot());
    }

    // ---------------------------------------------------------------- 真实语义通道

    /**
     * 疣木的地面判据现在是**真实 tag** {@code minecraft:soul_fire_base_blocks}
     * （上游 {@code SOUL_GROUND} 的基表），不是「看着像地面的材质」。
     *
     * <p>黑石在 {@code minecraft:base_stone_nether} 里、但**不在**灵魂地面里：疣木必须拒绝 ——
     * 这正是「材质名单近似」做不到的区分。
     */
    @Test
    void wartTreeGroundUsesTheRealSoulGroundTag() {
        final UpstreamSimpleTreeFeaturesFakeWorld onBlackstone =
                new UpstreamSimpleTreeFeaturesFakeWorld(MIN_Y, MAX_Y, 5);
        fillFloor(onBlackstone, "minecraft:blackstone");
        assertFalse(place(feature("betternether:wart_tree", wartJson(7, true)), onBlackstone),
                "黑石是下界石但不是灵魂地面，疣木不该长");

        final UpstreamSimpleTreeFeaturesFakeWorld onSoulSoil =
                new UpstreamSimpleTreeFeaturesFakeWorld(MIN_Y, MAX_Y, 5);
        fillFloor(onSoulSoil, "minecraft:soul_soil");
        assertTrue(place(feature("betternether:wart_tree", wartJson(7, true)), onSoulSoil),
                "灵魂土属于灵魂地面 tag，疣木应当能长");
        assertTrue(onSoulSoil.inTagCalls() > 0, "地面判据必须走真实 tag 通道");
    }

    /**
     * 柳树的地面判据是上游 {@code isNetherGround}：三支原版 tag + 菌丝（自定义方块）。
     *
     * <p>黑石能长（在 {@code base_stone_nether} 里）、黑曜石不能长（不在任何一支里）、
     * 菌丝能长（自定义方块按解析状态比）。旧的「非空气且非基岩」近似会把黑曜石也算成地面。
     */
    @Test
    void willowGroundUsesRealNetherTagsInsteadOfTheSurfaceApproximation() {
        final UpstreamSimpleTreeFeaturesFakeWorld onBlackstone = netherrackWorld(6);
        fillFloor(onBlackstone, "minecraft:blackstone");
        assertTrue(place(feature("betternether:willow_tree"), onBlackstone),
                "黑石在 minecraft:base_stone_nether 里，属于下界地面");

        final UpstreamSimpleTreeFeaturesFakeWorld onObsidian = netherrackWorld(6);
        fillFloor(onObsidian, "minecraft:obsidian");
        assertFalse(place(feature("betternether:willow_tree"), onObsidian),
                "黑曜石不属于任何一支地面 tag（旧近似会误判为地面）");

        final UpstreamSimpleTreeFeaturesFakeWorld onMycelium = myceliumWorld(6);
        assertTrue(place(feature("betternether:willow_tree"), onMycelium),
                "菌丝是 isNetherGround 的成员（自定义方块，按解析状态比）");
        assertTrue(onMycelium.inTagCalls() > 0, "地面判据必须走真实 tag 通道");

        // 上游把 jungle/mushroom/sepia/swampland 四种草都塞进了 minecraft:nylium
        // （见生成文件 data/minecraft/tags/block/nylium.json）。CE 包里它们不在 tag 里，
        // 所以 inTag 会给出「真答案的 false」——这条断言守的是「别把那一支当成冗余删掉」。
        final UpstreamSimpleTreeFeaturesFakeWorld onMushroomGrass = netherrackWorld(6);
        fillFloor(onMushroomGrass, "betternether:mushroom_grass");
        assertTrue(place(feature("betternether:willow_tree"), onMushroomGrass),
                "蘑菇草是上游 minecraft:nylium 的成员，必须算下界地面");

        // wover 的 stones 增量（data/wover/tags/block/surfaces/nether/stones.json）还多了三个
        // 成片出现的地面：netherrack_moss / veined_gloomsculk / ceiling_mushrooms。
        // 只认原版 base_stone_nether 会把这些位置静默判成「不是地面」。
        for (String id : new String[]{"betternether:netherrack_moss",
                "betternether:veined_gloomsculk", "betternether:ceiling_mushrooms"}) {
            final UpstreamSimpleTreeFeaturesFakeWorld world = netherrackWorld(6);
            fillFloor(world, id);
            assertTrue(place(feature("betternether:willow_tree"), world),
                    id + " 在 wover:surfaces/nether/stones 增量里，必须算下界地面");
        }
    }

    /**
     * 疣木的可替换判定改走真实 {@code canBeReplaced()}，而不是我原先那份材质名单。
     *
     * <p>{@code minecraft:tall_grass} 是原版 {@code replaceable} 但既不在上游显式那六个方块里、
     * 也不在我删掉的材质名单里：它被树干覆盖，只可能是真实通道生效。
     */
    @Test
    void wartTreeReplaceableUsesTheRealCanBeReplacedChannel() {
        final UpstreamSimpleTreeFeaturesFakeWorld world = soulSandWorld(8);
        world.put(0, GROUND_Y + 1, 0, "minecraft:tall_grass");
        assertTrue(place(feature("betternether:wart_tree", wartJson(7, true)), world));
        assertTrue(world.replaceableCalls() > 0, "可替换判定必须走真实 canBeReplaced 通道");
        assertTrue(world.blockIdCalls() > 0, "isWart 应当优先用真实 blockId 认原版疣块");
        assertTrue("betternether:wart_roots".equals(world.idAt(0, GROUND_Y + 1, 0)),
                "高草是可替换的（真实 canBeReplaced），树干必须顶掉它，实际 "
                        + world.state(0, GROUND_Y + 1, 0));
    }

    /**
     * **同材质、不同 id 不得互命中**。
     *
     * <p>CE 把自定义方块用**自己的 id** 注册进原版方块注册表，{@code auto_state} 只决定客户端外观，
     * 所以 {@code world.blockId} 是精确身份。这条用例把「两个 id 共用同一个宿主材质」这件事
     * 先断言出来（否则下面两行行为断言可能只是碰巧），再证明地面判据认 id 不认材质：
     * 长得一模一样的 {@code lookalike_moss} / {@code lookalike_soul} 必须被拒绝。
     */
    @Test
    void sameMaterialDifferentIdIsNotConfusedByGroundPredicates() {
        // 先证明这份测试数据里材质确实撞了（不然这条用例没有意义）。
        assertEquals(UpstreamSimpleTreeFeaturesFakeWorld.materialOf("betternether:netherrack_moss"),
                UpstreamSimpleTreeFeaturesFakeWorld.materialOf("betternether:lookalike_moss"),
                "测试数据里这两个 id 必须共用同一材质，才能验证「按 id 判」");
        assertEquals(UpstreamSimpleTreeFeaturesFakeWorld.materialOf("minecraft:soul_sand"),
                UpstreamSimpleTreeFeaturesFakeWorld.materialOf("betternether:lookalike_soul"));

        // 真 id → 能长（对照组）。
        final UpstreamSimpleTreeFeaturesFakeWorld realMoss = netherrackWorld(6);
        fillFloor(realMoss, "betternether:netherrack_moss");
        assertTrue(place(feature("betternether:willow_tree"), realMoss));

        // 同材质的另一个 id → 必须拒绝。材质兜底若被当成判据，这一行就会红。
        final UpstreamSimpleTreeFeaturesFakeWorld lookalikeMoss = netherrackWorld(6);
        fillFloor(lookalikeMoss, "betternether:lookalike_moss");
        assertFalse(place(feature("betternether:willow_tree"), lookalikeMoss),
                "同材质不同 id 不该被当成 wover:surfaces/nether/stones 的成员");

        final UpstreamSimpleTreeFeaturesFakeWorld lookalikeSoul = soulSandWorld(5);
        fillFloor(lookalikeSoul, "betternether:lookalike_soul");
        assertFalse(place(feature("betternether:wart_tree", wartJson(7, true)), lookalikeSoul),
                "同材质不同 id 不该被当成灵魂地面");
    }

    /**
     * {@code isWart} 也必须是方块级语义：同材质的另一个 id 不能让整棵树被判成「附近已有疣木」。
     *
     * <p>NonOverlapping 的扫描半径内（{@code manDist = 11}）放一个 5 格外的方块：
     * 真 {@code wart_log} → 整棵树被挡住；同材质的 {@code lookalike_wart_log} → 照常生长。
     */
    @Test
    void sameMaterialDifferentIdIsNotConfusedByIsWart() {
        assertEquals(UpstreamSimpleTreeFeaturesFakeWorld.materialOf("betternether:wart_log"),
                UpstreamSimpleTreeFeaturesFakeWorld.materialOf("betternether:lookalike_wart_log"));

        final UpstreamSimpleTreeFeaturesFakeWorld lookalike = soulSandWorld(17);
        lookalike.put(5, GROUND_Y + 1, 0, "betternether:lookalike_wart_log");
        assertTrue(place(feature("betternether:wart_tree", wartJson(7, true)), lookalike),
                "同材质不同 id 不该被 isWart 认成疣木原木");

        final UpstreamSimpleTreeFeaturesFakeWorld real = soulSandWorld(17);
        real.put(5, GROUND_Y + 1, 0, "betternether:wart_log");
        assertFalse(place(feature("betternether:wart_tree", wartJson(7, true)), real),
                "真 wart_log 在扫描半径内必须挡住（NonOverlappingFeature）");
    }

    // ---------------------------------------------------------------- 确定性与报告

    @Test
    void sameSeedPlacesIdenticalTrees() {
        assertSameOutcome("betternether:mushroom_fir", null, 21);
        assertSameOutcome("betternether:wart_tree", wartJson(7, true), 21);
        assertSameOutcome("betternether:willow_tree", null, 21);
        assertSameOutcome("betternether:old_willow_tree", oldWillowJson(13, true), 21);
        assertSameOutcome("betternether:big_brown_mushroom", null, 21);
    }

    @Test
    void missingPropertyIsReportedInsteadOfSilentlyAssumedSupported() {
        // resolver(false, …) 模拟「CE 包里没定义这个属性」：属性被丢掉，状态退回默认。
        final Feature willow = UpstreamSimpleTreeFeatures.create("betternether:willow_tree", null,
                UpstreamSimpleTreeFeaturesFakeWorld.resolver(false, null));
        assertNotNull(willow);
        final List<String> notes = UpstreamSimpleTreeFeatures.loadNotes();
        assertTrue(notes.stream().anyMatch(note -> note.contains("betternether:willow_trunk")
                        && note.contains("shape")),
                "属性没落地时必须留下报告，实际 notes：" + notes);
        assertTrue(notes.stream().anyMatch(note -> note.contains("betternether:willow_leaves")
                        && note.contains("facing")),
                "叶子朝向没落地时必须留下报告");
    }

    @Test
    void missingBlockIsReportedAndTheTreeDoesNotGrow() {
        final Feature broken = UpstreamSimpleTreeFeatures.create("betternether:mushroom_fir", null,
                UpstreamSimpleTreeFeaturesFakeWorld.resolver(true, "betternether:mushroom_fir_trunk"));
        assertNotNull(broken);
        assertTrue(UpstreamSimpleTreeFeatures.loadNotes().stream()
                        .anyMatch(note -> note.contains("方块无法解析：betternether:mushroom_fir_trunk")),
                "缺方块必须留下报告，实际 notes：" + UpstreamSimpleTreeFeatures.loadNotes());
        final UpstreamSimpleTreeFeaturesFakeWorld world = myceliumWorld(4);
        final Map<Long, String> before = world.snapshot();
        assertFalse(place(broken, world), "方块缺任何一个都整棵放弃");
        assertEquals(before, world.snapshot());
    }

    // ---------------------------------------------------------------- 脚手架

    private static Feature feature(String type) {
        return feature(type, null);
    }

    private static Feature feature(String type, JsonObject json) {
        final Feature feature = UpstreamSimpleTreeFeatures.create(type, json,
                UpstreamSimpleTreeFeaturesFakeWorld.resolver());
        assertNotNull(feature, "type 应当由本类负责：" + type);
        return feature;
    }

    private static boolean place(Feature feature, UpstreamSimpleTreeFeaturesFakeWorld world) {
        return feature.place(world, ORIGIN);
    }

    private static JsonObject wartJson(int distance, boolean natural) {
        final JsonObject json = new JsonObject();
        json.addProperty("distance", distance);
        json.addProperty("natural", natural);
        return json;
    }

    private static JsonObject oldWillowJson(int distance, boolean natural) {
        final JsonObject json = new JsonObject();
        json.addProperty("distance", distance);
        json.addProperty("natural", natural);
        return json;
    }

    /** 菌丝地面：15×15 的 {@code nether_mycelium} 圆盘（cover 半径 5 要踩到邻居）。 */
    private static UpstreamSimpleTreeFeaturesFakeWorld myceliumWorld(long seed) {
        final UpstreamSimpleTreeFeaturesFakeWorld world =
                new UpstreamSimpleTreeFeaturesFakeWorld(MIN_Y, MAX_Y, seed);
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                world.put(x, GROUND_Y, z, "betternether:nether_mycelium");
            }
        }
        return world;
    }

    private static UpstreamSimpleTreeFeaturesFakeWorld soulSandWorld(long seed) {
        final UpstreamSimpleTreeFeaturesFakeWorld world =
                new UpstreamSimpleTreeFeaturesFakeWorld(MIN_Y, MAX_Y, seed);
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                world.put(x, GROUND_Y, z, "minecraft:soul_sand");
            }
        }
        return world;
    }

    private static UpstreamSimpleTreeFeaturesFakeWorld netherrackWorld(long seed) {
        final UpstreamSimpleTreeFeaturesFakeWorld world =
                new UpstreamSimpleTreeFeaturesFakeWorld(MIN_Y, MAX_Y, seed);
        fillFloor(world, "minecraft:netherrack");
        return world;
    }

    private static UpstreamSimpleTreeFeaturesFakeWorld openWorld(long seed) {
        return new UpstreamSimpleTreeFeaturesFakeWorld(MIN_Y, MAX_Y, seed);
    }

    private static void fillFloor(UpstreamSimpleTreeFeaturesFakeWorld world, String id) {
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                world.put(x, GROUND_Y, z, id);
            }
        }
    }

    private static int myceliumCells(UpstreamSimpleTreeFeaturesFakeWorld world) {
        int count = 0;
        for (int x = -8; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                for (int y = MIN_Y; y <= MAX_Y; y++) {
                    if ("betternether:nether_mycelium".equals(world.idAt(x, y, z))) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    /** 断言树以外一片空气（地形原样）：树失败时绝不允许有半截写入。 */
    private static void assertAllAir(UpstreamSimpleTreeFeaturesFakeWorld world, String message) {
        final List<String> placed = new ArrayList<>();
        for (int y = MIN_Y; y <= MAX_Y; y++) {
            for (int dx = -16; dx <= 16; dx++) {
                for (int dz = -16; dz <= 16; dz++) {
                    final String state = world.state(dx, y, dz);
                    if (state == null) {
                        continue;
                    }
                    // 只允许地面那一层（测试自己铺的）仍在。
                    if (y != GROUND_Y) {
                        placed.add(dx + "," + y + "," + dz + "=" + state);
                    }
                }
            }
        }
        assertTrue(placed.isEmpty(), message + "：" + placed);
    }

    private static void assertSameOutcome(String type, JsonObject json, long seed) {
        final UpstreamSimpleTreeFeaturesFakeWorld first = worldFor(type, seed);
        final UpstreamSimpleTreeFeaturesFakeWorld second = worldFor(type, seed);

        assertEquals(place(feature(type, json), first), place(feature(type, json), second),
                type + " 同 seed 的返回值不同");
        assertEquals(first.snapshot(), second.snapshot(), type + " 同 seed 的形状不同");
    }

    /** 每棵树要它自己那种地面；旧柳与柳树都是下界地面。 */
    private static UpstreamSimpleTreeFeaturesFakeWorld worldFor(String type, long seed) {
        return switch (type) {
            case "betternether:mushroom_fir" -> myceliumWorld(seed);
            case "betternether:wart_tree" -> soulSandWorld(seed);
            case "betternether:willow_tree", "betternether:old_willow_tree" -> netherrackWorld(seed);
            default -> openWorld(seed);
        };
    }

    /** 只是为了让「解析 JSON」这条路径也被测到：字符串解析与 addProperty 结果必须等价。 */
    @Test
    void wartJsonParsingMatchesConstructedJson() {
        final JsonObject parsed = JsonParser.parseString("{\"type\":\"betternether:wart_tree\","
                + "\"distance\":7,\"natural\":true}").getAsJsonObject();
        assertSameOutcome("betternether:wart_tree", parsed, 14);
    }
}
