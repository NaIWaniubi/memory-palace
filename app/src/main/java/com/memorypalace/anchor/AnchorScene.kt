package com.memorypalace.anchor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import com.pico.spatial.core.math.EulerAngles
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.sense.world.WorldTrackingManager
import com.pico.spatial.sense.world.WorldTrackingResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 锚定场景状态（组合之外的单例，Stage 销毁重建不影响它）。
 *
 * 模拟器无法驱动空间指向交互（官方明示），因此 M2 的"放置锚点"
 * 通过 adb 广播无头驱动，坐标由 extra 指定（缺省用预设桌面位姿）：
 *
 *   adb shell am broadcast -a com.memorypalace.anchor.PLACE \
 *       --ef x 0.0 --ef y 1.2 --ef z -1.5 --es title "abandon v.放弃"
 *   adb shell am broadcast -a com.memorypalace.anchor.CLEAR
 */
object AnchorScene {

    const val TAG = "Anchor"
    const val ACTION_PLACE = "com.memorypalace.anchor.PLACE"
    const val ACTION_CLEAR = "com.memorypalace.anchor.CLEAR"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var store: AnchorStore

    /** 当前锚定记录；RoomStage 观察它重建场景实体。 */
    val records = MutableStateFlow<List<AnchorRecord>>(emptyList())

    fun register(context: Context) {
        store = AnchorStore(context.applicationContext)
        records.value = store.load()
        Log.i(TAG, "restored ${records.value.size} anchor record(s) from store")

        val filter = IntentFilter().apply {
            addAction(ACTION_PLACE)
            addAction(ACTION_CLEAR)
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
                            title = intent.getStringExtra("title") ?: "测试卡片",
                        )
                        ACTION_CLEAR -> clear()
                    }
                }
            },
            filter,
            ContextCompat.RECEIVER_EXPORTED,
        )
        Log.i(TAG, "anchor broadcast receiver registered")
    }

    /** 在指定位姿放一个锚点并持久化。 */
    fun place(x: Float, y: Float, z: Float, title: String) {
        scope.launch {
            Log.i(TAG, "createAnchor requested pos=($x,$y,$z) title=$title")
            val result = WorldTrackingManager.createAnchor(
                Vector3(x, y, z), EulerAngles(0f, 0f, 0f), "mp:$title"
            )
            val rec = when (result) {
                is WorldTrackingResult.Success -> {
                    val a = result.data ?: run {
                        Log.e(TAG, "createAnchor SUCCESS but data is null")
                        return@launch
                    }
                    val t = a.transform
                    Log.i(
                        TAG,
                        "createAnchor SUCCESS uuid=${a.anchorUUID} resolvedPos=" +
                            "(${t.position.x},${t.position.y},${t.position.z})"
                    )
                    AnchorRecord(
                        key = UUID.randomUUID().toString(),
                        uuid = a.anchorUUID.toString(),
                        title = title,
                        px = t.position.x, py = t.position.y, pz = t.position.z,
                        pitch = t.rotation.pitch, yaw = t.rotation.yaw, roll = t.rotation.roll,
                        placedAt = System.currentTimeMillis(),
                    )
                }
                is WorldTrackingResult.Error -> {
                    // 锚点服务不可用（如模拟器不支持）：记录回退位姿，场景里按位姿直渲并标注
                    Log.e(TAG, "createAnchor ERROR code=${result.errorCode} msg=${result.errorMessage}")
                    AnchorRecord(
                        key = UUID.randomUUID().toString(),
                        uuid = null,
                        title = title,
                        px = x, py = y, pz = z,
                        pitch = 0f, yaw = 0f, roll = 0f,
                        placedAt = System.currentTimeMillis(),
                    )
                }
                else -> {
                    Log.e(TAG, "createAnchor unknown result: $result")
                    return@launch
                }
            }
            records.value = records.value + rec
            store.save(records.value)
            Log.i(TAG, "record persisted key=${rec.key} uuid=${rec.uuid} total=${records.value.size}")
        }
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
                val loaded = (result.data ?: emptyArray()).associateBy { it.anchorUUID.toString() }
                anchored.forEach { rec ->
                    val a = loaded[rec.uuid]
                    if (a == null) {
                        Log.e(TAG, "verifyRestored: uuid=${rec.uuid} NOT resolved (anchor lost)")
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
            is WorldTrackingResult.Error ->
                Log.e(TAG, "verifyRestored: loadAnchor ERROR code=${result.errorCode} msg=${result.errorMessage}")
            else -> Log.e(TAG, "verifyRestored: unknown result $result")
        }
    }
}
