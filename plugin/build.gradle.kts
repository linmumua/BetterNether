import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.bundling.Jar
import org.gradle.language.jvm.tasks.ProcessResources

plugins {
    id("com.gradleup.shadow")
    `maven-publish`
}

// 依赖的实际 jar（仅编译期；运行期由服务端 / CraftEngine 插件提供）。
val mucoreApi = rootProject.file(providers.gradleProperty("mucoreApiJar").get())
val craftEngine = rootProject.file(providers.gradleProperty("craftEngineJar").get())
val snakeyamlVersion = providers.gradleProperty("snakeyamlVersion").get()
val pythonExecutable = providers.gradleProperty("pythonExecutable").get()

// 上游 BetterNether（只读）与资源迁移脚本（本工程负责，迁移器只读上游、只写 build/generated）。
val bnUpstream = rootProject.file(providers.gradleProperty("bnUpstreamDir").get())
val extractScript = rootProject.file("scripts/extract_bn.py")
// 生成目录内容与 jar 内路径一一对应：
//   datapack/**、datapacks/betternether-structures/**、craftengine/betternether/**。
val bnGeneratedDir = layout.buildDirectory.dir("generated/bn")

dependencies {
    // 运行期随产物打包的自家模块。
    implementation(project(":common"))
    // 仅编译期：Paper/Bukkit API 与 CraftEngine 由服务端提供，绝不打进产物。
    compileOnly(files(mucoreApi))
    compileOnly(files(craftEngine))
    compileOnly("org.jetbrains:annotations:26.1.0")
    // 随产物打包并重定位（见下方 shadowJar）；不依赖服务端内置的 org.yaml。
    implementation("org.yaml:snakeyaml:$snakeyamlVersion")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

// ---------------------------------------------------------------------------
// BetterNether 资源迁移：从只读上游生成适配后的数据包与 CraftEngine 资源。
// 产物约定：
//   <out>/datapack/{pack.mcmeta,data/betternether/worldgen/**}
//   <out>/datapacks/betternether-structures/{pack.mcmeta,data/**}
//   <out>/craftengine/betternether/{pack.yml,configuration/**,resourcepack/**}
// ---------------------------------------------------------------------------
val extractBnResources = tasks.register<Exec>("extractBnResources") {
    group = "build"
    description = "调用 scripts/extract_bn.py，从上游 BetterNether 生成适配后的数据包与 CE 资源。"

    inputs.files(extractScript).optional().withPropertyName("extractScript")
    inputs.dir(rootProject.file("scripts/bnlib")).optional().withPropertyName("bnLibDir")
    inputs.dir(bnUpstream.resolve("src/main/generated")).optional().withPropertyName("bnGenerated")
    inputs.dir(bnUpstream.resolve("src/main/resources")).optional().withPropertyName("bnResources")
    outputs.dir(bnGeneratedDir)

    doFirst {
        check(bnUpstream.isDirectory) { "缺少上游目录：${bnUpstream.absolutePath}" }
        check(extractScript.isFile) { "缺少迁移脚本：${extractScript.absolutePath}" }
    }

    commandLine(
        pythonExecutable,
        extractScript.absolutePath,
        "--upstream", bnUpstream.absolutePath,
        "--output", bnGeneratedDir.get().asFile.absolutePath
    )
}

// 生成目录内容直接并入主资源：其内部已带 datapack/ 与 craftengine/ 前缀，落点即 jar 内路径。
tasks.named<ProcessResources>("processResources") {
    dependsOn(extractBnResources)
    // 结构 NBT 以 src 为准：src 是完整的 67 个片段（含后来迁移的 9 个结构），生成目录只有早期
    // city 的 36 个。排除必须只作用于生成目录——写成全局 exclude 会连 src 的副本一起剔掉，
    // 结果是 jar 里一个 .nbt 都没有，所有结构引用全部悬空。
    from(bnGeneratedDir) {
        exclude("datapacks/**/structure/**/*.nbt")
    }
    // 仅重写输出副本：移除自定义台阶/楼梯后，用原版同形块保留建筑几何和状态。
    inputs.files(
        rootProject.file("scripts/prepare_structure_resources.py"),
        rootProject.file("scripts/bnlib/publication.py"),
        rootProject.file("scripts/bnlib/public_structures.py"),
        rootProject.file("scripts/bnlib/structures.py"),
        rootProject.file("scripts/bnlib/nbt.py")
    )
    doLast {
        providers.exec {
            commandLine(
                pythonExecutable,
                rootProject.file("scripts/prepare_structure_resources.py").absolutePath,
                "--root", destinationDir.absolutePath
            )
        }.result.get().assertNormalExitValue()
    }
    // 构建期报告（方块清单等）不是运行期资源，不进 jar。
    exclude("reports/**")
    // 结构包的迁移说明是给维护者看的，不是数据包内容。
    exclude("datapacks/betternether-structures/README-*.md")
    // 许可证以仓库根文件为准（见 shadowJar）；资源目录内的旧副本不参与打包，避免同名冲突。
    exclude("LICENSE", "LICENSE.ASSETS", "THIRD-PARTY-NOTICES")
    // 只对两个描述符做版本展开：CE 配置里满是 ${...} 占位符，不能被模板引擎处理。
    val pluginVersion = project.version.toString()
    inputs.property("version", pluginVersion)
    filesMatching(listOf("paper-plugin.yml", "plugin.yml")) {
        expand("version" to pluginVersion)
    }
}

// 资源契约测试：把生成目录并入测试资源，测试可直接以 classpath 读取。
tasks.named<ProcessResources>("processTestResources") {
    from(bnGeneratedDir)
    dependsOn(extractBnResources)
}

// 禁用普通（thin）jar：部署只允许 Shadow 产物。
// 若保留本任务，它会产出与 shadowJar 同名同版本的瘦包（仅一个在 build/libs、
// 一个在 jars/），极易被误部署。:common:jar 不受影响，继续供 shadow 解析工程依赖。
tasks.named<Jar>("jar") {
    enabled = false
}

// ---------------------------------------------------------------------------
// 最终产物：jars/BetterNether-<version>.jar
// 只叠加自家代码与运行期库（SnakeYAML 被重定位）；CE / API 一律排除。
// ---------------------------------------------------------------------------
val shadowJarTask = tasks.named<ShadowJar>("shadowJar") {
    archiveBaseName.set("BetterNether")
    archiveVersion.set(project.version.toString())
    archiveClassifier.set("")
    destinationDirectory.set(rootProject.layout.projectDirectory.dir("jars"))

    manifest {
        attributes(
            "Implementation-Title" to "BetterNether",
            "Implementation-Version" to project.version.toString()
        )
    }

    // 只打包运行期类路径；compileOnly 的服务端 API / CraftEngine 不在其中。
    configurations = listOf(project.configurations.runtimeClasspath.get())

    // 重定位 SnakeYAML，避免与服务端内置 org.yaml 及其它插件冲突。
    relocate("org.yaml.snakeyaml", "dev.mumu.betternether.libs.yaml")

    // 防回退护栏：即便那些类误入类路径，也绝不打进产物。
    exclude("org/bukkit/**", "org/spigotmc/**")
    exclude("io/papermc/**")
    exclude("net/momirealms/**", "org/momirealms/**")
    exclude("com/fren_gor/**", "net/kyori/**")

    // 仓库根许可证与第三方声明。
    from(rootProject.file("LICENSE"))
    from(rootProject.file("LICENSE.ASSETS"))
    from(rootProject.file("THIRD-PARTY-NOTICES"))

    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

// ---------------------------------------------------------------------------
// 产物校验：内容 / 重定位 / 描述符版本 / 许可证。由 tools/verify_jar.py 执行。
// 位于构建与发布的关键路径上：assemble / build / PublishToMavenRepository 均依赖它。
// 它只依赖 shadowJar，不反向依赖构建，无循环。
// ---------------------------------------------------------------------------
val verifyScript = rootProject.file("tools/verify_jar.py")
val shadowJarFile = shadowJarTask.flatMap { it.archiveFile }
tasks.register<Exec>("verifyJar") {
    group = "verification"
    description = "校验 Shadow 产物：数据包 / CE 资源 / SnakeYAML 重定位 / 描述符版本 / 许可证。"
    dependsOn(shadowJarTask)
    inputs.file(verifyScript).optional()
    inputs.file(shadowJarFile)
    doFirst {
        commandLine(
            pythonExecutable,
            verifyScript.absolutePath,
            shadowJarFile.get().asFile.absolutePath
        )
    }
}

tasks.named("assemble") {
    dependsOn("verifyJar")
}
tasks.named("build") {
    dependsOn("verifyJar")
}

// ---------------------------------------------------------------------------
// 结构包门禁：模板引用必须闭合、NBT 必须能解压解析、tag 里的群系 id 必须都存在。
// 这三条任何一条不满足，服务器会在 worldgen 注册表解析阶段中止，而不是报错后继续。
//
// 校验目标是**最终 jar**，不是源目录：NBT 片段由 extractBnResources 从上游生成（命名空间在那里
// 被改写），源目录里只有 JSON 定义。拿源目录去核对模板引用，会把「生成产物还没合并进来」
// 误报成「模板缺失」。
// ---------------------------------------------------------------------------
val verifyStructuresScript = rootProject.file("tools/verify_structures.py")
tasks.register<Exec>("verifyStructures") {
    group = "verification"
    description = "校验结构包：模板引用闭合 / NBT 可解析 / 群系 tag 无悬空 id。"
    dependsOn(shadowJarTask)
    inputs.file(verifyStructuresScript).optional()
    inputs.file(shadowJarFile)
    doFirst {
        commandLine(
            pythonExecutable,
            verifyStructuresScript.absolutePath,
            shadowJarFile.get().asFile.absolutePath
        )
    }
}

tasks.named("verifyJar") {
    dependsOn("verifyStructures")
}

// ---------------------------------------------------------------------------
// 资源包自洽性门禁：CE 配置引用的贴图必须存在、模型引用的贴图必须存在、模型内不得有循环纹理、
// 每个方块必须有可渲染外观。这四条对应三起真实事故（缺贴图刷满日志、`#particle -> #particle`
// 自引用、"无法生成模型因为文件已存在"）。verify_jar 会复用同一份实现，不重复校验。
// ---------------------------------------------------------------------------
val verifyAssetsScript = rootProject.file("tools/verify_assets.py")
tasks.register<Exec>("verifyAssets") {
    group = "verification"
    description = "校验资源包自洽性：贴图引用闭合 / 无循环纹理 / 方块都有外观。"
    dependsOn(shadowJarTask)
    inputs.file(verifyAssetsScript).optional()
    inputs.file(shadowJarFile)
    doFirst {
        check(verifyAssetsScript.isFile) { "缺少资源包门禁脚本：${verifyAssetsScript.absolutePath}" }
        commandLine(
            pythonExecutable,
            verifyAssetsScript.absolutePath,
            shadowJarFile.get().asFile.absolutePath
        )
    }
}

tasks.named("verifyJar") {
    dependsOn("verifyAssets")
}

// ---------------------------------------------------------------------------
// 群系覆盖门禁：NetherBiomes.ALL ↔ BiomeSurface ↔ FloraPopulator 三张表必须闭合。
//
// 这条不依赖 jar，读的是 Java 源码里的数据表，所以不挂 shadowJar —— 它可以在整条
// 构建链最早的位置失败，省掉一次 2 分钟打包。漏一个群系的地表/植被定义在游戏里
// 表现为「这个群系本来就这样」，只有表比对能稳定抓住。
//
// verify_jar.py 也会调用同一份脚本（import 后直接跑 main），保证「只跑 verifyJar」
// 的路径同样覆盖这条，不依赖 Gradle 任务图是否被裁剪。
// ---------------------------------------------------------------------------
val verifyBiomeCoverageScript = rootProject.file("tools/verify_biome_coverage.py")
tasks.register<Exec>("verifyBiomeCoverage") {
    group = "verification"
    description = "校验群系覆盖闭合：每个群系都有地表与植被定义，且没有孤儿条目。"
    inputs.file(verifyBiomeCoverageScript).optional()
    inputs.dir(rootProject.file("common/src/main/java/dev/mumu/betternether/biome")).optional()
        .withPropertyName("biomeSources")
    inputs.file(rootProject.file("common/src/main/java/dev/mumu/betternether/terrain/FloraPopulator.java"))
        .optional().withPropertyName("floraPopulator")
    doFirst {
        check(verifyBiomeCoverageScript.isFile) {
            "缺少群系覆盖门禁脚本：${verifyBiomeCoverageScript.absolutePath}"
        }
        commandLine(pythonExecutable, verifyBiomeCoverageScript.absolutePath)
    }
}

tasks.named("verifyJar") {
    dependsOn("verifyBiomeCoverage")
}

// ---------------------------------------------------------------------------
// 生成方块契约门禁：挖掘门槛 / 方向覆盖 / 藤蔓可爬 / 删除引用 / 透明实心外观 /
// 植物支撑。校验目标是 `extractBnResources` 的**产物**（CE 配置 + 资源包 + 数据包），
// 不是源目录：这些事实只有产物里才有（settings 合并后的门槛、variants 覆盖、贴图 alpha、
// 仍然指向已移除 id 的引用）。
//
// 与 verifyAssets/verifyStructures 的分工：那两个读最终 jar，管「引用闭合」；
// 这一条读生成目录，管「语义对不对」，所以可以更早失败、且不开 shadowJar。
//
// 植物支撑目前没有真值来源（上游 canSurvive 没导出成数据、bnlib/support.py 未实现），
// 脚本会明确报 `[UNIMPLEMENTED] plant_support.contract` 并以退出码 2 失败 ——
// 只打印警告就当成通过，等同于把未验证的规则伪装成已验证。确需在本地绕开时用
// `-PallowUnimplementedBlockContracts=true`，别把它写进默认构建。
// ---------------------------------------------------------------------------
val verifyBlockContractsScript = rootProject.file("tools/verify_block_contracts.py")
tasks.register<Exec>("verifyBlockContracts") {
    group = "verification"
    description = "校验生成的方块配置：挖掘门槛 / 方向覆盖 / 藤蔓可爬 / 删除引用 / 透明实心外观 / 植物支撑。"
    dependsOn(extractBnResources)
    inputs.file(verifyBlockContractsScript).optional().withPropertyName("verifyBlockContractsScript")
    inputs.dir(bnGeneratedDir).withPropertyName("generatedResources")
    inputs.dir(rootProject.file("scripts/bnlib")).optional().withPropertyName("bnLibDir")
    inputs.dir(bnUpstream.resolve("src/main/generated")).optional().withPropertyName("bnGenerated")
    doFirst {
        check(verifyBlockContractsScript.isFile) {
            "缺少方块契约门禁脚本：${verifyBlockContractsScript.absolutePath}"
        }
        val arguments = mutableListOf(
            pythonExecutable,
            verifyBlockContractsScript.absolutePath,
            "--generated", bnGeneratedDir.get().asFile.absolutePath,
            "--upstream", bnUpstream.absolutePath
        )
        if (providers.gradleProperty("allowUnimplementedBlockContracts")
                .getOrElse("false").toBoolean()) {
            arguments += "--allow-unimplemented"
        }
        commandLine(arguments)
    }
}

tasks.named("verifyJar") {
    dependsOn("verifyBlockContracts")
}

// ---------------------------------------------------------------------------
// 发布：只把最终 Shadow 产物发到 C:/PluginLibs/Maven。
// ---------------------------------------------------------------------------
publishing {
    publications {
        create<MavenPublication>("betternether") {
            groupId = project.group.toString()
            artifactId = "BetterNether"
            version = project.version.toString()
            artifact(shadowJarTask)
            pom {
                name.set("BetterNether")
                description.set(
                    "Server-side BetterNether for Paper/Folia 26.3, powered by CraftEngine. " +
                        "The adaptation (dev.mumu.betternether) is MIT; the upstream BetterNether " +
                        "mod by Team BetterX is MIT, and its assets are covered by LICENSE.ASSETS. " +
                        "See LICENSE, LICENSE.ASSETS and THIRD-PARTY-NOTICES."
                )
                licenses {
                    license {
                        name.set("MIT (this adaptation and the upstream BetterNether code)")
                        url.set("https://opensource.org/license/mit")
                    }
                }
                developers {
                    developer {
                        id.set("linmumua")
                        name.set("linmumua")
                    }
                }
            }
        }
    }
    repositories {
        maven {
            name = "pluginLibs"
            url = uri("C:/PluginLibs/Maven")
        }
    }
}

// 发布路径同样先过产物校验：只有通过 verifyJar 的 Shadow 产物才会被发布。
tasks.withType<org.gradle.api.publish.maven.tasks.PublishToMavenRepository>().configureEach {
    dependsOn("verifyJar")
}
