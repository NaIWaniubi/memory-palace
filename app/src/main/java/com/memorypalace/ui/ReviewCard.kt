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

    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        MpText("随身复习卡", fontSize = 26.sp, fontWeight = FontWeight(700))

        val s = session
        if (s == null) {
            MpText("未在复习中", fontSize = 18.sp, color = Color(0xFF9AA3B2))
            Button(onClick = { ReviewSession.start() }) {
                MpText("开始复习（取今日到期队列）", fontSize = 16.sp)
            }
            MpText(
                "广播：review.START / REVEAL / GRADE --es grade remember|forgot / EXIT",
                fontSize = 12.sp,
                color = Color(0xFF9AA3B2),
            )
        } else {
            val card = s.current
            MpText(
                "进度 ${s.index + 1} / ${s.total}",
                fontSize = 16.sp,
                color = Color(0xFF7CD4FD),
            )
            if (card != null) {
                MpText(card.front, fontSize = 34.sp, fontWeight = FontWeight(700))
                if (s.revealed) {
                    MpText(card.back, fontSize = 24.sp, color = Color(0xFFFFC107))
                    MpText(
                        "reps=${card.repetitions} ef=${"%.2f".format(card.easiness)} 间隔=${card.intervalDays}d",
                        fontSize = 13.sp,
                        color = Color(0xFF9AA3B2),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { ReviewSession.grade(Grade.REMEMBERED) }) {
                            MpText("记得", fontSize = 18.sp)
                        }
                        Button(onClick = { ReviewSession.grade(Grade.FORGOTTEN) }) {
                            MpText("忘了", fontSize = 18.sp)
                        }
                    }
                } else {
                    MpText("……", fontSize = 24.sp, color = Color(0xFF9AA3B2))
                    Button(onClick = { ReviewSession.reveal() }) {
                        MpText("翻面", fontSize = 18.sp)
                    }
                }
            }
            Button(onClick = { ReviewSession.exit() }) {
                MpText("退出复习", fontSize = 14.sp)
            }
        }

        EventLogView(maxLines = 5)
    }
}
