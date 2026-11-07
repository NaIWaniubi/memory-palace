package com.memorypalace.core.model

/**
 * 知识卡（纯数据，零 Android / SDK 依赖，可 JVM 单测）。
 * 调度字段内嵌：SM-2 变体的全部状态都长在卡片上，随卡片持久化。
 * 与空间锚定的绑定方向：AnchorRecord.key == Card.id（卡片不设回指字段）。
 */
data class Card(
    val id: String,
    val front: String,
    val back: String,
    val repetitions: Int,
    val easiness: Double,
    val intervalDays: Double,
    val dueAt: Long,
    val lastReviewedAt: Long?,
    val createdAt: Long,
)
