package com.memorypalace.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.memorypalace.anchor.AnchorRecord
import com.pico.spatial.ui.design.Text

/** 钉在空间锚点上的知识卡（M2 占位版：标题 + 锚点信息）。 */
@Composable
fun AnchorCard(rec: AnchorRecord) {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(rec.title, fontSize = 22.sp, color = Color.White)
        Text(
            if (rec.uuid != null) "锚点 ${rec.uuid.take(8)}…" else "未锚定（回退位姿）",
            fontSize = 13.sp,
            color = if (rec.uuid != null) Color(0xFF8BE9A8) else Color(0xFFFFB86B),
        )
        Text(
            "位姿 (${"%.2f".format(rec.px)}, ${"%.2f".format(rec.py)}, ${"%.2f".format(rec.pz)})",
            fontSize = 12.sp,
            color = Color(0xFFB0BEC5),
        )
    }
}
