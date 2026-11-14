# P1 几何内核与空间感知 — 空间计算 / 数值鲁棒性深评

审查范围：`Geometry.kt` / `RoomPlane.kt` / `PlaneSensor.kt` / `AimPlacement.kt` / `Reticle.kt` / `PlaneGeometryTest.kt`。
只读审查，未改动任何源文件。已修的两个 bug（坐标系不同、法线符号）不再复述，下面找的是**同类**的数值/并发漏洞。

统计：阻断 1 · 高 4 · 中 5 · 低 3 · 健壮性 3。最该修见文末摘要。

---

### [阻断] `normalized()` 用 `len < EPS` 判零，NaN 长度直接漏网，产出一个 NaN 向量而非 null

`core/src/main/java/com/memorypalace/core/spatial/Geometry.kt:33-37`

```kotlin
fun normalized(): Vec3? {
    val len = length()          // length() = sqrt(dot)，dot 含 NaN 时 len = NaN
    if (len < EPS) return null  // NaN < EPS == false —— 判零失败，不返回 null
    return Vec3(x / len, y / len, z / len) // NaN/NaN = NaN，返回 "看起来合法" 的 NaN 向量
}
```

IEEE-754 下 `NaN < 1e-6f` 恒为 `false`。这正是注释「宁可让调用方显式处理，也不要静默产出 NaN 坐标」想要杜绝的事，却因为只判 `<` 而恰好对 NaN 失效——函数**静默产出 NaN 向量**。`Quat.normalized()` 同一模子（见下条）。

可复现输入（纯 JVM，可写单测）：
`Vec3(Float.NaN, 1f, 0f).normalized()` → 期望 `null`，实际得到 `Vec3(NaN, ...)`；
`Vec3(0f, Float.POSITIVE_INFINITY, 0f).normalized()` → `len = Inf`，`Inf < EPS` false → `Vec3(0, Inf/Inf=NaN, 0)`。

修法：入口先挡非有限值。
```kotlin
fun normalized(): Vec3? {
    if (!isFinite()) return null          // NaN / Inf 一律拒绝，不再依赖 len<EPS 挡 NaN
    val len = length()
    if (len < EPS) return null
    return Vec3(x / len, y / len, z / len)
}
```

---

### [高] SDK 四元数不做 isFinite 过滤，NaN 四元数污染 normal / uHint，整张平面被悄悄丢或变成永不可命中的僵尸

`app/src/main/java/com/memorypalace/spatial/RoomPlane.kt:133-151`

```kotlin
val quat = Quat(t.quaternion.x, t.quaternion.y, t.quaternion.z, t.quaternion.w).normalized()
val verts = anchor.vertices.map { Vec3(it.x, it.y, it.z) }.filter { it.isFinite() }
...
val fromOrientation = when (anchor.planeOrientation) {
    PlaneOrientation.VERTICAL -> quat.rotate(Vec3.BACK)   // quat 含 NaN → BACK 转成 NaN
    else -> null
}
val normal = fromPolygon ?: fromOrientation ?: quat.rotate(Vec3.BACK)
```

`Quat.normalized()`（`Geometry.kt:70-74`）和 `Vec3.normalized()` 有一样的 NaN 洞：`sqrt(NaN)=NaN`，`NaN < EPS` false → 返回 NaN 四元数。于是 `quat.rotate(...)` 返回 NaN 向量，`uHint = quat.rotate(Vec3(1,0,0))`（第 154 行）也是 NaN。

已修 bug①只对 `center`、bug②只对 `verts` 做了 `isFinite`，**唯独四元数没校验**——这就是「信了一个坐标、漏了另一个坐标」的同类问题。

可复现：`PlaneAnchor` 的 `transform.quaternion = (NaN,0,0,1)`，`planeOrientation = VERTICAL`，`vertices` 少于 3 个（真实 SDK 对新生/退化平面会给空或 2 顶点轮廓）。`fromPolygon = null` → `normal = quat.rotate(BACK) = (NaN,NaN,NaN)` → `PlaneShape.build(normalHint=NaN)` → `NaN.normalized()`（修前）返回 NaN 而非 null → `build` 不返回 null，产出一张 `normal=(NaN..)` 的平面。`PlanePicker.hit` 里 `denom=NaN`，`abs(NaN)<1e-4` false，`t=NaN`，`t<0||t>max` false，`point`/`local` 全 NaN，最后靠 `covers` 里 NaN 比较恒 false 侥幸返回 null——平面留在列表、日志显示「成功映射」，但永远点不中，且 kindOf(NaN) 两个 `when` 分支都 false → 归为 `ARBITRARY`。

