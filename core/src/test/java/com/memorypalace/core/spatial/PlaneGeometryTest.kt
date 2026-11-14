package com.memorypalace.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 平面取点判定（M16）。
 *
 * 这一组测试的意义：`PlanePicker` 是「把卡贴到真实家具上」唯一的判定内核。
 * 模拟器没有平面、没有手柄，真机也不方便反复回归 —— 所以它的正确性必须
 * 在一个不依赖任何设备的层面锁死。这里每一个用例都对应一种真实会遇到的
 * 空间关系（手表打在自己的正前方、卡片被塞到桌沿外、地板和桌面同时被命中）。
 */
class PlaneGeometryTest {

    private val table = planeAt(Vec3(0f, 0.75f, -1f), Vec3.UP, 0.6f, 0.4f)
    private val floor = planeAt(Vec3(0f, 0f, -1f), Vec3.UP, 1.5f, 1.5f)

    private fun planeAt(
        center: Vec3,
        normal: Vec3,
        halfU: Float,
        halfV: Float,
        outline: List<Vec2>? = null,
    ): PlaneShape = PlaneShape.build(center, normal, Vec3(1f, 0f, 0f), halfU, halfV, outline)!!

    // ---------- 基本命中 ----------

    @Test
    fun `正下方射线命中平面中心`() {
        val ray = Ray.of(Vec3(0f, 1.5f, -1f), Vec3.DOWN)
        val hit = PlanePicker.hit(table, ray)
        assertNotNull(hit)
        assertEquals(0.75f, hit!!.distance, 1e-4f)
        assertEquals(0f, hit.local.u, 1e-4f)
        assertEquals(0f, hit.local.v, 1e-4f)
    }

    @Test
    fun `平行射线不命中`() {
        val ray = Ray.of(Vec3(0f, 0.75f, -1f), Vec3(1f, 0f, 0f))
        assertNull(PlanePicker.hit(table, ray))
    }

    @Test
    fun `背向射线不命中`() {
        val ray = Ray.of(Vec3(0f, 2f, -1f), Vec3.UP)
        assertNull("射线朝上，平面在下方，必须判不命中", PlanePicker.hit(table, ray))
    }

    @Test
    fun `超过最远距离不命中`() {
        val ray = Ray.of(Vec3(0f, 10f, -1f), Vec3.DOWN)
        assertNull(PlanePicker.hit(table, ray, maxDistance = 5f))
    }

    @Test
    fun `刚好落在最远距离内仍然命中`() {
        val ray = Ray.of(Vec3(0f, 5.4f, -1f), Vec3.DOWN)
        assertNotNull(PlanePicker.hit(floor, ray, maxDistance = 5.5f))
    }

    // ---------- 边界 ----------

    @Test
    fun `超出矩形范围不命中`() {
        // 桌面 halfU=0.6，从 u=0.9 处垂直向下
        val ray = Ray.of(Vec3(0.9f, 1.5f, -1f), Vec3.DOWN)
        assertNull(PlanePicker.hit(table, ray))
    }

    @Test
    fun `矩形边缘内侧命中`() {
        val ray = Ray.of(Vec3(0.59f, 1.5f, -1f), Vec3.DOWN)
        assertNotNull(PlanePicker.hit(table, ray))
    }

    @Test
    fun `margin 允许少量越界`() {
        val ray = Ray.of(Vec3(0.65f, 1.5f, -1f), Vec3.DOWN)
        assertNull(PlanePicker.hit(table, ray))
        val local = table.project(Vec3(0.65f, 0.75f, -1f))
        assertTrue("允许 5cm 余量时应命中", table.covers(local, margin = 0.05f))
    }

    // ---------- 最近优先 ----------

    @Test
    fun `地板与桌面同时命中时取最近的桌面`() {
        val ray = Ray.of(Vec3(0f, 1.6f, -1f), Vec3.DOWN)
        val hit = PlanePicker.pick(listOf(floor, table), ray)
        assertNotNull(hit)
        assertEquals("应当命中桌面（0.85m）而不是地板（1.6m）", 0.85f, hit!!.distance, 1e-3f)
    }

    @Test
    fun `没有平面可命中时返回 null`() {
        val ray = Ray.of(Vec3(0f, 1.6f, -1f), Vec3.UP)
        assertNull(PlanePicker.pick(listOf(floor, table), ray))
    }

    @Test
    fun `空平面列表返回 null`() {
        assertNull(PlanePicker.pick(emptyList(), Ray.of(Vec3(0f, 1.6f, 0f), Vec3.DOWN)))
    }

