package com.memorypalace.core.spatial

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 空间几何基础类型（纯 JVM，不依赖 PICO SDK）。
 *
 * 为什么自己写一套：`core` 是纯 JVM 模块，所有空间数学要能在没有头显、
 * 没有 SDK 的机器上跑单测。app 层负责把 SDK 的 Vector3/Quat 转成这里的类型，
 * 命中判定的逻辑全部留在 core，用 JUnit 覆盖。
 *
 * 坐标约定：右手系，Y 向上，米为单位（与 PICO Spatial / OpenXR 一致）。
 */
data class Vec3(val x: Float, val y: Float, val z: Float) {

    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = Vec3(x * s, y * s, z * s)
    operator fun unaryMinus() = Vec3(-x, -y, -z)

    infix fun dot(o: Vec3): Float = x * o.x + y * o.y + z * o.z

    infix fun cross(o: Vec3) = Vec3(
        y * o.z - z * o.y,
        z * o.x - x * o.z,
        x * o.y - y * o.x,
    )

    fun length(): Float = sqrt(dot(this))

    /** 零向量或含 NaN/Inf 的向量返回 null —— 宁可让调用方显式处理，也不要静默产出 NaN 坐标。 */
    fun normalized(): Vec3? {
        // IEEE-754 下 `NaN < EPS` 恒为 false，只判 len<EPS 会让含 NaN/Inf 的向量
        // 静默产出一个 NaN 向量，正好违背本函数「绝不静默产出 NaN」的设计意图。
        // 故入口先挡非有限值，再走零长判定。
        if (!isFinite()) return null
        val len = length()
        if (len < EPS) return null
        return Vec3(x / len, y / len, z / len)
    }

    fun isFinite(): Boolean =
        x.isFinite() && y.isFinite() && z.isFinite()

    companion object {
        const val EPS = 1e-6f
        val ZERO = Vec3(0f, 0f, 0f)
        val UP = Vec3(0f, 1f, 0f)
        val DOWN = Vec3(0f, -1f, 0f)
        val FORWARD = Vec3(0f, 0f, -1f)
        val BACK = Vec3(0f, 0f, 1f)
    }
}

/** 平面局部二维坐标（u, v）。命中点与多边形轮廓都用它。 */
data class Vec2(val u: Float, val v: Float)

/**
 * 四元数（只保留本项目需要的部分：旋转一个向量）。
 *
 * 与 SDK 的 `Quat` 字段顺序一致（x, y, z, w）。不提供 slerp / 求逆 ——
 * 平面上取点只需要把方向向量转过去。
 */
data class Quat(val x: Float, val y: Float, val z: Float, val w: Float) {

    /** 旋转向量：v' = v + 2w(q×v) + 2q×(q×v)，与 q v q* 等价但少一次乘法。 */
    fun rotate(v: Vec3): Vec3 {
        val q = Vec3(x, y, z)
        val t = q cross v * 2f
        return v + t * w + (q cross t)
    }

    /** 含 NaN/Inf 或零长的四元数退回 IDENTITY —— 与 Vec3 同理，绝不静默产出 NaN 四元数。 */
    fun normalized(): Quat {
        // 签名是 Quat（非空），退化只能退回 IDENTITY；入口先挡四个分量的非有限值，
        // 否则 sqrt(NaN)=NaN、NaN<EPS 为 false，会返回一个把 rotate 全带成 NaN 的僵尸四元数。
        if (!x.isFinite() || !y.isFinite() || !z.isFinite() || !w.isFinite()) return IDENTITY
        val n = sqrt(x * x + y * y + z * z + w * w)
        if (n < Vec3.EPS) return IDENTITY
        return Quat(x / n, y / n, z / n, w / n)
    }

    companion object {
        val IDENTITY = Quat(0f, 0f, 0f, 1f)
    }
}

/** 一条射线。direction 由调用方保证非零，构造时归一化以便距离单位为米。 */
data class Ray(val origin: Vec3, val direction: Vec3) {

    constructor(origin: Vec3, direction: Vec3, normalized: Boolean) : this(
        origin,
        if (normalized) direction else (direction.normalized() ?: Vec3.FORWARD),
    )

