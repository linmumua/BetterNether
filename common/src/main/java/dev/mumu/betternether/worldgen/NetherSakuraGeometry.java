package dev.mumu.betternether.worldgen;

import org.bukkit.block.data.BlockData;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * 下界樱（nether sakura）：上游 {@code NetherSakuraFeature} 逐行移植。
 *
 * <h2>形状</h2>
 * 从天花板上垂下来的「倒生」树：7×7 的范围里按 {@code x²+z²+1.4} 分档，中心 2 档长树干、
 * 其余向外倾斜下垂，每格的长度用 {@code randRange(l2, l)} 再除以档位；中心两格各挂一个
 * 「下半球 + 圆柱」冠层。
 *
 * <h2>随机消耗顺序（逐处照抄）</h2>
 * {@code l} → {@code height} → {@code nextDouble()}（半径）→ 门槛检查 → 二重循环里每格一次
 * {@code nextBoolean()}（只有 {@code d >= 2.8} 时求值）→ 每列的 {@code length}/{@code start}
 * → 冠层里的 {@code nextFloat}。
 *
 * <h2>三处与上游不同的地方</h2>
 * <ol>
 *   <li><b>叶子距离</b>：上游读 {@code LeavesBlock.DISTANCE} 再比较；CE 的资源包里
 *       {@code nether_sakura_leaves} 没有这个属性，读不到。这里自己维护
 *       {@code 我放下的叶子 → 当前距离} 的表（新叶子按 {@code DECAY_DISTANCE = 7} 起算），
 *       数值与上游一致，写出去的状态由属性表决定（包补上 distance 时自动生效）。</li>
 *   <li><b>可变坐标</b>：上游把 {@code context.POS} 这个共享可变对象在 x/z/y 循环里反复改、
 *       又整个传给 {@code crown}。这里用一个等价的 {@link Mut} 复现同样的读写顺序 ——
 *       冠层基点是「上一次 y 循环留下的 POS」，不是参数里的 y，改成干净传参会改变形状。</li>
 *   <li><b>方块识别</b>：{@code isTreeLog} 只认本文件解析出的 log/bark 两种状态
 *       （生成过程中不可能出现剥皮变体）。</li>
 * </ol>
 */
final class NetherSakuraGeometry implements Feature.Custom.Placer {
    /** {@code LeavesBlock.DECAY_DISTANCE}。 */
    private static final int DECAY_DISTANCE = 7;

    private final BlockData log;
    private final BlockData bark;
    private final BlockData[] leavesByDistance = new BlockData[DECAY_DISTANCE + 1];
    private final BlockData air;
    private final Set<BlockData> logs;
    private final Set<BlockData> leavesSet;
    /** id 级判据（{@code blockId} 精确）。 */
    private final Set<String> logIds = Set.of("betternether:nether_sakura_log",
            "betternether:nether_sakura_bark");
    private final Set<String> leafIds = Set.of("betternether:nether_sakura_leaves");

    NetherSakuraGeometry(UpstreamComplexTreeFeatures.BlockResolver resolver) {
        // 上游 NetherSakuraFeature 也从不 setValue(AXIS)：显式传 axis=y 与它的默认状态一致。
        this.log = UpstreamComplexTreeFeatures.block(resolver, "betternether:nether_sakura_log",
                UpstreamComplexTreeFeatures.props("axis", "y"));
        this.bark = UpstreamComplexTreeFeatures.block(resolver, "betternether:nether_sakura_bark",
                UpstreamComplexTreeFeatures.props("axis", "y"));
        for (int distance = 1; distance <= DECAY_DISTANCE; distance++) {
            leavesByDistance[distance] = UpstreamComplexTreeFeatures.block(resolver,
                    "betternether:nether_sakura_leaves",
                    UpstreamComplexTreeFeatures.props(
                            "distance", Integer.toString(distance), "persistent", "true"));
        }
        this.air = UpstreamComplexTreeFeatures.block(resolver, "minecraft:air", Map.of());
        this.logs = UpstreamComplexTreeFeatures.identities(log, bark);
        final BlockData[] allLeaves = new BlockData[DECAY_DISTANCE];
        for (int distance = 1; distance <= DECAY_DISTANCE; distance++) {
            allLeaves[distance - 1] = leavesByDistance[distance];
        }
        this.leavesSet = UpstreamComplexTreeFeatures.identities(allLeaves);
    }

