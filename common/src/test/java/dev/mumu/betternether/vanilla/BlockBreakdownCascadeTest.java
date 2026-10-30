package dev.mumu.betternether.vanilla;

import dev.mumu.betternether.vanilla.BlockBreakdownTable.Side;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 级联破坏：两阶段（只读收集 → 由深到浅掉落）加三条护栏与方向过滤。
 *
 * <h2>为什么用假世界而不是真服务器</h2>
 * 这些结论全部只依赖 {@link BlockBreakdownCascade.World} 的五个方法（见
 * {@link FakeBreakdownWorld}）。起一个带 CraftEngine 的 Folia 服务器去验证「掉落了几次」既跑不动，
 * 也说不清失败的是算法还是环境。
 *
 * <h2>「掉落而不是 setAir」在这层是怎么被钉住的</h2>
 * 接缝上**只有一条拆方块的出口**：{@link BlockBreakdownCascade.World#removeWithDrops}。
 * 没有 {@code setType}、没有 {@code setAir}、也没有「静默清除」。所以断言
 * 「drops 里出现了这一格」就等价于断言「走的是掉落路径」—— 这是把契约写进类型里，
 * 而不是靠测试记得去检查某个布尔参数。真实实现里那一步对应
 * {@code CraftEngineBlocks.remove(block, player, isMoving=false, dropLoot=true, ...)}，
 * 参数含义按字节码核对，见 {@link BlockBreakdownListener}。
 *
 * <h2>为什么一堆用例都把假世界标成 reactive</h2>
 * 那是 CE 的真实行为（{@code delay: 0} 的支撑行为在邻居变化时同步把方块改成空气、不掉落）。
 * 不模拟它，「边拆边读」的实现也能全绿 —— 见 {@link #cePreemptionModelIsReal()}。
 */
class BlockBreakdownCascadeTest {

    /** 一个「吊在上面的藤蔓」表项 + 一个「立在地上的植物」表项 + 贴面/混向各一条。 */
    private static final BlockBreakdownTable TABLE = BlockBreakdownTable.parse("""
            test:hangs up
            test:stands down
            test:torch facing
            test:mixed mixed
            test:loop both
            """);

    /**
     * 回归：三层吊链只拆最底下那格的支撑 → 三层都要**掉落**，即使 CE 的支撑行为是同步的。
     *
     * <h2>这条测试守的是什么</h2>
     * 「边拆边读」的实现（先拆第 1 层、再读第 2 层）在这里必红：它拆掉第 1 层的瞬间，
     * CE 就把第 2 层静默改成空气了 —— 等它回头读第 2 层只能看到空气，于是第 2、3 层静默消失。
     * 所以实现必须先一次性读完整条链（{@code silentClears} 必须为空），再逐格掉落。
     *
     * <p>执行顺序也因此被钉住：由深到浅（离断口最远的先），这样链上每一格在被拆之前都还有支撑。
     */
    @Test
    void threeLevelChainDropsEveryLevelEvenWithSynchronousCeBehavior() {
        FakeBreakdownWorld world = new FakeBreakdownWorld()
                .put(0, 10, 0, "test:support")   // 玩家挖掉的支撑：普通实心方块，自身没有 CE 支撑行为
                .putReactive(0, 9, 0, "test:hangs", Side.ABOVE)
                .putReactive(0, 8, 0, "test:hangs", Side.ABOVE)
                .putReactive(0, 7, 0, "test:hangs", Side.ABOVE);

        BlockBreakdownCascade.Result result = new BlockBreakdownCascade(TABLE, world).run(0, 10, 0);

        assertEquals(3, result.removed(), "整条链三格都要掉");
        assertEquals(
                List.of(
                        new FakeBreakdownWorld.Pos(0, 7, 0).toString(),
                        new FakeBreakdownWorld.Pos(0, 8, 0).toString(),
                        new FakeBreakdownWorld.Pos(0, 9, 0).toString()),
                world.drops(),
                "由深到浅：链尾先消失，链上每一格在被拆之前都还有支撑");
        assertEquals(List.of(), world.silentClears(),
                "一格都不许被 CE 抢先静默清空 —— 出现任何一格就说明又是「边拆边读」");
        assertEquals(0, result.preempted());
        assertFalse(result.truncated(), "三格的链远不到上限");
        assertFalse(world.drops().contains(new FakeBreakdownWorld.Pos(0, 10, 0).toString()),
                "玩家挖掉的那一格不归本监听 —— 它的掉落由 CE / 原版给，重复给就是双掉");
        assertNull(world.idAt(0, 9, 0), "拆掉之后世界不该还报告那个方块");
    }

    /**
     * 对照组：证明假世界的 CE 模拟不是摆设。
     *
     * <p>这里手工复现「边拆边读」的第一步 —— 先拆最靠近断口的那一层 —— 然后断言第二层
     * **确实**被静默清空（没有掉落）。如果这个断言失败，上面那条回归测试就是假绿。
     */
    @Test
    void cePreemptionModelIsReal() {
        FakeBreakdownWorld world = new FakeBreakdownWorld()
                .put(0, 10, 0, "test:support")
                .putReactive(0, 9, 0, "test:hangs", Side.ABOVE)
                .putReactive(0, 8, 0, "test:hangs", Side.ABOVE);

        assertTrue(world.removeWithDrops(0, 9, 0), "第一层被我们拆掉并掉落");

        assertNull(world.idAt(0, 8, 0), "CE 的同步支撑行为会立刻把第二层改成空气");
        assertEquals(List.of(new FakeBreakdownWorld.Pos(0, 8, 0).toString()), world.silentClears(),
                "而且它是静默消失：不进 drops");
    }

    /** 清单外的方块一律不动：这是「不要改变其它玩法」的硬边界。 */
    @Test
    void blockOutsideTheTableIsNeverTouched() {
        FakeBreakdownWorld world = new FakeBreakdownWorld()
                .put(0, 10, 0, "test:support")
                .put(0, 9, 0, "minecraft:soul_sand");

        BlockBreakdownCascade.Result result = new BlockBreakdownCascade(TABLE, world).run(0, 10, 0);

        assertEquals(0, result.removed());
        assertTrue(world.drops().isEmpty(), "清单外的方块绝不能跟着掉落：" + world.drops());
        assertEquals("minecraft:soul_sand", world.idAt(0, 9, 0), "它必须原地还在");
    }

    /**
     * 方向过滤：只依赖上方的方块，挖掉它下方不该动它；只依赖下方的，挖掉它上方也不该动它。
     *
     * <p>四条断言把「依赖哪一侧」的语义钉在坐标上，而不是钉在某个变量的名字上。
     */
    @Test
    void directionFilterFollowsTheBlocksOwnDependencySide() {
        // 1) 依赖上方 + 上方没了 → 掉
        FakeBreakdownWorld hanging = new FakeBreakdownWorld()
                .putReactive(0, 9, 0, "test:hangs", Side.ABOVE);
        assertEquals(1, new BlockBreakdownCascade(TABLE, hanging).run(0, 10, 0).removed(),
                "依赖上方的方块，上方那格没了就该掉");

        // 2) 依赖上方 + 下方没了 → 不动（它靠的是上面，下面通不通与它无关）
        FakeBreakdownWorld hangingButBrokenBelow = new FakeBreakdownWorld()
                .putReactive(0, 9, 0, "test:hangs", Side.ABOVE);
        assertEquals(0, new BlockBreakdownCascade(TABLE, hangingButBrokenBelow).run(0, 8, 0).removed(),
                "依赖上方的方块，挖掉它下方时不该跟着掉");
        assertEquals("test:hangs", hangingButBrokenBelow.idAt(0, 9, 0));

        // 3) 依赖下方 + 下方没了 → 掉
        FakeBreakdownWorld standing = new FakeBreakdownWorld()
                .putReactive(0, 9, 0, "test:stands", Side.BELOW);
        assertEquals(1, new BlockBreakdownCascade(TABLE, standing).run(0, 8, 0).removed(),
                "依赖下方的方块，下方那格没了就该掉");

        // 4) 依赖下方 + 上方没了 → 不动
        FakeBreakdownWorld standingButBrokenAbove = new FakeBreakdownWorld()
                .putReactive(0, 9, 0, "test:stands", Side.BELOW);
        assertEquals(0, new BlockBreakdownCascade(TABLE, standingButBrokenAbove).run(0, 10, 0).removed(),
                "依赖下方的方块，挖掉它上方时不该跟着掉");
        assertEquals("test:stands", standingButBrokenAbove.idAt(0, 9, 0));
    }

    /**
     * 钟乳石/石笋按**锚点方向**塌（原版语义，有意偏离上游的两个方向都塌）。
     *
     * <p>用户 2026-10-31 实测：一根从地面往上长的柱子，打掉**上面**那段时下面的也一起掉，
     * 那是悬挂（stalactite）的语义，不是石笋（stalagmite）的。这里钉住两条：
     * <ul>
     *   <li>立在地面上的柱子：拆中间，只有**上面**那截塌，下面还连着地面的不动；</li>
     *   <li>吊在天花板下的柱子：拆中间，只有**下面**那截塌，上面还连着天花板的不动。</li>
     * </ul>
     */
    @Test
    void supportDirectedStalactiteOnlyCollapsesAwayFromTheAnchor() {
        String id = "betternether:basalt_stalactite";
        BlockBreakdownTable table = BlockBreakdownTable.builtin();

        // 立在地面：锚点在 y=0，柱子 y=1..5，拆掉中间那格 y=3。
        FakeBreakdownWorld standing = new FakeBreakdownWorld()
                .putReactive(0, 1, 0, id, Side.BELOW)
                .putReactive(0, 2, 0, id, Side.BELOW)
                .putReactive(0, 4, 0, id, Side.BELOW)
                .putReactive(0, 5, 0, id, Side.BELOW)
                .anchor(0, 0, 0);
        BlockBreakdownCascade.Result ground = new BlockBreakdownCascade(table, standing).run(0, 3, 0);
        // 计数把「掉落式移除」和「执行时发现已经被 CE 清掉」分开记，两者对玩家都是"没了"，
        // 所以这里断言的是实际消失的格数，而不是其中某一个计数器。
        assertEquals(2, ground.removed() + ground.preempted(), "向上生长的柱子：拆中间只该塌上面那两格");
        assertNull(standing.idAt(0, 4, 0), "上面那段已经悬空，该掉");
        assertNull(standing.idAt(0, 5, 0), "上面那段已经悬空，该掉");
        assertEquals(id, standing.idAt(0, 1, 0), "下面那段还连着地面，不该掉");
        assertEquals(id, standing.idAt(0, 2, 0), "下面那段还连着地面，不该掉");

        // 吊在天花板：锚点在 y=6，柱子 y=1..5，拆掉中间那格 y=3。
        FakeBreakdownWorld hanging = new FakeBreakdownWorld()
                .putReactive(0, 1, 0, id, Side.ABOVE)
                .putReactive(0, 2, 0, id, Side.ABOVE)
                .putReactive(0, 4, 0, id, Side.ABOVE)
                .putReactive(0, 5, 0, id, Side.ABOVE)
                .anchor(0, 6, 0);
        BlockBreakdownCascade.Result roof = new BlockBreakdownCascade(table, hanging).run(0, 3, 0);
        assertEquals(2, roof.removed() + roof.preempted(), "悬挂的柱子：拆中间只该塌下面那两格");
        assertEquals(id, hanging.idAt(0, 5, 0), "上面那段还连着天花板，不该掉");
        assertEquals(id, hanging.idAt(0, 4, 0), "上面那段还连着天花板，不该掉");
        assertNull(hanging.idAt(0, 2, 0), "下面那段已经悬空，该掉");
        assertNull(hanging.idAt(0, 1, 0), "下面那段已经悬空，该掉");
    }

    /**
     * 贴面方块按它自己声明的朝向判；读不到朝向就不猜。
     *
     * <p>{@code facing} 的支撑就在方块声明的那一侧，所以挖掉「那一侧」才该掉。读不到朝向时
     * 宁可交给 CE，也不要拿一个猜出来的方向去拆方块。
     */
    @Test
    void facingBlockOnlyBreaksWhenItsOwnAttachedSideIsRemoved() {
        FakeBreakdownWorld matches = new FakeBreakdownWorld()
                .put(0, 9, 0, "test:torch")
                .attach(0, 9, 0, Side.EAST);
        assertEquals(1, new BlockBreakdownCascade(TABLE, matches).run(1, 9, 0).removed(),
                "贴在东面的火把，东面那格没了就该掉");

        FakeBreakdownWorld otherSide = new FakeBreakdownWorld()
                .put(0, 9, 0, "test:torch")
                .attach(0, 9, 0, Side.EAST);
        assertEquals(0, new BlockBreakdownCascade(TABLE, otherSide).run(0, 10, 0).removed(),
                "挖掉它上方不该动一个贴在东面的火把");

        FakeBreakdownWorld unknownFacing = new FakeBreakdownWorld()
                .put(0, 9, 0, "test:torch");
        assertEquals(0, new BlockBreakdownCascade(TABLE, unknownFacing).run(1, 9, 0).removed(),
                "读不到朝向就不级联 —— 绝不猜方向");
    }

    /** {@code mixed} 说不出单一方向：不级联（宁可交给 CE，也不误拆）。 */
    @Test
    void mixedCriterionIsNotCascadedInEitherDirection() {
        for (int originY : new int[]{8, 10}) {
            FakeBreakdownWorld world = new FakeBreakdownWorld().put(0, 9, 0, "test:mixed");
            assertEquals(0, new BlockBreakdownCascade(TABLE, world).run(0, originY, 0).removed(),
                    "mixed 判据无法用单一方向表达，任何一侧被挖都不该由本监听拆");
            assertEquals("test:mixed", world.idAt(0, 9, 0));
        }
    }

    /**
     * 已访问集合：每一格在一次级联里只进计划一次，即使「移除成功后世界仍报告那个方块」。
     *
     * <h2>为什么这条必须单独测</h2>
     * 三维网格里的链条本身走不成环，所以「循环保护」真正防的不是环，而是**反复发现**：
     * 移除被别的插件拦下、或者方块被原地改回来时，同一格会被它自己的邻居一次次重新发现。
     * 没有已访问集合，链条会在两格之间来回弹到深度上限 —— 每一轮都真的掉一次东西。
     */
    @Test
    void eachPositionIsAttemptedOnlyOnceEvenWhenTheWorldKeepsReportingIt() {
        FakeBreakdownWorld world = new FakeBreakdownWorld()
                .putSticky(0, 0, 0, "test:loop")
                .putSticky(0, 1, 0, "test:loop")
                .putSticky(0, -1, 0, "test:loop");

        BlockBreakdownCascade.Result result = new BlockBreakdownCascade(TABLE, world).run(0, 0, 0);

        assertEquals(2, result.removed(), "上下各一次，就这些");
        assertEquals(2, world.drops().size(), "起点自己不再被拆，弹回来的格子也不再拆第二遍");
        assertFalse(world.drops().contains(new FakeBreakdownWorld.Pos(0, 0, 0).toString()),
                "起点就是「刚消失的那一格」，它不该被自己带走");
    }

    /**
     * 深度上限：超过 {@link BlockBreakdownCascade#MAX_DEPTH} 的链尾不再处理，并且如实报告截断。
     *
     * <p>上限存在的意义是给一个 tick 的工作量封顶；「截断」必须能被观察到，否则一段过长的链
     * 会安静地只掉一半，看上去像随机的 bug。
     */
    @Test
    void longChainsAreTruncatedAtTheDepthCap() {
        FakeBreakdownWorld world = new FakeBreakdownWorld();
        for (int y = 0; y < 100; y++) {
            world.put(0, y, 0, "test:hangs");
        }

        BlockBreakdownCascade.Result result = new BlockBreakdownCascade(TABLE, world).run(0, 100, 0);

        assertEquals(BlockBreakdownCascade.MAX_DEPTH, result.removed(),
                "最多处理 MAX_DEPTH 格：" + BlockBreakdownCascade.MAX_DEPTH);
        assertTrue(result.truncated(), "链还没走完就到了上限，必须报截断");
        assertFalse(world.drops().contains(new FakeBreakdownWorld.Pos(0, 99 - BlockBreakdownCascade.MAX_DEPTH, 0).toString()),
                "上限之外的链尾保持原样（回落到 CE 自身行为）");

        FakeBreakdownWorld shortChain = new FakeBreakdownWorld();
        for (int y = 0; y < 10; y++) {
            shortChain.put(0, y, 0, "test:hangs");
        }
        BlockBreakdownCascade.Result shortResult =
                new BlockBreakdownCascade(TABLE, shortChain).run(0, 10, 0);
        assertEquals(10, shortResult.removed(), "十格的链要整条掉完");
        assertFalse(shortResult.truncated(), "没到上限就不能报截断");
    }

    /**
     * 跨区域：不归当前区域的那一格**一个字节都不读**，整格连判断一起交出去。
     *
     * <p>Folia 上跨区域读方块是硬错误（Paper 上是静默竞态）。这条同时盯着两件事：
     * 没有发生跨区域读，并且交出去时把「哪一侧的支撑没了」也一起交代了 —— 没有这个方向，
     * 对面只能瞎猜。
     */
    @Test
    void foreignRegionNeighbourIsHandedOffWithoutBeingRead() {
        FakeBreakdownWorld world = new FakeBreakdownWorld()
                .put(0, 9, 0, "test:hangs")
                .foreign(0, 9, 0);

        BlockBreakdownCascade.Result result = new BlockBreakdownCascade(TABLE, world).run(0, 10, 0);

        assertEquals(0, world.foreignReads(), "跨区域坐标不允许读");
        assertEquals(Side.ABOVE, world.handedOff().get(new FakeBreakdownWorld.Pos(0, 9, 0).toString()),
                "交出去时必须带上「它上面那格的支撑没了」这个方向");
        assertEquals(1, result.handedOff());
        assertEquals(0, result.removed());
        assertEquals("test:hangs", world.idAt(0, 9, 0), "父区域不许替对面把方块拆了");
    }

    /**
     * 转交过来的根必须**重新判定**：不是清单里的方块就不动，方向不对也不动。
     *
     * <p>这条钉住的是 {@code handOff} 的契约 —— 对面拿到的是「坐标 + 哪一侧没了」，而不是
     * 「对面让我拆」。父区域的结论在下一个 tick 可能已经过时，照抄会拆错方块。
     *
     * <p>刻意只用**单格**（不带链）来测：跨区域那一格自己的支撑在对面动手时确实已经不在了，
     * 所以它一旦落地触发邻居更新就可能被 CE 抢先清空 —— 那属于「无法覆盖」清单里的一条，
     * 不该混进这条契约测试里搅浑结论。
     */
    @Test
    void handedOffRootIsJudgedAgainInsteadOfTrustingThePreviousRegion() {
        // 不在清单里：判定不通过，一格都不拆。
        FakeBreakdownWorld notInTable = new FakeBreakdownWorld()
                .put(0, 9, 0, "minecraft:soul_sand");
        BlockBreakdownCascade.Result untouched = new BlockBreakdownCascade(TABLE, notInTable)
                .run(0, 9, 0, Side.ABOVE);
        assertEquals(0, untouched.removed(), "不是清单方块就不动");
        assertEquals(List.of(), notInTable.drops());
        assertEquals("minecraft:soul_sand", notInTable.idAt(0, 9, 0));

        // 方向不对：贴东面的火把，说是「上面没了」，不该拆。
        FakeBreakdownWorld wrongSide = new FakeBreakdownWorld()
                .put(0, 9, 0, "test:torch")
                .attach(0, 9, 0, Side.EAST);
        assertEquals(0, new BlockBreakdownCascade(TABLE, wrongSide)
                .run(0, 9, 0, Side.ABOVE).removed(), "方向不匹配就不拆");
        assertEquals(List.of(), wrongSide.drops());

        // 判定通过：这一格自己要被掉落式移除（父区域不许替它拆，它也不许被漏掉）。
        FakeBreakdownWorld ok = new FakeBreakdownWorld()
                .put(0, 9, 0, "test:hangs");
        BlockBreakdownCascade.Result result =
                new BlockBreakdownCascade(TABLE, ok).run(0, 9, 0, Side.ABOVE);
        assertEquals(List.of(new FakeBreakdownWorld.Pos(0, 9, 0).toString()), ok.drops(),
                "根自己也要掉落");
        assertEquals(1, result.removed());
        assertEquals(1, result.depth(), "只有它自己，没有下一层");
    }

    /**
     * CE 抢在收集与执行之间动手时：只记一次「被抢先」，不重复掉落。
     *
     * <p>这个竞态本身无法确定性复现，用假世界的 {@code preemptOnAnyDrop} 把它逼出来。
     * 关键是它必须被计数并进日志 —— 否则症状是「偶尔有一格不掉」，没人查得动。
     */
    @Test
    void ceWinningTheRaceIsCountedInsteadOfDoubleDropped() {
        FakeBreakdownWorld world = new FakeBreakdownWorld()
                .put(0, 10, 0, "test:support")
                .putReactive(0, 9, 0, "test:hangs", Side.ABOVE)
                .putReactive(0, 8, 0, "test:hangs", Side.ABOVE)
                .preemptOnAnyDrop(0, 9, 0);

        BlockBreakdownCascade.Result result = new BlockBreakdownCascade(TABLE, world).run(0, 10, 0);

        assertEquals(List.of(new FakeBreakdownWorld.Pos(0, 8, 0).toString()), world.drops(),
                "只掉了链尾那一格");
        assertEquals(1, result.removed());
        assertEquals(1, result.preempted(), "被抢先的一格必须被数出来，并且进 WARNING 日志");
        assertEquals(1, world.silentClears().size(), "它是静默消失，不进 drops");
    }
}
