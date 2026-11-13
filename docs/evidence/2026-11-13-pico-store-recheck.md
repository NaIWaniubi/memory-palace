# PICO 商店复查原始记录（2026-11-13）

> 本文件只记录**实际发出的请求与实际收到的响应**，不做推断。
> 结论与解读写在 `docs/COMPETITORS.md` 的「2026-11-13 PICO 商店复查」小节。

## 一、官方商店检索 API（三条路径，全部失败）

请求体统一为 `{"word":"<关键词>","page":1,"page_size":10}`。

### 1.1 `https://appstore-cn.picoxr.com/api/app/v1/search`（POST）

| 关键词 | 类别 | 实际响应 |
|---|---|---|
| `tetris` | 对照词（必然有结果） | `{"code":10000,"msg":"internal error","data":{}}` |
| `game` | 对照词 | `{"code":10000,"msg":"internal error","data":{}}` |
| `puzzle` | 对照词 | `{"code":10000,"msg":"internal error","data":{}}` |

补测（排除「缺请求头」这一可能）：

```bash
curl -X POST https://appstore-cn.picoxr.com/api/app/v1/search \
  -H "Content-Type: application/json" \
  -H "Origin: https://store.picoxr.com" \
  -H "Referer: https://store.picoxr.com/" \
  -H "User-Agent: Mozilla/5.0 ... Chrome/131.0 Safari/537.36" \
  -H "Accept: application/json, text/plain, */*" \
  -d '{"word":"tetris","page":1,"page_size":10}'
# → {"code":10000,"msg":"internal error","data":{}}
```

在 **真实渲染页面内**通过 `fetch` 发起同样请求（同源、带浏览器完整上下文）：

```
tetris  → 200 {"code":10000,"msg":"internal error","data":{}}
game    → 200 {"code":10000,"msg":"internal error","data":{}}
puzzle  → 200 {"code":10000,"msg":"internal error","data":{}}
```

### 1.2 `https://appstore-global.picoxr.com/api/app/v1/search`（POST）

该域名存在且 HTTP 200，但同样：

| 关键词 | 实际响应 |
|---|---|
| `tetris` | `{"code":10000,"msg":"internal error","data":{}}` |
| `game` | `{"code":10000,"msg":"internal error","data":{}}` |
| `puzzle` | `{"code":10000,"msg":"internal error","data":{}}` |

### 1.3 商店 Web 端

| URL | HTTP | 页面实际内容 |
|---|---|---|
| `https://store.picoxr.com/global/search?keyword=anki` | 200 | 客户端渲染后正文只有「**页面加载失败**」 |
| `https://store.picoxr.com/cn/search?keyword=anki` | 200 | 同上，正文 270 字符，无任何应用条目 |
| `https://store.picoxr.com/global/` | 302 | 跳转 |
| `https://store-global.pico-store.com/` | 000 | 连接失败 |

**判定**：`internal error` 对**必然有结果的对照词**同样出现，且横跨 cn / global 两个域名、
curl 与浏览器内两种发起方式、带与不带完整浏览器头 —— 属 **API 侧整体故障**，
不是「查无此应用」，也不是请求构造问题。

## 二、替代索引：SideQuest 官方后端 API（可用）

`https://api.sidequestvr.com/v2/apps?search=<关键词>&limit=40`

### 2.1 对照词验证（证明该 API 确实在工作）

| 关键词 | 命中数 | 首条结果 |
|---|---|---|
| `tetris` | 3 | Vetrix |
| `puzzle` | 3 | Puzzle Tropicana |
| `game` | 3 | Treehouse Games - multiplayer party games & classic games |

对照词均有命中 → **该索引可用**，此时同类词命中为空才可信。

### 2.2 同类关键词全量扫描（34 个词）

覆盖：`flashcard` / `flash card` / `flashcards` / `anki` / `spaced repetition` /
`memory palace` / `loci method` / `mnemonic` / `vocabulary` / `word card` / `study card` /
`quiz` / `revision` / `study` / `learn` / `education` / `card game` / `cards` /
`memorize` / `memory` / `recall` / `brain` / `闪卡` / `记忆卡片` / `记忆宫殿` / `记忆术` /
`间隔重复` / `背诵` / `单词卡` / `学习卡` / `教育` / `学习` / `记忆` / `复习`

**名称真正相关的应用：4 条（去重后 3 款产品）**

| 名称 | apps_id | 下载 | supports_pico | supports_quest | 简介（原文） |
|---|---|---|---|---|---|
| Shores of Loci | 8940 | 1196 | **False** | True | 「a single-player narrative puzzle game」——**叙事解谜游戏，不是记忆应用**（名字含 Loci 但语义无关） |
| Mind Palace | 19584 | 254 | **False** | True | 「provides an ideal environment for organizing thoughts in a 3D space」 |
| Mind Palace | 19581 | 166 | **False** | True | 同上（重复上架） |
| SpatialFlashcards | 35002 | 87 | **False** | True | 「the VR flashcard app that gives you control over your cards」 |

**关键点：4 条全部 `supports_pico = False`，即全部是 Quest 应用，PICO 侧没有。**

### 2.3 PICO 侧（`supports_pico=true`）命中

用 `supports_pico=true` 过滤后，命中检索词的 9 条（去重后 6 款）：

| 名称 | 下载 | 简介要点 |
|---|---|---|
| Tower Of Hanoi | 28 | 汉诺塔数学游戏 |
| Escape Room: Bank Robbery Gone Wrong | 11583 | 密室逃脱 |
| Colorway Antics Demo | 918 | 涂色解压 |
| testgame（3 条重复） | 1112 | 「lol」——开发者测试包 |
| 艺术馆VR/Art Gallery VR | 633 | 美术馆漫游 |
| Go Go Fight | 765 | 多人对抗 |
| MR Target Practice Simulator Demo for Pico4 | 4322 | MR 打靶 |

**无一条是教育 / 闪卡 / 记忆类。**

### 2.4 该索引的分页限制（影响覆盖面）

| 参数 | 行为 |
|---|---|
| `page=1..8` | **全部返回同一批 100 条**（首条 `apps_id` 恒为 64060） |
| `offset` / `start` | 无效 |
| `sort_by=downloads` / `-downloads` / `created` / `rating` / `name` | **全部返回同一批**，排序不生效 |

结论：该 API 只能取「第一页 100 条」或「按关键词检索」，**无法全量枚举**。
因此本复查以**关键词检索为主路径**，覆盖 34 个词（含中英文、行业词、场景词）。
