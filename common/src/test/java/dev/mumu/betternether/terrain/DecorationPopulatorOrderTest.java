package dev.mumu.betternether.terrain;

import dev.mumu.betternether.worldgen.DecorationScheduler;
import dev.mumu.betternether.worldgen.UnifiedDecorationPopulator;
import org.bukkit.generator.BlockPopulator;
import org.bukkit.generator.LimitedRegion;
import org.bukkit.generator.WorldInfo;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 注册顺序就是语义：原版 {@code addVanillaDecorations} 是「外层 step 升序，内层先结构后特征」，
 * 而 populator 的执行顺序等于 {@code getDefaultPopulators} 返回的列表顺序。
 *
 * <h2>为什么必须用单测锁住它</h2>
 * 顺序错了的世界**看起来完全正常**：地形在、树在、矿在，只是 step 1 的岩浆坑晚于 step 4 的尖锥，
 * 于是尖锥被挖掉、坑边没有该有的植被；或者原版装饰整道跑在 BN 之前，导致同一批
 * {@code minecraft:} 条目被放两次。这两种都不会报错，只会在世界里留下无法归因的差异。
 *
 * <p>所以这里不验证「放置结果」（那需要服务端），只验证**顺序与分工**：
 * 每个 step 的结构相位排在该 step 的特征相位之前，每个 step 的特征只由一个实例负责，
 * 两个外部 owner（巨型熔岩湖 / 矿）被夹在正确的位置。
 */
class DecorationPopulatorOrderTest {

    /** GenerationStep.Decoration 的 ordinal 上界（0..10），结构相位必须覆盖全程。 */
    private static final int LAST_STEP = 10;

    /**
     * 熔岩湖在本服 step 4 的槽位（上游是数据包结构，按路径排序；这里是 altars/gardens/
     * ghast_hive/jungle_temples 之后 = 4）。刻意写成固定值：本测试验证的是「窗口怎么切」，
     * 真实槽位由 NativeDecorationBridge#structureSlot 在运行期从注册表算出。
     */
    private static final int LAKE_SLOT = 4;

    @Test
    void structuresRunBeforeFeaturesInTheirOwnStep() {
        List<BlockPopulator> populators = NetherTerrainGenerator.decorationPopulators(
                emptyScheduler(), named("mega_lava_lake"), named("ore"), LAKE_SLOT);

        // 结构相位覆盖 0..10，且同一 step 的结构相位一定排在该 step 的特征相位之前。
        Map<Integer, Integer> structureAt = new TreeMap<>();
        Map<Integer, Integer> featureAt = new TreeMap<>();
        for (int position = 0; position < populators.size(); position++) {
            if (!(populators.get(position) instanceof UnifiedDecorationPopulator pass)) {
                continue;
            }
            for (int step : pass.steps()) {
                if (pass.phase().structures()) {
                    structureAt.put(step, position);
                }
                if (pass.phase().features()) {
                    featureAt.put(step, position);
                }
            }
        }
        for (int step = 0; step <= LAST_STEP; step++) {
            assertTrue(structureAt.containsKey(step), "step " + step + " 没有结构相位：该 step 的结构不会落方块");
            assertTrue(featureAt.containsKey(step), "step " + step + " 没有特征相位");
            assertTrue(structureAt.get(step) <= featureAt.get(step),
                    "step " + step + " 的结构相位排在了特征相位之后（列表位置 "
                            + structureAt.get(step) + " > " + featureAt.get(step) + "）");
        }
    }

    @Test
    void eachStepHasExactlyOneFeatureOwner() {
        BlockPopulator ore = named("ore");
        List<BlockPopulator> populators = NetherTerrainGenerator.decorationPopulators(
                emptyScheduler(), named("mega_lava_lake"), ore, LAKE_SLOT);

        // step → 负责它的特征相位的实例（列表位置）。重复 = 双放。
        Map<Integer, List<Integer>> owners = new LinkedHashMap<>();
        for (int position = 0; position < populators.size(); position++) {
            if (populators.get(position) instanceof UnifiedDecorationPopulator pass
                    && pass.phase().features()) {
                for (int step : pass.steps()) {
                    owners.computeIfAbsent(step, key -> new ArrayList<>()).add(position);
                }
            }
        }
        // step 7 的特征有两个 owner：调度器只放其中的**原版**条目（NATIVE_FEATURES），
        // BN 矿由 OrePopulator 自己放。两者覆盖的条目集合不相交
        //（见 NativeDecorationContractTest#phasesPartitionStructuresAndFeatureKinds），
        // 所以这里把矿也算作 step 7 的第二个 owner，然后要求「恰好两个」。
        int oreAt = indexOf(populators, ore);
        owners.computeIfAbsent(7, key -> new ArrayList<>()).add(oreAt);
        for (int step = 0; step <= LAST_STEP; step++) {
            List<Integer> forStep = owners.get(step);
            assertTrue(forStep != null && !forStep.isEmpty(), "step " + step + " 没有特征 owner");
            assertEquals(step == 7 ? 2 : 1, forStep.size(),
                    "step " + step + " 的特征 owner 数量不对：" + forStep);
        }
    }

