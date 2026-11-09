package com.memorypalace.core.importer

/**
 * 纯文本导入（M5）。一行一条：`正面<分隔符>背面`。
 * 分隔符优先级：TAB → " :: " → "——" → " - "。
 * 空行与 # 开头的注释行忽略；没有分隔符的行整行当正面（背面为空，便于只存问题/单词）。
 */
object TextImporter {

    private val SEPARATORS = listOf("\t", " :: ", "——", " - ")

    fun parse(text: String): List<ImportedCard> {
        return text.removePrefix("\uFEFF").lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { line ->
                val sep = SEPARATORS.firstOrNull { line.contains(it) }
                if (sep == null) {
                    ImportedCard(front = line, back = "")
                } else {
                    val idx = line.indexOf(sep)
                    ImportedCard(
                        front = line.substring(0, idx).trim(),
                        back = line.substring(idx + sep.length).trim(),
                    )
                }
            }
            .filter { it.front.isNotEmpty() }
            .toList()
    }
}
