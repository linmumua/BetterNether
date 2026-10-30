package dev.mumu.betternether;

import com.mojang.brigadier.Command;
import dev.mumu.betternether.biome.NetherBiomes;
import dev.mumu.betternether.biome.NetherDatapack;
import dev.mumu.betternether.config.ConfigStore;
import dev.mumu.betternether.config.HelpReport;
import dev.mumu.betternether.config.Messages;
import dev.mumu.betternether.config.NetherConfig;
import dev.mumu.betternether.config.NetherConfigParser;
import dev.mumu.betternether.config.ReloadCoordinator;
import dev.mumu.betternether.config.ReloadReport;
import dev.mumu.betternether.terrain.CraftEngineLookup;
import dev.mumu.betternether.terrain.NetherTerrainGenerator;
import dev.mumu.betternether.vanilla.BlockBreakdownListener;
import dev.mumu.betternether.vanilla.BlockShapeOnPlaceListener;
import dev.mumu.betternether.vanilla.HangingVineGrowthListener;
import dev.mumu.betternether.vanilla.RegionSchedulerAdapter;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

/**
 * BetterNether on Folia 26.3. The only plugin prerequisite is CraftEngine.
 * <p>
 * The Java package is {@code dev.mumu.betternether} while the resource namespace is {@code mumu}:
 * every biome, structure and block id this plugin publishes uses {@code mumu:}, so the port and the
 * original mod can coexist on one server without shadowing each other's ids.
 * <p>
 * When something is wrong -- CraftEngine absent, a bundled datapack missing, a biome that the pack
 * did not register -- the plugin does not enable. Being honest about what that means: a disabled
 * plugin (or a null generator) does NOT stop the server from generating. Paper/Folia falls back to
 * the vanilla generator for any world that named BetterNether, which bakes vanilla terrain into
 * those region files. The only safe response is to stop the server and fix the problem before any
 * such world is generated, which is exactly what every log line and the enable failure below tells
 * the operator. This class never claims to have prevented generation, because it cannot.
 * <p>
 * <b>What {@code /betternether reload} does and does not do.</b> It re-reads {@code config.yml} and
 * {@code messages.yml} and reports, key by key, what took effect. It does <b>not</b> write the
 * CraftEngine resource pack and does <b>not</b> ask CraftEngine to re-read it, and it cannot change
 * terrain that already exists -- the generator a world was created with is what that world keeps
 * using. The report says all three of those plainly rather than letting a single "reloaded" imply
 * them.
 */
public final class BetterNetherPlugin extends JavaPlugin {
    /**
     * Re-reading the configuration and text files. Checked in code with Brigadier's {@code requires},
     * so an unauthorised sender does not see the subcommand at all.
     * <p>
     * This node is <b>not</b> declared in {@code paper-plugin.yml} yet: that descriptor is outside this
     * class's write set. Until a {@code permissions:} block declares it with {@code default: op}, the
     * node still works -- operators hold it implicitly, and a permissions plugin can grant it by name --
     * but plain-vanilla permission listings will not show it and {@code permissions.default-permission}
     * never applies to it.
     */
    static final String RELOAD_PERMISSION = "betternether.command.reload";

    /** Blocks whose absence means the pack did not register; the world cannot be built without them. */
    private static final String[] CRITICAL_BLOCKS = {
            "betternether:cincinnasite_ore",
            "betternether:nether_mycelium"
    };

