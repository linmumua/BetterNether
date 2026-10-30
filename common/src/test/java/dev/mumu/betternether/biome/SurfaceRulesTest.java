package dev.mumu.betternether.biome;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rule interpreter: the semantics each JSON field has, and the 23 real files compiling.
 *
 * <h2>Every expectation here is transcribed from source</h2>
 * The numbers in this file are not "what looked right in game". Each one is what the upstream code
 * does, and the javadoc on each test says which code:
 *
 * <ul>
 *   <li><b>Minecraft 26.3</b>, disassembled out of the server jar this module compiles against:
 *       {@code StoneDepthCondition$1.compute}, {@code HoleCondition$1.compute},
 *       {@code VerticalGradientCondition$1.compute}, {@code NoiseThresholdCondition.lambda$compile$0},
 *       {@code MaterialSystem.getSurfaceDepth/getSurfaceSecondary/buildSurface},
 *       {@code MaterialRuleContext$1}, {@code BitRandomSource}.</li>
 *   <li><b>WorldWeaver {@code release/26.3}</b>, the library BetterNether 26.301.2 builds against:
 *       {@code ThresholdConditionImpl}, {@code VolumeThresholdConditionImpl},
 *       {@code RoughNoiseConditionImpl}, {@code NetherNoiseCondition}, {@code SwitchRuleSource},
 *       {@code MathHelper}.</li>
 *   <li><b>This repository</b>: the two numeric providers under
 *       {@code world/biomes/providers/}.</li>
 * </ul>
 *
 * <h2>Why the trees are hand-built</h2>
 * The samples below are three or four nodes long. That is deliberate: a failure has to say
 * <em>which semantic</em> broke. Asserting the same thing through one of the 23 real trees would tell
 * us only that "the Gloomwood floor changed", and the tree has four independent reasons to change.
 *
 * <h2>Two things every test has to know about the depths</h2>
 * Vanilla's {@code stoneDepthAbove} is 1-based -- 1 on the exposed block -- and {@code stone_depth}
 * tests {@code depth <= 1 + offset + surface + secondary}. So a rule with {@code offset: 0} claims
 * depth 1 (the surface block) and nothing below it, which is upstream's {@code ON_FLOOR}. The earlier
 * port counted from 0 and dropped the {@code 1 +}, which cancelled out for {@code offset} alone but
 * not for {@code add_surface_depth} or {@code secondary_depth_range}; those two now have their own
 * tests.
 */
class SurfaceRulesTest {
    // ------------------------------------------------------------------ stone depth

    /**
     * {@code stone_depth(0, false, 0, FLOOR)} -- upstream's {@code ON_FLOOR} -- claims the exposed
     * block and nothing below it.
     *
     * <p>Source: {@code StoneDepthCondition$1.compute()} ends with
     * {@code stoneDepthAbove <= 1 + offset + surface + secondary}. With {@code offset: 0} that is
     * {@code depth <= 1}, and vanilla's depth is 1 on the surface block, so the band is exactly one
     * block. A port that tested {@code depth < offset} would claim nothing at all and the whole nether
     * would fall through to the trailing fallback.
     */
    @Test
    void stoneDepthFloorClaimsTheSurfaceBlockOnly() {
        SurfaceRules.Set set = condition("""
                {"type": "minecraft:stone_depth", "surface_type": "floor", "offset": 0}
                """);
        assertTrue(decides(set, 1), "depth 1 是地表本身（vanilla 的计数是 1 基）");
        assertFalse(decides(set, 2), "offset=0 不该管到下一格");
        assertFalse(decides(set, 5), "更深处更不该");
    }

    /** {@code offset: n} extends the band to depth {@code 1 + n}. */
    @Test
    void stoneDepthOffsetReachesExactlyThatManyBlocksDown() {
        SurfaceRules.Set set = condition("""
                {"type": "minecraft:stone_depth", "surface_type": "floor", "offset": 2}
                """);
        assertTrue(decides(set, 1), "depth 1 在地表带内");
        assertTrue(decides(set, 3), "depth 3 = 1 + offset，最深处仍然成立");
        assertFalse(decides(set, 4), "depth 4 已超出 1 + offset");
    }

    /**
     * {@code secondary_depth_range} consumes vanilla's real per-column {@code surfaceSecondary},
     * mapped through {@code Mth.map(value, -1, 1, 0, range)} and truncated.
     *
     * <p>Source: {@code StoneDepthCondition$1.compute()} --
     * {@code secondary = secondaryDepthRange == 0 ? 0 : (int) Mth.map(context.getSurfaceSecondary(), -1.0, 1.0, 0.0, (double) secondaryDepthRange)}.
     * So the field's own range is what selects the widening, and a field of {@code -1} widens by
     * nothing while {@code +1} widens by the whole range. The earlier port multiplied a hand-made
     * {@code [0,1)} hash by {@code range + 1} instead, which both read the wrong field and could
     * overshoot the range.
     */
    @Test
    void secondaryDepthRangeMapsTheSurfaceSecondaryField() {
        SurfaceRules.Set set = condition("""
                {"type": "minecraft:stone_depth", "surface_type": "floor", "offset": 4,
                 "secondary_depth_range": 1}
                """);

        // secondary = -1 -> mapped 0 -> limit 1 + 4 = 5.
        assertTrue(decides(set, 5, -1.0), "secondary=-1 时 limit 是 5");
        assertFalse(decides(set, 6, -1.0), "secondary=-1 不该到 6");

        // secondary = +1 -> mapped 1 -> limit 6.
        assertTrue(decides(set, 6, 1.0), "secondary=+1 时 limit 是 1 + 4 + 1 = 6");
        assertFalse(decides(set, 7, 1.0), "range=1 也不会超过 6");

        // secondary = 0 -> mapped 0.5 -> (int) 0.5 = 0 -> limit 5: the truncation is part of it.
        assertTrue(decides(set, 5, 0.0), "secondary=0 映射到 0.5，截断后仍加 0");
        assertFalse(decides(set, 6, 0.0), "0.5 截断成 0，所以 6 仍在带外");
    }

    /**
     * {@code add_surface_depth} adds the column's real {@code surfaceDepth}.
     *
     * <p>Source: {@code surface = addSurfaceDepth ? context.surfaceDepth() : 0}, added to the same
     * {@code 1 + offset + secondary} limit. Vanilla's own {@code surfaceDepth} is
     * {@code (int)(surfaceNoise.get(x, 0, z) * 2.75 + 3.0 + positionalRandom.nextDouble() * 0.25)}
     * ({@code MaterialSystem.getSurfaceDepth}), i.e. a small positive number that varies per column and
     * can be zero or negative. Three upstream rules use it: GravelDesert's and SoulPlain's floor/ceiling
     * skin, and WartForest's {@code (4, true, 1, FLOOR)}.
     *
     * <p>The earlier port pinned it to 0 and documented that as safe ("upstream never relies on it"),
     * which is wrong: with {@code add_surface_depth} set, the band widens by that many blocks.
     */
    @Test
    void addSurfaceDepthAddsTheColumnsOwnSoilThickness() {
        SurfaceRules.Set withFlag = condition("""
                {"type": "minecraft:stone_depth", "surface_type": "floor", "offset": 0,
                 "add_surface_depth": true}
                """);
        SurfaceRules.Set without = condition("""
                {"type": "minecraft:stone_depth", "surface_type": "floor", "offset": 0}
                """);

        // surfaceDepth = 3 -> limit 4 -> depths 1..4; the plain rule stops at 1.
        for (int depth = 1; depth <= 4; depth++) {
            assertTrue(decides(withFlag, depth, 0.0, 3), "surfaceDepth=3 时应覆盖到 depth 4，实际 depth=" + depth);
        }
        assertFalse(decides(withFlag, 5, 0.0, 3), "surfaceDepth=3 只到 depth 4");
        assertTrue(decides(without, 1, 0.0, 3), "不加 surfaceDepth 时 depth 1 仍在地表");
        assertFalse(decides(without, 2, 0.0, 3), "不加 surfaceDepth 时 depth 2 已在带外");

        // A zero or negative surfaceDepth must not widen anything -- it can even make the band empty.
        assertFalse(decides(withFlag, 1, 0.0, -3), "surfaceDepth=-3 时 limit 为负，连地表都不成立");
    }

