# 架构（ARCHITECTURE）

> 面向想读懂或**二次开发**本项目的工程师。README 讲"它是什么"，本文讲"它怎么搭的、边界在哪、怎么扩"。
> 模块只有两个 Gradle 工程：**`:core`（纯 JVM）与 `:app`（Android/PICO）** —— 没有第三个模块，顶层也没有占位目录（曾有 `data/`、`spatial/`、`onboarding/`、`ui/`、`assets/` 五个只有 `.gitkeep` 的空壳目录，2026-11-14 删除，因为它们是设想不是结构）。

## 1. 分层总览

```
┌───────────────────────────── :app（Android / PICO SDK）─────────────────────────────┐
│ ui/        StudyPanel 书房面板 · ReviewCard 随身卡 · RoomStage 房间层(Stage)          │
│            AnchorCard 锚定卡 · PlacementPanel 平面/取点 · DemoMode 演示态开关          │
│            SpatialCompassView 逻辑方位罗盘                                            │
│ anchor/    AnchorScene 锚定场景状态(单例) · AnchorStore 持久层(SharedPreferences+JSON) │
│ spatial/   PlaneSensor 平面检测 · AimPlacement 手柄/手势取点 · Reticle 准星 ·          │
│            RoomPlane 平面语义映射（SDK 适配层，全部 runCatching 包住）                 │
│ audio/     SpatialCue(ObjectAudioComponent 挂载与播放) · AudioProbe                    │
│ data/      CardRepository · Room 数据库(db/) · ImportService · PresetDecks · Clock    │
│ notify/    DueReminder · DueNotifier（头显本地到期提醒）                               │
│ review/    ReviewSession 复习会话状态机                                                │
│ probe/     DualStateProbe 生命周期探针 · DualStateController 双态切换 + 无头广播        │
│ Main.kt    三窗声明（DefaultStage + 两个 WindowContainer）                             │
├──────────────────────────────────── 依赖方向 ────────────────────────────────────────┤
│            只允许 app → core；core 对 app / SDK / android.* 一无所知                   │
├────────────────────────────── :core（纯 Kotlin/JVM）────────────────────────────────┤
│ model/     Card 卡片模型（SM-2 状态字段）                                              │
│ schedule/  Scheduler 调度器（纯函数，now 由调用方传入）· MemoryStrength 遗忘强度→档位   │
│ importer/  CsvImporter · TextImporter · ApkgExtractor（SQLite 只读解析 anki2/21）      │
│ spatial/   Geometry(Vec3/Quat/Ray/PlaneShape/PlanePicker) · BearingGuide 逻辑方位罗盘  │
└──────────────────────────────────────────────────────────────────────────────────────┘
```

坐标约定（全仓统一）：**右手系、Y 向上、米**，与 PICO Spatial / OpenXR 一致。这条约定写死在 `Geometry.kt` 头部注释里，`Bearing`/`PlanePicker` 的单测都按它断言。

## 2. 为什么 core 是纯 JVM —— 这是本项目最重要的一条边界

`core/build.gradle.kts` 的依赖只有一个：`testImplementation(junit)`。core 里出现 `import android.*` 或 `import com.pico.*` 即违反架构约束，**CI 用一条 grep 静态拦截**（`.github/workflows/ci.yml` → job `app-static-check` → step "core must not depend on Android SDK"）：

```bash
if grep -rn "^import \(android\.\|com\.pico\.\)" core/src/main/java/ ; then
  echo "::error::core/ must stay framework-free (pure Kotlin/JVM)"
  exit 1
fi
```

它买到的东西：

1. **无头显、无 Android SDK 也能跑全量算法单测**：`./gradlew :core:test` 在任何装了 JDK 的机器上可复现（CI 就是这么跑的）。
2. **SDK 变了不动算法**：位姿来源（真手柄 / 模拟射线 / adb 注入）怎么换，命中判定、调度、方位计算都是同一份代码 —— `AimPlacement.simulateRay` 走的就是与真手柄完全相同的 `publish → PlanePicker.pick` 路径。
3. **数值纪律集中**：几何函数宁可返回 null 也不产 NaN（`Vec3.normalized` 挡非有限值、`PlanePicker.yawToward` 挡 NaN、`BearingGuide.of` 同样）。给 core 加任何返回坐标/角度的函数，请保持这条纪律并配退化输入单测。

## 3. 关键类与数据流