    /** The resolved biome mapping; null until {@link #onEnable()} verifies it. */
    private NetherBiomes.Resolved biomes;
    /**
     * Why the world generator cannot be handed out right now, or {@code null} when it can.
     *
     * <p>This exists so that a <em>recoverable</em> problem -- CraftEngine has not registered the
     * blocks yet -- degrades to "the plugin is up but refuses to generate", instead of killing the
     * plugin outright. That distinction is what breaks the deadlock described in {@link #onEnable}:
     * a plugin that will not enable can never run the release/reload that would fix it.
     */
    private volatile String generatorRefusal;
    /** Chinese MiniMessage text from messages.yml; never null, so a broken file degrades to key names. */
    private Messages messages = Messages.empty();
    /**
     * The flattened values the running server was actually BUILT from, captured once at load. Never
     * replaced by a reload: nothing a reload does can change what the live generator, populators and
     * biome registry were constructed with, so this is the honest baseline for "this change still has
     * not reached the world". Comparing against a reload-updated map instead would let a second reload
     * report "no restart needed" while the server kept generating from the old values.
     */
    private Map<String, Object> startupValues = Map.of();
    /**
     * The one reloadable triple. {@code null} means "there is no usable config" -- which is exactly
     * the condition {@link #getDefaultWorldGenerator} must refuse on, so the field that used to hold a
     * nullable config now holds this whole snapshot: a reader sees the old text+config+baseline or the
     * new one, never a mixture.
     */
    private final AtomicReference<ReloadCoordinator.Live> live = new AtomicReference<>();

    /** 悬挂藤蔓生长的周期任务持有者；onDisable 时要显式停掉。 */
    private @org.jetbrains.annotations.Nullable HangingVineGrowthListener hangingVineGrowth;
    /** Created only after the startup gates pass; closed before platform tasks are cancelled. */
    private ReloadCoordinator reloads;

    @Override
    public void onLoad() {
        // Text first, so every later line can be a message from the file rather than a bare string.
        messages = loadMessages();
        // The datapack has already been registered by NetherDatapack during the bootstrap phase,
        // before any world existed. Nothing else belongs in onLoad.
        loadConfig();
        // The pack CANNOT be released here: `load: BEFORE` orders CraftEngine's *enable* ahead of
        // ours, but at onLoad time it is still not enabled, so bridge.available() is false and the
        // release would report "CraftEngine 未安装". The release belongs in onEnable, before the
        // readiness gate -- see there.
    }

    /**
     * Releases the bundled CraftEngine pack, reporting what happened.
     *
     * <p>The rules live in {@link CraftEngineBridge}/{@link PackInstaller}: a fresh install is atomic,
     * an existing folder is only completed with missing files, and a file the operator edited is
     * never overwritten. So this is safe to call on every boot and on every reload -- it repairs a
     * deleted or half-written pack and leaves hand edits alone.
     *
     * @param occasion a short label for the log line ("启动", "重载")
     * @return the report, or {@code null} when the pack could not be released at all
     */
    private CraftEngineBridge.PackReport installPack(String occasion) {
        CraftEngineBridge bridge = new CraftEngineBridge(this);
        if (!bridge.available()) {
            getLogger().severe("CraftEngine 未安装或未启用，无法释放资源包。");
            return null;
        }
        try {
            CraftEngineBridge.PackReport report = bridge.installBundledPack();
            if (report.firstInstall()) {
                getComponentLogger().info(Component.text("[" + occasion + "] 已释放 CraftEngine 资源包 '"
                        + report.pack() + "'：" + report.released() + " 个文件（首次安装）。"));
            } else if (report.changed()) {
                getComponentLogger().info(Component.text("[" + occasion + "] CraftEngine 资源包 '"
                        + report.pack() + "' 已补齐缺失文件：" + report.released()
                        + " 个新增，" + report.skipped() + " 个已存在。"));
            } else if (report.skipped() > 0) {
                getComponentLogger().info(Component.text("[" + occasion + "] CraftEngine 资源包 '"
                        + report.pack() + "' 完整，" + report.skipped() + " 个文件均已存在。"));
            } else {
                getComponentLogger().warn(Component.text("[" + occasion + "] CraftEngine 资源包 '"
                        + report.pack() + "' 未写入任何文件。"));
            }
            return report;
        } catch (IOException error) {
            getLogger().log(java.util.logging.Level.SEVERE,
                    "[" + occasion + "] 释放 CraftEngine 资源包失败。", error);
            return null;
        }
    }

