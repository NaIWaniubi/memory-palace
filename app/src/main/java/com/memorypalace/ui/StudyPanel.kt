package com.memorypalace.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.memorypalace.probe.DualStateController
import com.memorypalace.probe.DualStateProbe
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
        }

        MpText(
            "无头驱动（模拟器点不了空间按钮时用）：adb shell am broadcast -a com.memorypalace.dualstate.OPEN_STAGE / CLOSE_STAGE / TOGGLE_REVIEW_CARD",
            fontSize = 13.sp,
            color = Color(0xFF9AA3B2),
        )

        EventLogView(maxLines = 9)
        Spacer(Modifier.height(4.dp))
    }
}
