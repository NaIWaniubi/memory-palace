package com.memorypalace.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.memorypalace.R
import com.memorypalace.app.LaunchActivity

/**
 * 头显本地通知（M14）。
 *
 * 这是"空间主动叫你回来"这个卖点的载体 —— 遗忘曲线算出今天该复习，
 * 到点了得有一声提醒，否则算法再准也只是一个后台数字。
 *
 * 为什么是"头显端本地通知"而不是"手机配套 App 推送"：
 * 用户戴着头显在房间里，没必要掏手机。PICO OS 6 支持应用在头显内发本地通知，
 * 通知直接出现在头显的系统通知面板。本项目不引入手机端 App ——
 * 那会引入账号体系、推送通道、网络依赖，与"离线可用"的核心承诺冲突。
 *
 * 触发方式：AlarmManager 精确闹钟 + BroadcastReceiver 落地通知。
 * 精确闹钟而非 WorkManager 周期任务，因为复习提醒要卡在具体时间点
 * （"晚上 8 点，该复习了"），周期任务会漂移到不确定的时刻。
 */
object DueNotifier {

    const val TAG = "DueNotifier"

    const val CHANNEL_ID = "mp_due_cards"
    const val CHANNEL_NAME = "复习提醒"

    const val EXTRA_COUNT = "count"
    const val EXTRA_TOP = "top"

    /** 通知 id 固定：同一时刻只保留一条提醒，多条会互相覆盖反而看不全。 */
    private const val NOTIFICATION_ID = 1001

    /**
     * 通道重要性：HIGH 才会有横幅弹出。用 IMPORTANCE_DEFAULT 的话通知只进
     * 面板不弹横幅 —— 对"该来复习了"这种提醒来说等于没发。
     */
    private const val CHANNEL_IMPORTANCE = NotificationManager.IMPORTANCE_HIGH

    /**
     * 建通知通道。Android 8.0 起必须先有通道才能发通知。
     * 幂等：重复创建同 id 通道不会报错。
     */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            CHANNEL_IMPORTANCE,
        ).apply {
            description = "遗忘曲线算出的复习到期提醒"
            enableVibration(true)
            setShowBadge(true)
        }
        nm.createNotificationChannel(channel)
        Log.i(TAG, "notification channel created")
    }

    /**
     * 发一条复习提醒。
     *
     * @param count 到期卡片数
     * @param top 第一张卡片的正面（通知里展示，点了就知道该复习什么）
     */
    fun notifyDue(context: Context, count: Int, top: String) {
        ensureChannel(context)
        val nm = context.getSystemService(NotificationManager::class.java) ?: run {
            Log.w(TAG, "NotificationManager unavailable")
            return
        }

        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, LaunchActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val body = if (count > 1) "$top 等 $count 张卡正在褪色" else "$top 正在褪色"

        val notification: Notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(if (count > 0) "$count 张卡该复习了" else "今天没有到期卡片")
            .setContentText(body)
            .setStyle(
                Notification.BigTextStyle().bigText(
                    if (count > 0) {
                        "你的房间里有 $count 张卡正在褪色。$body。戴上头显，走到它面前。"
                    } else {
                        "房间里的卡片都还记着，明天再来。"
                    }
                )
            )
            .setPriority(Notification.PRIORITY_HIGH)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()

        nm.notify(NOTIFICATION_ID, notification)
        Log.i(TAG, "due notification posted: count=$count top=\"$top\"")
    }

    /** 清掉提醒（复习完成后调用，避免"该复习了"还挂在那儿）。 */
    fun cancel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.cancel(NOTIFICATION_ID)
        Log.i(TAG, "due notification cancelled")
    }
}
