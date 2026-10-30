package dev.mumu.betternether.biome;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BiomePlacement} 的语义：<b>原版 {@code multi_noise} 的精确最近邻</b>，参数表 = 5 个原版下界点
 * + 23 个 {@code biome_data} 点。
 *
 * <h2>这些用例在验什么</h2>
 * 每一个用例都对应一条可指到源码/字节码/数据文件的事实，而不是"我觉得应该是这样"：
 * <ul>
 *   <li>{@link #tableMatchesTheGeneratedBiomeDataFiles()} 逐字段读上游 datagen 的 23 个 JSON。</li>
 *   <li>{@link #tableHoldsTheFiveVanillaNetherPresetPoints()} 钉住原版预设的 5 个点。</li>
 *   <li>{@link #quantizationMatchesVanillaTruncation()} 钉住 {@code Climate.quantizeCoord} 的 float 截断。</li>
 *   <li>{@link #fitnessIsSixSquaredDistancesPlusOffsetSquared()} 用 javap 读出的公式复算一遍。</li>
 *   <li>{@link #selectionIsTheArgminOfFitness()} 把选择与"逐点算 fitness 取最小"交叉验证。</li>
 *   <li>{@link #offsetIsACostNotAnAxis()} 证明 offset 不参与距离、只参与成本。</li>
 *   <li>{@link #quartConversionFloorsTowardNegativeInfinity()} / {@link #keyAtSamplesInQuartCoordinates()}
 *       证明 quart 换算向负无穷取整且真的按 quart 采样。</li>
 *   <li>{@link #heightNeverChangesTheAnswer()} 证明默认路径没有竖直气候轴。</li>
 *   <li>{@link #climateUnreachableBiomesArePinned()} 把"这套参数表下谁永远选不上"钉成清单。</li>
 * </ul>
 */
class BiomePlacementTest {
    /** 上游 datagen 输出目录；测试的工作目录是 {@code common/}。 */
    private static final Path BIOME_DATA = Path.of("..", "upstream", "BetterNether", "src", "main",
            "generated", "data", "betternether", "wover", "worldgen", "biome_data");

    private static final Pattern NUMBER = Pattern.compile(
            "\"(temperature|humidity|continentalness|erosion|depth|weirdness|offset)\"\\s*:\\s*(-?[0-9.]+)");

    // ------------------------------------------------------------------ 参数表

    /**
     * 23 行必须与上游 datagen 的 JSON 逐字段一致。
     *
     * <p>这是"参数点不是猜的"的唯一硬证据：把 {@code biome_data/*.json} 的
     * {@code generation_data.parameter_points[0]} 与 {@code genChance} 读出来，直接和
     * {@link BiomePlacement#climates()} 比。任何一个人手抄错的数字都会在这里炸。
     */
    @Test
    void tableMatchesTheGeneratedBiomeDataFiles() throws IOException {
        assertTrue(Files.isDirectory(BIOME_DATA), "找不到上游 datagen 目录：" + BIOME_DATA.toAbsolutePath());
        List<String> checked = new ArrayList<>();
        for (NetherBiomes.Entry entry : NetherBiomes.ALL) {
            Path file = BIOME_DATA.resolve(entry.key() + ".json");
            assertTrue(Files.isRegularFile(file), "上游 datagen 缺文件：" + file);
            String json = Files.readString(file);

            BiomePlacement.Climate row = BiomePlacement.climateOf(entry.key());
            assertNotNull(row, "参数表缺行：" + entry.key());

            assertEquals(axisValue(json, "temperature"), row.temperature(), 0.0f,
                    entry.key() + " 的 temperature 与 JSON 不一致");
            assertEquals(axisValue(json, "humidity"), row.humidity(), 0.0f,
                    entry.key() + " 的 humidity 与 JSON 不一致");
            assertEquals(axisValue(json, "offset"), row.offset(), 0.0f,
                    entry.key() + " 的 offset 与 JSON 不一致");
            // 另外四轴在 23 个文件里都是 0.0；这里顺带把"JSON 确实只有一组参数点"钉住。
            for (String axis : List.of("continentalness", "erosion", "depth", "weirdness")) {
                assertEquals(0.0f, axisValue(json, axis), 0.0f, entry.key() + " 的 " + axis + " 在 JSON 里不是 0");
            }
            assertEquals(floatField(json, "genChance"), row.genChance(), 0.0f,
                    entry.key() + " 的 genChance 与 JSON 不一致");
            checked.add(entry.key());
        }
        assertEquals(23, checked.size(), "BetterNether 的群系数量变了：23 -> " + checked.size());
    }

    /** {@code genChance} 在 JSON 里已经被 {@code GEN_CHANCE_FACTOR = 1.25f} 乘过，表里存的就是乘后的值。 */
    @Test
    void genChanceIsStoredAsTheJsonWritesIt() {
        assertEquals(21.0625, BiomePlacement.totalGenChance(), 1.0e-9,
                "23 行的 genChance 之和（1.25/0.625/0.375/0.3125 的加权计数）");
        assertEquals(1.25, BiomePlacement.weightOf("magma_land"), 1.0e-9);
        assertEquals(0.3125, BiomePlacement.weightOf(BiomePlacement.VERTICAL_KEY), 1.0e-9);
        assertNull(BiomePlacement.weightOf("not_a_biome"), "未声明的键不应有权重");
    }

    /**
     * 原版 {@code minecraft:nether} 预设的 5 个点。
     *
     * <p>数值来源是一次直接读取：{@code MultiNoiseBiomeSourceParameterList.Preset.NETHER
     * .provider().apply(Function.identity())}（javap 已确认 {@code provider()} 与
     * {@code SourceProvider.apply} 的签名），逐点打印出 temperature/humidity/offset。
     * 其中 {@code warped_forest} 的 offset {@code 0.375}、{@code basalt_deltas} 的 {@code 0.175}
     * 就是上游 {@code old_warped_woods} / {@code flooded_deltas} 照抄的那两个数。
     */
    @Test
    void tableHoldsTheFiveVanillaNetherPresetPoints() {
        record Expected(String key, float temperature, float humidity, float offset) {
        }
        List<Expected> expected = List.of(
                new Expected("minecraft:nether_wastes", 0.0f, 0.0f, 0.0f),
                new Expected("minecraft:soul_sand_valley", 0.0f, -0.5f, 0.0f),
                new Expected("minecraft:crimson_forest", 0.4f, 0.0f, 0.0f),
                new Expected("minecraft:warped_forest", 0.0f, 0.5f, 0.375f),
                new Expected("minecraft:basalt_deltas", -0.5f, 0.0f, 0.175f));

        for (Expected point : expected) {
            BiomePlacement.Climate row = BiomePlacement.climateOf(point.key());
            assertNotNull(row, "参数表缺原版点：" + point.key());
            assertTrue(row.vanilla(), point.key() + " 应被标记为原版行");
            assertEquals(point.temperature(), row.temperature(), 0.0f, point.key() + " temperature");
            assertEquals(point.humidity(), row.humidity(), 0.0f, point.key() + " humidity");
            assertEquals(point.offset(), row.offset(), 0.0f, point.key() + " offset");
        }
        assertEquals(28, BiomePlacement.climates().size(), "5 个原版 + 23 个 BetterNether");
        // 原版 5 个必须排在前面：合并顺序是并列时的裁决依据。
        for (int i = 0; i < NetherBiomes.VANILLA_NETHER.size(); i++) {
            assertEquals(NetherBiomes.VANILLA_NETHER.get(i), BiomePlacement.climates().get(i).key(),
                    "第 " + i + " 行应是原版点");
        }
        for (int i = 0; i < NetherBiomes.ALL.size(); i++) {
            assertEquals(NetherBiomes.ALL.get(i).key(),
                    BiomePlacement.climates().get(NetherBiomes.VANILLA_NETHER.size() + i).key(),
                    "BetterNether 行必须保持 ALL 的顺序（并列时的裁决顺序）");
        }
    }

    /**
     * 量化是 {@code (long) (value * 10000.0F)}，在 <b>float</b> 里算完再截断。
     *
     * <p>{@code -0.01F * 10000.0F} 的 float 结果是 {@code -100.0f}（乘积先按 float 舍入），
     * 所以量化值是 {@code -100} 而不是用 {@code double} 算出来的 {@code -99}。用 double 复算这张表
     * 会得到每轴最多 1 个量化单位的偏差，边界上就足以换一个群系。
     */
    @Test
    void quantizationMatchesVanillaTruncation() {
        assertEquals(10000L, TargetPoint.quantize(1.0f));
        assertEquals(-5000L, TargetPoint.quantize(-0.5f));
        assertEquals(3750L, TargetPoint.quantize(0.375f));
        assertEquals(1750L, TargetPoint.quantize(0.175f));
        assertEquals(8000L, TargetPoint.quantize(0.8f));
        assertEquals(7000L, TargetPoint.quantize(0.7f));
        assertEquals(6500L, TargetPoint.quantize(0.65f));
        assertEquals(-100L, TargetPoint.quantize(-0.01f), "-0.01F*10000.0F 的 float 乘积是 -100.0f");
        assertEquals(-500L, TargetPoint.quantize(-0.05f));
        assertEquals(-3500L, TargetPoint.quantize(-0.35f));
        assertEquals(0.375f, TargetPoint.unquantize(3750L), 0.0f);
    }

    // ------------------------------------------------------------------ 规则

    /**
     * 适应度公式：六个轴的 {@code distance²} 之和，再加 {@code offset²}。
     *
     * <p>公式来自 {@code Climate$ParameterPoint#fitness} 的字节码：六次
     * {@code Parameter.distance → Mth.square → ladd}，最后
     * {@code getfield offset → Mth.square → ladd}。
     */
    @Test
    void fitnessIsSixSquaredDistancesPlusOffsetSquared() {
        // old_warped_woods: T=0.0 H=0.55 offset=0.375；采样点取 T=0.0 H=0.5（原版 warped_forest 的位置）。
        BiomePlacement.Climate oldWarpedWoods = BiomePlacement.climateOf("old_warped_woods");
        TargetPoint target = TargetPoint.ofClimate(0.0f, 0.5f);

        long expected = 0L                       // 温度相同
                + 500L * 500L                    // 湿度差 0.05 → 500 个量化单位
                + 0L * 0L + 0L * 0L + 0L * 0L + 0L * 0L // 另外四轴两边都是 0
                + 3750L * 3750L;                 // offset 平方，是成本
        assertEquals(expected, BiomePlacement.fitness(oldWarpedWoods, target));

        // 原版 warped_forest 在同一位置：距离 0，offset 与它相同 → 只剩 offset²。
        BiomePlacement.Climate vanillaWarped = BiomePlacement.climateOf("minecraft:warped_forest");
        assertEquals(3750L * 3750L, BiomePlacement.fitness(vanillaWarped, target));
        assertTrue(BiomePlacement.fitness(vanillaWarped, target) < expected,
                "同一气候点上，距离更近的必须赢得适应度比较");
    }

    /**
     * 选择 = 适应度最小的那个参数点。把 {@link BiomePlacement#select} 与"逐点 {@code fitness} 取最小"
     * 在整张气候矩形上交叉验证，避免两条路径各写一遍还各错一遍。
     */
    @Test
    void selectionIsTheArgminOfFitness() {
        List<BiomePlacement.Climate> rows = BiomePlacement.climates();
        for (int t = -120; t <= 120; t++) {
            for (int h = -120; h <= 120; h++) {
                TargetPoint target = TargetPoint.ofClimate(t / 100.0f, h / 100.0f);
                String expected = null;
                long best = Long.MAX_VALUE;
                for (BiomePlacement.Climate row : rows) {
                    long fitness = BiomePlacement.fitness(row, target);
                    if (fitness < best) {
                        best = fitness;
                        expected = row.key();
                    }
                }
                assertEquals(expected, BiomePlacement.select(target),
                        "采样点不在 " + expected + " 时，select 与逐点 argmin 不一致：" + target);
            }
        }
    }

    /**
     * {@code offset} 是成本，不是第三个距离轴。
     *
     * <p>两个参数点温度湿度完全相同、{@code offset} 不同时，在它们共同的点上一定由 {@code offset}
     * 小的那个赢；而且把 {@code offset} 当成第二个坐标去算"距离"会得到相反的结论。
     */
    @Test
    void offsetIsACostNotAnAxis() {
        BiomePlacement.Climate plain = BiomePlacement.climateOf("nether_grasslands");      // offset 0.0
        BiomePlacement.Climate shifted = BiomePlacement.climateOf("nether_mushroom_forest_edge"); // 0.1
        assertEquals(plain.temperature(), shifted.temperature(), 0.0f);
        assertEquals(plain.humidity(), shifted.humidity(), 0.0f);

        TargetPoint at = TargetPoint.ofClimate(0.0f, 0.7f);
        assertTrue(BiomePlacement.fitness(plain, at) < BiomePlacement.fitness(shifted, at));
        assertEquals(0L, BiomePlacement.fitness(plain, at));
        assertEquals(1000L * 1000L, BiomePlacement.fitness(shifted, at));

        // 若把 offset 当坐标，shifted 会离采样点更远；但采样点自己就在 (0.0, 0.7)，两种读法的差别
        // 只在成本项上。用一个 offset 更大的点再证一次方向：offset 越大越吃亏。
        long tighter = BiomePlacement.fitness(BiomePlacement.climateOf("gravel_desert"),
                TargetPoint.ofClimate(-0.1f, 0.1f));
        long looser = BiomePlacement.fitness(BiomePlacement.climateOf("old_warped_woods"),
                TargetPoint.ofClimate(0.0f, 0.55f));
        assertEquals(2000L * 2000L, tighter, "gravel_desert 在自己点上的成本就是 offset²");
        assertEquals(3750L * 3750L, looser, "old_warped_woods 在自己点上的成本就是 offset²");
        assertTrue(looser > tighter, "offset 越大成本越大（越不容易被选中）");
    }

    // ------------------------------------------------------------------ 坐标

    /**
     * quart 换算向负无穷取整：{@code QuartPos.fromBlock(i) = i >> 2}。
     *
     * <p>这条不是细节：{@code -1 / 4 == 0} 会把 {@code x ∈ [-4, 4)} 并成一格，负半轴的群系边界
     * 整体错 4 格，而且正负两侧不再对称。
     */
    @Test
    void quartConversionFloorsTowardNegativeInfinity() {
        assertEquals(0, BiomePlacement.quart(0));
        assertEquals(0, BiomePlacement.quart(3));
        assertEquals(1, BiomePlacement.quart(4));
        assertEquals(1, BiomePlacement.quart(7));
        assertEquals(-1, BiomePlacement.quart(-1), "-1 >> 2 == -1（不是 0）");
        assertEquals(-1, BiomePlacement.quart(-4));
        assertEquals(-2, BiomePlacement.quart(-5));
        assertEquals(-2, BiomePlacement.quart(-8));
        assertEquals(-3, BiomePlacement.quart(-9));
        // 与"除法"对照，说明为什么不能用除法。
        assertEquals(0, -1 / 4);
        assertFalse(BiomePlacement.quart(-1) == -1 / 4, "除法会把负半轴并进 0");
    }

    /** 采样器收到的必须是 quart 坐标，且三参重载把 {@code y} 也换算了。 */
    @Test
    void keyAtSamplesInQuartCoordinates() {
        List<int[]> calls = new ArrayList<>();
        ClimateSampler recorder = (quartX, quartY, quartZ) -> {
            calls.add(new int[]{quartX, quartY, quartZ});
            return TargetPoint.ofClimate(0.0f, 0.0f);
        };
        BiomePlacement placement = new BiomePlacement(recorder);

        placement.keyAt(0, 0);
        assertEquals(1, calls.size());
        assertEquals(0, calls.get(0)[0]);
        assertEquals(BiomePlacement.SAMPLE_QUART_Y, calls.get(0)[1]);
        assertEquals(0, calls.get(0)[2]);

        calls.clear();
        placement.keyAt(-1, -5);
        assertEquals(-1, calls.get(0)[0], "x=-1 的 quart 是 -1");
        assertEquals(-2, calls.get(0)[2], "z=-5 的 quart 是 -2");

        calls.clear();
        placement.keyAt(-1, 70, 9);
        assertEquals(-1, calls.get(0)[0]);
        assertEquals(17, calls.get(0)[1], "y=70 的 quart 是 17");
        assertEquals(2, calls.get(0)[2], "z=9 的 quart 是 2");
    }

    /**
     * 本类<b>自身</b>不引入任何"随高度变化"的规则：同一列的回答与高度无关。
     *
     * <p>这条只覆盖 {@link BiomePlacement} 这一层可证伪的部分 —— 上一版在类里叠了一条
     * "y≥96 才算 {@code upside_down_forest}" 的竖带，本用例就是删掉它之后留下的负向断言。
     *
     * <p><b>它不是"下界群系与 y 无关"的证明。</b>两个重载给出同一答案，是因为这里的采样器
     * （{@link TestClimatePlane}）本身不读 y。真实采样器是不是也这样，取决于该世界实际用的
     * {@code NoiseGeneratorSettings}：26.3 原版 {@code data/minecraft/worldgen/noise_settings/nether.json}
     * 的 {@code noise_router} 里 {@code temperature}/{@code vegetation} 是 {@code y_scale: 0.0}
     * 的噪声、{@code depth} 等四轴是常量 {@code 0.0}，<b>在那个特定设置下</b>才成立。
     * {@link #theTwoOverloadsDivergeWhenTheRouterDependsOnHeight()} 用反例把这条依赖性写死：
     * 只要换成一个真读 y 的采样器，两个重载立刻不再一致 —— 也就是说"恒 2D"是环境性质，
     * 不是本类可以无条件声称的性质。
     */
    @Test
    void thisClassAddsNoHeightDependentRule() {
        BiomePlacement placement = new BiomePlacement(TestClimatePlane.seeded(20260102L));
        for (int x = -64; x <= 64; x += 7) {
            for (int z = -64; z <= 64; z += 7) {
                String column = placement.keyAt(x, z);
                for (int y = 0; y <= 127; y++) {
                    assertEquals(column, placement.keyAt(x, y, z),
                            "本类不得引入随高度变化的规则：" + x + "," + y + "," + z);
                }
            }
        }
    }

    /**
     * 反例：采样器一旦读 y，`keyAt(x, z)` 与 `keyAt(x, y, z)` 立刻分叉。
     *
     * <p>这条的作用是防止"恒 2D"被当成无条件成立的性质。{@code keyAt(x, z)} 用的是
     * {@link BiomePlacement#SAMPLE_QUART_Y}（0），它<b>只在</b>该世界的
     * {@code NoiseGeneratorSettings} 对 y 不敏感时才是精确的；一旦换成读 y 的设置，二维入口就必须
     * 补一个高度参数，否则地表/populator 会与 biome provider 给出不同答案。
     */
    @Test
    void theTwoOverloadsDivergeWhenTheRouterDependsOnHeight() {
        // 这是一片"越往上越热越干"的假气候，只用来证明本类没有偷偷把 y 丢掉。
        ClimateSampler heightDependent = (quartX, quartY, quartZ) -> quartY >= 24
                ? TargetPoint.ofClimate(0.8f, -0.7f)   // magma_land 自己的参数点
                : TargetPoint.ofClimate(-0.5f, 0.0f);  // basalt_deltas 自己的参数点
        BiomePlacement placement = new BiomePlacement(heightDependent);

        String column = placement.keyAt(0, 0);
        String high = placement.keyAt(0, 120, 0);
        assertFalse(column.equals(high),
                "采样器读 y 时，两个重载必然分叉：column=" + column + " high=" + high);
        assertEquals("minecraft:basalt_deltas", column, "quart y=0 → 温度 -0.5 → basalt_deltas");
        assertEquals("magma_land", high, "quart y=30 ≥ 24 → 温度 0.8 → magma_land");
    }

    /** 同一个采样点永远得到同一个答案（选择无随机、无状态）。 */
    @Test
    void selectionIsDeterministicAndStateless() {
        BiomePlacement first = new BiomePlacement(TestClimatePlane.seeded(4242L));
        BiomePlacement second = new BiomePlacement(TestClimatePlane.seeded(4242L));
        int differences = 0;
        for (int x = -256; x <= 256; x += 4) {
            for (int z = -256; z <= 256; z += 4) {
                String a = first.keyAt(x, z);
                assertEquals(a, first.keyAt(x, z), "同一列两次答案必须相同");
                String b = second.keyAt(x, z);
                assertEquals(a, b, "同种子的两个实例必须一致：" + x + "," + z);
                if (!a.equals(b)) differences++;
            }
        }
        assertEquals(0, differences);
    }

    /** 采样器为 {@code null} 时构造必须失败：不允许悄悄退化到"没有气候"。 */
    @Test
    void samplerIsMandatory() {
        org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class,
                () -> new BiomePlacement(null),
                "没有采样器就没有气候；不允许构造出一个会瞎猜的实例");
    }

    /**
     * 裁决器可替换，且必须给。
     *
     * <p>生产用 {@code NmsBiomeSelector}（原版 {@code Climate.ParameterList#findValue}），测试用
     * 线性参考 {@link BiomePlacement#select}。这里锁的是"接口真的被用上了"：塞一个恒返回
     * {@code wart_forest} 的裁决器，所有列都必须变成它 —— 否则说明 {@code keyAt} 绕过了裁决器、
     * 生产路径根本没走 NMS。
     */
    @Test
    void selectorIsHonouredAndMandatory() {
        BiomePlacement forced = new BiomePlacement(TestClimatePlane.seeded(5L),
                target -> "wart_forest");
        for (int x = -80; x <= 80; x += 13) {
            for (int z = -80; z <= 80; z += 13) {
                assertEquals("wart_forest", forced.keyAt(x, z), "裁决器必须真的被调用");
                assertEquals("wart_forest", forced.keyAt(x, 40, z));
            }
        }
        // 默认构造（不传裁决器）走线性参考：与显式传 BiomePlacement::select 完全一致。
        BiomePlacement byDefault = new BiomePlacement(TestClimatePlane.seeded(5L));
        BiomePlacement explicit = new BiomePlacement(TestClimatePlane.seeded(5L),
                BiomePlacement::select);
        for (int x = -80; x <= 80; x += 13) {
            assertEquals(byDefault.keyAt(x, 0), explicit.keyAt(x, 0),
                    "默认裁决器必须就是线性参考实现");
        }
        org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class,
                () -> new BiomePlacement(TestClimatePlane.seeded(5L), null),
                "没有裁决器就没有群系表被选中；不允许构造");
    }

    // ------------------------------------------------------------------ 可达性

    /**
     * 这套参数表下<b>永远选不上</b>的群系，钉成清单。
     *
     * <p>这不是缺陷，是上游数据 + 原版规则推出来的算术结果：{@code offset} 平方后加进适应度，
     * 于是 {@code nether_mushroom_forest}（0.0, 0.7, offset 0）与
     * {@code nether_grasslands}（0.0, 0.7, offset 0）到处完全并列，而合并顺序里 grasslands 在前；
     * {@code nether_mushroom_forest_edge} 少一个 offset 就多 {@code 1000²} 的成本；
     * {@code sulfuric_bone_reef} 的 {@code 0.37} offset 换算成成本等于 0.37 个气候单位的距离优势；
     * {@code old_warped_woods}（0.0, 0.55, 0.375）与 {@code minecraft:warped_forest}
     * （0.0, 0.5, 0.375）都被高湿度侧的 {@code nether_grasslands}（offset 0）压掉。
     *
     * <p>把它们钉住，是为了让"某个群系突然不生成"永远从一处可读的地方开始排查，而不是被悄悄改成
     * 一套自造的加权抽签。
     */
    @Test
    void climateUnreachableBiomesArePinned() {
        Set<String> reachable = new LinkedHashSet<>();
        int steps = 400;
        for (int t = 0; t <= steps; t++) {
            for (int h = 0; h <= steps; h++) {
                reachable.add(BiomePlacement.select(
                        TargetPoint.ofClimate(-1.2f + 2.4f * t / steps, -1.2f + 2.4f * h / steps)));
            }
        }
        Set<String> unreachable = new LinkedHashSet<>();
        for (BiomePlacement.Climate row : BiomePlacement.climates()) {
            if (!reachable.contains(row.key())) unreachable.add(row.key());
        }
        assertEquals(Set.of(
                        "minecraft:warped_forest",
                        "nether_mushroom_forest",
                        "nether_mushroom_forest_edge",
                        "old_warped_woods",
                        "sulfuric_bone_reef"),
                unreachable,
                "不可达清单变了：先查是不是参数表被改动，不要用规则去凑");
        assertEquals(23, reachable.size(), "28 个参数点里 23 个可达");
    }

    // ------------------------------------------------------------------ provider 侧

    /**
     * {@code NetherBiomeProvider} 与地形/populator 读到的是同一个 {@link BiomePlacement} 实例。
     *
     * <p>这一条正是"provider 与 populator 共用"的可测形式：{@code NetherTerrainGenerator} 在
     * {@code bindTo} 里只造一个 {@link BiomePlacement}，provider 与各 populator 都拿它。
     * 这里用同一个实例包一层 provider，断言键完全一致；不同实例则必然在边界上错开。
     */
    @Test
    void providerAndPlacementAgreeOnEveryColumn() {
        BiomePlacement placement = new BiomePlacement(TestClimatePlane.seeded(99L));
        NetherBiomeProvider provider = new NetherBiomeProvider(placement,
                new NetherBiomes.Resolved(java.util.Map.of(), List.of()));
        // keyAt 没有世界可传，必须先解析一次（生产路径里由 getBiome 完成）。
        provider.resolveFor(null);
        for (int x = -128; x <= 128; x += 3) {
            for (int z = -128; z <= 128; z += 3) {
                assertEquals(placement.keyAt(x, z), provider.keyAt(x, z),
                        "provider 与地形侧必须共用同一个 placement：" + x + "," + z);
            }
        }
    }

    /**
     * provider 的构造必须是<b>纯构造</b>：世界构建阶段解析不了采样器。
     *
     * <p>依据（javap 实读 26.3 服务端）：{@code MinecraftServer} 先
     * {@code new CraftWorldInfo(...)}，再调 {@code ChunkGenerator#getDefaultBiomeProvider(WorldInfo)}，
     * 之后才 {@code new ServerLevel(...)}；{@code ServerLevel} 构造里 527 处才
     * {@code new CustomWorldChunkManager(...)}，681 处才 {@code putfield chunkSource}。
     * 也就是说 provider 被创建时既没有 {@code ServerLevel}、也没有
     * {@code ServerChunkCache#randomState()}，连 {@code Bukkit.getWorld(name)} 都是 null。
     *
     * <p>所以本用例锁三件事：构造与 {@code getBiomes} 都不触碰来源；没有世界可传的
     * {@link NetherBiomeProvider#keyAt} 在解析前会响而不是瞎猜；解析只做一次。
     */
    @Test
    void providerDoesNotResolveItsSamplerAtConstruction() {
        int[] calls = {0};
        NetherBiomeProvider provider = new NetherBiomeProvider(() -> {
            calls[0]++;
            return new BiomePlacement(TestClimatePlane.fixed(0.0f, 0.0f));
        }, new NetherBiomes.Resolved(java.util.Map.of(), List.of()));

        assertEquals(0, calls[0], "构造 provider 时不允许解析采样器");
        assertEquals(List.of(), provider.getBiomes(null), "getBiomes 也不能触发解析");
        assertEquals(0, calls[0], "getBiomes 之后仍不允许解析");

        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> provider.keyAt(0, 0),
                "没有世界可传的 keyAt 在解析前必须响，不能用一个空世界去解析");
        assertEquals(0, calls[0], "失败的 keyAt 不得触发解析");

        BiomePlacement resolved = provider.resolveFor(null);
        assertEquals(1, calls[0], "显式解析只做一次");
        assertEquals("minecraft:nether_wastes", resolved.keyAt(0, 0));
        provider.resolveFor(null);
        assertEquals(1, calls[0], "后续解析必须复用同一个实例");
        assertEquals("minecraft:nether_wastes", provider.keyAt(0, 0), "解析后 keyAt 可直接用");
    }

    /** 每个声明过的键都有参数行；原版键不能与 BetterNether 键撞。 */
    @Test
    void everyDeclaredKeyHasExactlyOnePoint() {
        Set<String> keys = new LinkedHashSet<>();
        for (BiomePlacement.Climate row : BiomePlacement.climates()) {
            assertTrue(keys.add(row.key()), "参数表出现重复键：" + row.key());
        }
        for (NetherBiomes.Entry entry : NetherBiomes.ALL) {
            assertTrue(keys.contains(entry.key()), "群系缺少参数行：" + entry.key());
        }
        for (String key : NetherBiomes.VANILLA_NETHER) {
            assertTrue(keys.contains(key), "原版下界群系缺少参数行：" + key);
        }
        assertEquals(NetherBiomes.VANILLA_NETHER.size() + NetherBiomes.ALL.size(), keys.size());
    }

    // ------------------------------------------------------------------ JSON 读取

    private static float axisValue(String json, String axis) {
        Matcher matcher = NUMBER.matcher(json);
        while (matcher.find()) {
            if (matcher.group(1).equals(axis)) {
                return Float.parseFloat(matcher.group(2));
            }
        }
        throw new IllegalStateException("JSON 里找不到字段 " + axis);
    }

    private static float floatField(String json, String field) {
        Matcher matcher = Pattern.compile("\"" + field + "\"\\s*:\\s*(-?[0-9.]+)").matcher(json);
        if (!matcher.find()) throw new IllegalStateException("JSON 里找不到字段 " + field);
        return Float.parseFloat(matcher.group(1));
    }
}
