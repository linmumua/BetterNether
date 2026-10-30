package dev.mumu.betternether.worldgen;

import com.google.gson.JsonObject;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 三项「顺序/边界」纪律的测试：写区（{@code WriteZone}）、集合遍历顺序（{@code HashSet<BlockPos>}）、
 * 随机流指纹。
 *
 * <h2>为什么要单独一个文件</h2>
 * 它们测的不是「树长什么样」，而是「这棵树在哪一格拿到第几次抽样」—— 这正是移植里最容易
 * 悄悄错位、错了又看不出来的那一层。{@code UpstreamComplexTreeFeaturesTest} 管形状，本文件管顺序。
 *
 * <h2>假世界必须按区块建模</h2>
 * 写区是**区块对齐**的。用「方块半径」建模的假世界会让 {@code Zone.of} 探出一个自相矛盾的框
 * （{@code inRegion(±8)} 成立却算出 {@code minX = 0}）。所以这里的 {@link ZoneWorld} 按
 * {@code chunkX/chunkZ ∈ [-r, r]} 判可写，与 {@code WorldGenRegion} 的真实形状一致。
 */
class ComplexTreeWriteZoneAndRandomTest {
    private static final int MAX_Y = 127;

    // ================================================================ 写区：官方算术

    @Test
    void writeZoneBoxesAreChunkAlignedAroundTheProbingChunk() {
        // 3x3 区块（radius 1）→ x/z ∈ [-16, 31]。
        final ZoneWorld world = new ZoneWorld(1);
        final UpstreamSimpleTreeFeatures.Zone zone =
                UpstreamSimpleTreeFeatures.Zone.of(world, 8, 60, 8);
        assertEquals(-16, zone.minX());
        assertEquals(-16, zone.minZ());
        assertEquals(31, zone.maxX());
        assertEquals(31, zone.maxZ());

        // headroom = 到四边的最小距离（上游 clamp 到 >= 0）。
        // 3x3 区块的框是 -16..31，所以 (8,8) 到东边只有 31-8=23、到西边是 8+16=24 → 取 23。
        assertEquals(23.0F, zone.headroom(8, 8), 1e-4F);
        assertEquals(0.0F, zone.headroom(100, 8), 1e-4F);

        // fitRadius：放不下 → -1；放得下 → min(radius, room)。
        assertEquals(4.0F, zone.fitRadius(8, 8, 4.0F, 2.0F), 1e-4F);
        assertEquals(3.0F, zone.fitRadius(28, 8, 4.0F, 2.0F), 1e-4F);
        assertEquals(-1.0F, zone.fitRadius(30, 8, 4.0F, 2.0F), 1e-4F);
    }

    @Test
    void unboundedZoneLeavesEveryRadiusAlone() {
        // 没有写区限制（非 WorldGenRegion）：headroom 是 MAX_VALUE/2，fitRadius 原样返回。
        // ZoneWorld(-1) = 不按区块限制可写，等价于「不是 WorldGenRegion」。
        final ZoneWorld world = new ZoneWorld(-1);
        final UpstreamSimpleTreeFeatures.Zone zone =
                UpstreamSimpleTreeFeatures.Zone.of(world, 0, 60, 0);
        assertTrue(zone.unbounded());
        assertEquals(7.5F, zone.fitRadius(10_000, -10_000, 7.5F, 2.0F), 1e-4F);
    }

