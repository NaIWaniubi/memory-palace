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
| **锚定闭环回归（放卡→杀进程→重进→找回）** | ✅ | `restored 2 anchor record(s)` → `verifyRestored: ... drift=0.0m` |
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

# 锚定闭环回归（放卡 → 杀进程 → 重进 → 找回）
adb shell am broadcast -a com.memorypalace.anchor.PLACE \
    --ef x 0.0 --ef y 1.2 --ef z -1.5 --es front "persist-test" --es back "v.测试"
adb shell am force-stop com.memorypalace
adb shell am start -n com.memorypalace/.app.LaunchActivity
```

## 4b. 锚定闭环回归实测（M12 修复后的完整复核）

M12 改动了 manifest 与入口 Activity（启动路径），所以把**创新维度最硬的那条证据**重新跑了一遍，确认没被改坏：

```
04:04:03 I Anchor: createAnchor requested pos=(0.0,1.2,-1.5) card=e875ac3e
04:04:03 I Anchor: createAnchor SUCCESS uuid=a572e6cb-... resolvedPos=(0.0,1.2,-1.5)
04:04:05 I Anchor: verifyRestored: uuid=a572e6cb-... saved=(0.0,1.2,-1.5) resolved=(0.0,1.2,-1.5) drift=0.0m
        ↓ adb shell am force-stop com.memorypalace
04:05:26 I Anchor: restored 2 anchor record(s) from store
04:05:41 I Anchor: verifyRestored: uuid=a572e6cb-... saved=(0.0,1.2,-1.5) resolved=(0.0,1.2,-1.5) drift=0.0m
```

**放卡 → 杀进程 → 重进 → 找回，drift 仍然 0.0m。** 这是 `docs/COMPETITORS.md` §5.2 差异点 1 的证据支撑 —— AR 词典类竞品靠实时相机识别，移开相机就失效；我们是系统级持久锚点，这一轮复核确认该结论在 M12 修订后依然成立。

> ⚠️ **成立范围**：同一模拟器实例内。模拟器实例重建后系统锚点会丢（降级路径正常工作，卡片不消失）—— 详见 `docs/ANCHOR-M2-TEST.md`「边界条件」一节。

同一次启动里还顺带验证了：
- 冷启动占位（M12 修订版）不黑屏，进程存活无异常
- 三窗自动开启 attempt 1 成功
- 房间层实体建立 `anchors=1`
- 空间音频资源加载 `audio resource loaded: cue_due`

## 5. 本轮验证中新加的 CI 静态检查

启动即崩这类 bug 编译期抓不到，但**manifest 层面的约定可以**。CI 新增检查项：

- `pico.spatial.stage.id` / `pico.spatial.stage.style` 必须挂在 **LAUNCHER activity** 上
- 且**不能**挂在其他 activity 上（双份会互相覆盖）

这项检查在本次修复前会失败、修复后通过 —— 它防的是同类回归。

## 6. 模拟器故障恢复手册（2026-11-09 实测踩坑，决赛前必读）

模拟器被内存压力杀掉后重启，`--watch` **不会自动救回来** —— 它会拒绝启动，理由是"检测到另一个实例"。这个坑在决赛投屏时就是事故。

### 症状与日志

```
Entering emulator watch mode. If the process exits or adb goes offline, the CLI will try to restart it automatically.
Detected an abnormal emulator process or adb state. Preparing to restart the emulator.
Detected emulator exit or offline adb; restart attempted.
ADB command timed out after 30s: adb devices -l
Detected an abnormal emulator process or adb state. Preparing to restart the emulator.
Error: Unable to stop abnormal emulator Pico_Emulator_6_1 before restart; refusing to launch a second instance.
```

同时 `adb devices` 里设备显示 **offline**，或干脆列不出设备。

### 根因

qemu 进程还活着（占 ~1 GB 内存）但 adb 状态已断 —— **僵尸实例**。`--watch` 检测到"已有实例"就拒绝拉起第二个。

### 处置（实测有效）

```bash
# 1. 找出僵尸进程（emulator.exe + qemu-system-x86_64.exe）
tasklist | grep -iE "emulator|qemu"

# 2. 杀掉（Git Bash 里 /PID 会被转成路径，用双斜杠）
taskkill //PID <qemu-pid> //F
taskkill //PID <emulator-pid> //F

# 3. 重启
pico-cli emulator start --avd Pico_Emulator_6_1 --watch
```

### 两个实测细节

| 细节 | 说明 |
|---|---|
| **Git Bash 的 `taskkill /PID` 会被解析成路径** | `taskkill /PID 123` → `Invalid argument/option - 'C:/Program Files/Git/PID'`。用 `//PID` 或 `cmd //c taskkill /PID ...` |
| **模拟器端口不固定** | 同一台机器上出现过 5554 与 5556。**脚本里不要硬编码 `emulator-5556`**，用 `adb devices` 动态取，或 `adb -s $(adb devices \| awk 'NR==2{print $1}')` |

