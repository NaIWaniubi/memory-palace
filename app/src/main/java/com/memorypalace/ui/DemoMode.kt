package com.memorypalace.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 演示模式（M15）。
 *
 * 为什么要有：应用里的双态探针（实例号、计数器、事件日志）和无头驱动提示
 * （"adb shell am broadcast ..."）是**开发期的验证工具**，不是产品界面。
 * 但默认打开应用时它们全都在屏上，评委第一眼看到的是 `adb shell am broadcast`
 * 和 "切换后归零=状态丢失" —— 像未完成的测试脚手架，不像产品。
 *
 * 所以分两种形态：
 *   - 演示模式开（默认）：只留产品内容 —— 标题、到期队列、时间机器、复习卡
 *   - 演示模式关：探针全部回来，供开发和实测取证使用
 *
 * 注意：关掉演示模式后，双态探针的实例号/事件日志依然会写 logcat，
 * 所以《双态行为实测结论》的证据链不受影响 —— 只是屏上不显示。
 */
object DemoMode {

    /** 默认开：评委/观众看到的第一帧就是干净的产品界面。 */
    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun toggle() {
        _enabled.value = !_enabled.value
    }

    fun set(value: Boolean) {
        _enabled.value = value
    }
}