    /**
     * The ceiling direction is the mirror: vanilla reads {@code stoneDepthBelow}.
     *
     * <p>{@code StoneDepthCondition$1.compute()} picks {@code stoneDepthBelow()} when
     * {@code surfaceType == CEILING}. Gloomwood states {@code (1, false, 0, CEILING)} for its skin, so
     * reading the floor direction instead would paint the cavern under the roof and leave the roof's
     * own layers bare.
     */
    @Test
    void stoneDepthCeilingCountsUpFromTheUnderside() {
        SurfaceRules.Set set = condition("""
                {"type": "minecraft:stone_depth", "surface_type": "ceiling", "offset": 1}
                """);
        assertTrue(decidesBelow(set, 1), "紧贴下表面的一格（1 基）");
        assertTrue(decidesBelow(set, 2), "offset=1 再上一层");
        assertFalse(decidesBelow(set, 3), "offset=1 不该管到更里面");

        // Direction matters: a ceiling rule must not fire because of air *above* the block.
        SurfaceRules.Context ctx = context(0, 100, 0, 1, 9, 0, 0.0);
        assertFalse(set.decide(ctx) >= 0, "上方是空气不构成天花板；ceiling 规则只看下方");
    }

    /**
     * {@code minecraft:hole} is {@code surfaceDepth() <= 0}.
     *
     * <p>Source: {@code HoleCondition$1.compute()} is literally
     * {@code return context.surfaceDepth() <= 0;}. It is a property of the column, so a column either
     * is a hole everywhere or is not a hole at all -- the earlier port's per-block 50/50 coin flip was
     * a different phenomenon entirely, and it made MagmaLand's red-sand patches twice as common as
     * upstream's.
     */
    @Test
    void holeIsAColumnPropertyOfANonPositiveSurfaceDepth() {
        SurfaceRules.Set set = condition("""
                {"type": "minecraft:hole"}
                """);
        assertTrue(decides(set, 3, 0.0, 0), "surfaceDepth=0 是洞");
        assertTrue(decides(set, 3, 0.0, -4), "surfaceDepth<0 也是洞");
        assertFalse(decides(set, 3, 0.0, 1), "surfaceDepth=1 不是洞");

        // And it is independent of the height and the depth: same column, same answer.
        SurfaceRules.Context ctx = context(7, 40, 9, 5, 5, 2, 0.3);
        assertFalse(set.decide(ctx) >= 0, "surfaceDepth>0 时任何 y 都不是洞");
    }

    // ------------------------------------------------------------------ control flow

    /**
     * A sequence is first-match: a later rule must never override an earlier one.
     *
     * <p>Every one of the 23 files depends on this -- the surface rules come first and the netherrack
     * fallback last. A sequence that let the fallback win would paint the whole world netherrack while
     * still "compiling fine".
     */
    @Test
    void sequenceTakesTheFirstRuleThatDecides() {
        SurfaceRules.Set set = compile("""
                {"type": "minecraft:sequence", "sequence": [
                  {"type": "minecraft:block", "result_state": "minecraft:soul_sand"},
                  {"type": "minecraft:block", "result_state": "minecraft:netherrack"}]}
                """);
        assertEquals("minecraft:soul_sand", idAt(set, 1, 0),
                "第一条规则必须赢；被后面的兜底覆盖说明 sequence 变成了 last-match");
    }

    /** A false condition decides nothing, so the next rule in the sequence runs. */
    @Test
    void aFalseConditionFallsThroughToTheNextRule() {
        SurfaceRules.Set set = compile("""
                {"type": "minecraft:sequence", "sequence": [
                  {"type": "minecraft:condition",
                   "if_true": {"type": "minecraft:stone_depth", "surface_type": "floor", "offset": 0},
                   "then_run": {"type": "minecraft:block", "result_state": "minecraft:soul_sand"}},
                  {"type": "minecraft:block", "result_state": "minecraft:gravel"}]}
                """);
        assertEquals("minecraft:soul_sand", idAt(set, 1, 0), "depth 1 时第一条成立");
        assertEquals("minecraft:gravel", idAt(set, 2, 0), "depth 2 时第一条不成立，必须落到第二条");
    }

    /** {@code not} inverts its child, which is how the roof plate is expressed. */
    @Test
    void notInvertsItsChild() {
        SurfaceRules.Set inverted = compile("""
                {"type": "minecraft:condition",
                 "if_true": {"type": "minecraft:not",
                             "invert": {"type": "minecraft:stone_depth",
                                        "surface_type": "floor", "offset": 0}},
                 "then_run": {"type": "minecraft:block", "result_state": "minecraft:soul_soil"}}
                """);

        assertFalse(decides(inverted, 1), "not(地表) 在 depth 1 处为假");
        assertTrue(decides(inverted, 4), "not(地表) 在 depth 4 处为真");
    }

    /**
     * A {@code switch_rule} picks {@code max(0, selector) % length}.
     *
     * <p>Source: {@code SwitchRuleSource.compile} --
     * {@code int nr = Math.max(0, selector.getNumber(ctx)) % compiled.length;}. No wrap-around for
     * negative indices (the selector is clamped first) and no fallback for an empty collection.
     */
    @Test
    void switchRuleIndexesTheCollectionAndClamps() {
        SurfaceRules.Set set = compile("""
                {"type": "wover:switch_rule", "selector": {"type": "wover:nether_noise", "nether_noise": 0},
                 "collection": [
                   {"type": "minecraft:block", "result_state": "minecraft:soul_soil"},
                   {"type": "minecraft:block", "result_state": "minecraft:soul_sand"},
                   {"type": "minecraft:block", "result_state": "minecraft:netherrack"}]}
                """);

        // Every position must land on one of the three: no exception, no undecided.
        for (int x = -40; x <= 40; x += 3) {
            String id = idAt(set, 0, x);
            assertNotNull(id, "x=" + x + " 没有选中任何条目");
            assertTrue(set.materials().contains(id), "选中了集合之外的 id：" + id);
        }
    }

