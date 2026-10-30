package dev.mumu.betternether.worldgen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 装饰随机源必须与**本机真实服务端**逐个取值一致，而不只是「看起来像随机」。
 *
 * <h2>为什么这条测试必须存在</h2>
 * 种子错一位的表现不是崩溃，而是「树形都对、每棵树都长得不一样」—— 这种偏差肉眼无法归因，
 * 也不会有人报 bug，只会被当成「原来就这样」。所以这里钉住两件事：
 * <ol>
 *   <li>{@code setDecorationSeed} 的输出值（区块种子）；</li>
 *   <li>{@code setFeatureSeed} 之后前几个 {@code nextInt(1000)}。</li>
 * </ol>
 * Xoroshiro 的常数写错、{@code next(int)} 的移位写错、{@code 10000L * step} 写错，
 * 三者都会在这里变红。
 *
 * <h2>这些参考值从哪来</h2>
 * 由一段直接调用 {@code net.minecraft.world.level.levelgen.WorldgenRandom} +
 * {@code XoroshiroRandomSource}（{@code mucore-26.3.jar}，本机服务端本体）的对照程序打印。
 * 同一程序还与 {@link DecorationRandom} 在「6 个世界种子 × 6 个区块 × 7 组 (index, step)」上
 * 逐步比对了 {@code nextInt / nextInt(bound) / nextLong / nextFloat / nextDouble /
 * nextBoolean / nextGaussian}：252 组全部一致。这里固化其中一条世界种子作为回归锚点。
 */
class DecorationRandomTest {

    /** 对照程序使用的世界种子；参考值全部由真实 NMS 类产出。 */
    private static final long WORLD_SEED = 123456789012345L;

    /** 对照程序使用的区块（方块坐标，即 {@code -37<<4} 与 {@code 91<<4}）。 */
    private static final int CHUNK_X = -37;
    private static final int CHUNK_Z = 91;

    /**
     * {@code setDecorationSeed} 的输出必须逐位相同。
     *
     * <p>它决定「这个区块的装饰世界」—— 差一位，整块区块的所有特征全部换位置。
     */
    @Test
    void decorationSeedMatchesVanilla() {
        assertEquals(-3230959737316046215L,
                DecorationRandom.decorationSeed(WORLD_SEED, CHUNK_X << 4, CHUNK_Z << 4),
                "setDecorationSeed(worldSeed, -37<<4, 91<<4) 的返回值与真实 NMS 不一致");
    }

    /** decorationSeed 与区块位置强相关：不同区块不能撞成同一个。 */
    @Test
    void decorationSeedDependsOnChunk() {
        long origin = DecorationRandom.decorationSeed(WORLD_SEED, 0, 0);
        long east = DecorationRandom.decorationSeed(WORLD_SEED, 16, 0);
        long south = DecorationRandom.decorationSeed(WORLD_SEED, 0, 16);
        assertNotEquals(origin, east);
        assertNotEquals(origin, south);
        assertNotEquals(east, south);
    }

    /**
     * {@code setFeatureSeed(decorationSeed, index, step)} 之后的数列必须与真实 NMS 相同。
     *
     * <p>参考值覆盖 step 0 / 6 / 7 / 9：step 直接进 {@code 10000L * step}，
     * 任何一个 step 映射写错都会在这里暴露。
     */
    @Test
    void featureSeedMatchesVanilla() {
        long decorationSeed = DecorationRandom.decorationSeed(
                WORLD_SEED, CHUNK_X << 4, CHUNK_Z << 4);
        assertEquals("759,637,959,564,386", sequence(decorationSeed, 0, 0));
        assertEquals("104,14,296,127,809", sequence(decorationSeed, 5, 6));
        assertEquals("450,964,193,773,665", sequence(decorationSeed, 11, 7));
        assertEquals("368,448,271,361,446", sequence(decorationSeed, 2, 9));
    }

    /**
     * 同一个 {@code (decorationSeed, index, step)} 必须给出同一条流。
     *
     * <p>这条锁的是「装饰是确定性的」：原版每条 placed feature 一条独立流，
     * 若实现里偷偷复用一个实例（或忘了 {@code setSeed}），重放就会漂。
     */
    @Test
    void sameSeedReplaysTheSameStream() {
        long decorationSeed = DecorationRandom.decorationSeed(WORLD_SEED, 12 << 4, -34 << 4);
        assertEquals(sequence(decorationSeed, 4, 9), sequence(decorationSeed, 4, 9));
    }

    /** index 与 step 都必须参与种子：任何一个被忽略，两条流就会相同。 */
    @Test
    void indexAndStepBothChangeTheStream() {
        long decorationSeed = DecorationRandom.decorationSeed(WORLD_SEED, 0, 0);
        String base = sequence(decorationSeed, 3, 9);
        assertNotEquals(base, sequence(decorationSeed, 4, 9), "index 必须参与种子");
        assertNotEquals(base, sequence(decorationSeed, 3, 8), "step 必须参与种子");
    }

    /** 前 5 个 {@code nextInt(1000)} 的逗号拼接（对照程序的取样口径）。 */
    private static String sequence(long decorationSeed, int index, int step) {
        DecorationRandom random = DecorationRandom.forFeature(decorationSeed, index, step);
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(random.nextInt(1000));
        }
        return builder.toString();
    }
}
