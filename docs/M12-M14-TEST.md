# M12–M14 实测结论（2026-11-09）

> 实测环境：PICO Emulator 6.1.0（AVD `Pico_Emulator_6_1`，x86 转译）
> APK：debug，包名 `com.memorypalace`
> 证据：logcat 原文摘录 + 逐条命令

## 0. 结论速览

| 功能 | 状态 | 关键证据 |
|---|---|---|
| 应用冷启动（修复 M12 回归后） | ✅ | 进程存活，无 AndroidRuntime 异常 |
| 三窗自动开启 | ✅ | `autoOpenOnce: windows opened at attempt 1` |
| 房间层实体建立 | ✅ | `[room_stage] inst#1 SpatialView.initial anchors=1` |
| **空间音频资源加载** | ✅ | `SpatialCue: audio resource loaded: cue_due` |
| **三个提示音播放** | ✅ | `play(cue_due/cue_place/cue_done) on entity at ...` |
| **到期通知发出** | ✅ | `DueNotifier: due notification posted: count=55 top="ephemeral"` |
| **提醒调度 + 触发** | ✅ | `reminder overdue-immediate: fireAt=... (5s from now)` → `alarm fired, posting due notification` |
| **提醒撤销** | ✅ | `due notification cancelled` / `reminder cancelled` |
| 精确闹钟 | ❌ 不可用 | `exact alarm not permitted, fell back to inexact alarm` —— PICO OS 未授予 SCHEDULE_EXACT_ALARM |

## 1. 本轮抓到并修掉的两个真实 bug

### Bug 1：M12 引入的启动即崩（严重）

**现象**：应用一启动就被 SIGKILL（signal 9），logcat 里先有未捕获异常：

```
E AndroidRuntime: java.lang.IllegalStateException: Stage(room_stage) not registered
E AndroidRuntime: Process 4440 exited due to signal 9 (Killed)
```

**根因**：M12 为了消除冷启动 12 秒黑屏，把 LAUNCHER 入口从 `LaunchActivity`（Spatial stub）换成了普通 `SplashActivity`，并把 `pico.spatial.stage.id` meta-data 留在 `LaunchActivity` 上。SDK 在 LAUNCHER 启动时读 meta-data 注册 Stage —— meta-data 不在 LAUNCHER 上，Stage 根本不注册。

**第一次修错**：把 meta-data 挪到 `SplashActivity`，错误变成：

```
FATAL EXCEPTION: main
java.lang.IllegalStateException: Only support [SUIStage,SUIWindowContainer, but got a
[name = PICO_SYSTEM_DEFAULT_WINDOWCONTAINER, class = ... WindowContainer].
You cannot merely register non-default containers in the Manifest; you also need to register them in the DSL.
```

**真正的根因**：空间应用的入口容器由 SDK 在 LAUNCHER 启动时创建。LAUNCHER 一旦是普通 Activity，SDK 就先建出默认 WindowContainer；之后 `LaunchActivity` 想注册 Stage 就与之冲突。**官方三个 Sample 全部是「LAUNCHER = SpatialLaunchActivity，stage meta-data 挂在它上面」—— 这是 SDK 契约，不是示例写法。**

**最终修法**（M12 修订）：
1. LAUNCHER 回到 `LaunchActivity`（Spatial stub），stage meta-data 挂回它上面
2. 冷启动黑屏改用**窗口背景占位**：manifest 主题非透明（`Theme.MpSplash`，`windowBackground=@drawable/mp_splash_bg`），系统据此在进程起来前画 starting window；`onCreate` 里 `setTheme(Theme.SpatialApp)` 切回透明
3. 删掉 `SplashActivity.kt` 与 `mp_splash.xml`

**为什么不用 `setContentView` 占位**：SDK 的 `SpatialStubActivity.onCreate` 内部会调 `setSpatialContent()`（反编译确认），我们再 `setContentView` 会覆盖 SDK 的内容视图。占位只能靠窗口背景。

### Bug 2：提醒被排到过去的时间（功能缺陷）

**现象**：

```
I DueReminder: reminder scheduled: fireAt=1794134827030 (-24965s from now) dueCount=55
```

