package com.memorypalace.anchor

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * 逐条容错解析（纯函数，JVM 可测）：
 * [count] 条记录，第 i 条由 [parseAt] 解析；任何一条抛异常只丢它自己（记进返回的
 * 失败下标列表），其余好记录原样保留。
 *
 * 这是 P2 阻断项的核心修复：旧实现把整个列表包在一个 runCatching 里 getOrElse
 * emptyList()，一条坏 JSON 会让全部锚点"读不出来"，下一次 save 再把空态覆写落盘，
 * 永久丢失所有历史锚点（含仍活着的系统锚点 UUID）。
 */
internal fun <T> parseRecordsTolerantly(count: Int, parseAt: (Int) -> T): Pair<List<T>, List<Int>> {
    val good = ArrayList<T>(maxOf(count, 0))
    val failed = ArrayList<Int>()
    for (i in 0 until count) {
        try {
            good.add(parseAt(i))
        } catch (@Suppress("TooGenericExceptionCaught") t: Throwable) {
            failed.add(i)
        }
    }
    return good to failed
}

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
        // 结构性损坏（整串不是合法 JSON）无从逐条救：备份原始串、摘走坏键，
        // 返回空但**不写空列表覆盖**——原始数据留在 CORRUPT_BACKUP 里可人工找回。
        val arr = try {
            JSONArray(raw)
        } catch (@Suppress("TooGenericExceptionCaught") t: Throwable) {
            Log.e(TAG, "anchor JSON unreadable; raw preserved in '$KEY_CORRUPT_BACKUP'", t)
            preserveCorrupt(raw)
            return emptyList()
        }
        // 逐条隔离：坏记录只丢自己，好记录全部保留 —— 绝不因一条坏 JSON 清空整表。
        val (records, failed) = parseRecordsTolerantly(arr.length()) { i ->
            parseOne(arr.getJSONObject(i))
        }
        if (failed.isNotEmpty()) {
            Log.e(TAG, "load: dropped ${failed.size} corrupt record(s) at $failed; kept ${records.size}")
            preserveCorrupt(raw) // 留一份原始证据，便于事后核对被丢的那几条
        }
        return records
    }

    /** 单条记录 → AnchorRecord。缺字段的坏记录在这里抛，由 [parseRecordsTolerantly] 接住跳过。 */
    private fun parseOne(o: JSONObject): AnchorRecord {
        val uuidStr = if (o.isNull("uuid")) null else o.getString("uuid")
        return AnchorRecord(
            key = o.getString("key"),
            uuid = uuidStr,
            title = o.getString("title"),
            px = o.getDouble("px").toFloat(),
            py = o.getDouble("py").toFloat(),
            pz = o.getDouble("pz").toFloat(),
            pitch = o.getDouble("pitch").toFloat(),
            yaw = o.getDouble("yaw").toFloat(),
            roll = o.getDouble("roll").toFloat(),
            placedAt = o.getLong("placedAt"),
            // 旧版本写入的记录没有这个键：这是**兼容**不是坏数据，退回 null，不能让
            // 它抛异常连累整条记录被丢（外层已逐条隔离，但仍要显式 opt）。
            anchorLabel = if (o.has("anchorLabel") && !o.isNull("anchorLabel")) {
                o.getString("anchorLabel")
            } else null,
        )
    }

    /** 保留一份损坏原始串（已有备份则不覆盖，避免把首次事故现场冲掉）。 */
    private fun preserveCorrupt(raw: String) {
        if (prefs.getString(KEY_CORRUPT_BACKUP, null) == null) {
            prefs.edit().putString(KEY_CORRUPT_BACKUP, raw).apply()
        }
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

    companion object {
        private const val TAG = "Anchor"
        private const val KEY_RECORDS = "records"

        /** 解析失败时的原始 JSON 留证位；只在无备份时写一次，保留首次事故现场。 */
        private const val KEY_CORRUPT_BACKUP = "records_corrupt_backup"
    }
}