    /** 上游 {@code context.POS}：一个会在循环里被就地改的坐标。 */
    private static final class Mut {
        int x;
        int y;
        int z;

        void set(int nx, int ny, int nz) {
            this.x = nx;
            this.y = ny;
            this.z = nz;
        }
    }

    @Override
    public boolean place(WorldView world, Point point) {
        final int maxHeight = UpstreamComplexTreeFeatures.maxHeight(world);
        // 只在天花板附近长：这是「倒挂」物种的前提。
        if (point.y() < maxHeight * 0.75) {
            return false;
        }
        return grow(world, point, maxHeight);
    }

    private boolean grow(WorldView world, Point base, int maxHeight) {
        final Random random = world.random();
        final float scaleFactor = maxHeight / 128.0F;

        final Map<Point, Integer> logsDist = new HashMap<>(1024);
        // 我放下的叶子的当前距离（上游是读方块状态里的 distance；CE 没这个属性，见类注释）。
        final Map<Point, Integer> leafDistance = new LinkedHashMap<>();

        int length = UpstreamComplexTreeFeatures.randRange(
                (int) (15 * scaleFactor), (int) (24 * scaleFactor), random);
        double height = UpstreamComplexTreeFeatures.randRange(
                (int) (10 * scaleFactor), (int) (15 * scaleFactor), random);
        double radius = height * (0.2 + random.nextDouble() * 0.1);

        // 天花板离得太远就不长（垂不到地，也不像倒挂）。
        if ((length + height) - UpstreamComplexTreeFeatures.downRay(
                world, base.x(), base.y(), base.z(), (int) (length + height)) > 10) {
            return false;
        }

        length = UpstreamComplexTreeFeatures.downRay(
                world, base.x(), base.y(), base.z(), length + 1);
        final int half = length * 2 / 3;

        final Mut cursor = new Mut();
        for (int x = -3; x <= 3; x++) {
            final int x2 = x * x;
            cursor.x = base.x() + x;
            for (int z = -3; z <= 3; z++) {
                final int z2 = z * z;
                final double d = x2 + z2 + 1.4;
                if (d >= 10) {
                    continue;
                }
                if (d < 2.8 || random.nextBoolean()) {
                    cursor.z = base.z() + z;
                    double columnLength = UpstreamComplexTreeFeatures.randRange(half, length, random)
                            / (d > 2 ? d : 1);
                    if (columnLength < 1) {
                        columnLength = 1;
                    }
                    final int start = UpstreamComplexTreeFeatures.randRange(-2, 0, random);
                    for (int y = start; y < columnLength; y++) {
                        cursor.y = base.y() - y;
                        if (canReplace(world, cursor)) {
                            if (log != null) {
                                world.setBlock(cursor.x, cursor.y, cursor.z, log);
                                updateSdfFrom(cursor, logsDist);
                            }
                        }
                    }
                    // 每列的最后那一格如果是原木，就换成树皮（「树皮朝外」的尖端）。
                    if (UpstreamComplexTreeFeatures.isAnyOfIds(
                            world, cursor.x, cursor.y, cursor.z, logIds, logs) && bark != null) {
                        world.setBlock(cursor.x, cursor.y, cursor.z, bark);
                        updateSdfFrom(cursor, logsDist);
                    }
                }

                if (d < 2) {
                    crown(world, cursor, radius, height, random, leafDistance);
                }
            }
        }

        updateDistances(world, logsDist, leafDistance);
        return true;
    }

    /**
     * 上游 {@code updateSDFFrom}：把「这一列的原木」告诉周围 7 格内的每个位置，
     * 取曼哈顿距离的最小值。
     *
     * <p>注意这里**没有** {@code dist <= 7} 的过滤（{@code AnchorTreeBranchFeature} 的版本有），
     * 所以表里会出现大于 7 的距离 —— 那些正是要被删掉的叶子。
     */
    private void updateSdfFrom(Mut cell, Map<Point, Integer> logsDist) {
        for (int x = -7; x <= 7; x++) {
            for (int y = -7; y <= 7; y++) {
                for (int z = -7; z <= 7; z++) {
                    if (x == 0 && y == 0 && z == 0) {
                        continue;
                    }
                    final int dist = Math.abs(x) + Math.abs(y) + Math.abs(z);
                    final Point target = new Point(cell.x + x, cell.y + y, cell.z + z);
                    logsDist.merge(target, dist, Math::min);
                }
            }
        }
    }

