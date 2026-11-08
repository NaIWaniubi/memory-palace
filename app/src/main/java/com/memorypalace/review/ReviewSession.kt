package com.memorypalace.review

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import com.memorypalace.audio.SpatialCue
import com.memorypalace.core.model.Card
import com.memorypalace.core.schedule.Grade
import com.memorypalace.data.DataGraph
import com.memorypalace.notify.DueReminder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 复习会话（M4）。状态在组合之外的单例里 —— Stage/窗口重建不丢进度。
 *
 * 流程：start() 取当日到期队列 → 逐卡 翻面 → 自评（记得/忘了）→ 调度更新 → 下一卡，
 * 队列走完自动结束。模拟器无空间交互，全部操作可 adb 广播无头驱动：
 *
 *   adb shell am broadcast -a com.memorypalace.review.START
 *   adb shell am broadcast -a com.memorypalace.review.REVEAL
 *   adb shell am broadcast -a com.memorypalace.review.GRADE --es grade remember|forgot
 *   adb shell am broadcast -a com.memorypalace.review.EXIT
 */
object ReviewSession {

    const val TAG = "Review"
    const val ACTION_START = "com.memorypalace.review.START"
    const val ACTION_REVEAL = "com.memorypalace.review.REVEAL"
    const val ACTION_GRADE = "com.memorypalace.review.GRADE"
    const val ACTION_EXIT = "com.memorypalace.review.EXIT"

    data class State(
        val queue: List<Card>,
        val index: Int,
        val revealed: Boolean,
    ) {
        val current: Card? get() = queue.getOrNull(index)
        val total: Int get() = queue.size
    }

    /** null = 未在复习中。 */
    val state = MutableStateFlow<State?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 应用上下文：复习完成后要撤掉到期提醒（M14）。 */
    @Volatile
    private var appContext: Context? = null

    fun register(context: Context) {
        appContext = context.applicationContext
        val filter = IntentFilter().apply {
            addAction(ACTION_START)
            addAction(ACTION_REVEAL)
            addAction(ACTION_GRADE)
            addAction(ACTION_EXIT)
        }
        ContextCompat.registerReceiver(
            context.applicationContext,
            object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    when (intent.action) {
                        ACTION_START -> start()
                        ACTION_REVEAL -> reveal()
                        ACTION_GRADE -> grade(
                            if (intent.getStringExtra("grade") == "forgot") Grade.FORGOTTEN
                            else Grade.REMEMBERED
                        )
                        ACTION_EXIT -> exit()
                    }
                }
            },
            filter,
            ContextCompat.RECEIVER_EXPORTED,
        )
        Log.i(TAG, "review session receiver registered")
    }

    fun start() {
        scope.launch {
            val cards = DataGraph.repository.cards.first()
            val due = DataGraph.repository.dueQueue(cards)
            if (due.isEmpty()) {
                state.value = null
                Log.i(TAG, "start: due queue empty, nothing to review")
            } else {
                state.value = State(queue = due, index = 0, revealed = false)
                Log.i(TAG, "start: ${due.size} card(s) due, first=${due[0].front} id=${due[0].id.take(8)}")
            }
        }
    }

    /** 单卡复习（演示路径修复）：从锚定卡片直接发起，queue 只含这一张。 */
    fun startSingle(cardId: String) {
        scope.launch {
            val card = DataGraph.repository.cards.first().firstOrNull { it.id == cardId }
            if (card == null) {
                Log.w(TAG, "startSingle: card $cardId not found")
                return@launch
            }
            state.value = State(queue = listOf(card), index = 0, revealed = false)
            Log.i(TAG, "startSingle: ${card.front} id=${card.id.take(8)}")
        }
    }

    fun reveal() {
        val s = state.value ?: return
        state.value = s.copy(revealed = true)
        Log.i(TAG, "reveal: ${s.current?.front} back=${s.current?.back}")
    }

    fun grade(grade: Grade) {
        scope.launch {
            val s = state.value ?: return@launch
            val card = s.current ?: return@launch
            DataGraph.repository.review(card.id, grade)
            Log.i(
                TAG,
                "graded: ${card.front} grade=$grade (${s.index + 1}/${s.total})"
            )
            val next = s.index + 1
            state.value = if (next >= s.total) {
                Log.i(TAG, "session complete: ${s.total} card(s) reviewed")
                // M13：完成音从最后一张卡的锚点位置发出（没有锚点就退化为无声）
                SpatialCue.entityOf(card.id)?.let { entity ->
                    scope.launch { SpatialCue.play(entity, SpatialCue.CUE_DONE) }
                }
                // M14：复习完了就撤掉"该复习了"的提醒，别让它继续挂着
                appContext?.let { DueReminder.cancel(it) }
                null
            } else {
                s.copy(index = next, revealed = false)
            }
        }
    }

    fun exit() {
        if (state.value != null) Log.i(TAG, "session exited at ${state.value!!.index + 1}/${state.value!!.total}")
        state.value = null
    }
}
