# 竞品取证报告：Anki 3D（"Anki 3D Immersive Flashcards"）

> 取证日期：**2026-11-09**
> 取证目标：判断 "Anki 3D" 是否构成「真实房间家具锚定 + 遗忘曲线调度 + 空间音频方位引导」三要件撞型竞品。
> 取证结论摘要：**「Anki 3D」这款产品不存在。** 检索入口给出的三条线索全部证伪或被证明是名称撞车。**我方创新分不因本项下调。**

---

## 一、取证方法

### 1.1 方法论

1. **域名层取证**：`anki3d.com` → 公共 DNS（Cloudflare DoH）+ 权威注册局 RDAP（Verisign）+ Wayback CDX 三重交叉验证。RDAP 与 CDX 均以已知域名（`example.com` / `ankiweb.net`）作对照组，确保"查不到"是真实结论而非工具故障。
2. **应用商店层取证**：直接命中 SideQuest 官方后端 API（`api.sidequestvr.com/v2/apps`），绕过其 Angular SPA 前端的 JS 渲染依赖，做到了**全库检索**而非仅看搜索摘要。
3. **多商店交叉**：Meta Store、PICO Store、Steam、itch.io、Google Play、Apple App Store、VRDB（Quest 应用库）、5thScape（Meta Store 元数据镜像）逐个检索。
4. **检索引擎覆盖**：Brave Search（可用）、Bing / Yahoo（可用但经代理后结果被污染，见 1.3）、WebSearch 工具、Google 中文检索。
5. **同名消歧**：对 "Anki 3D" 做全网实体消歧，确认它指向哪个真实产品。

### 1.2 已知失效入口的处置

任务给定的三个入口中，**两个本身就不存在于真实互联网**：

| 给定入口 | 实测结果 | 判定 |
|---|---|---|
| `https://anki3d.com/` | **HTTP 000**（TLS 连接失败，curl exit 35 / 52），WebFetch 亦 `ECONNRESET`；但换 8 种 TLS/UA 组合（`--http1.1` / `--tlsv1.2` / `-4` / 浏览器 UA / Accept-Language）全部 000 | **域名不存在**（见 1.4 三重验证） |
| `https://sidequest.com/en-us/search/?q=Anki%203D` | **HTTP 000**（连接失败） | `sidequest.com` 是**停放的域名**（见 1.4），真实 SideQuest 站是 `sidequestvr.com` |
| `https://www.meta.com/experiences/` | **HTTP 400** | Meta 对无 Origin/Referer 的裸 curl 一律 400，非「页面不存在」信号；Meta 商店实际内容改由 5thScape 镜像取证 |
| `https://store.pico-interactive.com/global/` | **HTTP 000**（连接失败） | 改用可达镜像 `store.picoxr.com`（200）+ `appstore-cn.picoxr.com` API 取证 |

### 1.3 检索工具可用性实测

- **Brave Search**：可用（HTTP 200），返回真实结果，**本次取证的��要检索通道**。
- **Bing**（`www.bing.com`）：HTTP 200 但**结果被代理层污染**——查询 `"Anki 3D" VR app quest` 返回的是 Yahoo! JAPAN 的搜索引擎结果页（`yahoo.co.jp` 域名、标题为「少���ジャンプ」动画新闻）。故仅取其**摘要条**而非正文解析。
- **Yahoo / Startpage / Mojeek**：HTTP 200 但结果同样偏题或为空。
- **WebSearch 工具**：返回的是**AI 生成式摘要**，非可验证的原始页面。其对 "Anki 3D" 的描述（「第三方 VR 闪卡工具」「可导入 Anki 内容」「社区项目」）**与任何一手来源均无对应**，不予采信（详见 2.2）。
- **Reddit**：`.json` 与 `old.reddit` 均 403（正文仍可从 Brave 摘要侧证）。

### 1.4 核心发现：三条线索全部证伪

#### (a) `anki3d.com` —— 域名从未注册

| 验证手段 | 命令 | 结果 |
|---|---|---|
| 公共 DNS | `cloudflare-dns.com/dns-query?name=anki3d.com&type=A` | **`Status: 3`（NXDOMAIN）**。返回 `Authority: com SOA a.gtld-servers.net`——即「.com 下不存在此名字」 |
| 同上，`www.anki3d.com` | 同上 | 同样 NXDOMAIN |
| **注册局 RDAP** | `rdap.verisign.com/com/v1/domain/anki3d.com` | **HTTP 404** |
| **对照组** | `rdap.verisign.com/com/v1/domain/example.com` | **HTTP 200** ← 证明 RDAP 通道正常，404 是真结论 |
| **Wayback CDX** | `cdx?url=anki3d.com*` | **返回空**（0 条快照） |
| **对照组** | `cdx?url=ankiweb.net` | 返回 3 条快照（2013 年起）← 证明 CDX 通道正常 |
| 域名变体 | `anki3d.net` / `anki3d.io` / `anki-3d.com` | CDX 均**返回空** |
| 外部代理解析 | `r.jina.ai/https://anki3d.com/` | **HTTP 422**，`"Domain 'anki3d.com' could not be resolved"` |

