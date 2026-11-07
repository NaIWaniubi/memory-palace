package com.memorypalace.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.memorypalace.core.model.Card

@Entity(tableName = "cards")
data class CardEntity(
    @PrimaryKey val id: String,
    val front: String,
    val back: String,
    val repetitions: Int,
    val easiness: Double,
    val intervalDays: Double,
    val dueAt: Long,
    val lastReviewedAt: Long?,
    val createdAt: Long,
) {
    fun toModel() = Card(id, front, back, repetitions, easiness, intervalDays, dueAt, lastReviewedAt, createdAt)

    companion object {
        fun from(c: Card) = CardEntity(
            id = c.id, front = c.front, back = c.back,
            repetitions = c.repetitions, easiness = c.easiness, intervalDays = c.intervalDays,
            dueAt = c.dueAt, lastReviewedAt = c.lastReviewedAt, createdAt = c.createdAt,
        )
    }
}
