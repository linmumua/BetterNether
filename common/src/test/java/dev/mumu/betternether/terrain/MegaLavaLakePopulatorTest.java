package dev.mumu.betternether.terrain;

import dev.mumu.betternether.biome.BiomePlacement;
import dev.mumu.betternether.biome.TestClimatePlane;
import dev.mumu.betternether.worldgen.DecorationRandom;
import dev.mumu.betternether.terrain.MegaLavaLakePopulator.Decision;
import dev.mumu.betternether.terrain.MegaLavaLakePopulator.Kind;
import dev.mumu.betternether.terrain.MegaLavaLakePopulator.LakeParams;
import dev.mumu.betternether.terrain.MegaLavaLakePopulator.StartChunk;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Random;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The four failures this class exists to prevent, all of them invisible in game until someone
 * compares a map against upstream.
 *
 * <h2>1. Lakes on different chunks than upstream</h2>
 * The whole reason the structure JSON cannot be shipped is that its placement has to be reimplemented
 * by hand. If the reimplementation's seed math is off by one LCG step, lakes land on wrong chunks for
 * every seed and nobody can tell without the mod next to it. So the reference implementation here is
 * written from vanilla's algorithm -- a 48-bit LCG, {@code next(31)} and the power-of-two branch of
 * {@code nextInt} -- independently of the production code, which uses {@link java.util.Random}.
 *
 * <h2>2. A shape that depends on more than the seed</h2>
 * A lake's radius, depth and lobe layout have to be a pure function of {@code (worldSeed, startChunk)}:
 * that is what lets every chunk of the lake derive the same outline without asking its neighbours.
 *
 * <h2>3. Seams at chunk borders</h2>
 * A lake spans up to five chunks. Painting it through two adjacent chunk-sized regions must give
 * exactly the union of painting it in one pass -- not "the same lava", the same blocks, plant by
 * plant. This is the property that would silently rot if any read or any random draw ever reached
 * outside the column being painted.
 *
 * <h2>4. A lake where the gloomwood is not</h2>
 * Upstream's {@code #betternether:has_structure/mega_lava_lake} is {@code [betternether:gloomwood]},
 * asked at the lake's own middle at floor level. A lake in another biome is a biome-defining feature
 * in the wrong biome.
 *
 * <h2>Why no Bukkit registry</h2>
 * Reads and writes go through {@link MegaLavaLakePopulator.Sink}, expressed as {@link Kind} and
 * symbolic block ids, so nothing here needs a server, a {@code World} or a CraftEngine pack.
 */
class MegaLavaLakePopulatorTest {
    /** Vanilla's {@code RandomSpreadStructurePlacement} constants, from the upstream structure set. */
    private static final int SPACING = 24;
    private static final int SEPARATION = 8;
    private static final int SALT = 353194681;

    private static final long LCG_MULTIPLIER = 25214903917L;
    private static final long LCG_ADDEND = 11L;
    private static final long LCG_MASK = (1L << 48) - 1;

    /** The world seed the biome-gate tests use; fixed so the scanned start chunk is stable. */
    private static final long SEED = 4242L;

    private static final BiomePlacement PLACEMENT = new BiomePlacement(TestClimatePlane.seeded(SEED));

    /**
     * The step-4 registry slot the painting tests seed the shore stream from.
     *
     * <p>Any real slot works -- the tests only need a defined stream, not upstream's slot -- so this is an
     * arbitrary but explicit number rather than 0, because 0 belongs to {@code betternether:altars} and a
     * silent 0 would hide exactly the collapse-to-one-stream bug this parameter exists to prevent.
     */
    private static final int STRUCTURE_INDEX = 53;

    // ---------------------------------------------------------------- placement

    /**
     * The production placement agrees with an independent implementation of vanilla's algorithm.
     *
     * <p>The reference below encodes {@code RandomSpreadStructurePlacement.getPotentialStructureChunk}
     * literally: {@code setLargeFeatureWithSalt} builds
     * {@code regionX * 341873128712 + regionZ * 132897987541 + seed + salt} and seeds a legacy LCG
     * with it, then the linear spread draws {@code nextInt(spacing - separation)} twice. Bit-exact
     * agreement is the requirement, not "roughly the same chunks", because a lake that starts one
     * chunk off is a lake that is nowhere near where the mod puts it.
     */
    @Test
    void spreadMathMatchesVanillasAlgorithm() {
        long[] seeds = {0L, 1L, 424242L, -7L, 123456789012345L};
        for (long seed : seeds) {
            for (int regionX = -3; regionX <= 3; regionX++) {
                for (int regionZ = -3; regionZ <= 3; regionZ++) {
                    int[] expected = referenceStart(seed, regionX, regionZ);
                    StartChunk actual = MegaLavaLakePopulator.potentialStart(seed, regionX, regionZ);
                    assertEquals(expected[0], actual.x(),
                            "种子 " + seed + " 区域 (" + regionX + "," + regionZ + ") 的 x 与原始算法不符");
                    assertEquals(expected[1], actual.z(),
                            "种子 " + seed + " 区域 (" + regionX + "," + regionZ + ") 的 z 与原始算法不符");
                }
            }
        }
    }

    /**
     * Every region starts exactly one lake, inside its own region, and the offsets actually vary.
     *
     * <p>This is the other half of "the same chunks as upstream": even a wrong offset spread would
     * pass a "one lake per region" test, so the offsets are also asserted to be non-degenerate
     * (spanning most of {@code 0..15} over many regions) and to differ between seeds.
     */
    @Test
    void oneLakePerRegionInsideItsOwnRegion() {
        boolean[] seenOffsetX = new boolean[SPACING - SEPARATION];
        boolean[] seenOffsetZ = new boolean[SPACING - SEPARATION];
        for (int regionX = -16; regionX <= 16; regionX++) {
            for (int regionZ = -16; regionZ <= 16; regionZ++) {
                StartChunk start = MegaLavaLakePopulator.potentialStart(2024L, regionX, regionZ);
                int offsetX = start.x() - regionX * SPACING;
                int offsetZ = start.z() - regionZ * SPACING;
                assertTrue(offsetX >= 0 && offsetX < SPACING - SEPARATION,
                        "偏移 " + offsetX + " 超出 nextInt(" + (SPACING - SEPARATION) + ") 的范围");
                assertTrue(offsetZ >= 0 && offsetZ < SPACING - SEPARATION,
                        "偏移 " + offsetZ + " 超出 nextInt(" + (SPACING - SEPARATION) + ") 的范围");
                seenOffsetX[offsetX] = true;
                seenOffsetZ[offsetZ] = true;
            }
        }
        assertTrue(count(seenOffsetX) >= 12, "1089 个区域里 x 偏移只出现 " + count(seenOffsetX) + " 种，分布是坏的");
        assertTrue(count(seenOffsetZ) >= 12, "1089 个区域里 z 偏移只出现 " + count(seenOffsetZ) + " 种，分布是坏的");

        assertNotEquals(MegaLavaLakePopulator.potentialStart(1L, 0, 0),
                MegaLavaLakePopulator.potentialStart(2L, 0, 0),
                "换种子必须换起点，否则种子参数是假的");
    }

