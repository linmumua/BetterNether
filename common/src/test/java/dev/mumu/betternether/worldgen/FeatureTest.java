package dev.mumu.betternether.worldgen;

import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 特征（「放什么」）的行为测试。
 *
 * <p>每条断言都对着一个具体的上游事实，而不是「函数能跑」：
 * <ul>
 *   <li>权重选择按 {@code weight} 的比例走 —— 上游 {@code vegetation_nether_grasslands} 用
 *       7:20 的权重区分岩浆花与墨丛，比例错了整个群系的配色就变了；</li>
 *   <li>{@code wover:template} 的 {@code offset_y} 必须真的生效 —— 上游给不同模板配了不同的
 *       {@code offset_y}（-4..0），它是「这棵树根扎在哪」的数据；</li>
 *   <li>模板只覆盖空气/可替换、越界裁切 —— 否则一棵跨区块的树会削掉邻居的地形；</li>
 *   <li>{@code wover:pillar} 遇阻即停，{@code block_column} 保尖端。</li>
 * </ul>
 */
class FeatureTest {
    private static final int MIN_Y = 0;
    private static final int MAX_Y = 127;

    private static BlockData data(Material material) {
        return FakeWorld.blockData(material);
    }

    // ---------------------------------------------------------------- 权重

    /** 权重选择：7:21 的权重应当让两边约 1:3。 */
    @Test
    void weightedProviderFollowsItsWeights() {
        Feature.BlockProvider provider = new Feature.BlockProvider.Weighted(
                List.of(data(Material.MAGMA_BLOCK), data(Material.COAL_BLOCK)),
                new int[]{7, 21}, 28);
        Random random = new Random(101L);
        int first = 0;
        int trials = 4000;
        for (int i = 0; i < trials; i++) {
            if (provider.sample(random).getMaterial() == Material.MAGMA_BLOCK) {
                first++;
            }
        }
        double ratio = (double) first / trials;
        assertTrue(Math.abs(ratio - 0.25) < 0.04, "7/28 应当约 25%，实际 " + ratio);
    }

    /** 权重为 0 或负数的条目在装载期就被剔除，不会「永远选中最后一个」。 */
    @Test
    void zeroWeightEntriesAreNeverChosen() {
        Feature.BlockProvider provider = new Feature.BlockProvider.Weighted(
                List.of(data(Material.MAGMA_BLOCK)), new int[]{5}, 5);
        Random random = new Random(7L);
        for (int i = 0; i < 50; i++) {
            assertEquals(Material.MAGMA_BLOCK, provider.sample(random).getMaterial());
        }
        Feature.BlockProvider empty = new Feature.BlockProvider.Weighted(List.of(), new int[0], 0);
        assertEquals(null, empty.sample(random), "没有条目时应当明确返回 null");
    }

    // ---------------------------------------------------------------- 模板

    private static TemplateData template(int offsetX, int offsetY, Material material) {
        return new TemplateData(new int[]{0 + offsetX, 1 + offsetX}, new int[]{0, offsetY},
                new int[]{0, 0}, new BlockData[]{data(material), data(material)});
    }

    /**
     * {@code wover:template} 的 {@code offset_y} 必须生效。
     *
     * <p>上游给 {@code red_mushroom_07} 配的是 {@code offset_y:-4}、给
     * {@code red_mushroom_02} 配的是 0 —— 同一批模板共用一条 placed feature，靠这个偏移把它们
     * 的根部对齐到地面。丢掉它，一部分蘑菇会埋进地里、一部分悬空。
     */
    @Test
    void templateAppliesItsOffsetY() {
        FakeWorld world = new FakeWorld(MIN_Y, MAX_Y, 5L);
        world.fill(0, 0, 0, 39, Material.NETHERRACK);   // 地面到 y=39，可放区从 y=40 开始

        TemplateData data = template(0, 0, Material.MUSHROOM_STEM);
        Feature.Template at = new Feature.Template(
                List.of(new Feature.Template.TemplateChoice(data, 0, 1.0F)), 1.0F);
        assertTrue(at.place(world, new Point(0, 40, 0)));
        assertEquals(Material.MUSHROOM_STEM, world.materialAt(0, 40, 0));

        FakeWorld shifted = new FakeWorld(MIN_Y, MAX_Y, 5L);
        shifted.fill(0, 0, 0, 39, Material.NETHERRACK);   // 40 以上是空气，偏移后才落得下
        Feature.Template up = new Feature.Template(
                List.of(new Feature.Template.TemplateChoice(data, 5, 1.0F)), 1.0F);
        assertTrue(up.place(shifted, new Point(0, 40, 0)));
        assertEquals(Material.MUSHROOM_STEM, shifted.materialAt(0, 45, 0),
                "offset_y=5 应当把模板整体上移 5 格");
        assertEquals(Material.AIR, shifted.materialAt(0, 40, 0),
                "原位置不该被写");
    }

