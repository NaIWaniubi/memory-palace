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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.memorypalace.anchor.AnchorRecord
import com.memorypalace.anchor.AnchorScene
import com.memorypalace.audio.SpatialCue
import com.memorypalace.data.Clock
import com.memorypalace.probe.DualStateProbe
import com.memorypalace.spatial.AimPlacement
import com.memorypalace.spatial.PlaneSensor
import com.memorypalace.spatial.Reticle
import com.pico.spatial.core.container.SpatialViewContent
import com.pico.spatial.core.ecs.AnchorEntity
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.anchor.AnchorTarget
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.content.SpatialViewAttachments
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 房间层（Stage / Full Space）。M2：锚定最小闭环。
 *
 * 结构对齐官方 SpatialMesh Sample：SpatialView 的 attachments 声明卡片面板，
 * initial 里把面板实体挂到 AnchorEntity（绑定世界锚点 UUID）下。
 * Stage 每次重建时按持久层记录恢复；锚点服务不可用的记录按保存位姿直渲（回退）。
 *
 * P3 卡死加固（准入「30min 无卡死」直接威胁项）：
 * 旧实现 `key(records, tick){ SpatialView(...) }` —— 任何一条记录或 tick 变化都整棵
 * 场景图销毁重建，initial 里又跑 `verifyRestored`（loadAnchor 全量）。长时间运行 +
 * 反复复习/放卡 → GL 纹理 churn / GC 压力 / 累积掉帧。
 *
 * 现在拆成两条独立、便宜的更新路径：
 *  - [records] 变化：SpatialView **不重建**，由 [RoomSceneMount.applyDiff] 按 key+签名增量
 *    只挂/卸真正改变的那几张卡（UUID、位姿变则重挂该张，不变则原样复用）；
 *  - [tick] 变化：时间机器褪色需要刷新 GL 纹理（M6 实测），保留重建但**去抖**
 *    （连拨 +/- 合并成一次），且与 records 解耦 —— 复习/放卡这类高频操作不再触发重建；
 *  - `verifyRestored` 从"每次 records 变化的 initial 里重跑"改为"每个 Stage 实例进入时一次"。
 */
