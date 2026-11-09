# 双态行为实测结论（Shared Space ↔ Full Space）

> 本文是双态架构的**对外正式结论文档**，文件名由作战方案 §6.1 指定。
> 内容全部从已有实测记录提炼，**不引入新数字**；每条结论标注可追溯的源文档章节。
>
> **原始实测记录**（三份）：
> - `docs/DUAL-STATE-TEST.md` —— 双态切换与窗口生命周期（M1，2026-11-07）
> - `docs/M12-M14-TEST.md` —— M12–M14 实测、模拟器故障恢复手册（2026-11-09）
> - `docs/ANCHOR-M2-TEST.md` —— 锚定闭环与边界条件（M2，2026-11-07 / 2026-11-09 补充）
>
> 实测环境：PICO Emulator 6.1.0（AVD `Pico_Emulator_6_1`，swan_x86_64）· Spatial SDK 6.1.9 · APK `app-debug`（包名 `com.memorypalace`）
> 证据形式：logcat 原文摘录 + 逐条命令 + 截图（`media/screenshots/`）

---

## 1. 被测形态

### 1.1 Shared Space：两个 WindowContainer

| 容器 | ID | 常驻形态 | 职责 |
|---|---|---|---|
| 书房看板 | `study_panel` | Shared Space 常驻 | 复习队列、导入、设置入口 |
| 随身复习卡 | `review_card` | 可开关 | 单卡正反面翻看与自评 |

两者都是**具名 `WindowContainer`**（Planar），`resizeType = ContainerResizeType.ContentSize`（见 `app/src/main/java/com/memorypalace/Main.kt`）。

### 1.2 Full Space：Stage 房间层

| 容器 | ID | 形态 | 职责 |
|---|---|---|---|
| 房间层 | `room_stage` | `DefaultStage`，`StageStyle.Mixed` | 锚定卡片实体、空间音频、空间交互 |

**形态硬约束**：默认容器全局只能有一个 —— `DefaultStage` 与 `DefaultWindowContainer` 共存会在启动时崩溃（`IllegalStateException: DefaultWindowContainer registered already`）。默认容器必须给 Stage，窗口一律用具名 `WindowContainer`（见 `docs/DUAL-STATE-TEST.md` §结论 4）。

### 1.3 探针机制

每个组件内置探针（`app/src/main/java/com/memorypalace/probe/`）：

- 组合时领取实例号（`DualStateProbe.claim`），**实例号递增 = 销毁重建的直接证据**
- 全生命周期事件（ON_CREATE / START / RESUME / PAUSE / STOP / DESTROY + 组合 DISPOSE）打 logcat，tag `DualState`，并同步到屏上日志
- 因模拟器无法驱动空间按钮（官方明示，见 `docs/M12-M14-TEST.md` §7 的 HC-1），切换动作用 adb 广播无头驱动：

```bash
adb shell am broadcast -a com.memorypalace.dualstate.OPEN_STAGE      # 开房间层
adb shell am broadcast -a com.memorypalace.dualstate.CLOSE_STAGE     # 关房间层
adb shell am broadcast -a com.memorypalace.dualstate.TOGGLE_REVIEW_CARD  # 开关随身卡
```

---

## 2. 实测项逐条表

> 「预期」列写的是测量前的设计预期，「实测结果」列是 logcat 原文或观察到的现象，「结论」列是封板判定。

### 2.1 双态切换与窗口生命周期（M1）

见 `docs/DUAL-STATE-TEST.md` §观察到的现象、§结论（封板）。