    /**
     * 上游 {@code updateDistances}：够不到的叶子删掉，其余写回真实距离。
     *
     * <p>「够不到」的判据是「这一格的原木距离表里最小值 > 7」且该位置当前距离也 > 7。
     */
    private void updateDistances(WorldView world, Map<Point, Integer> logsDist,
                                 Map<Point, Integer> leafDistance) {
        for (Map.Entry<Point, Integer> entry : logsDist.entrySet()) {
            int dist = entry.getValue();
            final Point at = entry.getKey();
            if (!UpstreamComplexTreeFeatures.isAnyOfIds(world, at.x(), at.y(), at.z(), leafIds, leavesSet)
                    && !leafDistance.containsKey(at)) {
                continue;
            }
            final int current = leafDistance.getOrDefault(at, DECAY_DISTANCE);
            if (dist < current && dist <= DECAY_DISTANCE) {
                leafDistance.put(at, dist);
                final BlockData replaced = leavesByDistance[Math.max(1, dist)];
                if (replaced != null) {
                    world.setBlock(at.x(), at.y(), at.z(), replaced);
                }
            } else {
                dist = current;
            }

            if (dist > DECAY_DISTANCE && air != null) {
                world.setBlock(at.x(), at.y(), at.z(), air);
                leafDistance.remove(at);
            }
        }
    }

    /**
     * 上半球 + 下半锥。上游 {@code crown}。
     *
     * <p>第一段是球心在 {@code pos} 的半球（{@code cy >= 0}）；第二段是从 {@code pos} 往下
     * {@code height} 格、半径线性收缩到 0 的锥体。锥体一旦遇到既不可替换又不是本树原木的方块就
     * 整段放弃 —— 那说明它撞进了地形，继续画只会在岩石里塞叶子。
     */
    private void crown(WorldView world, Mut pos, double radius, double height, Random random,
                       Map<Point, Integer> leafDistance) {
        final BlockData leaves = leavesByDistance[DECAY_DISTANCE];
        double r2 = radius * radius;
        final int start = (int) Math.floor(-radius);
        for (int cy = 0; cy <= radius; cy++) {
            final int cy2 = cy * cy;
            final int worldY = pos.y + cy;
            for (int cx = start; cx <= radius; cx++) {
                final int cx2 = cx * cx;
                final int worldX = pos.x + cx;
                for (int cz = start; cz <= radius; cz++) {
                    final int cz2 = cz * cz;
                    if (cx2 + cy2 + cz2 < r2) {
                        final int worldZ = pos.z + cz;
                        if (UpstreamComplexTreeFeatures.replaceable(world, worldX, worldY, worldZ)) {
                            setLeaf(world, worldX, worldY, worldZ, leaves, leafDistance);
                        }
                    }
                }
            }
        }

        for (int cy = 0; cy <= height; cy++) {
            double taper = radius * (1 - (double) cy / height);
            taper *= taper;
            r2 = taper;
            final int worldY = pos.y - cy;
            if (!UpstreamComplexTreeFeatures.replaceable(world, pos.x, worldY, pos.z)
                    && !UpstreamComplexTreeFeatures.isAnyOfIds(world, pos.x, worldY, pos.z, logIds, logs)) {
                return;
            }
            for (int cx = start; cx <= radius; cx++) {
                final int cx2 = cx * cx;
                final int worldX = pos.x + cx;
                for (int cz = start; cz <= radius; cz++) {
                    final int cz2 = cz * cz;
                    if (cx2 + cz2 < r2) {
                        final int worldZ = pos.z + cz;
                        if (UpstreamComplexTreeFeatures.replaceable(world, worldX, worldY, worldZ)) {
                            setLeaf(world, worldX, worldY, worldZ, leaves, leafDistance);
                        }
                    }
                }
            }
        }
    }

    private void setLeaf(WorldView world, int x, int y, int z, @Nullable BlockData state,
                         Map<Point, Integer> leafDistance) {
        if (state == null) {
            return;
        }
        world.setBlock(x, y, z, state);
        // 新叶子的初始距离就是「会掉」的那个值：上游刚放下去时也是默认值。
        leafDistance.put(new Point(x, y, z), DECAY_DISTANCE);
    }

    private boolean canReplace(WorldView world, Mut cell) {
        return UpstreamComplexTreeFeatures.ground(world, cell.x, cell.y, cell.z)
                || UpstreamComplexTreeFeatures.replaceable(world, cell.x, cell.y, cell.z);
    }
}
