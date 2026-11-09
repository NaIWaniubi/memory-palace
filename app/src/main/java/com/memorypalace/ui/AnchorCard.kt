package com.memorypalace.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.memorypalace.anchor.AnchorRecord
import com.memorypalace.core.model.Card
import com.memorypalace.core.schedule.MemoryStrength
import com.memorypalace.data.Clock
import com.memorypalace.data.DataGraph
import com.memorypalace.review.ReviewSession
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.Text

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
    val demo by DemoMode.enabled.collectAsState()
    Clock.tick.collectAsState()  // 时间机器快进时触发重算

    val card = cards.firstOrNull { it.id == rec.key }
    val now = Clock.now()

    // 记忆强度 → 外观：逾期时长 / 间隔 = 遗忘程度（0=刚到期，1=彻底褪色）
    // 计算逻辑在 core/MemoryStrength（M13 下沉，纯 JVM 可单测），这里只做映射
    val fade = MemoryStrength.fade(card, now)
    val baseAlpha = MemoryStrength.alpha(fade)

    val isCurrent = session?.current?.id == rec.key
    val inReview = session != null
    val alpha = when {
        isCurrent -> 1f
        inReview -> baseAlpha * 0.3f  // 复习中非当前卡压暗
        else -> baseAlpha
    }

    val titleColor = when {
        isCurrent -> MpColors.Info
        MemoryStrength.tier(fade) == MemoryStrength.Tier.DEEP -> MpColors.FadedDeep
        MemoryStrength.tier(fade) == MemoryStrength.Tier.MID -> MpColors.FadedMid
        else -> Color.White
    }

    Column(
        modifier = Modifier
            .alpha(alpha)
            .clip(RoundedCornerShape(MpShape.Block))
            // 深色底 + 单色描边：卡片是贴在实物上的"物件"，需要自己的边界，
            // 否则在浅色墙面上就是一坨看不见的玻璃
            .background(if (isCurrent) MpColors.Info.copy(alpha = 0.18f) else MpColors.SurfaceDark.copy(alpha = 0.88f))
            .border(
                width = 1.dp,
                color = if (isCurrent) MpColors.Info else MpColors.TextSecondary.copy(alpha = 0.35f),
                shape = RoundedCornerShape(MpShape.Block),
            )
            .padding(MpSpace.CardAnchor),
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
                MemoryStrength.label(card, now, fade),
                fontSize = MpType.Caption,
                color = if (fade > 0f) MpColors.Warning else MpColors.Success,
            )
        }
        // M16：落点语义名是产品功能，不是调试信息 —— 演示模式下也要显示。
        // 「钉在桌面上」这一行就是记忆宫殿和"随便放个窗口"的区别所在。
        rec.anchorLabel?.let { label ->
            Text(
                "📍 钉在${com.memorypalace.spatial.PlaneSemantics.anchorPhrase(label)}",
                fontSize = MpType.Caption,
                color = MpColors.Accent,
            )
        }
        if (!demo) {
            Text(
                if (rec.uuid != null) "锚点 ${rec.uuid.take(8)}…" else "未锚定（回退位姿）",
                fontSize = MpType.Caption2,
                color = MpColors.TextSecondary,
            )
        }
        // 走到卡片前的真实复习入口（真机射线可点；模拟器无空间交互，记为真机待验）
        if (card != null && session == null) {
            Button(onClick = { ReviewSession.startSingle(card.id) }) {
                Text("复习这张", fontSize = MpType.BodySmall)
            }
        }
    }
}