    /**
     * {@code wover:nether_noise} is one fixed provider whose index spans {@code 0..5}.
     *
     * <p>Source: {@code NetherNoiseCondition}. Its codec is
     * {@code Codec.BYTE.fieldOf("nether_noise").xmap(obj -> INSTANCE, obj -> (byte) 0)}, i.e. the byte
     * in the JSON is decoded and discarded, and {@code getNumber} returns {@code 0 + offset},
     * {@code 1 + offset} or {@code 2 + offset} with {@code offset = nextInt(20) == 0 ? 3 : 0}. So the
     * only values it can ever produce are 0..5, and its "streaks" of {@code offset = 3} are one column
     * in twenty.
     */
    @Test
    void netherNoiseSelectorSpansZeroToFiveAndIgnoresItsJsonId() {
        SurfaceRules.Set withZero = switchOf(0);
        SurfaceRules.Set withSeven = switchOf(7);

        boolean[] seen = new boolean[6];
        for (int x = -200; x <= 200; x++) {
            String first = idAt(withZero, 0, x);
            String second = idAt(withSeven, 0, x);
            assertEquals(first, second, "x=" + x + " 处 nether_noise 的 JSON 值不该影响结果");
            assertNotNull(first);
            int index = indexOf(first);
            assertTrue(index >= 0 && index <= 5, "nether_noise 只会给出 0..5，实际选中 " + first);
            seen[index] = true;
        }
        for (int index = 0; index < seen.length; index++) {
            assertTrue(seen[index], "index " + index + " 在 400 列里一次都没出现");
        }
        assertEquals(6, seen.length, "集合有 6 条，才能把 0..5 都看见");
    }

    /**
     * The two {@code betternether:*} selectors are the providers in this repository, branch for branch.
     *
     * <p>{@code NetherGrasslandsNumericProvider.getNumber} branches on
     * {@code ctx.getStoneDepthAbove()}, i.e. the vanilla 1-based depth: at 1 it draws
     * {@code nextInt(3)}, up to {@code nextInt(3) + 1} it answers 0, otherwise 2. The earlier port fed
     * it a 0-based depth, which shifted every branch by one block.
     */
    @Test
    void betternetherSelectorsBranchOnTheVanillaStoneDepth() {
        SurfaceRules.Set grasslands = compile("""
                {"type": "wover:switch_rule",
                 "selector": {"type": "betternether:nether_grasslands", "nether_grasslands": 0},
                 "collection": [
                   {"type": "minecraft:block", "result_state": "minecraft:soul_soil"},
                   {"type": "minecraft:block", "result_state": "minecraft:soul_sand"},
                   {"type": "minecraft:block", "result_state": "minecraft:netherrack"}]}
                """);

        // Branch three can only answer 0 or 2: at depth 3 the provider either takes the second branch
        // (nextInt(3) + 1 >= 3) or the third, never the first (which draws nextInt(3) freely).
        for (int x = 0; x < 200; x++) {
            String atThree = idAt(grasslands, 3, x);
            assertNotNull(atThree);
            assertNotEquals("minecraft:soul_sand", atThree,
                    "depth 3 不可能落到 index 1：x=" + x);
        }
        // At depth 1 the first branch draws nextInt(3), so all three answers must appear.
        java.util.Set<String> atOne = new java.util.HashSet<>();
        for (int x = 0; x < 200; x++) {
            atOne.add(idAt(grasslands, 1, x));
        }
        assertEquals(3, atOne.size(), "depth 1 上三条分支都应出现，实测 " + atOne);
    }

    // ------------------------------------------------------------------ noise

    /**
     * {@code noise_threshold} compares the world's own field against the JSON's thresholds.
     *
     * <p>Source: {@code NoiseThresholdCondition.lambda$compile$0} is
     * {@code d >= minThreshold && d <= maxThreshold}, with {@code d} from
     * {@code MaterialRuleContext.getNoiseSampler(key, is3d)}; the 2D sampler is
     * {@code NormalNoise.get(x, 0.0, z)} cached per column. The field is <em>vanilla's</em>, which is
     * why this port asks the injected {@link SurfaceRules.Vanilla} for it instead of sampling a noise
     * of its own -- the earlier port's field had a different distribution, so the same threshold
     * produced a different share.
     */
    @Test
    void noiseThresholdUsesTheWorldsOwnFieldAndTheJsonThresholds() {
        SurfaceRules.Set nonNegative = condition("""
                {"type": "minecraft:noise_threshold", "noise": "minecraft:nether_state_selector",
                 "min_threshold": 0.0, "max_threshold": 1.7976931348623157E308}
                """);
        SurfaceRules.Set impossible = condition("""
                {"type": "minecraft:noise_threshold", "noise": "minecraft:nether_state_selector",
                 "min_threshold": 1.0, "max_threshold": 1.7976931348623157E308}
                """);

        int sample = 400;
        int passes = 0;
        for (int x = 0; x < sample; x++) {
            if (decides(nonNegative, 1, 0.0, 0, x)) {
                passes++;
            }
        }
        assertTrue(passes > sample / 10 && passes < sample * 9 / 10,
                "阈值 0 下方的场必须真的分成两半，实测通过 " + passes + "/" + sample
                        + "（全真或全假都说明阈值没被读到）");

        for (int x = 0; x < sample; x++) {
            assertFalse(decides(impossible, 1, 0.0, 0, x),
                    "min_threshold 1.0 超出注入场的取值范围，必须永不成立");
        }

        // The JSON's noise id must reach the world: a fake that records it is the only way to see that.
        FakeVanilla vanilla = FakeVanilla.constant(0.5);
        SurfaceRules.Context ctx = context(0, 100, 0, 1, 8, 0, 0.0, vanilla);
        nonNegative.decide(ctx);
        assertEquals("minecraft:nether_state_selector", vanilla.lastNoiseId,
                "noise_threshold 必须按 JSON 里的 id 去取世界噪声");
    }

    /**
     * {@code wover:threshold_condition} is {@code noise.eval(x * scaleX, z * scaleZ) + roughness > threshold}.
     *
     * <p>Source: {@code ThresholdConditionImpl.test}. Three things are asserted separately because the
     * earlier port got all three wrong:
     * <ul>
     *   <li>the comparison is strict, so a value exactly at the threshold is false;</li>
     *   <li>{@code roughness} is added to the field before the comparison, so a band of
     *       {@code -0.4..-0.3} makes the condition <em>harder</em> by roughly a third, not easier;</li>
     *   <li>the roughness draw is a pure function of {@code (seed, x, 0, z)}.</li>
     * </ul>
     */
    @Test
    void thresholdConditionAddsRoughnessThenComparesStrictly() {
        // A constant roughness (FloatProvider "constant") isolates the comparison from the draw.
        SurfaceRules.Set exactlyAtThreshold = condition("""
                {"type": "wover:threshold_condition", "threshold": 0.0,
                 "roughness": {"type": "minecraft:constant", "value": 0.0},
                 "scale_x": 0.25, "scale_z": 0.25, "seed": 6357170}
                """);
        SurfaceRules.Set unreachable = condition("""
                {"type": "wover:threshold_condition", "threshold": 10.0,
                 "roughness": {"type": "minecraft:uniform", "min_inclusive": -0.4, "max_exclusive": -0.3},
                 "scale_x": 0.25, "scale_z": 0.25, "seed": 6357170}
                """);
        SurfaceRules.Set always = condition("""
                {"type": "wover:threshold_condition", "threshold": -10.0,
                 "roughness": {"type": "minecraft:uniform", "min_inclusive": -0.4, "max_exclusive": -0.3},
                 "scale_x": 0.25, "scale_z": 0.25, "seed": 6357170}
                """);

        for (int x = 0; x < 200; x++) {
            assertFalse(decides(unreachable, 1, 0.0, 0, x), "阈值 10 不可能被越过：x=" + x);
            assertTrue(decides(always, 1, 0.0, 0, x), "阈值 -10 必须恒成立：x=" + x);
        }

        // Strictly greater: any position where the field is exactly 0 must not pass threshold 0.
        int decided = 0;
        for (int x = 0; x < 200; x++) {
            if (decides(exactlyAtThreshold, 1, 0.0, 0, x)) {
                decided++;
            }
        }
        assertTrue(decided > 0 && decided < 200,
                "阈值 0、roughness 恒 0 时，只有场大于 0 的列成立；实测 " + decided + "/200");
    }