修法：`from()` 顶部把四元数也纳入有限性校验，退化就用单位四元数或直接丢弃。
```kotlin
val q = t.quaternion
val quat = if (q.x.isFinite() && q.y.isFinite() && q.z.isFinite() && q.w.isFinite())
    Quat(q.x, q.y, q.z, q.w).normalized() else Quat.IDENTITY
```
并给 `Quat.normalized()` 同样加 `if (!(x.isFinite()&&y.isFinite()&&z.isFinite()&&w.isFinite())) return IDENTITY`。

---

### [高] `PlaneSensor` 读-改-写 `StateFlow.value` 非原子，`onPlaneUpdate`（SDK 回调线程）与 `reload()`（Dispatchers.Default）并发丢更新

`app/src/main/java/com/memorypalace/spatial/PlaneSensor.kt:120` 与 `135-144`

```kotlin
// reload(): 整表覆盖
_planes.value = mapped.sortedWith(compareByDescending<RoomPlane>{it.isHorizontal}.thenByDescending{it.areaM2})

// onPlaneUpdate(): 读旧值→算新值→写回，三步非原子
val current = _planes.value
val idx = current.indexOfFirst { it.uuid == plane.uuid }
_planes.value = if (idx >= 0) ... else (current + plane).sortedWith(...)
```

`start()` 里先 `subscribeAnchorUpdate`（第 65 行）再 `scope.launch { reload() }`（第 83 行）。`reload()` 协程跑在 `Dispatchers.Default`（可能任意线程、多次 `rescan()` 可并发），`onPlaneUpdate` 由 SDK 从其内部线程回调。二者对同一个 `_planes` 做「读快照→基于快照改→无条件写回」，写回不检测期间是否被别人改过 → 丢事件。`_updateCount.value += 1`（第 128 行）同样是 `+=` 非原子。

可复现状态序列：`loadAllAnchors()` 返回后挂起在 `sortedWith` 计算；此间一条 `ADDED(uuid=X)` 走 `onPlaneUpdate` 把 X 加进去；随后 `reload()` 用**它自己那份不含 X 的 anchors** 覆盖 `_planes.value` → X 消失，直到下一个事件才回来。或两条 `UPDATED` 交错 → 其中一次被覆盖。

修法：所有对 `_planes` 的改写走 `MutableStateFlow.update { }`（内部 CAS 重试，线程安全），`reload()` 也不要整表覆盖，而是与增量合并：
```kotlin
import kotlinx.coroutines.flow.update
_planes.update { cur ->
    val byUuid = cur.associateBy { it.uuid }.toMutableMap()
    mapped.forEach { byUuid[it.uuid] = it }   // 或保留 cur 中不在 mapped 的增量项
    byUuid.values.sortedWith(cmp)
}
_updateCount.update { it + 1 }
```

---

### [高] `simulateRay` 是一次性的，注入命中后 `_aimActive` 置 true 但无定时器复位，真实帧若没接着来就永久"假可用"

`app/src/main/java/com/memorypalace/spatial/AimPlacement.kt:299-325`、`386-395`；消费端 `AnchorScene.kt:193-195`

```kotlin
fun canAimPlace(): Boolean =
    PlaneSensor.planes.value.isNotEmpty() && AimPlacement.aimActive.value
```

`simulateRay` 设 `simulatedUntil = now+5000` 后调一次 `publish(..., simulated=true)`。命中路径 `_aimActive.value = true`（第 325 行）并写 `lastAim`。但 `_aimActive` 只有在下一次 `publish` 走进「hit==null」分支时才置 false（第 319 行）。模拟器/无头验证里 `simulateRay` 通过广播只发一次（`AnchorScene.kt:119`），之后没有硬件帧再调 `publish` → `_aimActive` 永远停在 `true`、`lastAim` 停在旧命中点。此时：① 用户对着一个早已移出视野 / 被 `PlaneSensor` 判 `REMOVED` 的平面，`canAimPlace()` 仍返回 true，按钮显示「🎯 手柄放置」，`confirmManually()` → `fire()` 拿旧 `lastAim` 把卡片钉到一个**已经不存在的锚点**（`planeUuid` 是过期的，`Aim.planeUuid` 第 349 行）；② 5 秒独占窗口结束后也没有任何帧把它拉回 false。