    /**
     * 巨型熔岩湖必须排在「step 4 的结构之后、step 4 的特征之前」。
     *
     * <p>上游它的身份是结构（{@code NetherStructures.MEGA_LAVA_LAKE}
     * {@code .step(SURFACE_STRUCTURES)}），由 {@code MegaLavaLakePopulator} 手动放置。
     * 放到特征之后，它会把刚长出来的尖锥整片挖掉。
     *
     * <p>它**不在**原版结构注册表里（structure pack 只带那 10 个 JSON，lake 的 structure type
     * 是 wover 的、26.3 注册不了），所以 {@code StructureManager} 不会替它播种；它的
     * {@code decorationSeed} 与位置下标由
     * {@code NativeDecorationSession#decorationSeed()} /
     * {@code NativeDecorationSession#nextStructureIndex(int)} 提供。上游 wover 把 code 条目追加在
     * 数据包条目之后，所以熔岩湖本就是 step 4 的最后一个条目 —— 「最后跑」不只是顺序合理，
     * 与上游一致（可复现的是位置语义，整数随数据包集合变，见接口注释）。
     */
    @Test
    void megaLavaLakeSitsBetweenStepFourPasses() {
        BlockPopulator lake = named("mega_lava_lake");
        List<BlockPopulator> populators = NetherTerrainGenerator.decorationPopulators(
                emptyScheduler(), lake, named("ore"), LAKE_SLOT);

        int lakeAt = indexOf(populators, lake);
        int step4StructuresBefore = -1;
        int step4StructuresAfter = -1;
        int step4Features = -1;
        for (int position = 0; position < populators.size(); position++) {
            if (!(populators.get(position) instanceof UnifiedDecorationPopulator pass)
                    || pass.phase() != DecorationScheduler.Phase.STRUCTURES
                    || !contains(pass.steps(), 4)) {
                continue;
            }
            if (position < lakeAt) {
                step4StructuresBefore = position;
            } else {
                step4StructuresAfter = position;
            }
        }
        for (int position = 0; position < populators.size(); position++) {
            if (populators.get(position) instanceof UnifiedDecorationPopulator pass
                    && pass.phase() == DecorationScheduler.Phase.FEATURES
                    && contains(pass.steps(), 4)) {
                step4Features = position;
            }
        }
        // 上游 mega_lava_lake 是**数据包结构**，占 step 4 的排序槽位，所以结构相位必须在它那里断开：
        // 前一段跑它之前的条目，后一段跑它之后的条目（j 整体 +1）。落在两段之间才是「同一个条目」。
        assertTrue(step4StructuresBefore >= 0, "熔岩湖之前必须有一段 step 4 结构相位");
        assertTrue(step4StructuresAfter >= 0, "熔岩湖之后必须有一段 step 4 结构相位");
        assertTrue(step4StructuresBefore < lakeAt, "巨型熔岩湖必须排在 step 4 前半段结构之后");
        assertTrue(lakeAt < step4StructuresAfter, "巨型熔岩湖必须排在 step 4 后半段结构之前");
        assertTrue(step4StructuresAfter < step4Features, "step 4 的特征必须在两段结构之后");
    }

    /**
     * 矿必须排在「step 7 的结构」与「step 7 的原版条目」之后、step 8 之前。
     *
     * <p>矿是 step 7 的一部分：排在结构之前，矿脉会被结构方块覆盖；排到 step 8 之后，
     * 它就晚于植被 —— 两者都与上游「一个 step 全员跑完再进下一个」不符。
     */
    @Test
    void oreSitsInsideStepSeven() {
        BlockPopulator ore = named("ore");
        List<BlockPopulator> populators = NetherTerrainGenerator.decorationPopulators(
                emptyScheduler(), named("mega_lava_lake"), ore, LAKE_SLOT);

        int oreAt = indexOf(populators, ore);
        for (int position = 0; position < populators.size(); position++) {
            if (populators.get(position) instanceof UnifiedDecorationPopulator pass) {
                for (int step : pass.steps()) {
                    if (step < 7) {
                        assertTrue(position < oreAt,
                                "step " + step + " 的装饰排在了矿之后：执行顺序就不是 step 升序了");
                    }
                    if (step > 7) {
                        assertTrue(oreAt < position,
                                "step " + step + " 的装饰排在了矿之前：矿会晚于更后面的 step 执行");
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- 工具

    private static DecorationScheduler emptyScheduler() {
        // 计划为空也不影响顺序：本测试只看注册结构，不看数据。
        return new DecorationScheduler(null, Map.of());
    }

    /** 只用来占位的 populator：不执行任何逻辑，只让「它在第几位」可断言。 */
    private static BlockPopulator named(String label) {
        return new BlockPopulator() {
            @Override
            public void populate(@NotNull WorldInfo worldInfo, @NotNull java.util.Random random,
                                 int chunkX, int chunkZ, @NotNull LimitedRegion region) {
                throw new AssertionError("占位 populator 不该被执行：" + label);
            }

            @Override
            public String toString() {
                return label;
            }
        };
    }

    private static int indexOf(List<BlockPopulator> populators, BlockPopulator expected) {
        for (int position = 0; position < populators.size(); position++) {
            if (populators.get(position) == expected) {
                return position;
            }
        }
        throw new AssertionError("列表里没有这个 populator：" + expected);
    }

    private static boolean contains(int[] values, int wanted) {
        for (int value : values) {
            if (value == wanted) {
                return true;
            }
        }
        return false;
    }

    /** 明确的「矿是 OrePopulator」检查：避免有人把它换成别的类还以为顺序没变。 */
    @Test
    void oreSlotIsTheOrePopulatorItself() {
        BlockPopulator ore = named("ore");
        List<BlockPopulator> populators = NetherTerrainGenerator.decorationPopulators(
                emptyScheduler(), named("mega_lava_lake"), ore, LAKE_SLOT);
        // step 4 的结构相位被熔岩湖切成两段，所以矿的位置随之后移一位（原为第 8 位）。
        assertSame(ore, populators.get(8), "第 9 位必须是传进来的矿 populator");
    }
}
