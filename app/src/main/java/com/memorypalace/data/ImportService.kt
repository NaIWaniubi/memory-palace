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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream

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

    /**
     * 导入大小闸（P2 高危：CSV/TXT readText 无尺寸上限 → 超大文件整份进内存 OOM 崩进程）。
     * 选 32 MiB：够装下任何合理词表 CSV/TXT；再大直接拒绝解析并提示，安全失败而非崩溃。
     * apkg 的 zip bomb 另由 [ApkgExtractor.extractCollection] 的解压字节上限防护。
     */
    const val MAX_TEXT_BYTES = 32L * 1024 * 1024 // 32 MiB

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
            com.memorypalace.debugReceiverFlag,
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
        val size = file.length()
        Log.i(TAG, "preview start: path=$path format=$fmt size=${size}B")
        // 尺寸闸（P2 高危）：CSV/TXT 走整文件 readText，超限直接安全失败，不 OOM。
        // apkg 的内存防护在 ApkgExtractor 内部（解压字节上限），这里也先拦一道原始包体积。
        if (size > MAX_TEXT_BYTES) {
            Log.w(TAG, "preview: $path 太大（${size}B > ${MAX_TEXT_BYTES}B），拒绝导入")
            return null
        }
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

    /**
     * 真机文件选择器路径：解析为预览并发布到 pendingPreview，由书房面板确认页接管。
     *
     * 两处必须注意（2026-11-09 审查发现）：
     * ① 全流程 try/catch —— provider 打不开文件（SecurityException/FileNotFound）、
     *    文件名含 `/` 或超长（ENAMETOOLONG）、cache 满（IOException）都会抛出去。
     *    调用方是 Activity 的 Main scope，没有异常处理器 → 直接崩进程。
     * ② 全流程在 Dispatchers.IO —— 查询文件名是 IPC、拷贝整文件、readText、
     *    开 SQLite 都是阻塞调用，放在主线程上导入几 MB 的 CSV 就卡 UI。
     */
    suspend fun previewFromUri(context: Context, uri: Uri) = withContext(Dispatchers.IO) {
        var tmp: File? = null
        try {
            val rawName = queryDisplayName(context, uri) ?: "import.csv"
            // 文件名只用来展示，落盘用 UUID —— 避免 `/`、超长名、控制字符
            val safeExt = rawName.substringAfterLast('.', "").lowercase()
                .filter { it.isLetterOrDigit() }.take(8).ifEmpty { "dat" }
            val displayName = rawName.replace('/', '_').take(120).ifEmpty { "import.$safeExt" }
            tmp = File(context.cacheDir, "import_${System.currentTimeMillis()}.$safeExt")

            val copied = runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    tmp.outputStream().use { out -> copyCapped(input, out, MAX_TEXT_BYTES) }
                } ?: 0L
            }.onFailure { Log.e(TAG, "preview: copy failed for $uri", it) }.getOrDefault(0L)
            if (copied == 0L) {
                Log.w(TAG, "preview: cannot open or empty: $uri")
                return@withContext
            }
            if (copied >= MAX_TEXT_BYTES) {
                // P2 高危：SAF 拷进 cacheDir 也无上限会塞满分区；到顶说明文件超限，安全失败。
                Log.w(TAG, "preview: $displayName 达上限 ${MAX_TEXT_BYTES}B，拒绝（疑似超大文件）")
                return@withContext
            }

            val preview = runCatching { previewFromPath(context, tmp.absolutePath) }
                .onFailure { Log.e(TAG, "preview: parse failed for $displayName", it) }
                .getOrNull() ?: return@withContext

            pendingPreview.value = preview.copy(fileName = displayName)
            Log.i(TAG, "preview ready: $displayName format=${preview.format} parsed=${preview.cards.size}")
        } catch (t: Throwable) {
            // 兜底：任何没料到的异常都不允许冒到 Main scope 崩掉进程
            Log.e(TAG, "previewFromUri failed: ${t.message}", t)
        } finally {
            runCatching { tmp?.delete() }
        }
    }

    /** 导入进行中的入库串行锁（P2 中危：查重-插入非事务）。见 persistImported。 */
    private val persistLock = Mutex()
    private val confirmLock = Mutex()

    /** 确认页点「确认导入」：把预览卡片原子认领、入库并清空待确认态。 */
    suspend fun confirmImport(): ImportStats? = confirmLock.withLock {
        // 认领预览（P2 中危：读-清非原子，双击/面板确认+CONFIRM 广播并发 → 同一批入库两遍）。
        // 本函数已被 confirmLock 串行化；再用 compareAndSet 原子置空，连"确认"与"取消预览"
        // 并发也只会有一方认领成功，第二个读到 null 或被 CAS 挡下 → 返回 null 不重复入库。
        val preview = pendingPreview.value ?: return@withLock null
        if (!pendingPreview.compareAndSet(preview, null)) {
            Log.i(TAG, "confirm skipped: preview already claimed by a concurrent confirm")
            return@withLock null
        }
        try {
            persistImported(preview.format, preview.cards).also { Log.i(TAG, "confirm import done: $it") }
        } catch (t: Throwable) {
            // 入库失败回滚认领，让用户能在确认页重试；期间若来了新预览则不覆盖。
            pendingPreview.compareAndSet(null, preview)
            throw t
        }
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
    suspend fun persistImported(fmt: String, cards: List<ImportedCard>): ImportStats = persistLock.withLock {
        val existing = DataGraph.repository.cards.first().map { it.front }
        val plan = planImportDedup(existing, cards)
        plan.toInsert.forEach { c -> DataGraph.repository.add(c.front, c.back) }
        val stats = ImportStats(format = fmt, parsed = cards.size, imported = plan.toInsert.size, skippedDup = plan.duplicates)
        Log.i(TAG, "import result: $stats")
        stats
    }
}

// ---------- 纯逻辑（导入去重，JVM 可单测；置于顶层避开 DataGraph/Room 依赖） ----------

/** [planImportDedup] 的结果：待入库列表 + 被去重跳过的条数。 */
internal class ImportDedupPlan(val toInsert: List<ImportedCard>, val duplicates: Int)

/**
 * 按 front 去重（P2 中危的「查重」部分下沉为纯函数）：
 *  - 与库内已存在的 front 冲突 → 跳过；
 *  - 同一批内后出现的重复 front → 跳过（只保留第一条）。
 * 顺序保持输入顺序，便于稳定入库。原实现把这两步和 Room 写库揉在挂起函数里无法单测，抽出来锁死语义。
 */
internal fun planImportDedup(existingFronts: List<String>, cards: List<ImportedCard>): ImportDedupPlan {
    val seen = existingFronts.toHashSet()
    val toInsert = ArrayList<ImportedCard>()
    var duplicates = 0
    for (c in cards) {
        if (seen.add(c.front)) toInsert.add(c) else duplicates++
    }
    return ImportDedupPlan(toInsert, duplicates)
}

/**
 * 带上限的流拷贝（P2 高危：SAF `copyTo` 无字节上限可塞满 cache 分区）。
 * 拷到 [maxBytes] 即停并返回已拷数（==maxBytes 表示越界被截断，调用方据此判定超限拒绝），
 * 不继续写入、不 OOM、不塞满磁盘。
 */
private fun copyCapped(input: java.io.InputStream, out: OutputStream, maxBytes: Long): Long {
    val buf = ByteArray(8192)
    var total = 0L
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        if (total + n >= maxBytes) {
            val room = (maxBytes - total).toInt()
            if (room > 0) out.write(buf, 0, room)
            return maxBytes
        }
        out.write(buf, 0, n)
        total += n
    }
    return total
}