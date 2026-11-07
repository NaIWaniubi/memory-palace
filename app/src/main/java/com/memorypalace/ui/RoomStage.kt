package com.memorypalace.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.memorypalace.probe.DualStateProbe
import com.pico.spatial.ui.foundation.content.SpatialView

/** 房间层（Stage / Full Space）。空壳阶段只放一个空 SpatialView 占住场景。 */
@Composable
fun RoomStage() {
    val instanceId = remember { DualStateProbe.claim("room_stage") }
    LifecycleLogger("room_stage", instanceId)

    SpatialView(
        initial = { _, _ ->
            DualStateProbe.log("room_stage", "inst#$instanceId SpatialView.initial")
        }
    )
}
