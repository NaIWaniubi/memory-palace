package com.memorypalace.spatial

import android.util.Log
import com.memorypalace.core.spatial.Vec3
import com.pico.spatial.sense.base.AnchorUpdate
import com.pico.spatial.sense.base.AnchorUpdateSubscriber
import com.pico.spatial.sense.plane.PlaneAnchor
import com.pico.spatial.sense.plane.PlaneTrackingManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 真实房间平面检测（M16）。
 *
 * 数据源：`PlaneTrackingManager`（com.pico.spatial.sense.plane）。
 * 它给出带语义标签的平面锚点 —— 桌面、沙发、柜子、墙、地板都能区分出来，
 * 这是「把卡钉在餐桌上」这句话唯一能成立的技术前提。
 *
 * 三条纪律：
 *  ① 所有 SDK 调用都包在 runCatching 里。模拟器没有平面检测能力，SDK 可能在
 *     任何一步抛异常或返回 null —— 这一层必须保证"没有平面"只是没有平面，
 *     不是应用崩掉。
 *  ② 单例 + StateFlow。Stage 每次进出 Full Space 销毁重建，平面列表不能跟着丢。
 *  ③ 按 UUID 增量更新，不整体重载。UPDATED 事件会在平面网格细化时高频触发，
 *     每次都 loadAllAnchors 会打爆主线程。
 */
object PlaneSensor {

    const val TAG = "PlaneSensor"

    /** 检测状态，UI 直接显示这三态。 */
    enum class Status { IDLE, SCANNING, UNSUPPORTED, STOPPED }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _planes = MutableStateFlow<List<RoomPlane>>(emptyList())
    val planes: StateFlow<List<RoomPlane>> = _planes.asStateFlow()

    private val _status = MutableStateFlow(Status.IDLE)
    val status: StateFlow<Status> = _status.asStateFlow()

    /** 累计收到多少次平面更新（答辩时"它真的在实时更新"的证据）。 */
    private val _updateCount = MutableStateFlow(0)
    val updateCount: StateFlow<Int> = _updateCount.asStateFlow()

    /** 统一排序：水平面优先、其次面积大者。抽出避免 reload/增量两处漂移。 */
    private val CMP: Comparator<RoomPlane> =
        compareByDescending<RoomPlane> { it.isHorizontal }.thenByDescending { it.areaM2 }

    private fun sorted(list: List<RoomPlane>): List<RoomPlane> = list.sortedWith(CMP)

    private var subscription: com.pico.spatial.core.lifecycle.Cancellable? = null
    @Volatile private var running = false

    fun start() {
        if (running) {
            Log.i(TAG, "start ignored: already running")
            return
        }
        running = true
        val started = runCatching {
            val state = PlaneTrackingManager.state
            Log.i(TAG, "trackingState before start = $state")
            PlaneTrackingManager.start()
            _status.value = Status.SCANNING
            subscription = PlaneTrackingManager.subscribeAnchorUpdate(
                object : AnchorUpdateSubscriber<PlaneAnchor> {
                    override fun onUpdate(update: AnchorUpdate<PlaneAnchor>) {
                        onPlaneUpdate(update)
                    }
                }
            )
            true
        }.getOrElse { e ->
            Log.e(TAG, "平面检测启动失败（设备可能不支持）", e)
            false
        }
        if (!started) {
            running = false
            _status.value = Status.UNSUPPORTED
            return
        }
        Log.i(TAG, "plane tracking started")
        scope.launch { reload() }
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { subscription?.cancel() }
            .onFailure { Log.w(TAG, "cancel subscription failed", it) }
        subscription = null
        runCatching { PlaneTrackingManager.stop() }
            .onFailure { Log.w(TAG, "PlaneTrackingManager.stop failed", it) }
        _status.value = Status.STOPPED
        // 平面列表保留：退出房间层再进来时先显示上次的，避免界面闪空
        Log.i(TAG, "plane tracking stopped, keeping ${_planes.value.size} cached plane(s)")
    }

    /** 重新拉一次全量（用户点「重新扫描」时）。 */
    fun rescan() {
        if (!running) {
            start()
            return
        }
        scope.launch { reload() }
    }

    private suspend fun reload() {
        val result = runCatching { PlaneTrackingManager.loadAllAnchors() }
        result.onFailure { e ->
            // P1[中]#4：一次 loadAllAnchors 抖动是"软失败"，绝不能把 status 永久钉死成
            // UNSUPPORTED（只有 start() 里构造/订阅失败才是硬失败）。保持 SCANNING，允许重试。
            if (running) Log.w(TAG, "loadAllAnchors 失败（软失败，保留 SCANNING，可重试）", e)
            else Log.i(TAG, "loadAllAnchors 失败（已停止），忽略", e)
        }
        val anchors = result.getOrNull() ?: return
        val mapped = anchors.mapNotNull { anchor ->
            runCatching { RoomPlaneMapper.from(anchor) }
                .onFailure { Log.e(TAG, "map plane failed", it) }
                .getOrNull()
        }
        // P1[高]#3：与实时增量**合并不覆盖**。loadAllAnchors 挂起期间到达的 ADDED/UPDATED
        // 已写进 _planes；以 cur 为底、用本次权威快照按 uuid 覆盖同名项，既刷新全量又不
        // 吞掉加载窗口内的增量事件。StateFlow.update 内部 CAS 重试，跨线程安全。
        // 取舍：reload 由此变"加性"，不再靠它清除 SDK 侧已消失的平面——实时 REMOVED 事件
        // 负责精确删除（见 onPlaneUpdate），reload 不再整表覆盖以免丢增量。
        _planes.update { cur ->
            val byUuid = LinkedHashMap<String, RoomPlane>()
            cur.forEach { byUuid[it.uuid] = it }
            mapped.forEach { byUuid[it.uuid] = it }
            sorted(byUuid.values.toList())
        }
        // 成功即复位（可能从早先软失败恢复）；仅仍运行时写，避免 stop 后把 UI 状态复活。
        if (running) _status.value = Status.SCANNING
        Log.i(TAG, "loadAllAnchors -> ${anchors.size} anchor(s), ${mapped.size} usable: " +
            mapped.joinToString { it.describe() })
    }

