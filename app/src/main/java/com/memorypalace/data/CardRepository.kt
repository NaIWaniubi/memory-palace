package com.memorypalace.data

import android.util.Log
import com.memorypalace.core.model.Card
import com.memorypalace.core.schedule.Grade
import com.memorypalace.core.schedule.Scheduler
import com.memorypalace.data.db.CardDao
import com.memorypalace.data.db.CardEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/** 卡片仓库：CRUD + 复习调度 + 到期队列。 */
class CardRepository(private val dao: CardDao) {

    val cards: Flow<List<Card>> = dao.observeAll().map { list -> list.map(CardEntity::toModel) }

    suspend fun add(front: String, back: String): Card {
        val card = Scheduler.newCard(UUID.randomUUID().toString(), front, back, Clock.now())
        dao.upsert(CardEntity.from(card))
        Log.i(TAG, "card added id=${card.id.take(8)} front=\"$front\" due=now")
        return card
    }

    suspend fun review(id: String, grade: Grade): Card? {
        val current = dao.byId(id)?.toModel() ?: return null
        val next = Scheduler.review(current, grade, Clock.now())
        dao.upsert(CardEntity.from(next))
        Log.i(
            TAG,
            "card reviewed id=${id.take(8)} grade=$grade reps=${next.repetitions} " +
                "ef=${"%.2f".format(next.easiness)} interval=${next.intervalDays}d"
        )
        return next
    }

    /** 广播驱动用：UUID 太长，允许前缀匹配。 */
    suspend fun reviewByPrefix(idPrefix: String, grade: Grade): Card? {
        val match = dao.all().firstOrNull { it.id.startsWith(idPrefix) } ?: run {
            Log.w(TAG, "review: no card matching prefix \"$idPrefix\"")
            return null
        }
        return review(match.id, grade)
    }

    suspend fun remove(id: String) = dao.delete(id)

    /** 当日到期队列（基于 Clock.now()，时间机器快进后自动变化）。 */
    fun dueQueue(cards: List<Card>): List<Card> = Scheduler.dueQueue(cards, Clock.now())

    companion object {
        const val TAG = "Cards"
    }
}
