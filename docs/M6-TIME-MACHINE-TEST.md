# M6 时间机器演示模式实测结论

日期：2026-11-07 ｜ 环境：PICO Emulator 6.1.0（emulator-5556）｜ 构建：assembleDebug 1m22s / 1m19s（含修复）

## 范围

一键快进时钟，让评委"看见"遗忘曲线：到期队列瞬时堆积 + 锚定卡片集体褪色。本里程碑同时修掉了两个实测暴露的缺陷（见"修复"节）。

## 实现

- `data/Clock.kt`：新增公开方法 `setOffsetDays(days)`（绝对偏移）、`addDays(days)`（累加）、`offsetDays()`（展示用）；广播处理器改走 `setOffsetDays`，与面板按钮同一路径。
- `ui/StudyPanel.kt`：新增 `TimeMachineView`——当前偏移显示（偏移非零时粉色高亮）+「+1 天 / +7 天 / 复位」三按钮，直接调 Clock。
- `ui/RoomStage.kt`：`key(records)` → `key(records, tick)`。实测发现附件面板的 GL 纹理**不随 Compose 重组自动刷新**（第一次 +7d 后 AnchorCard 褪色标签未更新），整体重建 SpatialView 才生效。
- `anchor/AnchorScene.kt`：`verifyRestored` 检出解析失败的锚点后，自动把记录降级为 `uuid=null`（回退位姿）并持久化——修复"锚点丢失后卡片从场景中静默消失"的缺陷。

## 证据

### 1. 快进 +7 天（15:46 / 16:00 两轮）

```
Clock   : offset set to 7d (604800000ms), now=1794643253243
DualState: [room_stage] inst#1 SpatialView.initial anchors=4      ← tick 触发场景重建（本里程碑修复点）
Anchor  : scene entity added key=329fa25b… uuid=fallback pos=(0.0,1.2,-1.5)
Anchor  : scene entity added key=6416f30c… uuid=fallback pos=(0.5,1.3,-1.2)
Anchor  : scene entity added key=af275b7a… uuid=fallback pos=(0.5,1.3,-1.2)
Anchor  : scene entity added key=e2df4f20… uuid=517db8b1… pos=(0.8,1.3,-1.8)
Anchor  : verifyRestored: … title=timetest drift=0.0m             ← 真锚点重建后仍零漂移
```

### 2. 到期队列堆积（截图裁剪对比）

- 快进前 `m6_1_before_panel.jpg`：**今日到期队列：14 张**，时间机器 +0 天。
- 快进后 `m6_2_after_panel.jpg`：**今日到期队列：18 张**，时间机器粉色「+7 天」。
  （14 → 17：1d/6d 间隔卡集中到期；+1：快进行中放置的 timetest 探针卡）

### 3. 锚定卡褪色（截图裁剪对比）

- 快进前 `m6_1_anchor_zoom.jpg`：mnemonic / ephemeral 标题清晰白色，标签「已逾期不到1天 · 正在遗忘」。
- 快进后 `m6_2_anchor_zoom.jpg`：**mnemonic 灰化到几乎不可读**（fade>0.66 → 灰字 + 透明度 0.3），ephemeral 标签变为「已逾期…天 · 快忘了」。
- 快进后立即截图可能撞上场景重建中——纹理未就绪导致卡片短暂消失（秒级），属预期重建窗口。

### 4. 复位（16:09）

```
Clock   : offset set to 0d (0ms)
Anchor  : scene entity added ×4（全部恢复）
```

- `m6_4_reset_offset0.jpg`：队列回落 14 张，mnemonic/ephemeral 恢复清晰白，timetest 显示绿色「记得牢·7天后到期」（它在 +7d 偏移下创建，复位后属于未到期卡）。

## 修复（本里程碑实测暴露）

1. **GL 纹理不随 Compose 重组刷新**：AnchorCard 内部已收集 `Clock.tick`，但 +7d 后标签不重算——2D 面板窗口更新正常，Stage 附件面板纹理冻结。修复：RoomStage 把 `tick` 纳入 `key()`，时钟跳变时整视图重建。重建由广播触发，日志时间戳证明延迟 <1s。
2. **锚点丢失→卡片消失**：force-stop 重启后模拟器世界锚点全部失效（`NOT resolved`），AnchorEntity 绑死 UUID 导致实体无位姿不渲染——卡片静默消失。修复：verifyRestored 检出丢失即降级 `uuid=null` 按持久化位姿直渲，日志 `downgraded 2 lost anchor(s) to fallback pose`，两张老卡当场复活。这对决赛"陌生场地锚点丢失"风险同样是兜底。

## 已知限制

- 首窗 ANR 弹窗仍常驻（M4 已封板为模拟器资源问题，本次零触屏重启依然出现，主线程实际在正常工作——所有广播、重建日志都正常）。`input tap`/返回键均无法关闭（PICO systemui 弹窗不响应注入事件），截图证据以关键信息可读为准。
- 时间偏移是进程内内存态，force-stop 后归零（复位即正确行为）。
