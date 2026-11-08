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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.memorypalace.anchor.AnchorScene
import com.memorypalace.audio.SpatialCue
import com.memorypalace.data.Clock
import com.memorypalace.data.DataGraph
import com.memorypalace.data.ImportPickerActivity
import com.memorypalace.data.ImportService
import com.memorypalace.notify.DueReminder
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
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier.fillMaxSize().padding(MpSpace.PanelMain),
        verticalArrangement = Arrangement.spacedBy(MpSpace.Xl),
    ) {
        MpText("记忆宫殿 · 书房面板", fontSize = MpType.Display, fontWeight = MpType.Bold)
        MpText("实例号 #$instanceId（切换双态后变化=窗口被销毁重建）", fontSize = MpType.BodyLarge, color = MpColors.Accent)

        StateCounter("study_panel", instanceId)

        Row(horizontalArrangement = Arrangement.spacedBy(MpSpace.Md)) {
            Button(onClick = { DualStateController.requestOpenStage(context) }) {
                MpText("进入房间层 (Full Space)", fontSize = MpType.Body)
            }
            Button(onClick = { DualStateController.requestCloseStage() }) {
                MpText("返回共享空间 (Shared)", fontSize = MpType.Body)
            }
            Button(onClick = { DualStateController.toggleReviewCard(context) }) {
                MpText("开关随身卡窗口", fontSize = MpType.Body)
            }
            Button(onClick = {
                context.startActivity(android.content.Intent(context, ImportPickerActivity::class.java))
            }) {
                MpText("导入牌组", fontSize = MpType.Body)
            }
        }

        MpText(
            "导入（M5/M8）：支持 CSV / 纯文本 / Anki .apkg，离线解析。真机走「导入牌组」选文件 → 此处出字段映射确认页；无头直导：card.IMPORT --es path …",
            fontSize = MpType.Caption,
            color = MpColors.TextSecondary,
        )

        // M14：到期提醒。头显本地通知，不依赖手机 App、不依赖网络。
        Row(horizontalArrangement = Arrangement.spacedBy(MpSpace.Md)) {
            Button(onClick = { scope.launch { DueReminder.schedule(context) } }) {
                MpText("排提醒", fontSize = MpType.BodySmall)
            }
            Button(onClick = { scope.launch { DueReminder.postDue(context) } }) {
                MpText("立刻试响", fontSize = MpType.BodySmall)
            }
            Button(onClick = { DueReminder.cancel(context) }) {
                MpText("撤提醒", fontSize = MpType.BodySmall)
            }
        }
        MpText(
            "到期提醒（M14）：头显本地通知，遗忘曲线算出到期时刻后在头显里弹横幅。" +
                "提醒时刻 = 当天最后一张到期卡的到期时间（一次响一整批，不逐张打扰）。" +
                "无头：am broadcast -a com.memorypalace.notify.SCHEDULE / TEST / CANCEL",
            fontSize = MpType.Caption,
            color = MpColors.TextSecondary,
        )

        ImportConfirmView()

        MpText(
            "无头驱动（模拟器点不了空间按钮时用）：adb shell am broadcast -a com.memorypalace.dualstate.OPEN_STAGE / CLOSE_STAGE / TOGGLE_REVIEW_CARD",
            fontSize = MpType.Caption,
            color = MpColors.TextSecondary,
        )

        TimeMachineView()

        DueQueueView()

        EventLogView(maxLines = 7)
        Spacer(Modifier.height(MpSpace.Xs))
    }
}

