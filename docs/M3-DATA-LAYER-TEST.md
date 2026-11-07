# M3 知识卡数据层 + 遗忘曲线调度实测结论

> 实测日期：2026-11-07 · 环境：PICO Emulator 6.1.0 · Spatial SDK 6.1.9 · APK：app-debug（M3）
> M3 验收口径：① 卡片 CRUD 入持久层；② SM-2 变体调度（答对/答错影响间隔）；③ 有"到期卡"概念与当日复习队列；④ 与 M2 锚定打通（锚点绑真实卡片 ID）。

## 实现形态

- `core/`（纯 Kotlin JVM 模块，零 SDK 依赖）：
  - `model/Card.kt`：调度字段内嵌（repetitions / easiness / intervalDays / dueAt）。
  - `schedule/Scheduler.kt`：SM-2 变体，纯函数、时间参数注入。新卡立即到期；遗忘 → reps 清零 + 10 分钟后重学 + easiness 按 SM-2 公式下调（下限 1.3）；记住 → 间隔 1 天 → 6 天 → 按 easiness 倍增。`dueQueue()` = dueAt <= now 按到期升序。
- `app/data/`（Room 2.6.1 + KSP）：`CardEntity / CardDao / AppDatabase / CardRepository / DataGraph`（装配 + 无头命令）；`Clock`（全局时钟 + 偏移，M6 时间机器的留口）。
- 打通 M2：`AnchorScene.place()` 先建卡落 Room，`AnchorRecord.key = Card.id`（不再是占位标题）。
- `StudyPanel` 增「今日到期队列」区：Room Flow → dueQueue(Clock.now()) 实时刷新。
- 无头驱动：`card.ADD --es front --es back`；`card.REVIEW --es id <前缀> --es grade remember|forgot`。

## 实测证据

**单测（JVM，5/5 通过）**：新卡立即到期；记住间隔 1d→6d→15d（6×2.5）；遗忘清零 reps + 10 分钟重学；easiness 连错 10 次钳到 1.3；到期队列过滤未来卡并按逾期排序。

**设备实测（logcat 原文）**：

| 操作 | 结果 |
|---|---|
| 加 3 卡（广播 ADD） | `card added id=92f1a379/02d62044/4460df58 due=now`（新卡立即到期） |
| PLACE 放锚定卡 mnemonic | `card added id=af275b7a` → `createAnchor SUCCESS uuid=7eb7b0cf-…` → `record persisted key=af275b7a-…`（**锚点 key = 真实卡片 ID**，打通确认） |
| 答对 ephemeral | `card reviewed id=02d62044 grade=REMEMBERED reps=1 ef=2.50 interval=1.0d`（离队 1 天） |
| 杀进程冷启动（新 PID） | `room database ready` + 到期队列完整恢复（Room 跨进程持久化确认）；锚点 `drift=0.0m` ×2 |
| 答错 ubiquitous | `card reviewed id=4460df58 grade=FORGOTTEN reps=0 ef=1.96 interval=0.0d`（ef 2.50→1.96 与 SM-2 公式手算一致） |

截图：`media/screenshots/m3-08-due-queue.png`（书房面板到期队列：serendipity/mnemonic 在列，ephemeral 已离队；被启动 ANR 弹窗遮挡部分区域，队列内容仍可辨）。

## 新发现的硬约束（进方案）

**BAL_BLOCK：后台禁开窗口**。模拟器（Android 15 行为）上，应用无可视 Activity 时（广播接收上下文），`openWindowContainer` 内部启动 SpatialStubActivity 被系统拦截：`Background activity launch blocked! … result code=102`。后果：窗口只创建出白色半残表面且不组合内容（本次白块谜团即此），且系统可能把半残窗口状态跨重启恢复。**规避：开窗/双态操作必须在应用前台时下发（Stage 开着的时候），或杀进程重启后先等 Stage 自动起来再操作。** 真机 PICO OS 是否同样拦截待验证（真机权限模型不同，可能无此限制）。

## 其他如实记录

- `loadAnchor` 批量调用混入一个已丢失的 UUID 会整体报 `code=-7`，已改为批量失败时逐条重试（逐条均能解析，drift=0.0m）。
- 启动 ANR 弹窗：曾尝试把 `launch()` 挪到后台线程消除，SDK 强制主线程初始化（`SpatialApp must be initialized in Android Main Thread`）直接崩，已回滚。维持 M2 定性：模拟器 houdini 转译独有，等真机复测。
- 模拟器 ANR 弹窗为空间系统面板，`input tap`/`keyevent BACK` 均无法消除（后者反而触发窗口重渲异常）；取证靠 logcat + 趁弹窗未起时截图。