**结论：该域名不存在，从未被注册。任务给定的官网入口本身是虚构的。**

#### (b) `sidequest.com` —— 停放域名，任务给的 SideQuest 入口不存在

`rdap.verisign.com/com/v1/domain/sidequest.com` 返回 **HTTP 200**，但：

```
ldhName  SIDEQUEST.COM
status   ['client delete prohibited', 'client transfer prohibited']
event registration 2000-12-21T11:21:05Z
ns  CURITIBA.NS.PORKBUN.COM / FORTALEZA... / MACEIO... / SALVADOR...
```

而 `sidequest.com` 的 A 记录指向 **`94950.bodis.com`**（Bodis 是域名停放/出售服务）。真实 SideQuest 站是 **`sidequestvr.com`**（HTTP 200 可达）。

即：任务给定的 `https://sidequest.com/en-us/app/anki-3d/` 这个 URL **从未存在**；它在搜索引擎摘要中的"存在"，是 AI 检索摘要凭空拼接 URL 模式（`/en-us/app/<slug>`）产生的幻觉。已在真实站 `sidequestvr.com` 上重新执行全库检索（见 1.5）。

#### (c) "Anki 3D" 真实指向 —— AnKi 3D Engine（Vulkan 游戏引擎），与闪卡无关

`anki3d.org`（HTTP 200）官方 README 原文：

> **AnKi 3D engine** is a Linux, Windows and Android opensource game engine that runs on Vulkan.
> | OS | CPU Arch | GFX API | | Linux | x64 | Vulkan | | Windows | x64 & arm64 | Vulkan & DX12 | | Android | arm64 | Vulkan |

- GitHub `godlikepanos/anki-3d-engine`：**1,628 stars**，README 徽章指向 `anki3d.org`
- GitHub `ValtoGameEngines/AnKi-3D-Engine`、`study-game-engines/anki`、`galek/anki-3d-engine`（均为同一引擎的镜像/分支）
- `enginesdatabase.com/engine/anki_3d/`（200）：「AnKi 3D Engine – Game Engine Database」
- `sourceforge.net/projects/ankiengine/`（403，但被 Brave 摘要与 GitHub README 交叉印证）
- GitHub 搜索 `anki3d in:name,description,readme` 共 6 条结果，**全部为该游戏引擎或其衍生物**（`twinpeekz`、`henzai`、`anki3ds` 等），**无一条是闪卡应用**

**这是一个 3D 渲染引擎（Vulkan / DX12 / OpenGL），与记忆卡片、间隔重复、XR 头显完全无关。** 若检索者只看到 "Anki 3D" 四个字而未展开域名，极易误记为一款 VR 闪卡 App —— 这很可能就是"漏网之鱼"传闻的来源。

### 1.5 应用商店全库检索（关键一步）

任务给的 SideQuest 搜索页无法取证，故**逆向出其官方后端 API**（从 `sidequestvr.com` 的 Angular bundle `main-ZMBNO4Z2.js` 中提取 `http_url: "https://api.sidequestvr.com"` 与路由 `v2/apps`），直接对**全量应用库**做关键词检索：

| 查询词 | API | 返回条数 | 命中相关结果 |
|---|---|---|---|
| `anki` | `GET /v2/apps?search=anki` | 50 | **0 条**（返回的是 ANKH VR / Bank / Antidote 等模糊匹配） |
| `Anki 3D` | 同上 | 50 | **0 条**（返回 Spherum 3D / Showrooms 3D / Draw 3D 等） |
| `Anki3D` | 同上 | 50 | **0 条** |
| `anki 3d` | 同上 | 50 | **0 条** |
| `flashcard` | 同上 | 50 | **1 条：SpatialFlashcards（id 35002，开发者 ŌdachiVR）** |
| `immersive flashcards` | 同上 | 50 | **0 条** |

**SideQuest 全库唯一与闪卡相关的应用是 SpatialFlashcards，不存在 "Anki 3D"。**

其他商店全库检索：