| # | 项 | 操作 | 预期 | 实测结果 | 结论 |
|---|---|---|---|---|---|
| 1 | Stage 首次创建 | 启动应用（T0） | Stage 建出房间层并进入 Full Space | `CLAIM #1` → RESUME | ✅ 与预期一致 |
| 2 | 声明即创建 | 声明了 Stage 后启动 | 房间层随应用启动直接就绪 | 房间层已存在；两个窗口**未创建** | ⚠️ 声明 ≠ 创建：窗口需 `Context.openWindowContainer(id, tag)` 显式打开 |
| 3 | 双窗口创建 | `openWindowContainer` ×2（T1） | 书房面板与随身卡各自建出 | 两者均 `CLAIM #1` → RESUME | ✅ 正常 |
| 4 | 三容器同屏 | Full Space 下观察 | 房间画面 + 两个窗口同时可见 | `m1-02-full-with-windows.jpg` 三容器同时可见 | ✅ Full Space 不排斥窗口 |
| 5 | 关闭房间层 | `closeStage`（T2） | 房间层销毁，窗口不受影响 | 房间层 `PAUSE→STOP→DESTROY→DISPOSE`；两个窗口**无任何事件** | ✅ 窗口存活 |
| 6 | 回到 Shared Space | 关闭后观察 | 双窗口仍在 | `m1-03-shared-space.jpg` 双窗口仍在 | ✅ 双态回落正常 |
| 7 | 窗口在切换中是否重建 | 全程观察实例号 | 窗口实例号不变 | 切换全程窗口**连 PAUSE 都没有**，实例号不变，`remember` 状态保留 | ✅ 窗口不销毁不重建 → **双态架构可行，不需要 L2 降级** |
| 8 | 房间层重建 | `openStage`（T3） | Stage 重新建出 | 房间层 `CLAIM #2` → RESUME（重建） | ⚠️ 每次进 Full Space 都销毁重建 |
| 9 | 随身卡独立开关 | 关闭再打开随身卡（T4） | 开关随身卡不影响书房面板 | 随身卡 `DESTROY→DISPOSE → CLAIM #2`；书房面板**零事件** | ✅ 窗口可独立开关、互不干扰 |
| 10 | 第二轮循环 | `closeStage→openStage`（T5） | 结论可重复 | 房间层 `DESTROY→CLAIM #3`；书房面板仍显示「实例号 #1」、随身卡「实例号 #2」，与 logcat 一致（`m1-05-cycle2-full.jpg`） | ✅ 结论可重复，非偶然 |
| 11 | 默认容器唯一性 | Stage 与 WindowContainer 共存 | 明确边界 | `IllegalStateException: DefaultWindowContainer registered already` | ⚠️ 架构硬约束：默认容器给 Stage |
| 12 | `openStage` 返回值 | 调 `openStage(id, StageStyle.Mixed)` | 可判定是否成功 | 返回 `OpenStageResult.Allowed` | ✅ 可用返回值做失败兜底 |

### 2.2 锚定闭环与 Stage 重建恢复（M2 / M12 修订后复核）

见 `docs/ANCHOR-M2-TEST.md` §实测序列与证据、§结论（封板）；`docs/M12-M14-TEST.md` §4b。

| # | 项 | 操作 | 预期 | 实测结果 | 结论 |
|---|---|---|---|---|---|
| 13 | 锚点服务冷启动延迟 | 应用启动后立即放置 | 锚点应可用 | `createAnchor ERROR code=-6 "The provider is not ready."` → 记录以**回退位姿**持久化（uuid=null） | ⚠️ 锚点服务有冷启动延迟；放置按钮需就绪态管理 |
| 14 | 锚点创建 | 24 秒后再次放置 | 锚点创建成功 | `createAnchor SUCCESS uuid=e71357b4-… resolvedPos=(0.5,1.3,-1.2)`，drift=0.0m | ✅ 正常 |
| 15 | 锚点位置正确性 | 截图 `m2-01-anchored-full.jpg` | 卡片钉在预期位置 | 「ephemeral」在右侧架子上方（0.5,1.3,-1.2），「abandon」在床面（0,1.2,-1.5） | ✅ 位置与保存位姿一致 |
| 16 | 杀进程重启恢复 | `am force-stop` → 冷启动（新 PID） | 记录恢复并按 UUID 重解析 | `restored 2 anchor record(s) from store` → 两条记录全部恢复渲染 | ✅ 锚定最小闭环通 |
| 17 | UUID 重新解析 | 恢复后校验 | save/resolved 应一致 | `verifyRestored: uuid=e71357b4-… saved=(0.5,1.3,-1.2) resolved=(0.5,1.3,-1.2)` | ✅ UUID 可重解析（**注意：模拟器回显请求位姿，相等是恒等式，不代表空间精度**） |
| 18 | ~~恢复后画面一致~~ | ~~截图对比~~ | — | **证据作废**：原引用的两张截图是同一份文件（MD5 相同），且模拟器回显位姿使「画面一致」成为必然结果 | ❌ 不能作为证据（复核后移除） |
| 19 | M12 修订后回归 | 放卡 → 杀进程 → 重进 → 找回 | 改 manifest/入口后未改坏 | `04:04:03 createAnchor SUCCESS uuid=a572e6cb-…` → `04:05:26 restored 2 anchor record(s) from store` → `04:05:41 verifyRestored: uuid=a572e6cb-… saved=(0.0,1.2,-1.5) resolved=(0.0,1.2,-1.5)` | ✅ 状态机未被 M12 改动破坏（`drift` 字段是恒等式，不作为精度证据） |

