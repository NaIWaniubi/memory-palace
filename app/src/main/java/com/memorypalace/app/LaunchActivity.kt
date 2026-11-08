package com.memorypalace.app

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
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
 */
@SuppressLint("CustomSplashScreen")
class LaunchActivity : SpatialLaunchActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 主题在 super.onCreate 之后切换：SDK 已完成容器注册与内容设置，
        // 此后换主题只影响窗口绘制，starting window 已经挡住了黑屏。
        setTheme(R.style.Theme_SpatialApp)
        Log.i(TAG, "launch activity created, theme switched to transparent")
    }

    companion object {
        const val TAG = "Launch"
    }
}
