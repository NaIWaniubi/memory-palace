# 迭代记录 ITERATION

## 项目声明

本仓库为 **2026-11-07 全新创建**的参赛项目，无任何旧项目代码搬运。所有 commit 自该日起连续可查。

## 里程碑日志

| 日期 | 事件 | 证据 |
|---|---|---|
| 2026-11-07 | 仓库初始化：License、目录骨架、README | 首个 commit |
| 2026-11-07 | M1 三窗空壳 + 双态生命周期实测封板 | [docs/DUAL-STATE-TEST.md](DUAL-STATE-TEST.md) |
| 2026-11-07 | M2 房间锚定最小闭环（放卡→杀进程→重进→找回，UUID 重新解析成功） | [docs/ANCHOR-M2-TEST.md](ANCHOR-M2-TEST.md) |
| 2026-11-07 | M3 知识卡数据层 + M4 复习流（会话状态机 + 自评调度 + 记忆强度外观） | [docs/M4-REVIEW-FLOW-TEST.md](M4-REVIEW-FLOW-TEST.md) |
| 2026-11-07 | M5 导入管线（CSV/TXT/Anki apkg 三格式离线导入） | [docs/M5-IMPORT-PIPELINE-TEST.md](M5-IMPORT-PIPELINE-TEST.md) |
| 2026-11-07 | M6 时间机器演示模式（一键快进 +1/+7 天 + 复位） | [docs/M6-TIME-MACHINE-TEST.md](M6-TIME-MACHINE-TEST.md) |
| 2026-11-07 | M7 三套预置示例牌组（语言/术语/常识） | [docs/M7-PRESET-DECKS-TEST.md](M7-PRESET-DECKS-TEST.md) |
| 2026-11-07 | M8 导入字段映射确认页（解析→确认→入库两段式） | [docs/M8-IMPORT-CONFIRM-TEST.md](M8-IMPORT-CONFIRM-TEST.md) |
| 2026-11-07 | M9 设计规范数字化（三窗共用一套 token） | [docs/DESIGN.md](DESIGN.md) |
| 2026-11-07 | M10 视频分镜脚本（150s 五幕 19 镜双轨） | [docs/VIDEO-STORYBOARD.md](VIDEO-STORYBOARD.md) |
| 2026-11-07 | M11 三分钟演示路径固化（冷启动自动开三窗 / 锚卡「复习这张」/ 面板「钉入房间」） | [docs/DEMO-RUNBOOK.md](DEMO-RUNBOOK.md) |
| 2026-11-09 | M12 冷启动占位（**修订版**：LAUNCHER 保持 Spatial stub，改用窗口背景占位；原 SplashActivity 方案因触发 SDK 容器冲突已删除）+ 时间跳转处理中提示 | `app/.../app/LaunchActivity.kt`、`res/values/themes.xml`（`Theme.MpSplash`） |
| 2026-11-09 | M13 空间音频方位引导 + 记忆强度计算下沉 core（单测 16→42 例） | `app/.../audio/SpatialCue.kt`、`core/.../schedule/MemoryStrength.kt` |
| 2026-11-09 | 文档三件套定稿（ALGORITHM / DESIGN / SCORING）+ README 同步到 M13 现状 + CI 真实化 | `docs/`、`.github/workflows/ci.yml` |
| 2026-11-09 | M14 头显本地到期提醒（通知通道 + 精确闹钟 + 复习后撤销） | `app/.../notify/DueNotifier.kt`、`DueReminder.kt` |
| 2026-11-09 | **M12–M14 模拟器实跑验证**：抓出并修掉两个真实 bug（M12 启动即崩 / 提醒排到过去时刻）；空间音频三音、到期通知、提醒调度触发撤销全部实测通过 | [docs/M12-M14-TEST.md](M12-M14-TEST.md) |

## 环境基线

| 组件 | 版本 |
|---|---|
| PICO CLI | 0.6.1（`doctor` = 0 error） |
| PICO Emulator | 6.1.0（AVD `Pico_Emulator_6_1`） |
| PICO Spatial SDK | 6.1.9 |
| JDK | Corretto 21.0.12 |
| Android SDK | Platform 35 + Sources 35 |

**账号门槛：无。** 全程无账号/实名/授权门槛。

## 干净克隆验证（2026-11-09）

交付物最基本的验收：评委 clone 下来能不能直接跑。

| 项 | 结果 |
|---|---|
| `git clone` | ✅ 成功，**约 70 秒**（含 44MB 演示视频；此前因 165MB 截图直接失败过） |
| `:core:test` | ✅ BUILD SUCCESSFUL，42 例全绿 |
| `:app:assembleDebug` | ✅ BUILD SUCCESSFUL，产出 30 MB debug APK |
| 版本库跟踪体积 | 59.5 MB（演示视频 44MB + 截图 14MB + 源码与文档 1.5MB） |

**为什么压缩截图**：57 张 PNG 占 165 MB —— MR 透视截图 2160×2158，PNG 无损格式对大量渐变与噪点极不友好（平均 2.9 MB/张）。`git clone` 报 `invalid index-pack output` 直接失败。仓库是参赛交付物，评委第一件事就是 clone，这个失败不可接受。压到 1600px + JPEG 质量 88 后逐张抽检，UI 文字仍清晰可读。

**为什么没改写 git 历史**：`.git` 约 176 MB，因为原始 PNG 和早期截图留在历史里。改写历史会破坏两样东西 —— ①「全新项目无旧代码搬运」的声明靠连续可查的 commit 时间戳支撑；②参赛的提交时间戳证据链。约 70 秒克隆可接受，不值得为此重写。

## 测试现状

| 层次 | 数量 | 状态 |
|---|---|---|
| core 单测（Scheduler） | 12 例 | ✅ 全绿 |
| core 单测（MemoryStrength） | 19 例 | ✅ 全绿 |
| core 单测（Importers） | 11 例 | ✅ 全绿 |
| **合计** | **42 例** | ✅ 全绿 |

CI：`.github/workflows/ci.yml` 在 GitHub Actions 跑 core 全量单测 + **四项**静态检查（core 零框架依赖、manifest 有 LAUNCHER 入口、stage meta-data 必须挂在 LAUNCHER activity 上、广播 action 唯一）。

**app/ 模块的构建不在 CI 里跑**：它依赖 PICO Spatial SDK 私有 Maven 仓库，CI 环境无凭据。app 构建在本地验证（`./gradlew :app:assembleDebug` 通过）。这一点在 CI 文件注释里写明，不假装 CI 跑过。

## 已知待验项（真机）

| 项 | 原因 |
|---|---|
| 空间交互（多窗拖动缩放、手势） | 模拟器官方明示无法驱动 3D 空间按钮 |
| 空间音频声场正确性（方位、距离衰减） | 需真机听辨（模拟器已验资源加载与播放调用） |
| 头显通知弹窗呈现与精确闹钟能否按时触发 | 需真机；模拟器无系统通知面板，且 PICO OS 未授予 SCHEDULE_EXACT_ALARM（已降级为不精确闹钟并做 UI 提示） |
| SAF 文件选择器在 PICO 真机的呈现 | 模拟器无空间交互 |
| 冷启动耗时（真机 arm64 原生应显著快于模拟器 12s） | 模拟器走 x86 转译 |

## 协议与素材来源

- 本项目源码：Apache-2.0
- 官方 Sample（参考实现）：Apache-2.0（Copyright 2026 PICO），借鉴时保留其版权声明
- 空间提示音资产：`scripts/gen_audio.py` 程序化生成，本项目原创