/** 时间机器（M6）：一键快进，看卡片集体褪色 + 到期队列堆积。 */
@Composable
fun TimeMachineView() {
    val tick by Clock.tick.collectAsState()
    val offset = remember(tick) { Clock.offsetDays() }
    // M12：跳转瞬间先亮"在处理"态 —— 房间层 GL 纹理重建有数秒延迟（M6 实测），
    // 没有这个提示的话，按完按钮房间层数秒没反应，现场观感="卡死了"。4.5s 后自动收起。
    var jumping by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun jump(action: () -> Unit) {
        jumping = true
        action()
        scope.launch { delay(4500); jumping = false }
    }

    Column(verticalArrangement = Arrangement.spacedBy(MpSpace.Sm)) {
        Row(horizontalArrangement = Arrangement.spacedBy(MpSpace.Md)) {
            MpText(
                "时间机器：当前偏移 ${if (offset >= 0) "+" else ""}${offset} 天",
                fontSize = MpType.Section,
                fontWeight = MpType.Bold,
                color = if (offset == 0L) MpColors.TextSecondary else MpColors.TimeMachine,
            )
            Button(onClick = { jump { Clock.addDays(1) } }) {
                MpText("+1 天", fontSize = MpType.BodySmall)
            }
            Button(onClick = { jump { Clock.addDays(7) } }) {
                MpText("+7 天", fontSize = MpType.BodySmall)
            }
            Button(onClick = { jump { Clock.setOffsetDays(0) } }) {
                MpText("复位", fontSize = MpType.BodySmall)
            }
        }
        if (jumping) {
            MpText(
                "⏳ 时间跳转中：到期队列已重算，房间层锚卡正在重建褪色纹理（约数秒）…",
                fontSize = MpType.BodySmall,
                color = MpColors.TimeMachine,
            )
        }
        MpText(
            "快进到未来：到期队列立即重算堆积，房间层锚定卡片按记忆强度褪色。无头：am broadcast -a com.memorypalace.clock.SET_OFFSET --el days 7",
            fontSize = MpType.Caption,
            color = MpColors.TextSecondary,
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
    val scope = rememberCoroutineScope()

    Column(verticalArrangement = Arrangement.spacedBy(MpSpace.Sm)) {
        Row(horizontalArrangement = Arrangement.spacedBy(MpSpace.Md)) {
            MpText(
                "今日到期队列：${due.size} 张 / 共 ${cards.size} 张",
                fontSize = MpType.Section,
                fontWeight = MpType.Bold,
                color = if (due.isEmpty()) MpColors.Success else MpColors.Accent,
            )
            if (session == null) {
                Button(onClick = { ReviewSession.start() }) {
                    MpText("开始复习", fontSize = MpType.BodySmall)
                }
            } else {
                MpText("复习中 ${session!!.index + 1}/${session!!.total}", fontSize = MpType.Body, color = MpColors.Info)
            }
        }
        due.take(6).forEachIndexed { i, c ->
            val intervalText = if (c.intervalDays < 1.0) "新卡/重学" else "${c.intervalDays.toInt()}d"
            Row(horizontalArrangement = Arrangement.spacedBy(MpSpace.Md)) {
                MpText(
                    "· ${c.front} — ${c.back}（reps=${c.repetitions} ef=${"%.2f".format(c.easiness)} 间隔=$intervalText id=${c.id.take(8)}）",
                    fontSize = MpType.BodySmall,
                )
                // 放置动作的真实入口：把到期卡钉到面前 1.5m（无头等价：anchor.PLACE）
                if (i == 0) {
                    Button(onClick = { AnchorScene.placeForCard(c.id, 0.0f, 1.2f, -1.5f) }) {
                        MpText("钉入房间", fontSize = MpType.Caption)
                    }
                }
            }
        }
        if (due.size > 6) {
            MpText("…还有 ${due.size - 6} 张", fontSize = MpType.Caption, color = MpColors.TextSecondary)
        }
        // M13：方位引导演示入口 —— 从第一张到期卡的锚点位置发提示音，
        // 用户转头就能定位它。卡片必须已钉入房间才有空间位置。
        val firstDue = due.firstOrNull()
        if (firstDue != null) {
            val anchored = AnchorScene.records.value.any { it.key == firstDue.id }
            Row(horizontalArrangement = Arrangement.spacedBy(MpSpace.Md)) {
                Button(onClick = {
                    scope.launch { SpatialCue.cueCardDue(firstDue.id) }
                }) {
                    MpText("🔊 播报方位", fontSize = MpType.Caption)
                }
                MpText(
                    if (anchored) "从「${firstDue.front}」的锚点位置发声"
                    else "「${firstDue.front}」尚未钉入房间，先点「钉入房间」再播报",
                    fontSize = MpType.Caption2,
                    color = if (anchored) MpColors.Success else MpColors.Warning,
                )
            }
        }
        if (cards.isEmpty()) {
            MpText(
                "加卡：am broadcast -a com.memorypalace.card.ADD --es front \"serendipity\" --es back \"n.意外之喜\"；" +
                    "复习：…card.REVIEW --es id <前缀> --es grade remember|forgot",
                fontSize = MpType.Caption2,
                color = MpColors.TextSecondary,
            )
        }
    }
}

/** 字段映射确认页（M8）：SAF/预览广播解析出 pendingPreview 后在此确认入库。 */
@Composable
fun ImportConfirmView() {
    val preview by ImportService.pendingPreview.collectAsState()
    val scope = rememberCoroutineScope()
    val p = preview ?: return

    Column(verticalArrangement = Arrangement.spacedBy(MpSpace.Sm)) {
        MpText(
            "导入确认：${p.fileName}（格式 ${p.format}，解析 ${p.cards.size} 条）",
            fontSize = MpType.Section,
            fontWeight = MpType.Bold,
            color = MpColors.Info,
        )
        MpText("字段映射：正面 → 背面，预览前 5 条：", fontSize = MpType.BodySmall, color = MpColors.TextSecondary)
        p.cards.take(5).forEach { c ->
            MpText("· ${c.front} → ${c.back}", fontSize = MpType.BodySmall)
        }
        if (p.cards.size > 5) {
            MpText("…还有 ${p.cards.size - 5} 条", fontSize = MpType.Caption, color = MpColors.TextSecondary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(MpSpace.Md)) {
            Button(onClick = { scope.launch { ImportService.confirmImport() } }) {
                MpText("确认导入", fontSize = MpType.BodySmall)
            }
            Button(onClick = { ImportService.cancelPreview() }) {
                MpText("取消", fontSize = MpType.BodySmall)
            }
        }
    }
}