`-24965s` = 约 7 小时前。原因是调度取"最后一张到期卡"的 `dueAt`，而到期队列里的卡**全部已经逾期**（用户拖了几天没复习）→ 排了个过去时刻的闹钟 → `setExact` 立刻触发 → "刚排上就响"，而且每次重排重复发生。

**修法**：区分两种语义：

| 情况 | 提醒时刻 | 日志 |
|---|---|---|
| 还有**未来**到期的卡 | 那个时刻（"到点了"） | `reminder scheduled` |
| 全部已逾期 | 立刻（+5s，给用户戴头显的时间） | `reminder overdue-immediate` |

**实测确认**：

```
I DueReminder: reminder overdue-immediate: fireAt=1794160067985 (5s from now) dueCount=55 futureCount=0 top=ephemeral
I DueReminder: alarm fired, posting due notification
```

## 2. 精确闹钟在 PICO OS 上不可用（降级已生效）

```
W DueReminder: exact alarm unavailable — 提醒可能被系统延后。引导用户到系统设置开启「闹钟和提醒」权限（SCHEDULE_EXACT_ALARM）
W DueReminder: exact alarm not permitted, fell back to inexact alarm at ...
```

Android 12+ 的 `SCHEDULE_EXACT_ALARM` 是特殊权限，需要用户手动授权。PICO OS 6 默认未授予。

**已做的处置**：
1. 代码层：不精确闹钟降级路径（`setAndAllowWhileIdle`），日志明确写"延后"，不假装精确
2. UI 层：书房面板显示橙色警示 + 精确的系统设置路径指引（`DueReminder.canScheduleExact()`）
3. 语义层：改用"批次提醒"（一次响一整批）后，对"延后几十分钟"的容忍度显著高于逐张提醒

**待真机确认**：PICO OS 6 真机上的实际表现可能与模拟器不同（模拟器的权限模型与真机有差异）。

## 3. 空间音频：模拟器能验什么，不能验什么

**能验**（本次已验）：
- `AudioResource.load(FROM_ASSETS)` 成功加载 wav 资产
- `Entity.prepareAudio()` 拿到 `AudioPlayerController`
- `ObjectAudioComponent` 装到锚定卡片实体上
- `controller.play()` 调用成功，无异常

**不能验**（需真机）：
- 声音的**方位**是否正确（"走到冰箱前声音在左边"）
- 距离衰减曲线是否符合预期
- 三个提示音在真实房间里是否可辨识、是否刺耳

模拟器无空间音频输出路径，这与 §5.2 的既有结论一致。

## 4. 无头验证命令（全部实测通过）

```bash
# 应用启动
adb shell am start -n com.memorypalace/.app.LaunchActivity

# 空间提示音（cue_due / cue_place / cue_done）
adb shell am broadcast -a com.memorypalace.audio.PLAY --es cue cue_due
adb shell am broadcast -a com.memorypalace.audio.LIST

# 到期提醒
adb shell am broadcast -a com.memorypalace.notify.SCHEDULE   # 排提醒
adb shell am broadcast -a com.memorypalace.notify.TEST       # 立刻试响
adb shell am broadcast -a com.memorypalace.notify.CANCEL     # 撤提醒
```

## 5. 本轮验证中新加的 CI 静态检查

启动即崩这类 bug 编译期抓不到，但**manifest 层面的约定可以**。CI 新增检查项：

- `pico.spatial.stage.id` / `pico.spatial.stage.style` 必须挂在 **LAUNCHER activity** 上
- 且**不能**挂在其他 activity 上（双份会互相覆盖）

这项检查在本次修复前会失败、修复后通过 —— 它防的是同类回归。

## 6. 待真机验证清单（本轮新增/变更）

| 项 | 原因 |
|---|---|
| 空间音频的**方位正确性** | 模拟器无空间音频输出 |
| 精确闹钟在 PICO OS 真机的可用性 | 模拟器权限模型与真机不同 |
| 通知弹窗在头显内的呈现 | 模拟器无系统通知面板 |
| 冷启动耗时（真机 arm64 原生应显著快于模拟器 12s） | 模拟器走 x86 转译 |
| 三窗拖动缩放 | 模拟器官方明示无法驱动 3D 空间按钮（HC-1） |
