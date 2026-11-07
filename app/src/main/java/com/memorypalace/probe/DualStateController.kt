package com.memorypalace.probe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.pico.spatial.ui.platform.containers.OpenStageResult
import com.pico.spatial.ui.platform.containers.StageStyle
import com.pico.spatial.ui.platform.containers.closeStage
import com.pico.spatial.ui.platform.containers.closeWindowContainer
import com.pico.spatial.ui.platform.containers.openStage
import com.pico.spatial.ui.platform.containers.openWindowContainer
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/**
 * 双态切换控制器。模拟器无法驱动空间按钮（官方明示），
 * 因此除应用内 2D 按钮外，再提供 adb 广播入口，保证测试可无头驱动：
 *
 *   adb shell am broadcast -a com.memorypalace.dualstate.OPEN_STAGE
 *   adb shell am broadcast -a com.memorypalace.dualstate.CLOSE_STAGE
 *   adb shell am broadcast -a com.memorypalace.dualstate.TOGGLE_REVIEW_CARD
 */
object DualStateController {

    const val ACTION_OPEN_STAGE = "com.memorypalace.dualstate.OPEN_STAGE"
    const val ACTION_CLOSE_STAGE = "com.memorypalace.dualstate.CLOSE_STAGE"
    const val ACTION_TOGGLE_REVIEW_CARD = "com.memorypalace.dualstate.TOGGLE_REVIEW_CARD"
    const val ACTION_OPEN_WINDOWS = "com.memorypalace.dualstate.OPEN_WINDOWS"

    const val STAGE_ID = "room_stage"
    const val STUDY_PANEL_ID = "study_panel"
    const val STUDY_PANEL_TAG = "study_panel_tag"
    const val REVIEW_CARD_ID = "review_card"
    const val REVIEW_CARD_TAG = "review_card_tag"

    private val scope = MainScope()
    private var reviewCardOpen = true

    fun register(context: Context) {
        val filter = IntentFilter().apply {
            addAction(ACTION_OPEN_STAGE)
            addAction(ACTION_CLOSE_STAGE)
            addAction(ACTION_TOGGLE_REVIEW_CARD)
            addAction(ACTION_OPEN_WINDOWS)
        }
        ContextCompat.registerReceiver(
            context,
            object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    DualStateProbe.log("controller", "onReceive ${intent.action}")
                    when (intent.action) {
                        ACTION_OPEN_STAGE -> requestOpenStage(ctx)
                        ACTION_CLOSE_STAGE -> requestCloseStage()
                        ACTION_TOGGLE_REVIEW_CARD -> toggleReviewCard(ctx)
                        ACTION_OPEN_WINDOWS -> openWindows(ctx)
                    }
                }
            },
            filter,
            // 调试用测试入口：需要 adb shell 广播能送达，故 EXPORTED（release 版移除）
            ContextCompat.RECEIVER_EXPORTED,
        )
        DualStateProbe.log("controller", "broadcast receiver registered")
    }

    fun requestOpenStage(context: Context) {
        scope.launch {
            DualStateProbe.log("controller", "openStage($STAGE_ID) requested")
            val result = context.applicationContext.openStage(STAGE_ID, StageStyle.Mixed)
            DualStateProbe.log("controller", "openStage result=${result.describe()}")
        }
    }

    fun requestCloseStage() {
        scope.launch {
            DualStateProbe.log("controller", "closeStage requested")
            closeStage()
            DualStateProbe.log("controller", "closeStage done")
        }
    }

    fun openWindows(context: Context) {
        context.applicationContext.openWindowContainer(STUDY_PANEL_ID, STUDY_PANEL_TAG)
        DualStateProbe.log("controller", "openWindowContainer($STUDY_PANEL_ID)")
        context.applicationContext.openWindowContainer(REVIEW_CARD_ID, REVIEW_CARD_TAG)
        DualStateProbe.log("controller", "openWindowContainer($REVIEW_CARD_ID)")
        reviewCardOpen = true
    }

    fun toggleReviewCard(context: Context) {
        if (reviewCardOpen) {
            context.applicationContext.closeWindowContainer(REVIEW_CARD_ID, REVIEW_CARD_TAG)
            DualStateProbe.log("controller", "closeWindowContainer($REVIEW_CARD_ID)")
        } else {
            context.applicationContext.openWindowContainer(REVIEW_CARD_ID, REVIEW_CARD_TAG)
            DualStateProbe.log("controller", "openWindowContainer($REVIEW_CARD_ID)")
        }
        reviewCardOpen = !reviewCardOpen
    }

    private fun OpenStageResult.describe(): String = when (this) {
        is OpenStageResult.Allowed -> "Allowed"
        is OpenStageResult.NotAllowed -> "NotAllowed"
        is OpenStageResult.Error -> "Error"
        else -> this.javaClass.simpleName
    }
}
