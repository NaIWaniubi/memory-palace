package com.memorypalace.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2 高危 Clock 污染：提醒落点必须走真实时钟、不受演示偏移影响，语义重启不漂移。
 * decideReminder 是抽出来的纯决策，锁死真实时间语义。
 */
class DecideReminderTest {

    private val day = 24L * 60 * 60 * 1000
    private val immediate = 5_000L

    @Test
    fun `有未来到期卡 取最近的未来真实时刻`() {
        val realNow = 1_000_000L
        val dueAts = listOf(realNow - day, realNow + 3 * day, realNow + day)
        val plan = decideReminder(dueAts, realNow, immediate)
        assertTrue(plan is ReminderPlan.Fire)
        plan as ReminderPlan.Fire
        assertEquals("scheduled", plan.mode)
        assertEquals(realNow + day, plan.fireAt) // 最近的未来，而非最远 / 而非逾期卡
    }

    @Test
    fun `全是逾期 用真实 now 加即时延迟 不会因偏移漂到未来`() {
        val realNow = 1_000_000L
        val dueAts = listOf(realNow - 5 * day, realNow - 1)
        val plan = decideReminder(dueAts, realNow, immediate) as ReminderPlan.Fire
        assertEquals("overdue-immediate", plan.mode)
        assertEquals(realNow + immediate, plan.fireAt)
    }

    @Test
    fun `一张卡都没有 取消提醒`() {
        assertTrue(decideReminder(emptyList(), 1_000_000L, immediate) is ReminderPlan.Cancel)
    }

    @Test
    fun `恰好此刻到期算逾期分支 不会立即触发未来闹钟错位`() {
        val realNow = 1_000_000L
        // dueAt == realNow：not > realNow，走逾期分支（即时延迟后响），避免 setExact(fireAt=now) 的边界
        val plan = decideReminder(listOf(realNow), realNow, immediate) as ReminderPlan.Fire
        assertEquals("overdue-immediate", plan.mode)
        assertEquals(realNow + immediate, plan.fireAt)
    }
}