    /**
     * A chunk is offered exactly the lakes that could reach it, and no lake is offered twice.
     *
     * <p>Missing a candidate is a hole in a lake; the extra ones cost nothing but time. The bound is
     * checked against the geometry instead of the constant: the farthest column a lake can write is
     * its centre plus {@code reach}, so a candidate list that omitted a start chunk within
     * {@code reach} would drop blocks.
     */
    @Test
    void candidatesCoverEveryLakeThatCanReachTheChunk() {
        long seed = 987654321L;
        for (int chunkX = -20; chunkX <= 20; chunkX += 3) {
            for (int chunkZ = -20; chunkZ <= 20; chunkZ += 3) {
                Map<Long, LakeParams> candidates = new LinkedHashMap<>();
                for (LakeParams lake : MegaLavaLakePopulator.candidatesNear(seed, chunkX, chunkZ)) {
                    Long key = ((long) lake.startChunkX() << 32) ^ (lake.startChunkZ() & 0xFFFFFFFFL);
                    assertTrue(candidates.put(key, lake) == null,
                            "起点 (" + lake.startChunkX() + "," + lake.startChunkZ() + ") 被列了两次");
                }
                // Brute force the starts within the modelled reach and require them all.
                for (int startX = chunkX - MegaLavaLakePopulator.REACH_CHUNKS;
                     startX <= chunkX + MegaLavaLakePopulator.REACH_CHUNKS; startX++) {
                    for (int startZ = chunkZ - MegaLavaLakePopulator.REACH_CHUNKS;
                         startZ <= chunkZ + MegaLavaLakePopulator.REACH_CHUNKS; startZ++) {
                        if (!isStart(seed, startX, startZ)) {
                            continue;
                        }
                        Long key = ((long) startX << 32) ^ (startZ & 0xFFFFFFFFL);
                        assertTrue(candidates.containsKey(key),
                                "区块 (" + chunkX + "," + chunkZ + ") 漏掉了起点 (" + startX + "," + startZ + ")");
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- shape

    /**
     * Same seed and same start chunk give the identical lake; a different start gives a different one.
     *
     * <p>Radius, depth and lobe seed all come off one RNG stream, so a reordering of the three draws
     * would change every lake in the world while still "looking random" -- the identical-lake half of
     * this test is what pins the stream, and the different-lake half is what pins that it is a
     * function of the coordinates at all.
     */
    @Test
    void lakeShapeIsAFunctionOfSeedAndStartChunk() {
        long seed = 4242L;
        LakeParams first = MegaLavaLakePopulator.lakeAt(seed, 5, -3);
        LakeParams again = MegaLavaLakePopulator.lakeAt(seed, 5, -3);
        assertEquals(first, again, "同一 (种子, 起点) 必须得到同一组参数");

        assertTrue(first.radius() >= MegaLavaLakePopulator.MIN_RADIUS
                        && first.radius() <= MegaLavaLakePopulator.MAX_RADIUS,
                "半径 " + first.radius() + " 超出上游 10..18");
        assertTrue(first.depth() >= MegaLavaLakePopulator.MIN_DEPTH
                        && first.depth() <= MegaLavaLakePopulator.MAX_DEPTH,
                "深度 " + first.depth() + " 超出上游 3..6");
        assertEquals((5 << 4) + 8, first.centerX(), "中心必须是区块中点");
        assertEquals((-3 << 4) + 8, first.centerZ(), "中心必须是区块中点");

        assertNotEquals(first, MegaLavaLakePopulator.lakeAt(seed, 6, -3), "换起点必须换形状");
        assertNotEquals(first, MegaLavaLakePopulator.lakeAt(seed + 1, 5, -3), "换种子必须换形状");
    }

    /**
     * The baked lake is the same block set twice, and a different lake is not.
     *
     * <p>Driven through the real painter over a flat fake floor, which is what a lake is supposed to
     * handle: the bowl sinks into the floor, the rim ring comes out solid, and the shore is the
     * gloomwood's own three materials.
     */
    @Test
    void paintedLakeIsIdenticalForTheSameSeedAndDiffersForAnother() {
        long seed = 777L;
        LakeParams lake = MegaLavaLakePopulator.lakeAt(seed, 10, 10);
        int surfaceY = 60;

        Map<String, String> first = paintWholeLake(lake, surfaceY, seed, FlatFloor.at(surfaceY));
        Map<String, String> again = paintWholeLake(lake, surfaceY, seed, FlatFloor.at(surfaceY));
        assertEquals(first, again, "同一片湖画两遍必须逐格相同");

        assertFalse(first.isEmpty(), "湖必须真的写出了方块，否则下面的比较没有意义");
        assertTrue(first.containsValue("minecraft:lava"), "湖里必须有岩浆");

        Map<String, String> other = paintWholeLake(MegaLavaLakePopulator.lakeAt(seed, 11, 10), surfaceY, seed,
                FlatFloor.at(surfaceY));
        assertNotEquals(first, other, "换起点必须换出一片不同的湖");
    }

    /**
     * A lake is only as wide as upstream's bound: lava and shore stay inside the model's reach.
     *
     * <p>A footprint that grew without bound would make {@link MegaLavaLakePopulator#REACH_CHUNKS}
     * wrong, and chunks two away would silently lose the far side of their lakes.
     */
    @Test
    void nothingIsWrittenBeyondTheModelsReach() {
        long seed = 31337L;
        LakeParams lake = MegaLavaLakePopulator.lakeAt(seed, 0, 0);
        int surfaceY = 50;
        int reach = new MegaLavaLakePopulator.LakeShape(lake).reach();

        Map<String, String> painted = paintWholeLake(lake, surfaceY, seed, FlatFloor.at(surfaceY));
        for (String key : painted.keySet()) {
            int[] xyz = parse(key);
            assertTrue(Math.abs(xyz[0] - lake.centerX()) <= reach,
                    "x=" + xyz[0] + " 超出湖泊半径 " + reach);
            assertTrue(Math.abs(xyz[2] - lake.centerZ()) <= reach,
                    "z=" + xyz[2] + " 超出湖泊半径 " + reach);
            assertTrue(xyz[1] <= surfaceY + MegaLavaLakePopulator.HEADROOM,
                    "y=" + xyz[1] + " 高于湖面上方 " + MegaLavaLakePopulator.HEADROOM + " 格的净空");
        }
        assertTrue(reach < MegaLavaLakePopulator.REACH_CHUNKS * 16,
                "半径 " + reach + " 超过候选区块搜索范围 " + MegaLavaLakePopulator.REACH_CHUNKS + " 区块");
    }

    // ---------------------------------------------------------------- seams

    /**
     * Painting a lake chunk by chunk equals painting it in one pass -- the whole block set, not just
     * the terrain.
     *
     * <p>Each simulated chunk gets a region that can neither read nor write outside its own sixteen
     * columns, which is stricter than a real {@link org.bukkit.generator.LimitedRegion} (that one has
     * a buffer). If any read or any random draw ever depended on a neighbour column, this fails.
     */
    @Test
    void chunkByChunkPaintEqualsTheSinglePass() {
        long seed = 20240607L;
        int surfaceY = 64;
        // A lake that certainly straddles a chunk border: its centre is the middle of chunk 0, and
        // its reach is over two chunks, so chunks -2..2 all carry part of it.
        LakeParams lake = MegaLavaLakePopulator.lakeAt(seed, 0, 0);
        int reach = new MegaLavaLakePopulator.LakeShape(lake).reach();
        assertTrue(reach > 16, "测试前提：湖泊必须跨区块，实际半径 " + reach);

        Map<String, String> whole = paintWholeLake(lake, surfaceY, seed, FlatFloor.at(surfaceY));

        Map<String, String> stitched = new TreeMap<>();
        int firstChunkX = (lake.centerX() - reach) >> 4;
        int lastChunkX = (lake.centerX() + reach) >> 4;
        int firstChunkZ = (lake.centerZ() - reach) >> 4;
        int lastChunkZ = (lake.centerZ() + reach) >> 4;
        for (int chunkX = firstChunkX; chunkX <= lastChunkX; chunkX++) {
            for (int chunkZ = firstChunkZ; chunkZ <= lastChunkZ; chunkZ++) {
                FakeRegion region = new FakeRegion(FlatFloor.at(surfaceY), chunkX, chunkZ);
                MegaLavaLakePopulator.paint(lake, surfaceY, chunkX, chunkZ, region, seed, STRUCTURE_INDEX);
                stitched.putAll(region.written);
            }
        }
        assertEquals(whole, stitched, "逐区块拼接必须与一次性绘制完全相同（含湖岸植被）");
    }

    // ---------------------------------------------------------------- chunk order

    /**
     * <b>The regression this class exists for:</b> the same world populated in any chunk order gives
     * the same lake.
     *
     * <p>An earlier revision had the lake's start chunk compute the water level and hand it to its
     * neighbours through a cache. That made the result depend on which chunk the player reached
     * first: a neighbour generated before the start chunk found no decision, skipped, and left a
     * permanent notch in the lake -- and a restart emptied the cache and did the same. The fix is
     * that the level is read from the vanilla base column, which no chunk generation can change, so
     * every chunk decides for itself.
     *
     * <p>This drives the real per-chunk entry point ({@link MegaLavaLakePopulator#populateChunk}) over
     * every chunk the lake reaches -- decision and painting both -- in two opposite orders, and
     * demands the identical block set. It also demands the lake is <em>not empty</em>: two empty
     * results would be equal and prove nothing.
     */
    @Test
    void populateChunkIsIndependentOfChunkOrder() {
        int surfaceY = 62;
        StartChunk start = findLakeStartInBiome(PLACEMENT, "gloomwood");
        LakeParams lake = MegaLavaLakePopulator.lakeAt(SEED, start.x(), start.z());
        Terrain terrain = FlatFloor.at(surfaceY);
        int reach = new MegaLavaLakePopulator.LakeShape(lake).reach();
        int firstChunkX = (lake.centerX() - reach) >> 4;
        int lastChunkX = (lake.centerX() + reach) >> 4;
        int firstChunkZ = (lake.centerZ() - reach) >> 4;
        int lastChunkZ = (lake.centerZ() + reach) >> 4;

        Map<String, String> forward = populateInOrder(terrain, firstChunkX, lastChunkX, firstChunkZ,
                lastChunkZ, false);
        Map<String, String> backward = populateInOrder(terrain, firstChunkX, lastChunkX, firstChunkZ,
                lastChunkZ, true);

        assertFalse(forward.isEmpty(), "该种子/区块必须真的生成一片湖，否则下面的比较没有意义");
        assertEquals(forward, backward, "区块生成顺序不得改变湖的任何一格（缓存/首到先决定已被移除）");

        // And it must be the same lake the painter alone would produce for that level.
        Map<String, String> singlePass = paintWholeLake(lake, surfaceY, SEED, terrain);
        assertEquals(singlePass, forward, "逐区块入口必须与一次性绘制完全一致");
    }

    /**
     * A chunk that is <em>not</em> the lake's start chunk paints its slice on its own.
     *
     * <p>This is the cut-down version of the test above, and it is the one that fails loudest if a
     * decision ever creeps back into "only the owner may answer": a single neighbour, given nothing
     * but the seed and its own coordinates, must reproduce the same blocks the whole-lake pass put in
     * that chunk.
     */
    @Test
    void aNeighbourChunkDecidesTheSameLakeByItself() {
        int surfaceY = 62;
        StartChunk start = findLakeStartInBiome(PLACEMENT, "gloomwood");
        LakeParams lake = MegaLavaLakePopulator.lakeAt(SEED, start.x(), start.z());
        Terrain terrain = FlatFloor.at(surfaceY);

        // Group the whole-lake pass by chunk, then pick a chunk that is NOT the start chunk and
        // really carries part of the lake. Guessing a chunk index by geometry alone would let this
        // test pass with an empty region (the lake's outer reach holds no painted columns).
        Map<String, String> whole = paintWholeLake(lake, surfaceY, SEED, terrain);
        Map<String, Map<String, String>> byChunk = new TreeMap<>();
        for (Map.Entry<String, String> entry : whole.entrySet()) {
            int[] xyz = parse(entry.getKey());
            String chunkKey = (xyz[0] >> 4) + "," + (xyz[2] >> 4);
            byChunk.computeIfAbsent(chunkKey, ignored -> new TreeMap<>()).put(entry.getKey(),
                    entry.getValue());
        }
        String startKey = start.x() + "," + start.z();
        String neighbourKey = null;
        for (Map.Entry<String, Map<String, String>> entry : byChunk.entrySet()) {
            if (!entry.getKey().equals(startKey) && !entry.getValue().isEmpty()) {
                neighbourKey = entry.getKey();
                break;
            }
        }
        assertNotNull(neighbourKey, "测试前提：这片湖必须跨到起点区块之外");

        String[] parts = neighbourKey.split(",");
        int neighbourX = Integer.parseInt(parts[0]);
        int neighbourZ = Integer.parseInt(parts[1]);
        FakeRegion region = new FakeRegion(terrain, neighbourX, neighbourZ);
        MegaLavaLakePopulator.populateChunk(SEED, new FakeColumns(terrain), neighbourX, neighbourZ,
                PLACEMENT, region, STRUCTURE_INDEX);

        assertEquals(byChunk.get(neighbourKey), new TreeMap<>(region.written),
                "邻居区块仅凭 (种子, 自身坐标) 独立生成的方块必须与整片湖在该区块的结果逐格相同");
    }

    /**
     * The rim drop test now sees all eight probes, including ones far outside any chunk region.
     *
     * <p>The base column answers for <em>any</em> coordinates, so a lake ringed by a chasm is rejected
     * no matter where the populator happens to run. A region-based revision could only test the
     * probes inside its buffer and treated the rest as "no drop", which accepted lakes hanging over
     * cliffs.
     */
    @Test
    void rimDropIsTestedOutsideTheChunkRegionToo() {
        int surfaceY = 62;
        StartChunk start = findLakeStartInBiome(PLACEMENT, "gloomwood");
        LakeParams lake = MegaLavaLakePopulator.lakeAt(SEED, start.x(), start.z());
        int radius = (int) (lake.radius() * MegaLavaLakePopulator.RIM_PROBE_REACH);
        int chasmX = lake.centerX() + radius;
        int probeY = surfaceY - MegaLavaLakePopulator.MAX_RIM_DROP;
        assertTrue(Math.abs((chasmX >> 4) - start.x()) >= 1, "测试前提：裂谷必须在起点区块之外");

        Terrain chasm = (x, y, z) -> {
            if (x == chasmX && y > probeY - 4) {
                return Kind.AIR;
            }
            return y <= surfaceY - 1 ? Kind.GROUND : Kind.AIR;
        };
        // Already-open air above the chasm column, solid everywhere else.
        FakeColumns columns = new FakeColumns(chasm);
        MegaLavaLakePopulator.Decision decision = MegaLavaLakePopulator.probe(lake, PLACEMENT, columns,
                108, 8);
        assertFalse(decision.accepted(), "区块外的边缘探针也必须被检测到，否则湖会架在悬崖上");
    }

    /** Populates every chunk of a rectangle, in ascending or descending order, into one world. */
    private static Map<String, String> populateInOrder(Terrain terrain, int firstChunkX, int lastChunkX,
                                                       int firstChunkZ, int lastChunkZ, boolean reverse) {
        Map<String, String> all = new TreeMap<>();
        FakeColumns columns = new FakeColumns(terrain);
        int stepX = reverse ? -1 : 1;
        int startX = reverse ? lastChunkX : firstChunkX;
        int endX = reverse ? firstChunkX - 1 : lastChunkX + 1;
        int stepZ = reverse ? -1 : 1;
        int startZ = reverse ? lastChunkZ : firstChunkZ;
        int endZ = reverse ? firstChunkZ - 1 : lastChunkZ + 1;
        for (int chunkX = startX; chunkX != endX; chunkX += stepX) {
            for (int chunkZ = startZ; chunkZ != endZ; chunkZ += stepZ) {
                FakeRegion region = new FakeRegion(terrain, chunkX, chunkZ);
                MegaLavaLakePopulator.populateChunk(SEED, columns, chunkX, chunkZ, PLACEMENT, region,
                        STRUCTURE_INDEX);
                all.putAll(region.written);
            }
        }
        return all;
    }

    // ---------------------------------------------------------------- placement decision

    /**
     * The floor search finds the first air-over-solid layer below the roof, not the roof itself.
     *
     * <p>This is the bug upstream's class comment warns about: {@code WORLD_SURFACE_WG} in the
     * Nether means the bedrock roof, so a heightmap-based search would put every lake on top of the
     * world. The column here has a floor, a mid shelf and rock up to the ceiling, so the search must
     * return the top shelf.
     */
    @Test
    void floorSearchReturnsTheTopmostSolidBlockUnderAir() {
        int topShelf = 92;
        int found = MegaLavaLakePopulator.findSurfaceY(
                new FakeColumns((x, y, z) -> y <= topShelf ? Kind.GROUND : Kind.AIR).at(8, 8),
                108, MegaLavaLakePopulator.SEARCH_STOP_ABOVE_BOTTOM,
                MegaLavaLakePopulator.REQUIRED_HEADROOM);
        assertEquals(topShelf, found,
                "wover 的 findYDownward 返回『实心且其上方是空气』的那一格实心块，不是上方空气格");

        // Headroom probe at found + 5: a ceiling four blocks up makes it solid, and the search must
        // fail outright rather than walk on to a deeper ledge (wover breaks at the first match).
        FakeColumns capped = new FakeColumns((x, y, z) -> {
            if (y <= topShelf) {
                return Kind.GROUND;
            }
            return y <= topShelf + 4 ? Kind.AIR : Kind.GROUND;
        });
        assertEquals(Integer.MIN_VALUE, MegaLavaLakePopulator.findSurfaceY(capped.at(8, 8),
                        108, MegaLavaLakePopulator.SEARCH_STOP_ABOVE_BOTTOM,
                        MegaLavaLakePopulator.REQUIRED_HEADROOM),
                "净空探测失败必须直接失败，不得继续向下寻找更深的台阶");
    }

    /**
     * A lake generates only where the gloomwood covers its middle at floor level.
     *
     * <p>The biome gate is upstream's {@code #betternether:has_structure/mega_lava_lake}, whose only
     * entry is {@code betternether:gloomwood}. The test finds a start chunk whose middle really is
     * gloomwood from the placement field rather than assuming one, so it cannot start passing for the
     * wrong reason if the field is retuned.
     */
    @Test
    void lakeIsRejectedOutsideTheGloomwood() {
        int surfaceY = 60;
        StartChunk gloomwood = findLakeStartInBiome(PLACEMENT, "gloomwood");
        LakeParams onGloomwood = MegaLavaLakePopulator.lakeAt(SEED, gloomwood.x(), gloomwood.z());
        Decision accepted = MegaLavaLakePopulator.probe(onGloomwood, PLACEMENT,
                new FakeColumns(FlatFloor.at(surfaceY)), 108, 8);
        assertTrue(accepted.accepted(), "幽暗林地表上必须能生成湖泊");
        assertEquals(surfaceY, accepted.surfaceY(), "湖面应落在假地形的顶面");

        StartChunk other = findLakeStartInBiome(PLACEMENT, "wart_forest");
        LakeParams onOther = MegaLavaLakePopulator.lakeAt(SEED, other.x(), other.z());
        assertFalse(MegaLavaLakePopulator.probe(onOther, PLACEMENT,
                        new FakeColumns(FlatFloor.at(surfaceY)), 108, 8).accepted(),
                "非幽暗林群系不该生成湖泊（上游 tag 只有 gloomwood）");
    }

    /**
     * A rim column that is open well below the lava level rejects the placement.
     *
     * <p>The drop test is what stops a lake from pouring out of one side over a cliff. Upstream reads
     * eight points at {@code radius * 1.3} and rejects when any of them is air at {@code surfaceY - 8}.
     *
     * <p>An unlimited region is used on purpose: the probes sit 13..23 blocks out, which is beyond the
     * current chunk, so with a real {@link org.bukkit.generator.LimitedRegion} they would only be
     * answered when the buffer reached that far. This test pins the logic as written; what the buffer
     * does to it in game is the deviation the class javadoc documents.
     */
    @Test
    void rimDropRejectsThePlacement() {
        int surfaceY = 60;
        StartChunk gloomwood = findLakeStartInBiome(PLACEMENT, "gloomwood");
        LakeParams lake = MegaLavaLakePopulator.lakeAt(SEED, gloomwood.x(), gloomwood.z());

        assertTrue(MegaLavaLakePopulator.probe(lake, PLACEMENT, new FakeColumns(FlatFloor.at(surfaceY)),
                108, 8).accepted(), "四面都是实地时必须接受");

        int radius = (int) (lake.radius() * MegaLavaLakePopulator.RIM_PROBE_REACH);
        int probeY = surfaceY - MegaLavaLakePopulator.MAX_RIM_DROP;
        int chasmX = lake.centerX() + radius;
        FakeColumns chasm = new FakeColumns((x, y, z) -> {
            // One column of the rim ring is open down through the lava level: a cliff under the edge.
            if (x == chasmX && y > probeY - 4) {
                return Kind.AIR;
            }
            return y <= surfaceY ? Kind.GROUND : Kind.AIR;
        });
        assertFalse(MegaLavaLakePopulator.probe(lake, PLACEMENT, chasm, 108, 8).accepted(),
                "边缘下方 " + MegaLavaLakePopulator.MAX_RIM_DROP + " 格是空的，必须拒绝");
    }

    // ---------------------------------------------------------------- the piece

    /**
     * The bowl is lava over a magma bed, and the rock under the bed is sealed where the bowl cut into
     * open air.
     *
     * <p>Without the shell the lake drains through a cave the moment the player opens it; without the
     * bed it is a hole. Both are asserted at the lake's middle, where the bowl is deepest.
     */
    @Test
    void bowlIsLavaOverAMagmaBedWithASealedBottom() {
        long seed = 555L;
        LakeParams lake = MegaLavaLakePopulator.lakeAt(seed, 0, 0);
        int surfaceY = 60;
        int depth = lake.depth();

        // Solid floor with a cave three blocks under where the bed will be.
        int below = surfaceY - depth;
        FakeRegion region = new FakeRegion((x, y, z) -> {
            if (y <= below - 1 && y >= below - 6) {
                return Kind.AIR;
            }
            return y <= surfaceY ? Kind.GROUND : Kind.AIR;
        }, 0, 0);
        MegaLavaLakePopulator.paint(lake, surfaceY, lake.centerX() >> 4, lake.centerZ() >> 4, region, seed,
                STRUCTURE_INDEX);

        String middle = key(lake.centerX(), surfaceY, lake.centerZ());
        assertEquals("minecraft:lava", region.written.get(middle), "湖心水面必须是岩浆");
        assertEquals("minecraft:magma_block", region.written.get(key(lake.centerX(), below, lake.centerZ())),
                "湖心湖底必须是岩浆块");

        int sealed = 0;
        for (int y = below - 1; y >= below - MegaLavaLakePopulator.BED_SHELL; y--) {
            if ("minecraft:netherrack".equals(region.written.get(key(lake.centerX(), y, lake.centerZ())))) {
                sealed++;
            }
        }
        assertTrue(sealed == MegaLavaLakePopulator.BED_SHELL,
                "湖底下方 " + MegaLavaLakePopulator.BED_SHELL + " 格必须被地狱岩封住，实际 " + sealed + " 格");
    }

    /**
     * Standing rock is left alone: a column with more than {@link MegaLavaLakePopulator#HEADROOM}
     * blocks of rock over the lava level gets no carve, no lava and no shore.
     *
     * <p>These are the spurs that reach up to the cavern roof. Shaving their bottom would leave the
     * rest of them hanging in the air, so upstream skips the whole column -- and that is also the only
     * thing that puts islands in a lake.
     */
    @Test
    void standingRockIsNeverTouched() {
        long seed = 606L;
        LakeParams lake = MegaLavaLakePopulator.lakeAt(seed, 0, 0);
        int surfaceY = 60;
        int wallX = lake.centerX() + 2;

        FakeRegion region = new FakeRegion((x, y, z) -> {
            if (x == wallX && y > surfaceY) {
                return Kind.GROUND;
            }
            return y <= surfaceY ? Kind.GROUND : Kind.AIR;
        }, 0, 0);
        MegaLavaLakePopulator.paint(lake, surfaceY, lake.centerX() >> 4, lake.centerZ() >> 4, region, seed,
                STRUCTURE_INDEX);

        for (String written : region.written.keySet()) {
            int[] xyz = parse(written);
            assertFalse(xyz[0] == wallX, "石柱列 " + wallX + " 被改写了：" + written);
        }
    }

    /**
     * The shore is the gloomwood's own three floor blocks and nothing else.
     *
     * <p>A shore of imported materials would read as a different biome's border. The three are the
     * bleached gloomsculk, vanilla sculk and molten gloomsculk -- and the molten one has to stay a
     * minority, which is what {@code MOLTEN_AT_WATER}/{@code MOLTEN_FADE} were measured for.
     */
    @Test
    void shoreUsesTheGloomwoodFloorMaterials() {
        long seed = 909L;
        LakeParams lake = MegaLavaLakePopulator.lakeAt(seed, 0, 0);
        int surfaceY = 60;
        Map<String, String> painted = paintWholeLake(lake, surfaceY, seed, FlatFloor.at(surfaceY));

        int skin = 0;
        int molten = 0;
        for (Map.Entry<String, String> entry : painted.entrySet()) {
            String id = bare(entry.getValue());
            if (id.equals("minecraft:sculk") || id.equals("betternether:bleached_gloomsculk")
                    || id.equals("betternether:molten_gloomsculk")) {
                skin++;
                if (id.equals("betternether:molten_gloomsculk")) {
                    molten++;
                }
            }
            assertTrue(id.equals("minecraft:lava") || id.equals("minecraft:magma_block")
                            || id.equals("minecraft:netherrack") || id.equals("minecraft:cave_air")
                            || id.equals("minecraft:sculk") || id.equals("betternether:bleached_gloomsculk")
                            || id.equals("betternether:molten_gloomsculk")
                            || id.equals("betternether:veined_gloomsculk")
                            || id.equals("betternether:gloomgrass")
                            || id.equals("betternether:pale_gloomgrass")
                            || id.equals("betternether:magma_flower")
                            || id.equals("betternether:gloomwisp_vine"),
                    "湖写入了上游不会用的方块：" + id);
        }
        assertTrue(skin > 0, "湖岸必须有皮肤方块");
        assertTrue(molten * 2 < skin, "熔融幽暗幽匿必须占少数，实际 " + molten + "/" + skin);
    }

    // ---------------------------------------------------------------- random stream

    /**
     * The decoration seed is the real NMS one -- delegated, not re-derived.
     *
     * <p>It is produced by drawing two {@code nextLong}s from an <em>Xoroshiro</em> source, so a 48-bit
     * LCG reimplementation gets a different number while looking plausible. The anchors below come from
     * the shared {@code DecorationRandom}, whose values were printed by a program calling the actual
     * {@code WorldgenRandom}/{@code XoroshiroRandomSource} in {@code mucore-26.3.jar} (252 cases matched;
     * see {@code DecorationRandomTest}). Pinning the same constant here means a hand-rolled seed in this
     * class fails loudly instead of quietly shifting every shore draw.
     */
    @Test
    void decorationSeedIsTheNmsOneNotAnLcgReimplementation() {
        long worldSeed = 123456789012345L;
        int chunkX = -37;
        int chunkZ = 91;
        assertEquals(-3230959737316046215L,
                MegaLavaLakePopulator.decorationSeed(worldSeed, chunkX << 4, chunkZ << 4),
                "decorationSeed 必须等于真实 NMS setDecorationSeed 的返回值");

        long[] seeds = {0L, 1L, 4242L, -9L};
        for (long seed : seeds) {
            for (int cx = -2; cx <= 2; cx++) {
                for (int cz = -2; cz <= 2; cz++) {
                    assertEquals(DecorationRandom.decorationSeed(seed, cx << 4, cz << 4),
                            MegaLavaLakePopulator.decorationSeed(seed, cx << 4, cz << 4),
                            "必须委托共享的 NMS 等价实现，不得自写");
                }
            }
        }
    }

    /**
     * The stalk carries upstream's shape gradient and a direction derived from its own column.
     *
     * <p>Upstream writes {@code TOP} for the head, {@code MIDDLE} directly under it and {@code BOTTOM}
     * below, and leaves the direction to its {@code random} property. CraftEngine cannot resolve
     * {@code random} per cell (the appearance owner's registration note), so this port derives it from
     * {@code (x ^ z)} -- deterministic, seam-free, and different from one stalk to the next. The test
     * asserts the shape <em>order</em> per column, not just that a stalk exists, because a stalk whose
     * head is {@code bottom} would be a silently wrong model rather than a missing block.
     */
    @Test
    void wispSegmentsCarryShapeGradientAndNoFabricatedDirection() {
        long seed = 4711L;
        LakeParams lake = MegaLavaLakePopulator.lakeAt(seed, 0, 0);
        int surfaceY = 58;
        Map<String, String> painted = paintWholeLake(lake, surfaceY, seed, FlatFloor.at(surfaceY));

        Map<String, Map<Integer, String>> stalks = new TreeMap<>();
        for (Map.Entry<String, String> entry : painted.entrySet()) {
            String id = entry.getValue();
            if (!id.startsWith("betternether:gloomwisp_vine[") && !id.equals("betternether:gloomwisp_vine")) {
                continue;
            }
            int[] xyz = parse(entry.getKey());
            stalks.computeIfAbsent(xyz[0] + "," + xyz[2], ignored -> new TreeMap<>())
                    .put(xyz[1], id);
        }
        assertFalse(stalks.isEmpty(), "整片湖至少应长出一根幽暗微光藤，否则这条断言没有意义");

        for (Map.Entry<String, Map<Integer, String>> stalk : stalks.entrySet()) {
            List<String> ids = new ArrayList<>(stalk.getValue().values());
            for (int i = 0; i < ids.size(); i++) {
                String id = ids.get(i);
                assertFalse(id.contains("vine_dir="),
                        "不得写入任何自造朝向：上游把 rotation 留成 random 由客户端按位置选取，"
                                + "服务端 jar 没有 client 类可复现（实际 " + id + "）");
                if (id.equals("betternether:gloomwisp_vine")) {
                    continue;   // the properties did not resolve in this test's empty pack
                }
                String expectedShape = i == ids.size() - 1 ? "shape=top"
                        : i == ids.size() - 2 ? "shape=middle" : "shape=bottom";
                assertTrue(id.contains(expectedShape),
                        "第 " + i + "/" + ids.size() + " 段应为 " + expectedShape + "，实际 " + id);
            }
        }
    }

    // ---------------------------------------------------------------- structure-phase stream

    /**
     * The shore stream is {@code setFeatureSeed(decorationSeed, index, 4)} on the <b>Xoroshiro</b> source.
     *
     * <p>Two things are pinned, both falsifiable:
     * <ul>
     *   <li>the seed arithmetic is vanilla's additive one ({@code deco + index + 10000*step}), checked
     *       against the shared NMS-equivalent {@link DecorationRandom};</li>
     *   <li>the <em>stream</em> is the Xoroshiro decoration stream, not a 48-bit LCG: the test prints the
     *       first draws and compares them with {@code DecorationRandom.forFeature}, then asserts that a
     *       {@code java.util.Random} seeded with the same number produces a <em>different</em> sequence.
     *       That second half is what a revert to {@code new Random(...)} would trip.</li>
     * </ul>
     */
    @Test
    void shoreStreamIsTheXoroshiroDecorationStream() {
        long[] seeds = {0L, 1L, 4242L, -9L};
        int[] indices = {0, 4, 53};
        for (long seed : seeds) {
            for (int chunkX = -2; chunkX <= 2; chunkX++) {
                for (int chunkZ = -2; chunkZ <= 2; chunkZ++) {
                    long deco = DecorationRandom.decorationSeed(seed, chunkX << 4, chunkZ << 4);
                    for (int index : indices) {
                        long phaseSeed = deco + index + 10000L * 4L;
                        assertEquals(phaseSeed,
                                MegaLavaLakePopulator.structurePhaseSeed(seed, chunkX, chunkZ, index),
                                "结构相位种子必须等于 deco + index + 10000*4");

                        String expected = draws(DecorationRandom.forFeature(deco, index, 4));
                        assertEquals(expected, draws(MegaLavaLakePopulator.shoreRandom(seed, chunkX, chunkZ,
                                        index)),
                                "岸流必须是真实 NMS 的 Xoroshiro 装饰流");
                        assertNotEquals(expected, draws(new java.util.Random(phaseSeed)),
                                "岸流不得退化成 48 位 LCG：那正是本轮修正的错");
                    }
                }
            }
        }
        assertNotEquals(MegaLavaLakePopulator.structurePhaseSeed(4242L, 0, 0, 0),
                MegaLavaLakePopulator.structurePhaseSeed(4242L, 0, 0, 9),
                "不同注册表槽位必须给出不同的流");
    }

    /** Five bounded draws, the same shape the shared DecorationRandomTest pins against NMS. */
    private static String draws(Random random) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(random.nextInt(1000));
        }
        return builder.toString();
    }

    /**
     * Without a step-4 slot nothing that draws is painted -- no vegetation, and no outer shore band.
     *
     * <p>The lake is not a vanilla structure, so on a runtime where the decoration session cannot hand
     * back a registry slot there is no honest stream to draw from. The port then paints only what needs no
     * draw (bowl, lava, bed, apron) rather than seeding a stream from an invented index. This test is the
     * falsifiable half of that claim: if someone quietly re-adds a made-up seed, plants reappear and this
     * fails.
     */
    @Test
    void withoutASlotOnlyTheDeterministicPartsArePainted() {
        long seed = 909L;
        LakeParams lake = MegaLavaLakePopulator.lakeAt(seed, 0, 0);
        int surfaceY = 60;
        Terrain terrain = FlatFloor.at(surfaceY);

        FakeRegion region = new FakeRegion(terrain, Integer.MIN_VALUE, Integer.MIN_VALUE);
        int reach = new MegaLavaLakePopulator.LakeShape(lake).reach();
        for (int chunkX = (lake.centerX() - reach) >> 4; chunkX <= (lake.centerX() + reach) >> 4; chunkX++) {
            for (int chunkZ = (lake.centerZ() - reach) >> 4;
                 chunkZ <= (lake.centerZ() + reach) >> 4; chunkZ++) {
                MegaLavaLakePopulator.paint(lake, surfaceY, chunkX, chunkZ, region, seed, null);
            }
        }

        Map<String, String> painted = new TreeMap<>(region.written);
        assertTrue(painted.containsValue("minecraft:lava"), "确定性部分（岩浆）必须照常生成");
        for (Map.Entry<String, String> entry : painted.entrySet()) {
            String bare = bare(entry.getValue());
            assertFalse(bare.equals("betternether:gloomgrass") || bare.equals("betternether:pale_gloomgrass")
                            || bare.equals("betternether:magma_flower")
                            || bare.equals("betternether:gloomwisp_vine"),
                    "没有注册表槽位时不得生成任何依赖随机流的装饰：" + entry.getKey() + " = "
                            + entry.getValue());
        }
    }

    // ---------------------------------------------------------------- structure-context seed

    /**
     * The structure context random is {@code setLargeFeatureSeed}, not {@code setLargeFeatureWithSalt}.
     *
     * <p>Vanilla has two similarly named primitives and they are not interchangeable:
     * <ul>
     *   <li>{@code setLargeFeatureWithSalt(seed, regionX, regionZ, salt)} = plain
     *       {@code regionX*341873128712 + regionZ*132897987541 + seed + salt} -- what
     *       {@code RandomSpreadStructurePlacement#getPotentialStructureChunk} uses to pick a lake's start
     *       chunk ({@link MegaLavaLakePopulator#potentialStart});</li>
     *   <li>{@code setLargeFeatureSeed(seed, chunkX, chunkZ)} = two {@code nextLong}s, then
     *       {@code (chunkX*a) ^ (chunkZ*b) ^ seed} -- what {@code Structure.GenerationContext#makeRandom}
     *       uses for the random {@code findGenerationPoint} draws shape parameters from.</li>
     * </ul>
     * The reference below is an independent 48-bit-LCG implementation of the second one (the source really
     * is {@code LegacyRandomSource}, bytecode-checked), and the last assertion is the one that matters: the
     * additive form must <em>not</em> match, because using it here compiles, runs, and silently shapes every
     * lake from a different stream.
     */
    @Test
    void structureContextSeedIsSetLargeFeatureSeedNotTheSaltVariant() {
        long[] seeds = {0L, 1L, 4242L, -7L, 123456789012345L};
        for (long seed : seeds) {
            for (int chunkX = -3; chunkX <= 3; chunkX++) {
                for (int chunkZ = -3; chunkZ <= 3; chunkZ++) {
                    long expected = referenceLargeFeatureSeed(seed, chunkX, chunkZ);
                    long actual = MegaLavaLakePopulator.largeFeatureSeed(seed, chunkX, chunkZ);
                    assertEquals(expected, actual, "种子 " + seed + " 区块 (" + chunkX + "," + chunkZ
                            + ") 的 setLargeFeatureSeed 不符");

                    long additive = (long) chunkX * 341873128712L + (long) chunkZ * 132897987541L + seed;
                    if (chunkX != 0 || chunkZ != 0) {
                        assertNotEquals(additive, actual,
                                "把 setLargeFeatureWithSalt 的加性公式当成 setLargeFeatureSeed 会得到不同的流");
                    }
                }
            }
        }
    }

    /**
     * Independent {@code setLargeFeatureSeed}: {@code a}/{@code b} from two {@code nextLong}s (high half
     * sign-extended, as {@code java.util.Random} does), then XOR with the products.
     */
    private static long referenceLargeFeatureSeed(long worldSeed, int chunkX, int chunkZ) {
        long s0 = (worldSeed ^ LCG_MULTIPLIER) & LCG_MASK;
        long s1 = advance(s0);
        long s2 = advance(s1);
        long a = ((long) (int) (s1 >>> 16) << 32) + (int) (s2 >>> 16);
        long s3 = advance(s2);
        long s4 = advance(s3);
        long b = ((long) (int) (s3 >>> 16) << 32) + (int) (s4 >>> 16);
        return ((long) chunkX * a) ^ ((long) chunkZ * b) ^ worldSeed;
    }

    // ---------------------------------------------------------------- helpers

    /** A lake painted over every chunk it reaches, into one unlimited region. */
    private static Map<String, String> paintWholeLake(LakeParams lake, int surfaceY, long seed,
                                                      Terrain terrain) {
        FakeRegion region = new FakeRegion(terrain, Integer.MIN_VALUE, Integer.MIN_VALUE);
        int reach = new MegaLavaLakePopulator.LakeShape(lake).reach();
        for (int chunkX = (lake.centerX() - reach) >> 4; chunkX <= (lake.centerX() + reach) >> 4; chunkX++) {
            for (int chunkZ = (lake.centerZ() - reach) >> 4;
                 chunkZ <= (lake.centerZ() + reach) >> 4; chunkZ++) {
                MegaLavaLakePopulator.paint(lake, surfaceY, chunkX, chunkZ, region, seed, STRUCTURE_INDEX);
            }
        }
        return new TreeMap<>(region.written);
    }

    /**
     * Finds a chunk that is <b>really</b> a lake start (per the spread placement) whose middle column
     * carries the given biome.
     *
     * <p>Both halves matter. Scanning arbitrary chunks for a biome would hand the populator a chunk
     * the spread placement never chooses, and every "this chunk holds a lake" assertion would then
     * be asserting about a lake that does not exist -- the test would pass while the feature was
     * dead. So the candidate set is {@link MegaLavaLakePopulator#potentialStart} itself.
     */
    private static StartChunk findLakeStartInBiome(BiomePlacement placement, String key) {
        for (int regionX = -60; regionX <= 60; regionX++) {
            for (int regionZ = -60; regionZ <= 60; regionZ++) {
                StartChunk start = MegaLavaLakePopulator.potentialStart(SEED, regionX, regionZ);
                int x = (start.x() << 4) + 8;
                int z = (start.z() << 4) + 8;
                if (key.equals(placement.keyAt(x, 40, z))) {
                    return start;
                }
            }
        }
        throw new IllegalStateException("在这个种子里找不到群系 " + key + " 的湖起点，测试前提不成立");
    }

    /** Whether some region starts its lake at exactly this chunk. */
    private static boolean isStart(long seed, int chunkX, int chunkZ) {
        int regionX = Math.floorDiv(chunkX, SPACING);
        int regionZ = Math.floorDiv(chunkZ, SPACING);
        StartChunk start = MegaLavaLakePopulator.potentialStart(seed, regionX, regionZ);
        return start.x() == chunkX && start.z() == chunkZ;
    }

    /**
     * Vanilla's {@code getPotentialStructureChunk}, written out from the algorithm rather than
     * reusing anything the production code uses.
     */
    private static int[] referenceStart(long worldSeed, int regionX, int regionZ) {
        long seed = ((long) regionX * 341873128712L + (long) regionZ * 132897987541L + worldSeed + SALT)
                ^ LCG_MULTIPLIER;
        seed &= LCG_MASK;
        int bound = SPACING - SEPARATION;
        int offsetX = (int) (((long) bound * next(seed, 31)) >> 31);
        // The state advances once per next(bits), so each draw needs a fresh state; recompute it.
        long state = advance(seed);
        int offsetZ = (int) (((long) bound * next(state, 31)) >> 31);
        return new int[]{regionX * SPACING + offsetX, regionZ * SPACING + offsetZ};
    }

    /** One LCG step. */
    private static long advance(long state) {
        return (state * LCG_MULTIPLIER + LCG_ADDEND) & LCG_MASK;
    }

    /** {@code Random.next(bits)}: one step, then the high bits. */
    private static int next(long state, int bits) {
        return (int) (advance(state) >>> (48 - bits));
    }

    private static int count(boolean[] flags) {
        int n = 0;
        for (boolean flag : flags) {
            if (flag) {
                n++;
            }
        }
        return n;
    }

    /** The id without its {@code [properties]} suffix. */
    private static String bare(String blockId) {
        int bracket = blockId.indexOf('[');
        return bracket < 0 ? blockId : blockId.substring(0, bracket);
    }


    private static String key(int x, int y, int z) {
        return x + "," + y + "," + z;
    }

    private static int[] parse(String key) {
        String[] parts = key.split(",");
        return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1]),
                Integer.parseInt(parts[2])};
    }

