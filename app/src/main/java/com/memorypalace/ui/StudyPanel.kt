package com.memorypalace.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.memorypalace.data.Clock
import com.memorypalace.data.DataGraph
import com.memorypalace.data.ImportPickerActivity
import com.memorypalace.probe.DualStateController
import com.memorypalace.probe.DualStateProbe
import com.memorypalace.review.ReviewSession
import com.pico.spatial.ui.design.Button

/** 书房面板（主窗口，Shared Space 常驻 2D 界面）。 */
@Composable
fun StudyPanel() {
    val instanceId = remember { DualStateProbe.claim("study_panel") }
    LifecycleLogger("study_panel", instanceId)
    val context = LocalContext.current

    Column(
        modifier = Modifier.fillMaxSize().padding(36.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        MpText("记忆宫殿 · 书房面板", fontSize = 32.sp, fontWeight = FontWeight(700))
        MpText("实例号 #$instanceId（切换双态后变化=窗口被销毁重建）", fontSize = 18.sp, color = Color(0xFFFFC107))

        StateCounter("study_panel", instanceId)

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { DualStateController.requestOpenStage(context) }) {
                MpText("进入房间层 (Full Space)", fontSize = 16.sp)
            }
            Button(onClick = { DualStateController.requestCloseStage() }) {
                MpText("返回共享空间 (Shared)", fontSize = 16.sp)
            }
            Button(onClick = { DualStateController.toggleReviewCard(context) }) {
                MpText("开关随身卡窗口", fontSize = 16.sp)
            }
            Button(onClick = {
                context.startActivity(android.content.Intent(context, ImportPickerActivity::class.java))
            }) {
                MpText("导入牌组", fontSize = 16.sp)
            }
        }

        MpText(
            "导入（M5）：支持 CSV / 纯文本 / Anki .apkg，离线解析。无头：adb push 后 " +
                "am broadcast -a com.memorypalace.card.IMPORT --es path /sdcard/…",
            fontSize = 13.sp,
            color = Color(0xFF9AA3B2),
        )

        MpText(
            "无头驱动（模拟器点不了空间按钮时用）：adb shell am broadcast -a com.memorypalace.dualstate.OPEN_STAGE / CLOSE_STAGE / TOGGLE_REVIEW_CARD",
            fontSize = 13.sp,
            color = Color(0xFF9AA3B2),
        )

        TimeMachineView()

        DueQueueView()

        EventLogView(maxLines = 7)
        Spacer(Modifier.height(4.dp))
    }
}

/** 时间机器（M6）：一键快进，看卡片集体褪色 + 到期队列堆积。 */
@Composable
fun TimeMachineView() {
    val tick by Clock.tick.collectAsState()
    val offset = remember(tick) { Clock.offsetDays() }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MpText(
                "时间机器：当前偏移 ${if (offset >= 0) "+" else ""}${offset} 天",
                fontSize = 20.sp,
                fontWeight = FontWeight(700),
                color = if (offset == 0L) Color(0xFF9AA3B2) else Color(0xFFFF79C6),
            )
            Button(onClick = { Clock.addDays(1) }) {
                MpText("+1 天", fontSize = 14.sp)
            }
            Button(onClick = { Clock.addDays(7) }) {
                MpText("+7 天", fontSize = 14.sp)
            }
            Button(onClick = { Clock.setOffsetDays(0) }) {
                MpText("复位", fontSize = 14.sp)
            }
        }
        MpText(
            "快进到未来：到期队列立即重算堆积，房间层锚定卡片按记忆强度褪色。无头：am broadcast -a com.memorypalace.clock.SET_OFFSET --el days 7",
            fontSize = 13.sp,
            color = Color(0xFF9AA3B2),
        )
    }
}

/** 当日到期队列（M3）：Room 全量卡片 → Scheduler.dueQueue(Clock.now())。 */
@Composable
fun DueQueueView() {
    val cards by DataGraph.repository.cards.collectAsState(initial = emptyList())
    val tick by Clock.tick.collectAsState()  // 时间机器快进后到期队列要重算
    val due = remember(cards, tick) { DataGraph.repository.dueQueue(cards) }
    val session by ReviewSession.state.collectAsState()

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MpText(
                "今日到期队列：${due.size} 张 / 共 ${cards.size} 张",
                fontSize = 20.sp,
                fontWeight = FontWeight(700),
                color = if (due.isEmpty()) Color(0xFF8BE9A8) else Color(0xFFFFC107),
            )
            if (session == null) {
                Button(onClick = { ReviewSession.start() }) {
                    MpText("开始复习", fontSize = 14.sp)
                }
            } else {
                MpText("复习中 ${session!!.index + 1}/${session!!.total}", fontSize = 16.sp, color = Color(0xFF7CD4FD))
            }
        }
        due.take(6).forEach { c ->
            val intervalText = if (c.intervalDays < 1.0) "新卡/重学" else "${c.intervalDays.toInt()}d"
            MpText(
                "· ${c.front} — ${c.back}（reps=${c.repetitions} ef=${"%.2f".format(c.easiness)} 间隔=$intervalText id=${c.id.take(8)}）",
                fontSize = 14.sp,
            )
        }
        if (due.size > 6) {
            MpText("…还有 ${due.size - 6} 张", fontSize = 13.sp, color = Color(0xFF9AA3B2))
        }
        if (cards.isEmpty()) {
            MpText(
                "加卡：am broadcast -a com.memorypalace.card.ADD --es front \"serendipity\" --es back \"n.意外之喜\"；" +
                    "复习：…card.REVIEW --es id <前缀> --es grade remember|forgot",
                fontSize = 12.sp,
                color = Color(0xFF9AA3B2),
            )
        }
    }
}