    // ---------- 非矩形轮廓 ----------

    @Test
    fun `L 形桌面缺口处不命中`() {
        // 2m × 2m 正方形挖掉右上角 1m × 1m，形成 L 形
        val lShape = listOf(
            Vec2(-1f, -1f), Vec2(1f, -1f), Vec2(1f, 0f),
            Vec2(0f, 0f), Vec2(0f, 1f), Vec2(-1f, 1f),
        )
        val desk = planeAt(Vec3(0f, 0.8f, 0f), Vec3.UP, 1f, 1f, lShape)
        // 世界坐标必须由局部 uv 反推 —— 直接写世界坐标会踩到 v 轴正向的定义
        fun worldAt(u: Float, v: Float) = desk.center + desk.u * u + desk.v * v
        // 缺口中心 uv=(0.5, 0.5) 在轮廓外
        assertNull(
            "L 形缺口处不该命中，否则卡片会悬空",
            PlanePicker.hit(desk, Ray.of(worldAt(0.5f, 0.5f) + Vec3.UP * 0.7f, Vec3.DOWN)),
        )
        // 实体部分 uv=(0.5, -0.5) 在轮廓内
        assertNotNull(PlanePicker.hit(desk, Ray.of(worldAt(0.5f, -0.5f) + Vec3.UP * 0.7f, Vec3.DOWN)))
    }

    @Test
    fun `多边形法线由顶点算出`() {
        val tri = listOf(
            Vec3(0f, 0f, 0f),
            Vec3(1f, 0f, 0f),
            Vec3(0f, 0f, -1f),
        )
        val n = PlaneShape.normalFromPolygon(tri)
        assertNotNull(n)
        assertEquals("叉积方向应为 +Y", 1f, n!!.y, 1e-5f)
    }

    @Test
    fun `共线顶点求不出法线`() {
        val line = listOf(Vec3(0f, 0f, 0f), Vec3(1f, 0f, 0f), Vec3(2f, 0f, 0f))
        assertNull(PlaneShape.normalFromPolygon(line))
    }

    @Test
    fun `少于三个顶点求不出法线`() {
        assertNull(PlaneShape.normalFromPolygon(listOf(Vec3.ZERO, Vec3.UP)))
    }

    // ---------- 基向量构造 ----------

    @Test
    fun `构造结果满足正交右手系`() {
        val p = planeAt(Vec3(1f, 2f, 3f), Vec3(0.3f, 0.9f, 0.2f), 0.5f, 0.5f)
        assertEquals(0f, p.normal dot p.u, 1e-5f)
        assertEquals(0f, p.normal dot p.v, 1e-5f)
        assertEquals(0f, p.u dot p.v, 1e-5f)
        assertEquals(1f, p.u.length(), 1e-5f)
        assertEquals(1f, p.v.length(), 1e-5f)
        // u × v 必须等于 normal，否则投影的符号会整体反掉
        val cross = p.u cross p.v
        assertEquals(1f, cross dot p.normal, 1e-5f)
    }

    @Test
    fun `u 提示与法线平行时自动兜底`() {
        // 法线 +Y，u 提示也给 +Y（退化）→ 必须换一根轴，不能返回零向量
        val p = PlaneShape.build(Vec3.ZERO, Vec3.UP, Vec3.UP, 1f, 1f)
        assertNotNull(p)
        assertEquals(1f, p!!.u.length(), 1e-5f)
        assertEquals(0f, p.u.y, 1e-5f)
    }

    @Test
    fun `零法线构造失败返回 null`() {
        assertNull("零向量法线必须显式失败，不能带 NaN 继续", PlaneShape.build(Vec3.ZERO, Vec3.ZERO, null, 1f, 1f))
    }

    // ---------- 位姿 → 射线 ----------

    @Test
    fun `单位旋转的射线朝前`() {
        val ray = PlanePicker.rayFromPose(Vec3.ZERO, Quat.IDENTITY)
        assertEquals(0f, ray.direction.x, 1e-5f)
        assertEquals(0f, ray.direction.y, 1e-5f)
        assertEquals(-1f, ray.direction.z, 1e-5f)
    }

    @Test
    fun `绕 Y 轴 90 度后射线朝左`() {
        val half = Math.toRadians(45.0).toFloat()
        val q = Quat(0f, kotlin.math.sin(half), 0f, kotlin.math.cos(half))
        val ray = PlanePicker.rayFromPose(Vec3.ZERO, q)
        assertEquals(-1f, ray.direction.x, 1e-4f)
        assertEquals(0f, ray.direction.y, 1e-4f)
        assertEquals(0f, ray.direction.z, 1e-4f)
    }

