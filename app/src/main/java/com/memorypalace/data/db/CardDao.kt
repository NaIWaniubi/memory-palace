package com.memorypalace.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface CardDao {

    @Query("SELECT * FROM cards")
    fun observeAll(): Flow<List<CardEntity>>

    @Query("SELECT * FROM cards")
    suspend fun all(): List<CardEntity>

    @Query("SELECT * FROM cards WHERE id = :id")
    suspend fun byId(id: String): CardEntity?

    /** 只投影 front 列：导入查重用，避免把整表实体 + 全部列拉回内存再 map（P3 O(n²) 项）。 */
    @Query("SELECT front FROM cards")
    suspend fun allFronts(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(card: CardEntity)

    /**
     * 批量插入：整个 List 在**一个事务**里写完（P3「导入 O(n²)」的直接修法）。
     *
     * before：导入 N 张卡 = N 次独立 `upsert` = N 次事务/fsync + N 次 `observeAll()`
     *         全表 Flow 重发（每个下游再 O(全表) 重算 → O(N×表) 总开销）。
     * after ：每分块 1 次事务、1 次 Flow 重发（千张卡按分块 500 → 2 次事务/2 次重发）。
     *
     * 显式 @Transaction 保证 Room 把多条目插入包成单事务（一次 WAL fsync），
     * 中途失败整体回滚，不留"半套牌组"（P2 健壮性条目一并解决）。
     */
    @Transaction
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(cards: List<CardEntity>)

    @Query("DELETE FROM cards WHERE id = :id")
    suspend fun delete(id: String)
}