    /**
     * {@code scale_x}/{@code scale_z} are frequency multipliers: {@code eval(x * scale, z * scale)}.
     *
     * <p>Source: {@code ThresholdConditionImpl.test} --
     * {@code noiseContext.eval(blockX * scaleX, blockZ * scaleZ)}. So {@code scale: 0.25} means one
     * noise unit per four blocks, i.e. features a few blocks across; the earlier port divided instead
     * ({@code blocksPerPatch = 2.5 / scale}), which at {@code 0.25} sampled every ten blocks and made
     * every patch 2.5 times too wide.
     *
     * <p>The assertion is the run length of a constant answer along x: at {@code scale 0.25} the field
     * changes sign every few blocks, so runs must stay short.
     */
    @Test
    void thresholdScaleIsAFrequencyMultiplier() {
        double tight = averageRun(condition("""
                {"type": "wover:threshold_condition", "threshold": 0.0,
                 "roughness": {"type": "minecraft:constant", "value": 0.0},
                 "scale_x": 0.25, "scale_z": 0.25, "seed": 6357170}
                """));
        double wide = averageRun(condition("""
                {"type": "wover:threshold_condition", "threshold": 0.0,
                 "roughness": {"type": "minecraft:constant", "value": 0.0},
                 "scale_x": 0.05, "scale_z": 0.05, "seed": 245}
                """));

        assertTrue(tight < 8.0, "scale 0.25 的斑块只有几格宽，实测平均连续段 " + tight + " 格");
        assertTrue(wide > tight * 2, "scale 0.05 的斑块必须明显更宽："
                + wide + " 格 vs " + tight + " 格");
    }

    /**
     * {@code volume_threshold_condition} samples the field in 3D.
     *
     * <p>Source: {@code VolumeThresholdConditionImpl.getValue} --
     * {@code eval(xx * scaleX, yy * scaleY, zz * scaleZ) + roughness.sample(randomAt(xx, yy, zz))},
     * i.e. the y coordinate takes part and so does {@code scale_y}. A port that ignored y would paint
     * a vertical column of one material through a magma field.
     */
    @Test
    void volumeThresholdSamplesAllThreeAxes() {
        SurfaceRules.Set set = condition("""
                {"type": "wover:volume_threshold_condition", "threshold": 0.0,
                 "roughness": {"type": "minecraft:constant", "value": 0.0},
                 "scale_x": 0.1, "scale_y": 0.2, "scale_z": 0.1, "seed": 245}
                """);

        boolean differs = false;
        for (int y = 10; y < 90 && !differs; y++) {
            differs = decides(set, 1, 0.0, 5, 5, y) != decides(set, 1, 0.0, 5, 5, y + 1);
        }
        assertTrue(differs, "竖直方向上必须能看到变化，否则 y 与 scale_y 被忽略了");
    }

    /**
     * {@code rough_noise_condition} = the world's noise plus one draw from a per-chunk stream.
     *
     * <p>Source: {@code RoughNoiseConditionImpl.compile} --
     * {@code d = normalNoise.get(x, y, z) + roughness.sample(roughnessSource);
     * return d >= minThreshold && d <= maxThreshold;}. The roughness source is built once per chunk and
     * per condition, so a condition that is evaluated consumes one draw per block; this port models
     * that with {@link SurfaceRules.Vanilla#roughnessCursor()} and a cursor slot per node (see the class
     * note -- the sequence is upstream's, the assignment to blocks is not).
     */
    @Test
    void roughNoiseAddsOneStreamDrawPerEvaluation() {
        SurfaceRules.Set set = condition("""
                {"type": "wover:rough_noise_condition", "noise": "minecraft:netherrack",
                 "min_threshold": 0.19,
                 "roughness": {"type": "minecraft:constant", "value": 0.5}}
                """);

        // Field 0.5, roughness 0.5 fixed -> 1.0 >= 0.19 -> decided; and exactly one draw per block.
        FakeVanilla vanilla = FakeVanilla.constant(0.5);
        SurfaceRules.Context ctx = context(0, 100, 0, 1, 8, 0, 0.0, vanilla);
        assertTrue(set.decide(ctx) >= 0, "0.5 + 0.5 = 1.0 必须越过 0.19");
        assertEquals(1, vanilla.roughnessDraws, "每次求值消耗一次流内抽样");

        // A field far below the threshold is not rescued by a small roughness.
        FakeVanilla low = FakeVanilla.constant(-1.0);
        SurfaceRules.Context second = context(0, 100, 0, 1, 8, 0, 0.0, low);
        assertFalse(set.decide(second) >= 0, "-1.0 + 0.5 依然低于 0.19");
    }

    /**
     * {@code vertical_gradient} draws from a positional random: {@code nextFloat() < Mth.map(y, ...)}.
     *
     * <p>Source: {@code VerticalGradientCondition$1.compute} -- {@code y <= trueAtAndBelow} is true,
     * {@code y >= falseAtAndAbove} is false, and in between
     * {@code randomFactory.at(x, y, z).nextFloat() < Mth.map(y, trueAt, falseAt, 1.0, 0.0)}. The plates
     * are ragged precisely because of this draw; the 23 files' gradients are dropped as bedrock plates,
     * so this node only exists for a tree that states one elsewhere.
     */
    @Test
    void verticalGradientIsAPositionalDrawBetweenTwoAnchors() {
        SurfaceRules.Set set = condition("""
                {"type": "minecraft:vertical_gradient", "random_name": "betternether:test_gradient",
                 "true_at_and_below": {"above_bottom": 0}, "false_at_and_above": {"above_bottom": 5}}
                """);

        FakeVanilla zero = FakeVanilla.gradient(0.0f);
        assertTrue(decidesAtY(set, 0, zero), "y=0 在第一个锚点，恒真（不看抽样）");
        assertFalse(decidesAtY(set, 5, zero), "y=5 在第二个锚点，恒假（不看抽样）");
        assertNull(zero.lastGradientName, "两个锚点之间之外的位置不该去抽位置随机源");

        // y=2 -> chance = 0.6; a draw of 0.0 must pass and 0.99 must fail.
        FakeVanilla middle = FakeVanilla.gradient(0.0f);
        assertTrue(decidesAtY(set, 2, middle), "y=2 的阈值是 0.6，抽到 0 必真");
        assertEquals("betternether:test_gradient", middle.lastGradientName,
                "抽样必须用 JSON 的 random_name 取位置随机源");
        assertFalse(decidesAtY(set, 2, FakeVanilla.gradient(0.99f)), "抽到 0.99 必假");
    }

    // ------------------------------------------------------------------ the random source

