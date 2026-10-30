package dev.mumu.betternether.vanilla;

import net.momirealms.craftengine.bukkit.api.CraftEngineBlocks;
import net.momirealms.craftengine.core.block.BlockStateWrapper;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.property.Property;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;


/**
 * 玩家手动放下「竖向多段」方块时，按上下邻居把形态算对。
 *
 * <h2>为什么需要这一层</h2>
 * 上游这些方块的形态（{@code shape} 的 bottom/middle/top、藤蔓的 {@code bottom} 布尔）
 * 不是放置时定下来的，而是邻居算出来的：单放一格是末端，往上一格接一格才会有中间段与顶端。
 * CraftEngine 侧核实过**没有任何 behavior 能按上下邻居重算任意 string 属性**
 * （{@code multi_high_block} 只吃 int、{@code stairs_block} 只认 stairs 的 shape、
 * {@code stackable_block} 只认 int 堆叠计数、洞穴藤蔓那两个 behavior 是 age+berries 的生长模型），
 * 于是放置永远拿到 YAML 的 {@code default}，一串藤从上到下都是同一个形态。
 * 判定规则与逐块依据写在 {@link BlockShapeTable}，这里只负责把它接到 Bukkit / CraftEngine 上。
 *
 * <h2>事件选择：为什么是 {@link BlockPlaceEvent}，为什么在 MONITOR</h2>
 * 只有玩家的手动放置会产生这个问题（worldgen 与 {@code /setblock} 不管这件事，也不该被这里修正）。
 * Bukkit 的贴图顺序是「先把方块写进世界、再发事件」，所以 MONITOR 阶段读到的已经是
 * 刚放下的那个方块 —— 这正是能认出它是什么的前提。MONITOR 仍然可能被保护区插件取消
 * （在那之前注册的监听），取消的放置不该被改写，所以显式查 {@link BlockPlaceEvent#isCancelled()}。
 *
 * <h2>读与写都走 CraftEngine</h2>
 * 反查用 {@link CraftEngineBlocks#getCustomBlockState(Block)}，与 {@link BlockBreakdownListener}
 * 同一条路子：{@code ImmutableBlockState} → {@code customBlockState()} → {@code ownerId()}，
 * 拼成 {@code namespace:value} 再查表；认不出来（空气 / 原版方块 / CE 缺席 / 接口变更）
 * 一律当作「不认识」，绝不回落到材质或名字猜测。
 * <p>改写时把当前状态换个属性值再整体写回去
 * （{@code CraftEngineBlocks.place(location, state, checkCanSurvive=false, playSound=false)}，
 * 走默认的 update flags 3 = 通知邻居 + 通知客户端）。{@code checkCanSurvive=false} 是必须的：
 * 吊藤的 {@code canSurvive} 判的是「上方是不是支撑」，而链中间的格子在该方法里本来就不成立，
 * 让它去检查等于什么都不写。属性值不在该属性的取值集里时（CE 的 {@code valueByName} 返回
 * {@code null}）直接放弃这一格，不猜、不硬塞。
 *
 * <h2>Folia</h2>
 * 事件在拥有该区域的线程上派发，所以对事件坐标的读写是合法的。往下走时每个候选格都先问
 * {@link Bukkit#isOwnedByCurrentRegion(Location)}，不归本区域就交给
 * {@link RegionSchedulerAdapter#run(Plugin, Location, Runnable)} 排到它的区域线程重判一次。
 * 全程不碰 {@code BukkitScheduler}。补充一句实话：Folia 的区域按 XZ 列划分，
 * **竖直邻格永远同属一个区域**，所以这条护栏在真实服务器上基本不会触发；
 * 留着它是为了让实现不依赖这个前提。
 *
 * <h2>明确不覆盖的场景</h2>
 * <ul>
 *   <li><b>其它途径放进世界的方块</b>：{@code /setblock}、结构方块、worldgen、别的插件调
 *       {@code Block#setType}、活塞推动 —— 都不经过 {@link BlockPlaceEvent}，这里不触发。
 *       上游对 worldgen 本来就直接写形态，不需要我们修。</li>
 *   <li><b>破坏后的形态回填</b>：挖掉中间一段之后，上下两段要不要重新接起来是
 *       {@link BlockBreakdownListener} 的领域；本类只在放置时工作，避免两条链互相打架。</li>
 *   <li><b>{@code brown_large_mushroom} 的 {@code side_*}/{@code corner_*}</b>：上游自己
 *       都不按邻居重算这些取值，规则无法确证，整块不处理（原因写在 {@link BlockShapeTable}）。</li>
 *   <li><b>CE 配置里的 {@code default}</b>：本类只修「放下来之后该是什么」，不改 YAML。
 *       所以 CE 没加载、或表里的方块根本没发布时，这里一次也不会命中 —— 注册成功与否要靠
 *       启动日志里的条数看得出来。</li>
 * </ul>
 */
public final class BlockShapeOnPlaceListener implements Listener {

    private final Plugin plugin;
    private final BlockShapeTable table;

    public BlockShapeOnPlaceListener(Plugin plugin) {
        this(plugin, BlockShapeTable.builtin());
    }

    BlockShapeOnPlaceListener(Plugin plugin, BlockShapeTable table) {
        this.plugin = plugin;
        this.table = table;
    }

