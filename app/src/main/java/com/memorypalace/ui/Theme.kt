package com.memorypalace.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 设计规范（M9）：三窗共用一套数字化 token——
 * 色板 / 字号阶梯 / 间距阶梯 / 圆角。没有专职设计师，统一性就是视觉分的大头。
 * 新增 UI 只允许引用这里的常量，禁止再写字面量。
 */
object MpColors {
    val TextPrimary = Color(0xFFE8EAF0)   // 主文字：近白
    val TextSecondary = Color(0xFF9AA3B2) // 次文字：灰蓝
    val Accent = Color(0xFFFFC107)        // 琥珀：强调 / 到期提醒 / 答案
    val Info = Color(0xFF7CD4FD)          // 青：进行中状态 / 当前卡
    val Success = Color(0xFF8BE9A8)       // 绿：记得牢 / 队列清空
    val TimeMachine = Color(0xFFFF79C6)   // 粉：时间机器激活态
    val Warning = Color(0xFFFFB86B)       // 橙：正在遗忘
    val FadedMid = Color(0xFFB0BEC5)      // 褪色中档（遗忘 1/3–2/3）
    val FadedDeep = Color(0xFF6B7280)     // 褪色深档（遗忘 >2/3，快忘了）
    val SurfaceDark = Color(0xFF12141A)   // 深色信息块底（事件日志等）
}

object MpType {
    val Display = 32.sp   // 面板大标题 / 复习卡正面
    val Title = 26.sp     // 窗口标题
    val CardFront = 22.sp // 锚定卡正面
    val Section = 20.sp   // 区块标题（到期队列 / 时间机器 / 导入确认）
    val BodyLarge = 18.sp // 正文大
    val Body = 16.sp      // 正文 / 按钮
    val BodySmall = 14.sp // 列表行
    val Caption = 13.sp   // 辅助说明
    val Caption2 = 12.sp  // 最弱说明

    val Bold = FontWeight(700)
    val Regular = FontWeight(400)
}

object MpSpace {
    val Xs = 4.dp
    val Sm = 6.dp         // 列表行距
    val Md = 12.dp        // 按钮间距
    val Lg = 14.dp
    val Xl = 16.dp        // 区块间距
    val PanelMain = 36.dp // 书房面板内边距
    val PanelCard = 28.dp // 随身卡面板内边距
    val CardAnchor = 16.dp// 锚定卡内边距
}

object MpShape {
    val Block = 8.dp      // 信息块圆角
}
