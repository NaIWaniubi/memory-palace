package com.memorypalace.core.importer

/** 导入得到的一条卡片（尚未进调度系统）。 */
data class ImportedCard(val front: String, val back: String)

/** 导入结果统计。 */
data class ImportStats(
    val format: String,
    val parsed: Int,      // 解析出的条目数
    val imported: Int,    // 实际入库数
    val skippedDup: Int,  // 按 front 去重跳过数
) {
    override fun toString(): String =
        "format=$format parsed=$parsed imported=$imported skippedDup=$skippedDup"
}
