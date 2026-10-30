// 依赖的实际 jar（仅编译期；运行期由服务端 / CraftEngine 插件提供）。
val mucoreApi = rootProject.file(providers.gradleProperty("mucoreApiJar").get())
val craftEngine = rootProject.file(providers.gradleProperty("craftEngineJar").get())
val serverJar = rootProject.file(providers.gradleProperty("serverJar").get())
val dataFixerUpperJar = rootProject.file(providers.gradleProperty("dataFixerUpperJar").get())
val commonsLangJar = rootProject.file(providers.gradleProperty("commonsLangJar").get())
val snakeyamlVersion = providers.gradleProperty("snakeyamlVersion").get()

dependencies {
    // MU核心 / Folia 26.3 API（内含 org.bukkit.* 与 io.papermc.* 的 Paper API）。
    compileOnly(files(mucoreApi))
    // CraftEngine 插件 jar（已内嵌 craft-engine-core / craft-engine-bukkit）。
    compileOnly(files(craftEngine))
    // 服务端本体（未混淆的 net.minecraft.*）。只供 MegaLavaLakePopulator 访问
    // ChunkGenerator#getBaseColumn：原版 base noise column 是巨型熔岩湖水位的唯一纯函数来源，
    // Bukkit/Paper API 没有等价物。运行期由服务端自身提供同名类，不打包、不重定位。
    compileOnly(files(serverJar))
    // 读取 serverJar 的类文件时 javac 需要这个：serverJar 只内嵌了 DFU 的一部分，而 ServerLevel
    // 等类的签名引用了 com.mojang.datafixers.util.Pair。取服务端自带的版本，不下载。
    compileOnly(files(dataFixerUpperJar))
    // NoiseBasedChunkGenerator 的列采样签名引用 MutableObject；复用服务端已有库，仅编译期。
    compileOnly(files(commonsLangJar))
    compileOnly("org.jetbrains:annotations:26.1.0")
    // 运行期 SnakeYAML 由 :plugin 打包并重定位；本模块只在编译期引用。
    compileOnly("org.yaml:snakeyaml:$snakeyamlVersion")

    // 测试类路径已由根脚本 extendsFrom(compileOnly) 继承 Bukkit / Paper API 与 SnakeYAML。
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

// 纯单元测试不启动服务端。服务端 jar 内的 PaperRegistryAccess 会覆盖 API 的无服务端实现，
// 并触发 NMS/Netty 的全局初始化；仅在编译类路径保留 NMS，测试通过 ColumnSource 注入隔离它。
tasks.withType<Test>().configureEach {
    classpath = classpath.minus(files(serverJar, dataFixerUpperJar))

    // 有四个回归直接读 :plugin 的真实产物语料（plugin/build/generated/bn/…：
    // worldgen-upstream 的清单/placed_feature 与 reports/block-manifest.json）。
    // clean build 里 :common:test 排在 :plugin:extractBnResources 之前，产物还不存在，
    // 这些测试就会走 Assumption.assumeTrue 静默 skip —— 净效果等于这几条回归没跑。
    // 用**字符串**形式声明依赖：只建立任务执行顺序，不把 :plugin 的配置/类路径投影进本模块
    // （:common 依旧不依赖 :plugin 的工程产物），产物归属不变。
    // 无环：:plugin:extractBnResources 是 Exec，只跑 scripts/extract_bn.py，输入是根工程的
    // scripts 与上游目录，自身不依赖 :common 的任何任务。这不是部署，只是测试读产物的顺序。
    dependsOn(":plugin:extractBnResources")
}
