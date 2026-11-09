package com.memorypalace.anchor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import com.memorypalace.audio.SpatialCue
import com.memorypalace.data.DataGraph
import com.pico.spatial.core.math.EulerAngles
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.sense.world.WorldTrackingManager
import com.pico.spatial.sense.world.WorldTrackingResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 锚定场景状态（组合之外的单例，Stage 销毁重建不影响它）。
 *
 * 模拟器无法驱动空间指向交互（官方明示），因此 M2 的"放置锚点"
 * 通过 adb 广播无头驱动，坐标由 extra 指定（缺省用预设桌面位姿）：
 *
 *   adb shell am broadcast -a com.memorypalace.anchor.PLACE \
 *       --ef x 0.0 --ef y 1.2 --ef z -1.5 --es front "abandon" --es back "v.放弃"
 *   adb shell am broadcast -a com.memorypalace.anchor.CLEAR
 *
 * M3 起：放置即建卡 —— front/back 先落入 Room 得到真实卡片 ID，
 * AnchorRecord.key 直接绑定该卡片 ID（不再是占位标题）。
 */
object AnchorScene {

    const val TAG = "Anchor"
    const val ACTION_PLACE = "com.memorypalace.anchor.PLACE"
    const val ACTION_CLEAR = "com.memorypalace.anchor.CLEAR"
    /** M16：无头触发取点放置（模拟器没有扳机，用它验证"瞄准→提交"这条链路）。 */
    const val ACTION_AIM_BEGIN = "com.memorypalace.anchor.AIM_BEGIN"
    const val ACTION_AIM_CONFIRM = "com.memorypalace.anchor.AIM_CONFIRM"
    const val ACTION_AIM_CANCEL = "com.memorypalace.anchor.AIM_CANCEL"
    /** M16：无头注入一条合成瞄准（没有真机时验证 UI 与落点计算）。 */
    const val ACTION_AIM_SIMULATE = "com.memorypalace.anchor.AIM_SIMULATE"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var store: AnchorStore

    /** 当前锚定记录；RoomStage 观察它重建场景实体。 */
    val records = MutableStateFlow<List<AnchorRecord>>(emptyList())

    /**
     * 待播提示音：卡片 ID -> 提示音名。
     * 放置确认音必须在卡片实体建出来之后才能播（空间音源挂在实体上），
     * 而实体是 RoomStage 重建场景时才有的。所以放置时先挂这里，
     * 由 RoomStage 注册实体后取走并播放。
     */
    private val pendingCues = mutableMapOf<String, String>()

    /** 登记一条待播提示音；RoomStage 会在实体就绪后取走。 */
    fun enqueueCue(cardId: String, cue: String) {
        pendingCues[cardId] = cue
    }

    /** 取走并移除待播提示音；没有则返回 null。 */
    fun takePendingCue(cardId: String): String? = pendingCues.remove(cardId)

    /** 全部待播提示音（RoomStage 重建后统一取）。 */
    fun drainPendingCues(): Map<String, String> {
        val copy = pendingCues.toMap()
        pendingCues.clear()
        return copy
    }