    @Test
    void fitSegmentOnlyShortensAndKeepsTheDirection() {
        // 官方 WriteZone#fitSegment：写区四边各内缩 radius，然后对凸盒做 slab clip。
        // 3x3 区块、圆心 (20,60,0)、半径 2 → x ∈ [-14, 29]；线段 (0,0,0)->(20,0,0) 的 t = (29-20)/20 = 0.45
        final ZoneWorld world = new ZoneWorld(1);
        final GloomwoodGeometry.TreeSpace space =
                GloomwoodGeometry.TreeSpace.of(world, new Point(20, 60, 0));
        final GloomwoodGeometry.Vec3 fitted = space.fitSegment(
                new GloomwoodGeometry.Vec3(0, 0, 0), new GloomwoodGeometry.Vec3(20, 0, 0), 2.0F);
        assertEquals(9.0F, fitted.x, 1e-3F);
        assertEquals(0.0F, fitted.y, 1e-3F);
        assertEquals(0.0F, fitted.z, 1e-3F);

        // 起点已经在写区外：原样返回起点（上游不重新瞄准）。
        final GloomwoodGeometry.Vec3 stuck = space.fitSegment(
                new GloomwoodGeometry.Vec3(20, 0, 0), new GloomwoodGeometry.Vec3(40, 0, 0), 2.0F);
        assertEquals(20.0F, stuck.x, 1e-3F);

        // 完全放得下：返回终点（绝大多数枝条因此与不裁剪时逐格相同）。
        final GloomwoodGeometry.Vec3 untouched = space.fitSegment(
                new GloomwoodGeometry.Vec3(0, 0, 0), new GloomwoodGeometry.Vec3(3, 4, 0), 0.5F);
        assertEquals(3.0F, untouched.x, 1e-3F);
        assertEquals(4.0F, untouched.y, 1e-3F);
    }

    @Test
    void gloomwoodWritesNothingOutsideTheZoneAndStillGrowsInside() {
        // 中心区块内的树：能长出来，且一个方块都不写到 3x3 之外。
        final ZoneWorld world = new ZoneWorld(1);
        world.fill(-16, 31, 0, 49, Material.NETHERRACK);
        final Feature feature = gloomwood();
        feature.place(world, new Point(8, 50, 8));
        assertTrue(world.placedCount() > 0, "中心区块里应当长出树来");
        assertEquals(0, world.writesOutsideZone(), "写区之外不允许出现任何方块");

        // 贴着东侧边缘的树：形状被 fitRadius/fitSegment 收进去，仍然一个方块都不越界。
        final ZoneWorld edge = new ZoneWorld(1);
        edge.fill(-16, 31, 0, 49, Material.NETHERRACK);
        gloomwood().place(edge, new Point(26, 50, 8));
        assertEquals(0, edge.writesOutsideZone(), "贴边时也不允许越界写（靠收形状，不是靠裁剪）");
    }

    // ================================================================ 集合遍历顺序

    @Test
    void blockPosOrderedSetIteratesLikeAHashSetOfBlockPos() {
        // 独立复算：桶下标 = (h ^ (h >>> 16)) & (cap - 1)，桶升序、桶内插入序；
        // 容量 16 起步、size > 0.75*cap 时翻倍（13 → 32，25 → 64）。
        final List<Point> points = new ArrayList<>();
        for (int y = 0; y < 7; y++) {
            for (int x = 0; x < 5; x++) {
                points.add(new Point(x - 2, y, (x * 7 + y * 3) % 5 - 2));
            }
        }

        final UpstreamComplexTreeFeatures.BlockPosOrderedSet set =
                new UpstreamComplexTreeFeatures.BlockPosOrderedSet(16);
        for (Point point : points) {
            set.add(point);
        }

        assertEquals(points.size(), set.size(), "重复坐标应当被去掉");
        assertEquals(expectedBlockPosOrder(points, 16), iterationOf(set));
    }

