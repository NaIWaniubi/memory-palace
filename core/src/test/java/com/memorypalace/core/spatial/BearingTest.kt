package com.memorypalace.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 逻辑方位罗盘单测（P3 维度 4：把"按方位发声"变成可核查的几何证据）。
 *
 * 坐标约定与被测代码一致：右手系 Y 上、观察者默认站在原点朝 −Z（进入朝向）。
 * 符号是关键契约：**方位角 + 为左、− 为右**（左 = 俯视逆时针），转身后同一目标
 * 的角度必须跟着变 —— 这两条错了，罗盘显示和真机声场方位就会整体镜像。
 */
class BearingTest {

    private companion object {
        const val EPS = 1e-3f
        val HEAD = Vec3(0f, 1.6f, 0f)
        val FWD = Vec3(0f, 0f, -1f)
    }

    // ---------- 基本方位 ----------

    @Test fun `正前方的目标方位角为 0`() {
        val b = BearingGuide.of(HEAD, FWD, Vec3(0f, 1.6f, -2f))!!
        assertEquals(0f, b.azimuthDeg, EPS)
        assertEquals(0f, b.elevationDeg, EPS)
        assertEquals(2f, b.distanceM, EPS)
        assertEquals("正前", b.side)
    }

    @Test fun `左手边的目标方位角为正 90`() {
        // 朝 −Z 时，右手系 +Y 向上的"左"是 −X
        val b = BearingGuide.of(HEAD, FWD, Vec3(-2f, 1.6f, 0f))!!
        assertEquals(90f, b.azimuthDeg, EPS)
        assertEquals("正左", b.side)
        assertEquals(3, b.dialCell)
    }

    @Test fun `右手边的目标方位角为负 90`() {
        val b = BearingGuide.of(HEAD, FWD, Vec3(2f, 1.6f, 0f))!!
        assertEquals(-90f, b.azimuthDeg, EPS)
        assertEquals("正右", b.side)
        assertEquals(5, b.dialCell)
    }

    @Test fun `正后方的目标是 180 度 正后`() {
        val b = BearingGuide.of(HEAD, FWD, Vec3(0f, 1.6f, 3f))!!
        assertEquals(180f, Math.abs(b.azimuthDeg), 0.01f)
        assertEquals("正后", b.side)
        assertEquals(7, b.dialCell)
    }

    @Test fun `左前 45 度`() {
        val b = BearingGuide.of(HEAD, FWD, Vec3(-1f, 1.6f, -1f))!!
        assertEquals(45f, b.azimuthDeg, EPS)
        assertEquals("左前", b.side)
        assertEquals(0, b.dialCell)
    }

    @Test fun `右后 135 度`() {
        val b = BearingGuide.of(HEAD, FWD, Vec3(1f, 1.6f, 1f))!!
        assertEquals(-135f, b.azimuthDeg, EPS)
        assertEquals("右后", b.side)
        assertEquals(8, b.dialCell)
    }

    // ---------- 角度语义：跟着观察者转 ----------

    @Test fun `转身九十度后 同一目标的相对方位跟着变`() {
        // 卡片固定在 (0,·,-2)。初始朝 −Z 时在正前；观察者右转 90°（朝向变 +X）
        // 后，同一目标应出现在正左 —— 角度跟着人转，这是罗盘的核心契约。
        val card = Vec3(0f, 1.6f, -2f)
        val before = BearingGuide.of(HEAD, FWD, card)!!
        assertEquals(0f, before.azimuthDeg, EPS)
        val after = BearingGuide.of(HEAD, Vec3(1f, 0f, 0f), card)!!
        assertEquals(90f, after.azimuthDeg, EPS)
    }

    @Test fun `八方位分桶边界 22_5 度仍算正前`() {
        // 22.5° 左前边界：取 20° 必为正前，30° 必为左前
        val a20 = Math.toRadians(20.0)
        val b20 = BearingGuide.of(HEAD, FWD, Vec3((-Math.sin(a20)).toFloat(), 1.6f, (-Math.cos(a20)).toFloat()))!!
        assertEquals("正前", b20.side)
        val a30 = Math.toRadians(30.0)
        val b30 = BearingGuide.of(HEAD, FWD, Vec3((-Math.sin(a30)).toFloat(), 1.6f, (-Math.cos(a30)).toFloat()))!!
        assertEquals("左前", b30.side)
    }

