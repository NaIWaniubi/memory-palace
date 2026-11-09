package com.memorypalace.core.importer

/**
 * CSV 导入（M5）。标准 CSV：每行 `正面,背面`，支持引号包裹（字段内可含逗号/换行，
 * 双引号用 "" 转义）。首行若为表头（front/back、正面/背面等）自动跳过。
 * 纯 JVM 实现，不依赖任何库。
 */
object CsvImporter {

    private val HEADER_HINTS = setOf("front", "back", "正面", "背面", "question", "answer", "term", "definition")

    fun parse(text: String): List<ImportedCard> {
        // 剥 BOM。Excel 的「CSV UTF-8」会带 U+FEFF，而 Kotlin 的 trim()/lowercase()
        // 不把它当空白 —— 不剥的话表头行会被当成一张卡导入（front 变成 "﻿front"）。
        val rows = parseRows(text.removePrefix("\uFEFF"))
        if (rows.isEmpty()) return emptyList()

        // 表头判定要保守。原逻辑是「首行任一 cell 命中表头词就丢整行」，
        // 而 HEADER_HINTS 里有 term/definition/answer 这些**常用数据词** ——
        // 第一行如果是真实卡片（`term,definition`，或 back 恰好是 "answer"），
        // 整条数据会被静默丢掉。改成：所有非空 cell 都是表头候选才算表头。
        val first = rows.first().filter { it.isNotBlank() }
        val hasHeader = first.isNotEmpty() && first.all { cell ->
            cell.trim().lowercase() in HEADER_HINTS
        }
        val data = if (hasHeader) rows.drop(1) else rows

        return data.mapNotNull { row ->
            val front = row.getOrElse(0) { "" }.trim()
            val back = row.getOrElse(1) { "" }.trim()
            if (front.isEmpty()) null else ImportedCard(front, back)
        }
    }

    /** RFC4180 风格行解析：返回每行的字段列表（换行可出现在引号内）。 */
    internal fun parseRows(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        fun endField() { row.add(field.toString()); field.setLength(0) }
        fun endRow() { endField(); rows.add(row.toList()); row.clear() }

        while (i < text.length) {
            val c = text[i]
            when {
                inQuotes -> when {
                    c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { field.append('"'); i++ }
                    c == '"' -> inQuotes = false
                    else -> field.append(c)
                }
                c == '"' && field.isEmpty() -> inQuotes = true
                c == ',' -> endField()
                c == '\r' -> { /* skip */ }
                c == '\n' -> endRow()
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) endRow()
        return rows.filter { r -> r.any { it.isNotBlank() } }
    }
}