可复现：`injectDemoPlanes()` → `simulateRay(命中桌面)` → `PlaneSensor.clearDemoPlanes()`（移除 sim-table）→ 不做任何再瞄准 → `aimActive.value` 仍 true、`lastAim.planeUuid=="sim-table"` → `canAimPlace()` 仍 true。

修法：注入路径命中后挂一个与 `simulatedUntil` 对齐的一次性协程，到点若无新命中就把 `_aimActive/lastAim/label` 复位；并让 `fire()` 校验 `lastAim.planeUuid` 仍在 `PlaneSensor.planes.value` 里才放行。
```kotlin
fun simulateRay(...) {
    simulatedUntil = System.currentTimeMillis() + 5_000L
    publish(Ray.of(origin, direction), source, simulated = true)
    scope.launch {                       // 独占到期后若无人接管，复位
        delay(5_100)
        if (System.currentTimeMillis() >= simulatedUntil && !hasFreshRealHit()) {
            lastAim = null; _aimActive.value = false; _label.value = null
        }
    }
}
```

---

### [高] `AimPlacement.publish` 被 `controllerJob` 与 `handJob` 两条 Default 协程并发调用，`lastHadHit`/`_aimActive`/`lastAim` 的读-改-写撕裂；`stop()` 后在途帧仍能把状态改回 true

`app/src/main/java/com/memorypalace/spatial/AimPlacement.kt:168-172`、`206-210`、`299-356`

```kotlin
controllerJob = scope.launch { p.dataFlow.collect { onControllerData(it) } }
handJob       = scope.launch { p.dataFlow.collect { onHandData(it) } }
...
private fun publish(ray, source, simulated=false) {   // 无 running 检查、无同步
    val lastHadHitBefore = lastHadHit
    ...
    if (_aimActive.value) _aimActive.value = false      // check-then-set 非原子
    ...
    if (!_aimActive.value) _aimActive.value = true       // 两条流可交错
    lastAim = aim
    _frames.tryEmit(aim)
}
```

`_source`、`_aimActive` 由手柄流和控制器流两个不同线程并发写，`lastAim` 是 `@Volatile` 但「判断 `_aimActive` 再改」是竞态：两线程都读到 false 都写 true（尚可收敛），但 `lastAim` 与 `_aimActive` 之间可能不一致——手柄刚算出 null 命中把 `lastAim=null`，同一瞬间控制器把 `_aimActive=true` 却写的是自己那条 aim，读到 `aimActive=true && lastAim` 为另一设备/旧值。更关键：`stop()`（第 130-146）置 `_aimActive=false`，但已在 `Dispatchers.Default` 排队的一帧 `publish` 会在 `stop()` 之后执行，把 `_aimActive` 又翻回 true、`lastAim` 又写上——房间层销毁后 `canAimPlace()` 复活。`publish` 里全程没有 `if (!running) return`。

修法：`publish`/`fire` 开头加 `if (!running) return`；`_aimActive`/`lastAim` 作为一个不可变快照原子更新（合成到单个 `StateFlow<Aim?>`，`aimActive` 派生自它），避免多字段各自 check-then-set 撕裂。
```kotlin
private val _aim = MutableStateFlow<Aim?>(null)
val aimActive = _aim.let { MutableStateFlow(false) } // 或直接用 _aim.value != null 派生
fun publish(...) { if (!running) return; ...; _aim.value = hit?.let{buildAim(...)} // 单写原子
```

---

### [中] `useOutline` 用「顶点算术平均质心」比对 anchor 位姿，对不规则大平面（L 形桌、整墙）会误判坐标系不同而退回矩形；过滤 NaN 顶点还会把质心拽偏

`app/src/main/java/com/memorypalace/spatial/RoomPlane.kt:174-177`、`237-243`

```kotlin
val polyCentroid = if (verts.size >= 3) centroidOf(verts) else null
val centroidGap = polyCentroid?.let { (it - center).length() } ?: Float.MAX_VALUE
val useOutline = polyCentroid != null && centroidGap < 0.35f
```