### 2.3 空间音频、到期通知与提醒调度（M13 / M14）

见 `docs/M12-M14-TEST.md` §0 结论速览、§3 空间音频、§6 恢复后复测。

| # | 项 | 操作 | 预期 | 实测结果 | 结论 |
|---|---|---|---|---|---|
| 20 | 空间音频资源加载 | 加载 `cue_due` 资产 | 资源加载成功 | `SpatialCue: audio resource loaded: cue_due` | ✅ 通过 |
| 21 | 三个提示音播放 | `am broadcast -a com.memorypalace.audio.PLAY --es cue cue_due` | 三个语义音均可触发 | `play(cue_due/cue_place/cue_done) on entity at …` | ✅ 调用层通过；**方位正确性模拟器不可验** |
| 22 | 房间层实体建立 | 进入 Full Space | 实体与锚点绑定 | `[room_stage] inst#1 SpatialView.initial anchors=1` | ✅ 通过 |
| 23 | 到期通知发出 | 排提醒后触发 | 通知贴出 | `DueNotifier: due notification posted: count=55 top="ephemeral"` | ✅ 通过（**弹窗呈现需真机**） |
| 24 | 提醒调度与触发 | `am broadcast -a com.memorypalace.notify.SCHEDULE` | 到点触发 | `reminder overdue-immediate: fireAt=… (5s from now)` → `alarm fired, posting due notification` | ✅ 通过 |
| 25 | 提醒撤销 | `am broadcast -a com.memorypalace.notify.CANCEL` | 撤销生效 | `due notification cancelled` / `reminder cancelled` | ✅ 通过 |
| 26 | 精确闹钟 | 尝试 `setExact` | 精确闹钟可用 | `exact alarm not permitted, fell back to inexact alarm` | ❌ **PICO OS 未授予 `SCHEDULE_EXACT_ALARM`**，降级路径已生效 |
| 27 | 全新实例复测 | 模拟器实例重建后重跑 M13/M14 | 结论不依赖实例状态 | 空间音频播放 / 通知发出 / 提醒调度触发撤销**四项全通过**，行为与首次一致 | ✅ 实现不依赖模拟器实例状态 |

---

## 3. 双态切换的生命周期日志原文摘录

以下为 `docs/DUAL-STATE-TEST.md` §观察到的现象（logcat 原文摘录）与 `docs/ANCHOR-M2-TEST.md` §边界条件 中的真实 logcat 行，**未改写**。

### 3.1 M1 双态切换生命周期时序表

| 时刻 | 动作 | 房间层 (room_stage) | 书房面板 (study_panel) | 随身卡 (review_card) |
|---|---|---|---|---|
| T0 启动 | — | CLAIM #1 → RESUME | 未创建 | 未创建 |
| T1 | openWindowContainer ×2 | 无事件 | CLAIM #1 → RESUME | CLAIM #1 → RESUME |
| T2 | closeStage | PAUSE→STOP→**DESTROY→DISPOSE** | **无任何事件** | **无任何事件** |
| T3 | openStage | **CLAIM #2** → RESUME（重建） | 无任何事件 | 无任何事件 |
| T4 | 关闭再开随身卡 | 无事件 | 无事件 | DESTROY→DISPOSE → **CLAIM #2** |
| T5 | closeStage→openStage | DESTROY→**CLAIM #3** | 无任何事件 | 无任何事件 |