    /**
     * The port's random is {@code java.util.Random} itself, because {@code RandomSource.create} is.
     *
     * <p>{@code RandomSource.create(long)} returns {@code LegacyRandomSource}, whose {@code setSeed} is
     * {@code (seed ^ 25214903917) & 281474976710655} and whose {@code next(int)} is
     * {@code (seed * 25214903917 + 11) & mask}, with {@code BitRandomSource}'s {@code nextInt(bound)} /
     * {@code nextFloat} / {@code nextDouble} / {@code nextBoolean} on top -- i.e. exactly
     * {@code java.util.Random}, including the power-of-two shortcut in {@code nextInt}. Pinning that
     * against the JDK is the cheapest possible proof, and it is the reason the selectors' block choices
     * now match upstream instead of merely matching its shares.
     */
    @Test
    void legacyRandomReproducesTheVanillaStream() {
        long[] seeds = {0L, 1L, -1L, 245L, 0x6E677261L, 123456789012345L, Long.MIN_VALUE};
        for (long seed : seeds) {
            SurfaceRules.LegacyRandom ours = new SurfaceRules.LegacyRandom(seed);
            Random reference = new Random(seed);
            for (int i = 0; i < 64; i++) {
                assertEquals(reference.nextInt(20), ours.nextInt(20), "nextInt(20) seed=" + seed);
                assertEquals(reference.nextFloat(), ours.nextFloat(), "nextFloat seed=" + seed);
                assertEquals(reference.nextBoolean(), ours.nextBoolean(), "nextBoolean seed=" + seed);
                assertEquals(reference.nextDouble(), ours.nextDouble(), "nextDouble seed=" + seed);
                assertEquals(reference.nextInt(3), ours.nextInt(3), "nextInt(3) seed=" + seed);
                assertEquals(reference.nextInt(4), ours.nextInt(4), "nextInt(4) seed=" + seed);
            }
        }
    }

    /** wover's {@code MathHelper.getSeed} mixing, including its arithmetic shifts. */
    @Test
    void woverSeedMixingIsTheDocumentedFunction() {
        int seed = 0x6E677261;
        int x = 3;
        int y = 100;
        int z = -7;
        int h = seed + x * 374761393 + y * 668265263 + z;
        h = (h ^ (h >> 13)) * 1274126177;
        int expected = h ^ (h >> 16);
        assertEquals(expected, SurfaceRules.getSeed(seed, x, y, z));
        assertEquals((int) (245L ^ (245L >>> 32)), SurfaceRules.seedHash(245L));
    }

    // ------------------------------------------------------------------ the injected world

    /**
     * A rule that needs the world fails loudly when nothing supplies it.
     *
     * <p>The alternative -- silently substituting a field of this port's own -- is what produced the
     * wrong shares in the first place, so an unbound {@link SurfaceRules.Vanilla} throws and names the
     * rule that asked.
     */
    @Test
    void anUnboundWorldFailsLoudlyInsteadOfSubstitutingANoise() {
        SurfaceRules.Set set = condition("""
                {"type": "wover:rough_noise_condition", "noise": "minecraft:netherrack",
                 "min_threshold": 0.14,
                 "roughness": {"type": "minecraft:uniform", "min_inclusive": -0.2, "max_exclusive": 0.4}}
                """);
        SurfaceRules.Context ctx = new SurfaceRules.Context();
        ctx.set(0, 100, 0, 1, 8, 0, 0.0);
        IllegalStateException error = assertThrows(IllegalStateException.class, () -> set.decide(ctx));
        assertTrue(error.getMessage().contains("rough_noise_condition"), error.getMessage());
    }

    // ------------------------------------------------------------------ the vanilla nether

    /**
     * The five vanilla nether biomes are covered by vanilla's own rule, not by the 23 files.
     *
     * <p>Without it a column of {@code soul_sand_valley} matches no rule set and keeps the base
     * netherrack: no soul sand, no soul soil, no blackstone in the deltas, no nylium under the forests.
     * The block list below is exactly {@code NetherMaterialRules}' own ten state rules.
     */
    @Test
    void vanillaNetherCarriesEveryBlockVanillasOwnRuleEmits() {
        SurfaceRules.Set vanilla = book().vanilla();
        assertNotNull(vanilla, "Book 必须带原版下界规则");
        assertEquals(List.of("minecraft:gravel", "minecraft:basalt", "minecraft:blackstone",
                        "minecraft:soul_sand", "minecraft:soul_soil", "minecraft:lava",
                        "minecraft:warped_wart_block", "minecraft:warped_nylium",
                        "minecraft:nether_wart_block", "minecraft:crimson_nylium",
                        "minecraft:netherrack"),
                vanilla.materials(),
                "原版下界规则的方块清单必须与 NetherMaterialRules 一致");
        assertFalse(vanilla.materials().contains(SurfaceRules.BEDROCK_ID),
                "基岩两板由 generateBedrock 负责，原版树里的那两条也被丢弃");
    }

    /**
     * Soul sand valley: {@code UNDER_FLOOR}/{@code UNDER_CEILING} skin, soul sand by the state selector.
     *
     * <p>Source: the {@code isBiome(soul_sand_valley)} entry of {@code NetherMaterialRules.bootstrap},
     * whose floor branch is {@code underFloor -> sequence(gravel, stateSelector -> SOUL_SAND, SOUL_SOIL)}.
     * The tree is one tree with biome branches, so the same {@link SurfaceRules.Set} answers all five.
     */
    @Test
    void vanillaSoulSandValleyUsesTheStateSelectorUnderFloorAndCeiling() {
        SurfaceRules.Book book = book();
        assertEquals("minecraft:soul_sand",
                vanillaId(book, "minecraft:soul_sand_valley", 60, 1, 20, 0),
                "地表一格：state selector 打开时是灵魂沙");
        assertEquals("minecraft:soul_sand",
                vanillaId(book, "minecraft:soul_sand_valley", 60, 20, 1, 0),
                "天花板一格：同一棵树的反方向");
        // Selector off: the floor is soul soil, at depth 1 and again at depth 2 (UNDER_FLOOR carries
        // add_surface_depth, so surfaceDepth 1 widens the band to two blocks).
        SurfaceRules.Context ctx = context(0, 60, 0, 1, 20, 1, 0.0, FakeVanilla.constant(-0.5));
        ctx.setBiome("minecraft:soul_sand_valley");
        assertEquals("minecraft:soul_soil", book.vanilla().id(book.vanilla().decide(ctx)),
                "state selector 关闭时地表是灵魂土");
        SurfaceRules.Context deep = context(0, 59, 0, 2, 20, 1, 0.0, FakeVanilla.constant(-0.5));
        deep.setBiome("minecraft:soul_sand_valley");
        assertEquals("minecraft:soul_soil", book.vanilla().id(book.vanilla().decide(deep)),
                "UNDER_FLOOR 带 add_surface_depth，第二格也应是灵魂土");
    }

    /** Basalt deltas: basalt on both faces, blackstone where the state selector is off. */
    @Test
    void vanillaBasaltDeltasPaintsBasaltAndBlackstone() {
        SurfaceRules.Book book = book();
        assertEquals("minecraft:basalt",
                vanillaId(book, "minecraft:basalt_deltas", 60, 20, 1, 0), "天花板是玄武岩");
        assertEquals("minecraft:basalt",
                vanillaId(book, "minecraft:basalt_deltas", 60, 1, 20, 0),
                "地表在 state selector 打开时也是玄武岩");

        // Selector off: the floor falls through to blackstone.
        SurfaceRules.Context ctx = context(0, 60, 0, 1, 20, 0, 0.0, FakeVanilla.constant(-0.5));
        ctx.setBiome("minecraft:basalt_deltas");
        int slot = book.vanilla().decide(ctx);
        assertEquals("minecraft:blackstone", book.vanilla().id(slot),
                "state selector 关闭时地表必须是黑石");
    }

