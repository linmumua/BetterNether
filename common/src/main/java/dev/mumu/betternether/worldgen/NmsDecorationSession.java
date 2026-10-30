package dev.mumu.betternether.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.placement.FeaturePlacer;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import org.bukkit.craftbukkit.generator.CraftLimitedRegion;
import org.bukkit.generator.LimitedRegion;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link NativeDecorationSession} 的唯一实现：把「服务端注册表里的原版 placed feature /
 * structure start」**真实执行**一次。
 *
 * <h2>它为什么存在</h2>
 * 本插件把 {@code shouldGenerateDecorations()} 关成 {@code false}（见
 * {@code NetherTerrainGenerator}），因为原版那一整道装饰 pass（
 * {@code ChunkGenerator#addVanillaDecorations}）会把**结构与特征一起**跑掉，而我们需要的是
 * 「按 step 升序、每个 step 先结构后特征、并且把 BN 特征插在同一个 step 里合适的相位」。
 * 关掉之后，原版的两件事就都没人做了：
 * <ol>
 *   <li>已生成结构的**方块放置**（{@code StructureStart#placeInChunk}）。只保留 start 不放置，
 *       世界里就是一座座空气结构；</li>
 *   <li>上游 {@code features[step]} 里那 414 条 {@code minecraft:} 原版 placed feature。</li>
 * </ol>
 * 本类把这两件事按 {@code addVanillaDecorations} 的字节码逐段复刻回来（不是近似、不猜种子）：
 * <pre>
 * ChunkGenerator#addVanillaDecorations 实录（mucore-26.3.jar，javap -c -p）：
 *   ChunkPos pos = chunk.getPos();
 *   if (SharedConstants.debugVoidTerrain(pos)) return;
 *   SectionPos sectionPos = SectionPos.of(pos, level.getMinSectionY());
 *   BlockPos origin = sectionPos.origin();
 *   Registry&lt;Structure&gt; structures = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
 *   Map&lt;Integer, List&lt;Structure&gt;&gt; byStep = structures.stream()
 *         .collect(groupingBy(s -&gt; s.step().ordinal()));          // 314: 原序，分组保持原序
 *   Supplier&lt;List&lt;StepFeatureData&gt;&gt; featuresPerStep = this.featuresPerStep;
 *   WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(generateUniqueSeed()));
 *   long decorationSeed = random.setDecorationSeed(level.getSeed(), origin.getX(), origin.getZ());
 *   for (int step = 0; step &lt; max(Decoration.values().length, featuresPerStep.get().size()); step++) {
 *       int j = 0;                                              // 结构下标，每个 step 归零
 *       if (structureManager.shouldGenerateStructures())
 *           for (Structure s : byStep.getOrDefault(step, List.of())) {
 *               random.setFeatureSeed(decorationSeed, j, step);  // 结构也用 j/step 播种
 *               level.setCurrentlyGenerating(() -&gt; registry.getKey(s).toString());
 *               structureManager.startsForStructure(sectionPos.x(), sectionPos.z(), s)
 *                   .forEach(start -&gt; start.placeInChunk(level, manager, this, random,
 *                              getWritableArea(chunk), pos));
 *               j++;
 *           }
 *       // 该 step 的 placed feature 相位在 413 之后（本类不重造 index 收集，见下）
 *   }
 *   level.setCurrentlyGenerating(null);
 *
 *   private static BoundingBox getWritableArea(ChunkAccess chunk) {     // 1127
 *       ChunkPos p = chunk.getPos();
 *       LevelHeightAccessor h = chunk.getHeightAccessorForGeneration();
 *       return new BoundingBox(p.getMinBlockX(), h.getMinY() + 1, p.getMinBlockZ(),
 *                              p.getMinBlockX() + 15, h.getMaxY(), p.getMinBlockZ() + 15);
 *   }
 *
 * 每个 placed feature 的种子（624..724）：
 *   long deco = decorationSeed;
 *   long override = level.getMinecraftWorld().paperConfig().featureSeeds.features
 *                        .getLong(placedFeature.feature());
 *   if (override != -1L) deco = random.setDecorationSeed(override, origin.getX(), origin.getZ());
 *   random.setFeatureSeed(deco, index, step);
 *   level.setCurrentlyGenerating(() -&gt; id);
 *   placer.placeWithBiomeCheck(placedFeature, random, origin);
 * </pre>
 *
 * <h2>为什么用 NMS 的 {@code WorldgenRandom} 而不是本项目的 {@link DecorationRandom}</h2>
 * 两条流在 {@code DecorationRandomTest} 里已验证逐位等价，但这里调的是**原版自己的类**，
 * 少一层「我们抄得对不对」的疑问：{@code new WorldgenRandom(new XoroshiroRandomSource(0))}
 * 之后立刻 {@code setDecorationSeed}，初始种子根本不影响结果（{@code setDecorationSeed}
 * 内部第一句就是 {@code setSeed(worldSeed)}）。
 *
 * <h2>为什么拿得到 {@code WorldGenLevel}</h2>
 * Bukkit populator 收到的是 {@link LimitedRegion}，运行期实现是
 * {@code org.bukkit.craftbukkit.generator.CraftLimitedRegion}，它有 public
 * {@code WorldGenLevel getHandle()}（javap 实测）。而 {@code CraftLimitedRegion} 就是在
 * {@code ChunkGenerator#applyBiomeDecoration} 里用**原版装饰用的那个 {@code WorldGenLevel}**
 * 构造出来的（字节码 38..47：{@code new CraftLimitedRegion(level, chunk.getPos())}），
 * 所以它的写入范围与边界行为跟原版装饰完全一致。
 *
 * <p>拿到它之后：{@code WorldGenRegion#structureManager()} 是**原版装饰时用的那个**
 * {@code StructureManager}（{@code WorldGenRegion} 自己的字段，javap 实测），不是
 * {@code ServerLevel#structureManager()} 再包一层 —— 前者才是原版传给
 * {@code StructureStart#placeInChunk} 的实例。
 *
 * <p>生成器同理取 {@code level.getLevel().getChunkSource().getGenerator()}：这就是
 * {@code addVanillaDecorations} 里的 {@code this}（原版把生成器交给
 * {@code FeaturePlacer}，而 {@code BiomeFilter} 会用它取群系的
 * {@code BiomeGenerationSettings}）。取自定义生成器外面那层
 * {@code CustomChunkGenerator} 是对的 —— 原版装饰时 {@code this} 正是它。
 *
 * <h2>用 {@code instanceof} 把门（不是按类型名猜）</h2>
 * 入口对象是 {@code Object}（{@link WorldView#nativeWorldGenLevel()} 只能交出它），
 * 判定只有一个依据：真实的 {@code instanceof WorldGenLevel} / {@code instanceof CraftLimitedRegion}。
 * 这两条指令在**首次执行**时才解析相应的类型 —— 所以在没有服务端的类路径上它们抛
 * {@link LinkageError}，由 {@link NativeDecorationBridge} 接住并记成 missing-nms。
 * 本类的所有方法、字段都是 NMS 类型，因此它一旦被加载就要求服务端在类路径上；
 * 入口与记账放在不含 NMS 符号的门面里，正是为了让「缺类」这条路径可测。
 *
 * <h2>本类不做的事</h2>
 * <ul>
 *   <li><b>不重造 index 收集</b>：原版从 3×3 区块的群系调色板算出该 step 要跑哪些下标。
 *       那张表（{@code FeatureSorter.StepFeatureData}）是服务端按**它自己**的
 *       placed feature 注册表算的，而本插件的计划来自上游 JSON（含 BN 条目、且 step 归属由
 *       上游数据决定）。两者不是同一张表，混用只会让 index 对不上。所以 index 由
 *       {@link DecorationScheduler} / {@link DecorationScheduler.Plan} 提供，本类只负责
 *       「按给定的 (step, index, id) 真实跑一次」。群系调色板仍然要用 —— 用来决定
 *       **哪些群系算在内**（{@link #biomeKeys()}），口径与原版一致。</li>
 *   <li><b>不延迟构造 ChunkAccess</b>：调色板与可写区都要它，它就在 {@code WorldGenLevel} 里。</li>
 * </ul>
 */
final class NmsDecorationSession implements NativeDecorationSession {

    /** 采样邻域半径（区块数）：原版 {@code ChunkPos.rangeClosed(pos, 1)}。 */
    private static final int NEIGHBOUR_CHUNKS = 1;

    private final WorldGenLevel level;
    private final ChunkPos chunkPos;
    private final SectionPos sectionPos;
    private final BlockPos origin;
    private final ChunkGenerator generator;
    /** 原版装饰用的那个 manager；{@code level} 不是 {@code WorldGenRegion} 时为 {@code null}。 */
    private final StructureManager structureManager;
    private final Registry<PlacedFeature> placedFeatures;
    private final Registry<Structure> structures;
    private final Map<Integer, List<Structure>> structuresByStep;
    private final WorldgenRandom random;
    private final FeaturePlacer featurePlacer;
    private final BoundingBox writableArea;
    private final long decorationSeed;

    private NmsDecorationSession(WorldGenLevel level, ChunkAccess chunk, ChunkPos chunkPos) {
        this.level = level;
        this.chunkPos = chunkPos;
        this.sectionPos = SectionPos.of(chunkPos, level.getMinSectionY());
        this.origin = sectionPos.origin();
        this.generator = level.getLevel().getChunkSource().getGenerator();
        this.structureManager = level instanceof WorldGenRegion region ? region.structureManager() : null;
        this.placedFeatures = level.registryAccess().lookupOrThrow(Registries.PLACED_FEATURE);
        this.structures = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        this.structuresByStep = groupStructuresByStep(this.structures);
        this.random = new WorldgenRandom(new XoroshiroRandomSource(0L));
        this.decorationSeed = random.setDecorationSeed(level.getSeed(), origin.getX(), origin.getZ());
        this.featurePlacer = new FeaturePlacer(level, generator);
        this.writableArea = writableArea(chunk);
    }

    /** 从 Bukkit 区域建会话：取真实 {@code WorldGenLevel} 后交给 {@link #openTyped}。 */
    static NativeDecorationSession openRegionTyped(LimitedRegion region, int chunkX, int chunkZ) {
        if (!(region instanceof CraftLimitedRegion craft)) {
            WorldgenWarnings.degrade("native-bridge:not-craft:" + region.getClass().getName(),
                    "装饰区域不是 CraftLimitedRegion（" + region.getClass().getName()
                            + "）：原版结构与原版 placed feature 都无法真实执行。");
            return null;
        }
        return openTyped(craft.getHandle(), chunkX, chunkZ);
    }

    /**
     * 从 {@code Object} 建会话：真正的 {@code instanceof} + 构造。
     *
     * @throws LinkageError 类路径上没有服务端（{@link NativeDecorationBridge#open(Object, int, int)}
     *                      会接住并记成 missing-nms）
     */
    static NativeDecorationSession openTyped(Object nativeLevel, int chunkX, int chunkZ) {
        if (!(nativeLevel instanceof WorldGenLevel level)) {
            WorldgenWarnings.degrade("native-bridge:invalidtype",
                    NativeDecorationBridge.reasonInvalidType(nativeLevel));
            return null;
        }
        try {
            ChunkAccess chunk = level.getChunk(chunkX, chunkZ);
            return new NmsDecorationSession(level, chunk, new ChunkPos(chunkX, chunkZ));
        } catch (RuntimeException | LinkageError error) {
            // 只记一次：这个失败通常是「整类环境不支持」，按区块刷屏没有诊断价值。
            WorldgenWarnings.degrade("native-bridge:open",
                    "无法为区块开原版装饰桥（结构与原版 placed feature 不会执行）：" + error);
            return null;
        }
    }

    /**
     * 该结构注册表按 {@code step.ordinal()} 分组（原序）。
     *
     * <p>等价于原版 {@code structures.stream().collect(Collectors.groupingBy(s -> s.step().ordinal()))}：
     * {@code groupingBy} 的 value 列表保持 stream 的遭遇顺序，也就是注册表顺序
     * （{@code Registry.stream()} 由 {@code IdMap} 的迭代器构造，按注册 id 升序）。
     * 顺序就是种子：{@code j} 这个 per-step 计数器直接进 {@code setFeatureSeed}。
     */
    private static Map<Integer, List<Structure>> groupStructuresByStep(Registry<Structure> structures) {
        Map<Integer, List<Structure>> byStep = new LinkedHashMap<>();
        for (Structure structure : structures.stream().toList()) {
            byStep.computeIfAbsent(structure.step().ordinal(), key -> new ArrayList<>()).add(structure);
        }
        return byStep;
    }

    /** {@code ChunkGenerator#getWritableArea}（private，逐行重建，不用反射）。 */
    private static BoundingBox writableArea(ChunkAccess chunk) {
        ChunkPos pos = chunk.getPos();
        LevelHeightAccessor height = chunk.getHeightAccessorForGeneration();
        return new BoundingBox(pos.getMinBlockX(), height.getMinY() + 1, pos.getMinBlockZ(),
                pos.getMinBlockX() + 15, height.getMaxY(), pos.getMinBlockZ() + 15);
    }

    @Override
    public long decorationSeed() {
        return decorationSeed;
    }

    @Override
    public int nextStructureIndex(int step) {
        return countInStep(structuresByStep, step);
    }

    /**
     * 免会话版本：只数「该 step 的结构注册表条目数」，不建会话、不取 {@code ChunkAccess}、
     * 不建 {@code FeaturePlacer}/{@code WorldgenRandom}。
     *
     * <p>给「不在注册表里、却要按结构相位播种」的调用方（本移植的巨型熔岩湖）省掉一次完整会话构造：
     * 它每区块只需要这一个整数，没必要为此把调色板/可写区/特征放置器都建一遍。
     *
     * @return {@code -1} = 拿不到真实区域（不是 CraftLimitedRegion）；**不编造**条目数
     */
    static int countStructuresInStep(LimitedRegion region, int step) {
        if (!(region instanceof CraftLimitedRegion craft)) {
            return -1;
        }
        return countInStep(craft.getHandle(), step);
    }

    /** 从 {@code WorldGenLevel} 直接数（与实例路径共用同一个分组函数，不会两处漂移）。 */
    private static int countInStep(WorldGenLevel level, int step) {
        Registry<Structure> structures = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        return countInStep(groupStructuresByStep(structures), step);
    }

    /**
     * 该 step 的注册表条目数 = 「排在它们之后的结构会拿到的 {@code j}」。
     *
     * <p>语义见 {@link NativeDecorationSession#nextStructureIndex(int)}：原版 {@code j} 计的是
     * **该 step 的注册表条目**（{@code j} 从 0 起、每条 +1），所以条目占 {@code 0..size-1}，
     * 追加在它们之后的结构拿到的正是 {@code size}。既不是 {@code size-1}，也不含调用方自身。
     */
    private static int countInStep(Map<Integer, List<Structure>> byStep, int step) {
        List<Structure> forStep = byStep.get(step);
        return forStep == null ? 0 : forStep.size();
    }

    @Override
    public int placeStructures(int step) {
        List<Structure> forStep = structuresByStep.get(step);
        return placeStructures(step, 0, forStep == null ? 0 : forStep.size(), -1);
    }

    /**
     * 跑 step 内注册表顺序的一段，并把「插入的虚拟条目」算进 {@code j}。
     *
     * <p>{@code j} 的映射：注册表下标 {@code i < virtualSlot} 时 {@code j = i}（不受影响），
     * {@code i >= virtualSlot} 时 {@code j = i + 1}（虚拟条目占了那个槽位）。
     */
    @Override
    public int placeStructures(int step, int fromIndex, int toIndexExclusive, int virtualSlot) {
        if (structureManager == null) {
            WorldgenWarnings.degrade("native-structures:no-manager",
                    "WorldGenLevel 不是 WorldGenRegion，拿不到原版装饰用的 StructureManager："
                            + "已生成的结构不会被放进区块（世界里会出现空气结构）。");
            return 0;
        }
        if (!structureManager.shouldGenerateStructures()) {
            return 0;
        }
        List<Structure> forStep = structuresByStep.get(step);
        if (forStep == null || forStep.isEmpty()) {
            return 0;
        }
        int from = Math.max(0, fromIndex);
        int to = Math.min(forStep.size(), toIndexExclusive);
        int placed = 0;
        for (int i = from; i < to; i++) {
            Structure structure = forStep.get(i);
            // 虚拟条目（上游是数据包里的 mega_lava_lake）占住 virtualSlot，所以它之后的条目整体 +1。
            // 换算放在 NativeDecorationSlots 里：那段是纯算术，能在没有服务端的单测里断言具体数字。
            int slot = NativeDecorationSlots.slot(i, virtualSlot);
            random.setFeatureSeed(decorationSeed, slot, step);
            level.setCurrentlyGenerating(() -> structures.getResourceKey(structure)
                    .map(key -> key.identifier().toString())
                    .orElseGet(structure::toString));
            List<StructureStart> starts = structureManager.startsForStructure(
                    sectionPos.x(), sectionPos.z(), structure);
            for (StructureStart start : starts) {
                // 原版 lambda$addVanillaDecorations$3：box 是 getWritableArea(chunk)，
                // 六个参数顺序即 StructureStart#placeInChunk 的签名。
                start.placeInChunk(level, structureManager, generator, random, writableArea, chunkPos);
                placed++;
            }
        }
        return placed;
    }

    @Override
    public int structureSlot(int step, String structureId) {
        return slotIn(structuresByStep.get(step), structures, structureId);
    }

    /**
     * 结构在该 step 的真实槽位：在注册表里 → 它的下标；不在 → 按排序它会插入的位置。
     *
     * <p>上游 {@code mega_lava_lake} 是数据包结构（bootstrap 在后、且对已存在的 key 只
     * {@code return holder}），所以它的槽位就是**排序位置**，不是追加位置。
     */
    private static int slotIn(List<Structure> forStep, Registry<Structure> registry, String structureId) {
        if (forStep == null) {
            return -1;
        }
        Identifier wanted;
        try {
            wanted = Identifier.parse(structureId);
        } catch (RuntimeException error) {
            WorldgenWarnings.degrade("native-slot:bad-id:" + structureId,
                    "结构 id 不合法，无法计算槽位：" + structureId + "（" + error + "）");
            return -1;
        }
        int before = 0;
        for (Structure structure : forStep) {
            Identifier id = registry.getResourceKey(structure)
                    .map(key -> key.identifier()).orElse(null);
            if (id == null) {
                continue;
            }
            if (id.equals(wanted)) {
                return before;
            }
            if (id.compareTo(wanted) < 0) {
                before++;
            } else {
                break;   // 注册表按 Identifier 有序，后面都更大
            }
        }
        return before;
    }

    /** 免会话：从 Bukkit 区域取该 step 的槽位；{@code -1} = 拿不到。 */
    static int structureSlot(LimitedRegion region, int step, String structureId) {
        if (!(region instanceof CraftLimitedRegion craft)) {
            return -1;
        }
        return structureSlot(craft.getHandle(), step, structureId);
    }

    /** 免会话：从 Bukkit 世界取该 step 的槽位（世界创建期算一次即可）。 */
    static int structureSlot(org.bukkit.World world, int step, String structureId) {
        if (!(world instanceof org.bukkit.craftbukkit.CraftWorld craft)) {
            return -1;
        }
        return structureSlot(craft.getHandle(), step, structureId);
    }

    private static int structureSlot(WorldGenLevel level, int step, String structureId) {
        Registry<Structure> structures = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        return slotIn(groupStructuresByStep(structures).get(step), structures, structureId);
    }

    /**
     * 跑某一条原版 placed feature：{@code (step, index)} 决定随机流，群系过滤由原版
     * {@code BiomeFilter} 自己判（{@code placeWithBiomeCheck}），本类不插手。
     *
     * <p><b>不吞异常</b>：特征抛错照原版一样冒出去，那是崩溃而不是「这条跳过」。
     */
    @Override
    public boolean placeFeature(int step, int index, String placedFeatureId) {
        PlacedFeature feature = placedFeatures.getValue(Identifier.parse(placedFeatureId));
        if (feature == null) {
            WorldgenWarnings.degrade("native-missing:" + placedFeatureId,
                    "服务端注册表里没有 placed feature " + placedFeatureId
                            + "（step " + step + ", index " + index + "）：这条上游装饰无法放置。"
                            + "通常是服务端版本与上游数据版本不一致。");
            return false;
        }
        long featureSeed = decorationSeed;
        // Paper 的 feature seed override（字节码 624..692）：命中就换成该特征专属的装饰种子，
        // 后面仍然走 setFeatureSeed(deco, index, step)。默认表是空的（getLong 返回 -1）。
        ServerLevel serverLevel = level.getLevel();
        long override = serverLevel.paperConfig().featureSeeds.features.getLong(feature.feature());
        if (override != -1L) {
            featureSeed = random.setDecorationSeed(override, origin.getX(), origin.getZ());
        }
        random.setFeatureSeed(featureSeed, index, step);
        level.setCurrentlyGenerating(() -> placedFeatureId);
        featurePlacer.placeWithBiomeCheck(feature, random, origin);
        return true;
    }

    @Override
    public int placeNativeFeatures(int step, DecorationScheduler.Plan plan) {
        List<CompiledFeature> order = plan.step(step).order();
        int placed = 0;
        for (int index = 0; index < order.size(); index++) {
            CompiledFeature feature = order.get(index);
            if (feature.isNativePlaced() && placeFeature(step, index, feature.id())) {
                placed++;
            }
        }
        return placed;
    }

    /**
     * 本区块邻域（3×3 区块）里出现过的群系 key，取自**真实区块的群系调色板**：
     * {@code LevelChunkSection#getBiomes()} 的 {@code PalettedContainerRO}。
     *
     * <p>口径逐行对齐原版 {@code lambda$addVanillaDecorations$1}：遍历
     * {@code ChunkPos.rangeClosed(pos, 1)}，对每个 section 的群系容器枚举调色板条目。
     * 原版在 {@code GlobalPalette} 分支用 {@code getAll}、否则用 {@code forEachInPalette}
     * （前者数实际使用、后者枚举调色板）；两者给出的集合语义相同，这里统一用
     * {@code getAll} —— 它不需要 {@code PalettedContainer.data} 这个 private 字段，
     * 也就避免了对 {@code instanceof GlobalPalette} 的复制。
     *
     * <p>{@code retainAll(biomeSource.possibleBiomes())} 也照做：它只用来剔掉「注册表里有、
     * 但本世界生成不出来」的群系，避免极端数据包把整张注册表的群系都算进来。
     */
    @Override
    public Set<String> biomeKeys() {
        Set<Holder<Biome>> holders = new LinkedHashSet<>();
        for (ChunkPos pos : ChunkPos.rangeClosed(chunkPos, NEIGHBOUR_CHUNKS).toList()) {
            ChunkAccess neighbour = level.getChunk(pos.x(), pos.z());
            for (LevelChunkSection section : neighbour.getSections()) {
                PalettedContainerRO<Holder<Biome>> biomes = section.getBiomes();
                biomes.getAll(holders::add);
            }
        }
        holders.retainAll(generator.getBiomeSource().possibleBiomes());
        Set<String> keys = new LinkedHashSet<>();
        for (Holder<Biome> holder : holders) {
            String key = holder.unwrapKey().map(key0 -> key0.identifier().toString()).orElse(null);
            if (key != null) {
                keys.add(key);
            }
        }
        return keys;
    }

    /** 收尾：原版在整道装饰末尾把 {@code currentlyGenerating} 清空（字节码 789..791）。 */
    @Override
    public void close() {
        level.setCurrentlyGenerating(null);
    }
}
