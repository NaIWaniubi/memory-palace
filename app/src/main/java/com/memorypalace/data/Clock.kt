package com.memorypalace.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 全局时钟。调度器本身不依赖它（时间全部参数注入），
 * 它只作为应用层的"现在"来源 —— M6 时间机器通过 offsetMillis 快进。
 *
 * 无头驱动（M4 起用于验证褪色外观，M6 正式成为时间机器）：
 *   adb shell am broadcast -a com.memorypalace.clock.SET_OFFSET --el days 7
 *   adb shell am broadcast -a com.memorypalace.clock.SET_OFFSET --el days 0   # 复位
 */
object Clock {

    const val TAG = "Clock"
    const val ACTION_SET_OFFSET = "com.memorypalace.clock.SET_OFFSET"

    @Volatile
    var offsetMillis: Long = 0
        private set

    /** 偏移变化时自增，UI 收集它触发重算（到期状态 / 褪色程度）。 */
    val tick = MutableStateFlow(0)

    fun now(): Long = System.currentTimeMillis() + offsetMillis

    /** 当前偏移（天），供 UI 展示。 */
    fun offsetDays(): Long = offsetMillis / (24L * 60 * 60 * 1000)

    /** M6 时间机器：设置绝对偏移（天）。 */
    fun setOffsetDays(days: Long) {
        offsetMillis = days * 24L * 60 * 60 * 1000
        tick.value += 1
        Log.i(TAG, "offset set to ${days}d (${offsetMillis}ms), now=${now()}")
    }

    /** M6 时间机器：在现有偏移上累加（天）。 */
    fun addDays(days: Long) = setOffsetDays(offsetDays() + days)

    fun register(context: Context) {
        val filter = IntentFilter(ACTION_SET_OFFSET)
        ContextCompat.registerReceiver(
            context.applicationContext,
            object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    setOffsetDays(intent.getLongExtra("days", 0L))
                }
            },
            filter,
            com.memorypalace.debugReceiverFlag,
        )
        Log.i(TAG, "clock receiver registered")
    }
}