    @Test
    fun `射线方向先归一化再算距离`() {
        // 手写一个未归一化的方向：距离必须以米计，不能受向量长度影响
        val ray = Ray.of(Vec3(0f, 2f, -1f), Vec3(0f, -7f, 0f))
        val hit = PlanePicker.hit(table, ray)
        assertNotNull(hit)
        assertEquals(1.25f, hit!!.distance, 1e-4f)
    }

    // ---------- 朝向与偏移 ----------

    @Test
    fun `卡片朝向观察者`() {
        val hit = Vec3(0f, 0.75f, -1f)
        assertEquals(0f, PlanePicker.yawToward(hit, Vec3(0f, 1.6f, 0f)), 1e-3f)
        assertEquals(90f, PlanePicker.yawToward(hit, Vec3(1f, 1.6f, -1f)), 1e-3f)
        assertEquals(-90f, PlanePicker.yawToward(hit, Vec3(-1f, 1.6f, -1f)), 1e-3f)
    }

    @Test
    fun `观察者与命中点重合时不产生 NaN`() {
        val p = Vec3(0f, 0.75f, -1f)
        assertEquals(0f, PlanePicker.yawToward(p, p), 0f)
    }

    @Test
    fun `抬高量沿法线方向`() {
        val lifted = PlanePicker.lift(Vec3(0f, 0.75f, -1f), Vec3.UP, 0.02f)
        assertEquals(0.77f, lifted.y, 1e-5f)
        assertEquals(0f, lifted.x, 1e-5f)
    }

    // ---------- 投影一致性 ----------

    @Test
    fun `投影与反向重建一致`() {
        val p = planeAt(Vec3(1f, 2f, 3f), Vec3(0.2f, 0.95f, 0.1f), 0.5f, 0.5f)
        val world = p.center + p.u * 0.3f + p.v * -0.2f
        val local = p.project(world)
        assertEquals(0.3f, local.u, 1e-5f)
        assertEquals(-0.2f, local.v, 1e-5f)
        val back = p.center + p.u * local.u + p.v * local.v
        assertEquals(0f, (back - world).length(), 1e-5f)
    }

    @Test
    fun `斜向射线命中距离等于真实欧氏距离`() {
        // 用一块 4m×4m 的大平面，避免斜射落点跑出桌面范围（那是另一个用例）
        val big = planeAt(Vec3(0f, 0.75f, -1f), Vec3.UP, 2f, 2f)
        // 从 (0, 1.75, -1) 朝斜下方 45° 打，平面在 y=0.75 → 竖直落差 1m，距离 √2
        val dir = Vec3(0f, -1f, -1f).normalized()!!
        val ray = Ray.of(Vec3(0f, 1.75f, -1f), dir)
        val hit = PlanePicker.hit(big, ray, maxDistance = 5f)
        assertNotNull(hit)
        assertEquals(kotlin.math.sqrt(2f), hit!!.distance, 1e-4f)
        assertTrue(abs(hit.point.y - 0.75f) < 1e-4f)
    }

    // ---------- [P1 阻断] normalized() 的 NaN / Inf 护栏 ----------

    @Test
    fun `零向量归一化返回 null`() {
        assertNull(Vec3.ZERO.normalized())
        assertNull(Vec3(0f, 0f, 0f).normalized())
    }

    @Test
    fun `长度小于 EPS 的极小向量归一化返回 null`() {
        // 有限值先放行入口，再由 len<EPS 拒绝；1e-30 平方在 Float 下溢为 0 也走此路
        assertNull(Vec3(1e-30f, 0f, 0f).normalized())
        assertNull(Vec3(1e-8f, 0f, 0f).normalized())
    }

    @Test
    fun `含 NaN 分量的向量归一化返回 null 而非 NaN 向量`() {
        assertNull("NaN 分量绝不能静默产出 NaN 坐标", Vec3(Float.NaN, 1f, 0f).normalized())
        assertNull(Vec3(0f, Float.NaN, 0f).normalized())
        assertNull(Vec3(1f, 0f, Float.NaN).normalized())
    }

    @Test
    fun `含 Inf 分量的向量归一化返回 null`() {
        assertNull(Vec3(0f, Float.POSITIVE_INFINITY, 0f).normalized())
        assertNull(Vec3(Float.NEGATIVE_INFINITY, 0f, 0f).normalized())
        assertNull(Vec3(1f, 0f, Float.POSITIVE_INFINITY).normalized())
    }

