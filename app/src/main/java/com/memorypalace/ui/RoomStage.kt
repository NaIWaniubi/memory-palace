package com.memorypalace.ui

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.memorypalace.anchor.AnchorScene
import com.memorypalace.probe.DualStateProbe
import com.pico.spatial.core.ecs.AnchorEntity
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.anchor.AnchorTarget
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.foundation.content.SpatialView
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 房间层（Stage / Full Space）。M2：锚定最小闭环。
 *
 * 结构对齐官方 SpatialMesh Sample：SpatialView 的 attachments 声明卡片面板，
 * initial 里把面板实体挂到 AnchorEntity（绑定世界锚点 UUID）下。
 * Stage 每次重建时按持久层记录恢复；锚点服务不可用的记录按保存位姿直渲（回退）。
 */
@Composable
fun RoomStage() {
    val instanceId = remember { DualStateProbe.claim("room_stage") }
    LifecycleLogger("room_stage", instanceId)

    val records by AnchorScene.records.collectAsState()
    val scope = rememberCoroutineScope()

    // 记录集合变化（放置/清除）时整体重建 SpatialView，重跑 initial 恢复流程
    key(records) {
        SpatialView(
            initial = { content, attachments ->
                DualStateProbe.log("room_stage", "inst#$instanceId SpatialView.initial anchors=${records.size}")
                records.forEach { rec ->
                    val panel = attachments.entity(rec.key) ?: return@forEach
                    val parent: Entity = if (rec.uuid != null) {
                        AnchorEntity(AnchorTarget.createWorldAnchorTarget(UUID.fromString(rec.uuid)))
                    } else {
                        Entity().apply {
                            components[TransformComponent::class.java]?.apply {
                                position = Vector3(rec.px, rec.py, rec.pz)
                            }
                        }
                    }
                    parent.addChild(panel)
                    content.addEntity(parent)
                    Log.i(
                        AnchorScene.TAG,
                        "scene entity added key=${rec.key} uuid=${rec.uuid ?: "fallback"} " +
                            "pos=(${rec.px},${rec.py},${rec.pz})"
                    )
                }
                // 恢复一致性校验：系统解析位姿 vs 持久层保存位姿
                scope.launch { AnchorScene.verifyRestored(records) }
            },
            attachments = {
                records.forEach { rec ->
                    AttachmentPanel(id = rec.key) {
                        AnchorCard(rec)
                    }
                }
            },
        )
    }
}
