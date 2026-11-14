# 记忆宫殿 Memory Palace

> 把你的房间变成记忆宫殿 —— 单词贴在冰箱上，公式贴在书桌上。
> PICO Space Program 2026 · 最佳 PICO Spatial 空间应用赛道参赛作品 · Apache-2.0 开源

**三窗产品界面**：书房看板（1280×720：今日到期 / 时间机器 / 房间平面 / 空间方位罗盘 / 复习）+ 随身复习卡（420×560 浮窗）+ 房间层（锚定在真实家具平面上的卡片 + 空间音频）。应用冷启动默认进入**演示模式 = 净界面**，开发探针不进任何对外展示位。

> **对外截图说明（2026-11-14）**：此前的两张 README 头图是在**调试态**截的，画面里混有开发探针面板（实例号 / 事件日志 / adb 命令）——那是脚手架不是产品，已从对外展示位撤下，`media/screenshots/` 仅留作开发实测记录。重截需要设备：一键切净界面的操作清单写在 [docs/DEMO-RUNBOOK.md 附录 D](docs/DEMO-RUNBOOK.md)，有设备后按单重传。

**演示视频**：[media/demo-video.mp4](media/demo-video.mp4) —— 165 秒，14 幕 + 片头片尾。全部画面来自 PICO Emulator 实时截屏（演示模式，无调试信息），右上角常驻「模拟器录制」角标，不伪造真机画面。**没有音轨** —— 分镜里的旁白是给真人念的，不是视频配音，这一条如实标注。

## 30 秒电梯陈述

1. 它把你的房间变成记忆宫殿 —— 单词贴在冰箱上，公式贴在书桌上。
2. 它按遗忘曲线算好你什么时候该回到哪件家具前，空间会主动提醒你。
3. 这是两千年记忆术第一次从"脑中想象"变成"眼睛看得见"。

记忆宫殿是一款 PICO MR 空间记忆应用：把 Anki 式卡片**用系统级空间锚点固定在房间里的坐标上**，由 SM-2 两档调度复习，空间音频从卡片所在位置发声。

> **一句实话（2026-11-10 更新）**：平面检测 + 家具语义 + 射线取点，**已经实现了，而且模拟器上就能验**。
>
> 一次进入房间层识别出 **57 个平面**，带家具级语义（地板 / 天花板 / 墙 / 门 / 窗 / 桌面 / 柜子 / 椅子 / 床 / 窗帘）。注入射线打到「桌面」，落点 `(-4.2471, 1.104, -3.3652)`，建出的真实世界锚点落在 `y = 1.124`（命中点 + 2 cm 抬升），持久层记 `label=桌面`，卡片显示「📍 钉在桌面上」。
>
> 证据：逐平面清单与 logcat 原文见 [docs/PLANE-ANCHOR.md](docs/PLANE-ANCHOR.md) §4（模拟器实时截屏取证，非渲染图）。该链路的旧调试态截图已从 README 撤下，待有设备按演示模式重传。
>
> 还不诚实的部分写在功能矩阵里：**模拟器没有手柄**（`ControllerTrackingProvider` 返回 `DEVICE_NOT_SUPPORTED`），所以"扣扳机"这一步只能在真机验证；被验证的是除"位姿从哪来"之外的全部链路（几何判定 → 准星 → 落点 → 建锚 → 持久化）。