    /** 表里有多少条方块。启动日志用一次，好让「表没加载」这件事在日志里看得出来。 */
    public int size() {
        return table.size();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerPlace(BlockPlaceEvent event) {
        // MONITOR 阶段仍可能被更早的监听取消（保护区插件），取消的放置不该被改写。
        if (event.isCancelled()) {
            return;
        }
        Block block = event.getBlockPlaced();
        apply(new CraftEngineShapeWorld(block.getWorld()), block.getX(), block.getY(), block.getZ());
    }

    /**
     * 一次放置的全部工作：重算这一列的形态。
     *
     * <p>「重新交给本方法」是本类唯一的跨线程入口 —— {@link CraftEngineShapeWorld#handOff}
     * 排到目标区域线程后调的就是它，所以那边会**重新读一次世界**再判定，不沿用父区域的结论。
     */
    private void apply(CraftEngineShapeWorld world, int x, int y, int z) {
        if (!world.ownedByCurrentRegion(x, y, z)) {
            world.handOff(x, y, z);
            return;
        }
        BlockShapeReshaper reshaper = new BlockShapeReshaper(table, world);
        BlockShapeReshaper.Result result = reshaper.reshapeAround(x, y, z);
        if (result.written() > 0) {
            plugin.getLogger().fine("放置形态重算 " + world.world.getName() + " " + x + "," + y + "," + z
                    + "：" + result.written() + " 格改写，" + result.examined() + " 格命中形状表。");
        }
        if (result.refused() > 0) {
            // 该写没写成：CE 拒绝、方块已被换掉、或属性值不在取值集里。不静默。
            plugin.getLogger().warning("放置形态重算在 " + world.world.getName() + " " + x + "," + y + "," + z
                    + " 有 " + result.refused() + " 格没能改写形态（CraftEngine 拒绝或属性值不在取值集里）。");
        }
    }

    /** 世界视图：把坐标翻译成 CraftEngine 的读写。 */
    private final class CraftEngineShapeWorld implements BlockShapeReshaper.World {
        private final World world;
        /** 转交是否已经做过一次：防止目标区域仍不认为自己拥有该格时无限排下去。 */
        private boolean handedOver;

        CraftEngineShapeWorld(World world) {
            this.world = world;
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
                Key owner = wrapper.ownerId();
                return owner == null ? null : owner.namespace() + ":" + owner.value();
            } catch (RuntimeException | LinkageError error) {
                return null;
            }
        }

        @Override
        public @Nullable String propertyValue(int x, int y, int z, String property) {
            try {
                BlockStateWrapper wrapper = wrapperAt(x, y, z);
                if (wrapper == null || !wrapper.hasProperty(property)) {
                    return null;
                }
                Object value = wrapper.getProperty(property);
                return value == null ? null : String.valueOf(value);
            } catch (RuntimeException | LinkageError error) {
                return null;
            }
        }

        @Override
        public boolean setProperty(int x, int y, int z, String property, String value) {
            try {
                Block block = world.getBlockAt(x, y, z);
                ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(block);
                if (state == null || state.isEmpty()) {
                    return false;
                }
                ImmutableBlockState next = withValue(state, property, value);
                if (next == null || next.customBlockState() == null) {
                    // 值不在该属性的取值集里，或换完之后凑不出合法的 CE 状态：放弃这一格。
                    return false;
                }
                // flags 用 place 的重载默认值 3（通知邻居 + 通知客户端），与上游 updateShape
                // 引发的邻居更新一致；checkCanSurvive=false / playSound=false 的理由见类注释。
                return CraftEngineBlocks.place(new Location(world, x, y, z), next, false, false);
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
        public boolean handOff(int x, int y, int z) {
            if (handedOver) {
                // 目标区域也不认为它拥有这一格：放弃（宁可少改一格，也不在两个区域之间来回排）。
                plugin.getLogger().fine("放置形态重算放弃 " + x + "," + y + "," + z + "：转交后仍不归当前区域。");
                return false;
            }
            Location location = new Location(world, x, y, z);
            try {
                RegionSchedulerAdapter.run(plugin, location, () -> {
                    CraftEngineShapeWorld relaunched = new CraftEngineShapeWorld(world);
                    relaunched.handedOver = true;
                    apply(relaunched, x, y, z);
                });
                return true;
            } catch (RuntimeException | LinkageError error) {
                // 插件已禁用 / 区域已注销：放弃这一格（宁可少改，也不跨线程写方块）。
                return false;
            }
        }

        private @Nullable BlockStateWrapper wrapperAt(int x, int y, int z) {
            Block block = world.getBlockAt(x, y, z);
            ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(block);
            if (state == null || state.isEmpty()) {
                return null;
            }
            return state.customBlockState();
        }

        /**
         * 复制一份状态并把某个属性换成新的字符串值；换不出来返回 {@code null}。
         *
         * <p>{@link Property#valueByName(String)} 对 string 属性返回那个字符串本身（不在取值集里
         * 返回 {@code null}），对枚举/方向属性按名字查表（CE 的 {@code EnumProperty} 内部把名字
         * 全部小写化了，所以传 {@code "up"} 这种小写名即可）。这里刻意用原始类型接住：
         * 属性值可能是 {@code String} 也可能是 {@code Direction} 枚举，按 {@code Property<String>}
         * 声明会在方向属性上抛 {@code ClassCastException}。
         */
        @SuppressWarnings({"unchecked", "rawtypes"})
        private @Nullable ImmutableBlockState withValue(ImmutableBlockState state, String property, String value) {
            Property raw = state.getProperty(property);
            if (raw == null) {
                return null;
            }
            Comparable resolved = (Comparable) raw.valueByName(value);
            if (resolved == null) {
                return null;
            }
            return state.with(raw, resolved);
        }
    }
}
