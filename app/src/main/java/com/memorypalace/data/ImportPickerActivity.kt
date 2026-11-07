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
 * 真机文件选择入口（M5）：透明 Activity，弹出系统文件选择器，
 * 选完把 Uri 交给 ImportService 后自动关闭。模拟器无头验证走 card.IMPORT 广播。
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
                    val stats = ImportService.importFromUri(applicationContext, uri)
                    Log.i(ImportService.TAG, "picker import done: $stats")
                }
            }
        }
        finish()
    }

    companion object {
        private const val REQ_PICK = 42
    }
}