### 复习闭环
```
ImportService / PresetDecks ──解析(core importer)──▶ CardRepository ──Room dao──▶ AppDatabase
        │                                                 │ observeAll (Flow)
        ▼                                                 ▼
  确认页(StudyPanel.ImportConfirmView)          DueQueueView = Scheduler.dueQueue(cards, Clock.now)
                                                            │ 开始复习
                                                            ▼
                                              ReviewSession ──Grade──▶ Scheduler.review(SM-2) ──▶ dao.upsert
```
- `Clock`（app/data）是全局可偏移时钟：时间机器只改它的偏移量，`Scheduler` 依旧纯函数 —— 演示"快进 7 天集体褪色"不需要改一行算法。
- `MemoryStrength.tier()`（core）按 遗忘程度 = 逾期时长 ÷ 间隔 产出三档，直接驱动 `Theme.kt` 的 `FadedMid/FadedDeep` 卡片外观 —— "遗忘曲线驱动空间外观"就这一条线。

### 锚定闭环
```
AimPlacement(手柄/手势/注入射线) ──Aim──▶ AnchorScene.beginAimPlacement ──确认──▶
  PlanePicker.lift(+2cm) ──▶ WorldTrackingManager.createAnchor ──UUID──▶ AnchorStore(JSON)
        │ createAnchor 失败(模拟器)：记录 uuid=null，按位姿回退直渲，卡片不消失
        ▼
RoomStage(RoomSceneMount 增量挂载) ──▶ SpatialCue(ObjectAudioComponent 挂实体) 
                                   └─▶ SpatialCompassView(BearingGuide 逻辑方位)
```
- `AnchorScene.records` 是唯一事实源（StateFlow）；Stage 销毁重建不影响它。
- `RoomStage` 的增量挂载台账 `RoomSceneMount`：records 变化只挂/卸真正变化的卡（`planSceneDiff`，有单测），时间机器跳变才整树重建且去抖 350ms —— 这是 30 分钟连续运行不 churn 的关键。

### 双态与探针
- Shared Space（书房 1280×720 + 随身卡 420×560 两个 WindowContainer）与 Full Space（DefaultStage）并存；`probe/` 记录窗口生命周期。
- `ui/DemoMode.kt` 是默认开的展示态开关：开 = 净界面（评委/观众所见），关 = 探针全回（开发取证）。见 README「演示模式 vs 调试模式」。

## 4. 无头测试通道（模拟器点不了空间按钮的对策）

所有可交互动作都有 adb 广播入口（`ACTION_*` 常量分布见 grep；CI 保证 action 全局唯一）：

| 域 | 入口 |
|---|---|
| 双态 | `com.memorypalace.dualstate.OPEN_STAGE / CLOSE_STAGE / TOGGLE_REVIEW_CARD / OPEN_WINDOWS / DEMO_MODE` |
| 锚定 | `com.memorypalace.anchor.PLACE / CLEAR / AIM_BEGIN / AIM_SIMULATE / AIM_CONFIRM / AIM_CANCEL` |
| 卡片 | `com.memorypalace.card.ADD / IMPORT / PREVIEW / CONFIRM / REVIEW` |
| 时钟/音频/提醒 | `com.memorypalace.clock.SET_OFFSET / audio.PLAY / notify.SCHEDULE` |

## 5. 扩展点（二开最短路径）

| 想加什么 | 动哪里 | 要不要碰 SDK |
|---|---|---|
| 新导入格式 | `core/importer/` 加一个解析器（参考 `CsvImporter`，输入 String/File 字节流 → `List<ImportedCard>`），在 `ImportService` 注册格式探测分支 | 否（可先写纯单测） |
| 新调度策略 | `core/schedule/` 实现与 `Scheduler` 相同的纯函数签名（输入卡+now，输出新卡），`MemoryStrength` 档位可重映射 | 否 |
| 新主题预置牌组 | `app/src/main/assets/decks/` 加 CSV（`front,back` 表头）或 TXT（`front\tback`），并加进 `PresetDecks.DECKS` | 否 |
| 新窗口 | `Main.kt` 加 `WindowContainer(...)`（参照书房面板参数），在 `DualStateController` 登记 id 并在 `openWindows` 里打开 | 是 |
| 新空间交互 | `app/spatial/` 加 SDK 适配（全部 `runCatching` 包住），判定数学下沉 `core/spatial/` | 是（边界见 §2） |

更多操作细节（环境、跑测试、代码风格、PR 约定）见根目录 [`CONTRIBUTING.md`](../CONTRIBUTING.md)。
