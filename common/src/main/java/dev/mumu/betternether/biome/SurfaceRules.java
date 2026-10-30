package dev.mumu.betternether.biome;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.mumu.betternether.terrain.OpenSimplexNoise;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Interpreter for the upstream surface-rule JSON that ships in this jar under
 * {@code worldgen-upstream/surface_rules/}.
 *
 * <h2>Why this exists at all</h2>
 * Upstream does not express a biome's surface as a table of blocks; it builds a rule tree
 * ({@code world/biomes/*.java}), which its own data generation serialises to
 * {@code data/betternether/wover/worldgen/surface_rules/&lt;biome&gt;.json}. The previous port
 * transcribed those trees into a flat table ({@code BiomeSurface}) and lost three things it said it
 * could not express: patchy noise, exact shares, and sub-layers at a fixed stone depth. Reading the
 * generated JSON removes the transcription entirely -- the numbers in it <em>are</em> upstream's.
 *
 * <p>The tree is compiled once (see {@link Book}) into arrays of small immutable nodes, so the
 * per-block path is a tree walk with no allocation and no map lookups: a {@code block} node holds an
 * {@code int} slot, and the caller turns slots into {@code BlockData} through its own palette array.
 * {@link Palette} assigns those slots.
 *
 * <h2>Every semantic here is transcribed from source, not inferred</h2>
 * The versions this class implements are
 * <a href="https://github.com/quiqueck/WorldWeaver/tree/release/26.3">WorldWeaver {@code release/26.3}</a>
 * (the library BetterNether 26.301.2 builds against) and Minecraft 26.3's material-rule system, which
 * is not the {@code SurfaceRules}/`SurfaceSystem` of earlier versions: 26.3 replaced it with
 * {@code MaterialRules} / {@code MaterialRuleContext} / {@code MaterialSystem}, and wover was ported to
 * it. The vanilla half was read out of the server jar this module compiles against
 * ({@code net.minecraft.world.level.levelgen.material.*}, disassembled with {@code javap -c}), and the
 * wover half out of the tagged sources. Each node names its source in its own javadoc, because the
 * first version of this file guessed at several of them and the guesses were wrong:
 *
 * <table border="1">
 *   <caption>What the earlier port approximated, and what the source actually says</caption>
 *   <tr><th>Node</th><th>Earlier port</th><th>Source</th></tr>
 *   <tr><td>{@code minecraft:hole}</td>
 *       <td>a 50/50 coin flip per block</td>
 *       <td>{@code surfaceDepth() <= 0} -- a property of the column, not a draw</td></tr>
 *   <tr><td>{@code minecraft:stone_depth}</td>
 *       <td>{@code depth <= offset + surface + secondary}, surface depth pinned to 0</td>
 *       <td>{@code depth <= 1 + offset + surface + secondary}, with the column's real
 *           {@code surfaceDepth} and real {@code surfaceSecondary} mapped through {@code Mth.map}</td></tr>
 *   <tr><td>wover {@code threshold_condition}</td>
 *       <td>OpenSimplex sampled at {@code x / blocksPerPatch}, scale re-derived as "blocks per patch"</td>
 *       <td>{@code noise.eval(x * scale_x, z * scale_z)}: scale is a frequency multiplier, and the
 *           noise is wover's own {@link OpenSimplexNoise} seeded by the JSON {@code seed}</td></tr>
 *   <tr><td>wover {@code rough_noise_condition}</td>
 *       <td>OpenSimplex in place of the vanilla field</td>
 *       <td>vanilla {@code NormalNoise} from the world's {@code RandomState}, plus the roughness draw</td></tr>
 *   <tr><td>{@code wover:nether_noise} selector</td>
 *       <td>its own noise field per JSON id, mapped through a hand-tuned {@code unit()}</td>
 *       <td>one fixed provider -- the JSON's byte is ignored by its codec -- reading
 *           {@code Conditions.NETHER_VOLUME_NOISE} and returning {@code 0..5}</td></tr>
 *   <tr><td>random draws</td>
 *       <td>SplitMix64 standing in for the vanilla source</td>
 *       <td>{@code RandomSource.create(...)} = {@code LegacyRandomSource}, i.e. the 48-bit LCG
 *           {@link LegacyRandom} reproduces bit for bit</td></tr>
 * </table>
 *
 * <h2>What is deliberately dropped</h2>
 * Every one of the 23 files starts with two rules that emit {@code minecraft:bedrock} from vanilla's
 * own {@code vertical_gradient} pair. Bedrock is {@code generateBedrock}'s work in this port (it has
 * to be: {@code shouldGenerateSurface} is false, so nothing else places the plates), so the pair is
 * recognised structurally and dropped at compile time. {@link #isBedrockPlate} is that recognition, and
 * a test pins that no compiled rule can ever emit bedrock.
 *
 * <h2>The one order-dependent rule: {@code rough_noise_condition}'s roughness draw</h2>
 * {@code RoughNoiseConditionImpl.compile} builds <em>one</em> roughness {@code RandomSource} per chunk
 * and per condition, and draws one {@code nextFloat()} from it for every block it evaluates. That makes
 * the value a function of the <em>order</em> in which blocks reach the condition, not of the position --
 * so reproducing it is a property of the <em>pass</em>, not of this interpreter. This class asks
 * {@link Vanilla#roughnessCursor()} for the same stream (the same call, the same seed) and leaves the
 * advancing to the caller; {@code NetherTerrainGenerator.planColumn} then walks the blocks in vanilla's
 * own domain and direction (see that method), which is what makes the assignment upstream's. A change to
 * that walk changes these draws, which is why a fingerprint test pins them.
 *
 * <p>Every other condition is a pure function of {@code (position, column, world)}.
 */
public final class SurfaceRules {
    /** Classpath directory holding the copied rules; written by {@code scripts/bnlib/surface_rules.py}. */
    public static final String RESOURCE_DIR = "worldgen-upstream/surface_rules";

    /** Classpath file listing the biomes to load, one per line, so no 23-name list is hardcoded here. */
    public static final String INDEX_FILE = "index.txt";

    /** The Nether's height, for {@code vertical_gradient}'s {@code above_bottom} / {@code below_top}. */
    static final int NETHER_BOTTOM = 0;
    static final int NETHER_TOP = 127;

    public static final String BEDROCK_ID = "minecraft:bedrock";

    private SurfaceRules() {
    }

    // ---------------------------------------------------------------- the vanilla side

    /**
     * Everything a compiled rule needs from the running server that is not a pure function of the JSON.
     *
     * <p>Injected rather than reached for, for two reasons. The rule semantics are the part that has to
     * be assertable, and a unit test cannot start a server: every implementation of this interface in
     * the tests is a plain lambda. And the noise fields are <em>world</em> state in vanilla -- they come
     * from {@code RandomState}, which is built from the world's seed and its datapack -- so a compiled
     * tree must not own them; one tree is shared by every world of one generator, and Folia generates
     * them on many threads.
     *
     * <p>The production implementation is {@code NetherTerrainGenerator.RandomStateSurface}, which is
     * four calls into {@code RandomState} plus the two {@code Noise#get} reads. Nothing here is
     * approximated: where a value is unavailable the implementation throws rather than substituting a
     * field of its own, because a substituted field is how the earlier port's shares came out wrong.
     */
    public interface Vanilla {
        /** The value no server provides: every call fails loudly, naming the rule that asked. */
        Vanilla ABSENT = new Vanilla() {
            @Override
            public double noise2d(String noiseId, int x, int z) {
                throw Vanilla.unavailable("minecraft:noise_threshold / wover:rough_noise_condition", noiseId);
            }

            @Override
            public double noise3d(String noiseId, int x, int y, int z) {
                throw Vanilla.unavailable("wover:rough_noise_condition", noiseId);
            }

            @Override
            public int surfaceDepth(int x, int z) {
                throw Vanilla.unavailable("minecraft:stone_depth(add_surface_depth)", "surface");
            }

            @Override
            public double surfaceSecondary(int x, int z) {
                throw Vanilla.unavailable("minecraft:stone_depth(secondary_depth_range)",
                        "surface_secondary");
            }

            @Override
            public float gradientNextFloat(String randomName, int x, int y, int z) {
                throw Vanilla.unavailable("minecraft:vertical_gradient", randomName);
            }

            @Override
            public Cursor roughnessCursor() {
                throw Vanilla.unavailable("wover:rough_noise_condition", "wover:roughness_noise");
            }
        };

        /** Vanilla's 2D sampler: {@code NormalNoise.get(x, 0.0, z)}. */
        double noise2d(String noiseId, int x, int z);

        /** Vanilla's 3D sampler: {@code NormalNoise.get(x, y, z)}. */
        double noise3d(String noiseId, int x, int y, int z);

        /** Vanilla's {@code MaterialSystem.getSurfaceDepth(x, z)}; see that method's formula below. */
        int surfaceDepth(int x, int z);

        /** Vanilla's {@code MaterialSystem.getSurfaceSecondary(x, z)}. */
        double surfaceSecondary(int x, int z);

        /** {@code randomFactory(randomName).at(x, y, z).nextFloat()} for {@code vertical_gradient}. */
        float gradientNextFloat(String randomName, int x, int y, int z);

        /** A fresh {@code RandomSource} for one chunk, seeded exactly as wover's roughness source. */
        Cursor roughnessCursor();

        private static RuntimeException unavailable(String rule, String what) {
            return new IllegalStateException(rule + " 需要服务端的真实噪声/随机源（" + what
                    + "），但当前没有任何 RandomState 绑定：不能用地形噪声顶替");
        }
    }

    /** One {@code RandomSource}, advanced by the caller. Only {@code nextFloat} is ever used. */
    public interface Cursor {
        float nextFloat();
    }

    // ---------------------------------------------------------------- rule nodes

    /**
     * A compiled rule: the block slot it decides, or {@link #UNDECIDED}.
     *
     * <p>Returning "undecided" rather than netherrack is what makes {@code sequence} work: vanilla's
     * sequences are a chain of tests where only the last element always decides, and the surface pass
     * must be able to tell "no rule matched" from "the rule says netherrack".
     */
    public interface Rule {
        int UNDECIDED = -1;

        int apply(Context ctx);
    }

    /** A compiled condition, i.e. a bare test with no block attached. */
    public interface Condition {
        boolean test(Context ctx);
    }

    /** A noise field wover owns: its own {@link OpenSimplexNoise}, sampled in world coordinates. */
    public interface Noise {
        double at(double x, double y, double z);
    }

    // ---------------------------------------------------------------- evaluation context

    /**
     * Everything a node may read about one position.
     *
     * <p>Mutable and reused: the surface pass sets it once per block and every node reads it, so a
     * chunk's 32k blocks allocate nothing but the per-node roughness cursors. One instance per thread
     * (the generator makes one per {@code generateSurface} call), which is also what keeps it safe on
     * Folia's worldgen workers.
     *
     * <h2>The two depths, in vanilla's own numbers</h2>
     * Vanilla's {@code MaterialRuleContext.stoneDepthAbove()} is a <em>1-based</em> count: it is 1 for
     * the block with air above it, 2 for the one below that, and so on down a run. Vanilla's
     * {@code StoneDepthCondition} then tests {@code depth <= 1 + offset}, which is exactly "the top
     * {@code 1 + offset} blocks of the run" -- that whole {@code + 1} is why a port that counts from 0
     * and tests {@code depth <= offset} gets the same blocks, and why the earlier port's offset rules
     * looked right while its {@code add_surface_depth} and {@code secondary_depth_range} rules did not.
     * This port keeps vanilla's numbering so the numbers in this file can be read against the source.
     *
     * <p>{@code stoneDepthAbove} counts <em>non-air</em> blocks, fluids included, because that is what
     * {@code MaterialSystem}'s column walk increments on; {@code stoneDepthBelow} stops at the first
     * block that is not stone ({@code !isAir && fluidState.isEmpty()}), again as vanilla does.
     */
    public static final class Context {
        private Vanilla vanilla = Vanilla.ABSENT;
        private Cursor[] cursors = new Cursor[0];
        private int x;
        private int y;
        private int z;
        private int stoneDepthAbove;
        private int stoneDepthBelow;
        private int surfaceDepth;
        private double surfaceSecondary;
        private String biome = "";

        /**
         * Binds this context to one chunk: the vanilla side, and a fresh roughness stream per node.
         *
         * <p>Per chunk because wover builds its roughness source once per chunk and per condition
         * ({@code RoughNoiseConditionImpl.compile}); keeping the cursors here rather than in the compiled
         * node is what lets one {@link Book} serve every thread and every world.
         *
         * @param vanilla     the world's noise and positional randoms
         * @param cursorSlots how many {@code rough_noise_condition} nodes the book holds
         */
        public void beginChunk(Vanilla vanilla, int cursorSlots) {
            this.vanilla = vanilla;
            if (cursors.length < cursorSlots) {
                cursors = new Cursor[cursorSlots];
            } else {
                java.util.Arrays.fill(cursors, 0, cursors.length, null);
            }
        }

        /**
         * Sets the position being decided.
         *
         * @param above           vanilla's {@code stoneDepthAbove}: non-air blocks above, this one
         *                        included, so 1 on an exposed top face
         * @param below           vanilla's {@code stoneDepthBelow}: blocks down to the first non-stone
         *                        one, this one included
         * @param surfaceDepth    vanilla's {@code MaterialSystem.getSurfaceDepth(x, z)}
         * @param surfaceSecondary vanilla's {@code MaterialSystem.getSurfaceSecondary(x, z)}
         */
        public void set(int x, int y, int z, int above, int below, int surfaceDepth,
                        double surfaceSecondary) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.stoneDepthAbove = above;
            this.stoneDepthBelow = below;
            this.surfaceDepth = surfaceDepth;
            this.surfaceSecondary = surfaceSecondary;
        }

        public int x() {
            return x;
        }

        public int y() {
            return y;
        }

        public int z() {
            return z;
        }

        /** Vanilla's {@code stoneDepthAbove}: 1 on an exposed top face, larger downwards. */
        public int stoneDepthAbove() {
            return stoneDepthAbove;
        }

        /** Vanilla's {@code stoneDepthBelow}: 1 on an exposed underside, larger upwards. */
        public int stoneDepthBelow() {
            return stoneDepthBelow;
        }

        /** Vanilla's {@code surfaceDepth} for this column, in blocks. */
        public int surfaceDepth() {
            return surfaceDepth;
        }

        /** Vanilla's {@code surfaceSecondary} for this column, in {@code [-1, 1]}. */
        public double surfaceSecondary() {
            return surfaceSecondary;
        }

        /** The vanilla side, for nodes that still need noise or a positional random. */
        public Vanilla vanilla() {
            return vanilla;
        }

        /**
         * The biome key whose rules are being evaluated at this position.
         *
         * <p>Set by the painter per block, because the key is per height: {@code minecraft:nether_wastes}
         * below the band and another biome above it are two different rule sets for the same column. The
         * vanilla nether tree needs it because it is <em>one</em> tree with {@code isBiome} branches,
         * exactly as vanilla builds it; this port keeps that shape and answers the branch from the key.
         */
        public String biome() {
            return biome;
        }

        /** Sets the biome key for the next {@link Rule} evaluation. */
        public void setBiome(String biome) {
            this.biome = biome == null ? "" : biome;
        }

        Cursor cursor(int node) {
            if (node < 0 || node >= cursors.length) {
                throw new IllegalStateException("wover:rough_noise_condition 需要每 chunk 的随机流，但 Context 没有"
                        + " beginChunk 到该节点（node=" + node + "，槽位=" + cursors.length + "）");
            }
            Cursor cursor = cursors[node];
            if (cursor == null) {
                cursor = vanilla.roughnessCursor();
                cursors[node] = cursor;
            }
            return cursor;
        }
    }

    // ---------------------------------------------------------------- palette

    /**
     * Interns block state strings into slots, so a rule node can hold an {@code int}.
     *
     * <p>One palette is shared by every biome in a {@link Book}, which is what lets the generator build
     * a single {@code BlockData[]} and index it directly on the hot path.
     *
     * <p>The key is a full state string ({@code minecraft:x[k=v]}); only vanilla ids can carry
     * properties here, because {@code CraftEngineLookup} resolves a CustomEngine block by id alone.
     */
    public static final class Palette {
        private final Map<String, Integer> slotOf = new LinkedHashMap<>();
        private final List<String> ids = new ArrayList<>();

        int slot(String id) {
            Integer existing = slotOf.get(id);
            if (existing != null) {
                return existing;
            }
            int slot = ids.size();
            ids.add(id);
            slotOf.put(id, slot);
            return slot;
        }

        /** The state string for a slot, or {@code null} when the slot is {@link Rule#UNDECIDED}. */
        public @Nullable String id(int slot) {
            return slot < 0 || slot >= ids.size() ? null : ids.get(slot);
        }

        /** Every state string, in slot order. The generator resolves this into a {@code BlockData[]}. */
        public List<String> ids() {
            return Collections.unmodifiableList(ids);
        }

        public int size() {
            return ids.size();
        }
    }
    // ---------------------------------------------------------------- one biome

    /** One biome's compiled rule tree. Immutable, so one instance serves every worldgen thread. */
    public static final class Set {
        private final String biome;
        private final Rule root;
        private final Palette palette;
        private final int maxStoneDepth;
        private final boolean usesSurfaceDepth;
        private final List<String> materials;

        Set(String biome, Rule root, Palette palette, int maxStoneDepth, boolean usesSurfaceDepth,
            List<String> materials) {
            this.biome = biome;
            this.root = root;
            this.palette = palette;
            this.maxStoneDepth = maxStoneDepth;
            this.usesSurfaceDepth = usesSurfaceDepth;
            this.materials = materials;
        }

        public String biome() {
            return biome;
        }

        /** The slot this position's block takes, or {@link Rule#UNDECIDED}. */
        public int decide(Context ctx) {
            return root.apply(ctx);
        }

        public @Nullable String id(int slot) {
            return palette.id(slot);
        }

        /**
         * Every block state this biome's rules can emit, in first-seen order.
         *
         * <p>For audits that must stay honest as the files change: the resource-location check and the
         * "does the pack actually ship this block" check both walk this, so a block added upstream is
         * covered without anyone remembering to add it to a list.
         */
        public List<String> materials() {
            return Collections.unmodifiableList(materials);
        }

        /**
         * The deepest {@code stone_depth} reach this biome's rules test, before the column's
         * {@code surfaceDepth} is added.
         *
         * <p>Vanilla's own bound: a condition with {@code offset} and {@code secondary_depth_range}
         * matches up to depth {@code 1 + offset + secondary}. A rule with {@code add_surface_depth} goes
         * further by the column's {@code surfaceDepth}, which is why {@link #usesSurfaceDepth()} exists
         * and why the generator adds it per column rather than assuming a constant.
         */
        public int maxStoneDepth() {
            return maxStoneDepth;
        }

        /** Whether any condition here adds the column's {@code surfaceDepth} to its reach. */
        public boolean usesSurfaceDepth() {
            return usesSurfaceDepth;
        }
    }

    // ---------------------------------------------------------------- the whole book

    /**
     * Every biome's rules plus the palette shared between them.
     *
     * <p>Loading happens once per generator, not per chunk: compiling 23 trees costs a few hundred JSON
     * nodes, which is not work for a worldgen thread.
     *
     * <p>Note what is <em>not</em> a parameter here: the world seed. Upstream's trees carry no world
     * seed at all -- wover's {@code ThresholdConditionImpl} builds {@code new OpenSimplexNoise(seed)}
     * from the JSON's own {@code seed} field, and the vanilla fields it also reads come from the world's
     * {@code RandomState} at evaluation time. So the same compiled trees, and the same patch pattern, are
     * used by every world; only the vanilla noise and the positional randoms differ between worlds.
     */
    public static final class Book {
        private final Map<String, Set> byBiome;
        private final Set vanilla;
        private final Palette palette;
        private final int maxStoneDepth;
        private final boolean usesSurfaceDepth;
        private final int cursorSlots;
        private final List<String> noiseIds;
        private final List<String> gradientNames;
        private final List<String> notes;

        Book(Map<String, Set> byBiome, Set vanilla, Palette palette, int maxStoneDepth,
             boolean usesSurfaceDepth, int cursorSlots, List<String> noiseIds,
             List<String> gradientNames, List<String> notes) {
            this.byBiome = byBiome;
            this.vanilla = vanilla;
            this.palette = palette;
            this.maxStoneDepth = maxStoneDepth;
            this.usesSurfaceDepth = usesSurfaceDepth;
            this.cursorSlots = cursorSlots;
            this.noiseIds = noiseIds;
            this.gradientNames = gradientNames;
            this.notes = notes;
        }

        /** Every biome key that has rules, in load order. */
        public java.util.Set<String> biomes() {
            return Collections.unmodifiableSet(byBiome.keySet());
        }

        /** The rules for a biome key (no namespace), or {@code null} when this biome has none. */
        public @Nullable Set of(String biomeKey) {
            return byBiome.get(biomeKey);
        }

        /**
         * Vanilla's own nether rule, for every biome the 23 upstream files do not cover.
         *
         * <p>Defaulting to it rather than to "paint nothing" is the whole point: {@code soul_sand_valley},
         * {@code crimson_forest} and {@code basalt_deltas} are reachable in this port's biome source, and
         * a column that matched none of the 23 used to keep the base netherrack with no soul sand, no
         * nylium and no blackstone. See {@link VanillaNether} for the source this is transcribed from.
         */
        public Set vanilla() {
            return vanilla;
        }

        /**
         * Every vanilla noise id the book names, so the world binding can resolve them before the first
         * chunk rather than from a worldgen worker.
         *
         * <p>{@code RandomState.getOrCreateNoise} caches through
         * {@code ConcurrentHashMap.computeIfAbsent} -- verified in the 26.3 jar -- so a lazy fill would
         * be safe; pre-warming only removes the first-chunk cost from the worker.
         */
        public List<String> noiseIds() {
            return Collections.unmodifiableList(noiseIds);
        }

        /** Every {@code vertical_gradient}'s {@code random_name}, for the same reason as {@link #noiseIds}. */
        public List<String> gradientNames() {
            return Collections.unmodifiableList(gradientNames);
        }

        public Palette palette() {
            return palette;
        }

        /** The deepest {@code stone_depth} reach across every biome, before {@code surfaceDepth}. */
        public int maxStoneDepth() {
            return maxStoneDepth;
        }

        /** Whether any rule in the book adds the column's {@code surfaceDepth} to its reach. */
        public boolean usesSurfaceDepth() {
            return usesSurfaceDepth;
        }

        /** How many {@code rough_noise_condition} nodes the book holds, i.e. how many streams a chunk needs. */
        public int cursorSlots() {
            return cursorSlots;
        }

        /** Non-fatal compilation notes, for the startup log (e.g. which rules were dropped). */
        public List<String> notes() {
            return Collections.unmodifiableList(notes);
        }

        public int size() {
            return byBiome.size();
        }

        /**
         * Loads the rules that were copied into this jar's resources.
         *
         * <p>The biome list comes from {@link #INDEX_FILE} rather than a hardcoded array, so adding a
         * biome upstream needs no Java change -- the copy script and this loader agree through the
         * file.
         *
         * @throws IllegalStateException when the index is missing/empty or any listed file is absent
         */
        public static Book loadBundled(ClassLoader loader) {
            String indexPath = RESOURCE_DIR + "/" + INDEX_FILE;
            List<String> keys = new ArrayList<>();
            try (Reader reader = open(loader, indexPath)) {
                for (String line : readAll(reader).split("\\R")) {
                    String key = line.trim();
                    if (!key.isEmpty()) {
                        keys.add(key);
                    }
                }
            } catch (IOException error) {
                throw new IllegalStateException("读取地表规则索引失败：" + indexPath
                        + "（scripts/extract_bn.py 未运行？）", error);
            }
            if (keys.isEmpty()) {
                throw new IllegalStateException("地表规则索引为空：" + indexPath);
            }
            return load(keys, key -> open(loader, RESOURCE_DIR + "/" + key + ".json"));
        }

        /**
         * Loads every {@code *.json} in a directory. Used by tests against the upstream folder itself,
         * which is the strongest form of "these 23 real files compile".
         */
        public static Book loadDirectory(Path directory) {
            List<String> keys = new ArrayList<>();
            try (var stream = Files.list(directory)) {
                stream.filter(path -> path.getFileName().toString().endsWith(".json"))
                        .forEach(path -> {
                            String name = path.getFileName().toString();
                            keys.add(name.substring(0, name.length() - ".json".length()));
                        });
            } catch (IOException error) {
                throw new IllegalStateException("列出地表规则目录失败：" + directory, error);
            }
            Collections.sort(keys);
            return load(keys, key -> Files.newBufferedReader(
                    directory.resolve(key + ".json"), StandardCharsets.UTF_8));
        }

        private static Book load(List<String> keys, ReaderSource source) {
            Palette palette = new Palette();
            Map<String, Set> byBiome = new LinkedHashMap<>();
            List<String> notes = new ArrayList<>();
            Compiler compiler = new Compiler(palette, notes);
            for (String key : keys) {
                JsonObject json;
                try (Reader reader = source.open(key)) {
                    JsonElement parsed = JsonParser.parseReader(reader);
                    if (!parsed.isJsonObject()) {
                        throw new IllegalStateException("地表规则不是 JSON 对象：" + key);
                    }
                    json = parsed.getAsJsonObject();
                } catch (IOException error) {
                    throw new IllegalStateException("读取地表规则失败：" + key, error);
                }
                JsonElement ruleSource = json.get("ruleSource");
                if (ruleSource == null || !ruleSource.isJsonObject()) {
                    throw new IllegalStateException("地表规则缺少 ruleSource：" + key);
                }
                compiler.begin(key);
                Rule root = compiler.rule(ruleSource.getAsJsonObject());
                byBiome.put(key, new Set(key, root, palette, compiler.maxStoneDepth(key),
                        compiler.usesSurfaceDepth(key), compiler.materials()));
            }
            if (compiler.bedrockDropped > 0) {
                notes.add("丢弃 " + compiler.bedrockDropped
                        + " 条基岩规则（上下基岩板由 generateBedrock 负责）");
            }
            // The vanilla half. Its materials share the palette and its noise ids share the pre-warm
            // list, because one pass evaluates both rule sets.
            Set vanilla = VanillaNether.build(palette, compiler.noiseIds);
            return new Book(byBiome, vanilla, palette, compiler.deepest(), compiler.anySurfaceDepth(),
                    compiler.cursorSlots, compiler.noiseIds(), compiler.gradientNames(), notes);
        }

        private static Reader open(ClassLoader loader, String path) throws IOException {
            var stream = loader.getResourceAsStream(path);
            if (stream == null) {
                throw new IOException("classpath 中不存在资源：" + path);
            }
            return new java.io.InputStreamReader(stream, StandardCharsets.UTF_8);
        }

        private static String readAll(Reader reader) throws IOException {
            StringBuilder text = new StringBuilder();
            char[] buffer = new char[2048];
            int read;
            while ((read = reader.read(buffer)) > 0) {
                text.append(buffer, 0, read);
            }
            return text.toString();
        }
    }

    /**
     * Compiles one {@code ruleSource} on its own, with its own palette.
     *
     * <p>For tests: every semantic in this class is easier to pin on a three-node tree than on one of
     * the 23 real files, where four independent things could explain a change. Not used by the
     * generator, which always loads a {@link Book}.
     */
    static Set compile(JsonObject ruleSource, String biome) {
        Palette palette = new Palette();
        List<String> notes = new ArrayList<>();
        Compiler compiler = new Compiler(palette, notes);
        compiler.begin(biome);
        Rule root = compiler.rule(ruleSource);
        return new Set(biome, root, palette, compiler.maxStoneDepth(biome),
                compiler.usesSurfaceDepth(biome), compiler.materials());
    }

    @FunctionalInterface
    private interface ReaderSource {
        Reader open(String key) throws IOException;
    }
    // ---------------------------------------------------------------- compiler

    /** Turns one biome's JSON tree into {@link Rule}s, collecting the palette and the depth reach. */
    private static final class Compiler {
        private final Palette palette;
        private final List<String> notes;
        private final Map<String, Integer> depthReach = new LinkedHashMap<>();
        private final Map<String, Boolean> surfaceDepthUse = new LinkedHashMap<>();
        private int currentDepthReach;
        private boolean currentUsesSurfaceDepth;
        private int bedrockDropped;
        private int cursorSlots;
        private String currentBiome = "";
        private final java.util.LinkedHashSet<String> materials = new java.util.LinkedHashSet<>();
        /**
         * Every vanilla noise id and positional-random name the compiled trees name.
         *
         * <p>Collected at compile time so the world binding can resolve them all up front, on the thread
         * that loads the world: {@code RandomState#getOrCreateNoise} caches through a
         * {@code ConcurrentHashMap.computeIfAbsent} (verified in the 26.3 jar), so a lazy fill from a
         * worldgen worker is safe but pays the build on the first chunk that reads it. Resolving them
         * once at bind keeps the hot path to a map hit.
         */
        private final java.util.LinkedHashSet<String> noiseIds = new java.util.LinkedHashSet<>();
        private final java.util.LinkedHashSet<String> gradientNames = new java.util.LinkedHashSet<>();

        Compiler(Palette palette, List<String> notes) {
            this.palette = palette;
            this.notes = notes;
        }

        void begin(String biome) {
            this.currentBiome = biome;
            this.currentDepthReach = 0;
            this.currentUsesSurfaceDepth = false;
            materials.clear();
        }

        int maxStoneDepth(String biome) {
            depthReach.put(biome, currentDepthReach);
            return currentDepthReach;
        }

        boolean usesSurfaceDepth(String biome) {
            surfaceDepthUse.put(biome, currentUsesSurfaceDepth);
            return currentUsesSurfaceDepth;
        }

        List<String> materials() {
            return List.copyOf(materials);
        }

        int deepest() {
            int deepest = 0;
            for (int reach : depthReach.values()) {
                deepest = Math.max(deepest, reach);
            }
            return deepest;
        }

        boolean anySurfaceDepth() {
            return surfaceDepthUse.containsValue(Boolean.TRUE);
        }

        List<String> noiseIds() {
            return List.copyOf(noiseIds);
        }

        List<String> gradientNames() {
            return List.copyOf(gradientNames);
        }

        // -------------------------------------------------- rules

        Rule rule(JsonObject json) {
            String type = type(json);
            switch (type) {
                case "minecraft:sequence":
                    return sequence(json);
                case "minecraft:condition":
                    return condition(json);
                case "minecraft:block": {
                    String key = stateKey(json, currentBiome);
                    materials.add(key);
                    return new BlockRule(palette.slot(key));
                }
                case "wover:switch_rule":
                    return switchRule(json);
                default:
                    throw unknown(type, "规则");
            }
        }

        private Rule sequence(JsonObject json) {
            JsonArray array = requireArray(json, "sequence");
            List<Rule> rules = new ArrayList<>(array.size());
            for (JsonElement element : array) {
                JsonObject child = asObject(element, "sequence[]");
                if (isBedrockPlate(child)) {
                    bedrockDropped++;
                    continue;
                }
                rules.add(rule(child));
            }
            return new Sequence(rules.toArray(new Rule[0]));
        }

        private Rule condition(JsonObject json) {
            Condition test = conditionNode(requireObject(json, "if_true"));
            return new Conditional(test, rule(requireObject(json, "then_run")));
        }

        private Rule switchRule(JsonObject json) {
            JsonArray collection = requireArray(json, "collection");
            Rule[] rules = new Rule[collection.size()];
            for (int i = 0; i < collection.size(); i++) {
                rules[i] = rule(asObject(collection.get(i), "collection[]"));
            }
            return new Switch(selector(requireObject(json, "selector")), rules);
        }

        // -------------------------------------------------- conditions

        Condition conditionNode(JsonObject json) {
            String type = type(json);
            switch (type) {
                case "minecraft:not":
                    return new Not(conditionNode(requireObject(json, "invert")));
                case "minecraft:vertical_gradient":
                    return verticalGradient(json);
                case "minecraft:stone_depth":
                    return stoneDepth(json);
                case "minecraft:noise_threshold":
                    return noiseThreshold(json);
                case "minecraft:hole":
                    return new Hole();
                case "wover:threshold_condition":
                    return thresholdCondition(json, false);
                case "wover:volume_threshold_condition":
                    return thresholdCondition(json, true);
                case "wover:rough_noise_condition":
                    return roughNoise(json);
                default:
                    throw unknown(type, "条件");
            }
        }

        private Condition verticalGradient(JsonObject json) {
            int trueAt = anchor(json, "true_at_and_below");
            int falseAt = anchor(json, "false_at_and_above");
            String randomName = stringOf(json, "random_name", "minecraft:gradient");
            gradientNames.add(randomName);
            return new VerticalGradient(trueAt, falseAt, randomName);
        }

        /**
         * {@code minecraft:stone_depth}, vanilla's {@code StoneDepthCondition}.
         *
         * <p>Vanilla computes, in {@code StoneDepthCondition$1.compute()}:
         * <pre>
         *   depth     = ceiling ? context.stoneDepthBelow() : context.stoneDepthAbove();
         *   surface   = addSurfaceDepth ? context.surfaceDepth() : 0;
         *   secondary = secondaryDepthRange == 0 ? 0
         *             : (int) Mth.map(context.getSurfaceSecondary(), -1.0, 1.0, 0.0, secondaryDepthRange);
         *   return depth &lt;= 1 + offset + surface + secondary;
         * </pre>
         * All four parts matter and all four were wrong or missing before: the {@code 1 +} (vanilla's
         * depths are 1-based), the real {@code surfaceDepth} (the port pinned it to 0), and the
         * {@code Mth.map} of the real {@code surfaceSecondary} (the port multiplied a hand-made
         * {@code [0,1)} hash by {@code range + 1}, which is a different distribution and a different
         * per-column value).
         *
         * <p>Reach for the depth budget: the deepest depth this condition can match is
         * {@code 1 + offset + secondaryDepthRange} (+ the column's {@code surfaceDepth} when
         * {@code add_surface_depth} is set).
         */
        private Condition stoneDepth(JsonObject json) {
            int offset = intOf(json, "offset", 0);
            boolean addSurfaceDepth = boolOf(json, "add_surface_depth", false);
            int secondary = intOf(json, "secondary_depth_range", 0);
            boolean ceiling = "ceiling".equalsIgnoreCase(stringOf(json, "surface_type", "floor"));
            currentDepthReach = Math.max(currentDepthReach, 1 + offset + Math.max(0, secondary));
            currentUsesSurfaceDepth |= addSurfaceDepth;
            return new StoneDepth(offset, addSurfaceDepth, secondary, ceiling);
        }

        /**
         * {@code minecraft:noise_threshold}, vanilla's {@code NoiseThresholdCondition}.
         *
         * <p>Vanilla: {@code d = context.getNoiseSampler(key, is3d).getAsDouble(); return d >= min && d <= max;},
         * where the 2D sampler is {@code NormalNoise.get(x, 0.0, z)} cached per column
         * ({@code MaterialRuleContext$1}). The JSON's own {@code min_threshold} / {@code max_threshold}
         * are used as written; there is no roughness term in this condition.
         */
        private Condition noiseThreshold(JsonObject json) {
            String noiseId = stringOf(json, "noise", "minecraft:netherrack");
            boolean is3d = boolOf(json, "is3d", false);
            double min = doubleOf(json, "min_threshold", -Double.MAX_VALUE);
            double max = doubleOf(json, "max_threshold", Double.MAX_VALUE);
            noiseIds.add(noiseId);
            return new NoiseThreshold(noiseId, is3d, min, max);
        }

        /**
         * {@code wover:threshold_condition} / {@code wover:volume_threshold_condition}.
         *
         * <p>From {@code ThresholdConditionImpl} and {@code VolumeThresholdConditionImpl}, verbatim:
         * <pre>
         *   value = noiseContext.eval(blockX * scaleX, blockZ * scaleZ);          // 2D
         *   value = noiseContext.eval(blockX * scaleX, blockY * scaleY, blockZ * scaleZ);  // 3D
         *   return value + roughness.sample(randomAt(x, y, z)) &gt; threshold;
         * </pre>
         * with {@code noiseContext.noise = new OpenSimplexNoise(seed)}, {@code seed} the JSON's own
         * {@code seed}, and {@code randomAt(x, y, z) = RandomSource.create(Mth.getSeed(Long.hashCode(seed), x, y, z))}.
         * {@code scale_*} is a <em>frequency</em> multiplier, so {@code 0.25} means "0.25 noise units per
         * block"; the previous port divided instead ({@code blocksPerPatch = 2.5 / scale}), which made
         * every patch 6.25 times too wide at {@code 0.25}.
         *
         * <p>{@code roughness} is a {@code FloatProvider} in that source, so its bounds are floats.
         */
        private Condition thresholdCondition(JsonObject json, boolean volumetric) {
            float[] roughness = floatRange(json, "roughness", 0.0f, 0.0f);
            double scaleX = doubleOf(json, "scale_x", 0.1);
            double scaleZ = doubleOf(json, "scale_z", 0.1);
            double scaleY = volumetric ? doubleOf(json, "scale_y", 0.1) : 1.0;
            double threshold = doubleOf(json, "threshold", 0.0);
            long seed = longOf(json, "seed", 0L);
            Noise noise = volumetric
                    ? new VolumeNoise(new OpenSimplexNoise(seed))
                    : new PlanarNoise(new OpenSimplexNoise(seed));
            return new Threshold(noise, scaleX, scaleY, scaleZ, roughness[0], roughness[1], threshold,
                    seed, volumetric);
        }

        /**
         * {@code wover:rough_noise_condition}, {@code RoughNoiseConditionImpl}.
         *
         * <p>Vanilla, verbatim: {@code d = normalNoise.get(x, y, z) + roughness.sample(roughnessSource);
         * return d >= minThreshold && d <= maxThreshold;} -- the field is the <em>vanilla</em>
         * {@code NormalNoise} the JSON names, reached through
         * {@code RandomState.getOrCreateNoise(key)}, not a noise of this port's own.
         *
         * <p>{@code roughnessSource} is built once per chunk by
         * {@code RandomState.getOrCreateRandomFactory(wover:roughness_noise).fromHashOf(...)}, i.e. one
         * running stream per condition per chunk whose seed does not depend on the position; the draw a
         * block gets therefore depends on how many blocks reached the condition before it. That is
         * upstream's own choice (and looks like a bug), so the pass has to reproduce its order, not just
         * its set: {@code NetherTerrainGenerator.planColumn} evaluates the same blocks (non-air,
         * non-fluid) in the same direction (downwards, columns x then z), and a fingerprint test pins the
         * resulting assignment. See the class note.
         */
        private Condition roughNoise(JsonObject json) {
            String noiseId = stringOf(json, "noise", "minecraft:netherrack");
            double min = doubleOf(json, "min_threshold", -Double.MAX_VALUE);
            double max = doubleOf(json, "max_threshold", Double.MAX_VALUE);
            float[] roughness = floatRange(json, "roughness", -0.1f, 0.4f);
            noiseIds.add(noiseId);
            int cursor = cursorSlots++;
            return new RoughNoise(cursor, noiseId, roughness[0], roughness[1], min, max);
        }

        /**
         * {@code wover:switch_rule}'s selector: an index into the collection.
         *
         * <p>{@code SwitchRuleSource.compile}: {@code int nr = Math.max(0, selector.getNumber(ctx)) % compiled.length;}
         * -- no negative wrap-around, and the whole collection is compiled regardless of the index.
         *
         * <p>Three selector types occur in the 23 files:
         * <ul>
         *   <li>{@code wover:nether_noise} -- {@code NetherNoiseCondition}. Its codec is
         *       {@code Codec.BYTE.fieldOf("nether_noise").xmap(... -> INSTANCE, ...)}, so the JSON's byte
         *       is decoded and then <em>discarded</em>: every {@code nether_noise} in every file is the
         *       same provider. It reads {@code Conditions.NETHER_VOLUME_NOISE} (seed 245, roughness
         *       uniform(-0.1, 0.2), scale (0.1, 0.2, 0.1)) and draws three values from
         *       {@code randomAt(x, y, z)}.</li>
         *   <li>{@code betternether:nether_grasslands} and {@code betternether:nether_mushroom_forrest_edge}
         *       -- the two providers in this repository
         *       ({@code world/biomes/providers/*NumericProvider.java}), reproduced branch for branch,
         *       including their fixed base seeds and their use of {@code stoneDepthAbove}.</li>
         * </ul>
         */
        private Selector selector(JsonObject json) {
            String type = type(json);
            switch (type) {
                case "wover:nether_noise":
                    return NetherNoiseSelector.INSTANCE;
                case "betternether:nether_grasslands":
                    return GrasslandsSelector.INSTANCE;
                case "betternether:nether_mushroom_forrest_edge":
                    return MushroomEdgeSelector.INSTANCE;
                default:
                    throw unknown(type, "选择器");
            }
        }

        // -------------------------------------------------- helpers

        private RuntimeException unknown(String type, String role) {
            return new IllegalStateException(
                    "群系 " + currentBiome + " 的地表规则含不支持的" + role + "类型：" + type
                            + "（SurfaceRules 必须显式支持它，不能默默回退成下界岩）");
        }
    }
    // ---------------------------------------------------------------- the vanilla nether

    /**
     * Vanilla's own nether material rule, transcribed from the server jar.
     *
     * <h2>Source</h2>
     * {@code net.minecraft.data.worldgen.material.NetherMaterialRules.bootstrap(BootstrapContext)}
     * plus {@code VanillaMaterialConditions.bootstrap} and {@code MaterialRules.yBlockCheck/yStartCheck},
     * all disassembled with {@code javap -c} from the 26.3 jar this module compiles against. It is
     * <em>one</em> tree of eight entries, dispatched by {@code isBiome}; nothing about it is a
     * per-biome table, and none of it is "pick the surface block and hope":
     *
     * <pre>
     *   ON_FLOOR      = stoneDepthCheck(0, false, FLOOR)      // VanillaMaterialConditions: iconst_0, iconst_0
     *   UNDER_FLOOR   = stoneDepthCheck(0, true,  FLOOR)      //                            iconst_0, iconst_1
     *   UNDER_CEILING = stoneDepthCheck(0, true,  CEILING)
     *   above31  = yBlockCheck(absolute(31), 0)      start30 = yStartCheck(absolute(30), 0)
     *   above32  = yBlockCheck(absolute(32), 0)      below35 = not(yStartCheck(absolute(35), 0))
     *   roof5    = yBlockCheck(belowTop(5), 0)       hole    = hole()
     *   noise: soul_sand_layer -0.012, gravel_layer -0.012, patch -0.012,
     *          netherrack 0.54, nether_wart 1.17, nether_state_selector 0.0
     *   gravel   = patch &amp;&amp; start30 &amp;&amp; below35 -&gt; GRAVEL
     *
     *   sequence(
     *     BEDROCK_FLOOR, BEDROCK_ROOF,                       // dropped here: generateBedrock stamps them
     *     roof5 -&gt; NETHERRACK,
     *     isBiome(basalt_deltas) -&gt; sequence(
     *         underCeiling -&gt; BASALT,
     *         underFloor   -&gt; sequence(gravel, stateSelector -&gt; BASALT, BLACKSTONE)),
     *     isBiome(soul_sand_valley) -&gt; sequence(
     *         underCeiling -&gt; sequence(stateSelector -&gt; SOUL_SAND, SOUL_SOIL),
     *         underFloor   -&gt; sequence(gravel, stateSelector -&gt; SOUL_SAND, SOUL_SOIL)),
     *     onFloor -&gt; sequence(
     *         !above32 &amp;&amp; hole            -&gt; LAVA,
     *         isBiome(warped_forest)  &amp;&amp; !netherrackNoise &amp;&amp; above31 -&gt; sequence(wart -&gt; WARPED_WART_BLOCK, WARPED_NYLIUM),
     *         isBiome(crimson_forest) &amp;&amp; !netherrackNoise &amp;&amp; above31 -&gt; sequence(wart -&gt; NETHER_WART_BLOCK, CRIMSON_NYLIUM)),
     *     isBiome(nether_wastes) -&gt; sequence(
     *         underFloor -&gt; soul_sand_layer -&gt; sequence(!hole &amp;&amp; start30 &amp;&amp; below35 -&gt; SOUL_SAND, NETHERRACK),
     *         onFloor    -&gt; above31 &amp;&amp; below35 &amp;&amp; gravel_layer
     *                       -&gt; sequence(above32 -&gt; GRAVEL, !hole -&gt; GRAVEL)),
     *     NETHERRACK)
     * </pre>
     *
     * <h2>Why this port carries it at all</h2>
     * Its biome source can produce all five vanilla nether biomes, and the 23 upstream files cover only
     * the mod's own. A column of {@code soul_sand_valley} used to fall through to the base netherrack:
     * no soul sand, no soul soil, no blackstone in {@code basalt_deltas}, no nylium under the forests.
     * Painting "<em>the</em> surface block" per vanilla biome would have looked plausible and been wrong
     * in every detail that matters here -- the lava pockets depend on {@code hole()}, the nylium only
     * exists above y=31, and the soul sand and gravel layers are noise bands under the floor.
     *
     * <p>The two bedrock entries are the one thing not carried over, for the same reason the 23 files'
     * plates are dropped: this port's {@code generateBedrock} stamps exactly those two gradients, and the
     * painter refuses to write inside the plates at all.
     *
     * <p>The depth reach this set contributes is vanilla's: {@code UNDER_FLOOR} / {@code UNDER_CEILING}
     * are {@code offset 0} with {@code add_surface_depth}, so the deepest block the tree can match is
     * {@code 1 + surfaceDepth}.
     */
    private static final class VanillaNether {
        private final Palette palette;
        private final java.util.Set<String> noiseIds;
        private final java.util.LinkedHashSet<String> materials = new java.util.LinkedHashSet<>();

        private VanillaNether(Palette palette, java.util.Set<String> noiseIds) {
            this.palette = palette;
            this.noiseIds = noiseIds;
        }

        static Set build(Palette palette, java.util.Set<String> noiseIds) {
            VanillaNether nether = new VanillaNether(palette, noiseIds);
            Rule root = nether.tree();
            return new Set("minecraft:nether", root, palette, 1, true,
                    List.copyOf(nether.materials));
        }

        private Rule block(String id) {
            materials.add(id);
            return new BlockRule(palette.slot(id));
        }

        private Condition biome(String id) {
            return new BiomeRule(List.of(id));
        }

        private Condition noise(String id, double min) {
            noiseIds.add(id);
            return new NoiseThreshold(id, false, min, Double.MAX_VALUE);
        }

        private static Rule ifTrue(Condition condition, Rule rule) {
            return new Conditional(condition, rule);
        }

        private static Rule seq(Rule... rules) {
            return new Sequence(rules);
        }

        private Rule tree() {
            Condition onFloor = new StoneDepth(0, false, 0, false);
            Condition underCeiling = new StoneDepth(0, true, 0, true);
            Condition underFloor = new StoneDepth(0, true, 0, false);
            Condition above31 = new YCheck(31, 0, false);
            Condition above32 = new YCheck(32, 0, false);
            Condition start30 = new YCheck(30, 0, true);
            Condition below35 = new Not(new YCheck(35, 0, true));
            Condition roof5 = new YCheck(NETHER_TOP - 5, 0, false);
            Condition hole = new Hole();
            Condition soulSandLayer = noise("minecraft:soul_sand_layer", -0.012);
            Condition gravelLayer = noise("minecraft:gravel_layer", -0.012);
            Condition patch = noise("minecraft:patch", -0.012);
            Condition netherrackNoise = noise("minecraft:netherrack", 0.54);
            Condition wartNoise = noise("minecraft:nether_wart", 1.17);
            Condition stateSelector = noise("minecraft:nether_state_selector", 0.0);

            Rule gravel = ifTrue(patch, ifTrue(start30, ifTrue(below35, block("minecraft:gravel"))));

            Rule basaltDeltas = ifTrue(biome("minecraft:basalt_deltas"), seq(
                    ifTrue(underCeiling, block("minecraft:basalt")),
                    ifTrue(underFloor, seq(
                            gravel,
                            ifTrue(stateSelector, block("minecraft:basalt")),
                            block("minecraft:blackstone")))));

            Rule soulSandValley = ifTrue(biome("minecraft:soul_sand_valley"), seq(
                    ifTrue(underCeiling, seq(
                            ifTrue(stateSelector, block("minecraft:soul_sand")),
                            block("minecraft:soul_soil"))),
                    ifTrue(underFloor, seq(
                            gravel,
                            ifTrue(stateSelector, block("minecraft:soul_sand")),
                            block("minecraft:soul_soil")))));

            Rule lavaPockets = ifTrue(onFloor, seq(
                    ifTrue(new Not(above32), ifTrue(hole, block("minecraft:lava"))),
                    ifTrue(biome("minecraft:warped_forest"), ifTrue(new Not(netherrackNoise),
                            ifTrue(above31, seq(
                                    ifTrue(wartNoise, block("minecraft:warped_wart_block")),
                                    block("minecraft:warped_nylium"))))),
                    ifTrue(biome("minecraft:crimson_forest"), ifTrue(new Not(netherrackNoise),
                            ifTrue(above31, seq(
                                    ifTrue(wartNoise, block("minecraft:nether_wart_block")),
                                    block("minecraft:crimson_nylium")))))));

            Rule netherWastes = ifTrue(biome("minecraft:nether_wastes"), seq(
                    ifTrue(underFloor, ifTrue(soulSandLayer, seq(
                            ifTrue(new Not(hole),
                                    ifTrue(start30, ifTrue(below35, block("minecraft:soul_sand")))),
                            block("minecraft:netherrack")))),
                    ifTrue(onFloor, ifTrue(above31, ifTrue(below35, ifTrue(gravelLayer, seq(
                            ifTrue(above32, block("minecraft:gravel")),
                            ifTrue(new Not(hole), block("minecraft:gravel")))))))));

            return seq(
                    ifTrue(roof5, block("minecraft:netherrack")),
                    basaltDeltas,
                    soulSandValley,
                    lavaPockets,
                    netherWastes,
                    block("minecraft:netherrack"));
        }
    }

    // ---------------------------------------------------------------- node implementations

    private static final class Sequence implements Rule {
        private final Rule[] rules;

        Sequence(Rule[] rules) {
            this.rules = rules;
        }

        @Override
        public int apply(Context ctx) {
            for (Rule rule : rules) {
                int slot = rule.apply(ctx);
                if (slot != UNDECIDED) {
                    return slot;
                }
            }
            return UNDECIDED;
        }
    }

    private static final class BlockRule implements Rule {
        private final int slot;

        BlockRule(int slot) {
            this.slot = slot;
        }

        @Override
        public int apply(Context ctx) {
            return slot;
        }
    }

    private static final class Conditional implements Rule {
        private final Condition test;
        private final Rule then;

        Conditional(Condition test, Rule then) {
            this.test = test;
            this.then = then;
        }

        @Override
        public int apply(Context ctx) {
            return test.test(ctx) ? then.apply(ctx) : UNDECIDED;
        }
    }

    private static final class Not implements Condition {
        private final Condition invert;

        Not(Condition invert) {
            this.invert = invert;
        }

        @Override
        public boolean test(Context ctx) {
            return !invert.test(ctx);
        }
    }

    private static final class Switch implements Rule {
        private final Selector selector;
        private final Rule[] collection;

        Switch(Selector selector, Rule[] collection) {
            this.selector = selector;
            this.collection = collection;
        }

        @Override
        public int apply(Context ctx) {
            if (collection.length == 0) {
                return UNDECIDED;
            }
            int index = Math.max(0, selector.index(ctx)) % collection.length;
            return collection[index].apply(ctx);
        }
    }

    interface Selector {
        int index(Context ctx);
    }

    /** {@code minecraft:stone_depth}; see {@code Compiler.stoneDepth} for the source and the four parts. */
    private static final class StoneDepth implements Condition {
        private final int offset;
        private final boolean addSurfaceDepth;
        private final int secondaryDepthRange;
        private final boolean ceiling;

        StoneDepth(int offset, boolean addSurfaceDepth, int secondaryDepthRange, boolean ceiling) {
            this.offset = offset;
            this.addSurfaceDepth = addSurfaceDepth;
            this.secondaryDepthRange = secondaryDepthRange;
            this.ceiling = ceiling;
        }

        @Override
        public boolean test(Context ctx) {
            int depth = ceiling ? ctx.stoneDepthBelow() : ctx.stoneDepthAbove();
            int surface = addSurfaceDepth ? ctx.surfaceDepth() : 0;
            int secondary = secondaryDepthRange == 0 ? 0
                    : (int) map(ctx.surfaceSecondary(), -1.0, 1.0, 0.0, secondaryDepthRange);
            return depth <= 1 + offset + surface + secondary;
        }
    }

    /**
     * {@code minecraft:hole}, vanilla's {@code HoleCondition}.
     *
     * <p>Verbatim: {@code return context.surfaceDepth() <= 0;} -- it is a property of the column, not a
     * draw. The earlier port implemented it as a per-block 50/50 coin flip, on the reasoning that
     * nothing in the repository defined a "hole"; this is the definition.
     */
    private static final class Hole implements Condition {
        @Override
        public boolean test(Context ctx) {
            return ctx.surfaceDepth() <= 0;
        }
    }

    /**
     * {@code minecraft:vertical_gradient}, vanilla's {@code VerticalGradientCondition}.
     *
     * <p>Verbatim: {@code if (y <= trueAtAndBelow) return true; if (y >= falseAtAndAbove) return false;
     * return randomFactory.at(x, y, z).nextFloat() < Mth.map(y, trueAt, falseAt, 1.0, 0.0);}, where the
     * random factory is {@code context.getOrCreateRandomFactory(randomName)} -- positional, so the same
     * block always draws the same value.
     *
     * <p>Only reachable here for gradients that survive the bedrock-pair drop, but implemented
     * faithfully so the semantics are not silently wrong: the plates are ragged because of this draw.
     */
    private static final class VerticalGradient implements Condition {
        private final int trueAt;
        private final int falseAt;
        private final String randomName;

        VerticalGradient(int trueAt, int falseAt, String randomName) {
            this.trueAt = trueAt;
            this.falseAt = falseAt;
            this.randomName = randomName;
        }

        @Override
        public boolean test(Context ctx) {
            int y = ctx.y();
            if (y <= trueAt) {
                return true;
            }
            if (y >= falseAt) {
                return false;
            }
            double chance = map(y, trueAt, falseAt, 1.0, 0.0);
            return ctx.vanilla().gradientNextFloat(randomName, ctx.x(), y, ctx.z()) < chance;
        }
    }

    /** {@code minecraft:noise_threshold}; see {@code Compiler.noiseThreshold} for the source. */
    private static final class NoiseThreshold implements Condition {
        private final String noiseId;
        private final boolean is3d;
        private final double min;
        private final double max;

        NoiseThreshold(String noiseId, boolean is3d, double min, double max) {
            this.noiseId = noiseId;
            this.is3d = is3d;
            this.min = min;
            this.max = max;
        }

        @Override
        public boolean test(Context ctx) {
            Vanilla vanilla = ctx.vanilla();
            double value = is3d
                    ? vanilla.noise3d(noiseId, ctx.x(), ctx.y(), ctx.z())
                    : vanilla.noise2d(noiseId, ctx.x(), ctx.z());
            return value >= min && value <= max;
        }
    }

    /**
     * {@code isBiome(...)}: whether the position's biome is one of these.
     *
     * <p>Vanilla resolves the keys into a {@code HolderSet} at build time; this port compares the key
     * the painter already knows, by path, so {@code minecraft:soul_sand_valley} and
     * {@code soul_sand_valley} are the same answer (this port's own 23 keys are unnamespaced).
     */
    private static final class BiomeRule implements Condition {
        private final List<String> paths;

        BiomeRule(List<String> biomeIds) {
            List<String> stripped = new ArrayList<>(biomeIds.size());
            for (String id : biomeIds) {
                int colon = id.indexOf(':');
                stripped.add(colon < 0 ? id : id.substring(colon + 1));
            }
            this.paths = List.copyOf(stripped);
        }

        @Override
        public boolean test(Context ctx) {
            String key = ctx.biome();
            int colon = key.indexOf(':');
            String path = colon < 0 ? key : key.substring(colon + 1);
            return paths.contains(path);
        }
    }

    /**
     * {@code YCondition}, i.e. vanilla's {@code yBlockCheck} / {@code yStartCheck}.
     *
     * <p>Verbatim from {@code YCondition$1.compute}:
     * <pre>
     *   y = context.blockY() + (addStoneDepth ? context.stoneDepthAbove() : 0);
     *   return y &gt;= context.resolveAnchorY(anchor) + context.surfaceDepth() * surfaceDepthMultiplier;
     * </pre>
     * The two factories differ only in {@code addStoneDepth}: {@code yBlockCheck} is the plain block
     * height, {@code yStartCheck} shifts by the stone depth above the block, which is what makes it a
     * <em>start</em> check (it answers per run of rock, not per block).
     */
    private static final class YCheck implements Condition {
        private final int anchorY;
        private final int surfaceDepthMultiplier;
        private final boolean addStoneDepth;

        YCheck(int anchorY, int surfaceDepthMultiplier, boolean addStoneDepth) {
            this.anchorY = anchorY;
            this.surfaceDepthMultiplier = surfaceDepthMultiplier;
            this.addStoneDepth = addStoneDepth;
        }

        @Override
        public boolean test(Context ctx) {
            int y = ctx.y() + (addStoneDepth ? ctx.stoneDepthAbove() : 0);
            return y >= anchorY + ctx.surfaceDepth() * surfaceDepthMultiplier;
        }
    }

    /**
     * {@code wover:threshold_condition} / {@code wover:volume_threshold_condition}.
     *
     * <p>{@code value = noise.eval(x * scaleX[, y * scaleY], z * scaleZ);
     * return value + roughness.sample(randomAt(x, y, z)) > threshold;} -- the comparison is strict, and
     * the roughness draw comes from a source seeded by the position, so it is a pure function of
     * {@code (seed, x, y, z)} and needs no per-chunk state.
     */
    private static final class Threshold implements Condition {
        private final Noise noise;
        private final double scaleX;
        private final double scaleY;
        private final double scaleZ;
        private final float minRoughness;
        private final float maxRoughness;
        private final double threshold;
        private final long seed;
        private final boolean volumetric;

        Threshold(Noise noise, double scaleX, double scaleY, double scaleZ,
                  float minRoughness, float maxRoughness, double threshold, long seed,
                  boolean volumetric) {
            this.noise = noise;
            this.scaleX = scaleX;
            this.scaleY = scaleY;
            this.scaleZ = scaleZ;
            this.minRoughness = minRoughness;
            this.maxRoughness = maxRoughness;
            this.threshold = threshold;
            this.seed = seed;
            this.volumetric = volumetric;
        }

        @Override
        public boolean test(Context ctx) {
            int x = ctx.x();
            int z = ctx.z();
            double value = noise.at(x * scaleX, volumetric ? ctx.y() * scaleY : 0.0, z * scaleZ);
            LegacyRandom random = new LegacyRandom(
                    getSeed((int) (seed ^ (seed >>> 32)), x, volumetric ? ctx.y() : 0, z));
            return value + randomBetween(random, minRoughness, maxRoughness) > threshold;
        }
    }

    /**
     * {@code wover:rough_noise_condition}; see {@code Compiler.roughNoise} for the source, and the class
     * note for the one thing that cannot be reproduced (which block gets which draw).
     */
    private static final class RoughNoise implements Condition {
        private final int cursor;
        private final String noiseId;
        private final float minRoughness;
        private final float maxRoughness;
        private final double min;
        private final double max;

        RoughNoise(int cursor, String noiseId, float minRoughness, float maxRoughness,
                   double min, double max) {
            this.cursor = cursor;
            this.noiseId = noiseId;
            this.minRoughness = minRoughness;
            this.maxRoughness = maxRoughness;
            this.min = min;
            this.max = max;
        }

        @Override
        public boolean test(Context ctx) {
            float drawn = ctx.cursor(cursor).nextFloat();
            double value = ctx.vanilla().noise3d(noiseId, ctx.x(), ctx.y(), ctx.z())
                    + (drawn * (maxRoughness - minRoughness) + minRoughness);
            return value >= min && value <= max;
        }
    }

    /**
     * {@code wover:nether_noise}, wover's {@code NetherNoiseCondition}.
     *
     * <p>The JSON's {@code nether_noise} byte is decoded and thrown away by the codec
     * ({@code Codec.BYTE.fieldOf("nether_noise").xmap(obj -> INSTANCE, obj -> (byte) 0)}), so every
     * {@code nether_noise} selector is this one provider. Verbatim:
     * <pre>
     *   value = NETHER_VOLUME_NOISE.noise.eval(x * 0.1, y * 0.2, z * 0.1);
     *   random = NETHER_VOLUME_NOISE.randomAt(x, y, z);
     *   offset = random.nextInt(20) == 0 ? 3 : 0;
     *   cmp = RandomHelper.inRange(random, 0.4F, 0.5F);
     *   if (value &gt; cmp || value &lt; -cmp) return 2 + offset;
     *   if (value &gt; NETHER_VOLUME_NOISE.roughness.sample(random)) return 0 + offset;
     *   return 1 + offset;
     * </pre>
     * {@code NETHER_VOLUME_NOISE} is {@code Conditions.volumeThreshold(245, 0, UniformFloat.of(-0.1f, 0.2f),
     * 0.1, 0.2, 0.1)}, whose noise is {@code new OpenSimplexNoise(245)} and whose {@code randomAt} is
     * {@code RandomSource.create(Mth.getSeed(Long.hashCode(245), x, y, z))}.
     */
    private static final class NetherNoiseSelector implements Selector {
        static final NetherNoiseSelector INSTANCE = new NetherNoiseSelector();

        private static final long SEED = 245L;
        private static final double SCALE_X = 0.1;
        private static final double SCALE_Y = 0.2;
        private static final double SCALE_Z = 0.1;
        private static final float ROUGH_MIN = -0.1f;
        private static final float ROUGH_MAX = 0.2f;
        private static final float NEAR_MIN = 0.4f;
        private static final float NEAR_MAX = 0.5f;

        private final OpenSimplexNoise noise = new OpenSimplexNoise(SEED);

        @Override
        public int index(Context ctx) {
            int x = ctx.x();
            int y = ctx.y();
            int z = ctx.z();
            double value = noise.eval(x * SCALE_X, y * SCALE_Y, z * SCALE_Z);
            LegacyRandom random = new LegacyRandom(getSeed((int) (SEED ^ (SEED >>> 32)), x, y, z));
            int offset = random.nextInt(20) == 0 ? 3 : 0;
            float cmp = randomBetween(random, NEAR_MIN, NEAR_MAX);
            if (value > cmp || value < -cmp) {
                return 2 + offset;
            }
            if (value > randomBetween(random, ROUGH_MIN, ROUGH_MAX)) {
                return offset;
            }
            return 1 + offset;
        }
    }

    /**
     * {@code betternether:nether_grasslands}, {@code NetherGrasslandsNumericProvider}, verbatim.
     *
     * <p>Note which depth it reads: {@code ctx.getStoneDepthAbove()}, wover's own 1-based count, so the
     * first branch is "the exposed block and the one below it" and the third is "two or more blocks
     * down". The earlier port passed a 0-based depth into the same branches, which shifted all three.
     */
    private static final class GrasslandsSelector implements Selector {
        static final GrasslandsSelector INSTANCE = new GrasslandsSelector();

        private static final int SEED = 0x6E677261;

        @Override
        public int index(Context ctx) {
            LegacyRandom random = new LegacyRandom(getSeed(SEED, ctx.x(), ctx.y(), ctx.z()));
            int depth = ctx.stoneDepthAbove();
            if (depth <= 1) {
                return random.nextInt(3);
            }
            if (depth <= random.nextInt(3) + 1) {
                return 0;
            }
            return 2;
        }
    }

    /** {@code betternether:nether_mushroom_forrest_edge}, verbatim (including its typo). */
    private static final class MushroomEdgeSelector implements Selector {
        static final MushroomEdgeSelector INSTANCE = new MushroomEdgeSelector();

        private static final int SEED = 0x6D736872;

        @Override
        public int index(Context ctx) {
            LegacyRandom random = new LegacyRandom(getSeed(SEED, ctx.x(), ctx.y(), ctx.z()));
            return random.nextInt(4) > 0 ? 0 : (random.nextBoolean() ? 1 : 2);
        }
    }

    /** A wover noise sampled on the XZ plane (2D conditions). */
    private static final class PlanarNoise implements Noise {
        private final OpenSimplexNoise noise;

        PlanarNoise(OpenSimplexNoise noise) {
            this.noise = noise;
        }

        @Override
        public double at(double x, double y, double z) {
            return noise.eval(x, z);
        }
    }

    /** A wover noise sampled in the block volume (the volume conditions). */
    private static final class VolumeNoise implements Noise {
        private final OpenSimplexNoise noise;

        VolumeNoise(OpenSimplexNoise noise) {
            this.noise = noise;
        }

        @Override
        public double at(double x, double y, double z) {
            return noise.eval(x, y, z);
        }
    }

    /**
     * Vanilla's {@code LegacyRandomSource}, which is what {@code RandomSource.create(long)} returns.
     *
     * <p>Reproduced rather than approximated: the same 48-bit LCG as {@code java.util.Random}
     * (multiplier {@code 25214903917}, addend {@code 11}, seeded with {@code seed ^ multiplier}), and the
     * same {@code nextInt(bound)} including its power-of-two shortcut. So
     * {@code new LegacyRandom(seed)} yields exactly the stream
     * {@code RandomSource.create(seed)} would -- which the tests pin against
     * {@code java.util.Random} itself. The earlier port used SplitMix64 here, on the reasoning that the
     * two providers only draw {@code nextInt(3)} / {@code nextInt(4)} / {@code nextBoolean} and the
     * shares would match; the shares do, the blocks do not, and the selectors also draw
     * {@code nextInt(20)} and {@code nextFloat}, where even the shares differ.
     */
    static final class LegacyRandom {
        private static final long MULTIPLIER = 25214903917L;
        private static final long ADDEND = 11L;
        private static final long MASK = 281474976710655L;

        private long seed;

        LegacyRandom(long seed) {
            this.seed = (seed ^ MULTIPLIER) & MASK;
        }

        private int next(int bits) {
            seed = (seed * MULTIPLIER + ADDEND) & MASK;
            return (int) (seed >>> (48 - bits));
        }

        int nextInt(int bound) {
            if (bound <= 0) {
                throw new IllegalArgumentException("bound must be positive");
            }
            int r = next(31);
            int m = bound - 1;
            if ((bound & m) == 0) {
                return (int) ((bound * (long) r) >> 31);
            }
            int u = r;
            while (u - (r = u % bound) + m < 0) {
                u = next(31);
            }
            return r;
        }

        float nextFloat() {
            return next(24) / (float) (1 << 24);
        }

        double nextDouble() {
            return (((long) next(26) << 27) + next(27)) * 0x1.0p-53;
        }

        boolean nextBoolean() {
            return next(1) != 0;
        }
    }

    // ---------------------------------------------------------------- helpers

    /**
     * {@code Mth.map(value, min, max, toMin, toMax)} = {@code lerp(inverseLerp(value, min, max), toMin, toMax)}.
     *
     * <p>Two callers, both vanilla's: {@code stone_depth}'s {@code secondary_depth_range} and
     * {@code vertical_gradient}'s probability ramp.
     */
    static double map(double value, double min, double max, double toMin, double toMax) {
        double delta = (value - min) / (max - min);
        return toMin + delta * (toMax - toMin);
    }

    /** {@code Mth.randomBetween(random, min, max)}: {@code nextFloat() * (max - min) + min}, in floats. */
    private static float randomBetween(LegacyRandom random, float min, float max) {
        return random.nextFloat() * (max - min) + min;
    }

    /**
     * {@code de.ambertation.wover.math.api.MathHelper.getSeed(int, int, int, int)}, verbatim.
     *
     * <p>wover's own mixing (not vanilla's), used by every positional {@code RandomSource.create} in
     * this file: {@code h = seed + x * 374761393 + y * 668265263 + z; h = (h ^ (h >> 13)) * 1274126177;
     * return h ^ (h >> 16);} -- note the arithmetic shifts and the {@code int} overflow, both of which
     * are part of the function.
     */
    static int getSeed(int seed, int x, int y, int z) {
        int h = seed + x * 374761393 + y * 668265263 + z;
        h = (h ^ (h >> 13)) * 1274126177;
        return h ^ (h >> 16);
    }

    /** {@code java.lang.Long.hashCode(long)}, which wover passes as the base seed. */
    static int seedHash(long seed) {
        return (int) (seed ^ (seed >>> 32));
    }

    /**
     * A {@code FloatProvider} from the JSON as {@code [min, max]} floats.
     *
     * <p>Vanilla's codec for the ones wover writes: {@code minecraft:uniform} with
     * {@code min_inclusive}/{@code max_exclusive} (older data nests them under {@code value}), and
     * {@code minecraft:constant} with {@code value}. A missing provider is the constant the call site
     * passed as its default, which is how {@code RoughNoiseConditionImpl}'s two convenience constructors
     * spell it.
     */
    private static float[] floatRange(JsonObject json, String key, float defaultMin, float defaultMax) {
        JsonElement element = json.get(key);
        if (element == null || element.isJsonNull()) {
            return new float[]{defaultMin, defaultMax};
        }
        if (element.isJsonPrimitive()) {
            float value = element.getAsFloat();
            return new float[]{value, value};
        }
        JsonObject object = asObject(element, key);
        String type = type(object);
        if ("minecraft:constant".equals(type)) {
            float value = (float) doubleOf(object, "value", 0.0);
            return new float[]{value, value};
        }
        if (!type.isEmpty() && !"minecraft:uniform".equals(type)) {
            throw new IllegalStateException("不支持的 FloatProvider 类型：" + type + "（" + key + "）");
        }
        JsonObject bounds = object;
        JsonElement nested = object.get("value");
        if (nested != null && nested.isJsonObject()) {
            bounds = nested.getAsJsonObject();
        }
        float min = (float) doubleOf(bounds, "min_inclusive", defaultMin);
        float max = (float) doubleOf(bounds, "max_exclusive", min);
        return new float[]{min, max};
    }

    /**
     * Whether this rule is one of the two bedrock plates every file re-declares.
     *
     * <p>Recognised structurally (a {@code condition} over a {@code vertical_gradient}, possibly
     * negated, whose {@code then_run} is a bedrock block) rather than by its numbers, so a file whose
     * anchors move is still dropped instead of quietly painting bedrock through the surface pass.
     */
    static boolean isBedrockPlate(JsonObject rule) {
        if (!"minecraft:condition".equals(type(rule))) {
            return false;
        }
        JsonElement thenRun = rule.get("then_run");
        JsonElement ifTrue = rule.get("if_true");
        if (thenRun == null || !thenRun.isJsonObject() || ifTrue == null || !ifTrue.isJsonObject()) {
            return false;
        }
        JsonObject then = thenRun.getAsJsonObject();
        if (!"minecraft:block".equals(type(then)) || !BEDROCK_ID.equals(state(then))) {
            return false;
        }
        return isGradient(ifTrue.getAsJsonObject());
    }

    private static boolean isGradient(JsonObject condition) {
        String type = type(condition);
        if ("minecraft:vertical_gradient".equals(type)) {
            return true;
        }
        if ("minecraft:not".equals(type)) {
            JsonElement invert = condition.get("invert");
            return invert != null && invert.isJsonObject() && isGradient(invert.getAsJsonObject());
        }
        return false;
    }

    /**
     * Resolves a {@code true_at_and_below} / {@code false_at_and_above} anchor to a y.
     *
     * <p>Vanilla's {@code VerticalAnchor}: {@code above_bottom(n)} is {@code minY + n},
     * {@code below_top(n)} is {@code minY + height - 1 - n} (the top block, inclusive), and
     * {@code absolute(n)} is {@code n}. For the Nether that is y=0..127, so {@code below_top: 5} is 122.
     */
    private static int anchor(JsonObject json, String key) {
        JsonObject anchor = requireObject(json, key);
        if (anchor.has("absolute")) {
            return anchor.get("absolute").getAsInt();
        }
        if (anchor.has("above_bottom")) {
            return NETHER_BOTTOM + anchor.get("above_bottom").getAsInt();
        }
        if (anchor.has("below_top")) {
            return NETHER_TOP - anchor.get("below_top").getAsInt();
        }
        throw new IllegalStateException("vertical_gradient 的锚点无效：" + key + " = " + anchor);
    }

    private static String type(JsonObject json) {
        JsonElement type = json.get("type");
        return type == null ? "" : type.getAsString();
    }

    /**
     * The palette key for a {@code result_state}.
     *
     * <p>A string is the id; an object is {@code {id, properties}} and becomes
     * {@code id[k=v,...]} in Bukkit's own state syntax. Only vanilla ids can carry properties into
     * {@code CraftEngineLookup}, which resolves a CustomEngine block by id; the one such state in the
     * 23 files ({@code betternether:soul_sandstone[up=false]}) is decorative, so it is flattened.
     */
    static String stateKey(JsonObject block, String biome) {
        JsonElement state = block.get("result_state");
        if (state == null) {
            throw new IllegalStateException("群系 " + biome + " 的 block 规则缺少 result_state");
        }
        if (state.isJsonPrimitive()) {
            return state.getAsString();
        }
        JsonObject object = state.getAsJsonObject();
        String id = object.get("id").getAsString();
        JsonElement properties = object.get("properties");
        if (properties == null || !properties.isJsonObject() || !id.startsWith("minecraft:")) {
            return id;
        }
        StringBuilder key = new StringBuilder(id).append('[');
        boolean first = true;
        for (Map.Entry<String, JsonElement> entry : properties.getAsJsonObject().entrySet()) {
            if (!first) {
                key.append(',');
            }
            key.append(entry.getKey()).append('=').append(entry.getValue().getAsString());
            first = false;
        }
        return key.append(']').toString();
    }

    static String state(JsonObject block) {
        JsonElement state = block.get("result_state");
        return state == null ? "" : state.isJsonPrimitive() ? state.getAsString()
                : state.getAsJsonObject().get("id").getAsString();
    }

    private static JsonObject requireObject(JsonObject json, String key) {
        JsonElement value = json.get(key);
        if (value == null || !value.isJsonObject()) {
            throw new IllegalStateException("地表规则缺少对象字段 " + key + "：" + json);
        }
        return value.getAsJsonObject();
    }

    private static JsonArray requireArray(JsonObject json, String key) {
        JsonElement value = json.get(key);
        if (value == null || !value.isJsonArray()) {
            throw new IllegalStateException("地表规则缺少数组字段 " + key + "：" + json);
        }
        return value.getAsJsonArray();
    }

    private static JsonObject asObject(JsonElement element, String where) {
        if (!element.isJsonObject()) {
            throw new IllegalStateException(where + " 不是对象：" + element);
        }
        return element.getAsJsonObject();
    }

    private static String stringOf(JsonObject json, String key, String fallback) {
        JsonElement value = json.get(key);
        return value == null || value.isJsonNull() ? fallback : value.getAsString();
    }

    private static int intOf(JsonObject json, String key, int fallback) {
        JsonElement value = json.get(key);
        return value == null || value.isJsonNull() ? fallback : value.getAsInt();
    }

    private static long longOf(JsonObject json, String key, long fallback) {
        JsonElement value = json.get(key);
        return value == null || value.isJsonNull() ? fallback : value.getAsLong();
    }

    private static double doubleOf(JsonObject json, String key, double fallback) {
        JsonElement value = json.get(key);
        return value == null || value.isJsonNull() ? fallback : value.getAsDouble();
    }

    private static boolean boolOf(JsonObject json, String key, boolean fallback) {
        JsonElement value = json.get(key);
        return value == null || value.isJsonNull() ? fallback : value.getAsBoolean();
    }
}
