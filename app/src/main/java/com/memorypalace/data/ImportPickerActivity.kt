package com.memorypalace.data

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 真机文件选择入口（M5/M8）：透明 Activity，弹出系统文件选择器，
 * 选完把 Uri 解析成预览发布到 ImportService.pendingPreview 后自动关闭——
 * 字段映射确认页在书房面板里渲染（Spatial UI Compose fork 无法在普通 Activity setContent）。
 * 模拟器无头验证走 card.IMPORT 广播（直导，不经确认页）。
 */
class ImportPickerActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, REQ_PICK)
    }

    @Deprecated("startActivityForResult 简单链路足够，避免引入 activity-ktx 依赖")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PICK && resultCode == RESULT_OK) {
            val uri = data?.data
            if (uri != null) {
                scope.launch {
                    ImportService.previewFromUri(applicationContext, uri)
                }
            }
        }
        finish()
    }

    companion object {
        private const val REQ_PICK = 42
    }
}