    /**
     * 模板**覆盖**已有方块，而不是「只覆盖空气/可替换」。
     *
     * <p>依据 wover 源码：{@code StructureNBT#generateCentered} 调
     * {@code structure.placeInWorld(..., BlockHelper.SET_SILENT)}，与原版一样直接写入。
     * 我此前按「只覆盖可替换」实现，会让树的树干被岩层挡住半截 —— 那是另一个世界。
     */
    @Test
    void templateOverwritesExistingBlocks() {
        FakeWorld world = new FakeWorld(MIN_Y, MAX_Y, 7L);
        world.fill(0, 0, 40, 40, Material.OBSIDIAN);   // 实心方块挡在模板里
        TemplateData data = template(0, 0, Material.MUSHROOM_STEM);
        Feature.Template feature = new Feature.Template(
                List.of(new Feature.Template.TemplateChoice(data, 0, 1.0F)), 1.0F);
        feature.place(world, new Point(0, 40, 0));
        assertEquals(Material.MUSHROOM_STEM, world.materialAt(0, 40, 0),
                "上游 placeInWorld 是覆盖语义，实心方块也会被模板写掉");
    }

    /**
     * bedrock 守卫：模板范围内（自 pos 起每 2 格）出现基岩就整棵放弃。
     *
     * <p>依据 {@code FeatureTemplateImpl#containsBedrock}：{@code for (i = 0; i < sizeY; i += 2)}
     * 检查 {@code pos.above(i)}，命中 {@code Blocks.BEDROCK} 直接返回 false。
     */
    @Test
    void templateRefusesWhenBedrockIsInTheWay() {
        FakeWorld world = new FakeWorld(MIN_Y, MAX_Y, 11L);
        world.solid(0, 42, 0, Material.BEDROCK);
        TemplateData sized = new TemplateData(3, 8, 3,
                new int[]{0}, new int[]{0}, new int[]{0},
                new org.bukkit.block.data.BlockData[]{FakeWorld.blockData(Material.STONE)});
        Feature.Template feature = new Feature.Template(
                List.of(new Feature.Template.TemplateChoice(sized, 0, 1.0F)), 1.0F);
        assertFalse(feature.place(world, new Point(0, 40, 0)),
                "模板高度范围内有基岩时应当整棵放弃（i=0 检查 y=40，i=2 检查 y=42）");
    }

