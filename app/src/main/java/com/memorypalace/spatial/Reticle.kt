package com.memorypalace.spatial

import android.util.Log
import com.memorypalace.core.spatial.Vec3
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.math.EulerAngles
import com.pico.spatial.core.math.Vector3

/**
 * 空间准星（M16）。
 *
 * 手柄射线打到哪里，准星就停在哪里 —— 这是"所见即所得"的关键：
 * 如果只在 2D 面板上写「瞄准桌面 1.2m」，用户根本不知道自己在指哪。
 *
 * 实现要点：
 *  ① 只持有实体引用，不经过 Compose 重组。每帧改 TransformComponent，
 *     走 Compose 的话 60Hz 重组会拖垮 2D 面板。
 *  ② 没有瞄准时把实体挪到 y=-1000（视野外）而不是移除 —— 反复 add/remove
 *     ECS 实体会触发场景重建，那是几百毫秒的卡顿。
 *  ③ 实体由 RoomStage 创建并 bind；Stage 销毁重建时自动解绑，不会悬空引用。
 */
object Reticle {

    const val TAG = "Reticle"

    /** 藏在视野下方时用的坐标（原点附近会被看到，所以压到 -1000m）。 */
    private const val HIDDEN_Y = -1000f

    private var entity: Entity? = null
    private var transform: TransformComponent? = null

    fun bind(e: Entity, t: TransformComponent) {
        entity = e
        transform = t
        hide()
        Log.i(TAG, "reticle bound")
    }

    fun unbind() {
        entity = null
        transform = null
        Log.i(TAG, "reticle unbound")
    }

    /** 每帧调用：把准星挪到命中点，并按观察方向转正。 */
    fun move(point: Vec3, yawDeg: Float) {
        val t = transform ?: return
        runCatching {
            t.position = Vector3(point.x, point.y, point.z)
            t.eulerAngles = EulerAngles(0f, yawDeg, 0f)
        }.onFailure { Log.w(TAG, "move failed", it) }
    }

    fun hide() {
        val t = transform ?: return
        runCatching {
            t.position = Vector3(0f, HIDDEN_Y, 0f)
        }.onFailure { Log.w(TAG, "hide failed", it) }
    }
}