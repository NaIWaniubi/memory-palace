package com.memorypalace.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.memorypalace.R

/**
 * M12 冷启动占位页（LAUNCHER 入口）。
 *
 * 为什么单独一个 Activity：LaunchActivity 用的 Theme.Translucent 没有 starting window，
 * 冷启动 12s（模拟器）屏幕全空，现场观感="它挂了"。本 Activity 用非透明主题，
 * 系统 starting window 立刻画 windowBackground（图标），进程起来后这里补一行状态字，
 * 并停留到 DualStateController 报"三窗已开"才关闭——空屏被完整盖住。
 *
 * 兜底：20s 超时自动关闭（正常路径远早于此；异常时不至于把 splash 永久留在场上）。
 */
class SplashActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable {
        Log.w(TAG, "splash timeout, closing without ready signal")
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.mp_splash)
        instance = this
        Log.i(TAG, "splash shown, starting LaunchActivity")
        startActivity(Intent(this, LaunchActivity::class.java))
        handler.postDelayed(timeout, 20_000)
    }

    override fun onDestroy() {
        handler.removeCallbacks(timeout)
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        const val TAG = "Splash"

        @Volatile
        private var instance: SplashActivity? = null

        /** 三窗打开后由 DualStateController 调用：占位使命完成，关闭。 */
        fun notifyReady() {
            instance?.let {
                Log.i(TAG, "windows ready, closing splash")
                it.runOnUiThread { it.finish() }
            }
            instance = null
        }
    }
}