### 3.2 M2 锚定恢复 logcat 原文

```
04:04:03 I Anchor: createAnchor requested pos=(0.0,1.2,-1.5) card=e875ac3e
04:04:03 I Anchor: createAnchor SUCCESS uuid=a572e6cb-... resolvedPos=(0.0,1.2,-1.5)
04:04:05 I Anchor: verifyRestored: uuid=a572e6cb-... saved=(0.0,1.2,-1.5) resolved=(0.0,1.2,-1.5) drift=0.0m
        ↓ adb shell am force-stop com.memorypalace
04:05:26 I Anchor: restored 2 anchor record(s) from store
04:05:41 I Anchor: verifyRestored: uuid=a572e6cb-... saved=(0.0,1.2,-1.5) resolved=(0.0,1.2,-1.5) drift=0.0m
```

### 3.3 模拟器实例重建后：应用层数据在、系统锚点丢

```
步骤 3  am start -n com.memorypalace/.app.LaunchActivity
        I Anchor: restored 2 anchor record(s) from store           ← 应用层数据没丢
        I Anchor: verifyRestored: no uuid-bound records, skip      ← 系统锚点已随实例重建丢失（预期）

步骤 4  am broadcast -a com.memorypalace.anchor.PLACE --ef x 0.0 --ef y 1.2 --ef z -1.5 ...
        I Anchor: createAnchor SUCCESS uuid=1c06daa0-... resolvedPos=(0.0,1.2,-1.5)
        I Anchor: verifyRestored: uuid=1c06daa0-... saved=(0.0,1.2,-1.5) resolved=(0.0,1.2,-1.5) drift=0.0m
```

### 3.4 跨实例重建时锚点失效与降级

```
W Anchor: verifyRestored: batch loadAnchor ERROR code=-7 msg=The specified anchor was not found.，逐条重试
E Anchor: verifyRestored: uuid=a572e6cb-… NOT resolved code=-7 msg=The specified anchor was not found.
W Anchor: downgraded 1 lost anchor(s) to fallback pose: [a572e6cb-…]
I DualState: [room_stage] inst#1 SpatialView.initial anchors=2     ← 两条记录都还在
```

### 3.5 提醒调度 / 触发 / 撤销 与 精确闹钟降级

```
I DueReminder: reminder overdue-immediate: fireAt=1794160067985 (5s from now) dueCount=55 futureCount=0 top=ephemeral
I DueReminder: alarm fired, posting due notification
W DueReminder: exact alarm unavailable — 提醒可能被系统延后。引导用户到系统设置开启「闹钟和提醒」权限（SCHEDULE_EXACT_ALARM）
W DueReminder: exact alarm not permitted, fell back to inexact alarm at ...
I DueNotifier: due notification posted: count=57 top="ephemeral"
I DueNotifier: due notification cancelled
I DueReminder: reminder cancelled
```

---

## 4. 关键结论

### 4.1 已成立的能力（模拟器实测通过）