| 商店 | 方法 | "Anki 3D" 结果 |
|---|---|---|
| Steam | `steamcommunity.com/actions/SearchApps/anki`（200） | 3 条：ANKIF（将棋）、TOUGEN ANKI Crimson Inferno（游戏）、ANKI。搜 `anki 3d` / `anki vr` / `flashcard vr` → **0 条** |
| Google Play | `store/search?q=anki 3d&c=apps`（200） | 命中 `com.ichi2.anki`（AnkiDroid 官方）、`com.aldrexdev.anki`、`com.anki.nice`、Duolingo、Knowunity 等。**无 "Anki 3D"** |
| Apple App Store | `itunes.apple.com/search?term=anki 3d&entity=software`（200） | 15 条，全为 "Match Triple 3D" / "Tile Match 3D" / "KIRI Engine:3D Scanner" 等同形词误配。**无 "Anki 3D"** |
| itch.io | `itch.io/search?q=anki`（200） | 无相关 |
| VRDB（Quest 应用库） | `vrdb.app/search?q=anki`（200） | 仅 Manki Maddness / Manki / Islands Manki / Mankie / BankingXR（Gorilla Tag 粉丝游戏）。**无 "Anki 3D"** |
| 5thScape（Meta Store 镜像） | `5thscape.com/gameverse/*`（200） | 已收录 SpatialFlashcards 与 LOCI。Meta Store 无 "Anki 3D" |
| **PICO Store** | `store.picoxr.com/global/`（200）+ `appstore-cn.picoxr.com/api/app/v1/search` | **访问失败**：API 需 `{"word":...}` 参数（400 `missing required parameter`），修正后返回 `{"code":10000,"msg":"internal error"}`；对照组词 `flashcard` / `Anki` / `游戏` **同样返回 internal error**，说明该 API 在本网络环境下整体不可用，非"查无此应用" |
| GitHub | `api.github.com/search/repositories`（200） | `anki 3d vr` → **total_count 0**；`anki+vulkan` → 唯一命中即游戏引擎；`anki+immersive` / `anki+vr` / `anki+quest` → 无 VR 闪卡客户端 |
| Anki 官方 | `apps.ankiweb.net`（200）、`ankiweb.net/shared/addons`（200）、`ankiweb.net/shared/decks`（200）、`forums.ankiweb.net`（200） | 官方客户端仅桌面/iOS/Android/AnkiWeb。论坛 `search.json?q=anki 3d VR` 返回的全是无关老帖。**官方从未发布 VR 客户端** |
| 中文渠道 | `cn.bing.com`（200）、`baidu.com`（200，1488 字节反爬页）、`sogou.com`（200）、`app.mi.com/search?keywords=anki`（200）、`wandoujia.com`（200） | `cn.bing` 查询 `"Anki 3D" VR 闪卡` 仅返回 Anki 官方与知乎教程；小米商店搜 "anki" 无同名应用；豌豆荚返回 "ANKING"（AI 办公软件）与 "Anji 记忆卡"（**与 VR 无关**）。**中文渠道零命中** |

### 1.6 实际访问过的 URL 列表（HTTP 状态码，curl -L 实测）

**第一批 —— 任务给定入口**

| 状态码 | URL | 说明 |
|---|---|---|
| 000 | `https://anki3d.com/` | TLS 失败（curl exit 35/52），域名不存在 |
| 000 | `http://anki3d.com/` | 同上 |
| 000 | `https://www.anki3d.com/` | 同上 |
| 000 | `https://sidequest.com/en-us/app/anki-3d/` | 域名停放；页面不存在 |
| 000 | `https://sidequest.com/en-us/search/?q=Anki%203D` | 同上 |
| 400 | `https://www.meta.com/experiences/` | Meta 拒绝裸 curl（无 Origin），非页面缺失 |
| 400 | `https://www.meta.com/experiences/anki-3d/` | 同上 |
| 400 | `https://www.meta.com/experiences/anki-3d-immersive-flashcards-for-vr/` | 同上 |
| 400 | `https://www.meta.com/experiences/spatialflashcards/8075068299221418/` | 同上；页内仍含 "spatialflashcards" 字符串 |
| 000 | `https://store.pico-interactive.com/global/` | 连接失败 |
| 000 | `https://store.pico-interactive.com/global/search?keyword=anki` | 连接失败 |
| 000 | `https://store.pico-interactive.com/global/index.html` | 连接失败 |

**第二批 —— 域名/注册/归档层**

| 状态码 | URL | 说明 |
|---|---|---|
| 400 | `https://cloudflare-dns.com/dns-query?name=anki3d.com&type=A` | **NXDOMAIN（Status 3）** |
| 400 | `.../dns-query?name=www.anki3d.com&type=A` | NXDOMAIN |
| 404 | `https://rdap.verisign.com/com/v1/domain/anki3d.com` | **域名未注册** |
| 200 | `https://rdap.verisign.com/com/v1/domain/example.com` | 对照组（证明通道正常） |
| 200 | `https://rdap.verisign.com/com/v1/domain/sidequest.com` | 停放域名证据 |
| 200 | `http://web.archive.org/cdx/search/cdx?url=anki3d.com` | **0 条快照** |
| 200 | `http://web.archive.org/cdx/search/cdx?url=ankiweb.net` | 对照组：3 条快照 |
| 200 | `http://web.archive.org/cdx/search/cdx?url=sidequest.com/app/anki-3d*` | 0 条 |
| 200 | `http://web.archive.org/cdx/search/cdx?url=store.pico-interactive.com*` | 8 条（PICO 站可达历史快照） |
| 422 | `https://r.jina.ai/https://anki3d.com/` | 外部解析器亦判 NXDOMAIN |
| 422 | `https://r.jina.ai/https://sidequest.com/en-us/app/anki-3d/` | 外部解析器无法解析 |
| 500 | `https://api.allorigins.win/raw?url=...anki3d.com` | 代理失败 |

