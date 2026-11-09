# M1 双态行为实测结论（Shared Space ↔ Full Space 窗口生命周期）

> 📌 **本文是 `docs/DUAL-STATE-VERIFICATION.md` 的原始实测记录** —— 后者是双态行为的正式结论文档（文件名由作战方案 §6.1 指定），从本文及 `docs/M12-M14-TEST.md`、`docs/ANCHOR-M2-TEST.md` 提炼而成，对外引用请用后者。

> 实测日期：2026-11-07 · 环境：PICO Emulator 6.1.0（AVD Pico_Emulator_6_1, swan_x86_64）· Spatial SDK 6.1.9 · APK：app-debug（M1 三窗空壳）
> 对应校验清单 #4（双态切换窗口是否重建 = L2 降级触发点）——**本条现在有实测答案了。**

## 测法

三窗空壳应用（`com.memorypalace`）：书房面板（WindowContainer `study_panel`）、随身复习卡（WindowContainer `review_card`）、房间层（DefaultStage `room_stage`, Mixed 模式）。每个组件内置探针：

- 组合时领取实例号（`DualStateProbe.claim`），实例号递增 = 销毁重建的直接证据
- 全生命周期事件（ON_CREATE/START/RESUME/PAUSE/STOP/DESTROY + 组合 DISPOSE）打 logcat（tag `DualState`）并同步到屏上日志
- 因模拟器无法驱动空间按钮（官方明示），切换动作用 adb 广播无头驱动：
  `am broadcast -a com.memorypalace.dualstate.OPEN_STAGE / CLOSE_STAGE / TOGGLE_REVIEW_CARD`

实测序列：启动（T0）→ 打开双窗口（T1）→ closeStage（T2）→ openStage（T3）→ 随身卡关闭再打开（T4）→ 完整第二轮循环（T5）。

## 观察到的现象（logcat 原文摘录）

| 时刻 | 动作 | 房间层 (room_stage) | 书房面板 (study_panel) | 随身卡 (review_card) |
|---|---|---|---|---|
| T0 启动 | — | CLAIM #1 → RESUME | 未创建 | 未创建 |
| T1 | openWindowContainer ×2 | 无事件 | CLAIM #1 → RESUME | CLAIM #1 → RESUME |
| T2 | closeStage | PAUSE→STOP→**DESTROY→DISPOSE** | **无任何事件** | **无任何事件** |
| T3 | openStage | **CLAIM #2** → RESUME（重建） | 无任何事件 | 无任何事件 |
| T4 | 关闭再开随身卡 | 无事件 | 无事件 | DESTROY→DISPOSE → **CLAIM #2** |
| T5 | closeStage→openStage | DESTROY→**CLAIM #3** | 无任何事件 | 无任何事件 |

截图证据（`media/screenshots/`）：
- `m1-02-full-with-windows.jpg`：Full Space 下房间画面 + 书房面板 + 随身卡**三容器同时可见**
- `m1-03-shared-space.jpg`：closeStage 后 Shared Space，双窗口仍在
- `m1-05-cycle2-full.jpg`：两轮循环后书房面板仍显示「实例号 #1」、随身卡「实例号 #2」——屏上实例号与 logcat 一致

## 结论（封板）

1. **WindowContainer 在 Shared ↔ Full 切换中存活，不销毁不重建。** 切换全程窗口收不到任何生命周期事件（连 PAUSE 都没有），实例号不变，`remember` 状态保留。双态架构（Shared 跑书房+随身卡、Full 跑房间层）**可行，不需要 L2 降级**。
2. **Stage 每次进入 Full Space 都销毁重建**（实例号 #1→#2→#3）。推论：房间层的一切运行态（锚点引用、场景实体、播放状态）必须外置到组合之外（ViewModel/单例/持久层），进入时重建恢复。这直接决定 M2 锚定模块的架构：锚点 UUID 存仓库层，Stage 重建后按 UUID 重新解析。
3. **窗口可独立开关，互不干扰**：关闭重开随身卡时书房面板零事件。关窗 = 销毁（实例号递增），即窗口级状态同理要外置。
4. **默认容器全局只能有一个**：`DefaultStage` 与 `DefaultWindowContainer` 共存会在启动时崩溃（`IllegalStateException: DefaultWindowContainer registered already`）。默认容器必须给 Stage，窗口用具名 `WindowContainer`。
5. **DSL 声明窗口 ≠ 打开窗口**：声明了 Stage 时应用直接启动进 Full Space，窗口不创建，需 `Context.openWindowContainer(id, tag)` 显式打开（官方 SpatialAudio Sample 也是这个模式——在 Stage ON_RESUME 里打开面板）。
6. `Context.openStage(id, StageStyle.Mixed)` 返回 `OpenStageResult.Allowed`，可用返回值做失败兜底。

## 未能验证 / 遗留问题（如实记录）

- **窗口拖动、缩放未验证**：模拟器驱动不了空间按钮（官方明示），`resizeType = ContentSize` 已声明但无法触发。留待真机（借测 11/20 判定）。
- **出现一次 ANR 弹窗**（「记忆宫殿没有响应」）：首个窗口打开时弹出一次，之后应用持续正常响应所有广播与生命周期回调，弹窗因模拟器无法点击而未消失、一直挂在画面里。`/data/anr/` 无新 trace。初步判断是首次窗口组合时主线程短暂繁忙触发，需后续优化（首屏组合减重），不阻塞 M1 结论，但**若真机上复现则升级为 P0**。
- Full Space 下手势/手柄输入模型未验（同属模拟器边界）。

## 对产品的影响

- 三窗架构按当前形态继续：书房面板（Shared 常驻）+ 随身卡（可开关）+ 房间层（Stage）。**L2 降级方案（单窗口 + 可召唤浮层）保留为备案，不启动。**
- M2 锚定：锚点数据（UUID、家具语义、卡片绑定）放 `data/` 层 Room 持久化，Stage 每次重建时恢复——架构硬性约束，已写入上述结论 2。
- 双态切换入口：产品内用「进入房间层」按钮（窗口内 2D 按钮）调 `openStage`；系统级返回（摘头显/系统手势）行为待真机补测。
