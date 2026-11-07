package com.memorypalace.app

import android.app.Application
import com.memorypalace.anchor.AnchorScene
import com.memorypalace.data.Clock
import com.memorypalace.data.DataGraph
import com.memorypalace.mainApp
import com.memorypalace.probe.DualStateController
import com.memorypalace.probe.DualStateProbe
import com.memorypalace.review.ReviewSession
import com.pico.spatial.ui.foundation.dsl.launch

class MemoryPalaceApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DualStateProbe.log("app", "Application.onCreate")
        DualStateController.register(this)
        DataGraph.init(this)
        AnchorScene.register(this)
        ReviewSession.register(this)
        Clock.register(this)
        // 注：SDK 强制主线程初始化（SpatialApp.initialize 校验线程），
        // 后台线程启动会崩 —— 启动 ANR 只能等真机（arm64 原生无 houdini 转译开销）验证。
        launch(::mainApp)
    }
}
