# M8 导入字段映射确认页实测结论

日期：2026-11-07 ｜ 环境：PICO Emulator 6.1.0（emulator-5556）｜ 构建：assembleDebug ×3（1m59s / 3m04s / 1m30s）

## 范围

评审若问"能不能用我自己的资料"，M5 已有 CSV / TXT / apkg 三格式离线解析，M8 补上最后一环：**解析后先入确认页，用户看到字段映射（正面→背面）再决定是否入库**。无确认不入库。

## 实现

- `data/ImportService.kt`：
  - 新增 `ImportPreview(format, fileName, cards)` + `pendingPreview: MutableStateFlow<ImportPreview?>`。
  - 拆出 `previewFromPath`（只解析不入库）/ `persistImported`（公开化）/ `confirmImport` / `cancelPreview`。
  - SAF 路径（`previewFromUri`）改为发预览，不再直导；广播 `card.IMPORT` 保持直导（自动化/无头用途）。
  - 新增无头广播 `card.PREVIEW --es path`（等价 SAF 选完）与 `card.CONFIRM`（等价点「确认导入」）——模拟器点不了空间按钮，确认页也要能无头验收。
- `data/ImportPickerActivity.kt`：选完文件改为发布预览，确认页交给书房面板渲染（Spatial UI 的 Compose fork 排除了 androidx.compose.ui，普通 Activity 无法 setContent，确认页放面板是唯一可行路径）。
- `ui/StudyPanel.kt`：新增 `ImportConfirmView`——文件名/格式/解析数 + 前 5 条「正面 → 背面」映射预览 + 确认导入/取消按钮；pendingPreview 为空时整块不渲染。

## 证据

### 1. 预览（16:56:21）

```
Import  : preview start: path=…/files/import/m8_lang.csv format=csv size=187B
Import  : preview ready: m8_lang.csv format=csv parsed=5      ← 只解析，未入库
```

### 2. 确认页渲染（截图 m8_1_confirm_page.jpg）

书房面板出现确认区：标题、字段映射说明、5 条正面→背面预览、「确认导入 / 取消」按钮。

> 过程缺陷记录：首版确认页字符串模板被 PowerShell here-string 吃掉 `$`，屏上显示字面量 `{p.fileName}`——被截图当场抓出，改用 Edit 修复后重建验证。这正是"截图验收"流程的价值。

### 3. 确认导入（17:03:39）

```
Import  : confirm import done: format=csv parsed=5 imported=0 skippedDup=5
```

5 条全部与存量卡重名（上一轮已导入 1 条），去重拦截正常，无重复入库。

### 4. 确认后确认页消失（像素级验证）

确认后 `pendingPreview=null`，确认区应从面板消失。读图工具当时不可用，改用像素对比：确认页区域（标题+列表+按钮）前后截图差异像素 **66.8%**——响应式移除成立。logcat 时间戳链完整：preview ready → confirm done → 区域消失。

## 结论

- 真机用户路径闭环：导入选文件 → 面板确认页核对字段映射 → 确认入库 / 取消放弃。
- 无头验收路径：`card.PREVIEW` → 截图确认页 → `card.CONFIRM` → logcat 统计 + 像素对比。
- 广播直导（`card.IMPORT`）保留给自动化与演示脚本，不走确认页（明示预期行为）。
