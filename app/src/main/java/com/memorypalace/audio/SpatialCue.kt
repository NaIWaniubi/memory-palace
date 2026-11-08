package com.memorypalace.audio

import android.util.Log
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ObjectAudioComponent
import com.pico.spatial.core.ecs.audio.AudioPlayerController
import com.pico.spatial.core.ecs.audio.Directivity
import com.pico.spatial.core.ecs.audio.DistanceAttenuationMode
import com.pico.spatial.core.ecs.resource.AudioResource
import com.pico.spatial.core.ecs.LoadType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/**
 * 空间提示音（M13）。三个语义提示音，以空间定位方式挂在锚定卡片实体上。
 *
 * 为什么是"实体空间音"而不是"自己算方位 + 立体声 pan"：
 * SDK 的 ObjectAudioComponent 把音源实体放进空间音频管线，
 * 由引擎按实体世界坐标做 HRTF/距离衰减/指向性渲染 —— 方位感来自
 * 真实锚点位置，不是我们手写的声像 pan。这样"走到冰箱前声音在左边"
 * 才和"卡片贴在冰箱上"是同一套坐标系，不会两套真相。
 *
 * 提示音语义：
 *   cue_due    到期提醒：卡片该复习了（引导你走到它面前）
 *   cue_place  放置确认：卡片已钉在这个位置
 *   cue_done   复习完成：这一轮结束了
 *
 * 资源生命周期：AudioResource 是原生资源，必须在 Stage 销毁时 close，
 * 否则多次进出 Full Space 会泄漏。故持有方做引用计数 + 幂等 close。
 */
object SpatialCue {

    const val TAG = "SpatialCue"

    const val CUE_DUE = "cue_due"
    const val CUE_PLACE = "cue_place"
    const val CUE_DONE = "cue_done"