`centroidOf` 是顶点坐标的算术平均，不是**面积形心**；而 anchor 的 `transform.position` 通常是面积/包围盒中心。对凸四边形两者接近，但对凹形/疏密不均的轮廓（SDK 对 L 形桌、带缺口墙会返回更多顶点在一侧），顶点平均会明显偏离几何中心——顶点数越多且分布越不均，偏差越大，可以轻易超过绝对阈值 0.35m，导致一张**同坐标系**的正确大墙被误退回矩形轮廓（丢失精确轮廓判定，回到「L 形也算命中」的老问题）。此外 `verts` 先 `filter{it.isFinite()}`（第 134 行）：若偶发丢掉一两个顶点，算术平均质心会被剩余点带偏，进一步加剧误伤。

可复现（可直接写成 JVM 单测，绕过 Android）：一个中心在原点、面积 3×2 的 L 形顶点集（6 个点，长边侧点多），`transform.position=(0,0,0)`。手算顶点平均 ≈ 偏移 >0.35m → 期望 `useOutline=true` 却被判 false。（注意 `RoomPlaneMapper` import 了 `android.util.Log`，需先把 `from()` 的纯几何部分抽成可测函数，或用 Robolectric。）

修法：把「顶点算术平均」换成**多边形面积形心**（鞋带带权），且与 anchor 比对时给尺寸相关阈值（如 `max(0.35f, 0.1f * bboxDiag)`）而非纯绝对值；或用「anchor 到多边形所在平面的距离 + 落在 uv 包围盒内」作为一致性判据，比质心间距稳。
```kotlin
// 面积形心（2D 投影后），比算术平均稳
fun areaCentroid(poly: List<Vec2>): Vec2? {
    var a = 0f; var cu = 0f; var cv = 0f
    for (i in poly.indices) {
        val p = poly[i]; val q = poly[(i+1)%poly.size]
        val cross = p.u*q.v - q.u*p.v; a += cross; cu += (p.u+q.u)*cross; cv += (p.v+q.v)*cross
    }
    if (abs(a) < 1e-6f) return null
    return Vec2(cu/(3f*a), cv/(3f*a))
}
```

---

### [中] `PlaneSensor` 状态机单向：一次 `loadAllAnchors` 抖动就把 `status` 永久钉死为 `UNSUPPORTED`，`start()` 因 `running=true` 拒绝重试

`app/src/main/java/com/memorypalace/spatial/PlaneSensor.kt:77-84`、`108-114`、`54-59`

```kotlin
result.onFailure { e -> Log.e(...); _status.value = Status.UNSUPPORTED }  // reload() 里
...
fun start() { if (running) { Log.i(...); return } running = true; ... }    // 再点无效
```

`reload()` 是 `rescan()` 与首帧启动都走的路径。真机上 `loadAllAnchors()` 偶发抛一次（SDK 初始化竞态），`_status` 被置 `UNSUPPORTED`，但 `running` 仍为 true、订阅仍在。UI 显示「不支持」，用户点「重新扫描」→ `rescan()`→`running` 为 true→`scope.launch{reload()}` 若这次成功却**从不把 status 改回 SCANNING**（成功路径没有 `_status.value = SCANNING`）；用户点开始→`start()` 因 `running` 早退。于是短暂失败升级成永久「不支持」假象。

可复现：mock `loadAllAnchors()` 第 1 次抛、第 2 次返回 5 个锚点；观察 `status` 停在 UNSUPPORTED 而 `planes` 却有 5 项，UI 三态与实际矛盾。

修法：`reload()` 成功分支显式 `_status.value = Status.SCANNING`；把「不支持」区分成硬失败（构造/`start()` 失败）与软失败（一次 reload 抛），后者保留 SCANNING 并允许重试。

---

### [中] `Reticle.move` 只喂 yaw，pitch/roll 恒 0；`EulerAngles(0,yaw,0)` 与 `yawToward` 的 `atan2(dx,dz)` 约定是否一致无测试锁定，符号/零轴约定一错全体朝向偏 90°

`app/src/main/java/com/memorypalace/spatial/Reticle.kt:47-52`；`Geometry.kt:272-278`

