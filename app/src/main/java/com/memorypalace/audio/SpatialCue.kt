package com.memorypalace.audio

import android.util.Log
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ObjectAudioComponent
import com.pico.spatial.core.ecs.audio.AudioPlayerController
import com.pico.spatial.core.ecs.audio.Directivity
import com.pico.spatial.core.ecs.audio.DistanceAttenuationMode
import com.pico.spatial.core.ecs.resource.AudioResource
import com.pico.spatial.core.ecs.LoadType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
 *
 * P3 泄漏加固（准入「30min 无崩溃」直接威胁项）：
 * 旧实现里 `releaseAll()` 与在途 `AudioResource.load` 竞态 —— clear 之后，
 * 迟到的加载结果仍会把原生句柄写回 `resources`（旧 :117），该句柄再也无人 close。
 * 反复进出 Full Space（每次 attach/play 都可能触发 load）句柄缓慢累积泄漏。
 * 现在用「代次（generation）+ 原子认领加载权」根治：
 *  - 每个句柄恰好被 close 一次：发布出去的归 releaseAll 关，没发布的在发布点就地 close；
 *  - release 只作用于其之前创建的句柄；release 之后才完成的 load 发现代次变了，自弃并 close；
 *  - 同一 name 的并发 load 被合并成一次（锁内原子认领），旧实现「先 async 再 put、
 *    抢输方 cancel 掉自己的 deferred」在 load 已完成时 cancel 不掉句柄，那条漏路径一并堵死。
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

    /**
     * 资源代次。[releaseAll] 每调一次 +1：代次即"哪一代 Stage"的身份戳，
     * load 完成时拿它和发起时的快照比对，即可识别"release 之后才迟到的结果"。
     */
    private var generation = 0L

    /** name -> 已加载资源。加载一次，重复请求直接复用；[releaseAll] 负责全部 close。 */
    private val resources = mutableMapOf<String, AudioResource>()

    /** name -> 在途加载。同一 name 并发请求共享一个 deferred，绝不重复触发原生 load。 */
    private val loading = mutableMapOf<String, CompletableDeferred<LoadOutcome>>()

    /**
     * 卡片 ID -> 该卡片在房间层里的实体。
     * RoomStage 建场景时注册；锚点位置变了要更新（同一张卡可能重新锚定）。
     */
    private val entityByCard = mutableMapOf<String, Entity>()

    /** 守护 [generation]/[resources]/[loading]/[entityByCard] 的同一把锁（对象内状态全走它）。 */
    private val lock = Any()

    /**
     * 一次在途加载的结果。
     * @param resource 加载出的句柄；null = 失败/作废（作废时句柄已在发布点就地 close，无人再持有）
     * @param generation 发起本次加载时的代次快照，仅用于日志与语义自证。
     */
    internal class LoadOutcome(val resource: AudioResource?, val generation: Long)

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
     *
     * 并发/竞态不变式（P3）：
     *  ① 锁内一次完成「查已发布 → 查在途 → 原子认领加载权」，无 check-then-act 窗口；
     *  ② 加载完成后在锁内做发布裁决：代次未变才进 [resources]，
     *     代次已变（releaseAll 插队）则就地 close 新句柄并回 null —— 每个句柄恰好 close 一次；
     *  ③ finally 保证：加载槽位必被摘除、等待者必被唤醒（异常/取消路径也不例外），
     *     [releaseAll] 同时会唤醒它清掉的旧槽位，不会留下永久挂起的 await。
     */
    suspend fun resource(name: String): AudioResource? {
        val path = ASSET_PATHS[name] ?: run {
            Log.w(TAG, "unknown cue: $name")
            return null
        }

        val mine = CompletableDeferred<LoadOutcome>()
        // 认领动作与代次快照在同一把锁里取出，保证 StartLoad.gen 就是"登记在途"那一刻的代次。
        val claim: LoadAction = synchronized(lock) {
            val published = resources[name]
            when {
                published != null -> LoadAction.UsePublished(published)
                loading[name] != null -> LoadAction.JoinInflight(loading.getValue(name))
                else -> {
                    loading[name] = mine
                    LoadAction.StartLoad(generation)
                }
            }
        }
        // when 作表达式：两条"直接返回"的分支外提，唯一走到加载流程的分支把代次快照取出为 genAtStart。
        // 不依赖 when 语句之后的隐式智能转型（Kotlin 不在 when 语句后收窄 sealed 变量类型）。
        val genAtStart: Long = when (claim) {
            is LoadAction.UsePublished -> return claim.resource
            is LoadAction.JoinInflight ->
                // 在途方失败/作废返回 null：调用方（attach/play）拿 null 安静跳过，不崩不泄。
                return runCatching { claim.slot.await() }.getOrNull()?.resource
            is LoadAction.StartLoad -> claim.gen
        }

        // 我是本 name 的加载者。try/finally：无论成功、失败还是被取消，
        // 自己的槽位必被摘除、等待者必被唤醒，句柄必被"发布"或"就地 close"二选一。
        var outcome = LoadOutcome(null, genAtStart)
        try {
            val res = withContext(Dispatchers.IO) {
                val loaded = runCatching {
                    AudioResource.load(name = name, path = path, loadType = LoadType.FROM_ASSETS)
                }.onSuccess {
                    Log.i(TAG, "audio resource loaded: $name")
                }.onFailure {
                    Log.e(TAG, "audio resource load failed: $name — ${it.message}", it)
                }.getOrNull()
                // 发布裁决（与 releaseAll 互斥、在 load 返回后的第一个同步点完成，中间无挂起点）：
                val keep = synchronized(lock) {
                    if (loaded != null && generation == genAtStart && !resources.containsKey(name)) {
                        resources[name] = loaded
                        true
                    } else {
                        false
                    }
                }
                if (!keep && loaded != null) {
                    // releaseAll 插队（或同名并发已发布）：本句柄无归属，就地 close，绝不成为无主句柄。
                    Log.i(TAG, "resource($name) load finished after releaseAll/dup — closing unowned handle")
                    runCatching { loaded.close() }
                        .onFailure { e -> Log.e(TAG, "unowned close failed: $name — ${e.message}", e) }
                }
                LoadOutcome(if (keep) loaded else null, genAtStart)
            }
            outcome = res
        } catch (t: Throwable) {
            Log.e(TAG, "audio resource load crashed: $name — ${t.message}", t)
        } finally {
            synchronized(lock) {
                // 只摘属于自己的槽位；releaseAll 抢先 clear 过也无害（remove 不存在的键是 no-op）。
                if (loading[name] === mine) loading.remove(name)
            }
            if (!mine.isCompleted) mine.complete(outcome)
        }
        return outcome.resource
    }

    /** 认领结果：复用已发布 / 等待在途 / 由我加载（[gen] 为登记在途时的代次快照）。 */
    private sealed interface LoadAction {
        class UsePublished(val resource: AudioResource) : LoadAction
        class JoinInflight(val slot: CompletableDeferred<LoadOutcome>) : LoadAction
        class StartLoad(val gen: Long) : LoadAction
    }

    /**
     * 在锚定实体上装空间音源。挂 ObjectAudioComponent 并 prepare，
     * 之后 controller.play() 即可从该实体位置发声。
     *
     * @param entity 锚定卡片实体（AnchorEntity 或带 Transform 的普通 Entity）
     */
    suspend fun attach(entity: Entity): AudioPlayerController? {
        val res = runCatching { resource(CUE_DUE) }.getOrNull() ?: return null
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
        val res = runCatching { resource(cue) }.getOrNull() ?: return
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

    /**
     * Stage 销毁时释放全部原生资源。多次进出 Full Space 会重复调用，必须幂等。
     *
     * P3 语义收紧：
     *  1. 先自增 [generation] 再 close —— 从这一刻起，任何在途 load 的发布裁决都会失败并自弃，
     *     "release 只作用于其之前创建的句柄"由此保证；
     *  2. 唤醒（complete(null)）被清掉的在途槽位，避免旧 Stage 的调用方永远 await 一个死槽；
     *  3. close 全程 runCatching，一个句柄 close 失败不影响其余释放。
     */
    fun releaseAll() {
        val toClose: List<AudioResource>
        val staleLoads: List<CompletableDeferred<LoadOutcome>>
        val gen: Long
        synchronized(lock) {
            gen = ++generation
            toClose = resources.values.toList()
            staleLoads = loading.values.toList()
            resources.clear()
            loading.clear()
            entityByCard.clear()
        }
        // 死槽唤醒放在锁外：complete 可能就地恢复等待协程，别把它们拽进临界区。
        // 用空结果（resource=null）唤醒旧代次的在途 await：调用方拿 null 即安静跳过，
        // 这些等待者本身不持有任何句柄（有句柄的加载线程会在发布点自 close），无泄漏。
        staleLoads.forEach { if (!it.isCompleted) it.complete(LoadOutcome(null, gen - 1)) }
        toClose.forEach {
            runCatching { it.close() }.onFailure { e -> Log.w(TAG, "close failed: ${e.message}") }
        }
        Log.i(TAG, "all audio resources released: closed=${toClose.size} staleLoads=${staleLoads.size} gen=$gen")
    }
}
