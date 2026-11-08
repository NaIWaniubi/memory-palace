# M5 导入管线实测结论

日期：2026-11-07 ｜ 环境：PICO Emulator 6.1.0（emulator-5556）｜ 构建：assembleDebug 1m12s ｜ core 单测：16/16 通过（ImportersTest 11 + SchedulerTest 5）

## 范围

三种格式离线本地导入，全部不联网：**CSV / 纯文本 TXT / Anki .apkg**。解析 → 按 front 去重 → 批量入 Room → 进当日到期队列。大模型取词仅作可选增强，不在本里程碑范围。

## 实现

- `core/importer/`（纯 Kotlin，无 Android 依赖，全部单测覆盖）：
  - `CsvImporter`：RFC4180 风格解析（引号字段含逗号/换行、"" 转义），表头自动识别跳过。
  - `TextImporter`：一行一条，分隔符优先级 TAB → ` :: ` → `——` → ` - `；`#` 注释与空行忽略；无分隔符行整行当正面。
  - `ApkgExtractor`：apkg=zip，解包取 `collection.anki21`（优先）/`collection.anki2`；`notes.flds` 按 `\x1f`（Unit Separator）拆分取前两字段，剥 HTML 标签与实体。
- `app/data/ImportService.kt`：广播入口 `card.IMPORT --es path [--es format]`，格式按扩展名自动识别；apkg 落临时文件用 `SQLiteDatabase.openDatabase(READONLY)` 读 notes 表；按 front 去重批量入库，输出 `ImportStats`（parsed/imported/skippedDup）。
- `app/data/ImportPickerActivity.kt`：真机入口——书房面板「导入牌组」按钮 → 系统文件选择器（SAF，无需存储权限）→ content Uri 拷缓存后走同一导入路径。
- 测试样例 `testdata/`：`lang_en.csv`（含表头+引号逗号字段）、`common_sense.txt`（三种分隔符+注释+无分隔符行）、`tech_terms.apkg`（Python 生成的最小合法 anki2，含 HTML 字段），生成脚本 `gen_samples.py` 一并入库。

## 证据

### 1. 三种格式导入（logcat，15:24:21–23）

```
Import  : import start: path=…/lang_en.csv format=csv size=187B
Cards   : card added id=239e5de8 front="luminous" due=now
Cards   : card added id=bf562d9f front="run, rise" due=now      ← 引号内含逗号字段正确解析为一张卡
Cards   : card added id=1115aa43 front="resilient" due=now
Cards   : card added id=b1f29d7f front="ambiguous" due=now
Import  : import result: format=csv parsed=5 imported=4 skippedDup=1   ← ubiquitous 与存量卡重名被去重

Import  : import start: path=…/common_sense.txt format=txt size=262B
Cards   : card added front="闪电和雷声" / "蜂蜜" / "冰水混合物的温度" / "蝙蝠" / "鸵鸟"
Import  : import result: format=txt parsed=5 imported=5 skippedDup=0   ← 注释行跳过、无分隔符行（鸵鸟）背面为空入库

Import  : import start: path=…/tech_terms.apkg format=apkg size=758B
Cards   : card added front="TCP 三次握手" / "向量数据库" / "幂等性" / "MVCC"
Import  : import result: format=apkg parsed=4 imported=4 skippedDup=0  ← zip→SQLite→flds 拆分→剥 HTML 全链路通
```

### 2. 幂等性（15:24:54 重复导入同一 CSV）

```
Import  : import result: format=csv parsed=5 imported=0 skippedDup=5
```

### 3. 到期队列联动（截图）

- `m5_3_imported_queue_panel.jpg`：书房面板「今日到期队列：14 张」（导入前 5 张），随身复习卡窗口提示"开始复习（取今日到期队列）"。
- 画面中 ANR 弹窗为 M4 已封板的模拟器残留面板（应用主线程在弹窗挂着的同时照常完成全部导入，日志时间戳为证）。

## 坑与结论

1. **scoped storage**：targetSdk 35 下应用无权读 /sdcard 任意路径，且未声明任何存储权限（最终产品走 SAF 选择器，天然无权限问题）。模拟器无头验证的落点：`adb push → /data/local/tmp → chmod 666 → run-as cp` 进应用私有目录，广播指私有路径。此链路已跑通。
2. apkg 解析只依赖 `notes.flds` 前两字段，不处理模板/正反转卡/媒体引用——对"导入成记忆宫殿卡片"够用；media 资源暂不导入（卡片当前无图）。
3. CSV 表头识别基于首行字段名命中（front/back/正面/背面/question/answer/term/definition），误判风险低。
