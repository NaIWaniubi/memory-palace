package com.memorypalace.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.memorypalace.R
import com.pico.spatial.ui.platform.stub.SpatialLaunchActivity

/**
 * 应用入口（M12 修订版）。
 *
 * 为什么不能另起一个 SplashActivity 当 LAUNCHER：
 * 空间应用的入口容器由 SDK 在 LAUNCHER 启动时创建。如果 LAUNCHER 是普通
 * Activity（非 Spatial stub），SDK 会创建默认 WindowContainer；之后
 * LaunchActivity 想再注册 Stage 就冲突，抛
 *   "Only support [SUIStage, SUIWindowContainer, but got a
 *    [PICO_SYSTEM_DEFAULT_WINDOWCONTAINER]"
 * 启动直接崩。反过来把 stage meta-data 挪到普通 LAUNCHER 上，则 Stage
 * 根本不注册（"Stage(room_stage) not registered"）。
 * 官方三个 Sample 全部是"LAUNCHER 就是 SpatialLaunchActivity，meta-data
 * 挂在它上面"——这是 SDK 契约，不是示例写法。
 *
 * 冷启动 12 秒黑屏怎么办：不用另一个 Activity，改用**窗口背景占位**——
 * manifest 主题非透明（系统据此在进程起来前画 starting window，显示
 * mp_splash_bg），onCreate 里 setTheme 切回透明。窗口已在创建时确定，
 * 主题切换只影响后续绘制，不触碰 SDK 的容器注册。
 *
 * 注意不能在 onCreate 里 setContentView —— SDK 的 SpatialStubActivity.onCreate
 * 内部会 setSpatialContent()，我们再设一次会覆盖它的内容视图。
 * 所以占位靠窗口背景，不靠内容视图。
 *
 * M15：在这里请求 POST_NOTIFICATIONS。
 * targetSdk 35 下没有这个运行时授权，NotificationManager.notify() 只会在
 * logcat 写一句 "posted"，系统根本不显示 —— 模拟器没有通知面板，所以之前
 * 的实测"通过"其实是假通过。真机上必须先拿到授权。
 */
@SuppressLint("CustomSplashScreen")
class LaunchActivity : SpatialLaunchActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 主题在 super.onCreate 之后切换：SDK 已完成容器注册与内容设置，
        // 此后换主题只影响窗口绘制，starting window 已经挡住了黑屏。
        setTheme(R.style.Theme_SpatialApp)
        Log.i(TAG, "launch activity created, theme switched to transparent")
        requestNotificationPermission()
    }

    /**
     * 请求通知权限（Android 13+）。拒绝也不影响其他功能 —— 到期提醒只是
     * 增强项，拒绝后由书房面板上的权限提示引导用户去设置里开。
     */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            Log.i(TAG, "POST_NOTIFICATIONS already granted")
            return
        }
        ActivityCompat.requestPermissions(
            this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATION,
        )
        Log.i(TAG, "POST_NOTIFICATIONS requested")
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIFICATION) {
            val ok = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
            Log.i(TAG, "POST_NOTIFICATIONS result=${if (ok) "granted" else "denied"}")
        }
    }

    companion object {
        const val TAG = "Launch"
        private const val REQ_NOTIFICATION = 1001
    }
}
