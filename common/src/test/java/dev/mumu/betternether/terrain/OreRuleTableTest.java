package dev.mumu.betternether.terrain;

import dev.mumu.betternether.config.NetherConfig;
import dev.mumu.betternether.config.NetherConfig.OreRule;
import dev.mumu.betternether.config.NetherConfig.OreShape;
import dev.mumu.betternether.config.NetherConfig.OreStep;
import dev.mumu.betternether.config.NetherConfig.OreTargetState;
import dev.mumu.betternether.config.NetherConfig.VerticalAnchor;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 把矿物规则表钉在上游真正发布的数字上，并钉住「按群系门控」与「链的次序」这两件事。
 *
 * <p>每条断言都对应一个真实存在过的错误：
 * <ul>
 *   <li><b>少条目</b>：{@code nether_ruby_large_ore} 曾被注释成「没有群系引用它，永不生成」。
 *       它的注释来源是猜测，而 {@code ruby_ore_dense.json} 确实把它挂到了
 *       {@code minecraft:soul_sand_valley}。{@code gloomwood_ceiling_debris} 则被整条漏掉 ——
 *       它也是 {@code minecraft:ore}，只是链更长。</li>
 *   <li><b>数值被摊平</b>：旧实现把 placed feature 折成「每区块 28.5 条上限 + 按比例缩放」，
 *       任何一次调低 {@code veins-per-chunk} 都会等比削掉所有矿。</li>
 *   <li><b>忘掉 discard</b>：redstone 的 0.3 与两个 ruby 变体的 0.1 曾被注释成「对 block_match
 *       目标永不触发」。它对任何谓词语义相同，永远会触发。</li>
 *   <li><b>链的次序写错</b>：{@code gloomwood_ceiling_debris} 先抽高度再 count，末尾还有
 *       {@code every_layer} 与 {@code rarity_filter}；按「count 在最前」的模板套会把随机消费
 *       次序搞乱。</li>
 *   <li><b>群系集合写错</b>：{@code nether_swampland} / {@code nether_swampland_terraces} 的
 *       {@code features[7]} 一条 BN 矿都没有。</li>
 * </ul>
 *
 * <p>数据出处：{@code upstream/BetterNether/src/main/generated/data/betternether/worldgen/
 * placed_feature/*.json}、{@code .../feature/*.json}、{@code .../biome/*.json} 的
 * {@code features[7]}，{@code .../wover/worldgen/biome_modifications/
 * {default_ores,ruby_ore,ruby_ore_dense}.json}，以及 {@code registry/features/placed/
 * NetherOresPlaced.java}（step 归属）。
 */
class OreRuleTableTest {
    @Test
    void oreStepIsUndergroundDecorationNotUndergroundOres() {
        // NetherOresPlaced.java：8 条矿全部 .setDecoration(GenerationStep.Decoration.UNDERGROUND_DECORATION)；
        // 生成的群系数据也把矿放在 features[7]。7 才是这个词的真实 ordinal。
        assertEquals(7, NetherConfig.ORE_DECORATION_STEP,
                "矿在 UNDERGROUND_DECORATION(7)，不是 UNDERGROUND_ORES(6)：这个数字直接进 setFeatureSeed");
    }

    @Test
    void ruleTableMatchesUpstreamPlacedFeatures() {
        List<OreRule> rules = NetherConfig.UPSTREAM_ORE_RULES;

        assertEquals(8, rules.size(),
                "上游矿物 placed feature 共 8 条：cincinnasite / ruby_rare / lapis / redstone / ruby / "
                        + "ruby_large / ruby_soul / gloomwood_ceiling_debris");

        assertRule(rules.get(0), "betternether:cincinnasite_ore", 8, 0.0f, 10, 0,
                VerticalAnchor.aboveBottom(10), VerticalAnchor.belowTop(10), OreShape.UNIFORM,
                targets(new OreTargetState("minecraft:netherrack", "betternether:cincinnasite_ore")),
                List.of(OreStep.Kind.COUNT, OreStep.Kind.HEIGHT_RANGE, OreStep.Kind.IN_SQUARE,
                        OreStep.Kind.BIOME));
        assertRule(rules.get(1), "betternether:nether_ruby_rare_ore", 12, 0.0f, 0, 2,
                VerticalAnchor.aboveBottom(70), VerticalAnchor.belowTop(0), OreShape.TRAPEZOID,
                targets(new OreTargetState("minecraft:netherrack", "betternether:nether_ruby_ore")),
                List.of(OreStep.Kind.RARITY, OreStep.Kind.HEIGHT_RANGE, OreStep.Kind.IN_SQUARE,
                        OreStep.Kind.BIOME));
        assertRule(rules.get(2), "betternether:nether_lapis_ore", 4, 0.0f, 14, 0,
                VerticalAnchor.aboveBottom(32), VerticalAnchor.belowTop(10), OreShape.TRAPEZOID,
                targets(new OreTargetState("minecraft:netherrack", "betternether:nether_lapis_ore")),
                List.of(OreStep.Kind.COUNT, OreStep.Kind.HEIGHT_RANGE, OreStep.Kind.IN_SQUARE,
                        OreStep.Kind.BIOME));
        assertRule(rules.get(3), "betternether:nether_redstone_ore", 16, 0.3f, 0, 1,
                VerticalAnchor.aboveBottom(8), VerticalAnchor.aboveBottom(40), OreShape.TRAPEZOID,
                targets(new OreTargetState("minecraft:netherrack", "betternether:nether_redstone_ore")),
                List.of(OreStep.Kind.RARITY, OreStep.Kind.HEIGHT_RANGE, OreStep.Kind.IN_SQUARE,
                        OreStep.Kind.BIOME));
        assertRule(rules.get(4), "betternether:nether_ruby_ore", 8, 0.0f, 3, 0,
                VerticalAnchor.aboveBottom(32), VerticalAnchor.belowTop(32), OreShape.UNIFORM,
                targets(new OreTargetState("minecraft:netherrack", "betternether:nether_ruby_ore")),
                List.of(OreStep.Kind.COUNT, OreStep.Kind.HEIGHT_RANGE, OreStep.Kind.IN_SQUARE,
                        OreStep.Kind.BIOME));
        assertRule(rules.get(5), "betternether:nether_ruby_large_ore", 5, 0.1f, 5, 0,
                VerticalAnchor.aboveBottom(32), VerticalAnchor.belowTop(0), OreShape.UNIFORM,
                targets(new OreTargetState("minecraft:netherrack", "betternether:nether_ruby_ore")),
                List.of(OreStep.Kind.COUNT, OreStep.Kind.HEIGHT_RANGE, OreStep.Kind.IN_SQUARE,
                        OreStep.Kind.BIOME));
        assertRule(rules.get(6), "betternether:nether_ruby_soul_ore", 5, 0.1f, 5, 0,
                VerticalAnchor.aboveBottom(32), VerticalAnchor.belowTop(0), OreShape.UNIFORM,
                targets(new OreTargetState("minecraft:soul_soil", "betternether:nether_ruby_ore")),
                List.of(OreStep.Kind.COUNT, OreStep.Kind.HEIGHT_RANGE, OreStep.Kind.IN_SQUARE,
                        OreStep.Kind.BIOME));
        // 链是另一种形状：先 height_range、再 count，后面还有 every_layer 与 rarity_filter。
        assertRule(rules.get(7), "betternether:gloomwood_ceiling_debris", 2, 0.0f, 1, 5,
                VerticalAnchor.aboveBottom(4), VerticalAnchor.belowTop(4), OreShape.UNIFORM,
                targets(new OreTargetState("minecraft:sculk", "minecraft:ancient_debris"),
                        new OreTargetState("minecraft:netherrack", "minecraft:ancient_debris")),
                List.of(OreStep.Kind.HEIGHT_RANGE, OreStep.Kind.COUNT, OreStep.Kind.IN_SQUARE,
                        OreStep.Kind.BIOME, OreStep.Kind.CEILING_LAYERS, OreStep.Kind.BIOME,
                        OreStep.Kind.RARITY));
    }

    @Test
    void gloomwoodCeilingDebrisIsAnOreWithTwoTargetsAndItsOwnChain() {
        OreRule debris = NetherConfig.UPSTREAM_ORE_RULES.get(7);

        assertEquals(2, debris.targets().size(),
                "feature/gloomwood_ceiling_debris.json 的 targets 是数组，不是单个目标");
        assertEquals("minecraft:sculk", debris.targets().get(0).matchId(), "第一条先试 sculk");
        assertEquals("minecraft:netherrack", debris.targets().get(1).matchId(), "再试 netherrack");
        for (OreTargetState target : debris.targets()) {
            assertEquals("minecraft:ancient_debris", target.stateId(), "两条都换成 ancient debris");
        }

        // every_layer 的 min 是 4，且每一步的位置必须和 JSON 的数组次序一致。
        assertEquals(4, debris.placement().get(4).value(), "wover:every_layer 的 min");
        assertEquals(5, debris.placement().get(6).value(), "尾部的 rarity_filter chance");
        assertEquals(Set.of("betternether:gloomwood"), debris.biomes(),
                "只有 gloomwood 的 features[7] 列出这条 placed feature");
    }

    @Test
    void discardChancesAreUpstreamsAndNotAllZero() {
        assertEquals(0.3f, NetherConfig.UPSTREAM_ORE_RULES.get(3).discardChance(), 1e-6f, "redstone");
        assertEquals(0.1f, NetherConfig.UPSTREAM_ORE_RULES.get(5).discardChance(), 1e-6f, "ruby large");
        assertEquals(0.1f, NetherConfig.UPSTREAM_ORE_RULES.get(6).discardChance(), 1e-6f, "ruby soul");
        for (int index : new int[]{0, 1, 2, 4, 7}) {
            assertEquals(0.0f, NetherConfig.UPSTREAM_ORE_RULES.get(index).discardChance(), 1e-6f,
                    NetherConfig.UPSTREAM_ORE_RULES.get(index).placedFeatureId());
        }
    }

    @Test
    void rubyBelongsToExactlyTenBetternetherBiomesPlusTwoVanillaForests() {
        Set<String> biomes = NetherConfig.UPSTREAM_ORE_RULES.get(4).biomes();

        Set<String> betternetherOnly = biomes.stream()
                .filter(key -> key.startsWith("betternether:"))
                .collect(Collectors.toSet());
        assertEquals(10, betternetherOnly.size(),
                "上游真实数组里带 nether_ruby_ore 的 BN 群系正好 10 个：" + betternetherOnly);

        assertTrue(biomes.contains("minecraft:crimson_forest"), "ruby_ore.json 挂了 crimson_forest");
        assertTrue(biomes.contains("minecraft:warped_forest"), "ruby_ore.json 挂了 warped_forest");
        assertFalse(biomes.contains("betternether:nether_swampland"), "swampland 没有 ruby");
        assertFalse(biomes.contains("betternether:soul_plain"), "soul_plain 的 ruby 走 soul 变体");
    }

    @Test
    void swamplandsReceiveNoBetternetherOreAtAll() {
        for (String swampland : List.of("betternether:nether_swampland",
                "betternether:nether_swampland_terraces")) {
            for (OreRule rule : NetherConfig.UPSTREAM_ORE_RULES) {
                assertFalse(rule.allowsBiome(swampland),
                        rule.placedFeatureId() + " 不该出现在 " + swampland + "（真实数组里没有）");
            }
        }
    }

    @Test
    void baseOresCoverTwentyOneBetternetherBiomesAndEveryVanillaBiome() {
        OreRule cincinnasite = NetherConfig.UPSTREAM_ORE_RULES.get(0);

        assertEquals(21, cincinnasite.biomes().size(),
                "23 个 BN 群系减去两个 swampland：" + cincinnasite.biomes());
        assertTrue(cincinnasite.allowsBiome("minecraft:nether_wastes"),
                "default_ores.json 的谓词 = in_dimension(the_nether) ∧ not(is_namespace betternether)，"
                        + "所以每个非 betternether 命名空间的下界群系都有这四条基础矿");
        assertTrue(cincinnasite.allowsBiome("minecraft:basalt_deltas"));
        assertTrue(cincinnasite.allowsBiome("betternether:gloomwood"));
        assertFalse(cincinnasite.allowsBiome("betternether:nether_swampland"));
        assertFalse(cincinnasite.allowsBiome(null), "问不出群系时不放，而不是猜一个");
    }

    /**
     * {@code WorldView#biomeAt} 对本包群系返回的是 {@code NetherBiomes.ALL} 里的<b>裸键</b>
     * （{@code "wart_forest"}），只有原版群系带 {@code minecraft:}。规则表的群系集合逐字转写上游
     * JSON，一律带 {@code betternether:} —— 这个口径差曾经同时造成两个真实故障：
     * {@code Set#contains} 全部落空（ruby 一个也不生成），而
     * {@code startsWith("betternether:")} 把裸键判成「非本包」，让 {@code anyNonBetternetherBiome}
     * 把这些 BN 群系当原版群系、绕过 swampland 的排除。
     */
    @Test
    void bareBetternetherKeysMeanTheSameBiomeAsNamespacedOnes() {
        OreRule cincinnasite = NetherConfig.UPSTREAM_ORE_RULES.get(0);
        OreRule ruby = NetherConfig.UPSTREAM_ORE_RULES.get(4);

        assertEquals(cincinnasite.allowsBiome("betternether:gloomwood"), cincinnasite.allowsBiome("gloomwood"),
                "裸键 gloomwood 与 betternether:gloomwood 是同一个群系");
        assertTrue(cincinnasite.allowsBiome("gloomwood"), "gloomwood 的 features[7] 里有基础四条矿");
        assertTrue(ruby.allowsBiome("crimson_glowing_woods"),
                "ruby 必须能通过运行期的裸键命中 —— 否则整条 placed feature 一个也不生成");
        assertTrue(ruby.allowsBiome("betternether:crimson_glowing_woods"));
        assertFalse(ruby.allowsBiome("soul_plain"), "soul_plain 的 ruby 走 soul 变体，裸键也要按清单排除");
    }

    @Test
    void bareSwamplandKeysStillGetNoBetternetherOre() {
        for (String swampland : List.of("nether_swampland", "nether_swampland_terraces")) {
            for (OreRule rule : NetherConfig.UPSTREAM_ORE_RULES) {
                assertFalse(rule.allowsBiome(swampland),
                        rule.placedFeatureId() + " 不该出现在裸键 " + swampland + " 里 —— 裸键是本包群系，"
                                + "不能被 anyNonBetternetherBiome 当成「非 betternether」放行");
            }
        }
    }

    @Test
    void foreignNamespacesKeepTheUpstreamRuleAndUnknownBareKeysCannotBypassIt() {
        OreRule cincinnasite = NetherConfig.UPSTREAM_ORE_RULES.get(0);
        OreRule ruby = NetherConfig.UPSTREAM_ORE_RULES.get(4);

        // 其他数据包/模组的群系仍然是「非 betternether 命名空间」：default_ores 的谓词放行。
        assertTrue(cincinnasite.allowsBiome("terralith:cave/thermal_caves"),
                "foreign namespace 属于 not(is_namespace betternether)，不能被归到本包");
        assertFalse(ruby.allowsBiome("terralith:cave/thermal_caves"),
                "ruby 只在自己的 10+2 个群系里，不跟着 foreign namespace 扩散");
        // 原版群系照旧。
        assertTrue(cincinnasite.allowsBiome("minecraft:nether_wastes"));
        assertTrue(ruby.allowsBiome("minecraft:crimson_forest"));
        // 未知裸键归到本包命名空间：不在清单里，也不许靠「看起来像原版」绕过限制。
        assertFalse(cincinnasite.allowsBiome("nonexistent_biome"),
                "未知裸键不能被当成非 BN 群系而拿到全部基础矿");
        assertFalse(ruby.allowsBiome("nonexistent_biome"));
    }

    @Test
    void rubyLargeIsOnlySoulSandValleyAndRubySoulIsOnlySoulPlain() {
        OreRule large = NetherConfig.UPSTREAM_ORE_RULES.get(5);
        assertEquals(Set.of("minecraft:soul_sand_valley"), large.biomes(),
                "ruby_ore_dense.json 的谓词只有一个 is_biome(minecraft:soul_sand_valley)");
        assertFalse(large.anyNonBetternetherBiome(), "它不是 default_ores 那一条");

        OreRule soul = NetherConfig.UPSTREAM_ORE_RULES.get(6);
        assertEquals(Set.of("betternether:soul_plain"), soul.biomes(),
                "soul_plain 是唯一列出 nether_ruby_soul_ore 的群系");
        assertEquals("minecraft:soul_soil", soul.targets().get(0).matchId(),
                "它替换 soul soil，折进通用 ruby 会把矿撒到每个群系的 soul soil 上");
    }

    @Test
    void defaultConfigReproducesUpstreamExactly() {
        assertEquals(NetherConfig.UPSTREAM_ORE_RULES, NetherConfig.DEFAULTS.oreRules(),
                "缺省配置必须是上游表本身：缺省 vein-size 16 = 上游最大 size，不裁剪任何一条");
    }

    @Test
    void zeroVeinsDisablesOre() {
        assertTrue(new NetherConfig(0L, 0, 16, 28, 9, 1, 100, 4).oreRules().isEmpty(),
                "veins-per-chunk: 0 是关闭，而不是回退默认");
    }

    @Test
    void loweringVeinsPerChunkNoLongerScalesAnything() {
        // 旧实现用 min(1, veins/28.5) 等比缩放整张表，调低就把所有矿一起削掉。
        // 现在它只是一个开关，数量完全由每条 placed feature 自己的链决定。
        for (int veins : new int[]{1, 14, 28, 29, 64}) {
            assertEquals(NetherConfig.UPSTREAM_ORE_RULES,
                    new NetherConfig(0L, veins, 16, 28, 9, 1, 100, 4).oreRules(),
                    "veins-per-chunk=" + veins + " 不应改变任何一条矿的数量或尺寸");
        }
    }

    @Test
    void sizeCapOnlyClipsWhenLoweredBelowTheUpstreamMaximum() {
        List<OreRule> clipped = new NetherConfig(0L, 29, 4, 28, 9, 1, 100, 4).oreRules();

        assertEquals(4, clipped.get(0).size(), "cincinnasite 8 被上限 4 裁剪");
        assertEquals(4, clipped.get(1).size(), "ruby rare 12 被上限 4 裁剪");
        assertEquals(4, clipped.get(2).size(), "lapis 4 恰好等于上限，不变");
        assertEquals(4, clipped.get(3).size(), "redstone 16 被上限 4 裁剪");
        assertEquals(4, clipped.get(4).size(), "ruby 8 被上限 4 裁剪");
        assertEquals(4, clipped.get(5).size(), "ruby large 5 被上限 4 裁剪");
        assertEquals(4, clipped.get(6).size(), "ruby soul 5 被上限 4 裁剪");
        assertEquals(2, clipped.get(7).size(), "gloomwood debris 只有 2，未超过上限");
        assertEquals(NetherConfig.UPSTREAM_ORE_RULES.get(0).countOf(), clipped.get(0).countOf(),
                "裁剪尺寸不能动链");
    }

    @Test
    void anchorsResolveAgainstTheWorldTheyRunIn() {
        OreRule cincinnasite = NetherConfig.UPSTREAM_ORE_RULES.get(0);
        OreRule redstone = NetherConfig.UPSTREAM_ORE_RULES.get(3);
        OreRule ruby = NetherConfig.UPSTREAM_ORE_RULES.get(4);

        // 原版 the_nether：min_y 0、height 256。
        assertEquals(10, cincinnasite.resolveMinY(0, 256));
        assertEquals(245, cincinnasite.resolveMaxY(0, 256), "below_top 10 -> 256 - 1 - 10");
        assertEquals(8, redstone.resolveMinY(0, 256), "above_bottom 8 与高度无关");
        assertEquals(40, redstone.resolveMaxY(0, 256), "above_bottom 40 与高度无关");
        assertEquals(32, ruby.resolveMinY(0, 256));
        assertEquals(223, ruby.resolveMaxY(0, 256), "below_top 32 -> 256 - 1 - 32");

        // 若世界只有 0..127（上游地形实际填充的体量），带子必须跟着收。
        assertEquals(117, cincinnasite.resolveMaxY(0, 128));
        assertEquals(95, ruby.resolveMaxY(0, 128));
        assertEquals(8, redstone.resolveMinY(0, 128));
        assertEquals(40, redstone.resolveMaxY(0, 128));
    }

    @Test
    void configRejectsImpossibleOreCaps() {
        assertThrows(IllegalArgumentException.class,
                () -> new NetherConfig(0L, -1, 16, 28, 9, 1, 100, 4));
        assertThrows(IllegalArgumentException.class,
                () -> new NetherConfig(0L, 65, 16, 28, 9, 1, 100, 4));
        assertThrows(IllegalArgumentException.class,
                () -> new NetherConfig(0L, 29, 0, 28, 9, 1, 100, 4));
        assertThrows(IllegalArgumentException.class,
                () -> new NetherConfig(0L, 29, 65, 28, 9, 1, 100, 4));
    }

    private static List<OreTargetState> targets(OreTargetState... states) {
        return List.of(states);
    }

    private static void assertRule(OreRule rule, String placedFeatureId, int size, float discard,
                                   int count, int rarity, VerticalAnchor min, VerticalAnchor max,
                                   OreShape shape, List<OreTargetState> targets,
                                   List<OreStep.Kind> steps) {
        assertEquals(placedFeatureId, rule.placedFeatureId());
        assertEquals(size, rule.size(), placedFeatureId + " 的 feature.size");
        assertEquals(discard, rule.discardChance(), 1e-6f,
                placedFeatureId + " 的 discard_chance_on_air_exposure");
        assertEquals(count, rule.countOf(), placedFeatureId + " 链里的 minecraft:count");
        assertEquals(rarity, rule.rarityOf(), placedFeatureId + " 链里的 rarity_filter chance");
        assertEquals(min, rule.min(), placedFeatureId + " 的高度下界锚点");
        assertEquals(max, rule.max(), placedFeatureId + " 的高度上界锚点");
        assertEquals(shape, rule.shape(), placedFeatureId + " 的高度分布");
        assertEquals(targets, rule.targets(), placedFeatureId + " 的 targets");
        List<OreStep.Kind> actual = rule.placement().stream().map(OreStep::kind).toList();
        assertEquals(steps, actual, placedFeatureId + " 的 placement 次序");
    }
}