@Composable
fun RoomStage() {
    val instanceId = remember { DualStateProbe.claim("room_stage") }
    LifecycleLogger("room_stage", instanceId)

    // M13：组合销毁时释放原生音频资源。Stage 每次进出 Full Space 都会销毁重建，
    // 不释放就泄漏（AudioResource 是原生句柄）。SpatialCue.releaseAll 现为代次化，
    // 只关闭其之前创建的句柄，与仍在途的 load 无竞态（见 SpatialCue）。
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
    val tick by Clock.tick.collectAsState()

    // tick 去抖：时间机器连点 +/- 时，只有停手 [ROOM_REBUILD_DEBOUNCE_MS] 后才真正重建一次
    // （GL 纹理刷新靠重建，见类注释）。这是 M6 唯一还需要的整树重建触发源。
    var stableTick by remember { mutableStateOf(tick) }
    LaunchedEffect(tick) {
        if (tick != stableTick) {
            delay(ROOM_REBUILD_DEBOUNCE_MS)
            stableTick = tick
        }
    }

    // records 变化【不再】进入这个 key —— 只有去抖后的时间机器跳变才重建整视图。
    key(stableTick) {
        val scope = rememberCoroutineScope()
        // mount 与 SpatialView 同生命周期：stableTick 变化会重建本块，remember 随之换新实例。
        val mount = remember { RoomSceneMount() }
        DisposableEffect(mount) {
            onDispose { mount.detach() }
        }

        SpatialView(
            initial = { content, attachments ->
                DualStateProbe.log("room_stage", "inst#$instanceId SpatialView.initial anchors=${records.size}")
                // 交出 content/attachments 引用，之后的增量挂载都走它；就绪后唤醒首帧 effect。
                mount.attach(content, attachments)

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
                // 注意：卡片实体的挂载、音源登记、恢复校验都挪到下面的 effect，
                // 统一走增量路径，避免 initial 与 effect 对同一张卡双写。
            },
            attachments = {
                // 准星排在最前：它只在有瞄准时才出现在视野里，不遮挡卡片
                AttachmentPanel(id = RETICLE_ID) { ReticleView() }
                records.forEach { rec ->
                    // 面板 id 恒为 rec.key → 卡片实体不因邻卡增删而销毁重建；
                    // 只有这张卡的"身份签名"变了才在其内容里换 key 强制重组（锚点/位姿刷新）。
                    AttachmentPanel(id = rec.key) {
                        key(sceneSignature(rec)) { AnchorCard(rec) }
                    }
                }
            },
        )

        // records 增量：SpatialView 存活，只增删改变化的那几张卡。
        // 首帧（mount 刚就绪）会走一次全量挂载，等价于旧 initial 的 records.forEach。
        LaunchedEffect(records) {
            if (!mount.awaitReady()) return@LaunchedEffect // 本实例已 detached（离开房间层）
            // 增量对齐 + 有限重试：某张卡的 attachment 面板首帧可能尚未被 SDK 建出来
            // （mountOne 拿不到 entity 就跳过、且**不**记进 mounted），下一拍再试，最多几帧。
            // 没有这个重试，加固前的整树重建会在 records 每次变化时顺带补挂，改成增量后必须显式兜住。
            var attempt = 0
            while (attempt < MOUNT_RETRY_MAX) {
                val changed = mount.applyDiff(records)
                if (!changed && mount.allDesiredMounted(records)) break
                attempt++
                if (attempt < MOUNT_RETRY_MAX) delay(MOUNT_RETRY_DELAY_MS)
            }
            // 新挂载的卡：装空间音源 + 播排队的放置确认音（M13）。
            scope.launch {
                records.forEach { rec ->
                    SpatialCue.entityOf(rec.key)?.let { entity -> SpatialCue.attach(entity) }
                }
                // 实体就绪后播排队中的提示音（如放置确认音）
                AnchorScene.drainPendingCues().forEach { (cardId, cue) ->
                    SpatialCue.entityOf(cardId)?.let { entity ->
                        launch { SpatialCue.play(entity, cue) }
                    }
                }
            }
        }

        // 恢复一致性校验：只在"进入这个 Stage 实例"时跑一次（P3：不再随 records 变化重跑 loadAnchor 全量）。
        LaunchedEffect(Unit) {
            if (!mount.awaitReady()) return@LaunchedEffect
            AnchorScene.verifyRestored(AnchorScene.records.value)
        }
    }
}

/** 增量挂载重试：面板实体首帧可能未就绪，补挂几帧。 */
private const val MOUNT_RETRY_MAX = 5
private const val MOUNT_RETRY_DELAY_MS = 50L


/** 时间机器连拨去抖窗口：稳定 350ms 才重建一次纹理，合并 ±1/±7 的连点。 */
private const val ROOM_REBUILD_DEBOUNCE_MS = 350L

/**
 * 单张锚定卡在当前场景里的"身份签名"：UUID + 位姿 + 语义标签。
 * 签名不变 → 实体可原样复用（不重挂）；签名变了（新锚定成功 / 降级 / 位姿漂移）→ 只重挂这一张。
 * 这是 [planSceneDiff] 判断"哪几张真的变了"的依据，也是 attachments 内容里让未变卡片
 * 免于重组的 Compose key。
 */
internal fun sceneSignature(rec: AnchorRecord): String =
    "${rec.uuid}|${rec.px}|${rec.py}|${rec.pz}|${rec.pitch}|${rec.yaw}|${rec.roll}|${rec.anchorLabel}"

/** [planSceneDiff] 的输入：一张卡在场景里的可比较状态（无 SDK 依赖，纯数据）。 */
internal class SceneMountState(val key: String, val signature: String)