    @Test
    void blockPosOrderedSetResizesAndKeepsInsertionOrderInsideBuckets() {
        final List<Point> points = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            points.add(new Point(i * 3 - 40, i % 11, i * 5 - 100));
        }
        final UpstreamComplexTreeFeatures.BlockPosOrderedSet set =
                new UpstreamComplexTreeFeatures.BlockPosOrderedSet(16);
        for (Point point : points) {
            set.add(point);
        }
        assertEquals(expectedBlockPosOrder(points, 16), iterationOf(set));
    }

    @Test
    void orderedSetMatchesAPlainJdkHashSetOfTheSameKeySemantics() {
        // 对拍对象是**测试自己写的** NmsPos（只实现 hash/equals/compareTo，三行照抄 Vec3i），
        // 用的是 JDK 的 HashSet —— 也就是说这条断言检查的是「我的适配器没有加任何重排」。
        final List<Point> insertion = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            insertion.add(new Point(i * 37 % 91 - 45, i % 9, i * 53 % 77 - 38));
        }
        final UpstreamComplexTreeFeatures.BlockPosOrderedSet mine =
                new UpstreamComplexTreeFeatures.BlockPosOrderedSet(16);
        final java.util.HashSet<NmsPos> jdk = new java.util.HashSet<>(16);
        for (Point point : insertion) {
            mine.add(point);
            jdk.add(new NmsPos(point.x(), point.y(), point.z()));
        }
        assertEquals(project(jdk), iterationOf(mine));
    }

    @Test
    void orderedSetMatchesAPlainJdkHashSetAlongTheTreeifyPath() {
        // 两种容器路径都要对上：
        //   A) 从容量 16 起步、靠扩容长到 40 个（HashMap 的 resize 路径）；
        //   B) 预置容量 64 后往同一个桶里塞 10 个（HashMap 的 treeifyBin 路径 —— 一个桶里 ≥ 8 个元素）。
        // 我不去断言「树化后桶内顺序 = compareTo 序」：JDK 的 TreeBin 迭代走的是穿在树节点上的
        // next 链，既不是 compareTo 序、也不保证等于插入序。这条不该由我猜，只该由「与 JDK 同序」来盖。
        final java.util.HashSet<NmsPos> jdk = new java.util.HashSet<>(64);
        final UpstreamComplexTreeFeatures.BlockPosOrderedSet mine =
                new UpstreamComplexTreeFeatures.BlockPosOrderedSet(64);
        final List<Point> insertion = new ArrayList<>();
        // 这 10 个点的 h = x（y = z = 0），x & 63 == 1 → 在容量 64 下全部落在同一个桶。
        for (int i = 9; i >= 0; i--) {
            insertion.add(new Point(1 + i * 64, 0, 0));
        }
        for (Point point : insertion) {
            mine.add(point);
            jdk.add(new NmsPos(point.x(), point.y(), point.z()));
        }

        // 1) 关键不变量：与「测试自己的 NmsPos + JDK HashSet」逐元素相同。
        assertEquals(project(jdk), iterationOf(mine));

        // 2) 这个场景确实非平凡：碰撞桶的迭代顺序不等于插入顺序（否则上面的对拍是空转的）。
        //    如果哪天换个 JDK 版本这句话不再成立，说明 JDK 的实现变了 —— 那时这条守卫应当更新，
        //    而第 1 条不变量仍然必须成立。
        final List<Point> inBucket = new ArrayList<>();
        for (Point point : mine) {
            if (point.y() == 0 && point.z() == 0) {
                inBucket.add(point);
            }
        }
        assertEquals(10, inBucket.size(), "十个碰撞点都应当在集合里");
        assertTrue(!insertion.equals(inBucket),
                "十个碰撞键在同一个桶里时，JDK 的顺序不应等于插入顺序（实际：" + inBucket + "）");

        // 3) 扩容路径同样对拍：16 起步长到 40 个。
        final java.util.HashSet<NmsPos> grown = new java.util.HashSet<>(16);
        final UpstreamComplexTreeFeatures.BlockPosOrderedSet grownMine =
                new UpstreamComplexTreeFeatures.BlockPosOrderedSet(16);
        for (int i = 0; i < 40; i++) {
            final Point point = new Point(i * 37 % 91 - 45, i % 9, i * 53 % 77 - 38);
            grownMine.add(point);
            grown.add(new NmsPos(point.x(), point.y(), point.z()));
        }
        assertEquals(project(grown), iterationOf(grownMine));
    }

    @Test
    void orderedSetKeepsCoordinatesPackableToLong() {
        // 「对外仍 long packing 适配」：键可以打包成 20/20/20 位的 long，和世界读写用的打包一致。
        final UpstreamComplexTreeFeatures.BlockPosKey key =
                new UpstreamComplexTreeFeatures.BlockPosKey(-12, 80, 33);
        assertEquals(((long) (-12 & 0xFFFFF) << 40) | ((long) 80 << 20) | (33 & 0xFFFFF),
                key.packed());
        assertEquals((80 + 33 * 31) * 31 + (-12), key.hashCode(), "hashCode 必须等于 Vec3i 的公式");
    }

    /** 测试自己的 BlockPos 语义：三行照抄 {@code Vec3i} 的 hash/equals/compareTo。 */
    private record NmsPos(int x, int y, int z) implements Comparable<NmsPos> {
        @Override
        public int hashCode() {
            return (y + z * 31) * 31 + x;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof NmsPos pos && pos.x == x && pos.y == y && pos.z == z;
        }

        @Override
        public int compareTo(NmsPos other) {
            if (y == other.y) {
                return z == other.z ? x - other.x : z - other.z;
            }
            return y - other.y;
        }
    }

    private static List<Point> project(java.util.HashSet<NmsPos> keys) {
        final List<Point> result = new ArrayList<>();
        for (NmsPos key : keys) {
            result.add(new Point(key.x(), key.y(), key.z()));
        }
        return result;
    }

    @Test
    void iteratorRemoveMatchesHashSetBehaviour() {
        // 上游用 Iterator.remove() 剪两轮（TOP/MIDDLE）。这里锁住「删掉当前元素、其余不动」。
        final UpstreamComplexTreeFeatures.BlockPosOrderedSet set =
                new UpstreamComplexTreeFeatures.BlockPosOrderedSet(16);
        for (int i = 0; i < 10; i++) {
            set.add(new Point(i, 0, 0));
        }
        final java.util.Iterator<Point> iterator = set.iterator();
        int seen = 0;
        while (iterator.hasNext()) {
            final Point point = iterator.next();
            seen++;
            if (point.x() % 2 == 0) {
                iterator.remove();
            }
        }
        assertEquals(10, seen, "遍历本身不能漏元素");
        assertEquals(5, set.size(), "只删掉偶数 x 的那一半");
        for (Point point : set) {
            assertEquals(1, point.x() % 2, "删完后只剩奇数 x");
        }
    }

    // ================================================================ 随机流指纹

    @Test
    void rubeusDrawsTheUpstreamRandomPrologue() {
        // 独立推导的前六次抽样（上游 RubeusTreeFeature#grow）：
        //   scale      = randRange(0.5, 1)                       -> nextFloat   F
        //   count      = randRange(minCount, maxCount)           -> nextInt     I
        //   branchSize = randRange(0.5, 0.8) * scale             -> nextFloat   F
        //   x1         = ... + randRange(-2, 2) * branchSize     -> nextFloat   F
        //   y1         = ... + randRange(-2, 2) * branchSize     -> nextFloat   F
        //   z1         = ... + randRange(-2, 2) * branchSize     -> nextFloat   F
        // 顺序错一位，树的形状就换一棵，所以这里从**算法**推，不从录制值抄。
        final RecordingRandom random = new RecordingRandom(20260101L);
        place("betternether:rubeus_tree", random, rubeusWorld());
        assertTrue(random.trace().startsWith("FIFFFF"),
                "赤菇木开头必须是 scale/count/branchSize/x1/y1/z1，实际：" + random.trace().substring(0, 8));
    }

    @Test
    void severalTreeDrawsAreInTheSameOrderAcrossRunsAndAcrossInstances() {
        // 同 seed、同位置、同方块解析顺序 → 抽样序列完全相同（指纹）。
        // 不同 feature 实例也相同：形状是纯函数，随机流只由 seed 决定。
        final String first = traceOf("betternether:gloomwood_tree", 777L);
        final String second = traceOf("betternether:gloomwood_tree", 777L);
        assertEquals(first, second);
        assertTrue(first.length() > 40, "幽光木应当有足够多的抽样（实际 " + first.length() + "）");

        final String rubeus = traceOf("betternether:rubeus_tree", 424242L);
        assertEquals(rubeus, traceOf("betternether:rubeus_tree", 424242L));
        assertTrue(rubeus.length() > 50);
    }

    @Test
    void anchorTreeRootDrawsRotationSizeThenParableJitter() {
        // 上游 AnchorTreeRootFeature#place：angle = nextDouble（1 次）、size = randRange(10,25)（nextInt 1 次），
        // 然后 buildLine 的半径 nextDouble，最后 lineParable 的每个内点两次 nextGaussian。
        final RecordingRandom random = new RecordingRandom(90210L);
        place("betternether:anchor_tree_root", random, highCavern());
        final String trace = random.trace();
        assertEquals('D', trace.charAt(0), "第一个抽样是角度 nextDouble，实际：" + trace);
        assertEquals('I', trace.charAt(1), "第二个抽样是 size 的 nextInt，实际：" + trace);
    }

    // ================================================================ 夹具

    private static String traceOf(String type, long seed) {
        final RecordingRandom random = new RecordingRandom(seed);
        place(type, random, type.contains("rubeus") ? rubeusWorld() : gloomyWorld());
        return random.trace();
    }

    private static void place(String type, Random random, ZoneWorld world) {
        world.useRandom(random);
        final Feature feature = UpstreamComplexTreeFeatures.create(type, new JsonObject(), resolver());
        feature.place(world, world.anchor());
    }

    private static Feature gloomwood() {
        return UpstreamComplexTreeFeatures.create("betternether:gloomwood_tree", new JsonObject(),
                resolver());
    }

    private static ZoneWorld rubeusWorld() {
        final ZoneWorld world = new ZoneWorld(1);
        world.fill(-16, 31, 0, 49, Material.NETHERRACK);
        world.anchor(new Point(8, 50, 8));
        return world;
    }

    private static ZoneWorld gloomyWorld() {
        return rubeusWorld();
    }

    private static ZoneWorld highCavern() {
        final ZoneWorld world = new ZoneWorld(1);
        world.fill(-16, 31, 0, 99, Material.NETHERRACK);
        world.fill(-16, 31, 127, MAX_Y, Material.NETHERRACK);
        world.anchor(new Point(8, 120, 8));
        return world;
    }

    // ---------------------------------------------------------------- 3x3 区块的假世界

    /**
     * 一个按**区块**判可写的假世界：{@code chunkX/chunkZ ∈ [-radius, radius]} 且 y 在给定范围内。
     *
     * <p>刻意不复用 {@link FakeWorld}：那个实现的 {@code inRegion} 只看 y（等于「无写区限制」），
     * 正是 {@code Zone.of} 探针会自相矛盾的那种建模。这里同时统计「区域外被写了几次」——
     * {@code WorldView#setBlock} 在区域外是丢弃的，所以只要我这里有计数，就说明丢弃发生了
     * （= 形状没有先收进去）。
     */
    private static final class ZoneWorld implements WorldView {
        private final int radiusChunks;
        private final Map<Long, Material> blocks = new LinkedHashMap<>();
        private Random random = new Random(0L);
        private Point anchor = new Point(8, 50, 8);
        private int writesOutsideZone;

        ZoneWorld(int radiusChunks) {
            this.radiusChunks = radiusChunks;
        }

        void useRandom(Random random) {
            this.random = random;
        }

        void anchor(Point point) {
            this.anchor = point;
        }

        Point anchor() {
            return anchor;
        }

        void fill(int fromX, int toX, int fromY, int toY, Material material) {
            for (int x = fromX; x <= toX; x++) {
                for (int z = fromX; z <= toX; z++) {
                    for (int y = fromY; y <= toY; y++) {
                        blocks.put(key(x, y, z), material);
                    }
                }
            }
        }

        int placedCount() {
            int placed = 0;
            for (Material material : blocks.values()) {
                if (material != Material.AIR && material != Material.NETHERRACK) {
                    placed++;
                }
            }
            return placed;
        }

        int writesOutsideZone() {
            return writesOutsideZone;
        }

        private static long key(int x, int y, int z) {
            return ((long) (x & 0xFFFFF) << 40) | ((long) (y & 0xFFFFF) << 20) | (z & 0xFFFFF);
        }

        private boolean withinChunks(int x, int z) {
            if (radiusChunks < 0) {
                return true;   // 无限写区：用来测 WriteZone.UNBOUNDED 那条路径
            }
            final int chunkX = x >> 4;
            final int chunkZ = z >> 4;
            return chunkX >= -radiusChunks && chunkX <= radiusChunks
                    && chunkZ >= -radiusChunks && chunkZ <= radiusChunks;
        }

        @Override
        public boolean inRegion(int x, int y, int z) {
            return y >= 0 && y <= MAX_Y && withinChunks(x, z);
        }

        @Override
        public int minY() {
            return 0;
        }

        @Override
        public int maxY() {
            return MAX_Y;
        }

        @Override
        public boolean isAir(int x, int y, int z) {
            return inRegion(x, y, z) && blocks.getOrDefault(key(x, y, z), Material.AIR) == Material.AIR;
        }

        @Override
        public BlockData blockAt(int x, int y, int z) {
            if (!inRegion(x, y, z)) {
                return null;
            }
            return data(blocks.getOrDefault(key(x, y, z), Material.AIR));
        }

        @Override
        public void setBlock(int x, int y, int z, BlockData data) {
            if (!inRegion(x, y, z)) {
                writesOutsideZone++;
                return;
            }
            blocks.put(key(x, y, z), data.getMaterial());
        }

        @Override
        public String biomeAt(int x, int y, int z) {
            return "betternether:gloomwood";
        }

        @Override
        public String decoratedBiome() {
            return "betternether:gloomwood";
        }

        @Override
        public Random random() {
            return random;
        }

        // ---- 精确通道：假世界里「问得出来」的都照实回答，避免踩 BlockPredicates 的降级告警
        //（降级告警会走 Bukkit.getLogger()，单元测试里没有服务器）。

        @Override
        public Boolean replaceable(int x, int y, int z) {
            if (!inRegion(x, y, z)) {
                return Boolean.FALSE;
            }
            final Material material = blocks.getOrDefault(key(x, y, z), Material.AIR);
            return material == Material.AIR || material == Material.WATER || material == Material.LAVA;
        }

        /** 这个假世界不模拟附着/重力：能放上去就当作能活。 */
        @Override
        public Boolean wouldSurvive(BlockData data, int x, int y, int z) {
            return Boolean.TRUE;
        }

        /**
         * 假世界不认识任何 tag（{@code null} = 问不出来）。
         *
         * <p>刻意不是 {@code FALSE}：wover 的 {@code surfaces/*} tag 本来就不在服务端注册表里，
         * 真值语境下也是 null；返回 false 会让「地面」判据把一切都判成不是地面。
         */
        @Override
        public Boolean inTag(String tag, int x, int y, int z) {
            return null;
        }

        private static BlockData data(Material material) {
            return (BlockData) java.lang.reflect.Proxy.newProxyInstance(
                    ZoneWorld.class.getClassLoader(),
                    new Class<?>[]{BlockData.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getMaterial" -> material;
                        case "matches" -> args != null && args.length == 1
                                && ((BlockData) args[0]).getMaterial() == material;
                        case "clone", "copy" -> proxy;
                        case "getAsString" -> material.getKey().toString();
                        case "equals" -> proxy == args[0];
                        case "hashCode" -> material.hashCode();
                        case "toString" -> material.name();
                        default -> null;
                    });
        }
    }

    // ---------------------------------------------------------------- 记录抽样序列的随机源

    /** 记录每次抽样「类型」的随机源：I=nextInt、F=nextFloat、D=nextDouble、L=nextLong、G=nextGaussian、B=nextBoolean。 */
    private static final class RecordingRandom extends Random {
        private final Random delegate;
        private final StringBuilder trace = new StringBuilder();

        RecordingRandom(long seed) {
            this.delegate = new Random(seed);
        }

        String trace() {
            return trace.toString();
        }

        @Override
        public int nextInt() {
            trace.append('I');
            return delegate.nextInt();
        }

        @Override
        public int nextInt(int bound) {
            trace.append('I');
            return delegate.nextInt(bound);
        }

        @Override
        public float nextFloat() {
            trace.append('F');
            return delegate.nextFloat();
        }

        @Override
        public double nextDouble() {
            trace.append('D');
            return delegate.nextDouble();
        }

        @Override
        public long nextLong() {
            trace.append('L');
            return delegate.nextLong();
        }

        @Override
        public boolean nextBoolean() {
            trace.append('B');
            return delegate.nextBoolean();
        }

        @Override
        public double nextGaussian() {
            trace.append('G');
            return delegate.nextGaussian();
        }
    }

    // ---------------------------------------------------------------- 独立复算 HashSet<BlockPos> 顺序

    /**
     * 独立算一遍「{@code HashSet<BlockPos>} 会按什么顺序遍历」，用来对拍
     * {@link UpstreamComplexTreeFeatures.BlockPosOrderedSet}。
     *
     * <p>规则来自 {@code java.util.HashMap}：{@code spread = h ^ (h >>> 16)}、
     * {@code bucket = spread & (cap-1)}、桶升序、桶内插入序、{@code size > 0.75*cap} 时容量翻倍。
     * {@code h} 用上游 {@code Vec3i#hashCode = (y + z*31)*31 + x}。
     */
    private static List<Point> expectedBlockPosOrder(List<Point> insertion, int initialCapacity) {
        int capacity = initialCapacity;
        final List<Point> unique = new ArrayList<>();
        for (Point point : insertion) {
            if (!unique.contains(point)) {
                unique.add(point);
            }
        }
        // 容量在插入过程中按需翻倍：先算出「插入第 i 个之后」的容量。
        final List<Integer> capacityAfter = new ArrayList<>();
        int size = 0;
        for (int i = 0; i < unique.size(); i++) {
            size++;
            if (size > (int) (capacity * 0.75F)) {
                capacity <<= 1;
            }
            capacityAfter.add(capacity);
        }
        final int finalCapacity = capacityAfter.isEmpty() ? capacity : capacityAfter.get(capacityAfter.size() - 1);

        final Map<Integer, List<Point>> buckets = new TreeMap<>();
        for (Point point : unique) {
            final int hash = (point.y() + point.z() * 31) * 31 + point.x();
            buckets.computeIfAbsent((hash ^ (hash >>> 16)) & (finalCapacity - 1), ignored -> new ArrayList<>())
                    .add(point);
        }
        final List<Point> result = new ArrayList<>();
        for (List<Point> bucket : buckets.values()) {
            result.addAll(bucket);
        }
        return result;
    }

    private static List<Point> iterationOf(UpstreamComplexTreeFeatures.BlockPosOrderedSet set) {
        final List<Point> result = new ArrayList<>();
        for (Point point : set) {
            result.add(point);
        }
        return result;
    }

    // ---------------------------------------------------------------- 方块解析

    private static UpstreamComplexTreeFeatures.BlockResolver resolver() {
        final Map<String, Material> assigned = new LinkedHashMap<>();
        final Material[] pool = pool();
        return (id, properties) -> {
            if ("minecraft:air".equals(id)) {
                return ZoneWorld.data(Material.AIR);
            }
            if ("minecraft:ancient_debris".equals(id)) {
                return ZoneWorld.data(Material.ANCIENT_DEBRIS);
            }
            final StringBuilder key = new StringBuilder(id);
            new TreeMap<>(properties == null ? Map.of() : properties)
                    .forEach((k, v) -> key.append('|').append(k).append('=').append(v));
            final Material material = assigned.computeIfAbsent(key.toString(),
                    ignored -> pool[assigned.size() % pool.length]);
            return ZoneWorld.data(material);
        };
    }

    private static Material[] pool() {
        final List<Material> materials = new ArrayList<>();
        for (Material material : Material.values()) {
            if (material.name().startsWith("LEGACY_")) {
                continue;
            }
            if (material == Material.AIR || material == Material.NETHERRACK
                    || material == Material.NETHER_BRICKS || material == Material.ANCIENT_DEBRIS) {
                continue;
            }
            materials.add(material);
        }
        return materials.toArray(new Material[0]);
    }
}
