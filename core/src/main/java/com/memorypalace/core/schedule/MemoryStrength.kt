package com.memorypalace.core.schedule

import com.memorypalace.core.model.Card

/**
 * 记忆强度可视化（M13 下沉到 core）。
 *
 * 之前这段逻辑写在 app/ui/AnchorCard.kt 的私有函数里 —— 无法 JVM 单测，
 * 而它恰恰是"看得见的遗忘曲线"的核心：把调度状态翻译成视觉褪色程度。
 * 下沉到 core 后可以纯单测，app 层只负责把数值喂给 Compose。
 *
 * 褪色定义：逾期时长 / 当前间隔 = 遗忘程度（0 = 刚到期，1 = 彻底褪色）。
 * 间隔为 0 的新卡用 1 天兜底，避免除零导致"新卡一创建就是深灰"。
 */
object MemoryStrength {

    const val DAY_MILLIS = Scheduler.DAY_MILLIS

    /** 新卡/重学卡的兜底间隔（天）。 */
    private const val FALLBACK_INTERVAL_DAYS = 1.0

    /**
     * 遗忘程度 0..1。
     * @param card 卡片；null（卡片数据还没到）视为 0（清晰显示，不闪灰）
     */
    fun fade(card: Card?, now: Long): Float {
        if (card == null) return 0f
        val overdue = now - card.dueAt
        if (overdue <= 0) return 0f
        val intervalMillis = intervalMillis(card)
        return (overdue.toDouble() / intervalMillis).coerceIn(0.0, 1.0).toFloat()
    }

    /** 当前间隔的毫秒数；新卡（间隔 0）按 1 天兜底。 */
    fun intervalMillis(card: Card): Double {
        val days = if (card.intervalDays < FALLBACK_INTERVAL_DAYS) FALLBACK_INTERVAL_DAYS else card.intervalDays
        return days * DAY_MILLIS
    }

    /** 逾期天数（负数表示还没到期）。 */
    fun overdueDays(card: Card, now: Long): Double =
        (now - card.dueAt).toDouble() / DAY_MILLIS

    /**
     * 视觉档位：0 = 清晰，1 = 褪色中档，2 = 褪色深档。
     * 与 ui/Theme.kt 的 FadedMid / FadedDeep 取色阈值保持一致。
     */
    enum class Tier { CLEAR, MID, DEEP }

    fun tier(fade: Float): Tier = when {
        fade > 0.66f -> Tier.DEEP
        fade > 0.33f -> Tier.MID
        else -> Tier.CLEAR
    }

    /**
     * 卡片下方的文字标签。三档说法对应三种心理状态：
     * 未到期=安全感，逾期中=紧迫，逾期满一间隔=该复习了。
     */
    fun label(card: Card, now: Long, fade: Float): String {
        val days = overdueDays(card, now)
        return when {
            days <= 0 -> {
                val left = -days
                val whenText = if (left < 1) "今天" else "${left.toInt()}天后"
                "记得牢 · ${whenText}到期 · 间隔${card.intervalDays.toInt()}d"
            }
            fade >= 1f -> "已逾期${days.toInt()}天 · 快忘了"
            else -> "已逾期${if (days < 1) "不到1" else days.toInt().toString()}天 · 正在遗忘"
        }
    }

    /**
     * 不透明度：清晰 1.0，彻底褪色 0.3（1 - 0.7 * fade）。
     * 下限 0.3 保证卡片不会淡到看不见 —— 褪色是提示，不是隐藏。
     */
    fun alpha(fade: Float): Float = (1f - 0.7f * fade).coerceIn(0.3f, 1f)
}
