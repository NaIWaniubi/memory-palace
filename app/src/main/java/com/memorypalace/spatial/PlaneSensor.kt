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

    private var subscription: com.pico.spatial.core.lifecycle.Cancellable? = null
    private var running = false

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
            Log.e(TAG, "loadAllAnchors failed", e)
            _status.value = Status.UNSUPPORTED
        }
        val anchors = result.getOrNull() ?: return
        val mapped = anchors.mapNotNull { anchor ->
            runCatching { RoomPlaneMapper.from(anchor) }
                .onFailure { Log.e(TAG, "map plane failed", it) }
                .getOrNull()
        }
        _planes.value = mapped.sortedWith(
            compareByDescending<RoomPlane> { it.isHorizontal }.thenByDescending { it.areaM2 }
        )
        Log.i(TAG, "loadAllAnchors -> ${anchors.size} anchor(s), ${mapped.size} usable: " +
            mapped.joinToString { it.describe() })
    }

    private fun onPlaneUpdate(update: AnchorUpdate<PlaneAnchor>) {
        _updateCount.value += 1
        val event = runCatching { update.event }.getOrNull() ?: return
        val anchor = runCatching { update.anchor }.getOrNull()
        when (event) {
            AnchorUpdate.Event.ADDED, AnchorUpdate.Event.UPDATED, AnchorUpdate.Event.LOADED -> {
                if (anchor == null) return
                val plane = runCatching { RoomPlaneMapper.from(anchor) }.getOrNull() ?: return
                val current = _planes.value
                val idx = current.indexOfFirst { it.uuid == plane.uuid }
                _planes.value = if (idx >= 0) {
                    current.toMutableList().also { it[idx] = plane }
                } else {
                    (current + plane).sortedWith(
                        compareByDescending<RoomPlane> { it.isHorizontal }.thenByDescending { it.areaM2 }
                    )
                }
                Log.i(TAG, "event=$event uuid=${plane.uuid} -> ${plane.describe()} (total ${_planes.value.size})")
            }
            AnchorUpdate.Event.REMOVED -> {
                val uuid = anchor?.anchorUUID?.toString() ?: return
                _planes.value = _planes.value.filterNot { it.uuid == uuid }
                Log.i(TAG, "event=REMOVED uuid=$uuid (left ${_planes.value.size})")
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
        val before = _planes.value.size
        _planes.value = _planes.value.filterNot { it.uuid.startsWith("sim-") }
        Log.i(TAG, "cleared simulated planes: $before -> ${_planes.value.size}")
    }
}