# M2 房间锚定最小闭环实测结论

> 实测日期：2026-11-07 · 环境：PICO Emulator 6.1.0（AVD Pico_Emulator_6_1, swan_x86_64）· Spatial SDK 6.1.9 · APK：app-debug（M2）
> M2 验收口径：① 锚定数据进持久层；② Stage 重建后按 UUID 恢复；③ 锚点坐标与渲染位置一致。

## 实现形态

- `anchor/AnchorStore.kt`：持久层（SharedPreferences + JSON，M3 迁移 Room）。记录 = key（卡片占位 ID）+ 锚点 UUID + 位姿 + 标题。
- `anchor/AnchorScene.kt`：组合之外的全局单例，持有锚定记录 StateFlow；放置/清除/恢复校验全在这里（M1 结论 2 的直接落地：Stage 必重建，运行态必须外置）。
- `ui/RoomStage.kt`：SpatialView attachments 按记录声明卡片面板，initial 里把面板实体挂到 `AnchorEntity(AnchorTarget.createWorldAnchorTarget(uuid))`；锚点服务不可用的记录按保存位姿直渲并标注「未锚定（回退位姿）」。
- 模拟器无法指向交互（官方明示），放置走 adb 广播无头驱动：
  `am broadcast -a com.memorypalace.anchor.PLACE --ef x 0.5 --ef y 1.3 --ef z -1.2 --es title "ephemeral adj.短暂的"`，清除用 `…anchor.CLEAR`。

## 实测序列与证据

| 步骤 | 操作 | 结果（logcat 原文） |
|---|---|---|
| 1 | 启动即放置（13:53:06） | `createAnchor ERROR code=-6 "The provider is not ready."` → 记录以**回退位姿**持久化（uuid=null） |
| 2 | 24 秒后再次放置 | `createAnchor SUCCESS uuid=e71357b4-… resolvedPos=(0.5,1.3,-1.2)`，drift=0.0m |
| 3 | 截图 m2-01 | 两卡均钉在预期位置：「ephemeral」在右侧架子上方（0.5,1.3,-1.2），「abandon」在床面（0,1.2,-1.5） |
| 4 | `am force-stop` 杀进程，重新冷启动（新 PID） | `restored 2 anchor record(s) from store` → 两条记录全部恢复渲染 |
| 5 | 恢复校验 | `verifyRestored: uuid=e71357b4-… saved=(0.5,1.3,-1.2) resolved=(0.5,1.3,-1.2) drift=0.0m` |
| 6 | 截图 m2-04 | 与 m2-01 逐字节一致：两张卡原位重现 |

截图：`media/screenshots/m2-01-anchored-full.jpg`（放置后）、`m2-04-after-process-restart.jpg`（杀进程重启后）。

## 结论（封板）

1. **锚定最小闭环通**：放置 → 持久化 → 杀进程 → 重启 → 按 UUID 恢复 → 渲染位置与保存位姿漂移 0.0m。这覆盖了 Stage 重建（每次进入 Full Space 必重建）的更强情形——进程都没了照样恢复。
2. **锚点服务冷启动有延迟**：应用启动后头几十秒 `createAnchor` 返回 `code=-6 The provider is not ready`，之后正常。产品含义：进入房间层后「放置」按钮要有就绪状态管理（灰态/重试），不能假设随时可放。已按回退位姿兜底。
3. **回退路径有效**：uuid=null 的记录按保存位姿直渲，视觉位置正确（m2-01 床面卡片）。真机上锚点丢失（搬家/陌生场地）时可用同一机制降级。

## ANR 定性（M1 遗留，本轮查清）

拉取 `/data/anr/` 两个 trace，均为 `com.memorypalace` 且 subject = **"failed to complete startup"**。主线程栈：

```
System.loadLibrary ← com.pico.spatial.runtime.Client.<clinit> (Client.java:36)
← SpatialSessionController.initSpatialInstance
← SpatialApp.initialize ← SpatialAppScopeKt.launch
← MemoryPalaceApplication.onCreate
```

栈顶全部在 **libhoudini.so**（x86_64 模拟器的 ARM 转译层）：我们的 APK 只编 arm64-v8a（SDK 要求），模拟器上所有原生库经 houdini 转译，SDK 引擎初始化在 `onCreate` 主线程耗时过长，触发启动 ANR 弹窗。**应用逻辑无死锁**——弹窗挂屏期间应用正常响应全部广播、完成锚点创建与恢复（见上表，所有操作都发生在弹窗存在期间）。真机 arm64 原生执行无此转译开销，判断为**模拟器独有问题**，降级为观察项；真机到位后复测（借测 11/20 判定）。

## 遗留问题（如实记录）

- **`openStage` 在 Shared Space 返回 `NotAllowed`**：本轮 closeStage 成功后，openStage 连续三次 NotAllowed（包括先开窗口再调），与 M1（同流程成功）行为不一致。怀疑与挂屏的 ANR 弹窗有关（系统判定应用无响应时拒绝进入 Full Space）。临时绕过：杀进程重启即恢复（重启后直接进 Full Space）。待真机验证；若真机复现则升级为 P0。
- 「用户指向平面放锚点」的**手势指向取点**未实现：模拟器无空间交互，当前用广播参数代替。真机后接手柄射线 + 平面求交。
- 锚点与真实平面的贴合（识别桌面/墙面法线）未做，M2 按约定不追求网格贴合。
