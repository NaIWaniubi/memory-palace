package com.memorypalace.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.util.Log
import androidx.core.content.ContextCompat
import com.memorypalace.core.importer.ApkgExtractor
import com.memorypalace.core.importer.CsvImporter
import com.memorypalace.core.importer.ImportStats
import com.memorypalace.core.importer.ImportedCard
import com.memorypalace.core.importer.TextImporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

/**
 * 导入服务（M5）：CSV / 纯文本 / Anki apkg 三种格式，离线本地解析，不联网。
 *
 * 无头驱动（模拟器验证用）：
 *   adb push deck.csv /sdcard/memory-palace/deck.csv
 *   adb shell am broadcast -a com.memorypalace.card.IMPORT --es path /sdcard/memory-palace/deck.csv
 *   adb shell am broadcast -a com.memorypalace.card.IMPORT --es path /sdcard/memory-palace/deck.apkg --es format apkg
 *
 * format 省略时按扩展名自动识别（.csv / .apkg / 其他=txt）。
 */
object ImportService {

    const val TAG = "Import"
    const val ACTION_IMPORT = "com.memorypalace.card.IMPORT"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun register(context: Context) {
        val filter = IntentFilter(ACTION_IMPORT)
        ContextCompat.registerReceiver(
            context.applicationContext,
            object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    val path = intent.getStringExtra("path") ?: return
                    val format = intent.getStringExtra("format")
                    scope.launch {
                        val stats = importFromPath(ctx.applicationContext, path, format)
                        Log.i(TAG, "broadcast import done: $stats")
                    }
                }
            },
            filter,
            ContextCompat.RECEIVER_EXPORTED,
        )
        Log.i(TAG, "import receiver registered")
    }

    /** 按路径导入（广播路径 / 测试路径）。返回统计，失败记日志并返回 null。 */
    suspend fun importFromPath(context: Context, path: String, format: String? = null): ImportStats? {
        val file = File(path)
        if (!file.isFile) {
            Log.w(TAG, "import: file not found: $path")
            return null
        }
        val fmt = (format ?: file.extension.lowercase().ifEmpty { "txt" })
        Log.i(TAG, "import start: path=$path format=$fmt size=${file.length()}B")
        return try {
            val cards = when (fmt) {
                "csv" -> CsvImporter.parse(file.readText(Charsets.UTF_8))
                "apkg" -> parseApkg(context, file)
                else -> TextImporter.parse(file.readText(Charsets.UTF_8))
            }
            persist(fmt, cards)
        } catch (t: Throwable) {
            Log.e(TAG, "import failed: $path ($fmt): ${t.message}", t)
            null
        }
    }

    /** 真机文件选择器路径：content:// Uri 先拷到缓存再按路径导入。 */
    suspend fun importFromUri(context: Context, uri: Uri): ImportStats? {
        val name = queryDisplayName(context, uri) ?: "import.csv"
        val tmp = File(context.cacheDir, "import_${System.currentTimeMillis()}_$name")
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            } ?: return null.also { Log.w(TAG, "import: cannot open $uri") }
            importFromPath(context, tmp.absolutePath, format = null)
        } finally {
            tmp.delete()
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (c.moveToFirst() && idx >= 0) c.getString(idx) else null
            }
        }.getOrNull()
    }

    /** apkg：解 zip 取 collection.anki2/21 → 临时文件 → SQLiteDatabase 只读打开 → notes.flds。 */
    private fun parseApkg(context: Context, file: File): List<ImportedCard> {
        val dbBytes = ApkgExtractor.extractCollection(file)
            ?: throw IllegalArgumentException("apkg 里没有 collection.anki2/anki21")
        val tmp = File(context.cacheDir, "apkg_${System.currentTimeMillis()}.anki2")
        try {
            tmp.writeBytes(dbBytes)
            val db = SQLiteDatabase.openDatabase(tmp.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            val out = mutableListOf<ImportedCard>()
            db.rawQuery("SELECT flds FROM notes", null).use { c ->
                while (c.moveToNext()) {
                    ApkgExtractor.splitFields(c.getString(0) ?: "")?.let { out.add(it) }
                }
            }
            db.close()
            return out
        } finally {
            tmp.delete()
        }
    }

    /** 按 front 去重后批量入库。 */
    private suspend fun persist(fmt: String, cards: List<ImportedCard>): ImportStats {
        val existing = DataGraph.repository.cards.first().map { it.front }.toHashSet()
        var imported = 0
        var dup = 0
        cards.forEach { c ->
            if (existing.add(c.front)) {
                DataGraph.repository.add(c.front, c.back)
                imported++
            } else {
                dup++
            }
        }
        val stats = ImportStats(format = fmt, parsed = cards.size, imported = imported, skippedDup = dup)
        Log.i(TAG, "import result: $stats")
        return stats
    }
}
