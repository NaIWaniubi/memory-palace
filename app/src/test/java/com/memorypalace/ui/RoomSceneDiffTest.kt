package com.memorypalace.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P3 卡死项「RoomStage 用 key(records,tick) 整树重建」的核心修复下沉为纯差集 [planSceneDiff]：
 * 只输出真正变化的卡（新增/删除/签名变），未变的卡不进任何列表 → 复用现有实体，不再 churn。
 * 锁死「整树重建 → 增量」这条路径的正确性，无需 Android/SDK。
 */
class RoomSceneDiffTest {

    private fun s(key: String, sig: String = "u|$key") = SceneMountState(key, sig)

    @Test
    fun `全新增时 desired 全部进 toAdd 且无 remove 与 replace`() {
        val diff = planSceneDiff(emptyMap(), listOf(s("a"), s("b"), s("c")))
        assertEquals(listOf("a", "b", "c"), diff.toAdd.map { it.key })
        assertTrue(diff.toRemove.isEmpty())
        assertTrue(diff.toReplace.isEmpty())
    }

    @Test
    fun `签名不变的卡不进任何列表 - 这是消除整树 churn 的关键`() {
        val mounted = mapOf("a" to "u|a", "b" to "u|b")
        // 同 key、同签名
        val diff = planSceneDiff(mounted, listOf(s("a", "u|a"), s("b", "u|b")))
        assertTrue("未变的卡不该被重挂", diff.isEmpty)
    }

    @Test
    fun `同 key 签名变化 = 单卡替换而非整树重建`() {
        val mounted = mapOf("a" to "uuid1|1f", "b" to "uuid1|2f")
        // a 重新锚定成功（uuid 变化），b 不变
        val diff = planSceneDiff(mounted, listOf(s("a", "uuid2|1f"), s("b", "uuid1|2f")))
        assertEquals(listOf("a"), diff.toReplace.map { it.key })
        assertTrue(diff.toAdd.isEmpty())
        assertTrue(diff.toRemove.isEmpty())
    }

    @Test
    fun `删除的卡进 toRemove`() {
        val mounted = mapOf("a" to "x", "b" to "y", "c" to "z")
        val diff = planSceneDiff(mounted, listOf(s("b", "y"), s("c", "z")))
        assertEquals(listOf("a"), diff.toRemove)
        assertTrue(diff.toAdd.isEmpty())
        assertTrue(diff.toReplace.isEmpty())
    }

    @Test
    fun `一张卡被清除 + 一张新卡进入 + 一张降级 = 各自只动自己`() {
        val mounted = mapOf(
            "keep" to "sig-keep",          // 不变
            "gone" to "sig-gone",          // 删除
            "downgrade" to "uuid1|p",      // 降级 → 签名变
        )
        val desired = listOf(s("keep", "sig-keep"), s("downgrade", "null|p"), s("fresh", "sig-fresh"))
        val diff = planSceneDiff(mounted, desired)
        assertEquals(listOf("fresh"), diff.toAdd.map { it.key })
        assertEquals(listOf("gone"), diff.toRemove)
        assertEquals(listOf("downgrade"), diff.toReplace.map { it.key })
    }

    @Test
    fun `desired 内重复 key 以最后一条为准 LinkedHashMap 去重`() {
        val diff = planSceneDiff(emptyMap(), listOf(s("a", "v1"), s("a", "v2")))
        assertEquals(1, diff.toAdd.size)
        assertEquals("v2", diff.toAdd.single().signature)
    }

    @Test
    fun `空 desired 清空全部已挂载`() {
        val diff = planSceneDiff(mapOf("a" to "x", "b" to "y"), emptyList())
        assertTrue(diff.toAdd.isEmpty())
        assertTrue(diff.toReplace.isEmpty())
        assertEquals(setOf("a", "b"), diff.toRemove.toSet())
    }

    @Test
    fun `sceneSignature 纳入 uuid 与位姿 - 二者任一变化都应判为签名变`() {
        val base = com.memorypalace.anchor.AnchorRecord(
            key = "k", uuid = "u1", title = "t",
            px = 1f, py = 0f, pz = 0f, pitch = 0f, yaw = 0f, roll = 0f,
            placedAt = 0L, anchorLabel = null,
        )
        val sig1 = sceneSignature(base)
        val sigUuidChanged = sceneSignature(base.copy(uuid = "u2"))
        val sigPosChanged = sceneSignature(base.copy(px = 9f))
        val sigSame = sceneSignature(base.copy(title = "different-title-not-in-signature"))
        assertTrue(sig1 != sigUuidChanged)
        assertTrue(sig1 != sigPosChanged)
        // title 不进签名：title 变了但 UUID/位姿没变，实体无需重挂（面板内容另有 AnchorCard 观察 cards 流刷新）
        assertEquals(sig1, sigSame)
    }
}
