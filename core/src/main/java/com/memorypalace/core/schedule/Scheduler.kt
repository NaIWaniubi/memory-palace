package com.memorypalace.core.schedule

import com.memorypalace.core.model.Card
import kotlin.math.roundToLong

/** 自评档位：映射 SM-2 quality（忘=1，记=4）。 */
enum class Grade(val quality: Int) { FORGOTTEN(1), REMEMBERED(4) }

/**
 * SM-2 变体调度器。纯函数、时间由调用方注入 —— 便于 JVM 单测，
 * 也为 M6 时间机器（改 Clock 偏移即可批量快进）留口。
 *
 * 规则：
 * - 新卡立即到期（dueAt = 创建时刻）
 * - 遗忘：repetitions 清零，10 分钟后重学；easiness 按 SM-2 公式下调，下限 1.3
 * - 记住：间隔 1 天 → 6 天 → 之后按 easiness 倍增（至少 1 天）
 */
object Scheduler {

    const val MIN_EASINESS = 1.3
    const val INITIAL_EASINESS = 2.5
    const val DAY_MILLIS = 24L * 60 * 60 * 1000
    const val RELEARN_DELAY_MILLIS = 10L * 60 * 1000

    fun newCard(id: String, front: String, back: String, now: Long): Card = Card(
        id = id,
        front = front,
        back = back,
        repetitions = 0,
        easiness = INITIAL_EASINESS,
        intervalDays = 0.0,
        dueAt = now,
        lastReviewedAt = null,
        createdAt = now,
    )

    fun review(card: Card, grade: Grade, now: Long): Card {
        val q = grade.quality
        val easiness = (card.easiness + (0.1 - (5 - q) * (0.08 + (5 - q) * 0.02)))
            .coerceAtLeast(MIN_EASINESS)
        return if (grade == Grade.FORGOTTEN) {
            card.copy(
                repetitions = 0,
                easiness = easiness,
                intervalDays = 0.0,
                dueAt = now + RELEARN_DELAY_MILLIS,
                lastReviewedAt = now,
            )
        } else {
            val reps = card.repetitions + 1
            val days = when (reps) {
                1 -> 1.0
                2 -> 6.0
                else -> (card.intervalDays * easiness).roundToLong().toDouble().coerceAtLeast(1.0)
            }
            card.copy(
                repetitions = reps,
                easiness = easiness,
                intervalDays = days,
                dueAt = now + (days * DAY_MILLIS).toLong(),
                lastReviewedAt = now,
            )
        }
    }

    /** 当日到期队列：dueAt <= now，按到期时间升序（最逾期的排最前）。 */
    fun dueQueue(cards: List<Card>, now: Long): List<Card> =
        cards.filter { it.dueAt <= now }.sortedBy { it.dueAt }
}
