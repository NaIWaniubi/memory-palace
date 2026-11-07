package com.memorypalace.core.importer

import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Anki .apkg 解包（M5）。apkg 本质是 zip，内含 SQLite 库（collection.anki2，
 * 新版为 collection.anki21）与 media 映射。本类只负责纯 JVM 的解包与字段拆分；
 * SQLite 读取在 app 侧用 android.database.sqlite 完成。
 */
object ApkgExtractor {

    /** 从 apkg zip 流中取出 collection 数据库字节（anki21 优先）。找不到返回 null。 */
    fun extractCollection(input: InputStream): ByteArray? {
        var anki2: ByteArray? = null
        var anki21: ByteArray? = null
        ZipInputStream(input.buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                when (entry.name) {
                    "collection.anki21" -> anki21 = zis.readBytes()
                    "collection.anki2" -> anki2 = zis.readBytes()
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return anki21 ?: anki2
    }

    fun extractCollection(file: File): ByteArray? = file.inputStream().use { extractCollection(it) }

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
