package com.memorypalace.core.spatial

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 逻辑方位罗盘（评审 P3 维度 4 动作 1：把「按方位发声」从纸面变成看得见的证据）。
 *
 * 为什么要它：空间音频的真实渲染（`ObjectAudioComponent`）依赖头显姿态与真机声场，
 * 模拟器没有输出路径，「走到冰箱前声音在左边」一直只有 `play()` 调用这一层证据。
 * 本类把同一件事的**几何事实**独立算出来：目标锚点相对观察者的方位角 / 俯仰角 /
 * 距离，UI 直接渲染成罗盘刻度 —— 评委在没有声场的设备上也能肉眼核对
 * 「卡片确实在你左前方 35°、2.1m」，真实 `play()` 路径原样保留。
 *
 * 纯 JVM 零 SDK：与 [PlanePicker] 同一纪律，角度语义全部由 [BearingTest] 锁死。
 *
 * 角度约定（右手系、Y 向上，与 Geometry.kt 一致）：
 *  - **方位角** azimuthDeg：从观察者水平朝向转到目标水平方向的**带符号**角，
 *    **+ 为左、− 为右**，值域 (−180, 180]。正前方为 0°，转身目标跟着转。
 *  - **俯仰角** elevationDeg：+ 为上、− 为下，值域 [−90, 90]。
 *  - **距离** distanceM：三维直线距离（米）。
 */
data class Bearing(
    val azimuthDeg: Float,
    val elevationDeg: Float,
    val distanceM: Float,
) {

    /** 八方位方位词（以观察者朝向为「前」）。 */
    val side: String
        get() {
            val a = abs(azimuthDeg)
            val left = azimuthDeg > 0f
            return when {
                a <= 22.5f -> "正前"
                a <= 67.5f -> if (left) "左前" else "右前"
                a <= 112.5f -> if (left) "正左" else "正右"
                a <= 157.5f -> if (left) "左后" else "右后"
                else -> "正后"
            }
        }

    /**
     * 3×3 罗盘格子里目标所在的格（4 = 中央的观察者）。
     * 布局：0↖ 1↑ 2↗ / 3← 4🧍 →5 / 6↙ 7↓ 8↘（↑ 是观察者正前方）。
     */
    val dialCell: Int
        get() {
            val a = abs(azimuthDeg)
            val left = azimuthDeg > 0f
            return when {
                a <= 22.5f -> 1
                a <= 67.5f -> if (left) 0 else 2
                a <= 112.5f -> if (left) 3 else 5
                a <= 157.5f -> if (left) 6 else 8
                else -> 7
            }
        }

    /** 面板一行文案：「左前 35° · 2.1m」。角度取绝对值，左右由方位词表达。 */
    fun format(): String {
        val deg = abs(azimuthDeg).roundToInt()
        return "$side ${deg}° · ${"%.1f".format(distanceM)}m"
    }
}

/**
 * 方位计算入口（全部纯函数，可无头单测）。
 *
 * 退化输入（NaN/Inf、零朝向）宁可返回 null 让调用方显式处理，
 * 也不产出带 NaN 的"看起来合法"的角度 —— 与 [Vec3.normalized] 同一纪律。
 */
object BearingGuide {

    /** 观察者基准眼高（米）。房间层 Stage 约定用户进入时位于原点、面朝 −Z。 */
    val STAGE_EYE: Vec3 = Vec3(0f, 1.6f, 0f)

    /** Stage 进入朝向（OpenXR 惯例：−Z 为前）。 */
    val STAGE_FORWARD: Vec3 = Vec3.FORWARD

    /**
     * 目标 [target] 相对观察者（站在 [head]、朝 [forward]）的方位。
     *
     * [forward] 不需要水平：内部会去掉 Y 分量再归一化；若整个朝向接近竖直
     * （水平分量退化）返回 null —— 那时候"左右"没有定义，硬算就是骗人。
     */
    fun of(head: Vec3, forward: Vec3, target: Vec3): Bearing? {
        if (!head.isFinite() || !forward.isFinite() || !target.isFinite()) return null
        val d = target - head
        val dist3 = sqrt(d dot d)
        if (!dist3.isFinite()) return null
        if (dist3 < Vec3.EPS) {
            // 目标就在观察者的位置：没有方向可言，按"0°、0 距离"给出中性结果
            return Bearing(0f, 0f, 0f)
        }
        val fh = Vec3(forward.x, 0f, forward.z)
        val dh = Vec3(d.x, 0f, d.z)
        if ((fh dot fh) < Vec3.EPS * Vec3.EPS) return null // 朝向接近竖直，左右无定义
        val horiz = sqrt(dh dot dh)
        // 右手系 Y-up：cross(forward, toTarget).y = fz·dx − fx·dz > 0 ⇔ 目标在朝向左侧（俯视逆时针）
        val crossY = fh.z * dh.x - fh.x * dh.z
        val dot = fh.x * dh.x + fh.z * dh.z
        val az = Math.toDegrees(atan2(crossY.toDouble(), dot.toDouble())).toFloat()
        val el = Math.toDegrees(atan2(d.y.toDouble(), horiz.toDouble())).toFloat()
        return Bearing(azimuthDeg = az, elevationDeg = el, distanceM = dist3)
    }

    /** 以 Stage 基准（房间原点、眼高 1.6m、进入朝向 −Z）观察目标 —— 模拟器无实时头位姿时的口径。 */
    fun fromStageOrigin(target: Vec3): Bearing? = of(STAGE_EYE, STAGE_FORWARD, target)

    /**
     * 渲染 3×3 罗盘文本（给 2D 面板用，`mark` 传 [Bearing.dialCell]；4/越界 = 不标）。
     * 每行 3 格，行内以空格分隔；中央固定是观察者 `◉`，被标记格换成 `●`。
     */
    fun dialText(mark: Int): String {
        val arrows = charArrayOf('↖', '↑', '↗', '←', '◉', '→', '↙', '↓', '↘')
        return (0..2).joinToString("\n") { row ->
            (0..2).joinToString(" ") { col ->
                val i = row * 3 + col
                when (i) {
                    4 -> "◉"
                    mark -> "●"
                    else -> arrows[i].toString()
                }
            }
        }
    }

    /** 俯仰补充文案：±10° 内视为同高，避免"抬头 1°"这种噪音。 */
    fun elevationText(bearing: Bearing): String {
        val e = bearing.elevationDeg
        val deg = abs(e).roundToInt()
        return when {
            abs(e) <= 10f -> "与视线同高"
            e > 0f -> "上方 ${deg}°"
            else -> "下方 ${deg}°"
        }
    }

    /** 两点水平距离（米）—— 罗盘脚注用，NaN 安全。 */
    fun horizontalDistance(a: Vec3, b: Vec3): Float {
        if (!a.isFinite() || !b.isFinite()) return Float.NaN
        val dx = a.x - b.x
        val dz = a.z - b.z
        if (!(dx.isFinite() && dz.isFinite())) return Float.NaN
        return hypot(dx, dz)
    }
}

/** 直接渲染这条方位的 3×3 罗盘文本（三行，每行三格）。 */
fun Bearing.dialText(): String = BearingGuide.dialText(dialCell)
