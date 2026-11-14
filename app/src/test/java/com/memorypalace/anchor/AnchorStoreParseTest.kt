package com.memorypalace.anchor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2 阻断项「一条坏记录静默丢全部锚点」的纯逻辑回归：
 * 逐条解析必须只丢坏的那条，好记录一条都不能少。
 */
class AnchorStoreParseTest {

    @Test
    fun `坏记录只丢自己 好记录全留`() {
        val (good, failed) = parseRecordsTolerantly(count = 5) { i ->
            if (i == 1 || i == 3) error("simulated JSONException on record #$i")
            "record-$i"
        }
        assertEquals(listOf("record-0", "record-2", "record-4"), good)
        assertEquals(listOf(1, 3), failed)
    }

    @Test
    fun `全部坏记录时好列表为空而不是把异常抛出去`() {
        val (good, failed) = parseRecordsTolerantly(count = 3) { _ -> error("all bad") }
        assertTrue(good.isEmpty())
        assertEquals(listOf(0, 1, 2), failed)
    }

    @Test
    fun `空表安全`() {
        val (good, failed) = parseRecordsTolerantly(count = 0) { error("never") }
        assertTrue(good.isEmpty())
        assertTrue(failed.isEmpty())
    }

    @Test
    fun `catch 住 Error 级异常（坏数据可导致 LinkageError 等）也不崩`() {
        val (good, failed) = parseRecordsTolerantly(count = 2) { i ->
            if (i == 0) throw OutOfMemoryError("simulated corrupt-driven error") else "ok"
        }
        assertEquals(listOf("ok"), good)
        assertEquals(listOf(0), failed)
    }
}

/**
 * P2 高危 verifyRestored：只有「锚点确已不存在」才降级 uuid=null，
 * 暂时未就绪（冷启动定位未收敛等）一律保留 uuid 待重试。
 */
class AnchorVerifyDecisionTest {

    @Test
    fun `仅 NOT_FOUND 码判降级 其余全保留`() {
        assertEquals(VerifyDecision.DOWNGRADE, decideVerifyError(-7))
        // 未就绪 / 服务错误 / 未知码：保守 KEEP（绝不落盘永久降级）
        assertEquals(VerifyDecision.KEEP, decideVerifyError(0))
        assertEquals(VerifyDecision.KEEP, decideVerifyError(1))
        assertEquals(VerifyDecision.KEEP, decideVerifyError(-1))
        assertEquals(VerifyDecision.KEEP, decideVerifyError(-8))
        assertEquals(VerifyDecision.KEEP, decideVerifyError(Int.MIN_VALUE))
    }
}

/** P2 中危 CLEAR：removeAnchor 失败的记录必须保留待重试，不得静默丢弃。 */
class AnchorClearRetainTest {

    @Test
    fun `无 uuid 的记录没有系统侧要清 不保留`() {
        assertFalse(shouldRetainAfterClear(uuid = null, removalSucceeded = false))
    }

    @Test
    fun `删除成功 不保留`() {
        assertFalse(shouldRetainAfterClear("uuid-1", removalSucceeded = true))
    }

    @Test
    fun `删除失败 保留待重试（防系统锚点泄漏）`() {
        assertTrue(shouldRetainAfterClear("uuid-1", removalSucceeded = false))
    }
}