    /** A column-profile function: what is at an absolute coordinate. */
    interface Terrain {
        Kind kindAt(int x, int y, int z);
    }

    /**
     * The vanilla base column, faked from a {@link Terrain}.
     *
     * <p>This is the whole point of {@link MegaLavaLakePopulator.ColumnSource} being an interface: the
     * production implementation asks the server's chunk generator, which needs a running server, but
     * the lake's decision only ever asks two questions of a column, and both are answerable from a
     * plain function. So the ordering and acceptance tests run the real code path with no server at
     * all.
     *
     * <p>It deliberately answers for <b>any</b> coordinate, like the real base column does -- that is
     * what lets a single chunk decide a lake that is two chunks wide.
     */
    record FakeColumns(Terrain terrain) implements MegaLavaLakePopulator.ColumnSource {
        /** Vanilla Nether: 128 tall, starting at 0 -- the port's world. */
        @Override
        public int genDepth() {
            return 128;
        }

        @Override
        public int minY() {
            return 0;
        }

        @Override
        public MegaLavaLakePopulator.Column at(int x, int z) {
            return new MegaLavaLakePopulator.Column() {
                /** Upstream's {@code state.isAir()}. */
                @Override
                public boolean isAir(int y) {
                    return terrain.kindAt(x, y, z) == Kind.AIR;
                }

                /** Upstream's {@code WORLD_SURFACE_WG.isOpaque() && !liquid}: ground, not fluid. */
                @Override
                public boolean isSolid(int y) {
                    return terrain.kindAt(x, y, z) == Kind.GROUND;
                }
            };
        }
    }