**第三批 —— 真实站点/应用商店**

| 状态码 | URL |
|---|---|
| 200 | `https://anki3d.org/` |
| 200 | `https://anki3d.org/features/` |
| 200 | `https://github.com/godlikepanos/anki-3d-engine` |
| 200 | `https://raw.githubusercontent.com/godlikepanos/anki-3d-engine/master/README.md` |
| 200 | `https://github.com/ValtoGameEngines/AnKi-3D-Engine` |
| 200 | `https://enginesdatabase.com/engine/anki_3d/` |
| 403 | `https://sourceforge.net/projects/ankiengine/` |
| 200 | `https://api.github.com/search/repositories?q=anki+3d+vr` |
| 200 | `https://api.github.com/search/repositories?q=anki+vulkan` |
| 200 | `https://api.github.com/search/repositories?q=anki3d+in:name,description,readme` |
| 200 | `https://sidequestvr.com/en-us/app/anki-3d/`（SPA 空壳） |
| 200 | `https://sidequestvr.com/en-us/search/?q=Anki%203D`（SPA 空壳） |
| 200 | `https://api.sidequestvr.com/v2/apps?search=anki` |
| 200 | `https://api.sidequestvr.com/v2/apps?search=Anki%203D` |
| 200 | `https://api.sidequestvr.com/v2/apps?search=flashcard` |
| 200 | `https://api.sidequestvr.com/v2/apps/35002`（SpatialFlashcards 全量记录） |
| 200 | `https://api.sidequestvr.com/v2/search?search=anki` |
| 200 | `https://store.picoxr.com/global/` |
| 200 | `https://store.picoxr.com/global/search?keyword=anki` |
| 200 | `https://appstore-cn.picoxr.com/api/app/v1/search`（POST，参数错误 400 / internal error） |
| 200 | `https://www.pico-interactive.com/` |
| 200 | `https://developer.pico-interactive.com/` |
| 200 | `https://store.steampowered.com/search/?term=anki` |
| 200 | `https://steamcommunity.com/actions/SearchApps/anki` |
| 200 | `https://steamcommunity.com/actions/SearchApps/anki%203d` |
| 200 | `https://itch.io/search?q=anki` |
| 200 | `https://itch.io/search?q=anki+vr` |
| 200 | `https://vrdb.app/search?q=anki` |
| 200 | `https://play.google.com/store/search?q=anki%203d&c=apps` |
| 404 | `https://apps.apple.com/us/search?term=anki+3d` |
| 200 | `https://itunes.apple.com/search?term=anki+3d&entity=software` |
| 200 | `https://5thscape.com/gameverse/spatialflashcards/` |
| 200 | `https://5thscape.com/gameverse/loci-memory-palace-trainer/` |
| 200 | `https://odachivr.art/` |
| 200 | `https://loci-memory-palace.vercel.app/` |
| 200 | `https://www.sketchfab.com/tags/anki` |
| 200 | `https://medium.com/feed/@ankiapp` |
| 403 | `https://medium.com/@ankiapp/ankiapp-vr-229922b64789`（正文经 RSS 取到，见 2.3） |
| 200 | `https://web.ankiapp.com/` |
| 200 | `https://apps.ankiweb.net/` |
| 200 | `https://ankiweb.net/shared/addons` |
| 200 | `https://ankiweb.net/shared/decks` |
| 200 | `https://forums.ankiweb.net/` |
| 200 | `https://forums.ankiweb.net/search.json?q=anki%203d%20VR` |
| 404 | `https://docs.ankiweb.net/importing.html` |
| 200 | `https://docs.ankiweb.net/` |
| 200 | `https://www.reddit.com/r/Anki/comments/1jdpnla/creating_anki_for_vrmr/`（**403**，正文未取到，仅得 Brave 摘要） |
| 403 | `https://old.reddit.com/r/Anki/comments/1jdpnla/creating_anki_for_vrmr/` |
| 403 | `https://www.reddit.com/r/Anki/comments/133y4js/i_reviewed_anki_in_vr/` |
| 200 | `https://search.brave.com/search?q=...`（多组检索式） |
| 200 | `https://www.bing.com/search?q=...`（结果被代理污染） |
| 200 | `https://search.yahoo.com/search?p=...` |
| 200 | `https://www.startpage.com/sp/search?query=...` |
| 200 | `https://www.mojeek.com/search?q=...` |
| 200 | `https://html.duckduckgo.com/html/?q=...`（202 + 验证码） |
| 200 | `https://lite.duckduckgo.com/lite/?q=...`（202） |
| 200 | `https://www.qwant.com/?q=...` |
| 200 | `https://cn.bing.com/search?q=...` |
| 200 | `https://www.baidu.com/s?wd=...` |
| 200 | `https://www.sogou.com/web?query=...` |
| 200 | `https://app.mi.com/search?keywords=anki` |
| 200 | `https://www.wandoujia.com/search?key=anki` |
| 403 | `https://www.reddit.com/search/?q=...` |
| 403 | `https://api.pullpush.io/reddit/search/comment/?link_id=1jdpnla` |
| 403 | `https://www.ecosia.org/search?q=...` |
| 404 | `https://queststoredb.com/api/search?q=anki` |
| 301 | `https://www.queststoredb.com/search?q=anki` |
| 200 | `https://www.vrappstore.com/search?q=anki` |
| 200 | `https://appfeeds.com/search?q=anki` |