    // ---------- 俯仰与距离 ----------

    @Test fun `正上方目标 俯仰为正 90`() {
        val b = BearingGuide.of(HEAD, FWD, Vec3(0f, 3.6f, 0f))!!
        assertEquals(90f, b.elevationDeg, EPS)
        assertEquals(0f, b.azimuthDeg, EPS)
    }

    @Test fun `桌面高度的卡 俯仰为负`() {
        val b = BearingGuide.of(HEAD, FWD, Vec3(0f, 0.8f, -1f))!!
        assertTrue(b.elevationDeg < 0f)
    }

    @Test fun `三维直线距离`() {
        // 水平 3m、高差 -1.2m+... 用 0.6/0.8/3 勾股：3-4-5 缩放
        val b = BearingGuide.of(HEAD, FWD, Vec3(0f, 1.6f - 1.2f, -1.6f))!!
        assertEquals(2f, b.distanceM, EPS)
    }

    @Test fun `俯仰文案 十度内算同高`() {
        val flat = Bearing(10f, 5f, 1f)
        assertEquals("与视线同高", BearingGuide.elevationText(flat))
        val up = Bearing(0f, 45f, 1f)
        assertEquals("上方 45°", BearingGuide.elevationText(up))
        val down = Bearing(0f, -45f, 1f)
        assertEquals("下方 45°", BearingGuide.elevationText(down))
    }

    // ---------- 退化输入：宁可 null，不产 NaN ----------

    @Test fun `目标与观察者重合 给出零距离中性结果`() {
        val b = BearingGuide.of(HEAD, FWD, HEAD)!!
        assertEquals(0f, b.azimuthDeg, 0f)
        assertEquals(0f, b.distanceM, 0f)
    }

    @Test fun `NaN 输入返回 null 而不是 NaN 角度`() {
        val nan = Float.NaN
        assertNull(BearingGuide.of(HEAD, FWD, Vec3(nan, 1f, 1f)))
        assertNull(BearingGuide.of(HEAD, Vec3(0f, nan, -1f), Vec3(0f, 1f, -1f)))
        assertNull(BearingGuide.of(Vec3(Float.POSITIVE_INFINITY, 0f, 0f), FWD, Vec3(1f, 1f, 1f)))
    }

    @Test fun `竖直朝向无法定义左右 返回 null`() {
        assertNull(BearingGuide.of(HEAD, Vec3(0f, 1f, 0f), Vec3(1f, 1f, 1f)))
    }

    // ---------- 展示文案 ----------

    @Test fun `format 输出方位词加角度加距离`() {
        val b = Bearing(35f, 0f, 2.1f)
        val s = b.format()
        assertTrue(s, s.contains("左前"))
        assertTrue(s, s.contains("35°"))
        assertTrue(s, s.contains("2.1m"))
    }

    @Test fun `罗盘文本 标记格换成实心点`() {
        val dial = BearingGuide.dialText(0)
        val lines = dial.lines()
        assertEquals(3, lines.size)
        assertEquals('●', lines[0].first())
        assertTrue(dial, lines[1].contains('◉'))
        // 不标记（中央 4）时保持原样箭头
        val plain = BearingGuide.dialText(4)
        assertEquals('↖', plain.lines()[0].first())
    }

    @Test fun `fromStageOrigin 以进入朝向为基准`() {
        val b = BearingGuide.fromStageOrigin(Vec3(0f, 1.6f, -1f))
        assertNotNull(b)
        assertEquals(0f, b!!.azimuthDeg, EPS)
    }

    @Test fun `水平距离忽略高差`() {
        assertEquals(5f, BearingGuide.horizontalDistance(Vec3(0f, 0f, 0f), Vec3(3f, 99f, -4f)), EPS)
        assertTrue(BearingGuide.horizontalDistance(Vec3(Float.NaN, 0f, 0f), Vec3.ZERO).isNaN())
    }
}
