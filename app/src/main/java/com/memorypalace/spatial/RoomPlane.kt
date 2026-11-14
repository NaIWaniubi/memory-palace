package com.memorypalace.spatial

import android.util.Log
import com.memorypalace.core.spatial.PlaneShape
import com.memorypalace.core.spatial.Quat
import com.memorypalace.core.spatial.Vec2
import com.memorypalace.core.spatial.Vec3
import com.pico.spatial.sense.base.SemanticLabelType
import com.pico.spatial.sense.plane.PlaneAnchor
import com.pico.spatial.sense.plane.PlaneOrientation
import kotlin.math.abs

/**
 * 一个已识别的房间平面（世界坐标系快照）。
 *
 * 这是 SDK 的 [PlaneAnchor] 到纯几何 [PlaneShape] 的翻译层：
 * SDK 的对象带原生句柄、生命周期跟着 tracking 走，不能在 UI 层长期持有；
 * 快照成不可变数据后，取点判定、UI 列表、日志全部只依赖快照。
 */
data class RoomPlane(
    val uuid: String,
    val shape: PlaneShape,
    val kind: Kind,
    /** 中文语义名（桌面/沙发/墙/地板…），卡片上直接写「钉在餐桌上」。 */
    val label: String,
    /** SDK 原始枚举名，日志与答辩追问时用。 */
    val rawLabel: String,
    val areaM2: Float,
) {
    /**
     * 平面朝向分类。
     *
     * 只看 |法线.y|，不看符号 —— 平面是双面的，"朝上/朝下"取决于顶点绕序，
     * 实测同一张桌子会被算成"朝下"、地板算成"朝上"（2026-11-10 模拟器 57 个
     * 平面实测）。把符号当特征显示出来只会得到"桌面 · 水平朝下"这种自相矛盾的
     * 文案。放置时的抬升方向另有处理：按观察者所在侧翻法线（见 AimPlacement）。
     */
    enum class Kind { HORIZONTAL, VERTICAL, ARBITRARY }

    val isHorizontal: Boolean
        get() = kind == Kind.HORIZONTAL

    /**
     * UI 一行文案。
     *
     * 只写「语义 + 朝向 + 包围盒尺寸」，不写面积：SDK 的 vertices 和
     * transform.position 实测不在同一坐标系（差 5.5 米），两者算出来的面积
     * 差 4 倍。尺寸取 boundingBoxSize，跟位姿同源，至少自洽。
     */
    fun describe(): String {
        val o = when (kind) {
            Kind.HORIZONTAL -> "水平"
            Kind.VERTICAL -> "竖直"
            Kind.ARBITRARY -> "倾斜"
        }
        val w = shape.halfU * 2f
        val h = shape.halfV * 2f
        return "$label · $o · ${"%.1f".format(w)}×${"%.1f".format(h)} m"
    }
}

/**
 * 语义标签 → 中文名。
 *
 * PICO 的语义识别直接给到家具级（24 类），这是本项目「把卡钉在餐桌上」
 * 这句话能成立的前提 —— 没有它只能说「钉在某个平面上」，跟普通放置没区别。
 */
object PlaneSemantics {

    fun label(type: SemanticLabelType?): String = when (type) {
        SemanticLabelType.FLOOR -> "地板"
        SemanticLabelType.CEILING -> "天花板"
        SemanticLabelType.WALL -> "墙"
        SemanticLabelType.DOOR -> "门"
        SemanticLabelType.WINDOW -> "窗"
        SemanticLabelType.OPENING -> "通道"
        SemanticLabelType.TABLE -> "桌面"
        SemanticLabelType.SOFA -> "沙发"
        SemanticLabelType.CHAIR -> "椅子"
        SemanticLabelType.CABINET -> "柜子"
        SemanticLabelType.BED -> "床"
        SemanticLabelType.CURTAIN -> "窗帘"
        SemanticLabelType.SCREEN -> "屏幕"
        SemanticLabelType.PLANT -> "植物"
        SemanticLabelType.BEAM -> "梁"
        SemanticLabelType.COLUMN -> "柱"
        SemanticLabelType.REFRIGERATOR -> "冰箱"
        SemanticLabelType.WASHING_MACHINE -> "洗衣机"
        SemanticLabelType.AIR_CONDITIONER -> "空调"
        SemanticLabelType.LAMP -> "灯"
        SemanticLabelType.WALL_ART -> "挂画"
        SemanticLabelType.STAIRWAY -> "楼梯"
        SemanticLabelType.VIRTUAL_WALL -> "虚拟墙"
        SemanticLabelType.HUMAN -> "人"
        SemanticLabelType.UNKNOWN, null -> "平面"
    }

