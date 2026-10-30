package dev.mumu.betternether.worldgen;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.mumu.betternether.terrain.OpenSimplexNoise;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 上游<em>地形</em>特征里「形状是算法」的那几个，逐行移植。
 *
 * <h2>为什么地形需要另一张注册表</h2>
 * 上游 {@code world/features/} 下的地形特征不像植被那样全是 {@code MapCodec.unit(...)} 的空配置：
 * {@code wover:pillar} 的尖端粗细、{@code wover:sequence} / {@code wover:condition} 的分支、
 * {@code betternether:obsidian_crystal} 的十二面体，都是**代码里算出来的形状**，JSON 只带参数。
 * 所以这里保留算法本体，只把「写方块」接到 {@link WorldView} 上，由装载器当成 {@link Feature} 用。
 *
 * <h2>覆盖范围（= step 0/1/4/7/9 里出现的全部地形 type）</h2>
 * <table border="1">
 *   <caption>上游 JSON 里出现的地形 feature type 与本类的对应</caption>
 *   <tr><th>type</th><th>上游实现</th><th>本类</th></tr>
 *   <tr><td>{@code wover:pillar}</td>
 *       <td>{@code wover-feature-api/.../api/features/PillarFeature.java}</td>
 *       <td>{@link Pillar}（6 个 {@code transform} 全部实现）</td></tr>
 *   <tr><td>{@code wover:sequence}</td>
 *       <td>{@code .../api/features/SequenceFeature.java}</td>
 *       <td>{@link #sequence}</td></tr>
 *   <tr><td>{@code wover:condition}</td>
 *       <td>{@code .../api/features/ConditionFeature.java}</td>
 *       <td>{@link Condition}</td></tr>
 *   <tr><td>{@code betternether:obsidian_crystal}</td>
 *       <td>{@code .../world/features/CrystalFeature.java} + {@code NetherSurfaceFeature}</td>
 *       <td>{@link ObsidianCrystal}</td></tr>
 * </table>
 *
 * <p>{@code minecraft:simple_block} / {@code wover:random_patch} / {@code minecraft:block_column} /
 * {@code wover:overlay} / {@code minecraft:random_selector} / {@code wover:template} 已经在
 * {@link WorldgenLoader} 与 {@link Feature} 里，这里不重复实现；{@code minecraft:ore}（含
 * {@code gloomwood_ceiling_debris}）归 {@code OrePopulator} 那条线。
 *
 * <h2>第五个：{@code minecraft:multiface_growth}（幽匿脉）</h2>
 * 上游 {@code NetherTerrain#sculkVein} 直接构造
 * {@code new MultifaceGrowthFeature(Blocks.SCULK_VEIN, 10, true, true, true, 0.75F, tag)}，
 * 形状全在原版类里。实现见 {@link SculkVein}：算法逐条来自本机 mojmap 服务端 jar
 * （{@code folia测试端/versions/26.3/mucore-26.3.jar}）的 {@code javap -p -c}，
 * 读的是 {@code MultifaceGrowthFeature} / {@code MultifaceSpreader}(+{@code SpreadType}) /
 * {@code SculkVeinBlock$SculkVeinSpreaderConfig} 的方法体，不是签名。
 * 四处只有「实心/可替换/流体来源/full-face 形状」这类 Bukkit 拿不到的判据用近似，
 * 逐条写在 {@link SculkVein} 的类文档里，随机流不受影响。
 *
 * <p>旧 waiver 里另外两条也已被证伪，一并删掉：{@code betternether:smoker} 与
 * {@code betternether:netherrack_stalactite}、以及完整黑曜石调色板**现在都在 CE 包里注册了**，
 * 所以 {@code patch_smoker} 与尖锥不需要任何豁免。
 *
 * <h2>随机数：个数与顺序必须与上游一致</h2>
 * 每个 {@code world.random()} 调用的位置都对得上上游：
 * <ul>
 *   <li>{@link Pillar}：先 {@code max_height} 再 {@code min_height}（<b>这个顺序反了就换世界</b>），
 *       之后<b>不再抽随机数</b>（状态变体在装载期就解析好了）；</li>
 *   <li>{@link ObsidianCrystal}：{@code nextInt(2)} → {@code nextDouble} → 三个
 *       {@code nextFloat}，之后逐格的 {@code nextInt(12/9/20/50/4)} 只在对应分支里抽；</li>
 *   <li>{@link Condition} 与 {@link #sequence} 自己不抽，全部交给子特征与 filter。</li>
 * </ul>
 *
 * <h2>方块状态：能表达多少就表达多少</h2>
 * 上游 {@code PillarFeature.KnownTransformers} 是**改方块状态属性**（{@code size} /
 * {@code bottom} / {@code shape}）。这里不做「读旧状态再 {@code setValue}」（Bukkit 的
 * {@code BlockData} 在属性不存在时会抛异常），而是在装载期按属性值各解析一次
 * （{@link WorldgenAccess#block}），运行期只做数组索引。CE 侧属性不被支持时
 * {@code CraftEngineLookup} 会退回默认状态 —— 于是「包里有属性就生效、没有就退化成单一方块」，
 * 不会因为缺属性而炸掉整条链。晶体的 {@code weeping/crying/glowstone} 用的是完整调色板，
 * 全部在包里注册过（旧 {@code TerrainFeaturePopulator} 说「只有两个黑曜石」是过时的判断）。
 */
public final class UpstreamTerrainFeatures {
    /**
     * 本类负责的 type 全名。装载器据此决定是否继续 skip；命中就调
     * {@link #create(String, JsonObject, WorldgenAccess)}。
     */
    public static final Set<String> SUPPORTED = Set.of(
            "wover:pillar",
            "wover:sequence",
            "wover:condition",
            "betternether:obsidian_crystal",
            "minecraft:multiface_growth"
    );

    /**
     * 下界「海平面」，即上游 {@code ChunkGenerator#getSeaLevel()}。
     *
     * <p>{@code NetherSurfaceFeature#place} 用它当 {@code findSurfaceBelow} 的下界：
     * {@code findSurfaceBelow(level, origin, minHeight(generator), this::isValidSurface)}。
     * 原版下界的 {@code NoiseGeneratorSettings.seaLevel} 是 32，本移植的生成器同样是
     * {@code minY = 0}、{@code CEILING_Y = 127} 的 128 层世界，所以常量 32 与上游对齐。
     */
    static final int NETHER_SEA_LEVEL = 32;

    /** 装载期发现的问题（缺方块、属性退回默认、无法编译的子特征…）。 */
    private static final List<String> NOTES = Collections.synchronizedList(new ArrayList<>());

    private UpstreamTerrainFeatures() {
    }

    /** 本类支持的 type 全名。 */
    public static Set<String> supportedTypes() {
        return SUPPORTED;
    }

    /** 装载期发现的问题，由装载器并入自己的 notes 一次性打出来。 */
    public static List<String> loadNotes() {
        return List.copyOf(NOTES);
    }

    // ---------------------------------------------------------------- 入口

    /**
     * 装载器需要的三件事：解析方块、解析方块谓词、编译子 placed feature。
     *
     * <p>由 {@link WorldgenLoader} 用三个方法引用构造：
     * {@code new WorldgenAccess(this::block, this::predicate, this::placedOrInline)}。
     * 做成 3 个函数式接口的 record 而不是让调用方实现一个 3 方法的接口，是为了
     * **装载器侧只有一行**，也让单元测试能只替换需要的那一个（{@code null} 表示「这里不用」）。
     */
    public record WorldgenAccess(BlockResolver blocks, PredicateResolver predicates,
                                 PlacedResolver placers) {
        /** {@code id + properties} → 方块状态；解析不到返回 {@code null}。 */
        @FunctionalInterface
        public interface BlockResolver {
            @Nullable
            BlockData resolve(String id, @Nullable JsonObject properties);
        }

        /** 方块谓词 JSON → 谓词。 */
        @FunctionalInterface
        public interface PredicateResolver {
            BlockPredicates.Predicate resolve(@Nullable JsonObject json);
        }

        /** 一个 placed feature（字符串 id 或内联对象）→ 可直接放置的 {@link Feature}。 */
        @FunctionalInterface
        public interface PlacedResolver {
            @Nullable
            Feature resolve(@Nullable JsonElement element);
        }

        @Nullable
        BlockData block(String id, @Nullable JsonObject properties) {
            return blocks.resolve(id, properties);
        }

        BlockPredicates.Predicate predicate(@Nullable JsonObject json) {
            return predicates.resolve(json);
        }

        @Nullable
        Feature placed(@Nullable JsonElement element) {
            return placers.resolve(element);
        }
    }

    /**
     * 建一个地形特征；{@code null} 表示这个 type 不归本类管（装载器继续问下一张注册表）。
     */
    public static @Nullable Feature create(String configuredType, @Nullable JsonObject json,
                                           WorldgenAccess access) {
        if (json == null) {
            note("特征 " + configuredType + " 缺少配置对象");
            return null;
        }
        return switch (configuredType) {
            case "wover:pillar" -> custom("wover:pillar", pillar(json, access));
            case "wover:sequence" -> sequence(json, access);
            case "wover:condition" -> custom("wover:condition", condition(json, access));
            case "betternether:obsidian_crystal" ->
                    custom("betternether:obsidian_crystal", new ObsidianCrystal(access));
            case "minecraft:multiface_growth" -> custom("minecraft:multiface_growth",
                    sculkVein(json, access));
            case "wover:mark_postprocessing" ->
                    custom("wover:mark_postprocessing", markPostProcessing());
            default -> null;
        };
    }

    private static @Nullable Feature custom(String name, @Nullable Feature.Custom.Placer placer) {
        return placer == null ? null : new Feature.Custom(name, placer);
    }

    // ---------------------------------------------------------------- wover:mark_postprocessing

    /**
     * {@code wover:mark_postprocessing}：**只**把当前位置放进区块的「放置后处理」队列，然后返回
     * {@code true}，不写任何方块，也不消耗随机数。
     *
     * <p>逐行照抄上游（WorldWeaver {@code MarkPostProcessingFeature#place}）：
     * <pre>
     * level.getChunk(pos.getX() &gt;&gt; 4, pos.getZ() &gt;&gt; 4)
     *      .markPosForPostProcessing(new BlockPos(pos.getX() &amp; 15, pos.getY(), pos.getZ() &amp; 15));
     * return true;
     * </pre>
     *
     * <p>数据里它的唯一用法是 {@code nether_terrain:mark}：{@code placement} 只有一条
     * {@code block_predicate_filter(matching_blocks lava)}，所以「灌完岩浆之后把岩浆格标记成
     * 待后处理」，让流体在区块提交后重新结算。上游把它串在 {@code flooded_lava_pit} 的
     * {@code sequence} 末尾（{@code extend_basalt} → {@code flooded_lava_pit_surface} → {@code mark}）。
     *
     * <p>本移植的等价物是 {@link WorldView#markForPostProcessing}：生产实现问真实的
     * {@code ChunkAccess#markPosForPostProcessing}（{@code ProtoChunk}/{@code ImposterProtoChunk}
     * 覆写过，不是基类那个「log 一句然后什么都不做」的版本）。标记拿不到时 {@link WorldView}
     * 的默认实现会记账，不静默；这里仍然返回 {@code true} —— 上游的返回值是常量，
     * 调用方的 {@code wover:sequence} 也不看它。
     */
    private static Feature.Custom.Placer markPostProcessing() {
        return (world, point) -> {
            world.markForPostProcessing(point.x(), point.y(), point.z());
            return true;
        };
    }

    // ---------------------------------------------------------------- wover:pillar

    /**
     * {@code wover:pillar}：**逐行照抄** {@code PillarFeature#place}（release/26.3）。
     *
     * <p>关键点（顺序即语义）：
     * <ol>
     *   <li>{@code max_height.sample(random)} **然后** {@code min_height.sample(random)}；</li>
     *   <li>从 {@code origin} 沿 {@code direction} 前进一步开始，{@code height = 1..max-1} 逐个测
     *       {@code allowed_placement}；第一个不通过的位置把 {@code max_height} 截短为当前
     *       {@code height} 并退出。**测不到的位置就是 {@code aboveTop}**，后面
     *       {@code triple_shape_fill} 还要用它判「空间够不够」；</li>
     *   <li>截短后的 {@code max_height < min_height} 直接放弃（不写任何方块）；</li>
     *   <li>逐格写：{@code transform(height, max_height - 1, ...)}，注意第二个参数是
     *       <b>尖端下标</b>（{@code max_height - 1}），不是 {@code max_height}。</li>
     * </ol>
     *
     * <p>{@code state} 的解析在装载期完成，并为每个 {@code transform} 需要的属性值预解析一份状态
     * （见类文档「方块状态」）。
     */
    static @Nullable Pillar pillar(JsonObject json, WorldgenAccess access) {
        BlockState state = BlockState.of(json.get("state"), access);
        if (state == null) {
            return null;
        }
        Point.Direction direction = json.has("direction")
                ? Point.Direction.byJsonName(string(json, "direction")) : Point.Direction.UP;
        IntProvider minHeight = json.has("min_height")
                ? intProvider(json.get("min_height")) : new IntProvider.Constant(0);
        IntProvider maxHeight = json.has("max_height")
                ? intProvider(json.get("max_height")) : new IntProvider.Constant(0);
        BlockPredicates.Predicate allowed = access.predicate(json.getAsJsonObject("allowed_placement"));
        if (allowed == null) {
            note("pillar 的 allowed_placement 无法解析，整条柱子跳过");
            return null;
        }
        Transform transform = Transform.byName(json.has("transform") ? string(json, "transform") : "none");
        return new Pillar(state, transform, direction, minHeight, maxHeight, allowed, access);
    }

    /**
     * {@code PillarFeature.KnownTransformers}：把「第几格 / 尖端第几格」变成方块状态属性。
     *
     * <p>属性名与取值来自 {@code wover-block-api/.../block/api/BlockProperties.java}：
     * {@code SIZE = IntegerProperty.create("size", 0, 7)}、
     * {@code BOTTOM = BooleanProperty.create("bottom")}、
     * {@code TRIPLE_SHAPE = EnumProperty.create("shape", TripleShape)}（{@code bottom/middle/top}）。
     */
    enum Transform {
        /** {@code size = clamp(tip - height, 0, 7)}：尖端最细，往下逐格变粗。 */
        SIZE_DECREASE("size_decrease") {
            @Override
            String value(int height, int tip) {
                return String.valueOf(Math.max(0, Math.min(7, tip - height)));
            }
        },
        /** {@code size = clamp(height, 0, 7)}：底部最细，往上逐格变粗。 */
        SIZE_INCREASE("size_increase") {
            @Override
            String value(int height, int tip) {
                return String.valueOf(Math.max(0, Math.min(7, height)));
            }
        },
        /** {@code bottom = (height == tip)}：只有尖端带 {@code bottom}。 */
        BOTTOM_GROW("bottom_grow") {
            @Override
            String value(int height, int tip) {
                return String.valueOf(height == tip);
            }
        },
        /** {@code bottom = (height == 0)}：只有底部带 {@code bottom}。 */
        BOTTOM("bottom") {
            @Override
            String value(int height, int tip) {
                return String.valueOf(height == 0);
            }
        },
        /** {@code shape = bottom/top/middle}，并要求尖端上方那格**不**满足 {@code allowed_placement}。 */
        TRIPLE_SHAPE_FILL("triple_shape_fill") {
            @Override
            String value(int height, int tip) {
                if (height == 0) {
                    return "bottom";
                }
                return height == tip ? "top" : "middle";
            }
        },
        /** 没有 {@code transform}（本包数据里不会出现，留作兜底）。 */
        NONE("none") {
            @Override
            String value(int height, int tip) {
                return null;
            }
        };

        /** JSON 里的名字。 */
        final String name;

        Transform(String name) {
            this.name = name;
        }

        abstract String value(int height, int tip);

        /** {@code transform} 可能用到的属性取值（装载期各解析一份状态）。 */
        String[] candidates() {
            return switch (this) {
                case SIZE_DECREASE, SIZE_INCREASE ->
                        new String[]{"0", "1", "2", "3", "4", "5", "6", "7"};
                case BOTTOM, BOTTOM_GROW -> new String[]{"false", "true"};
                case TRIPLE_SHAPE_FILL -> new String[]{"bottom", "middle", "top"};
                case NONE -> new String[0];
            };
        }

        static Transform byName(String name) {
            String key = name.toLowerCase(Locale.ROOT);
            for (Transform transform : values()) {
                if (transform.name.equals(key)) {
                    return transform;
                }
            }
            note("未知的 pillar transform：" + name + "（按 none 处理）");
            return NONE;
        }

        /** 属性名：{@code size} / {@code bottom} / {@code shape}。 */
        String property() {
            return switch (this) {
                case SIZE_DECREASE, SIZE_INCREASE -> "size";
                case BOTTOM, BOTTOM_GROW -> "bottom";
                case TRIPLE_SHAPE_FILL -> "shape";
                case NONE -> "";
            };
        }
    }

    /**
     * 一个 {@code state} 描述（{@code {"id": ..., "properties": {...}}}）。
     *
     * <p>两种上游写法都认：直接给 {@code id}，或包一层
     * {@code {"type": "minecraft:simple_state_provider", "state": {...}}}。
     */
    static final class BlockState {
        private final String id;
        private final JsonObject properties;
        private final @Nullable BlockData base;

        private BlockState(String id, JsonObject properties, @Nullable BlockData base) {
            this.id = id;
            this.properties = properties;
            this.base = base;
        }

        static @Nullable BlockState of(@Nullable JsonElement element, WorldgenAccess access) {
            JsonObject direct = element != null && element.isJsonObject()
                    ? element.getAsJsonObject() : null;
            while (direct != null && !direct.has("id") && direct.has("state")
                    && direct.get("state").isJsonObject()) {
                direct = direct.getAsJsonObject("state");
            }
            if (direct == null || !direct.has("id")) {
                note("pillar 的 state 缺少 id");
                return null;
            }
            String id = direct.get("id").getAsString();
            JsonObject properties = direct.has("properties")
                    ? direct.getAsJsonObject("properties").deepCopy() : new JsonObject();
            return new BlockState(id, properties, access.block(id, properties));
        }

        /** 在原属性的基础上覆盖一条属性，再解析一次（装载期调用）。 */
        @Nullable
        BlockData resolve(String property, String value, WorldgenAccess access) {
            JsonObject merged = properties.deepCopy();
            merged.addProperty(property, value);
            return access.block(id, merged);
        }

        @Nullable
        BlockData base() {
            return base;
        }
    }

    /** {@code wover:pillar} 的放置算法（形状与随机数顺序见 {@link #pillar}）。 */
    static final class Pillar implements Feature.Custom.Placer {
        private final BlockState state;
        private final Transform transform;
        private final Point.Direction direction;
        private final IntProvider minHeight;
        private final IntProvider maxHeight;
        private final BlockPredicates.Predicate allowedPlacement;
        /** 属性值 → 预解析状态（装载期填好；{@code none} 时为空表）。 */
        private final Map<String, BlockData> variants;

        Pillar(BlockState state, Transform transform, Point.Direction direction,
               IntProvider minHeight, IntProvider maxHeight,
               BlockPredicates.Predicate allowedPlacement, WorldgenAccess access) {
            this.state = state;
            this.transform = transform;
            this.direction = direction;
            this.minHeight = minHeight;
            this.maxHeight = maxHeight;
            this.allowedPlacement = allowedPlacement;
            this.variants = new java.util.LinkedHashMap<>();
            fill(access);
        }

        /** 把 {@code transform} 可能用到的属性值各解析一份：SIZE 0..7、BOTTOM false/true、SHAPE 三种。 */
        private void fill(WorldgenAccess access) {
            String property = transform.property();
            if (property.isEmpty()) {
                return;
            }
            for (String value : transform.candidates()) {
                BlockData data = state.resolve(property, value, access);
                if (data != null) {
                    variants.put(value, data);
                }
            }
        }

        @Override
        public boolean place(WorldView world, Point point) {
            Random random = world.random();
            int max = maxHeight.sample(random);
            int min = minHeight.sample(random);

            // 1) 量高度：第一个 allowed_placement 不通过的位置就是 aboveTop
            Point probe = point.offset(direction, 1);
            for (int height = 1; height < max; ++height) {
                if (!allowedPlacement.test(world, probe)) {
                    max = height;
                    break;
                }
                probe = probe.offset(direction, 1);
            }
            if (max < min) {
                return false;
            }
            // 2) triple_shape_fill 额外要求「尖端上方那格不能是可放置的」（空间必须够）
            if (transform == Transform.TRIPLE_SHAPE_FILL && allowedPlacement.test(world, probe)) {
                return false;
            }

            // 3) 逐格写：第 0 格在 origin，状态按 (height, tip) 取
            Point target = point;
            int tip = max - 1;
            for (int height = 0; height < max; ++height) {
                BlockData data = data(height, tip);
                if (data != null && world.inRegion(target.x(), target.y(), target.z())) {
                    world.setBlock(target.x(), target.y(), target.z(), data);
                }
                target = target.offset(direction, 1);
            }
            return true;
        }

        private @Nullable BlockData data(int height, int tip) {
            String value = transform.value(height, tip);
            if (value == null) {
                return state.base();
            }
            BlockData variant = variants.get(value);
            return variant != null ? variant : state.base();
        }
    }

    /**
     * 建 {@code minecraft:multiface_growth}。
     *
     * <p>上游 {@code NetherTerrain#sculkVein} 把参数直接写在代码里
     * （{@code search_range = 10}、floor/ceiling/wall 全 {@code true}、{@code chance_of_spreading = 0.75}），
     * JSON 只留 {@code block} / {@code can_be_placed_on} / 三个开关 / 扩散概率。缺省值与 codec 一致。
     *
     * <p>{@code can_be_placed_on} 是 tag 字符串（{@code "#wover:surfaces/sculk_like"}）。这里把它
     * 包成一条 {@code matching_block_tag} 谓词交给装载器解析 —— tag 的解析与
     * 实现只该有一份（{@code WorldgenLoader#tagPredicate}），本类不另建一套。
     */
    static @Nullable SculkVein sculkVein(JsonObject json, WorldgenAccess access) {
        String blockId = json.has("block") ? string(json, "block") : "minecraft:sculk_vein";
        if (!"minecraft:sculk_vein".equals(blockId)) {
            // 扩散规则（SculkVeinSpreaderConfig）是 sculk 专有的，换方块就得换规则，不能照搬。
            note("multiface_growth 的 block 不是 minecraft:sculk_vein（" + blockId + "），本类只移植了 sculk 的扩散规则");
            return null;
        }
        BlockData vein = access.block(blockId, null);
        if (vein == null) {
            note("minecraft:sculk_vein 解析不到，幽匿脉不会放置");
            return null;
        }
        JsonObject tagPredicate = new JsonObject();
        tagPredicate.addProperty("type", "minecraft:matching_block_tag");
        String tag = json.has("can_be_placed_on") ? string(json, "can_be_placed_on") : "minecraft:sculk";
        tagPredicate.addProperty("tag", tag.startsWith("#") ? tag.substring(1) : tag);
        BlockPredicates.Predicate canBePlacedOn = access.predicate(tagPredicate);
        if (canBePlacedOn == null) {
            note("multiface_growth 的 can_be_placed_on 无法解析：" + tag);
            return null;
        }
        boolean floor = !json.has("can_place_on_floor") || json.get("can_place_on_floor").getAsBoolean();
        boolean ceiling = !json.has("can_place_on_ceiling") || json.get("can_place_on_ceiling").getAsBoolean();
        boolean wall = !json.has("can_place_on_wall") || json.get("can_place_on_wall").getAsBoolean();
        int searchRange = json.has("search_range")
                ? json.get("search_range").getAsInt() : 10;
        float chance = json.has("chance_of_spreading")
                ? json.get("chance_of_spreading").getAsFloat() : 0.5F;
        return new SculkVein(vein, searchRange, chance, floor, ceiling, wall, canBePlacedOn);
    }

    // ---------------------------------------------------------------- wover:sequence

    /**
     * {@code wover:sequence}：**依次**放每一条子 placed feature，任一条成功就算成功
     * （{@code SequenceFeature#place} 的 {@code placed |= f.value().place(...)}）。
     *
     * <p>这里直接用 {@link Feature#Overlay}：它的语义就是「子特征都在同一个位置上各放一次」，
     * 与 sequence 完全一致，没必要再造一个同名 record。
     *
     * <p>某条子特征编译不出来（缺方块、缺模板）时**只丢那一条**：整条 sequence 一起放弃会让
     * 「补丁里的树枝 + 骨头锥」这种组合一个都不剩，丢掉的内容更多。丢掉的条目会进 notes。
     */
    static @Nullable Feature sequence(JsonObject json, WorldgenAccess access) {
        JsonArray array = json.getAsJsonArray("features");
        if (array == null) {
            note("wover:sequence 缺少 features 数组");
            return null;
        }
        List<Feature> features = new ArrayList<>(array.size());
        for (JsonElement element : array) {
            Feature feature = access.placed(element);
            if (feature == null) {
                note("wover:sequence 的子特征无法编译，已跳过：" + describe(element));
                continue;
            }
            features.add(feature);
        }
        if (features.isEmpty()) {
            return null;
        }
        return new Feature.Overlay(List.copyOf(features));
    }

    // ---------------------------------------------------------------- wover:condition

    /**
     * {@code wover:condition}：先在该位置跑一次 {@code filter}（一个**放置修饰符**），
     * 出位置就走 {@code filter_pass}，一个都不出就走 {@code filter_fail}（可缺省）。
     *
     * <p>上游用 {@code modify(...)} 的 consumer 记一个 boolean，这里用「只有一个元素的列表」
     * 达到同一效果：{@code filter} 自己消耗的随机数与上游完全相同。
     *
     * <p>{@code filter} 在本包数据里只出现 {@code wover:is_basin} 一种
     * （{@code flooded_lava_pit_surface}），所以这里按类型显式构造；遇到没见过的类型记 note
     * 并让整条 placed feature 跳过 —— 而不是「当条件永远成立」（那会把熔岩湖灌到所有地方）。
     */
    static @Nullable Condition condition(JsonObject json, WorldgenAccess access) {
        PlacementModifier filter = filter(json.getAsJsonObject("filter"), access);
        if (filter == null) {
            return null;
        }
        Feature pass = access.placed(json.get("filter_pass"));
        if (pass == null) {
            note("wover:condition 的 filter_pass 无法编译");
            return null;
        }
        Feature fail = json.has("filter_fail") ? access.placed(json.get("filter_fail")) : null;
        return new Condition(filter, pass, fail);
    }

    private static @Nullable PlacementModifier filter(@Nullable JsonObject json, WorldgenAccess access) {
        if (json == null) {
            note("wover:condition 缺少 filter");
            return null;
        }
        String type = string(json, "type");
        if (type == null) {
            note("wover:condition 的 filter 缺少 type");
            return null;
        }
        return switch (type) {
            case "wover:is_basin" -> new PlacementModifier.IsBasin(
                    access.predicate(json.getAsJsonObject("predicate")),
                    json.has("top_predicate")
                            ? access.predicate(json.getAsJsonObject("top_predicate")) : null);
            default -> {
                note("wover:condition 的 filter 类型尚未实现：" + type);
                yield null;
            }
        };
    }

    /** {@code wover:condition} 的放置体：filter 通过走 pass，否则走 fail。 */
    static final class Condition implements Feature.Custom.Placer {
        private final PlacementModifier filter;
        private final Feature pass;
        private final @Nullable Feature fail;

        Condition(PlacementModifier filter, Feature pass, @Nullable Feature fail) {
            this.filter = filter;
            this.pass = pass;
            this.fail = fail;
        }

        @Override
        public boolean place(WorldView world, Point point) {
            List<Point> out = new ArrayList<>(1);
            filter.apply(List.of(point), out, world);
            Feature chosen = out.isEmpty() ? fail : pass;
            return chosen != null && chosen.place(world, point);
        }
    }

    // ---------------------------------------------------------------- betternether:obsidian_crystal

    /**
     * {@code betternether:obsidian_crystal}：{@code CrystalFeature} 的十二面体晶体。
     *
     * <h2>两段结构</h2>
     * <ol>
     *   <li>{@code NetherSurfaceFeature#place}：从 origin <b>向下</b>扫到
     *       {@link #NETHER_SEA_LEVEL}，第一个「下界地面」方块就是晶体中心；扫不到就整条放弃
     *       （不消耗随机数）。</li>
     *   <li>{@code CrystalFeature#generate}：随机方向 + 随机十二面体半径，逐格算 SDF，按深度分四档
     *       选方块。</li>
     * </ol>
     *
     * <h2>旋转数学：已用字节码核实，不是推测</h2>
     * {@code new Vec3(x,y,z).zRot(angleZ).xRot(angleX).yRot(angleY)} 三步依次应用，
     * 每个都是「先 {@code Mth.cos}/{@code Mth.sin} 转 float，再 double 乘加」。本机
     * {@code minecraft-common.jar}（mojmap 命名）里 {@code net/minecraft/world/phys/Vec3}
     * 的 {@code -c`} 反汇编三段完全一致：
     * <pre>
     * xRot: d0 = x;               d1 = y*f + z*f1;  d2 = z*f - y*f1
     * yRot: d0 = x*f + z*f1;      d1 = y;           d2 = z*f - x*f1
     * zRot: d0 = x*f + y*f1;      d1 = y*f - x*f1;  d2 = z
     * </pre>
     * 其中 {@code f = (float) Math.cos(angle)}、{@code f1 = (float) Math.sin(angle)}。
     *
     * <h2>SDF 与四档方块</h2>
     * {@code d = (max(max(|x|+|y|, |y|+|z|), |z|+|x|) - 1) * sqrt(0.5)}，输入按
     * {@code (x/size, y/size*0.3, z/size)} 压扁 y。然后
     * {@code d<=-0.3} 泪状黑曜石（1/12）、{@code d<=-0.15} 哭泣黑曜石（1/9）、
     * 内部（六邻都在体内）1/20 荧石、贴着壳的 1/50 泪/哭泣，其余走噪声调色板
     * （{@code |OpenSimplexNoise(0).eval(v*0.2)| > 0.2 ? 玻璃 : 实心}）。
     */
    static final class ObsidianCrystal implements Feature.Custom.Placer {
        private static final double SQRT05 = Math.sqrt(0.5);
        private static final float MAX_ANGLE_X = (float) Math.toRadians(45);
        private static final float MAX_ANGLE_Y = (float) (Math.PI * 2);
        private static final float MAX_ANGLE_Z = (float) Math.toRadians(30);
        /** 与上游同一个实现（Kurt Spencer v1.1），同一个种子 0 —— 调色板纹理才逐格一致。 */
        private static final OpenSimplexNoise NOISE = new OpenSimplexNoise(0);

        private final WorldgenAccess access;

        ObsidianCrystal(WorldgenAccess access) {
            this.access = access;
        }

        @Override
        public boolean place(WorldView world, Point point) {
            Point surface = findSurfaceBelow(world, point);
            if (surface == null) {
                return false;
            }
            generate(world, surface);
            return true;
        }

        /** {@code bclib BlocksHelper#findSurfaceBelow}：从 y 往下（含 minY）找第一个匹配的方块。 */
        private static @Nullable Point findSurfaceBelow(WorldView world, Point origin) {
            for (int y = origin.y(); y >= NETHER_SEA_LEVEL; y--) {
                if (BlockPredicates.surface(world, origin.x(), y, origin.z())) {
                    return new Point(origin.x(), y, origin.z());
                }
            }
            return null;
        }

        private void generate(WorldView world, Point pos) {
            Random random = world.random();
            // genDepth：上游 ContextFeature 传的是 generator.getGenDepth()（128 层世界）
            int maxHeight = world.maxY() - world.minY() + 1;
            float scaleFactor = ((maxHeight / 128.0f) - 1) * 0.5f + 1;

            int index = random.nextInt(2);
            boolean isBlue = index == 1;
            double a = random.nextDouble();
            double radius = 2 + a * a * 5 * scaleFactor;
            int sideXZ = (int) Math.ceil(radius * 2);
            int sideY = (int) Math.ceil(radius * 3);
            float angleX = random.nextFloat() * MAX_ANGLE_X;
            float angleY = random.nextFloat() * MAX_ANGLE_Y;
            float angleZ = (random.nextFloat() * 2 - 1) * MAX_ANGLE_Z;

            String weeping = isBlue
                    ? "betternether:blue_weeping_obsidian" : "betternether:weeping_obsidian";
            String crying = isBlue
                    ? "betternether:blue_crying_obsidian" : "minecraft:crying_obsidian";

            for (int y = -sideY; y <= sideY; y++) {
                for (int x = -sideXZ; x <= sideXZ; x++) {
                    for (int z = -sideXZ; z <= sideXZ; z++) {
                        double[] v = rotate(x, y, z, angleZ, angleX, angleY);
                        double d = depth(v[0], v[1], v[2], radius);
                        if (d > 0) {
                            continue;
                        }
                        int by = pos.y() + y;
                        // 上游跳过维度上下各两格：晶体不该穿出基岩板
                        if (by <= world.minY() || by >= maxHeight - 2) {
                            continue;
                        }
                        int bx = pos.x() + x;
                        int bz = pos.z() + z;
                        if (!world.inRegion(bx, by, bz)) {
                            continue;
                        }
                        BlockData data = pick(random, index, v, d, radius, weeping, crying);
                        if (data != null) {
                            world.setBlock(bx, by, bz, data);
                        }
                    }
                }
            }
        }

        private @Nullable BlockData pick(Random random, int index, double[] v, double d, double radius,
                                         String weeping, String crying) {
            if (d <= -0.3) {
                return random.nextInt(12) == 0 ? block(weeping) : palette(index, v);
            }
            if (d <= -0.15) {
                return random.nextInt(9) == 0 ? block(crying) : palette(index, v);
            }
            if (isNotEdge(v[0], v[1], v[2], radius)) {
                return random.nextInt(20) == 0 ? block("minecraft:glowstone") : palette(index, v);
            }
            if (random.nextInt(50) == 0) {
                return random.nextInt(4) == 0 ? block(weeping) : block(crying);
            }
            return palette(index, v);
        }

        /**
         * 四块调色板 {@code [obsidian_glass, obsidian, blue_obsidian_glass, blue_obsidian]}，
         * 索引 {@code (index << 1) | (rigidNoise > 0.2 ? 0 : 1)}。
         */
        private @Nullable BlockData palette(int index, double[] v) {
            boolean glass = rigidNoise(v) <= 0.2;
            String id = index == 1
                    ? (glass ? "betternether:blue_obsidian_glass" : "betternether:blue_obsidian")
                    : (glass ? "betternether:obsidian_glass" : "minecraft:obsidian");
            return block(id);
        }

        private @Nullable BlockData block(String id) {
            return access.block(id, null);
        }

        private static double rigidNoise(double[] v) {
            return Math.abs(NOISE.eval(v[0] * 0.2, v[1] * 0.2, v[2] * 0.2));
        }

        /** {@code zRot → xRot → yRot}，逐条对齐 {@code net.minecraft.world.phys.Vec3} 的字节码。 */
        static double[] rotate(double x, double y, double z,
                               float angleZ, float angleX, float angleY) {
            float f = (float) Math.cos(angleZ);
            float f1 = (float) Math.sin(angleZ);
            double ax = x * f + y * f1;
            double ay = y * f - x * f1;

            f = (float) Math.cos(angleX);
            f1 = (float) Math.sin(angleX);
            double bx = ax;
            double bz = z * f - ay * f1;
            double by = ay * f + z * f1;

            f = (float) Math.cos(angleY);
            f1 = (float) Math.sin(angleY);
            double cx = bx * f + bz * f1;
            double cz = bz * f - bx * f1;
            return new double[]{cx, by, cz};
        }

        private static double depth(double x, double y, double z, double size) {
            return dodecahedron(x / size, y / size * 0.3, z / size);
        }

        private static boolean isInside(double x, double y, double z, double size) {
            return dodecahedron(x / size, y / size * 0.3, z / size) <= 0;
        }

        private static boolean isNotEdge(double x, double y, double z, double size) {
            return isInside(x + 1, y, z, size)
                    && isInside(x - 1, y, z, size)
                    && isInside(x, y + 1, z, size)
                    && isInside(x, y - 1, z, size)
                    && isInside(x, y, z + 1, size)
                    && isInside(x, y, z - 1, size);
        }

        private static double dodecahedron(double x, double y, double z) {
            x = Math.abs(x);
            y = Math.abs(y);
            z = Math.abs(z);
            return (Math.max(Math.max(x + y, y + z), z + x) - 1) * SQRT05;
        }
    }

    // ---------------------------------------------------------------- minecraft:multiface_growth

    /**
     * {@code minecraft:multiface_growth}：原版幽匿脉（gloomwood 的
     * {@code sculk_vein_floor/wall/ceiling} 三条 placed feature）。
     *
     * <h2>算法来源：服务端 jar 的 {@code javap -p -c} 反汇编，逐条对照</h2>
     * 用的是本机 {@code folia测试端/versions/26.3/mucore-26.3.jar}（mojmap 命名）：
     * <ul>
     *   <li>{@code MultifaceGrowthFeature#place}：先要求落点是空气/水；再
     *       {@code getShuffledDirections} 洗一遍有效方向 → {@code placeGrowthIfPossible} 试一次；
     *       失败则对每个方向重洗「除反方向外」的有效方向，并在**该方向前进一步的位置**上最多重试
     *       {@code searchRange} 次（每次都会重设到同一个位置——上游就是这样写的）；</li>
     *   <li>{@code placeGrowthIfPossible}：按给定方向序找第一个「可依附」的邻居 →
     *       {@code getStateForPlacement} 出状态 → 写 → {@code nextFloat() < chanceOfSpreading}
     *       时向随机方向扩散一次；</li>
     *   <li>{@code MultifaceSpreader#spreadFromFaceTowardDirection} 链路 +
     *       {@code SpreadType} 三态（{@code SAME_POSITION} / {@code SAME_PLANE} /
     *       {@code WRAP_AROUND}，顺序即 {@code DEFAULT_SPREAD_ORDER}）；</li>
     *   <li>{@code SculkVeinBlock.SculkVeinSpreaderConfig#stateCanBeReplaced}：sculk /
     *       sculk_catalyst / moving_piston 上不长；绕角（曼哈顿距离 2）且背面是实心面时长不进去；
     *       非水的流体、火焰里不长；</li>
     *   <li>{@code Util.shuffle}：{@code for (i = size; i > 1; i--) swap(list, nextInt(i), i - 1)}，
     *       {@code nextInt} 的上界就是当时的 {@code i}。</li>
     * </ul>
     *
     * <h2>为什么不是「直接调 NMS」</h2>
     * 上游入口是 {@code place(WorldGenLevel, ChunkGenerator, RandomSource, BlockPos)}。
     * 本仓库的放置层只有 Bukkit 的 {@code LimitedRegion}（{@link RegionView}），拿不到
     * {@code WorldGenLevel}；而且 {@code RandomSource} 也没法从 Bukkit 传进来的
     * {@code java.util.Random} 无损桥接（那会换掉整条随机流，同 seed 就不是同一个世界）。
     * 所以按上面的反汇编逐行重写，随机数的**个数与顺序**与上游一致。
     *
     * <h2>判据来源：能问真实状态的一律问，剩下的逐条标出「待接口」</h2>
     * <p><b>已用真实判据（不是近似）</b>：
     * <ul>
     *   <li>{@code BlockTags.FIRE} → {@code WorldView#inTag("minecraft:fire", …)}；</li>
     *   <li>{@code state.canBeReplaced()} → {@code WorldView#replaceable(x,y,z)}（NMS
     *       {@code BlockState}）；问不出来（默认实现返回 {@code null}）才回退到
     *       「空气/水/脉本身」三分口径；</li>
     *   <li>{@code state.is(SCULK/SCULK_CATALYST/MOVING_PISTON)}、{@code is(Blocks.WATER)}：
     *       材质比较在原版这几个方块上是可靠的（它们没有 CE 化）。</li>
     * </ul>
     * <p><b>已换成真实判据（WorldView 提供，零反射）</b>：
     * <ul>
     *   <li>{@code state.isFaceSturdy(level,pos,dir)} → {@link WorldView#faceSturdy}，**带方向**，
     *       方向取 {@code face.opposite()}（从邻块朝我）；</li>
     *   <li>{@code FluidState#isSourceOfType(Fluids.WATER)} → {@link WorldView#waterSource}；
     *       {@code FluidState#isEmpty()}/{@code is(Fluids.WATER)} → {@link WorldView#fluidAt}；</li>
     *   <li>{@code ChunkAccess#markPosForPostProcessing} → {@link WorldView#markForPostProcessing}
     *       （RegionView 经 public 的 {@code CraftLimitedRegion#getHandle()} 桥接，先
     *       {@code isInRegion} 再调、不缓存 chunk）；</li>
     *   <li>{@code BlockTags.FIRE} → {@link WorldView#inTag}；</li>
     *   <li>{@code state.canBeReplaced()} → {@link WorldView#replaceable}。</li>
     * </ul>
     * <p><b>没有剩余缺口</b>：{@code canAttachTo} 的两条分支
     * （{@code isFaceFull(getBlockSupportShape(...), dir)} 与
     * {@code isFaceFull(getCollisionShape(...), dir)}）分别由
     * {@link WorldView#faceSturdy} 与 {@link WorldView#faceFullCollision} 承担，短路顺序与上游一致。
     * 唯一还会退回近似的情况是「接口返回 {@code null}」—— 那时代码会记一条 note，不会静默。
     */
    static final class SculkVein implements Feature.Custom.Placer {
        /** 上游 codec 缺省 {@code search_range = 10}（{@code NetherTerrain#sculkVein} 也传 10）。 */
        private static final int DEFAULT_SEARCH_RANGE = 10;
        /** 上游 {@code MultifaceBlock} 的六个面，顺序即 {@code Direction.values()}
         *  （{@code Direction.allShuffled} 就是拿它洗牌）。 */
        private static final Point.Direction[] ALL_DIRECTIONS = {
                Point.Direction.DOWN, Point.Direction.UP,
                Point.Direction.NORTH, Point.Direction.SOUTH,
                Point.Direction.WEST, Point.Direction.EAST
        };

        private final BlockData vein;
        private final int searchRange;
        private final float chanceOfSpreading;
        private final List<Point.Direction> validDirections;
        private final BlockPredicates.Predicate canBePlacedOn;

        SculkVein(BlockData vein, int searchRange, float chanceOfSpreading,
                  boolean floor, boolean ceiling, boolean wall,
                  BlockPredicates.Predicate canBePlacedOn) {
            this.vein = vein;
            this.searchRange = searchRange;
            this.chanceOfSpreading = chanceOfSpreading;
            this.canBePlacedOn = canBePlacedOn;
            // MultifaceGrowthFeature#validDirections：上→下→四个水平方向
            List<Point.Direction> directions = new ArrayList<>(6);
            if (ceiling) {
                directions.add(Point.Direction.UP);
            }
            if (floor) {
                directions.add(Point.Direction.DOWN);
            }
            if (wall) {
                directions.add(Point.Direction.NORTH);
                directions.add(Point.Direction.EAST);
                directions.add(Point.Direction.SOUTH);
                directions.add(Point.Direction.WEST);
            }
            this.validDirections = List.copyOf(directions);
        }

        @Override
        public boolean place(WorldView world, Point point) {
            if (!isAirOrWater(world, point)) {
                return false;
            }
            List<Point.Direction> directions = shuffled(world.random(), validDirections);
            if (placeGrowthIfPossible(world, point, directions)) {
                return true;
            }
            for (Point.Direction direction : directions) {
                List<Point.Direction> except = shuffledExcept(world.random(), validDirections,
                        direction.opposite());
                for (int i = 0; i < searchRange; i++) {
                    Point cursor = point.offset(direction, 1);
                    BlockData here = world.blockAt(cursor.x(), cursor.y(), cursor.z());
                    if (!isAirOrWater(here) && !isVein(here)) {
                        break;
                    }
                    if (placeGrowthIfPossible(world, cursor, except)) {
                        return true;
                    }
                }
            }
            return false;
        }

        /** {@code MultifaceGrowthFeature#placeGrowthIfPossible}。 */
        private boolean placeGrowthIfPossible(WorldView world, Point pos,
                                              List<Point.Direction> directions) {
            BlockData current = world.blockAt(pos.x(), pos.y(), pos.z());
            for (Point.Direction direction : directions) {
                Point neighbour = pos.offset(direction, 1);
                if (!canBePlacedOn.test(world, neighbour)) {
                    continue;
                }
                BlockData placed = stateForPlacement(world, current, pos, direction);
                if (placed == null) {
                    return false;
                }
                world.setBlock(pos.x(), pos.y(), pos.z(), placed);
                // 原版紧接着登记后处理标记（neighbour/光照更新）。RegionView 已桥接
                // CraftLimitedRegion#getHandle() → WorldGenLevel#getChunk(...)#markPosForPostProcessing。
                world.markForPostProcessing(pos.x(), pos.y(), pos.z());
                if (world.random().nextFloat() < chanceOfSpreading) {
                    spreadFromFaceTowardRandomDirection(world, placed, pos, direction);
                }
                return true;
            }
            return false;
        }

        /**
         * {@code MultifaceSpreader#spreadFromFaceTowardRandomDirection}：把**六个**方向洗一遍，
         * 依次试「从 {@code fromFace} 朝该方向扩散」，第一个成功的就返回。
         */
        private void spreadFromFaceTowardRandomDirection(WorldView world, BlockData state, Point pos,
                                                         Point.Direction fromFace) {
            for (Point.Direction spreadDirection : shuffled(world.random(), List.of(ALL_DIRECTIONS))) {
                SpreadPos spread = spreadFromFaceTowardDirection(world, state, pos, fromFace,
                        spreadDirection);
                if (spread != null) {
                    return;
                }
            }
        }

        /** {@code MultifaceSpreader#spreadFromFaceTowardDirection}（含 {@code spreadToFace}）。 */
        private @Nullable SpreadPos spreadFromFaceTowardDirection(WorldView world, BlockData state,
                                                                  Point pos, Point.Direction fromFace,
                                                                  Point.Direction spreadDirection) {
            SpreadPos spread = getSpreadFromFaceTowardDirection(world, state, pos, fromFace,
                    spreadDirection);
            if (spread == null) {
                return null;
            }
            BlockData data = world.blockAt(spread.pos.x(), spread.pos.y(), spread.pos.z());
            return placeBlock(world, spread, data) ? spread : null;
        }

        /** {@code MultifaceSpreader#getSpreadFromFaceTowardDirection}。 */
        private @Nullable SpreadPos getSpreadFromFaceTowardDirection(WorldView world, BlockData state,
                                                                     Point pos, Point.Direction fromFace,
                                                                     Point.Direction spreadDirection) {
            if (axis(spreadDirection) == axis(fromFace)) {
                return null;
            }
            // config.isOtherBlockValidAsSource(state) == state.is(SCULK_VEIN)
            if (!isVein(state)
                    && (!hasFace(state, fromFace) || hasFace(state, spreadDirection))) {
                return null;
            }
            for (SpreadType type : SpreadType.values()) {
                SpreadPos spread = type.spreadPos(pos, spreadDirection, fromFace);
                if (canSpreadInto(world, pos, spread)) {
                    return spread;
                }
            }
            return null;
        }

        /** {@code DefaultSpreaderConfig#canSpreadInto} + {@code SculkVeinSpreaderConfig} 的替换判据。 */
        private boolean canSpreadInto(WorldView world, Point sourcePos, SpreadPos spread) {
            BlockData data = world.blockAt(spread.pos.x(), spread.pos.y(), spread.pos.z());
            if (data == null) {
                return false;
            }
            return stateCanBeReplaced(world, sourcePos, spread, data)
                    && isValidStateForPlacement(world, data, spread.pos, spread.face);
        }

        /** {@code SculkVeinSpreaderConfig#stateCanBeReplaced}。 */
        private boolean stateCanBeReplaced(WorldView world, Point sourcePos, SpreadPos spread,
                                           BlockData data) {
            Material material = data.getMaterial();
            if (material == Material.SCULK || material == Material.SCULK_CATALYST
                    || material == Material.MOVING_PISTON) {
                return false;
            }
            if (manhattan(spread.pos, sourcePos) == 2) {
                // 字节码：behind = p1(source).relative(face.getOpposite())，再问
                // isFaceSturdy(level, behind, face) —— 方向是 face 本身，不是它的反面。
                Point behind = sourcePos.offset(spread.face.opposite(), 1);
                if (faceSturdy(world, behind, spread.face)) {
                    return false;
                }
            }
            // 非水的流体不长：原版 `getFluidState().isEmpty() || is(Fluids.WATER)` 的反面。
            // 用 fluidAt 的真实流体身份（"minecraft:empty/water/lava"），不再按材质猜。
            String fluid = world.fluidAt(spread.pos.x(), spread.pos.y(), spread.pos.z());
            if (fluid != null && !"minecraft:empty".equals(fluid) && !"minecraft:water".equals(fluid)) {
                return false;
            }
            // 原版 state.is(BlockTags.FIRE)：服务端真有这个 tag，就直接问真实 tag。
            // 已核对 mucore-26.3.jar!/data/minecraft/tags/block/fire.json =
            // {minecraft:fire, minecraft:soul_fire}，所以运行期不会返回 null，兜底分支不会走到。
            Boolean fire = world.inTag("minecraft:fire", spread.pos.x(), spread.pos.y(),
                    spread.pos.z());
            if (fire != null ? fire : material == Material.FIRE || material == Material.SOUL_FIRE) {
                return false;
            }
            // 原版 state.canBeReplaced()：WorldView 已有真实判据（NMS BlockState），优先用它；
            // 问不出来（默认实现返回 null）才回退到本项目的三分口径。
            Boolean replaceable = world.replaceable(spread.pos.x(), spread.pos.y(), spread.pos.z());
            if (replaceable != null) {
                // 父类那三条里，空气与「脉本身」已由 canBeReplaced 覆盖；水源要单独问。
                Boolean source = world.waterSource(spread.pos.x(), spread.pos.y(), spread.pos.z());
                boolean water = source != null ? source : material == Material.WATER;
                return replaceable || water || material == Material.SCULK_VEIN;
            }
            return material == Material.AIR || material == Material.WATER
                    || material == Material.SCULK_VEIN;
        }

        /** {@code MultifaceBlock#isValidStateForPlacement}（sculk vein 六个面全支持）。 */
        private boolean isValidStateForPlacement(WorldView world, BlockData state, Point pos,
                                                 Point.Direction face) {
            if (isVein(state) && hasFace(state, face)) {
                return false;
            }
            return canAttachTo(world, pos.offset(face, 1), face);
        }

        /** {@code MultifaceBlock#getStateForPlacement(state, level, pos, face)}。 */
        private @Nullable BlockData stateForPlacement(WorldView world, @Nullable BlockData state,
                                                      Point pos, Point.Direction face) {
            if (!isValidStateForPlacement(world, state, pos, face)) {
                return null;
            }
            BlockData base;
            if (isVein(state)) {
                base = state;
            } else {
                base = vein.clone();
                // 水源上方要带 waterlogged（上游 setValue(WATERLOGGED, true)）
                if (world.blockAt(pos.x(), pos.y(), pos.z()) != null
                        && world.blockAt(pos.x(), pos.y(), pos.z()).getMaterial() == Material.WATER
                        && base instanceof org.bukkit.block.data.Waterlogged waterlogged) {
                    waterlogged.setWaterlogged(true);
                }
            }
            return withFace(base, face);
        }

        /** {@code MultifaceSpreader.SpreadConfig#placeBlock}：写方块（事件/后处理标记不迁移）。 */
        private boolean placeBlock(WorldView world, SpreadPos spread, @Nullable BlockData data) {
            if (data == null || !world.inRegion(spread.pos.x(), spread.pos.y(), spread.pos.z())) {
                return false;
            }
            // getStateForPlacement(defaultBlockState(), level, pos, face) → 带该面的状态
            BlockData placed = stateForPlacement(world, data, spread.pos, spread.face);
            if (placed == null) {
                return false;
            }
            world.setBlock(spread.pos.x(), spread.pos.y(), spread.pos.z(), placed);
            return true;
        }

        // ------------------------------------------------------------ 状态与谓词

        private boolean isVein(@Nullable BlockData data) {
            return data != null && data.getMaterial() == vein.getMaterial();
        }

        private boolean isAirOrWater(@Nullable BlockData data) {
            return data == null || data.getMaterial() == Material.AIR
                    || data.getMaterial() == Material.WATER;
        }

        private boolean isAirOrWater(WorldView world, Point pos) {
            return isAirOrWater(world.blockAt(pos.x(), pos.y(), pos.z()));
        }

        private boolean hasFace(@Nullable BlockData data, Point.Direction face) {
            return data instanceof org.bukkit.block.data.MultipleFacing facing
                    && facing.hasFace(blockFace(face));
        }

        /** {@code MultifaceBlock#getFaceProperty(dir).setValue(true)}。 */
        private @Nullable BlockData withFace(BlockData data, Point.Direction face) {
            if (!(data instanceof org.bukkit.block.data.MultipleFacing facing)) {
                note("minecraft:sculk_vein 的 BlockData 不是 MultipleFacing，面朝向无法表达");
                return data;
            }
            BlockData copy = data.clone();
            ((org.bukkit.block.data.MultipleFacing) copy).setFace(blockFace(face), true);
            return copy;
        }

        /**
         * {@code MultifaceBlock#canAttachTo(level, dir, pos, state)}：邻块朝我这面是否够实。
         *
         * <p>上游两条分支：{@code Block.isFaceFull(state.getBlockSupportShape(...), dir.getOpposite())}
         * 与 {@code isFaceFull(state.getCollisionShape(...), dir.getOpposite())}。这里用
         * {@link WorldView#faceSturdy}（= {@code state.isFaceSturdy(level,pos,dir)}，读的正是
         * block support shape）做主判据，**方向是 {@code face.opposite()}**（从邻块朝我）。
         *
         * <p>缺的一条：{@code isFaceFull(collisionShape, dir)} 那第二分支 —— 它只比 support shape
         * 多覆盖「支撑面不满但碰撞面满」的方块（如部分台阶组合）。WorldView 目前没有一步到位的
         * 形状入口，已把这个缺口报给 owner，不在这里用材质/形状猜。
         * {@code faceSturdy} 返回 {@code null}（问不出来，例如离线单测）时退回
         * {@link BlockData#isOccluding()} 并记一次账 —— 不静默当成 false。
         */
        /**
         * {@code MultifaceBlock#canAttachTo(level, dir, pos, state)}，两条分支按上游顺序短路：
         *
         * <pre>
         * return Block.isFaceFull(state.getBlockSupportShape(level, pos), dir.getOpposite())
         *     || Block.isFaceFull(state.getCollisionShape(level, pos), dir.getOpposite());
         * </pre>
         *
         * <p>第一支 = {@link WorldView#faceSturdy}（正读 block support shape），第二支 =
         * {@link WorldView#faceFullCollision}（正读 collision shape）。**顺序不能换**：换了两件事
         * 会变 —— 第二支多问一次（真机上绝大多数方块第一支就返回），以及「支撑面不满但碰撞面满」
         * 的方块会被判成不可依附。
         *
         * <p>「朝我」= 从我到邻块方向的相反面。
         */
        private boolean canAttachTo(WorldView world, Point neighbour, Point.Direction fromMe) {
            Point.Direction back = fromMe.opposite();
            if (faceSturdy(world, neighbour, back)) {
                return true;
            }
            Boolean full = world.faceFullCollision(neighbour.x(), neighbour.y(), neighbour.z(), back);
            if (full != null) {
                return full;
            }
            note("WorldView#faceFullCollision 问不出来，canAttachTo 只按 support shape 判定（" + neighbour + "）");
            return false;
        }

        /**
         * {@code state.isFaceSturdy(level, pos, direction)} 的裸查询（方向原样传入）。
         *
         * <p>{@code null}（问不出来，例如离线单测）时退回 {@link BlockData#isOccluding()} 并记一次账，
         * 不静默当成 false。
         */
        private boolean faceSturdy(WorldView world, Point pos, Point.Direction direction) {
            Boolean sturdy = world.faceSturdy(pos.x(), pos.y(), pos.z(), direction);
            if (sturdy != null) {
                return sturdy;
            }
            BlockData data = world.blockAt(pos.x(), pos.y(), pos.z());
            note("WorldView#faceSturdy 问不出来，退回 isOccluding（" + pos + " / " + direction + "）");
            return data != null && data.isOccluding();
        }

        private static org.bukkit.block.BlockFace blockFace(Point.Direction direction) {
            return org.bukkit.block.BlockFace.valueOf(direction.name());
        }

        /** 原版 {@code Direction#getAxis()} 的三值：{@code 0}=Y、{@code 1}=X、{@code 2}=Z。 */
        private static int axis(Point.Direction direction) {
            return switch (direction) {
                case UP, DOWN -> 0;
                case EAST, WEST -> 1;
                case NORTH, SOUTH -> 2;
            };
        }

        private static int manhattan(Point a, Point b) {
            return Math.abs(a.x() - b.x()) + Math.abs(a.y() - b.y()) + Math.abs(a.z() - b.z());
        }

        /** {@code Util.shuffle}：{@code i} 从 {@code size} 递减到 2，取 {@code nextInt(i)} 交换。 */
        private static List<Point.Direction> shuffled(Random random, List<Point.Direction> source) {
            List<Point.Direction> list = new ArrayList<>(source);
            for (int i = list.size(); i > 1; i--) {
                int j = random.nextInt(i);
                Point.Direction tmp = list.get(i - 1);
                list.set(i - 1, list.get(j));
                list.set(j, tmp);
            }
            return list;
        }

        private static List<Point.Direction> shuffledExcept(Random random,
                                                           List<Point.Direction> source,
                                                           Point.Direction excluded) {
            List<Point.Direction> kept = new ArrayList<>(source.size());
            for (Point.Direction direction : source) {
                if (direction != excluded) {
                    kept.add(direction);
                }
            }
            return shuffled(random, kept);
        }

        /**
         * {@code MultifaceSpreader.SpreadType}：三个 {@code getSpreadPos} 的逐条对照。
         *
         * <pre>
         * SAME_POSITION: new SpreadPos(pos,                            spreadDir,                pos)
         * SAME_PLANE:    new SpreadPos(pos.relative(spreadDir),        spreadDir,                pos)
         * WRAP_AROUND:   new SpreadPos(pos.relative(spreadDir)
         *                                  .relative(fromFace),        spreadDir.getOpposite(),  pos)
         * </pre>
         * 枚举顺序就是 {@code DEFAULT_SPREAD_ORDER}（{@code SculkVeinBlock} 用的正是它）。
         */
        enum SpreadType {
            SAME_POSITION,
            SAME_PLANE,
            WRAP_AROUND;

            SpreadPos spreadPos(Point pos, Point.Direction spreadDirection,
                                Point.Direction fromFace) {
                return switch (this) {
                    case SAME_POSITION -> new SpreadPos(pos, spreadDirection, pos);
                    case SAME_PLANE -> new SpreadPos(pos.offset(spreadDirection, 1),
                            spreadDirection, pos);
                    case WRAP_AROUND -> new SpreadPos(
                            pos.offset(spreadDirection, 1).offset(fromFace, 1),
                            spreadDirection.opposite(), pos);
                };
            }
        }

        /** {@code MultifaceSpreader.SpreadPos(pos, face, source)}。 */
        record SpreadPos(Point pos, Point.Direction face, Point source) {
        }
    }

    // ---------------------------------------------------------------- 小工具

    /**
     * {@code IntProvider} 的 JSON 解析。
     *
     * <p>与 {@code WorldgenLoader#intProvider} 同构（那边是 private，本类只能自带一份）；
     * 只覆盖上游 terrain JSON 用到的五种，外加裸整数（{@code "min_height": 0} 就是这种）。
     * 构造期调用一次，热路径只 {@code sample}。
     */
    static IntProvider intProvider(@Nullable JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return new IntProvider.Constant(0);
        }
        if (element.isJsonPrimitive()) {
            return new IntProvider.Constant(element.getAsInt());
        }
        JsonObject json = element.getAsJsonObject();
        String type = string(json, "type");
        if (type == null) {
            // {"min_inclusive":..,"max_inclusive":..} 这种省略 type 的 uniform 写法
            return uniform(json);
        }
        return switch (type) {
            case "minecraft:uniform" -> uniform(json);
            case "minecraft:constant" -> new IntProvider.Constant(json.get("value").getAsInt());
            case "minecraft:biased_to_bottom" -> new IntProvider.BiasedToBottom(
                    intOr(json, "min_inclusive", 1), intOr(json, "max_inclusive", 1));
            case "minecraft:clamped_normal" -> new IntProvider.ClampedNormal(
                    json.get("mean").getAsDouble(),
                    json.get("deviation").getAsDouble(),
                    intOr(json, "min_inclusive", 0), intOr(json, "max_inclusive", 0));
            case "minecraft:trapezoid" -> new IntProvider.Trapezoid(
                    intOr(json, "min_inclusive", 1), intOr(json, "max_inclusive", 1),
                    intOr(json, "plateau", 0));
            default -> {
                note("未知的 IntProvider 类型：" + type + "（按 0 处理）");
                yield new IntProvider.Constant(0);
            }
        };
    }

    private static IntProvider uniform(JsonObject json) {
        return new IntProvider.Uniform(intOr(json, "min_inclusive", 0), intOr(json, "max_inclusive", 0));
    }

    private static int intOr(JsonObject json, String key, int fallback) {
        return json.has(key) ? json.get(key).getAsInt() : fallback;
    }

    private static @Nullable String string(JsonObject json, String key) {
        return json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsString() : null;
    }

    private static String describe(@Nullable JsonElement element) {
        if (element == null) {
            return "null";
        }
        if (element.isJsonPrimitive()) {
            return element.getAsString();
        }
        if (element.isJsonObject() && element.getAsJsonObject().has("type")) {
            return "内联 " + element.getAsJsonObject().get("type").getAsString();
        }
        return String.valueOf(element).length() > 60
                ? String.valueOf(element).substring(0, 57) + "…" : String.valueOf(element);
    }

    private static void note(String message) {
        NOTES.add(message);
    }
}
