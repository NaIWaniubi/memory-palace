# 集群 1114 · 多视角评审与修复总账

> 方法：6 个只读视角并发评审 → 按证据分域派 builder 修复 → 每轮 gradle/npm 门禁验证 → 逐条提交。
> 同仓 builder 串行（避免 gradle/npm 抢 build 目录），跨仓并行。

## 评审视角（只读，报告在本目录）

| 视角 | 范围 | 报告 | 阻断 | 高 | 中 | 低/健壮 |
|---|---|---|---|---|---|---|
| P1 | 空间几何与并发鲁棒性 | `P1-geometry.md` | 1 | 4 | 5 | 6 |
| P2 | 持久化与生命周期 | `P2-persistence.md` | 1 | 5 | 6 | 6 |
| P3 | 评分维度缺口与评委可见面 | `P3-scoring.md` | — | — | — | — |

## 修复提交（全部经 `:core:test` / `:app:testDebugUnitTest` / `:app:compileDebugKotlin` / `:app:assembleDebug` 绿）

| commit | 内容 | 关键 |
|---|---|---|
| `b052e33` | core 几何 NaN/Inf 护栏 + 18 边界单测 | `Vec3/Quat.normalized()` 入口 `isFinite`（旧 `len<EPS` 对 NaN 恒 false 失效）|
| `aba8adc` | spatial 平面/瞄准并发与坐标 | `PlaneSensor` 非原子 `+=`→`StateFlow.update{}`（实测旧法 8 线程丢 62%）；`AimPlacement` `running` @Volatile 守卫杜绝销毁后 `canAimPlace` 复活；模拟注入带超时自失效 + confirm 前校验 planeUuid 仍存活；质心自校验改扇形面积加权（L 形桌不再误退矩形）|
| `2c3a167` | 持久化数据丢失与提醒连续性 | `AnchorStore.load` 逐条容错（**一条坏 JSON 不再清空全库**）；`verifyRestored` 仅 NOT_FOUND 降级、未就绪重试；apkg zip-bomb 字节/条目/尺寸闸；闹钟 BOOT/TIME/TZ 重排（含 Manifest 注册）；`Clock` 真实时钟与演示偏移分离 |
| `2413911` | 30 分钟稳定性性能/泄漏 | `RoomStage` 去 `key(records,tick)` 整树重建→按 uuid\|签名增量挂卸；`SpatialCue` 代次+原子认领，每个原生句柄恰好 close 一次（堵两条泄漏）；导入千张卡 **1000 事务→2**、全表 Flow 重发 1000→2；`AnchorStore` 构造+load 移出主线程；新增 `scripts/stability-30min.sh` |
| `cb5f0b4` | 评委可见面与开源二开加分 | 撤 README 脚手架态头图 + `DEMO_MODE` 一键净界面；单测数改真实分布；新增 `ARCHITECTURE.md`/`CONTRIBUTING.md`；删 6 个空壳模块；触屏「模拟射线」兜底 + 纯 JVM `Bearing` 方位罗盘 19 例 |

## 测试规模轨迹

core：68 → 86（B1）→ 94（B3）→ **113（B5，+Bearing 19）**
app 单测：0 → 16（B3）→ 24（B4）
合计 JVM 可跑单测 **137**（`:core:test` + `:app:testDebugUnitTest`，0 失败 0 错误）
core 零 SDK/Android import（`grep '^import (com.pico|android|androidx)'` = 0），`:app:assembleDebug` 出包 30MB。

## 边界（如实）

- **真机 30 分钟连续运行**本机无设备未实测；已交付 `scripts/stability-30min.sh`（adb 回放 PLACE/OPEN_STAGE/IMPORT + PSS/fd/线程趋势 + FATAL/ANR 判定），离线用合成 leaky/clean 数据验证过判据；有设备/模拟器时一键跑。
- 手柄 `ControllerTrackingProvider` 在模拟器返回 `DEVICE_NOT_SUPPORTED`，真机才能实录；本轮以触屏「模拟射线」兜底把「对准→放置→钉上」闭环做成无手柄可演示。
- 空间音频 `play()` 真实路径保留未动；方位可视化是叠加的证据层，非替代。
</content>