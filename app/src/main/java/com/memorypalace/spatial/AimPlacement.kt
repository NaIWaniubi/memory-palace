package com.memorypalace.spatial

import android.util.Log
import com.memorypalace.core.spatial.PlaneHit
import com.memorypalace.core.spatial.PlanePicker
import com.memorypalace.core.spatial.Quat
import com.memorypalace.core.spatial.Ray
import com.memorypalace.core.spatial.Vec3
import com.pico.spatial.tracking.DataProvider
import com.pico.spatial.tracking.controller.ControllerActionData
import com.pico.spatial.tracking.controller.ControllerPose
import com.pico.spatial.tracking.controller.ControllerTrackingData
import com.pico.spatial.tracking.controller.ControllerTrackingProvider
import com.pico.spatial.tracking.hand.HandTrackingData
import com.pico.spatial.tracking.hand.HandTrackingProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 手柄 / 手势取点（M16）。
 *
 * 这一层只做三件事：取射线、算命中、把命中结果推给准星和 UI。
 * 判定逻辑全在 core 的 [PlanePicker]（纯函数，有单测）；
 * SDK 交互全部用 runCatching 包住 —— 模拟器没有手柄也没有平面，
 * 这里必须能安静地降级，而不是让房间层崩掉。
 *
 * 两条输入通道：
 *  ① 手柄：ControllerTrackingProvider（射线 = 位姿 -Z，扳机 = 确认）
 *  ② 手势：HandTrackingProvider（射线 = aim pose，捏合 = 确认）
 *
 * 为什么两条都做：真机演示时手柄可能没电/没配对，手势是唯一备份；
 * 而且"捏一下把卡片贴到桌上"在评委面前比按扳机更有说服力。
 */
object AimPlacement {

    const val TAG = "Aim"

    /** 合成射线的输入独占窗口（毫秒）；到期若无真实命中接管则复位 aim 状态（P1[高]#6）。 */
    private const val SIM_EXCLUSIVE_MS = 5_000L

    enum class Source(val text: String) {
        CONTROLLER_RIGHT("右手柄"),
        CONTROLLER_LEFT("左手柄"),
        HAND_RIGHT("右手"),
        HAND_LEFT("左手"),
        NONE("无输入设备"),
    }

