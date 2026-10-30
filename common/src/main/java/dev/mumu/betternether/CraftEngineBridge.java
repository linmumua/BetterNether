package dev.mumu.betternether;

import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Bridges to CraftEngine: finds it, hands it our bundled resource pack, and reports whether the
 * blocks the world cannot open without are actually registered.
 *
 * <h2>Why the pack must be released by us</h2>
 * CraftEngine only reads what is under {@code plugins/CraftEngine/resources/<pack>/}. Our pack lives
 * inside the plugin jar, so somebody has to write it out. Doing it by hand does not survive contact
 * with reality: CraftEngine cleans its own resource tree between runs (observed — a hand-copied
 * {@code betternether/} folder was gone on the next boot), and every plugin update would silently
 * leave the server on an old pack.
 *
 * <p>So the plugin releases it itself, on every load <em>and</em> on {@code /betternether reload}.
 * The rules are {@link PackInstaller}'s: first install is atomic, an existing folder is only
 * <em>completed</em> with missing files, and a file the owner edited is never overwritten. That means
 * "auto-release when missing" and "do not clobber my edits" are both satisfied, which is what an
 * operator actually wants.
 */
public final class CraftEngineBridge {
    /** Our pack's directory name under CraftEngine's {@code resources/}. Also its id. */
    public static final String PACK = "betternether";
    /** Where the pack lives inside the plugin jar. */
    private static final String RESOURCE_ROOT = "craftengine/" + PACK;
    /** Build-time file list, written by {@code scripts/extract_bn.py}. Sits beside the pack, not in it. */
    private static final String MANIFEST = "craftengine/" + PACK + ".manifest.txt";

    private final JavaPlugin plugin;

    public CraftEngineBridge(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** The CraftEngine plugin, or {@code null} when it is absent or disabled. */
    public Plugin plugin() {
        Plugin craftEngine = plugin.getServer().getPluginManager().getPlugin("CraftEngine");
        if (craftEngine == null || !craftEngine.isEnabled()) {
            return null;
        }
        return craftEngine;
    }

    public boolean available() {
        return plugin() != null;
    }

    /**
     * One release pass over the bundled pack.
     *
     * @param firstInstall  true when the pack directory did not exist before this pass
     * @param released      files written
     * @param skipped       files already present and left untouched
     * @param failed        files that could not be written
     */
    public record PackReport(String pack, boolean firstInstall, int released, int skipped, int failed) {
        /** Whether this pass changed anything on disk. */
        public boolean changed() {
            return released > 0;
        }
    }

    /**
     * Writes the bundled pack into CraftEngine's resource tree.
     *
     * <p>An existing folder is always completed with the files it is missing -- that is the half of
     * "auto-release when missing" that repairs a deleted or partial pack. Files already present are
     * never overwritten, so an operator's edits survive.
     *
     * @return the report, never null
     * @throws IOException when CraftEngine is absent, the pack is not bundled in this build, or any
     *                     file failed -- the caller must treat all of these as "not installed"
     */
    public PackReport installBundledPack() throws IOException {
        Plugin craftEngine = plugin();
        if (craftEngine == null) {
            throw new IOException("CraftEngine 未安装或未启用");
        }
        Path resources = craftEngine.getDataFolder().toPath().resolve("resources");

        Map<String, String> entries = enumerate();
        if (entries.isEmpty()) {
            throw new IOException("本构建未内置 CraftEngine 资源包 '" + RESOURCE_ROOT
                    + "'；插件无法提供方块，拒绝启用。");
        }

        PackInstaller.Source source = new JarPackSource(entries);
        PackInstaller.Result result = new PackInstaller(PACK, plugin.getLogger())
                .install(resources.resolve(PACK), source);

        if (result.failed() > 0) {
            throw new IOException("CraftEngine 资源包 '" + PACK + "' 安装失败（" + result.failed()
                    + " 个文件），拒绝以不完整的资源包启用。");
        }
        return new PackReport(PACK, result.firstInstall(), result.released(),
                result.skipped(), result.failed());
    }

    /**
     * Whether the pack's own descriptor is present inside this build.
     *
     * <p>Checked before anything is written: a jar built without the pack must fail loudly at
     * startup, not produce an empty {@code resources/betternether/} that CraftEngine then reports as
     * an empty pack.
     */
    public boolean bundled() {
        return getClass().getClassLoader().getResource(RESOURCE_ROOT + "/pack.yml") != null;
    }

    /** Lists every bundled file as {@code relative path -> jar resource name}. */
    private Map<String, String> enumerate() {
        // The jar carries no reliable directory listing (shadowJar omits directory entries), so the
        // file set comes from a manifest written at build time by scripts/extract_bn.py.
        //
        // Traversing the jar instead would look correct and fail silently: getResource() on a
        // directory returns null in a shadowJar, the walk yields nothing, and the release produces a
        // pack containing only pack.yml. CraftEngine then reports the pack as loaded while every block
        // is missing -- the worst shape of failure, because the log says it worked.
        Map<String, String> entries = new LinkedHashMap<>();
        java.net.URL url = getClass().getClassLoader().getResource(MANIFEST);
        if (url == null) {
            plugin.getLogger().severe("插件 jar 内找不到资源清单 " + MANIFEST
                    + "；无法释放 CraftEngine 资源包。这通常意味着构建不完整。");
            return entries;
        }
        List<String> lines;
        try (InputStream stream = url.openStream()) {
            lines = new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).lines()
                    // strip() 是防御性的：清单必须用 '\n' 写，但构建机若把换行转成 CRLF，每一行
                    // 都会带一个 '\r'，`getResource("configuration/x.yml\r")` 返回 null，释放时
                    // 会把它当成缺失文件。这个错误在 Linux 构建时不出现，只在 Windows 上炸。
                    .map(String::strip)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .toList();
        } catch (IOException error) {
            plugin.getLogger().log(java.util.logging.Level.SEVERE,
                    "读取内置资源清单 " + MANIFEST + " 失败。", error);
            return entries;
        }
        for (String relative : lines) {
            entries.put(relative, RESOURCE_ROOT + "/" + relative);
        }
        return entries;
    }

    /** The bundled bytes, opened by full jar resource name. */
    private final class JarPackSource implements PackInstaller.Source {
        private final Map<String, String> entries;

        JarPackSource(Map<String, String> entries) {
            this.entries = entries;
        }

        @Override
        public Set<String> paths() {
            return entries.keySet();
        }

        @Override
        public InputStream open(String relativePath) throws IOException {
            String name = entries.get(relativePath);
            if (name == null) {
                throw new IOException("没有这个内置资源：" + relativePath);
            }
            InputStream stream = getClass().getClassLoader().getResourceAsStream(name);
            if (stream == null) {
                throw new IOException("读不到内置资源：" + name);
            }
            return stream;
        }
    }
}