    private fun onPlaneUpdate(update: AnchorUpdate<PlaneAnchor>) {
        _updateCount.update { it + 1 } // P1[高]#3：+= 非原子，改 CAS
        val event = runCatching { update.event }.getOrNull() ?: return
        val anchor = runCatching { update.anchor }.getOrNull()
        when (event) {
            AnchorUpdate.Event.ADDED, AnchorUpdate.Event.UPDATED, AnchorUpdate.Event.LOADED -> {
                if (anchor == null) return
                val plane = runCatching { RoomPlaneMapper.from(anchor) }.getOrNull() ?: return
                // P1[高]#3：读-改-写整段进 CAS，避免与 reload() / 其他回调线程交错丢更新。
                _planes.update { cur ->
                    val idx = cur.indexOfFirst { it.uuid == plane.uuid }
                    if (idx >= 0) cur.toMutableList().also { it[idx] = plane }
                    else sorted(cur + plane)
                }
                Log.i(TAG, "event=$event uuid=${plane.uuid} -> ${plane.describe()} (total ${_planes.value.size})")
            }
            AnchorUpdate.Event.REMOVED -> {
                // P1[中]#4：不能因 anchor==null 就 return —— 那样被删平面永久滞留。
                // anchor 非空按 UUID 精确删（不依赖 anchor 对象整体非空之外的一切）；
                // 取不到 UUID（SDK 异常兜底路径）时退回一次 reload 重新同步。
                val uuid = runCatching { anchor?.anchorUUID?.toString() }.getOrNull()
                if (uuid != null) {
                    _planes.update { cur -> cur.filterNot { it.uuid == uuid } }
                    Log.i(TAG, "event=REMOVED uuid=$uuid (left ${_planes.value.size})")
                } else {
                    Log.w(TAG, "event=REMOVED 但拿不到 UUID，触发 reload 重新同步")
                    if (running) scope.launch { reload() }
                }
            }
            else -> Log.d(TAG, "event=$event ignored")
        }
    }

    /** 单测/调试用：把 SDK 状态翻成可读字符串。 */
    fun stateLabel(): String = runCatching { "trackingState=${PlaneTrackingManager.state}" }
        .getOrElse { "trackingState=unavailable" }

    /**
     * 注入一组合成平面（模拟器专用）。
     *
     * 为什么需要：模拟器没有平面检测（PICO 官方明示），但「取点」这条链路
     * 不只是 SDK 调用 —— 几何判定、准星、落点、卡片朝向、持久化字段全都能在
     * 合成平面上跑通。没有这个入口，这些代码在 11/20 借到真机前一行都验不了。
     *
     * 注入的平面带 `simulated=true` 标记，UI 明写「模拟平面」——
     * 不允许和真实检测结果混在一起冒充。
     */
    fun injectDemoPlanes() {
        val table = com.memorypalace.core.spatial.PlaneShape.build(
            center = Vec3(0f, 0.75f, -1.2f),
            normalHint = Vec3.UP,
            uHint = Vec3(1f, 0f, 0f),
            halfU = 0.6f, halfV = 0.4f,
        )
        val wall = com.memorypalace.core.spatial.PlaneShape.build(
            center = Vec3(0f, 1.4f, -2.0f),
            normalHint = Vec3.BACK,
            uHint = Vec3(1f, 0f, 0f),
            halfU = 1.2f, halfV = 0.9f,
        )
        val floor = com.memorypalace.core.spatial.PlaneShape.build(
            center = Vec3(0f, 0f, -1.2f),
            normalHint = Vec3.UP,
            uHint = Vec3(1f, 0f, 0f),
            halfU = 1.5f, halfV = 1.5f,
        )
        val demo = listOfNotNull(
            table?.let {
                RoomPlane("sim-table", it, RoomPlane.Kind.HORIZONTAL, "桌面（模拟）", "SIM_TABLE", 0.96f)
            },
            wall?.let {
                RoomPlane("sim-wall", it, RoomPlane.Kind.VERTICAL, "墙（模拟）", "SIM_WALL", 4.3f)
            },
            floor?.let {
                RoomPlane("sim-floor", it, RoomPlane.Kind.HORIZONTAL, "地板（模拟）", "SIM_FLOOR", 9f)
            },
        )
        _planes.value = demo
        if (_status.value == Status.IDLE || _status.value == Status.UNSUPPORTED) {
            _status.value = Status.SCANNING
        }
        Log.i(TAG, "injected ${demo.size} simulated plane(s): ${demo.joinToString { it.describe() }}")
    }

    fun clearDemoPlanes() {
        _planes.update { cur -> cur.filterNot { it.uuid.startsWith("sim-") } }
        Log.i(TAG, "cleared simulated planes, now ${_planes.value.size}")
    }
}