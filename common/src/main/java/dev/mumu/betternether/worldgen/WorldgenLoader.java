package dev.mumu.betternether.worldgen;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.mumu.betternether.terrain.CraftEngineLookup;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把 jar 内 {@code worldgen-upstream/} 的 JSON 编译成运行期结构。
 *
 * <h2>为什么在启动时编译，而不是生成时逐块解析</h2>
 * 每区块都会遍历这些特征。JSON 解析、id 解析、权重表构建都是「一次就够」的工作，放到启动期
 * 意味着生成热路径上只剩数组索引与随机数。
 *
 * <h2>两条解析规则（上游 JSON 里最容易搞混的地方）</h2>
 * <ul>
 *   <li>配置特征位置上的字符串（{@code placed_feature.feature}）指向 {@code feature/<name>}；
 *       而 {@code wover:random_patch.feature} / {@code overlay.features} /
 *       {@code random_selector} 的字符串指向 <b>placed_feature</b>
 *       （wover 的 {@code featureToPlace(NetherVegetationPlaced.X)}）。同一个名字在两个目录里
 *       都存在，只能靠上下文区分。</li>
 *   <li>解析不到的方块 id（CE 包没发布那个方块）在这里剔除并汇总；热路径上不再逐块判断，
 *       而「少了哪些 id」必须有人能看到。</li>
 * </ul>
 */
public final class WorldgenLoader {
    /** jar 内根目录，必须与 {@code scripts/bnlib/worldgen.py} 的 OUTPUT_DIR 一致。 */
    public static final String RESOURCE_ROOT = "/worldgen-upstream";

    private final List<String> notes = new ArrayList<>();

    /**
     * 装载**统计**（不是缺口）：原版分工、显式 skip、占位数量。
     *
     * <p>与 {@link #notes} 分开的理由：`notes` 的语义是「世界会少东西，必须能定位到文件」，
     * 走 WARN；而这几条只是记账 —— 原版条目本来就不该由本装载器编译，`skip:true` 是清单里
     * 写明的约定，占位数量是它们的结果。混在 WARN 里会让人以为启动有问题。
     */
    private final List<String> stats = new ArrayList<>();

    /** 方块缓存：键含属性文本，值为 {@code null} 表示「这个 id 解析不到」（负缓存）。 */
    private final Map<String, BlockData> blockCache = new HashMap<>();
    private final Map<String, JsonObject> placedCache = new HashMap<>();
    private final Map<String, JsonObject> featureCache = new HashMap<>();
    private final Map<String, TemplateData> templateCache = new HashMap<>();
    private final Set<String> missingBlockIds = new LinkedHashSet<>();
    private final Set<String> missingTemplateIds = new LinkedHashSet<>();
    /**
     * 清单里标了 {@code native: true} 的原版条目（保留 id/step/index，但**不由本装载器编译**）。
     *
     * <p>它们的执行权在原生侧（原版 datapack pass 或调度器的 {@code FeaturePlacer} 回调）；
     * 这里只把它们连同 index 一起交出去，绝不当「缺失」删掉 —— 删一条，后面所有 featureIndex 前移。
     */
    private final Set<String> nativePlacedIds = new LinkedHashSet<>();
    /** 标签名 -> 已解析方块；近似标签（{@code wover:surfaces/*}）在这里是空表。 */
    private final Map<String, List<BlockData>> tags = new HashMap<>();
    /** 同一份 manifest 里的**原始 id 字符串**：{@link BlockPredicates.BlockIds} 要按身份比，
     *  解析失败的 id 也必须留在里面（那正是「CE 缺这个方块」要与「这里是别的方块」区分的地方）。 */
    private final Map<String, java.util.Set<String>> tagIds = new HashMap<>();

    /** 装载期攒下的诊断信息，启动时一次性打出来。 */
    /** 装载统计（原版分工 / 显式 skip / 占位数量）—— 信息级，不是缺口。 */
    public List<String> stats() {
        return List.copyOf(stats);
    }

    public List<String> notes() {
        return List.copyOf(notes);
    }

    /** CE 包里不存在的方块 id（去重，按首次出现顺序）。 */
    public List<String> missingBlockIds() {
        return List.copyOf(missingBlockIds);
    }

    /** 清单里标了 {@code native: true} 的原版条目（由原生侧执行，本装载器只保留其 index）。 */
    public Set<String> nativePlacedIds() {
        return Set.copyOf(nativePlacedIds);
    }

    /** 模板缺失的 id。 */
    public List<String> missingTemplateIds() {
        return List.copyOf(missingTemplateIds);
    }

    // ---------------------------------------------------------------- 入口

    /**
     * 读 jar 内清单并编译每个群系的 placed feature 列表。
     *
     * @return 群系 key -> 已编译列表（顺序与上游 JSON 一致）
     * @throws IllegalStateException 清单缺失或结构性损坏 —— 静默返回空表会让整个世界没有植被
     */
    public @NotNull Map<String, List<CompiledFeature>> load() {
        return load("manifest");
    }

    /**
     * 读 jar 内 {@code <manifestName>.json} 并编译。
     *
     * <p>植被（{@code manifest.json}）与地形（{@code terrain.json}）各用一份清单：
     * 两者由不同的构建期模块写出，分开可以避免「植被 loader 顺带编译地形特征」这种耦合 ——
     * 两条链路的重修饰符集合本来就不一样（地形要 stencil/extend_xyz，植被不需要）。
     */
    public @NotNull Map<String, List<CompiledFeature>> load(@NotNull String manifestName) {
        JsonObject manifest = read(RESOURCE_ROOT + "/" + manifestName + ".json");
        if (manifest == null) {
            throw new IllegalStateException(
                    "jar 内缺少 " + RESOURCE_ROOT + "/" + manifestName + ".json（构建期没运行？）");
        }
        readBlockTags(manifest);

        JsonObject biomes = manifest.getAsJsonObject("biomes");
        if (biomes == null) {
            throw new IllegalStateException("worldgen 清单缺少 biomes 段");
        }
        Map<String, List<CompiledFeature>> compiled = compileBiomes(biomes);
        if (!missingBlockIds.isEmpty()) {
            notes.add("CraftEngine 包缺少 " + missingBlockIds.size()
                    + " 个世界生成方块 id，相关放置会被跳过：" + firstFew(missingBlockIds));
        }
        if (!missingTemplateIds.isEmpty()) {
            notes.add("缺少 " + missingTemplateIds.size() + " 个树模板：" + firstFew(missingTemplateIds));
        }
        // 形状注册表自己的装载期报告（属性在 CE 包里没定义、方块 id 解析不到）。
        // 这些正是「哪棵树会退化成同一个方块」的清单，必须并进启动日志，不能留在各自类里。
        notes.addAll(UpstreamSimpleTreeFeatures.loadNotes());
        installGroundTags();
        return Map.copyOf(compiled);
    }

