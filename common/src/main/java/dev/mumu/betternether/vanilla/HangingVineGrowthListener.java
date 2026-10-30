package dev.mumu.betternether.vanilla;

import dev.mumu.betternether.config.NetherConfig;
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
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.Plugin;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * @author Liannai
 * @Desc 悬挂藤蔓（eye_vine / golden_vine / lumabus_vine）向下延伸：手动右键续放一株 + 周期随机自然生长。
 * @date 2026-10-30
 *
 * <h2>为什么需要这个类</h2>
 * CE 的 `hanging_block` 只能表达「挂在某个方块下面」，`stackable: true` 让它允许下方再挂同类，
 * 但**它不会自己生长**，也不会响应玩家右键。所以「向下延伸」这件事必须由插件在运行期做：
 * <ul>
 *   <li><b>手动</b>：玩家空手右键藤蔓 → 下方再放一株（受 {@code vine.max-length} 限制）；</li>
 *   <li><b>自然</b>：每 {@code vine.growth-interval-ticks} tick 挑 {@code vine.growth-attempts}
 *       株藤蔓，按 {@code vine.growth-chance}（百分比）决定是否向下延伸一段。</li>
 * </ul>
 *
 * <h2>放置判定</h2>
 * 只在三个条件同时成立时延伸：
 * <ol>
 *   <li>目标格**确实是**这三个藤蔓之一（用 CE 反查 id，绝不按材质猜）；</li>
 *   <li>它下面那格是**空气**（不覆盖、不替换任何已有方块——这是「不破坏已有存档实例」的底线）；</li>
 *   <li>当前链条长度 &lt; {@code vine.max-length}，且未触及 CE 的构建期硬上限。</li>
 * </ol>
 *
 * <h2>碰撞与支撑</h2>
 * 新放的一株继承同 id 的默认 CE 状态，支撑行为由 CE 的 `hanging_block`（`blacklist: true`）
 * 保证「上方任意方块都算支撑」；因此链条中段被破坏后，CE 的 support 判定会让下方整段掉落，
 * 插件侧不再重复实现一套支撑逻辑。
 *
 * <h2>破坏时整条链的掉落与连锁移除</h2>
 * 不复用本类的逻辑，而是走已有的 {@link BlockBreakdownListener} →
 * {@link BlockBreakdownCascade}（上限 {@code MAX_DEPTH = 64}，逐层按
 * {@link BlockBreakdownTable} 的 {@code up} 方向判据级联移除并掉落）。
 * 也就是说：**破坏链条中任意一株，下方的整条链由既有级联负责移除与掉落**，
 * 本类只负责「长出来」，「掉下去」归既有链路。
 *
 * <h2>Folia 线程约束</h2>
 * 周期任务跑在 global 线程，**任何方块读写都必须转交拥有该区块的 region 线程**
 * （{@link Bukkit#getRegionScheduler()}）。手动放置走事件线程，本身就持有该区块的 region。
 */
public final class HangingVineGrowthListener implements Listener {

    /** 允许向下延伸的三个藤蔓（与 scripts/bnlib/support.py 的 HANGING_GROWING_VINES 保持一致）。 */
    private static final Set<String> VINES = Set.of(
            "betternether:eye_vine",
            "betternether:golden_vine",
            "betternether:lumabus_vine"
    );

    /** 自然生长每轮在玩家周围搜寻的半径（方块）。只影响「挑哪儿去试」，不影响链长上限。 */
    private static final int SCAN_RADIUS = 24;

    /** 每次向后搜索的竖直范围：从玩家脚下往上找这么高，覆盖常见的天花板藤蔓高度。 */
    private static final int SCAN_UP = 12;
    private static final int SCAN_DOWN = 8;

    private final Plugin plugin;
    private final Supplier<NetherConfig> config;

    /** 周期任务句柄；{@code null} 表示自然生长未启动（间隔非正或 attempts 为 0）。 */
    private @Nullable ScheduledTask growthTask;

    public HangingVineGrowthListener(Plugin plugin, Supplier<NetherConfig> config) {
        this.plugin = plugin;
        this.config = config;
    }

    /**
     * 手动向下放置：玩家空手右键藤蔓，下方再放一株。
     *
     * <p>只认 {@code RIGHT_CLICK_BLOCK} + 主手 + 空手，避免和「手持方块放置」「副手物品」抢事件；
     * 也不取消事件——右键藤蔓本身没有原版行为，取消与否都不影响玩家。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Block clicked = event.getClickedBlock();
        if (clicked == null) {
            return;
        }
        Player player = event.getPlayer();
        if (!player.isSneaking() || !player.getInventory().getItemInMainHand().getType().isAir()) {
            // 蹲下 + 空手才算「续藤」，防止误触影响正常放置/交互。
            return;
        }
        NetherConfig current = config.get();
        if (current == null) {
            return;
        }
        // 事件线程已持有该区块，可直接读写。
        extendDown(clicked, current.vineMaxLength());
    }

    /**
     * 启动周期自然生长。配置为 0 次尝试或间隔非正时不启动。
     *
     * @return 是否真的启动了任务（供调用方打日志）
     */
    public boolean start() {
        NetherConfig current = config.get();
        if (current == null) {
            return false;
        }
        if (current.vineGrowthAttempts() <= 0 || current.vineGrowthChance() <= 0) {
            return false;
        }
        long period = current.vineGrowthIntervalTicks();
        growthTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, task -> growOnce(), period, period);
        return true;
    }

    /** 停服/重载时取消任务。 */
    public void stop() {
        if (growthTask != null) {
            growthTask.cancel();
            growthTask = null;
        }
    }

    /**
     * 一轮自然生长：在随机玩家的随机高度挑若干候选格，命中则尝试向下延伸一段。
     *
     * <p>只在 global 线程做「选点」，真正的方块读写通过 region 调度转交——
     * 这是 Folia 上唯一安全的写法。
     */
    private void growOnce() {
        NetherConfig current = config.get();
        if (current == null) {
            return;
        }
        List<Player> players = List.copyOf(Bukkit.getOnlinePlayers());
        if (players.isEmpty()) {
            return;
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int attempts = Math.min(current.vineGrowthAttempts(), 64);
        int chance = current.vineGrowthChance();
        for (int i = 0; i < attempts; i++) {
            Player player = players.get(random.nextInt(players.size()));
            Location base = player.getLocation();
            World world = base.getWorld();
            if (world == null) {
                continue;
            }
            int x = base.getBlockX() + random.nextInt(-SCAN_RADIUS, SCAN_RADIUS + 1);
            int y = base.getBlockY() + random.nextInt(-SCAN_DOWN, SCAN_UP + 1);
            int z = base.getBlockZ() + random.nextInt(-SCAN_RADIUS, SCAN_RADIUS + 1);
            // 只有加载中的区块才值得调度；未加载的 region 调度会白跑。
            if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                continue;
            }
            Location target = new Location(world, x, y, z);
            Bukkit.getRegionScheduler().run(plugin, target, ignored -> {
                Block block = world.getBlockAt(x, y, z);
                if (vineId(block) == null) {
                    return;
                }
                if (random.nextInt(100) >= chance) {
                    return;
                }
                extendDown(block, current.vineMaxLength());
            });
        }
    }

    /**
     * 让 {@code from} 这一株向下延伸一格。
     *
     * <p>三个前提见类注释：是目标藤蔓、下方是空气、链长未超上限。任何一条不满足都安静返回——
     * 自然生长本来就不该每次都成功，不需要日志噪音。
     */
    private void extendDown(Block from, int maxLength) {
        String id = vineId(from);
        if (id == null) {
            return;
        }
        if (chainLengthAt(from) >= maxLength) {
            return;
        }
        Block below = from.getRelative(0, -1, 0);
        if (!below.getType().isAir()) {
            // 不覆盖任何已有方块（含水/岩浆/其它植物）——这是「不破坏存档实例」的底线。
            return;
        }
        place(id, below);
    }

    /**
     * 返回 {@code from} 所在整条链的长度（从最顶端数到最底端）。
     *
     * <p>向上找到链顶再向下数，得到的是**整条链**的长度而不是「从这一株往下还有几格」。
     * 用整条链长度对比 {@code max-length}，才能保证整条链不超过配置值。
     */
    private int chainLengthAt(Block from) {
        Block top = from;
        // 上限 64 与 BlockBreakdownCascade.MAX_DEPTH 对齐，避免病态数据下无限循环。
        for (int i = 0; i < 64; i++) {
            Block above = top.getRelative(0, 1, 0);
            if (vineId(above) == null) {
                break;
            }
            top = above;
        }
        int length = 0;
        Block cursor = top;
        String id = vineId(from);
        for (int i = 0; i < 64; i++) {
            if (!id.equals(vineId(cursor))) {
                break;
            }
            length++;
            cursor = cursor.getRelative(0, -1, 0);
        }
        return length;
    }

    /** 把 {@code id} 对应的 CE 方块放到 {@code target}。失败时安静返回（CE 未就绪/区块已卸载）。 */
    private void place(String id, Block target) {
        try {
            var definition = CraftEngineBlocks.byId(Key.of(id));
            if (definition == null) {
                return;
            }
            // checkCanSurvive=false：支撑由 CE 的 hanging_block 行为自己判，这里不重复判定；
            // playSound=false：自然生长不该每次都响放置音效。
            CraftEngineBlocks.place(target.getLocation(), definition.defaultState(), false, false);
        } catch (RuntimeException | LinkageError error) {
            // CE 缺席或接口变更：安静放弃这一次生长，绝不回落到材质猜测。
        }
    }

    /**
     * 这一格是哪个目标藤蔓的 CE id；不是（空气/原版/其它 CE 方块）返回 {@code null}。
     *
     * <p>与 {@link BlockBreakdownListener} 同样的反查方式：只认 CE 的方块状态，
     * 材质相同但 id 不同的方块一律不认。
     */
    private static @Nullable String vineId(Block block) {
        try {
            ImmutableBlockState state = CraftEngineBlocks.getCustomBlockState(block);
            if (state == null || state.isEmpty()) {
                return null;
            }
            BlockStateWrapper wrapper = state.customBlockState();
            if (wrapper == null || !wrapper.isCustom()) {
                return null;
            }
            Key key = wrapper.ownerId();
            String id = key.namespace() + ":" + key.value();
            return VINES.contains(id) ? id : null;
        } catch (RuntimeException | LinkageError error) {
            return null;
        }
    }
}