    /**
     * 注册「失去支撑的植物掉落」监听。
     *
     * <p>CraftEngine 没就绪时跳过：这个监听认方块靠 CE 的方块状态反查，CE 没注册包的时候它一次
     * 也不会命中，注册等于给每个破坏事件白加六个坐标查询。跳过必须留一条日志 —— 否则「为什么
     * 这个世界里的藤蔓不掉落」会在下一次排查里变成谜。
     *
     * <p>只在 enable 时注册一次：{@code /betternether reload} 之后即使 CE 才就绪，也需要重启服务器
     * 才能装上（Bukkit 的监听注册没有「重载」这个概念）。
     */
    private void registerBlockBreakdown() {
        if (generatorRefusal != null) {
            getComponentLogger().warn(Component.text(
                    "CraftEngine 未就绪，跳过「失去支撑的植物掉落」监听：破坏后不会掉落。"
                            + "修正 CE 并重启服务器后该功能才会生效。"));
            return;
        }
        BlockBreakdownListener listener = new BlockBreakdownListener(this);
        getServer().getPluginManager().registerEvents(listener, this);
        getComponentLogger().info(Component.text(
                "已注册「失去支撑的植物掉落」监听：" + listener.size() + " 个方块失去支撑时由本插件掉落。"));
    }

    /**
     * 注册「放置后按上下邻居重算形态」监听。
     *
     * <p>和上一个监听同样的前提：认方块靠 CE 的方块状态反查，CE 没就绪时一次也不会命中，
     * 所以就绪判断失败时不注册，并留一条日志（否则「为什么放下来的藤永远是同一段」会变成谜）。
     * 走 {@link BlockShapeOnPlaceListener#size()} 把条数写进日志，表是不是空的能一眼看出来。
     */
    private void registerBlockShapeOnPlace() {
        if (generatorRefusal != null) {
            getComponentLogger().warn(Component.text(
                    "CraftEngine 未就绪，跳过「放置后重算方块形态」监听：手动放置的藤/树干不会自动分段。"
                            + "修正 CE 并重启服务器后该功能才会生效。"));
            return;
        }
        BlockShapeOnPlaceListener listener = new BlockShapeOnPlaceListener(this);
        getServer().getPluginManager().registerEvents(listener, this);
        getComponentLogger().info(Component.text(
                "已注册「放置后重算方块形态」监听：" + listener.size() + " 个方块按上下邻居自动分段。"));
    }

    /**
     * 注册「悬挂藤蔓向下延伸」：周期自然生长 + 蹲下空手右键续放一株。
     *
     * <p>与上面两个监听同一前提：认方块靠 CE 反查，CE 未就绪时一次也不会命中，
     * 所以不注册并留日志。自然生长是**周期任务**，因此要显式 start/stop
     * （见 {@link HangingVineGrowthListener#start()} / {@link HangingVineGrowthListener#stop()}）。
     */
    private void registerHangingVineGrowth() {
        if (generatorRefusal != null) {
            getComponentLogger().warn(Component.text(
                    "CraftEngine 未就绪，跳过「悬挂藤蔓向下延伸」监听：藤蔓不会自然生长，"
                            + "也不能右键续放。修正 CE 并重启服务器后该功能才会生效。"));
            return;
        }
        HangingVineGrowthListener listener =
                new HangingVineGrowthListener(this, () -> {
                    ReloadCoordinator.Live current = live.get();
                    return current == null ? null : current.config();
                });
        getServer().getPluginManager().registerEvents(listener, this);
        hangingVineGrowth = listener;
        boolean started = listener.start();
        NetherConfig current = live.get() == null ? NetherConfig.DEFAULTS : live.get().config();
        getComponentLogger().info(Component.text(
                "已注册「悬挂藤蔓向下延伸」监听："
                        + (started
                        ? "自然生长已启动（周期 " + current.vineGrowthIntervalTicks()
                          + " tick，每轮 " + current.vineGrowthAttempts()
                          + " 次、几率 " + current.vineGrowthChance() + "%），"
                          + "最大长度 " + current.vineMaxLength()
                        : "自然生长未启动（vine.growth-attempts 或 vine.growth-chance 为 0）")
                        + "；蹲下空手右键藤蔓可续放一株。"));
    }

