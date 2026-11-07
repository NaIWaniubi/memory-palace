package com.memorypalace.probe

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 双态生命周期探针。
 *
 * 判定逻辑：每个窗口/Stage 内容组合首次进入组合时调用 [claim] 领取实例号。
 * 若 Shared <-> Full Space 切换后同一组件实例号不变、且只收到 PAUSE/RESUME，
 * 说明窗口存活；若收到 DESTROY/CREATE 且实例号递增，说明被销毁重建。
 */
object DualStateProbe {
    const val TAG = "DualState"

    private val counters = ConcurrentHashMap<String, AtomicInteger>()
    private val _events = MutableStateFlow<List<String>>(emptyList())
    val events: StateFlow<List<String>> = _events

    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun claim(component: String): Int {
        val id = counters.getOrPut(component) { AtomicInteger(0) }.incrementAndGet()
        log(component, "CLAIM instance#$id")
        return id
    }

    fun log(component: String, event: String) {
        val line = "${fmt.format(Date())} [$component] $event"
        Log.i(TAG, line)
        _events.value = (_events.value + line).takeLast(60)
    }
}
