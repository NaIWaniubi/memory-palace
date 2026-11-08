package com.memorypalace.core.schedule

import com.memorypalace.core.model.Card
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SchedulerTest {

    private val t0 = 1_700_000_000_000L

    @Test
    fun `new card is due immediately`() {
        val c = Scheduler.newCard("a", "abandon", "v.放弃", t0)
        assertEquals(t0, c.dueAt)
        assertEquals(0, c.repetitions)
        assertEquals(Scheduler.INITIAL_EASINESS, c.easiness, 1e-9)
        assertEquals(listOf("a"), Scheduler.dueQueue(listOf(c), t0).map { it.id })
    }

    @Test
    fun `remembered intervals follow 1d, 6d, then easiness growth`() {
        var c = Scheduler.newCard("a", "f", "b", t0)
        c = Scheduler.review(c, Grade.REMEMBERED, t0)
        assertEquals(1, c.repetitions)
        assertEquals(1.0, c.intervalDays, 1e-9)
        assertEquals(t0 + Scheduler.DAY_MILLIS, c.dueAt)

        c = Scheduler.review(c, Grade.REMEMBERED, c.dueAt)
        assertEquals(2, c.repetitions)
        assertEquals(6.0, c.intervalDays, 1e-9)

        val t2 = c.dueAt
        c = Scheduler.review(c, Grade.REMEMBERED, t2)
        assertEquals(3, c.repetitions)
        assertEquals(15.0, c.intervalDays, 1e-9) // 6 * 2.5
        assertEquals(t2 + 15 * Scheduler.DAY_MILLIS, c.dueAt)
    }

    @Test
    fun `forgotten resets repetitions and re-learns in 10 minutes`() {
        var c = Scheduler.newCard("a", "f", "b", t0)
        c = Scheduler.review(c, Grade.REMEMBERED, t0)
        val due1 = c.dueAt
        c = Scheduler.review(c, Grade.FORGOTTEN, due1)
        assertEquals(0, c.repetitions)
        assertEquals(0.0, c.intervalDays, 1e-9)
        assertEquals(due1 + Scheduler.RELEARN_DELAY_MILLIS, c.dueAt)
        assertTrue("easiness should decrease after failure", c.easiness < Scheduler.INITIAL_EASINESS)
    }

    @Test
    fun `easiness never drops below floor`() {
        var c = Scheduler.newCard("a", "f", "b", t0)
        var now = t0
        repeat(10) {
            c = Scheduler.review(c, Grade.FORGOTTEN, now)
            now = c.dueAt
        }
        assertEquals(Scheduler.MIN_EASINESS, c.easiness, 1e-9)
    }

    @Test
    fun `due queue filters future cards and sorts overdue first`() {
        val future = Card("c", "f", "b", 2, 2.5, 6.0, t0 + Scheduler.DAY_MILLIS, t0, t0)
        val overdue = Card("b", "f", "b", 1, 2.5, 1.0, t0 - 2000, t0, t0)
        val justDue = Card("a", "f", "b", 1, 2.5, 1.0, t0 - 500, t0, t0)
        val queue = Scheduler.dueQueue(listOf(future, justDue, overdue), t0)
        assertEquals(listOf("b", "a"), queue.map { it.id })
    }

    @Test
    fun `empty queue stays empty`() {
        assertTrue(Scheduler.dueQueue(emptyList(), t0).isEmpty())
    }

    @Test
    fun `新卡立即进入到期队列`() {
        val c = Scheduler.newCard("new", "f", "b", t0)
        assertEquals(listOf("new"), Scheduler.dueQueue(listOf(c), t0).map { it.id })
    }

    @Test
    fun `记住后间隔按 easiness 增长`() {
        var c = Scheduler.newCard("a", "f", "b", t0)
        c = Scheduler.review(c, Grade.REMEMBERED, t0)
        c = Scheduler.review(c, Grade.REMEMBERED, c.dueAt)
        // 第三次：6 * 2.5 = 15 天
        c = Scheduler.review(c, Grade.REMEMBERED, c.dueAt)
        assertEquals(15.0, c.intervalDays, 1e-9)
        // 第四次：15 * 2.5 = 37.5 → 取整 38（roundToLong）
        c = Scheduler.review(c, Grade.REMEMBERED, c.dueAt)
        assertEquals(38.0, c.intervalDays, 1e-9)
    }

    @Test
    fun `遗忘后重新记住回到一天间隔`() {
        var c = Scheduler.newCard("a", "f", "b", t0)
        c = Scheduler.review(c, Grade.REMEMBERED, t0)
        c = Scheduler.review(c, Grade.REMEMBERED, c.dueAt)
        c = Scheduler.review(c, Grade.FORGOTTEN, c.dueAt)
        assertEquals(0, c.repetitions)
        c = Scheduler.review(c, Grade.REMEMBERED, c.dueAt)
        assertEquals(1, c.repetitions)
        assertEquals(1.0, c.intervalDays, 1e-9)
    }

    @Test
    fun `重学延迟把卡片排除出立即到期队列`() {
        var c = Scheduler.newCard("a", "f", "b", t0)
        c = Scheduler.review(c, Grade.FORGOTTEN, t0)
        // 10 分钟内不回到队列里
        assertTrue(Scheduler.dueQueue(listOf(c), t0 + Scheduler.RELEARN_DELAY_MILLIS - 1000).isEmpty())
        // 超过 10 分钟后回到队列
        assertEquals(1, Scheduler.dueQueue(listOf(c), t0 + Scheduler.RELEARN_DELAY_MILLIS + 1000).size)
    }

    @Test
    fun `easiness 记住时也会下降符合 SM2 公式`() {
        var c = Scheduler.newCard("a", "f", "b", t0)
        val before = c.easiness
        c = Scheduler.review(c, Grade.REMEMBERED, t0)
        // q=4：ef' = ef + (0.1 - (5-4)*(0.08 + (5-4)*0.02)) = ef + (0.1 - 0.1) = ef
        assertEquals(before, c.easiness, 1e-9)
    }

    @Test
    fun `连续记住间隔单调不减`() {
        var c = Scheduler.newCard("a", "f", "b", t0)
        var last = 0.0
        repeat(6) {
            c = Scheduler.review(c, Grade.REMEMBERED, c.dueAt)
            assertTrue("间隔应单调不减：${c.intervalDays} < $last", c.intervalDays >= last)
            last = c.intervalDays
        }
    }
}