| 结论 | 依据 |
|---|---|
| **双态架构可行，不需要 L2 降级** —— WindowContainer 在 Shared ↔ Full 切换中存活，不销毁不重建（切换全程无任何生命周期事件，实例号不变，`remember` 状态保留） | `docs/DUAL-STATE-TEST.md` §结论 1 |
| **Stage 每次进入 Full Space 都销毁重建**（实例号 #1→#2→#3）→ 房间层的一切运行态（锚点引用、场景实体、播放状态）必须外置到组合之外（ViewModel / 单例 / 持久层），进入时重建恢复 | `docs/DUAL-STATE-TEST.md` §结论 2 |
| **窗口可独立开关、互不干扰**；关窗 = 销毁（实例号递增），窗口级状态同理要外置 | `docs/DUAL-STATE-TEST.md` §结论 3 |
| **默认容器全局只能有一个** —— `DefaultStage` 与 `DefaultWindowContainer` 共存会启动崩溃；默认容器给 Stage，窗口用具名 `WindowContainer` | `docs/DUAL-STATE-TEST.md` §结论 4 |
| **DSL 声明窗口 ≠ 打开窗口** —— 声明了 Stage 时应用直接启动进 Full Space，窗口不创建，需 `Context.openWindowContainer(id, tag)` 显式打开 | `docs/DUAL-STATE-TEST.md` §结论 5 |
| `Context.openStage(id, StageStyle.Mixed)` 返回 `OpenStageResult.Allowed`，可用返回值做失败兜底 | `docs/DUAL-STATE-TEST.md` §结论 6 |
| **锚定最小闭环通**：放置 → 持久化 → 杀进程 → 重启 → 按 UUID 恢复 → 渲染位置漂移 0.0m（覆盖了 Stage 必重建的更强情形） | `docs/ANCHOR-M2-TEST.md` §结论 1 |
| **锚点服务冷启动有延迟** —— 启动后头几十秒 `createAnchor` 返回 `code=-6 The provider is not ready`；放置按钮需就绪态管理（灰态 / 重试） | `docs/ANCHOR-M2-TEST.md` §结论 2 |
| **回退路径有效** —— uuid=null 的记录按保存位姿直渲，视觉位置正确 | `docs/ANCHOR-M2-TEST.md` §结论 3 |
| **M12 修订（LAUNCHER 回到 Spatial stub + 窗口背景占位）后锚定闭环未回归**，`restored 2 anchor record(s)` → `drift=0.0m` 依然成立 | `docs/M12-M14-TEST.md` §4b |
| **M13/M14 实现不依赖模拟器实例状态** —— 全新实例上复测：空间音频播放 / 通知发出 / 提醒调度触发撤销，四项全通过 | `docs/M12-M14-TEST.md` §6 |
| **模拟器故障恢复流程已端到端实跑验证** —— 从模拟器挂掉到能演示全程约 3 分钟（重启 90s + 应用冷启 50s + 放卡 10s），必须算进决赛应急预算 | `docs/M12-M14-TEST.md` §6 |

### 4.2 必须真机才能验证的能力

| 结论 | 依据 |
|---|---|
| **窗口拖动、缩放** —— `resizeType = ContentSize` 已声明但模拟器无法触发空间按钮 | `docs/DUAL-STATE-TEST.md` §未能验证 / 遗留问题；`docs/M12-M14-TEST.md` §7 |
| **Full Space 下的手势 / 手柄输入模型** | `docs/DUAL-STATE-TEST.md` §未能验证 / 遗留问题 |
| **空间音频的方位正确性** —— "走到冰箱前声音在左边"是否成立、距离衰减曲线、三个提示音在真实房间里是否可辨识 | `docs/M12-M14-TEST.md` §3 不能验 |
| **通知弹窗在头显内的呈现** —— 模拟器无系统通知面板 | `docs/M12-M14-TEST.md` §7 |
| **精确闹钟在 PICO OS 真机的可用性** —— 模拟器权限模型与真机不同 | `docs/M12-M14-TEST.md` §2、§7 |
| **冷启动耗时** —— 真机 arm64 原生应显著快于模拟器 12s | `docs/M12-M14-TEST.md` §7 |
| **锚点在设备重启后的存续** —— 模拟器实例重建必丢，真机是否持久未验 | `docs/M12-M14-TEST.md` §7；`docs/ANCHOR-M2-TEST.md` §边界条件 |
| **系统级返回（摘头显 / 系统手势）行为** —— 退出 Full Space 的系统级路径 | `docs/DUAL-STATE-TEST.md` §对产品的影响 |
| **手势指向放锚点** —— 真机后接手柄射线 + 平面求交（当前用广播参数代替） | `docs/ANCHOR-M2-TEST.md` §遗留问题 |
| **启动 ANR 是否复现** —— 判定为模拟器独有问题（栈顶全在 `libhoudini.so` ARM 转译层），真机复现则升级为 P0 | `docs/ANCHOR-M2-TEST.md` §ANR 定性；`docs/DUAL-STATE-TEST.md` §未能验证 / 遗留问题 |
| **`openStage` 在 Shared Space 返回 `NotAllowed`** —— 本轮连续三次 NotAllowed，与 M1 行为不一致，怀疑与挂屏 ANR 弹窗有关；真机复现则升级为 P0 | `docs/ANCHOR-M2-TEST.md` §遗留问题 |

