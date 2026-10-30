package dev.mumu.betternether.vanilla;

import dev.mumu.betternether.vanilla.BlockShapeTable.Entry;
import dev.mumu.betternether.vanilla.BlockShapeTable.Rule;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 放置后的形态重算：单格、接第二格（邻居也要跟着变）、接第三格、跨区域、表外方块一律不动。
 *
 * <h2>这几条用例为什么非有不可</h2>
 * 用户报的是「放下来永远是最末端那一种」。最直觉的实现 —— 只算被放下的那一格 —— 能让
 * 第 1 条测试全绿，却解决不了真正的病症：接第二段时**上面那一格**不会从末端变成中间段，
 * 于是一串藤永远连不成串。所以 {@link #secondSegmentTurnsTheOneAboveIntoMiddle()} 与
 * {@link #thirdSegmentFillsInTheMiddle()} 才是这个功能的验收标准，而不是第 1 条。
 *
 * <h2>方向：为什么「接在下面」才是藤的正常操作</h2>
 * 吊藤（lumabus / golden_lumabus / whispering_gourd / golden_vine 等，BCLib
 * {@code AbstractVineBlock.updateShape}）的形态是「下方不同类 → BOTTOM，上方不同类 → TOP」，
 * 也就是 TOP 是贴着天花板的那一格、BOTTOM 是最下面的链尾。所以玩家从天花板往下接时，
 * 第二段出现之后**上面**那格变成 TOP、新放的这格是 BOTTOM；一直要等到第三段，中间那格才
 * 出现 MIDDLE。用例里刻意把天花板（{@code minecraft:netherrack}）也放进假世界，
 * 因为上游的 TOP 判定就是拿它当「上方不是同类」的。
 *
 * <h2>为什么每条都要断言写入条数</h2>
 * 「形态对」可能靠的是假世界本来就是对的。写入列表是唯一能证明「这一次真的改写了这些格、
 * 且只改写了这些格」的证据；表外方块那一条更是只能靠写入数为 0 来断言。
 */
class BlockShapeReshaperTest {

    private static final String LUMABUS = "betternether:lumabus_vine";
    private static final String GOLDEN_VINE = "betternether:golden_vine";
    private static final String STONE = "minecraft:stone";

    private static BlockShapeReshaper reshaper(FakeShapeWorld world) {
        return new BlockShapeReshaper(BlockShapeTable.builtin(), world);
    }

    /**
     * 1. 单独放一块吊藤 → BOTTOM（链尾）。
     *
     * <p>此时写入应为 0：CE 配置里这些方块的 {@code default} 本来就是 {@code bottom}，
     * 单放一格恰好就是正确答案。这条用例同时把「默认值恰好对」钉住 —— 一旦哪天 CE 配置把
     * default 改掉，它仍然成立（规则算出来是 bottom，与默认值无关）。
     */
    @Test
    void singleVineIsBottom() {
        FakeShapeWorld world = new FakeShapeWorld()
                .put(0, 10, 0, STONE, "shape", BlockShapeTable.FALSE)
                .placeShape(0, 9, 0, LUMABUS);

        BlockShapeReshaper.Result result = reshaper(world).reshapeAround(0, 9, 0);

        assertEquals(BlockShapeTable.BOTTOM, world.shapeAt(0, 9, 0), "单放一格就是链尾");
        assertEquals(List.of(), world.writes(), "默认值已经是对的，不该产生写入");
        assertEquals(1, result.examined(), "只有那一格命中形状表");
    }

    /**
     * 2. 在它下面再接一段：**邻居也要跟着重算** —— 上面那格从 BOTTOM 变成 TOP。
     *
     * <p>这就是「只算被放下那一格」的实现会红的那条。上面那格的写入必须出现在 writes 里，
     * 且顺序无关（本类先算写谁都不影响结果，见 {@link BlockShapeReshaper} 类注释）。
     */
    @Test
    void secondSegmentAlsoRewritesTheOneAbove() {
        FakeShapeWorld world = new FakeShapeWorld()
                .put(0, 10, 0, STONE, "shape", BlockShapeTable.FALSE)
                .placeShape(0, 9, 0, LUMABUS)
                .placeShape(0, 8, 0, LUMABUS);

        reshaper(world).reshapeAround(0, 8, 0);

        assertEquals(BlockShapeTable.TOP, world.shapeAt(0, 9, 0), "贴着天花板那格是 TOP");
        assertEquals(BlockShapeTable.BOTTOM, world.shapeAt(0, 8, 0), "新放的那格是链尾");
        assertEquals(List.of("0,9,0 shape=top"), world.writes(), "新放的格子本身不需要改写");
    }

    /**
     * 3. 再往下接第三段 → 中间那格变成 MIDDLE，最上面仍是 TOP、最下面仍是 BOTTOM。
     *
     * <p>这一条才是「一串藤连得起来」的最终形态：三段齐全时三个取值各就各位。
     */
    @Test
    void thirdSegmentFillsInTheMiddle() {
        FakeShapeWorld world = new FakeShapeWorld()
                .put(0, 10, 0, STONE, "shape", BlockShapeTable.FALSE)
                .placeShape(0, 9, 0, LUMABUS)
                .placeShape(0, 8, 0, LUMABUS);

        // 第二段落地之后的状态（与上一条一致），再放第三段。
        reshaper(world).reshapeAround(0, 8, 0);
        world.placeShape(0, 7, 0, LUMABUS);
        reshaper(world).reshapeAround(0, 7, 0);

        assertEquals(BlockShapeTable.TOP, world.shapeAt(0, 9, 0), "最上面是 TOP");
        assertEquals(BlockShapeTable.MIDDLE, world.shapeAt(0, 8, 0), "中间是 MIDDLE");
        assertEquals(BlockShapeTable.BOTTOM, world.shapeAt(0, 7, 0), "最下面是 BOTTOM");
        assertTrue(world.writes().contains("0,8,0 shape=middle"), "中间那格必须被改写");
    }

    /**
     * 6. 布尔 tip 标记（{@code bottom}）：链尾是 {@code true}，上面接东西之后变 {@code false}。
     *
     * <p>{@code golden_vine} 等三种简单藤在 CE 里是 string 属性 {@code "true"}/{@code "false"}，
     * 默认 {@code "false"} —— 也就是放下来永远不是链尾。
     */
    @Test
    void bottomFlagMarksOnlyTheTail() {
        FakeShapeWorld world = new FakeShapeWorld()
                .put(0, 10, 0, STONE, "bottom", BlockShapeTable.FALSE)
                .placeBottomFlag(0, 9, 0, GOLDEN_VINE);

        reshaper(world).reshapeAround(0, 9, 0);
        assertEquals(BlockShapeTable.TRUE, world.valueAt(0, 9, 0, "bottom"), "单放一格是链尾");

        world.placeBottomFlag(0, 8, 0, GOLDEN_VINE);
        reshaper(world).reshapeAround(0, 8, 0);
        assertEquals(BlockShapeTable.FALSE, world.valueAt(0, 9, 0, "bottom"), "上面接了东西就不再是链尾");
        assertEquals(BlockShapeTable.TRUE, world.valueAt(0, 8, 0, "bottom"), "新放的是链尾");
    }

    /**
     * 7. 不在表里的方块：**完全不动**，一格都不写。
     *
     * <p>石头堆在石头上面、原版方块、空气全都在这里 —— 这是「本功能不会偷偷改别的玩法」的底线。
     */
    @Test
    void blocksOutsideTheTableAreNeverTouched() {
        FakeShapeWorld world = new FakeShapeWorld()
                .put(0, 9, 0, STONE, "shape", BlockShapeTable.BOTTOM)
                .put(0, 8, 0, "minecraft:netherrack", "shape", BlockShapeTable.BOTTOM);

        BlockShapeReshaper.Result result = reshaper(world).reshapeAround(0, 8, 0);

        assertEquals(List.of(), world.writes(), "表外方块一格都不该被写");
        assertEquals(new BlockShapeReshaper.Result(0, 0, 0, 0), result, "没有命中任何一条");
    }

    /**
     * 8. 跨区域：不归当前区域的那一格交给 {@code handOff}，本线程**一个字节都不读它**。
     *
     * <p>老实说 Folia 的区域按 XZ 列划分，竖直邻格永远同属一个区域，所以真实服务器上这条分支
     * 基本不会走到。留着它的意义是把「不跨线程读写字」写成可执行的契约：将来规则要是变成
     * 看水平邻居，这里不用重新设计。断言 {@code foreignReads() == 0} 才是这条用例的重点 ——
     * 只看「有没有 handOff」会漏掉「先读了再转交」这种实现。
     */
    @Test
    void foreignRegionIsHandedOffWithoutBeingRead() {
        FakeShapeWorld world = new FakeShapeWorld()
                .put(0, 10, 0, STONE, "shape", BlockShapeTable.FALSE)
                .placeShape(0, 9, 0, LUMABUS)
                .placeShape(0, 8, 0, LUMABUS)
                .foreign(0, 9, 0);

        BlockShapeReshaper.Result result = reshaper(world).reshapeAround(0, 8, 0);

        assertEquals(List.of("0,9,0"), world.handedOff(), "上面那格交给它的区域线程");
        assertEquals(0, world.foreignReads(), "不归当前区域的格子一个字节都不读");
        assertEquals(List.of(), world.writes(), "本轮没有写任何东西");
        assertEquals(1, result.handedOff());
    }

    /**
     * 10. 该写却写不进去（CE 拒绝、方块已被换掉、属性值不在取值集里）必须计入 {@code refused}。
     *
     * <p>不静默：调用方拿这个数字记 WARNING。少写一格是「看着像随机 bug」的典型症状，
     * 所以它必须从算法层面就能被数出来。
     */
    @Test
    void refusedWriteIsCounted() {
        FakeShapeWorld world = new FakeShapeWorld()
                .put(0, 10, 0, STONE, "shape", BlockShapeTable.FALSE)
                .placeShape(0, 9, 0, LUMABUS)
                .placeShape(0, 8, 0, LUMABUS)
                .failing(0, 9, 0);

        BlockShapeReshaper.Result result = reshaper(world).reshapeAround(0, 8, 0);

        assertEquals(1, result.refused(), "上面那格该改但没改成");
        assertEquals(0, result.written(), "失败不能算成功");
        assertEquals(BlockShapeTable.BOTTOM, world.shapeAt(0, 9, 0), "世界里的值没变");
    }

    /** 规则判定本身（不经过世界）：三条藤规则、两种列规则与朝向的表驱动断言。 */
    @Test
    void evaluateTableIsExactForEveryRule() {
        BlockShapeTable.Neighbours lone = new BlockShapeTable.Neighbours(null, null, null);
        BlockShapeTable.Neighbours vineBelow = new BlockShapeTable.Neighbours(null, null, LUMABUS);
        BlockShapeTable.Neighbours vineEverywhere = new BlockShapeTable.Neighbours(LUMABUS, LUMABUS, LUMABUS);

        assertEquals(BlockShapeTable.BOTTOM, BlockShapeTable.evaluate(Rule.HANGING_VINE, LUMABUS, lone));
        assertEquals(BlockShapeTable.TOP, BlockShapeTable.evaluate(Rule.HANGING_VINE, LUMABUS, vineBelow));
        assertEquals(BlockShapeTable.MIDDLE, BlockShapeTable.evaluate(Rule.HANGING_VINE, LUMABUS, vineEverywhere));

        assertEquals(BlockShapeTable.TRUE, BlockShapeTable.evaluate(Rule.BOTTOM_FLAG, GOLDEN_VINE, lone));
        assertEquals(BlockShapeTable.FALSE, BlockShapeTable.evaluate(Rule.BOTTOM_FLAG, GOLDEN_VINE,
                new BlockShapeTable.Neighbours(null, null, GOLDEN_VINE)));
    }

    /** 表里剩下的吊藤用同一条规则，且形态属性名一致（防止手抄时把某一行漏成别的规则）。 */
    @Test
    void lumabusVineUsesTheHangingRule() {
        BlockShapeTable table = BlockShapeTable.builtin();
        Entry lumabus = table.entry(LUMABUS);
        assertNotNull(lumabus, "lumabus_vine 必须在表里");
        assertEquals(Rule.HANGING_VINE, lumabus.rule());
        assertEquals("shape", lumabus.property());
        assertFalse(table.ids().isEmpty());
    }
}
