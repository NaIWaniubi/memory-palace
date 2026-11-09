package com.memorypalace.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.memorypalace.anchor.AnchorScene
import com.memorypalace.spatial.AimPlacement
import com.memorypalace.spatial.PlaneSensor
import com.pico.spatial.ui.design.Button

/**
 * 房间平面 + 手柄取点面板（M16）。
 *
 * 这一段是给「卡贴在家具上」提供可看见、可验证的证据：
 *  - 上面列出房间实际识别到哪些平面、各自多大、朝向如何（含语义名）
 *  - 中间显示手柄射线当前指到哪个平面、多远
 *  - 下面是取点放置的两个入口（扳机 / 面板确认）
 *
 * 模拟器上平面检测不可用，所以额外给了「注入模拟平面」——
 * 注入的条目名字里带「（模拟）」，不允许冒充真实检测结果。
 */
@Composable
fun PlacementPanel() {
    val planes by PlaneSensor.planes.collectAsState()
    val status by PlaneSensor.status.collectAsState()
    val updates by PlaneSensor.updateCount.collectAsState()
    val aimLabel by AimPlacement.label.collectAsState()
    val source by AimPlacement.source.collectAsState()
    val target by AnchorScene.aimTarget.collectAsState()
    val demo by DemoMode.enabled.collectAsState()

    Column(verticalArrangement = Arrangement.spacedBy(MpSpace.Sm)) {
        MpText(
            "房间平面：${planes.size} 个" + if (updates > 0) "（收到 $updates 次更新）" else "",
            fontSize = MpType.Section,
            fontWeight = MpType.Bold,
            color = if (planes.isEmpty()) MpColors.TextSecondary else MpColors.Success,
        )

        // 状态行：区分"还没开始扫描""设备不支持""已识别到 N 个"
        MpText(
            when (status) {
                PlaneSensor.Status.IDLE -> "尚未开始扫描。进入房间层后自动开始。"
                PlaneSensor.Status.SCANNING ->
                    if (planes.isEmpty()) "扫描中：请缓慢环视房间，让系统把桌面/墙面识别出来…"
                    else "扫描中：已识别 ${planes.size} 个平面"
                PlaneSensor.Status.UNSUPPORTED ->
                    "本设备不支持平面检测（模拟器属正常）。可以注入模拟平面验证取点链路。"
                PlaneSensor.Status.STOPPED -> "已停止扫描。"
            },
            fontSize = MpType.BodySmall,
            color = when (status) {
                PlaneSensor.Status.UNSUPPORTED -> MpColors.Warning
                PlaneSensor.Status.SCANNING -> MpColors.Info
                else -> MpColors.TextSecondary
            },
        )

        Row(horizontalArrangement = Arrangement.spacedBy(MpSpace.Md)) {
            Button(onClick = { PlaneSensor.rescan() }) {
                MpText("重新扫描", fontSize = MpType.Caption)
            }
            if (!demo) {
                Button(onClick = { PlaneSensor.injectDemoPlanes() }) {
                    MpText("注入模拟平面", fontSize = MpType.Caption)
                }
                Button(onClick = { PlaneSensor.clearDemoPlanes() }) {
                    MpText("清除模拟", fontSize = MpType.Caption)
                }
            }
        }

        // 平面清单（最多 6 条，避免把面板撑爆）
        planes.take(6).forEach { p ->
            MpText(
                "· ${p.describe()}",
                fontSize = MpType.BodySmall,
                color = if (p.label.contains("模拟")) MpColors.Warning else MpColors.TextPrimary,
            )
        }
        if (planes.size > 6) {
            MpText("…还有 ${planes.size - 6} 个", fontSize = MpType.Caption2, color = MpColors.TextSecondary)
        }

        // 瞄准行
        Row(horizontalArrangement = Arrangement.spacedBy(MpSpace.Md)) {
            MpText("取点输入：${source.text}", fontSize = MpType.BodySmall, color = MpColors.TextSecondary)
            MpText(
                aimLabel?.let { "瞄准 $it" } ?: "未指到平面",
                fontSize = MpType.BodySmall,
                color = if (aimLabel != null) MpColors.Info else MpColors.TextSecondary,
            )
        }

        if (target != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(MpSpace.Md)) {
                MpText(
                    "正在瞄准放置：扣扳机 / 捏合确认",
                    fontSize = MpType.BodySmall,
                    fontWeight = MpType.Bold,
                    color = MpColors.Info,
                )
                Button(onClick = { AimPlacement.confirmManually() }) {
                    MpText("在这里放下", fontSize = MpType.Caption)
                }
                Button(onClick = { AnchorScene.cancelAimPlacement() }) {
                    MpText("取消", fontSize = MpType.Caption)
                }
            }
        }

        if (!demo) {
            MpText(
                "无头驱动：anchor.AIM_BEGIN（缺省取第一张未锚定的卡）/ AIM_SIMULATE " +
                    "--ef ox 0 --ef oy 1.6 --ef dx 0 --ef dy -1 / AIM_CONFIRM / AIM_CANCEL",
                fontSize = MpType.Caption2,
                color = MpColors.TextSecondary,
            )
        }
    }
}