package dev.mumu.betternether.terrain;

import dev.mumu.betternether.biome.BiomeEdges;
import dev.mumu.betternether.biome.BiomePlacement;
import dev.mumu.betternether.biome.ClimateSampler;
import dev.mumu.betternether.biome.SurfaceRules;
import dev.mumu.betternether.biome.TargetPoint;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The column painter: geometry, cost, and the rules it now reads.
 *
 * <h2>Why this class changed shape</h2>
 * It used to test a hand-transcribed table ({@code BiomeSurface}) by sampling its shares. That table is
 * gone: the painter now evaluates the upstream surface-rule trees themselves, so the interesting
 * failures moved. They are, in order of how expensive they would be to notice in game:
 *
 * <ul>
 *   <li><b>The painter writes where it must not</b> -- into a bedrock plate, or over bedrock anywhere.
 *       Both are irreversible in a generated chunk.</li>
 *   <li><b>It paints deep rock</b> -- evaluating the rules past the depth budget both costs a whole
 *       column of noise and replaces the vanilla filler with a rule's fallback.</li>
 *   <li><b>It paints the wrong block</b> -- the rule that decides a position must be the biome's own,
 *       at the depth and the side (floor/ceiling) the JSON describes.</li>
 *   <li><b>It reads the chunk it is writing</b> -- the snapshot guarantee; see
 *       {@link #theSnapshotCostsExactlyOneReadPerBlock()}.</li>
 * </ul>
 *
 * <p>Everything below drives {@link NetherTerrainGenerator#planColumn}, which takes no
 * {@code ChunkData}: the whole decision is assertable without a server, and no test can accidentally
 * depend on write ordering.
 */
class NetherTerrainGeneratorSurfaceTest {
    /** Every id the plan wrote, as a list of "y=id" pairs -- what a failure message needs. */
    private static SurfaceRules.Book book() {
        Path dir = rulesDirectory();
        Assumptions.assumeTrue(dir != null, "找不到上游 surface_rules 目录；跳过与真实规则的对照");
        return SurfaceRules.Book.loadDirectory(dir);
    }

    private static Path rulesDirectory() {
        Path current = Path.of("").toAbsolutePath();
        for (; current != null; current = current.getParent()) {
            Path candidate = current.resolve(
                    "upstream/BetterNether/src/main/generated/data/betternether/wover/worldgen/surface_rules");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ rules on a column

    /**
     * WartForest's soul sandstone still reaches depths 4 and 5.
     *
     * <p>This is the case the old table explicitly could not express: it had one {@code underDepth}
     * counted from the surface, and the entry's javadoc said so ("writing it would push sandstone up
     * to where the player can see it"). Upstream instead writes
     * {@code stoneDepthCheck(4, true, 1, FLOOR)} -- a band that ends at depth 4, or 5 on a column whose
     * secondary depth draws 1. So the assertion is about <em>reach</em>: the top block is claimed by the
     * {@code ON_FLOOR} rule ahead of it, and the sandstone must still be there four and five blocks
     * down. A painter with a single fixed sub-layer depth gets one of those two wrong.
     */
    @Test
    void wartForestSoulSandstoneReachesDepthsFourAndFive() {
        SurfaceRules.Book book = book();
        SurfaceRules.Set set = book.of("wart_forest");
        assertNotNull(set, "wart_forest 必须有地表规则");

        // Solid from the floor plate up to y=60, with the biome everywhere; y=60 is the surface.
        IntPredicate solid = y -> y >= 5 && y <= 60;
        int[] plan = plan(book, solid, y -> false, (y, above, below) -> "wart_forest", 60, 1.0);

        assertTrue(isSoulSandstone(set, plan[60 - 4]),
                "depth 4 必须是灵魂砂岩，实际 " + set.id(plan[60 - 4]));
        assertTrue(isSoulSandstone(set, plan[60 - 5]),
                "depth 5（secondary_depth_range 打满）也必须是灵魂砂岩，实际 " + set.id(plan[60 - 5]));
        assertFalse(isSoulSandstone(set, plan[60 - 6]),
                "depth 6 已超出 offset 4 + range 1，不该是灵魂砂岩");
        assertFalse(isSoulSandstone(set, plan[60]),
                "地表本身由前一条 ON_FLOOR 规则拿走，不是砂岩");
    }

    /** Whether a planned slot is either soul-sandstone state. */
    private static boolean isSoulSandstone(SurfaceRules.Set set, int slot) {
        String id = set.id(slot);
        return id != null && id.startsWith("betternether:soul_sandstone");
    }

    /**
     * Every exposed face of a multi-level column is decided, and no interior rock is.
     *
     * <p>This is the regression that made the terrain read as "skinned": taking a single floor y per
     * column meant only the highest ledge carried biome materials. The Nether stacks ledges, steps and
     * overhangs in one column, and {@code stone_depth} runs at every one of those faces -- on both
     * sides, which is what makes a hanging biome paint the underside of a ledge.
     */
    @Test
    void everyExposedFaceOfAMultiLevelColumnIsPainted() {
        SurfaceRules.Book book = book();
        SurfaceRules.Set set = book.of("gloomwood");
        assertNotNull(set);

        // Three thick ledges (so they have a genuinely deep interior) and two overhanging slabs.
        IntPredicate solid = runs(10, 40, 60, 61, 80, 81, 100, 120);
        int[] plan = plan(book, solid, y -> false, (y, above, below) -> "gloomwood", 0, 0.5);

        for (int face : new int[]{40, 61, 81, 120, 10, 60, 80, 100}) {
            assertNotEquals(SurfaceRules.Rule.UNDECIDED, plan[face],
                    "y=" + face + " 是某个实心段的暴露面，必须被规则决定");
        }
        // Deep inside a thick run: further than the whole depth budget from either side.
        int budget = book.maxStoneDepth() + 1;
        for (int interior : new int[]{10 + budget + 2, 25, 110}) {
            assertEquals(SurfaceRules.Rule.UNDECIDED, plan[interior],
                    "y=" + interior + " 距上下空气都超过 " + budget + " 格，是内部岩体，不该被写");
        }
    }

    /**
     * No rule ever writes inside a bedrock plate, and never over bedrock.
     *
     * <p>The plates belong to {@link NetherTerrainGenerator#generateBedrock}. Upstream's own two
     * bedrock rules are dropped when the files are compiled (pinned in
     * {@code SurfaceRulesTest}), but this asserts the painter's side of the same promise: even if a
     * rule named bedrock, the plan stays empty and the plate stays intact.
     */
    @Test
    void nothingIsPaintedInsideTheBedrockPlates() {
        SurfaceRules.Book book = book();
        // Solid from the bottom plate all the way to the roof plate: the worst case, where a rule could
        // match at any y inside either plate.
        IntPredicate solid = y -> true;
        int[] plan = plan(book, solid, y -> false, (y, above, below) -> "gloomwood", 0, 0.5);

        for (int y = 0; y <= NetherTerrainGenerator.BEDROCK_FLOOR_MAX; y++) {
            assertEquals(SurfaceRules.Rule.UNDECIDED, plan[y], "y=" + y + " 在基岩地板里");
        }
        for (int y = NetherTerrainGenerator.BEDROCK_ROOF_MIN; y <= NetherTerrainGenerator.CEILING_Y; y++) {
            assertEquals(SurfaceRules.Rule.UNDECIDED, plan[y], "y=" + y + " 在基岩屋顶里");
        }

        // And a bedrock block is never replaced, even where a rule would otherwise decide.
        IntPredicate bedrock = y -> y == 100;
        int[] withBedrock = plan(book, y -> y >= 90 && y <= 110, bedrock, (y, above, below) -> "gloomwood", 0, 0.5);
        assertEquals(SurfaceRules.Rule.UNDECIDED, withBedrock[100], "基岩格不可被地表规则覆盖");
    }

    /**
     * Rock further than the depth budget from any air gap is read, and written only when a rule actually
     * names a different block.
     *
     * <p>Every {@code stone_depth} condition in upstream's 23 files stops inside the budget, so past it
     * only the files' <em>ungated</em> rules can still decide. In the biome used here
     * ({@code gloomwood}) that is the trailing {@code minecraft:netherrack} fallback, which the noise
     * stage already put there -- so the write is skipped and the plan stays empty. That is the no-op
     * the filter is allowed to make, and nothing more: {@code wart_forest} and {@code flooded_deltas}
     * decide {@code soul_sand}/{@code deepslate} at the same depth and those writes must happen, which
     * {@link #deepRockKeepsTheMaterialItsOwnUngatedRulesDecide()} pins.
     *
     * <p>The evaluation itself is <em>not</em> skipped: the per-chunk roughness streams make the set of
     * evaluations part of the result, which is why this asserts the plan only, and why the draw-order
     * test above exists.
     */
    @Test
    void rockDeeperThanTheDepthBudgetIsLeftAsBaseTerrain() {
        SurfaceRules.Book book = book();
        int budget = book.maxStoneDepth() + 1;
        IntPredicate solid = y -> y >= 5 && y <= 122;
        int[] plan = plan(book, solid, y -> false, (y, above, below) -> "gloomwood", 0, 0.5);

        // The column's only surfaces are y=5 (floor face) and y=122 (ceiling face), so everything
        // between the two budgets must be untouched.
        for (int y = 5 + budget; y <= 122 - budget; y++) {
            assertEquals(SurfaceRules.Rule.UNDECIDED, plan[y],
                    "y=" + y + " 距上下空气都超过 " + budget + " 格，且该群系深部就是下界岩兜底，不该被写");
        }
    }

    /**
     * The biome whose rules decide a position is the biome at that height, not the column's.
     *
     * <p>{@link dev.mumu.betternether.biome.BiomePlacement} puts a vertical biome in the band above
     * {@code CEILING_BAND_FLOOR}. The painter reads the key per y, so the roof and the floor can carry
     * different palettes -- which is the whole point of the vertical biome.
     */
    @Test
    void eachHeightIsPaintedWithItsOwnBiomeRule() {
        SurfaceRules.Book book = book();
        BiomeEdges.ColumnKey keyAt = (y, above, below) -> y >= 100 ? "upside_down_forest" : "wart_forest";
        int[] plan = plan(book, y -> y >= 5 && y <= 122, y -> false, keyAt, 122, 0.5);

        SurfaceRules.Set roof = book.of("upside_down_forest");
        SurfaceRules.Set floor = book.of("wart_forest");
        assertNotNull(roof);
        assertNotNull(floor);

        // The roof's underside is decided, and it is the upside-down forest that decides it: only that
        // biome's rules can paint ceiling mushrooms, so the slot at y=122 must be one of its own.
        assertNotEquals(SurfaceRules.Rule.UNDECIDED, plan[122], "屋顶底面必须被决定");
        assertTrue(roof.materials().contains(id(roof, plan[122])),
                "y=122 的材质 " + id(roof, plan[122]) + " 不在屋顶群的规则材质里：" + roof.materials());
        assertTrue(roof.materials().contains("betternether:ceiling_mushrooms"),
                "倒挂森林的规则必须能画菌盖：" + roof.materials());
        assertFalse(floor.materials().contains("betternether:ceiling_mushrooms"),
                "wart_forest 不该画顶板菌盖");
        // And the floor face is decided by the *other* rule set: y=5 is below the band, so it must be
        // wart forest's palette, never the upside-down forest's ceiling material.
        assertNotEquals(SurfaceRules.Rule.UNDECIDED, plan[5], "地板面也必须被决定");
        assertTrue(floor.materials().contains(id(floor, plan[5])),
                "y=5 的材质 " + id(floor, plan[5]) + " 必须来自 wart_forest 自己的规则：" + floor.materials());
    }

    /**
     * Same position, same rules, same block -- on every thread and every call.
     *
     * <p>The whole pass must be a pure function of (seed, x, z, terrain). Folia generates chunks on
     * many threads, and a rule that read shared mutable state would produce chunks that differ between
     * two runs of the same world -- unfixable after they are saved.
     */
    @Test
    void planningIsDeterministic() {
        SurfaceRules.Book book = book();
        IntPredicate solid = runs(10, 40, 60, 61, 100, 102);
        int[] first = plan(book, solid, y -> false, (y, above, below) -> "nether_grasslands", 0, 0.37);
        int[] second = plan(book, solid, y -> false, (y, above, below) -> "nether_grasslands", 0, 0.37);

        assertTrue(Arrays.equals(first, second), "同一列两次规划必须完全一致");
    }

    /**
     * A vanilla nether biome column is painted by vanilla's own rule, not left as bare netherrack.
     *
     * <p>This is the regression the review found: the biome source can produce all five vanilla nether
     * biomes, and {@code Book} used to hold only the 23 mod files, so a {@code soul_sand_valley} column
     * matched nothing and kept the base terrain -- no soul sand, no soul soil, and in
     * {@code basalt_deltas} no blackstone at all.
     */
    @Test
    void vanillaBiomeColumnsArePaintedWithVanillasOwnRule() {
        SurfaceRules.Book book = book();
        SurfaceRules.Set vanilla = book.vanilla();
        assertNotNull(vanilla, "Book 必须带原版下界规则");

        IntPredicate solid = y -> y >= 5 && y <= 60;
        int[] plan = plan(book, solid, y -> false, (y, above, below) -> "minecraft:soul_sand_valley", 60, 1, 0.0,
                FakeWorld.fixed(0.5));

        for (int face : new int[]{60, 5}) {
            assertNotEquals(SurfaceRules.Rule.UNDECIDED, plan[face],
                    "y=" + face + " 是暴露面，原版规则必须决定它（旧实现留成裸下界岩）");
            String id = vanilla.id(plan[face]);
            assertTrue(vanilla.materials().contains(id),
                    "y=" + face + " 的材质 " + id + " 必须来自原版规则：" + vanilla.materials());
        }
        assertEquals("minecraft:soul_sand", vanilla.id(plan[60]), "灵魂沙峡谷的地表是灵魂沙");
        assertEquals("minecraft:soul_sand", vanilla.id(plan[5]), "天花板同样是灵魂沙");
    }

    /**
     * The mod's own biomes are untouched by the vanilla fallback.
     *
     * <p>The fallback is keyed on "no rule set for this biome", so a {@code wart_forest} column must
     * still come out as wart forest: its materials include none of vanilla's ten.
     */
    @Test
    void theModBiomesKeepTheirOwnRules() {
        SurfaceRules.Book book = book();
        SurfaceRules.Set mod = book.of("wart_forest");
        assertNotNull(mod);

        int[] plan = plan(book, y -> y >= 5 && y <= 60, y -> false, (y, above, below) -> "wart_forest", 60, 1, 0.0,
                FakeWorld.fixed(0.5));

        assertNotEquals(SurfaceRules.Rule.UNDECIDED, plan[60], "模组群系的地表仍由它自己的规则决定");
        assertTrue(mod.materials().contains(mod.id(plan[60])),
                "材质必须来自 wart_forest 自己的规则：" + mod.id(plan[60]));
        assertFalse(book.vanilla().materials().contains("betternether:soul_sandstone"),
                "原版规则不该知道模组的方块");
    }

    /**
     * Which block consumes which draw of wover's per-chunk roughness stream.
     *
     * <p>{@code RoughNoiseConditionImpl.compile} builds one {@code RandomSource} per chunk <em>and per
     * condition</em> and draws one {@code nextFloat()} for every block it evaluates, so the value a
     * block gets is a function of the evaluation order, not of its position. That order is vanilla's:
     * {@code MaterialSystem.buildSurface} walks {@code y} from {@code getHeight(WORLD_SURFACE_WG) + 1}
     * down to the world floor -- at <em>every</em> y, air included -- with the column loop nested
     * {@code x} outside {@code z}. {@link NetherTerrainGenerator#planColumn} and
     * {@link NetherTerrainGenerator#forEachColumn} reproduce exactly that, and this test is the lock:
     * it replays the pass over four columns with a recorded stream and asserts the draw assignment
     * literally, so any change to the traversal, to the pruning, or to which rules are reached shows up
     * as a diff here instead of as a silently different Nether.
     *
     * <p>The list below is the fingerprint. It is not a "looks right" sample: each entry is
     * {@code node#draw@x,y,z}, where {@code node} is the cursor's creation index (one per
     * {@code rough_noise_condition}) and {@code draw} its 1-based position in that stream.
     */
    @Test
    void roughnessDrawsAreAssignedInVanillasTraversalOrder() {
        SurfaceRules.Book book = book();
        RecordingWorld world = new RecordingWorld();
        SurfaceRules.Context context = new SurfaceRules.Context();
        context.beginChunk(world, book.cursorSlots());

        // Two faces per column: y=60 (floor) and y=10 (ceiling of the run).
        IntPredicate solid = y -> y >= 10 && y <= 60;
        int[] plan = new int[NetherTerrainGenerator.COLUMN_HEIGHT];
        List<String> visited = new ArrayList<>();

        NetherTerrainGenerator.forEachColumn((dx, dz) -> {
            if (dx < 2 && dz < 2) {
                visited.add(dx + "," + dz);
                Arrays.fill(plan, SurfaceRules.Rule.UNDECIDED);
                NetherTerrainGenerator.planColumn(book, context, solid, solid, y -> false,
                        (y, above, below) -> "nether_swampland", dx, dz, book.maxStoneDepth() + 1, 1, 0.0, plan);
            }
        });

        assertEquals(List.of("0,0", "0,1", "1,0", "1,1"), visited,
                "列顺序必须是 x 在外、z 在内（vanilla 的循环嵌套）");

        // nether_swampland's tree is `sequence(..., ifTrue(stoneDepth(floor,0), ifTrue(rough0 -> grass)),
        // ifTrue(rough1 -> soul_sand), soul_soil, netherrack)`: the stone_depth gate covers only the
        // first rough_noise, so the second is evaluated at **every** stone block of the column while
        // the first is evaluated only on the exposed face. That asymmetry is exactly what a traversal
        // change would stir, which is why the count and the fingerprint below are this specific.
        int perColumn = 51;
        assertEquals(4 * perColumn, world.draws.size(),
                "四列的消耗次数：y=10..60 共 51 个石头格逐个求值，地表 y=60 上 rough0 先命中并决定，"
                        + "所以那一格不落到 rough1 —— 共 51 笔。实际："
                        + world.draws.subList(0, Math.min(6, world.draws.size())));
        assertEquals(2, world.cursorCount, "该群系有 2 个 rough_noise 节点，每 chunk 各一条流");
        // The stream bookkeeping (node + index) must repeat exactly per column; the positions differ
        // only by which column they are in.
        List<String> firstColumnStreams = stream(book, world.draws.subList(0, perColumn));
        for (int column = 1; column < 4; column++) {
            assertEquals(firstColumnStreams,
                    stream(book, world.draws.subList(column * perColumn, (column + 1) * perColumn)),
                    "第 " + column + " 列的消耗序列必须与第一列逐项相同（列之间没有共享状态）");
        }

        List<Integer> ys = new ArrayList<>();
        for (String draw : world.draws.subList(0, perColumn)) {
            ys.add(Integer.parseInt(draw.split("@")[1].split(",")[1]));
        }
        for (int index = 1; index < ys.size(); index++) {
            assertTrue(ys.get(index) <= ys.get(index - 1),
                    "列内必须 y 降序（vanilla 从 height+1 走到 0）：" + ys);
        }
        assertEquals(60, ys.get(0), "第一笔消耗在最高的石头格 y=60：空气与流体从不求值");
        assertEquals(10, ys.get(ys.size() - 1), "最后一笔在该列最低的石头格 y=10");

        // The fingerprint: FNV-1a over the whole assignment. Any change to the traversal, to which
        // rules are reached, or to the per-node stream bookkeeping changes this number.
        assertEquals("0f5082a5e4eb0331", fingerprint(world.draws),
                "粗糙噪声消耗归属的指纹变了：先确认是遍历/求值集合有意变动，再更新这个常量");
    }

    /**
     * Every upstream file carries a {@code minecraft:netherrack} fallback.
     *
     * <p>This is necessary for the painter's deep-write filter but <b>not</b> sufficient to make it a
     * no-op: 8 of the files also decide non-netherrack blocks deep inside the rock through branches
     * that do not test {@code stone_depth}. The filter's real condition is pinned by
     * {@link #deepRockKeepsTheMaterialItsOwnUngatedRulesDecide()}; this test only records that the
     * fallback exists in every file.
     */
    @Test
    void everyUpstreamFileCarriesANetherrackFallback() {
        SurfaceRules.Book book = book();
        for (String key : book.biomes()) {
            List<String> materials = book.of(key).materials();
            assertTrue(materials.contains("minecraft:netherrack"),
                    "群系 " + key + " 的规则里必须有下界岩兜底：" + materials);
        }
    }

    /**
     * 深度预算之外的深部岩体：上游真会画 {@code soul_sand}/{@code soul_soil}/{@code deepslate}。
     *
     * <p>这条替代了先前那个"钉住偏差集合"的写法。旧写法把错误当成期望值记下来：它证明了"8 个
     * 群系在预算之外决定的不是下界岩"，却让 {@code planColumn} 继续跳过这些写入，于是那些材质在
     * 我们这里<b>根本不存在</b>。修法是把跳过改成条件式（只有决定出来的方块确实是基础地形
     * {@link NetherTerrainGenerator#BASE_TERRAIN_ID} 时才省），并且把结果钉在"读到真实材质"上。
     *
     * <p>夹具是一整列埋死的岩体（{@code y=5..127} 没有一处空气），于是 {@code y=64} 一定在预算
     * 之外、离所有 {@code stone_depth} 的可达范围足够远。三件事一起断言：
     * <ul>
     *   <li>{@code wart_forest} / {@code nether_swampland} 的深部读到 {@code minecraft:soul_sand}；</li>
     *   <li>{@code flooded_deltas} 的深部读到 {@code minecraft:deepslate}；</li>
     *   <li>{@code gloomwood} 的深部决定就是下界岩兜底，因此计划仍然是"不写"——那才是真正的
     *       no-op，跳过它省下的正是"把已经在的方块再写一遍"。</li>
     * </ul>
     */
    @Test
    void deepRockKeepsTheMaterialItsOwnUngatedRulesDecide() {
        SurfaceRules.Book book = book();
        assertEquals("minecraft:soul_sand", deepId(book, "wart_forest"),
                "wart_forest 在兜底之前有一条不受 stone_depth 约束的 soul_sand 分支");
        assertEquals("minecraft:soul_sand", deepId(book, "nether_swampland"),
                "nether_swampland 同一条分支");
        assertEquals("minecraft:deepslate", deepId(book, "flooded_deltas"),
                "flooded_deltas 有一条不受约束的 switch_rule 会画 deepslate");
        assertEquals(SurfaceRules.Rule.UNDECIDED, planBuried(book, "gloomwood")[64],
                "深部决定就是下界岩兜底时，写入仍然省掉（否则等于把已经在的方块再写一遍）");
    }

    /**
     * 一整列埋死的岩体：{@code y=5..127} 全是实心，于是整列都在深度预算之外。
     *
     * <p>用来问"深部到底会被写成什么"，不会被任何一个地表规则干扰。
     */
    private static final IntPredicate BURIED_COLUMN = runs(5, NetherTerrainGenerator.CEILING_Y);

    /** 深部（{@code y=64}）实际决定出来的方块 id；计划为空则直接失败。 */
    private static String deepId(SurfaceRules.Book book, String biome) {
        int[] plan = planBuried(book, biome);
        int slot = plan[64];
        assertNotEquals(SurfaceRules.Rule.UNDECIDED, slot,
                biome + " 的深部必须真的被写：上游在那里画的不是下界岩");
        return book.of(biome).id(slot);
    }

    /** 计划一整列埋死岩体，群系固定为 {@code biome}。 */
    private static int[] planBuried(SurfaceRules.Book book, String biome) {
        int[] plan = new int[NetherTerrainGenerator.COLUMN_HEIGHT];
        Arrays.fill(plan, SurfaceRules.Rule.UNDECIDED);
        SurfaceRules.Context context = new SurfaceRules.Context();
        context.beginChunk(FakeWorld.INSTANCE, book.cursorSlots());
        NetherTerrainGenerator.planColumn(book, context, BURIED_COLUMN, BURIED_COLUMN, y -> false,
                (y, above, below) -> biome, 0, 0, book.maxStoneDepth() + 1, 0, 0.5, plan);
        return plan;
    }

    // ------------------------------------------------------------------ 交界掺混 × 真实规则

    /** 掺混用的世界种子；只喂给相干噪声，与群系选择无关。 */
    private static final long BLEND_SEED = 20260210L;

    /** 一条笔直的气候边界：quart x &lt; 0 是冷侧，其余是暖侧。 */
    private static BiomePlacement straightBoundary() {
        ClimateSampler sampler = (quartX, quartY, quartZ) ->
                TargetPoint.ofClimate(quartX < 0 ? -1.0f : 1.0f, 0.0f);
        return new BiomePlacement(sampler);
    }

    /** 一条合成洞穴列：地板面 y=40，天花板下表面 y=10，中间 24 格是深部岩体。 */
    private static final IntPredicate CAVE_COLUMN = runs(10, 40);

    /**
     * <b>合成洞穴列（固定种子）：只有暴露地板表层会换群系，深层与天花板一格都不动。</b>
     *
     * <p>这是"整列邻居置换"那条缺陷的回归。旧实现一旦被噪声挑中，这一列从地板到天花板全用邻居的
     * 规则；现在掺混只看 {@code stoneDepthAbove}，所以：
     * <ul>
     *   <li>{@code y ∈ [10, 40 - SKIN]}（天花板下表面 + 深部岩体）必须与未掺混的计划<b>逐格相同</b>；
     *       天花板被这一条自动覆盖，因为它的上方是整块岩体；</li>
     *   <li>表层 {@code y ∈ [41 - SKIN, 40]} 里至少要有一格真的换掉 —— 否则这个用例什么也没量到。</li>
     * </ul>
     *
     * <p>坐标特意取负（{@code x=0} 在界面上、{@code z=-17} 在负半轴）：{@code keyAt} 的 quart 换算
     * 是往负无穷取整，掺混的距离换算顺着它走，负坐标因此不该有任何特殊行为。
     */
    @Test
    void blendingOnlyRewritesTheExposedFloorSkin() {
        SurfaceRules.Book book = book();
        BiomePlacement placement = straightBoundary();
        BiomeEdges edges = new BiomeEdges(placement, BLEND_SEED);
        int x = 0;
        int z = -17;
        int skinBottom = 41 - BiomeEdges.SKIN;

        int changedSkin = 0;
        for (int columnZ = z - 32; columnZ <= z + 32; columnZ++) {
            final int zAt = columnZ;
            int[] plain = planAt(book, CAVE_COLUMN,
                    (y, above, below) -> placement.keyAt(x, y, zAt), x, zAt);
            int[] blended = planAt(book, CAVE_COLUMN, edges.column(x, columnZ), x, columnZ);

            for (int y = 10; y < skinBottom; y++) {
                assertEquals(plain[y], blended[y],
                        "y=" + y + "（天花板或深部岩体）不该被掺混：z=" + columnZ);
            }
            for (int y = skinBottom; y <= 40; y++) {
                if (plain[y] != blended[y]) {
                    changedSkin++;
                }
            }
        }
        assertTrue(changedSkin > 0, "边界上的表层必须真的有一格换掉，否则这个用例没量到掺混");
    }

    /**
     * 区块缝两侧、负坐标上：掺混的结果只由世界坐标决定。
     *
     * <p>生产路径把<b>世界</b>坐标交给 {@code BiomeEdges}（{@code baseX + dx}），所以同一个世界列
     * 无论落在哪个区块、{@code dx} 是几，都该得到同一份计划；而"深层与天花板等于未掺混"这条在
     * 负坐标上同样成立。用例在 {@code x = -16, -9, -8, -1, 0, 7, 8, 15, 16} 上逐列重算两遍并对照
     * 未掺混的计划。
     */
    @Test
    void blendingIsTheSameOnBothSidesOfAChunkSeam() {
        SurfaceRules.Book book = book();
        BiomePlacement placement = straightBoundary();
        BiomeEdges edges = new BiomeEdges(placement, BLEND_SEED);
        int skinBottom = 41 - BiomeEdges.SKIN;
        int[] worlds = {-16, -9, -8, -1, 0, 7, 8, 15, 16};

        for (int worldX : worlds) {
            for (int worldZ : new int[]{-16, -1, 0, 15}) {
                int[] plain = planAt(book, CAVE_COLUMN,
                        (y, above, below) -> placement.keyAt(worldX, y, worldZ), worldX, worldZ);
                int[] first = planAt(book, CAVE_COLUMN, edges.column(worldX, worldZ), worldX, worldZ);
                int[] second = planAt(book, CAVE_COLUMN, edges.column(worldX, worldZ), worldX, worldZ);

                assertArrayEquals(first, second,
                        "同一世界列算两遍必须逐格相同：" + worldX + "," + worldZ);
                for (int y = 10; y < skinBottom; y++) {
                    assertEquals(plain[y], first[y],
                            "y=" + y + " 在表层之下，负坐标/区块缝也不该被掺混："
                                    + worldX + "," + worldZ);
                }
            }
        }
    }

    /**
     * 掺混的表皮严格浅于深度预算。
     *
     * <p>{@link BiomeEdges#SKIN} 就是"看得见的那几格"，而 {@code depthBudget} 是规则还能决定多深。
     * 前者必须严格更小，否则 {@code planColumn} 传下去的 {@code stoneDepthAbove} 会在预算处被封顶、
     * 让掺混误以为深处仍是表层 —— 这是两个常量之间唯一的一条隐式依赖，因此钉在这里。
     */
    @Test
    void theBlendSkinStaysStrictlyInsideTheDepthBudget() {
        SurfaceRules.Book book = book();
        // 预算 = maxStoneDepth + (usesSurfaceDepth ? max(0, surfaceDepth) : 0) + 1，
        // 后者非负，所以 maxStoneDepth + 1 是预算的下界。
        assertTrue(book.maxStoneDepth() + 1 > BiomeEdges.SKIN,
                "深度预算的下界必须严格大于掺混表皮（" + BiomeEdges.SKIN
                        + "），实际 maxStoneDepth=" + book.maxStoneDepth());
    }

    /**
     * Which node consumed each draw, without the stream index or the position.
     *
     * <p>The index deliberately keeps counting across columns -- one stream per node per chunk, exactly
     * as wover builds it -- so it is the <em>node sequence</em> that has to repeat per column; the
     * absolute indices are locked by {@link #fingerprint}.
     */
    private static List<String> stream(SurfaceRules.Book book, List<String> draws) {
        List<String> streams = new ArrayList<>(draws.size());
        for (String draw : draws) {
            streams.add(draw.substring(0, draw.indexOf('#')));
        }
        return streams;
    }

    /**
     * FNV-1a over the recorded assignment, as a short hex string.
     *
     * <p>Written out rather than hashed with {@code List.hashCode} because the point is a stable,
     * human-comparable lock on the sequence, not on a JDK implementation detail.
     */
    private static String fingerprint(List<String> draws) {
        long hash = 0xcbf29ce484222325L;
        for (String draw : draws) {
            for (int index = 0; index < draw.length(); index++) {
                hash ^= draw.charAt(index);
                hash *= 0x100000001b3L;
            }
            hash ^= '|';
            hash *= 0x100000001b3L;
        }
        return String.format("%016x", hash);
    }

    /**
     * A world that records which draw of which stream each block consumed.
     *
     * <p>The {@code rough_noise_condition} node calls {@code cursor.nextFloat()} and then
     * {@code noise3d(...)} for the same block, so a {@code noise3d} call names exactly the block that
     * just consumed a draw. Each {@link #roughnessCursor()} call is one node's stream, numbered in
     * creation order, which is how the fingerprint below can say <em>which</em> stream advanced.
     */
    private static final class RecordingWorld implements SurfaceRules.Vanilla {
        final List<String> draws = new ArrayList<>();
        int cursorCount;

        @Override
        public double noise2d(String noiseId, int x, int z) {
            return 0.5;
        }

        @Override
        public double noise3d(String noiseId, int x, int y, int z) {
            draws.set(draws.size() - 1, draws.get(draws.size() - 1) + "@" + x + "," + y + "," + z);
            return 0.5;
        }

        @Override
        public int surfaceDepth(int x, int z) {
            return 1;
        }

        @Override
        public double surfaceSecondary(int x, int z) {
            return 0.0;
        }

        @Override
        public float gradientNextFloat(String randomName, int x, int y, int z) {
            return 0.5f;
        }

        @Override
        public SurfaceRules.Cursor roughnessCursor() {
            int node = cursorCount++;
            int[] drawn = {0};
            return () -> {
                drawn[0]++;
                draws.add("node" + node + "#" + drawn[0]);
                // A fixed, index-dependent value: the stream is the test's, the assignment is what is
                // under test.
                return Math.min(0.99f, drawn[0] * 0.01f);
            };
        }
    }

    // ------------------------------------------------------------------ geometry (unchanged)

    /**
     * The pass reads every block of a column exactly once, and the bound is a constant.
     *
     * <p>This is the observable cost claim behind the snapshot. Paper's {@code ChunkData} is a live
     * view of the chunk being built -- {@code CraftChunkData} holds the chunk itself, with no staging
     * buffer -- so {@code getType} after {@code setBlock} sees this pass's own output. Reading the
     * column once into a snapshot is what makes the decisions order-independent, and it is also what
     * bounds the cost: one read per block, {@code 16 * 16 * 128 = 32768} per chunk.
     *
     * <p>{@link NetherTerrainGenerator#planColumn} takes no {@code ChunkData} at all, which is the
     * structural half of the guarantee: it cannot read the chunk even if it wanted to.
     */
    @Test
    void theSnapshotCostsExactlyOneReadPerBlock() {
        assertEquals(128, NetherTerrainGenerator.COLUMN_HEIGHT, "下界整列是 y=0..127");
        assertEquals(32_768, NetherTerrainGenerator.SNAPSHOT_READS_PER_CHUNK,
                "每 chunk 的 getType 上界必须是 16*16*128，即每格恰好一次");
        assertEquals(NetherTerrainGenerator.BEDROCK_FLOOR_MAX + 1,
                NetherTerrainGenerator.PAINT_MIN_Y, "可画区从基岩地板之上开始");
        assertEquals(NetherTerrainGenerator.BEDROCK_ROOF_MIN - 1,
                NetherTerrainGenerator.PAINT_MAX_Y, "可画区止于基岩屋顶之下");
        assertEquals(122, NetherTerrainGenerator.PAINT_MAX_Y,
                "123..127 是原版屋顶梯度带，必须排除在可画区之外");
    }

    /**
     * The top of the world is never an exposed floor.
     *
     * <p>Above {@code y=127} the chunk answers {@code VOID_AIR}, so without the guard the bedrock roof
     * at y=127 reads as having air above it -- an exposed top face -- and a floor rule would paint the
     * world's ceiling with ground material. The Nether's ceiling is the one block that must never be
     * treated as ground.
     */
    @Test
    void theTopOfTheWorldIsNeverAFloorSurface() {
        IntPredicate solid = y -> y >= 123 && y <= 127;

        assertFalse(NetherTerrainGenerator.isFloorSurface(solid, NetherTerrainGenerator.CEILING_Y),
                "y=127 的上方是世界外，必须不算暴露的顶面");
        assertTrue(NetherTerrainGenerator.isCeilingSurface(solid, 123),
                "屋顶的下表面仍然是天花板面");

        // One below the top is still a normal floor when there is air above it.
        assertTrue(NetherTerrainGenerator.isFloorSurface(y -> y == 126, 126),
                "y=126 上方是空气，仍然算顶面（守卫只排除世界顶端，不是整个上部）");
    }

    /**
     * A multi-level column reports every one of its exposed faces, not just the top one.
     *
     * <p>This pins the two predicates the rest of the pipeline asks ({@code isFloorSurface},
     * {@code isCeilingSurface}), which are upstream's {@code ON_FLOOR} / {@code ON_CEILING} at depth 0.
     */
    @Test
    void everyExposedFaceOfAMultiLevelColumnIsFound() {
        IntPredicate solid = runs(10, 12, 40, 42, 60, 61, 80, 81, 100, 102);

        List<Integer> floors = new ArrayList<>();
        List<Integer> ceilings = new ArrayList<>();
        for (int y = NetherTerrainGenerator.CEILING_Y; y > 4; y--) {
            if (NetherTerrainGenerator.isFloorSurface(solid, y)) floors.add(y);
            if (NetherTerrainGenerator.isCeilingSurface(solid, y)) ceilings.add(y);
        }

        assertEquals(List.of(102, 81, 61, 42, 12), floors,
                "每个实心段的顶面都必须是地板面；只取最高点会丢掉下面四层");
        // 扫描方向是 y 从高到低，所以收集顺序也是降序。
        assertEquals(List.of(100, 80, 60, 40, 10), ceilings,
                "每个实心段的底面都必须是天花板面；只取屋顶会丢掉所有悬垂底面");
    }

    /**
     * A solid block with solid neighbours on both sides is interior rock and must be left alone.
     *
     * <p>Guards the other direction: painting every solid block instead of every exposed face would
     * replace the entire volume, which is both wrong and enormously more expensive.
     */
    @Test
    void interiorRockIsNotASurface() {
        IntPredicate solid = y -> y >= 10 && y <= 20;
        for (int y = 11; y <= 19; y++) {
            assertFalse(NetherTerrainGenerator.isFloorSurface(solid, y), "y=" + y + " 是内部岩体");
            assertFalse(NetherTerrainGenerator.isCeilingSurface(solid, y), "y=" + y + " 是内部岩体");
        }
        assertTrue(NetherTerrainGenerator.isFloorSurface(solid, 20));
        assertTrue(NetherTerrainGenerator.isCeilingSurface(solid, 10));
    }

    /**
     * The plates are vanilla's gradients, and the painter must never write inside them.
     *
     * <p>Vanilla's nether bedrock is the surface rule's work, not the noise pass's: {@code
     * SurfaceRuleData} emits {@code bedrock_floor} = {@code verticalGradient(bottom(),
     * aboveBottom(5))} and {@code bedrock_roof} = {@code not(verticalGradient(belowTop(5), top()))},
     * and upstream re-declares the same two gradients. A gradient is 1 at its first anchor and 0 at its
     * second, which is exactly what {@link NetherTerrainGenerator#bedrockChance} reproduces -- and that
     * the 23 JSON files state verbatim, which {@code SurfaceRulesTest} checks.
     */
    @Test
    void bedrockPlateChancesAreVanillasGradients() {
        assertEquals(1.0, NetherTerrainGenerator.bedrockChance(0, 0, 5), 1e-9,
                "y=0 必须是 100% 基岩：地板梯度的锚点是全基岩端");
        assertEquals(0.2, NetherTerrainGenerator.bedrockChance(4, 0, 5), 1e-9,
                "y=4 是地板梯度的另一端，只剩 20%");
        assertEquals(0.0, NetherTerrainGenerator.bedrockChance(5, 0, 5), 1e-9,
                "y=5 已在地板梯度之外：vanilla 不在那里放基岩");

        assertEquals(1.0, NetherTerrainGenerator.bedrockChance(127, 127, 122), 1e-9,
                "y=127 必须是 100% 基岩：屋顶的锚点是全基岩端");
        assertEquals(0.2, NetherTerrainGenerator.bedrockChance(123, 127, 122), 1e-9,
                "y=123 是屋顶梯度的另一端，只剩 20%");
        assertEquals(0.0, NetherTerrainGenerator.bedrockChance(122, 127, 122), 1e-9,
                "y=122 已超出屋顶梯度：画地表的那一层不该被判成基岩");
    }

    /**
     * The depth predicates are the numbers the rules count from -- and vanilla's are 1-based.
     *
     * <p>{@code MaterialSystem.buildSurface} keeps one counter that resets on air and increments on
     * every non-air block as it walks down, so the exposed block reads as 1, not 0;
     * {@code stoneDepthBelow} is the mirror and stops at the first block that is not stone
     * ({@code !isAir && fluidState.isEmpty()}). Both are capped, and the cap must never read outside
     * the column -- the probe stops at the world's own top and bottom instead.
     */
    @Test
    void stoneDepthCountsFromOneAtTheFaceAndIsCappedElsewhere() {
        IntPredicate solid = y -> y >= 10 && y <= 20;

        assertEquals(1, NetherTerrainGenerator.depthAbove(solid, 20, 8), "y=20 的上方是空气，计数为 1");
        assertEquals(2, NetherTerrainGenerator.depthAbove(solid, 19, 8), "y=19 上方一格是实心");
        assertEquals(8, NetherTerrainGenerator.depthAbove(solid, 10, 8), "深处被 cap 截断");

        assertEquals(1, NetherTerrainGenerator.depthBelow(solid, 10, 8), "y=10 的下方是空气，计数为 1");
        assertEquals(2, NetherTerrainGenerator.depthBelow(solid, 11, 8), "y=11 下方一格是实心");

        // Fluids count as cover above but are not stone below -- vanilla's two different predicates.
        IntPredicate withLavaAbove = y -> y == 20 || y == 21;
        assertEquals(2, NetherTerrainGenerator.depthAbove(withLavaAbove, 20, 8),
                "上方的熔岩算作覆盖（stoneDepthAbove 只跳过空气）");
        assertEquals(1, NetherTerrainGenerator.depthBelow(y -> y == 21, 21, 8),
                "熔岩不是石头，所以它自己的 stoneDepthBelow 是 1");

        // Outside the world the probe stops instead of reading: both counts are 0 on an empty column.
        assertEquals(0, NetherTerrainGenerator.depthAbove(y -> false, 127, 8),
                "y=127 之上是界外，且 y=127 自身不是方块，计数为 0");
        assertEquals(0, NetherTerrainGenerator.depthBelow(y -> false, 0, 8),
                "y=0 之下是界外，且 y=0 自身不是方块，计数为 0");
    }

    // ------------------------------------------------------------------ unwrapping the world's generator

    /**
     * Behind a Bukkit wrapper, the noise settings are reached by following the delegate.
     *
     * <p>This is the case the server always has: a world with a Bukkit generator is a
     * {@code CustomChunkGenerator} installed <em>over</em> the vanilla noise generator, so the settings
     * are one {@code getDelegate()} hop down. If the walk stops before that hop, every Nether world
     * fails at load with "取不到下界的 NoiseGeneratorSettings" -- the failure this policy exists to
     * avoid.
     */
    @Test
    void theNoiseSettingsAreReachedThroughABukkitWrapper() {
        assertEquals("nether", peeledSettings(Wrapper.bukkit(Wrapper.settings())),
                "Bukkit 包装一层之后必须还能取到设置");
        assertEquals("nether", peeledSettings(Wrapper.settings()),
                "没有包装时设置就在最外层，一步都不用走");
    }

    /**
     * A wrapper this code cannot see through reports "no settings" instead of throwing.
     *
     * <p>CraftEngine installs its own {@code InjectedCustomChunkGenerator} on top of the world's
     * generator, and it keeps that generator in a {@code private final target} field with no accessor
     * (and {@code InjectedChunkGenerator} is an empty marker interface), so the chain ends there. The
     * old single-hop unwrap read that as "the delegate is not a noise generator" and threw -- which is
     * the crash this test pins. Returning {@code null} is what lets the caller read the same generator
     * from the dimension's {@code LevelStem} instead of giving up or guessing an algorithm.
     */
    @Test
    void craftEnginesOpaqueWrapperReportsNoSettingsInsteadOfThrowing() {
        Wrapper injected = Wrapper.craftEngine(Wrapper.bukkit(Wrapper.settings()));
        assertNull(peeledSettings(injected),
                "看不穿的包装必须回答「取不到」，由调用方改读 LevelStem，而不是当场抛异常");
    }

    /**
     * The walk is capped, so a chain that never exposes the settings cannot spin forever.
     *
     * <p>{@link NetherTerrainGenerator#MAX_WRAPPER_HOPS} is both the headroom for another plugin
     * wrapping this one's world and the loop bound; a delegate that answers with itself (a badly
     * written wrapper) would otherwise hang the world-loading thread, which is far worse than a loud
     * failure.
     */
    @Test
    void theWrapperWalkIsCappedRatherThanUnbounded() {
        assertEquals("nether",
                peeledSettings(chain(NetherTerrainGenerator.MAX_WRAPPER_HOPS)),
                "上限之内的包装链必须走完");
        assertNull(
                peeledSettings(chain(NetherTerrainGenerator.MAX_WRAPPER_HOPS + 1)),
                "超过上限就停下并回答「取不到」，不能无限往下走");
    }

    /** {@code hops} Bukkit-style wrappers around a generator that holds the settings. */
    private static Wrapper chain(int hops) {
        Wrapper node = Wrapper.settings();
        for (int hop = 0; hop < hops; hop++) {
            node = Wrapper.bukkit(node);
        }
        return node;
    }

    /** The settings {@code peel} finds, using the same step {@code settingsOf} passes in production. */
    private static String peeledSettings(Wrapper start) {
        return NetherTerrainGenerator.peel(start,
                wrapper -> wrapper.opaque() ? null : wrapper.delegate(),
                wrapper -> Optional.ofNullable(wrapper.carries()));
    }

    /**
     * One generator in a wrapper chain, as {@code peel} sees it.
     *
     * @param carries the noise settings id it holds, or {@code null} when it holds none
     * @param delegate the generator it wraps, or {@code null} when the chain ends here
     * @param opaque   whether this code can see through it -- CraftEngine's injected generator knows its
     *                 delegate but keeps it in a private field, so it reads as opaque
     */
    private record Wrapper(String carries, Wrapper delegate, boolean opaque) {
        static Wrapper settings() {
            return new Wrapper("nether", null, true);
        }

        static Wrapper bukkit(Wrapper delegate) {
            return new Wrapper(null, delegate, false);
        }

        static Wrapper craftEngine(Wrapper delegate) {
            return new Wrapper(null, delegate, true);
        }
    }

    // ------------------------------------------------------------------ helpers

    /** Plans one column and returns the whole slot array. */
    /**
     * 计划一列，<b>世界坐标原样传下去</b>（{@code x} 与 {@code z} 都当真）。
     *
     * <p>上面那组 {@code plan} 助手固定 {@code z = 0}：它们断言的是"哪条规则决定某个高度的方块"，
     * 与平面位置无关。交界掺混不是这样 —— 它的噪声、quart 偏移与群系查询全是 {@code (x, z)} 的
     * 函数，所以这一组用例必须把真实坐标交给 {@code planColumn}，否则负坐标与区块缝根本量不到。
     */
    private static int[] planAt(SurfaceRules.Book book, IntPredicate solid,
                                BiomeEdges.ColumnKey keyAt, int x, int z) {
        int[] plan = new int[NetherTerrainGenerator.COLUMN_HEIGHT];
        Arrays.fill(plan, SurfaceRules.Rule.UNDECIDED);
        int budget = book.maxStoneDepth() + 1;
        SurfaceRules.Context context = new SurfaceRules.Context();
        context.beginChunk(FakeWorld.INSTANCE, book.cursorSlots());
        NetherTerrainGenerator.planColumn(book, context, solid, solid, y -> false,
                keyAt, x, z, budget, 0, 0.5, plan);
        return plan;
    }

    private static int[] plan(SurfaceRules.Book book, IntPredicate solid, IntPredicate bedrock,
                              BiomeEdges.ColumnKey keyAt, int x, double secondary) {
        return plan(book, solid, bedrock, keyAt, x, 0, secondary);
    }

    /**
     * Plans one column exactly as {@code generateSurface} does for it.
     *
     * <p>The budget is the generator's own expression, because a test with a different budget would be
     * asserting a different pass: book-wide {@code stone_depth} reach, plus this column's
     * {@code surfaceDepth} where a rule adds it, plus one.
     */
    private static int[] plan(SurfaceRules.Book book, IntPredicate solid, IntPredicate bedrock,
                              BiomeEdges.ColumnKey keyAt, int x, int surfaceDepth,
                              double secondary) {
        return plan(book, solid, bedrock, keyAt, x, surfaceDepth, secondary, FakeWorld.INSTANCE);
    }

    private static int[] plan(SurfaceRules.Book book, IntPredicate solid, IntPredicate bedrock,
                              BiomeEdges.ColumnKey keyAt, int x, int surfaceDepth,
                              double secondary, SurfaceRules.Vanilla world) {
        int[] plan = new int[NetherTerrainGenerator.COLUMN_HEIGHT];
        Arrays.fill(plan, SurfaceRules.Rule.UNDECIDED);
        int budget = book.maxStoneDepth()
                + (book.usesSurfaceDepth() ? Math.max(0, surfaceDepth) : 0) + 1;
        // One context per planned column, bound like a chunk: a rule that needs the world's noise must
        // go through the same beginChunk the generator does, or it fails loudly by design.
        SurfaceRules.Context context = new SurfaceRules.Context();
        context.beginChunk(world, book.cursorSlots());
        NetherTerrainGenerator.planColumn(book, context, solid, solid, bedrock,
                keyAt, x, 0, budget, surfaceDepth, secondary, plan);
        return plan;
    }

    /**
     * A stand-in for the world, so the painter can be driven without a server.
     *
     * <p>The noise is a position-dependent sine: the assertions below are about which <em>rule</em>
     * decides a block, not about a share, so a field with no upstream meaning is exactly right here --
     * and the production field comes from {@code RandomState} through
     * {@code NetherTerrainGenerator.RandomStateSurface}.
     */
    private static final class FakeWorld implements SurfaceRules.Vanilla {
        static final FakeWorld INSTANCE = new FakeWorld(null);

        /** {@code null} means the positional sine; a value means "every noise answers this". */
        private final Double constant;

        private FakeWorld(Double constant) {
            this.constant = constant;
        }

        static FakeWorld fixed(double noise) {
            return new FakeWorld(noise);
        }

        @Override
        public double noise2d(String noiseId, int x, int z) {
            return constant != null ? constant : 0.5 * Math.sin(x * 0.37 + z * 0.11);
        }

        @Override
        public double noise3d(String noiseId, int x, int y, int z) {
            return constant != null ? constant : 0.5 * Math.sin(x * 0.37 + y * 0.9 + z * 0.11);
        }

        @Override
        public int surfaceDepth(int x, int z) {
            return 0;
        }

        @Override
        public double surfaceSecondary(int x, int z) {
            return 0.0;
        }

        @Override
        public float gradientNextFloat(String randomName, int x, int y, int z) {
            return 0.5f;
        }

        @Override
        public SurfaceRules.Cursor roughnessCursor() {
            return () -> 0.5f;
        }
    }

    /** The painted ids between two y values inclusive, in ascending order. */
    private static List<String> ids(SurfaceRules.Set set, int[] plan, int from, int to) {
        List<String> painted = new ArrayList<>();
        for (int y = from; y <= to && y < plan.length; y++) {
            String id = set.id(plan[y]);
            if (id != null) {
                painted.add(y + "=" + id);
            }
        }
        return painted;
    }

    private static String id(SurfaceRules.Set set, int slot) {
        return set.id(slot);
    }

    /** A solidity predicate from inclusive y runs, so a column reads as terrain rather than as code. */
    private static IntPredicate runs(int... bounds) {
        Set<Integer> ys = new HashSet<>();
        for (int index = 0; index + 1 < bounds.length; index += 2) {
            for (int y = bounds[index]; y <= bounds[index + 1]; y++) {
                ys.add(y);
            }
        }
        return ys::contains;
    }
}
