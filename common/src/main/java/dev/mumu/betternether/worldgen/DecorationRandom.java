package dev.mumu.betternether.worldgen;

/**
 * 原版世界装饰的随机源：与 mucore-26.3 的 {@code WorldgenRandom} +
 * {@code XoroshiroRandomSource} **逐位等价**，不是「同分布」近似。
 *
 * <h2>为什么必须逐位复刻</h2>
 * 上游每个 placed feature 的位置、数量、每个方块状态都由一条**独立**的随机流决定。
 * 原版链路的种子算法（{@code ChunkGenerator#addVanillaDecorations} 字节码实录）是：
 * <pre>
 * WorldgenRandom random = new WorldgenRandom(new XoroshiroRandomSource(RandomSupport.generateUniqueSeed()));
 * long decorationSeed = random.setDecorationSeed(level.getSeed(), origin.getX(), origin.getZ());
 * ...
 * random.setFeatureSeed(decorationSeed, featureIndex, step);
 * placer.placeWithBiomeCheck(placedFeature, random, origin);
 * </pre>
 * 其中 {@code setDecorationSeed}/{@code setFeatureSeed} 都只是 {@code setSeed(...)}，真正产生数列的是
 * {@code XoroshiroRandomSource} 里的 Xoroshiro128++。这里把它整套搬过来，任何一处常数写错都会让
 * 整条随机流错开，表现为「树形都对，但每棵树都长得不一样」。
 *
 * <h2>为什么继承 {@link java.util.Random}</h2>
 * 放置管线（{@link WorldView#random()}、{@link PlacementModifier}、各 Feature 实现）全部按
 * {@code java.util.Random} 取数。原版 {@code WorldgenRandom} 的 {@code next(int)} 委托给
 * Xoroshiro，正好对应 {@code java.util.Random} 里那个 protected 的 {@code next(int)} 钩子 ——
 * 覆写它，{@code nextInt/nextLong/nextFloat/nextBoolean} 就自动等价。
 *
 * <p>两个**必须单独覆写**的方法，因为原版与 JDK 的算法本身不同：
 * <ul>
 *   <li>{@link #nextDouble()}：原版 {@code BitRandomSource} 走 {@code (float)} 中转，
 *       再乘 {@code 1.110223E-16F} 后扩宽成 {@code double}。照抄 JDK 会丢掉那些位。</li>
 *   <li>{@link #nextGaussian()}：原版是 {@code MarsagliaPolarGaussian}（含一次缓存），
 *       JDK 是 Box-Muller。{@code clamped_normal} 的 int provider 会用到它。</li>
 * </ul>
 * {@code nextFloat()} 不必覆写：{@code next(24) * 5.9604645E-8F} 与
 * {@code next(24) / (float)(1<<24)} 是同一个值（2^24 是 2 的幂）。
 *
 * <h2>证据</h2>
 * 常数与运算顺序取自 {@code javap -p -c} 的 {@code Xoroshiro128PlusPlus}、
 * {@code RandomSupport}（{@code upgradeSeedTo128bit} / {@code mixStafford13}）、
 * {@code WorldgenRandom}、{@code BitRandomSource}、{@code MarsagliaPolarGaussian}。
 * 一组对照程序直接调用真实 NMS 类与本实现，在 6 个世界种子 × 6 个区块 × 7 个
 * (index, step) 上比对 {@code nextInt/nextInt(bound)/nextLong/nextFloat/nextDouble/
 * nextBoolean/nextGaussian} 的每一步取值：252 组全部一致（{@code DecorationRandomTest}
 * 固化其中若干条参考序列）。
 */
public final class DecorationRandom extends java.util.Random {

    /** {@code RandomSupport#upgradeSeedTo128bitUnmixed}：低位先异或这个常数。 */
    private static final long SEED_LO_XOR = 7640891576956012809L;
    /** {@code upgradeSeedTo128bitUnmixed}：高位 = 低位 + 这个常数。 */
    private static final long SEED_HI_OFFSET = -7046029254386353131L;
    /** {@code RandomSupport#mixStafford13} 的两个乘数。 */
    private static final long STAFFORD_MUL_1 = -4658895280553007687L;
    private static final long STAFFORD_MUL_2 = -7723592293110705685L;
    /** {@code Xoroshiro128PlusPlus} 构造函数对全零种子的替换值。 */
    private static final long ZERO_SEED_LO = -7046029254386353131L;
    private static final long ZERO_SEED_HI = 7640891576956012809L;

    private long seedLo;
    private long seedHi;
    private double nextNextGaussian;
    private boolean haveNextNextGaussian;

    private DecorationRandom(long seed) {
        super(0L);
        setSeed(seed);
    }

    /** 以 {@code seed} 为种子建一条流（原版 {@code new WorldgenRandom(...)} 的等价物）。 */
    public static DecorationRandom seeded(long seed) {
        return new DecorationRandom(seed);
    }

    // ---------------------------------------------------------------- 种子