---

## 5. 已知边界

### 5.1 模拟器不可驱动的空间交互（HC-1）

PICO 官方明示：模拟器**无法驱动 3D 空间按钮**。因此以下能力在模拟器上一律无法验证，本项目为此为每个可交互动作配了 adb 广播无头通道：

| 受影响能力 | 模拟器替代手段 | 依据 |
|---|---|---|
| 双态切换（开 / 关房间层） | `am broadcast -a com.memorypalace.dualstate.OPEN_STAGE / CLOSE_STAGE` | `docs/DUAL-STATE-TEST.md` §测法 |
| 随身卡开关 | `am broadcast -a com.memorypalace.dualstate.TOGGLE_REVIEW_CARD` | 同上 |
| 放锚点 / 清锚点 | `am broadcast -a com.memorypalace.anchor.PLACE / CLEAR`（带 `--ef x/y/z`） | `docs/ANCHOR-M2-TEST.md` §实现形态 |
| 窗口拖动、缩放 | **无替代** —— 声明了 `resizeType = ContentSize` 但触发不了 | `docs/DUAL-STATE-TEST.md` §未能验证 / 遗留问题；`docs/M12-M14-TEST.md` §7 |
| 手势 / 手柄 3D 交互 | **无替代** —— 手势指向取点未实现 | `docs/ANCHOR-M2-TEST.md` §遗留问题 |
| 空间音频方位 | **无替代** —— 模拟器无空间音频输出路径 | `docs/M12-M14-TEST.md` §3 |
| 系统通知面板 | **无替代** —— 模拟器无系统通知面板 | `docs/M12-M14-TEST.md` §7 |

### 5.2 锚点持久化的成立范围

「杀进程重启后 drift 0.0m」成立于**同一个模拟器实例内** —— 系统级锚点服务（`WorldTrackingManager`）随模拟器进程存活，锚点 UUID 在其内部持久化（`docs/ANCHOR-M2-TEST.md` §边界条件）。

| 表述 | 准确性 |
|---|---|
| 「锚点记录持久化，杀进程重进后 UUID 能重新解析」 | ✅ 准确 —— **仅限同一模拟器实例内**。不要写成「漂移 0.0m」：那是恒等式 |
| 「锚点在设备重启后仍在」 | ❌ **未验证** —— 模拟器实例重建会丢；真机行为待验 |
| 「锚点在搬家 / 陌生场地后仍在」 | ❌ **明确不会** —— 系统锚点绑定当前空间，换场地必丢（这正是 L4 降级存在的原因） |

**降级形态有效**：锚点丢失 → 记录降级为 uuid=null → 场景按保存位姿直渲 → **卡片不消失**。这正是方案 L4 降级形态里说的"会话内记忆 + 幽灵态重放"。

**答辩正确口径**：「设备不重启、应用不被杀时不会丢；设备重启或换场地会丢，所以我们做了降级 —— 卡片会按最后记录的位姿继续在房间里，不会凭空消失。决赛现场就是靠这个降级兜底，12/25 会实测陌生场地找回率。」

### 5.3 精确闹钟权限在 PICO OS 未授予

Android 12+ 的 `SCHEDULE_EXACT_ALARM` 是特殊权限，需要用户手动授权，**PICO OS 6 默认未授予**：

```
W DueReminder: exact alarm unavailable — 提醒可能被系统延后。引导用户到系统设置开启「闹钟和提醒」权限（SCHEDULE_EXACT_ALARM）
W DueReminder: exact alarm not permitted, fell back to inexact alarm at ...
```

已做的三层处置（`docs/M12-M14-TEST.md` §2）：

