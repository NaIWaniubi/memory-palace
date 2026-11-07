package com.memorypalace.data

/**
 * 全局时钟。调度器本身不依赖它（时间全部参数注入），
 * 它只作为应用层的"现在"来源 —— M6 时间机器通过 offsetMillis 快进。
 */
object Clock {
    @Volatile
    var offsetMillis: Long = 0

    fun now(): Long = System.currentTimeMillis() + offsetMillis
}
