package dev.mumu.betternether.worldgen;

import com.google.gson.JsonObject;
import dev.mumu.betternether.terrain.CraftEngineLookup;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 上游七个「形状是算法」的植被特征，逐行移植。
 *
 * <h2>为什么这些不能写成 JSON</h2>
 * 上游 {@code org.betterx.betternether.world.features} 里这几个类是 Java 算法而不是配置：
 * {@code WartCapFeature} 先按 {@code x² + 6y² + z² ≤ r²} 建一个三维椭球、再"挖一遍壳"；
 * 三个 {@code *BushFeature} 用 {@code nextFloat()} 决定层数与每格是否落叶子；
 * {@code Lucis} / {@code SoulLily} / {@code JellyfishMushroom} 都是读邻居的分支生长。
 * 用 {@code random_patch} / {@code block_column} 之类去逼近只能得到"看起来差不多"的随机分布，
 * 不是同一个形状、也不是同一串随机数。所以这里保留算法本体，只把"写方块"接到
 * {@link WorldView} 上，由装载器当成一个 {@link Feature} 使用。
 *
 * <h2>随机数：个数与顺序都必须是上游的</h2>
 * 每个 {@code world.random().nextXxx()} 的位置都与上游源码一一对应，包括：
 * <ul>
 *   <li>三个 bush 的每格一次 {@code nextFloat()}（判据 {@code sqx+sqz < r² + nextFloat()*r}
 *       的右半边**总是**会被求值，所以"短路掉这次抽样"是不允许的优化）；</li>
 *   <li>{@code wart_cap} 只有开头一次 {@code nextInt(3)}；</li>
 *   <li>{@code soul_lily} 一次 {@code nextInt(RANDOM_BOUND)}；
 *       {@code jellyfish_mushroom} 一次 {@code nextBoolean()}；{@code lucis} 一次 {@code nextInt(3)}。</li>
 * </ul>
 *
 * <h2>{@code MAX_HEIGHT} 的换算</h2>
 * 上游 {@code ContextFeature#place} 传的是 {@code generator.getGenDepth()}，即"这个世界有多少层 y"：
 * 原版下界是 {@code 0..127}，所以 {@code genDepth = 128}。
 * 这里取 {@code world.maxY() - world.minY() + 1} —— 本移植的生成器同样是 {@code minY = 0}、
 * 天花板 {@code CEILING_Y = 127}，因此同样得 128。
 *
 * <p><b>不能写成 256</b>：{@code wart_cap} 的 {@code y ∈ [MAX_HEIGHT*0.25, MAX_HEIGHT*0.45]}
 * 与 {@code soul_lily} 的 {@code RANDOM_BOUND = 6 * MAX_HEIGHT / 128} 都直接吃这个数，
 * 按两倍高度算会把阈值整体推到两倍 y 上（"处处为真/处处为假"的静默形状错位）。
 *
 * <p><b>前提：绝对 y 与 {@code MAX_HEIGHT} 混用的两处</b> ——
 * {@code wart_cap} 的高度窗口与 {@code soul_lily} 的 {@code y > 31}，上游就是拿**绝对 y**
 * 与一个"高度比例"或常量比较（它假定 minY=0）。我保持原样：改写成"相对世界底部"会让
 * 0..127 的世界里数值变了，那不是移植而是改设定。若将来把维度改成负 minY，
 * 这两处必须跟着改成 {@code y - world.minY()}，否则门槛会整体平移。
 *
 * <h2>CraftEngine 下无法表达的三件事（每条都在使用处标注）</h2>
 * <ol>
 *   <li><b>属性被资源包折叠</b>：上游这些方块都有状态属性（{@code soul_lily} 的 {@code shape}、
 *       {@code wart_seed} 的 {@code facing}、{@code rubeus_bark} 的 {@code shape}、
 *       {@code willow_leaves} 的 {@code facing}、叶子们的 {@code persistent}），而当前 CE 包里
 *       它们都是 {@code state: auto_state: solid}（{@code soul_lily}/{@code wart_seed} 是
 *       {@code auto_state: {type: sugar_cane}}），也就是**没有这些属性**。这里仍然按上游属性名
 *       原样调用 {@link CraftEngineLookup#lookup(String, Map)}（属性不被支持时它退回默认状态），
 *       所以包一旦补上属性，本类无需改动就自动正确。</li>
 *   <li><b>按类/按 tag 认方块</b>：CE 把自定义方块映射到某个原版宿主状态，同一个
 *       {@code auto_state} 池里的方块通常共享宿主材质，所以"这个方块是不是
 *       {@code BlockNetherGrass}／是不是 nylium tag／是不是锚林木"这类判断无法用
 *       {@link BlockData#getMaterial()} 可靠回答。受影响的三处：
 *       {@code willow_bush} 的"覆盖草"分支（放弃，只覆盖空气）、
 *       {@code lucis} 的"紧贴锚树原木"分支（放弃，只认下界岩）、
 *       {@code jellyfish_mushroom} 的 nylium 判据（含平菇草，可能放宽）。</li>
 *   <li><b>读不到方块属性</b>：{@link WorldView} 只暴露材质，所以 {@code lucis} 第二次生长分支里
 *       "上一株 {@code lucis_spore} 朝哪边"读不到。该分支的触发条件（世界里有 {@code lucis_spore}）
 *       在当前资源包里根本不成立，见 {@link #loadNotes()} 的输出。</li>
 * </ol>
 *
 * <h2>只覆盖空气/可替换</h2>
 * 上游用 {@code world.isEmptyBlock} / {@code canBeReplaced()} 当判据、{@code setWithoutUpdate}
 * 当真写。这里 {@code empty()} 对应前者、{@link BlockPredicates#replaceable} 对应后者；
 * 越界一律交给 {@link WorldView#setBlock} 裁切，绝不直接碰 Bukkit {@code Block}。
 *
 * <p><b>接线约定</b>：本类不注册任何东西。装载器按 configured-feature 的 {@code type} 询问
 * {@link #supportedTypes()}，命中就调 {@link #create(String, JsonObject)}。
 */
public final class UpstreamVegetationFeatures {
    /**
     * 上游 configured-feature 的 type 全名，来源 {@code registry/NetherFeatures.java} 里的
     * {@code register(BN.id(...), ...)}。
     *
     * <p>注意 {@code NetherSakuraBushFeature} 的 type 是 {@code sakura_bush}，
     * 不是 {@code nether_sakura_bush}；方块 id 才叫 {@code nether_sakura_*}。
     */
    public static final Set<String> SUPPORTED = Set.of(
            "betternether:wart_cap",
            "betternether:rubeus_bush",
            "betternether:sakura_bush",
            "betternether:wart_bush",
            "betternether:willow_bush",
            "betternether:lucis",
            "betternether:soul_lily",
            "betternether:jellyfish_mushroom"
    );

    /**
     * {@code wover} 的 {@code BlockProperties.TRIPLE_SHAPE} 属性名。
     *
     * <p>已核对（WorldWeaver {@code release/26.3}，
     * {@code wover-block-api/.../block/api/BlockProperties.java}）：
     * {@code EnumProperty.create("shape", TripleShape.class)}，取值 {@code top/middle/bottom}。
     * 当前 CE 包里 {@code rubeus_bark} 没有任何属性，所以这个名字暂时落不到实处；
     * 留着是为了包补上属性时只改这一处。
     */
    private static final String TRIPLE_SHAPE = "shape";

    /** 装载期发现的问题（缺方块、属性退回默认…）。装载器可以选择并入自己的 notes。 */
    private static final List<String> NOTES = Collections.synchronizedList(new ArrayList<>());

    private UpstreamVegetationFeatures() {
    }

    /** 本类支持的 type 全名。装载器据此决定是否继续 skip。 */
    public static Set<String> supportedTypes() {
        return SUPPORTED;
    }

    /**
     * 装载期发现的问题。空表 = 一切照预期解析。
     *
     * <p>刻意做成静态表而不是往控制台写：装载器已经有一套 notes 通道，这里只提供素材。
     */
    public static List<String> loadNotes() {
        return List.copyOf(NOTES);
    }

    // ---------------------------------------------------------------- 入口

    /** 方块解析：{@code id + 属性} → 方块状态。测试注入假实现，运行期走 CraftEngine。 */
    public interface BlockResolver {
        @Nullable
        BlockData resolve(String id, Map<String, String> properties);

        /**
         * 这些属性里哪些**没有被方块定义**（会被静默丢掉）。
         *
         * <p>默认实现返回空 —— 「解析器没能力判断」不能当成「属性全都定义了」，所以调用方
         * 只在**明确知道**时才用它报缺失。真实现走
         * {@link dev.mumu.betternether.terrain.CraftEngineLookup#missingProperties}。
         *
         * <p>为什么不做成「解析后比对 {@code BlockData#getAsString()}」：CE 的
         * {@code getAsString()} 返回的是**原版方块状态**（{@code craftengine:custom_8520}），
         * CE 的内部属性名本来就不在里面 —— 拿它比对会把每个属性都判成缺失（线上实测刷了 60 条假 WARN，
         * 而缓存里 {@code [shape=bottom] → 8520} 明明是对的）。
         */
        default java.util.List<String> missingProperties(String id, Map<String, String> properties) {
            return java.util.List.of();
        }
    }

    /**
     * 建一个特征；{@code null} 表示这个 type 不归本类管（装载器应继续走自己的分支）。
     *
     * <p>{@code json} 只用于确认 type：上游这八个特征全是 {@code MapCodec.unit(...)}，
     * 配置特征是空的，没有任何字段可读。
     */
    public static @Nullable Feature create(String configuredType, @Nullable JsonObject json) {
        return create(configuredType, json, new BlockResolver() {
            @Override
            public @Nullable BlockData resolve(String id, Map<String, String> properties) {
                return UpstreamVegetationFeatures.resolve(id, properties);
            }

            @Override
            public java.util.List<String> missingProperties(String id, Map<String, String> properties) {
                return dev.mumu.betternether.terrain.CraftEngineLookup.missingProperties(id, properties);
            }
        });
    }

    /**
     * 同上，但方块解析可注入 —— 单元测试用它避开 CraftEngine 与服务器注册表。
     */
    public static @Nullable Feature create(String configuredType, @Nullable JsonObject json,
                                           BlockResolver resolver) {
        Feature.Custom.Placer placer = switch (configuredType) {
            case "betternether:wart_cap" -> wartCap(resolver);
            case "betternether:rubeus_bush" -> rubeusBush(resolver);
            case "betternether:sakura_bush" -> sakuraBush(resolver);
            case "betternether:wart_bush" -> wartBush(resolver);
            case "betternether:willow_bush" -> willowBush(resolver);
            case "betternether:lucis" -> lucis(resolver);
            case "betternether:soul_lily" -> soulLily(resolver);
            case "betternether:jellyfish_mushroom" -> jellyfishMushroom(resolver);
            default -> null;
        };
        return placer == null ? null : new Feature.Custom(configuredType, placer);
    }

    // ---------------------------------------------------------------- 各特征的状态

    private static Feature.Custom.Placer wartCap(BlockResolver resolver) {
        BlockData inside = resolver.resolve("minecraft:red_mushroom_block", props(
                "north", "false", "south", "false", "east", "false",
                "west", "false", "up", "false", "down", "false"));
        BlockData skin = resolver.resolve("minecraft:nether_wart_block", Map.of());
        return new WartCap(inside, skin);
    }

    private static Feature.Custom.Placer rubeusBush(BlockResolver resolver) {
        BlockData blob = resolver.resolve("betternether:rubeus_leaves", props("persistent", "true"));
        BlockData cap = resolver.resolve("betternether:rubeus_leaves", props("distance", "1"));
        BlockData trunk = resolver.resolve("betternether:rubeus_bark", props(TRIPLE_SHAPE, "middle"));
        return new Bush(3.0F, 1.0F, (dx, dy, dz) -> blob, caps(cap),
                Target.AIR, trunk, "rubeus_bush");
    }

    private static Feature.Custom.Placer sakuraBush(BlockResolver resolver) {
        BlockData blob = resolver.resolve("betternether:nether_sakura_leaves", props("persistent", "true"));
        BlockData cap = resolver.resolve("betternether:nether_sakura_leaves", props("distance", "1"));
        BlockData trunk = resolver.resolve("betternether:nether_sakura_bark", Map.of());
        return new Bush(1.5F, 0.5F, (dx, dy, dz) -> blob, caps(cap),
                Target.REPLACEABLE, trunk, "sakura_bush");
    }

    private static Feature.Custom.Placer willowBush(BlockResolver resolver) {
        // 每片叶子带上"从树心朝哪边"的 facing：上游按 |dx|/|dy|/|dz| 的最大者选轴，
        // 再按正负选方向，顺序是 X → Y → Z（见 Bush 里的 maxAxis）。
        BlockBlob blob = (dx, dy, dz) -> resolver.resolve("betternether:willow_leaves", props(
                "facing", maxAxis(dx, dy, dz), "persistent", "true"));
        BlockData[] caps = new BlockData[]{
                resolver.resolve("betternether:willow_leaves", props("facing", "up", "distance", "1")),
                resolver.resolve("betternether:willow_leaves", props("facing", "north", "distance", "1")),
                resolver.resolve("betternether:willow_leaves", props("facing", "south", "distance", "1")),
                resolver.resolve("betternether:willow_leaves", props("facing", "east", "distance", "1")),
                resolver.resolve("betternether:willow_leaves", props("facing", "west", "distance", "1"))
        };
        BlockData trunk = resolver.resolve("betternether:willow_bark", Map.of());
        return new Bush(2.0F, 0.5F, blob, caps, Target.AIR, trunk, "willow_bush");
    }

    private static Feature.Custom.Placer wartBush(BlockResolver resolver) {
        BlockData body = resolver.resolve("minecraft:nether_wart_block", Map.of());
        // 顺序与上游 DIRS 一致：UP, NORTH, SOUTH, EAST, WEST。
        BlockData[] seeds = new BlockData[]{
                resolver.resolve("betternether:wart_seed", props("facing", "up")),
                resolver.resolve("betternether:wart_seed", props("facing", "north")),
                resolver.resolve("betternether:wart_seed", props("facing", "south")),
                resolver.resolve("betternether:wart_seed", props("facing", "east")),
                resolver.resolve("betternether:wart_seed", props("facing", "west"))
        };
        return new WartBush(body, seeds);
    }

    private static Feature.Custom.Placer lucis(BlockResolver resolver) {
        BlockData center = resolver.resolve("betternether:lucis_mushroom", props("shape", "center"));
        BlockData[] sides = new BlockData[]{
                resolver.resolve("betternether:lucis_mushroom", props("shape", "side", "facing", "north")),
                resolver.resolve("betternether:lucis_mushroom", props("shape", "side", "facing", "south")),
                resolver.resolve("betternether:lucis_mushroom", props("shape", "side", "facing", "east")),
                resolver.resolve("betternether:lucis_mushroom", props("shape", "side", "facing", "west"))
        };
        // 四个角共用同一组状态：两个生长分支里都是 facing = SOUTH / EAST / WEST / NORTH。
        BlockData[] corners = new BlockData[]{
                resolver.resolve("betternether:lucis_mushroom", props("shape", "corner", "facing", "south")),
                resolver.resolve("betternether:lucis_mushroom", props("shape", "corner", "facing", "east")),
                resolver.resolve("betternether:lucis_mushroom", props("shape", "corner", "facing", "west")),
                resolver.resolve("betternether:lucis_mushroom", props("shape", "corner", "facing", "north"))
        };
        BlockData spore = resolver.resolve("betternether:lucis_spore", Map.of());
        return new Lucis(center, sides, corners, spore == null ? null : spore.getMaterial());
    }

    private static Feature.Custom.Placer soulLily(BlockResolver resolver) {
        BlockData small = resolver.resolve("betternether:soul_lily", Map.of());
        BlockData mediumBottom = resolver.resolve("betternether:soul_lily", props("shape", "medium_bottom"));
        BlockData mediumTop = resolver.resolve("betternether:soul_lily", props("shape", "medium_top"));
        BlockData bigBottom = resolver.resolve("betternether:soul_lily", props("shape", "big_bottom"));
        BlockData bigMiddle = resolver.resolve("betternether:soul_lily", props("shape", "big_middle"));
        BlockData bigTopCenter = resolver.resolve("betternether:soul_lily", props("shape", "big_top_center"));
        // 四个侧瓣：north → _S、south → _N、east → _W、west → _E（上游就是这么交叉的）。
        BlockData bigTopSideS = resolver.resolve("betternether:soul_lily", props("shape", "big_top_side_s"));
        BlockData bigTopSideN = resolver.resolve("betternether:soul_lily", props("shape", "big_top_side_n"));
        BlockData bigTopSideW = resolver.resolve("betternether:soul_lily", props("shape", "big_top_side_w"));
        BlockData bigTopSideE = resolver.resolve("betternether:soul_lily", props("shape", "big_top_side_e"));
        return new SoulLily(small, mediumBottom, mediumTop, bigBottom, bigMiddle, bigTopCenter,
                bigTopSideN, bigTopSideS, bigTopSideE, bigTopSideW);
    }

    private static Feature.Custom.Placer jellyfishMushroom(BlockResolver resolver) {
        BlockData[][] states = new BlockData[JellyfishMushroom.Visual.values().length]
                [JellyfishMushroom.Shape.values().length];
        for (JellyfishMushroom.Visual visual : JellyfishMushroom.Visual.values()) {
            for (JellyfishMushroom.Shape shape : JellyfishMushroom.Shape.values()) {
                states[visual.ordinal()][shape.ordinal()] = resolver.resolve(
                        "betternether:jellyfish_mushroom",
                        props("shape", shape.id, "visual", visual.id));
            }
        }
        BlockData mushroomGrass = resolver.resolve("betternether:mushroom_grass", Map.of());
        BlockData sepiaGrass = resolver.resolve("betternether:sepia_mushroom_grass", Map.of());
        return new JellyfishMushroom(states,
                mushroomGrass == null ? null : mushroomGrass.getMaterial(),
                sepiaGrass == null ? null : sepiaGrass.getMaterial());
    }

    // ---------------------------------------------------------------- 形状实现

    /**
     * {@code WartCapFeature}：贴着下界砖墙长出来的一顶巨菇伞。
     *
     * <p>三步：按 {@code x² + 6y² + z² ≤ r²} 填一个半椭球 → 把"有邻居空缺"的方块改成表皮
     * → 逐格只往空气里写。半径 {@code 3 + nextInt(3)}，{@code y1 = r/2}，高度只有 {@code y1+1} 层。
     */
    static final class WartCap implements Feature.Custom.Placer {
        private final BlockData inside;
        private final BlockData skin;

        WartCap(@Nullable BlockData inside, @Nullable BlockData skin) {
            this.inside = inside;
            this.skin = skin;
        }

        @Override
        public boolean place(WorldView world, Point point) {
            if (inside == null || skin == null) {
                return false;
            }
            int maxHeight = depth(world);
            if (!isWall(world, point)
                    || point.y() > maxHeight * 0.45
                    || point.y() < maxHeight * 0.25
                    || !empty(world, point.x(), point.y() - 3, point.z())) {
                return false;
            }

            int radius = 3 + world.random().nextInt(3);
            int r2 = radius * radius;
            int side = radius * 2 + 1;
            int y1 = radius >> 1;
            BlockData[][][] shape = new BlockData[side][y1 + 1][side];

            for (int y = 0; y <= y1; y++) {
                for (int x = -radius; x <= radius; x++) {
                    int sx = x + radius;
                    for (int z = -radius; z <= radius; z++) {
                        int sz = z + radius;
                        int d = x * x + y * y * 6 + z * z;
                        if (d <= r2) {
                            boolean surface = y == y1 || x == -radius || x == radius
                                    || z == -radius || z == radius;
                            shape[sx][y][sz] = surface ? skin : inside;
                        }
                    }
                }
            }

            // 挖壳：任何一个"缺口邻居"旁边的实心格改成表皮，这样伞面才有厚度感。
            for (int y = 0; y < y1; y++) {
                for (int x = 1; x < side - 1; x++) {
                    for (int z = 1; z < side - 1; z++) {
                        if (shape[x][y][z] == null) {
                            continue;
                        }
                        if (shape[x - 1][y][z] == null || shape[x + 1][y][z] == null
                                || shape[x][y][z - 1] == null || shape[x][y][z + 1] == null
                                || shape[x][y + 1][z] == null) {
                            shape[x][y][z] = skin;
                        }
                    }
                }
            }

            for (int y = 0; y <= y1; y++) {
                for (int x = 0; x < side; x++) {
                    for (int z = 0; z < side; z++) {
                        BlockData state = shape[x][y][z];
                        if (state == null) {
                            continue;
                        }
                        int wx = point.x() + x - radius;
                        int wy = point.y() + y;
                        int wz = point.z() + z - radius;
                        if (empty(world, wx, wy, wz)) {
                            world.setBlock(wx, wy, wz, state);
                        }
                    }
                }
            }
            return true;
        }

        /** 上游 {@code isWall}：四个水平方向里有一个是下界砖就算。顺序 NORTH,EAST,SOUTH,WEST。 */
        private static boolean isWall(WorldView world, Point point) {
            return isBlock(world, point.x(), point.y(), point.z() - 1, "minecraft:nether_bricks", Material.NETHER_BRICKS)
                    || isBlock(world, point.x() + 1, point.y(), point.z(), "minecraft:nether_bricks", Material.NETHER_BRICKS)
                    || isBlock(world, point.x(), point.y(), point.z() + 1, "minecraft:nether_bricks", Material.NETHER_BRICKS)
                    || isBlock(world, point.x() - 1, point.y(), point.z(), "minecraft:nether_bricks", Material.NETHER_BRICKS);
        }
    }

    /** 落点判据：空气，还是可替换（空气/液体）。 */
    enum Target {
        AIR,
        REPLACEABLE
    }

    /** 一片叶子该用什么状态（willow 按方位分状态，另两个 bush 恒定）。 */
    interface BlockBlob {
        @Nullable
        BlockData at(int dx, int dy, int dz);
    }

    /**
     * 三个 {@code *BushFeature} 共用的形状：半径由 {@code nextFloat()*spread + base} 决定，
     * 每层半径向下取整递减，层内逐格用 {@code sqx+sqz < r² + nextFloat()*r} 判定。
     *
     * <p>三个上游类的差别只在：{@code spread/base}、叶子状态（willow 带 facing）、
     * 以及 {@code setIfAir} 的判据（rubeus 只覆盖空气、sakura 覆盖可替换、willow 还覆盖草）。
     * 上游三份代码的循环结构完全一致，所以合成一个实现、用参数区分 —— 分开抄三遍只会让
     * "三份随机数顺序"以后各自漂移。
     *
     * <p>{@code target} 的 {@link Target#AIR} 分支同时承载 willow：上游那边多一个
     * "覆盖 {@code BlockNetherGrass}"的判断，CE 下无法识别方块类（见类注释），所以按
     * 只覆盖空气处理 —— 少覆盖几片草，不会把草写成叶子。
     */
    static final class Bush implements Feature.Custom.Placer {
        private final float spread;
        private final float base;
        private final BlockBlob blob;
        /** 树心正上方与四个水平邻居的状态，顺序 up,north,south,east,west。 */
        private final BlockData[] caps;
        private final Target target;
        private final BlockData trunk;
        private final String name;

        Bush(float spread, float base, BlockBlob blob, BlockData[] caps, Target target,
             @Nullable BlockData trunk, String name) {
            this.spread = spread;
            this.base = base;
            this.blob = blob;
            this.caps = caps;
            this.target = target;
            this.trunk = trunk;
            this.name = name;
            if (trunk == null) {
                note(name + " 的树干方块无法解析，该特征不会落任何方块");
            }
        }

        @Override
        public boolean place(WorldView world, Point point) {
            // 上游：pos 与上面一格必须是空气，15 格高处也必须是空气（避免长进天花板）。
            if (!empty(world, point.x(), point.y(), point.z())
                    || !empty(world, point.x(), point.y() + 1, point.z())
                    || !empty(world, point.x(), point.y() + 15, point.z())) {
                return false;
            }
            if (trunk == null) {
                return false;
            }
            Random random = world.random();
            float r = random.nextFloat() * spread + base;
            int count = (int) r;

            for (int i = 0; i < count; i++) {
                float fr = r - i;
                int ir = (int) Math.ceil(fr);
                float r2 = fr * fr;
                int x1 = point.x() - ir;
                int x2 = point.x() + ir;
                int z1 = point.z() - ir;
                int z2 = point.z() + ir;
                for (int x = x1; x < x2; x++) {
                    int sqx = x - point.x();
                    sqx *= sqx;
                    for (int z = z1; z < z2; z++) {
                        int sqz = z - point.z();
                        sqz *= sqz;
                        // nextFloat() 必须无条件消耗：上游的右半边总会求值。
                        if (sqx + sqz < r2 + random.nextFloat() * r) {
                            int dy = i;
                            BlockData state = blob.at(x - point.x(), dy, z - point.z());
                            if (state != null) {
                                setIfAllowed(world, x, point.y() + dy, z, state);
                            }
                        }
                    }
                }
            }

            placeTrunkAndCap(world, point);
            return true;
        }

        private void placeTrunkAndCap(WorldView world, Point point) {
            // 上游 setWithoutUpdate：树干是无条件写的（pos 开头已经确认是空气）。
            if (world.inRegion(point.x(), point.y(), point.z())) {
                world.setBlock(point.x(), point.y(), point.z(), trunk);
            }
            setIfAllowed(world, point.x(), point.y() + 1, point.z(), caps[0]);
            setIfAllowed(world, point.x(), point.y(), point.z() - 1, caps[1]);
            setIfAllowed(world, point.x(), point.y(), point.z() + 1, caps[2]);
            setIfAllowed(world, point.x() + 1, point.y(), point.z(), caps[3]);
            setIfAllowed(world, point.x() - 1, point.y(), point.z(), caps[4]);
        }

        private boolean setIfAllowed(WorldView world, int x, int y, int z, @Nullable BlockData state) {
            if (state == null) {
                return false;
            }
            boolean allowed = target == Target.REPLACEABLE
                    ? BlockPredicates.replaceable(world, x, y, z)
                    : empty(world, x, y, z);
            if (!allowed) {
                return false;
            }
            world.setBlock(x, y, z, state);
            return true;
        }
    }

    /**
     * {@code WartBushFeature}：一格下界疣块 + 上下左右四个方向的疣芽。
     *
     * <p>{@code NetherWoodBlocks.MAT_WART.getSeed()} 就是 {@code betternether:wart_seed}，
     * 每个芽的 {@code facing} 指向它自己所在的那一侧（正上方那支是 {@code up}）。
     */
    static final class WartBush implements Feature.Custom.Placer {
        private final BlockData body;
        private final BlockData[] seeds;

        WartBush(@Nullable BlockData body, BlockData[] seeds) {
            this.body = body;
            this.seeds = seeds;
        }

        @Override
        public boolean place(WorldView world, Point point) {
            if (body == null) {
                return false;
            }
            int x = point.x();
            int y = point.y();
            int z = point.z();
            if (!empty(world, x, y, z)) {
                return false;
            }
            world.setBlock(x, y, z, body);
            // 上游 DIRS：UP, NORTH, SOUTH, EAST, WEST。
            trySeed(world, x, y + 1, z, seeds[0]);
            trySeed(world, x, y, z - 1, seeds[1]);
            trySeed(world, x, y, z + 1, seeds[2]);
            trySeed(world, x + 1, y, z, seeds[3]);
            trySeed(world, x - 1, y, z, seeds[4]);
            return true;
        }

        private static void trySeed(WorldView world, int x, int y, int z, @Nullable BlockData seed) {
            if (seed != null && empty(world, x, y, z)) {
                world.setBlock(x, y, z, seed);
            }
        }
    }

    /**
     * {@code LucisFeature}：贴地的发光小蘑菇，按 {@code nextInt(3)} 分成"一整片"和"一个角"两种。
     *
     * <p>能否生长：四个水平邻居里有下界岩（上游还接受锚树原木，CE 下认不出来，见类注释）。
     * 可覆盖：空气/可替换，或上一株 {@code lucis_spore}。
     */
    static final class Lucis implements Feature.Custom.Placer {
        private final BlockData center;
        private final BlockData[] sides;
        private final BlockData[] corners;
        private final @Nullable Material sporeMaterial;

        Lucis(@Nullable BlockData center, BlockData[] sides, BlockData[] corners,
              @Nullable Material sporeMaterial) {
            this.center = center;
            this.sides = sides;
            this.corners = corners;
            this.sporeMaterial = sporeMaterial;
            if (center == null) {
                note("betternether:lucis_mushroom 无法解析，betternether:lucis 不会落任何方块"
                        + "（当前 CE 包里没有这个方块 id，只有 betternether:giant_lucis）");
            }
        }

        @Override
        public boolean place(WorldView world, Point point) {
            if (!canGenerate(world, point)) {
                return false;
            }
            if (world.random().nextInt(3) == 0) {
                growPatch(world, point);
            } else {
                growCorner(world, point);
            }
            return true;
        }

        /** 一整个「中心 + 四边 + 四角」的伞面。 */
        private void growPatch(WorldView world, Point point) {
            int x = point.x();
            int y = point.y();
            int z = point.z();
            put(world, x, y, z, center);
            put(world, x, y, z - 1, sides[0]);
            put(world, x, y, z + 1, sides[1]);
            put(world, x + 1, y, z, sides[2]);
            put(world, x - 1, y, z, sides[3]);
            put(world, x + 1, y, z - 1, corners[0]);
            put(world, x - 1, y, z - 1, corners[1]);
            put(world, x + 1, y, z + 1, corners[2]);
            put(world, x - 1, y, z + 1, corners[3]);
        }

        /**
         * 只有一角的四格。
         *
         * <p>上游先把原点挪到"空的一侧"，其中一半分支读的是它上一株 {@code lucis_spore} 的
         * {@code facing}。这里的问题在于 {@link WorldView} 只暴露材质，读不到朝向 —— 所以
         * 当在场方块确实是孢子时，按"不挪"处理（与 facing=UP 等价）。当前资源包里没有
         * {@code lucis_spore} 方块，这条分支事实上走不到，见 {@link #loadNotes()}。
         */
        private void growCorner(WorldView world, Point point) {
            int x = point.x();
            int y = point.y();
            int z = point.z();
            boolean atSpore = is(world, x, y, z, sporeMaterial);
            if (!atSpore) {
                if (!air(world, x, y, z - 1)) {
                    z -= 1;
                } else if (!air(world, x + 1, y, z)) {
                    x += 1;
                }
            }
            put(world, x, y, z, corners[0]);
            put(world, x - 1, y, z, corners[1]);
            put(world, x, y, z + 1, corners[2]);
            put(world, x - 1, y, z + 1, corners[3]);
        }

        private void put(WorldView world, int x, int y, int z, @Nullable BlockData state) {
            if (state == null || !canReplace(world, x, y, z)) {
                return;
            }
            world.setBlock(x, y, z, state);
        }

        private boolean canReplace(WorldView world, int x, int y, int z) {
            return is(world, x, y, z, sporeMaterial) || BlockPredicates.replaceable(world, x, y, z);
        }

        private static boolean canGenerate(WorldView world, Point point) {
            int x = point.x();
            int y = point.y();
            int z = point.z();
            // 上游顺序：NORTH, EAST, SOUTH, WEST。锚树原木那一半认不出来，只认下界岩。
            return isBlock(world, x, y, z - 1, "minecraft:netherrack", Material.NETHERRACK)
                    || isBlock(world, x + 1, y, z, "minecraft:netherrack", Material.NETHERRACK)
                    || isBlock(world, x, y, z + 1, "minecraft:netherrack", Material.NETHERRACK)
                    || isBlock(world, x - 1, y, z, "minecraft:netherrack", Material.NETHERRACK);
        }
    }

    /**
     * {@code SoulLilyFeature}：灵魂沙上的灵魂兰，三档大小。
     *
     * <p>第一段是「在这一列里往下扫一小段，找一块能长的地方」：
     * {@code y = pos.y + nextInt(RANDOM_BOUND)}，再往下试 {@code RANDOM_BOUND} 次，
     * 只接受「脚下是灵魂沙 + 自己是空气」且 {@code y > 31}（上游的绝对高度门槛）。
     * 第二段按上方空间选小/中/大：上方是空气才可能中/大，上方第二格与四个侧面都空才是大。
     */
    static final class SoulLily implements Feature.Custom.Placer {
        private final BlockData small;
        private final BlockData mediumBottom;
        private final BlockData mediumTop;
        private final BlockData bigBottom;
        private final BlockData bigMiddle;
        private final BlockData bigTopCenter;
        private final BlockData bigTopSideN;
        private final BlockData bigTopSideS;
        private final BlockData bigTopSideE;
        private final BlockData bigTopSideW;

        SoulLily(@Nullable BlockData small, @Nullable BlockData mediumBottom,
                 @Nullable BlockData mediumTop, @Nullable BlockData bigBottom,
                 @Nullable BlockData bigMiddle, @Nullable BlockData bigTopCenter,
                 @Nullable BlockData bigTopSideN, @Nullable BlockData bigTopSideS,
                 @Nullable BlockData bigTopSideE, @Nullable BlockData bigTopSideW) {
            this.small = small;
            this.mediumBottom = mediumBottom;
            this.mediumTop = mediumTop;
            this.bigBottom = bigBottom;
            this.bigMiddle = bigMiddle;
            this.bigTopCenter = bigTopCenter;
            this.bigTopSideN = bigTopSideN;
            this.bigTopSideS = bigTopSideS;
            this.bigTopSideE = bigTopSideE;
            this.bigTopSideW = bigTopSideW;
        }

        @Override
        public boolean place(WorldView world, Point point) {
            if (small == null) {
                return false;
            }
            float scaleFactor = depth(world) / 128.0F;
            int randomBound = (int) (6 * scaleFactor);
            // 上游这里没有下界保护：高度小于约 22 层时 randomBound 会变成 0，nextInt(0) 直接抛异常。
            // 移植不复制这个崩溃点，改成"这个高度下不长"。
            if (randomBound <= 0) {
                return false;
            }
            if (!isBlock(world, point.x(), point.y() - 1, point.z(), "minecraft:soul_sand", Material.SOUL_SAND)) {
                return false;
            }
            int x = point.x();
            int z = point.z();
            int y = point.y() + world.random().nextInt(randomBound);
            for (int j = 0; j < randomBound; j++) {
                int py = y - j;
                if (py > 31) {
                    if (isBlock(world, x, py - 1, z, "minecraft:soul_sand", Material.SOUL_SAND) && empty(world, x, py, z)) {
                        growTree(world, new Point(x, py, z));
                    }
                } else {
                    break;
                }
            }
            return true;
        }

        private boolean growTree(WorldView world, Point point) {
            int x = point.x();
            int y = point.y();
            int z = point.z();
            if (!isBlock(world, x, y - 1, z, "minecraft:soul_sand", Material.SOUL_SAND)) {
                return false;
            }
            if (empty(world, x, y + 1, z)) {
                int top = y + 2;
                if (empty(world, x, top, z) && isAirSides(world, x, top, z)) {
                    growBig(world, x, y, z);
                } else {
                    growMedium(world, x, y, z);
                }
            } else {
                growSmall(world, x, y, z);
            }
            return true;
        }

        private void growSmall(WorldView world, int x, int y, int z) {
            put(world, x, y, z, small);
        }

        private void growMedium(WorldView world, int x, int y, int z) {
            put(world, x, y, z, mediumBottom);
            put(world, x, y + 1, z, mediumTop);
        }

        private void growBig(WorldView world, int x, int y, int z) {
            put(world, x, y, z, bigBottom);
            put(world, x, y + 1, z, bigMiddle);
            int top = y + 2;
            put(world, x, top, z, bigTopCenter);
            put(world, x, top, z - 1, bigTopSideS);
            put(world, x, top, z + 1, bigTopSideN);
            put(world, x + 1, top, z, bigTopSideW);
            put(world, x - 1, top, z, bigTopSideE);
        }

        private static boolean isAirSides(WorldView world, int x, int y, int z) {
            return empty(world, x, y, z - 1) && empty(world, x, y, z + 1)
                    && empty(world, x + 1, y, z) && empty(world, x - 1, y, z);
        }

        /** 上游 setWithUpdate：无条件写（调用前已确认位置可长）。 */
        private static void put(WorldView world, int x, int y, int z, @Nullable BlockData state) {
            if (state != null) {
                world.setBlock(x, y, z, state);
            }
        }
    }

    /**
     * {@code JellyfishMushroomFeature}：菌岩上的水母菇，一档或两档。
     *
     * <p>{@code visual} 看脚下是什么菌草：平菇草 = {@code normal}、褐菌草 = {@code sepia}、
     * 其它 = {@code poor}。判据优先 {@link WorldView#blockId}（对 CE 方块是精确的 CE id），
     * 材质比较只是最后兜底。
     */
    static final class JellyfishMushroom implements Feature.Custom.Placer {
        /** {@code BNBlockProperties.JellyShape} 的序列化名。 */
        enum Visual {
            NORMAL("normal"),
            SEPIA("sepia"),
            POOR("poor");

            final String id;

            Visual(String id) {
                this.id = id;
            }
        }

        /** {@code wover} 的 {@code TripleShape}（水母菇只用 bottom/middle/top）。 */
        enum Shape {
            BOTTOM("bottom"),
            MIDDLE("middle"),
            TOP("top");

            final String id;

            Shape(String id) {
                this.id = id;
            }
        }

        private final BlockData[][] states;
        private final @Nullable Material mushroomGrass;
        private final @Nullable Material sepiaGrass;

        JellyfishMushroom(BlockData[][] states, @Nullable Material mushroomGrass,
                          @Nullable Material sepiaGrass) {
            this.states = states;
            this.mushroomGrass = mushroomGrass;
            this.sepiaGrass = sepiaGrass;
            if (states[Visual.NORMAL.ordinal()][Shape.BOTTOM.ordinal()] == null) {
                note("betternether:jellyfish_mushroom 无法解析，该特征不会落任何方块"
                        + "（当前 CE 包里没有这个方块 id，只有同名的树苗物品）");
            }
        }

        @Override
        public boolean place(WorldView world, Point point) {
            int x = point.x();
            int y = point.y();
            int z = point.z();
            if (!empty(world, x, y, z) || !isNylium(world, x, y - 1, z)) {
                return false;
            }
            growHere(world, point);
            return true;
        }

        private void growHere(WorldView world, Point point) {
            if (world.random().nextBoolean() && empty(world, point.x(), point.y() + 1, point.z())) {
                growMedium(world, point);
            } else {
                growSmall(world, point);
            }
        }

        private void growSmall(WorldView world, Point point) {
            Visual visual = visualBelow(world, point);
            set(world, point.x(), point.y(), point.z(), states[visual.ordinal()][Shape.BOTTOM.ordinal()]);
        }

        private void growMedium(WorldView world, Point point) {
            Visual visual = visualBelow(world, point);
            set(world, point.x(), point.y(), point.z(), states[visual.ordinal()][Shape.MIDDLE.ordinal()]);
            set(world, point.x(), point.y() + 1, point.z(), states[visual.ordinal()][Shape.TOP.ordinal()]);
        }

        private Visual visualBelow(WorldView world, Point point) {
            // 先按真实身份 id（原版与 CE 真实方块都精确）；问不到才退回材质。
            String id = world.blockId(point.x(), point.y() - 1, point.z());
            if (id != null) {
                if (id.equals("betternether:mushroom_grass")) {
                    return Visual.NORMAL;
                }
                if (id.equals("betternether:sepia_mushroom_grass")) {
                    return Visual.SEPIA;
                }
                return Visual.POOR;
            }
            Material below = materialAt(world, point.x(), point.y() - 1, point.z());
            if (mushroomGrass != null && below == mushroomGrass) {
                return Visual.NORMAL;
            }
            if (sepiaGrass != null && below == sepiaGrass) {
                return Visual.SEPIA;
            }
            return Visual.POOR;
        }

        /**
         * 上游判据是 {@code #minecraft:nylium}。
         *
         * <p>精确路径：{@link WorldView#inTag}（真实的原版 tag；CE 自定义方块只要在包里声明了
         * {@code tags:} 也会命中）。问不到（tag 不存在/CE 未就绪）才退回材质近似：原版菌岩，
         * 或两种平菇草。后者在 deceive 模式下与其它借宿主的方块共享材质，是一条**已知放宽**，
         * fallback 里写清楚。
         */
        private boolean isNylium(WorldView world, int x, int y, int z) {
            Boolean tagged = world.inTag("minecraft:nylium", x, y, z);
            if (tagged != null) {
                return tagged;
            }
            // 退回：先按身份 id（原版与 CE 真实方块都精确），最后才按材质。
            String id = world.blockId(x, y, z);
            if (id != null) {
                return id.equals("minecraft:crimson_nylium") || id.equals("minecraft:warped_nylium")
                        || id.equals("betternether:mushroom_grass")
                        || id.equals("betternether:sepia_mushroom_grass");
            }
            Material material = materialAt(world, x, y, z);
            if (material == null) {
                return false;
            }
            if (material == Material.CRIMSON_NYLIUM || material == Material.WARPED_NYLIUM) {
                return true;
            }
            return (mushroomGrass != null && material == mushroomGrass)
                    || (sepiaGrass != null && material == sepiaGrass);
        }

        /** 上游 setWithUpdate：无条件写（调用前已确认位置是空气）。 */
        private static void set(WorldView world, int x, int y, int z, @Nullable BlockData state) {
            if (state != null) {
                world.setBlock(x, y, z, state);
            }
        }
    }

    // ---------------------------------------------------------------- 小工具

    /**
     * 世界的层数，对应上游的 {@code generator.getGenDepth()}。
     *
     * <p>原版下界 {@code 0..127} → {@code genDepth = 128}；本移植的生成器同为
     * {@code minY = 0} / {@code CEILING_Y = 127}，所以这里同样得 128。
     * {@code wart_cap}/{@code soul_lily} 里与绝对 y 的比较照抄上游，只有 minY=0 时才逐位相同。
     */
    private static int depth(WorldView world) {
        return world.maxY() - world.minY() + 1;
    }

    /** 上游 {@code world.isEmptyBlock(pos)}：越界当作"不是空气"，于是整株不落。 */
    private static boolean empty(WorldView world, int x, int y, int z) {
        return world.inRegion(x, y, z) && world.isAir(x, y, z);
    }

    /** 上游 {@code state.isAir()}（不会因为越界而放宽）。 */
    private static boolean air(WorldView world, int x, int y, int z) {
        return empty(world, x, y, z);
    }

    private static @Nullable Material materialAt(WorldView world, int x, int y, int z) {
        BlockData data = world.blockAt(x, y, z);
        return data == null ? null : data.getMaterial();
    }

    /** 上游 {@code state.getBlock() == X}。{@code expected} 为 null（方块不存在）时恒假。 */
    private static boolean is(WorldView world, int x, int y, int z, @Nullable Material expected) {
        if (expected == null) {
            return false;
        }
        return materialAt(world, x, y, z) == expected;
    }

    /**
     * 原版方块的精确判定：优先问真实身份 id，问不到才退回材质。
     *
     * <p>为什么值得：CE 的 deceive-bukkit-material 会把自定义方块映射到某个原版 Material，
     * 所以「材质是不是 NETHERRACK」可能对一个长得像下界岩的**自定义**方块成立。问 id 没有这个问题
     * （原版方块的身份 id 永远精确）。
     */
    private static boolean isBlock(WorldView world, int x, int y, int z, String id, Material fallback) {
        String actual = world.blockId(x, y, z);
        if (actual != null) {
            return actual.equals(id);
        }
        return materialAt(world, x, y, z) == fallback;
    }

    /** 五份相同的帽子状态（rubeus / sakura 五个方向本来就是同一个状态）。 */
    private static BlockData[] caps(@Nullable BlockData state) {
        return new BlockData[]{state, state, state, state, state};
    }

    private static Map<String, String> props(String... keysAndValues) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keysAndValues.length; i += 2) {
            map.put(keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }

    /**
     * willow 叶子的朝向：取 {@code |dx|/|dy|/|dz|} 最大的那个轴，再按正负选方向。
     *
     * <p>顺序必须是 X → Y → Z：三轴相等时（例如原点 {@code (0,0,0)}）上游走 X 轴、
     * 然后因为 {@code dx} 不大于 0 而取 {@code west}。改顺序就会让树心那片叶子的朝向变。
     */
    static String maxAxis(int dx, int dy, int dz) {
        int ax = Math.abs(dx);
        int ay = Math.abs(dy);
        int az = Math.abs(dz);
        int max = Math.max(ax, Math.max(ay, az));
        if (max == ax) {
            return dx > 0 ? "east" : "west";
        }
        if (max == ay) {
            return dy > 0 ? "up" : "down";
        }
        return dz > 0 ? "south" : "north";
    }

    private static void note(String message) {
        NOTES.add(message);
    }

    // ---------------------------------------------------------------- 运行期方块解析

    /**
     * {@code id + 属性} → 方块状态。{@code betternether:} 走 CE 的带状态查询
     * （{@link CraftEngineLookup#lookup(String, Map)}，属性不被支持时它会退回默认状态）；
     * {@code minecraft:} 走 Bukkit 按属性构造 —— CE 那条路径对原版 id 会直接返回 null，
     * 而 {@code wart_cap} 的内芯正是靠 {@code red_mushroom_block} 六个面才能出蘑菇内部纹理。
     */
    static @Nullable BlockData resolve(String id, Map<String, String> properties) {
        if (id.startsWith("minecraft:")) {
            return vanilla(id, properties);
        }
        BlockData data = CraftEngineLookup.lookup(id, properties);
        if (data == null) {
            note("CraftEngine 未注册方块 " + id + "（相关放置会落空）");
        }
        return data;
    }

    private static @Nullable BlockData vanilla(String id, Map<String, String> properties) {
        Material material = Material.matchMaterial(id);
        if (material == null) {
            note("原版材质不存在：" + id);
            return null;
        }
        if (properties == null || properties.isEmpty()) {
            return material.createBlockData();
        }
        StringBuilder spec = new StringBuilder(id).append('[');
        boolean first = true;
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            if (!first) {
                spec.append(',');
            }
            first = false;
            spec.append(entry.getKey()).append('=').append(entry.getValue());
        }
        spec.append(']');
        try {
            return Bukkit.createBlockData(spec.toString());
        } catch (IllegalArgumentException error) {
            note("方块 " + spec + " 的属性无法解析，退回默认状态");
            return material.createBlockData();
        }
    }
}
