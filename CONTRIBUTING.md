# 参与贡献（CONTRIBUTING）

本项目 Apache-2.0 开源，欢迎二次开发。开工前请先读 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)（分层、边界、扩展点）。本文只讲操作：怎么把环境立起来、怎么跑测试、必须遵守哪些边界。

## 1. 环境要求

| 用途 | 需要什么 | 不需要什么 |
|---|---|---|
| 只跑 core 算法单测（推荐入门路径） | JDK 11+（CI 用 21）、git | **不需要 Android SDK、不需要 PICO 设备/模拟器、不需要网络上的私有 Maven** |
| 构建/安装 app | Android SDK 35、`local.properties` 写 `sdk.dir=…`、PICO CLI + PICO Emulator 6.1（或真机） | —— |

Gradle 用仓库自带的 wrapper（`./gradlew`），不要自己装 Gradle。

```bash
# 最低验证路径（只要 JDK，一条命令）：
git clone https://github.com/NaIWaniubi/memory-palace && cd memory-palace
./gradlew :core:test
```

## 2. 模块边界（改动前必看）

Gradle 模块只有两个：`:core` 与 `:app`。

- **`:core` 永远不许出现 `import android.*` 或 `import com.pico.*`。** 这不是口头约定：CI（`.github/workflows/ci.yml`，job `app-static-check`）用一条 grep 静态拦截，违规直接红：

  ```bash
  if grep -rn "^import \(android\.\|com\.pico\.\)" core/src/main/java/ ; then
    echo "::error::core/ must stay framework-free (pure Kotlin/JVM)"
    exit 1
  fi
  ```

  为什么这么硬：core 是本项目的"可离线验证层"——调度算法、导入解析、空间几何、方位罗盘全部能在无头显、无 Android SDK 的机器上跑单测。破坏这条边界 = 破坏本项目对评审和后续开发者的核心承诺。
- `:app` 依赖 `:core`（方向只能是 app → core）。app 里所有 PICO SDK 调用包在 `runCatching` 里，SDK 不可用（模拟器缺能力）时必须安静降级，不许崩。
- 新增**算法/几何/解析/格式判断**逻辑 → 写进 `core`，同时补纯 JVM 单测；新增**交互/渲染/传感器** → 写进 `app` 对应包，把可测的决策部分抽成 core 纯函数（参考 `AnchorScene` 里的 `decideVerifyError`、`RoomStage` 里的 `planSceneDiff`——都是为了让决策逻辑进单测而从 Android 类里拆出来的）。

## 3. 跑测试

```bash
./gradlew :core:test              # 纯 JVM 算法单测（无需 Android SDK）
./gradlew :app:testDebugUnitTest  # app 层纯逻辑单测（JVM，无需设备/模拟器）
./gradlew :app:compileDebugKotlin # 改 app 后至少要编过
./gradlew :app:assembleDebug      # 产出可安装 APK（需 Android SDK + PICO Maven 可达）
```

改动的最低门禁：以上前两条全绿。CI 在每次 push / pull_request 自动跑 `:core:test` 与四条静态检查（core 零依赖 / LAUNCHER 入口 / stage meta-data 位置 / 广播 action 唯一）。

测试风格：JUnit4、方法名用中文描述句（如 `` `左手边的目标方位角为正 90` ``），断言围绕**契约**（符号约定、边界值、退化输入宁可 null 不产 NaN），每个测试类头部注释写清"为什么锁这几条"。

## 4. 加一个主题词库（最常见的二开）

预置牌组是 `app/src/main/assets/decks/` 下的纯文本，首次启动由 `PresetDecks.installIfFirstLaunch` 拷出并走与手动导入完全相同的管线（按 `front` 去重，重复安装不堆卡）：

- CSV：首行表头 `front,back`，UTF-8，一行一张卡（Anki 导出可直接用）。
- TXT：每行 `正面<TAB>背面`（参考 `common_sense.txt`）。
- Anki `.apkg`：支持 `collection.anki2` / `anki21`；**`anki21b`（zstd 压缩）当前未支持**，会导入失败——有需求可以从 `core/importer/ApkgExtractor.kt` 加一个 zstd 分支（zstd 解压是纯 JVM 逻辑，适合放 core 并配单测），欢迎 PR。

新增主题三步：① 词库文件放进 `decks/`（命名 `主题_语种.csv`）→ ② 加进 `PresetDecks.DECKS` 列表 → ③ 冷启动验证自动导入条数。

## 5. 代码风格

- Kotlin 官方风格，4 空格缩进；UI 一律引用 `ui/Theme.kt` 的 token（`MpColors/MpType/MpSpace`），**禁止新写颜色/字号字面量**——设计规范见 `docs/DESIGN.md`。
- 注释写"为什么"不写"是什么"；踩过的坑要在注释里留案底（全仓风格，搜 `为什么` 可感受基准）。
- 用户可见文案与"验证状态"必须一致：没在真机验过的能力，界面上不许写成功能已就绪（本项目所有文档用 ✅模拟器已实测 / ⚠️待真机 / ❌未做 三态口径，见 `docs/SCORING.md`）。

## 6. PR 约定

- 一个 PR 做一件事；描述里写：动机 → 改动的文件/类 → 测试怎么跑的（贴命令）。
- 动到 `core/spatial` 几何或 `schedule` 调度的输入输出契约时，先补/改单测再改实现。
- 空间交互类改动无法在 CI 验证是**已知事实**，PR 里如实写"模拟器实测到什么、真机待验什么"，不要求也不会接受伪造的真机证据。

## 7. 演示模式 vs 调试模式

应用默认**演示模式开**（净界面）。界面上「显示调试信息」按钮，或：

```bash
adb shell am broadcast -a com.memorypalace.dualstate.DEMO_MODE --ez enabled false  # 进调试态（探针全回）
adb shell am broadcast -a com.memorypalace.dualstate.DEMO_MODE --ez enabled true   # 回演示态（截图/录屏用这个）
```

开发/取证时截对外素材前请确认处于演示模式。
