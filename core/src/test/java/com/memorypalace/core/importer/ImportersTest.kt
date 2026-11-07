package com.memorypalace.core.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ImportersTest {

    // ---------- CSV ----------

    @Test
    fun `csv 基础两行`() {
        val cards = CsvImporter.parse("serendipity,n.意外之喜\nephemeral,adj.转瞬即逝的")
        assertEquals(2, cards.size)
        assertEquals(ImportedCard("serendipity", "n.意外之喜"), cards[0])
        assertEquals(ImportedCard("ephemeral", "adj.转瞬即逝的"), cards[1])
    }

    @Test
    fun `csv 表头自动跳过`() {
        val cards = CsvImporter.parse("front,back\nabandon,v.放弃")
        assertEquals(listOf(ImportedCard("abandon", "v.放弃")), cards)
    }

    @Test
    fun `csv 引号字段含逗号与换行与双引号转义`() {
        val text = "\"say, speak\",\"v.说（含, 逗号）\"\n\"multi\nline\",\"a\"\"quoted\"\" back\""
        val cards = CsvImporter.parse(text)
        assertEquals(2, cards.size)
        assertEquals(ImportedCard("say, speak", "v.说（含, 逗号）"), cards[0])
        assertEquals(ImportedCard("multi\nline", "a\"quoted\" back"), cards[1])
    }

    @Test
    fun `csv 空正面行丢弃`() {
        val cards = CsvImporter.parse(",no front\nok,back\n  ,  ")
        assertEquals(listOf(ImportedCard("ok", "back")), cards)
    }

    // ---------- 纯文本 ----------

    @Test
    fun `txt tab 分隔`() {
        val cards = TextImporter.parse("abandon\tv.放弃\nparis\t法国首都")
        assertEquals(2, cards.size)
        assertEquals(ImportedCard("abandon", "v.放弃"), cards[0])
    }

    @Test
    fun `txt 多分隔符与注释空行`() {
        val text = """
            # 这是注释
            mnemonic :: n.助记术

            光合作用——植物把光能转成化学能
            单独一个词
        """.trimIndent()
        val cards = TextImporter.parse(text)
        assertEquals(3, cards.size)
        assertEquals(ImportedCard("mnemonic", "n.助记术"), cards[0])
        assertEquals(ImportedCard("光合作用", "植物把光能转成化学能"), cards[1])
        assertEquals(ImportedCard("单独一个词", ""), cards[2])
    }

    // ---------- APKG ----------

    private fun zipOf(entries: Map<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            entries.forEach { (name, bytes) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    @Test
    fun `apkg 解包取 collection，anki21 优先`() {
        val zip = zipOf(mapOf(
            "collection.anki2" to byteArrayOf(1, 2, 3),
            "collection.anki21" to byteArrayOf(9, 9),
            "media" to "{}".toByteArray(),
        ))
        val got = ApkgExtractor.extractCollection(ByteArrayInputStream(zip))
        assertTrue(got contentEquals byteArrayOf(9, 9))
    }

    @Test
    fun `apkg 只有 anki2 时也能取`() {
        val zip = zipOf(mapOf("collection.anki2" to byteArrayOf(1, 2, 3)))
        val got = ApkgExtractor.extractCollection(ByteArrayInputStream(zip))
        assertTrue(got contentEquals byteArrayOf(1, 2, 3))
    }

    @Test
    fun `apkg 无 collection 返回 null`() {
        val zip = zipOf(mapOf("media" to "{}".toByteArray()))
        assertNull(ApkgExtractor.extractCollection(ByteArrayInputStream(zip)))
    }

    @Test
    fun `anki flds 按 US 拆分并剥 HTML`() {
        val card = ApkgExtractor.splitFields("abandonv.<b>放弃</b><br/>例句 &amp; 用法extra")
        assertEquals(ImportedCard("abandon", "v.放弃\n例句 & 用法"), card)
    }

    @Test
    fun `anki flds 正面为空丢弃`() {
        assertNull(ApkgExtractor.splitFields("只有背面"))
    }
}
