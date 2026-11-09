package com.memorypalace.anchor

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 一条锚定记录：卡片 → 空间锚点（或回退位姿）的绑定。 */
data class AnchorRecord(
    val key: String,          // 记录主键（= 卡片占位 ID，M3 起对接真实卡片 ID）
    val uuid: String?,        // 系统锚点 UUID；createAnchor 失败时为 null（回退模式）
    val title: String,
    val px: Float, val py: Float, val pz: Float,
    val pitch: Float, val yaw: Float, val roll: Float,
    val placedAt: Long,
    /**
     * M16：落点的语义名（「桌面」「沙发」「墙」…）。
     * 卡片上直接写「钉在桌面上」—— 这是"记忆宫殿"与"随便放个窗口"的分界线。
     * 旧记录没有这个字段，读出来是 null，卡片退回显示锚点 UUID。
     */
    val anchorLabel: String? = null,
)

/**
 * 锚定数据持久层（M2 用 SharedPreferences + JSON，M3 迁移 Room）。
 * M1 实测结论：Stage 每次进入 Full Space 销毁重建，
 * 因此锚点 UUID 与位姿必须在组合之外持久化，重建后按 UUID 恢复。
 */
class AnchorStore(context: Context) {

    private val prefs = context.getSharedPreferences("anchor_store", Context.MODE_PRIVATE)

    fun load(): List<AnchorRecord> {
        val raw = prefs.getString(KEY_RECORDS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                AnchorRecord(
                    key = o.getString("key"),
                    uuid = if (o.isNull("uuid")) null else o.getString("uuid"),
                    title = o.getString("title"),
                    px = o.getDouble("px").toFloat(),
                    py = o.getDouble("py").toFloat(),
                    pz = o.getDouble("pz").toFloat(),
                    pitch = o.getDouble("pitch").toFloat(),
                    yaw = o.getDouble("yaw").toFloat(),
                    roll = o.getDouble("roll").toFloat(),
                    placedAt = o.getLong("placedAt"),
                    // 旧版本写入的记录没有这个键，不能直接 getString（会抛 JSONException
                    // 并被外层 getOrElse 吞成"全部记录丢失"，那是最坏的结果）
                    anchorLabel = if (o.has("anchorLabel") && !o.isNull("anchorLabel")) {
                        o.getString("anchorLabel")
                    } else null,
                )
            }
        }.getOrElse { emptyList() }
    }

    fun save(records: List<AnchorRecord>) {
        val arr = JSONArray()
        records.forEach { r ->
            arr.put(JSONObject().apply {
                put("key", r.key)
                put("uuid", r.uuid ?: JSONObject.NULL)
                put("title", r.title)
                put("px", r.px.toDouble()); put("py", r.py.toDouble()); put("pz", r.pz.toDouble())
                put("pitch", r.pitch.toDouble()); put("yaw", r.yaw.toDouble()); put("roll", r.roll.toDouble())
                put("placedAt", r.placedAt)
                put("anchorLabel", r.anchorLabel ?: JSONObject.NULL)
            })
        }
        prefs.edit().putString(KEY_RECORDS, arr.toString()).apply()
    }

    companion object { private const val KEY_RECORDS = "records" }
}
