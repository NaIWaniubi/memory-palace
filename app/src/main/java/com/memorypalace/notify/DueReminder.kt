package com.memorypalace.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.memorypalace.data.Clock
import com.memorypalace.data.DataGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 到期提醒调度（M14）。
 *
 * 调度时刻 = 当天最后一个到期卡片的到期时间。理由：头显的使用场景是
 * "坐下来复习一段时间"，在最后一张卡到期的时刻提醒一次，比每张卡各响一次
 * 更符合使用节奏（连续响会烦）。
 *
 * 闹钟类型：
 *   - 精确闹钟（setExactAndAllowWhileIdle）：复习提醒要卡在具体时刻。
 *     但 Android 12+ 需要 SCHEDULE_EXACT_ALARM 权限，且用户可撤销。
 *   - 退路：权限不可用时用 setAndAllowWhileIdle（不精确，可能延后），
 *     并在日志里明确记录降级 —— 提醒晚点到总比没有强，但不能假装它是精确的。
 */
object DueReminder {

    const val TAG = "DueReminder"

    const val ACTION_FIRE = "com.memorypalace.notify.FIRE"
    const val ACTION_SCHEDULE = "com.memorypalace.notify.SCHEDULE"
    const val ACTION_CANCEL = "com.memorypalace.notify.CANCEL"
    const val ACTION_TEST = "com.memorypalace.notify.TEST"

    private const val REQUEST_CODE = 2001

    /**
     * 全员逾期时的提醒延迟。给一点延迟而不是 0 —— 让用户先把头戴上，
     * 别在还没进入应用时弹一条通知。
     */
    private const val IMMEDIATE_DELAY_MILLIS = 5_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * 定时触发后真正发通知的接收器。
     * 单独一个类而不是匿名对象：AlarmManager 的 PendingIntent 广播在应用
     * 进程已被杀时也要能被系统拉起来，必须是 manifest 里声明的静态组件。
     */
    class FireReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.i(TAG, "alarm fired, posting due notification")
            scope.launch { postDue(context) }
        }
    }

    /**
     * 静态接收器：处理"重排提醒"与"取消提醒"的广播。
     * SCHEDULE/CANCEL 是应用内行为，可以走注册式接收器（省电，且不暴露给外部）。
     */
    fun register(context: Context) {
        val filter = IntentFilter().apply {
            addAction(ACTION_SCHEDULE)
            addAction(ACTION_CANCEL)
            addAction(ACTION_TEST)
        }
        ContextCompat.registerReceiver(
            context.applicationContext,
            object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    when (intent.action) {
                        ACTION_SCHEDULE -> scope.launch { schedule(ctx) }
                        ACTION_CANCEL -> cancel(ctx)
                        ACTION_TEST -> scope.launch { postDue(ctx) }
                    }
                }
            },
            filter,
            ContextCompat.RECEIVER_EXPORTED,
        )
        Log.i(TAG, "reminder broadcast receiver registered")
    }

    /**
     * 按当前到期队列重排提醒。
     * 队列为空就取消提醒（没有到期卡片就不该响）。
     */
    suspend fun schedule(context: Context) {
        val cards = DataGraph.repository.cards.first()
        val due = DataGraph.repository.dueQueue(cards)
        if (due.isEmpty()) {
            cancel(context)
            Log.i(TAG, "no due card, reminder cancelled")
            return
        }

        val now = Clock.now()
        // 提醒时刻的语义（这里踩过坑）：
        // 到期队列里的卡 dueAt 可能全在**过去** —— 用户已经拖了几天没复习。
        // 这时"最后一张卡"的到期时刻是过去时间，setExact 会立刻触发，
        // 变成"刚排上就响"的空提醒，而且每次重排都重复发生。
        //
        // 正确语义分两种情况：
        //  1. 还有**未来**到期的卡 → 提醒设在那个时刻（"到点了，该复习了"）
        //  2. 全部已经逾期 → 已经没有"未来的时刻"可等，立刻提醒一次
        //     （"你有 N 张卡正在褪色，现在就来看"）
        val future = due.filter { it.dueAt > now }
        val fireAt: Long
        val mode: String
        if (future.isNotEmpty()) {
            fireAt = future.maxOf { it.dueAt }
            mode = "scheduled"
        } else {
            fireAt = now + IMMEDIATE_DELAY_MILLIS
            mode = "overdue-immediate"
        }

        setAlarm(context, fireAt)
        Log.i(
            TAG,
            "reminder $mode: fireAt=$fireAt (${(fireAt - now) / 1000}s from now) " +
                "dueCount=${due.size} futureCount=${future.size} top=${due.first().front}"
        )
    }

    private fun setAlarm(context: Context, fireAt: Long) {
        val am = context.getSystemService(AlarmManager::class.java) ?: run {
            Log.w(TAG, "AlarmManager unavailable")
            return
        }
        val pi = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, FireReceiver::class.java).setAction(ACTION_FIRE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // 提醒时刻必须在未来，否则 setExact 会立刻触发
        val target = maxOf(fireAt, Clock.now() + 1000)

        val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            am.canScheduleExactAlarms()
        } else {
            true
        }
        if (!canExact) {
            // 精确闹钟不可用：用户在系统设置里关掉了，或 PICO OS 未授予。
            // 不精确闹钟会被系统批量延后（可能延几十分钟）——对"该复习了"
            // 这种提醒来说延迟可接受，但必须让用户知道去开权限。
            // 面板上给出指引（见书房面板的权限提示），并在日志写明。
            Log.w(TAG, "exact alarm unavailable — 提醒可能被系统延后。" +
                "引导用户到系统设置开启「闹钟和提醒」权限（SCHEDULE_EXACT_ALARM）")
        }

        runCatching {
            if (canExact) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, target, pi)
                Log.i(TAG, "exact alarm set at $target")
            } else {
                // 降级：不精确，可能延后。日志写明，不假装精确。
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, target, pi)
                Log.w(TAG, "exact alarm not permitted, fell back to inexact alarm at $target")
            }
        }.onFailure {
            Log.e(TAG, "setAlarm failed: ${it.message}", it)
        }
    }

    /** 真正发通知：取当前到期队列的发数与首卡内容。 */
    suspend fun postDue(context: Context) {
        val cards = DataGraph.repository.cards.first()
        val due = DataGraph.repository.dueQueue(cards)
        if (due.isEmpty()) {
            DueNotifier.cancel(context)
            Log.i(TAG, "fire but queue empty, nothing to notify")
            return
        }
        DueNotifier.notifyDue(context, due.size, due.first().front)
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, FireReceiver::class.java).setAction(ACTION_FIRE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        am.cancel(pi)
        DueNotifier.cancel(context)
        Log.i(TAG, "reminder cancelled")
    }

    /**
     * 精确闹钟是否可用。UI 用它提示用户去开权限 ——
     * 没有精确闹钟，系统会把提醒延后到不确定的时刻，用户以为功能坏了。
     */
    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = context.getSystemService(AlarmManager::class.java) ?: return false
        return am.canScheduleExactAlarms()
    }
}
