package com.memorypalace.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.memorypalace.probe.DualStateProbe
import com.pico.spatial.ui.design.Text

/** 记录组件全部生命周期事件 + 组合销毁事件，证据同时进 logcat 与屏上日志。 */
@Composable
fun LifecycleLogger(component: String, instanceId: Int) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            DualStateProbe.log(component, "inst#$instanceId ${event.name}")
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            DualStateProbe.log(component, "inst#$instanceId DISPOSE(composition)")
        }
    }
}

@Composable
fun MpText(
    text: String,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = 18.sp,
    fontWeight: FontWeight = FontWeight(400),
    color: Color = Color(0xFFE8EAF0),
) {
    Text(
        modifier = modifier,
        text = text,
        fontWeight = fontWeight,
        fontSize = fontSize,
        lineHeight = fontSize * 1.3,
        color = color,
    )
}

/** 屏上事件日志（最近 N 条），截图即可取证，不必依赖 logcat。 */
@Composable
fun EventLogView(modifier: Modifier = Modifier, maxLines: Int = 10) {
    val events by DualStateProbe.events.collectAsState()
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF12141A))
            .padding(12.dp)
    ) {
        MpText("事件日志（DualState / logcat 同步）", fontSize = 14.sp, fontWeight = FontWeight(700), color = Color(0xFF7CD4FD))
        Spacer(Modifier.height(6.dp))
        events.takeLast(maxLines).forEach { line ->
            MpText(line, fontSize = 11.sp, color = Color(0xFF9AA3B2))
        }
    }
}

/** 计数器：remember 状态。切换双态后若数值归零，即状态随组合销毁丢失的直接证据。 */
@Composable
fun StateCounter(component: String, instanceId: Int) {
    var count by remember { mutableIntStateOf(0) }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        com.pico.spatial.ui.design.Button(onClick = {
            count += 1
            DualStateProbe.log(component, "inst#$instanceId counter=$count")
        }) {
            MpText("计数 +1", fontSize = 16.sp)
        }
        MpText("当前值：$count（切换后归零=状态丢失）", fontSize = 16.sp, color = Color(0xFFFFC107))
    }
}

fun Lifecycle.Event.readable(): String = name