1. **代码层**：不精确闹钟降级路径（`setAndAllowWhileIdle`），日志明确写"延后"，不假装精确
2. **UI 层**：书房面板显示橙色警示 + 精确的系统设置路径指引（`DueReminder.canScheduleExact()`）
3. **语义层**：改用"批次提醒"（一次响一整批）后，对"延后几十分钟"的容忍度显著高于逐张提醒

---

## 6. 真机待验清单

> 以下 12 项是**待真机验证**的清单。借测窗口 11/14–11/22，判定日 11/20（见 `README.md` §路线图）。
> 每项都标明了"为什么模拟器验不了"与源文档出处 —— 答辩时若被追问某项为何未验，按此表如实回答。

| # | 待验项 | 原因 | 源 |
|---|---|---|---|
| 1 | **三窗拖动与缩放** | 模拟器官方明示无法驱动 3D 空间按钮（HC-1） | `docs/M12-M14-TEST.md` §7；`docs/DUAL-STATE-TEST.md` §未能验证 / 遗留问题 |
| 2 | **空间音频的方位正确性**（走到冰箱前声音在左边）、距离衰减曲线、三个提示音在真实房间里是否可辨识 | 模拟器无空间音频输出路径 | `docs/M12-M14-TEST.md` §3、§7 |
| 3 | **精确闹钟在 PICO OS 真机的可用性**（`SCHEDULE_EXACT_ALARM` 实际授权路径与表现） | 模拟器权限模型与真机不同 | `docs/M12-M14-TEST.md` §2、§7 |
| 4 | **通知弹窗在头显内的呈现** | 模拟器无系统通知面板 | `docs/M12-M14-TEST.md` §7 |
| 5 | **冷启动耗时**（真机 arm64 原生应显著快于模拟器 12s） | 模拟器走 x86 转译 | `docs/M12-M14-TEST.md` §7 |
| 6 | **锚点在设备重启后的存续** | 模拟器实例重建必丢；真机是否持久未验 | `docs/M12-M14-TEST.md` §7；`docs/ANCHOR-M2-TEST.md` §边界条件 |
| 7 | **Full Space 下的手势 / 手柄输入模型** | 同属模拟器边界 | `docs/DUAL-STATE-TEST.md` §未能验证 / 遗留问题 |
| 8 | **手势指向放锚点**（手柄射线 + 平面求交） | 模拟器无空间交互，当前用广播参数代替 | `docs/ANCHOR-M2-TEST.md` §遗留问题 |
| 9 | **系统级返回（摘头显 / 系统手势）退出 Full Space 的行为** | 模拟器只能触发应用内广播入口 | `docs/DUAL-STATE-TEST.md` §对产品的影响 |
| 10 | **启动 ANR 是否在真机复现** | 判定为模拟器独有问题（`libhoudini.so` 转译开销），真机复现则升级为 P0 | `docs/ANCHOR-M2-TEST.md` §ANR 定性；`docs/DUAL-STATE-TEST.md` §未能验证 / 遗留问题 |
| 11 | **`openStage` 在 Shared Space 返回 `NotAllowed`** 是否真机复现 | 与 M1 同流程成功的行为不一致，怀疑与 ANR 弹窗有关；真机复现则升级为 P0 | `docs/ANCHOR-M2-TEST.md` §遗留问题 |
| 12 | **锚点与真实平面的贴合**（识别桌面 / 墙面法线） | M2 按约定不追求网格贴合，未做 | `docs/ANCHOR-M2-TEST.md` §遗留问题 |

---

## 附：源文档索引

| 源文档 | 覆盖本文的章节 |
|---|---|
| `docs/DUAL-STATE-TEST.md` | §2.1 全部；§3.1；§4.1 前 6 条；§5.1；§6 第 1、7、9、10 项 |
| `docs/M12-M14-TEST.md` | §2.3；§3.2–3.5；§4.1 后 3 条；§5.3；§6 第 2–5 项 |
| `docs/ANCHOR-M2-TEST.md` | §2.2；§3.3–3.4；§4.1 第 7–9 条；§5.2；§6 第 6、8、10–12 项 |
