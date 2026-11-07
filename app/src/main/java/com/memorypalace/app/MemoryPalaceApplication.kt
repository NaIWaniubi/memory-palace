package com.memorypalace.app

import android.app.Application
import com.memorypalace.anchor.AnchorScene
import com.memorypalace.mainApp
import com.memorypalace.probe.DualStateController
import com.memorypalace.probe.DualStateProbe
import com.pico.spatial.ui.foundation.dsl.launch

class MemoryPalaceApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DualStateProbe.log("app", "Application.onCreate")
        DualStateController.register(this)
        AnchorScene.register(this)
        launch(::mainApp)
    }
}
