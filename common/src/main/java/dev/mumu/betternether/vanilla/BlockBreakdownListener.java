package dev.mumu.betternether.vanilla;

import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.block.BlockStateWrapper;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 植物失去支撑时跟着**掉落**的监听器：把上游「静默消失」改成本项目要求的「掉落」。
 *
 * <h2>为什么要单独写一层</h2>
 * 上游 BetterNether 的 {@code updateShape} 判据在大多数方块上是「不满足就 {@code Blocks.AIR}」，
 * 也就是静默消失、什么都不掉。CE 的支撑行为（{@code sturdy_base_block} / {@code hanging_block} /
 * {@code directional_attached_block}）同样只负责把它变回空气。用户明确要求「被链接的也该掉落」，
 * 这是有意偏离上游；而 CE 的 YAML 层表达不了「掉落」，所以只能落在 Java 侧。
 * 生成期的证据在 {@code reports/plant-breakdown.json}（59 条，{@code drops_source} 全是
 * {@code user_requirement}），运行期表见 {@link BlockBreakdownTable}。
 *
 * <h2>只处理清单内的方块</h2>
 * 表里没有的方块一律不动。改这个范围等于悄悄改别的玩法，而那张表是逐条核实过的。
 *
 * <h2>事件选择：为什么是它们，为什么没有第三个</h2>
 * <ul>
 *   <li>{@link BlockBreakEvent}（玩家破坏，可靠）：这是唯一能拿到「谁挖的」的路径，
 *       掉落物因此能记到正确的玩家名下（CE 的 {@code remove} 会把玩家放进掉落上下文）。</li>
 *   <li>{@link BlockExplodeEvent} / {@link EntityExplodeEvent}：爆炸会把支撑炸掉，
 *       而玩家在这一刻通常就在旁边看着。两者都取 {@code blockList()}，同一个 tick 内处理。</li>
 *   <li><b>不用 {@code BlockPhysicsEvent}</b>：Paper 会大量过滤这个事件（只在「自己关心」的
 *       更新上触发），Folia 上它在哪个线程、按什么粒度触发都没有稳定契约；而且它的语义是
 *       「某格被邻居变化惊动」，不是「某格消失了」——用它的前提是能证明「惊动」等于「消失」，
 *       这一步没法证明。所以这里只在「确实有一格被移除」的事件上工作。</li>
 * </ul>
 *
 * <h2>时序：两阶段，以及为什么不能「边走边拆」</h2>
 * 事件的 MONITOR 阶段**在方块真正被移除之前**执行（Bukkit 先发事件、后拆方块），于是：
 * <ol>
 *   <li>此刻链上每一格都还在，CE 的方块状态可读 —— 这是能认出它们的前提；</li>
 *   <li>先做一次**纯只读**的收集：把整条依赖链读成一份按层排好的计划，全程不改任何方块；</li>
 *   <li>再按**由深到浅**逐格掉落式移除，链尾先消失；</li>
 *   <li>随后方块真被移除、邻居更新触发 CE 的支撑行为时，那几格**已经是空气**，
 *       CE 找不到自定义方块状态，什么也不会做 —— 也就没有第二次掉落。</li>
 * </ol>
 * <b>为什么必须先收集再执行</b>：CE 的支撑行为是同步的（{@code delay: 0}）。若边拆边读，
 * 我们拆掉第 1 层的那一瞬间，CE 就已经把第 2 层静默改成空气了 —— 等我们回头读它，只能看到空气，
 * 于是第 2 层、第 3 层全部静默消失，整条链只有第一格真的掉。这是审查中确认的失败形态
 * （{@code BlockBreakdownCascadeTest#cePreemptionModelIsReal} 用假世界把它钉住了），
 * 也是 {@link BlockBreakdownCascade} 分成两个阶段的唯一原因。
 * <p>反过来的情况（CE 先动手）也被覆盖：执行阶段每一格开拆前重读一次 id，已经空了就只计一次
 * 「被抢先」，绝不重复掉落、也绝不对着空气做掉落。
 *
 * <h2>Folia</h2>
 * 事件本身在拥有该区域的线程上触发，所以该事件里对**本区域**方块的读写是合法的。收集阶段每个
 * 候选邻居都先问 {@link Bukkit#isOwnedByCurrentRegion}：不归本区域的格子**一个字节都不读**，
 * 而是把「坐标 + 它哪一侧的支撑没了」交给 {@link BlockBreakdownCascade.World#handOff}，
 * 那边用 {@link RegionSchedulerAdapter#run(Plugin, Location, Runnable)} 排到它的区域线程，
 * **重新判定一次**（是不是清单方块、方向对不对）才决定拆不拆 —— 父区域的结论在下一个 tick
 * 可能已经过时，照抄会拆错方块。全程不碰 {@code BukkitScheduler}（Folia 上它连调度语义都不成立）。
 *
 * <h2>明确无法覆盖的场景</h2>
 * <ul>
 *   <li><b>{@link BlockBreakdownCascade#MAX_DEPTH} 格以上的长链尾</b>：超出上限的部分不由本监听
 *       处理，会回落到 CE 的静默消失。命中上限时会记一条 WARNING（带坐标），不是安静截断。</li>
 *   <li><b>{@code mixed} 判据的两个方块</b>（{@code bone_mushroom} / {@code wart_seed}）：
 *       报告说它们「两个方向混用」，单一方向判不出来，因此不级联（不会误拆，但也不掉）。</li>
 *   <li><b>{@code facing} 且读不到朝向</b>：CE 方块状态里没有 {@code facing} 类属性时不猜方向，
 *       该方块交给 CE 处理。</li>
 *   <li><b>跨区域的邻居</b>：会被排到下一个 tick，由那个区域**重新判定**。此时它自己那一侧的支撑
 *       确实已经没了，所以 CE 有可能在它落地前就把它静默拆掉 —— 结果是边界上那一格不掉，
 *       而它下游的链仍然照常掉落（由深到浅执行，只会波及紧邻的那一格）。
 *       这种损失会被计数并记一条 WARNING，不是无声的。</li>
 *   <li><b>活塞推动 / {@code /setblock} / 结构方块 / {@code World#setType} / 其它插件的
 *       直接写方块</b>：这些都不经过上面三个事件，本监听不会触发。</li>
 *   <li><b>玩家用 CE 掉落表之外的方式挖掉</b>（例如别的插件调 {@code Block#breakNaturally}）：
 *       不经过 {@link BlockBreakEvent}，不触发。</li>
 *   <li><b>创造模式也掉落</b>：{@code dropLoot=true} 是无条件的（与上游
 *       {@code destroyBlock(pos, true)} 一致）。玩家自己挖掉的那一格仍按原版/CE 规则走，
 *       所以创造模式下会看到「主方块不掉、被链带的植物掉」。这是有意为之：本功能的前提就是
 *       「被链接的也要掉」，为此加一条模式判断等于把需求改回去。</li>
 * </ul>
 */
public final class BlockBreakdownListener implements Listener {

    /** CE 方块状态里可能承载「贴在哪一面」的属性名，按优先级排列。 */
    private static final List<String> FACING_PROPERTIES = List.of("facing", "attachment", "face");

    private final Plugin plugin;
    private final BlockBreakdownTable table;

    public BlockBreakdownListener(Plugin plugin) {
        this(plugin, BlockBreakdownTable.builtin());
    }

    private BlockBreakdownListener(Plugin plugin, BlockBreakdownTable table) {
        this.plugin = plugin;
        this.table = table;
    }

    /** 表里有多少条方块。启动日志用一次，好让「表没加载」这件事在日志里看得出来。 */
    public int size() {
        return table.size();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerBreak(BlockBreakEvent event) {
        // MONITOR 阶段仍可能被更早的监听取消（保护区插件），取消的破坏不该带走任何东西。
        if (event.isCancelled()) {
            return;
        }
        cascadeAt(event.getBlock(), event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (event.isCancelled()) {
            return;
        }
        // 爆炸没有「谁」，掉落上下文里玩家为 null（CE 的 remove 明确接受 null 玩家）。
        cascadeMany(event.blockList(), null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (event.isCancelled()) {
            return;
        }
        cascadeMany(event.blockList(), null);
    }

    /**
     * 一次破坏的入口。
     *
     * <p>入口自己在拥有该区域的线程上（事件由服务端在区域线程上派发），所以这里对
     * {@code block} 所在坐标的读取是合法的；再往外一格的合法性由级联逐个判断。
     */
    private void cascadeAt(Block block, @Nullable Player player) {
        World world = block.getWorld();
        BlockBreakdownCascade cascade = new BlockBreakdownCascade(table,
                new CraftEngineWorld(world, player));
        BlockBreakdownCascade.Result result = cascade.run(block.getX(), block.getY(), block.getZ());
        report(block, result);
    }

    /**
     * 把「没做到的部分」说出来。
     *
     * <p>两条都必须可见，否则症状会像随机 bug：截断意味着长链只掉了一部分；被抢先意味着
     * CE 在我们之前按它的语义静默拆掉了计划里的某一格（那一格没有掉落）。
     */
    private void report(Block block, BlockBreakdownCascade.Result result) {
        if (result.truncated()) {
            plugin.getLogger().warning("「失去支撑的植物掉落」在 " + block.getWorld().getName() + " "
                    + block.getX() + "," + block.getY() + "," + block.getZ() + " 达到 "
                    + BlockBreakdownCascade.MAX_DEPTH + " 层上限：更远的链尾不再由本插件掉落"
                    + "（会回落到 CraftEngine 的静默移除）。");
        }
        if (result.preempted() > 0) {
            plugin.getLogger().warning("「失去支撑的植物掉落」在 " + block.getWorld().getName() + " "
                    + block.getX() + "," + block.getY() + "," + block.getZ() + " 有 "
                    + result.preempted() + " 格在执行前已被 CraftEngine 静默移除"
                    + "（这些格子不会掉落）。");
        }
    }

    /**
     * 爆炸：清单里每一格都当一次「刚消失的支撑」。
     *
     * <p>逐格独立跑，不跨格共享已访问集合。这不是为了省事，而是因为「重复」在这里是自愈的：
     * 爆炸清单里的某一格如果已经被上一格带走了，轮到它自己时 {@code blockId} 已经是空，
     * 整格直接跳过，不会掉第二遍。共享集合要把状态铺到多个入口、还要处理跨区域转交时的可见性，
     * 换来的只是几次 CE 查询的节省。
     */
    private void cascadeMany(List<Block> blocks, @Nullable Player player) {
        if (blocks.isEmpty()) {
            return;
        }
        // blockList() 是事件自己的可变表；我们只读，但复制一份避免任何并发修改的争议。
        for (Block block : List.copyOf(blocks)) {
            if (block.getType().isAir()) {
                continue;
            }
            cascadeAt(block, player);
        }
    }

    /** 世界视图：把坐标翻译成 CraftEngine 的查询与掉落式移除。 */
    private final class CraftEngineWorld implements BlockBreakdownCascade.World {
        private final World world;
        private final @Nullable Player player;

        CraftEngineWorld(World world, @Nullable Player player) {
            this.world = world;
            this.player = player;
        }

        @Override
        public @Nullable String blockId(int x, int y, int z) {
            try {
                Block block = world.getBlockAt(x, y, z);
                ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(block);
                if (state == null || state.isEmpty()) {
                    // 不是 CE 方块（空气/原版），或 CE 已经把它拆了 —— 两种都当作「没有」。
                    return null;
                }
                BlockStateWrapper wrapper = state.customBlockState();
                if (wrapper == null || !wrapper.isCustom()) {
                    return null;
                }
                return idOf(wrapper.ownerId());
            } catch (RuntimeException | LinkageError error) {
                // CE 缺席或接口变更：当作「不认识这一格」。绝不回落到材质猜测。
                return null;
            }
        }

        @Override
        public boolean anchoredAt(int x, int y, int z) {
            try {
                // 只要不是空气就算锚点：原版方块（下界岩、黑曜石…）和 CE 自定义方块都能撑住
                // 一串钟乳石 —— 这正是 blockId() 给不出的信息（它对两者都返回 null）。
                return !world.getBlockAt(x, y, z).getType().isAir();
            } catch (RuntimeException | LinkageError error) {
                // 读不到就当锚点：少拆一格好过多拆一整串。
                return true;
            }
        }

        @Override
        public @Nullable BlockBreakdownTable.Side attachedSide(int x, int y, int z) {
            try {
                Block block = world.getBlockAt(x, y, z);
                ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(block);
                if (state == null || state.isEmpty()) {
                    return null;
                }
                BlockStateWrapper wrapper = state.customBlockState();
                if (wrapper == null) {
                    return null;
                }
                for (String name : FACING_PROPERTIES) {
                    if (!wrapper.hasProperty(name)) {
                        continue;
                    }
                    BlockBreakdownTable.Side side = BlockBreakdownTable.sideOf(
                            String.valueOf(wrapper.getProperty(name)));
                    if (side != null) {
                        return side;
                    }
                }
                return null;
            } catch (RuntimeException | LinkageError error) {
                return null;
            }
        }

        /**
         * 掉落式移除。
         *
         * <p>{@code CraftEngineBlocks.remove(Block, Player, boolean, boolean, boolean)} 的五个参数
         * 是按 <b>字节码</b>核对的，不是按参数名猜的（jar 没有 {@code MethodParameters}）：
         * 第 1 个 boolean 原样传给 {@code LevelProxy.removeBlock} 的 {@code isMoving}
         * （原版 {@code Level#removeBlock(pos, isMoving)} 的语义），第 2 个 boolean 守着
         * {@code getDrops(...)} + {@code dropItemNaturally(...)} 那一段，第 3 个 boolean 守着
         * {@code levelEvent(2001, ...)}（方块破坏音效）。所以：
         * <pre>remove(block, player, isMoving=false, dropLoot=true, playSound=true)</pre>
         *
         * <p>{@code isMoving=false} 是要紧的：传 {@code true} 等于「本次移除不惊动邻居」，
         * 那会让 CE 的支撑行为与其它方块物理都看不到这次变化。
         */
        @Override
        public boolean removeWithDrops(int x, int y, int z) {
            try {
                Block block = world.getBlockAt(x, y, z);
                return CraftEngineBlocks.remove(block, player, false, true, true);
            } catch (RuntimeException | LinkageError error) {
                return false;
            }
        }

        @Override
        public boolean ownedByCurrentRegion(int x, int y, int z) {
            try {
                return Bukkit.isOwnedByCurrentRegion(new Location(world, x, y, z));
            } catch (RuntimeException | LinkageError error) {
                // 拿不到归属就不冒险跨区域写方块：当作「不归我」，交给 handOff。
                return false;
            }
        }

        @Override
        public boolean handOff(int x, int y, int z, BlockBreakdownTable.Side removedSide) {
            Location location = new Location(world, x, y, z);
            try {
                RegionSchedulerAdapter.run(plugin, location, () -> {
                    // 在拥有这一格的区域线程上**重新走一遍完整判定**：是不是清单里的方块、方向对不对、
                    // 要不要掉落，都不沿用父区域的结论（那边看到的是上一个 tick 的世界）。
                    BlockBreakdownCascade cascade =
                            new BlockBreakdownCascade(table, new CraftEngineWorld(world, player));
                    BlockBreakdownCascade.Result result = cascade.run(x, y, z, removedSide);
                    if (result.removed() > 0 || result.preempted() > 0) {
                        plugin.getLogger().fine("「失去支撑的植物掉落」跨区域处理 " + x + "," + y + "," + z
                                + "：" + result.removed() + " 格掉落，"
                                + result.preempted() + " 格已被 CraftEngine 抢先移除。");
                    }
                });
                return true;
            } catch (RuntimeException | LinkageError error) {
                // 插件已禁用 / 区域已注销：放弃这一格（宁可少拆，也不跨线程写方块）。
                return false;
            }
        }

        /** {@code Key} → {@code namespace:value}。自己拼，不依赖 {@code toString} 的约定。 */
        private String idOf(Key key) {
            return key.namespace() + ":" + key.value();
        }
    }
}
