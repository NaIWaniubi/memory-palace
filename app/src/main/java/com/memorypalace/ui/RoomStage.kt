package com.memorypalace.ui

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.memorypalace.anchor.AnchorScene
import com.memorypalace.audio.SpatialCue
import com.memorypalace.data.Clock
import com.memorypalace.probe.DualStateProbe
import com.memorypalace.spatial.AimPlacement
import com.memorypalace.spatial.PlaneSensor
import com.memorypalace.spatial.Reticle
import com.pico.spatial.core.ecs.AnchorEntity
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.anchor.AnchorTarget
import com.pico.spatial.core.math.EulerAngles
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

    // M13：组合销毁时释放原生音频资源。Stage 每次进出 Full Space 都会销毁重建，
    // 不释放就泄漏（AudioResource 是原生句柄）。
    //
    // M16：顺带管理平面检测与手柄取点的生命周期 —— 两者都只在房间层有意义，
    // 放在 Stage 里关掉比常驻省电，也避免 Shared Space 里白跑 tracking。
    DisposableEffect(Unit) {
        PlaneSensor.start()
        AimPlacement.start()
        onDispose {
            AimPlacement.stop()
            PlaneSensor.stop()
            Reticle.unbind()
            SpatialCue.releaseAll()
        }
    }

    // M16：准星每帧跟随。走 SharedFlow 而不是 StateFlow —— 60Hz 的定位更新
    // 不该触发任何 Compose 重组，直接改实体 Transform 才不卡 2D 面板。
    LaunchedEffect(Unit) {
        AimPlacement.frames.collect { aim ->
            if (aim == null) Reticle.hide()
            else Reticle.move(aim.point, aim.yawDeg)
        }
    }

    val records by AnchorScene.records.collectAsState()
    // M6：时间机器快进时也要重建场景 —— 附件面板的 GL 纹理不随 Compose 重组自动刷新，
    // 实测（2026-11-07）tick 变化后 AnchorCard 褪色标签不更新，整视图重建才生效。
    val tick by Clock.tick.collectAsState()
    val scope = rememberCoroutineScope()

    // 记录集合变化（放置/清除）或时钟跳变时整体重建 SpatialView，重跑 initial 恢复流程
    key(records, tick) {
        SpatialView(
            initial = { content, attachments ->
                DualStateProbe.log("room_stage", "inst#$instanceId SpatialView.initial anchors=${records.size}")

                // M16：空间准星。挂在一个自由实体下，位姿每帧由 AimPlacement 改，
                // 不参与任何锚定。默认藏在 y=-1000（视野外），有瞄准才移进来。
                attachments.entity(RETICLE_ID)?.let { panel ->
                    val holder = Entity()
                    val rt = TransformComponent()
                    rt.position = Vector3(0f, -1000f, 0f)
                    holder.components.set(TransformComponent::class.java, rt)
                    holder.addChild(panel)
                    content.addEntity(holder)
                    Reticle.bind(holder, rt)
                }

                records.forEach { rec ->
                    val panel = attachments.entity(rec.key) ?: return@forEach
                    val parent: Entity = if (rec.uuid != null) {
                        // UUID 来自持久层，可能是坏数据 —— 解析失败时不能让整个
                        // composition 抛异常（那会崩在进入房间层的第一步），
                        // 按回退位姿处理
                        val parsed = runCatching { UUID.fromString(rec.uuid) }.getOrNull()
                        if (parsed != null) {
                            AnchorEntity(AnchorTarget.createWorldAnchorTarget(parsed))
                        } else {
                            Log.e(AnchorScene.TAG, "bad uuid in record, fallback: ${rec.uuid}")
                            fallbackEntity(rec)
                        }
                    } else {
                        fallbackEntity(rec)
                    }
                    parent.addChild(panel)
                    content.addEntity(parent)
                    // M13：登记卡片→实体映射，到期提示音才能从这张卡的锚点位置发声
                    SpatialCue.registerEntity(rec.key, parent)
                    Log.i(
                        AnchorScene.TAG,
                        "scene entity added key=${rec.key} uuid=${rec.uuid ?: "fallback"} " +
                            "pos=(${rec.px},${rec.py},${rec.pz})"
                    )
                }
                // M13：给每张锚定卡片装空间音源（ObjectAudioComponent 由引擎做空间化渲染）
                scope.launch {
                    records.forEach { rec ->
                        SpatialCue.entityOf(rec.key)?.let { entity ->
                            SpatialCue.attach(entity)
                        }
                    }
                    // 实体就绪后播排队中的提示音（如放置确认音）
                    AnchorScene.drainPendingCues().forEach { (cardId, cue) ->
                        SpatialCue.entityOf(cardId)?.let { entity ->
                            scope.launch { SpatialCue.play(entity, cue) }
                        }
                    }
                }
                // 恢复一致性校验：系统解析位姿 vs 持久层保存位姿
                scope.launch { AnchorScene.verifyRestored(records) }
            },
            attachments = {
                // 准星排在最前：它只在有瞄准时才出现在视野里，不遮挡卡片
                AttachmentPanel(id = RETICLE_ID) { ReticleView() }
                records.forEach { rec ->
                    AttachmentPanel(id = rec.key) {
                        AnchorCard(rec)
                    }
                }
            },
        )
    }
}

/**
 * 回退实体（锚点服务不可用 / UUID 坏数据时用）。
 *
 * 关键：必须显式挂一个 TransformComponent 再设 position。
 * 之前写的是 `Entity().apply { components[TransformComponent::class.java]?.apply { ... } }`——
 * Entity() 只创建空的 ComponentSet，`components[Class]` 是纯查表、缺省返回 null，
 * 所以 `?.apply` 是空操作，parent 没有 Transform，卡片全部落在原点。
 * 那是"按保存位姿直渲"这条叙事唯一的渲染路径，等于整条路是假的。
 */
private fun fallbackEntity(rec: com.memorypalace.anchor.AnchorRecord): Entity =
    Entity().apply {
        val t = TransformComponent()
        t.position = Vector3(rec.px, rec.py, rec.pz)
        t.eulerAngles = com.pico.spatial.core.math.EulerAngles(rec.pitch, rec.yaw, rec.roll)
        components.set(TransformComponent::class.java, t)
    }

/** 准星面板 id。用不可打印前缀，保证永不会和卡片 id（UUID）撞。 */
private const val RETICLE_ID = "__aim_reticle"

/**
 * 空间准星（M16）。
 *
 * 只有"指到哪里"被看见，取点才可信 —— 评委看的是射线落点亮起来的那一刻。
 * 面板本身很小（一个环 + 平面名），因为它贴在命中点上，太大会挡住要放的卡。
 */
@Composable
private fun ReticleView() {
    val label by AimPlacement.label.collectAsState()
    val target by AnchorScene.aimTarget.collectAsState()
    val placing = target != null
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(MpShape.Block))
            .background(
                if (placing) MpColors.Info.copy(alpha = 0.92f)
                else MpColors.SurfaceDark.copy(alpha = 0.86f)
            )
            .border(
                width = 1.dp,
                color = if (placing) Color.White.copy(alpha = 0.9f) else MpColors.TextSecondary.copy(alpha = 0.5f),
                shape = RoundedCornerShape(MpShape.Block),
            )
            .padding(MpSpace.Sm),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        MpText("◎", fontSize = MpType.Section, color = if (placing) Color.White else MpColors.Accent)
        MpText(
            if (placing) "扳机放下" else (label ?: "未指到平面"),
            fontSize = MpType.Caption2,
            color = if (placing) Color.White else MpColors.TextSecondary,
        )
    }
}