### 恢复后必须重跑的两件事

模拟器实例重建会丢系统级锚点（见 `docs/ANCHOR-M2-TEST.md` 边界条件），所以：

1. **重新放卡**：丢卡前跑 `am broadcast -a com.memorypalace.anchor.PLACE --ef x 0.0 --ef y 1.2 --ef z -1.5 --es front "..." --es back "..."`
2. **重验 drift**：重启后看 logcat 的 `verifyRestored: ... drift=0.0m`

### 恢复流程端到端实测（2026-11-09，本手册已实跑验证）

按上面三步完整跑了一遍，每步都有日志：

```
步骤 1  taskkill //PID <qemu-pid> //F
        SUCCESS: The process with PID 356504 has been terminated.   ← 两个进程都杀成功
        进程计数复查 → 0

步骤 2  pico-cli emulator start --avd Pico_Emulator_6_1 --watch
        adb devices → emulator-5554  device
        getprop sys.boot_completed → 1                              ← 开机完成

步骤 3  am start -n com.memorypalace/.app.LaunchActivity
        I Anchor: restored 2 anchor record(s) from store           ← 应用层数据没丢
        I Anchor: verifyRestored: no uuid-bound records, skip      ← 系统锚点已随实例重建丢失（预期）

步骤 4  am broadcast -a com.memorypalace.anchor.PLACE --ef x 0.0 --ef y 1.2 --ef z -1.5 ...
        I Anchor: createAnchor SUCCESS uuid=1c06daa0-... resolvedPos=(0.0,1.2,-1.5)
        I Anchor: verifyRestored: uuid=1c06daa0-... saved=(0.0,1.2,-1.5) resolved=(0.0,1.2,-1.5) drift=0.0m
```

**结论：这套恢复流程是可用的，不是纸上谈兵。** 从模拟器挂掉到能演示，全程约 3 分钟（重启 90 秒 + 应用冷启 50 秒 + 放卡 10 秒）。**这 3 分钟必须算进决赛的应急预算** —— 超出 60 秒就切兜底视频。

**一个正面发现**：应用层数据（卡片、锚定记录）**完全没丢**（`restored 2 anchor record(s)`）—— 丢的只有系统级锚点绑定。这正是"降级路径按设计工作"的最好证明：卡片仍在、位置仍在、只是不再绑定系统锚点。

### 恢复后的 M13/M14 全链路复测（同一次恢复后立刻做）

模拟器实例重建相当于一次「全新设备」，所以借这个时机把 M13/M14 在**干净状态**下再验一遍 —— 证明之前的结论不是偶然：

```
I SpatialCue: play(cue_due) on entity at 73234514
I DueNotifier: due notification posted: count=57 top="ephemeral"
W DueReminder: exact alarm unavailable — 提醒可能被系统延后。引导用户到系统设置开启「闹钟和提醒」权限
W DueReminder: exact alarm not permitted, fell back to inexact alarm
I DueReminder: reminder overdue-immediate: fireAt=... (5s from now) dueCount=57 futureCount=0
I DueReminder: alarm fired, posting due notification
I DueNotifier: due notification posted: count=57 top="ephemeral"
I DueNotifier: due notification cancelled
I DueReminder: reminder cancelled
```

**空间音频播放 / 通知发出 / 提醒调度触发撤销，四项全通过**，且行为与首次实测完全一致（含精确闹钟不可用时的降级路径）。**M13/M14 的实现不依赖模拟器实例的状态** —— 换设备重跑依然成立。

**这正是决赛现场的操作顺序** —— 模拟器挂了重启之后，评委不会给你时间重放三分钟视频。演练时必须把这个恢复流程跑一遍。

## 7. 待真机验证清单（本轮新增/变更）

| 项 | 原因 |
|---|---|
| 空间音频的**方位正确性** | 模拟器无空间音频输出 |
| 精确闹钟在 PICO OS 真机的可用性 | 模拟器权限模型与真机不同 |
| 通知弹窗在头显内的呈现 | 模拟器无系统通知面板 |
| 冷启动耗时（真机 arm64 原生应显著快于模拟器 12s） | 模拟器走 x86 转译 |
| 三窗拖动缩放 | 模拟器官方明示无法驱动 3D 空间按钮（HC-1） |
| **锚点在设备重启后的存续** | 模拟器实例重建必丢；真机是否持久未验（见 ANCHOR-M2-TEST 边界条件） |