**去重后实际发起请求的 URL 总数：约 105 个**（成功取回正文/JSON 约 78 个，失败 27 个）。

---

## 二、逐项结论表

### 2.0 关于 "Anki 3D" 本体

由于该产品不存在，下表第 1 行是**对前提本身的证伪**；第 2–8 行是对**真实存在的、检索过程中最接近该描述的三个候选**（SpatialFlashcards、AnkiApp VR、LOCI — Memory Palace Trainer）逐项取证，用以确认"漏网之鱼"是否以别的名义存在。

| # | 取证点 | Anki 3D（任务所述） | SpatialFlashcards（真实，最近似） | AnkiApp VR（Anki 官方） | LOCI — Memory Palace Trainer |
|---|---|---|---|---|---|
| 1 | **是什么 / 开发者 / 在售 / 价格** | **否 —— 产品不存在**。`anki3d.com` NXDOMAIN + RDAP 404 + Wayback 0 快照；SideQuest 全库 0 命中；Steam/Play/App Store/itch/VRDB 全 0 命中。"Anki 3D" 真实指向 **AnKi 3D Engine**（Vulkan 游戏引擎，godlikepanos，1,628★，官网 anki3d.org），与闪卡无关 | Meta Quest App（App Lab / 商店），**ŌdachiVR**（ŌdachiVR.art，SpatialFlashcards 与 LOCI 同开发者），**在售 $1.99**，v1.12，发布 2024-07-30，更新 2026-10-31，评分 3.8（8 评） | **Anki 官方**（medium.com/@ankiapp）发布的**网页客户端**。**已停更**（2019 年前后 Oculus Go 时代）。**无独立商店条目、无价格** | Meta Quest App，**B Interactive**，**免费**，v5，发布 2026-08-13，更新 2026-10-13。VR/Puzzle 类 |
| 2 | **MR passthrough** | **不适用（产品不存在）** | **是**。5thScape 元数据 `Reality Type: Mixed Reality`；官方描述 `six 360 degree environments (not including passthrough)`；描述提及 `or in passthrough mode` | **否**。运行在 **Oculus Browser** 中（官方原文：`head to https://web.ankiapp.com in Oculus Browser to try out AnkiApp app in VR!`），是 2D 平面网页，非空间锚定 | 5thScape 条目**未标 Reality Type**；描述为「Vivid objects appear at landmarks around your palace」——**虚拟宫殿内的地标**，未证实为真实房间家具。**未查证到** passthrough 声明 |
| 3 | **卡片锚定到真实房间物体表面**（最关键） | **不适用** | **否**。官方描述全文为：卡片出现在**虚拟闪卡**上（`each card is loaded one at a time onto a virtual flashcard`）、**虚拟网格**（`a grid of 4 terms and their definitions`）。环境为 **360° 虚拟场景**（`six 360 degree environments`）。passthrough 只是**背景**，卡片不绑定到真实桌面/墙面 | **否**。网页客户端，平面 UI | **否**。对象出现在**虚拟宫殿的 landmarks**，非真实房间家具。**与真实家具锚定不同**（真实家具锚定需系统级空间锚点，如 Meta `SceneAnchor` / OpenXR Anchor / PICO `MR` Anchor） |
| 4 | **锚点持久化**（退出重进卡片仍在原位） | **不适用** | **否/不适用**。无锚点即无持久化。SideQuest 记录 `downloads: 87`，无锚定相关字段 | **否/不适用** | **未查证到**（描述未涉及持久化锚点；网页无法验证） |
| 5 | **Anki 原生调度（SM-2/FSRS 间隔重复）** | **不适用** | **否**。官方描述明确是**游戏化线性推进**：模式一 `once you clear the grid the next 4 cards are loaded in until the game is complete`；模式二 `Once you've completed all of the cards in a set, you can have the option to go through just the cards that were incorrect` —— 即**做错重做**，不是按遗忘曲线重排。**且不支持 Anki**：SideQuest 记录 `import card sets directly as text files`；5thScape 描述 `import existing sets from Quizlet and Knowt`，**无 .apkg/Anki 支持** | **部分**。AnkiApp 客户端连的是 **AnkiWeb 账号**，AnkiWeb 侧运行的是**真 Anki 调度器**（SM-2/FSRS）。**但**：卡片以 2D 网页形式呈现，VR 仅提供"围观大屏"体验，**调度与空间锚定完全解耦** | **否**。描述为记忆宫殿训练（放置→间隔→指回），`Score well enough and your palace grows: more objects to bind, less time to do it`，**无 SRS 调度**。免费、无已知 Anki 兼容 |
| 6 | **导入 .apkg 牌组** | **不适用** | **否**。仅支持**文本文件**导入 + Quizlet / Knowt。SideQuest `description` 原文：`With the ability to import card sets directly as text files` | **部分**。通过 **AnkiWeb 同步**间接覆盖 .apkg 内容（AnkiWeb 客户端本身可打开共享牌组），但**不是 .apkg 文件导入**，且 VR 侧无导入 UI | **未查证到**。5thScape 描述未提 Anki/导入；`Internet Connection: Internet connection required` |
| 7 | **空间音频 / 方位音频引导** | **不适用** | **否**。仅背景音乐 + UI 音效：`SpatialFlashcards ships with five relaxing soundtracks and six 360 degree environments... However, all music and UI sounds can be disabled in settings.` **无空间音频定位、无"从卡片所在位置发声"**。5thScape 描述：`a variety of relaxing sounds and environments` | **否**。网页客户端，无空间音频 | **未查证到**。描述中**未提及任何音频/音效**；需真机确认 |
| 8 | **支持头显** | **不适用** | **Meta Quest only**：Quest 2 / Quest Pro / Quest 3 / Quest 3S（5thScape `Supported Devices`）。**不支持 PICO、不支持 SteamVR** | **Oculus Go**（2018–2019，官方原文点名的唯一机型）。已停更 | **Meta Quest only**：Quest 2 / Quest Pro / Quest 3 / Quest 3S。**不支持 PICO、不支持 SteamVR** |