    @Test
    fun `有限向量归一化后仍有限且为单位长`() {
        val v = Vec3(3f, -4f, 0f).normalized()
        assertNotNull(v)
        assertTrue(v!!.isFinite())
        assertEquals(1f, v.length(), 1e-6f)
    }

    // ---------- [P1 高] Quat.normalized() 的 NaN / Inf 护栏 ----------

    @Test
    fun `含 NaN 的四元数归一化退回 IDENTITY 而非 NaN 四元数`() {
        assertEquals(Quat.IDENTITY, Quat(Float.NaN, 0f, 0f, 1f).normalized())
        assertEquals(Quat.IDENTITY, Quat(0f, Float.NaN, 0f, 1f).normalized())
        val q = Quat(0f, 0f, 0f, Float.POSITIVE_INFINITY).normalized()
        assertEquals(Quat.IDENTITY, q)
        assertTrue(
            "归一化结果不得含 NaN/Inf 分量",
            q.x.isFinite() && q.y.isFinite() && q.z.isFinite() && q.w.isFinite(),
        )
    }

    @Test
    fun `零四元数归一化退回 IDENTITY`() {
        assertEquals(Quat.IDENTITY, Quat(0f, 0f, 0f, 0f).normalized())
    }

    @Test
    fun `归一化四元数旋转单位向量仍保持单位长`() {
        val q = Quat(0.1f, 0.2f, -0.3f, 0.9f).normalized()
        val r = q.rotate(Vec3(0f, 0f, -1f))
        assertTrue(r.isFinite())
        assertEquals(1f, r.length(), 1e-4f)
    }

    // ---------- 近平行阈值两侧 ----------

    @Test
    fun `射线近平行低于阈值不命中`() {
        val big = planeAt(Vec3(0f, 0f, 0f), Vec3.UP, 100f, 100f)
        // |n·d| ≈ 1e-6 < 1e-4；若无阈值、t≈0.1 本会命中，故此处专测近平行护栏
        val ray = Ray.of(Vec3(0f, 1e-7f, 0f), Vec3(0f, -1e-6f, -1f))
        assertNull(PlanePicker.hit(big, ray))
    }

    @Test
    fun `射线刚过近平行阈值正常命中`() {
        val big = planeAt(Vec3(0f, 0f, 0f), Vec3.UP, 100f, 100f)
        // |n·d| ≈ 5e-3 > 1e-4；t ≈ 2m，落进大面内，应命中且距离有限
        val ray = Ray.of(Vec3(0f, 0.01f, 0f), Vec3(0f, -5e-3f, -1f))
        val hit = PlanePicker.hit(big, ray, maxDistance = 5f)
        assertNotNull(hit)
        assertTrue("命中距离必须是有限值，不能是 Inf/NaN", hit!!.distance.isFinite())
    }

    // ---------- t<0：平面在身后 ----------

    @Test
    fun `平面在射线身后 t 小于零时不命中`() {
        val big = planeAt(Vec3(0f, 0f, 0f), Vec3.UP, 100f, 100f)
        // 原点在平面上方且射线朝上：交点在身后，t = -1
        val ray = Ray.of(Vec3(0f, 1f, 0f), Vec3.UP)
        assertNull("平面在身后（t<0）必须判不命中", PlanePicker.hit(big, ray))
    }

    @Test
    fun `NaN 射线方向被兜底后不命中且不产 NaN 坐标`() {
        val ray = Ray.of(Vec3(0f, 1.5f, -1f), Vec3(Float.NaN, Float.NaN, Float.NaN))
        // 修好的 normalized 对 NaN 方向返回 null → Ray.of 兜底成 FORWARD（有限）
        assertTrue("方向必须被兜底为有限向量", ray.direction.isFinite())
        val hit = PlanePicker.hit(table, ray)
        if (hit != null) assertTrue("命中点绝不允许是 NaN", hit.point.isFinite())
    }

    // ---------- build 拒绝 NaN 法线 / NaN 中心（P1 阻断下游连锁）----------

    @Test
    fun `NaN 法线构造返回 null 不产出 NaN 平面`() {
        assertNull(PlaneShape.build(Vec3.ZERO, Vec3(Float.NaN, 0f, 0f), null, 1f, 1f))
        assertNull(PlaneShape.build(Vec3.ZERO, Vec3(0f, Float.POSITIVE_INFINITY, 0f), null, 1f, 1f))
    }

