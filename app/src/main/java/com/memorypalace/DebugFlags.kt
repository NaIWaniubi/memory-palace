package com.memorypalace

import androidx.core.content.ContextCompat

/**
 * 调试广播接收器的导出标志。
 *
 * debug 版导出 —— adb 广播是我们唯一的无头测试通道（模拟器驱不了空间按钮），
 * 必须能收到。
 * release 版**不导出** —— 否则同设备任意应用都能发广播改卡、改时钟、清空锚点、
 * 让应用以自己身份读沙盒文件。之前 8 个接收器全部硬编码 RECEIVER_EXPORTED，
 * 只在注释里写「release 版移除」，但 release 只关了 minify，并不会移除。
 */
val debugReceiverFlag: Int
    get() = if (BuildConfig.DEBUG) {
        ContextCompat.RECEIVER_EXPORTED
    } else {
        ContextCompat.RECEIVER_NOT_EXPORTED
    }