    /** 一次有效瞄准。 */
    data class Aim(
        val point: Vec3,
        val normal: Vec3,
        val yawDeg: Float,
        val distance: Float,
        val planeLabel: String,
        val planeUuid: String,
        val source: Source,
    ) {
        /** 面板一行文案：「右手柄 → 桌面 1.24m」。 */
        fun describe(): String = "${source.text} → $planeLabel ${"%.2f".format(distance)}m"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 每帧的瞄准结果：给准星实体用（不进 Compose 重组）。 */
    private val _frames = MutableSharedFlow<Aim?>(
        replay = 0,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val frames: SharedFlow<Aim?> = _frames.asSharedFlow()

    /**
     * 给面板文字用的节流结果。
     * 只在"文案变了"时更新 —— 距离抖到小数点后两位也照样 60Hz 重组，
     * 2D 面板会掉帧，而这段文字人眼只需要每秒看到几次。
     */
    private val _label = MutableStateFlow<String?>(null)
    val label: StateFlow<String?> = _label.asStateFlow()

    private val _source = MutableStateFlow(Source.NONE)
    val source: StateFlow<Source> = _source.asStateFlow()

    /**
     * 当前是否真的指着一个平面。
     *
     * 按钮能不能变成「🎯 手柄放置」只看这个 —— 不看"有没有平面/有没有输入设备"。
     * 否则会出现这种死路：模拟器上平面有 57 个、手势跟踪也报了 SUPPORTED，
     * 但根本没人用手在指，按钮切成取点模式后用户会发现怎么点都没反应。
     * 真机上只要射线打到东西，这个值就自动翻 true。
     */
    private val _aimActive = MutableStateFlow(false)
    val aimActive: StateFlow<Boolean> = _aimActive.asStateFlow()

    /** 最近一次瞄准（扳机按下时用它，避免读到下一帧的空值）。 */
    @Volatile
    private var lastAim: Aim? = null

    /** 扳机/捏合回调。由 [AnchorScene] 在进入放置模式时挂上。 */
    @Volatile
    var onTrigger: ((Aim) -> Unit)? = null

    private var controllerJob: Job? = null
    private var handJob: Job? = null
    private var controller: ControllerTrackingProvider? = null
    private var hand: HandTrackingProvider? = null
    // P1[高]#5：running 被 controllerJob/handJob（Dispatchers.Default 多线程）与 stop()
    // （UI 线程）并发读写，必须 @Volatile，否则在途帧读不到 stop 的置位、把状态又翻回 true。
    @Volatile private var running = false

    // P1[中]#7：扳机/捏合闩锁由 SDK 回调线程写、可能被另一路径读；@Volatile 保证可见性，
    // 且 stop() 必须清闩，否则退出房间时正好按住、重进后第一次扳机被吞（闩锁还停在 true）。
    @Volatile private var controllerTriggerDown = false
    @Volatile private var handPinchDown = false

    /**
     * 每次"写出一条命中"自增（见 publish 命中分支）。simulateRay 用它判断
     * 独占到期后是否已被某条真实命中接管：若版本号没变，说明没有任何后续命中，
     * 才把 aim 状态复位，避免模拟器/无头环境下 aimActive 永久假可用。P1[高]#6。
     * 用 AtomicLong：publish 由 controller/hand 两条流并发调用，`long++` 非原子。
     */
    private val aimVersion = java.util.concurrent.atomic.AtomicLong(0L)
    private var simExpiryJob: Job? = null

    fun start() {
        if (running) return
        running = true
        startController()
        startHand()
        if (controller == null && hand == null) {
            _source.value = Source.NONE
            Log.w(TAG, "没有可用的输入设备，取点不可用（模拟器属正常情况）")
        }
    }

    fun stop() {
        if (!running) return
        running = false // 先翻标志：publish/fire 入口检查会立刻拒绝后续在途帧
        // P1[中]#7：清闩锁，否则退出时正按住扳机/捏着指，重进房间第一次被吞。
        controllerTriggerDown = false
        handPinchDown = false
        // P1[高]#6：取消挂起的模拟到期协程并解除独占窗口。
        simExpiryJob?.cancel()
        simExpiryJob = null
        simulatedUntil = 0L
        controllerJob?.cancel()
        handJob?.cancel()
        controllerJob = null
        handJob = null
        runCatching { controller?.stop() }.onFailure { Log.w(TAG, "controller.stop failed", it) }
        runCatching { hand?.stop() }.onFailure { Log.w(TAG, "hand.stop failed", it) }
        controller = null
        hand = null
        lastAim = null
        _label.value = null
        _aimActive.value = false
        lastHadHit = false
        _source.value = Source.NONE
        Log.i(TAG, "aim tracking stopped")
    }

    private fun startController() {
        val p = runCatching { ControllerTrackingProvider() }.getOrElse {
            Log.w(TAG, "ControllerTrackingProvider 构造失败", it)
            return
        }
        val support = runCatching { p.supportState }.getOrNull()
        Log.i(TAG, "controller supportState=$support")
        if (support != DataProvider.SupportState.SUPPORTED) {
            Log.i(TAG, "手柄不可用（$support），跳过")
            return
        }
        val started = runCatching { p.start() }.getOrElse {
            Log.w(TAG, "controller.start 抛异常", it)
            return
        }
        if (started != DataProvider.StartResult.SUCCESS && started != DataProvider.StartResult.PENDING) {
            Log.w(TAG, "controller.start 返回 $started，跳过")
            return
        }
        controller = p
        controllerJob = scope.launch {
            runCatching {
                p.dataFlow.collect { data -> onControllerData(data) }
            }.onFailure { Log.e(TAG, "controller dataFlow 中断", it) }
        }
        runCatching {
            // 显式对象而不是 lambda：SDK 侧的接口不是 Kotlin `fun interface`，
            // 直接传 lambda 编译不过（SAM 转换只对 fun interface 生效）
            p.addControllerActionListener(
                object : ControllerTrackingProvider.ControllerActionListener {
                    override fun onControllerAction(actions: ControllerActionData) {
                        onControllerActionEvent(actions)
                    }
                }
            )
        }.onFailure { Log.w(TAG, "addControllerActionListener 失败", it) }
    }

    private fun startHand() {
        val p = runCatching { HandTrackingProvider() }.getOrElse {
            Log.w(TAG, "HandTrackingProvider 构造失败", it)
            return
        }
        val support = runCatching { p.supportState }.getOrNull()
        Log.i(TAG, "hand supportState=$support")
        if (support != DataProvider.SupportState.SUPPORTED) {
            Log.i(TAG, "手势不可用（$support），跳过")
            return
        }
        val started = runCatching { p.start() }.getOrElse {
            Log.w(TAG, "hand.start 抛异常", it)
            return
        }
        if (started != DataProvider.StartResult.SUCCESS && started != DataProvider.StartResult.PENDING) {
            Log.w(TAG, "hand.start 返回 $started，跳过")
            return
        }
        hand = p
        handJob = scope.launch {
            runCatching {
                p.dataFlow.collect { data -> onHandData(data) }
            }.onFailure { Log.e(TAG, "hand dataFlow 中断", it) }
        }
    }

    // ---------- 手柄 ----------

    private fun onControllerData(data: ControllerTrackingData) {
        // 优先右手；右手没跟踪到再试左手
        val right: ControllerPose? = runCatching { data.right }.getOrNull()
        val left: ControllerPose? = runCatching { data.left }.getOrNull()
        val (pose, src) = when {
            isTracked(right) -> right!! to Source.CONTROLLER_RIGHT
            isTracked(left) -> left!! to Source.CONTROLLER_LEFT
            else -> return
        }
        val origin = Vec3(pose.position.x, pose.position.y, pose.position.z)
        val rot = Quat(pose.rotation.x, pose.rotation.y, pose.rotation.z, pose.rotation.w)
        publish(PlanePicker.rayFromPose(origin, rot), src)
    }

    private fun isTracked(pose: ControllerPose?): Boolean {
        if (pose == null) return false
        val p = pose.position
        return p.x.isFinite() && p.y.isFinite() && p.z.isFinite() &&
            !(p.x == 0f && p.y == 0f && p.z == 0f)
    }

    private fun onControllerActionEvent(actions: ControllerActionData) {
        // SDK 声明这两个字段非空，但没跟踪时会实测出现 null；整段包住，
        // 让"翻不动"退化成"没按扳机"，而不是 NPE 崩掉房间层
        val pressed = runCatching {
            actions.right.triggerPressed || actions.left.triggerPressed
        }.getOrDefault(false)
        if (pressed && !controllerTriggerDown) {
            controllerTriggerDown = true
            fire("trigger")
        } else if (!pressed) {
            controllerTriggerDown = false
        }
    }

    // ---------- 手势 ----------

    private fun onHandData(data: HandTrackingData) {
        // 与手柄不同：手势的 aim/pinch 在 SDK 里就是 nullable，用安全调用即可；
        // 外层再加 runCatching 兜住 SDK 内部异常（异常一旦冒到 collect 会终止整条流）
        val rightReady = runCatching { data.rightAim?.isReady == true }.getOrDefault(false)
        val leftReady = if (rightReady) false else
            runCatching { data.leftAim?.isReady == true }.getOrDefault(false)
        if (!rightReady && !leftReady) return

        val src = if (rightReady) Source.HAND_RIGHT else Source.HAND_LEFT
        val aimPose = runCatching {
            if (rightReady) data.rightAim?.aimPose else data.leftAim?.aimPose
        }.getOrNull() ?: return

        val p = aimPose.position
        val origin = Vec3(p.x, p.y, p.z)
        val r = aimPose.rotation
        publish(PlanePicker.rayFromPose(origin, Quat(r.x, r.y, r.z, r.w)), src)

        // 捏合：pinchValue 跨过阈值算一次按下
        val pinchValue = runCatching {
            (if (rightReady) data.rightPinch?.pinchValue else data.leftPinch?.pinchValue) ?: 0f
        }.getOrDefault(0f)
        val pinching = pinchValue > 0.6f
        if (pinching && !handPinchDown) {
            handPinchDown = true
            fire("pinch")
        } else if (!pinching) {
            handPinchDown = false
        }
    }

    // ---------- 共用 ----------

    /**
     * 合成瞄准的独占截止时间（毫秒时间戳）。
     *
     * 为什么需要：模拟器上**手势跟踪是开启的**，HandTrackingProvider 每帧都在
     * 发位姿。注入一条合成射线后，下一帧真实手部位姿立刻把它覆盖掉 —— 屏幕上
     * 只闪一下，根本来不及看结果。所以注入后 5 秒内忽略硬件输入。
     */
    @Volatile
    private var simulatedUntil = 0L

    /** 上一次是否有命中：只在"有→无 / 无→有"的跳变时打日志，避免 60Hz 刷屏。 */
    @Volatile
    private var lastHadHit = false

    private fun publish(ray: Ray, source: Source, simulated: Boolean = false) {
        // P1[高]#5：controllerJob 与 handJob 在 Dispatchers.Default 上并发调用本函数，
        // stop() 后还可能在途。入口先看 running，挡住"房间层销毁后又把 aimActive 翻回 true"。
        if (!running) return
        if (!simulated && System.currentTimeMillis() < simulatedUntil) return
        _source.value = if (simulated) Source.CONTROLLER_RIGHT else source
        val planes = PlaneSensor.planes.value
        val hit: PlaneHit? = if (planes.isEmpty()) {
            null
        } else {
            PlanePicker.pick(planes.map { it.shape }, ray)
        }
        val lastHadHitBefore = lastHadHit
        if (hit == null) {
            if (lastHadHit || simulated) {
                Log.i(
                    TAG,
                    "无命中 origin=(${ray.origin.x},${ray.origin.y},${ray.origin.z}) " +
                        "dir=(${ray.direction.x},${ray.direction.y},${ray.direction.z}) planes=${planes.size}",
                )
            }
            lastHadHit = false
            // P1[高]#5：先撤 aimActive 再清 lastAim —— 这样任何读者都不会观察到
            // "aimActive=true 但 lastAim 已被清空"的中间态。每步都是 StateFlow/
            // @Volatile 的单字段原子写（不再 `if(x) x=` 的读-改-写）。
            _aimActive.value = false
            lastAim = null
            _frames.tryEmit(null)
            _label.value = null
            return
        }
        lastHadHit = true
        val plane = planes.firstOrNull { it.shape === hit.plane }
        val label = plane?.label ?: "平面"
        if (simulated || !lastHadHitBefore) {
            Log.i(
                TAG,
                "命中 $label dist=${"%.3f".format(hit.distance)} " +
                    "point=(${hit.point.x},${hit.point.y},${hit.point.z}) " +
                    "uv=(${hit.local.u},${hit.local.v})",
            )
        }
        // 法线翻向观察者所在的一侧。
        // 平面是双面的，SDK 给的符号取决于顶点绕序（实测同场景里桌面朝下、地板朝上），
        // 直接拿它去 lift 会把卡片往桌面里埋 2cm —— 卡片被桌子吃掉一半，
        // 从上面看是"卡片不见了"，从侧面看是穿模。
        val rawNormal = hit.plane.normal
        val toViewer = ray.origin - hit.point
        val normal = if ((rawNormal dot toViewer) < 0f) -rawNormal else rawNormal
        val aim = Aim(
            point = hit.point,
            normal = normal,
            yawDeg = PlanePicker.yawToward(hit.point, ray.origin),
            distance = hit.distance,
            planeLabel = label,
            planeUuid = plane?.uuid ?: "",
            source = source,
        )
        // P1[高]#5/#6：先落 lastAim、自增 aimVersion，再翻 aimActive —— 保证
        // aimActive=true 时 lastAim 必是本次命中的最新值；aimVersion 供模拟到期
        // 复位协程判断"是否已被后续命中接管"。
        lastAim = aim
        aimVersion.incrementAndGet()
        _aimActive.value = true
        _frames.tryEmit(aim)
        _label.value = aim.describe()
    }

    private fun fire(how: String) {
        if (!running) {
            Log.i(TAG, "$how 触发，但取点已停止（房间层可能已销毁），忽略")
            return
        }
        val aim = lastAim
        val handler = onTrigger
        if (aim == null) {
            Log.i(TAG, "$how 触发，但当前没有瞄准任何平面（未识别平面或没指到）")
            return
        }
        if (handler == null) {
            Log.i(TAG, "$how 触发，但未处于放置模式，忽略：${aim.describe()}")
            return
        }
        // P1[高]#6：确认前校验命中平面仍在当前平面列表里。否则 lastAim 可能指向一个
        // 已被 REMOVED / 已 clearDemoPlanes 的过期锚点，把卡片钉到不存在的 planeUuid 上。
        val uuid = aim.planeUuid
        if (uuid.isNotEmpty() && PlaneSensor.planes.value.none { it.uuid == uuid }) {
            Log.w(TAG, "$how 命中已失效平面 uuid=$uuid（已不在 planes），忽略并复位瞄准状态")
            lastAim = null
            _aimActive.value = false
            _label.value = null
            return
        }
        Log.i(TAG, "$how 触发 → ${aim.describe()} point=(${aim.point.x},${aim.point.y},${aim.point.z})")
        handler(aim)
    }

    /** 手动确认（面板按钮 / 无头广播）。把"最近一次瞄准"当成扳机。 */
    fun confirmManually() = fire("manual")

    fun last(): Aim? = lastAim

    /**
     * 注入一条合成射线（模拟器 / 无头验证专用）。
     *
     * 关键在于它走的是**和真手柄完全相同的** [publish] → [PlanePicker] 路径，
     * 只是射线来源换成了广播参数。所以它验证的不是"假数据能显示"，而是
     * "几何判定 + 准星 + 落点 + 持久化"整条链路；真机上唯一多出来的是
     * "手柄位姿从哪来"这一件事。
     */
    fun simulateRay(origin: Vec3, direction: Vec3, source: Source = Source.CONTROLLER_RIGHT) {
        Log.i(
            TAG,
            "simulateRay origin=(${origin.x},${origin.y},${origin.z}) " +
                "dir=(${direction.x},${direction.y},${direction.z})"
        )
        if (!running) {
            Log.w(TAG, "simulateRay 忽略：取点未运行（先 start()/进房间层）")
            return
        }
        // 5 秒内屏蔽真实输入（模拟器手势跟踪是开着的，否则下一帧就被覆盖）
        simulatedUntil = System.currentTimeMillis() + SIM_EXCLUSIVE_MS
        publish(Ray.of(origin, direction), source, simulated = true)
        // P1[高]#6：注入命中后 _aimActive/lastAim 已置真，但复位原本只发生在"下一次
        // publish 走进无命中分支"。模拟器/无头环境里广播只发一次、没有后续硬件帧 →
        // aimActive 永久假可用，confirmManually 会把卡钉到过期锚点。挂一个与独占窗口
        // 对齐的一次性协程：到点若 aimVersion 未变（没有新的真实命中接管）就复位。
        val capturedVersion = aimVersion.get()
        val until = simulatedUntil
        simExpiryJob?.cancel()
        simExpiryJob = scope.launch {
            delay(SIM_EXCLUSIVE_MS + 100)
            if (running && aimVersion.get() == capturedVersion && System.currentTimeMillis() >= until) {
                lastAim = null
                _aimActive.value = false
                lastHadHit = false
                _label.value = null
                _frames.tryEmit(null)
                Log.i(TAG, "模拟瞄准到期且无真实输入接管 → 复位 aimActive=false")
            }
        }
    }

    /** 取消合成瞄准的独占并真正复位瞄准状态（避免清完模拟后 aimActive 仍停在 true）。 */
    fun clearSimulation() {
        simulatedUntil = 0L
        simExpiryJob?.cancel()
        simExpiryJob = null
        // P1[高]#6：复位模拟注入留下的 aim 状态；之后真实帧会自然接管。
        if (running) {
            lastAim = null
            _aimActive.value = false
            lastHadHit = false
            _label.value = null
            _frames.tryEmit(null)
        }
        Log.i(TAG, "simulation cleared, aim state reset")
    }
}