    /**
     * 把清单的 {@code biomes} 段编译成「群系 → 有序计划」。
     *
     * <p><b>为什么单独成一个包级方法</b>：{@code worldgen-upstream/manifest.json} 是 {@code :plugin}
     * 的构建产物，{@code :common} 的测试类路径上没有它（本模块没有 test resources），
     * {@link #load(String)} 那条路没法在单测里喂数据。这里只吃一份已经解析好的 JSON，
     * 于是「条目怎么读、占位怎么处理、下标怎么保」都能用合成清单直接断言。
     *
     * <h2>三种条目与 {@code skip: true}</h2>
     * <ul>
     *   <li><b>可执行条目</b>（普通 id 字符串，或 {@code {id:…}} 不带标记）：必须编译出真实特征。
     *       编译不出来就**降级成占位并在 notes 里点名**（下面 {@code failedIds}），
     *       因为这类条目在清单里的意思就是「本移植负责」——静默变成占位等于整条特征消失。</li>
     *   <li><b>原版条目</b>（{@code native:true}）：交给原版 {@code FeaturePlacer}（{@link NativeDecorationBridge}）。</li>
     *   <li><b>显式占位</b>（{@code skip:true}）：清单作者明确声明「这条不由本移植执行」
     *       （矿归 {@code OrePopulator}、已移除的上游条目等）。只保下标，
     *       **不尝试编译、不计入失败**，因此不会在日志里刷成「缺 placed_feature」。</li>
     * </ul>
     * 三种都占住自己在 {@code features[step]} 里的位置：{@code featureIndex} 是整条数组在全局
     * 有序列表里的下标，删一条后面全部前移（见 {@link CompiledFeature} 的说明）。
     */
    @NotNull Map<String, List<CompiledFeature>> compileBiomes(@NotNull JsonObject biomes) {
        Map<String, List<CompiledFeature>> compiled = new LinkedHashMap<>();
        int placeholders = 0;
        int explicitSkips = 0;
        int failed = 0;
        Set<String> failedIds = new LinkedHashSet<>();
        for (Map.Entry<String, JsonElement> entry : biomes.entrySet()) {
            List<CompiledFeature> list = new ArrayList<>();
            JsonElement value = entry.getValue();
            if (value.isJsonObject()) {
                // 规范形态：{biome: {step: [id…]}}。step 顺序无关（下面排序），但**每条的位置**
                // 就是它在所属 step 数组里的下标 —— 原版种子里的 featureIndex 正是这个下标，
                // 所以顺序与「不删条目」都是语义的一部分。
                JsonObject byStep = value.getAsJsonObject();
                List<Integer> steps = new ArrayList<>();
                for (String key : byStep.keySet()) {
                    steps.add(Integer.parseInt(key));
                }
                java.util.Collections.sort(steps);
                for (int step : steps) {
                    JsonArray refs = byStep.getAsJsonArray(Integer.toString(step));
                    for (int index = 0; refs != null && index < refs.size(); index++) {
                        JsonElement refElement = refs.get(index);
                        boolean nativeEntry = false;
                        boolean skipEntry = false;
                        String id;
                        if (refElement.isJsonObject()) {
                            JsonObject refObject = refElement.getAsJsonObject();
                            id = refObject.get("id").getAsString();
                            nativeEntry = refObject.has("native")
                                    && refObject.get("native").getAsBoolean();
                            skipEntry = explicitSkip(refObject, id);
                        } else {
                            id = refElement.getAsString();
                        }
                        if (skipEntry) {
                            // skip 与 native 同时出现是矛盾的清单（「不执行」与「交给原版执行」）。
                            // 按 skip 处理 —— 少放一条能被看见，多放一条会盖掉别人刚清掉的东西。
                            if (nativeEntry) {
                                notes.add("worldgen: " + id
                                        + " 同时标了 native:true 与 skip:true，按 skip 处理（不执行、只占下标）");
                            }
                            list.add(CompiledFeature.placeholder(id, step, index));
                            explicitSkips++;
                            placeholders++;
                        } else if (nativeEntry) {
                            // 原版条目：**参与** index 分配（与原版 FeatureSorter 一致，minecraft: 与
                            // 模组条目混排在同一张图上），执行权交给 NativeDecorationBridge。
                            nativePlacedIds.add(id);
                            list.add(CompiledFeature.nativePlaced(id, step, index));
                        } else {
                            CompiledFeature feature = compilePlaced(id, step, index);
                            if (feature != null) {
                                list.add(feature);
                            } else {
                                // 由 ore / terrain 等别的 owner 执行：占位保住全局排序位置，
                                // 绝不从清单里删掉（删一条后面所有 featureIndex 前移）。
                                // 但这条在清单里没有 skip 标记，字面意思就是「本移植负责」，
                                // 所以要点名记进失败清单（compilePlaced 已单独记了缺哪份 JSON）。
                                list.add(CompiledFeature.placeholder(id, step, index));
                                placeholders++;
                                failed++;
                                failedIds.add(id);
                            }
                        }
                    }
                }
            } else {
                // 过渡兼容：老的扁平数组（只有 step 9、无 index 语义）
                for (JsonElement element : value.getAsJsonArray()) {
                    CompiledFeature feature = compilePlaced(element.getAsString(),
                            VEGETAL_STEP_INDEX, 0);
                    if (feature == null) {
                        failed++;
                        failedIds.add(element.getAsString());
                        continue;
                    }
                    list.add(feature);
                }
            }
            compiled.put(entry.getKey(), List.copyOf(list));
        }
        if (!nativePlacedIds.isEmpty()) {
            stats.add("worldgen: " + nativePlacedIds.size()
                    + " 条原版条目标记为 native（由原生 FeaturePlacer/datapack 执行，本装载器不编译）");
        }
        if (explicitSkips > 0) {
            stats.add("worldgen: " + explicitSkips + " 条条目按清单的 skip:true 显式占位"
                    + "（由其它 owner 执行或已移除），不编译、不计失败，下标已保留");
        }
        if (failed > 0) {
            // 具体点名：这类条目清单里没有 skip，意思是「本移植负责」，编译失败必须能定位到文件。
            notes.add("worldgen: " + failed + " 条可执行 placed feature 编译失败（槽位保留为占位，"
                    + "这些特征不会放置）：" + firstFew(failedIds));
        }
        if (placeholders > 0) {
            stats.add("worldgen: " + placeholders + " 条条目作为占位保留（显式 skip / 编译失败），"
                    + "用于对齐全局 featureIndex");
        }
        return Map.copyOf(compiled);
    }

    /**
     * 读条目的 {@code skip} 标记（显式占位契约：{@code {"id":"…","skip":true}}）。
     *
     * <p>缺字段 = {@code false}（可执行条目）。字段不是布尔值时**抛**：一个既不是 true 也不是
     * false 的标记无法判断「该不该编译」，猜错就是把一条本该占位的特征真的放出去。
     */
    private static boolean explicitSkip(JsonObject refObject, String id) {
        JsonElement element = refObject.get("skip");
        if (element == null || element.isJsonNull()) {
            return false;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalStateException(
                    "manifest 条目 " + id + " 的 skip 必须是布尔值，实际是 " + element);
        }
        return element.getAsBoolean();
    }

    private static String firstFew(Set<String> ids) {
        List<String> list = new ArrayList<>(ids);
        return list.size() <= 8 ? list.toString() : list.subList(0, 8) + " …";
    }

    // ---------------------------------------------------------------- 方块

    /**
     * 解析方块 id + 属性。
     *
     * <p>{@code betternether:} 走 CE 的**带状态**查询（{@code BlockDefinition.getBlockState}），
     * 属性名/值不被支持时退回默认状态 —— 上游的形状（芦苇 top、巨菇 shape、墨丛 age）全靠它，
     * 丢掉属性等于把「按段拼成的蘑菇」变成一根方块柱。
     * {@code minecraft:} 走 Bukkit 按属性构造 —— 原版属性是确定的。
     */
    @Nullable BlockData block(String id, @Nullable JsonObject properties) {
        String key = properties == null || properties.isEmpty() ? id : id + properties;
        if (blockCache.containsKey(key)) {
            return blockCache.get(key);
        }
        BlockData data = id.startsWith("betternether:")
                ? CraftEngineLookup.lookup(id, propertyMap(properties))
                : vanilla(id, properties);
        if (data == null) {
            missingBlockIds.add(id);
        }
        blockCache.put(key, data);
        return data;
    }