```kotlin
fun move(point: Vec3, yawDeg: Float) { t.eulerAngles = EulerAngles(0f, yawDeg, 0f) }
...
// yawToward: 0 表示朝 +Z
return Math.toDegrees(atan2(dx.toDouble(), dz.toDouble())).toFloat()
```

`yawToward` 返回 `atan2(x,z)`（+Z 为 0，逆时针为正），直接塞进 SDK `EulerAngles` 的 yaw 槽。这两个约定的正方向/零轴是否吻合，代码里没有任何往返测试（`PlaneGeometryTest` 只测 `yawToward` 自身三点，不测它进 `EulerAngles` 后旋转出来的朝向）。一旦 SDK yaw 是「绕 Y 顺时针为正」或「0 朝 +X」，卡片会系统性背对或侧对观察者，且因每帧都朝、人眼不易一眼看出偏 90°。属于约定脆弱点。

修法：加一条把 `yawToward` 结果构造成 `Quat`（轴 Y, 角=yaw）再 `rotate(BACK)`，断言其方向 ≈ 归一化的 `(viewer-hit)` 水平分量；即锁死「卡片正面法线指向观察者」这条几何不变量。
```kotlin
@Test fun `yawToward 转出的朝向应指向观察者`() {
    val hit = Vec3(0f,0f,-1f); val viewer = Vec3(1f,0f,0f)   // 观察者在 +X
    val yaw = PlanePicker.yawToward(hit, viewer)
    val q = Quat(0f, kotlin.math.sin(Math.toRadians(yaw.toDouble()).toFloat()/2), 0f,
                      kotlin.math.cos(Math.toRadians(yaw.toDouble()).toFloat()/2))
    val facing = (q.rotate(Vec3.BACK) ).normalized()!!       // 卡面法线
    val want = (viewer - hit).normalized()!!
    assertTrue(facing dot want > 0.99f)
}
```

---

### [中] 命中判定的近平行阈值 `1e-4` 与 `maxDistance` 都用绝对量，斜射远处大平面（整墙）在阈值边缘被随机吞掉

`core/src/main/java/com/memorypalace/core/spatial/Geometry.kt:235-237`

```kotlin
if (abs(denom) < 1e-4f) return null      // denom = n·d
val t = ((plane.center - ray.origin) dot plane.normal) / denom
if (t < 0f || t > maxDistance) return null
```

`denom` 是 `cos(射线与法线夹角补)`，`1e-4` 对应约 0.0057°。真正的问题不是阈值本身，而是 `t = offset / denom`：近平行时 `t` 对 `denom` 极敏感，`denom` 在 `1e-4` 附近抖动会让 `t` 在几十万米量级跳变——大部分被 `t>maxDistance` 挡掉，但在「恰好掠过大墙面、t 略小于 5m」的窄带里，浮点抖动会让同一手势这帧命中、下帧不命中，准星在远墙上闪烁。此外 `t>maxDistance` 与 `t<0` 用硬边界，`t` 恰好 `== maxDistance` 命中（`>` 为 false），无 epsilon 容差，5.000m 与 4.999m 一帧之内跳变。

可复现：法线 +Y、中心在 5m 外的一堵「水平」大面，射线近乎水平地掠过；微调方向使 `denom` 在 1e-4 上下摆动，观察命中距离在「0 / ~5m」间跳。

修法：近平行阈值按 maxDistance 反推一个最小可分辨夹角，并对边界用相对容差。
```kotlin
if (abs(denom) < 1e-4f) return null
if (t < -1e-3f || t > maxDistance + 1e-3f) return null   // 边界容差，避免闪烁
```

---

### [低] `pick` 多平面等距时按 `minByOrNull` 取列表序第一，依赖 `PlaneSensor` 排序（水平降序→面积降序），语义上是"任意一个"

`core/src/main/java/com/memorypalace/core/spatial/Geometry.kt:250-256`；`PlaneSensor.kt:120-122`

两块同高桌面（拼接桌）或一张桌与其镜像 anchor 会给出**完全相同的 distance**，`minByOrNull` 稳定返回先到的，而"先到"取决于 `_planes` 的排序；`ADDED` 分支新平面被 append 后再 `sortedWith`（第 140-142 行），`UPDATED` 分支只原位替换（第 138 行）不重排 → 等距平面的先后顺序在增量更新后与全量 reload 后不一致，同样手势可能这帧落 A 那帧落 B。属确定性/可测性问题，非崩溃。

