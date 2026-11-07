package com.memorypalace.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.room.Room
import com.memorypalace.core.schedule.Grade
import com.memorypalace.data.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 数据层装配：Room + 仓库单例 + 无头驱动命令（模拟器验证用）。
 *
 *   adb shell am broadcast -a com.memorypalace.card.ADD --es front "serendipity" --es back "n.意外之喜"
 *   adb shell am broadcast -a com.memorypalace.card.REVIEW --es id <id前缀> --es grade remember|forgot
 */
object DataGraph {

    const val TAG = "DataGraph"
    const val ACTION_ADD = "com.memorypalace.card.ADD"
    const val ACTION_REVIEW = "com.memorypalace.card.REVIEW"

    lateinit var repository: CardRepository
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(context: Context) {
        val db = Room.databaseBuilder(
            context.applicationContext, AppDatabase::class.java, "memory-palace.db"
        ).build()
        repository = CardRepository(db.cardDao())
        Log.i(TAG, "room database ready")

        val filter = IntentFilter().apply {
            addAction(ACTION_ADD)
            addAction(ACTION_REVIEW)
        }
        ContextCompat.registerReceiver(
            context.applicationContext,
            object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    when (intent.action) {
                        ACTION_ADD -> {
                            val front = intent.getStringExtra("front") ?: return
                            val back = intent.getStringExtra("back") ?: ""
                            scope.launch { repository.add(front, back) }
                        }
                        ACTION_REVIEW -> {
                            val idPrefix = intent.getStringExtra("id") ?: return
                            val grade = if (intent.getStringExtra("grade") == "forgot") {
                                Grade.FORGOTTEN
                            } else {
                                Grade.REMEMBERED
                            }
                            scope.launch { repository.reviewByPrefix(idPrefix, grade) }
                        }
                    }
                }
            },
            filter,
            ContextCompat.RECEIVER_EXPORTED,
        )
        Log.i(TAG, "card command receiver registered")
    }
}