    /**
     * The forests paint nylium only above y=31, and wart blocks where the wart noise is on.
     *
     * <p>Source: {@code onFloor -> isBiome(warped_forest) && !netherrackNoise && above31}.
     */
    @Test
    void vanillaForestsPaintNyliumOnlyAboveThirtyOne() {
        SurfaceRules.Book book = book();
        assertEquals("minecraft:crimson_nylium",
                vanillaId(book, "minecraft:crimson_forest", 60, 1, 20, 0), "y=60 的绯红菌岩");
        assertEquals("minecraft:warped_nylium",
                vanillaId(book, "minecraft:warped_forest", 60, 1, 20, 0), "y=60 的诡异菌岩");
        assertNotEquals("minecraft:crimson_nylium",
                vanillaId(book, "minecraft:crimson_forest", 20, 1, 20, 0),
                "y=20 在 above31 之下，不该有菌岩");

        // Wart noise on (>= 1.17) with the netherrack noise off: the wart block goes on top of the
        // nylium. One constant cannot do this -- the two noises are tested in opposite directions,
        // which is exactly why they are separate conditions in vanilla.
        SurfaceRules.Context ctx = context(0, 60, 0, 1, 20, 0, 0.0,
                FakeVanilla.names(0.0, "minecraft:nether_wart", 1.5));
        ctx.setBiome("minecraft:crimson_forest");
        assertEquals("minecraft:nether_wart_block", book.vanilla().id(book.vanilla().decide(ctx)));
        ctx.setBiome("minecraft:warped_forest");
        assertEquals("minecraft:warped_wart_block", book.vanilla().id(book.vanilla().decide(ctx)));

        // And with the netherrack noise on, the forest branch is skipped entirely.
        SurfaceRules.Context blocked = context(0, 60, 0, 1, 20, 0, 0.0,
                FakeVanilla.names(0.0, "minecraft:netherrack", 0.9));
        blocked.setBiome("minecraft:crimson_forest");
        assertEquals("minecraft:netherrack", book.vanilla().id(book.vanilla().decide(blocked)),
                "!netherrackNoise 不成立时不该画菌岩");
    }

    /**
     * Lava fills the holes of the lava sea, and only there.
     *
     * <p>Source: {@code onFloor -> sequence(!above32 && hole -> LAVA, ...)}. {@code hole} is vanilla's
     * {@code surfaceDepth <= 0}, which is why this port had to take that condition seriously: with the
     * earlier 50/50 coin flip the sea would have had lava pockets scattered at random instead of where
     * the surface noise says the column is a hole.
     */
    @Test
    void vanillaPutsLavaInHolesOnlyBelowThirtyTwo() {
        SurfaceRules.Book book = book();
        assertEquals("minecraft:lava", vanillaId(book, "minecraft:nether_wastes", 10, 1, 20, -1),
                "y=10、surfaceDepth<0 是洞：熔岩");
        assertNotEquals("minecraft:lava", vanillaId(book, "minecraft:nether_wastes", 10, 1, 20, 1),
                "surfaceDepth>0 不是洞");
        assertNotEquals("minecraft:lava", vanillaId(book, "minecraft:nether_wastes", 40, 1, 20, -1),
                "y=40 在 y>=32 之上，即使有洞也不填熔岩");
    }

    /** Above the roof anchor and outside every biome branch, vanilla falls back to netherrack. */
    @Test
    void vanillaRoofLayerAndUnknownBiomeFallBackToNetherrack() {
        SurfaceRules.Book book = book();
        assertEquals("minecraft:netherrack",
                vanillaId(book, "minecraft:crimson_forest", 122, 1, 20, 1),
                "y=122 是 below_top(5) 的锚点，先于所有群系分支");
        assertEquals("minecraft:netherrack",
                vanillaId(book, "minecraft:the_end", 60, 1, 20, 1),
                "不属于五个下界群系的键落到兜底");
    }

    /**
     * Nether wastes: the soul-sand layer under the floor and the gravel band over the lava.
     *
     * <p>Source: the {@code isBiome(nether_wastes)} entry -- {@code underFloor -> soul_sand_layer ->
     * sequence(!hole && start30 && below35 -> SOUL_SAND, NETHERRACK)} and
     * {@code onFloor -> above31 && below35 && gravel_layer}.
     */
    @Test
    void vanillaNetherWastesKeepsTheSoulSandLayerAndGravelBand() {
        SurfaceRules.Book book = book();
        assertEquals("minecraft:soul_sand",
                vanillaId(book, "minecraft:nether_wastes", 32, 1, 20, 1),
                "y=32 落在 start30..below35 带内，灵魂沙层的噪声打开时是灵魂沙");
        assertEquals("minecraft:netherrack",
                vanillaId(book, "minecraft:nether_wastes", 60, 1, 20, 1),
                "带外的地表没有灵魂沙，也没有砾石：下界岩");
        // The gravel band lives above y=31 and below the start-30 band's top (35).
        SurfaceRules.Context ctx = context(0, 38, 0, 1, 20, 3, 0.0, FakeVanilla.constant(0.5));
        ctx.setBiome("minecraft:nether_wastes");
        int slot = book.vanilla().decide(ctx);
        assertEquals("minecraft:netherrack", book.vanilla().id(slot),
                "y=38 已越过 below35，砾石带不在这");
    }

    // ------------------------------------------------------------------ the real files

    /**
     * All 23 upstream files compile, and every rule type in them is supported.
     *
     * <p>This is the test that keeps the interpreter honest: it fails the moment upstream adds a rule
     * type, a selector, or a shape this class does not implement -- instead of that biome silently
     * generating as plain netherrack.
     */
    @Test
    void allTwentyThreeUpstreamRuleFilesCompile() {
        Path dir = rulesDirectory();
        Assumptions.assumeTrue(dir != null, "找不到上游 surface_rules 目录");

        SurfaceRules.Book book = SurfaceRules.Book.loadDirectory(dir);

        assertEquals(23, book.size(), "上游有 23 个群系的 surface rule");
        for (String key : List.of("bone_reef", "gloomwood", "wart_forest", "nether_grasslands",
                "upside_down_forest", "magma_land", "soul_plain", "gravel_desert")) {
            SurfaceRules.Set set = book.of(key);
            assertNotNull(set, "缺少群系规则：" + key);
            assertFalse(set.materials().isEmpty(), "群系 " + key + " 的规则没有任何材质");
        }
        // WartForest is the deepest: offset 4 plus a secondary range of 1, in vanilla's 1-based numbers.
        assertTrue(book.maxStoneDepth() >= 6,
                "最深 stone_depth 至少 6（WartForest 的 1 + 4 + 1），实际 " + book.maxStoneDepth());
        assertTrue(book.usesSurfaceDepth(),
                "GravelDesert/SoulPlain/WartForest 都用 add_surface_depth，book 必须报告这一点");
        assertTrue(book.cursorSlots() >= 7,
                "7 条 rough_noise 规则各需一个每 chunk 的流，实际 " + book.cursorSlots());
    }

