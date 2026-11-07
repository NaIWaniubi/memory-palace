package com.memorypalace

import androidx.compose.ui.unit.dp
import com.memorypalace.probe.DualStateController
import com.memorypalace.probe.DualStateProbe
import com.memorypalace.ui.ReviewCard
import com.memorypalace.ui.RoomStage
import com.memorypalace.ui.StudyPanel
import com.pico.spatial.ui.foundation.dsl.DefaultStage
import com.pico.spatial.ui.foundation.dsl.Form
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import com.pico.spatial.ui.foundation.dsl.WindowContainer
import com.pico.spatial.ui.foundation.dsl.WindowContainerSize
import com.pico.spatial.ui.platform.resize.ContainerResizeType

/**
 * 三窗空壳：书房面板 + 随身复习卡（两个 Planar WindowContainer）+ 房间层（DefaultStage）。
 * 结构对齐官方 SpatialAudio Sample（默认容器 = Stage，窗口为具名容器）。
 * 本版本目标：验证三窗口并存与 Shared/Full Space 切换时的窗口生命周期，
 * 不含任何业务逻辑。
 */
fun mainApp(scope: SpatialAppScope) =
    with(scope) {
        DualStateProbe.log("mainApp", "compose entry")
        DualStateController.autoOpenOnce()  // 冷启动即开三窗，现场无"开窗口"入口的兜底

        // 房间层：默认容器（全局唯一 default 必须给 Stage，否则与窗口容器冲突）
        DefaultStage {
            RoomStage()
        }

        // 书房面板（2D 看板窗口）
        WindowContainer(
            id = DualStateController.STUDY_PANEL_ID,
            form = Form.Planar,
            defaultSize = WindowContainerSize(1280.dp, 720.dp),
            resizeType = ContainerResizeType.ContentSize,
            enableMaterialBackground = true,
        ) {
            StudyPanel()
        }

        // 随身复习卡（第二个独立窗口）
        WindowContainer(
            id = DualStateController.REVIEW_CARD_ID,
            form = Form.Planar,
            defaultSize = WindowContainerSize(420.dp, 560.dp),
            resizeType = ContainerResizeType.ContentSize,
            enableMaterialBackground = true,
        ) {
            ReviewCard()
        }
    }
