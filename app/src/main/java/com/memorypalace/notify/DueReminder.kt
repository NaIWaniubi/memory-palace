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
            // P2 中危：onReceive 立即返回，postDue 里的 Room 查询/发通知跑在游离协程，
            // 进程被回收则提醒静默丢失。用 goAsync 持住广播直到通知发完。
            val pending = goAsync()
            scope.launch {
                try {
                    postDue(context)
                } finally {
                    pending.finish()
                }
            }
        }
    }

    /**
     * 系统事件重排接收器（P2 高危：闹钟不跨重启 / 时区、时间跳变）。
     *
     * AlarmManager 闹钟不随设备重启存活；用户手改系统时间、跨时区出行也会让
     * RTC_WAKEUP 绝对时刻错位。开机、时间改变、时区改变后都要按真实到期时间重排。
     *
     * 需在 `app/src/main/AndroidManifest.xml` 的 `<application>` 内静态注册（此文件不在本次
     * 可改范围，故这里只实现逻辑，注册项见交付说明）：
     * ```xml
     * <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
     * <receiver android:name=".notify.DueReminder$ReminderReceiver" android:exported="false">
     *   <intent-filter>
     *     <action android:name="android.intent.action.BOOT_COMPLETED" />
     *     <action android:name="android.intent.action.LOCKED_BOOT_COMPLETED" />
     *     <action android:name="android.intent.action.TIME_SET" />
     *     <action android:name="android.intent.action.TIMEZONE_CHANGED" />
     *   </intent-filter>
     * </receiver>
     * ```
     * 声明后进程会在这些事件被拉起，Application.onCreate（含 DataGraph.init）先于 onReceive 完成，
     * 因此这里可直接调用 [schedule]。
     */
    class ReminderReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.i(TAG, "system event ${intent.action} → rescheduling reminder")
            val pending = goAsync()
            scope.launch {
                try {
                    schedule(context.applicationContext)
                } catch (@Suppress("TooGenericExceptionCaught") t: Throwable) {
                    Log.e(TAG, "reschedule after ${intent.action} failed", t)
                } finally {
                    pending.finish()
                }
            }
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
            com.memorypalace.debugReceiverFlag,
        )
        Log.i(TAG, "reminder broadcast receiver registered")
    }

    /**
     * 按当前到期队列重排提醒。
     * 队列为空就取消提醒（没有到期卡片就不该响）。
     *
     * P2 高危修正：提醒**时刻**是交给 AlarmManager RTC_WAKEUP 的绝对时间，一律用真实时钟
     * ([Clock.nowReal]) 与卡片存储的真实 dueAt 计算，绝不含时间机器的演示偏移。演示快进只
     * 影响 UI 上的到期/褪色视图，不会让真闹钟提前/滞后。决策逻辑抽到纯函数 [decideReminder]。
     */
    suspend fun schedule(context: Context) {
        val cards = DataGraph.repository.cards.first()
        val realNow = Clock.nowReal()
        val dueCount = cards.count { it.dueAt <= realNow }
        when (val plan = decideReminder(cards.map { it.dueAt }, realNow, IMMEDIATE_DELAY_MILLIS)) {
            is ReminderPlan.Cancel -> {
                cancel(context)
                Log.i(TAG, "no card at all, reminder cancelled")
            }
            is ReminderPlan.Fire -> {
                setAlarm(context, plan.fireAt, realNow)
                Log.i(
                    TAG,
                    "reminder ${plan.mode}: fireAt=${plan.fireAt} (${(plan.fireAt - realNow) / 1000}s from now) " +
                        "cards=${cards.size} dueCount=$dueCount",
                )
            }
        }
    }

    private fun setAlarm(context: Context, fireAt: Long, realNow: Long) {
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
        // 提醒时刻必须在未来，否则 setExact 会立刻触发。fireAt 已是真实绝对时刻，
        // 兜底也只按真实时钟抬到 realNow+1s，绝不掺入演示偏移（P2：偏移污染 RTC_WAKEUP）。
        val target = maxOf(fireAt, realNow + 1000)

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

// ---------- 纯决策（真实时钟语义，JVM 可单测；见 schedule） ----------

/** 到期提醒的调度裁决。 */
internal sealed class ReminderPlan {
    /** 在真实绝对时刻 [fireAt] 响一次；[mode] 仅用于日志（scheduled / overdue-immediate）。 */
    data class Fire(val fireAt: Long, val mode: String) : ReminderPlan()

    /** 无任何到期卡 → 取消提醒。 */
    object Cancel : ReminderPlan()
}

/**
 * 依据卡片存储的**真实** dueAt 与真实当前时刻，决定闹钟落点。
 *
 * 语义（对应原注释里的两个坑，保持不变，只是全部走真实时间）：
 *  - 有未来到期的卡 → 最近的那个真实 dueAt 落一次（"到点了"）
 *  - 没有未来、但有已逾期 → realNow + immediateDelayMs 落一次（"你有 N 张正在褪色"）
 *  - 一张卡都没有 → 取消
 *
 * 这里不掺入任何演示偏移：dueAts 已是真实时间（[com.memorypalace.data.CardRepository] 用
 * [com.memorypalace.data.Clock.nowReal] 落库），realNow 也是真实时间，RTC_WAKEUP 直接可用，
 * 进程重启 / 时间机器快进都不会让落点错位（P2 高危 Clock 污染项的根治点）。
 */
internal fun decideReminder(dueAts: List<Long>, realNow: Long, immediateDelayMs: Long): ReminderPlan {
    val nextFuture = dueAts.filter { it > realNow }.minOrNull()
    return when {
        nextFuture != null -> ReminderPlan.Fire(nextFuture, "scheduled")
        dueAts.any { it <= realNow } -> ReminderPlan.Fire(realNow + immediateDelayMs, "overdue-immediate")
        else -> ReminderPlan.Cancel
    }
}
