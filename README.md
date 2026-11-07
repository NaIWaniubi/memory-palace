# 记忆宫殿 Memory Palace

> 把你的房间变成记忆宫殿 —— 单词贴在冰箱上，公式贴在书桌上。
> PICO Space Program 2026 · 最佳 PICO Spatial 空间应用赛道参赛作品 · Apache-2.0 开源

## 30 秒电梯陈述

1. 它把你的房间变成记忆宫殿——单词贴在冰箱上，公式贴在书桌上。
2. 它按遗忘曲线算好你什么时候该回到哪件家具前，空间会主动提醒你。
3. 这是两千年记忆术第一次从"脑中想象"变成"眼睛看得见"。

记忆宫殿是一款 PICO MR 空间记忆应用：把 Anki 式卡片锚定到真实家具上，由 SM-2 改进版遗忘曲线调度复习，空间音频在正确的时间把你引导到正确的位置。

## 30 秒运行指南

> 前置：已安装 PICO CLI 与 PICO Emulator（见[官方文档](https://developer.picoxr.com/document/pico-cli/)）

```bash
pico-cli emulator start --avd Pico_Emulator_6_1   # 1. 启动 PICO 模拟器
./gradlew installDebug                            # 2. 构建并安装
pico-cli app launch                               # 3. 启动应用
```

> 状态：应用模块尚未落地（仓库 2026-11-07 初始化，排期见下文路线图）。上述命令为最终形态，当前仅第 1 步可用。

## 功能矩阵

| 功能 | 状态 |
|---|---|
| 三窗架构（书房看板 / 随身卡 / 房间层） | M1 空壳已实测（[双态结论](docs/DUAL-STATE-TEST.md)） |
| 空间锚定（放卡 → 杀进程 → 重进找回） | M2 闭环已实测（[锚定结论](docs/ANCHOR-M2-TEST.md)，恢复漂移 0.0m） |
| SM-2 改进版遗忘曲线调度 | 待开发 |
| 空间音频方位引导 | 待环境验证 |
| CSV / apkg 牌组导入 | 待开发 |
| 手势 + 手柄双路径交互 | 待环境验证 |

截图：待补充（`media/screenshots/`）。

## 架构

双态架构：**Shared Space** 跑 WindowContainer 多窗口管理（书房看板 + 随身卡），**Full Space（Stage）** 跑空间锚定复习。`core/` 模块不 import 任何 PICO SDK，保证算法可纯 JVM 单测。

```
┌───────────────────────── PICO OS 6 ─────────────────────────┐
│  Shared Space                    Full Space (Stage)          │
│  ┌──────────────┐ ┌───────────┐   ┌───────────────────────┐  │
│  │ 书房 Window   │ │ 随身卡     │   │ 房间层：锚定卡片 +       │  │
│  │ Container     │ │ Container │   │ 空间音频 + 手势          │  │
│  └──────┬───────┘ └─────┬─────┘   └───────────┬───────────┘  │
│         └───────────────┴─────────────────────┘              │
│                         │                                    │
│              spatial/（SDK 适配层）                            │
│                         │                                    │
│         core/（数据模型 + SM-2，纯 Kotlin，零 SDK 依赖）         │
│         data/（Room 持久化 + CSV/apkg 导入管线）                │
└──────────────────────────────────────────────────────────────┘
```

（正式架构图待补充）

## 调度算法

SM-2 改进版为主路径（可解释、可单测），FSRS 预留接口位。详见 `docs/ALGORITHM.md`（待补充）。

## Vibe Coding 工作流

本项目采用 **PICO CLI + AI 编程助手** 的实际开发工作流：

- `pico-cli doctor` 诊断环境、`pico-cli emulator` 管理模拟器生命周期、`pico-cli app` / `pico-cli log` / `pico-cli capture` 覆盖安装、日志、截图
- AI 助手负责模块骨架、单测生成与文档同步；人负责架构决策、空间交互体验与最终验收
- commit 记录如实反映人机协作过程，不做"全人工"伪装

## 空间能力矩阵

| PICO Spatial SDK 能力 | 本项目用法 | 官方文档 |
|---|---|---|
| WindowContainer（Planar） | 书房看板、随身卡窗口 | [空间容器与空间状态](https://developer.picoxr.com/document/spatial-sdk/learn-about-spatial-containers-and-space-states/) |
| Stage（Full Space） | 房间层沉浸复习 | 同上 |
| WorldTrackingManager 空间锚定 | 卡片绑定真实家具（每应用上限 1024 锚点，检索半径 5m） | Spatial SDK 锚定文档 |
| 空间音频 | 方位引导音（能力上限待环境验证） | [空间音频示例](https://developer.picoxr.com/document/spatial-sdk/spatial-audio-sample/) |
| 手势追踪 | 捏合抓取/翻卡（手柄为主路径） | [手部追踪](https://developer-cn.picoxr.com/document/spatial-sdk/hand-tracking/) |
| PICO Emulator | 全流程无真机开发与录屏 | [PICO Emulator](https://developer-cn.picoxr.com/document/spatial-toolkit/learn-about-pico-emulator/) |

## 路线图

- **2026-11-07**：仓库初始化；**M1 三窗空壳完成**，双态生命周期实测封板（[结论](docs/DUAL-STATE-TEST.md)：窗口存活、Stage 重建、双态架构可行无需 L2 降级）
- 11/10–11/13：core 数据模型 + SM-2 + 单测；CSV 导入
- ~~11/14–11/17：三窗空壳 + 双态切换实测~~（11/07 提前完成）
- 11/18–11/22：锚定最小闭环
- 11/25：v0.1 占位提交
- 11/30–12/7：空间音频、时间机器演示模式、视觉打磨
- 12/8–12/16：视频、文档定稿，**12/16 24:00 最终提交**
- 12/26：北京决赛路演

## License

[Apache-2.0](LICENSE) © 2026 Memory Palace contributors
