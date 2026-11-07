package com.memorypalace.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * M7 预置示例牌组：三套开箱可玩，覆盖不同记忆内容类型——
 * 语言词汇（lang_en.csv）、专业术语（tech_terms.csv）、生活常识（common_sense.txt）。
 *
 * 首次启动时从 assets 拷入私有目录后走 M5 同一导入管线（按 front 去重，
 * 所以重复安装/升级不会堆卡）。SharedPreferences 标记只控制"是否再跑一遍"，
 * 真正的幂等由导入管线的 front 去重兜底。
 */
object PresetDecks {

    const val TAG = "PresetDecks"
    private const val PREFS = "preset_decks"
    private const val KEY_IMPORTED = "v1_imported"

    private val DECKS = listOf("lang_en.csv", "tech_terms.csv", "common_sense.txt")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun installIfFirstLaunch(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_IMPORTED, false)) {
            Log.i(TAG, "preset decks already imported, skip")
            return
        }
        scope.launch {
            val dir = File(context.filesDir, "decks").apply { mkdirs() }
            DECKS.forEach { name ->
                try {
                    val out = File(dir, name)
                    context.assets.open("decks/$name").use { input ->
                        out.outputStream().use { input.copyTo(it) }
                    }
                    val stats = ImportService.importFromPath(context, out.absolutePath)
                    Log.i(TAG, "preset deck $name -> $stats")
                } catch (t: Throwable) {
                    Log.e(TAG, "preset deck $name failed: ${t.message}", t)
                }
            }
            prefs.edit().putBoolean(KEY_IMPORTED, true).apply()
            Log.i(TAG, "preset decks first-launch import done")
        }
    }
}