    fun register(context: Context) {
        store = AnchorStore(context.applicationContext)
        records.value = store.load()
        Log.i(TAG, "restored ${records.value.size} anchor record(s) from store")

        val filter = IntentFilter().apply {
            addAction(ACTION_PLACE)
            addAction(ACTION_CLEAR)
            addAction(ACTION_AIM_BEGIN)
            addAction(ACTION_AIM_CONFIRM)
            addAction(ACTION_AIM_CANCEL)
            addAction(ACTION_AIM_SIMULATE)
        }
        ContextCompat.registerReceiver(
            context.applicationContext,
            object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    when (intent.action) {
                        ACTION_PLACE -> place(
                            x = intent.getFloatExtra("x", 0.0f),
                            y = intent.getFloatExtra("y", 1.2f),
                            z = intent.getFloatExtra("z", -1.5f),
                            front = intent.getStringExtra("front")
                                ?: intent.getStringExtra("title") ?: "测试卡片",
                            back = intent.getStringExtra("back") ?: "",
                            yaw = intent.getFloatExtra("yaw", 0f),
                            label = intent.getStringExtra("label"),
                        )
                        ACTION_CLEAR -> clear()
                        ACTION_AIM_BEGIN -> {
                            // card 缺省用第一张未锚定的卡 —— 无头验证时少一个参数。
                            // 必须切协程：读卡片要查 Room，广播回调在主线程，同步查会 ANR
                            val explicit = intent.getStringExtra("card")
                            scope.launch {
                                val id = explicit ?: firstUnanchoredCardId()
                                if (id == null) Log.w(TAG, "AIM_BEGIN: 没有未锚定的卡片")
                                else beginAimPlacement(id)
                            }
                        }
                        ACTION_AIM_CONFIRM -> com.memorypalace.spatial.AimPlacement.confirmManually()
                        ACTION_AIM_CANCEL -> cancelAimPlacement()
                        ACTION_AIM_SIMULATE -> {
                            com.memorypalace.spatial.AimPlacement.simulateRay(
                                origin = com.memorypalace.core.spatial.Vec3(
                                    intent.getFloatExtra("ox", 0f),
                                    intent.getFloatExtra("oy", 1.6f),
                                    intent.getFloatExtra("oz", 0f),
                                ),
                                direction = com.memorypalace.core.spatial.Vec3(
                                    intent.getFloatExtra("dx", 0f),
                                    intent.getFloatExtra("dy", -1f),
                                    intent.getFloatExtra("dz", 0f),
                                ),
                            )
                        }
                    }
                }
            },
            filter,
            com.memorypalace.debugReceiverFlag,
        )
        Log.i(TAG, "anchor broadcast receiver registered")
    }

    /**
     * 无头取点用：第一张还没进房间的卡片。
     * suspend：读卡片要走 Room 查询，不能在广播回调的主线程上同步做。
     */
    private suspend fun firstUnanchoredCardId(): String? = runCatching {
        val cards = DataGraph.repository.cards.first()
        val anchored = records.value.map { it.key }.toSet()
        cards.firstOrNull { it.id !in anchored }?.id
    }.getOrElse {
        Log.w(TAG, "firstUnanchoredCardId failed", it)
        null
    }

    /** 在指定位姿放一个锚点并持久化；M3 起同时建卡，记录 key = 卡片 ID。 */
    fun place(x: Float, y: Float, z: Float, front: String, back: String, yaw: Float = 0f, label: String? = null) {
        scope.launch {
            val card = DataGraph.repository.add(front, back)
            bind(card.id, if (back.isBlank()) front else "$front  $back", x, y, z, yaw, label)
        }
    }

    /**
     * 把已有卡片钉进房间（预设位姿路径：没有平面检测/没有手柄时的回退）。
     * 不新建卡、不重复锚定；位姿缺省由调用方给（如面板按钮用预设面前位姿）。
     */
    fun placeForCard(cardId: String, x: Float, y: Float, z: Float, yaw: Float = 0f, label: String? = null) {
        scope.launch {
            val card = DataGraph.repository.cards.first().firstOrNull { it.id == cardId }
            if (card == null) {
                Log.w(TAG, "placeForCard: card not found $cardId")
                return@launch
            }
            if (records.value.any { it.key == cardId }) {
                Log.i(TAG, "placeForCard: already anchored ${card.front}, skip")
                return@launch
            }
            bind(card.id, "${card.front}  ${card.back}", x, y, z, yaw, label)
        }
    }

    // ---------- M16：手柄 / 手势取点放置 ----------

    /** 正在等扳机的卡片 ID；null = 不在放置模式。 */
    val aimTarget = MutableStateFlow<String?>(null)

    /**
     * 取点是否可用：已识别到平面，**并且**当前真的指着某个平面。
     *
     * 第二个条件很关键。只看"有平面 + 有输入设备"会在模拟器上给出假的可行状态：
     * 平面有 57 个、手势跟踪也返回 SUPPORTED，但没有人真的在指 —— 用户点了
     * 取点按钮之后会卡在"等扳机"里出不来。
     */
    fun canAimPlace(): Boolean =
        com.memorypalace.spatial.PlaneSensor.planes.value.isNotEmpty() &&
            com.memorypalace.spatial.AimPlacement.aimActive.value

    /**
     * 进入取点放置模式：瞄准任意已识别平面，按扳机 / 捏合确认。
     *
     * 确认回调里立刻退出模式（幂等）—— 扳机容易连发，不退出会把同一张卡
     * 在相邻两帧建出两个锚点，卡片就重叠成一片。
     */
    fun beginAimPlacement(cardId: String) {
        if (records.value.any { it.key == cardId }) {
            Log.i(TAG, "beginAimPlacement: $cardId 已锚定，忽略")
            return
        }
        aimTarget.value = cardId
        com.memorypalace.spatial.AimPlacement.onTrigger = { aim ->
            val pending = aimTarget.value
            if (pending == null) {
                Log.i(TAG, "扳机确认但已退出放置模式，忽略")
            } else {
                aimTarget.value = null
                com.memorypalace.spatial.AimPlacement.onTrigger = null
                Log.i(
                    TAG,
                    "aim placement committed card=${pending.take(8)} plane=${aim.planeLabel} " +
                        "point=(${aim.point.x},${aim.point.y},${aim.point.z}) yaw=${aim.yawDeg}"
                )
                val lift = com.memorypalace.core.spatial.PlanePicker.lift(aim.point, aim.normal)
                scope.launch {
                    val card = DataGraph.repository.cards.first().firstOrNull { it.id == pending }
                    if (card == null) {
                        Log.w(TAG, "aim commit: card not found $pending")
                        return@launch
                    }
                    bind(
                        cardId = card.id,
                        title = "${card.front}  ${card.back}",
                        x = lift.x, y = lift.y, z = lift.z,
                        yaw = aim.yawDeg,
                        label = aim.planeLabel,
                    )
                }
            }
        }
        Log.i(TAG, "aim placement mode entered for card=${cardId.take(8)}")
    }

    fun cancelAimPlacement() {
        if (aimTarget.value == null) return
        aimTarget.value = null
        com.memorypalace.spatial.AimPlacement.onTrigger = null
        Log.i(TAG, "aim placement mode cancelled")
    }

    /** 建锚 + 落记录 + 持久化（place / placeForCard / 取点放置共用）。 */
    private suspend fun bind(
        cardId: String,
        title: String,
        x: Float, y: Float, z: Float,
        yaw: Float = 0f,
        label: String? = null,
    ) {
        Log.i(TAG, "createAnchor requested pos=($x,$y,$z) yaw=$yaw label=$label card=${cardId.take(8)}")
        val result = WorldTrackingManager.createAnchor(
            Vector3(x, y, z), EulerAngles(0f, yaw, 0f), "mp:${cardId.take(8)}"
        )
        val rec = when (result) {
            is WorldTrackingResult.Success -> {
                val a = result.data ?: run {
                    Log.e(TAG, "createAnchor SUCCESS but data is null")
                    return
                }
                val t = a.transform
                Log.i(
                    TAG,
                    "createAnchor SUCCESS uuid=${a.anchorUUID} resolvedPos=" +
                        "(${t.position.x},${t.position.y},${t.position.z})"
                )
                AnchorRecord(
                    key = cardId,
                    uuid = a.anchorUUID.toString(),
                    title = title,
                    px = t.position.x, py = t.position.y, pz = t.position.z,
                    pitch = t.rotation.pitch, yaw = t.rotation.yaw, roll = t.rotation.roll,
                    placedAt = System.currentTimeMillis(),
                    anchorLabel = label,
                )
            }
            is WorldTrackingResult.Error -> {
                // 锚点服务不可用（如模拟器不支持）：记录回退位姿，场景里按位姿直渲并标注
                Log.e(TAG, "createAnchor ERROR code=${result.errorCode} msg=${result.errorMessage}")
                AnchorRecord(
                    key = cardId,
                    uuid = null,
                    title = title,
                    px = x, py = y, pz = z,
                    pitch = 0f, yaw = yaw, roll = 0f,
                    placedAt = System.currentTimeMillis(),
                    anchorLabel = label,
                )
            }
            else -> {
                Log.e(TAG, "createAnchor unknown result: $result")
                return
            }
        }
        records.value = records.value + rec
        store.save(records.value)
        // M13：放置确认音排队，等 RoomStage 把卡片实体建出来后再从该位置播
        enqueueCue(cardId, SpatialCue.CUE_PLACE)
        Log.i(TAG, "record persisted key=${rec.key} uuid=${rec.uuid} label=${rec.anchorLabel} total=${records.value.size}")
    }

    fun clear() {
        scope.launch {
            records.value.forEach { rec ->
                rec.uuid?.let {
                    runCatching { WorldTrackingManager.removeAnchor(UUID.fromString(it)) }
                        .onFailure { e -> Log.e(TAG, "removeAnchor($it) failed", e) }
                }
            }
            records.value = emptyList()
            store.save(emptyList())
            Log.i(TAG, "all anchors cleared")
        }
    }

    /**
     * Stage 重建后的恢复校验：按 UUID 调 loadAnchor，把系统解析出的位姿
     * 与持久层保存的位姿逐条对比打日志（一致性证据）。
     *
     * M6 增补：解析不出来的 UUID（锚点丢失，如模拟器进程重启后）会把记录
     * 降级为 uuid=null 并持久化 —— 场景按保存位姿回退直渲，卡片不消失。
     */
    suspend fun verifyRestored(recs: List<AnchorRecord>) {
        val anchored = recs.filter { it.uuid != null }
        if (anchored.isEmpty()) {
            Log.i(TAG, "verifyRestored: no uuid-bound records, skip")
            return
        }
        val uuids = anchored.map { UUID.fromString(it.uuid) }.toTypedArray()
        when (val result = WorldTrackingManager.loadAnchor(uuids)) {
            is WorldTrackingResult.Success -> {
                reportLoaded(anchored, (result.data ?: emptyArray()).associateBy { it.anchorUUID.toString() })
            }
            is WorldTrackingResult.Error -> {
                // 批量里混入一个丢失的 UUID 会整体报错（code=-7），退化为逐条加载
                Log.w(TAG, "verifyRestored: batch loadAnchor ERROR code=${result.errorCode} msg=${result.errorMessage}，逐条重试")
                anchored.forEach { rec ->
                    when (val r = WorldTrackingManager.loadAnchor(arrayOf(UUID.fromString(rec.uuid)))) {
                        is WorldTrackingResult.Success ->
                            reportLoaded(listOf(rec), (r.data ?: emptyArray()).associateBy { it.anchorUUID.toString() })
                        is WorldTrackingResult.Error -> {
                            Log.e(TAG, "verifyRestored: uuid=${rec.uuid} NOT resolved code=${r.errorCode} msg=${r.errorMessage}")
                            rec.uuid?.let { u -> synchronized(lostLock) { lost.add(u) } }
                        }
                        else -> Log.e(TAG, "verifyRestored: unknown result $r")
                    }
                }
            }
            else -> Log.e(TAG, "verifyRestored: unknown result $result")
        }
        downgradeLost()
    }

    /** 把 loadAnchor 解析失败的记录降级为回退位姿（uuid=null），持久化并触发场景重建。 */
    private fun downgradeLost() {
        val lostUuids = synchronized(lostLock) { lost.toSet().also { lost.clear() } }
        if (lostUuids.isEmpty()) return
        records.value = records.value.map { rec ->
            if (rec.uuid in lostUuids) rec.copy(uuid = null) else rec
        }
        store.save(records.value)
        Log.w(TAG, "downgraded ${lostUuids.size} lost anchor(s) to fallback pose: $lostUuids")
    }

    private val lostLock = Any()
    private val lost = mutableListOf<String>()

    private fun reportLoaded(anchored: List<AnchorRecord>, loaded: Map<String, com.pico.spatial.sense.world.WorldAnchor>) {
        anchored.forEach { rec ->
            val a = loaded[rec.uuid]
            if (a == null) {
                Log.e(TAG, "verifyRestored: uuid=${rec.uuid} NOT resolved (anchor lost)")
                rec.uuid?.let { u -> synchronized(lostLock) { lost.add(u) } }
            } else {
                val p = a.transform.position
                val dx = p.x - rec.px; val dy = p.y - rec.py; val dz = p.z - rec.pz
                val drift = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
                Log.i(
                    TAG,
                    "verifyRestored: uuid=${rec.uuid} title=${rec.title} " +
                        "saved=(${rec.px},${rec.py},${rec.pz}) " +
                        "resolved=(${p.x},${p.y},${p.z}) drift=${drift}m"
                )
            }
        }
    }
}
