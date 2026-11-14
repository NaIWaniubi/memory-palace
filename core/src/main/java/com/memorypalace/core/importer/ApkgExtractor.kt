package com.memorypalace.core.importer

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Anki .apkg 解包（M5）。apkg 本质是 zip，内含 SQLite 库（collection.anki2，
 * 新版为 collection.anki21）与 media 映射。本类只负责纯 JVM 的解包与字段拆分；
 * SQLite 读取在 app 侧用 android.database.sqlite 完成。
 */
object ApkgExtractor {

    /**
     * zip bomb 防线（P2 高危：apkg 解包无尺寸上限，100KB 声明 2GB 的坏包 readBytes
     * 直接 OOM 拖垮整个头显进程）。三个闸：
     *  - 单个 collection 条目解压后字节上限 [MAX_ENTRY_BYTES]
     *  - 全部已读字节总量上限 [MAX_TOTAL_BYTES]（两库都在包里时不翻倍到内存爆）
     *  - 条目数上限 [MAX_ENTRIES]（海量小条目枚举 DoS）
     * 超限一律抛 IOException，由调用方 ImportService 兜成"导入失败"日志，绝不静默、
     * 也绝不继续分配内存。声明解压后大小不可信（伪造），故以**实际读到的字节数**计数。
     */
    const val MAX_ENTRY_BYTES = 128L * 1024 * 1024 // 128 MiB：单个 collection 库
    const val MAX_TOTAL_BYTES = 256L * 1024 * 1024 // 256 MiB：解包总量
    const val MAX_ENTRIES = 4096

    /** 从 apkg zip 流中取出 collection 数据库字节（anki21 优先）。找不到返回 null。 */
    fun extractCollection(
        input: InputStream,
        maxEntryBytes: Long = MAX_ENTRY_BYTES,
        maxTotalBytes: Long = MAX_TOTAL_BYTES,
        maxEntries: Int = MAX_ENTRIES,
    ): ByteArray? {
        var anki2: ByteArray? = null
        var anki21: ByteArray? = null
        var total = 0L
        var entries = 0
        ZipInputStream(input.buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (++entries > maxEntries) throw IOException("apkg 条目数超上限 $maxEntries（疑似 zip bomb）")
                when (entry.name) {
                    // 精确名匹配 + 不落盘解压，无 zip-slip；按字节计数而非 readBytes()，
                    // 在真正撑爆内存前先失败。
                    "collection.anki21" -> anki21 = readEntry(zis, entry.name, maxEntryBytes).also { total += it.size }
                    "collection.anki2" -> anki2 = readEntry(zis, entry.name, maxEntryBytes).also { total += it.size }
                }
                if (total > maxTotalBytes) throw IOException("apkg 解压总量超上限 $maxTotalBytes 字节（疑似 zip bomb）")
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return anki21 ?: anki2
    }

    /** 带字节上限地把当前 zip 条目读进内存；越界即抛 IOException（不继续分配）。 */
    private fun readEntry(zis: ZipInputStream, name: String, maxBytes: Long): ByteArray {
        val buf = ByteArrayOutputStream()
        val tmp = ByteArray(8192)
        var n: Int
        while (zis.read(tmp).also { n = it } >= 0) {
            if (buf.size().toLong() + n > maxBytes) {
                throw IOException("apkg 条目 $name 解压后超上限 $maxBytes 字节（疑似 zip bomb）")
            }
            buf.write(tmp, 0, n)
        }
        return buf.toByteArray()
    }

    fun extractCollection(
        file: File,
        maxEntryBytes: Long = MAX_ENTRY_BYTES,
        maxTotalBytes: Long = MAX_TOTAL_BYTES,
        maxEntries: Int = MAX_ENTRIES,
    ): ByteArray? = file.inputStream().use {
        extractCollection(it, maxEntryBytes, maxTotalBytes, maxEntries)
    }

    /**
     * Anki notes.flds 用 \x1f（Unit Separator）拼接各字段。
     * 取前两个字段作为 正面/背面，并剥掉 Anki 字段里常见的 HTML 标签。
     */
    fun splitFields(flds: String): ImportedCard? {
        val parts = flds.split('')
        val front = stripHtml(parts.getOrElse(0) { "" }).trim()
        val back = stripHtml(parts.getOrElse(1) { "" }).trim()
        return if (front.isEmpty()) null else ImportedCard(front, back)
    }

    private val HTML_TAG = Regex("<[^>]+>")
    private val HTML_ENTITIES = mapOf("&nbsp;" to " ", "&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"")

    internal fun stripHtml(s: String): String {
        var out = s.replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        out = HTML_TAG.replace(out, "")
        HTML_ENTITIES.forEach { (k, v) -> out = out.replace(k, v) }
        return out
    }
}