    /**
     * 原版 {@code WorldgenRandom#setDecorationSeed(long, int, int)}。
     *
     * <h3>为什么把它做成静态</h3>
     * 「这个区块的装饰种子」是一块区块一次的量，跟具体哪条 placed feature 无关。
     * 做成静态 + 内部临时实例，调用点就不会误以为要复用一个带状态的实例。
     *
     * @param worldSeed 世界种子（{@code World#getSeed()}，即 {@code WorldGenLevel#getSeed()}）
     * @param blockX    区块起始方块 x（{@code chunkX << 4}）
     * @param blockZ    区块起始方块 z（{@code chunkZ << 4}）
     * @return 该区块的 decorationSeed，同时把内部流重置到它
     */
    public static long decorationSeed(long worldSeed, int blockX, int blockZ) {
        DecorationRandom scratch = new DecorationRandom(worldSeed);
        return scratch.setDecorationSeed0(worldSeed, blockX, blockZ);
    }

    /**
     * 原版 {@code setDecorationSeed}：{@code setSeed(worldSeed)} → 取两个长整数（各自 {@code |1}）
     * → {@code ((long)x * a + (long)z * b) ^ worldSeed} → {@code setSeed(该值)} → 返回它。
     */
    private long setDecorationSeed0(long worldSeed, int blockX, int blockZ) {
        setSeed(worldSeed);
        long a = nextLong() | 1L;
        long b = nextLong() | 1L;
        long decorationSeed = ((long) blockX * a + (long) blockZ * b) ^ worldSeed;
        setSeed(decorationSeed);
        return decorationSeed;
    }

    /**
     * 原版 {@code WorldgenRandom#setFeatureSeed(long, int, int)}：
     * {@code setSeed(decorationSeed + featureIndex + 10000L * step)}。
     *
     * <p>{@code featureIndex} 是**该 step 最终有序列表里的位置**（原版
     * {@code FeatureSorter.StepFeatureData#indexMapping()} 就是
     * {@code Util.createIndexIdentityLookup(features)}，即列表下标），不是注册序号、
     * 也不是 per-biome 下标。{@code step} 是 {@code GenerationStep.Decoration} 的 ordinal。
     */
    public void setFeatureSeed(long decorationSeed, int featureIndex, int step) {
        setSeed(decorationSeed + (long) featureIndex + 10000L * (long) step);
    }

    /** 按原版口径造一条「某条 placed feature 在这个区块」的独立流。 */
    public static DecorationRandom forFeature(long decorationSeed, int featureIndex, int step) {
        DecorationRandom random = new DecorationRandom(0L);
        random.setFeatureSeed(decorationSeed, featureIndex, step);
        return random;
    }

    // ---------------------------------------------------------------- 128 位种子展开

    /** {@code RandomSupport#mixStafford13}。 */
    private static long mixStafford13(long z) {
        z = (z ^ (z >>> 30)) * STAFFORD_MUL_1;
        z = (z ^ (z >>> 27)) * STAFFORD_MUL_2;
        return z ^ (z >>> 31);
    }

    /**
     * {@code XoroshiroRandomSource#setSeed(long)}：
     * {@code new Xoroshiro128PlusPlus(RandomSupport.upgradeSeedTo128bit(seed))}。
     */
    @Override
    public synchronized void setSeed(long seed) {
        long lo = seed ^ SEED_LO_XOR;
        long hi = lo + SEED_HI_OFFSET;
        this.seedLo = mixStafford13(lo);
        this.seedHi = mixStafford13(hi);
        // Xoroshiro128PlusPlus 构造函数：两个 64 位字全零时替换成固定非零种子。
        if ((this.seedLo | this.seedHi) == 0L) {
            this.seedLo = ZERO_SEED_LO;
            this.seedHi = ZERO_SEED_HI;
        }
        this.haveNextNextGaussian = false;
    }

    // ---------------------------------------------------------------- 取数

    /** {@code Xoroshiro128PlusPlus#nextLong}。 */
    private long nextXoroshiroLong() {
        long lo = seedLo;
        long hi = seedHi;
        long result = Long.rotateLeft(lo + hi, 17) + lo;
        hi ^= lo;
        seedLo = Long.rotateLeft(lo, 49) ^ hi ^ (hi << 21);
        seedHi = Long.rotateLeft(hi, 28);
        return result;
    }

    /** {@code WorldgenRandom#next(int)}：取 {@code nextLong()} 的高 {@code bits} 位。 */
    @Override
    protected int next(int bits) {
        return (int) (nextXoroshiroLong() >>> (64 - bits));
    }

    /** {@code BitRandomSource#nextDouble()}：{@code (float)} 中转的那一步不能省。 */
    @Override
    public double nextDouble() {
        int high = next(26);
        int low = next(27);
        long bits = ((long) high << 27) + (long) low;
        return (double) ((float) bits * 1.110223E-16F);
    }

    /** {@code MarsagliaPolarGaussian#nextGaussian()}（含一次缓存）。 */
    @Override
    public double nextGaussian() {
        if (haveNextNextGaussian) {
            haveNextNextGaussian = false;
            return nextNextGaussian;
        }
        double first;
        double second;
        double square;
        do {
            first = 2.0 * nextDouble() - 1.0;
            second = 2.0 * nextDouble() - 1.0;
            square = first * first + second * second;
        } while (square >= 1.0 || square == 0.0);
        double scale = Math.sqrt(-2.0 * Math.log(square) / square);
        nextNextGaussian = second * scale;
        haveNextNextGaussian = true;
        return first * scale;
    }
}
