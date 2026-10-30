package dev.mumu.betternether.worldgen;

import dev.mumu.betternether.terrain.OpenSimplexNoise;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 锚树三件套：{@code AnchorTreeFeature} / {@code AnchorTreeBranchFeature} /
 * {@code AnchorTreeRootFeature} 逐行移植。
 *
 * <h2>为什么是三个 feature 而不是一棵树</h2>
 * 上游 26.3 在倒置森林里把这三条 placed_feature 并排挂：
 * {@code anchor_tree} 只长「木」（干 + 环状枝 + 巨灵芝），{@code anchor_tree_branch} 单独
 * 从天花板垂下带叶与藤的枝，{@code anchor_tree_root} 从天花板垂一条抛物线状的根。
 * 所以本类按 {@link Kind} 分派，三条各自独立、各自消耗随机数 ——
 * 把它们合成一棵「树」会同时改掉三条的随机顺序与形状。
 *
 * <h2>固定 seed 的噪声是形状的一部分</h2>
 * 三处判定用 {@code new OpenSimplexNoise(2145)} 的 {@code eval}：灵芝/苔藓的分布、
 * 圆柱表面的粗糙、墙面植物的成片。上游那份是
 * {@code de.ambertation.wover.math.api.noise.OpenSimplexNoise}（{@code WorldWeaver/release/26.3},
 * {@code wover-math-api/.../noise/OpenSimplexNoise.java}）；本仓库 {@code terrain/OpenSimplexNoise}
 * 与它**逐 token 相同**（2D/3D eval、permutation 生成、gradients 表；本地只少 4D），种子照抄 2145。
 *
 * <h2>与上游不同的地方（逐条列清，全部是「做不到」而不是「懒得做」）</h2>
 * <ol>
 *   <li><b>城市结构</b>：{@code CityStructure.buildingsWithin(...)} 是上游为避免树穿墙而做的
 *       城市查表。本移植没有城市结构，所以这段判定跳过（不消耗随机数，不影响形状）。</li>
 *   <li><b>巨灵芝的第二种形态</b>：{@code LucisFeature.grow} 放的是
 *       {@code lucis_mushroom}/{@code lucis_spore}，当前资源包只收录了 {@code giant_lucis}。
 *       走到那个分支时**照常消耗它内部的 {@code nextInt(3)}**，然后不放置 —— 这样后面的随机流不错位。</li>
 *   <li><b>锚树藤</b>：{@code anchor_tree_vine} 未收录，藤的挂接与「藤换叶」整段跳过；
 *       抽到的 {@code randRange(3, length)} 照常消耗。</li>
 *   <li><b>写区边界盒</b>：上游 {@code BlocksHelper.decorationBounds(...)} =
 *       {@code WriteZone.of(level).toBoundingBox(minY, maxY)}。这里用同包
 *       {@link UpstreamSimpleTreeFeatures.Zone#toBox(int, int)}（同一份算术），锚树本体连
 *       {@code blockBox.isInside(bpos)} 与四向墙面植物的 {@code blockBox.minZ()/maxZ()/minX()/maxX()}
 *       条件一起照抄；垂根保留上游那种「循环只看 y、盒子只用于灵芝」的口径。</li>
 *   <li><b>{@code canSurvive} 恒真、集合迭代顺序</b>：同 {@link RubeusGeometry} 的两条已知缺口。</li>
 * </ol>
 */
final class AnchorTreeGeometry implements Feature.Custom.Placer {
    /** 上游 {@code AnchorTreeFeature.NOISE}：固定种子，形状的一部分。 */
    private static final OpenSimplexNoise NOISE = new OpenSimplexNoise(2145);
    /** 上游 {@code SHAPED_FOR_DEPTH}：树是按 128 层下界设计的。 */
    private static final int SHAPED_FOR_DEPTH = 128;
    /** {@code LeavesBlock.DECAY_DISTANCE}。 */
    private static final int DECAY_DISTANCE = 7;
    /** {@code AnchorTreeFeature#circleLinesEnds} 的水平上限。 */
    private static final int MAX_DIST = 16;
    /** 上游 {@code AnchorTreeBranchFeature} 的曲线点。 */
    private static final float[] CURVE_X = {9F, 7F, 1.5F, 0.5F, 3F, 7F};
    private static final float[] CURVE_Y = {-20F, -17F, -12F, -4F, 0F, 2F};
    private static final int MIDDLE_Y = 10;

    enum Kind {
        TREE, BRANCH, ROOT
    }

    private final Kind kind;
    private final BlockData log;
    private final BlockData bark;
    /** 墙面植物：行 = 上游 wallPlants 数组的序号，列 = HORIZONTAL 的方向（N,S,E,W）。 */
    private final BlockData[][] wallPlants = new BlockData[4][4];
    private final BlockData mossCover;
    private final BlockData[] giantLucis = new BlockData[16];
    private final BlockData[] branchLeaves;
    /** 小灵芝的三档状态：{@code center} 一格、{@code side[4]}（与 HORIZONTAL 同序）、{@code corner[4]}。 */
    private final BlockData lucisCenter;
    private final BlockData[] lucisSide = new BlockData[HORIZONTAL.length];
    private final BlockData[] lucisCorner = new BlockData[4];
    /** 灵芝孢子四朝向状态（与 HORIZONTAL 同序）：既用于「是不是孢子」，也用于反查朝向。 */
    private final BlockData[] lucisSpore = new BlockData[HORIZONTAL.length];
    /** 孢子状态的实例集（{@code isAnyOf} 用）。 */
    private final Set<BlockData> lucisSporeSet;
    private final BlockData air;
    private final BlockData[] brickTiles;
    private final Set<BlockData> logs;
    private final Set<BlockData> leavesSet;
    /** id 级判据（{@code blockId} 精确；见 isAnyOfIds 的说明）。 */
    private final Set<String> logIds = Set.of("betternether:anchor_tree_log",
            "betternether:anchor_tree_bark");
    private final Set<String> leafIds = Set.of("betternether:anchor_tree_leaves");
    private final Set<String> giantLucisIds = Set.of("betternether:giant_lucis");
    private final Set<String> sporeIds = Set.of("betternether:lucis_spore");
    private final Set<String> brickIds = Set.of("minecraft:nether_bricks",
            "betternether:nether_brick_tile_large", "betternether:nether_brick_tile_small");

    AnchorTreeGeometry(UpstreamComplexTreeFeatures.BlockResolver resolver, Kind kind) {
        this.kind = kind;
        // 上游 AnchorTreeFeature 从不 setValue(AXIS)（树皮/原木保持 registerDefaultState 的 Y），
        // 所以显式传 axis=y，避免 CE 默认轴不同时落下别的状态。
        this.log = UpstreamComplexTreeFeatures.block(resolver, "betternether:anchor_tree_log",
                UpstreamComplexTreeFeatures.props("axis", "y"));
        this.bark = UpstreamComplexTreeFeatures.block(resolver, "betternether:anchor_tree_bark",
                UpstreamComplexTreeFeatures.props("axis", "y"));
        // 顺序照抄上游 wallPlants 数组：jungle_moss ×2、wall_mushroom_brown、wall_mushroom_red。
        final String[] plants = {
                "betternether:jungle_moss", "betternether:jungle_moss",
                "betternether:wall_mushroom_brown", "betternether:wall_mushroom_red"
        };
        for (int i = 0; i < plants.length; i++) {
            for (int dir = 0; dir < HORIZONTAL.length; dir++) {
                wallPlants[i][dir] = UpstreamComplexTreeFeatures.block(resolver, plants[i],
                        UpstreamComplexTreeFeatures.props("facing",
                                HORIZONTAL[dir].name().toLowerCase(java.util.Locale.ROOT)));
            }
        }
        this.mossCover = UpstreamComplexTreeFeatures.block(resolver, "betternether:moss_cover", Map.of());
        // 巨灵芝的四个 side 布尔。索引 = north | south<<1 | east<<2 | west<<3。
        for (int mask = 0; mask < 16; mask++) {
            giantLucis[mask] = UpstreamComplexTreeFeatures.block(resolver, "betternether:giant_lucis",
                    UpstreamComplexTreeFeatures.props(
                            "north", Boolean.toString((mask & 1) != 0),
                            "south", Boolean.toString((mask & 2) != 0),
                            "east", Boolean.toString((mask & 4) != 0),
                            "west", Boolean.toString((mask & 8) != 0),
                            "up", "false", "down", "false"));
        }
        // 小灵芝（LucisFeature）：center 一格 + side 四向 + corner 四向。属性名与取值来自上游
        // （shape = center/side/corner，facing = north/south/east/west），CE 侧已按原样发布。
        this.lucisCenter = UpstreamComplexTreeFeatures.block(resolver, "betternether:lucis_mushroom",
                UpstreamComplexTreeFeatures.props("shape", "center", "facing", "north"));
        for (int i = 0; i < HORIZONTAL.length; i++) {
            final String facing = HORIZONTAL[i].name().toLowerCase(java.util.Locale.ROOT);
            lucisSide[i] = UpstreamComplexTreeFeatures.block(resolver, "betternether:lucis_mushroom",
                    UpstreamComplexTreeFeatures.props("shape", "side", "facing", facing));
        }
        // 上游四处 corner 的朝向是 south / east / west / north，顺序与它在代码里的四处判定一致。
        final String[] cornerFacings = {"south", "east", "west", "north"};
        for (int i = 0; i < cornerFacings.length; i++) {
            lucisCorner[i] = UpstreamComplexTreeFeatures.block(resolver, "betternether:lucis_mushroom",
                    UpstreamComplexTreeFeatures.props("shape", "corner", "facing", cornerFacings[i]));
        }
        for (int i = 0; i < HORIZONTAL.length; i++) {
            lucisSpore[i] = UpstreamComplexTreeFeatures.block(resolver, "betternether:lucis_spore",
                    UpstreamComplexTreeFeatures.props(
                            "facing", HORIZONTAL[i].name().toLowerCase(java.util.Locale.ROOT)));
        }
        this.lucisSporeSet = UpstreamComplexTreeFeatures.identities(lucisSpore);

        branchLeaves = new BlockData[DECAY_DISTANCE + 1];
        for (int distance = 1; distance <= DECAY_DISTANCE; distance++) {
            branchLeaves[distance] = UpstreamComplexTreeFeatures.block(resolver,
                    "betternether:anchor_tree_leaves",
                    UpstreamComplexTreeFeatures.props(
                            "distance", Integer.toString(distance), "persistent", "true"));
        }
        this.air = UpstreamComplexTreeFeatures.block(resolver, "minecraft:air", Map.of());
        this.brickTiles = new BlockData[]{
                UpstreamComplexTreeFeatures.block(resolver, "betternether:nether_brick_tile_large", Map.of()),
                UpstreamComplexTreeFeatures.block(resolver, "betternether:nether_brick_tile_small", Map.of())
        };
        this.logs = UpstreamComplexTreeFeatures.identities(log, bark);
        final BlockData[] allLeaves = new BlockData[DECAY_DISTANCE];
        for (int distance = 1; distance <= DECAY_DISTANCE; distance++) {
            allLeaves[distance - 1] = branchLeaves[distance];
        }
        this.leavesSet = UpstreamComplexTreeFeatures.identities(allLeaves);

        if (UpstreamComplexTreeFeatures.block(resolver, "betternether:anchor_tree_vine", Map.of()) == null) {
            UpstreamComplexTreeFeatures.note(
                    "anchor_tree_vine 未收录：锚树枝/根的垂藤与「藤换叶」整段跳过（抽样照做）");
        }
        if (lucisCenter == null) {
            UpstreamComplexTreeFeatures.note("lucis_mushroom 未收录：锚树根上的小灵芝跳过（抽样照做）");
        }
    }

    // ================================================================ 入口

    @Override
    public boolean place(WorldView world, Point point) {
        final int maxHeight = UpstreamComplexTreeFeatures.maxHeight(world);
        return switch (kind) {
            case TREE -> placeTree(world, point, maxHeight);
            case BRANCH -> placeBranch(world, point, maxHeight);
            case ROOT -> placeRoot(world, point, maxHeight);
        };
    }

    // ================================================================ AnchorTreeFeature

    private boolean placeTree(WorldView world, Point point, int maxHeight) {
        // 把树往区块中心拉：它的水平触角有 32 格，靠近写区边缘就会被切平。
        final Point snapped = new Point(toMiddle(point.x()), point.y(), toMiddle(point.z()));
        final int drop = UpstreamComplexTreeFeatures.downRay(
                world, snapped.x(), snapped.y(), snapped.z(), maxHeight);
        final Point floor = snapped.offset(0, -drop, 0);
        // 上游这里还有一道「城市建筑」检查；本移植没有城市结构，见类注释。
        growTree(world, snapped, floor, world.random(), maxHeight);
        // 上游对世界生成路径无条件返回 true（结果只喂「放到了没有」的统计）。
        return true;
    }

    /** 上游 {@code toMiddle}：把坐标推到 {@code 0xF == 7} 那一列/行。 */
    private static int toMiddle(int value) {
        return value + (7 - (value & 0xF));
    }

    /**
     * 上游 {@code AnchorTreeFeature#grow}（自然生成路径，{@code airOnly = false}）。
     *
     * <p>{@code up} 是天花板端、{@code down} 是地板端：树自己不在乎锚在哪一头。
     *
     * <p>三条高度阀（45 / 64 / 90）是绝对 y，按 128 层下界调的，决定灵芝区间与苔藓区间。
     */
    private boolean growTree(WorldView world, Point up, Point down, Random random, int maxHeight) {
        final float scaleFactor = maxHeight / (float) SHAPED_FOR_DEPTH;
        final int height64 = (int) (maxHeight / 4.0 + UpstreamComplexTreeFeatures.randRange(0F, 32F, random));
        final int height45 = (int) (20 + UpstreamComplexTreeFeatures.randRange(0F, 20 * scaleFactor, random));
        final int height90 = (int) (maxHeight / 2.0
                + UpstreamComplexTreeFeatures.randRange(0F, 15 * scaleFactor, random));
        final int segmentLength = (int) ((15 + UpstreamComplexTreeFeatures.randRange(
                0F, 5 * scaleFactor, random)) * scaleFactor);

        if (up.y() - down.y() < 30) {
            return false;
        }

        final int pd = UpstreamComplexTreeFeatures.downRay(
                world, down.x(), down.y(), down.z(), maxHeight) + 1;
        for (int i = 0; i < 5; i++) {
            if (isBrick(world, down.x(), down.y() - pd - i, down.z())) {
                return false;
            }
        }

        final Point trunkTop = lerp(down, up, 0.6);
        final Point trunkBottom = lerp(down, up, 0.3);

        int count = (trunkTop.y() - trunkBottom.y()) / 7;
        if (count < 2) {
            count = 2;
        }
        final List<Point> spine = line(trunkBottom, trunkTop, count, random, 2.5);

        final UpstreamComplexTreeFeatures.Scratch scratch = new UpstreamComplexTreeFeatures.Scratch();
        scratch.blocks.clear();

        int branches = Math.min(7, Math.max(3, (up.y() - down.y()) / (int) (10 * scaleFactor) - 1));
        final double radius = Math.min(7, Math.max(3.5, (up.y() - down.y()) / 15));

        drawLine(spine, radius + (0.5 * scaleFactor), maxHeight, world, scratch);

        buildBigCircle(world, up, trunkTop, segmentLength, branches, 2,
                random.nextDouble() * Math.PI * 2, radius, random, maxHeight, scratch);
        buildBigCircle(world, up, trunkBottom, -segmentLength, branches, 2,
                random.nextDouble() * Math.PI * 2, radius, random, maxHeight, scratch);

        final int offset = random.nextInt(4);
        final int minBuildHeight = world.minY() + 1;
        final int maxBuildHeight = maxHeight - 2;
        // 上游：decorationBounds(level, up, minBuildHeight, MAX_HEIGHT - 2) = 写区水平框 + 这段 y。
        final UpstreamSimpleTreeFeatures.Box box = UpstreamSimpleTreeFeatures.Zone
                .of(world, up.x(), up.y(), up.z())
                .toBox(minBuildHeight, maxBuildHeight);
        // 灵芝密度按树干占比缩放：树干跨满整个洞窟时才用 32。
        final int mushroomChance = (int) (32 * scaleFactor);

        for (Point here : scratch.blocks) {
            if (!box.isInside(here.x(), here.y(), here.z())) {
                continue;
            }
            final BlockData state = world.blockAt(here.x(), here.y(), here.z());
            // 只覆盖「下界地面」或「可替换」。
            if (!UpstreamComplexTreeFeatures.ground(world, here.x(), here.y(), here.z())
                    && !UpstreamComplexTreeFeatures.replaceable(world, here.x(), here.y(), here.z())) {
                continue;
            }
            final boolean blockUp = scratch.blocks.contains(here.offset(0, 1, 0));
            final boolean blockDown = scratch.blocks.contains(here.offset(0, -1, 0));
            final BlockData trunk = blockUp && blockDown ? log : bark;
            if (trunk != null) {
                world.setBlock(here.x(), here.y(), here.z(), trunk);
            }

            if (here.y() > height45 && here.y() < height90 && (here.y() & 3) == offset
                    && NOISE.eval(here.x() * 0.1, here.y() * 0.1, here.z() * 0.1) > 0) {
                if (random.nextInt(mushroomChance) == 0
                        && !scratch.blocks.contains(here.offset(0, 0, -1))) {
                    makeMushroom(world, here.offset(0, 0, -1), random.nextDouble() * 3 + 1.5, box);
                }
                if (random.nextInt(mushroomChance) == 0
                        && !scratch.blocks.contains(here.offset(0, 0, 1))) {
                    makeMushroom(world, here.offset(0, 0, 1), random.nextDouble() * 3 + 1.5, box);
                }
                if (random.nextInt(mushroomChance) == 0
                        && !scratch.blocks.contains(here.offset(1, 0, 0))) {
                    makeMushroom(world, here.offset(1, 0, 0), random.nextDouble() * 3 + 1.5, box);
                }
                if (random.nextInt(mushroomChance) == 0
                        && !scratch.blocks.contains(here.offset(-1, 0, 0))) {
                    makeMushroom(world, here.offset(-1, 0, 0), random.nextDouble() * 3 + 1.5, box);
                }
            }

            if (here.y() > height64) {
                final Point abovePoint = here.offset(0, 1, 0);
                if (!blockUp && UpstreamComplexTreeFeatures.replaceable(
                        world, abovePoint.x(), abovePoint.y(), abovePoint.z()) && mossCover != null) {
                    world.setBlock(abovePoint.x(), abovePoint.y(), abovePoint.z(), mossCover);
                }

                if (NOISE.eval(here.x() * 0.05, here.y() * 0.05, here.z() * 0.05) > 0) {
                    final int plantIndex = random.nextInt(wallPlants.length);
                    for (int dir = 0; dir < HORIZONTAL.length; dir++) {
                        final Point.Direction direction = HORIZONTAL[dir];
                        final Point at = here.offset(direction.dx(), 0, direction.dz());
                        // 上游对四个方向各有一个盒子边界条件（north → minZ、south → maxZ、
                        // east → maxX、west → minX），少了它就会在写区外缘多一圈贴墙植物。
                        if (random.nextInt(8) == 0 && !scratch.blocks.contains(at)
                                && UpstreamComplexTreeFeatures.air(world, at.x(), at.y(), at.z())
                                && insideBoxEdge(box, direction, at)) {
                            final BlockData plant = wallPlants[plantIndex][dir];
                            if (plant != null) {
                                world.setBlock(at.x(), at.y(), at.z(), plant);
                            }
                        }
                    }
                }
            }
        }
        return true;
    }

    /** 上游 {@code buildBigCircle}：递归的「圆环端点 → 每段画一条粗线 → 在每个端点再来一轮」。 */
    private void buildBigCircle(WorldView world, Point seedPos, Point from, int length, int count,
                                int iteration, double angle, double size, Random random,
                                int maxHeight, UpstreamComplexTreeFeatures.Scratch scratch) {
        if (iteration < 0) {
            return;
        }
        final List<List<Point>> lines = circleLinesEnds(world, seedPos, from, angle, count, length,
                Math.abs(length) * 0.7, random, iteration == 0, maxHeight);
        final double sizeSmall = size * 0.8;
        int nextLength = (int) (length * 0.8);
        double nextAngle = angle + Math.PI * 4 / count;
        nextAngle += random.nextDouble() * nextAngle * 0.75;
        for (List<Point> path : lines) {
            drawLine(path, size, maxHeight, world, scratch);
            buildBigCircle(world, seedPos, path.get(1), nextLength, count, iteration - 1,
                    nextAngle, sizeSmall, random, maxHeight, scratch);
        }
    }

    /** 上游 {@code drawLine}：逐 y 扫，每层用余弦插值定位圆心，再压一个圆盘。 */
    private void drawLine(List<Point> path, double radius, int maxHeight, WorldView world,
                          UpstreamComplexTreeFeatures.Scratch scratch) {
        for (int i = 0; i < path.size() - 1; i++) {
            Point a = path.get(i);
            Point b = path.get(i + 1);
            if (b.y() < a.y()) {
                final Point swap = b;
                b = a;
                a = swap;
            }
            double max = b.y() - a.y();
            if (max < 1) {
                max = 1;
            }
            for (int y = a.y(); y <= b.y(); y++) {
                cylinder(lerpCos(a, b, y, (y - a.y()) / max), radius,
                        world.minY(), maxHeight, scratch);
            }
        }
    }

    /**
     * 一个水平圆盘，半径被噪声扰动。
     *
     * <p>地板截断相对 {@code minY} 而不是绝对 y=2：下界里两者一致，换到负 minY 的世界才不会被
     * 「y>2」这道绝对门槛整段砍掉。
     */
    private void cylinder(Point centre, double radius, int minY, int maxHeight,
                          UpstreamComplexTreeFeatures.Scratch scratch) {
        final int x1 = UpstreamComplexTreeFeatures.floor(centre.x() - radius);
        final int z1 = UpstreamComplexTreeFeatures.floor(centre.z() - radius);
        final int x2 = UpstreamComplexTreeFeatures.floor(centre.x() + radius + 1);
        final int z2 = UpstreamComplexTreeFeatures.floor(centre.z() + radius + 1);
        final double r2 = radius * radius;

        for (int x = x1; x <= x2; x++) {
            int px2 = x - centre.x();
            px2 *= px2;
            for (int z = z1; z <= z2; z++) {
                int pz2 = z - centre.z();
                pz2 *= pz2;
                final double noisy = NOISE.eval(x * 0.5, centre.y() * 0.5, z * 0.5) * 0.25 + 0.75;
                if (px2 + pz2 <= r2 * noisy
                        && centre.y() > minY + 2 && centre.y() < maxHeight - 2) {
                    scratch.blocks.add(new Point(x, centre.y(), z));
                }
            }
        }
    }

    /** 上游 {@code circleLinesEnds}：一圈辐射状端点，可选「找到地表再往下/往上续一段」。 */
    private List<List<Point>> circleLinesEnds(WorldView world, Point seedPos, Point from,
                                              double startAngle, int count, int length,
                                              double inRadius, Random random, boolean findSurface,
                                              int maxHeight) {
        final List<List<Point>> result = new ArrayList<>(count);
        final double step = Math.PI * 2 / count;
        double angle = startAngle;
        for (int i = 0; i < count; i++) {
            // 上游 MHelper.nextDouble(random, 0.25) = nextDouble() * 0.25（不是 randRange）。
            double radius = inRadius * (random.nextDouble() * 0.25 + 0.8);

            double x = from.x() + Math.sin(angle) * radius;
            if (x - seedPos.x() > MAX_DIST) {
                x = seedPos.x() + MAX_DIST - random.nextInt(10);
            }
            if (x - seedPos.x() < -MAX_DIST) {
                x = seedPos.x() - MAX_DIST + random.nextInt(10);
            }

            double z = from.z() + Math.cos(angle) * radius;
            if (z - seedPos.z() > MAX_DIST) {
                z = seedPos.z() + MAX_DIST - random.nextInt(10);
            }
            if (z - seedPos.z() < -MAX_DIST) {
                z = seedPos.z() - MAX_DIST + random.nextInt(10);
            }

            final Point end = new Point(
                    (int) x,
                    (int) (from.y() + length + length * random.nextDouble() * 0.5),
                    (int) z
            );
            final List<Point> element = new ArrayList<>(2);
            element.add(from);
            element.add(end);
            result.add(element);

            if (findSurface && end.y() > world.minY() + 2 && end.y() < maxHeight - 2) {
                final int dist = length < 0
                        ? -UpstreamComplexTreeFeatures.downRay(world, end.x(), end.y(), end.z(),
                        Math.abs(length * 2))
                        : UpstreamComplexTreeFeatures.upRay(world, end.x(), end.y(), end.z(),
                        Math.abs(length * 2));
                if (dist > 0) {
                    if (Math.abs(seedPos.x() - x) > MAX_DIST || Math.abs(seedPos.z() - z) > MAX_DIST) {
                        radius = 2;
                    }
                    result.addAll(circleLinesEnds(world, seedPos, end,
                            UpstreamComplexTreeFeatures.randRange(0F, 360F, random),
                            radius < 5 ? 1 : (count % 2 + 1),
                            dist, radius / 2, random, findSurface, maxHeight));
                }
            }

            angle += step;
        }
        return result;
    }

    /**
     * 上游 {@code makeMushroom}：一坨巨灵芝，四个 side 布尔按邻居是不是自己决定。
     *
     * <p>{@code box} 是上游那道 {@code bounds.isInside(p)}：灵芝的圆盘会伸出树干之外，必须被写区
     * 边界盒裁掉，否则跨区块时会在区域外留下一圈空洞。
     */
    private void makeMushroom(WorldView world, Point centre, double radius,
                              UpstreamSimpleTreeFeatures.Box box) {
        if (!UpstreamComplexTreeFeatures.replaceable(
                world, centre.x(), centre.y(), centre.z())) {
            return;
        }
        final int x1 = UpstreamComplexTreeFeatures.floor(centre.x() - radius);
        final int z1 = UpstreamComplexTreeFeatures.floor(centre.z() - radius);
        final int x2 = UpstreamComplexTreeFeatures.floor(centre.x() + radius + 1);
        final int z2 = UpstreamComplexTreeFeatures.floor(centre.z() + radius + 1);
        final double r2 = radius * radius;

        final List<Point> placed = new ArrayList<>();
        for (int x = x1; x <= x2; x++) {
            int px2 = x - centre.x();
            px2 *= px2;
            for (int z = z1; z <= z2; z++) {
                int pz2 = z - centre.z();
                pz2 *= pz2;
                if (px2 + pz2 <= r2) {
                    final Point at = new Point(x, centre.y(), z);
                    if (UpstreamComplexTreeFeatures.replaceable(world, at.x(), at.y(), at.z())
                            && box.isInside(at.x(), at.y(), at.z())) {
                        placed.add(at);
                    }
                }
            }
        }

        for (Point at : placed) {
            int mask = 0;
            if (!isGiantLucis(world, at.x(), at.y(), at.z() - 1)) {
                mask |= 1;
            }
            if (!isGiantLucis(world, at.x(), at.y(), at.z() + 1)) {
                mask |= 2;
            }
            if (!isGiantLucis(world, at.x() + 1, at.y(), at.z())) {
                mask |= 4;
            }
            if (!isGiantLucis(world, at.x() - 1, at.y(), at.z())) {
                mask |= 8;
            }
            final BlockData state = giantLucis[mask];
            if (state != null) {
                world.setBlock(at.x(), at.y(), at.z(), state);
            }
        }
    }

    /**
     * 邻居是不是巨灵芝（用来决定四个 side 布尔）。
     *
     * <p>id 优先：包里 {@code giant_lucis} 是单状态，按材质/状态比对也能中，但 id 判据是精确的，
     * 而且不受「别的自定义方块共用同一宿主材质」影响。
     */
    private boolean isGiantLucis(WorldView world, int x, int y, int z) {
        final String id = world.blockId(x, y, z);
        if (id != null) {
            return giantLucisIds.contains(id);
        }
        final BlockData state = world.blockAt(x, y, z);
        if (state == null) {
            return false;
        }
        for (BlockData candidate : giantLucis) {
            if (candidate != null && (candidate == state || state.matches(candidate))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 上游那段「下面 5 格里有下界砖就放弃」。
     *
     * <p>id 优先：{@code blockId} 对 CE 方块是精确身份，{@code nether_brick_tile_large/small}
     * 也因此能判准；{@code getMaterial()} 只在问不到 id 时作退路（原版下界砖本来就是
     * {@code Material.NETHER_BRICKS}，这条退路对它是准的）。
     */
    private boolean isBrick(WorldView world, int x, int y, int z) {
        final String id = world.blockId(x, y, z);
        if (id != null) {
            return brickIds.contains(id);
        }
        final BlockData state = world.blockAt(x, y, z);
        if (state == null) {
            return false;
        }
        if (state.getMaterial() == Material.NETHER_BRICKS) {
            return true;
        }
        for (BlockData tile : brickTiles) {
            if (tile != null && (tile == state || state.matches(tile))) {
                return true;
            }
        }
        return false;
    }

    // ================================================================ AnchorTreeBranchFeature

    /**
     * 一条从天花板垂下来的带叶枝。上游 {@code AnchorTreeBranchFeature}。
     *
     * <p>高度门槛 {@code 56 + nextInt(20)} 让它只长在高处；曲线是
     * {@link #CURVE_X}/{@link #CURVE_Y} 的倒挂版本（y 为负），所以读起来是「往下垂」而不是「往上长」。
     */
    private boolean placeBranch(WorldView world, Point point, int maxHeight) {
        final float scaleFactor = maxHeight / 128.0F;
        final Random random = world.random();
        if (point.y() < 56 + random.nextInt((int) (20 * scaleFactor))) {
            return false;
        }
        // 上游这里还有城市建筑距离检查（见类注释）。
        // 上游 AnchorTreeBranchFeature#grow 里一次 WriteZone.of(world)。
        final UpstreamSimpleTreeFeatures.Zone zone =
                UpstreamSimpleTreeFeatures.Zone.of(world, point.x(), point.y(), point.z());
        return growBranch(world, point, random, scaleFactor, zone);
    }

    private boolean growBranch(WorldView world, Point base, Random random, float scaleFactor,
                               UpstreamSimpleTreeFeatures.Zone zone) {
        final UpstreamComplexTreeFeatures.Scratch scratch = new UpstreamComplexTreeFeatures.Scratch();
        final Map<Point, Integer> leafDistance = new LinkedHashMap<>();
        if (air != null) {
            world.setBlock(base.x(), base.y(), base.z(), air);
        }
        final float scale = UpstreamComplexTreeFeatures.randRange(0.5F, 1F, random);
        final int minCount = scale < 0.75 ? 3 : 4;
        final int maxCount = scale < 0.75 ? 5 : 7;
        final int count = UpstreamComplexTreeFeatures.randRange(minCount, maxCount, random);

        for (int n = 0; n < count; n++) {
            final float branchSize = UpstreamComplexTreeFeatures.randRange(0.5F, 0.8F, random) * scale;
            final float angle = n * UpstreamComplexTreeFeatures.PI2 / count;
            float radius = CURVE_X[0] * branchSize * scaleFactor;
            int x1 = Math.round(base.x() + radius * (float) Math.cos(angle)
                    + UpstreamComplexTreeFeatures.randRange(-2F, 2F, random) * branchSize);
            int y1 = Math.round(base.y() + CURVE_Y[0] * branchSize
                    + UpstreamComplexTreeFeatures.randRange(-2F, 2F, random) * branchSize);
            int z1 = Math.round(base.z() + radius * (float) Math.sin(angle)
                    + UpstreamComplexTreeFeatures.randRange(-2F, 2F, random) * branchSize);
            float crownR = 9 * branchSize;
            if (crownR < 1.5F) {
                crownR = 1.5F;
            }
            // 冠层按可用空间收：标准 128 层下界下这是空操作（触及的正好是特征保证的 16 格）。
            // 收不到 1.5 就不画冠层 —— 一格叶子的「冠层」不如没有，枝条本身照画。
            final float fitted = zone.fitRadius(x1, z1, crownR, 1.5F);
            if (fitted >= 0) {
                crownBranch(world, new Point(x1, y1 + 1, z1), Math.min(crownR, fitted), random,
                        scaleFactor, leafDistance);
            }

            final int middle = Math.round(base.y()
                    + (MIDDLE_Y + UpstreamComplexTreeFeatures.randRange(-2, 2, random)) * branchSize);
            boolean generate = true;
            for (int i = 1; i < CURVE_X.length && generate; i++) {
                radius = CURVE_X[i] * branchSize;
                int x2 = Math.round(base.x() + radius * (float) Math.cos(angle)
                        + UpstreamComplexTreeFeatures.randRange(-2F, 2F, random) * branchSize);
                int y2 = Math.round(base.y() + CURVE_Y[i] * branchSize + (CURVE_Y[i] > 0
                        ? UpstreamComplexTreeFeatures.randRange(-2F, 2F, random) * branchSize
                        : 0));
                int z2 = Math.round(base.z() + radius * (float) Math.sin(angle)
                        + UpstreamComplexTreeFeatures.randRange(-2F, 2F, random) * branchSize);

                if (CURVE_Y[i] >= 0) {
                    // 抬头的这段必须落在可替换的位置；找不到就整条截断。
                    if (canReplace(world, new Point(x2, y2, z2))) {
                        boolean noGround = true;
                        for (int d = 1; d < 3; d++) {
                            if (!canReplace(world, new Point(x2, y2 - d, z2))) {
                                y2 -= d;
                                noGround = false;
                                break;
                            }
                        }
                        if (noGround) {
                            x2 = base.x();
                            y2 = base.y();
                            generate = false;
                        }
                    }
                }

                lineBranch(x1, y1, z1, x2, y2, z2, middle, scratch);
                x1 = x2;
                y1 = y2;
                z1 = z2;
            }
        }

        pruneTopAndMiddle(scratch);

        for (Point here : scratch.points) {
            final boolean columned = scratch.points.contains(here.offset(0, 1, 0))
                    && scratch.points.contains(here.offset(0, -1, 0));
            final BlockData state = columned ? log : bark;
            if (state != null) {
                world.setBlock(here.x(), here.y(), here.z(), state);
            }
            fillLeavesAround(world, here, leafDistance);
            updateSdfFrom(here, scratch.logsDist);
        }

        // 上游在这里先 context.clear()（会把 LOGS_DIST 一起清空）再 updateDistances()，所以那次
        // 回写实际上遍历的是空表。照抄这个顺序：叶子的 persistent=true 本来也不依赖 distance，
        // 而删掉这一步会让「上游其实什么都没做」这件事从代码里消失。
        scratch.clear();
        updateDistances(world, scratch.logsDist, leafDistance);
        scratch.logsDist.clear();
        return true;
    }

    /**
     * 枝条冠层：先画球壳，再按「往下还能垂多远」挂叶子；垂得够长的地方把中段换成藤（本移植没有藤）。
     *
     * <p>{@code scale_factor} 在这里被再压缩一次：{@code (sf - 1) * 0.25 + 1}，128 层世界里就是 1。
     */
    private void crownBranch(WorldView world, Point crownPos, float radius, Random random,
                             float scaleFactor, Map<Point, Integer> leafDistance) {
        final float sf = (scaleFactor - 1) * 0.25F + 1;
        final float rnd = UpstreamComplexTreeFeatures.randRange(0F, 5 * sf, random);
        final int height10 = (int) (10 * sf + rnd);
        final int height15 = (int) (15 * sf + rnd);
        final int height17 = (int) (17 * sf + rnd);

        final float halfR = radius * 0.5F;
        final float r2 = radius * radius;
        final int start = (int) Math.floor(-radius);

        for (int cy = (int) Math.floor(radius); cy >= start; cy--) {
            final int cy2Out = cy * cy;
            float cy2In = cy + halfR;
            cy2In *= cy2In;
            final int worldY = (int) (crownPos.y() + cy - halfR);
            for (int cx = start; cx <= radius; cx++) {
                final int cx2 = cx * cx;
                final int worldX = crownPos.x() + cx;
                for (int cz = start; cz <= radius; cz++) {
                    final int cz2 = cz * cz;
                    if (cx2 + cy2Out + cz2 < r2 && cx2 + cy2In + cz2 > r2) {
                        final int worldZ = crownPos.z() + cz;
                        if (!UpstreamComplexTreeFeatures.replaceable(world, worldX, worldY, worldZ)) {
                            continue;
                        }
                        int length = UpstreamComplexTreeFeatures.downRay(
                                world, worldX, worldY, worldZ, height17);
                        if (length < 5) {
                            setLeaf(world, worldX, worldY, worldZ, leafDistance);
                            continue;
                        }
                        if (length > height15) {
                            length = UpstreamComplexTreeFeatures.randRange(height10, height15, random);
                        } else if (length > height10) {
                            length = UpstreamComplexTreeFeatures.randRange(height10, length, random);
                        }
                        if (cz % 2 == cx % 2) {
                            length /= 3;
                        }
                        // length > 4 的藤段：anchor_tree_vine 未收录，整段跳过（这里不消耗随机数）。
                        setLeaf(world, worldX, worldY, worldZ, leafDistance);
                    }
                }
            }
        }
    }

    /** 上游 {@code line}（枝条用）：与锚树本体同构，只是写进 POINTS/MIDDLE/TOP。 */
    private void lineBranch(int x1, int y1, int z1, int x2, int y2, int z2, int middleY,
                            UpstreamComplexTreeFeatures.Scratch scratch) {
        final int dx = x2 - x1;
        final int dy = y2 - y1;
        final int dz = z2 - z1;
        final int mx = Math.max(Math.max(Math.abs(dx), Math.abs(dy)), Math.abs(dz));
        final float fdx = (float) dx / mx;
        final float fdy = (float) dy / mx;
        final float fdz = (float) dz / mx;
        float px = x1;
        float py = y1;
        float pz = z1;

        track(scratch, x1, y1, z1, middleY);
        track(scratch, x2, y2, z2, middleY);

        for (int i = 0; i < mx; i++) {
            px += fdx;
            py += fdy;
            pz += fdz;
            track(scratch, Math.round(px), Math.round(py), Math.round(pz), middleY);
        }
    }

    /** 上游 TOP/MIDDLE 的两轮剪枝（枝条与赤菇木同构）。 */
    private static void pruneTopAndMiddle(UpstreamComplexTreeFeatures.Scratch scratch) {
        final Iterator<Point> topIterator = scratch.top.iterator();
        while (topIterator.hasNext()) {
            final Point here = topIterator.next();
            final Point above = here.offset(0, 1, 0);
            if (scratch.points.contains(above) && !scratch.top.contains(above)) {
                topIterator.remove();
            }
        }

        final Iterator<Point> middleIterator = scratch.middle.iterator();
        while (middleIterator.hasNext()) {
            final Point here = middleIterator.next();
            final Point above = here.offset(0, 1, 0);
            if (scratch.middle.contains(above)
                    || (!scratch.top.contains(above) && scratch.points.contains(above))) {
                middleIterator.remove();
            }
        }
    }

    /**
     * 沿六个方向找「这一格旁边的叶子能伸到多远」，把中间的空隙/藤补成叶子。
     *
     * <p>上游的判据是 {@code hasProperty(DISTANCE)}（即任何原版叶子）；CE 下读不到属性，
     * 这里按「我认识的本树叶子状态」判 —— 见类注释的已知缺口。
     */
    private void fillLeavesAround(WorldView world, Point here, Map<Point, Integer> leafDistance) {
        boolean hasLeavesOnLevel = false;
        for (Point.Direction dir : ALL) {
            // 主干所在的那一列不往下补叶子。
            if (!hasLeavesOnLevel && dir == Point.Direction.DOWN) {
                continue;
            }
            int max = 0;
            for (int i = 1; i <= DECAY_DISTANCE; i++) {
                final Point probe = here.offset(dir.dx() * i, dir.dy() * i, dir.dz() * i);
                if (isLeaf(world, probe.x(), probe.y(), probe.z())) {
                    max = i;
                }
            }
            if (dir != Point.Direction.DOWN && dir != Point.Direction.UP) {
                hasLeavesOnLevel |= max > 0;
            }
            for (int i = 1; i < max; i++) {
                final Point probe = here.offset(dir.dx() * i, dir.dy() * i, dir.dz() * i);
                if (isVine(world, probe.x(), probe.y(), probe.z())
                        || UpstreamComplexTreeFeatures.air(world, probe.x(), probe.y(), probe.z())) {
                    setLeaf(world, probe.x(), probe.y(), probe.z(), leafDistance);
                    // 向上的藤整段换成叶子（本移植没有 anchor_tree_vine，此循环永不进入）。
                    Point up = probe.offset(0, 1, 0);
                    while (isVine(world, up.x(), up.y(), up.z())) {
                        setLeaf(world, up.x(), up.y(), up.z(), leafDistance);
                        up = up.offset(0, 1, 0);
                    }
                }
            }
        }
    }

    /**
     * 上游 {@code updateSDFFrom}（枝条版）：7 格曼哈顿内的位置记录到「最近的木」距离。
     *
     * <p>与樱树版的差别：这里**有** {@code dist <= 7} 的过滤。
     */
    private void updateSdfFrom(Point cell, Map<Point, Integer> logsDist) {
        for (int x = -7; x <= 7; x++) {
            for (int y = -7; y <= 7; y++) {
                for (int z = -7; z <= 7; z++) {
                    if (x == 0 && y == 0 && z == 0) {
                        continue;
                    }
                    final int dist = Math.abs(x) + Math.abs(y) + Math.abs(z);
                    if (dist <= DECAY_DISTANCE) {
                        logsDist.merge(cell.offset(x, y, z), dist, Math::min);
                    }
                }
            }
        }
    }

    /**
     * 上游 {@code updateDistances}（枝条版）：把记录下来的距离写回叶子，够不到的删掉。
     *
     * <p>当前调用点传进来的表是空的（见 {@link #growBranch} 里那段说明），所以它在行为上是空转；
     * 保留是因为「上游这里其实是空转」是移植事实的一部分。
     */
    private void updateDistances(WorldView world, Map<Point, Integer> logsDist,
                                 Map<Point, Integer> leafDistance) {
        for (Map.Entry<Point, Integer> entry : logsDist.entrySet()) {
            int dist = entry.getValue();
            final Point at = entry.getKey();
            if (!isLeaf(world, at.x(), at.y(), at.z()) && !leafDistance.containsKey(at)) {
                continue;
            }
            final int cDist = leafDistance.getOrDefault(at, DECAY_DISTANCE);
            if (dist < cDist) {
                leafDistance.put(at, dist);
                final BlockData replaced = branchLeaves[Math.max(1, Math.min(DECAY_DISTANCE, dist))];
                if (replaced != null) {
                    world.setBlock(at.x(), at.y(), at.z(), replaced);
                }
            } else {
                dist = cDist;
            }
            if (dist >= DECAY_DISTANCE && air != null) {
                world.setBlock(at.x(), at.y(), at.z(), air);
                leafDistance.remove(at);
            }
        }
    }

    private void setLeaf(WorldView world, int x, int y, int z, Map<Point, Integer> leafDistance) {
        final BlockData leaves = branchLeaves[DECAY_DISTANCE];
        if (leaves == null) {
            return;
        }
        world.setBlock(x, y, z, leaves);
        leafDistance.put(new Point(x, y, z), DECAY_DISTANCE);
    }

    private boolean isLeaf(WorldView world, int x, int y, int z) {
        return UpstreamComplexTreeFeatures.isAnyOfIds(world, x, y, z, leafIds, leavesSet);
    }

    /** {@code anchor_tree_vine}：id 优先（CE 精确），问不到才看状态字符串。 */
    private boolean isVine(WorldView world, int x, int y, int z) {
        final String id = world.blockId(x, y, z);
        if (id != null) {
            return "betternether:anchor_tree_vine".equals(id);
        }
        final BlockData state = world.blockAt(x, y, z);
        return state != null && "betternether:anchor_tree_vine".equals(
                state.getAsString().replaceAll("\\[.*]$", ""));
    }

    // ================================================================ AnchorTreeRootFeature

    /**
     * 一条从天花板垂下来的抛物线状粗根。上游 {@code AnchorTreeRootFeature}。
     *
     * <p>只在 {@code y >= MAX_HEIGHT * 0.75} 处长；{@code start}/{@code end} 各自从天花板往下
     * {@code upRay} 找「贴顶」的位置，中间用抛物线往下压，然后沿折线压球。
     */
    private boolean placeRoot(WorldView world, Point point, int maxHeight) {
        if (point.y() < maxHeight * 0.75) {
            return false;
        }
        final Random random = world.random();
        final double angle = random.nextDouble() * Math.PI * 2;
        final double dx = Math.sin(angle);
        final double dz = Math.cos(angle);
        final double size = UpstreamComplexTreeFeatures.randRange(10, 25, random) * 0.5;
        int count = UpstreamComplexTreeFeatures.floor(size * 2);
        if (count < 3) {
            count = 3;
        }
        if ((count & 1) == 0) {
            count++;
        }

        final int search = (int) (maxHeight * 0.75);
        final Point firstFloor = floor(point.x() - dx * size, point.y() + 10, point.z() - dz * size);
        Point start = firstFloor.offset(0, UpstreamComplexTreeFeatures.upRay(
                world, firstFloor.x(), firstFloor.y(), firstFloor.z(), search), 0);
        if (start.y() < point.y()) {
            start = firstFloor.offset(0, 10, 0);
        }
        final Point secondFloor = floor(point.x() + dx * size, point.y() + 10, point.z() + dz * size);
        Point end = secondFloor.offset(0, UpstreamComplexTreeFeatures.upRay(
                world, secondFloor.x(), secondFloor.y(), secondFloor.z(), search), 0);
        if (end.y() < point.y()) {
            end = secondFloor.offset(0, 10, 0);
        }

        final List<Point> spine = lineParable(start, end, count, random, 0.2);

        final UpstreamComplexTreeFeatures.Scratch scratch = new UpstreamComplexTreeFeatures.Scratch();
        buildLine(spine, 1.3 + random.nextDouble(), scratch);

        final int minBuildHeight = world.minY() + 1;
        final int maxBuildHeight = maxHeight - 2;
        // 上游：blockBox 只喂给灵芝（{@code makeMushroom} 的 bounds.isInside），循环本身只看 y。
        final UpstreamSimpleTreeFeatures.Box box = UpstreamSimpleTreeFeatures.Zone
                .of(world, point.x(), point.y(), point.z())
                .toBox(minBuildHeight, maxBuildHeight);

        for (Point here : scratch.blocks) {
            if (here.y() < minBuildHeight || here.y() > maxBuildHeight) {
                continue;
            }
            if (!UpstreamComplexTreeFeatures.ground(world, here.x(), here.y(), here.z())
                    && !canReplace(world, here)) {
                continue;
            }
            final boolean blockUp = scratch.blocks.contains(here.offset(0, 1, 0));
            final boolean blockDown = scratch.blocks.contains(here.offset(0, -1, 0));
            final BlockData trunk = blockUp && blockDown ? log : bark;
            if (trunk != null) {
                world.setBlock(here.x(), here.y(), here.z(), trunk);
            }

            final Point abovePoint = here.offset(0, 1, 0);
            if (!blockUp && UpstreamComplexTreeFeatures.replaceable(
                    world, abovePoint.x(), abovePoint.y(), abovePoint.z()) && mossCover != null) {
                world.setBlock(abovePoint.x(), abovePoint.y(), abovePoint.z(), mossCover);
            }

            if ((here.y() & 3) == 0 && NOISE.eval(
                    here.x() * 0.1, here.y() * 0.1, here.z() * 0.1) > 0) {
                for (int i = 0; i < HORIZONTAL.length; i++) {
                    final Point.Direction direction = HORIZONTAL[i];
                    final Point at = here.offset(direction.dx(), 0, direction.dz());
                    // 上游是 if (nextInt(32)==0 && !BLOCKS.contains(邻居))，nextInt 总是先求值。
                    if (random.nextInt(32) != 0 || scratch.blocks.contains(at)) {
                        continue;
                    }
                    if (random.nextBoolean()) {
                        makeMushroom(world, at, random.nextDouble() + 1.5, box);
                    } else {
                        // 上游 LUCIS.grow(world, bpos, random, false)：注意位置是 bpos 本身。
                        growLucis(world, here, random);
                    }
                }
            }

            final int plantIndex = random.nextInt(wallPlants.length);
            for (int dir = 0; dir < HORIZONTAL.length; dir++) {
                final Point.Direction direction = HORIZONTAL[dir];
                final Point at = here.offset(direction.dx(), 0, direction.dz());
                if (random.nextInt(8) == 0 && !scratch.blocks.contains(at)
                        && UpstreamComplexTreeFeatures.air(world, at.x(), at.y(), at.z())) {
                    final BlockData plant = wallPlants[plantIndex][dir];
                    if (plant != null) {
                        world.setBlock(at.x(), at.y(), at.z(), plant);
                    }
                }
            }

            if (blockUp && !blockDown && random.nextInt(16) == 0) {
                final Point below = here.offset(0, -1, 0);
                int length = UpstreamComplexTreeFeatures.downRay(
                        world, below.x(), below.y(), below.z(), 17);
                if (length > 4) {
                    // 藤的长度抽样照做（否则随机流错位），写入跳过：anchor_tree_vine 未收录。
                    length = UpstreamComplexTreeFeatures.randRange(3, length, random);
                }
            }
        }

        return true;
    }

    /**
     * 上游那四个方向各不相同的盒子边界条件（{@code AnchorTreeFeature#grow}）。
     *
     * <p>逐向照抄：north 只查 {@code minZ}、south 只查 {@code maxZ}、east 只查 {@code maxX}、
     * west 只查 {@code minX}。看着不对称，但上游就是这么写的，改成「四边都查」会少放一圈植物。
     */
    private static boolean insideBoxEdge(UpstreamSimpleTreeFeatures.Box box,
                                         Point.Direction direction, Point at) {
        return switch (direction) {
            case NORTH -> at.z() >= box.minZ();
            case SOUTH -> at.z() <= box.maxZ();
            case EAST -> at.x() <= box.maxX();
            case WEST -> at.x() >= box.minX();
            default -> true;
        };
    }

    /** 上游 {@code MutableBlockPos#set(double,double,double)}：向下取整成整数格。 */
    private static Point floor(double x, double y, double z) {
        return new Point((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
    }

    /** 上游 {@code lineParable}：中间按抛物线往下压，x/z 各加一次高斯抖动。 */
    private List<Point> lineParable(Point start, Point end, int count, Random random, double range) {
        final List<Point> result = new ArrayList<>(count);
        final int last = count - 1;
        final int middle = count / 2;
        result.add(start);
        final double size = Math.sqrt(squaredDistance(start, end)) * 0.8;
        for (int i = 1; i < last; i++) {
            double offset = (double) (i - middle) / middle;
            offset = 1 - offset * offset;
            final double delta = (double) i / last;
            final double x = start.x() + (end.x() - start.x()) * delta + random.nextGaussian() * range;
            final double y = start.y() + (end.y() - start.y()) * delta - offset * size;
            final double z = start.z() + (end.z() - start.z()) * delta + random.nextGaussian() * range;
            result.add(new Point((int) x, (int) y, (int) z));
        }
        result.add(end);
        return result;
    }

    /** 上游 {@code BlockPos#distSqr}。 */
    private static double squaredDistance(Point a, Point b) {
        final double dx = a.x() - b.x();
        final double dy = a.y() - b.y();
        final double dz = a.z() - b.z();
        return dx * dx + dy * dy + dz * dz;
    }

    /** 上游 {@code buildLine}：沿折线等距压球。 */
    private void buildLine(List<Point> path, double radius,
                           UpstreamComplexTreeFeatures.Scratch scratch) {
        for (int i = 0; i < path.size() - 1; i++) {
            Point a = path.get(i);
            Point b = path.get(i + 1);
            if (b.y() < a.y()) {
                final Point swap = b;
                b = a;
                a = swap;
            }
            final int steps = (int) Math.ceil(Math.sqrt(squaredDistance(b, a)));
            for (int j = 0; j < steps; j++) {
                sphere(lerpCos3(a, b, (double) j / steps), radius, scratch);
            }
        }
    }

    /** 上游 {@code sphere}：一个实心球的所有格子。 */
    private void sphere(Point centre, double radius, UpstreamComplexTreeFeatures.Scratch scratch) {
        final int x1 = UpstreamComplexTreeFeatures.floor(centre.x() - radius);
        final int y1 = UpstreamComplexTreeFeatures.floor(centre.y() - radius);
        final int z1 = UpstreamComplexTreeFeatures.floor(centre.z() - radius);
        final int x2 = UpstreamComplexTreeFeatures.floor(centre.x() + radius + 1);
        final int y2 = UpstreamComplexTreeFeatures.floor(centre.y() + radius + 1);
        final int z2 = UpstreamComplexTreeFeatures.floor(centre.z() + radius + 1);
        final double r2 = radius * radius;

        for (int x = x1; x <= x2; x++) {
            int px2 = x - centre.x();
            px2 *= px2;
            for (int z = z1; z <= z2; z++) {
                int pz2 = z - centre.z();
                pz2 *= pz2;
                for (int y = y1; y <= y2; y++) {
                    int py2 = y - centre.y();
                    py2 *= py2;
                    if (px2 + pz2 + py2 <= r2) {
                        scratch.blocks.add(new Point(x, y, z));
                    }
                }
            }
        }
    }

    /**
     * 上游 {@code LucisFeature#grow(level, pos, random, force = false)}，逐行移植。
     *
     * <p>两支：
     * <ul>
     *   <li>{@code nextInt(3) == 0}（1/3）：中心一格 + 四个正方向 side + 四个对角 corner；</li>
     *   <li>否则：先挪位置（贴到旁边那面墙），再摆四个 corner。</li>
     * </ul>
     *
     * <p>第二支要读「上一株 {@code lucis_spore} 朝哪边」（上游 {@code state.getValue(FACING)}）。
     * 这里不读属性，而是拿**装载期解析好的四个朝向状态**逐个比 {@code BlockData}：
     * 先 {@link Object#equals}，再 {@code getAsString()}（两者都只比较状态，不比较材质 ——
     * CE 下多个自定义方块共用宿主材质，按材质比会把四个朝向判成同一个）。
     *
     * <p>如果资源包把这四个朝向折叠成同一个 Bukkit 状态（它们的差别只体现在
     * {@code entity_renderer} 的 {@code rotation} 上），比对会全部落空：此时按上游
     * 「facing 既非 SOUTH 也非 WEST 就不挪」的路径处理，并记一条 note（不是静默残留）。
     */
    private void growLucis(WorldView world, Point pos, Random random) {
        if (lucisCenter == null) {
            // 方块缺失时仍然要把上游那次 nextInt(3) 消耗掉，否则后续随机流错位。
            if (canGrowLucis(world, pos)) {
                random.nextInt(3);
            }
            return;
        }
        if (!canGrowLucis(world, pos)) {
            return;
        }
        if (random.nextInt(3) == 0) {
            setLucis(world, pos, lucisCenter);
            for (int i = 0; i < HORIZONTAL.length; i++) {
                final Point.Direction dir = HORIZONTAL[i];
                setLucis(world, pos.offset(dir.dx(), 0, dir.dz()), lucisSide[i]);
            }
            setLucis(world, pos.offset(1, 0, -1), lucisCorner[0]);    // north().east()  → SOUTH
            setLucis(world, pos.offset(-1, 0, -1), lucisCorner[1]);   // north().west()  → EAST
            setLucis(world, pos.offset(1, 0, 1), lucisCorner[2]);     // south().east()  → WEST
            setLucis(world, pos.offset(-1, 0, 1), lucisCorner[3]);    // south().west()  → NORTH
            return;
        }

        Point target = pos;
        if (UpstreamComplexTreeFeatures.isAnyOfIds(
                world, pos.x(), pos.y(), pos.z(), sporeIds, lucisSporeSet)) {
            // 上游：孢子朝 SOUTH 就挪到 north，朝 WEST 就挪到 east，其余朝向不动。
            final Point.Direction facing = sporeFacing(world, pos);
            if (facing == Point.Direction.SOUTH) {
                target = pos.offset(0, 0, -1);
            } else if (facing == Point.Direction.WEST) {
                target = pos.offset(1, 0, 0);
            } else if (facing == null) {
                UpstreamComplexTreeFeatures.note(
                        "lucis_spore 的 facing 既读不到属性、也无法用状态区分，"
                                + "该处按上游「既非 SOUTH 也非 WEST」路径不挪位");
            }
        } else if (!UpstreamComplexTreeFeatures.air(world, pos.x(), pos.y(), pos.z() - 1)) {
            target = pos.offset(0, 0, -1);      // north 不是空气 → 挪到 north
        } else if (!UpstreamComplexTreeFeatures.air(world, pos.x() + 1, pos.y(), pos.z())) {
            target = pos.offset(1, 0, 0);       // east 不是空气 → 挪到 east
        }
        setLucis(world, target, lucisCorner[0]);
        setLucis(world, target.offset(-1, 0, 0), lucisCorner[1]);      // west  → EAST
        setLucis(world, target.offset(0, 0, 1), lucisCorner[2]);       // south → WEST
        setLucis(world, target.offset(-1, 0, 1), lucisCorner[3]);      // south().west() → NORTH
    }

    /**
     * 这个位置的孢子朝哪边（{@code null} = 判不出来 / 不是孢子）。
     *
     * <h2>先读属性，读不到才退状态比对</h2>
     * {@link WorldView#propertyAt(String, int, int, int)} 直接给出 CE 自定义状态里的 {@code facing}
     * （链路 {@code CraftEngineBlocks.getCustomBlockState -> ImmutableBlockState#getProperty -> valueName}），
     * 这是上游 {@code state.getValue(BlockLucisSpore.FACING)} 的逐字对应 —— 优先用它。
     *
     * <p>生产实现链路已核实：{@code WorldView#propertyAt}（{@code WorldView.java:207}）→
     * {@code RegionView:227} → {@code RegionBlockAccess#propertyAt}，返回的是属性值的**序列化名**
     * （{@code "south"} 之类），与上游 {@code state.getValue(FACING)} 同名。
     *
     * <p>只有 {@code propertyAt} 返 {@code null}（越界、非 CE 自定义方块、该方块没有这个属性、
     * 或测试假世界）时，才退到「拿装载期解析好的四个朝向状态逐个比 {@link Object#equals} /
     * {@code getAsString()}」。这条**不是材质猜测**（比的是完整状态），只作诊断与退化；
     * 而且它不可能给出一个「错误的挪位」：若资源包把四个朝向折叠成同一状态，第一个命中的是
     * {@code HORIZONTAL[0] = NORTH}，而 NORTH 在上游同样不挪位 —— 结果与「读不到」一致。
     * 两条都落空时由调用方记一条 note，说明那次为什么没挪位。
     */
    private @Nullable Point.Direction sporeFacing(WorldView world, Point at) {
        final String facing = world.propertyAt("facing", at.x(), at.y(), at.z());
        if (facing != null) {
            return switch (facing) {
                case "north" -> Point.Direction.NORTH;
                case "south" -> Point.Direction.SOUTH;
                case "east" -> Point.Direction.EAST;
                case "west" -> Point.Direction.WEST;
                default -> null;
            };
        }

        final BlockData here = world.blockAt(at.x(), at.y(), at.z());
        if (here == null) {
            return null;
        }
        final String hereText = here.getAsString();
        for (int i = 0; i < HORIZONTAL.length; i++) {
            final BlockData spore = lucisSpore[i];
            if (spore == null) {
                continue;
            }
            if (spore.equals(here)) {
                return HORIZONTAL[i];
            }
            final String sporeText = spore.getAsString();
            if (sporeText != null && sporeText.equals(hereText)) {
                return HORIZONTAL[i];
            }
        }
        return null;
    }

    /** 上游 {@code canReplace(state)} = 孢子 ∪ {@code canBeReplaced()}。 */
    private void setLucis(WorldView world, Point at, @Nullable BlockData state) {
        if (state == null) {
            return;
        }
        if (UpstreamComplexTreeFeatures.isAnyOfIds(world, at.x(), at.y(), at.z(), sporeIds, lucisSporeSet)
                || UpstreamComplexTreeFeatures.replaceable(world, at.x(), at.y(), at.z())) {
            world.setBlock(at.x(), at.y(), at.z(), state);
        }
    }

    /**
     * 上游 {@code LucisFeature#canGenerate}：紧邻位置有下界岩或锚树木头。
     *
     * <p>只有它为真时上游才会走到 {@code nextInt(3)}，所以随机流的对齐依赖这个判定。
     */
    private boolean canGrowLucis(WorldView world, Point at) {
        for (Point.Direction direction : HORIZONTAL) {
            final Point probe = at.offset(direction.dx(), 0, direction.dz());
            final String id = world.blockId(probe.x(), probe.y(), probe.z());
            if (id != null) {
                if ("minecraft:netherrack".equals(id) || logIds.contains(id)) {
                    return true;
                }
                continue;
            }
            final BlockData state = world.blockAt(probe.x(), probe.y(), probe.z());
            if (state != null && state.getMaterial() == Material.NETHERRACK) {
                return true;
            }
            if (UpstreamComplexTreeFeatures.isAnyOf(state, logs)) {
                return true;
            }
        }
        return false;
    }

    // ================================================================ 共用小工具

    private static final Point.Direction[] HORIZONTAL = {
            Point.Direction.NORTH, Point.Direction.SOUTH,
            Point.Direction.EAST, Point.Direction.WEST
    };
    private static final Point.Direction[] ALL = {
            Point.Direction.NORTH, Point.Direction.EAST, Point.Direction.SOUTH,
            Point.Direction.WEST, Point.Direction.DOWN, Point.Direction.UP
    };

    /** 上游 {@code Mth.lerp}（截断成整数格）。 */
    private static Point lerp(Point start, Point end, double mix) {
        final double x = start.x() + (end.x() - start.x()) * mix;
        final double y = start.y() + (end.y() - start.y()) * mix;
        final double z = start.z() + (end.z() - start.z()) * mix;
        return new Point((int) x, (int) y, (int) z);
    }

    private static Point lerpCos(Point start, Point end, int y, double mix) {
        final double v = lcos(mix);
        final double x = start.x() + (end.x() - start.x()) * v;
        final double z = start.z() + (end.z() - start.z()) * v;
        return new Point((int) x, y, (int) z);
    }

    private static Point lerpCos3(Point start, Point end, double mix) {
        final double v = lcos(mix);
        final double x = start.x() + (end.x() - start.x()) * v;
        final double y = start.y() + (end.y() - start.y()) * v;
        final double z = start.z() + (end.z() - start.z()) * v;
        return new Point((int) x, (int) y, (int) z);
    }

    private static double lcos(double mix) {
        return Math.max(0, Math.min(1, 0.5 - Math.cos(mix * Math.PI) * 0.5));
    }

    /** 上游 {@code line}（锚树用）：等分插值 + 每个内点两次 {@code nextGaussian}。 */
    private List<Point> line(Point start, Point end, int count, Random random, double range) {
        final List<Point> result = new ArrayList<>(count);
        final int last = count - 1;
        result.add(start);
        for (int i = 1; i < last; i++) {
            final double delta = (double) i / last;
            final double x = start.x() + (end.x() - start.x()) * delta + random.nextGaussian() * range;
            final double y = start.y() + (end.y() - start.y()) * delta;
            final double z = start.z() + (end.z() - start.z()) * delta + random.nextGaussian() * range;
            result.add(new Point((int) x, (int) y, (int) z));
        }
        result.add(end);
        return result;
    }

    private static void put(UpstreamComplexTreeFeatures.Scratch scratch, Point point, int middleY) {
        scratch.points.add(point);
        if (point.y() == middleY) {
            scratch.middle.add(point);
        } else if (point.y() > middleY) {
            scratch.top.add(point);
        }
    }

    private static void track(UpstreamComplexTreeFeatures.Scratch scratch, int x, int y, int z,
                              int middleY) {
        put(scratch, new Point(x, y, z), middleY);
    }

    private boolean canReplace(WorldView world, Point point) {
        return UpstreamComplexTreeFeatures.ground(world, point.x(), point.y(), point.z())
                || UpstreamComplexTreeFeatures.replaceable(world, point.x(), point.y(), point.z());
    }
}
