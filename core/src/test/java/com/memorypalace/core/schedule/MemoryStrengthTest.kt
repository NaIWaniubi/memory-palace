package com.memorypalace.core.schedule

import com.memorypalace.core.model.Card
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryStrengthTest {

    private val t0 = 1_700_000_000_000L
    private val day = Scheduler.DAY_MILLIS

    private fun card(
        id: String = "a",
        dueAt: Long = t0,
        intervalDays: Double = 6.0,
    ) = Card(id, "front", "back", 2, 2.5, intervalDays, dueAt, t0, t0)

    // ---------- fade ----------

    @Test
    fun `未到期卡片不褪色`() {
        assertEquals(0f, MemoryStrength.fade(card(dueAt = t0 + day), t0), 1e-6f)
    }

    @Test
    fun `刚到期瞬间仍不褪色`() {
        assertEquals(0f, MemoryStrength.fade(card(dueAt = t0), t0), 1e-6f)
    }

    @Test
    fun `逾期半程褪色到一半`() {
        // 间隔 6 天，逾期 3 天 → fade = 0.5
        val c = card(dueAt = t0, intervalDays = 6.0)
        assertEquals(0.5f, MemoryStrength.fade(c, t0 + 3 * day), 1e-5f)
    }

    @Test
    fun `逾期满一间隔完全褪色`() {
        val c = card(dueAt = t0, intervalDays = 6.0)
        assertEquals(1f, MemoryStrength.fade(c, t0 + 6 * day), 1e-6f)
    }

    @Test
    fun `逾期超过一间隔不会超过 1`() {
        val c = card(dueAt = t0, intervalDays = 6.0)
        assertEquals(1f, MemoryStrength.fade(c, t0 + 100 * day), 1e-6f)
    }

    @Test
    fun `null 卡片视为清晰不闪灰`() {
        assertEquals(0f, MemoryStrength.fade(null, t0), 1e-6f)
    }

    @Test
    fun `新卡间隔为 0 时用一天兜底不除零`() {
        // 新卡刚建（间隔 0、立即到期）：逾期 0.5 天应是 0.5 而不是 Infinity
        val c = card(dueAt = t0, intervalDays = 0.0)
        assertEquals(0.5f, MemoryStrength.fade(c, t0 + day / 2), 1e-5f)
    }

    // ---------- tier ----------

    @Test
    fun `褪色档位阈值 033 与 066`() {
        assertEquals(MemoryStrength.Tier.CLEAR, MemoryStrength.tier(0f))
        assertEquals(MemoryStrength.Tier.CLEAR, MemoryStrength.tier(0.33f))
        assertEquals(MemoryStrength.Tier.MID, MemoryStrength.tier(0.34f))
        assertEquals(MemoryStrength.Tier.MID, MemoryStrength.tier(0.66f))
        assertEquals(MemoryStrength.Tier.DEEP, MemoryStrength.tier(0.67f))
        assertEquals(MemoryStrength.Tier.DEEP, MemoryStrength.tier(1f))
    }

    // ---------- alpha ----------

    @Test
    fun `透明度随褪色下降但不低于 03`() {
        assertEquals(1f, MemoryStrength.alpha(0f), 1e-6f)
        assertEquals(0.65f, MemoryStrength.alpha(0.5f), 1e-6f)
        assertEquals(0.3f, MemoryStrength.alpha(1f), 1e-6f)
    }

    @Test
    fun `透明度不会低于下限 03`() {
        assertEquals(0.3f, MemoryStrength.alpha(2f), 1e-6f)
    }

    // ---------- label ----------

    @Test
    fun `未到期标签给出剩余天数`() {
        val c = card(dueAt = t0 + 3 * day, intervalDays = 6.0)
        assertEquals("记得牢 · 3天后到期 · 间隔6d", MemoryStrength.label(c, t0, 0f))
    }

    @Test
    fun `今天到期的卡片标签是今天`() {
        val c = card(dueAt = t0 + day / 2, intervalDays = 6.0)
        assertEquals("记得牢 · 今天到期 · 间隔6d", MemoryStrength.label(c, t0, 0f))
    }

    @Test
    fun `逾期未满一间隔显示正在遗忘`() {
        val c = card(dueAt = t0, intervalDays = 6.0)
        assertEquals("已逾期2天 · 正在遗忘", MemoryStrength.label(c, t0 + 2 * day, 0.33f))
    }

    @Test
    fun `逾期不足一天显示不到1天`() {
        val c = card(dueAt = t0, intervalDays = 6.0)
        assertEquals("已逾期不到1天 · 正在遗忘", MemoryStrength.label(c, t0 + day / 2, 0.08f))
    }

    @Test
    fun `逾期满一间隔显示快忘了`() {
        val c = card(dueAt = t0, intervalDays = 6.0)
        assertEquals("已逾期6天 · 快忘了", MemoryStrength.label(c, t0 + 6 * day, 1f))
    }

    // ---------- interval / overdueDays ----------

    @Test
    fun `间隔毫秒换算`() {
        assertEquals(6.0 * day, MemoryStrength.intervalMillis(card(intervalDays = 6.0)), 1e-6)
    }

    @Test
    fun `新卡间隔兜底为一天`() {
        assertEquals(1.0 * day, MemoryStrength.intervalMillis(card(intervalDays = 0.0)), 1e-6)
    }

    @Test
    fun `逾期天数为负表示尚未到期`() {
        val c = card(dueAt = t0 + 2 * day)
        assertEquals(-2.0, MemoryStrength.overdueDays(c, t0), 1e-9)
    }

    @Test
    fun `时间机器快进后褪色随之加深`() {
        // M6 时间机器的核心语义：快进 → 逾期天数涨 → 褪色加深
        val c = card(dueAt = t0, intervalDays = 6.0)
        val before = MemoryStrength.fade(c, t0)
        val after = MemoryStrength.fade(c, t0 + 3 * day)
        assertTrue("快进后褪色应加深", after > before)
        assertEquals(0.5f, after, 1e-5f)
    }
}