/** [planSceneDiff] 的结果：三类增量操作，全是 key/状态集合，供 [RoomSceneMount] 逐条落地。 */
internal class SceneDiff(
    val toAdd: List<SceneMountState>,
    val toRemove: List<String>,
    val toReplace: List<SceneMountState>,
) {
    val isEmpty: Boolean get() = toAdd.isEmpty() && toRemove.isEmpty() && toReplace.isEmpty()
}

/**
 * 纯增量差集（无 Android/协程/SDK 依赖，JVM 可单测；置于顶层避开 SpatialView 初始化）。
 *
 * 对比"当前已挂载" [mounted]（key→signature）与"期望场景" [desired]：
 *  - key 只在 desired 出现 → [SceneDiff.toAdd]（新增卡）
 *  - key 只在 mounted 出现 → [SceneDiff.toRemove]（清除卡）
 *  - key 两边都有但签名不同 → [SceneDiff.toReplace]（同一张卡重新锚定/降级/位姿变）
 *  - key 都有且签名相同 → 不进任何列表（原样复用，零操作）—— 这正是消除整树 churn 的关键。
 */
internal fun planSceneDiff(
    mounted: Map<String, String>,
    desired: List<SceneMountState>,
): SceneDiff {
    val desiredByKey = LinkedHashMap<String, SceneMountState>()
    for (s in desired) desiredByKey[s.key] = s
    val toAdd = ArrayList<SceneMountState>()
    val toReplace = ArrayList<SceneMountState>()
    val toRemove = ArrayList<String>()
    for ((key, state) in desiredByKey) {
        val oldSig = mounted[key]
        when {
            oldSig == null -> toAdd.add(state)
            oldSig != state.signature -> toReplace.add(state)
            // else：完全复用，不进任何列表
        }
    }
    for (key in mounted.keys) {
        if (!desiredByKey.containsKey(key)) toRemove.add(key)
    }
    return SceneDiff(toAdd, toRemove, toReplace)
}

/**
 * 房间层场景图的挂载台账（组合之外、每-SpatialView 实例状态）。
 *
 * 持有 SpatialView 的 content/attachments 引用 + 当前已挂载的 key→(Entity, signature)，
 * 把 records 变化翻译成"只增删改变化的那几张卡"的实体级操作，替代旧的整树 key 重建。
 * 生命周期严格绑定单个 SpatialView 实例：[RoomSceneMount.attach] 在 initial 里就绪，
 * SpatialView 随 stableTick 销毁或离开房间层时 [RoomSceneMount.detach] 清空引用（绝不误用旧 content）。
 */
private class RoomSceneMount {
    private var content: SpatialViewContent? = null
    private var attachments: SpatialViewAttachments? = null

    /** 已挂载卡片：key → 该卡在场景里的父实体 + 身份签名。 */
    private val mounted = LinkedHashMap<String, MountedCard>()

    private class MountedCard(val entity: Entity, val signature: String)

    @Volatile
    private var ready = false

    fun attach(content: SpatialViewContent, attachments: SpatialViewAttachments) {
        synchronized(this) {
            this.content = content
            this.attachments = attachments
            mounted.clear() // 新 SpatialView 的实体树是全新的，旧台账作废
            ready = true
        }
    }

    fun detach() {
        synchronized(this) {
            ready = false
            content = null
            attachments = null
            mounted.clear()
        }
    }

    /** 等待 [attach] 完成；本实例若已 detached 则返回 false（调用方应放弃这一帧）。 */
    suspend fun awaitReady(timeoutMs: Long = 2_000L): Boolean {
        var waited = 0L
        while (waited < timeoutMs) {
            synchronized(this) { if (ready) return true }
            delay(20L); waited += 20L
        }
        return synchronized(this) { ready }
    }

