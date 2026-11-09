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
| 6 | 截图 m2-04 | 与 m2-01 **像素一致**（本次实测里两者是同一份截图文件，见下方更正） |

截图：`media/screenshots/m2-01-anchored-full.jpg`（放置后）。

> **更正（2026-11-09 复核）**：原文把「m2-04 与 m2-01 逐字节一致」当成「原位重现」的证据，这是错的 —— 那两个文件 MD5 相同，是同一份截图。**截图一致性在这里证明不了任何事**：模拟器的 `createAnchor` 会把请求位姿原样回显，所以「重启后画面一样」是必然结果，不构成独立证据。
>
> 这条证据链里**真正能用的只有 logcat**：`restored N anchor record(s)` 证明记录持久化成功，`verifyRestored: ... resolved=(...)` 证明 UUID 能被重新解析。**视觉对比这一环作废**，已从结论里移除。重复的截图文件已删除。

## 结论（封板）

1. **锚点记录持久化通**：放置 → 写 SharedPreferences → 杀进程 → 重启 → `restored N anchor record(s)` → 按 UUID 重新解析并渲染。**这是「状态没丢」，不是「位置精度高」** —— 见下节的恒等式说明。
2. **锚点服务冷启动有延迟**：应用启动后头几十秒 `createAnchor` 返回 `code=-6 The provider is not ready`，之后正常。产品含义：进入房间层后「放置」按钮要有就绪状态管理（灰态/重试），不能假设随时可放。已按回退位姿兜底。
3. **回退路径有效**：uuid=null 的记录按保存位姿直渲，卡片不消失。真机上锚点丢失（搬家/陌生场地）时可用同一机制降级。

## ⚠️ drift 0.0m 是恒等式，不是精度（2026-11-09 复核修正）

**这条必须先说清楚，否则会被当成伪造。**

模拟器里 `WorldTrackingManager.createAnchor(pos)` 返回的位姿就是请求时给的 `pos`，`loadAnchor` 再把同一个 `pos` 还回来。所以 `saved - resolved = 0` 是**数学恒等式**，不是测量结果。

它证明了什么：
- ✅ 锚点 UUID 创建成功、能被持久化、能在进程重启后重新解析
- ✅ 应用层的记录-恢复状态机是通的（这才是 M2 要验的东西）

它**没有**证明什么：
- ❌ 空间精度。真实头显上锚点会因漂移产生厘米级偏差，模拟器测不出来
- ~~❌ 「卡片贴在冰箱上」这件事~~ —— **2026-11-10 已由 M16 补齐**：平面检测 + 家具语义 + 射线取点已实现并在模拟器实测通过（57 个平面、桌面命中、真实锚点）。见 [PLANE-ANCHOR.md](PLANE-ANCHOR.md)。本节其余结论（UUID 可重解析、回退位姿）仍然有效。

**因此：对外材料里不再出现「恢复漂移 0.0m」这种写法**，改成「按 UUID 重新解析成功」。真机精度要等借到设备后用物理参照物测（12/25 陌生场地实测会顺带做）。

## 同一模拟器实例内的边界（2026-11-08 补充）

**这一节记录另一个容易被过度解读的点。**

「杀进程重启后重新解析成功」成立于**同一个模拟器实例内**——系统级锚点服务（`WorldTrackingManager`）随模拟器进程存活，锚点 UUID 在其内部持久化。

**跨模拟器实例重建时，系统锚点会丢**。2026-11-09 实测：模拟器因内存压力被外部 kill 后重建，之前创建的系统锚点全部失效：

```
W Anchor: verifyRestored: batch loadAnchor ERROR code=-7 msg=The specified anchor was not found.，逐条重试
E Anchor: verifyRestored: uuid=a572e6cb-… NOT resolved code=-7 msg=The specified anchor was not found.
W Anchor: downgraded 1 lost anchor(s) to fallback pose: [a572e6cb-…]
I DualState: [room_stage] inst#1 SpatialView.initial anchors=2     ← 两条记录都还在
```

**这不是 bug，是降级路径按设计工作**：锚点丢失 → 记录降级为 uuid=null → 场景按保存位姿直渲 → **卡片不消失**。这正是方案 L4 降级形态里说的"会话内记忆 + 幽灵态重放"。

**但它对创新分有影响，必须说清楚**：

| 表述 | 准确性 |
|---|---|
| 「锚点持久化，杀进程重进可找回，drift 0.0m」 | ✅ 准确 —— 指**同一模拟器实例内**的应用进程被杀重启 |
| 「锚点在设备重启后仍在」 | ❌ **未验证** —— 模拟器实例重建会丢；真机行为待验 |
| 「锚点在搬家/陌生场地后仍在」 | ❌ **明确不会** —— 系统锚点绑定当前空间，换场地必丢（这正是 L4 降级存在的原因） |

**答辩时若被问「锚点会不会丢」**，正确答案是：「设备不重启、应用不被杀时不会丢；设备重启或换场地会丢，所以我们做了降级 —— 卡片会按最后记录的位姿继续在房间里，不会凭空消失。**决赛现场就是靠这个降级兜底**，12/25 会实测陌生场地找回率。」

**模拟器实例重建会丢锚点，真机行为待验** —— 这一条列入 `docs/M12-M14-TEST.md` 的待真机清单。

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
- ~~「用户指向平面放锚点」的**手势指向取点**未实现~~ —— **已实现（M16）**：`AimPlacement` 同时接手柄（`ControllerTrackingProvider`）与手势（`HandTrackingProvider`），判定逻辑在 `core/.../PlanePicker`（26 例单测）。模拟器实测：`ControllerTrackingProvider = DEVICE_NOT_SUPPORTED`、`HandTrackingProvider = SUPPORTED`，因此扳机这一步仍需真机。见 [PLANE-ANCHOR.md](PLANE-ANCHOR.md)。
- ~~锚点与真实平面的贴合（识别桌面/墙面法线）未做~~ —— **M16 已实现**（2026-11-10）：`PlaneTrackingManager` 给平面 + 24 类语义，法线由多边形叉积或朝向枚举推出，`AimPlacement` 做射线求交。见 [PLANE-ANCHOR.md](PLANE-ANCHOR.md)。