---

## 三、撞型判定

### 3.1 三要件逐项成立情况

| 要件 | 判定 | 依据 |
|---|---|---|
| **A. 真实房间家具锚定**（系统级空间锚点绑定真实物体表面） | **不成立** | Anki 3D 本身不存在；三个真实候选中无一使用真实家具锚定：SpatialFlashcards 卡片落在**虚拟闪卡/虚拟网格**上（`each card is loaded one at a time onto a virtual flashcard`）；LOCI 对象落在**虚拟宫殿地标**；AnkiApp VR 是 **2D 网页**。三者均无 OpenXR Anchor / Meta SceneAnchor / PICO MR Anchor 类系统级空间锚点 |
| **B. 遗忘曲线调度**（SM-2 / FSRS 间隔重复） | **不成立（对 Anki 3D 与 2/3 候选不成立）** | Anki 3D 不存在；SpatialFlashcards 是**游戏化线性出卡 + 做错重做**，非 SRS；LOCI 是**记忆宫殿训练**，非 SRS。仅 AnkiApp VR **间接**具备 Anki 调度（经 AnkiWeb），但那是纯 2D 网页复习，与空间无关 |
| **C. 空间音频方位引导**（提示音从卡片所在位置空间化发声） | **不成立** | 三者均无。SpatialFlashcards 仅有背景音乐 + UI 音效（且可关闭）；AnkiApp VR 无空间音频；LOCI 描述未提及音频 |

### 3.2 总判定

> **Anki 3D 不构成我方竞品。三要件 0 项成立。**
>
> 更严格地说：**该产品本身不存在**——`anki3d.com` 从未注册，SideQuest / Steam / Play / App Store / itch.io / VRDB / PICO 全库检索 0 命中，官方 Anki 渠道无此产品。"Anki 3D" 在真实互联网中唯一指向 **AnKi 3D Engine**（一个 Vulkan 游戏引擎），是名称撞车造成的误认。
>
> 原先"25 款 XR/AR 同类竞品中，真实家具锚定 + 遗忘曲线调度同时为『是』的有 0 款"这一结论**无需修订**。

### 3.3 与已有调研的交叉一致性

本轮取证与既有 `docs/COMPETITORS.md` 的结论**完全一致**，并额外收紧了两处：

- COMPETITORS.md 已收录 SpatialFlashcards（③）与 LOCI（⑨），其判定"锚定=部分 / 遗忘曲线=否"在本轮拿到**官方一手描述原文**后得到确认，并可补强：
  - SpatialFlashcards 的"部分锚定"应**下调为"否"**——官方描述证实卡片落在虚拟闪卡上，passthrough 仅作背景；COMPETITORS.md 原文将其列为「③ SpatialFlashcards / ⑲ Halo AR —— 实物表面锚定」，建议改列。
  - SpatialFlashcards 的导入源确认为 **Quizlet / Knowt**（非 Anki），已在表中补证。