    /** JSON 的 {@code properties} 对象 → {@code Map<String,String>}（无属性返回空表）。 */
    private static java.util.Map<String, String> propertyMap(@Nullable JsonObject properties) {
        if (properties == null || properties.isEmpty()) {
            return java.util.Map.of();
        }
        java.util.Map<String, String> map = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : properties.entrySet()) {
            map.put(entry.getKey(), entry.getValue().getAsString());
        }
        return map;
    }

    private @Nullable BlockData vanilla(String id, @Nullable JsonObject properties) {
        Material material = Material.matchMaterial(id);
        if (material == null) {
            return null;
        }
        if (properties == null || properties.isEmpty()) {
            return material.createBlockData();
        }
        StringBuilder spec = new StringBuilder(id).append('[');
        boolean first = true;
        for (Map.Entry<String, JsonElement> entry : properties.entrySet()) {
            if (!first) {
                spec.append(',');
            }
            first = false;
            spec.append(entry.getKey()).append('=').append(entry.getValue().getAsString());
        }
        spec.append(']');
        try {
            return Bukkit.createBlockData(spec.toString());
        } catch (IllegalArgumentException error) {
            // 属性名/值对不上（上游比服务端新）：退回默认状态，并记一次。
            notes.add("方块 " + spec + " 的属性无法解析，退回默认状态");
            return material.createBlockData();
        }
    }

    private void readBlockTags(JsonObject manifest) {
        JsonObject raw = manifest.getAsJsonObject("blockTags");
        if (raw == null) {
            return;
        }
        for (Map.Entry<String, JsonElement> entry : raw.entrySet()) {
            List<BlockData> resolved = new ArrayList<>();
            java.util.Set<String> ids = new java.util.LinkedHashSet<>();
            for (JsonElement idElement : entry.getValue().getAsJsonArray()) {
                String id = idElement.getAsString();
                ids.add(id);
                BlockData data = block(id, null);
                if (data != null) {
                    resolved.add(data);
                }
            }
            tags.put(entry.getKey(), List.copyOf(resolved));
            tagIds.put(entry.getKey(), java.util.Set.copyOf(ids));
        }
        // 层扫描（{@link Layers}）按身份判地形，注入同一份展开结果：CE 侧没有 wover 的 tag，
        // 直接问 WorldView.inTag 会拿到 null。与 ore 的 BlockIds 共用这一个集合，避免两套扫描。
        Layers.installTerrainIds(tagIds.getOrDefault("wover:surfaces/terrain", java.util.Set.of()));
    }

    /**
     * 装载期解析出的 tag → 方块列表（只读）。
     *
     * <p>给同包形状注册表用：它们需要「这个方块属不属于某个 tag」的判定，
     * 而 tag 内容来自构建期清单（官方基表 + BetterNether 增量 + 原版 tag 递归展开），
     * 不能各自重算。空列表表示「该 tag 未解析出任何 id」。
     */
    /**
     * 某个 tag 解析出的原始方块 id 集（构建期展开结果）。
     *
     * <p>给需要「按身份」判定 tag 但不该自建一套扫描的调用方用（现在只有
     * {@link Layers#installTerrainIds} 的 terrain tag）。**不**做材质兜底。
     */
    java.util.Set<String> tagIdSet(String tag) {
        java.util.Set<String> ids = tagIds.get(tag);
        return ids == null ? java.util.Set.of() : java.util.Set.copyOf(ids);
    }

    java.util.Map<String, List<BlockData>> resolvedTags() {
        return java.util.Map.copyOf(tags);
    }

    /**
     * 把「下界地面」的真 tag 注入复杂树注册表。
     *
     * <p>上游 {@code BlocksHelper.isNetherGround} = {@code NETHER_STONES} ∪ 灵魂沙/土 ∪
     * {@code NETHER_MYCELIUM} ∪ nylium。**注意不能用 {@code wover:surfaces/nether/terrain}**
     * 顶替：那个 tag 含砂砾/岩浆块/骨块等 41 个 id，用它会
     * 让幽暗木/赤菇木长在岩浆块上。
     *
     * <p>{@code SCULK_LIKE} 单独给幽光木用。
     */
    private void installGroundTags() {
        BlockPredicates.Predicate stones = tagPredicate("wover:surfaces/nether/stones");
        BlockPredicates.Predicate mycelium = tagPredicate("wover:surfaces/nether/mycelium");
        BlockPredicates.Predicate nylium = tagPredicate("minecraft:nylium");
        BlockPredicates.Predicate sculkLike = tagPredicate("wover:surfaces/sculk_like");
        List<BlockData> souls = new ArrayList<>();
        for (String id : new String[]{"minecraft:soul_sand", "minecraft:soul_soil"}) {
            BlockData data = block(id, null);
            if (data != null) {
                souls.add(data);
            }
        }
        BlockPredicates.Predicate soulGround = new BlockPredicates.Blocks("soul_ground", souls);
        UpstreamComplexTreeFeatures.installGroundTags(new UpstreamComplexTreeFeatures.GroundTags() {
            @Override
            public boolean netherGround(WorldView world, int x, int y, int z) {
                Point point = new Point(x, y, z);
                return stones.test(world, point) || mycelium.test(world, point)
                        || nylium.test(world, point) || soulGround.test(world, point);
            }

            @Override
            public boolean sculkLike(WorldView world, int x, int y, int z) {
                return sculkLike.test(world, new Point(x, y, z));
            }
        });
    }

    // ---------------------------------------------------------------- 谓词

    BlockPredicates.Predicate predicate(@Nullable JsonObject json) {
        if (json == null) {
            return new BlockPredicates.Constant(true);
        }
        String type = string(json, "type");
        BlockPredicates.Predicate inner = switch (type) {
            case "minecraft:matching_block_tag" -> tagPredicate(string(json, "tag"));
            case "minecraft:matching_blocks" -> blocksPredicate(json);
            case "minecraft:matching_fluids" -> fluidsPredicate(json);
            case "minecraft:solid" -> new BlockPredicates.Solid();
            case "minecraft:replaceable" -> new BlockPredicates.Replaceable();
            // 真实 canSurvive：owner 已在 BlockPredicates.WouldSurvive 里接到 WorldView#wouldSurvive，
            // 问不到时内部记账降级，不再恒真。
            case "minecraft:would_survive" ->
                    new BlockPredicates.WouldSurvive(state(json.get("state")));
            case "minecraft:true" -> new BlockPredicates.Constant(true);
            // wover:full_shape（WorldWeaver IsFullShape）：真实 isCollisionShapeFullBlock。
            // 它的可选 offset 由下面的通用 predicateOffset 处理（与上游「读 pos+offset 那一格」一致）。
            case "wover:full_shape" -> new BlockPredicates.FullShape();
            case "minecraft:all_of" -> new BlockPredicates.AllOf(predicates(json, "predicates"));
            case "minecraft:any_of" -> new BlockPredicates.AnyOf(predicates(json, "predicates"));
            case "minecraft:not" -> new BlockPredicates.Not(predicate(json.getAsJsonObject("predicate")));
            default -> {
                notes.add("未知方块谓词 " + type + "，按「恒真」处理（装载器本应拦下它）");
                yield new BlockPredicates.Constant(true);
            }
        };
        Point shift = predicateOffset(json);
        return shift == null ? inner : new BlockPredicates.Offset(shift, inner);
    }

    /**
     * 把一个 tag 编成谓词（**包级**：同包的形状注册表要用真实 tag 判定地面，例如
     * {@code UpstreamComplexTreeFeatures} 的 {@code ground()}）。
     *
     * <p>语义：manifest 里该 tag 解析出非空方块列表 → {@link BlockPredicates.Blocks}
     * （按材质比较，忽略属性）；否则退回 {@link BlockPredicates.Surface}
     * （实心非基岩，仅作为「tag 未解析出来」的兜底线）。
     */
    BlockPredicates.Predicate tagPredicate(String tag) {
        if ("minecraft:air".equals(tag)) {
            return new BlockPredicates.Air();
        }
        java.util.Set<String> ids = tagIds.get(tag);
        if (ids != null && !ids.isEmpty()) {
            // 按**身份**比较（CE 的 blockId 对自定义方块是精确 CE id，已由 predicate owner 用源码证实）。
            // resolved 只作为 blockId 问不出来时的材质兜底。
            return new BlockPredicates.BlockIds(tag, ids,
                    tags.get(tag) == null ? List.of() : tags.get(tag));
        }
        List<BlockData> accepted = tags.get(tag);
        if (accepted == null || accepted.isEmpty()) {
            // 解析不出任何 id 的 tag 一律拒绝，**不再**退回「实心非基岩」近似：
            // 近似会把「站在什么方块上」判断错（CE 下不同方块可能共用宿主 Material，
            // 按材质比较本身也需要 predicate owner 的 identity 修复）。宁可让这条特征
            // 在装载期被点名跳过，也不要静默按错误的地面放置。
            throw new IllegalStateException(
                    "tag " + tag + " 在构建期没有解析出任何方块 id（检查官方基表/增量/tag 展开）；"
                            + "拒绝用近似谓词顶替，见 notes 里的其余缺口");
        }
        return new BlockPredicates.Blocks(tag, accepted);
    }

    private BlockPredicates.Predicate blocksPredicate(JsonObject json) {
        List<BlockData> accepted = new ArrayList<>();
        for (String id : stringList(json.get("blocks"))) {
            BlockData data = block(id, null);
            if (data != null) {
                accepted.add(data);
            }
        }
        return new BlockPredicates.Blocks("blocks", List.copyOf(accepted));
    }

    private BlockPredicates.Predicate fluidsPredicate(JsonObject json) {
        List<Material> fluids = new ArrayList<>();
        for (String id : stringList(json.get("fluids"))) {
            Material material = Material.matchMaterial(id);
            if (material != null) {
                fluids.add(material);
            }
        }
        return new BlockPredicates.MatchingFluids(List.copyOf(fluids));
    }

    private List<BlockPredicates.Predicate> predicates(JsonObject json, String key) {
        List<BlockPredicates.Predicate> list = new ArrayList<>();
        JsonArray array = json.getAsJsonArray(key);
        if (array != null) {
            for (JsonElement element : array) {
                list.add(predicate(element.getAsJsonObject()));
            }
        }
        return List.copyOf(list);
    }

    // ---------------------------------------------------------------- 修饰符

    /**
     * 编译一条修饰符链。
     *
     * @return {@code null} 表示这条链里出现了**没有实现**的修饰符 —— 整条 placed feature 必须被
     *         跳过。这是刻意的：少跑一个修饰符不会报错，只会让位置整体偏移（例如少了
     *         {@code rarity_filter} 就把稀有植被变成满屏），比不放更难发现。
     */
    @Nullable List<PlacementModifier> placement(@Nullable JsonArray array) {
        if (array == null) {
            return List.of();
        }
        List<PlacementModifier> modifiers = new ArrayList<>();
        for (JsonElement element : array) {
            JsonObject json = element.getAsJsonObject();
            String type = string(json, "type");
            switch (type) {
                case "minecraft:height_range" -> modifiers.add(
                        new PlacementModifier.HeightRange(heightRange(json.getAsJsonObject("height"))));
                case "minecraft:count" -> modifiers.add(
                        new PlacementModifier.Count(intProvider(json.get("count"))));
                case "minecraft:in_square" -> modifiers.add(new PlacementModifier.InSquare());
                case "minecraft:heightmap" -> {
                    String heightmapType = string(json, "heightmap");
                    if (!"MOTION_BLOCKING".equals(heightmapType)) {
                        notes.add("heightmap 类型 " + heightmapType
                                + " 未实现（只支持 MOTION_BLOCKING），整条 placed feature 被跳过");
                        return null;
                    }
                    modifiers.add(new PlacementModifier.Heightmap());
                }
                case "minecraft:biome" -> modifiers.add(new PlacementModifier.Biome());
                case "minecraft:rarity_filter" -> modifiers.add(
                        new PlacementModifier.RarityFilter(json.get("chance").getAsInt()));
                case "minecraft:count_on_every_layer" -> modifiers.add(
                        new PlacementModifier.CountOnEveryLayer(intProvider(json.get("count"))));
                case "wover:every_layer" -> {
                    // 源码缺省：min=Integer.MIN_VALUE, max=Integer.MAX_VALUE, top=true（都会夹到建造高度）
                    int min = json.has("min") ? json.get("min").getAsInt() : Integer.MIN_VALUE;
                    int max = json.has("max") ? json.get("max").getAsInt() : Integer.MAX_VALUE;
                    boolean top = !json.has("top") || json.get("top").getAsBoolean();
                    modifiers.add(new PlacementModifier.EveryLayer(min, max, top));
                }
                case "minecraft:block_predicate_filter" -> modifiers.add(
                        new PlacementModifier.BlockPredicateFilter(
                                predicate(json.getAsJsonObject("predicate"))));
                case "wover:is" -> modifiers.add(new PlacementModifier.Is(
                        requireOffset(json), predicate(json.getAsJsonObject("predicate"))));
                case "wover:is_next_to" -> modifiers.add(new PlacementModifier.IsNextTo(
                        optionalOffset(json), predicate(json.getAsJsonObject("predicate"))));
                case "wover:solid_in_dir" -> modifiers.add(solidInDir(json));
                case "minecraft:offset" -> modifiers.add(new PlacementModifier.Offset(
                        intProvider(json.get("x")), intProvider(json.get("y")),
                        intProvider(json.get("z"))));
                case "wover:offset" -> modifiers.add(
                        new PlacementModifier.FixedOffset(requireBlocksVector(json)));
                case "wover:stencil" -> modifiers.add(stencil(json));
                case "wover:is_basin" -> modifiers.add(new PlacementModifier.IsBasin(
                        predicate(json.getAsJsonObject("predicate")),
                        json.has("top_predicate")
                                ? predicate(json.getAsJsonObject("top_predicate")) : null));
                case "wover:extend" -> modifiers.add(new PlacementModifier.Extend(
                        Point.Direction.byJsonName(
                                json.has("direction") ? string(json, "direction") : "down"),
                        json.has("length") ? intProvider(json.get("length"))
                                : new IntProvider.Uniform(0, 3)));
                case "wover:extend_xyz" -> modifiers.add(extendXyz(json));
                case "wover:noise_filter" -> modifiers.add(new PlacementModifier.NoiseFilter(
                        string(json, "noise"),
                        json.has("min_noise_level") ? json.get("min_noise_level").getAsDouble()
                                : -Double.MAX_VALUE,
                        json.has("max_noise_level") ? json.get("max_noise_level").getAsDouble()
                                : Double.MAX_VALUE,
                        json.has("scale_xz") ? json.get("scale_xz").getAsDouble() : 1.0,
                        json.has("scale_y") ? json.get("scale_y").getAsDouble() : 1.0));
                case "betternether:avoid_nether_city" ->
                        modifiers.add(new PlacementModifier.AvoidNetherCity(
                                avoidNetherCityReach(json),
                                json.has("include_cave") && json.get("include_cave").getAsBoolean()));
                case "wover:all" -> {
                    if (json.keySet().size() > 1) {
                        throw new IllegalStateException(
                                "wover:all 带了字段 " + json.keySet() + "：它的语义未知，拒绝猜测。");
                    }
                    modifiers.add(new PlacementModifier.EmitGrid());
                }
                default -> {
                    notes.add("未实现的放置修饰符 " + type + "，整条 placed feature 被跳过");
                    return null;
                }
            }
        }
        return List.copyOf(modifiers);
    }

    /** {@code wover:stencil}：16×16 布尔模板 + {@code one_in}。 */
    private PlacementModifier stencil(JsonObject json) {
        JsonArray array = json.getAsJsonArray("structures");
        boolean[] cells = new boolean[256];
        int size = array == null ? 0 : array.size();
        for (int i = 0; i < 256; i++) {
            cells[i] = i < size && array.get(i).getAsBoolean();
        }
        return new PlacementModifier.Stencil(cells,
                json.has("one_in") ? json.get("one_in").getAsInt() : 1);
    }

    /**
     * {@code betternether:avoid_nether_city} 的 {@code reach}（上游
     * {@code Codec.intRange(0, 64).fieldOf("reach")}：**必填**，范围 0..64）。
     *
     * <p>缺字段或越界直接抛：这两个值都会改变「够不够得着城市」的判定，猜一个默认值会让落叶
     * 静默落进建筑里。上游的 codec 对同样的数据也会拒绝加载。
     */
    private static int avoidNetherCityReach(JsonObject json) {
        JsonElement element = json.get("reach");
        if (element == null || element.isJsonNull()) {
            throw new IllegalStateException(
                    "修饰符 betternether:avoid_nether_city 缺少 reach（上游是必填的 intRange(0,64)）");
        }
        int reach = element.getAsInt();
        if (reach < 0 || reach > 64) {
            throw new IllegalStateException(
                    "修饰符 betternether:avoid_nether_city 的 reach=" + reach + " 越界（上游是 intRange(0,64)）");
        }
        return reach;
    }

    private PlacementModifier extendXyz(JsonObject json) {
        return new PlacementModifier.ExtendXYZ(
                intProvider(json.get("radius")),
                floats(json, "center_density", 1.0F),
                floats(json, "border_density", 0.05F),
                json.has("square") && json.get("square").getAsBoolean(),
                floats(json, "height", 1.0F),
                json.has("height_propagation") ? string(json, "height_propagation") : "none");
    }

    /** {@code {type: constant|uniform}} → 浮点提供器；uniform 区间是 {@code [min, max)}。 */
    private static PlacementModifier.Floats floats(JsonObject json, String key, float fallback) {
        JsonElement element = json.get(key);
        if (element == null || !element.isJsonObject()) {
            return new PlacementModifier.Floats(fallback, fallback, true);
        }
        JsonObject spec = element.getAsJsonObject();
        if ("minecraft:uniform".equals(string(spec, "type"))) {
            float min = spec.get("min_inclusive").getAsFloat();
            float max = spec.has("max_exclusive")
                    ? spec.get("max_exclusive").getAsFloat()
                    : spec.get("max_inclusive").getAsFloat();
            return new PlacementModifier.Floats(min, max, false);
        }
        float value = spec.has("value") ? spec.get("value").getAsFloat() : fallback;
        return new PlacementModifier.Floats(value, value, true);
    }

    /**
     * {@code wover:solid_in_dir} → {@code FindInDirection}。
     *
     * <p>缺省值全部照源码：{@code dir=[down]}、{@code dist=12}（range 1..32）、
     * {@code random_select=true}、{@code offset_in_dir=0}。
     * {@code surface_predicate} 源码缺省是 {@code BlockPredicates.ONLY_GROUND}，
     * 而其定义已核实：{@code BlockPredicate.matchesTag(CommonBlockTags.TERRAIN)}（**tag 谓词**）。
     * 因此这里映射为 {@code wover:surfaces/terrain} 的近似谓词，而不是「实心」。
     */
    private PlacementModifier solidInDir(JsonObject json) {
        List<Point.Direction> dirs = new ArrayList<>();
        JsonArray array = json.getAsJsonArray("dir");
        if (array != null) {
            for (JsonElement element : array) {
                dirs.add(Point.Direction.byJsonName(element.getAsString()));
            }
        }
        if (dirs.isEmpty()) {
            dirs.add(Point.Direction.DOWN);
        }
        int dist = json.has("dist") ? Math.max(1, Math.min(32, json.get("dist").getAsInt())) : 12;
        int offsetInDir = json.has("offset_in_dir") ? json.get("offset_in_dir").getAsInt() : 0;
        boolean randomSelect = !json.has("random_select") || json.get("random_select").getAsBoolean();
        // 源码缺省 surface_predicate = BlockPredicates.ONLY_GROUND = matchesTag(TERRAIN)，
        // 而 TERRAIN 就是 `wover:surfaces/terrain`。该 tag 现在由构建期按官方基表递归展开
        // （WorldWeaver release/26.3 的 src/main/generated/... + BN 增量 + 原版 tag），
        // 所以缺省走真实 tag 谓词，不再用「实心」近似。
        BlockPredicates.Predicate surface = json.has("surface_predicate")
                ? predicate(json.getAsJsonObject("surface_predicate"))
                : tagPredicate("wover:surfaces/terrain");
        return new PlacementModifier.FindInDirection(List.copyOf(dirs), dist, offsetInDir,
                randomSelect, surface);
    }

    /** {@code height_range} 的 {@code height}：带世界锚点的区间，锚点在放置时按世界高度换算。 */
    private PlacementModifier.HeightRangeSpec heightRange(@Nullable JsonObject json) {
        if (json == null) {
            return new PlacementModifier.HeightRangeSpec(
                    new PlacementModifier.Anchored("above_bottom", 0),
                    new PlacementModifier.Anchored("above_bottom", 0),
                    PlacementModifier.HeightKind.UNIFORM);
        }
        String type = string(json, "type");
        PlacementModifier.HeightKind kind = switch (type) {
            case "minecraft:trapezoid" -> {
                if (json.has("plateau") && json.get("plateau").getAsInt() != 0) {
                    throw new IllegalStateException(
                            "height_range trapezoid 的 plateau 非 0（= " + json.get("plateau")
                                    + "）：上游数据里从未出现，未实现其分布，拒绝猜测。");
                }
                yield PlacementModifier.HeightKind.TRAPEZOID;
            }
            case "minecraft:constant" -> PlacementModifier.HeightKind.CONSTANT;
            default -> PlacementModifier.HeightKind.UNIFORM;
        };
        PlacementModifier.Anchored min = anchor(json.get("min_inclusive"));
        PlacementModifier.Anchored max;
        if (json.has("max_inclusive")) {
            max = anchor(json.get("max_inclusive"));
        } else if (json.has("max_exclusive")) {
            max = new PlacementModifier.Anchored(anchor(json.get("max_exclusive")).anchor(),
                    anchor(json.get("max_exclusive")).offset() - 1);
        } else {
            max = min;
        }
        return new PlacementModifier.HeightRangeSpec(min, max, kind);
    }

    /** {@code {"above_bottom": 4}} / {@code {"below_top": 4}} / {@code 40}。 */
    private static PlacementModifier.Anchored anchor(@Nullable JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return new PlacementModifier.Anchored("above_bottom", 0);
        }
        if (!element.isJsonObject()) {
            return new PlacementModifier.Anchored("absolute", element.getAsInt());
        }
        JsonObject json = element.getAsJsonObject();
        for (String name : new String[]{"above_bottom", "below_top", "absolute"}) {
            if (json.has(name)) {
                return new PlacementModifier.Anchored(name, json.get(name).getAsInt());
            }
        }
        return new PlacementModifier.Anchored("above_bottom", 0);
    }

    /** 普通整数提供器（{@code count} / {@code max_height}）。这里的值是绝对数，不带锚点。 */
    private IntProvider intProvider(@Nullable JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return new IntProvider.Constant(1);
        }
        if (!element.isJsonObject()) {
            return new IntProvider.Constant(element.getAsInt());
        }
        JsonObject json = element.getAsJsonObject();
        String type = string(json, "type");
        int min = json.has("min_inclusive") ? json.get("min_inclusive").getAsInt() : 0;
        int max;
        if (json.has("max_inclusive")) {
            max = json.get("max_inclusive").getAsInt();
        } else if (json.has("max_exclusive")) {
            max = json.get("max_exclusive").getAsInt() - 1;
        } else {
            max = min;
        }
        if (max < min) {
            int swap = min;
            min = max;
            max = swap;
        }
        return switch (type) {
            case "minecraft:uniform" -> new IntProvider.Uniform(min, max);
            case "minecraft:constant" -> new IntProvider.Constant(min);
            case "minecraft:biased_to_bottom" -> new IntProvider.BiasedToBottom(min, max);
            case "minecraft:very_biased_to_bottom" -> new IntProvider.VeryBiasedToBottom(min, max);
            case "minecraft:clamped_normal" -> new IntProvider.ClampedNormal(
                    json.get("mean").getAsDouble(), json.get("deviation").getAsDouble(), min, max);
            case "minecraft:trapezoid" -> new IntProvider.Trapezoid(min, max,
                    json.has("plateau") ? json.get("plateau").getAsInt() : 0);
            default -> new IntProvider.Constant(min);
        };
    }

    // ---------------------------------------------------------------- 特征

    /** 上游 step 9（vegetal_decoration）的下标，用于过渡期的扁平清单。 */
    private static final int VEGETAL_STEP_INDEX = 9;

    private @Nullable CompiledFeature compilePlaced(String id, int step, int index) {
        JsonObject placed = readPlaced(id);
        if (placed == null) {
            notes.add("缺少 placed_feature " + id);
            return null;
        }
        Feature feature = configured(placed.get("feature"));
        if (feature == null) {
            notes.add("placed_feature " + id + " 的特征无法编译");
            return null;
        }
        List<PlacementModifier> placement = placement(placed.getAsJsonArray("placement"));
        if (placement == null) {
            return null;
        }
        return new CompiledFeature(id, step, index, placement, feature);
    }

    private @Nullable Feature configured(@Nullable JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonPrimitive()) {
            JsonObject json = readFeature(element.getAsString());
            return json == null ? null : configuredObject(json);
        }
        return configuredObject(element.getAsJsonObject());
    }

    private @Nullable Feature configuredObject(JsonObject json) {
        String type = string(json, "type");
        return switch (type) {
            case "minecraft:simple_block" -> new Feature.SimpleBlock(blockProvider(json.get("to_place")));
            case "wover:random_patch" -> randomPatch(json);
            case "minecraft:block_column" -> blockColumn(json);
            case "wover:place_block" -> placeBlock(json);
            case "minecraft:overlay" -> overlay(json);
            case "minecraft:random_selector" -> randomSelector(json);
            case "wover:template" -> template(json);
            case "betternether:twisting_vines" -> new Feature.TwistedVines(
                    block("minecraft:twisting_vines_plant", null),
                    block("minecraft:twisting_vines", null));
            case "minecraft:spring_feature" -> spring(json);
            default -> {
                // 地形特征（lakes / underground_decoration 等非 step 9）：形状与 transform 在
                // UpstreamTerrainFeatures 里逐行移植，需要访问本类的 block/predicate/placedOrInline。
                Feature custom = UpstreamTerrainFeatures.create(type, json,
                        new UpstreamTerrainFeatures.WorldgenAccess(
                                this::block, this::predicate, this::placedOrInline));
                if (custom == null) {
                    // 植被形状（wart_cap / bush / lucis / soul_lily / jellyfish_mushroom）
                    custom = UpstreamVegetationFeatures.create(type, json);
                }
                if (custom == null) {
                    // 简化树（mushroom_fir / wart / willow / old_willow / big_brown）
                    custom = UpstreamSimpleTreeFeatures.create(type, json);
                }
                if (custom == null) {
                    // 复杂树（gloomwood / rubeus / anchor_tree + branch/root / sakura）
                    custom = UpstreamComplexTreeFeatures.create(type, json);
                }
                if (custom == null) {
                    notes.add("未知特征类型 " + type + "（装载器与各注册表都不认它）");
                }
                yield custom;
            }
        };
    }

    private @Nullable Feature randomPatch(JsonObject json) {
        int tries = json.get("tries").getAsInt();
        int xz = json.get("xz_spread").getAsInt();
        int y = json.get("y_spread").getAsInt();
        JsonElement inner = json.get("feature");
        Feature feature;
        if (inner.isJsonPrimitive()) {
            // Holder<PlacedFeature>：字符串指 placed_feature。
            JsonObject placed = readPlaced(inner.getAsString());
            feature = placed == null ? null : compileInnerPlaced(placed);
        } else {
            feature = innerPlaced(inner.getAsJsonObject());
        }
        if (feature == null) {
            return null;
        }
        return new Feature.RandomPatch(tries, xz, y, feature);
    }

    private Feature pillar(JsonObject json) {
        // 26.3 的 pillar JSON：state 可能是 {id, properties}，也可能包一层
        // {"type":"minecraft:simple_state_provider","state":{...}}。
        JsonObject state = json.getAsJsonObject("state");
        if (state != null && state.has("state") && state.get("state").isJsonObject()) {
            state = state.getAsJsonObject("state");
        }
        String id = state != null && state.has("id") ? state.get("id").getAsString() : null;
        java.util.Map<String, String> props = state != null && state.has("properties")
                ? propertyMap(state.getAsJsonObject("properties")) : java.util.Map.of();
        return new Feature.Pillar(
                id,
                props,
                state(json.get("state")),
                Point.Direction.byJsonName(string(json, "direction")),
                json.has("min_height") ? intProvider(json.get("min_height"))
                        : new IntProvider.Constant(0),
                intProvider(json.get("max_height")),
                predicate(json.getAsJsonObject("allowed_placement")),
                json.has("transform") ? string(json, "transform") : "none");
    }

    private Feature blockColumn(JsonObject json) {
        Point.Direction direction = Point.Direction.byJsonName(string(json, "direction"));
        boolean prioritizeTip = json.has("prioritize_tip") && json.get("prioritize_tip").getAsBoolean();
        BlockPredicates.Predicate allowed = predicate(json.getAsJsonObject("allowed_placement"));
        List<Feature.BlockColumn.Layer> layers = new ArrayList<>();
        JsonArray array = json.getAsJsonArray("layers");
        if (array != null) {
            for (JsonElement element : array) {
                JsonObject layer = element.getAsJsonObject();
                layers.add(new Feature.BlockColumn.Layer(intProvider(layer.get("height")),
                        blockProvider(layer.get("provider"))));
            }
        }
        return new Feature.BlockColumn(direction, List.copyOf(layers), prioritizeTip, allowed);
    }

    private Feature placeBlock(JsonObject json) {
        List<Point.Direction> dirs = new ArrayList<>();
        JsonArray array = json.getAsJsonArray("dir");
        if (array != null) {
            for (JsonElement element : array) {
                dirs.add(Point.Direction.byJsonName(element.getAsString()));
            }
        }
        if (dirs.isEmpty()) {
            dirs.addAll(List.of(Point.Direction.NORTH, Point.Direction.EAST,
                    Point.Direction.SOUTH, Point.Direction.WEST));
        }
        return new Feature.PlaceBlock(List.copyOf(dirs), blockProvider(json.get("entries")));
    }

    private Feature overlay(JsonObject json) {
        JsonElement subs = json.get("features");
        List<Feature> features = new ArrayList<>();
        if (subs != null) {
            if (subs.isJsonArray()) {
                for (JsonElement element : subs.getAsJsonArray()) {
                    addPlaced(element, features);
                }
            } else {
                addPlaced(subs, features);
            }
        }
        return new Feature.Overlay(List.copyOf(features));
    }

    /** overlay / random_selector 的子项是 <b>placed feature</b>（内联对象或字符串引用）。 */
    private void addPlaced(JsonElement element, List<Feature> into) {
        if (element.isJsonPrimitive()) {
            JsonObject placed = readPlaced(element.getAsString());
            Feature feature = placed == null ? null : compileInnerPlaced(placed);
            if (feature != null) {
                into.add(feature);
            }
            return;
        }
        JsonObject inline = element.getAsJsonObject();
        if (inline.has("type")) {
            // 直接写成了配置特征（上游部分 overlay 就是这样）：没有内层修饰符。
            Feature feature = configuredObject(inline);
            if (feature != null) {
                into.add(feature);
            }
            return;
        }
        Feature feature = innerPlaced(inline);
        if (feature != null) {
            into.add(feature);
        }
    }

    private @Nullable Feature compileInnerPlaced(JsonObject placed) {
        Feature feature = configured(placed.get("feature"));
        if (feature == null) {
            return null;
        }
        List<PlacementModifier> placement = placement(placed.getAsJsonArray("placement"));
        return placement == null ? null : new Feature.Wrapped(placement, feature);
    }

    private @Nullable Feature innerPlaced(JsonObject inline) {
        Feature feature = configured(inline.get("feature"));
        if (feature == null) {
            return null;
        }
        List<PlacementModifier> placement = placement(inline.getAsJsonArray("placement"));
        return placement == null ? null : new Feature.Wrapped(placement, feature);
    }

    private Feature randomSelector(JsonObject json) {
        List<Feature.RandomSelector.Selection> selections = new ArrayList<>();
        JsonArray array = json.getAsJsonArray("features");
        if (array != null) {
            for (JsonElement element : array) {
                JsonObject entry = element.getAsJsonObject();
                float chance = entry.get("chance").getAsFloat();
                Feature feature = entry.get("feature") == null
                        ? null : placedOrInline(entry.get("feature"));
                if (feature != null) {
                    selections.add(new Feature.RandomSelector.Selection(chance, feature));
                }
            }
        }
        Feature fallback = json.get("default") == null ? null : placedOrInline(json.get("default"));
        return new Feature.RandomSelector(List.copyOf(selections), fallback);
    }

    @Nullable Feature placedOrInline(JsonElement element) {
        if (element.isJsonPrimitive()) {
            JsonObject placed = readPlaced(element.getAsString());
            return placed == null ? null : compileInnerPlaced(placed);
        }
        JsonObject inline = element.getAsJsonObject();
        if (inline.has("type")) {
            return configuredObject(inline);
        }
        return innerPlaced(inline);
    }

    /**
     * {@code minecraft:spring_feature}：{@code state} + {@code valid_blocks} + {@code requires_block_below}。
     *
     * <p>26.3 的字段名照原版 JSON 读，不按旧版猜：{@code spring_lava_overworld.json} 里是
     * {@code "state": {"id":"minecraft:lava","properties":{"falling":"true"}}} 与
     * {@code "valid_blocks": [...] }；{@code requires_block_below} 缺省为 true。
     */
    /**
     * {@code minecraft:spring_feature}：{@code state} + {@code valid_blocks} +
     * {@code requires_block_below}（缺省 true）+ {@code rock_count}（缺省 4）+
     * {@code hole_count}（缺省 1）。
     *
     * <p>字段名照 26.3 的原版 JSON（`spring_lava_overworld.json`）读，不按旧版猜。
     */
    private Feature spring(JsonObject json) {
        List<BlockData> valid = new ArrayList<>();
        for (String id : stringList(json.get("valid_blocks"))) {
            BlockData data = block(id, null);
            if (data != null) {
                valid.add(data);
            }
        }
        return new Feature.Spring(
                state(json.get("state")),
                !json.has("requires_block_below") || json.get("requires_block_below").getAsBoolean(),
                json.has("rock_count") ? json.get("rock_count").getAsInt() : 4,
                json.has("hole_count") ? json.get("hole_count").getAsInt() : 1,
                List.copyOf(valid));
    }

    private Feature template(JsonObject json) {
        List<Feature.Template.TemplateChoice> choices = new ArrayList<>();
        float total = 0.0F;
        JsonObject structures = json.getAsJsonObject("structures");
        JsonArray items = structures == null ? null : structures.getAsJsonArray("items");
        if (items != null) {
            for (JsonElement element : items) {
                JsonObject item = element.getAsJsonObject();
                JsonObject value = item.getAsJsonObject("value");
                String location = string(value, "location");
                int offsetY = value.has("offset_y") ? value.get("offset_y").getAsInt() : 0;
                float weight = item.has("weight") ? item.get("weight").getAsFloat() : 1.0F;
                TemplateData data = template(location);
                if (data == null || weight <= 0.0F) {
                    continue;
                }
                choices.add(new Feature.Template.TemplateChoice(data, offsetY, weight));
                total += weight;
            }
        }
        return new Feature.Template(List.copyOf(choices), total);
    }

    // ---------------------------------------------------------------- 方块状态提供器

    private Feature.BlockProvider blockProvider(@Nullable JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return new Feature.BlockProvider.Single(null);
        }
        if (!element.isJsonObject()) {
            return new Feature.BlockProvider.Single(block(element.getAsString(), null));
        }
        JsonObject json = element.getAsJsonObject();
        if ("minecraft:weighted".equals(string(json, "type"))) {
            List<BlockData> data = new ArrayList<>();
            List<Integer> weights = new ArrayList<>();
            JsonArray entries = json.getAsJsonArray("entries");
            if (entries != null) {
                for (JsonElement entryElement : entries) {
                    JsonObject entry = entryElement.getAsJsonObject();
                    BlockData state = state(entry.get("data"));
                    int weight = entry.get("weight").getAsInt();
                    if (state == null || weight <= 0) {
                        continue;
                    }
                    data.add(state);
                    weights.add(weight);
                }
            }
            int[] array = new int[weights.size()];
            int total = 0;
            for (int i = 0; i < array.length; i++) {
                array[i] = weights.get(i);
                total += array[i];
            }
            return new Feature.BlockProvider.Weighted(List.copyOf(data), array, total);
        }
        return new Feature.BlockProvider.Single(state(element));
    }

    /** {@code {"id": "...", "properties": {...}}} 或纯字符串。 */
    private @Nullable BlockData state(@Nullable JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (!element.isJsonObject()) {
            return block(element.getAsString(), null);
        }
        JsonObject json = element.getAsJsonObject();
        if (!json.has("id")) {
            if (json.has("state")) {
                return state(json.get("state"));
            }
            return null;
        }
        return block(json.get("id").getAsString(),
                json.has("properties") ? json.getAsJsonObject("properties") : null);
    }

    // ---------------------------------------------------------------- 模板

    private @Nullable TemplateData template(String location) {
        TemplateData cached = templateCache.get(location);
        if (cached != null) {
            return cached;
        }
        String name = location.split(":", 2)[1];
        JsonObject json = read(RESOURCE_ROOT + "/templates/" + name + ".json");
        if (json == null) {
            missingTemplateIds.add(location);
            return null;
        }
        JsonArray palette = json.getAsJsonArray("palette");
        List<BlockData> resolved = new ArrayList<>();
        if (palette != null) {
            for (JsonElement element : palette) {
                JsonObject entry = element.getAsJsonObject();
                resolved.add(block(entry.get("id").getAsString(),
                        entry.has("properties") ? entry.getAsJsonObject("properties") : null));
            }
        }
        JsonArray blocks = json.getAsJsonArray("blocks");
        int size = blocks == null ? 0 : blocks.size();
        int[] xs = new int[size];
        int[] ys = new int[size];
        int[] zs = new int[size];
        BlockData[] states = new BlockData[size];
        int kept = 0;
        if (blocks != null) {
            for (JsonElement element : blocks) {
                JsonArray block = element.getAsJsonArray();
                int stateIndex = block.get(3).getAsInt();
                BlockData data = stateIndex >= 0 && stateIndex < resolved.size()
                        ? resolved.get(stateIndex) : null;
                if (data == null) {
                    continue;
                }
                xs[kept] = block.get(0).getAsInt();
                ys[kept] = block.get(1).getAsInt();
                zs[kept] = block.get(2).getAsInt();
                states[kept] = data;
                kept++;
            }
        }
        JsonArray sizeArray = json.getAsJsonArray("size");
        int sizeX = sizeArray != null && sizeArray.size() == 3 ? sizeArray.get(0).getAsInt() : 0;
        int sizeY = sizeArray != null && sizeArray.size() == 3 ? sizeArray.get(1).getAsInt() : 0;
        int sizeZ = sizeArray != null && sizeArray.size() == 3 ? sizeArray.get(2).getAsInt() : 0;
        TemplateData data = new TemplateData(sizeX, sizeY, sizeZ,
                Arrays.copyOf(xs, kept), Arrays.copyOf(ys, kept),
                Arrays.copyOf(zs, kept), Arrays.copyOf(states, kept));
        templateCache.put(location, data);
        return data;
    }

    // ---------------------------------------------------------------- JSON 小工具

    private @Nullable JsonObject readPlaced(String id) {
        return placedCache.computeIfAbsent(id, key -> read(RESOURCE_ROOT + "/placed_feature/"
                + key.split(":", 2)[1] + ".json"));
    }

    private @Nullable JsonObject readFeature(String id) {
        return featureCache.computeIfAbsent(id, key -> read(RESOURCE_ROOT + "/feature/"
                + key.split(":", 2)[1] + ".json"));
    }

    private @Nullable JsonObject read(String resource) {
        try (InputStream stream = WorldgenLoader.class.getResourceAsStream(resource)) {
            if (stream == null) {
                return null;
            }
            try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader).getAsJsonObject();
            }
        } catch (IOException | IllegalStateException error) {
            notes.add("读取 " + resource + " 失败：" + error.getMessage());
            return null;
        }
    }

    private static String string(JsonObject json, String key) {
        JsonElement element = json.get(key);
        return element == null || element.isJsonNull() ? "" : element.getAsString();
    }

    /** 字符串或字符串数组。 */
    private static List<String> stringList(@Nullable JsonElement element) {
        List<String> list = new ArrayList<>();
        if (element == null || element.isJsonNull()) {
            return list;
        }
        if (element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) {
                list.add(item.getAsString());
            }
        } else {
            list.add(element.getAsString());
        }
        return list;
    }

    /**
     * 谓词自带的 {@code offset}（Vec3i，三个整数）。
     *
     * <p>**只读 {@code offset}，绝不回退到 {@code blocks}**：两者同名不同义。
     * {@code minecraft:matching_blocks} 的 {@code blocks} 是**方块 id**（字符串或字符串数组，
     * 真实数据里就有 3 个 id 的数组，见 {@code patch_nether_cactus.json}），把它当坐标读会直接抛
     * （{@code getAsInt} 打在字符串上）；即便正好是三个整数，语义也完全不同。
     * wover 修饰符的坐标字段 {@code blocks} 只由 {@link #requireBlocksVector} 读。
     *
     * <p>缺省（无字段 / JSON null）返回 {@code null}；**声明了但形态不对就抛**，不静默忽略。
     */
    private static @Nullable Point predicateOffset(JsonObject json) {
        JsonElement element = json.get("offset");
        if (element == null || element.isJsonNull()) {
            return null;
        }
        return vector(element, "谓词 " + string(json, "type") + " 的 offset");
    }

    /** {@code wover:is} 的坐标字段 {@code offset}（必填）。 */
    private static Point requireOffset(JsonObject json) {
        JsonElement element = json.get("offset");
        if (element == null || element.isJsonNull()) {
            throw new IllegalStateException("修饰符 " + string(json, "type") + " 缺少 offset");
        }
        return vector(element, "修饰符 " + string(json, "type") + " 的 offset");
    }

    /**
     * {@code wover:is_next_to} 的坐标字段；WoVer 缺省为当前位置，因此缺字段按零向量处理。
     * 声明了字段但形态错误仍然显式失败，避免把损坏数据静默当成缺省值。
     */
    private static Point optionalOffset(JsonObject json) {
        JsonElement element = json.get("offset");
        if (element == null || element.isJsonNull()) {
            return new Point(0, 0, 0);
        }
        return vector(element, "修饰符 " + string(json, "type") + " 的 offset");
    }

    /**
     * {@code wover:offset} 的坐标字段 {@code blocks}（Vec3i，必填）。
     *
     * <p>wover 把这个字段也叫 {@code blocks}，但它与 {@code minecraft:matching_blocks.blocks}
     * 完全无关；两处各用各的解析入口，避免任何一方被另一方的数据形态误导。
     */
    private static Point requireBlocksVector(JsonObject json) {
        JsonElement element = json.get("blocks");
        if (element == null || element.isJsonNull()) {
            throw new IllegalStateException("修饰符 " + string(json, "type") + " 缺少 blocks 向量");
        }
        return vector(element, "修饰符 " + string(json, "type") + " 的 blocks");
    }

    /** 严格解「三个整数」的向量：形态不对就抛，绝不按缺省值顶替。 */
    private static Point vector(JsonElement element, String what) {
        if (!element.isJsonArray() || element.getAsJsonArray().size() != 3) {
            throw new IllegalStateException(what + " 必须是三个整数的数组，实际是 " + element);
        }
        JsonArray array = element.getAsJsonArray();
        int[] value = new int[3];
        for (int i = 0; i < 3; i++) {
            JsonElement item = array.get(i);
            if (item == null || !item.isJsonPrimitive() || !item.getAsJsonPrimitive().isNumber()) {
                throw new IllegalStateException(what + " 的第 " + i + " 项不是整数，实际是 " + item);
            }
            value[i] = item.getAsInt();
        }
        return new Point(value[0], value[1], value[2]);
    }
}