    companion object {
        fun of(origin: Vec3, direction: Vec3): Ray =
            Ray(origin, direction.normalized() ?: Vec3.FORWARD, normalized = true)
    }
}

/**
 * 平面锚点的几何抽象：中心 + 正交基 + 半尺寸。
 *
 * 用正交基而不是直接存四元数，是因为命中判定只依赖投影，跟 SDK 的四元数
 * 轴向约定解耦 —— 约定变了（Y-up 还是 Z-up 作为法线）只影响构造，不影响判定。
 */
data class PlaneShape(
    val center: Vec3,
    /** 单位法线。 */
    val normal: Vec3,
    /** 平面内第一根单位基向量。 */
    val u: Vec3,
    /** 平面内第二根单位基向量，满足 u × v = normal。 */
    val v: Vec3,
    val halfU: Float,
    val halfV: Float,
    /**
     * 精确多边形轮廓（局部 uv）。为空时退化为矩形判定。
     * 真实平面往往是不规则的（L 形桌面、带缺口的墙），矩形判定会把
     * 空处也算成命中，卡片就悬在半空 —— 有轮廓时优先用轮廓。
     */
    val outline: List<Vec2>? = null,
) {

    /** 把世界点投到局部坐标；点不在平面上时也照样投影（用于轮廓判定）。 */
    fun project(point: Vec3): Vec2 {
        val d = point - center
        return Vec2(d dot u, d dot v)
    }

    /** 局部坐标是否落在平面内（先轮廓、后退化矩形）。 */
    fun covers(local: Vec2, margin: Float = 0f): Boolean {
        val poly = outline
        if (poly != null && poly.size >= 3) return pointInPolygon(poly, local)
        return abs(local.u) <= halfU + margin && abs(local.v) <= halfV + margin
    }

    companion object {
        /**
         * 由中心 + 法线提示 + 平面内基向量提示构造正交基。
         *
         * 做法：法线归一化 → u 提示投到平面上再归一化 → v = normal × u。
         * 若 u 提示退化（与法线平行），自动挑一根与法线最不平行的世界轴兜底。
         * 失败（法线为零）返回 null，调用方必须显式跳过，不能带着 NaN 继续。
         */
        fun build(
            center: Vec3,
            normalHint: Vec3,
            uHint: Vec3?,
            halfU: Float,
            halfV: Float,
            outline: List<Vec2>? = null,
        ): PlaneShape? {
            if (!center.isFinite()) return null
            val n = normalHint.normalized() ?: return null
            var u = uHint?.let { hint -> (hint - n * (hint dot n)).normalized() }
            if (u == null) {
                // 挑与法线最不平行的世界轴做兜底
                val axes = listOf(Vec3(1f, 0f, 0f), Vec3(0f, 1f, 0f), Vec3(0f, 0f, 1f))
                val best = axes.minByOrNull { abs(it dot n) } ?: Vec3(1f, 0f, 0f)
                u = (best - n * (best dot n)).normalized() ?: return null
            }
            val v = (n cross u).normalized() ?: return null
            return PlaneShape(
                center = center,
                normal = n,
                u = u,
                v = v,
                halfU = abs(halfU),
                halfV = abs(halfV),
                outline = outline,
            )
        }

        /**
         * 由多边形顶点求法线（牛顿-高斯方向：相邻两边叉积）。
         *
         * PICO 的 PlaneAnchor 同时给 `vertices` 和 `planeOrientation`。两者本该
         * 一致，但 SDK 的四元数轴向约定没有官方文档保证；用真实顶点算法线是
         * 唯一不依赖约定的来源。顶点退化（共线/少于 3 个）返回 null。
         */
        fun normalFromPolygon(points: List<Vec3>): Vec3? {
            if (points.size < 3) return null
            val origin = points[0]
            for (i in 1 until points.size - 1) {
                val a = points[i] - origin
                val b = points[i + 1] - origin
                val n = (a cross b).normalized()
                if (n != null) return n
            }
            return null
        }

        /** 射线-多边形（凸/凹都适用，奇偶规则）包含判定。 */
        fun pointInPolygon(poly: List<Vec2>, p: Vec2): Boolean {
            var inside = false
            var j = poly.size - 1
            for (i in poly.indices) {
                val vi = poly[i]
                val vj = poly[j]
                val straddles = (vi.v > p.v) != (vj.v > p.v)
                if (straddles) {
                    val t = (vj.u - vi.u) * (p.v - vi.v) / (vj.v - vi.v) + vi.u
                    if (p.u < t) inside = !inside
                }
                j = i
            }
            return inside
        }
    }
}