    @Test
    fun `NaN 中心构造返回 null 不产出僵尸平面`() {
        assertNull(PlaneShape.build(Vec3(Float.NaN, 0f, 0f), Vec3.UP, Vec3(1f, 0f, 0f), 1f, 1f))
    }

    // ---------- 多平面等距 pick 的确定性 ----------

    @Test
    fun `多平面等距命中时 pick 结果确定`() {
        val pA = planeAt(Vec3(0f, 0f, 0f), Vec3.UP, 2f, 2f)
        val pB = planeAt(Vec3(0.5f, 0f, 0.3f), Vec3.UP, 2f, 2f)
        val ray = Ray.of(Vec3(0f, 1f, 0f), Vec3.DOWN)
        assertEquals("构造等距：两平面距离应完全相等", PlanePicker.hit(pA, ray)!!.distance, PlanePicker.hit(pB, ray)!!.distance, 0f)
        // 等距时 minByOrNull 稳定取列表首位 → 结果只依赖列表顺序，重复调用不变
        val first = PlanePicker.pick(listOf(pA, pB), ray)!!
        val second = PlanePicker.pick(listOf(pB, pA), ray)!!
        assertEquals(pA.center, first.plane.center)
        assertEquals(pB.center, second.plane.center)
        repeat(100) {
            assertEquals("等距 pick 必须逐次稳定", first.plane.center, PlanePicker.pick(listOf(pA, pB), ray)!!.plane.center)
        }
    }

    // ---------- 退化平面（bbox=0 / 顶点不足）被正确拒绝 ----------

    @Test
    fun `零尺寸平面仅在正落中心时可命中`() {
        val point = planeAt(Vec3(0f, 0.75f, -1f), Vec3.UP, 0f, 0f)
        assertNotNull("垂直正落中心（local=(0,0)）勉强命中", PlanePicker.hit(point, Ray.of(Vec3(0f, 1.5f, -1f), Vec3.DOWN)))
        assertNull("偏移即被矩形 covers 拒绝（bbox=0 退化）", PlanePicker.hit(point, Ray.of(Vec3(0.1f, 1.5f, -1f), Vec3.DOWN)))
    }

    @Test
    fun `少于三个或退化顶点轮廓求不出法线`() {
        assertNull(PlaneShape.normalFromPolygon(emptyList()))
        assertNull(PlaneShape.normalFromPolygon(listOf(Vec3.ZERO)))
        assertNull(PlaneShape.normalFromPolygon(listOf(Vec3.ZERO, Vec3.ZERO, Vec3.ZERO)))
        // 混入一个 NaN 顶点：差分成 NaN，叉积归一化经护栏返回 null，不外抛
        assertNull(PlaneShape.normalFromPolygon(listOf(Vec3(0f, 0f, 0f), Vec3(Float.NaN, 0f, 0f), Vec3(0f, 0f, -1f))))
    }

    // ---------- acos / 除零类运算：退化输入不抛不 NaN ----------

    @Test
    fun `退化输入下几何运算不抛异常且不产 NaN`() {
        // yawToward：命中点正上方（水平分量退化为 0）→ 0，非 NaN
        assertEquals(0f, PlanePicker.yawToward(Vec3(0f, 0.75f, -1f), Vec3(0f, 1.6f, -1f)), 0f)
        // yawToward：NaN / Inf 输入被入口护栏挡下 → 仍为有限值
        assertTrue(PlanePicker.yawToward(Vec3(Float.NaN, 0f, 0f), Vec3(0f, 1f, 1f)).isFinite())
        assertTrue(PlanePicker.yawToward(Vec3(0f, 0f, 0f), Vec3(0f, 1f, Float.POSITIVE_INFINITY)).isFinite())
        // pointInPolygon：含水平边（除零隐患处）→ 不抛、结果确定
        val square = listOf(Vec2(-1f, -1f), Vec2(1f, -1f), Vec2(1f, 1f), Vec2(-1f, 1f))
        assertTrue(PlaneShape.pointInPolygon(square, Vec2(0f, 0f)))
        assertFalse(PlaneShape.pointInPolygon(square, Vec2(2f, 0f)))
        val onBottom = PlaneShape.pointInPolygon(square, Vec2(0f, -1f))
        assertEquals("扫描线贴水平边应确定", onBottom, PlaneShape.pointInPolygon(square, Vec2(0f, -1f)))
    }
}