修法：`pick` 命中相同 `distance` 时用稳定 tie-break（如 `plane.uuid.hashCode()` 或更小面积优先），并在文档注明等距语义。

---

### [低] `Ray` 主构造函数不校验 `direction`，`normalized=true` 只是"声称"；`data class` 相等性按 Float 位比较

`core/src/main/java/com/memorypalace/core/spatial/Geometry.kt:82-92`

`Ray(origin, direction)` 主构造直接接受任意 `direction`（含零/非单位），`Ray.of` 会归一化但手写 `Ray(...)` 不会；下游 `PlanePicker.hit` 靠再 `ray.direction.normalized()` 兜底，配合上面的 NaN 漏判形成隐患链。低优先。修法：把主构造设 `private`，只暴露 `Ray.of`。

---

### [低] `PlaneShape.build` 的兜底 `minByOrNull { abs(it dot n) }`：`n` 为 NaN（修前）时 `abs(NaN·n)` 比较全 false，`minByOrNull` 返回 null → `?: Vec3(1,0,0)` 侥幸不崩，但语义随机

`core/src/main/java/com/memorypalace/core/spatial/Geometry.kt:152-154`。这条是 #2 的下游连锁：`build(normalHint=NaN)`（修 `normalized()` 前）会得到 NaN 法线并进入兜底分支。修了 #2/#3 即消解。列此以便测试覆盖「build 传 NaN 法线」返回 null。

---

### [健壮性] `onPlaneUpdate` 的 `else -> Log.d(... ignored)` 静默吞未知事件枚举

`app/src/main/java/com/memorypalace/spatial/PlaneSensor.kt:151`。SDK 若新增事件类型（如 `EXPIRED`/`TRACKING_lost`）不会清缓存平面 → 陈旧 anchor 长期留在 `planes`。建议对未知 event 记 warning 并在连续多帧未知时触发一次 `reload()` 兜底。

---

### [健壮性] `RoomPlaneMapper` 里 `bbox` 为 0 且无轮廓时 `area = 4*halfU*halfV = 0` 被丢弃；但**有轮廓**时 `halfU/halfV` 被 `coerceAtLeast(0.01f)` 填成 0.01，`describe()` 会显示「0.0×0.0 m」而实际用多边形判定

`app/src/main/java/com/memorypalace/spatial/RoomPlane.kt:156-158`、`179`、`56-58`。UI 文案与真实判定不一致（显示 0 尺寸却可命中）。建议有 outline 时 `describe()` 报多边形包围盒尺寸，与日志里 `uvBox` 同源。

---

### [健壮性] `_aimActive`/`_source`/`_label` 在 `publish` 与 `stop` 多处 `if (x.value) x.value = ...` 的读-改-写，`StateFlow` 虽线程安全但条件更新非原子，`lastHadHit` 为 `@Volatile var` 但与其它字段无 happens-before 组合不变量

`app/src/main/java/com/memorypalace/spatial/AimPlacement.kt:296-297`、`318-325`、`352-355`。与 #5 同根，建议归一到一个不可变快照 `StateFlow<UiState>` 一次 `value=` 落地（见 #5 修法）。

---

## 建议新增边界单测清单（≥12 条）

以下均可在纯 JVM `PlaneGeometryTest` 跑（前 3 条需先把 `RoomPlaneMapper.from` 的纯几何子步骤抽成无 `Log` 依赖的函数，或用 Robolectric）。