    /**
     * A flat floor whose <b>top solid block</b> is {@code surfaceY}: ground up to and including it, air
     * above.
     *
     * <p>That is the level WoVer's {@code findYDownward} reports -- "solid here, air above" resolves to
     * the solid block, not to the air cell over it -- and therefore the Y the lake's lava surface sits
     * at. Getting this backwards is a one-block error in every lake, which is why the anchor is spelled
     * out rather than implied.
     */
    record FlatFloor(int surfaceY) implements Terrain {
        static Terrain at(int surfaceY) {
            return new FlatFloor(surfaceY);
        }

        @Override
        public Kind kindAt(int x, int y, int z) {
            return y <= surfaceY ? Kind.GROUND : Kind.AIR;
        }
    }

    /**
     * A region around one chunk with a buffer, or unlimited when the chunk coordinates are
     * {@link Integer#MIN_VALUE}.
     *
     * <p>The restriction is the point of the seam tests: outside its own chunk plus buffer the region
     * refuses to answer at all, so any read that needed a neighbour column fails instead of quietly
     * reading whatever the fake terrain would have said.
     */
    private static final class FakeRegion implements MegaLavaLakePopulator.Sink {
        private final Terrain terrain;
        private final int chunkX;
        private final int chunkZ;
        private final Map<String, String> written = new TreeMap<>();

        FakeRegion(Terrain terrain, int chunkX, int chunkZ) {
            this.terrain = terrain;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }

        /**
         * Strictly one chunk, no buffer: the painter only ever touches the column it is about to
         * write, so a lake still comes out whole -- which is exactly the claim this shape of region
         * is here to test.
         */
        @Override
        public boolean readable(int x, int y, int z) {
            if (y < 0 || y > 127) {
                return false;
            }
            if (chunkX == Integer.MIN_VALUE) {
                return true;
            }
            return (x >> 4) == chunkX && (z >> 4) == chunkZ;
        }

        @Override
        public Kind kindAt(int x, int y, int z) {
            String override = written.get(key(x, y, z));
            if (override != null) {
                return switch (override) {
                    case "minecraft:lava" -> Kind.LIQUID;
                    case "minecraft:cave_air" -> Kind.AIR;
                    default -> Kind.GROUND;
                };
            }
            return terrain.kindAt(x, y, z);
        }

        @Override
        public boolean write(int x, int y, int z, String blockId) {
            if (!readable(x, y, z)) {
                return false;
            }
            written.put(key(x, y, z), blockId);
            return true;
        }
    }
}