    @Override
    public void onEnable() {
        // ---------------------------------------------------------------------------------------
        // What is fatal vs what is recoverable, and why that line is drawn here.
        //
        // FATAL -- the plugin refuses to enable, because nothing it can do at runtime would fix it:
        //   * the bundled datapack never registered (needs a full restart, not a reload);
        //   * config.yml is unreadable (the operator must edit a file).
        //
        // RECOVERABLE -- the plugin STAYS ENABLED and only refuses to hand out a generator:
        //   * CraftEngine has not registered our blocks yet.
        //
        // The second case used to be fatal, and that was a deadlock: the repair path (release the
        // pack, then reload) runs from this plugin's own command, and a disabled plugin has no
        // commands. So a server that started with a missing CraftEngine pack could never recover --
        // `/betternether reload` did not exist exactly when it was needed. Measured on a live server:
        // "CraftEngine 尚未注册世界生成必需的方块" then "Disabling BetterNether", and no command.
        // Now the plugin enables, registers its command, logs what is missing, and lets the operator
        // (or the next reload) release the pack and re-check.
        // ---------------------------------------------------------------------------------------
        String refusal = NetherDatapack.refusalReason();
        if (refusal != null) {
            throw new IllegalStateException(refusal
                    + " BetterNether 不可用；Paper/Folia 可能回退原版生成器，请停止服务器并修正后再生成世界。");
        }
        if (live.get() == null) {
            throw new IllegalStateException(plain("config.invalid"));
        }

        // Release the bundled pack as early as it can possibly work (CraftEngine is enabled by now,
        // thanks to `load: BEFORE`). A missing or half-written pack is the most common reason the
        // blocks below are unregistered, so repairing it first turns a mystery into a plain start.
        installPack("启动");

        // Resolve biomes: this only depends on our own datapack, which is already registered.
        NetherBiomes.Resolution resolution = NetherBiomes.resolve();
        if (resolution instanceof NetherBiomes.Failure failure) {
            // Biomes come from the datapack, and a datapack only enters the registry at startup.
            // A reload cannot fix that, so this stays fatal.
            throw new IllegalStateException(failure.reason()
                    + " BetterNether 不可用；Paper/Folia 可能回退原版生成器，请停止服务器并修正后再生成世界。");
        }
        biomes = (NetherBiomes.Resolved) resolution;

        // CraftEngine readiness: recoverable. Record why we cannot generate, but stay enabled.
        generatorRefusal = craftEngineRefusal();
        if (generatorRefusal != null) {
            getComponentLogger().error(Component.text(generatorRefusal));
            getComponentLogger().error(texts().get("craftengine.not-ready-hint"));
            getComponentLogger().warn(Component.text(
                    "插件保持启用，但不会接管世界生成。/betternether reload 会尝试释放资源包并重新检查；"
                            + "若仍不就绪，请确认 CraftEngine 已加载 betternether 包（/ce reload 后查看日志）。"));
        }

        // 植物失去支撑时跟着掉落。CE 的支撑行为只会把方块变回空气，「掉落」在 YAML 层表达不了，
        // 这是用户明确要求、且有意偏离上游的一处（详见 BlockBreakdownListener 的类注释）。
        // 排在 CE 就绪判断之后：没就绪时连方块 id 都认不出来，注册了也只是跑一遍空循环。
        registerBlockBreakdown();
        registerBlockShapeOnPlace();
        registerHangingVineGrowth();

        // Everything a reload touches is injected: this class owns the platform, the coordinator owns
        // single-flight, publication order and the report. File I/O runs on Folia's async pool, never
        // on the region thread that dispatched the command.
        reloads = new ReloadCoordinator(startupValues, live,
                this::readConfig, this::readMessages, this::contentReady,
                () -> {
                    // Runs on the async pool, before the readiness probe: a deleted or partial pack is
                    // the most common reason the probe would say "not ready", so repair it first.
                    CraftEngineBridge.PackReport report = installPack("重载");
                    if (report == null) {
                        throw new IOException("资源包释放失败，详见控制台日志");
                    }
                    // Re-evaluate the generator blocker after the release. This is the self-healing
                    // half: with the pack now on disk, a reload that started with "no blocks" can end
                    // with "ready", and the very next world creation gets a real generator. Without
                    // this line the refusal recorded at enable would stick until a full restart,
                    // which is the same deadlock in slower motion.
                    generatorRefusal = craftEngineRefusal();
                    if (report.firstInstall()) {
                        return "首次安装 " + report.released() + " 个文件";
                    }
                    if (report.changed()) {
                        return "补齐 " + report.released() + " 个文件，已存在 " + report.skipped() + " 个";
                    }
                    return "完整，无需写入（已存在 " + report.skipped() + " 个文件）";
                },
                task -> RegionSchedulerAdapter.runAsync(this, task));

        registerCommands();

        getComponentLogger().info(texts().get("plugin.enabled"));
        getComponentLogger().info(Component.text("BetterNether："
                + biomes.declared().size() + " 个下界群系已就绪，"
                + "用 generator=BetterNether:betternether 创建 NETHER 世界。"));
    }