1. **`Vec3(NaN,1,0).normalized() == null`** — 断言 NaN/Inf 一律返回 null（当前返回 NaN 向量）。同测 `Vec3(0,Inf,0).normalized()==null`、`Vec3(1e-30,0,0).normalized()==null`（长度 < EPS）。
2. **`Quat(NaN,0,0,1).normalized() == IDENTITY`** — 断言非有限分量不产 NaN 四元数。同测全零 `Quat(0,0,0,0).normalized()==IDENTITY`。
3. **`PlaneShape.build(center, normalHint=Vec3(NaN,0,0), ...)` 返回 null** — 非有限法线必须显式失败，不产出 NaN 平面。
4. **`PlanePicker.hit` 射线方向为 NaN（经修好的 `Ray`/`normalized` 应被挡）** — 断言返回 null 且不抛异常，命中点绝不出现 NaN 坐标。
5. **near-parallel 阈值边界** — 构造 `denom` 恰为 `1e-4f` 上下各一测试：`abs(denom)<1e-4` 时 null，刚过阈值时有限距离命中；断言不返回 `t=Inf/NaN`。
6. **`maxDistance` 闭/开边界** — `t` 精确 `== maxDistance`、`== maxDistance+ε`、`== -ε` 三点：分别期望命中 / null / null（t<0 反方向）。
7. **多平面等距 tie-break** — 两块 distance 完全相同（如 0.85m）的平行面，`pick` 断言返回确定的一项（修法落地后按 uuid/面积），重复 100 次结果稳定。
8. **`areaCentroid` vs 算术平均** — 对一个顶点疏密不均的 L 形凸凹混合多边形，断言面积形心落在 anchor 中心 0.05m 内（锁死 useOutline 不再误伤大平面）。
9. **`useOutline` 尺寸相关阈值** — 3m 长整墙、顶点为面积形心的合法轮廓，`centroidGap` 用绝对 0.35 会误退矩形；断言修复后仍 `useOutline=true`。
10. **bbox=0 但有 ≥3 共面顶点** — `PlaneShape.build` 后 `covers(center投影)` 应命中（多边形兜底），`describe()` 尺寸不为 0（健壮性 #14）。
11. **`normalFromPolygon` 全重合顶点 / 全共线** — `[(0,0,0),(0,0,0),(0,0,0)]` 与共线序列均返回 null；混入一个 NaN 顶点前已被 `filter` 掉，断言不抛。
12. **`pointInPolygon` 边界点与重复顶点** — 顶点落在边上、多边形含重复连续顶点、凹自交轮廓：断言不抛异常、结果确定（奇偶规则），`covers(margin=0)` 与文档一致。
13. **`yawToward` 观察者正上方** — `hit=(0,0.75,-1)`、`viewer=(0,1.6,-1)`（dx=dz=0）断言返回 0 且非 NaN；再断言修法 #10 的「卡面法线·指向观察者 > 0.99」往返不变量（锁 EulerAngles 约定）。
14. **`Quat.rotate` 单位性保持** — 任意单位四元数旋转单位向量后 `length()≈1`（`1e-5` 内），连续旋转 100 次仍≈1（锁累积误差）。
15. **并发：`PlaneSensor` 交错 reload/ADDED** — 用确定性调度（`SingleThreadDispatcher` 手动 step 或 fake scheduler）模拟 `loadAllAnchors` 挂起期间来一条 `ADDED`，断言修复（`update{}`）后新平面不被全量覆盖吞掉。
16. **`aimActive` 泄漏** — 调一次 `simulateRay` 命中，随后从 `PlaneSensor` 移除该平面且无后续帧，断言在独占到期后 `aimActive==false`（锁 #6）。

---

## 摘要（返回给上层）

- 计数：阻断 1 · 高 4 · 中 5 · 低 3 · 健壮性 3。
- 最该修的 5 条：
  1. [阻断] `Vec3.normalized()` 用 `len<EPS` 挡零但 NaN 漏判，返回 NaN 向量而非 null；`Quat.normalized()` 同病（Geometry.kt:33-37/70-74）。
  2. [高] `RoomPlaneMapper` 校验了 center 和 vertices 的 isFinite，却漏了 SDK 四元数——NaN 四元数把 normal/uHint 带成 NaN，平面被悄悄丢弃或永不命中（RoomPlane.kt:133-154），与已修 bug①②同类。
  3. [高] `PlaneSensor` 的 `_planes` 读-改-写与整表 `reload()` 覆盖跨线程非原子，增量 ADDED 会被并发 reload 吞掉——必须改 `StateFlow.update{}`（PlaneSensor.kt:120/135-144）。
  4. [高] `simulateRay` 一次性注入把 `_aimActive` 置 true 却无复位定时器，无头/模拟器下永久"假可用"，`confirmManually()` 会把卡钉到已不存在的过期锚点（AimPlacement.kt:299-395 + AnchorScene.kt:193）。
  5. [高] `AimPlacement.publish` 被手柄流与控制器流并发调用、且 `stop()` 后在途帧仍翻状态（无 `if(!running)` 守卫），`_aimActive/lastAim` 撕裂（AimPlacement.kt:168-356）。
