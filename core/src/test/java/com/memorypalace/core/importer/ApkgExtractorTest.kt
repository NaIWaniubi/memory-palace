package com.memorypalace.core.importer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * P2 高危「apkg zip bomb 无上限」修复的回归测试：
 * 超限必须安全失败（IOException），而不是继续 readBytes() 分配内存。
 */
class ApkgExtractorTest {

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            entries.forEach { (name, data) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(data)
                zos.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    @Test
    fun `正常提取 anki21 优先于 anki2`() {
        val zip = zipOf(
            "collection.anki2" to byteArrayOf(1, 2, 3),
            "collection.anki21" to byteArrayOf(9, 9),
            "media/ignored.bin" to ByteArray(1024),
        )
        val out = ApkgExtractor.extractCollection(ByteArrayInputStream(zip))
        assertArrayEquals(byteArrayOf(9, 9), out)
    }

    @Test
    fun `只有 anki2 时也能提取`() {
        val zip = zipOf("collection.anki2" to byteArrayOf(7))
        assertArrayEquals(byteArrayOf(7), ApkgExtractor.extractCollection(ByteArrayInputStream(zip)))
    }

    @Test
    fun `没有 collection 返回 null`() {
        val zip = zipOf("manifest.json" to "{}".toByteArray())
        assertNull(ApkgExtractor.extractCollection(ByteArrayInputStream(zip)))
    }

    @Test
    fun `zip bomb 单条目超解压上限 抛 IOException 而非全量进内存`() {
        // 经典 zip bomb：小压缩包，解压后远大于声明体积。注入小上限模拟。
        val bomb = zipOf("collection.anki2" to ByteArray(64 * 1024) { 7 })
        try {
            ApkgExtractor.extractCollection(ByteArrayInputStream(bomb), maxEntryBytes = 1024)
            throw AssertionError("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().contains("超上限"))
        }
    }

    @Test
    fun `两库并存的解压总量超上限 抛 IOException`() {
        val zip = zipOf(
            "collection.anki2" to ByteArray(8 * 1024),
            "collection.anki21" to ByteArray(8 * 1024),
        )
        try {
            ApkgExtractor.extractCollection(
                ByteArrayInputStream(zip),
                maxEntryBytes = 64L * 1024, // 单条不超
                maxTotalBytes = 10L * 1024, // 总量必超
            )
            throw AssertionError("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().contains("总量超上限"))
        }
    }

    @Test
    fun `条目数超上限 抛 IOException 防枚举 DoS`() {
        val entries = (0 until 8).map { "junk_$it" to ByteArray(1) } + ("collection.anki2" to ByteArray(1))
        val zip = zipOf(*entries.toTypedArray())
        try {
            ApkgExtractor.extractCollection(ByteArrayInputStream(zip), maxEntries = 4)
            throw AssertionError("expected IOException")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().contains("条目数超上限"))
        }
    }

    @Test
    fun `无关大条目不解压不触发上限`() {
        // media 条目按精确名匹配根本不会被读取解压（防 zip bomb 的一部分），大媒体无害。
        val zip = zipOf("media/big.bin" to ByteArray(256 * 1024))
        assertNull(ApkgExtractor.extractCollection(ByteArrayInputStream(zip), maxEntryBytes = 128))
    }

    @Test
    fun `splitFields 按分隔符取前两字段并剥 html`() {
        val card = ApkgExtractor.splitFields("front<b>x</b>" + '' + "back<br>line")
        assertEquals("frontx", card?.front)
        assertEquals("back\nline", card?.back)
    }
}
