package com.memorypalace.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 全局时钟。调度器本身不依赖它（时间全部参数注入），它只作为应用层的"现在"来源。
 *
 * 时间语义严格分成两条，互不污染（P2 高危：偏移曾同时污染持久 dueAt 与 RTC_WAKEUP 绝对时刻）：
 *  - [nowReal]：未偏移的真实墙上时间。**所有落库的时间（dueAt/createdAt/lastReviewedAt）
 *    和所有交给 AlarmManager 的绝对时刻都用它**。这样时间机器快进/进程重启都不会让调度错位。
 *  - [now]：真实时间 + [offsetMillis] 的"演示态"。**只用于纯 UI 视角**（到期队列注入、
 *    褪色程度计算），绝不写库、绝不喂闹钟。M6 时间机器调它让整屋瞬时"看起来"到期。
 *
 * 偏移本身持久化（[register] 时恢复），这样时间机器快进后进程被杀重启，演示态不漂移、
 * 也不会把带偏移的时间当成真实时间写回库。
 *
 * 无头驱动（M4 起用于验证褪色外观，M6 正式成为时间机器）：
 *   adb shell am broadcast -a com.memorypalace.clock.SET_OFFSET --el days 7
 *   adb shell am broadcast -a com.memorypalace.clock.SET_OFFSET --el days 0   # 复位
 */
object Clock {

    const val TAG = "Clock"
    const val ACTION_SET_OFFSET = "com.memorypalace.clock.SET_OFFSET"

    private const val PREFS = "clock"
    private const val KEY_OFFSET = "offsetMillis"
    private const val DAY_MILLIS = 24L * 60 * 60 * 1000

    @Volatile
    var offsetMillis: Long = 0
        private set

    /** 偏移变化时自增，UI 收集它触发重算（到期状态 / 褪色程度）。 */
    val tick = MutableStateFlow(0)

    /** 真实墙上时间：落库时间、AlarmManager 绝对时刻一律用它。 */
    fun nowReal(): Long = System.currentTimeMillis()

    /** 演示态时间：真实 + 偏移。仅供 UI 到期/褪色注入，不写库、不进闹钟。 */
    fun now(): Long = nowReal() + offsetMillis

    /** 当前偏移（天），供 UI 展示。 */
    fun offsetDays(): Long = offsetMillis / DAY_MILLIS

    /** M6 时间机器：设置绝对偏移（天）。 */
    fun setOffsetDays(days: Long) {
        offsetMillis = days * DAY_MILLIS
        tick.value += 1
        Log.i(TAG, "demo offset set to ${days}d (${offsetMillis}ms), now=${now()} realNow=${nowReal()}")
    }

    /** M6 时间机器：在现有偏移上累加（天）。 */
    fun addDays(days: Long) = setOffsetDays(offsetDays() + days)

    fun register(context: Context) {
        // 恢复上次持久化的演示偏移（真实到期时间不受此影响，见类注释）。
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        offsetMillis = prefs.getLong(KEY_OFFSET, 0L)
        if (offsetMillis != 0L) {
            tick.value += 1
            Log.i(TAG, "restored demo offset=${offsetMillis}ms (=${offsetDays()}d)")
        }

        val filter = IntentFilter(ACTION_SET_OFFSET)
        ContextCompat.registerReceiver(
            context.applicationContext,
            object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    setOffsetDays(intent.getLongExtra("days", 0L))
                    // 偏移变化持久化，重启后演示态不漂移（真实到期/闹钟时刻本就不含偏移）。
                    prefs.edit().putLong(KEY_OFFSET, offsetMillis).apply()
                }
            },
            filter,
            com.memorypalace.debugReceiverFlag,
        )
        Log.i(TAG, "clock receiver registered")
    }
}