    private val ASSET_PATHS = mapOf(
        CUE_DUE to "asset://audio/cue_due.wav",
        CUE_PLACE to "asset://audio/cue_place.wav",
        CUE_DONE to "asset://audio/cue_done.wav",
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** name -> 已加载资源。加载一次，重复请求直接复用。 */
    private val resources = mutableMapOf<String, AudioResource>()

    /** name -> 正在进行的加载任务，避免并发重复 load（原生资源重复 load 会崩）。 */
    private val loading = mutableMapOf<String, kotlinx.coroutines.Deferred<AudioResource?>>()

    /**
     * 卡片 ID -> 该卡片在房间层里的实体。
     * RoomStage 建场景时注册；锚点位置变了要更新（同一张卡可能重新锚定）。
     */
    private val entityByCard = mutableMapOf<String, Entity>()

    private val lock = Any()

    /**
     * 音量与衰减参数。距离衰减用平方反比（INVERSE_SQUARED），
     * 官方 Sample 的 ObjectAudioComponent 也是这个模式；音量给 0.9，
     * 提示音要能在 3m 内听清，同时不至于在耳边刺耳。
     */
    private const val VOLUME = 0.9f
    private const val REVERB_VOLUME = 0.3f

    /**
     * 指向性：pattern=1.0（全向）+ sharpness=1.0（无指向），
     * 即音源朝所有方向一致。卡片提示音不该偏听——
     * 真正的"方向感"来自音源实体在空间中的位置，而不是声束指向。
     */
    private val DIRECTIVITY = Directivity(1.0f, 1.0f)

    /**
     * 取（必要时加载）音频资源。加载失败返回 null 并打日志，绝不抛——
     * 提示音是增强项，不能因为音频失败把主流程带崩。
     */
    suspend fun resource(name: String): AudioResource? {
        synchronized(lock) {
            resources[name]?.let { return it }
        }

        val path = ASSET_PATHS[name] ?: run {
            Log.w(TAG, "unknown cue: $name")
            return null
        }

        // 已有加载任务在跑就等它，不重复 load（原生资源重复 load 会崩）
        val existing = synchronized(lock) { loading[name] }
        if (existing != null) return existing.await()

        val deferred = scope.async {
            runCatching {
                AudioResource.load(name = name, path = path, loadType = LoadType.FROM_ASSETS)
            }.onSuccess {
                Log.i(TAG, "audio resource loaded: $name")
            }.onFailure {
                Log.e(TAG, "audio resource load failed: $name — ${it.message}", it)
            }.getOrNull()
        }

        // 竞态兜底：另一个协程刚抢到加载权，我取消自己的任务并等它的
        val prev = synchronized(lock) { loading.put(name, deferred) }
        if (prev != null) {
            deferred.cancel()
            return prev.await()
        }

        val res = deferred.await()
        synchronized(lock) {
            loading.remove(name)
            if (res != null) resources[name] = res
        }
        return res
    }

    /**
     * 在锚定实体上装空间音源。挂 ObjectAudioComponent 并 prepare，
     * 之后 controller.play() 即可从该实体位置发声。
     *
     * @param entity 锚定卡片实体（AnchorEntity 或带 Transform 的普通 Entity）
     */
    suspend fun attach(entity: Entity): AudioPlayerController? {
        val res = resource(CUE_DUE) ?: return null
        return runCatching {
            entity.components.set(
                ObjectAudioComponent(
                    volume = VOLUME,
                    directivity = DIRECTIVITY,
                    distanceAttenuationMode = DistanceAttenuationMode.INVERSE_SQUARED,
                    reverbVolume = REVERB_VOLUME,
                )
            )
            entity.prepareAudio(res)
        }.onFailure {
            Log.e(TAG, "attach failed: ${it.message}", it)
        }.getOrNull()
    }

    /**
     * 在指定实体上播放一次提示音。
     *
     * @param entity 锚定卡片实体；null 表示无空间定位（纯 UI 提示）
     * @param cue 提示音名
     */
    suspend fun play(entity: Entity?, cue: String) {
        val res = resource(cue) ?: return
        val target = entity ?: run {
            Log.w(TAG, "play($cue) without entity — no spatial positioning")
            return
        }
        runCatching {
            val controller: AudioPlayerController = target.prepareAudio(res)
            controller.setVolume(VOLUME)
            controller.setLoop(false)
            controller.play()
            Log.i(TAG, "play($cue) on entity at ${target.hashCode()}")
        }.onFailure {
            Log.e(TAG, "play($cue) failed: ${it.message}", it)
        }
    }

    /**
     * 登记卡片在房间层里的实体。RoomStage 建场景时调用。
     * 之后任何"这张卡到期了"的事件都能找到它的空间位置并发声。
     */
    fun registerEntity(cardId: String, entity: Entity) {
        synchronized(lock) { entityByCard[cardId] = entity }
    }

    fun unregisterEntity(cardId: String) {
        synchronized(lock) { entityByCard.remove(cardId) }
    }

    /** 卡片实体；未登记返回 null（此时播报退化为无空间定位）。 */
    fun entityOf(cardId: String): Entity? = synchronized(lock) { entityByCard[cardId] }

    /**
     * 播放一张卡片的到期提示音：从该卡片的锚点位置发声，
     * 用户转头就能找到该去哪。
     */
    suspend fun cueCardDue(cardId: String) {
        val entity = entityOf(cardId)
        if (entity == null) {
            Log.w(TAG, "cueCardDue($cardId): entity not registered — card not anchored?")
            return
        }
        play(entity, CUE_DUE)
    }

    /** Stage 销毁时释放全部原生资源。多次进出 Full Space 会重复调用，必须幂等。 */
    fun releaseAll() {
        synchronized(lock) {
            resources.values.forEach {
                runCatching { it.close() }.onFailure { e -> Log.w(TAG, "close failed: ${e.message}") }
            }
            resources.clear()
            loading.clear()
            entityByCard.clear()
        }
        Log.i(TAG, "all audio resources released")
    }
}