    @Override
    public void onDisable() {
        if (reloads != null) {
            reloads.shutdown();
        }
        // 周期任务必须显式停掉：插件禁用后它还持有 Block 引用并在 global 线程排队。
        if (hangingVineGrowth != null) {
            hangingVineGrowth.stop();
            hangingVineGrowth = null;
        }
        // Async work that is still queued must not run against a disabled plugin.
        Bukkit.getAsyncScheduler().cancelTasks(this);
        getComponentLogger().info(texts().get("plugin.disabled"));
    }

    /**
     * Registers {@code /betternether} through Paper's lifecycle API, which is the only way a
     * {@code paper-plugin.yml} plugin can own a command: {@code PaperPluginMeta} declares no
     * {@code commands} field at all (name, main, bootstrapper, loader, provides, version, description,
     * authors, contributors, website, prefix, load, api-version, folia-supported, dependencies -- that
     * is the whole surface), so a {@code commands:} block in the descriptor would be silently ignored
     * by the Configurate object mapper that reads it. {@code LifecycleEvents.COMMANDS} is documented
     * for exactly this.
     * <p>
     * Only {@code reload} is gated by {@link #RELOAD_PERMISSION}: help changes nothing, so hiding it
     * from a curious operator teaches nobody anything, while Brigadier's {@code requires} keeps an
     * unauthorised sender from even seeing {@code reload} in the tree.
     */
    private void registerCommands() {
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            Commands commands = event.registrar();
            commands.register(
                    Commands.literal("betternether")
                            .executes(context -> {
                                showHelp(context.getSource().getSender());
                                return Command.SINGLE_SUCCESS;
                            })
                            .then(Commands.literal("help").executes(context -> {
                                showHelp(context.getSource().getSender());
                                return Command.SINGLE_SUCCESS;
                            }))
                            .then(Commands.literal("reload")
                                    .requires(source -> source.getSender().hasPermission(RELOAD_PERMISSION))
                                    .executes(context -> {
                                        reload(context.getSource().getSender());
                                        return Command.SINGLE_SUCCESS;
                                    }))
                            .build(),
                    texts().raw("command.description", "BetterNether"), List.of());
        });
    }

    /**
     * Opt-in Bukkit generator; world owners select it with {@code generator=BetterNether:betternether}.
     * <p>
     * Returning {@code null} does NOT stop generation: Paper/Folia falls back to the vanilla generator
     * for this world, which will save vanilla terrain into its region files. Every branch below says so
     * plainly -- "stop the server and fix it" -- and never claims the world was refused.
     * <p>
     * The config read here is the live one, so a world created after a reload is built from the
     * freshly-read values while a world created before it keeps the generator it already has. That is
     * the whole reason the reload report lists changed world-generation keys under "restart".
     */
    @Override
    public ChunkGenerator getDefaultWorldGenerator(@NotNull String worldName, @Nullable String id) {
        ReloadCoordinator.Live current = live.get();
        if (current == null) {
            // loadConfig() already reported the cause; returning null means the vanilla generator runs.
            getLogger().severe("BetterNether 不可用：config.yml 无效。Paper/Folia 可能为世界 '" + worldName
                    + "' 回退原版生成器，请停止服务器并修正 config.yml 后再生成世界。");
            return null;
        }
        if (id != null && !id.isEmpty() && !id.equalsIgnoreCase("betternether")) {
            getLogger().severe("未知的 BetterNether 生成器 id '" + id + "'（世界 '" + worldName
                    + "'），只接受 'betternether'。Paper/Folia 可能回退原版生成器，"
                    + "请停止服务器并修正后再生成世界。");
            return null;
        }
        if (biomes == null) {
            getLogger().severe("BetterNether 未就绪：群系尚未解析（世界 '" + worldName
                    + "'）。Paper/Folia 可能回退原版生成器，请停止服务器并修正后再生成世界。");
            return null;
        }
        // A recoverable blocker (CraftEngine has not registered our blocks) recorded at enable, or
        // refreshed by the last reload. Checked here because this is the exact moment chunks can be
        // baked: handing out a generator now would write terrain that is missing every custom block.
        String blocked = generatorRefusal;
        if (blocked != null) {
            getLogger().severe("BetterNether 拒绝为世界 '" + worldName + "' 生成：" + blocked
                    + " 服务器会回退原版生成器；请修正后重新生成该世界。");
            return null;
        }
        try {
            return new NetherTerrainGenerator(current.config(), biomes, getLogger());
        } catch (IllegalStateException error) {
            getLogger().severe("BetterNether 未就绪（" + error.getMessage() + "）：世界 '" + worldName
                    + "' 可能回退原版生成器，请停止服务器并修正后再生成世界。");
            return null;
        }
    }

    /**
     * The reason CraftEngine cannot serve this plugin, as finished plain text, or {@code null} when it
     * can. One message source: the same sentence is logged (as a component) and thrown (as text), so
     * the console and the abort cannot disagree.
     */
    private @Nullable String craftEngineRefusal() {
        if (getServer().getPluginManager().getPlugin("CraftEngine") == null) {
            getComponentLogger().error(texts().get("craftengine.missing"));
            return plain("craftengine.missing");
        }
        if (!getServer().getPluginManager().isPluginEnabled("CraftEngine")) {
            getComponentLogger().error(texts().get("craftengine.missing"));
            return plain("craftengine.missing");
        }
        List<String> missing = missingContent();
        if (!missing.isEmpty()) {
            TagResolver resolver = Placeholder.unparsed("missing", String.join(", ", missing));
            getComponentLogger().error(texts().get("craftengine.not-ready", resolver));
            return plain("craftengine.not-ready", resolver);
        }
        return null;
    }

    /** The critical block ids CraftEngine has not registered, in declaration order. */
    private static List<String> missingContent() {
        List<String> missing = new ArrayList<>();
        for (String id : CRITICAL_BLOCKS) {
            if (!CraftEngineLookup.resolves(id)) {
                missing.add(id);
            }
        }
        return missing;
    }

    /** Whether the blocks the world cannot be generated without are registered right now. */
    private boolean contentReady() {
        if (!getServer().getPluginManager().isPluginEnabled("CraftEngine")) {
            return false;
        }
        return missingContent().isEmpty();
    }

    /** Starts the file work off the caller's tick thread; the coordinator owns single-flight. */
    private void reload(@NotNull CommandSender sender) {
        ReloadCoordinator coordinator = reloads;
        if (coordinator == null) {
            ReloadReport.failed(texts(), "重载服务未初始化（插件未通过启用检查）")
                    .forEach(line -> send(sender, line));
            return;
        }
        // Capture a command block's position on its owner thread, never from the async callback.
        Location source = sender instanceof BlockCommandSender block ? block.getBlock().getLocation() : null;
        coordinator.reload(lines -> reply(sender, source, lines));
    }

    /** Help is a local reply, not an administrative action to echo into the server log. */
    private void showHelp(CommandSender sender) {
        HelpReport.lines(texts()).forEach(line -> send(sender, line));
    }

    /** One line to a sender on the thread that currently owns them (the command's own thread). */
    private void send(CommandSender sender, Component line) {
        ((Audience) sender).sendMessage(line);
    }

    /**
     * Routes one finished report to the thread that owns the sender, and keeps a copy in the log so a
     * reload is auditable after the sender has logged off.
     */
    private void reply(CommandSender sender, @Nullable Location source, List<Component> lines) {
        boolean console = sender instanceof ConsoleCommandSender;
        if (!console) {
            lines.forEach(line -> getComponentLogger().info(line));
        }
        if (!isEnabled()) {
            if (console) {
                lines.forEach(line -> getComponentLogger().info(line));
            }
            return;
        }
        Runnable delivery = () -> {
            if (isEnabled()) {
                lines.forEach(line -> ((Audience) sender).sendMessage(line));
            }
        };
        try {
            if (sender instanceof Entity entity) {
                // A retired entity means the player logged off mid-reload: the report stays in the log.
                RegionSchedulerAdapter.reply(this, entity, delivery, null);
            } else if (source != null) {
                RegionSchedulerAdapter.run(this, source, delivery);
            } else {
                RegionSchedulerAdapter.replyGlobal(this, delivery);
            }
        } catch (RuntimeException refused) {
            if (console) {
                lines.forEach(line -> getComponentLogger().info(line));
            }
            getLogger().log(Level.WARNING, "重载报告无法投递，结果已保留到控制台。", refused);
        }
    }

    /** The text in force: the published snapshot once a config exists, the file's own copy before. */
    private Messages texts() {
        ReloadCoordinator.Live current = live.get();
        return current == null ? messages : current.messages();
    }

    /** A message rendered as plain text, for a log line or an exception that cannot carry formatting. */
    private String plain(String key, TagResolver... resolvers) {
        return PlainTextComponentSerializer.plainText().serialize(texts().get(key, resolvers));
    }

    /**
     * Seeds config.yml from the jar on first run, adds keys a newer build introduced without touching
     * values the owner set, and parses the result into the live snapshot.
     * <p>
     * A failure leaves {@link #live} null, which is what {@link #onEnable()} and {@link
     * #getDefaultWorldGenerator} refuse on. Nothing is published from a file that did not parse.
     */
    private void loadConfig() {
        try {
            ReloadCoordinator.ConfigRead read = readConfig();
            startupValues = frozen(read.values());
            live.set(new ReloadCoordinator.Live(messages, read.config(), startupValues));
            if (read.released()) {
                getLogger().info("已从 jar 生成默认 config.yml。");
            }
        } catch (IOException | RuntimeException error) {
            getLogger().log(Level.SEVERE, "config.yml 无法读取，BetterNether 不会启用。", error);
            live.set(null);
        }
    }

    /**
     * Reads the config file and parses it, before {@link ConfigStore} is allowed to back up or replace
     * the owner's file. Used by startup and by reload, so both paths validate identically.
     * <p>
     * A value the parser rejects is reported through {@code warn} and replaced by its default rather
     * than aborting -- that is {@link NetherConfigParser}'s contract. Those warnings are collected so
     * the reload report can say a value was silently replaced, instead of announcing a clean reload.
     */
    private ReloadCoordinator.ConfigRead readConfig() throws IOException {
        Path file = getDataFolder().toPath().resolve("config.yml");
        boolean absent = !Files.exists(file, LinkOption.NOFOLLOW_LINKS);
        List<String> problems = new ArrayList<>();
        AtomicReference<NetherConfig> parsed = new AtomicReference<>();
        ConfigStore.Loaded loaded = ConfigStore.of(file, () -> getResource("config.yml"),
                        "config.yml", getLogger())
                .load(values -> parsed.set(NetherConfigParser.parse(values::get, problem -> {
                    problems.add(problem);
                    getLogger().warning(problem);
                })));
        return new ReloadCoordinator.ConfigRead(parsed.get(), loaded.values(),
                loaded.bundledVersion(), absent, List.copyOf(problems));
    }

    /** Reads, seeds and backfills messages.yml. A failure keeps the running text in place. */
    private Messages readMessages() throws IOException {
        return new Messages(ConfigStore.of(getDataFolder().toPath().resolve("messages.yml"),
                () -> getResource("messages.yml"), "messages.yml", getLogger()).load().values());
    }

    private Messages loadMessages() {
        try {
            return readMessages();
        } catch (IOException | RuntimeException error) {
            getLogger().log(Level.WARNING, "messages.yml 无法读取，将直接以键名作为文本输出。", error);
            return Messages.empty();
        }
    }

    /** An unmodifiable snapshot that tolerates YAML null leaves. */
    private static Map<String, Object> frozen(Map<String, Object> values) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
}
