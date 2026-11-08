# M7 预置示例牌组实测结论

日期：2026-11-07 ｜ 环境：PICO Emulator 6.1.0（emulator-5556）｜ 构建：assembleDebug 1m16s

## 范围

三套预置牌组开箱可玩，覆盖不同记忆内容类型：**语言词汇 / 专业术语 / 生活常识**。首次启动自动导入，重复启动不重复。

## 实现

- `app/src/main/assets/decks/` 内置三套牌组文件：
  - `lang_en.csv`——英语词汇 17 张（serendipity / ephemeral / petrichor / sonder 等）
  - `tech_terms.csv`——专业术语 17 张（TCP 三次握手 / MVCC / CAP / LSM 树等）
  - `common_sense.txt`——生活常识 16 张（纯文本格式，顺带覆盖 TextImporter 路径；含一条纠错卡"金鱼记忆远不止 3 秒"）
- `data/PresetDecks.kt`：SharedPreferences 首启标记 + assets 拷入私有目录 + 走 M5 同一 `ImportService.importFromPath` 管线。双保险幂等：首启标记挡重复执行，导入管线按 front 去重挡重复数据。
- `MemoryPalaceApplication.onCreate` 注册导入服务后立即调度（IO 协程，不阻塞主线程）。

## 证据

### 1. 全新安装（`pm clear` 后冷启动）自动导入，16:20:35–36

```
Import  : import start: path=…/files/decks/lang_en.csv format=csv size=650B
Import  : import result: format=csv parsed=17 imported=17 skippedDup=0
Import  : import start: path=…/files/decks/tech_terms.csv format=csv size=1176B
Import  : import result: format=csv parsed=17 imported=17 skippedDup=0
Import  : import start: path=…/files/decks/common_sense.txt format=txt size=1000B
Import  : import result: format=txt parsed=16 imported=16 skippedDup=0
```

三套共 **50 张**，全部入库，0 重复，全程约 1.2 秒。

### 2. 开箱到期队列（截图）

- `m7_1_fresh_install_queue.jpg` / 裁剪 `m7_1_fresh_install_panel.jpg`：书房面板「**今日到期队列：50 张**」，队列列出 serendipity / ephemeral / ubiquitous / mellifluous / petrichor / luminous……新卡全部立即到期，开箱即可进入复习流。

### 3. 二次启动幂等（16:24:28）

```
PresetDecks: preset decks already imported, skip
```

不再执行导入，卡片数不增长（无重复堆卡）。

## 备注

- 牌组文件同时是可交付的内容资产：评委手册/演示脚本可直接引用三套主题。
- 与 M5 的关系：预置走同一条导入管线，用户后续仍可自行导入 CSV / TXT / apkg，预置卡不会阻塞去重（front 相同才跳过）。