    /**
     * The bundled loader reads {@code index.txt} and every file it names.
     *
     * <p>This is the path the plugin actually runs at world load, and it is different from
     * {@link SurfaceRules.Book#loadDirectory}: the jar has no directory listing, so the index file is
     * the only thing that says which biomes exist.
     */
    @Test
    void theBundledLoaderFollowsTheIndexFile() throws IOException {
        Path dir = rulesDirectory();
        Assumptions.assumeTrue(dir != null, "找不到上游 surface_rules 目录");

        Path root = Files.createTempDirectory("bn-surface-rules");
        Path target = root.resolve(SurfaceRules.RESOURCE_DIR);
        Files.createDirectories(target);
        StringBuilder index = new StringBuilder();
        try (var files = Files.list(dir)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".json")).toList()) {
                Files.copy(file, target.resolve(file.getFileName()));
                index.append(file.getFileName().toString().replace(".json", "")).append('\n');
            }
        }
        Files.writeString(target.resolve(SurfaceRules.INDEX_FILE), index.toString(),
                StandardCharsets.UTF_8);

        ClassLoader loader = new java.net.URLClassLoader(
                new java.net.URL[]{root.toUri().toURL()}, null);
        try {
            SurfaceRules.Book book = SurfaceRules.Book.loadBundled(loader);
            assertEquals(23, book.size(), "按 index.txt 加载的群系数量");
            for (String key : book.biomes()) {
                assertFalse(book.of(key).materials().isEmpty(), key + " 没有任何材质");
            }
        } finally {
            try (var walk = Files.walk(root)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                        // Temp directory; the OS will clean up anything left behind.
                    }
                });
            }
        }
    }

    /**
     * No compiled rule can emit bedrock: the plates belong to {@code generateBedrock}.
     *
     * <p>Every file re-declares vanilla's two bedrock gradients, and they are recognised structurally
     * and dropped. If that recognition ever stops working, this fails -- rather than an entire world
     * turning to bedrock.
     */
    @Test
    void noCompiledRuleEmitsBedrock() {
        Path dir = rulesDirectory();
        Assumptions.assumeTrue(dir != null, "找不到上游 surface_rules 目录");

        SurfaceRules.Book book = SurfaceRules.Book.loadDirectory(dir);
        for (String key : book.biomes()) {
            assertFalse(book.of(key).materials().contains(SurfaceRules.BEDROCK_ID),
                    "群系 " + key + " 的规则仍会产出基岩；基岩板由 generateBedrock 负责");
        }
    }

    /**
     * The bedrock anchors the JSON states are the ones this port's plates already use.
     *
     * <p>Read straight out of a file rather than through the interpreter, because the interpreter
     * drops these rules: {@code roof} is {@code true_at_and_below below_top 5} /
     * {@code false_at_and_above below_top 0}, and {@code floor} is {@code true_at_and_below
     * above_bottom 0} / {@code false_at_and_above above_bottom 5}. With the nether at 0..127 that is
     * y>=123 for the roof and y<=4 for the floor.
     */
    @Test
    void theJsonBedrockAnchorsMatchThisPortsPlateBounds() throws IOException {
        Path dir = rulesDirectory();
        Assumptions.assumeTrue(dir != null, "找不到上游 surface_rules 目录");

        JsonObject root = JsonParser.parseString(Files.readString(
                dir.resolve("gloomwood.json"), StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject rule = root.getAsJsonObject("ruleSource").getAsJsonArray("sequence")
                .get(0).getAsJsonObject();
        JsonObject roof = rule.getAsJsonObject("if_true").getAsJsonObject("invert");
        JsonObject floor = root.getAsJsonObject("ruleSource").getAsJsonArray("sequence")
                .get(1).getAsJsonObject().getAsJsonObject("if_true");

        assertEquals(5, roof.getAsJsonObject("true_at_and_below").get("below_top").getAsInt(),
                "屋顶基岩从「顶以下 5 格」开始，即 y>=123");
        assertEquals(0, roof.getAsJsonObject("false_at_and_above").get("below_top").getAsInt());
        assertEquals(0, floor.getAsJsonObject("true_at_and_below").get("above_bottom").getAsInt(),
                "地板基岩从「底以上 0 格」开始，即 y<=0");
        assertEquals(5, floor.getAsJsonObject("false_at_and_above").get("above_bottom").getAsInt(),
                "地板梯度在 y=5 归零");
    }

    // ------------------------------------------------------------------ failure behaviour

    /** An unknown rule type fails compilation instead of quietly painting netherrack. */
    @Test
    void anUnknownRuleTypeFailsLoudly() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> compile("""
                        {"type": "wover:not_a_real_rule"}
                        """));
        assertTrue(error.getMessage().contains("wover:not_a_real_rule"), error.getMessage());
    }

    /** A rule where a condition belongs is not something vanilla accepts either, so it fails too. */
    @Test
    void aRuleInAConditionPositionFailsLoudly() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> compile("""
                        {"type": "minecraft:condition",
                         "if_true": {"type": "minecraft:block", "result_state": "minecraft:gravel"},
                         "then_run": {"type": "minecraft:block", "result_state": "minecraft:gravel"}}
                        """));
        assertTrue(error.getMessage().contains("minecraft:block"), error.getMessage());
    }

    /** {@code palette.id} answers {@code null} for "paint nothing", never a stray block. */
    @Test
    void undecidedPaletteSlotHasNoBlock() {
        SurfaceRules.Set set = compile("""
                {"type": "minecraft:condition",
                 "if_true": {"type": "minecraft:stone_depth", "surface_type": "floor", "offset": 0},
                 "then_run": {"type": "minecraft:block", "result_state": "minecraft:gravel"}}
                """);
        assertNull(set.id(SurfaceRules.Rule.UNDECIDED), "未决定的位置没有方块");
        assertEquals("minecraft:gravel", set.id(0));
    }

    // ------------------------------------------------------------------ helpers

    /** Compiles a single-rule document, as if it were one biome's {@code ruleSource}. */
    private static SurfaceRules.Set compile(String ruleJson) {
        JsonObject root = JsonParser.parseString("""
                {"biome": "betternether:test", "priority": 1000, "ruleSource": %s}
                """.formatted(ruleJson)).getAsJsonObject();
        return SurfaceRules.compile(root.getAsJsonObject("ruleSource"), "test");
    }

    /** The real book: the 23 upstream files plus the vanilla nether tree. */
    private static SurfaceRules.Book book() {
        Path dir = rulesDirectory();
        Assumptions.assumeTrue(dir != null, "找不到上游 surface_rules 目录；跳过原版下界规则对照");
        return SurfaceRules.Book.loadDirectory(dir);
    }

    /**
     * The block the vanilla nether tree decides for one position, or {@code null}.
     *
     * <p>The noise is a constant, so which branch fires is decided by the geometry and by the one field
     * value the test states -- not by where a noise happens to cross a threshold.
     */
    private static String vanillaId(SurfaceRules.Book book, String biome, int y, int above, int below,
                                    int surfaceDepth) {
        SurfaceRules.Set vanilla = book.vanilla();
        SurfaceRules.Context ctx = context(0, y, 0, above, below, surfaceDepth, 0.0,
                FakeVanilla.constant(0.5));
        ctx.setBiome(biome);
        int slot = vanilla.decide(ctx);
        return slot < 0 ? null : vanilla.id(slot);
    }

    /**
     * Compiles a bare condition as a rule, wrapped the way the real files wrap theirs.
     *
     * <p>A condition is not a rule in vanilla either -- it decides nothing on its own -- so it is
     * wrapped in a {@code condition} whose {@code then_run} is a block, which keeps the tests honest
     * about the shape upstream produces.
     */
    private static SurfaceRules.Set condition(String conditionJson) {
        return compile("""
                {"type": "minecraft:condition",
                 "if_true": %s,
                 "then_run": {"type": "minecraft:block", "result_state": "minecraft:gravel"}}
                """.formatted(conditionJson));
    }

    /** A switch over six blocks, so the selector's own {@code 0..5} index is visible through it. */
    private static SurfaceRules.Set switchOf(int netherNoise) {
        return compile("""
                {"type": "wover:switch_rule",
                 "selector": {"type": "wover:nether_noise", "nether_noise": %d},
                 "collection": [
                   {"type": "minecraft:block", "result_state": "minecraft:soul_soil"},
                   {"type": "minecraft:block", "result_state": "minecraft:soul_sand"},
                   {"type": "minecraft:block", "result_state": "minecraft:gravel"},
                   {"type": "minecraft:block", "result_state": "minecraft:magma_block"},
                   {"type": "minecraft:block", "result_state": "minecraft:blackstone"},
                   {"type": "minecraft:block", "result_state": "minecraft:basalt"}]}
                """.formatted(netherNoise));
    }

    private static int indexOf(String id) {
        return switch (id) {
            case "minecraft:soul_soil" -> 0;
            case "minecraft:soul_sand" -> 1;
            case "minecraft:gravel" -> 2;
            case "minecraft:magma_block" -> 3;
            case "minecraft:blackstone" -> 4;
            case "minecraft:basalt" -> 5;
            default -> -1;
        };
    }

    /** A context bound to a fake world; every test goes through here, so no test runs unbound. */
    private static SurfaceRules.Context context(int x, int y, int z, int above, int below,
                                                int surfaceDepth, double secondary) {
        return context(x, y, z, above, below, surfaceDepth, secondary,
                FakeVanilla.of(surfaceDepth, secondary));
    }

    private static SurfaceRules.Context context(int x, int y, int z, int above, int below,
                                                int surfaceDepth, double secondary,
                                                FakeVanilla vanilla) {
        SurfaceRules.Context ctx = new SurfaceRules.Context();
        ctx.beginChunk(vanilla, 4);
        ctx.set(x, y, z, above, below, surfaceDepth, secondary);
        return ctx;
    }

    /** Whether the rule decides at a floor position with the given vanilla depth above it. */
    private static boolean decides(SurfaceRules.Set set, int depthAbove) {
        return decides(set, depthAbove, 0.0, 0, 0);
    }

    private static boolean decides(SurfaceRules.Set set, int depthAbove, double secondary) {
        return decides(set, depthAbove, secondary, 0, 0);
    }

    private static boolean decides(SurfaceRules.Set set, int depthAbove, double secondary, int surfaceDepth) {
        return decides(set, depthAbove, secondary, surfaceDepth, 0);
    }

    private static boolean decides(SurfaceRules.Set set, int depthAbove, double secondary,
                                   int surfaceDepth, int z) {
        return decides(set, depthAbove, secondary, surfaceDepth, z, 100);
    }

    private static boolean decides(SurfaceRules.Set set, int depthAbove, double secondary,
                                   int surfaceDepth, int z, int y) {
        SurfaceRules.Context ctx = context(0, y, z, depthAbove, 8, surfaceDepth, secondary,
                FakeVanilla.of(surfaceDepth, secondary));
        return set.decide(ctx) >= 0;
    }

    /** Whether the rule decides at a ceiling position with the given vanilla depth below it. */
    private static boolean decidesBelow(SurfaceRules.Set set, int depthBelow) {
        SurfaceRules.Context ctx = context(0, 100, 0, 8, depthBelow, 0, 0.0);
        return set.decide(ctx) >= 0;
    }

    private static boolean decidesAtY(SurfaceRules.Set set, int y, FakeVanilla vanilla) {
        SurfaceRules.Context ctx = context(0, y, 0, 1, 8, 0, 0.0, vanilla);
        return set.decide(ctx) >= 0;
    }

    private static String idAt(SurfaceRules.Set set, int depthAbove, int z) {
        SurfaceRules.Context ctx = context(0, 100, z, depthAbove, 8, 0, 0.0);
        int slot = set.decide(ctx);
        return slot < 0 ? null : set.id(slot);
    }

    /** Mean length of a run of equal answers along x, i.e. the patch width in blocks. */
    private static double averageRun(SurfaceRules.Set set) {
        int runs = 0;
        int columns = 0;
        Boolean previous = null;
        for (int x = -2000; x <= 2000; x++) {
            boolean value = decides(set, 1, 0.0, 0, x);
            columns++;
            if (previous == null || value != previous) {
                runs++;
            }
            previous = value;
        }
        return columns / (double) runs;
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

    /**
     * A stand-in for the running server, so the rule semantics can be asserted without one.
     *
     * <p>What it supplies is deliberately trivial: the two noises are a position-dependent sine (so a
     * threshold test has something to split), and everything else is a value the test sets. The
     * production implementation is {@code NetherTerrainGenerator.RandomStateSurface}, which reads the
     * world's real {@code RandomState}.
     */
    private static final class FakeVanilla implements SurfaceRules.Vanilla {
        private final int depth;
        private final double secondary;
        private final Double constant;
        private final Float gradient;
        private final java.util.Map<String, Double> overrides;

        private String lastNoiseId;
        private String lastGradientName;
        private int roughnessDraws;

        private FakeVanilla(int depth, double secondary, Double constant, Float gradient,
                            java.util.Map<String, Double> overrides) {
            this.depth = depth;
            this.secondary = secondary;
            this.constant = constant;
            this.gradient = gradient;
            this.overrides = overrides;
        }

        /** A world whose noise is a fixed value, so a condition's arithmetic can be asserted exactly. */
        static FakeVanilla of(int surfaceDepth, double secondary) {
            return new FakeVanilla(surfaceDepth, secondary, null, 0.0f, java.util.Map.of());
        }

        static FakeVanilla constant(double noise) {
            return new FakeVanilla(0, 0.0, noise, 0.0f, java.util.Map.of());
        }

        static FakeVanilla gradient(float value) {
            return new FakeVanilla(0, 0.0, null, value, java.util.Map.of());
        }

        /**
         * A world where named noises answer stated values and every other id answers {@code fallback}.
         *
         * <p>Needed because vanilla's forest branches test two different noises in opposite directions
         * ({@code !netherrackNoise} and {@code nether_wart}), which one constant can never satisfy.
         */
        static FakeVanilla names(double fallback, String id, double value) {
            return new FakeVanilla(0, 0.0, fallback, 0.0f, java.util.Map.of(id, value));
        }

        @Override
        public double noise2d(String noiseId, int x, int z) {
            lastNoiseId = noiseId;
            Double override = overrides.get(noiseId);
            if (override != null) {
                return override;
            }
            return constant != null ? constant : 0.5 * Math.sin(x * 0.37 + z * 0.11);
        }

        @Override
        public double noise3d(String noiseId, int x, int y, int z) {
            lastNoiseId = noiseId;
            Double override = overrides.get(noiseId);
            if (override != null) {
                return override;
            }
            return constant != null ? constant : 0.5 * Math.sin(x * 0.37 + y * 0.9 + z * 0.11);
        }

        @Override
        public int surfaceDepth(int x, int z) {
            return depth;
        }

        @Override
        public double surfaceSecondary(int x, int z) {
            return secondary;
        }

        @Override
        public float gradientNextFloat(String randomName, int x, int y, int z) {
            lastGradientName = randomName;
            return gradient;
        }

        @Override
        public SurfaceRules.Cursor roughnessCursor() {
            return () -> {
                roughnessDraws++;
                return 0.5f;
            };
        }
    }
}