    /**
     * 卡片上写什么：「钉在餐桌上」的落点词。
     *
     * 不是所有语义都适合当落点：地板/天花板/墙都是"某个面"，家具才是"某个东西"。
     * 中文里家具前面不加量词、面类加"面"更自然。
     */
    fun anchorPhrase(label: String): String = when (label) {
        "地板" -> "地板上"
        "天花板" -> "天花板上"
        "墙" -> "墙上"
        "桌面" -> "桌面上"
        "沙发" -> "沙发上"
        "椅子" -> "椅子上"
        "柜子" -> "柜子上"
        "床" -> "床沿上"
        "门" -> "门边"
        "窗" -> "窗边"
        "屏幕" -> "屏幕旁"
        "冰箱" -> "冰箱上"
        else -> "${label}上"
    }
}

/** SDK 平面 → 快照。任何一步失败返回 null（宁可少一个平面，也不要 NaN 坐标）。 */
object RoomPlaneMapper {

    const val TAG = "Plane"

    fun from(anchor: PlaneAnchor): RoomPlane? {
        val t = anchor.transform
        val center = Vec3(t.position.x, t.position.y, t.position.z)
        if (!center.isFinite()) {
            Log.w(TAG, "plane ${anchor.anchorUUID} 中心坐标非有限值，丢弃")
            return null
        }
        // P1[高]#1：core 的 Quat.normalized() 已把非有限分量挡成 IDENTITY，
        // 但这里再显式校验一次，把"信了 center/verts 却漏了 quat"的意图留在本地，
        // 不依赖 core 的隐式行为。非法四元数退回 IDENTITY（等价于只信中心+法线方向）。
        val q = t.quaternion
        val quat = if (q.x.isFinite() && q.y.isFinite() && q.z.isFinite() && q.w.isFinite()) {
            Quat(q.x, q.y, q.z, q.w).normalized()
        } else {
            Log.w(TAG, "plane ${anchor.anchorUUID} 四元数含非有限值，退回单位四元数")
            Quat.IDENTITY
        }
        val verts = anchor.vertices.map { Vec3(it.x, it.y, it.z) }.filter { it.isFinite() }

        // 法线来源优先级：
        //  ① 顶点多边形叉积 —— 不依赖 SDK 的四元数轴向约定，最可信
        //  ② 朝向枚举 —— 水平面用世界 +Y，竖直面用位姿的 +Z
        //  ③ 四元数 +Z —— 兜底
        //
        // 符号（朝上还是朝下）**不可信**：实测（2026-11-10，模拟器 57 个平面）
        // 地板是"朝上"、同一场景的桌面/柜子/椅子/床都是"朝下"——那是顶点绕序
        // 造成的，不是真实朝向。所以这里只保证法线与平面垂直，符号交给取点时
        // 按观察者所在侧翻（AimPlacement），朝向分类只看 |n.y|。
        val fromPolygon = PlaneShape.normalFromPolygon(verts)
        val fromOrientation = when (anchor.planeOrientation) {
            PlaneOrientation.HORIZONTAL_UPWARD, PlaneOrientation.HORIZONTAL_DOWNWARD -> Vec3.UP
            PlaneOrientation.VERTICAL -> quat.rotate(Vec3.BACK)
            else -> null
        }
        val normal = fromPolygon ?: fromOrientation ?: quat.rotate(Vec3.BACK)
        val kind = kindOf(normal)

        val uHint = quat.rotate(Vec3(1f, 0f, 0f))
        val bbox = anchor.boundingBoxSize
        val halfU = abs(bbox.x) / 2f
        val halfV = abs(bbox.y) / 2f
        // P1[高]#1 真正的幽灵平面入口：boundingBoxSize 没做有限性校验，而
        // PlaneShape.build 只校验 center/normal，NaN 的 halfU/halfV 会被原样带进
        // shape；面积退化成 4*NaN=NaN，旧判定 `area < 0.005f` 对 NaN 恒 false，
        // 平面不被丢弃、covers 又因 NaN 比较恒 false 永远点不中 → "日志成功、
        // 永不命中"的幽灵平面。这里显式丢弃非有限尺寸。
        if (!halfU.isFinite() || !halfV.isFinite()) {
            Log.w(TAG, "plane ${anchor.anchorUUID} boundingBoxSize 非有限值 (${bbox.x},${bbox.y})，丢弃")
            return null
        }

        // 轮廓：只在顶点与 anchor 位姿**同一个坐标系**时才用。
        //
        // 2026-11-10 模拟器实测的坑：`PlaneAnchor.vertices` 与 `transform.position`
        // 不是同一个坐标系。桌面那张平面，位姿在 (-4.25, 1.10, -3.37)，
        // 而四个顶点算出来的质心在 (0.00, 1.10, 0.00) —— 差了 5.4 米。
        // 如果直接拿顶点投影当轮廓，多边形会整体偏到房间另一头，
        // 射线打在自己的中心点上反而"不命中"，功能看起来完全坏的。
        //
        // 自校验：质心与位姿差 < 0.35m 才认为两者一致，用精确轮廓；
        // 否则退回 boundingBoxSize 的矩形（宽度方向与四元数基一致，是对的）。
        val base = PlaneShape.build(center, normal, uHint, halfU, halfV)
            ?: run {
                Log.w(TAG, "plane ${anchor.anchorUUID} 法线退化，丢弃")
                return null
            }
        // P1[中]#2：形心改用面积加权（鞋带/扇形三角剖分），不再用顶点算术平均。
        // 算术平均对顶点疏密不均的合法大平面（L 形桌、整墙）会偏离 anchor 的
        // 面积/包围盒中心，越过 0.35m 绝对阈值 → 同坐标系的正确轮廓被误退回矩形。
        val polyCentroid = if (verts.size >= 3) centroidOf(verts, normal) else null
        val centroidGap = polyCentroid?.let { (it - center).length() } ?: Float.MAX_VALUE
        val useOutline = polyCentroid != null && centroidGap < 0.35f
        val outline = if (useOutline) verts.map { base.project(it) } else null
        val shape = if (outline != null) {
            base.copy(outline = outline, halfU = halfU.coerceAtLeast(0.01f), halfV = halfV.coerceAtLeast(0.01f))
        } else base

        val area = if (outline != null) {
            polygonArea(outline)
        } else {
            4f * halfU * halfV
        }
        if (!(area >= 0.005f)) {
            // 5cm² 以下的碎片（噪声平面）不参与取点：点中了也没法放卡片。
            // 用 `!(area >= …)` 而非 `area < …`：后者对 NaN 恒 false，会让面积
            // 非有限的僵尸平面躲过过滤（P1[高]#1 的兜底防线）。
            Log.i(TAG, "plane ${anchor.anchorUUID} 面积 ${"%.4f".format(area)} m² 过小/非法，丢弃")
            return null
        }

        val raw = anchor.semantics?.name ?: "UNKNOWN"
        val label = PlaneSemantics.label(anchor.semantics)
        Log.i(
            TAG,
            "plane ${anchor.anchorUUID} kind=${kind} raw=$raw label=$label " +
                "center=(${center.x},${center.y},${center.z}) " +
                "normal=(${normal.x},${normal.y},${normal.z}) " +
                "normalSrc=${if (fromPolygon != null) "polygon" else if (fromOrientation != null) "orientation" else "quat"} " +
                "verts=${verts.size} outline=$useOutline centroidGap=${"%.2f".format(centroidGap)}m " +
                "area=${"%.2f".format(area)}m² " +
                // 轮廓的局部包围盒：用来判断 SDK 给的 anchor 中心是否落在多边形内
                // （如果位姿在角点上，中心恰好压在边界上，取点会因边界判定失败）
                "uvBox=" + (outline?.let {
                    val us = it.map { p -> p.u }
                    val vs = it.map { p -> p.v }
                    "[${"%.2f".format(us.min())},${"%.2f".format(us.max())}]x" +
                        "[${"%.2f".format(vs.min())},${"%.2f".format(vs.max())}]"
                } ?: "rect"),
        )
        return RoomPlane(
            uuid = anchor.anchorUUID.toString(),
            shape = shape,
            kind = kind,
            label = label,
            rawLabel = raw,
            areaM2 = area,
        )
    }