/** 一次射线命中的结果：落在哪个平面、世界坐标、距离、局部坐标。 */
data class PlaneHit(
    val plane: PlaneShape,
    val point: Vec3,
    val distance: Float,
    val local: Vec2,
)

/**
 * 射线取点：从手柄/手部/头部发出的射线打到哪个已识别平面上。
 *
 * 纯函数，无状态，无 SDK 依赖 —— 真机与模拟器的差别只在"谁来喂射线、
 * 谁来喂平面"，判定逻辑完全一致，所以这部分可以 100% 用单测锁死。
 */
object PlanePicker {

    /** 默认最远取点距离（米）。超过这个距离的平面不参与命中。 */
    const val DEFAULT_MAX_DISTANCE = 5.0f

    /**
     * 单平面命中。返回 null 的四种情况（都会被单测覆盖）：
     *  ① 射线与平面平行（|n·d| < EPS）
     *  ② 交点在射线反方向（t < 0）
     *  ③ 交点超出 [0, maxDistance]
     *  ④ 交点落在平面轮廓/矩形之外
     */
    fun hit(plane: PlaneShape, ray: Ray, maxDistance: Float = DEFAULT_MAX_DISTANCE): PlaneHit? {
        val dir = ray.direction.normalized() ?: return null
        val denom = plane.normal dot dir
        if (abs(denom) < 1e-4f) return null // 平行或近似平行
        val t = ((plane.center - ray.origin) dot plane.normal) / denom
        if (t < 0f || t > maxDistance) return null
        val point = ray.origin + dir * t
        val local = plane.project(point)
        if (!plane.covers(local)) return null
        return PlaneHit(plane, point, t, local)
    }

    /**
     * 多平面取最近命中。
     *
     * 真实房间里同一方向会同时命中地板、桌面、墙 —— 取最近的那个，
     * 这与人的直觉一致：放下巴前的卡，落在桌面上而不是桌后面的地板。
     */
    fun pick(
        planes: List<PlaneShape>,
        ray: Ray,
        maxDistance: Float = DEFAULT_MAX_DISTANCE,
    ): PlaneHit? = planes
        .mapNotNull { hit(it, ray, maxDistance) }
        .minByOrNull { it.distance }

    /**
     * 把手柄位姿转成射线：位姿的 -Z 是"指哪打哪"的方向（OpenXR 约定）。
     * 单独抽出来是因为这个约定错了会让所有取点整体偏 90°，值得一个断言。
     */
    fun rayFromPose(origin: Vec3, rotation: Quat): Ray =
        Ray.of(origin, rotation.rotate(Vec3.FORWARD))

    /**
     * 取点后把卡片立起来：让卡面朝向观察者（水平面朝上、竖直面朝外）。
     *
     * 返回欧拉角（PICO 用法：pitch / yaw / roll，Y 轴朝上）。
     * 这里只算 yaw：卡片绕 Y 轴转向观察者，pitch/roll 保持 0 —— 卡片始终竖直，
     * 不会被"躺"在桌面上，这是可读性的硬要求（躺着看文字要歪头）。
     */
    fun yawToward(hit: Vec3, viewer: Vec3): Float {
        val dx = viewer.x - hit.x
        val dz = viewer.z - hit.z
        // 入口挡非有限值：否则 atan2(NaN, …) 会把 NaN 悄悄当成合法 yaw 返回
        if (!dx.isFinite() || !dz.isFinite()) return 0f
        if (abs(dx) < Vec3.EPS && abs(dz) < Vec3.EPS) return 0f
        // atan2(x, z)：0 表示朝 +Z，与 EulerAngles.yaw 的零点一致
        return Math.toDegrees(kotlin.math.atan2(dx.toDouble(), dz.toDouble())).toFloat()
    }

    /** 把卡片抬高一点点，避免与桌面共面导致 z-fighting（渲染闪烁）。 */
    fun lift(point: Vec3, normal: Vec3, amount: Float = 0.02f): Vec3 =
        point + normal * amount
}