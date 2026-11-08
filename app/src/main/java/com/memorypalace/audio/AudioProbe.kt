package com.memorypalace.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import com.memorypalace.anchor.AnchorScene
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 空间提示音的无头驱动入口（M13）。
 *
 * 模拟器驱动不了 3D 空间按钮，所以提示音的验证路径是广播：
 *
 *   adb shell am broadcast -a com.memorypalace.audio.PLAY --es cue cue_due --es card <卡片ID前缀>
 *   adb shell am broadcast -a com.memorypalace.audio.PLAY --es cue cue_place
 *   adb shell am broadcast -a com.memorypalace.audio.PLAY --es cue cue_done
 *   adb shell am broadcast -a com.memorypalace.audio.LIST
 *
 * 不带 --es card 时：对第一张锚定卡片发声（没有 card 就退化为日志说明原因）。
 * LIST 打印已加载的音频资源与已登记的卡片实体数，作为"资源加载成功"的取证。
 */
object AudioProbe {

    const val TAG = "AudioProbe"
    const val ACTION_PLAY = "com.memorypalace.audio.PLAY"
    const val ACTION_LIST = "com.memorypalace.audio.LIST"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun register(context: Context) {
        val filter = IntentFilter().apply {
            addAction(ACTION_PLAY)
            addAction(ACTION_LIST)
        }
        ContextCompat.registerReceiver(
            context.applicationContext,
            object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    when (intent.action) {
                        ACTION_PLAY -> play(intent.getStringExtra("cue"), intent.getStringExtra("card"))
                        ACTION_LIST -> list()
                    }
                }
            },
            filter,
            ContextCompat.RECEIVER_EXPORTED,
        )
        Log.i(TAG, "audio broadcast receiver registered")
    }

    private fun play(cue: String?, cardPrefix: String?) {
        val name = cue ?: SpatialCue.CUE_DUE
        scope.launch {
            val target = resolveTarget(cardPrefix)
            if (target == null) {
                Log.w(TAG, "play($name): no anchored card to attach audio to")
                return@launch
            }
            SpatialCue.play(target, name)
        }
    }

    /** 解析目标卡片实体：指定前缀则按前缀匹配，否则取第一张锚定卡片。 */
    private suspend fun resolveTarget(cardPrefix: String?): com.pico.spatial.core.ecs.Entity? {
        val records = AnchorScene.records.value
        if (records.isEmpty()) return null
        val rec = if (cardPrefix != null) {
            records.firstOrNull { it.key.startsWith(cardPrefix) }
        } else {
            records.first()
        }
        return rec?.let { SpatialCue.entityOf(it.key) }
    }

    private fun list() {
        Log.i(
            TAG,
            "cues=${listOf(SpatialCue.CUE_DUE, SpatialCue.CUE_PLACE, SpatialCue.CUE_DONE).joinToString(",")} " +
                "anchored=${AnchorScene.records.value.size} " +
                "entities=${AnchorScene.records.value.count { SpatialCue.entityOf(it.key) != null }}"
        )
    }
}