[![CI](https://github.com/NaIWaniubi/memory-palace/actions/workflows/ci.yml/badge.svg)](https://github.com/NaIWaniubi/memory-palace/actions/workflows/ci.yml)

## 30 秒运行指南

> 前置：已安装 [PICO CLI](https://developer.picoxr.com/document/pico-cli/) 与 PICO Emulator

```bash
# 0. 告诉 Gradle Android SDK 在哪（local.properties 不进版本库，各机路径不同）
echo "sdk.dir=/path/to/your/Android/sdk" > local.properties

pico-cli emulator start --avd Pico_Emulator_6_1   # 1. 启动 PICO 模拟器
./gradlew installDebug                            # 2. 构建并安装
pico-cli app launch                               # 3. 启动应用
```

约 12 秒后三窗出现（模拟器**热启动**实测，x86 转译；**全新安装首次因 dex 校验约 27–80 秒**；真机 arm64 原生更快）。首次启动会自动导入三套示例牌组（50 张），开箱即可复习。

**本仓库已在干净克隆下验证**（2026-11-09，同机）：`git clone` → `:core:test` 全绿（当时 68 例；**现口径 core 113 + app 24 = 137**，见「完成度证据」）→ `:app:assembleDebug` 产出 30 MB APK。克隆约 70 秒（含 44MB 演示视频）。**跨机器验证待补**。

只跑算法单测不需要 Android SDK：`./gradlew :core:test`（`core/` 是纯 JVM 模块，零 SDK 依赖）；app 层的纯逻辑单测同样无需设备：`./gradlew :app:testDebugUnitTest`。

## 演示模式 vs 调试模式

评委/用户看到的第一帧就是产品界面——**默认态 = 演示模式开**：

| | 演示模式（默认） | 调试模式 |
|---|---|---|
| 屏上内容 | 标题、到期队列、时间机器、房间平面、方位罗盘、复习卡 | 额外显示双态探针（实例号 / 计数器 / 屏上事件日志）、无头 adb 命令提示、注入模拟平面入口 |
| 定位 | 产品态：所有对外截图 / 录屏 / 现场演示 | 开发取证态：跑实测、截证据时用 |
| 进入方式 | 冷启动即是 | 面板底部小字「显示调试信息」按钮；或无头一条命令（见下） |

```bash
adb shell am broadcast -a com.memorypalace.dualstate.DEMO_MODE --ez enabled false  # 进调试态
adb shell am broadcast -a com.memorypalace.dualstate.DEMO_MODE --ez enabled true   # 回净界面（截图/录屏前确认）
```

两条纪律：① 对外素材只在演示模式下截（重截清单在 [docs/DEMO-RUNBOOK.md 附录 D](docs/DEMO-RUNBOOK.md)）；② 切回演示模式**不影响实测证据链**——双态探针照常写 logcat，屏上只是不显示。为什么这样分：探针面板是验证工具不是产品界面，混在首帧里"像未完成的脚手架"（`DemoMode.kt` 注释原话）。

## 功能矩阵

> 状态口径与 [docs/SCORING.md](docs/SCORING.md) 一致：**✅ 表示模拟器上已实测通过**，不代表真机可用。凡涉及空间交互与声场的，一律 ⚠️。

| 功能 | 状态 | 证据 |
|---|---|---|
| 三窗架构（书房看板 / 随身卡 / 房间层） | ✅ 模拟器已实测 | [双态结论](docs/DUAL-STATE-VERIFICATION.md) |
| 空间锚定：系统锚点创建 + 杀进程后按 UUID 重新解析 | ✅ 模拟器已实测（**是「UUID 可重解析」，不是空间精度**，见下注） | [锚定结论](docs/ANCHOR-M2-TEST.md) |
| **平面检测 + 家具语义** | ✅ 模拟器已实测：57 个平面，含地板/天花板/墙/门/窗/桌面/柜子/椅子/床/窗帘 | [平面锚定结论](docs/PLANE-ANCHOR.md) |
| **射线取点 → 任意位置放置** | ✅ 模拟器已实测（注入射线）：命中点 → 真实世界锚点，落点误差 0，抬升 2 cm | [平面锚定结论](docs/PLANE-ANCHOR.md) |
| **手柄扳机 / 手势捏合确认** | ⚠️ 模拟器 `ControllerTrackingProvider = DEVICE_NOT_SUPPORTED`，扳机这一下只能真机验 | [平面锚定结论](docs/PLANE-ANCHOR.md) |
| SM-2 两档调度（1 天 → 6 天 → ×EF） | ✅ 已实现，137 例单测全绿（core 113 + app 24） | [算法说明](docs/ALGORITHM.md) |
| 看得见的遗忘（卡片随逾期褪色） | ✅ 模拟器已实测 | [时间机器结论](docs/M6-TIME-MACHINE-TEST.md) |
| 复习流（队列 → 翻面 → 自评 → 调度更新） | ✅ 模拟器已实测 | [复习流结论](docs/M4-REVIEW-FLOW-TEST.md) |
| CSV / TXT / Anki apkg（anki2 / anki21）导入 + 字段映射确认页 | ✅ 模拟器已实测（**anki21b/zstd 未支持**） | [导入管线](docs/M5-IMPORT-PIPELINE-TEST.md) · [确认页](docs/M8-IMPORT-CONFIRM-TEST.md) |
| 三套预置示例牌组（语言 / 专业术语 / 生活常识） | ✅ 模拟器已实测 | [预置牌组](docs/M7-PRESET-DECKS-TEST.md) |
| 空间音频：从锚点实体位置播放提示音 | ⚠️ **只验到「资源加载 + play() 调用成功」**；声场方位、距离衰减未经听感验证 | [实测结论](docs/M12-M14-TEST.md) |
| 空间方位可视化（逻辑方位罗盘：目标卡「左前 35° · 2.1m」+ 3×3 刻度） | ✅ 方位/俯仰/距离计算 19 例纯 JVM 单测全绿；屏上渲染待模拟器截图（本轮无设备） | [BearingTest](core/src/test/java/com/memorypalace/core/spatial/BearingTest.kt) · [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) |
| 无手柄放置兜底（面板「在这里放下」/「钉入房间」/「模拟射线瞄准」） | ✅ 链路经无头通道实测（注入射线→命中→建锚）；头显内按钮点击本身按全仓口径列真机待验 | [平面锚定结论](docs/PLANE-ANCHOR.md) |
| 头显本地到期提醒 | ⚠️ 通知可发出、闹钟可调度；**弹窗呈现与精确闹钟在 PICO OS 未授予**（已降级为不精确闹钟） | [实测结论](docs/M12-M14-TEST.md) |
| 手势 + 手柄双路径交互 | ⚠️ 手柄路径已通，手势待真机 | 模拟器无法驱动空间按钮 |

**关于「恢复漂移 0.0m」的准确含义**：模拟器里 `createAnchor` 拿到的位姿会被原样回显，所以 `saved` 与 `resolved` 相等是**恒等式**，只能证明「锚点 UUID 能被重新解析、记录没丢」，**不能证明空间精度**。空间精度需要真机上用物理参照物测量，尚未做。见 [ARITHMETIC 说明](docs/ANCHOR-M2-TEST.md#边界条件drift-00m-的成立范围2026-11-09-补充)。

**为什么有 ⚠️ / ❌**：模拟器官方明示无法驱动 3D 空间按钮，空间交互与声场只能在真机验证。本项目坚持不伪造真机画面 —— 所有模拟器截图都带角标标注。

## 架构

双态架构：**Shared Space** 跑 WindowContainer 多窗口管理（书房看板 + 随身卡），**Full Space（Stage）** 跑空间锚定复习。

```
┌──────────────────────── PICO OS 6 ────────────────────────┐
│ Shared Space                  Full Space (Stage)           │
│ ┌────────────┐ ┌──────────┐   ┌────────────────────────┐   │
│ │ 书房窗口     │ │ 随身卡    │   │ 房间层：锚定卡片 +       │   │
│ │ (1280×720)  │ │ (420×560)│   │ 空间音频 + 手柄/手势     │   │
│ └─────┬──────┘ └────┬─────┘   └───────────┬────────────┘   │
│       └─────────────┴─────────────────────┘                │
│   :app —— Android/PICO 层（ui · anchor · spatial · audio · │
│            data · notify · review · probe，均为 app 内包）  │
│                        │ 依赖方向单向：app → core           │
│   :core —— 纯 JVM：模型 · SM-2 调度 · 导入解析 ·            │
│            平面几何 · 逻辑方位罗盘（零 SDK、零 android.*）   │
└────────────────────────────────────────────────────────────┘
```

Gradle 模块**只有 `:app` 与 `:core` 两个**（`settings.gradle.kts` 为准）；data / spatial / audio / notify 是 app 内的包，不是独立模块——早版图里把它们画成平级层是"目录是设想"的误导，已按实际结构重画（原先冒充模块的 5 个顶层空壳目录已删除）。

**关键约束**：`core/` 模块不 import 任何 PICO SDK，也不 import `android.*` —— 调度算法与导入管线是纯 JVM，可离线单测，CI 里跑（core 113 例）。这条约束由 CI 静态检查强制（`.github/workflows/ci.yml`）。二开者怎么在边界内扩展，见 [CONTRIBUTING.md](CONTRIBUTING.md) 与 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)。

### 目录

| 路径 | 内容 |
|---|---|
| `core/` | 纯 JVM：卡片模型、SM-2 调度器、记忆强度、CSV/TXT/apkg 解析、平面几何、逻辑方位罗盘 |
| `app/` | Android/PICO 层：三窗 UI、锚定场景、复习会话、导入服务、Room 持久化、空间音频、提醒（data/spatial/audio/notify 为其中包） |
| `docs/` | 实测结论、算法、设计规范、架构与二开指引、评分口径、竞品调研 |
| `CONTRIBUTING.md` | 环境要求、怎么跑测试、模块边界、加词库/加导入器的操作步骤 |
| `docs/ARCHITECTURE.md` | 分层图、关键类与数据流、扩展点速查表 |
| `scripts/gen_audio.py` | 生成空间提示音资产（可复现） |

## 调度算法

SM-2 改进版为主路径（**可解释、可单测**），FSRS 保留演进位。

| 自评 | 间隔 | EF 变化 |
|---|---|---|
| 忘了 | 10 分钟后重学，`repetitions` 清零 | 下调（公式值，下限 1.3） |
| 记住了 | 1 天 → 6 天 → 之后 × EF | 不变 |

遗忘程度直接驱动卡片外观：**逾期时长 ÷ 当前间隔 = 褪色程度**，逾期越久卡片越暗、标签从"记得牢"变成"正在遗忘"再变成"快忘了"。这就是"看得见的遗忘曲线"。

详见 [docs/ALGORITHM.md](docs/ALGORITHM.md)。

## 空间音频

提示音不是屏幕提示，是**从卡片所在位置发出来的空间音源**：三个语义音（到期 / 放置确认 / 复习完成）挂在锚定卡片实体上，由 PICO 空间音频管线按实体世界坐标做空间化渲染。

这样"走到冰箱前声音在左边"和"卡片贴在冰箱上"用的是同一套坐标系 —— 方位感来自真实锚点位置，不是手写的声像 pan。

模拟器没有空间音频输出路径，听感只能等真机。为了让"按方位发声"**今天就有可核查的证据**，书房面板增加了**逻辑方位罗盘**：把同一份锚点坐标经 `core/spatial/BearingGuide`（纯 JVM，19 例单测锁死符号约定与八方位分桶）算成屏幕上看得见的「『abandon』左前 35° · 2.1m + 3×3 刻度」——真实 `ObjectAudioComponent` 渲染路径原样保留，罗盘展示的是它的几何事实。

提示音资产由 `scripts/gen_audio.py` 程序化生成（48kHz / 16-bit 单声道 WAV），可复现、可审计。

## Vibe Coding 工作流

本项目采用 **PICO CLI + AI 编程助手** 的实际开发工作流：

- `pico-cli doctor` 诊断环境、`pico-cli emulator` 管理模拟器生命周期、`pico-cli app` / `pico-cli log` / `pico-cli capture` 覆盖安装、日志、截图
- AI 助手负责模块骨架、单测生成与文档同步；人负责架构决策、空间交互体验与最终验收
- commit 记录如实反映人机协作过程，**不做"全人工风格"伪装** —— 评委对照 README 翻 git log 时两者一致，才是加分而不是穿帮

### 无头测试通道

模拟器驱动不了 3D 空间按钮，所以每个可交互动作都配了 adb 广播入口（保留作自动化测试用，现场演示走真实 UI）：

```bash
adb shell am broadcast -a com.memorypalace.dualstate.OPEN_STAGE       # 开房间层
adb shell am broadcast -a com.memorypalace.dualstate.DEMO_MODE --ez enabled false  # 切调试态（默认=演示净界面）
adb shell am broadcast -a com.memorypalace.anchor.PLACE --ef x 0 --ef y 1.2 --ef z -1.5 --es front "abandon"
adb shell am broadcast -a com.memorypalace.review.START               # 开始复习
adb shell am broadcast -a com.memorypalace.review.GRADE --es grade remember
adb shell am broadcast -a com.memorypalace.clock.SET_OFFSET --el days 7  # 时间机器
adb shell am broadcast -a com.memorypalace.audio.PLAY --es cue cue_due  # 空间提示音
adb shell am broadcast -a com.memorypalace.notify.SCHEDULE   # 排到期提醒
```

## 空间能力矩阵

| PICO Spatial SDK 能力 | 本项目用法 | 官方文档 |
|---|---|---|
| WindowContainer（Planar） | 书房看板、随身卡窗口 | [空间容器与空间状态](https://developer.picoxr.com/document/spatial-sdk/learn-about-spatial-containers-and-space-states/) |
| Stage（Full Space） | 房间层沉浸复习 | 同上 |
| WorldTrackingManager 空间锚定 | 卡片绑定真实家具（每应用上限 1024 锚点，检索半径 5m） | Spatial SDK 锚定文档 |
| ObjectAudioComponent 空间音频 | 从锚点位置发提示音（距离平方反比衰减） | [空间音频示例](https://developer.picoxr.com/document/spatial-sdk/spatial-audio-sample/) |
| 手势追踪 | 捏合抓取/翻卡（手柄为主路径） | [手部追踪](https://developer-cn.picoxr.com/document/spatial-sdk/hand-tracking/) |
| PICO Emulator | 全流程无真机开发与录屏 | [PICO Emulator](https://developer-cn.picoxr.com/document/spatial-toolkit/learn-about-pico-emulator/) |

## 文档

| 文档 | 内容 |
|---|---|
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | 架构：分层图、关键类与数据流、core 零 SDK 边界为什么成立、**扩展点速查表** |
| [CONTRIBUTING.md](CONTRIBUTING.md) | 二开入口：环境、跑测试、模块边界（含 CI 强制那条 grep）、加词库/加导入器步骤、PR 约定 |
| [docs/ALGORITHM.md](docs/ALGORITHM.md) | SM-2 调度器与记忆强度可视化的完整规则与边界 |
| [docs/DESIGN.md](docs/DESIGN.md) | 设计规范：色板 / 字号 / 间距 token 与褪色视觉规则 |
| [docs/SCORING.md](docs/SCORING.md) | 评分维度的自评口径与每个数字的来源（含诚实声明） |
| [docs/COMPETITORS.md](docs/COMPETITORS.md) | XR 同类竞品对比与差异化结论（25 款取证） |
| [docs/COMPETITOR-ANKI3D.md](docs/COMPETITOR-ANKI3D.md) | 「Anki 3D」线索证伪档案：产品不存在，含方法论教训 |
| [docs/DEMO-RUNBOOK.md](docs/DEMO-RUNBOOK.md) | 三分钟现场演示操作单（给没碰过应用的操作员） |
| [docs/DUAL-STATE-VERIFICATION.md](docs/DUAL-STATE-VERIFICATION.md) | 双态行为实测结论（正式文档）：逐条实测表、生命周期日志原文、已知边界与真机待验清单 |
| [docs/DUAL-STATE-TEST.md](docs/DUAL-STATE-TEST.md) | 双态切换与窗口生命周期实测结论（原始记录） |
| [docs/ANCHOR-M2-TEST.md](docs/ANCHOR-M2-TEST.md) | 锚定闭环实测（含恢复漂移数据） |
| [docs/M4–M8-*-TEST.md](docs/) | 复习流 / 导入管线 / 时间机器 / 预置牌组 / 确认页 实测记录 |
| [docs/VIDEO-STORYBOARD.md](docs/VIDEO-STORYBOARD.md) | 演示视频分镜（**旧版规划稿** 150s 五幕 19 镜；成片是 165s 14 幕，见上） |
| [docs/ITERATION.md](docs/ITERATION.md) | 迭代记录：全新项目声明与里程碑日志 |

## 完成度证据

评分表里「完成度」这一维度是白送分 —— 所以把分散在 `docs/` 各处的机械证据集中到这一屏，每个数字都有出处。

### 仓库体积与克隆耗时

| 项 | 实测值 | 来源 |
|---|---|---|
| 版本库跟踪体积 | **59.5 MB**（其中演示视频 44 MB + 截图 14 MB + 源码与文档 1.5 MB） | `git ls-tree -r -l HEAD` 实测 |
| `git clone` 耗时 | **约 70 秒** | 2026-11-09 干净克隆实测（含 44MB 演示视频） |

`.git` 目录约 **176 MB** —— 原始 PNG 和早期截图留在历史里，没改写历史，因为「全新项目无旧代码搬运」的声明靠连续可查的 commit 时间戳支撑。克隆约 70 秒，可接受。

### 单测：137 例全绿（core 113 + app 24，2026-11-14 本机 `./gradlew :core:test :app:testDebugUnitTest` 实测）

| core 测试文件（纯 JVM，`./gradlew :core:test`） | 用例数 | 状态 |
|---|---|---|
| `SchedulerTest`（SM-2 调度器） | 12 | ✅ 全绿 |
| `MemoryStrengthTest`（记忆强度可视化） | 19 | ✅ 全绿 |
| `ImportersTest`（CSV / TXT / apkg 解析） | 11 | ✅ 全绿 |
| `ApkgExtractorTest`（apkg SQLite 抽取） | 8 | ✅ 全绿 |
| `PlaneGeometryTest`（射线-平面命中 / 正交基 / 轮廓） | 44 | ✅ 全绿 |
| `BearingTest`（逻辑方位罗盘：符号约定 / 八方位分桶 / NaN 退化） | 19 | ✅ 全绿 |
| **core 合计** | **113** | ✅ 全绿 |

| app 测试文件（同样 JVM 可跑，`./gradlew :app:testDebugUnitTest`，无需设备） | 用例数 | 状态 |
|---|---|---|
| `AnchorStoreParseTest`（锚点记录解析逐条隔离） | 4 | ✅ 全绿 |
| `AnchorClearRetainTest` / `AnchorVerifyDecisionTest`（清除保留 / 降级判定） | 3 + 1 | ✅ 全绿 |
| `PlanImportDedupTest`（导入去重计划） | 4 | ✅ 全绿 |
| `DecideReminderTest`（提醒调度决策） | 4 | ✅ 全绿 |
| `RoomSceneDiffTest`（房间层增量挂载差集） | 8 | ✅ 全绿 |
| **app 合计** | **24** | ✅ 全绿 |

以上每个数字都来自 `build/test-results/` 的 XML 实测计数（2026-11-14），不是手挑子集。

`core/` 是**纯 JVM 模块，零 SDK 依赖**：不 import 任何 `android.*`，也不 import 任何 `com.pico.*`，唯一的依赖是测试用的 JUnit。所以算法层可以离线跑单测，不需要 Android SDK —— `./gradlew :core:test` 一条命令就完事。这条约束由 CI 静态检查强制（见下），边界内的扩展路径（加导入器 / 加调度策略 / 加词库 / 加窗口）写在 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) §5 与 [CONTRIBUTING.md](CONTRIBUTING.md)。

### 干净克隆验证结论（2026-11-09）

评委 clone 下来能不能直接跑，这是交付物最基本的验收。实测顺序与结果：

| 步骤 | 结果 |
|---|---|
| `git clone` | ✅ 成功，约 70 秒（含 44MB 视频） |
| `:core:test` | ✅ BUILD SUCCESSFUL，68 例全绿（当日口径；现为 113） |
| `:app:assembleDebug` | ✅ BUILD SUCCESSFUL，产出 **30 MB** debug APK |

### CI 现状

`.github/workflows/ci.yml` 在 push / pull_request 上跑，**两个 job**：

- **`core-test`** —— core 全量单测（`:core:test`），并上传测试报告作为 artifact（保留 14 天）。
- **`app-static-check`** —— 四项静态检查：

| 检查项 | 为什么需要它 |
|---|---|
| core 零框架依赖（无 `android.*` / `com.pico.*` import） | 保证算法层永远可离线单测 |
| manifest 有 LAUNCHER 入口 | 没有入口应用起不来 |
| stage meta-data 必须挂在 LAUNCHER activity 上（且不能挂到别的 activity 上） | SDK 在 LAUNCHER 启动时读 `pico.spatial.stage.*` 注册 Stage；挂错地方 → `Stage(...) not registered` → 进程被杀（M12 踩过这个坑） |
| 广播 action 唯一 | 无头驱动全靠 action 唯一性，重名会让两个接收器抢同一条消息 |

`app/` 的构建**不在 CI 里跑** —— 它依赖 PICO Spatial SDK 的私有 Maven 仓库，CI 环境拿不到凭据。这一点写在 CI 文件注释里，不假装 CI 跑过 app 构建；app 的构建在本地验证（`:app:assembleDebug` 通过）。

### 三个功能模块的模拟器实测状态

| 模块 | 模拟器已实测 | 待真机 |
|---|---|---|
| **空间音频**（M13） | 音频资源加载成功、实体挂载 `ObjectAudioComponent`、三个提示音（到期 / 放置确认 / 复习完成）播放调用成功无异常 | **方位正确性**（"走到冰箱前声音在左边"）、距离衰减曲线、提示音在真实房间里的可辨识度 —— 模拟器无空间音频输出路径 |
| **到期通知**（M14） | 通知发出成功、提醒调度与触发、撤销生效、精确闹钟不可用时的降级路径（不假装精确，UI 有橙色警示 + 系统设置指引） | **通知弹窗在头显内的呈现**（模拟器无系统通知面板）、精确闹钟在 PICO OS 真机的可用性 |
| **锚定闭环**（M2） | 放卡 → 杀进程 → 重进 → 找回，drift **0.0m**；**同一模拟器实例内**成立（应用进程被杀也照样恢复，覆盖 Stage 重建这一更强情形） | **锚点在设备重启后的存续** —— 模拟器实例重建会丢系统锚点（降级路径已验：卡片按最后记录位姿继续渲染，不消失），真机是否持久未验 |

### 一句话小结

**评委 clone 下来约 70 秒、`./gradlew :core:test` 一条命令看到 113 例 core 单测全绿（app 层另有 24 例，`:app:testDebugUnitTest` 同样无需设备）；装上模拟器能跑通三窗 + 锚定 + 复习 + 到期提醒的完整闭环。还没验的是 12 项必须靠真机的东西**（空间交互、手势、声场方位、通知弹窗呈现、锚点跨重启存续、SAF 呈现等，完整清单见 [DUAL-STATE-VERIFICATION](docs/DUAL-STATE-VERIFICATION.md) §6）。

## 路线图

- **2026-11-07**：仓库初始化；M1 三窗空壳（双态生命周期实测封板）
- **2026-11-07**：M2–M8 集中落地 —— 锚定闭环、数据层、复习流、导入管线、时间机器、预置牌组、导入确认页
- **2026-11-09**：M12 冷启动占位页；M13 空间音频方位引导 + 记忆强度下沉 core（68 例单测全绿）；M14 头显本地到期提醒
- ~~11/10–11/13：竞品调研 + 文档定稿~~ → **已提前完成（11/09）**：25 款竞品取证、ALGORITHM / DESIGN / SCORING / COMPETITORS 定稿
- **2026-11-14**：评审集群收口 —— 几何/并发/持久化/稳定性修复（P1/P2）；新增**逻辑方位罗盘**（空间方位屏上可视化，19 例纯 JVM 单测）与**触屏放置兜底链**（无手柄也能演示对准→放置→钉上）；README 撤下调试态头图（重拍清单见 DEMO-RUNBOOK 附录 D）；开源二开文档 CONTRIBUTING / ARCHITECTURE 上线；单测 68 → **137 例（core 113 + app 24）**
- **11/14–11/22**：真机借测窗口（空间交互唯一验证手段）；锚定最小闭环复测
- **11/23–11/29**：复习流打磨；11/25 提交 v0.1 占位；11/28 起每日真实使用记录
- **11/30–12/7**：空间音频真机验证；视觉打磨（设计规范数字化已于 11/07 完成）
- **12/8–12/16**：视频按真机素材重拍补剪（模拟器版已于 11/09 产出）、文档定稿；**12/16 24:00 最终提交**
- **12/26**：北京大钟寺决赛路演

## License

[Apache-2.0](LICENSE) © 2026 Memory Palace contributors
