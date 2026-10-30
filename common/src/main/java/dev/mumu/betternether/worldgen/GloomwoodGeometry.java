package dev.mumu.betternether.worldgen;

import dev.mumu.betternether.terrain.OpenSimplexNoise;
import org.bukkit.block.data.BlockData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 幽光木（gloomwood）：上游 {@code world/tree} 树库 + {@code GloomwoodTreeFeature} 的逐行移植。
 *
 * <h2>为什么要连树库一起移植</h2>
 * 上游这棵树不是「画几个叶子球」，而是「先算出一整棵树的体素，再求解叶子衰减，最后一次性提交」。
 * 中间那一步（{@link LeafDecay}）会**改几何**：把树冠外缘够不到木头的叶子变成真正的支撑枝，
 * 或者剪掉。用任何现成的 {@link Feature} 类型都表达不了这个流程 —— 原版的 foliage placer
 * 是纯加法的，而这里冠层本身是「圆柱 ∪ 椭球穹顶 平滑并，再减去椭球裙摆」的减集形状。
 * 所以整个 {@code world/tree} 包按原算法搬过来，只把「世界」换成 {@link WorldView}。
 *
 * <h2>写区匹配</h2>
 * 上游用 wover {@code WriteZone}（{@code release/26.3},
 * {@code wover-feature-api/src/main/java/de/ambertation/wover/feature/api/WriteZone.java}，已读原文）
 * 把形状**收进**可写区域。这里复用同包 {@link UpstreamSimpleTreeFeatures.Zone}：那份实现逐行照抄了
 * {@code of/contains/headroom/fitRadius/toBoundingBox} 的算术，只把探测对象换成
 * {@link WorldView#inRegion}。{@link TreeSpace#fitSegment} 按官方 {@code fitSegment} 的
 * slab-clip 逐行补齐（上游把它写在 {@code WriteZone} 里，同包的 Zone 复刻未含）。
 *
 * <h2>随机消耗顺序</h2>
 * 逐处照抄，包括：
 * <ul>
 *   <li>{@code bleached} 在尺寸循环**之前**只抽一次（上游注释写明：树被降档重建时不该重抽）；</li>
 *   <li>{@code random.nextInt(3) == 0 ? Size.SMALL.ordinal() : 0} 决定起始档；</li>
 *   <li>{@code ForkingTrunk} 里 4 次 stem 采样 + jitter 的每点 3 次 + 每条臂的角度/伸展/高度各 1 次；</li>
 *   <li>{@code GhostCanopy} 每片冠层 1 次 {@code nextLong()}（裙摆噪声的 seed）；</li>
 *   <li>{@code LogBands} 的接缝高度用**列哈希**而不是随机数（上游明确这么做，否则同一列会被问
 *       多次而给出不同答案）。</li>
 * </ul>
 */
final class GloomwoodGeometry implements Feature.Custom.Placer {
    /** 上看天花板的最大距离。上游 {@code MAX_CLEARANCE_SCAN}。 */
    private static final int MAX_CLEARANCE_SCAN = 40;
    /** 树冠上方要留的空气。上游 {@code CROWN_MARGIN}。 */
    private static final int CROWN_MARGIN = 2;
    /** 多少分之一的树站在熔根上。上游 {@code MOLTEN_ROOT_CHANCE}。 */
    private static final int MOLTEN_ROOT_CHANCE = 2;
    /** 熔根/古残骸的水平半径。上游 {@code ROOT_RADIUS}。 */
    private static final int ROOT_RADIUS = 2;
    private static final int MIN_DEBRIS_DEPTH = 1;
    private static final int MAX_DEBRIS_DEPTH = 4;
    private static final float MOLTEN_COLUMN_CHANCE = 0.55F;

    private final UpstreamComplexTreeFeatures.BlockResolver resolver;
    private final float bleachedChance;
    private final int spacing;
    private final Palette palette;

    GloomwoodGeometry(UpstreamComplexTreeFeatures.BlockResolver resolver, float bleachedChance,
                      int spacing) {
        this.resolver = resolver;
        this.bleachedChance = bleachedChance;
        this.spacing = spacing;
        this.palette = new Palette();
    }

    // ================================================================ 上游 Size 枚举

    /**
     * 三档尺寸，最大在前。数值逐字来自上游 {@code GloomwoodTreeFeature.Size}：
     * 小树不是「只有树干矮」，冠层半径 / 身体高 / 穹顶 / 裙摆深度 / 眼睛标记全部按比例缩。
     *
     * <p>{@code clearance} 是「低于这个净空连试都不试」的预筛，刻意偏小（树干高度在区间内随机，
     * 太紧会把矮的那几棵也拒掉，而猜小的唯一代价是精确测量后丢掉）。
     */
    private enum Size {
        FULL(11, 16, 2, 3, 3.0F, 1.6F, 0.8F,
                4.0F, 11.0F, 4.0F, 5.5F, -4.0F, 2.0F, 1.5F, 0.7F,
                3.0F, 1.6F, 3.4F, 24, 5.0F, 7.5F),
        SMALL(7, 10, 2, 2, 2.2F, 1.2F, 0.6F,
                2.6F, 6.5F, 2.5F, 3.25F, -2.2F, 1.2F, 1.0F, 0.45F,
                2.0F, 1.1F, 2.2F, 15, 2.6F, 4.2F),
        TINY(3, 5, 2, 2, 1.2F, 0.8F, 0.45F,
                1.4F, 2.8F, 1.1F, 1.4F, -0.9F, 0.6F, 0.55F, 0.25F,
                1.0F, 0.6F, 1.2F, 12, 1.1F, 1.9F);

        final int minHeight, maxHeight, minArms, maxArms;
        final float spread, baseRadius, tipRadius;
        final float canopyRadius, bodyHeight, domeHeight, skirtDepth, verticalOffset, minRadius;
        final float smoothing, hemWobble;
        final float eyeHeight, eyeSeparation, eyeForward;
        final int clearance;
        final float darkUpTo, transitionUpTo;

        Size(int minHeight, int maxHeight, int minArms, int maxArms, float spread,
             float baseRadius, float tipRadius, float canopyRadius, float bodyHeight,
             float domeHeight, float skirtDepth, float verticalOffset, float minRadius,
             float smoothing, float hemWobble,
             float eyeHeight, float eyeSeparation, float eyeForward, int clearance,
             float darkUpTo, float transitionUpTo) {
            this.minHeight = minHeight;
            this.maxHeight = maxHeight;
            this.minArms = minArms;
            this.maxArms = maxArms;
            this.spread = spread;
            this.baseRadius = baseRadius;
            this.tipRadius = tipRadius;
            this.canopyRadius = canopyRadius;
            this.bodyHeight = bodyHeight;
            this.domeHeight = domeHeight;
            this.skirtDepth = skirtDepth;
            this.verticalOffset = verticalOffset;
            this.minRadius = minRadius;
            this.smoothing = smoothing;
            this.hemWobble = hemWobble;
            this.eyeHeight = eyeHeight;
            this.eyeSeparation = eyeSeparation;
            this.eyeForward = eyeForward;
            this.clearance = clearance;
            this.darkUpTo = darkUpTo;
            this.transitionUpTo = transitionUpTo;
        }
    }

    // ================================================================ place

    @Override
    public boolean place(WorldView world, Point point) {
        // 上游这是 NonOverlappingFeature#place 的两条前置：站在下界地面上，且附近没有同种原木。
        // 两条都不吃随机数，但会整棵跳过 —— 少一条密度就变。
        // 上游 GloomwoodTreeFeature#isGround = isNetherGround ∪ isSculkLike：
        // 它自己群系的地表大半是原版 sculk，只判下界地面会把它自己的群系拒掉。
        if (!UpstreamComplexTreeFeatures.sculkGround(world, point.x(), point.y() - 1, point.z())) {
            return false;
        }
        if (!UpstreamComplexTreeFeatures.noObjectNear(world, point, spacing,
                UpstreamComplexTreeFeatures.manhattan(spacing),
                palette.structureIds, palette.structure)) {
            return false;
        }

        final int headroom = clearanceAbove(world, point);
        final Size[] sizes = Size.values();
        // 只抽一次，且在尺寸循环之前：降档重建的是同一棵树，每次重抽会把实际漂白率偷偷抬高。
        final boolean bleached = bleachedChance > 0 && world.random().nextFloat() < bleachedChance;
        // 三分之一是小树，与可用空间无关 —— 否则矮档只会在低天花板下出现，开阔洞窟里全长成同一档。
        for (int i = world.random().nextInt(3) == 0 ? Size.SMALL.ordinal() : 0; i < sizes.length; i++) {
            final Size size = sizes[i];
            if (headroom < size.clearance) {
                continue;
            }

            final TreeBuilder builder = builder(world.random(), size, bleached);
            final TreeSpace space = TreeSpace.of(world, point);
            final TreeVoxels voxels = builder.build(space, world.random(), builder.filterFor(world, space));
            // 精确测量：树干是随机高的，预筛只能给下界；真正写之前按实际包围盒再判一次。
            if (voxels.minY() + voxels.sizeY() + CROWN_MARGIN > headroom) {
                continue;
            }

            if (builder.commit(world, space, voxels) > 0) {
                // 古残骸在熔根交换**之前**：它要找「这棵树自己的原木」来判定哪些列是树根，
                // 而熔根会把其中一些换成它不认识的第四种方块。
                if (bleached) {
                    ancientDebrisRoots(world, space, point, world.random());
                }
                if (world.random().nextInt(MOLTEN_ROOT_CHANCE) == 0) {
                    moltenRoot(world, space, point, world.random());
                }
                return true;
            }
        }
        return false;
    }

    /** 向上数连续可覆盖格，含基点自身，上限 {@link #MAX_CLEARANCE_SCAN}。 */
    private static int clearanceAbove(WorldView world, Point point) {
        int free = 0;
        while (free < MAX_CLEARANCE_SCAN) {
            int y = point.y() + free;
            // 上游判据是「不是空气且不可替换就停」；空气本身可替换，所以等价于「可替换」。
            if (!UpstreamComplexTreeFeatures.replaceable(world, point.x(), y, point.z())) {
                break;
            }
            free++;
        }
        return free;
    }

    /**
     * 把最低一层里的暗色原木换成熔岩木。
     *
     * <p>逐列判定、而不是整层替换：整层替换会让每块基座同时变色，读起来像「这棵树的属性」而不是
     * 「木头里渗出的热」。只碰真正落成暗色幽光木的格子，所以基座落在过渡木上的树保持原样。
     *
     * <p>抽样在判定**之前**（上游如此）：熔根的图案因此属于这棵树自己，不会随写区边缘或地形改变。
     */
    private void moltenRoot(WorldView world, TreeSpace space, Point base, Random random) {
        final BlockData molten = palette.molten;
        if (molten == null) {
            return;
        }
        final Set<BlockData> darkLogs = palette.darkLogs;
        for (int dx = -ROOT_RADIUS; dx <= ROOT_RADIUS; dx++) {
            for (int dz = -ROOT_RADIUS; dz <= ROOT_RADIUS; dz++) {
                final boolean isMolten = random.nextFloat() < MOLTEN_COLUMN_CHANCE;
                if (!isMolten || !space.canWrite(dx, dz)) {
                    continue;
                }
                int x = base.x() + dx;
                int y = base.y();
                int z = base.z() + dz;
                if (!UpstreamComplexTreeFeatures.isAnyOfIds(
                        world, x, y, z, palette.darkLogIds, palette.darkLogs)) {
                    continue;
                }
                world.setBlock(x, y, z, molten);
            }
        }
    }

    /**
     * 漂白树的根下埋古残骸：每列 1..4 格，只替换地面，遇到非地面就停。
     *
     * <p>深度同样在判定前抽样（理由同 {@link #moltenRoot}）。只碰「最底层已经是这棵树的原木」的列，
     * 所以残骸整片位于树干正下方、地表完全看不见。
     */
    private void ancientDebrisRoots(WorldView world, TreeSpace space, Point base, Random random) {
        final BlockData debris = palette.debris;
        if (debris == null) {
            return;
        }
        for (int dx = -ROOT_RADIUS; dx <= ROOT_RADIUS; dx++) {
            for (int dz = -ROOT_RADIUS; dz <= ROOT_RADIUS; dz++) {
                final int depth = MIN_DEBRIS_DEPTH + random.nextInt(MAX_DEBRIS_DEPTH - MIN_DEBRIS_DEPTH + 1);
                if (!space.canWrite(dx, dz)) {
                    continue;
                }
                int x = base.x() + dx;
                int z = base.z() + dz;
                if (!UpstreamComplexTreeFeatures.isAnyOfIds(
                        world, x, base.y(), z, palette.structureIds, palette.structure)) {
                    continue;
                }
                for (int down = 1; down <= depth; down++) {
                    int y = base.y() - down;
                    if (!UpstreamComplexTreeFeatures.sculkGround(world, x, y, z)) {
                        break;
                    }
                    world.setBlock(x, y, z, debris);
                }
            }
        }
    }

    /**
     * 每棵树一个新 builder：调色板里烘进了这棵树的随机朝向（眼睛），树皮噪声也按树播种。
     */
    private TreeBuilder builder(Random random, Size size, boolean bleached) {
        final float facing = random.nextFloat() * (float) Math.PI * 2;
        // 顺序与上游一致：朝向 nextFloat → 接缝 nextLong（palette 里）→ 树皮 nextLong（bark）。
        final TreePalette treePalette = palette(random, facing, size, bleached);

        return TreeBuilder.create()
                .trunk(ForkingTrunk.builder()
                        .height(size.minHeight, size.maxHeight)
                        .forkAt(0.5F)
                        .arms(size.minArms, size.maxArms)
                        .spread(size.spread)
                        .lean(0.8F)
                        .jitter(0.6F)
                        .radius(size.baseRadius, size.tipRadius)
                        // 每条臂一个锚点：两片高冠层叠在一根臂上会并成一根看不出轮廓的柱子。
                        .anchorsPerArm(1)
                        .build())
                .canopy(GhostCanopy.builder()
                        .radius(size.canopyRadius)
                        .bodyHeight(size.bodyHeight)
                        .domeHeight(size.domeHeight)
                        .skirt(0.75F, size.skirtDepth, 0.5F)
                        .verticalOffset(size.verticalOffset)
                        .smoothing(size.smoothing)
                        .hemWobble(size.hemWobble)
                        .minRadius(size.minRadius)
                        .build())
                .palette(treePalette)
                .leaves(palette.leafStates())
                // 裙摆离锚点 5.5 格，绕开中空之后远超 6 步衰减预算，所以每片冠层都要一条
                // 沿中轴往下刻的支撑枝。这是有意为之，代价是每片冠层几次 carve。
                .maxCarves(64)
                // ±0.35 格的树皮脊。0.8 尖端半径的一半是上限；再大细枝会碎成片。
                .bark(Crackle.bark(random.nextLong(), 0.7F, 0.55F))
                .repair(DecayRepair.CARVE_BRANCH)
                .build();
    }

    // ================================================================ 调色板

    /**
     * 方块状态与「哪个高度用哪种木头 / 哪片叶子有眼睛」。
     *
     * <p>所有变体在装载期一次解析成表，运行期只做查表 —— 一是因为 CE 的 {@code byId} 不该在
     * worldgen worker 里调用，二是因为叶子要按衰减距离 1..6 写状态，逐个解析会把随机数之外的
     * 东西带进热路径。
     */
    final class Palette {
        final BlockData[] darkBand = new BlockData[3];
        final BlockData[] transitionBand = new BlockData[3];
        final BlockData[] topBand = new BlockData[3];
        final BlockData molten;
        final BlockData debris;
        final Set<BlockData> darkLogs;
        final Set<BlockData> structure;
        /** 同一批方块的 **id**（{@code blockId} 精确判据用；见 isAnyOfIds 的说明）。 */
        final Set<String> darkLogIds = Set.of("betternether:gloomwood_dark_log");
        final Set<String> structureIds = Set.of(
                "betternether:gloomwood_log",
                "betternether:gloomwood_dark_log",
                "betternether:gloomwood_transition_log");
        /** 索引 0 = 冠层底色，1 = 眼睛。 */
        final BlockData[][] leavesByDistance = new BlockData[2][8];
        final BlockData[] leavesPersistent = new BlockData[2];

        Palette() {
            for (int axis = 0; axis < 3; axis++) {
                String name = axisName(axis);
                darkBand[axis] = UpstreamComplexTreeFeatures.block(resolver,
                        "betternether:gloomwood_dark_log", UpstreamComplexTreeFeatures.props("axis", name));
                transitionBand[axis] = UpstreamComplexTreeFeatures.block(resolver,
                        "betternether:gloomwood_transition_log", UpstreamComplexTreeFeatures.props("axis", name));
                topBand[axis] = UpstreamComplexTreeFeatures.block(resolver,
                        "betternether:gloomwood_log", UpstreamComplexTreeFeatures.props("axis", name));
            }
            molten = UpstreamComplexTreeFeatures.block(resolver,
                    "betternether:gloomwood_dark_molten_log", UpstreamComplexTreeFeatures.props("axis", "y"));
            debris = UpstreamComplexTreeFeatures.block(resolver, "minecraft:ancient_debris", Map.of());

            darkLogs = UpstreamComplexTreeFeatures.identities(
                    darkBand[0], darkBand[1], darkBand[2]);
            structure = UpstreamComplexTreeFeatures.identities(
                    darkBand[0], darkBand[1], darkBand[2],
                    transitionBand[0], transitionBand[1], transitionBand[2],
                    topBand[0], topBand[1], topBand[2]);

            for (int type = 0; type < 2; type++) {
                for (int distance = 1; distance <= 7; distance++) {
                    leavesByDistance[type][distance] = leafState(type, distance, false);
                }
                leavesByDistance[type][0] = leavesByDistance[type][1];
                leavesPersistent[type] = leafState(type, 1, true);
            }
        }

        private BlockData leafState(int type, int distance, boolean persistent) {
            String id = type == 0 ? "betternether:gloomwood_leaves"
                    : "betternether:gloomwood_bleached_leaves";
            return UpstreamComplexTreeFeatures.block(resolver, id, UpstreamComplexTreeFeatures.props(
                    "distance", Integer.toString(distance),
                    "persistent", Boolean.toString(persistent)));
        }

        /**
         * 按这一档尺寸与是否漂白造一个新的 {@link LogBands} + {@link LeafMarks}。
         *
         * <p>过渡木是**接缝**而不是色带：一列只有一格过渡木，位置在 darkUpTo 与 transitionUpTo 之间
         * 随机（按列哈希）。给它一整段色带会叠出两三格过渡木，读起来像第三种木头。
         *
         * @param seamSeed 上游在这一步抽的 {@code random.nextLong()}，由调用方按顺序抽取后传入
         * @param facing   这棵树的眼睛朝向（弧度）
         */
        TreePalette treePalette(Size size, boolean bleached, long seamSeed, float facing) {
            LogBands logs = new LogBands(darkBand, transitionBand, topBand,
                    size.darkUpTo, size.transitionUpTo, seamSeed);
            // 漂白树只是把两种叶子互换：原本只做眼睛标记的浅色叶成为整片冠层，
            // 眼睛改用普通暗色叶挖出来。木头、形状、标记几何都不变。
            final int canopyType = bleached ? 1 : 0;
            final int eyeType = bleached ? 0 : 1;
            LeafMarks leaves = LeafMarks.of(canopyType)
                    .eyesOnPrimaryLobe(facing, size.eyeHeight, size.canopyRadius,
                            size.eyeSeparation, size.eyeForward, eyeType)
                    .build();
            return new TreePalette(logs, leaves);
        }

        private String axisName(int axis) {
            return axis == 0 ? "x" : axis == 1 ? "y" : "z";
        }

        /** 叶子状态表：类型 0 = 冠层底色，1 = 眼睛。 */
        LeafStates leafStates() {
            return new LeafStates(leavesByDistance, leavesPersistent);
        }
    }

    /** 上游 {@code TreePalette palette(random, facing, size, bleached)}：抽接缝 seed 后造调色板。 */
    private TreePalette palette(Random random, float facing, Size size, boolean bleached) {
        return palette.treePalette(size, bleached, random.nextLong(), facing);
    }


    // ================================================================ 树形数学（上游 world/tree/math）

    /** 上游 JOML {@code Vector3f}：只有「可变三元组」这一件事，jitter 需要就地改点。 */
    static final class Vec3 {
        float x;
        float y;
        float z;

        Vec3(float x, float y, float z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        Vec3(Vec3 other) {
            this(other.x, other.y, other.z);
        }

        Vec3 set(float nx, float ny, float nz) {
            this.x = nx;
            this.y = ny;
            this.z = nz;
            return this;
        }

        Vec3 set(Vec3 other) {
            return set(other.x, other.y, other.z);
        }

        void add(float dx, float dy, float dz) {
            this.x += dx;
            this.y += dy;
            this.z += dz;
        }

        float distance(Vec3 other) {
            final float dx = x - other.x;
            final float dy = y - other.y;
            final float dz = z - other.z;
            return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
    }

    /** 上游 {@code Volume.Displacement}：给 {@link Volume#displace} 用的标量场。 */
    @FunctionalInterface
    interface Displacement {
        float at(float x, float y, float z);
    }

    /**
     * 有符号距离场：内部为负、外部为正（上游 {@code math/Volume}）。
     *
     * <p><b>符号权威、模长不权威</b>：所有光栅化只问 {@link #contains}，因为 {@link #subtract}
     * 与 {@link #intersect} 取 max 会在接缝处高估、{@link #scale} 只给界。符号在这三处都精确保留，
     * 所以形状是对的；只有 {@link #smoothUnion} 真的读模长，而它读的是紧贴表面的地方。
     * 由此得出一条必须遵守的顺序：**先平滑并、再减**（{@link GhostCanopy} 就是为此写的）。
     */
    @FunctionalInterface
    interface Volume {
        float distance(float x, float y, float z);

        Volume EMPTY = (x, y, z) -> Float.MAX_VALUE;

        default boolean contains(float x, float y, float z) {
            return distance(x, y, z) < 0;
        }

        default Volume union(Volume other) {
            return (x, y, z) -> Math.min(this.distance(x, y, z), other.distance(x, y, z));
        }

        default Volume subtract(Volume other) {
            return (x, y, z) -> Math.max(this.distance(x, y, z), -other.distance(x, y, z));
        }

        /**
         * 带圆角接缝的并集。{@code k} 是接缝宽度（格），0 退化成普通并集。
         *
         * <p>标准 SDF 工具箱里的多项式 smooth-min，逐字符照抄上游。
         */
        default Volume smoothUnion(Volume other, float k) {
            if (k <= 0) {
                return union(other);
            }
            return (x, y, z) -> {
                final float a = this.distance(x, y, z);
                final float b = other.distance(x, y, z);
                final float h = Math.max(0, Math.min(1, 0.5F + 0.5F * (b - a) / k));
                return b + (a - b) * h - k * h * (1 - h);
            };
        }

        default Volume translate(float dx, float dy, float dz) {
            return (x, y, z) -> this.distance(x - dx, y - dy, z - dz);
        }

        /** 给表面加一个扰动场（"裂开的树皮"算子）。正值把表面往里拉。 */
        default Volume displace(Displacement displacement) {
            return (x, y, z) -> this.distance(x, y, z) + displacement.at(x, y, z);
        }
    }

    /** 上游 {@code math/Volumes}：只带这一点点用得到的原语。 */
    static final class Volumes {
        private Volumes() {
        }

        static Volume sphere(float radius) {
            return (x, y, z) -> (float) Math.sqrt(x * x + y * y + z * z) - radius;
        }

        /**
         * 轴向对齐椭球。
         *
         * <p>刻意不是「球体 + 缩放」：那种写法被最短半轴支配，对冠层裙摆这种又扁又宽的椭球太悲观。
         * 这里用梯度校正的标准估计，平滑并接时仍然可用。
         */
        static Volume ellipsoid(float rx, float ry, float rz) {
            return (x, y, z) -> {
                final float nx = x / rx;
                final float ny = y / ry;
                final float nz = z / rz;
                final float k0 = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                if (k0 == 0) {
                    return -Math.min(rx, Math.min(ry, rz));
                }
                final float mx = x / (rx * rx);
                final float my = y / (ry * ry);
                final float mz = z / (rz * rz);
                final float k1 = (float) Math.sqrt(mx * mx + my * my + mz * mz);
                return k0 * (k0 - 1) / k1;
            };
        }

        /** 绕 Y 轴的平顶圆柱，上下各 {@code halfHeight}。 */
        static Volume cylinder(float radius, float halfHeight) {
            return (x, y, z) -> {
                final float dx = (float) Math.sqrt(x * x + z * z) - radius;
                final float dy = Math.abs(y) - halfHeight;
                final float outside = (float) Math.sqrt(
                        Math.max(dx, 0) * Math.max(dx, 0) + Math.max(dy, 0) * Math.max(dy, 0)
                );
                return Math.min(Math.max(dx, dy), 0) + outside;
            };
        }

        /** 圆头锥形胶囊：树干与枝条的主力，一个骨架段就是一支。 */
        static Volume capsule(Vec3 a, Vec3 b, float r0, float r1) {
            final float bax = b.x - a.x;
            final float bay = b.y - a.y;
            final float baz = b.z - a.z;
            final float baLenSq = bax * bax + bay * bay + baz * baz;
            if (baLenSq < 1.0e-6F) {
                return sphere(Math.max(r0, r1)).translate(a.x, a.y, a.z);
            }
            return (x, y, z) -> {
                final float pax = x - a.x;
                final float pay = y - a.y;
                final float paz = z - a.z;
                float h = (pax * bax + pay * bay + paz * baz) / baLenSq;
                h = Math.max(0, Math.min(1, h));
                final float dx = pax - bax * h;
                final float dy = pay - bay * h;
                final float dz = paz - baz * h;
                return (float) Math.sqrt(dx * dx + dy * dy + dz * dz) - (r0 + (r1 - r0) * h);
            };
        }
    }

    /**
     * 上游 {@code math/Crackle}：表面扰动场。
     *
     * <p><b>幅度纪律</b>：扰动是加到有符号距离上的，幅度 {@code a} 会让表面最多内外各动 {@code a} 格。
     * 超过形状最小半径，固体就碎成孤立方块 —— 这个库存在的意义就是避免那种悬浮方块。
     *
     * <p><b>噪声实现已与官方源核对</b>：上游用的是 {@code de.ambertation.wover.math.api.noise.OpenSimplexNoise}
     * （{@code WorldWeaver/release/26.3},
     * {@code wover-math-api/src/main/java/de/ambertation/wover/math/api/noise/OpenSimplexNoise.java}）。
     * 本仓库 {@code terrain/OpenSimplexNoise} 的 2D/3D {@code eval}、{@code OpenSimplexNoise(long)} 的
     * permutation 生成与 {@code gradients3D} 表，与那份官方原文**逐 token 相同**（脚本比对；
     * 本地只少了 4D，树形用不到）。所以 {@code bark}/{@code wobble} 的噪声场与上游一致。
     */
    static final class Crackle {
        private Crackle() {
        }

        /**
         * 龟裂树皮：ridged 噪声，捏出的是窄缝而不是沙丘。
         *
         * @param amplitude 表面移动幅度（格）
         * @param scale     特征尺度，越大越细
         */
        static Displacement bark(long seed, float amplitude, float scale) {
            final OpenSimplexNoise noise = new OpenSimplexNoise(seed);
            return (x, y, z) -> {
                // 1 - |n| 把平滑场变成脊；竖直方向按水平的三分之一采样，缝隙因此**沿着**树干跑。
                final double n = noise.eval(x * scale, y * scale * 0.33, z * scale);
                return amplitude * (float) (1.0 - Math.abs(n)) - amplitude * 0.5F;
            };
        }

        /** 软的各向同性抖动 —— 给冠层下摆用，目标是「撕开的」而不是「龟裂的」。 */
        static Displacement wobble(long seed, float amplitude, float scale) {
            final OpenSimplexNoise noise = new OpenSimplexNoise(seed);
            return (x, y, z) -> amplitude * (float) noise.eval(x * scale, y * scale, z * scale);
        }
    }

    /** 上游 {@code math/Spline}：树干与枝条的折线工具。 */
    static final class Spline {
        private Spline() {
        }

        /** 从 {@code from} 到 {@code to} 的 {@code points} 个采样点，含两端。 */
        static List<Vec3> line(Vec3 from, Vec3 to, int points) {
            final List<Vec3> result = new ArrayList<>(Math.max(points, 2));
            final int last = Math.max(points, 2) - 1;
            for (int i = 0; i <= last; i++) {
                final float t = (float) i / last;
                result.add(new Vec3(
                        from.x + (to.x - from.x) * t,
                        from.y + (to.y - from.y) * t,
                        from.z + (to.z - from.z) * t
                ));
            }
            return result;
        }

        /**
         * Catmull-Rom 细分，让几个控制点抖过的折线读起来是曲线而不是一串折角。
         *
         * @param subdivisions 每段生成的采样数；1 表示原样返回
         */
        static List<Vec3> smooth(List<Vec3> control, int subdivisions) {
            if (control.size() < 3 || subdivisions <= 1) {
                return new ArrayList<>(control);
            }
            final List<Vec3> result = new ArrayList<>(control.size() * subdivisions);
            for (int i = 0; i < control.size() - 1; i++) {
                // 端点用重复而不是外插：曲线不会冲过树根或枝尖，这两处是必须留在原地的接点。
                final Vec3 p0 = control.get(Math.max(i - 1, 0));
                final Vec3 p1 = control.get(i);
                final Vec3 p2 = control.get(i + 1);
                final Vec3 p3 = control.get(Math.min(i + 2, control.size() - 1));
                for (int s = 0; s < subdivisions; s++) {
                    result.add(catmullRom(p0, p1, p2, p3, (float) s / subdivisions));
                }
            }
            result.add(new Vec3(control.get(control.size() - 1)));
            return result;
        }

        private static Vec3 catmullRom(Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3, float t) {
            final float t2 = t * t;
            final float t3 = t2 * t;
            return new Vec3(
                    catmullRom(p0.x, p1.x, p2.x, p3.x, t, t2, t3),
                    catmullRom(p0.y, p1.y, p2.y, p3.y, t, t2, t3),
                    catmullRom(p0.z, p1.z, p2.z, p3.z, t, t2, t3)
            );
        }

        private static float catmullRom(float a, float b, float c, float d,
                                        float t, float t2, float t3) {
            return 0.5F * ((2 * b) + (-a + c) * t
                    + (2 * a - 5 * b + 4 * c - d) * t2
                    + (-a + 3 * b - 3 * c + d) * t3);
        }

        /**
         * 只抖内部点，两端不动。位移按正弦窗进出，所以中段最大 ——
         * 一棵在基部也歪得同样厉害的树干看起来就是没扎根。
         */
        static void jitter(List<Vec3> spline, Random random, float amount) {
            if (spline.size() < 3 || amount <= 0) {
                return;
            }
            for (int i = 1; i < spline.size() - 1; i++) {
                final float window = (float) Math.sin(Math.PI * i / (spline.size() - 1));
                final float scale = amount * window;
                // 三个 nextFloat 的顺序是 x → y → z，与上游参数求值顺序一致。
                final float dx = (random.nextFloat() * 2 - 1) * scale;
                final float dy = (random.nextFloat() * 2 - 1) * scale * 0.35F;
                final float dz = (random.nextFloat() * 2 - 1) * scale;
                spline.get(i).add(dx, dy, dz);
            }
        }
    }

    // ================================================================ 骨架（上游 world/tree/skeleton）

    /** 原版 {@code Direction.Axis} 的三档，骨架段只需要它。 */
    enum Axis {
        X, Y, Z
    }

    /**
     * 一段锥形木头，树局部坐标（原点 = 树长出来的那一格）。
     *
     * <p>留成「段」而不是合并成一个 {@link Volume}：光栅化需要每段的 {@link #axis}，
     * 合并后的距离函数已经把它丢掉了。
     */
    record Segment(Vec3 from, Vec3 to, float radiusFrom, float radiusTo) {
        Volume toVolume() {
            return Volumes.capsule(from, to, radiusFrom, radiusTo);
        }

        float maxRadius() {
            return Math.max(radiusFrom, radiusTo);
        }

        /**
         * 原木该用哪个轴：取方向的主分量，而不是按方块位置取 —— 近水平的枝条整条都是水平原木，
         * 不会随光栅化格子怎么拐而翻轴。
         */
        Axis axis() {
            final float dx = Math.abs(to.x - from.x);
            final float dy = Math.abs(to.y - from.y);
            final float dz = Math.abs(to.z - from.z);
            if (dy >= dx && dy >= dz) {
                return Axis.Y;
            }
            return dx >= dz ? Axis.X : Axis.Z;
        }
    }

    /** 叶片挂点：相当于原版 {@code FoliageAttachment}，外加一个缩放。 */
    record Anchor(Vec3 position, float scale) {
        Anchor(Vec3 position) {
            this(position, 1.0F);
        }
    }

    /** 树的木质部分：所有段 + 所有挂点。 */
    record Skeleton(List<Segment> segments, List<Anchor> anchors) {
        static Builder builder() {
            return new Builder();
        }

        static final class Builder {
            private final List<Segment> segments = new ArrayList<>();
            private final List<Anchor> anchors = new ArrayList<>();

            /** 把一条折线的每对相邻点加成一段，半径沿整条折线线性收窄。 */
            Builder path(List<Vec3> points, float radiusStart, float radiusEnd) {
                for (int i = 0; i < points.size() - 1; i++) {
                    final float t0 = (float) i / (points.size() - 1);
                    final float t1 = (float) (i + 1) / (points.size() - 1);
                    segments.add(new Segment(
                            points.get(i), points.get(i + 1),
                            radiusStart + (radiusEnd - radiusStart) * t0,
                            radiusStart + (radiusEnd - radiusStart) * t1
                    ));
                }
                return this;
            }

            Builder anchor(Anchor anchor) {
                anchors.add(anchor);
                return this;
            }

            Skeleton build() {
                return new Skeleton(List.copyOf(segments), List.copyOf(anchors));
            }
        }
    }

    /**
     * 先上升、再散成一把臂的树干 —— 那个 "Y" 形。上游 {@code skeleton/ForkingTrunk}。
     *
     * <p>{@code anchorsAlong} 把挂点铺在臂的**外侧**而不是只放尖端：长臂的叶子沿全长都能贴到木头，
     * 而不是只在末端接上。
     */
    static final class ForkingTrunk {
        private final int minHeight;
        private final int maxHeight;
        private final float forkAt;
        private final int minArms;
        private final int maxArms;
        private final float spread;
        private final float lean;
        private final float jitter;
        private final float baseRadius;
        private final float tipRadius;
        private final int anchorsPerArm;

        private ForkingTrunk(Builder b) {
            this.minHeight = b.minHeight;
            this.maxHeight = b.maxHeight;
            this.forkAt = b.forkAt;
            this.minArms = b.minArms;
            this.maxArms = b.maxArms;
            this.spread = b.spread;
            this.lean = b.lean;
            this.jitter = b.jitter;
            this.baseRadius = b.baseRadius;
            this.tipRadius = b.tipRadius;
            this.anchorsPerArm = b.anchorsPerArm;
        }

        static Builder builder() {
            return new Builder();
        }

        Skeleton build(TreeSpace space, Random random) {
            final Skeleton.Builder skeleton = Skeleton.builder();

            final int height = minHeight + random.nextInt(Math.max(1, maxHeight - minHeight + 1));
            final float forkHeight = height * forkAt;

            // --- 主干，地面到分叉 ---
            final float leanAngle = random.nextFloat() * (float) Math.PI * 2;
            final float leanAmount = random.nextFloat() * lean;
            final Vec3 forkPoint = new Vec3(
                    (float) Math.cos(leanAngle) * leanAmount,
                    forkHeight,
                    (float) Math.sin(leanAngle) * leanAmount
            );

            final List<Vec3> stem = Spline.line(new Vec3(0, 0, 0), forkPoint, 4);
            Spline.jitter(stem, random, jitter);
            // 基点必须精确落回原点：树苗长在那里，会飘走的树干会在玩家脚下留一个洞。
            stem.get(0).set(0, 0, 0);
            final float forkRadius = baseRadius * 0.75F;
            skeleton.path(Spline.smooth(stem, 3), baseRadius, forkRadius);

            // --- 臂 ---
            final int arms = minArms + random.nextInt(Math.max(1, maxArms - minArms + 1));
            final float armBase = random.nextFloat() * (float) Math.PI * 2;
            for (int i = 0; i < arms; i++) {
                // 绕主干均匀分布再各带一点偏差，三根臂才不会像用尺子量过。
                final float angle = armBase + i * (float) Math.PI * 2 / arms
                        + (random.nextFloat() - 0.5F) * 0.6F;
                final float reach = spread * (0.7F + random.nextFloat() * 0.6F);
                final float top = height * (0.85F + random.nextFloat() * 0.3F);

                Vec3 tip = new Vec3(
                        forkPoint.x + (float) Math.cos(angle) * reach,
                        top,
                        forkPoint.z + (float) Math.sin(angle) * reach
                );
                // 只收长度、不改方向：扇形的角度因此保持住。
                tip = space.fitSegment(forkPoint, tip, tipRadius);

                final List<Vec3> arm = Spline.line(forkPoint, tip, 4);
                Spline.jitter(arm, random, jitter * 0.7F);
                arm.get(0).set(forkPoint);
                final List<Vec3> smoothed = Spline.smooth(arm, 3);
                skeleton.path(smoothed, forkRadius, tipRadius);

                for (Anchor anchor : anchorsAlong(smoothed)) {
                    skeleton.anchor(anchor);
                }
            }

            return skeleton.build();
        }

        /** 挂点铺在臂的外侧；越靠后的越小（冠层朝分叉方向收窄）。 */
        private List<Anchor> anchorsAlong(List<Vec3> arm) {
            final List<Anchor> result = new ArrayList<>(anchorsPerArm);
            for (int i = 0; i < anchorsPerArm; i++) {
                final float t = anchorsPerArm == 1
                        ? 1.0F
                        : 1.0F - 0.35F * i / (anchorsPerArm - 1);
                final int index = Math.min(arm.size() - 1, Math.round(t * (arm.size() - 1)));
                result.add(new Anchor(new Vec3(arm.get(index)), 1.0F - 0.2F * i));
            }
            return result;
        }

        static final class Builder {
            private int minHeight = 9;
            private int maxHeight = 14;
            private float forkAt = 0.55F;
            private int minArms = 2;
            private int maxArms = 3;
            private float spread = 3.0F;
            private float lean = 0.8F;
            private float jitter = 0.6F;
            private float baseRadius = 1.4F;
            private float tipRadius = 0.7F;
            private int anchorsPerArm = 2;

            Builder height(int min, int max) {
                this.minHeight = min;
                this.maxHeight = max;
                return this;
            }

            Builder forkAt(float fraction) {
                this.forkAt = fraction;
                return this;
            }

            Builder arms(int min, int max) {
                this.minArms = min;
                this.maxArms = max;
                return this;
            }

            Builder spread(float blocks) {
                this.spread = blocks;
                return this;
            }

            Builder lean(float blocks) {
                this.lean = blocks;
                return this;
            }

            Builder jitter(float blocks) {
                this.jitter = blocks;
                return this;
            }

            Builder radius(float base, float tip) {
                this.baseRadius = base;
                this.tipRadius = tip;
                return this;
            }

            Builder anchorsPerArm(int count) {
                this.anchorsPerArm = Math.max(1, count);
                return this;
            }

            ForkingTrunk build() {
                return new ForkingTrunk(this);
            }
        }
    }

    // ================================================================ 生长空间（上游 TreeSpace / wover WriteZone）

    /**
     * 树在哪儿长、有多少地方可写。
     *
     * <h2>写区：直接用上游 WriteZone 的算术</h2>
     * 上游 {@code TreeSpace} 持一个 wover {@code WriteZone}
     * （{@code wover-feature-api/src/main/java/de/ambertation/wover/feature/api/WriteZone.java}，
     * {@code release/26.3}，已读原文）。这里复用同包 {@link UpstreamSimpleTreeFeatures.Zone} ——
     * 那份实现逐行照抄了 {@code headroom} / {@code fitRadius} / {@code contains} / {@code toBoundingBox}，
     * 唯一替换是把探测对象从 {@code WorldGenRegion#isWithinWriteZone} 换成
     * {@link WorldView#inRegion}（本移植里「可读可写吗」的唯一权威判据）。
     *
     * <p>{@link #fitSegment} 上游没有落在 {@code Zone} 那份复刻里，这里按官方源码的 slab-clip
     * 逐行补上（见方法注释），不重算任何写区边界。
     */
    record TreeSpace(WorldView world, Point origin, UpstreamSimpleTreeFeatures.Zone zone) {
        /** 上游 {@code TreeSpace.of(world, pos)}：一次 place 取一次写区。 */
        static TreeSpace of(WorldView world, Point origin) {
            return new TreeSpace(world, origin,
                    UpstreamSimpleTreeFeatures.Zone.of(world, origin.x(), origin.y(), origin.z()));
        }

        /** 上游 {@code WriteZone#contains(int,int)}：只看 X/Z。 */
        boolean canWrite(int localX, int localZ) {
            return zone.contains(origin.x() + localX, origin.z() + localZ);
        }

        /**
         * 上游 {@code WriteZone#fitRadius(int,int,float,float)}：
         * {@code room < minRadius} → {@code -1}（整片放弃），否则 {@code min(radius, room)}。
         * 有富余时原样返回，所以绝大多数冠层与被裁剪前完全相同。
         */
        float fitRadius(Vec3 localCentre, float radius, float minRadius) {
            return zone.fitRadius(
                    origin.x() + Math.round(localCentre.x),
                    origin.z() + Math.round(localCentre.z),
                    radius, minRadius);
        }

        /**
         * 上游 {@code WriteZone#fitSegment(Vector3f start, Vector3f end, BlockPos origin, float radius)}。
         *
         * <p><b>实现只有一份</b>：算术在同包的
         * {@link UpstreamSimpleTreeFeatures.Zone#fitSegment(UpstreamSimpleTreeFeatures.Vec3,
         * UpstreamSimpleTreeFeatures.Vec3, int, int, float)} 里（逐行照抄官方 slab-clip），
         * 这里只把本类的可变 {@link Vec3} 转成它那边的 record 再转回来 —— 不重写一遍边界算法。
         *
         * <p>语义四条（与上游一致）：无写区限制 → 原样返回 {@code end}；起点已在「内缩 radius 的盒」
         * 之外 → 原样返回 {@code start}；{@code t = min(1, tX, tZ) >= 1} → {@code end}；
         * {@code t <= 0} → {@code start}；否则线性插值。**只收长度、不动方向**，扇形枝条因此保住角度。
         */
        Vec3 fitSegment(Vec3 start, Vec3 end, float radius) {
            final UpstreamSimpleTreeFeatures.Vec3 fitted = zone.fitSegment(
                    new UpstreamSimpleTreeFeatures.Vec3(start.x, start.y, start.z),
                    new UpstreamSimpleTreeFeatures.Vec3(end.x, end.y, end.z),
                    origin.x(), origin.z(), radius);
            return new Vec3(fitted.x(), fitted.y(), fitted.z());
        }
    }

    // ================================================================ 冠层（上游 world/tree/canopy）

    /**
     * 吊着的兜帽状冠层：圆柱身体 + 球状穹顶，再从底面挖掉一个椭球当裙摆。上游 {@code canopy/GhostCanopy}。
     *
     * <pre>
     *      .-'''-.        穹顶（球，与身体平滑并）
     *     |       |       身体（圆柱）
     *      \_   _/        下摆（减掉裙摆之后剩下的）
     *        '-'          裙摆（椭球，被减掉的）
     * </pre>
     *
     * <h2>顺序</h2>
     * 穹顶在减裙摆**之前**并进身体。反过来会把「减法结果」喂给 smoothUnion，而减法不保距，
     * 接缝会鼓成一圈不对称的疙瘩。
     */
    static final class GhostCanopy {
        private final float radius;
        private final float bodyHeight;
        private final float domeHeight;
        private final float skirtWidth;
        private final float skirtDepth;
        private final float skirtRise;
        private final float smoothing;
        private final float hemWobble;
        private final float minRadius;
        private final float verticalOffset;

        private GhostCanopy(Builder b) {
            this.radius = b.radius;
            this.bodyHeight = b.bodyHeight;
            this.domeHeight = b.domeHeight;
            this.skirtWidth = b.skirtWidth;
            this.skirtDepth = b.skirtDepth;
            this.skirtRise = b.skirtRise;
            this.smoothing = b.smoothing;
            this.hemWobble = b.hemWobble;
            this.minRadius = b.minRadius;
            this.verticalOffset = b.verticalOffset;
        }

        static Builder builder() {
            return new Builder();
        }

        Volume volumeAt(Anchor anchor, TreeSpace space, Random random) {
            final float wanted = radius * anchor.scale();
            final float r = space.fitRadius(anchor.position(), wanted, minRadius);
            if (r < 0) {
                return Volume.EMPTY;
            }

            // 其余一切跟着「实际塞进来的半径」缩，被写区压过的冠层才不会变成一个矮盘子。
            final float shrink = r / wanted;
            final float half = bodyHeight * anchor.scale() * shrink * 0.5F;

            // 身体挂在锚点**下方**。这不是装饰选择：裙摆是从身体中部减出去的，身体若以锚点为中心，
            // 中空就会一直顶到锚点，枝尖和它旁边每一格都落在冠层内部的开阔空间里。那样支撑枝
            // 无处可长（一长就破面），衰减求解器最后会把冠层剪掉而不是喂它。
            final float drop = verticalOffset * anchor.scale() * shrink;

            final Volume body = Volumes.cylinder(r, half).translate(0, drop, 0);
            final Volume dome = Volumes.ellipsoid(r, domeHeight * anchor.scale() * shrink, r)
                    .translate(0, drop + half, 0);
            final Volume solid = body.smoothUnion(dome, smoothing);

            final Volume skirt = Volumes
                    .ellipsoid(r * skirtWidth, skirtDepth * anchor.scale() * shrink, r * skirtWidth)
                    .translate(0, drop - half + skirtRise, 0);

            Volume ghost = solid.subtract(skirt);
            if (hemWobble > 0) {
                // 每片冠层一次 nextLong：噪声按冠层播种，两片并排的冠层不会共用同一道撕口。
                ghost = ghost.displace(Crackle.wobble(random.nextLong(), hemWobble, 0.4F));
            }

            final Vec3 p = anchor.position();
            return ghost.translate(p.x, p.y, p.z);
        }

        /**
         * 这个形状从锚点能伸出多远（scale = 1）。
         *
         * <p>必须覆盖**从锚点算起**的真实范围：drop 让形状上下不对称，下摆现在到了
         * {@code |offset| + half} 那么深。少报会静默截断冠层，因为光栅化按这个数定扫描盒。
         */
        float reach() {
            final float below = Math.abs(verticalOffset) + bodyHeight * 0.5F;
            final float above = verticalOffset + bodyHeight * 0.5F + domeHeight;
            return Math.max(radius, Math.max(below, above)) + hemWobble + 1;
        }

        static final class Builder {
            private float radius = 4.0F;
            private float bodyHeight = 6.0F;
            private float domeHeight = 3.0F;
            private float skirtWidth = 0.72F;
            private float skirtDepth = 3.0F;
            private float skirtRise = 0.5F;
            private float smoothing = 1.5F;
            private float hemWobble = 0.7F;
            private float minRadius = 2.0F;
            private float verticalOffset = 0.0F;

            Builder radius(float blocks) {
                this.radius = blocks;
                return this;
            }

            Builder bodyHeight(float blocks) {
                this.bodyHeight = blocks;
                return this;
            }

            Builder domeHeight(float blocks) {
                this.domeHeight = blocks;
                return this;
            }

            Builder skirt(float width, float depth, float rise) {
                this.skirtWidth = width;
                this.skirtDepth = depth;
                this.skirtRise = rise;
                return this;
            }

            Builder smoothing(float blocks) {
                this.smoothing = blocks;
                return this;
            }

            Builder hemWobble(float blocks) {
                this.hemWobble = blocks;
                return this;
            }

            Builder minRadius(float blocks) {
                this.minRadius = blocks;
                return this;
            }

            Builder verticalOffset(float blocks) {
                this.verticalOffset = blocks;
                return this;
            }

            GhostCanopy build() {
                return new GhostCanopy(this);
            }
        }
    }

    // ================================================================ 调色板（上游 world/tree/palette）

    /** 一次原木查询：树局部高度 + 骨架段主轴（原版 {@code BlockStateProvider} 看不到这个高度）。 */
    record LogQuery(int worldX, int worldZ, float heightAbove, int axis) {
    }

    /** 一次叶片查询：叶子在它自己冠层里的位置，以及它是不是那棵树的主冠层。 */
    record LeafQuery(float localX, float localY, float localZ,
                     float anchorX, float anchorY, float anchorZ,
                     boolean primary, Random random) {
    }

    /** 上游 {@code TreePalette}：木头与叶子分开问。 */
    record TreePalette(LogBands logs, LeafMarks leaves) {
        BlockData log(LogQuery query) {
            return logs.at(query);
        }

        int leaf(LeafQuery query) {
            return leaves.typeAt(query);
        }

        BlockData topOfColumn(@Nullable BlockData state) {
            return logs.topOfColumn(state);
        }
    }

    /**
     * 按「离树基多高」选原木，并用**接缝**表达两种木头之间的那一格过渡木。上游 {@code palette/LogBands}。
     *
     * <p>接缝是每列一格、位置在 {@code [from, upTo)} 之间按列哈希抽的。给定一整段色带会叠出两三格
     * 过渡木，读成第三种木头；用接缝则两种木头在整个区间上交错，接缝始终只有一格。
     *
     * <p>用**列哈希**而不是 {@code random}：调色板是按光栅化的遍历顺序被问的，同一列会被问好几次，
     * 每次抽样都会给出不同答案 —— 接缝会叠起来而且不可复现，树的样子还会取决于包围盒怎么扫。
     *
     * <p>上游还有一条按 2D 噪声抖动色带边界的路径（{@code jitter}）；幽光木不用它（接缝已经解决了
     * 同一个问题），所以这里没有实现 —— 没有调用方的东西不写。
     */
    static final class LogBands {
        private final List<Band> bands;
        private final long seamSeed;
        private final IdentityHashMap<BlockData, Integer> bandOf = new IdentityHashMap<>();
        private final IdentityHashMap<BlockData, Integer> axisOf = new IdentityHashMap<>();

        /**
         * @param dark / {@code transition} / {@code top} 三个色带各自的三种轴变体（X, Y, Z）
         */
        LogBands(BlockData[] dark, BlockData[] transition, BlockData[] top,
                 float darkUpTo, float transitionUpTo, long seamSeed) {
            this.bands = List.of(
                    new Band(darkUpTo, dark, false),
                    new Band(transitionUpTo, transition, true),
                    new Band(Float.MAX_VALUE, top, false)
            );
            this.seamSeed = seamSeed;
            for (int band = 0; band < bands.size(); band++) {
                for (int axis = 0; axis < 3; axis++) {
                    BlockData state = bands.get(band).byAxis()[axis];
                    if (state != null) {
                        bandOf.putIfAbsent(state, band);
                        axisOf.putIfAbsent(state, axis);
                    }
                }
            }
        }

        @Nullable
        BlockData at(LogQuery query) {
            final float height = query.heightAbove();
            BlockData state = null;
            float from = 0;
            for (Band band : bands) {
                if (band.seam()) {
                    // 按整层比较：接缝只占这一列的一格，它下面的层要落回下面那个色带，上面的层落到上面那个。
                    final int layer = UpstreamComplexTreeFeatures.floor(height);
                    final int seam = seamLayer(query, from, band.upTo());
                    if (layer > seam) {
                        from = band.upTo();
                        continue;
                    }
                    if (layer == seam) {
                        state = band.byAxis()[query.axis()];
                    }
                    break;
                }
                state = band.byAxis()[query.axis()];
                if (height < band.upTo()) {
                    break;
                }
                from = band.upTo();
            }
            return state;
        }

        /**
         * 顶上没有木头的接缝格不是接缝：那是刚好停在接缝层的枝端，上面是叶子或天。
         * 这种格子退回**下面**那个色带的木头，而不是上面那个（否则会把一格上层木头孤零零
         * 吊在一整条下层木头上）。
         */
        @Nullable
        BlockData topOfColumn(@Nullable BlockData state) {
            if (state == null) {
                return null;
            }
            final Integer band = bandOf.get(state);
            if (band == null || band <= 0 || band >= bands.size() - 1) {
                return state;
            }
            if (!bands.get(band).seam()) {
                return state;
            }
            final Integer axis = axisOf.getOrDefault(state, 1);
            return bands.get(band - 1).byAxis()[axis];
        }

        /**
         * 这一列的接缝落在 {@code [from, upTo)} 里的哪一层。
         *
         * <p>在整层之间选而不是直接按高度比例缩放：区间两端很少落在层边界上，而下面那个色带
         * 已经占住的层不能归接缝 —— 否则某些列一格过渡木都不会有，暗色木直接接上浅色木。
         */
        private int seamLayer(LogQuery query, float from, float upTo) {
            final int lowest = (int) Math.ceil(from);
            final int highest = Math.max(lowest, (int) Math.ceil(upTo) - 1);
            return lowest + choice(query.worldX(), query.worldZ(), highest - lowest + 1);
        }

        /** 每列一个稳定的 {@code [0, choices)}：64 位混合，相邻列（输入差一）不会连号。 */
        private int choice(int x, int z, int choices) {
            long h = seamSeed ^ (x * 0x9E3779B97F4A7C15L) ^ (z * 0xC2B2AE3D27D4EB4FL);
            h ^= h >>> 33;
            h *= 0xFF51AFD7ED558CCDL;
            h ^= h >>> 33;
            h *= 0xC4CEB9FE1A85EC53L;
            h ^= h >>> 33;
            return (int) Math.floorMod(h, choices);
        }

        private record Band(float upTo, BlockData[] byAxis, boolean seam) {
        }
    }

    /**
     * 一种叶子底色 + 若干「用另一种叶子重涂」的区域。上游 {@code palette/LeafMarks}。
     *
     * <h2>区域而不是几何</h2>
     * 标记是在**锚点局部空间**里求值的 {@link Volume}（叶子位置减它所属锚点）。所以一个标记
     * 只定义一次就适用于每个冠层；更重要的是标记与冠层形状完全解耦：形状决定哪里有叶子，
     * 标记只重涂落在它里面的那些。标记戳出冠层之外不会有任何后果 ——
     * 它只是没东西可涂，而不是长出一坨散块。
     */
    static final class LeafMarks {
        private final int baseType;
        private final List<Mark> marks;

        private LeafMarks(int baseType, List<Mark> marks) {
            this.baseType = baseType;
            this.marks = List.copyOf(marks);
        }

        static Builder of(int baseType) {
            return new Builder(baseType);
        }

        int typeAt(LeafQuery query) {
            final float lx = query.localX() - query.anchorX();
            final float ly = query.localY() - query.anchorY();
            final float lz = query.localZ() - query.anchorZ();

            for (Mark mark : marks) {
                if (mark.primaryOnly() && !query.primary()) {
                    continue;
                }
                if (mark.region().contains(lx, ly, lz)
                        && (mark.chance() >= 1 || query.random().nextFloat() < mark.chance())) {
                    return mark.type();
                }
            }
            return baseType;
        }

        private record Mark(Volume region, int type, float chance, boolean primaryOnly) {
        }

        static final class Builder {
            private final int baseType;
            private final List<Mark> marks = new ArrayList<>();

            private Builder(int baseType) {
                this.baseType = baseType;
            }

            /**
             * 一对圆形标记，只画在这棵树的主冠层上 —— 一棵树一张脸。
             *
             * @param facing     朝向（绕 Y 轴弧度）
             * @param height     这一对高出锚点多少
             * @param separation 两个中心的间距
             * @param radius     每个标记的半径
             * @param forward    中心离冠层轴多远。要比冠层半径略往里：标记只重涂已经存在的叶子，
             *                   戳在外面的部分自动无效果，剩下的才是干净的圆形斑
             */
            Builder eyesOnPrimaryLobe(float facing, float height, float separation, float radius,
                                      float forward, int type) {
                final float fx = (float) Math.cos(facing);
                final float fz = (float) Math.sin(facing);
                // 水平面内的垂直方向，两个标记就沿它分开。
                final float px = -fz;
                final float pz = fx;
                final float half = separation * 0.5F;

                marks.add(new Mark(
                        Volumes.sphere(radius).translate(fx * forward + px * half, height, fz * forward + pz * half),
                        type, 1.0F, true
                ));
                marks.add(new Mark(
                        Volumes.sphere(radius).translate(fx * forward - px * half, height, fz * forward - pz * half),
                        type, 1.0F, true
                ));
                return this;
            }

            LeafMarks build() {
                return new LeafMarks(baseType, marks);
            }
        }
    }

    /**
     * 叶子状态的查表：按「类型 × 衰减距离」。上游是就地 {@code setValue(DISTANCE, d)}；
     * 这里改成装载期解析好的表 —— 运行期不再碰注册表，而且 CE 若没有 {@code distance} 属性，
     * 表里各项都指向同一个默认状态，行为自动等价。
     */
    record LeafStates(BlockData[][] byDistance, BlockData[] persistent) {
        @Nullable
        BlockData at(int type, int distance) {
            final BlockData[] row = byDistance[type];
            if (row == null) {
                return null;
            }
            final int index = Math.max(0, Math.min(row.length - 1, distance));
            return row[index];
        }
    }

    // ================================================================ 体素缓冲（上游 build/TreeVoxels）

    /**
     * 整棵树的树局部整数坐标版本，任何一格都还没碰世界。上游 {@code build/TreeVoxels}。
     *
     * <h2>为什么非要一个缓冲区</h2>
     * 叶子衰减是**成品树**的性质：某片叶子够不够得着木头，取决于这棵树最后所有的原木。所以它决定
     * 不了正在画的树，而修它需要改几何 —— 方块一进世界那个自由就没了。先建、再求解、最后提交，
     * 是 {@link LeafDecay} 能工作的前提。附带好处：提交时每个位置只读一次世界，求形状时一次都不读。
     */
    static final class TreeVoxels {
        static final byte EMPTY = 0;
        static final byte LOG = 1;
        static final byte LEAF = 2;

        private final int minX;
        private final int minY;
        private final int minZ;
        private final int sizeX;
        private final int sizeY;
        private final int sizeZ;

        private final byte[] kind;
        private final BlockData[] state;
        /** 叶子类型（0 = 冠层底色，1 = 眼睛）；原木格为 0。 */
        private final byte[] leafType;

        TreeVoxels(int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ) {
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.sizeX = Math.max(1, sizeX);
            this.sizeY = Math.max(1, sizeY);
            this.sizeZ = Math.max(1, sizeZ);
            final int cells = this.sizeX * this.sizeY * this.sizeZ;
            this.kind = new byte[cells];
            this.state = new BlockData[cells];
            this.leafType = new byte[cells];
        }

        int minX() {
            return minX;
        }

        int minY() {
            return minY;
        }

        int minZ() {
            return minZ;
        }

        int sizeX() {
            return sizeX;
        }

        int sizeY() {
            return sizeY;
        }

        int sizeZ() {
            return sizeZ;
        }

        int cellCount() {
            return kind.length;
        }

        boolean inBounds(int x, int y, int z) {
            return x >= minX && x < minX + sizeX
                    && y >= minY && y < minY + sizeY
                    && z >= minZ && z < minZ + sizeZ;
        }

        int index(int x, int y, int z) {
            if (!inBounds(x, y, z)) {
                return -1;
            }
            return ((x - minX) * sizeY + (y - minY)) * sizeZ + (z - minZ);
        }

        int localX(int index) {
            return index / (sizeY * sizeZ) + minX;
        }

        int localY(int index) {
            return (index / sizeZ) % sizeY + minY;
        }

        int localZ(int index) {
            return index % sizeZ + minZ;
        }

        byte kindAt(int index) {
            return index < 0 ? EMPTY : kind[index];
        }

        byte kindAt(int x, int y, int z) {
            return kindAt(index(x, y, z));
        }

        @Nullable
        BlockData stateAt(int index) {
            return index < 0 ? null : state[index];
        }

        byte leafTypeAt(int index) {
            return index < 0 ? 0 : leafType[index];
        }

        void set(int index, byte what, @Nullable BlockData blockState) {
            if (index < 0) {
                return;
            }
            kind[index] = what;
            state[index] = blockState;
            if (what != LEAF) {
                leafType[index] = 0;
            }
        }

        /** 写原木，覆盖原有内容：原木压过叶子。 */
        void setLog(int x, int y, int z, @Nullable BlockData blockState) {
            final int i = index(x, y, z);
            if (i < 0 || blockState == null) {
                return;
            }
            kind[i] = LOG;
            state[i] = blockState;
            leafType[i] = 0;
        }

        /** 只往空格里写叶子，冠层就不会吃掉自己挂着的树干。 */
        void setLeafIfEmpty(int x, int y, int z, int type, @Nullable BlockData blockState) {
            final int i = index(x, y, z);
            if (i < 0 || blockState == null || kind[i] != EMPTY) {
                return;
            }
            kind[i] = LEAF;
            state[i] = blockState;
            leafType[i] = (byte) type;
        }

        void clear(int index) {
            if (index < 0) {
                return;
            }
            kind[index] = EMPTY;
            state[index] = null;
            leafType[index] = 0;
        }

        /**
         * 六个邻居都占着 —— 也就是从外面看不见这一格。贴着缓冲区边界的格子算暴露：
         * 边界之外是什么我们不知道。
         */
        boolean isBuried(int index) {
            if (index < 0) {
                return false;
            }
            final int x = localX(index);
            final int y = localY(index);
            final int z = localZ(index);
            for (int face = 0; face < 6; face++) {
                final int n = index(x + FACE_X[face], y + FACE_Y[face], z + FACE_Z[face]);
                if (n < 0 || kind[n] == EMPTY) {
                    return false;
                }
            }
            return true;
        }

        /** 访问每个非空格。 */
        void forEach(CellVisitor visitor) {
            for (int i = 0; i < kind.length; i++) {
                if (kind[i] != EMPTY) {
                    visitor.visit(localX(i), localY(i), localZ(i), kind[i], state[i]);
                }
            }
        }

        @FunctionalInterface
        interface CellVisitor {
            void visit(int x, int y, int z, byte kind, @Nullable BlockData state);
        }

        private static final int[] FACE_X = {-1, 1, 0, 0, 0, 0};
        private static final int[] FACE_Y = {0, 0, -1, 1, 0, 0};
        private static final int[] FACE_Z = {0, 0, 0, 0, -1, 1};
    }

    // ================================================================ 叶子衰减（上游 decay/）

    /** 上游 {@code decay/DecayRepair}：够不到的叶子怎么办。 */
    enum DecayRepair {
        /** 删掉。永远正确，代价是过大的冠层会静默失去外壳。 */
        PRUNE,
        /** 沿叶子自己已经形成的路径长真枝条，剩下还够不到的剪掉。 */
        CARVE_BRANCH,
        /** 标 persistent 让原版永不检查。逃生口，不是解法（砍树也不掉）。 */
        PERSIST
    }

    /**
     * 让光栅化出来的树活过原版叶子衰减。上游 {@code decay/LeafDecay}。
     *
     * <h2>要满足的规则</h2>
     * {@code LeavesBlock} 存 1..7 的 {@code distance}，非 persistent 的叶子到 7 就消失。该值从六个
     * 邻居松弛（tag {@code prevents_nearby_leaf_decay} 的方块 —— 也就是任何原木 —— 算 0），
     * 所以真正的规则是「每片叶子都要在六步六连通、**穿叶子**的路径内够到某根原木」。
     * 注意这是穿叶子的路径长度，不是直线距离：绕开中空的冠层，直线三格可能要走九步。
     *
     * <h2>为什么 carve 不会造出悬浮原木</h2>
     * carve 沿广度优先的梯度从孤叶走回木头，把 {@code distance 1} 到 {@code d - budget} 那一段
     * 前缀翻成木头。每格都与前一格相邻，第一格贴着**已经在的**木头。没有任何一步是「往冠层里
     * 塞一根原木然后祈祷」。一条走不通的路径只会被搁置（{@code giveUp}），不影响别的叶子。
     */
    static final class LeafDecay {
        /** 能活下来的最大 {@code distance}：比 {@code LeavesBlock.DECAY_DISTANCE} 少 1。 */
        static final int VANILLA_BUDGET = 6;

        private static final int UNREACHED = Integer.MAX_VALUE;

        private LeafDecay() {
        }

        /** 为被 carve 成支撑枝的格子产出原木状态。 */
        @FunctionalInterface
        interface BranchFactory {
            @Nullable
            BlockData at(int x, int y, int z);
        }

        record Result(int leavesKept, int leavesPruned, int branchCells, int leavesPersist) {
            boolean isClean() {
                return leavesPruned == 0 && branchCells == 0 && leavesPersist == 0;
            }
        }

        static Result solve(TreeVoxels voxels, DecayRepair repair, int budget, int maxCarves,
                            LeafStates leaves, BranchFactory branches) {
            int[] distance = sweep(voxels);
            int branchCells = 0;

            if (repair == DecayRepair.CARVE_BRANCH && branches != null) {
                final boolean[] giveUp = new boolean[voxels.cellCount()];
                for (int carve = 0; carve < maxCarves; carve++) {
                    final int worst = worstReachableLeaf(voxels, distance, budget, giveUp);
                    if (worst < 0) {
                        break;
                    }
                    final int converted = carveTowardsWood(voxels, distance, worst, budget, branches);
                    if (converted == 0) {
                        giveUp[worst] = true;
                        continue;
                    }
                    branchCells += converted;
                    distance = sweep(voxels);
                }
            }

            return finish(voxels, distance, repair, budget, branchCells, leaves);
        }

        /**
         * 从最近的原木出发、只穿叶子做广度优先。
         *
         * <p>原木是 0 号源但**不继续传播** —— 与原版一致：一根原木把 0 递给邻居，一串原木并不会
         * 沿自己搬运叶子距离。
         */
        private static int[] sweep(TreeVoxels voxels) {
            final int[] distance = new int[voxels.cellCount()];
            final int[] queue = new int[voxels.cellCount()];
            int head = 0;
            int tail = 0;

            for (int i = 0; i < distance.length; i++) {
                if (voxels.kindAt(i) == TreeVoxels.LOG) {
                    distance[i] = 0;
                    queue[tail++] = i;
                } else {
                    distance[i] = UNREACHED;
                }
            }

            while (head < tail) {
                final int current = queue[head++];
                final int next = distance[current] + 1;
                final int x = voxels.localX(current);
                final int y = voxels.localY(current);
                final int z = voxels.localZ(current);

                for (int face = 0; face < 6; face++) {
                    final int neighbour = voxels.index(
                            x + FACE_X[face], y + FACE_Y[face], z + FACE_Z[face]);
                    if (neighbour < 0 || voxels.kindAt(neighbour) != TreeVoxels.LEAF) {
                        continue;
                    }
                    if (distance[neighbour] <= next) {
                        continue;
                    }
                    distance[neighbour] = next;
                    queue[tail++] = neighbour;
                }
            }
            return distance;
        }

        private static int worstReachableLeaf(TreeVoxels voxels, int[] distance, int budget,
                                              boolean[] giveUp) {
            int worst = -1;
            int worstDistance = budget;
            for (int i = 0; i < distance.length; i++) {
                if (voxels.kindAt(i) != TreeVoxels.LEAF) {
                    continue;
                }
                if (distance[i] == UNREACHED || giveUp[i]) {
                    continue;
                }
                if (distance[i] > worstDistance) {
                    worstDistance = distance[i];
                    worst = i;
                }
            }
            return worst;
        }

        private static int carveTowardsWood(TreeVoxels voxels, int[] distance, int leaf, int budget,
                                            BranchFactory branches) {
            // 距离 <= 这个值的格子都变木头，于是那片孤叶离新枝尖正好 budget 步。
            final int convertUpTo = distance[leaf] - budget;
            if (convertUpTo < 1) {
                return 0;
            }

            final List<Integer> path = new ArrayList<>();
            int current = leaf;
            while (distance[current] > 0) {
                final int previous = stepTowardsWood(voxels, distance, current);
                if (previous < 0) {
                    break;
                }
                if (distance[previous] >= 1 && distance[previous] <= convertUpTo) {
                    path.add(previous);
                }
                current = previous;
            }
            Collections.reverse(path);   // 现在是「从木头往外」的顺序：1, 2, 3, ...

            int converted = 0;
            for (int cell : path) {
                // 走到第一个不是「被其他树块完全包住」的格子就停：支撑枝存在的意义是从冠层内部
                // 喂叶子；一旦它伸到外面看得见的地方，它就不再是结构，而是从叶子里戳出来的原木。
                // 外面的部分交给剪枝。
                if (!voxels.isBuried(cell)) {
                    break;
                }
                final BlockData wood = branches.at(
                        voxels.localX(cell), voxels.localY(cell), voxels.localZ(cell));
                if (wood == null) {
                    break;
                }
                voxels.set(cell, TreeVoxels.LOG, wood);
                converted++;
            }
            return converted;
        }

        private static int stepTowardsWood(TreeVoxels voxels, int[] distance, int from) {
            final int target = distance[from] - 1;
            final int x = voxels.localX(from);
            final int y = voxels.localY(from);
            final int z = voxels.localZ(from);
            for (int face = 0; face < 6; face++) {
                final int neighbour = voxels.index(
                        x + FACE_X[face], y + FACE_Y[face], z + FACE_Z[face]);
                if (neighbour >= 0 && distance[neighbour] == target) {
                    return neighbour;
                }
            }
            return -1;
        }

        private static Result finish(TreeVoxels voxels, int[] distance, DecayRepair repair,
                                     int budget, int branchCells, LeafStates leaves) {
            int kept = 0;
            int pruned = 0;
            int persisted = 0;

            for (int i = 0; i < distance.length; i++) {
                if (voxels.kindAt(i) != TreeVoxels.LEAF) {
                    continue;
                }
                final int type = voxels.leafTypeAt(i);
                final int d = distance[i];

                if (d > budget) {
                    // CARVE_BRANCH 到这里也退回剪枝：carve 预算用完之后仍够不到的，要么不可达、
                    // 要么不值得再长一根枝。
                    if (repair == DecayRepair.PERSIST && leaves.persistent()[type] != null) {
                        voxels.set(i, TreeVoxels.LEAF, leaves.persistent()[type]);
                        persisted++;
                    } else {
                        voxels.clear(i);
                        pruned++;
                    }
                    continue;
                }

                final BlockData settled = leaves.at(type, Math.max(1, d));
                if (settled == null) {
                    voxels.clear(i);
                    pruned++;
                    continue;
                }
                voxels.set(i, TreeVoxels.LEAF, settled);
                kept++;
            }

            return new Result(kept, pruned, branchCells, persisted);
        }

        private static final int[] FACE_X = {-1, 1, 0, 0, 0, 0};
        private static final int[] FACE_Y = {0, 0, -1, 1, 0, 0};
        private static final int[] FACE_Z = {0, 0, 0, 0, -1, 1};
    }

    /**
     * 删掉没接回树基的木头。上游 {@code build/LooseWood}。
     *
     * <p>树皮扰动是加到距离上的，局部比半径大的地方不是「让表面粗糙」，而是把那一截**删掉**。
     * 细枝尖、被两段夹住的树干都可能这样断开。还连着的读起来是树皮，断开的读起来是悬空原木 ——
     * 后者是烂树最容易被认出来的破绽。
     *
     * <p>连通性按 26 邻居而不是 6：只有棱或角相接的两格看起来仍然是连着的，而斜着走的细枝
     * 正是这么光栅化的 —— 只测面会锯掉视觉上完好的枝条。
     */
    static final class LooseWood {
        private LooseWood() {
        }

        static int prune(TreeVoxels voxels) {
            final boolean[] attached = new boolean[voxels.cellCount()];
            final int[] queue = new int[voxels.cellCount()];
            int head = 0;
            int tail = 0;

            // 从「最低的有木头的那一层」播种 —— 那是树与地面的接触面。只从原点那一列播种，对
            // 足够粗的树干是错的（(0,y,0) 本身可能不是原木格）；而那一列的每一根都播种，
            // 又会把直接浮在树桩上方的碎片当成接上了。
            final int groundLayer = lowestWoodLayer(voxels);
            if (groundLayer == Integer.MIN_VALUE) {
                return 0;
            }
            for (int x = voxels.minX(); x < voxels.minX() + voxels.sizeX(); x++) {
                for (int z = voxels.minZ(); z < voxels.minZ() + voxels.sizeZ(); z++) {
                    final int seed = voxels.index(x, groundLayer, z);
                    if (seed >= 0 && voxels.kindAt(seed) == TreeVoxels.LOG && !attached[seed]) {
                        attached[seed] = true;
                        queue[tail++] = seed;
                    }
                }
            }

            while (head < tail) {
                final int current = queue[head++];
                final int x = voxels.localX(current);
                final int y = voxels.localY(current);
                final int z = voxels.localZ(current);

                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            if (dx == 0 && dy == 0 && dz == 0) {
                                continue;
                            }
                            final int neighbour = voxels.index(x + dx, y + dy, z + dz);
                            if (neighbour < 0 || attached[neighbour]) {
                                continue;
                            }
                            if (voxels.kindAt(neighbour) != TreeVoxels.LOG) {
                                continue;
                            }
                            attached[neighbour] = true;
                            queue[tail++] = neighbour;
                        }
                    }
                }
            }

            int removed = 0;
            for (int i = 0; i < attached.length; i++) {
                if (voxels.kindAt(i) == TreeVoxels.LOG && !attached[i]) {
                    voxels.clear(i);
                    removed++;
                }
            }
            return removed;
        }

        private static int lowestWoodLayer(TreeVoxels voxels) {
            for (int y = voxels.minY(); y < voxels.minY() + voxels.sizeY(); y++) {
                for (int x = voxels.minX(); x < voxels.minX() + voxels.sizeX(); x++) {
                    for (int z = voxels.minZ(); z < voxels.minZ() + voxels.sizeZ(); z++) {
                        if (voxels.kindAt(x, y, z) == TreeVoxels.LOG) {
                            return y;
                        }
                    }
                }
            }
            return Integer.MIN_VALUE;
        }
    }

    // ================================================================ 组装（上游 build/TreeBuilder）

    /**
     * 把 {@link ForkingTrunk} + {@link GhostCanopy} + {@link TreePalette} 拼成一棵树。
     * 上游 {@code build/TreeBuilder}。
     *
     * <h2>两段式，故意的</h2>
     * {@link #build} 做完几何、调色板与衰减求解，一次都不碰世界 —— 给定随机源就是纯函数，因此
     * 没有服务器也能单测。{@link #commit} 是唯一读写世界的地方，每个格子只碰一次。
     */
    static final class TreeBuilder {
        /** 树皮扰动可能把表面顶出半径多少。上游常量，不从扰动里推（扰动是拿不到幅度的函数）。 */
        private static final float BARK_MARGIN = 2.0F;

        private final ForkingTrunk trunk;
        private final GhostCanopy canopy;
        private final TreePalette palette;
        private final LeafStates leaves;
        private final Displacement bark;
        private final DecayRepair repair;
        private final int decayBudget;
        private final int maxCarves;
        private final boolean pruneLooseWood;

        private TreeBuilder(Builder b) {
            this.trunk = b.trunk;
            this.canopy = b.canopy;
            this.palette = b.palette;
            this.leaves = b.leaves;
            this.bark = b.bark;
            this.repair = b.repair;
            this.decayBudget = b.decayBudget;
            this.maxCarves = b.maxCarves;
            this.pruneLooseWood = b.pruneLooseWood;
        }

        static Builder create() {
            return new Builder();
        }

        /**
         * 这棵树允许占哪些树局部格子。
         *
         * <h2>形状为什么必须知道</h2>
         * 覆盖不了的格子不只是「少放一个方块」—— 它会静默让衰减求解失效：树干撞进崖壁时原木在
         * 提交期没了，靠它们支撑的叶子就成了孤儿。所以掩码必须作用在求解**之前**，而不是出口过滤。
         */
        @FunctionalInterface
        interface CellFilter {
            CellFilter ANY = (x, y, z) -> true;

            boolean canOccupy(int x, int y, int z);
        }

        TreeVoxels build(TreeSpace space, Random random) {
            return build(space, random, CellFilter.ANY);
        }

        TreeVoxels build(TreeSpace space, Random random, CellFilter filter) {
            final Skeleton skeleton = trunk.build(space, random);
            final TreeVoxels voxels = allocate(skeleton);

            rasterizeWood(voxels, skeleton, space, random, filter);
            if (pruneLooseWood) {
                LooseWood.prune(voxels);
            }
            rasterizeFoliage(voxels, skeleton, space, random, filter);

            LeafDecay.solve(voxels, repair, decayBudget, maxCarves, leaves,
                    // 被 carve 出来的支撑枝用 Y 轴：carve 是一格一格沿叶子路径走的，工厂拿到的是位置
                    // 而不是方向，没有「一段」可以取轴。支撑枝只是埋在叶子里的几格。
                    (x, y, z) -> palette.log(new LogQuery(
                            space.origin().x() + x, space.origin().z() + z, y, 1)));
            settleColumnTops(voxels);
            return voxels;
        }

        /**
         * 给每个「上面没有原木」的原木最后一次发言权。
         *
         * <p>在衰减求解之后跑：剪枝会删原木、{@code CARVE_BRANCH} 会加原木，所以画树干时上方有原木的
         * 格子到最后可能没了，反过来也一样。
         */
        private void settleColumnTops(TreeVoxels voxels) {
            for (int i = 0; i < voxels.cellCount(); i++) {
                if (voxels.kindAt(i) != TreeVoxels.LOG) {
                    continue;
                }
                final int x = voxels.localX(i);
                final int y = voxels.localY(i);
                final int z = voxels.localZ(i);
                if (voxels.kindAt(x, y + 1, z) == TreeVoxels.LOG) {
                    continue;
                }
                final BlockData state = voxels.stateAt(i);
                final BlockData settled = palette.topOfColumn(state);
                if (settled != null && settled != state) {
                    voxels.set(i, TreeVoxels.LOG, settled);
                }
            }
        }

        /** 把建好的树写进世界，跳过区域外与被地形挡住的格子。返回真正放下的方块数。 */
        int commit(WorldView world, TreeSpace space, TreeVoxels voxels) {
            final Point origin = space.origin();
            final int[] placed = {0};
            voxels.forEach((x, y, z, kind, state) -> {
                // 与 CellFilter 同一条判据，这里只是兜底：带着匹配 filter 建出来的缓冲区不该有东西
                // 被拒，一旦有就说明它绕过了衰减求解。
                if (!space.canWrite(x, z)) {
                    return;
                }
                final int wx = origin.x() + x;
                final int wy = origin.y() + y;
                final int wz = origin.z() + z;
                if (wy < world.minY() || wy > world.maxY() || !world.inRegion(wx, wy, wz)) {
                    return;
                }
                if (!UpstreamComplexTreeFeatures.replaceable(world, wx, wy, wz)) {
                    return;
                }
                if (state == null) {
                    return;
                }
                world.setBlock(wx, wy, wz, state);
                placed[0]++;
            });
            return placed[0];
        }

        /** 真实放置用的格子掩码：在写区内、在世界高度内、且这里允许覆盖。 */
        CellFilter filterFor(WorldView world, TreeSpace space) {
            final Point origin = space.origin();
            return (x, y, z) -> {
                if (!space.canWrite(x, z)) {
                    return false;
                }
                final int wy = origin.y() + y;
                if (wy < world.minY() || wy > world.maxY()) {
                    return false;
                }
                return UpstreamComplexTreeFeatures.replaceable(
                        world, origin.x() + x, wy, origin.z() + z);
            };
        }

        private TreeVoxels allocate(Skeleton skeleton) {
            final Bounds bounds = new Bounds();
            for (Segment s : skeleton.segments()) {
                // 树皮扰动会把表面往外顶，盒子必须让出来，否则裂纹在边缘会被削平。
                final float r = s.maxRadius() + BARK_MARGIN;
                bounds.include(s.from(), r);
                bounds.include(s.to(), r);
            }
            for (Anchor a : skeleton.anchors()) {
                bounds.include(a.position(), canopy.reach() * Math.max(1, a.scale()));
            }
            // 一根段都没有的树会给出一个反的盒子；留一格让下游全部平凡成立。
            if (bounds.empty) {
                bounds.include(new Vec3(0, 0, 0), 1);
            }
            // 树基本身永远在范围内，不管形状怎么决定。
            bounds.include(new Vec3(0, 0, 0), 1);

            return new TreeVoxels(
                    bounds.minX, bounds.minY, bounds.minZ,
                    bounds.maxX - bounds.minX + 1,
                    bounds.maxY - bounds.minY + 1,
                    bounds.maxZ - bounds.minZ + 1
            );
        }

        private void rasterizeWood(TreeVoxels voxels, Skeleton skeleton, TreeSpace space,
                                   Random random, CellFilter filter) {
            for (Segment segment : skeleton.segments()) {
                Volume volume = segment.toVolume();
                if (bark != null) {
                    volume = volume.displace(bark);
                }

                final Bounds bounds = new Bounds();
                final float r = segment.maxRadius() + BARK_MARGIN;
                bounds.include(segment.from(), r);
                bounds.include(segment.to(), r);

                final int axis = axisIndex(segment.axis());
                for (int x = bounds.minX; x <= bounds.maxX; x++) {
                    for (int y = bounds.minY; y <= bounds.maxY; y++) {
                        for (int z = bounds.minZ; z <= bounds.maxZ; z++) {
                            if (!volume.contains(x, y, z)) {
                                continue;
                            }
                            if (!filter.canOccupy(x, y, z)) {
                                continue;
                            }
                            final BlockData state = palette.log(new LogQuery(
                                    space.origin().x() + x, space.origin().z() + z, y, axis));
                            voxels.setLog(x, y, z, state);
                        }
                    }
                }
            }
        }

        private void rasterizeFoliage(TreeVoxels voxels, Skeleton skeleton, TreeSpace space,
                                      Random random, CellFilter filter) {
            final Anchor primary = primaryAnchor(skeleton);
            // 先画主冠层。叶子只填空格，所以两片冠层重叠处的归属由**先画的那片**决定；
            // 如果先画的不是主冠层，调色板想留在这棵树身上的那个标记就会落进已经被占的格子里，
            // 静默消失。顺序就是全部修复，形状一点没变（并集本来就一样）。
            for (Anchor anchor : orderedAnchors(skeleton, primary)) {
                final Volume volume = canopy.volumeAt(anchor, space, random);
                final float reach = canopy.reach() * Math.max(1, anchor.scale()) + 1;
                final boolean isPrimary = anchor == primary;

                final Bounds bounds = new Bounds();
                bounds.include(anchor.position(), reach);

                for (int x = bounds.minX; x <= bounds.maxX; x++) {
                    for (int y = bounds.minY; y <= bounds.maxY; y++) {
                        for (int z = bounds.minZ; z <= bounds.maxZ; z++) {
                            if (!voxels.inBounds(x, y, z)) {
                                continue;
                            }
                            if (voxels.kindAt(x, y, z) != TreeVoxels.EMPTY) {
                                continue;
                            }
                            if (!volume.contains(x, y, z)) {
                                continue;
                            }
                            if (!filter.canOccupy(x, y, z)) {
                                continue;
                            }

                            final int type = palette.leaf(new LeafQuery(
                                    x, y, z,
                                    anchor.position().x, anchor.position().y, anchor.position().z,
                                    isPrimary, random));
                            // 先按类型占格，衰减求解之后才换成带 distance 的最终状态。
                            voxels.setLeafIfEmpty(x, y, z, type, leaves.at(type, 1));
                        }
                    }
                }
            }
        }

        /** 骨架的挂点，把 {@code primary} 挪到最前。按引用比较（两个同位置同尺寸的冠层不能搞混）。 */
        private static List<Anchor> orderedAnchors(Skeleton skeleton, Anchor primary) {
            if (primary == null) {
                return skeleton.anchors();
            }
            final List<Anchor> ordered = new ArrayList<>(skeleton.anchors());
            ordered.removeIf(a -> a == primary);
            ordered.add(0, primary);
            return ordered;
        }

        /**
         * 决定这棵树轮廓的那个挂点：最高的，同高比尺寸。
         *
         * <p>刻意是成品骨架的性质而不是树干形状自报的：形状不知道自己哪条臂赢了高度随机，
         * 而「只标一次」的调色板（一张脸、一圈冠）要的正是赢了的那一个。
         */
        private static Anchor primaryAnchor(Skeleton skeleton) {
            Anchor best = null;
            for (Anchor a : skeleton.anchors()) {
                if (best == null
                        || a.position().y > best.position().y
                        || (a.position().y == best.position().y && a.scale() > best.scale())) {
                    best = a;
                }
            }
            return best;
        }

        private static int axisIndex(Axis axis) {
            return switch (axis) {
                case X -> 0;
                case Y -> 1;
                case Z -> 2;
            };
        }

        /** 树局部空间里可长大的整数盒。 */
        private static final class Bounds {
            private boolean empty = true;
            private int minX;
            private int minY;
            private int minZ;
            private int maxX;
            private int maxY;
            private int maxZ;

            void include(Vec3 point, float radius) {
                final int lowX = (int) Math.floor(point.x - radius);
                final int lowY = (int) Math.floor(point.y - radius);
                final int lowZ = (int) Math.floor(point.z - radius);
                final int highX = (int) Math.ceil(point.x + radius);
                final int highY = (int) Math.ceil(point.y + radius);
                final int highZ = (int) Math.ceil(point.z + radius);
                if (empty) {
                    minX = lowX;
                    minY = lowY;
                    minZ = lowZ;
                    maxX = highX;
                    maxY = highY;
                    maxZ = highZ;
                    empty = false;
                    return;
                }
                minX = Math.min(minX, lowX);
                minY = Math.min(minY, lowY);
                minZ = Math.min(minZ, lowZ);
                maxX = Math.max(maxX, highX);
                maxY = Math.max(maxY, highY);
                maxZ = Math.max(maxZ, highZ);
            }
        }

        static final class Builder {
            private ForkingTrunk trunk;
            private GhostCanopy canopy;
            private TreePalette palette;
            private LeafStates leaves;
            private Displacement bark;
            private DecayRepair repair = DecayRepair.CARVE_BRANCH;
            private int decayBudget = LeafDecay.VANILLA_BUDGET;
            private int maxCarves = 32;
            private boolean pruneLooseWood = true;

            Builder trunk(ForkingTrunk shape) {
                this.trunk = shape;
                return this;
            }

            Builder canopy(GhostCanopy shape) {
                this.canopy = shape;
                return this;
            }

            Builder palette(TreePalette treePalette) {
                this.palette = treePalette;
                return this;
            }

            Builder leaves(LeafStates leafStates) {
                this.leaves = leafStates;
                return this;
            }

            Builder bark(Displacement displacement) {
                this.bark = displacement;
                return this;
            }

            Builder repair(DecayRepair decayRepair) {
                this.repair = decayRepair;
                return this;
            }

            Builder maxCarves(int carves) {
                this.maxCarves = carves;
                return this;
            }

            Builder pruneLooseWood(boolean prune) {
                this.pruneLooseWood = prune;
                return this;
            }

            TreeBuilder build() {
                if (trunk == null) {
                    throw new IllegalStateException("一棵树需要树干形状");
                }
                if (canopy == null) {
                    throw new IllegalStateException("一棵树需要冠层形状");
                }
                if (palette == null) {
                    throw new IllegalStateException("一棵树需要调色板");
                }
                if (leaves == null) {
                    throw new IllegalStateException("一棵树需要叶子状态表");
                }
                return new TreeBuilder(this);
            }
        }
    }
}