    /**
     * 由法线判定朝向分类。阈值取 0.85 / 0.25：
     * 0.85 ≈ 偏离竖直 32°，略有倾斜的桌面仍算水平面；
     * 0.25 ≈ 偏离竖直 76°，斜面单独归类，避免"倾斜的墙"被当成桌子。
     */
    private fun kindOf(normal: Vec3): RoomPlane.Kind {
        val ay = abs(normal.y)
        return when {
            ay > 0.85f -> RoomPlane.Kind.HORIZONTAL
            ay < 0.25f -> RoomPlane.Kind.VERTICAL
            else -> RoomPlane.Kind.ARBITRARY
        }
    }

    /**
     * 顶点多边形的**面积加权形心**（扇形三角剖分 + 有符号面积），用于校验
     * vertices 与 transform.position 是否同一坐标系。
     *
     * 相比旧的顶点算术平均：凹形/疏密不均的合法轮廓（L 形桌、带缺口的墙）算出的
     * 形心与 anchor 的面积/包围盒中心一致，不会误判坐标系不同（P1[中]#2）。
     * 用有符号面积累加，对简单凹多边形也成立（重叠三角自动相消）。
     *
     * 先过滤非有限顶点（调用方已 filter，这里再兜一层防 NaN 拽偏）。
     * 顶点少于 3 / 面积≈0 / 法线退化 → 返回 null（退回矩形轮廓）。
     * normal 约定为单位向量；即使非单位，只影响有符号面积的缩放，形心是缩放不变量，
     * 结果仍正确（前提：normal 与多边形平面近似垂直、非零）。
     */
    fun centroidOf(pts: List<Vec3>, normal: Vec3): Vec3? {
        val p = pts.filter { it.isFinite() }
        if (p.size < 3) return null
        val p0 = p[0]
        var area2 = 0f // 2× 有符号总面积
        var sx = 0f; var sy = 0f; var sz = 0f
        for (i in 1 until p.size - 1) {
            val a = p[i] - p0
            val b = p[i + 1] - p0
            val signed = (a cross b) dot normal
            area2 += signed
            sx += signed * (p0.x + p[i].x + p[i + 1].x) / 3f
            sy += signed * (p0.y + p[i].y + p[i + 1].y) / 3f
            sz += signed * (p0.z + p[i].z + p[i + 1].z) / 3f
        }
        if (abs(area2) < 1e-6f) return null
        return Vec3(sx / area2, sy / area2, sz / area2)
    }

    /** 鞋带公式，返回绝对面积。 */
    fun polygonArea(poly: List<Vec2>): Float {
        if (poly.size < 3) return 0f
        var sum = 0f
        for (i in poly.indices) {
            val a = poly[i]
            val b = poly[(i + 1) % poly.size]
            sum += a.u * b.v - b.u * a.v
        }
        return abs(sum) / 2f
    }
}