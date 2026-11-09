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
    fontSize: TextUnit = MpType.BodyLarge,
    fontWeight: FontWeight = MpType.Regular,
    color: Color = MpColors.TextPrimary,
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
            .clip(RoundedCornerShape(MpShape.Block))
            .background(MpColors.SurfaceDark)
            .padding(MpSpace.Md)
    ) {
        MpText("事件日志（DualState / logcat 同步）", fontSize = MpType.BodySmall, fontWeight = MpType.Bold, color = MpColors.Info)
        Spacer(Modifier.height(MpSpace.Sm))
        events.takeLast(maxLines).forEach { line ->
            MpText(line, fontSize = MpType.Caption2, color = MpColors.TextSecondary)
        }
    }
}

/** 计数器：remember 状态。切换双态后若数值归零，即状态随组合销毁丢失的直接证据。 */
@Composable
fun StateCounter(component: String, instanceId: Int) {
    var count by remember { mutableIntStateOf(0) }
    Row(horizontalArrangement = Arrangement.spacedBy(MpSpace.Md)) {
        com.pico.spatial.ui.design.Button(onClick = {
            count += 1
            DualStateProbe.log(component, "inst#$instanceId counter=$count")
        }) {
            MpText("计数 +1", fontSize = MpType.Body)
        }
        MpText("当前值：$count（切换后归零=状态丢失）", fontSize = MpType.Body, color = MpColors.Accent)
    }
}

fun Lifecycle.Event.readable(): String = name

/**
 * 面板深色底（M15）。
 *
 * 为什么需要：窗口的 `enableMaterialBackground` 是浅色半透明材质，而我们的
 * 文字近白（#E8EAF0）—— 叠在一起对比度约 1.2:1，正文基本不可读（2026-11-09
 * 审查实测）。关掉系统材质底板、由应用自己画一层 94% 不透明的深色面板，
 * 对比度拉到 12:1 以上，同时让三个窗口的外观可控。
 */
@Composable
fun Modifier.mpPanel(): Modifier = this
    .clip(RoundedCornerShape(MpShape.Block))
    .background(MpColors.SurfaceDark.copy(alpha = 0.97f))
    .padding(MpSpace.PanelMain)

/**
 * 演示模式开关（M15）。演示模式开 = 干净的产品界面；关 = 探针全回来。
 *
 * 放在面板最底部、字号最小 —— 演示时它不占视觉重量，需要取证时点一下就有。
 * 关掉演示模式后双态探针仍会写 logcat，所以实测证据链不受影响。
 */
@Composable
fun DemoModeToggle() {
    val demo by DemoMode.enabled.collectAsState()
    Row(horizontalArrangement = Arrangement.spacedBy(MpSpace.Sm)) {
        com.pico.spatial.ui.design.Button(onClick = { DemoMode.toggle() }) {
            MpText(
                if (demo) "显示调试信息" else "隐藏调试信息",
                fontSize = MpType.Caption2,
                color = MpColors.TextSecondary,
            )
        }
    }
}
