package dev.mumu.betternether.config;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The immutable, parsed configuration.
 *
 * <p>Only values the generator and populators actually read live here. Everything is primitives or
 * strings resolved at load time, so worldgen never parses YAML and never sees a partially-applied
 * reload: a reload swaps in a whole new instance.
 *
 * <h2>Why the ore table is a table of placed features, not a quota</h2>
 * BetterNether's ores are not one population split by weight. Each ore is its own
 * {@code placed_feature}: its own count (or {@code rarity_filter}), its own {@code height_range},
 * its own {@code feature.size}, its own {@code discard_chance_on_air_exposure}, and its own
 * <b>biome list</b> -- the ore is not in every Nether biome. {@link #UPSTREAM_ORE_RULES} is that
 * table, one row per upstream placed feature, transcribed from
 * {@code data/betternether/worldgen/placed_feature/*.json}, {@code .../feature/*.json}, each
 * biome's {@code features[7]} array and
 * {@code data/betternether/wover/worldgen/biome_modifications/{default_ores,ruby_ore,ruby_ore_dense}.json}.
 * Nothing here shares a budget between ores and nothing scales by a "veins per chunk" total.
 *
 * @param seed                  seed for the ore streams, the tree/feature randoms and the
 *                              surface-rule noise. {@code 0} means "use the world seed".
 *                              <p><b>It no longer selects the biome layout.</b> The biome map is the
 *                              vanilla climate: {@code NetherClimateSampler} reads the world's own
 *                              {@code RandomState}, which is seeded from the world seed and cannot be
 *                              overridden by a plugin. Making this seed drive the biome map again would
 *                              require computing a climate field of our own, which this port must not do.
 * @param oreVeinCount          <b>只是一个开关</b>：{@code 0} 关闭矿物，任何非 0 值都按上游表原样生成。
 *                              <b>它不再是「每区块几条」</b>：每条 placed feature 自己带
 *                              {@code count} / {@code rarity_filter}，把它再当成总量乘一遍就是双重
 *                              计数，按比例缩放则会改变密度 —— 两者都不是上游。缺省 29 只是历史值，
 *                              不代表任何配额。
 * @param oreVeinSize           {@code feature.size} 的硬上限，<b>仅调试用</b>。缺省 16 恰好等于上游
 *                              最大值（{@code nether_redstone_ore} 的 size 16），即不裁剪；调低它会让
 *                              矿团比上游小，属于刻意偏离。
 * @param floraPerChunk         flora 开关：{@code 0} 关闭植被，非 0 按上游 worldgen 数据放置。
 *                              <b>它不再是「每区块尝试几次」</b> —— 次数由上游每条 placed feature
 *                              自己的 {@code tries}/{@code count} 决定，再乘一遍就是双重计数。
 */
public record NetherConfig(
        long seed,
        int oreVeinCount,
        int oreVeinSize,
        int floraPerChunk,
        int vineMaxLength,
        int vineGrowthChance,
        int vineGrowthIntervalTicks,
        int vineGrowthAttempts
) {
    /**
     * The step every BetterNether ore belongs to: {@code the_nether} 与 BN 的 step 7。
     *
     * <p>上游注册表 {@code src/main/java/org/betterx/betternether/registry/features/placed/
     * NetherOresPlaced.java} 里 8 条矿物 placed feature <b>每一条</b>都是
     * {@code .setDecoration(GenerationStep.Decoration.UNDERGROUND_DECORATION)}；生成的群系数据也把矿
     * 写在每个群系的 {@code features[7]}（第 6 槽在 23 个 BN 群系里全为空数组）。
     *
     * <p>因此它<b>不是</b> 6：{@code 6 = UNDERGROUND_ORES} 只是原版矿物惯例所在的槽位，BN 把自家矿
     * 挂到了 {@code 7 = UNDERGROUND_DECORATION}。这个数字会直接进 {@code setFeatureSeed} 的
     * {@code 10000L * step}，错一位整条随机流就不同，所以按真实注册而不是按惯例取。
     */
    public static final int ORE_DECORATION_STEP = 7;

    /** {@code betternether} 数据包命名空间；{@code default_ores} 的谓词就是按它排除。 */
    private static final String BETTERNETHER_NAMESPACE = "betternether:";

    /**
     * 23 个 {@code betternether} 群系里，{@code features[7]} 含那四条基础矿的 <b>21</b> 个。
     *
     * <p>{@code nether_swampland} 与 {@code nether_swampland_terraces} 的 {@code features[7]} 一条 BN
     * 矿都没有（真实数组里它们止于 {@code minecraft:ore_debris_small}）—— 这两个群系不该出任何一种
     * 自定义矿，包括 ruby。
     */
    private static final Set<String> BASE_ORE_BIOMES = Set.of(
            "betternether:bone_reef",
            "betternether:crimson_glowing_woods",
            "betternether:crimson_pinewood",
            "betternether:flooded_deltas",
            "betternether:gloomwood",
            "betternether:gravel_desert",
            "betternether:magma_land",
            "betternether:nether_grasslands",
            "betternether:nether_jungle",
            "betternether:nether_mushroom_forest",
            "betternether:nether_mushroom_forest_edge",
            "betternether:old_fungiwoods",
            "betternether:old_swampland",
            "betternether:old_warped_woods",
            "betternether:poor_nether_grasslands",
            "betternether:soul_plain",
            "betternether:sulfuric_bone_reef",
            "betternether:upside_down_forest",
            "betternether:upside_down_forest_cleared",
            "betternether:wart_forest",
            "betternether:wart_forest_edge");

    /**
     * {@code betternether:nether_ruby_ore} 的群系集合：正好 10 个 BN 群系（真实数组里带
     * {@code nether_ruby_ore} 的那些）加上 {@code ruby_ore.json} 注入的两个原版森林。
     */
    private static final Set<String> RUBY_ORE_BIOMES = Set.of(
            "betternether:crimson_glowing_woods",
            "betternether:crimson_pinewood",
            "betternether:nether_mushroom_forest",
            "betternether:nether_mushroom_forest_edge",
            "betternether:old_fungiwoods",
            "betternether:old_warped_woods",
            "betternether:upside_down_forest",
            "betternether:upside_down_forest_cleared",
            "betternether:wart_forest",
            "betternether:wart_forest_edge",
            "minecraft:crimson_forest",
            "minecraft:warped_forest");

    /** {@code ruby_ore_dense.json} 只把 {@code nether_ruby_large_ore} 挂到 {@code soul_sand_valley}。 */
    private static final Set<String> RUBY_LARGE_ORE_BIOMES = Set.of("minecraft:soul_sand_valley");

    /** {@code nether_ruby_soul_ore} 只在 {@code soul_plain} 的 {@code features[7]} 里。 */
    private static final Set<String> RUBY_SOUL_ORE_BIOMES = Set.of("betternether:soul_plain");

    /**
     * {@code betternether:gloomwood_ceiling_debris} 只在 {@code gloomwood} 的 {@code features[7]} 里。
     *
     * <p>它<b>也是</b> {@code minecraft:ore}（上游 {@code NetherOresPlaced} 把它和另外 7 条一起注册到
     * UNDERGROUND_DECORATION），所以归矿物这条线，而不是植被那条线：{@code feature/
     * gloomwood_ceiling_debris.json} 是 {@code type: minecraft:ore}、size 2、discard 0.0、
     * 两个 target（sculk → ancient debris、netherrack → ancient debris）。
     */
    private static final Set<String> GLOOMWOOD_DEBRIS_BIOMES = Set.of("betternether:gloomwood");

    /**
     * 上游 {@code feature.size} 的最大值：{@code nether_redstone_ore} 的 16。
     * {@code ore.vein-size} 缺省取它，于是缺省配置不裁剪任何矿团。
     */
    public static final int UPSTREAM_MAX_ORE_SIZE = 16;

    /**
     * 每一条上游矿物 placed feature，逐字转写；顺序 = {@code features[7]} 里 BN 矿的先后。
     *
     * <p>数值全部来自两个 JSON 的字段对：
     * <ul>
     *   <li>{@code minecraft:count count: N} → {@code count = N}；{@code minecraft:rarity_filter
     *       chance: N} → {@code rarityChance = N}（两者互斥，未用到的那一个是 0/1 里的空档）。</li>
     *   <li>{@code height_range} 的 {@code min_inclusive}/{@code max_inclusive} 保留
     *       {@code above_bottom}/{@code below_top} 锚点，不烘成绝对 Y。</li>
     *   <li>{@code feature.size} 与 {@code discard_chance_on_air_exposure} 原值。</li>
     *   <li>{@code targets[0].target} 决定替换什么（netherrack / soul soil），{@code targets[0].state}
     *       是放什么；ruby 的三个变体放的都是 {@code betternether:nether_ruby_ore}。</li>
     * </ul>
     *
     * <p>被纠正的两处旧判断：
     * <ul>
     *   <li>{@code nether_ruby_large_ore} <b>不是</b> "永不生成"：{@code ruby_ore_dense.json} 把它挂到
     *       {@code minecraft:soul_sand_valley}。</li>
     *   <li>{@code nether_ruby_soul_ore} <b>不是</b> 可以折进通用 ruby 预算：它替换
     *       {@code minecraft:soul_soil}，只属于 {@code soul_plain}。</li>
     * </ul>
     */
    public static final List<OreRule> UPSTREAM_ORE_RULES = List.of(
            // placed_feature/cincinnasite_ore: count 10 → height_range uniform
            // above_bottom 10 .. below_top 10 → in_square → biome;
            // feature/cincinnasite_ore: size 8, discard 0.0, target netherrack.
            new OreRule("betternether:cincinnasite_ore",
                    targets(target("minecraft:netherrack", "betternether:cincinnasite_ore")),
                    8, 0.0f,
                    VerticalAnchor.aboveBottom(10), VerticalAnchor.belowTop(10), OreShape.UNIFORM,
                    List.of(OreStep.count(10), OreStep.HEIGHT_RANGE, OreStep.IN_SQUARE, OreStep.BIOME),
                    BASE_ORE_BIOMES, true),
            // placed_feature/nether_ruby_rare_ore: rarity_filter chance 2 → height_range trapezoid
            // above_bottom 70 .. below_top 0 → in_square → biome;
            // feature/nether_ruby_rare_ore: size 12, discard 0.0, 放的是 nether_ruby_ore。
            new OreRule("betternether:nether_ruby_rare_ore",
                    targets(target("minecraft:netherrack", "betternether:nether_ruby_ore")),
                    12, 0.0f,
                    VerticalAnchor.aboveBottom(70), VerticalAnchor.belowTop(0), OreShape.TRAPEZOID,
                    List.of(OreStep.rarity(2), OreStep.HEIGHT_RANGE, OreStep.IN_SQUARE, OreStep.BIOME),
                    BASE_ORE_BIOMES, true),
            // placed_feature/nether_lapis_ore: count 14 → height_range trapezoid
            // above_bottom 32 .. below_top 10 → in_square → biome;
            // feature/nether_lapis_ore: size 4, discard 0.0.
            new OreRule("betternether:nether_lapis_ore",
                    targets(target("minecraft:netherrack", "betternether:nether_lapis_ore")),
                    4, 0.0f,
                    VerticalAnchor.aboveBottom(32), VerticalAnchor.belowTop(10), OreShape.TRAPEZOID,
                    List.of(OreStep.count(14), OreStep.HEIGHT_RANGE, OreStep.IN_SQUARE, OreStep.BIOME),
                    BASE_ORE_BIOMES, true),
            // placed_feature/nether_redstone_ore: rarity_filter chance 1 → height_range trapezoid
            // above_bottom 8 .. above_bottom 40 → in_square → biome;
            // feature/nether_redstone_ore: size 16, discard 0.3（四条基础矿里唯一真的会丢的）。
            new OreRule("betternether:nether_redstone_ore",
                    targets(target("minecraft:netherrack", "betternether:nether_redstone_ore")),
                    16, 0.3f,
                    VerticalAnchor.aboveBottom(8), VerticalAnchor.aboveBottom(40), OreShape.TRAPEZOID,
                    List.of(OreStep.rarity(1), OreStep.HEIGHT_RANGE, OreStep.IN_SQUARE, OreStep.BIOME),
                    BASE_ORE_BIOMES, true),
            // placed_feature/nether_ruby_ore: count 3 → height_range uniform
            // above_bottom 32 .. below_top 32 → in_square → biome;
            // feature/nether_ruby_ore: size 8, discard 0.0.
            new OreRule("betternether:nether_ruby_ore",
                    targets(target("minecraft:netherrack", "betternether:nether_ruby_ore")),
                    8, 0.0f,
                    VerticalAnchor.aboveBottom(32), VerticalAnchor.belowTop(32), OreShape.UNIFORM,
                    List.of(OreStep.count(3), OreStep.HEIGHT_RANGE, OreStep.IN_SQUARE, OreStep.BIOME),
                    RUBY_ORE_BIOMES, false),
            // placed_feature/nether_ruby_large_ore: count 5 → height_range uniform
            // above_bottom 32 .. below_top 0 → in_square → biome; feature: size 5, discard 0.1。
            // 只挂 minecraft:soul_sand_valley（ruby_ore_dense.json）。
            new OreRule("betternether:nether_ruby_large_ore",
                    targets(target("minecraft:netherrack", "betternether:nether_ruby_ore")),
                    5, 0.1f,
                    VerticalAnchor.aboveBottom(32), VerticalAnchor.belowTop(0), OreShape.UNIFORM,
                    List.of(OreStep.count(5), OreStep.HEIGHT_RANGE, OreStep.IN_SQUARE, OreStep.BIOME),
                    RUBY_LARGE_ORE_BIOMES, false),
            // placed_feature/nether_ruby_soul_ore: count 5 → height_range uniform
            // above_bottom 32 .. below_top 0 → in_square → biome; feature: size 5, discard 0.1,
            // target 是 minecraft:soul_soil。
            new OreRule("betternether:nether_ruby_soul_ore",
                    targets(target("minecraft:soul_soil", "betternether:nether_ruby_ore")),
                    5, 0.1f,
                    VerticalAnchor.aboveBottom(32), VerticalAnchor.belowTop(0), OreShape.UNIFORM,
                    List.of(OreStep.count(5), OreStep.HEIGHT_RANGE, OreStep.IN_SQUARE, OreStep.BIOME),
                    RUBY_SOUL_ORE_BIOMES, false),
            // placed_feature/gloomwood_ceiling_debris —— 链是**另一种形状**，逐字转写：
            // height_range uniform above_bottom 4 .. below_top 4 → count 1 → in_square → biome →
            // wover:every_layer(min 4, top:false) → biome → rarity_filter chance 5。
            // feature/gloomwood_ceiling_debris: size 2, discard 0.0，两个 target
            // （sculk → ancient debris、netherrack → ancient debris）。
            new OreRule("betternether:gloomwood_ceiling_debris",
                    targets(target("minecraft:sculk", "minecraft:ancient_debris"),
                            target("minecraft:netherrack", "minecraft:ancient_debris")),
                    2, 0.0f,
                    VerticalAnchor.aboveBottom(4), VerticalAnchor.belowTop(4), OreShape.UNIFORM,
                    List.of(OreStep.HEIGHT_RANGE, OreStep.count(1), OreStep.IN_SQUARE, OreStep.BIOME,
                            OreStep.ceilingLayers(4), OreStep.BIOME, OreStep.rarity(5)),
                    GLOOMWOOD_DEBRIS_BIOMES, false)
    );

    private static OreTargetState target(String matchId, String stateId) {
        return new OreTargetState(matchId, stateId);
    }

    private static List<OreTargetState> targets(OreTargetState... states) {
        return List.of(states);
    }

    /** Defaults, mirrored exactly into the shipped config.yml. */
    public static final NetherConfig DEFAULTS =
            new NetherConfig(0L, 29, 16, 28, 9, 1, 100, 4);

    public NetherConfig {
        if (oreVeinCount < 0 || oreVeinCount > 64) {
            throw new IllegalArgumentException("ore.veins-per-chunk 必须在 0..64，当前 " + oreVeinCount);
        }
        if (oreVeinSize < 1 || oreVeinSize > 64) {
            throw new IllegalArgumentException("ore.vein-size 必须在 1..64，当前 " + oreVeinSize);
        }
        if (floraPerChunk < 0 || floraPerChunk > 256) {
            throw new IllegalArgumentException("flora.per-chunk 必须在 0..256，当前 " + floraPerChunk);
        }
        if (vineMaxLength < 1 || vineMaxLength > 32) {
            throw new IllegalArgumentException("vine.max-length 必须在 1..32（CE 侧硬上限 32），当前 "
                    + vineMaxLength);
        }
        if (vineGrowthChance < 0 || vineGrowthChance > 100) {
            throw new IllegalArgumentException("vine.growth-chance 必须在 0..100，当前 " + vineGrowthChance);
        }
        if (vineGrowthIntervalTicks < 1 || vineGrowthIntervalTicks > 12000) {
            throw new IllegalArgumentException("vine.growth-interval-ticks 必须在 1..12000，当前 "
                    + vineGrowthIntervalTicks);
        }
        if (vineGrowthAttempts < 0 || vineGrowthAttempts > 64) {
            throw new IllegalArgumentException("vine.growth-attempts 必须在 0..64，当前 " + vineGrowthAttempts);
        }
    }

    /**
     * The effective seed for a world: the configured override when non-zero, otherwise the world's.
     */
    public long seedFor(long worldSeed) {
        return seed == 0L ? worldSeed : seed;
    }

    /**
     * The per-ore rules this configuration produces: {@link #UPSTREAM_ORE_RULES} unchanged, minus an
     * optional debug clamp on {@code feature.size}.
     *
     * <p>There is deliberately <b>no</b> scaling here. Upstream gives each placed feature its own
     * {@code count}, so a single "veins per chunk" divisor would silently change every ore's density;
     * that is a different world, not a scaled one. {@code ore.veins-per-chunk} is an on/off switch
     * only, and {@code ore.vein-size} defaults to {@link #UPSTREAM_MAX_ORE_SIZE} so it clips nothing.
     *
     * @return the rules to generate, empty when ore is disabled
     */
    public List<OreRule> oreRules() {
        if (oreVeinCount == 0) {
            return List.of();
        }
        if (oreVeinSize >= UPSTREAM_MAX_ORE_SIZE) {
            return UPSTREAM_ORE_RULES;
        }
        List<OreRule> rules = new ArrayList<>(UPSTREAM_ORE_RULES.size());
        for (OreRule rule : UPSTREAM_ORE_RULES) {
            rules.add(rule.withSizeCap(oreVeinSize));
        }
        return List.copyOf(rules);
    }

    /** How a {@code height_range} distributes the samples between its two anchors. */
    public enum OreShape {
        /** {@code minecraft:uniform} —— {@code Mth.randomBetweenInclusive}：一次 {@code nextInt(span)}。 */
        UNIFORM,
        /**
         * {@code minecraft:trapezoid} —— {@code TrapezoidHeightProvider}，{@code plateau} 取缺省 0：
         * {@code min + randInt(0, range - range/2) + randInt(0, range/2)}，两次 {@code nextInt}，
         * 因此中间高、两端低。
         */
        TRAPEZOID
    }

    /**
     * One end of a {@code height_range}, kept as an anchor instead of an absolute Y.
     *
     * @param kind   which edge of the dimension the offset counts from
     * @param offset blocks away from that edge, inclusive
     */
    public record VerticalAnchor(Kind kind, int offset) {
        /** The anchor kinds upstream uses. */
        public enum Kind {
            /** {@code above_bottom} -- {@code minY + offset}. */
            ABOVE_BOTTOM,
            /** {@code below_top} -- {@code maxY - 1 - offset}. */
            BELOW_TOP
        }

        public static VerticalAnchor aboveBottom(int offset) {
            return new VerticalAnchor(Kind.ABOVE_BOTTOM, offset);
        }

        public static VerticalAnchor belowTop(int offset) {
            return new VerticalAnchor(Kind.BELOW_TOP, offset);
        }

        /**
         * The absolute block Y this anchor names in a world of the given bounds.
         *
         * @param worldMinY           the world's lowest block Y ({@link WorldInfo#getMinHeight()})
         * @param worldMaxYExclusive  one past the world's highest block Y
         *                            ({@link WorldInfo#getMaxHeight()})
         */
        public int resolve(int worldMinY, int worldMaxYExclusive) {
            return switch (kind) {
                case ABOVE_BOTTOM -> worldMinY + offset;
                case BELOW_TOP -> worldMaxYExclusive - 1 - offset;
            };
        }
    }

    /**
     * {@code minecraft:ore} 的一条 {@code targets[]}：匹配什么、换成什么。
     *
     * <p>上游 {@code targets} 是**数组**，不是单个目标：{@code gloomwood_ceiling_debris} 就有两条
     * （sculk 与 netherrack 都换成 ancient debris）。所以要按顺序逐个试，命中哪条就用哪条的 state。
     *
     * <p>{@code matchId} 比的是<b>方块真实身份 id</b>，不是 Bukkit {@code Material}：CE 的 deceive
     * 模式会把自定义方块借到某个原版宿主上，按材质比较会把「长得像 netherrack 的自定义方块」
     * 误当成 netherrack。
     */
    public record OreTargetState(String matchId, String stateId) {
    }

    /**
     * {@code placed_feature} 的 {@code placement} 数组里的一步。
     *
     * <p>做成有序列表而不是几个平铺字段，是因为上游每个 placed feature 的链长、次序、甚至条数都
     * 不一样（{@code gloomwood_ceiling_debris} 是 7 步，而且在 {@code count} 之前先抽高度）。
     * 次序是逐位重要的：每一步消费随机的方式不同，换个次序同样种子就得到不同的矿。
     *
     * @param kind  这一步是哪个修饰符
     * @param value {@code COUNT} 的次数 / {@code RARITY} 的 chance / {@code CEILING_LAYERS} 的 min；
     *              其余步骤忽略
     */
    public record OreStep(Kind kind, int value) {
        /** 上游矿物链里出现过的修饰符。 */
        public enum Kind {
            /** {@code minecraft:height_range}：区间与形态取自 {@link OreRule#min()}/{@link OreRule#shape()}。 */
            HEIGHT_RANGE,
            /** {@code minecraft:count}：常量次数，不消费随机。 */
            COUNT,
            /** {@code minecraft:in_square}：先 {@code nextInt(16)} 取 x 偏移，再取 z。 */
            IN_SQUARE,
            /** {@code minecraft:biome}：位置的群系必须列出这条 placed feature。 */
            BIOME,
            /**
             * {@code wover:every_layer top:false}：把位置展开成该列的**每一个天花板层**
             * （该格是空气、正上方是实心），自下而上。
             */
            CEILING_LAYERS,
            /** {@code minecraft:rarity_filter}：每个位置 {@code nextFloat() < 1/chance} 才保留。 */
            RARITY
        }

        public static final OreStep HEIGHT_RANGE = new OreStep(Kind.HEIGHT_RANGE, 0);
        public static final OreStep IN_SQUARE = new OreStep(Kind.IN_SQUARE, 0);
        public static final OreStep BIOME = new OreStep(Kind.BIOME, 0);

        public static OreStep count(int times) {
            return new OreStep(Kind.COUNT, times);
        }

        public static OreStep rarity(int chance) {
            return new OreStep(Kind.RARITY, chance);
        }

        public static OreStep ceilingLayers(int minLayers) {
            return new OreStep(Kind.CEILING_LAYERS, minLayers);
        }
    }

    /**
     * One upstream {@code placed_feature} + {@code feature} pair, plus the biome list it is attached to.
     *
     * <p>Ruby appears three times: {@code nether_ruby_ore}, {@code nether_ruby_rare_ore} and
     * {@code nether_ruby_large_ore} all place the same block, in three different bands with three
     * different sizes; {@code nether_ruby_soul_ore} places it into soul soil in one biome. Keeping
     * them apart is the whole point -- averaging them into one row changes both the density and the
     * height distribution.
     *
     * @param placedFeatureId         上游 placed feature 的 id，也是 {@code setFeatureSeed} 用的身份
     * @param targets                 {@code feature.targets[]}，按顺序试
     * @param size                    {@code feature.size}，是**球链层数**而不是方块数上限
     * @param discardChance           {@code feature.discard_chance_on_air_exposure}
     * @param min                     {@code height_range} 的 {@code min_inclusive}
     * @param max                     {@code height_range} 的 {@code max_inclusive}
     * @param shape                   {@code height_range} 的分布（{@code uniform} / {@code trapezoid}）
     * @param placement               {@code placed_feature.placement}，逐字转写、按序执行
     * @param biomes                  这条 placed feature 所在群系的显式清单
     * @param anyNonBetternetherBiome 是否还适用于「非 {@code betternether:} 命名空间」的群系
     *                                （即 {@code biome_modifications} 的谓词）
     */
    public record OreRule(String placedFeatureId, List<OreTargetState> targets, int size, float discardChance,
                          VerticalAnchor min, VerticalAnchor max, OreShape shape, List<OreStep> placement,
                          Set<String> biomes, boolean anyNonBetternetherBiome) {
        public OreRule {
            if (targets.isEmpty()) {
                throw new IllegalArgumentException(placedFeatureId + " 的 feature.targets 不能为空");
            }
            if (size < 1) {
                throw new IllegalArgumentException(placedFeatureId + " 的 feature.size 必须 >= 1");
            }
            if (discardChance < 0.0f || discardChance > 1.0f) {
                throw new IllegalArgumentException(
                        placedFeatureId + " 的 discard_chance_on_air_exposure 必须在 0..1");
            }
            if (placement.isEmpty()) {
                throw new IllegalArgumentException(placedFeatureId + " 的 placement 不能为空");
            }
            biomes = Set.copyOf(biomes);
            targets = List.copyOf(targets);
            placement = List.copyOf(placement);
        }

        /** This rule with a debug clamp applied to {@code feature.size}. */
        OreRule withSizeCap(int sizeCap) {
            return new OreRule(placedFeatureId, targets, Math.min(size, sizeCap), discardChance,
                    min, max, shape, placement, biomes, anyNonBetternetherBiome);
        }

        /**
         * 这条 placed feature 是否属于该群系 —— 原版 {@code minecraft:biome} 的语义。
         *
         * <p>入参的两种口径都收：带命名空间的完整 key（{@code betternether:wart_forest}、
         * {@code minecraft:nether_wastes}），和不带命名空间的本地 key（{@code wart_forest}）。
         * 后者是运行期的真实值 —— {@code WorldView#biomeAt} 走 {@code BiomePlacement#keyAt}，
         * 而它对本包群系返回的就是 {@code NetherBiomes.ALL} 里的裸键，只有原版群系才带
         * {@code minecraft:}。口径必须在<b>比较点</b>上统一，否则两种判据同时出错：
         * {@code Set#contains} 对裸键全部落空（ruby 一个都不生成），而
         * {@code startsWith("betternether:")} 又把裸键判成「非本包」，于是
         * {@code anyNonBetternetherBiome} 会把这些 BN 群系当原版群系、绕过 swampland 的排除。
         *
         * @param biomeKey 位置所属群系的 key（{@code null} = 问不出来）
         */
        public boolean allowsBiome(@Nullable String biomeKey) {
            if (biomeKey == null) {
                return false;
            }
            String key = namespaced(biomeKey);
            if (biomes.contains(key)) {
                return true;
            }
            // default_ores.json 的谓词：in_dimension(the_nether) ∧ not(is_namespace betternether)。
            // 本生成器只产下界，维度那一半恒真，于是只剩命名空间那一半 —— 而且要用归一后的 key
            // 判：裸键是本包群系，不是「非 betternether」。
            return anyNonBetternetherBiome && !key.startsWith(BETTERNETHER_NAMESPACE);
        }

        /**
         * 把群系 key 归一成本包命名空间下的完整形式。
         *
         * <p>不带冒号的键视为 {@code betternether} 的本地键：{@code WorldView#biomeAt} 的裸键
         * 只可能来自 {@code NetherBiomes.ALL}，而任何注册表 id 的完整形式一定带命名空间
         * （{@code minecraft:...} 或别的数据包）。所以一个<b>未知</b>的裸键会被归到本包命名空间
         * 里、按「不在清单中」处理，而不是被当成「非本包群系」放行 —— 后者等于让拼错的键绕过
         * 群系限制。
         */
        private static String namespaced(String biomeKey) {
            return biomeKey.indexOf(':') < 0 ? BETTERNETHER_NAMESPACE + biomeKey : biomeKey;
        }

        /** 链里的 {@code minecraft:count} 次数；没有这一步时为 0。 */
        public int countOf() {
            for (OreStep step : placement) {
                if (step.kind() == OreStep.Kind.COUNT) {
                    return step.value();
                }
            }
            return 0;
        }

        /** 链里的 {@code minecraft:rarity_filter chance}；没有这一步时为 0。 */
        public int rarityOf() {
            for (OreStep step : placement) {
                if (step.kind() == OreStep.Kind.RARITY) {
                    return step.value();
                }
            }
            return 0;
        }

        /** 只用 {@code count} 而不用 {@code rarity_filter}。 */
        public boolean usesCount() {
            return countOf() > 0 && rarityOf() == 0;
        }

        /** The band's lower Y in a world of the given bounds. */
        public int resolveMinY(int worldMinY, int worldMaxYExclusive) {
            return min.resolve(worldMinY, worldMaxYExclusive);
        }

        /** The band's upper Y in a world of the given bounds. */
        public int resolveMaxY(int worldMinY, int worldMaxYExclusive) {
            return max.resolve(worldMinY, worldMaxYExclusive);
        }
    }
}