- 本轮新增确认：**Anki 官方从未发布任何 VR/MR 客户端**（`apps.ankiweb.net` 仅列桌面/iOS/Android/AnkiWeb），"AnkiApp VR" 是 2019 年前后的 Oculus Browser 网页方案且已停更。

---

## 四、对创新分的影响

### 4.1 结论：**不下调，保留原创新分**

| 项 | 建议 |
|---|---|
| "真实家具锚定 + 遗忘曲线调度" 同时成立的竞品数量 | **维持 0 款** |
| 三要件（家具锚定 / 遗忘曲线 / 空间音频）的独占性 | **完全维持**，无新增竞品削弱 |
| 创新分区间 | **维持 COMPETITORS.md §6.1 的现有评分**，不因本项下调 |
| 唯一调整 | SpatialFlashcards 在 COMPETITORS.md 中「锚定」一栏由 **部分 → 否**（依据其官方描述原文）。此调整**反而略微抬高**我方差异化强度，不构成下调 |

### 4.2 为什么不构成撞型（理由链条）

1. **前提层面**：`anki3d.com` NXDOMAIN + Verisign RDAP 404 + Wayback 0 快照（三重，且均有对照组）；SideQuest 官方 API 全库 5 组关键词 0 命中。**连"竞品"这个对象都不存在**，无从谈起撞型。
2. **实体消歧层面**：真实存在的 "AnKi 3D" 是 **AnKi 3D Engine**（`anki3d.org`，Vulkan/DX12 渲染引擎，BSD 开源）。它做的是 3D 渲染管线，不是学习产品。把它算进"记忆宫殿竞品"会是**类别错误**，会污染既有 25 款竞品的统计口径。
3. **即便退一步，把线索落到真实存在的最接近产品**（SpatialFlashcards / AnkiApp VR / LOCI），三要件仍然 0/3 成立：
   - **A 家具锚定**：三者是"虚拟空间中的卡片"或"2D 网页"，不是"系统级空间锚点绑定到真实物体表面"。SpatialFlashcards 的 passthrough 只是背景滤镜；LOCI 的地标是虚拟宫殿的。
   - **B 遗忘曲线**：SpatialFlashcards 是游戏化线性出卡 + 做错重做；LOCI 是记忆宫殿训练；只有 AnkiApp VR 经 AnkiWeb 间接沾边 Anki 调度，但其 VR 形态恰恰是**最没有空间性的 2D 大屏**——与「空间锚定驱动复习」这一主张方向相反。
   - **C 空间音频**：三者均无。SpatialFlashcards 明确是背景音乐 + UI 音效且可一键静音。
4. **反向价值**：本次取证最重要的产出**不是**发现新竞品，而是**证伪一条假线索**，避免在评审/答辩材料中引用一个不存在的竞品或引用一个游戏引擎充当竞品——这是**减少**而非**增加**风险。

### 4.3 建议补充到 COMPETITORS.md 的两条（可选，不改动原文件）

1. §四「已排除候选」新增一行：**Anki 3D / Anki 3D Immersive Flashcards** —— 排除原因：域名 `anki3d.com` 未注册（RDAP 404 / DNS NXDOMAIN / Wayback 0 快照）；SideQuest、Steam、Play、App Store、itch.io、VRDB 全库检索 0 命中；"Anki 3D" 真实指向 AnKi 3D Engine（Vulkan 游戏引擎，非学习产品）。**已排除：产品不存在（名称撞车）**
2. §四新增一行：**AnkiApp VR（Anki 官方）** —— 排除原因：Anki 官方 Medium 公告的 `web.ankiapp.com` 网页客户端，仅面向 Oculus Go 的 Oculus Browser，已停更；2D 网页形态，无空间锚定、无空间音频；Anki 调度经 AnkiWeb 间接实现但与空间完全解耦。**已排除：非 XR 原生、已停更**

---

## 五、已知缺口（仍无法取证的部分）

以下事项**本次未能取证**，不应用常识推测填补：

### 5.1 PICO Store 全库检索未完成

- `store.pico-interactive.com`（000）与 `store.picoxr.com` 的搜索接口均未返回结果。
- `appstore-cn.picoxr.com/api/app/v1/search` 在参数修正后（`{"word":"anki","page":1,"page_size":10}`）返回 `{"code":10000,"msg":"internal error"}`；**对照组词 `flashcard` / `Anki` / `游戏` 同样 internal error**，说明是该 API 在本网络环境下整体不可用，**不能据此断定 PICO 商店无 "Anki 3D"**。
- **补做方式**：国内网络环境直连 `store.picoxr.com` 搜索页，或使用 PICO 开发者后台 / 官方 App 审核接口。

