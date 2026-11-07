package com.memorypalace.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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

/** 随身复习卡（第二个独立 WindowContainer）。 */
@Composable
fun ReviewCard() {
    val instanceId = remember { DualStateProbe.claim("review_card") }
    LifecycleLogger("review_card", instanceId)
    val context = LocalContext.current

    Column(
        modifier = Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        MpText("随身复习卡", fontSize = 28.sp, fontWeight = FontWeight(700))
        MpText("实例号 #$instanceId", fontSize = 18.sp, color = Color(0xFFFFC107))

        StateCounter("review_card", instanceId)

        Button(onClick = { DualStateController.requestOpenStage(context) }) {
            MpText("进入房间层", fontSize = 16.sp)
        }

        EventLogView(maxLines = 6)
    }
}
