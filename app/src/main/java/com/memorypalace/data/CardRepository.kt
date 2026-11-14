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

/**
 * 卡片仓库：CRUD + 复习调度 + 到期队列。
 *
 * 时间语义（P2 高危：Clock 偏移曾污染持久 dueAt）：
 *  - 落库时间（newCard/review 的 dueAt/createdAt/lastReviewedAt）一律用 [Clock.nowReal]
 *    真实时间，绝不带演示偏移——否则快进后重启，库里 dueAt 与真实时间线互相矛盾。
 *  - [dueQueue] 用 [Clock.now]（演示态）作注入点：时间机器把整屋"看起来"提前到期，
 *    是纯 UI 效果，不反写库。
 */
class CardRepository(private val dao: CardDao) {

    val cards: Flow<List<Card>> = dao.observeAll().map { list -> list.map(CardEntity::toModel) }

    suspend fun add(front: String, back: String): Card {
        val card = Scheduler.newCard(UUID.randomUUID().toString(), front, back, Clock.nowReal())
        dao.upsert(CardEntity.from(card))
        Log.i(TAG, "card added id=${card.id.take(8)} front=\"$front\" due=now")
        return card
    }

    /**
     * 批量建卡（P3「导入 O(n²)」修法）：一批 [front to back] 生成卡片实体后按 [BATCH_SIZE]
     * 分块走 `insertAll`，**每块一个事务**。
     *
     * 与逐条 [add] 的量化差异（导入 N 张、当前表大小 M）：
     *  - before：N 次单条 upsert = N 个事务（N 次 WAL fsync），且每次都触发 `observeAll()`
     *    全表 Flow 重发 → 下游（DueQueueView 的 remember(cards)/AnchorCard 全表收集）
     *    各重算 O(M+i)，总开销 ~O(N×M)；
     *  - after：⌈N/BATCH_SIZE⌉ 个事务、⌈N/BATCH_SIZE⌉ 次 Flow 重发。千张卡 = 2 个事务而非 1000 个。
     *
     * 分块（而非一口气一个巨型事务）：事务持有写锁的时长与失败回滚量都有界；
     * 单条 Room INSERT 参数固定 9 列，也远离 SQLite 变量数上限。
     */
    suspend fun addAll(entries: List<Pair<String, String>>): List<Card> {
        if (entries.isEmpty()) return emptyList()
        val now = Clock.nowReal()
        val cards = entries.map { (front, back) ->
            Scheduler.newCard(UUID.randomUUID().toString(), front, back, now)
        }
        cards.chunked(BATCH_SIZE).forEach { chunk ->
            dao.insertAll(chunk.map(CardEntity::from))
        }
        Log.i(TAG, "cards added in batch: n=${cards.size} batches=${(cards.size + BATCH_SIZE - 1) / BATCH_SIZE}")
        return cards
    }

    /** 导入查重用：只回 front 列（投影查询，不整表拉实体）。 */
    suspend fun existingFronts(): List<String> = dao.allFronts()

    suspend fun review(id: String, grade: Grade): Card? {
        val current = dao.byId(id)?.toModel() ?: return null
        val next = Scheduler.review(current, grade, Clock.nowReal())
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

        /** [addAll] 分块大小：千张 deck 导入 ≈ 2 个事务 / 2 次 Flow 重发，而不是 1000 个。 */
        const val BATCH_SIZE = 500
    }
}