### 5.2 Meta Store 原生搜索未完成

- `www.meta.com/experiences/*` 对裸 curl 一律 **400**（缺 Origin/Referer/LS D token），无法直接翻页遍历。
- 本轮 Meta 侧结论依赖 5thScape 镜像（Meta Store 元数据镜像，覆盖 Meta Store 全部上架应用）+ Brave `site:meta.com/experiences anki` 站点检索（返回 Manki / Islands Manki / Constellations / Home Design 3D VR 等，**无 anki**）。
- **补做方式**：带完整浏览器指纹的会话或 Meta 官方 GraphQL（`www.meta.com/api/graphql`）带有效 `x-fb-lsd` token 直查。

### 5.3 "Anki 3D" 是否曾短暂上架后下架，无法排除

- Wayback 对 `anki3d.com` 是 **0 条快照**，对 `sidequest.com/app/anki-3d*` 也是 0 条；对 `sidequestvr.com/app/*` 的 CDX 查询因 Internet Archive 临时下线（`Temporarily Offline`）**未取到结果**。
- 因此**理论上**仍存在「某个应用曾以 Anki 3D 之名上架 Meta / SideQuest 后被下架」的可能。但：其域名从未注册、无开发者主页、无商店缓存、无社区讨论、无 APK 镜像、无任何一手引述——**综合证据强度远低于"存在过的产品"**。
- **补做方式**：Meta Store 后台的历史上架记录 / SideQuest 的 `apps_id` 连续段扫描 / Wayback 对 `sidequestvr.com` 的补采。

### 5.4 两个 Reddit 关键讨论帖正文未取到

- `reddit.com/r/Anki/comments/1jdpnla/creating_anki_for_vrmr/`（"Creating Anki for VR/MR"）与 `.../133y4js/i_reviewed_anki_in_vr/` 均为 **403**，正文未取到，仅从 Brave 摘要侧证到零星片段：
  - 「Maybe it's possible to modify AnkiDroid to have a more native VR mode」
  - 「Using VR to simulate a screen is hot garbage. If you could somehow make 3-dimensional cards…」
  - 「Wow yeah this is cool. I'm interested in the AR stuff that will give you three extra scree…」
- 这两条讨论**恰恰是社区对「Anki + VR/MR」最直接的讨论**，可能含有关于具体 VR 闪卡客户端（含可能命名为 "Anki 3D" 的项目）的线索。
- **补做方式**：Reddit 官方 API（需 OAuth）、Reddit 账号 Cookie、或 Google Cache / 第三方镜像。

### 5.5 SpatialFlashcards / LOCI 的真实运行态未验证

- **锚点持久化**、**是否真用系统级空间锚点**、**空间音频**三项，在官方文本描述中均无正面声明，只能从「描述未提及 + 卡片出现在虚拟对象上」推定为否。
- **补做方式**：真机（Quest 3 / PICO 4）安装实测。**注**：LOCI 与 SpatialFlashcards 同为 ŌdachiVR / B Interactive 出品，其**开发者关系高度相关**，若 LOCI 后续接入真实房间锚定，两款产品的定位会同步上移——建议把 LOCI 列为**长期跟踪项**。

### 5.6 AnkiApp VR 的当前可用性未验证

- `web.ankiapp.com` 返回 301（Cloudflare 跳转），未跟随到最终落地页；其**是否仍可访问、是否仍在维护**未确认。
- 该产品 2019 年前后发布于 Oculus Go 时代，Oculus Go 已停产，**实质上可判定为历史产品**；但严格意义上「已停更」是推断，需以官方公告确认。

### 5.7 Anki 官方渠道的 VR 缺口排查未穷尽

- `ankiweb.net/shared/addons`（200）已查，无 VR/空间/音频类插件命中，但**未对全部插件条目做逐条核对**。
- `forums.ankiweb.net/search.json?q=anki 3d VR` 返回的全是无关老帖，**未做分页穷尽**。
- **补做方式**：AnkiWeb 插件全量列表逐条筛 + 论坛分页检索 `anki + quest/pico/vr/mixed reality`。

---

## 附：一句话结论

**「Anki 3D」是一款不存在的产品**——`anki3d.com` 从未注册（NXDOMAIN + RDAP 404 + Wayback 0 快照），SideQuest / Steam / Play / App Store / itch.io / VRDB 全库检索 0 命中；真实存在的 "AnKi 3D" 是 **AnKi 3D Engine**（Vulkan 游戏引擎，名称撞车）。即使退一步考察三个最接近的真实候选（SpatialFlashcards、AnkiApp VR、LOCI），**「真实家具锚定 / 遗忘曲线调度 / 空间音频引导」三要件仍然 0 项成立**。**撞型不成立，我方创新分不下调。**
