package com.memorypalace.data

import com.memorypalace.core.importer.ImportedCard
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * P2 中危「导入查重读-清非原子」——把去重决策抽成纯函数 [planImportDedup]，
 * 与 DataGraph/Room 解耦后可 JVM 单测。锁死「库内已存在」与「批内自重复」两类去重。
 */
class PlanImportDedupTest {

    private fun card(f: String, b: String = "x") = ImportedCard(f, b)

    @Test
    fun `库内已存在的 front 被跳过`() {
        val plan = planImportDedup(listOf("apple"), listOf(card("apple"), card("pear")))
        assertEquals(listOf("pear"), plan.toInsert.map { it.front })
        assertEquals(1, plan.duplicates)
    }

    @Test
    fun `同批内后出现的重复只保留第一条`() {
        val plan = planImportDedup(emptyList(), listOf(card("a"), card("a"), card("b"), card("a")))
        assertEquals(listOf("a", "b"), plan.toInsert.map { it.front })
        assertEquals(2, plan.duplicates)
    }

    @Test
    fun `入库顺序保持输入顺序`() {
        val plan = planImportDedup(emptyList(), listOf(card("z"), card("m"), card("a")))
        assertEquals(listOf("z", "m", "a"), plan.toInsert.map { it.front })
        assertEquals(0, plan.duplicates)
    }

    @Test
    fun `全新增时 parsed==imported dup=0`() {
        val cards = listOf(card("1"), card("2"), card("3"))
        val plan = planImportDedup(emptyList(), cards)
        assertEquals(3, plan.toInsert.size)
        assertEquals(0, plan.duplicates)
    }
}
