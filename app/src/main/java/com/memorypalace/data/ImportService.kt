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
    const val ACTION_PREVIEW = "com.memorypalace.card.PREVIEW"  // 无头验证确认页用：只解析不入库
    const val ACTION_CONFIRM = "com.memorypalace.card.CONFIRM"  // 无头验证确认页用：等价点「确认导入」

    /** 待确认的导入预览（字段映射确认页，M8）：SAF 选完文件先解析到这里，书房面板确认后才入库。 */
    data class ImportPreview(
        val format: String,
        val fileName: String,
        val cards: List<com.memorypalace.core.importer.ImportedCard>,
    )

    val pendingPreview = kotlinx.coroutines.flow.MutableStateFlow<ImportPreview?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun register(context: Context) {
        val filter = IntentFilter(ACTION_IMPORT).apply { addAction(ACTION_PREVIEW); addAction(ACTION_CONFIRM) }
        ContextCompat.registerReceiver(
            context.applicationContext,
            object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    val path = intent.getStringExtra("path")
                    val format = intent.getStringExtra("format")
                    scope.launch {
                        when (intent.action) {
                            ACTION_CONFIRM -> Log.i(TAG, "confirm import done: ${confirmImport()}")
                            ACTION_PREVIEW -> {
                                if (path == null) return@launch
                                val preview = previewFromPath(ctx.applicationContext, path, format)
                                if (preview != null) {
                                    pendingPreview.value = preview
                                    Log.i(TAG, "preview ready: ${preview.fileName} format=${preview.format} parsed=${preview.cards.size}")
                                }
                            }
                            else -> {
                                if (path == null) return@launch
                                val stats = importFromPath(ctx.applicationContext, path, format)
                                Log.i(TAG, "broadcast import done: $stats")
                            }
                        }
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
        val preview = previewFromPath(context, path, format) ?: return null
        return persistImported(preview.format, preview.cards)
    }

    /** 只解析不入库：字段映射确认页的数据源。 */
    fun previewFromPath(context: Context, path: String, format: String? = null): ImportPreview? {
        val file = File(path)
        if (!file.isFile) {
            Log.w(TAG, "preview: file not found: $path")
            return null
        }
        val fmt = (format ?: file.extension.lowercase().ifEmpty { "txt" })
        Log.i(TAG, "preview start: path=$path format=$fmt size=${file.length()}B")
        return try {
            val cards = when (fmt) {
                "csv" -> CsvImporter.parse(file.readText(Charsets.UTF_8))
                "apkg" -> parseApkg(context, file)
                else -> TextImporter.parse(file.readText(Charsets.UTF_8))
            }
            ImportPreview(format = fmt, fileName = file.name, cards = cards)
        } catch (t: Throwable) {
            Log.e(TAG, "preview failed: $path ($fmt): ${t.message}", t)
            null
        }
    }

    /** 真机文件选择器路径：解析为预览并发布到 pendingPreview，由书房面板确认页接管。 */
    suspend fun previewFromUri(context: Context, uri: Uri) {
        val name = queryDisplayName(context, uri) ?: "import.csv"
        val tmp = File(context.cacheDir, "import_${System.currentTimeMillis()}_$name")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            } ?: run { Log.w(TAG, "preview: cannot open $uri"); return }
            val preview = previewFromPath(context, tmp.absolutePath)
            if (preview != null) {
                pendingPreview.value = preview.copy(fileName = name)
                Log.i(TAG, "preview ready: ${name} format=${preview.format} parsed=${preview.cards.size}")
            }
        } finally {
            tmp.delete()
        }
    }

    /** 确认页点「确认导入」：把预览卡片入库并清空待确认态。 */
    suspend fun confirmImport(): ImportStats? {
        val preview = pendingPreview.value ?: return null
        val stats = persistImported(preview.format, preview.cards)
        pendingPreview.value = null
        Log.i(TAG, "confirm import done: $stats")
        return stats
    }

    fun cancelPreview() {
        pendingPreview.value = null
        Log.i(TAG, "preview canceled")
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
    suspend fun persistImported(fmt: String, cards: List<ImportedCard>): ImportStats {
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