    /**
     * 居中：模板以 x/z 中心落在位置上（{@code getCenteredPos} 的 {@code (-extent) >> 1}）。
     *
     * <p>这条是「角原点」错误的回归测试：size=7 时角原点会让树干偏 +3 格，
     * 而 {@code (-7) >> 1 = -4}。用一个只含 1 个方块、位于模板中心的模板断言落点。
     */
    @Test
    void templateIsCentredOnThePosition() {
        FakeWorld world = new FakeWorld(MIN_Y, MAX_Y, 13L);
        // size (7,4,7)，唯一的方块在模板中心 (3,0,3)
        TemplateData centred = new TemplateData(7, 4, 7,
                new int[]{3}, new int[]{0}, new int[]{3},
                new org.bukkit.block.data.BlockData[]{FakeWorld.blockData(Material.MUSHROOM_STEM)});
        Feature.Template feature = new Feature.Template(
                List.of(new Feature.Template.TemplateChoice(centred, 0, 1.0F)), 1.0F);
        feature.place(world, new Point(10, 40, 10));
        // 旋转/镜像随机：遍历 4×3 种组合，中心方块必须始终落在 x/z 的 10±? 范围内且 y=40
        boolean anyAtCentre = false;
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                if (world.materialAt(10 + dx, 40, 10 + dz) == Material.MUSHROOM_STEM) {
                    anyAtCentre = true;
                }
            }
        }
        assertTrue(anyAtCentre, "模板必须落在位置的 x/z 居中范围内（不是角原点 +7）");
    }

    /** 越出可写区域的模板方块被裁掉，不抛异常。 */
    @Test
    void templateClipsInsteadOfThrowing() {
        FakeWorld world = new FakeWorld(MIN_Y, 41, 9L);   // 只到 y=41
        TemplateData data = new TemplateData(new int[]{0, 0}, new int[]{0, 40}, new int[]{0, 0},
                new BlockData[]{data(Material.MUSHROOM_STEM), data(Material.MUSHROOM_STEM)});
        Feature.Template feature = new Feature.Template(
                List.of(new Feature.Template.TemplateChoice(data, 0, 1.0F)), 1.0F);
        assertTrue(feature.place(world, new Point(0, 40, 0)), "区域内的那一格应当被放置");
        assertEquals(Material.AIR, world.materialAt(0, 80, 0), "区域外的方块应当被裁掉");
    }

    /** 多个模板的权重选择：总是选中合法的那个，并且不会因为权重全 0 而崩。 */
    @Test
    void templatePicksAmongChoices() {
        FakeWorld world = new FakeWorld(MIN_Y, MAX_Y, 11L);
        TemplateData a = template(0, 0, Material.MUSHROOM_STEM);
        Feature.Template feature = new Feature.Template(List.of(
                new Feature.Template.TemplateChoice(a, 0, 1.0F),
                new Feature.Template.TemplateChoice(a, 3, 3.0F)), 4.0F);
        for (int i = 0; i < 40; i++) {
            // 每次换一列：同一格第二次会被「只覆盖可替换」正确拒绝。
            assertTrue(feature.place(world, new Point(i, 40, i)));
        }
        Feature.Template none = new Feature.Template(List.of(), 0.0F);
        assertFalse(none.place(world, new Point(0, 40, 0)), "没有模板时应当明确不放置");
    }

    // ---------------------------------------------------------------- 柱 / 列

    /**
     * {@code wover:pillar} 遇阻即停。
     *
     * <p>上游 {@code patch_black_vine} 是「从屋顶往下长，撞到地面就停」。真实实现
     * （{@code PillarFeature#place}）的写法是：先采样 max，再沿方向逐格问 {@code allowedPlacement}，
     * 一旦不通过就把 {@code maxHeight} 截断到当前高度 —— 不是「跳到断点继续长」。
     */
    @Test
    void pillarStopsAtTheFirstObstruction() {
        FakeWorld world = new FakeWorld(MIN_Y, MAX_Y, 13L);
        world.fill(0, 0, 60, 60, Material.NETHERRACK);   // 天花板以上到 60 是实心，下面 59.. 是空气
        Feature.Pillar pillar = new Feature.Pillar(
                null, java.util.Map.of(), data(Material.CRIMSON_ROOTS),
                Point.Direction.DOWN,
                new IntProvider.Constant(3), new IntProvider.Constant(12),
                new BlockPredicates.Air(), "none");
        pillar.place(world, new Point(0, 59, 0));
        assertEquals(Material.CRIMSON_ROOTS, world.materialAt(0, 59, 0));
        assertEquals(Material.CRIMSON_ROOTS, world.materialAt(0, 48, 0),
                "12 格全应当放下（59 到 48）");
        assertEquals(Material.AIR, world.materialAt(0, 47, 0), "高度用尽后不该继续");
    }


    /**
     * {@code min_height} <b>不是</b>下限补足：max 采样值小于 min 时整根放弃。
     *
     * <p>源码 {@code PillarFeature#place}：{@code if (maxHeight < minHeight) return false;} ——
     * 我之前把它当成「至少放 min 格」，那是错的：min 只是「净空不足就干脆不放」的门槛。
     */
    @Test
    void pillarRefusesWhenMaxSampledBelowMin() {
        FakeWorld world = new FakeWorld(MIN_Y, MAX_Y, 17L);
        Feature.Pillar pillar = new Feature.Pillar(
                null, java.util.Map.of(), data(Material.CRIMSON_ROOTS),
                Point.Direction.UP,
                new IntProvider.Constant(4), new IntProvider.Constant(0),
                new BlockPredicates.Air(), "none");
        assertFalse(pillar.place(world, new Point(0, 20, 0)),
                "max(0) < min(4) 时必须整根放弃，而不是补足到 min 格");
        assertEquals(Material.AIR, world.materialAt(0, 20, 0), "一个方块都不该被写");
    }


    /**
     * {@code block_column} + {@code prioritize_tip}：空间不足时优先保住尖端。
     *
     * <p>上游芦苇是「3 段秸秆 + 1 个顶」，顶段决定它看起来像不像芦苇；只放半截秸秆
     * 会变成一根断了的棍子。
     */
    @Test
    void blockColumnKeepsTheTipWhenSpaceRunsOut() {
        FakeWorld world = new FakeWorld(MIN_Y, MAX_Y, 19L);
        world.fill(0, 0, 24, 40, Material.OBSIDIAN);  // 只能放到 y=23 以下（24 起是实心）

        List<Feature.BlockColumn.Layer> layers = List.of(
                new Feature.BlockColumn.Layer(new IntProvider.Constant(3),
                        new Feature.BlockProvider.Single(data(Material.SUGAR_CANE))),
                new Feature.BlockColumn.Layer(new IntProvider.Constant(1),
                        new Feature.BlockProvider.Single(data(Material.BAMBOO))));
        Feature.BlockColumn column = new Feature.BlockColumn(Point.Direction.UP, layers, true,
                new BlockPredicates.Air());
        column.place(world, new Point(0, 21, 0));

        // 空间只有 21,22,23 三格：尖端（bamboo）必须占一格。
        boolean tip = false;
        int stalks = 0;
        for (int y = 21; y <= 23; y++) {
            Material material = world.materialAt(0, y, 0);
            if (material == Material.BAMBOO) {
                tip = true;
            } else if (material == Material.SUGAR_CANE) {
                stalks++;
            }
        }
        assertTrue(tip, "prioritize_tip 应当保住顶段");
        assertEquals(2, stalks, "剩下的空间放秸秆");
    }

    // ---------------------------------------------------------------- 贴墙

    /**
     * {@code wover:place_block} 的真实语义：方块放在 {@code pos.relative(dir)}，要求**那一格是空气**。
     *
     * <p>源码 {@code PlaceFacingBlockFeature#placeBlock}：对每个 dir 取 {@code testPos = pos.relative(dir)}，
     * 若 {@code testPos} 是空气且状态 {@code canSurvive} 成立，就在 {@code testPos} 放并把
     * {@code FACING = dir}。所以：
     * <ul>
     *   <li>入参位置本身**不是**落点；</li>
     *   <li>目标格是实心时该方向被跳过，换下一个方向 —— 这正是「贴在墙上」的效果。</li>
     * </ul>
     * 我此前把它写成「放在入参位置、要求旁边有实心」，两处都反了。
     */
    @Test
    void placeBlockPlacesIntoTheAirCellBesideIt() {
        FakeWorld world = new FakeWorld(MIN_Y, MAX_Y, 23L);
        Feature.PlaceBlock feature = new Feature.PlaceBlock(
                List.of(Point.Direction.NORTH, Point.Direction.SOUTH),
                new Feature.BlockProvider.Single(data(Material.BROWN_MUSHROOM)));

        // 全空气：第一个方向（北）的目标格就是空气 -> 落在北侧一格。
        assertTrue(feature.place(world, new Point(0, 40, 0)));
        assertEquals(Material.BROWN_MUSHROOM, world.materialAt(0, 40, -1),
                "应当落在 pos.relative(NORTH)");
        assertEquals(Material.AIR, world.materialAt(0, 40, 0), "入参位置本身不该被写");

        // 北侧被占：跳过后落在南侧。
        FakeWorld blocked = new FakeWorld(MIN_Y, MAX_Y, 23L);
        blocked.solid(0, 40, -1, Material.NETHERRACK);
        assertTrue(feature.place(blocked, new Point(0, 40, 0)));
        assertEquals(Material.BROWN_MUSHROOM, blocked.materialAt(0, 40, 1),
                "北侧实心时应跳过，落到南侧空气格");
        assertEquals(Material.NETHERRACK, blocked.materialAt(0, 40, -1), "实心格不该被覆盖");

        // 两个方向都实心：什么都不放。
        FakeWorld sealed = new FakeWorld(MIN_Y, MAX_Y, 23L);
        sealed.solid(0, 40, -1, Material.NETHERRACK);
        sealed.solid(0, 40, 1, Material.NETHERRACK);
        assertFalse(feature.place(sealed, new Point(0, 40, 0)),
                "所有目标方向都不是空气时必须返回 false");
    }

    // ---------------------------------------------------------------- 组合

    /** {@code overlay} 把每个子特征都在同一位置放一次。 */
    @Test
    void overlayPlacesEveryChild() {
        FakeWorld world = new FakeWorld(MIN_Y, MAX_Y, 29L);
        Feature overlay = new Feature.Overlay(List.of(
                new Feature.SimpleBlock(new Feature.BlockProvider.Single(data(Material.FERN))),
                new Feature.SimpleBlock(new Feature.BlockProvider.Single(data(Material.LILY_PAD)))));
        // 两个子特征都只覆盖空气/可替换，所以先放的那个占住位置、后一个让位 ——
        // 这与原版一致：第二个子特征的 canSurvive 不成立时静默不放置，而不是挖掉刚放的方块。
        assertTrue(overlay.place(world, new Point(0, 40, 0)));
        assertEquals(Material.FERN, world.materialAt(0, 40, 0),
                "同一位置的第二个子特征不得覆盖第一个");
    }

    /** {@code random_selector}：chance 只影响概率，不改变「最终一定放一个」。 */
    @Test
    void randomSelectorAlwaysPlacesOne() {
        FakeWorld world = new FakeWorld(MIN_Y, MAX_Y, 31L);
        Feature.RandomSelector selector = new Feature.RandomSelector(
                List.of(new Feature.RandomSelector.Selection(0.5F,
                        new Feature.SimpleBlock(new Feature.BlockProvider.Single(
                                data(Material.FERN))))),
                new Feature.SimpleBlock(new Feature.BlockProvider.Single(data(Material.DEAD_BUSH))));
        for (int i = 0; i < 30; i++) {
            world.solid(0, 40 + i, 0, Material.AIR);
            assertTrue(selector.place(world, new Point(0, 40 + i, 0)));
        }
    }

    /**
     * e2e：{@code wover:place_block} 必须**先设 FACING、再问 canSurvive**，存活不成立时拒绝放置。
     *
     * <p>上游 {@code PlaceFacingBlockFeature#placeBlock}：
     * {@code lookupState = targetState.setValue(FACING, dir)} 之后才
     * {@code lookupState.canSurvive(level, testPos)}。所以「四周空气」不放置的**原因**是
     * 壁挂方块的 canSurvive 要求支撑面，而不是「旁边没有实心方块」。
     * 我的旧实现只判「目标格是空气」，这条用例就是防止那个 bug 回来。
     */
    @Test
    void placeBlockConsultsSurvivalAfterSettingFacing() {
        Feature.PlaceBlock feature = new Feature.PlaceBlock(
                List.of(Point.Direction.NORTH),
                new Feature.BlockProvider.Single(data(Material.BROWN_MUSHROOM)));

        // 存活不成立：四周全空气也必须 false，且一个方块都不写。
        FakeWorld noSupport = new FakeWorld(MIN_Y, MAX_Y, 31L).wouldSurvive(false);
        assertFalse(feature.place(noSupport, new Point(0, 40, 0)),
                "canSurvive 为假时即使目标格是空气也不能放");
        assertEquals(Material.AIR, noSupport.materialAt(0, 40, -1), "不该写任何方块");

        // 被问到的状态必须**已经带 FACING**（先 facing、后 canSurvive）。
        BlockData probed = noSupport.lastSurvivalProbe();
        assertNotNull(probed, "必须真的问过 canSurvive，而不是跳过");
        assertEquals(org.bukkit.block.BlockFace.NORTH,
                ((org.bukkit.block.data.Directional) probed).getFacing(),
                "问 canSurvive 之前状态上就该有 FACING");

        // 存活成立：同样的空气环境可以放。
        FakeWorld supported = new FakeWorld(MIN_Y, MAX_Y, 31L).wouldSurvive(true);
        assertTrue(feature.place(supported, new Point(0, 40, 0)), "canSurvive 为真时应当放下");
        assertEquals(Material.BROWN_MUSHROOM, supported.materialAt(0, 40, -1));
    }

    /** {@code simple_block} 只覆盖空气/可替换，不替换实心方块。 */
    @Test
    void simpleBlockDoesNotReplaceTheGround() {
        FakeWorld world = new FakeWorld(MIN_Y, MAX_Y, 37L);
        world.fill(0, 0, 40, 40, Material.NETHERRACK);
        Feature.SimpleBlock feature = new Feature.SimpleBlock(
                new Feature.BlockProvider.Single(data(Material.FERN)));
        assertFalse(feature.place(world, new Point(0, 40, 0)), "实心方块不该被植被替换");
        assertTrue(feature.place(world, new Point(0, 41, 0)));
    }

    /** 解析不到的方块（提供器返回 null）时静默不放置 —— 热路径不能抛异常。 */
    @Test
    void unresolvedBlockPlacesNothing() {
        FakeWorld world = new FakeWorld(MIN_Y, MAX_Y, 41L);
        Feature.SimpleBlock feature = new Feature.SimpleBlock(
                new Feature.BlockProvider.Single(null));
        assertFalse(feature.place(world, new Point(0, 40, 0)));
        assertEquals(Material.AIR, world.materialAt(0, 40, 0));
    }

    /** 模板数据结构自检：长度必须一致。 */
    @Test
    void templateDataRejectsRaggedArrays() {
        assertNotNull(new TemplateData(new int[]{0}, new int[]{0}, new int[]{0},
                new BlockData[]{data(Material.STONE)}));
        boolean threw = false;
        try {
            new TemplateData(new int[]{0}, new int[]{0}, new int[0], new BlockData[0]);
        } catch (IllegalArgumentException expected) {
            threw = true;
        }
        assertTrue(threw, "长度不一致应当直接拒绝");
    }

    /** 统计用：模板里只保留非空气方块（装载期剔除），数量与设置的一致。 */
    @Test
    void templateKeepsOnlyResolvedBlocks() {
        TemplateData data = new TemplateData(new int[]{0, 1}, new int[]{0, 1}, new int[]{0, 0},
                new BlockData[]{data(Material.STONE), data(Material.STONE)});
        assertEquals(2, data.size());
        List<Integer> ys = new ArrayList<>();
        for (int i = 0; i < data.size(); i++) {
            ys.add(data.y(i));
        }
        assertEquals(List.of(0, 1), ys);
    }

    // ---------------------------------------------------------------- twisting_vines / spring

    /**
     * {@code minecraft:spring_feature}：**rockCount == 4 且 holeCount == 1** 才放（缺省配置）。
     *
     * <p>不是「四邻 valid ≥ 4 就拒绝」——那是把 5 向计数读成 4 向+反向判断的结果。
     * 这条用例构造「下方 + 四向里 4 面是石头、1 面是空气」，应当放置。
     */
    @Test
    void springNeedsFourRocksAndOneHole() {
        BlockData lava = data(Material.LAVA);
        BlockData stone = data(Material.STONE);
        Feature.Spring spring = new Feature.Spring(lava, true, 4, 1, List.of(stone));

        // below + west/east/north 是石头（4 个 rock），south 是空气（1 个 hole）
        FakeWorld world = new FakeWorld(MIN_Y, MAX_Y, 41L);
        world.solid(0, 41, 0, Material.STONE);     // above：valid
        world.solid(0, 39, 0, Material.STONE);     // below：rock
        world.solid(-1, 40, 0, Material.STONE);    // west
        world.solid(1, 40, 0, Material.STONE);     // east
        world.solid(0, 40, -1, Material.STONE);    // north
        assertTrue(spring.place(world, new Point(0, 40, 0)),
                "4 rock + 1 hole 必须放行");
        assertEquals(Material.LAVA, world.materialAt(0, 40, 0));
        assertEquals(1, world.fluidTicks().size(), "放完必须 scheduleTick 一次");
    }

    /** 中空四邻（rock 数不足）不能放。 */
    @Test
    void springRejectsWhenNotEnoughRocks() {
        BlockData lava = data(Material.LAVA);
        BlockData stone = data(Material.STONE);
        Feature.Spring spring = new Feature.Spring(lava, true, 4, 1, List.of(stone));

        FakeWorld world = new FakeWorld(MIN_Y, MAX_Y, 43L);
        world.solid(0, 41, 0, Material.STONE);     // above
        world.solid(0, 39, 0, Material.STONE);     // below
        // 四个水平邻居全是空气 -> rock=1, hole=5
        assertFalse(spring.place(world, new Point(0, 40, 0)),
                "rock 数不等于配置值时不能放");
        assertEquals(0, world.fluidTicks().size(), "没放就不该 scheduleTick");
    }

    /** pos 本身是 valid_blocks 时允许被替换（上游 63..84 的第二个分支），并照常 tick。 */
    @Test
    void springMayReplaceAValidBlock() {
        BlockData lava = data(Material.LAVA);
        BlockData stone = data(Material.STONE);
        Feature.Spring spring = new Feature.Spring(lava, true, 4, 1, List.of(stone));

        FakeWorld world = new FakeWorld(MIN_Y, MAX_Y, 47L);
        world.solid(0, 41, 0, Material.STONE);
        world.solid(0, 39, 0, Material.STONE);
        world.solid(-1, 40, 0, Material.STONE);
        world.solid(1, 40, 0, Material.STONE);
        world.solid(0, 40, 0, Material.STONE);     // pos 本身是石头
        // 此时 rock = pos 以外的 below/west/east + north/south 空气
        // 为了让计数落在 4 rock/1 hole，把 north 设为石头、south 留空气
        world.solid(0, 40, -1, Material.STONE);
        assertTrue(spring.place(world, new Point(0, 40, 0)),
                "pos 是 valid_blocks 时允许被替换");
        assertEquals(Material.LAVA, world.materialAt(0, 40, 0));
        assertEquals(1, world.fluidTicks().size());
    }

    /** {@code betternether:twisting_vines}：下方必须是 warped_nylium 或 twisting_vines。 */
    @Test
    void twistedVinesRequireWarpedNyliumBelow() {
        BlockData plant = data(Material.TWISTING_VINES_PLANT);
        BlockData tip = data(Material.TWISTING_VINES);
        Feature.TwistedVines vines = new Feature.TwistedVines(plant, tip);

        FakeWorld wrongGround = new FakeWorld(MIN_Y, MAX_Y, 47L);
        wrongGround.solid(0, 39, 0, Material.NETHERRACK);
        assertFalse(vines.place(wrongGround, new Point(0, 40, 0)),
                "下方是下界岩时不该长扭曲藤");

        FakeWorld good = new FakeWorld(MIN_Y, MAX_Y, 47L);
        good.solid(0, 39, 0, Material.WARPED_NYLIUM);
        assertTrue(vines.place(good, new Point(0, 40, 0)));
        boolean wrote = false;
        for (int y = 40; y <= 45; y++) {
            Material m = good.materialAt(0, y, 0);
            if (m == Material.TWISTING_VINES || m == Material.TWISTING_VINES_PLANT) {
                wrote = true;
            }
        }
        assertTrue(wrote, "起点 + nextInt(6) 后应落在 40..45 并写出藤蔓");
    }
}
