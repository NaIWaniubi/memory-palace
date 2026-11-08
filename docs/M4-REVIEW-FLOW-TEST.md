# M4 复习流实测结论

日期：2026-11-07 ｜ 环境：PICO Emulator 6.1.0（emulator-5556）｜ 构建：assembleDebug 1m19s

## 范围

复习流闭环：进入复习 → 到期卡逐张出现 → 翻面 → 自评（记得/忘了）→ SM-2 调度更新 → 下一张 → 队列走完自动结束；空间锚定卡按记忆强度改变外观（清晰→褪色）；复习模式当前卡高亮、其余压暗。

## 实现

- `review/ReviewSession.kt`：复习会话状态机（组合之外单例，Stage/窗口重建不丢进度）。队列=当日到期队列快照，`state: StateFlow<State?>`（queue/index/revealed）。
- `ui/ReviewCard.kt`：复习主界面（进度 x/N、正面→翻面→背面+调度参数→记得/忘了）。
- `ui/AnchorCard.kt`：外观随记忆强度变化。遗忘程度 = 逾期时长/max(间隔,1d)，映射透明度 1.0→0.3、文字白→灰；复习模式当前卡青色"▶"高亮、其余压暗至 0.3 倍。
- `data/Clock.kt`：新增偏移广播入口（`clock.SET_OFFSET --el days N`）+ `tick` 流驱动 UI 重算——M4 用于验证褪色，M6 时间机器正式用。
- 书房面板到期队列跟随 Clock.tick 重算，头部加"开始复习"按钮/复习进度。

无头驱动（模拟器无空间交互，全程广播验证）：

```
am broadcast -a com.memorypalace.review.START / REVEAL / EXIT
am broadcast -a com.memorypalace.review.GRADE --es grade remember|forgot
am broadcast -a com.memorypalace.clock.SET_OFFSET --el days 7
```

## 证据

### 1. 复习流 + 调度更新（logcat，14:58–15:03）

```
Review  : start: 3 card(s) due, first=serendipity id=92f1a379
Review  : reveal: serendipity back=n.意外之喜
Cards   : card reviewed id=92f1a379 grade=REMEMBERED reps=1 ef=2.50 interval=1.0d
Review  : graded: serendipity grade=REMEMBERED (1/3)
Review  : reveal: mnemonic back=n.助记术
Cards   : card reviewed id=af275b7a grade=FORGOTTEN reps=0 ef=1.96 interval=0.0d
Review  : graded: mnemonic grade=FORGOTTEN (2/3)
Cards   : card reviewed id=4460df58 grade=REMEMBERED reps=1 ef=1.96 interval=1.0d
Review  : session complete: 3 card(s) reviewed
```

调度语义全部正确：记得→reps+1、间隔 1d；忘了→reps 清零、easiness 2.50→1.96、10 分钟重学。

### 2. 记忆强度外观（截图对比）

- `m4_7_1.jpg` / `m4_7_3.jpg`：偏移 0d，锚定卡（mnemonic/ephemeral/abandon）清晰白字。
- `m4_8_faded_7d.jpg`：`SET_OFFSET 7` 后同机位，卡片整体褪色变暗（fade→1，alpha→0.3）。
- `m4_9_review_mode_room.jpg`：+7d 下 `review.START`（4 张到期：3 张原卡 + 忘了的 mnemonic 重学到期），书房面板到期队列由 0 堆到 4 张，非当前卡压暗。

### 3. 生命周期

- 进程重启后复习会话注册正常、锚点恢复 drift=0.0m（15:04 logcat）。
- Clock 偏移为进程态，重启归零（符合预期，时间机器是演示模式不是持久状态）。

## ANR 弹窗定性（应要求查清，硬证据）

拉取 `/data/anr/anr_2026-11-07-14-17-24-426`：**不是应用真卡，是模拟器资源问题**。

- Subject：`failed to complete startup`（启动超时，window_ms=300000）
- 主线程栈：`MemoryPalaceApplication.onCreate → SpatialApp.initialize → SpatialSessionController.initSpatialInstance → System.loadLibrary (com.pico.spatial.runtime.Client.<clinit>)`——卡在 SDK 强制的主线程引擎库加载
- 反证：弹窗挂着的同时，应用主线程对全部广播（REVEAL/GRADE/SET_OFFSET）秒级响应——主线程早已恢复，弹窗是系统侧未回收的残留面板
- 结论：x86_64 模拟器 + 宿主 5.6GB 内存下 SDK 初始化慢触发启动 ANR；`input tap`/`CLOSE_SYSTEM_DIALOGS` 均无法消除该空间系统面板。取证手法：重启后趁弹窗未起截图（约 2 分钟窗口期）。真机（arm64 原生）复测前维持此定性。

## 遗留

- 空间交互（指向卡片翻面/自评）模拟器不可验，待真机；当前交互面在随身卡窗口（2D 可点）+ 广播。
- `key(records)` 重建 SpatialView 时卡片外观状态由 AnchorCard 自行收集流恢复，无需额外处理。
