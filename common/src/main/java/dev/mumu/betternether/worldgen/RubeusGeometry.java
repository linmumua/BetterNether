package dev.mumu.betternether.worldgen;

import org.bukkit.block.data.BlockData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 赤菇木（rubeus）：上游 {@code RubeusTreeFeature} 逐行移植。
 *
 * <h2>形状从哪来</h2>
 * 六个控制点组成的曲线（{@link #CURVE_X} / {@link #CURVE_Y}）绕一圈 {@code count} 条枝，
 * 每条枝从树基斜上、再向外下垂，落到地面附近；末端挂一个「空心球壳」状冠层
 * （{@code cx²+cy²+cz²} 的内外两个球面之间才有叶子）。木块形状（{@code shape=bottom/middle/top}）
 * 不是按高度定的，而是按**同一列上下是否有木头**定的 —— 这就是它读起来像一根被剥了囊的茎的原因。
 *
 * <h2>随机消耗顺序（逐处照抄）</h2>
 * {@code scale} → {@code count} → 每条枝：{@code branchSize}、x1/y1/z1 三个 {@code randRange}、
 * 冠层里每格的条件 {@code nextInt(6)}、{@code middle} 的 {@code randRange(-2,2)}、每个控制点的三个
 * {@code randRange}；最后是写方块阶段每格的 {@code nextInt(3)}（覆盖草）。
 *
 * <h2>三处与上游不同的地方（都在使用处标注）</h2>
 * <ol>
 *   <li><b>集合迭代顺序</b>：上游遍历 {@code HashSet<BlockPos>}，这里用插入序的 {@link LinkedHashSet}。
 *       方块集合、随机数个数都不变；变的是「哪一格拿到这次抽样」（只影响苔藓落点）。</li>
 *   <li><b>{@code canSurvive} 恒真</b>：服务端 API 拿不到，与
 *       {@link BlockPredicates#unverifiedSurvive()} 同一个已知缺口。</li>
 *   <li><b>赤菇木锥（{@code rubeus_cone}）当前资源包未收录</b>：抽样照做（否则后续随机流错位），
 *       放置跳过，并记一条 note。</li>
 * </ol>
 */
final class RubeusGeometry implements Feature.Custom.Placer {
    /** 上游 {@code BlockStalagnateSeed.MAX_SEARCH_LENGTH}：上方要有这么高的空气才长。 */
    private static final int MAX_SEARCH_LENGTH = 25;
    /** {@code LeavesBlock.DECAY_DISTANCE}：到它叶子就消失。 */
    private static final int DECAY_DISTANCE = 7;

    private static final float[] CURVE_X = {9F, 7F, 1.5F, 0.5F, 3F, 7F};
    private static final float[] CURVE_Y = {20F, 17F, 12F, 4F, 0F, -2F};
    private static final int MIDDLE_Y = 10;

    /** 上游 {@code BlocksHelper.HORIZONTAL} 的顺序：NORTH, SOUTH, EAST, WEST。 */
    private static final Point.Direction[] HORIZONTAL = {
            Point.Direction.NORTH, Point.Direction.SOUTH,
            Point.Direction.EAST, Point.Direction.WEST
    };
    private static final Point.Direction[] ALL = {
            Point.Direction.NORTH, Point.Direction.EAST, Point.Direction.SOUTH,
            Point.Direction.WEST, Point.Direction.DOWN, Point.Direction.UP
    };

    private final int spacing;
    /** 索引 0 = bottom，1 = middle，2 = top（上游 {@code TripleShape} 的三档）。 */
    private final BlockData[] logByShape = new BlockData[3];
    private final BlockData[] barkByShape = new BlockData[3];
    private final BlockData[] leavesByDistance = new BlockData[DECAY_DISTANCE];
    private final BlockData[] mossByDirection = new BlockData[4];
    private final BlockData air;
    private final Set<BlockData> logs;
    /**
     * BFS 的木头源：原木**与树皮**。
     *
     * <p>上游这里问的是 {@code BlockTags.PREVENTS_NEARBY_LEAF_DECAY}（即 LOGS），而
     * {@code rubeus_bark} 同样带 {@code LOG_BLOCK} trait、也在 LOGS 里。只按原木算会把树皮段
     * 排除在源之外，小树（冠层完全由树皮段承托）的叶子会被判成「够不到」而整片剪掉。
     */
    private final Set<BlockData> woodSources;
    /** id 级判据（{@code blockId} 精确）。 */
    private final Set<String> logIds = Set.of("betternether:rubeus_log");
    private final Set<String> woodSourceIds = Set.of("betternether:rubeus_log", "betternether:rubeus_bark");
    private final Set<String> leafIds = Set.of("betternether:rubeus_leaves");
    private final Set<String> coneIds = Set.of("betternether:rubeus_cone");
    private final Set<BlockData> leavesSet;
    @Nullable
    private final BlockData cone;
    private final Set<BlockData> coneSet;
    /** 上游 {@code NaturalTreeConfiguration.natural}：只有自然生成的树才长苔藓。 */
    private final boolean natural;

    RubeusGeometry(UpstreamComplexTreeFeatures.BlockResolver resolver, int spacing, boolean natural) {
        this.spacing = spacing;
        this.natural = natural;
        final String[] shapes = {"bottom", "middle", "top"};
        // 上游 RubeusLog 是 RotatedPillarBlock，registerDefaultState 把 AXIS 固定成 Y，
        // 而 RubeusTreeFeature#grow 从不 setValue(AXIS)（grep 过：全文件没有 AXIS）。
        // 所以这里显式传 axis=y：即使 CE 的默认轴不是 y，落下的状态也与上游一致。
        for (int i = 0; i < shapes.length; i++) {
            logByShape[i] = UpstreamComplexTreeFeatures.block(resolver, "betternether:rubeus_log",
                    UpstreamComplexTreeFeatures.props("shape", shapes[i], "axis", "y"));
            barkByShape[i] = UpstreamComplexTreeFeatures.block(resolver, "betternether:rubeus_bark",
                    UpstreamComplexTreeFeatures.props("shape", shapes[i], "axis", "y"));
        }
        for (int distance = 1; distance < DECAY_DISTANCE; distance++) {
            leavesByDistance[distance] = UpstreamComplexTreeFeatures.block(resolver,
                    "betternether:rubeus_leaves",
                    UpstreamComplexTreeFeatures.props("distance", Integer.toString(distance)));
        }
        for (int i = 0; i < HORIZONTAL.length; i++) {
            mossByDirection[i] = UpstreamComplexTreeFeatures.block(resolver, "betternether:jungle_moss",
                    UpstreamComplexTreeFeatures.props("facing", faceName(HORIZONTAL[i])));
        }
        this.air = UpstreamComplexTreeFeatures.block(resolver, "minecraft:air", Map.of());
        this.cone = UpstreamComplexTreeFeatures.block(resolver, "betternether:rubeus_cone", Map.of());
        if (cone == null) {
            UpstreamComplexTreeFeatures.note(
                    "rubeus_cone 未收录：赤菇木冠层下的锥形果实整段跳过（抽样仍然照做，随机流不变）");
        }
        this.logs = UpstreamComplexTreeFeatures.identities(logByShape[0], logByShape[1], logByShape[2]);
        this.woodSources = UpstreamComplexTreeFeatures.identities(
                logByShape[0], logByShape[1], logByShape[2],
                barkByShape[0], barkByShape[1], barkByShape[2]);
        this.leavesSet = UpstreamComplexTreeFeatures.identities(leavesByDistance[1]);
        this.coneSet = UpstreamComplexTreeFeatures.identities(cone);
    }

    private static String faceName(Point.Direction direction) {
        return direction.name().toLowerCase(java.util.Locale.ROOT);
    }

    // ---------------------------------------------------------------- 放置门槛

    @Override
    public boolean place(WorldView world, Point point) {
        // 上方空气不足（有个天花板贴着头）就不长 —— 上游用 stalagnate 种子的搜索长度当阈值。
        final int length = UpstreamComplexTreeFeatures.upRay(
                world, point.x(), point.y(), point.z(), MAX_SEARCH_LENGTH + 2);
        if (length < MAX_SEARCH_LENGTH) {
            return false;
        }
        if (!UpstreamComplexTreeFeatures.ground(world, point.x(), point.y() - 1, point.z())) {
            return false;
        }
        if (!UpstreamComplexTreeFeatures.noObjectNear(world, point, spacing,
                UpstreamComplexTreeFeatures.manhattan(spacing), logIds, logs)) {
            return false;
        }
        return grow(world, point);
    }

    // ---------------------------------------------------------------- 生成

    private boolean grow(WorldView world, Point base) {
        final Random random = world.random();
        final UpstreamComplexTreeFeatures.Scratch scratch = new UpstreamComplexTreeFeatures.Scratch();
        // 上游 {@code Set<BlockPos> leaves} 只被 updateDistances 读，同样用插入序集合。
        final Set<Point> leaves = new LinkedHashSet<>();

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
            float radius = CURVE_X[0] * branchSize;
            int x1 = Math.round(base.x() + radius * (float) Math.cos(angle)
                    + UpstreamComplexTreeFeatures.randRange(-2F, 2F, random) * branchSize);
            int y1 = Math.round(base.y() + CURVE_Y[0] * branchSize
                    + UpstreamComplexTreeFeatures.randRange(-2F, 2F, random) * branchSize);
            int z1 = Math.round(base.z() + radius * (float) Math.sin(angle)
                    + UpstreamComplexTreeFeatures.randRange(-2F, 2F, random) * branchSize);
            float crownR = 5 * branchSize;
            if (crownR < 1.5F) {
                crownR = 1.5F;
            }
            crown(world, x1, y1 + 1, z1, crownR, random, leaves);

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

                if (CURVE_Y[i] <= 0) {
                    // 往下落的这段必须落到地面上：找不到地面就把整条枝截断在树基。
                    if (!UpstreamComplexTreeFeatures.ground(world, x2, y2, z2)) {
                        boolean noGround = true;
                        for (int d = 1; d < 3; d++) {
                            if (UpstreamComplexTreeFeatures.ground(world, x2, y2 - d, z2)) {
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

                line(x1, y1, z1, x2, y2, z2, middle, scratch);
                x1 = x2;
                y1 = y2;
                z1 = z2;
            }
        }

        // TOP：只要上面还有木头、而上面那格不是 TOP，这格就不是顶端。
        final Iterator<Point> topIterator = scratch.top.iterator();
        while (topIterator.hasNext()) {
            final Point here = topIterator.next();
            final Point above = here.offset(0, 1, 0);
            if (scratch.points.contains(above) && !scratch.top.contains(above)) {
                topIterator.remove();
            }
        }

        // MIDDLE：上面还是 MIDDLE、或者上面是普通木头（不是 TOP）→ 这格不是「中间段」。
        final Iterator<Point> middleIterator = scratch.middle.iterator();
        while (middleIterator.hasNext()) {
            final Point here = middleIterator.next();
            final Point above = here.offset(0, 1, 0);
            if (scratch.middle.contains(above)
                    || (!scratch.top.contains(above) && scratch.points.contains(above))) {
                middleIterator.remove();
            }
        }

        for (Point here : scratch.points) {
            final boolean columned = scratch.points.contains(here.offset(0, 1, 0))
                    && scratch.points.contains(here.offset(0, -1, 0));
            final BlockData[] source = columned ? logByShape : barkByShape;
            if (scratch.middle.contains(here)) {
                setCondition(world, here, base.y(), source[1], false, random);
            } else if (scratch.top.contains(here)) {
                setCondition(world, here, base.y(), source[2], false, random);
            } else {
                // 只有「底段」长苔藓，而且只在自然生成的树上。
                setCondition(world, here, base.y(), source[0], natural, random);
            }
        }

        updateDistances(world, leaves, scratch);
        return true;
    }

    /**
     * 冠层：三层同心判定切出的空心球壳（{@code cy} 从下往上扫，外层条件里 {@code cy2_in} 是
     * 以「球心 - halfR」为准的内球面）。上游 {@code crown}。
     */
    private void crown(WorldView world, int x, int y, int z, float radius, Random random,
                       Set<Point> placed) {
        final BlockData leaves = leavesByDistance[1];
        final float halfR = radius * 0.5F;
        final float r2 = radius * radius;
        final int start = (int) Math.floor(-radius);
        for (int cy = start; cy <= radius; cy++) {
            final int cy2Out = cy * cy;
            float cy2In = cy + halfR;
            cy2In *= cy2In;
            // Mth.floor，不是 (int) 强转：强转向零取整，会把 y<0 的冠层整体抬高一格。
            final int worldY = UpstreamComplexTreeFeatures.floor(y + cy - halfR);
            for (int cx = start; cx <= radius; cx++) {
                final int cx2 = cx * cx;
                final int worldX = x + cx;
                for (int cz = start; cz <= radius; cz++) {
                    final int cz2 = cz * cz;
                    if (cx2 + cy2Out + cz2 < r2 && cx2 + cy2In + cz2 > r2) {
                        final int worldZ = z + cz;
                        if (setIfAirLeaves(world, worldX, worldY, worldZ, leaves)) {
                            placed.add(new Point(worldX, worldY, worldZ));
                        }
                        // 抽样条件照抄（含 && 短路）：只有奇偶校验通过才消耗那次 nextInt(6)。
                        if (((worldX + worldZ) & 1) == 0 && random.nextInt(6) == 0) {
                            if (cone != null) {
                                setIfAir(world, worldX, worldY - 1, worldZ, cone);
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 一根木头的落点判定 + 覆盖草。
     *
     * <p>{@code y} 是树基高度：比它高的格子只覆盖空气，比它低的允许覆盖地面
     * （树基以下的枝是要压过地表的）。
     */
    private void setCondition(WorldView world, Point here, int baseY, @Nullable BlockData state,
                              boolean moss, Random random) {
        if (state == null) {
            return;
        }
        if (here.y() > baseY) {
            setIfAir(world, here.x(), here.y(), here.z(), state);
        } else {
            setIfGroundOrAir(world, here.x(), here.y(), here.z(), state);
        }
        if (moss && Math.abs(here.y() - baseY) < 4) {
            for (int i = 0; i < HORIZONTAL.length; i++) {
                final Point.Direction dir = HORIZONTAL[i];
                if (random.nextInt(3) > 0) {
                    final BlockData plant = mossByDirection[i];
                    if (plant != null) {
                        setIfAir(world, here.x() + dir.dx(), here.y(), here.z() + dir.dz(), plant);
                    }
                }
            }
        }
    }

    /**
     * 只覆盖「空的/可替换的/自己的叶子/自己的锥」。
     *
     * <p>锥也算空位：冠层先挂锥、枝条随后往上长进冠层，木头必须赢，否则枝上会留个缺口。
     */
    private void setIfAir(WorldView world, int x, int y, int z, @Nullable BlockData state) {
        if (state == null) {
            return;
        }
        if (UpstreamComplexTreeFeatures.air(world, x, y, z)
                || UpstreamComplexTreeFeatures.replaceable(world, x, y, z)
                || UpstreamComplexTreeFeatures.isAnyOfIds(world, x, y, z, leafIds, leavesSet)
                || UpstreamComplexTreeFeatures.isAnyOfIds(world, x, y, z, coneIds, coneSet)) {
            // 上游这一步还有 state.canSurvive(world, pos)：问得到就问真的，问不到按「能活」并记账。
            if (BlockPredicates.wouldSurvive(world, state, x, y, z)) {
                world.setBlock(x, y, z, state);
            }
        }
    }

    private void setIfGroundOrAir(WorldView world, int x, int y, int z, @Nullable BlockData state) {
        if (state == null) {
            return;
        }
        if (UpstreamComplexTreeFeatures.air(world, x, y, z)
                || UpstreamComplexTreeFeatures.isAnyOfIds(world, x, y, z, leafIds, leavesSet)
                || UpstreamComplexTreeFeatures.isAnyOfIds(world, x, y, z, coneIds, coneSet)
                || UpstreamComplexTreeFeatures.replaceable(world, x, y, z)
                || UpstreamComplexTreeFeatures.ground(world, x, y, z)) {
            if (BlockPredicates.wouldSurvive(world, state, x, y, z)) {
                world.setBlock(x, y, z, state);
            }
        }
    }

    private boolean setIfAirLeaves(WorldView world, int x, int y, int z, @Nullable BlockData state) {
        if (state == null) {
            return false;
        }
        if (UpstreamComplexTreeFeatures.air(world, x, y, z)
                || UpstreamComplexTreeFeatures.replaceable(world, x, y, z)) {
            world.setBlock(x, y, z, state);
            return true;
        }
        return false;
    }

    /**
     * 树枝折线：沿主轴逐格推进，端点单独记录。上游 {@code line}。
     *
     * <p>记录三份信息：全部木质格（{@link UpstreamComplexTreeFeatures.Scratch#points}）、
     * 恰好落在 {@code middleY} 的格、以及高于 {@code middleY} 的格。后两份用来决定
     * {@code shape} 属性 —— 这就是同一个高度上不同木块形状不同的原因。
     */
    private void line(int x1, int y1, int z1, int x2, int y2, int z2, int middleY,
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

    private void track(UpstreamComplexTreeFeatures.Scratch scratch, int x, int y, int z,
                        int middleY) {
        final Point point = new Point(x, y, z);
        scratch.points.add(point);
        if (y == middleY) {
            scratch.middle.add(point);
        } else if (y > middleY) {
            scratch.top.add(point);
        }
    }

    /**
     * 给冠层每片叶子算真正的衰减距离，够不到的连同它下面的锥一起删掉。上游 {@code updateDistances}。
     *
     * <p>这是**穿叶子**的广度优先路径长度，不是直线距离：绕开空心冠层，直线三格可能要九步。
     * 用直线距离会低估、让叶子在邻居变化后的第一次随机刻消失。
     */
    private void updateDistances(WorldView world, Set<Point> leaves,
                                 UpstreamComplexTreeFeatures.Scratch scratch) {
        final Map<Point, Integer> distance = new HashMap<>();
        final ArrayDeque<Point> queue = new ArrayDeque<>();
        for (Point log : scratch.points) {
            if (UpstreamComplexTreeFeatures.isAnyOfIds(
                    world, log.x(), log.y(), log.z(), woodSourceIds, woodSources)) {
                distance.put(log, 0);
                queue.add(log);
            }
        }

        while (!queue.isEmpty()) {
            final Point current = queue.poll();
            final int next = distance.get(current) + 1;
            if (next >= DECAY_DISTANCE) {
                continue;
            }
            for (Point.Direction dir : ALL) {
                final Point neighbour = current.offset(dir.dx(), dir.dy(), dir.dz());
                if (!leaves.contains(neighbour) || distance.containsKey(neighbour)) {
                    continue;
                }
                if (!UpstreamComplexTreeFeatures.isAnyOfIds(
                        world, neighbour.x(), neighbour.y(), neighbour.z(), leafIds, leavesSet)) {
                    continue;
                }
                distance.put(neighbour, next);
                queue.add(neighbour);
            }
        }

        for (Point leaf : leaves) {
            if (!UpstreamComplexTreeFeatures.isAnyOfIds(
                    world, leaf.x(), leaf.y(), leaf.z(), leafIds, leavesSet)) {
                continue;
            }
            final Integer dist = distance.get(leaf);
            if (dist != null && leavesByDistance[Math.max(1, Math.min(DECAY_DISTANCE - 1, dist))] != null) {
                world.setBlock(leaf.x(), leaf.y(), leaf.z(),
                        leavesByDistance[Math.max(1, Math.min(DECAY_DISTANCE - 1, dist))]);
            } else {
                if (air != null) {
                    world.setBlock(leaf.x(), leaf.y(), leaf.z(), air);
                }
                final int belowY = leaf.y() - 1;
                if (air != null && UpstreamComplexTreeFeatures.isAnyOfIds(
                        world, leaf.x(), belowY, leaf.z(), coneIds, coneSet)) {
                    world.setBlock(leaf.x(), belowY, leaf.z(), air);
                }
            }
        }
    }

}
