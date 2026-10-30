package dev.mumu.betternether.terrain;

import dev.mumu.betternether.config.NetherConfig;
import dev.mumu.betternether.config.NetherConfig.OreRule;
import dev.mumu.betternether.config.NetherConfig.OreStep;
import dev.mumu.betternether.config.NetherConfig.OreTargetState;
import dev.mumu.betternether.worldgen.Layers;
import dev.mumu.betternether.worldgen.Point;
import dev.mumu.betternether.worldgen.WorldView;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 矿脉放置本身的行为，用 {@link OrePopulator#placeChunk} 加一个内存版 {@link OrePopulator.OreRegion}
 * 驱动，不需要服务端。
 *
 * <p>钉住的东西，以及为什么每一条都是真实失败过的地方：
 * <ul>
 *   <li><b>确定性</b>：上游给每条 placed feature 一个 per-chunk 种子，同一个种子必须复现同一批矿。</li>
 *   <li><b>链的次序与随机消费</b>：{@code count} 不消费随机、{@code rarity_filter} 每个位置只抽一个
 *       {@code nextFloat}、{@code in_square} 先 x 后 z。旧实现用「整数部分 + 一次硬币」模仿
 *       rarity，随机路径完全不同。</li>
 *   <li><b>高度带</b>：矿团必须落在它自己那条 {@code height_range} 里。</li>
 *   <li><b>按群系门控</b>：{@code nether_swampland} 一条 BN 矿都没有，ruby 只在 10 个 BN 群系
 *       （外加两个原版森林）里。</li>
 *   <li><b>不重复坐标</b>：{@code BitSet} 去重，一条矿团不会把两个方块花在同一个格子上。</li>
 *   <li><b>只替换 target 列出的方块</b>：判据是方块身份 id，不是 {@code Material}。</li>
 *   <li><b>targets 按序试</b>：{@code gloomwood_ceiling_debris} 有两条（sculk / netherrack）。</li>
 *   <li><b>天花板层展开</b>：{@code wover:every_layer top:false} 把位置摊到该列每一个
 *       「空气且正上方实心」的 y。</li>
 * </ul>
 */
class OrePopulatorPlacementTest {
    /** 原版 {@code minecraft:the_nether}：min_y 0、height 256。 */
    private static final int NETHER_MIN_Y = 0;
    private static final int NETHER_MAX_Y = 255;
    /** {@code OCEAN_FLOOR_WG} 的返回值：这一列最高实心方块之上那一格。128 以上是空气。 */
    private static final int TERRAIN_TOP = 128;

    private static final String RUBY_BIOME = "betternether:crimson_glowing_woods";
    private static final String PLAIN_BN_BIOME = "betternether:gloomwood";
    private static final String SWAMPLAND = "betternether:nether_swampland";

    private static final Set<String> ALL_PLACED = Set.of(
            "betternether:cincinnasite_ore",
            "betternether:nether_ruby_rare_ore",
            "betternether:nether_lapis_ore",
            "betternether:nether_redstone_ore",
            "betternether:nether_ruby_ore",
            "betternether:nether_ruby_large_ore",
            "betternether:nether_ruby_soul_ore",
            "betternether:gloomwood_ceiling_debris");

    /**
     * 层扫描现在走共享的 {@code Layers}，它要求装载期注入过真实 terrain id 集（没注入会抛）。
     * 单测在这里显式注入与假区域对应的那一份，不依赖服务端装载流程。
     */
    @BeforeAll
    static void installTerrainIds() {
        Layers.installTerrainIds(Set.of("minecraft:netherrack", "minecraft:sculk"));
    }

    private static List<OreRule> rules() {
        return NetherConfig.DEFAULTS.oreRules();
    }

    private static OreRule rule(String placedFeatureId) {
        for (OreRule rule : NetherConfig.UPSTREAM_ORE_RULES) {
            if (rule.placedFeatureId().equals(placedFeatureId)) {
                return rule;
            }
        }
        throw new IllegalArgumentException(placedFeatureId);
    }

    private static void place(FakeRegion region, long seed, int chunkX, int chunkZ) {
        OrePopulator.placeChunk(rules(), ALL_PLACED, List.of(region.biome), new Random(seed),
                seed, chunkX, chunkZ, null, region);
    }

    // ------------------------------------------------------------------ 确定性与形状

    @Test
    void sameSeedAndChunkProduceTheSameOres() {
        FakeRegion first = new FakeRegion();
        place(first, 424242L, 7, -13);
        FakeRegion second = new FakeRegion();
        place(second, 424242L, 7, -13);

        assertFalse(first.placed.isEmpty(), "固定种子下这一区块必须真的生成了矿，否则下面的比较没有意义");
        assertEquals(first.placed, second.placed, "同一 (种子, 区块) 必须逐格相同");
    }

    @Test
    void aDifferentSeedMovesTheVeins() {
        FakeRegion first = new FakeRegion();
        place(first, 1L, 0, 0);
        FakeRegion second = new FakeRegion();
        place(second, 2L, 0, 0);

        assertNotEquals(first.placed, second.placed, "换种子必须换布局，否则种子参数是假的");
    }

    @Test
    void oneVeinNeverWritesTheSameCoordinateTwice() {
        OreRule redstone = rule("betternether:nether_redstone_ore");
        for (long seed = 0; seed < 32; seed++) {
            FakeRegion region = new FakeRegion();
            OrePopulator.placeOre(redstone, new Random(seed), 3, 20, 9, region);
            assertEquals(region.writes.size(), region.placed.size(),
                    "单条矿团里出现了重复坐标：" + region.writes.size() + " 次写入只有 "
                            + region.placed.size() + " 个格子");
            // feature.size 是**球链的层数**，不是方块数上限：每层半径 = ((sin(pi t)+1)*r+1)/2 >= 0.5，
            // 所以一条团会写 1~2 倍 size 个格子（size 16 实测 14~27）。旧实现在 3 格左右，正是
            // 「绕原点重抖」留下的空壳。上界取 2*size 既能抓跑飞，也能抓住退回空壳。
            assertTrue(region.placed.size() >= 1, "一条矿团至少要写出一个方块");
            assertTrue(region.placed.size() <= 2 * redstone.size(),
                    "一条 size " + redstone.size() + " 的矿团写了 " + region.placed.size()
                            + " 个格子，超过 2*size（球链层数语义下的几何上界）");
        }
    }

    @Test
    void oneVeinStaysInsideItsOriginBox() {
        // 外包盒 = 2*(ceil(size/8) + ceil((size/16*2+1)/2))，x/z 与 y 各一份。
        // size 16 -> ceil(2.0)=2, boxRadius=2 -> 每轴跨度 <= 8。
        OreRule redstone = rule("betternether:nether_redstone_ore");
        int box = 2 * (boxRadius(16) + extent(16));
        for (long seed = 0; seed < 32; seed++) {
            FakeRegion region = new FakeRegion();
            OrePopulator.placeOre(redstone, new Random(seed), 0, 20, 0, region);
            assertTrue(region.span(0) <= box, "x 跨度 " + region.span(0) + " 超出盒子 " + box);
            assertTrue(region.span(1) <= box, "y 跨度 " + region.span(1) + " 超出盒子 " + box);
            assertTrue(region.span(2) <= box, "z 跨度 " + region.span(2) + " 超出盒子 " + box);
        }
    }

    // ------------------------------------------------------------------ 链的次序与随机消费

    @Test
    void countStepCopiesPositionsWithoutConsumingRandom() {
        // minecraft:count 是常量提供器（ConstantInt#sample 直接返回 value），一次随机都不该消费。
        // 用只含 count 一步的合成规则来测：真实规则里 count 后面还跟 height_range/in_square，
        // 那些本来就要抽随机，混在一起就分不清是谁抽的。
        OreRule onlyCount = new OreRule("test:count", List.of(
                new OreTargetState("minecraft:netherrack", "betternether:cincinnasite_ore")),
                8, 0.0f, NetherConfig.VerticalAnchor.aboveBottom(4), NetherConfig.VerticalAnchor.belowTop(4), NetherConfig.OreShape.UNIFORM,
                List.of(OreStep.count(10)),
                Set.of(RUBY_BIOME), false);

        Random probe = new Random(12345L);
        Random reference = new Random(12345L);
        FakeRegion region = new FakeRegion();
        List<Point> targets = OrePopulator.placementTargets(onlyCount, probe, 0, 0, region);

        assertEquals(10, targets.size(), "count 10 -> 10 个位置");
        assertEquals(reference.nextLong(), probe.nextLong(),
                "count 不该消费随机：消费了就会把后面的随机流推错位");
    }

    @Test
    void rarityStepUsesNextFloatComparableToTheInverseChance() {
        // RarityFilter#shouldPlace 的字节码是 nextFloat() < 1.0f / chance。
        OreRule redstone = rule("betternether:nether_redstone_ore");
        FakeRegion region = new FakeRegion();
        for (long seed = 0; seed < 32; seed++) {
            Random probe = new Random(seed);
            Random reference = new Random(seed);
            int expected = reference.nextFloat() < 1.0f / 1 ? 1 : 0;
            assertEquals(expected, OrePopulator.placementTargets(redstone, probe, 0, 0, region).size(),
                    "chance 1 的 rarity_filter 与 nextFloat 版本判定不一致");
        }
    }

    @Test
    void rarityChanceTwoKeepsAboutHalfTheChunks() {
        OreRule rare = rule("betternether:nether_ruby_rare_ore");
        FakeRegion region = new FakeRegion();
        int kept = 0;
        int chunks = 512;
        for (int index = 0; index < chunks; index++) {
            kept += OrePopulator.placementTargets(rare, new Random(index * 0xBF58476D1CE4E5B9L),
                    0, 0, region).size();
        }
        // 二项分布 sd ≈ sqrt(512*0.25) ≈ 11.3；给 ±60（约 5σ），既钉住「一半」又不因换种子变红。
        assertTrue(Math.abs(kept - chunks * 0.5) < 60,
                "chance 2 -> 平均 0.5 条/区块，512 区块实际 " + kept);
    }

    @Test
    void inSquareMovesThePositionInsideTheChunkAndConsumesTwoDraws() {
        OreRule cincinnasite = rule("betternether:cincinnasite_ore");
        FakeRegion region = new FakeRegion();
        Random probe = new Random(99L);
        List<Point> targets = OrePopulator.placementTargets(cincinnasite, probe, 4, -3, region);

        for (Point point : targets) {
            assertTrue(point.x() >= 4 << 4 && point.x() < (4 << 4) + 16,
                    "in_square 必须把 x 落在本区块内，实际 " + point.x());
            assertTrue(point.z() >= -3 << 4 && point.z() < (-3 << 4) + 16,
                    "in_square 必须把 z 落在本区块内，实际 " + point.z());
            assertTrue(point.y() >= 10 && point.y() <= 245,
                    "height_range 必须落在 cincinnasite 的带子里，实际 " + point.y());
        }
    }

    // ------------------------------------------------------------------ 天花板层（gloomwood 碎屑）

    @Test
    void ceilingLayersExpandsToOnePositionPerCeilingRow() {
        // 只保留到 every_layer 为止的链，这样能精确数出展开后的位置数（尾部还有 rarity 会打折）。
        OreRule debris = rule("betternether:gloomwood_ceiling_debris");
        OreRule chainOnly = new OreRule(debris.placedFeatureId(), debris.targets(), debris.size(),
                debris.discardChance(), debris.min(), debris.max(), debris.shape(),
                List.of(OreStep.HEIGHT_RANGE, OreStep.count(1), OreStep.IN_SQUARE, OreStep.BIOME,
                        OreStep.ceilingLayers(4)),
                debris.biomes(), false);

        FakeRegion region = new FakeRegion();
        region.biome = "betternether:gloomwood";
        // 实心 0..63，然后 66 / 68 / 70 三层实心，其余空气 -> 天花板层是 65 / 67 / 69。
        region.solidRows = new HashSet<>(Set.of(66, 68, 70));
        region.solidBelow = 64;

        List<Point> targets = OrePopulator.placementTargets(chainOnly, new Random(1L), 0, 0, region);
        List<Integer> ys = new ArrayList<>();
        for (Point point : targets) {
            ys.add(point.y());
        }
        // EveryLayer.findUnderGroundYPosition：自顶向下，发射地形方块下面那一格（y-1）。
        assertEquals(List.of(69, 67, 65), ys,
                "every_layer top:false 必须自顶向下发射 y-1，实际 " + ys);
    }

    @Test
    void ceilingLayersRespectTheMinBound() {
        OreRule debris = rule("betternether:gloomwood_ceiling_debris");
        OreRule chainOnly = new OreRule(debris.placedFeatureId(), debris.targets(), debris.size(),
                debris.discardChance(), debris.min(), debris.max(), debris.shape(),
                List.of(OreStep.HEIGHT_RANGE, OreStep.count(1), OreStep.IN_SQUARE, OreStep.BIOME,
                        OreStep.ceilingLayers(68)),
                debris.biomes(), false);

        FakeRegion region = new FakeRegion();
        region.solidRows = new HashSet<>(Set.of(66, 68, 70));
        region.solidBelow = 64;

        Set<Integer> ys = new LinkedHashSet<>();
        for (Point point : OrePopulator.placementTargets(chainOnly, new Random(1L), 0, 0, region)) {
            ys.add(point.y());
        }
        assertTrue(ys.stream().allMatch(y -> y >= 68), "every_layer 的 min 是 68，实际 " + ys);
    }

    /**
     * {@code gloomwood_ceiling_debris} 有两条 target，按 JSON 的次序试。
     *
     * <p>直接测派发而不是测布局：这条 feature 的 {@code size} 是 <b>2</b>，
     * {@code radius = ((sin+1)*nextDouble()*size/16 + 1)/2} 最大只有 0.625，而球内判定用的是三个轴的
     * 平方和（{@code < 1}），格子中心离球心至少差半格，三轴叠加后基本永远进不去。也就是说这条矿团
     * 在上游自己也是近乎不产出的（实测 2048 个区块 0 格），拿它当布局断言只会写成随机 flaky。
     * target 的次序是确定性的，所以在这里钉住。
     */
    @Test
    void gloomwoodDebrisTriesItsTargetsInOrder() {
        OreRule debris = rule("betternether:gloomwood_ceiling_debris");

        assertEquals(0, targetIndexOf(debris, "minecraft:sculk"), "sculk 是第一条 target");
        assertEquals(1, targetIndexOf(debris, "minecraft:netherrack"), "netherrack 是第二条 target");
        assertEquals(-1, targetIndexOf(debris, "minecraft:blackstone"),
                "两条 target 都不匹配时一格都不该写");
    }

    /** 让一格地面参与一次 target 派发，返回命中的 target 下标（{@code -1} = 都没命中）。 */
    private static int targetIndexOf(OreRule rule, String ground) {
        FakeRegion region = new FakeRegion();
        region.ground = ground;
        if (!OrePopulator.canPlaceOre(rule, new Random(1L), 4, 40, 4, region)) {
            return -1;
        }
        return region.lastTargetIndex;
    }

    @Test
    void ceilingLayerScanStopsAtTheMotionBlockingHeight() {
        // 与 worldgen.Layers.layers(..., top=false) 同一个上界：min(min(max, MOTION_BLOCKING 高度), maxY)。
        // 不夹这一刀，扫描会把「高度图之上」也算进来，与植被那条线的 every_layer 给出不同层集合。
        OreRule debris = rule("betternether:gloomwood_ceiling_debris");
        OreRule chainOnly = new OreRule(debris.placedFeatureId(), debris.targets(), debris.size(),
                debris.discardChance(), debris.min(), debris.max(), debris.shape(),
                List.of(OreStep.HEIGHT_RANGE, OreStep.count(1), OreStep.IN_SQUARE, OreStep.BIOME,
                        OreStep.ceilingLayers(4)),
                debris.biomes(), false);

        FakeRegion region = new FakeRegion();
        region.biome = "betternether:gloomwood";
        region.solidRows = new HashSet<>(Set.of(66, 68, 70));
        region.solidBelow = 64;
        region.motionTop = 66;

        Set<Integer> ys = new LinkedHashSet<>();
        for (Point point : OrePopulator.placementTargets(chainOnly, new Random(1L), 0, 0, region)) {
            ys.add(point.y());
        }
        assertEquals(Set.of(65), ys,
                "MOTION_BLOCKING 高度是 66 时，67 与 69 两层必须在扫描上界之外，实际 " + ys);
    }

    @Test
    void ceilingLayerSkipsTheCellUnderBedrock() {
        // EveryLayer 的条件里有 `!now.is(BEDROCK)`：基岩下面的那一格不算层。
        OreRule debris = rule("betternether:gloomwood_ceiling_debris");
        OreRule chainOnly = new OreRule(debris.placedFeatureId(), debris.targets(), debris.size(),
                debris.discardChance(), debris.min(), debris.max(), debris.shape(),
                List.of(OreStep.HEIGHT_RANGE, OreStep.count(1), OreStep.IN_SQUARE, OreStep.BIOME,
                        OreStep.ceilingLayers(4)),
                debris.biomes(), false);

        FakeRegion region = new FakeRegion();
        region.biome = "betternether:gloomwood";
        region.solidBelow = 64;
        region.solidRows = new HashSet<>(Set.of(74));
        region.bedrockRows = new HashSet<>(Set.of(74));

        List<Integer> ys = new ArrayList<>();
        for (Point point : OrePopulator.placementTargets(chainOnly, new Random(1L), 0, 0, region)) {
            ys.add(point.y());
        }
        assertTrue(ys.isEmpty(), "74 是基岩，73 不该被当成天花板层，实际 " + ys);
    }

    @Test
    void ceilingLayerAcceptsFluidBelowTerrain() {
        // BlockHelper.isFreeOrFluid = isAir() || liquid()：地形方块下面是岩浆也算「空位」。
        OreRule debris = rule("betternether:gloomwood_ceiling_debris");
        OreRule chainOnly = new OreRule(debris.placedFeatureId(), debris.targets(), debris.size(),
                debris.discardChance(), debris.min(), debris.max(), debris.shape(),
                List.of(OreStep.HEIGHT_RANGE, OreStep.count(1), OreStep.IN_SQUARE, OreStep.BIOME,
                        OreStep.ceilingLayers(4)),
                debris.biomes(), false);

        FakeRegion region = new FakeRegion();
        region.biome = "betternether:gloomwood";
        region.solidBelow = 64;
        region.solidRows = new HashSet<>(Set.of(74));
        region.liquidRows = new HashSet<>(Set.of(73));

        List<Integer> ys = new ArrayList<>();
        for (Point point : OrePopulator.placementTargets(chainOnly, new Random(1L), 0, 0, region)) {
            ys.add(point.y());
        }
        assertEquals(List.of(73), ys, "岩浆属于 freeOrFluid，73 必须被发射，实际 " + ys);
    }

    @Test
    void findLowestLayerIsMinHeight() {
        // wover 的循环下界是 `y >= minHeight + 1`，最低发射 minHeight。
        // 实心 0..63 + 66 / 68 / 70 时，min = 68 只能发射 69；若把扫描下界错传成 68，
        // 会从 y=68 发射出 67 —— 这条断言就是钉那个 off-by-one 的。
        OreRule debris = rule("betternether:gloomwood_ceiling_debris");
        OreRule chainOnly = new OreRule(debris.placedFeatureId(), debris.targets(), debris.size(),
                debris.discardChance(), debris.min(), debris.max(), debris.shape(),
                List.of(OreStep.HEIGHT_RANGE, OreStep.count(1), OreStep.IN_SQUARE, OreStep.BIOME,
                        OreStep.ceilingLayers(68)),
                debris.biomes(), false);

        FakeRegion region = new FakeRegion();
        region.biome = "betternether:gloomwood";
        region.solidBelow = 64;
        region.solidRows = new HashSet<>(Set.of(66, 68, 70));

        List<Integer> ys = new ArrayList<>();
        for (Point point : OrePopulator.placementTargets(chainOnly, new Random(1L), 0, 0, region)) {
            ys.add(point.y());
        }
        assertEquals(List.of(69), ys,
                "min=68 时最低只能发射 68，67 必须被下界挡住，实际 " + ys);
    }

    @Test
    void oneVeinFootprintStaysWellInsideTheLimitedRegionBuffer() {
        // 父代理要求的证据：8 条矿在最大 size 下，矿团外包盒 + 邻接空气检查都必须落在
        // LimitedRegion 的 buffer 里。buffer 的实测值来自字节码：
        //   javap -c org.bukkit.craftbukkit.generator.CraftLimitedRegion
        //   → 构造函数 `bipush 16; putfield buffer:I`
        // 也就是 region = 中心区块 ± 16 格。这里逐条量出真实足迹（包括 oceanFloorHeight 的列查询、
        // 六向 airCheck、以及 inRegion 查询），而不是按公式推一遍就完。
        int buffer = 16;
        for (OreRule rule : NetherConfig.UPSTREAM_ORE_RULES) {
            int size = rule.size();
            int horizontal = extent(size) + boxRadius(size) + 1;
            int vertical = boxRadius(size) + 3;
            FootprintRegion footprint = new FootprintRegion(0, 60, 0);
            for (long seed = 0; seed < 256; seed++) {
                OrePopulator.placeOre(rule, new Random(seed), 0, 60, 0, footprint);
            }
            assertTrue(footprint.maxX - 0 <= horizontal && 0 - footprint.minX <= horizontal,
                    rule.placedFeatureId() + " 的 x 足迹 [" + footprint.minX + "," + footprint.maxX
                            + "] 超出 size " + size + " 的理论半宽 " + horizontal);
            assertTrue(footprint.maxZ - 0 <= horizontal && 0 - footprint.minZ <= horizontal,
                    rule.placedFeatureId() + " 的 z 足迹 [" + footprint.minZ + "," + footprint.maxZ
                            + "] 超出理论半宽 " + horizontal);
            assertTrue(60 - footprint.minY <= vertical && footprint.maxY - 60 <= vertical,
                    rule.placedFeatureId() + " 的 y 足迹 [" + footprint.minY + "," + footprint.maxY
                            + "] 超出理论半高 " + vertical);
            assertTrue(horizontal <= buffer,
                    rule.placedFeatureId() + " 的半宽 " + horizontal + " 超过 LimitedRegion buffer "
                            + buffer + "：换到相邻区块的矿就会越界写");
        }
    }

    @Test
    void gloomwoodDebrisNeverAppearsOutsideItsBiome() {
        FakeRegion region = new FakeRegion();
        region.biome = RUBY_BIOME;
        region.ground = "minecraft:sculk";
        place(region, 21L, 0, 0);
        assertFalse(region.placed.containsValue("betternether:gloomwood_ceiling_debris"),
                "gloomwood_ceiling_debris 只在 betternether:gloomwood 的 features[7] 里");
    }

    // ------------------------------------------------------------------ 高度带

    @Test
    void everyBlockLandsInsideItsOresResolvedBand() {
        for (int chunkX = 0; chunkX < 8; chunkX++) {
            for (int chunkZ = 0; chunkZ < 8; chunkZ++) {
                FakeRegion region = new FakeRegion();
                place(region, 987654321L, chunkX, chunkZ);
                for (Map.Entry<String, String> entry : region.placed.entrySet()) {
                    int y = y(entry.getKey());
                    int min = switch (entry.getValue()) {
                        case "betternether:cincinnasite_ore" -> 10 - 2 - boxRadius(8);
                        case "betternether:nether_lapis_ore" -> 32 - 2 - boxRadius(4);
                        case "betternether:nether_redstone_ore" -> 8 - 2 - boxRadius(16);
                        case "betternether:nether_ruby_large_ore" -> 32 - 2 - boxRadius(5);
                        case "betternether:nether_ruby_soul_ore" -> 32 - 2 - boxRadius(5);
                        case "betternether:nether_ruby_rare_ore" -> 70 - 2 - boxRadius(12);
                        case "betternether:gloomwood_ceiling_debris" -> 4 - 2 - boxRadius(2);
                        default -> 32 - 2 - boxRadius(8);
                    };
                    assertTrue(y >= min, entry.getValue() + " 出现在 y=" + y + "，低于上游下界（含团半径）" + min);
                    assertTrue(y <= TERRAIN_TOP - 1,
                            entry.getValue() + " 出现在 y=" + y + "，高于地形顶面（该处只有空气）");
                }
            }
        }
    }

    @Test
    void redstoneStaysInsideItsNarrowBand() {
        OreRule redstone = rule("betternether:nether_redstone_ore");
        int floor = 8 - 2 - boxRadius(16);
        int ceiling = 40 + 2 + boxRadius(16);
        for (long seed = 0; seed < 16; seed++) {
            FakeRegion region = new FakeRegion();
            OrePopulator.placeOre(redstone, new Random(seed), 0, 20, 0, region);
            for (String key : region.placed.keySet()) {
                int y = y(key);
                assertTrue(y >= floor && y <= ceiling,
                        "redstone 上游是 8..40 above_bottom，实际 y=" + y);
            }
        }
    }

    @Test
    void rubyNeverAppearsBelowItsUpstreamFloor() {
        int floor = 32 - 2 - boxRadius(8);
        for (int chunkX = 0; chunkX < 8; chunkX++) {
            FakeRegion region = new FakeRegion();
            place(region, 11L, chunkX, 0);
            for (Map.Entry<String, String> entry : region.placed.entrySet()) {
                if (!entry.getValue().contains("ruby")) {
                    continue;
                }
                int min = entry.getValue().equals("betternether:nether_ruby_rare_ore")
                        ? 70 - 2 - boxRadius(12)
                        : floor;
                assertTrue(y(entry.getKey()) >= min,
                        entry.getValue() + " 出现在 y=" + y(entry.getKey()) + "，低于上游下界 " + min
                                + "（本包旧实现是 5）");
            }
        }
    }

    // ------------------------------------------------------------------ 群系门控

    @Test
    void swamplandGetsNoBetternetherOreAtAll() {
        FakeRegion region = new FakeRegion();
        region.biome = SWAMPLAND;
        place(region, 99L, 0, 0);
        assertTrue(region.placed.isEmpty(),
                "nether_swampland 的 features[7] 一条 BN 矿都没有，实际写了 " + region.placed.size() + " 格");
    }

    @Test
    void rubyOnlyAppearsInTheBiomesThatListIt() {
        FakeRegion rubyBiome = new FakeRegion();
        rubyBiome.biome = RUBY_BIOME;
        place(rubyBiome, 4242L, 3, 3);
        assertTrue(rubyBiome.placed.containsValue("betternether:nether_ruby_ore"),
                RUBY_BIOME + " 的 features[7] 里有 nether_ruby_ore");

        FakeRegion noRuby = new FakeRegion();
        noRuby.biome = PLAIN_BN_BIOME;
        place(noRuby, 4242L, 3, 3);
        assertFalse(noRuby.placed.containsValue("betternether:nether_ruby_ore"),
                PLAIN_BN_BIOME + " 的 features[7] 里没有 nether_ruby_ore");
        assertTrue(noRuby.placed.containsValue("betternether:nether_lapis_ore"),
                "同一群系的基础四条矿仍然要在");
    }

    /**
     * 生产里 {@code WorldView#biomeAt} 对本包群系返回的是 {@code NetherBiomes.ALL} 的<b>裸键</b>
     * （{@code "crimson_glowing_woods"}），只有原版群系带 {@code minecraft:}；而
     * {@code DecorationScheduler#chunkBiomes} 交给 {@code listedInAnyBiome} 的也是同一批裸键。
     * 这条按真实形状跑一遍：裸键与带 {@code betternether:} 的写法必须得到<b>完全相同</b>的矿。
     */
    @Test
    void bareBiomeKeysFromTheWorldViewGateOresExactlyLikeNamespacedOnes() {
        for (String bare : List.of("crimson_glowing_woods", "nether_swampland", "gloomwood",
                "soul_plain")) {
            FakeRegion bareKeys = new FakeRegion();
            bareKeys.biome = bare;
            place(bareKeys, 4242L, 3, 3);
            FakeRegion namespaced = new FakeRegion();
            namespaced.biome = "betternether:" + bare;
            place(namespaced, 4242L, 3, 3);
            assertEquals(namespaced.placed, bareKeys.placed,
                    bare + " 的裸键与 betternether:" + bare + " 必须放出一模一样的矿");
        }

        FakeRegion rubyBiome = new FakeRegion();
        rubyBiome.biome = "crimson_glowing_woods";
        place(rubyBiome, 4242L, 3, 3);
        assertTrue(rubyBiome.placed.containsValue("betternether:nether_ruby_ore"),
                "裸键也必须命中 RUBY_ORE_BIOMES，否则 ruby 一个都不生成");

        FakeRegion swampland = new FakeRegion();
        swampland.biome = "nether_swampland";
        place(swampland, 99L, 0, 0);
        assertTrue(swampland.placed.isEmpty(),
                "裸键 swampland 也要被排除，实际写了 " + swampland.placed.size() + " 格");
    }

    @Test
    void rubyLargeBelongsToSoulSandValleyOnly() {
        FakeRegion valley = new FakeRegion();
        valley.biome = "minecraft:soul_sand_valley";
        place(valley, 5L, 0, 0);
        assertTrue(valley.placed.containsValue("betternether:nether_ruby_large_ore"),
                "soul_sand_valley 通过 ruby_ore_dense.json 拿到 nether_ruby_large_ore（旧实现判成永不生成）");
        assertFalse(valley.placed.containsValue("betternether:nether_ruby_ore"),
                "普通 ruby 不挂在 soul_sand_valley 上");

        FakeRegion crimson = new FakeRegion();
        crimson.biome = "minecraft:crimson_forest";
        place(crimson, 5L, 0, 0);
        assertFalse(crimson.placed.containsValue("betternether:nether_ruby_large_ore"),
                "large 变体只在 soul_sand_valley");
        assertTrue(crimson.placed.containsValue("betternether:nether_ruby_ore"),
                "原版 crimson_forest 通过 ruby_ore.json 拿到普通 ruby");
    }

    @Test
    void soulRubyNeedsSoulSoil() {
        FakeRegion soulPlain = new FakeRegion();
        soulPlain.biome = "betternether:soul_plain";
        soulPlain.ground = "minecraft:soul_soil";
        place(soulPlain, 8L, 0, 0);
        assertTrue(soulPlain.placed.containsValue("betternether:nether_ruby_soul_ore"),
                "soul_plain + soul soil 是最低条件");

        FakeRegion noSoulSoil = new FakeRegion();
        noSoulSoil.biome = "betternether:soul_plain";
        place(noSoulSoil, 8L, 0, 0);
        assertFalse(noSoulSoil.placed.containsValue("betternether:nether_ruby_soul_ore"),
                "target 是 block_match minecraft:soul_soil，换成 netherrack 就不该写");

        FakeRegion otherBiome = new FakeRegion();
        otherBiome.biome = RUBY_BIOME;
        otherBiome.ground = "minecraft:soul_soil";
        place(otherBiome, 8L, 0, 0);
        assertFalse(otherBiome.placed.containsValue("betternether:nether_ruby_soul_ore"),
                "soul 变体只属于 soul_plain");
    }

    // ------------------------------------------------------------------ target 与 discard

    @Test
    void onlyTheTargetBlockIsReplaced() {
        FakeRegion region = new FakeRegion();
        region.ground = "minecraft:blackstone";
        place(region, 99L, 0, 0);
        assertTrue(region.placed.isEmpty(),
                "target 是 block_match minecraft:netherrack：没有 netherrack 就一格都不该写");
    }

    @Test
    void unknownBlockIdentityIsTreatedAsNoMatch() {
        FakeRegion region = new FakeRegion();
        region.ground = null;
        place(region, 99L, 0, 0);
        assertTrue(region.placed.isEmpty(),
                "问不出方块身份时不能按宿主材质猜，只能不放");
    }

    @Test
    void discardChanceSkipsTheAirCheckOnlyWhenItShould() {
        assertTrue(OrePopulator.shouldSkipAirCheck(new Random(1L), 0.0f));
        assertFalse(OrePopulator.shouldSkipAirCheck(new Random(1L), 1.0f));
        for (long seed = 0; seed < 16; seed++) {
            Random probe = new Random(seed);
            Random reference = new Random(seed);
            boolean expected = reference.nextFloat() >= 0.3f;
            assertEquals(expected, OrePopulator.shouldSkipAirCheck(probe, 0.3f));
        }
    }

    @Test
    void discardOnlyConsumesRandomWhenTheChanceIsFractional() {
        Random probe = new Random(4321L);
        Random reference = new Random(4321L);
        OrePopulator.shouldSkipAirCheck(probe, 0.0f);
        OrePopulator.shouldSkipAirCheck(probe, 1.0f);
        assertEquals(reference.nextLong(), probe.nextLong(),
                "0 与 1 两个边界都不该消费 nextFloat");
    }

    @Test
    void airExposureDiscardsOnlyWhenTheOreSaysSo() {
        OreRule cincinnasite = rule("betternether:cincinnasite_ore");
        OreRule exposed = new OreRule("test:exposed", cincinnasite.targets(), cincinnasite.size(), 1.0f,
                cincinnasite.min(), cincinnasite.max(), cincinnasite.shape(), cincinnasite.placement(),
                cincinnasite.biomes(), false);

        FakeRegion skip = new FakeRegion();
        assertTrue(OrePopulator.canPlaceOre(cincinnasite, new Random(1L),
                4, TERRAIN_TOP - 1, 4, skip), "discard 0.0 时邻接空气也照放");

        FakeRegion reject = new FakeRegion();
        assertFalse(OrePopulator.canPlaceOre(exposed, new Random(1L),
                4, TERRAIN_TOP - 1, 4, reject), "discard 1.0 时邻接空气必须丢弃");
        assertTrue(reject.placed.isEmpty());
    }

    // ------------------------------------------------------------------ 高度图守卫

    @Test
    void veinsSampledAboveTheTerrainPlaceNothing() {
        // ruby 的带子是 32..223。地形只有 16 高时 OCEAN_FLOOR_WG 守卫会让 doPlace 一次都不跑；
        // 同一份数据在「整根柱子都是实心」下必须真的写出矿来，否则这个断言什么都证明不了。
        FakeRegion low = new FakeRegion();
        low.terrainTop = 16;
        OrePopulator.placeChunk(List.of(rule("betternether:nether_ruby_ore")),
                Set.of("betternether:nether_ruby_ore"), List.of(RUBY_BIOME),
                new Random(3L), 3L, 0, 0, null, low);
        assertTrue(low.placed.isEmpty(),
                "地形只有 16 高时，32 以上的 ruby 不该写任何方块");

        FakeRegion solid = new FakeRegion();
        solid.terrainTop = 256;
        OrePopulator.placeChunk(List.of(rule("betternether:nether_ruby_ore")),
                Set.of("betternether:nether_ruby_ore"), List.of(RUBY_BIOME),
                new Random(3L), 3L, 0, 0, null, solid);
        assertFalse(solid.placed.isEmpty(),
                "同一个种子在实心柱子里必须写得出 ruby，否则上一条断言毫无意义");
    }

    @Test
    void perChunkCountsStayUnderTheirUpstreamCeiling() {
        // 每次尝试最多写 2*size 个方块（见 oneVeinNeverWritesTheSameCoordinateTwice 的推导），
        // count 型就是 count 次尝试，rarity 型每区块至多 1 次。
        Map<String, Integer> ceilings = Map.of(
                "betternether:cincinnasite_ore", 10 * 2 * 8,
                "betternether:nether_lapis_ore", 14 * 2 * 4,
                "betternether:nether_redstone_ore", 1 * 2 * 16,
                "betternether:nether_ruby_rare_ore", 1 * 2 * 12,
                "betternether:nether_ruby_ore", 3 * 2 * 8,
                "betternether:nether_ruby_large_ore", 5 * 2 * 5,
                "betternether:nether_ruby_soul_ore", 5 * 2 * 5,
                "betternether:gloomwood_ceiling_debris", 1 * 2 * 2);
        for (int i = 0; i < 16; i++) {
            FakeRegion region = new FakeRegion();
            place(region, 77L, i, 0);
            for (Map.Entry<String, Integer> entry : region.countsByOre().entrySet()) {
                Integer ceiling = ceilings.get(entry.getKey());
                assertTrue(ceiling != null, "出现了规则表里没有的 placed feature：" + entry.getKey());
                assertTrue(entry.getValue() <= ceiling,
                        entry.getKey() + " 一区块写了 " + entry.getValue() + " 格，超过上游上限 " + ceiling);
            }
        }
    }

    @Test
    void smallerWorldBoundsPullTheBandsDown() {
        FakeRegion region = new FakeRegion();
        region.maxY = 127;
        region.terrainTop = 112;
        OrePopulator.placeChunk(rules(), ALL_PLACED, List.of(RUBY_BIOME), new Random(123L),
                123L, 0, 0, null, region);
        for (String key : region.placed.keySet()) {
            int y = y(key);
            assertTrue(y >= 0 && y <= 126, "世界只有 0..127 时不该写到界外，实际 y=" + y);
        }
    }

    // ------------------------------------------------------------------ Mth 的复刻

    @Test
    void sineTableMatchesTheVanillaLookup() {
        assertEquals(0.0f, OrePopulator.sin(0.0), 1e-7f);
        assertEquals(1.0f, OrePopulator.cos(0.0), 1e-4f);
        assertEquals(1.0f, OrePopulator.sin(Math.PI / 2), 1e-4f);
        assertEquals(0.0f, OrePopulator.cos(Math.PI / 2), 1e-4f);
        // 查表版与 Math.sin 最多差一个表步长（2π/65536 ≈ 9.6e-5）。
        for (double value = 0.0; value < 3.0; value += 0.37) {
            assertEquals(Math.sin(value), OrePopulator.sin(value), 2e-4);
        }
    }

    @Test
    void ceilAndLerpMatchVanilla() {
        assertEquals(2, OrePopulator.ceil(1.5f));
        assertEquals(1, OrePopulator.ceil(1.0f));
        assertEquals(0, OrePopulator.ceil(-0.5f));
        assertEquals(3.0, OrePopulator.lerp(0.5, 1.0, 5.0), 1e-9);
        assertEquals(1.0, OrePopulator.lerp(0.0, 1.0, 5.0), 1e-9);
        assertEquals(5.0, OrePopulator.lerp(1.0, 1.0, 5.0), 1e-9);
    }

    // ------------------------------------------------------------------ 工具

    /** {@code OreFeature#place} 里的 {@code i}：{@code ceil((size/16*2+1)/2)}。 */
    private static int boxRadius(int size) {
        return (int) Math.ceil((size / 16.0 * 2.0 + 1.0) / 2.0);
    }

    /** {@code ceil(size/8.0f)}：端点在外包盒里额外占的半宽。 */
    private static int extent(int size) {
        return (int) Math.ceil(size / 8.0f);
    }

    private static int y(String key) {
        return Integer.parseInt(key.split(",")[1]);
    }

    /**
     * 只做一件事的区域：记录被访问过的每个坐标的包围盒。
     *
     * <p>用来证明「一条矿团 + 邻接空气检查」到底伸到多远 —— 这是 LimitedRegion 越界风险的唯一
     * 可测形式，不能靠读公式代替。
     */
    private static final class FootprintRegion implements OrePopulator.OreRegion {
        private int minX;
        private int maxX;
        private int minY;
        private int maxY;
        private int minZ;
        private int maxZ;

        /**
         * 以探针原点起步：某条矿团一次都没碰到某个轴时（例如 size 2 的球半径进不了任何格子），
         * 该轴足迹读作 0，而不是 {@code Integer.MAX_VALUE} 那种伪值。
         */
        private FootprintRegion(int originX, int originY, int originZ) {
            this.minX = this.maxX = originX;
            this.minY = this.maxY = originY;
            this.minZ = this.maxZ = originZ;
        }

        /** 列查询（高度图）只约束 x/z：它按列取值，与矿团本身的 y 无关。 */
        private void touchColumn(int x, int z) {
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minZ = Math.min(minZ, z);
            maxZ = Math.max(maxZ, z);
        }

        private void touch(int x, int y, int z) {
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
            minZ = Math.min(minZ, z);
            maxZ = Math.max(maxZ, z);
        }

        @Override
        public int minY() {
            return NETHER_MIN_Y;
        }

        @Override
        public int maxY() {
            return NETHER_MAX_Y;
        }

        @Override
        public boolean inRegion(int x, int y, int z) {
            touch(x, y, z);
            return y >= NETHER_MIN_Y && y <= NETHER_MAX_Y;
        }

        @Override
        public int oceanFloorHeight(int x, int z) {
            touchColumn(x, z);
            return NETHER_MAX_Y;
        }

        @Override
        public int motionBlockingHeight(int x, int z) {
            touchColumn(x, z);
            return NETHER_MAX_Y;
        }

        /** 全实心：让 {@code isAdjacentToAir} 把六个邻居都读一遍，足迹取最大值。 */
        @Override
        public String blockId(int x, int y, int z) {
            touch(x, y, z);
            return "minecraft:netherrack";
        }

        @Override
        public boolean isAir(int x, int y, int z) {
            touch(x, y, z);
            return false;
        }

        @Override
        public WorldView worldView() {
            return null;
        }

        @Override
        public String biomeAt(int x, int y, int z) {
            touch(x, y, z);
            return "betternether:crimson_glowing_woods";
        }

        @Override
        public void write(int x, int y, int z, String placedFeatureId, int targetIndex) {
            touch(x, y, z);
        }
    }

    /**
     * 内存版区域：默认整根柱子都是实心地面（netherrack），地形顶面以上是空气。
     *
     * <p>刻意用方块<b>身份 id</b> 而不是 {@code Material} 作答 —— 这正是生产实现被要求做的事，
     * 测试也跟着同一条契约走，否则「按宿主材质猜测」这个错误在测试里看不见。
     */
    private static final class FakeRegion implements OrePopulator.OreRegion {
        private final Map<String, String> placed = new LinkedHashMap<>();
        private final List<String> writes = new ArrayList<>();
        private String biome = RUBY_BIOME;
        /** {@code null} = 问不出身份。 */
        private String ground = "minecraft:netherrack";
        private int terrainTop = TERRAIN_TOP;
        private int minY = NETHER_MIN_Y;
        private int maxY = NETHER_MAX_Y;
        /** {@code > minY} 时改用「solidBelow 以下实心 + solidRows 里这些层实心」的柱形。 */
        private int solidBelow = -1;
        private Set<Integer> solidRows = Set.of();
        /** {@code >= 0} 时覆盖 {@link #oceanFloorHeight} 的返回值（造洞穴用）。 */
        private int oceanFloor = -1;
        /** {@code >= 0} 时覆盖 {@link #motionBlockingHeight} 的返回值；缺省用 {@link #maxY}。 */
        private int motionTop = -1;
        /** 基岩层（{@code nowState.is(Blocks.BEDROCK)} 为真的那些 y）。 */
        private Set<Integer> bedrockRows = Set.of();
        /** 流体层（{@code state.liquid()} 为真的那些 y）。 */
        private Set<Integer> liquidRows = Set.of();
        /** 最近一次写入用的 target 下标，{@code -1} = 还没写过。 */
        private int lastTargetIndex = -1;

        private boolean solidAt(int y) {
            if (solidBelow >= 0) {
                return y < solidBelow || solidRows.contains(y);
            }
            return y < terrainTop;
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
        public boolean inRegion(int x, int y, int z) {
            return y >= minY && y <= maxY;
        }

        @Override
        public int oceanFloorHeight(int x, int z) {
            return oceanFloor >= 0 ? oceanFloor : terrainTop;
        }

        @Override
        public int motionBlockingHeight(int x, int z) {
            return motionTop >= 0 ? motionTop : maxY;
        }

        @Override
        public String blockId(int x, int y, int z) {
            if (!inRegion(x, y, z)) {
                return null;
            }
            String written = placed.get(key(x, y, z));
            if (written != null) {
                return written;
            }
            if (liquidRows.contains(y)) {
                return "minecraft:lava";
            }
            if (bedrockRows.contains(y)) {
                return "minecraft:bedrock";
            }
            return solidAt(y) ? ground : "minecraft:air";
        }

        @Override
        public boolean isAir(int x, int y, int z) {
            return !solidAt(y) && !liquidRows.contains(y) && !bedrockRows.contains(y)
                    && !placed.containsKey(key(x, y, z));
        }

        @Override
        public WorldView worldView() {
            return new WorldView() {
                @Override
                public boolean inRegion(int x, int y, int z) {
                    return FakeRegion.this.inRegion(x, y, z);
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
                    return FakeRegion.this.isAir(x, y, z);
                }

                @Override
                public BlockData blockAt(int x, int y, int z) {
                    return null;
                }

                @Override
                public void setBlock(int x, int y, int z, BlockData data) {
                    // 层扫描不写方块。
                }

                @Override
                public String biomeAt(int x, int y, int z) {
                    return biome;
                }

                @Override
                public String decoratedBiome() {
                    return biome;
                }

                @Override
                public Random random() {
                    return new Random(0L);
                }

                @Override
                public int motionBlockingHeight(int x, int z) {
                    return FakeRegion.this.motionBlockingHeight(x, z);
                }

                @Override
                public String blockId(int x, int y, int z) {
                    return FakeRegion.this.blockId(x, y, z);
                }

                @Override
                public Boolean liquidAt(int x, int y, int z) {
                    return liquidRows.contains(y);
                }
            };
        }

        @Override
        public String biomeAt(int x, int y, int z) {
            return biome;
        }

        @Override
        public void write(int x, int y, int z, String placedFeatureId, int targetIndex) {
            lastTargetIndex = targetIndex;
            writes.add(placedFeatureId + "#" + targetIndex);
            placed.put(key(x, y, z), placedFeatureId);
        }

        Map<String, Integer> countsByOre() {
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (String id : placed.values()) {
                counts.merge(id, 1, Integer::sum);
            }
            return counts;
        }

        /** Largest spread along one axis, in blocks. */
        int span(int axis) {
            Set<Integer> values = new LinkedHashSet<>();
            for (String key : placed.keySet()) {
                values.add(Integer.parseInt(key.split(",")[axis]));
            }
            int low = Integer.MAX_VALUE;
            int high = Integer.MIN_VALUE;
            for (int value : values) {
                low = Math.min(low, value);
                high = Math.max(high, value);
            }
            return values.isEmpty() ? 0 : high - low;
        }

        private static String key(int x, int y, int z) {
            return x + "," + y + "," + z;
        }
    }
}
