package com.memorypalace.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.memorypalace.core.schedule.Grade
import com.memorypalace.probe.DualStateProbe
import com.memorypalace.review.ReviewSession
import com.pico.spatial.ui.design.Button

/**
 * 随身复习卡（M4：复习流主界面）。
 * 未在复习 → 入口与到期数；复习中 → 当前卡正面 → 翻面 → 记得/忘了。
 */
@Composable
fun ReviewCard() {
    val instanceId = remember { DualStateProbe.claim("review_card") }
    LifecycleLogger("review_card", instanceId)

    val session by ReviewSession.state.collectAsState()
    val demo by DemoMode.enabled.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize().padding(MpSpace.PanelCard),
        verticalArrangement = Arrangement.spacedBy(MpSpace.Lg),
    ) {
        MpText("随身复习卡", fontSize = MpType.Title, fontWeight = MpType.Bold)

        val s = session
        if (s == null) {
            MpText("未在复习中", fontSize = MpType.BodyLarge, color = MpColors.TextSecondary)
            Button(onClick = { ReviewSession.start() }) {
                MpText("开始复习（取今日到期队列）", fontSize = MpType.Body)
            }
            if (!demo) {
                MpText(
                    "广播：review.START / REVEAL / GRADE --es grade remember|forgot / EXIT",
                    fontSize = MpType.Caption2,
                    color = MpColors.TextSecondary,
                )
            }
        } else {
            val card = s.current
            MpText(
                "进度 ${s.index + 1} / ${s.total}",
                fontSize = MpType.Body,
                color = MpColors.Info,
            )
            if (card != null) {
                MpText(card.front, fontSize = MpType.Display, fontWeight = MpType.Bold)
                if (s.revealed) {
                    MpText(card.back, fontSize = MpType.CardFront, color = MpColors.Accent)
                    if (!demo) {
                        MpText(
                            "reps=${card.repetitions} ef=${"%.2f".format(card.easiness)} 间隔=${card.intervalDays}d",
                            fontSize = MpType.Caption,
                            color = MpColors.TextSecondary,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(MpSpace.Md)) {
                        Button(onClick = { ReviewSession.grade(Grade.REMEMBERED) }) {
                            MpText("记得", fontSize = MpType.BodyLarge)
                        }
                        Button(onClick = { ReviewSession.grade(Grade.FORGOTTEN) }) {
                            MpText("忘了", fontSize = MpType.BodyLarge)
                        }
                    }
                } else {
                    MpText("……", fontSize = MpType.CardFront, color = MpColors.TextSecondary)
                    Button(onClick = { ReviewSession.reveal() }) {
                        MpText("翻面", fontSize = MpType.BodyLarge)
                    }
                }
            }
            Button(onClick = { ReviewSession.exit() }) {
                MpText("退出复习", fontSize = MpType.BodySmall)
            }
        }

        if (!demo) {
            EventLogView(maxLines = 5)
        }
    }
}