    /**
     * 把场景对齐到 [records]：只动真正变化的卡，返回是否有改动。
     * 主线程调用（initial / LaunchedEffect 都在 Main）。attachment 面板实体由 SDK 在组合提交时建好，
     * 若个别 key 尚未就绪（[SpatialViewAttachments.entity] 返回 null）本轮跳过，下一帧再补挂。
     */
    fun applyDiff(records: List<AnchorRecord>): Boolean {
        val byKey = LinkedHashMap<String, AnchorRecord>()
        records.forEach { byKey[it.key] = it }
        val desired = byKey.values.map { SceneMountState(it.key, sceneSignature(it)) }
        // 在锁内一次性算出差集 + 抓取 content/attachments 快照；无改动或实例已 detach 直接返回。
        val snapshot = synchronized(this) {
            val diff = planSceneDiff(mounted.mapValues { it.value.signature }, desired)
            if (diff.isEmpty) null else Triple(diff, content, attachments)
        } ?: return false
        val (diff, contentRef, attachmentsRef) = snapshot
        val c = contentRef ?: return false
        val a = attachmentsRef ?: return false

        // 1) 删除：移除的卡（clear / 记录被删）——先摘实体，再注销音源。
        diff.toRemove.forEach { key ->
            val m = synchronized(this) { mounted.remove(key) }
            if (m != null) {
                runCatching { c.removeEntity(m.entity) }
                    .onFailure { Log.e(AnchorScene.TAG, "removeEntity($key) failed", it) }
                SpatialCue.unregisterEntity(key)
            }
        }
        // 2) 替换：同一 key 签名变了（重新锚定/降级/位姿漂移）——先卸旧再挂新，避免同卡双实体。
        diff.toReplace.forEach { state ->
            val old = synchronized(this) { mounted.remove(state.key) }
            if (old != null) {
                runCatching { c.removeEntity(old.entity) }
                    .onFailure { Log.e(AnchorScene.TAG, "removeEntity(replace ${state.key}) failed", it) }
                SpatialCue.unregisterEntity(state.key)
            }
            byKey[state.key]?.let { mountOne(c, a, it) }
        }
        // 3) 新增：只挂真正新的那几张卡。
        diff.toAdd.forEach { state ->
            byKey[state.key]?.let { mountOne(c, a, it) }
        }
        Log.i(
            AnchorScene.TAG,
            "scene incremental: +${diff.toAdd.size} ~${diff.toReplace.size} -${diff.toRemove.size} " +
                "reuse=${desired.size - diff.toAdd.size - diff.toReplace.size} total=${desired.size}",
        )
        return true
    }

    /** 期望集合里的每张卡是否都已在 [mounted] 里（用于增量重试的"已对齐"判据）。 */
    fun allDesiredMounted(records: List<AnchorRecord>): Boolean = synchronized(this) {
        records.all { mounted.containsKey(it.key) }
    }

    /** 挂载单张卡（锚点实体或回退位姿实体），登记进 [mounted] 与 SpatialCue。 */
    private fun mountOne(c: SpatialViewContent, a: SpatialViewAttachments, rec: AnchorRecord) {
        val panel = a.entity(rec.key) ?: run {
            Log.w(AnchorScene.TAG, "attachment entity not ready for ${rec.key}, retry next frame")
            return
        }
        val parent: Entity = if (rec.uuid != null) {
            // UUID 来自持久层，可能是坏数据 —— 解析失败时不能让整个挂载抛异常，按回退位姿处理。
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
        c.addEntity(parent)
        SpatialCue.registerEntity(rec.key, parent)
        synchronized(this) { mounted[rec.key] = MountedCard(parent, sceneSignature(rec)) }
        Log.i(
            AnchorScene.TAG,
            "scene entity added key=${rec.key} uuid=${rec.uuid ?: "fallback"} " +
                "pos=(${rec.px},${rec.py},${rec.pz})",
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
private fun fallbackEntity(rec: AnchorRecord): Entity =
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
