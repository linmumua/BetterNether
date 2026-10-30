# BetterNether

把 Fabric 模组 [Better Nether](https://github.com/quiqueck/BetterNether)（原 Team BetterX）的下界内容，重新实现为 **Paper / Folia 26.3 服务端插件**。

所有方块、物品、模型、配方与翻译由 [CraftEngine](https://github.com/Xiao-MoMi/craft-engine) 提供，插件本体只负责世界生成、群系与结构。

## 特性

- **下界地形与群系**：23 个下界群系，地形形状复用原版下界噪声（与上游一致），在材质层按群系铺装
- **矿物**：铌钙石矿、下界青金石矿、下界红石矿、下界红玉矿，各自的掉落与精准采集规则
- **植被**：约 170 种方块与植物，含多段植物、悬挂藤蔓、大型蘑菇、钟乳石/石笋
- **结构**：Nether City、祭坛、花园、金字塔、Ghast Hive、丛林神庙等（含原版要塞/堡垒共存）
- **原版行为对齐**：失去支撑的植物掉落、放置后按上下邻居重算方块形态、可攀爬藤蔓、方块连锁破坏

## 环境要求

| 项目 | 版本 |
|---|---|
| 服务端 | Paper 26.3 或 Folia 26.3 |
| Java | 25 及以上 |
| 必需前置 | CraftEngine（26.10+） |

## 安装

1. 把 `BetterNether-<version>.jar` 放进 `plugins/`
2. 安装并启用 **CraftEngine**
3. 启动服务端。插件首次启动会把 CraftEngine 资源包释放到
   `plugins/CraftEngine/resources/betternether/`
4. **再重启一次**。资源包释放晚于 CraftEngine 的首次扫描时，插件会打印
   「插件保持启用，但不会接管世界生成」—— 第二次启动才会真正就绪

新建下界世界时用：

```
generator=BetterNether:betternether
```

## 构建

需要 JDK 25。

```bash
./gradlew clean shadowJar
```

产物：`jars/BetterNether-<version>.jar`

### 关于 `upstream/`

本仓库**不包含**上游模组源码。构建前请自行准备：

```
upstream/BetterNether/          # 上游 BetterNether 的 src/main/{java,generated,resources}
```

路径由 `gradle.properties` 的 `bnUpstreamDir` 指定。缺少该目录时，资源生成步骤会失败并指出缺什么。

## 配置

- `plugins/BetterNether/config.yml` — 运行期配置
- `plugins/BetterNether/messages.yml` — 全部玩家可见文案（MiniMessage 格式）

两个文件都带 `config-version`：升级时只补回缺失键，**不会覆盖你改过的值**。

## 已知限制

这些是当前版本**明确未复刻**的部分，不是 bug：

- **钟乳石 / 石笋的 `size` 粗细档位**：上游有 8 档（2px 细尖 → 满格），需要占用大量 CraftEngine 视觉状态槽；当前版本只发布单档，所有段位外观相同
- **Nether City 只生成单栋建筑**：上游用自定义结构类型由 Java 算法程序化摆放道路/桥梁/楼群，本移植改为 `minecraft:jigsaw`，而拼图块内没有连接块，因此每座「城市」只落一栋孤立建筑
- **鬼火藤的位置抖动**（`offset`）：上游按方块位置做 XZ 抖动，CraftEngine 无对应能力，所有鬼火藤对齐落在方块中心
- **树叶的 `distance` / `persistent`**：上游的纯运行时属性（生长衰减计时），不参与模型，未复刻

## 许可

- 本移植（`dev.mumu.betternether`）：MIT
- 上游 BetterNether：MIT，Copyright (c) 2022 Team BetterX
- 美术资源：见 `LICENSE.ASSETS`
- 第三方组件与逐项声明：见 `THIRD-PARTY-NOTICES`

## 致谢

- [Team BetterX](https://github.com/quiqueck/BetterNether) —— 原 Better Nether 模组
- [CraftEngine](https://github.com/Xiao-MoMi/craft-engine) —— 方块/物品/模型运行时
- [PaperMC](https://papermc.io/) / [Folia](https://github.com/PaperMC/Folia) —— 服务端
