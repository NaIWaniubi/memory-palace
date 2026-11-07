package com.memorypalace.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.memorypalace.anchor.AnchorRecord
import com.memorypalace.core.model.Card
import com.memorypalace.core.schedule.Scheduler
import com.memorypalace.data.Clock
import com.memorypalace.data.DataGraph
import com.memorypalace.review.ReviewSession
import com.pico.spatial.ui.design.Text
import kotlin.math.max

/**
 * 钉在空间锚点上的知识卡。
 *
 * M4：外观随记忆强度变化 —— 未到期=清晰；逾期越久越褪色（透明度下降、文字变灰），
 * 这就是"看得见的遗忘曲线"。复习模式下当前卡高亮，其余压暗。
 * AnchorRecord.key == Card.id，卡片调度数据从 Room 实时取。
 */
@Composable
fun AnchorCard(rec: AnchorRecord) {
    val cards by DataGraph.repository.cards.collectAsState(initial = emptyList())
    val session by ReviewSession.state.collectAsState()
    Clock.tick.collectAsState()  // 时间机器快进时触发重算

    val card = cards.firstOrNull { it.id == rec.key }
    val now = Clock.now()

    // 记忆强度 → 外观：逾期时长 / 间隔 = 遗忘程度（0=刚到期，1=彻底褪色）
    val fade = memoryFade(card, now)
    val baseAlpha = 1f - 0.7f * fade

    val isCurrent = session?.current?.id == rec.key
    val inReview = session != null
    val alpha = when {
        isCurrent -> 1f
        inReview -> baseAlpha * 0.3f  // 复习中非当前卡压暗
        else -> baseAlpha
    }

    val titleColor = when {
        isCurrent -> MpColors.Info
        fade > 0.66f -> MpColors.FadedDeep
        fade > 0.33f -> MpColors.FadedMid
        else -> Color.White
    }

    Column(
        modifier = Modifier.alpha(alpha).padding(MpSpace.CardAnchor),
        verticalArrangement = Arrangement.spacedBy(MpSpace.Sm),
    ) {
        Text(
            (if (isCurrent) "▶ " else "") + (card?.front ?: rec.title),
            fontSize = MpType.CardFront,
            color = titleColor,
        )
        if (card != null) {
            Text(card.back, fontSize = MpType.Body, color = titleColor.copy(alpha = 0.85f))
            Text(
                memoryLabel(card, now, fade),
                fontSize = MpType.Caption,
                color = if (fade > 0f) MpColors.Warning else MpColors.Success,
            )
        }
        Text(
            if (rec.uuid != null) "锚点 ${rec.uuid.take(8)}…" else "未锚定（回退位姿）",
            fontSize = MpType.Caption2,
            color = MpColors.TextSecondary,
        )
    }
}

/** 遗忘程度 0..1：未到期=0；逾期满一个间隔=1（彻底褪色）。 */
private fun memoryFade(card: Card?, now: Long): Float {
    if (card == null) return 0f
    val overdue = now - card.dueAt
    if (overdue <= 0) return 0f
    val intervalMillis = max(card.intervalDays, 1.0) * Scheduler.DAY_MILLIS
    return (overdue / intervalMillis).coerceIn(0.0, 1.0).toFloat()
}

private fun memoryLabel(card: Card, now: Long, fade: Float): String {
    val overdueDays = (now - card.dueAt).toDouble() / Scheduler.DAY_MILLIS
    return when {
        overdueDays <= 0 -> {
            val left = -overdueDays
            "记得牢 · ${if (left < 1) "今天" else "${left.toInt()}天后"}到期 · 间隔${card.intervalDays.toInt()}d"
        }
        fade >= 1f -> "已逾期${overdueDays.toInt()}天 · 快忘了"
        else -> "已逾期${if (overdueDays < 1) "不到1" else overdueDays.toInt().toString()}天 · 正在遗忘"
    }
}
