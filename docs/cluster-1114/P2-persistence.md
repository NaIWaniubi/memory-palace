# P2 持久化 / 生命周期 / 调度审查（cluster-1114）

- 审查日期：2026-11-14
- 范围：`anchor/AnchorStore.kt`、`anchor/AnchorScene.kt`、`data/*`、`data/db/*`、`core/importer/*`、`core/model/Card.kt`、`core/schedule/*`、`notify/*`、`review/ReviewSession.kt`（含装配点 `app/MemoryPalaceApplication.kt`、`ui/RoomStage.kt`、`ui/StudyPanel.kt`、`app/LaunchActivity.kt`、`AndroidManifest.xml`、`DebugFlags.kt` 作为证据）
- 方式：只读静态审查，未修改任何源文件。
- 说明：简报提到的 DataStore 实际不存在——锚点持久化是 SharedPreferences+JSON（`AnchorStore.kt:30`），全仓 grep 无 `androidx.datastore`。以下按严重度排序，全部有行号证据。

---

### [阻断] AnchorStore.load 任何一条记录解析失败都会静默丢全部锚点，且下一次 save 立即把空态覆写落盘，丢失变成永久

`app/src/main/java/com/memorypalace/anchor/AnchorStore.kt:34-56`（整表 `runCatching{}.getOrElse { emptyList() }`）、`:72`（`save` 无条件整键覆写）

```kotlin
return runCatching {
    val arr = JSONArray(raw)
    (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        AnchorRecord(key = o.getString("key"), ...)
    }
}.getOrElse { emptyList() }        // ← 一条坏，全体丢
```

可复现序列：
1. 任一记录缺字段/类型变化（例：老版本没有 `anchorLabel` 是兼容的，但若未来加一条 `getString` 的新必填字段、或某字段写坏、JSON 半损），任意一条抛 JSONException。
2. `load()` 返回 emptyList（无任何日志/告警）；`AnchorScene.register` 直接把 `records.value` 置空（`AnchorScene.kt:79`）。
3. 用户放一张新卡 → `bind()` → `store.save(records.value)`（`AnchorScene.kt:301`）→ 旧记录文件被 `[new]` 覆写，所有历史锚点（含仍活着的系统锚点 UUID）永久丢失。
   代码注释（`:49-50`）自己承认了这个吞没模式，只是给 `anchorLabel` 单点打补丁——`key/uuid/title/px/...` 任何一个坏都触发同一路径。

修法（逐条隔离 + 坏数据保全）：
```kotlin
fun load(): List<AnchorRecord> {
    val raw = prefs.getString(KEY_RECORDS, null) ?: return emptyList()
    val arr = runCatching { JSONArray(raw) }.getOrElse {
        prefs.edit().putString(KEY_RECORDS_CORRUPT_BACKUP, raw).remove(KEY_RECORDS).apply()
        Log.e(TAG, "anchor JSON corrupt, backed up"); return emptyList()
    }
    return (0 until arr.length()).mapNotNull { i ->
        runCatching { parseOne(arr.getJSONObject(i)) }
            .onFailure { Log.e(TAG, "drop bad record #$i", it) }
            .getOrNull()
    }
}
```
并把 `getString("px")` 之类全部换成带默认值的 `opt*`。

---

### [高] bind() 对 records 做跨挂起点的读-改-写，并发放置会丢锚点记录（卡片已入库、锚点记录被覆盖）

`app/src/main/java/com/memorypalace/anchor/AnchorScene.kt:300-301`

```kotlin
records.value = records.value + rec   // 读和写之间隔着 createAnchor 挂起点（:257）
store.save(records.value)
```

`place()` / `placeForCard()` / 取点扳机回调都在同一个 Main scope 里 launch（`:156-159`、`:167-179`、`:222-235`）。协程 A 执行到 `createAnchor` 挂起时协程 B 也挂起；A 恢复后 `records.value + recA` 写回，B 恢复后基于**旧值**写回 `+recB`，A 的记录被覆盖，且 `save` 落盘固化。

可复现序列：
1. 快速连发两条 `am broadcast -a com.memorypalace.anchor.PLACE ...`（或面板双指点放置）。
2. 两张卡都进 Room（`repository.add` 各自完成），但两次 `bind` 交错 → store 里只剩一条。
3. 重启后：DB 有 2 张卡、锚点只有 1 条——孤儿卡，无任何日志提示数量对不上。

修法：
```kotlin
private val bindMutex = Mutex()
...
bindMutex.withLock {
    records.update { it + rec }
    store.save(records.value)
}
```

---

### [高] verifyRestored 把"任何一次 loadAnchor 失败"当成锚点永久丢失，冷启动竞态会健康锚点一次性降级成 uuid=null 且不可逆

`app/src/main/java/com/memorypalace/anchor/AnchorScene.kt:339-356、359-368`

```kotlin
is WorldTrackingResult.Error -> {
    anchored.forEach { rec ->
        when (val r = WorldTrackingManager.loadAnchor(arrayOf(UUID.fromString(rec.uuid)))) {
            is WorldTrackingResult.Error -> {
                rec.uuid?.let { u -> synchronized(lostLock) { lost.add(u) } }  // 不区分错误码
            }
...
private fun downgradeLost() { ... rec.copy(uuid = null) ... store.save(records.value) }
```

`verifyRestored` 由 `RoomStage` 每次 `SpatialView.initial` 触发（`ui/RoomStage.kt:142`）——包括刚放置新卡导致的整场景重建。此时新锚点可能尚未被世界跟踪服务本地化（TrackingState 未就绪 / 服务瞬时错误），`loadAnchor` 返回瞬时 Error ≠ anchor-not-found。当前代码不分错误码、不重试，直接 `uuid=null` 落盘：系统锚点还活在设备世界地图里，但应用侧永久退化为保存位姿直渲，之后房间重定位再也不会跟随真实锚点漂移，也没有任何恢复通道（下次启动该记录已不在 `anchored` 过滤集里，`:329`）。

可复现序列：
1. 真机 Full Space 放置一张卡（得到 UUID，save）。
2. 立即杀掉重进（冷启动，空间定位尚需几秒收敛），RoomStage initial → verifyRestored → loadAnchor 因定位未就绪返回 Error。
3. 记录被降级、持久化；此后即使定位成功，卡片也不再绑回系统锚点。

修法：只对"锚点不存在"这一确定错误码降级；其余保留 UUID，`delay` 后限次重试：
```kotlin
is WorldTrackingResult.Error -> when (r.errorCode) {
    CODE_ANCHOR_NOT_FOUND -> markLost(rec.uuid)
    else -> retryLater(rec, attempts + 1)   // 上限 3 次 + 定位就绪门控，超限也只降级内存态、不落盘
}
```

---

### [高] apkg 解包无解压尺寸上限（zip bomb），CSV/TXT `readText` 无文件大小上限——超大/恶意文件直接 OOM 崩进程

`core/src/main/java/com/memorypalace/core/importer/ApkgExtractor.kt:22-23`；`app/src/main/java/com/memorypalace/data/ImportService.kt:103-105、132-138`

```kotlin
"collection.anki21" -> anki21 = zis.readBytes()   // 解压后尺寸完全不限
"collection.anki2"  -> anki2 = zis.readBytes()    // 两个都中时内存翻倍
```
```kotlin
"csv" -> CsvImporter.parse(file.readText(Charsets.UTF_8))  // 整文件一次进内存
```

可复现序列：
1. `am broadcast -a com.memorypalace.card.IMPORT --es path /sdcard/bomb.apkg`，bomb.apkg 为 100KB、声明 2GB `collection.anki2`（经典 zip bomb）→ `readBytes` 尝试分配 2GB → OOM。
   好消息：条目按精确名匹配、不落盘解压，无 zip-slip 路径穿越（`:21`）。
2. 或 SAF 选一个 1GB CSV：`previewFromUri` 先 `copyTo` 整份进 cacheDir（`:136`，无字节上限、可塞满分区），再 `readText` → OOM。
3. 两处都发生在 `ImportService.scope`（SupervisorJob 吞掉子失败）但 OOM 是进程级——整个头显应用被杀。

修法：
```kotlin
// ApkgExtractor
private const val MAX_ENTRY = 64L shl 20
val buf = ByteArrayOutputStream(); val tmp = ByteArray(8192); var total = 0L
while (true) { val n = zis.read(tmp)
  if (n < 0) break; total += n
  if (total > MAX_ENTRY) throw IOException("apkg entry too large")
  buf.write(tmp, 0, n) }
// ImportService.previewFromPath
if (file.length() > 32L shl 20) return null 日志提示；CSV 改 BufferedReader 逐行解析
```
`previewFromUri` 的 `copyTo` 同理传入 `copyTo(out, LimitedInputStream(maxBytes))` 或在循环里计数截断。

---

### [高] 精确闹钟用 RTC_WAKEUP 但从不因开机/时区/时间变化重排：设备重启后提醒静默消失，直到用户手动点按钮

`app/src/main/java/com/memorypalace/notify/DueReminder.kt:143-183`（只有 `setAlarm`，无系统事件接收器）；`app/src/main/AndroidManifest.xml`（receiver 只声明了 FIRE；全仓无 `RECEIVE_BOOT_COMPLETED`、无 `ACTION_TIME_CHANGED/TIMEZONE_CHANGED` 过滤器）；`app/src/main/java/com/memorypalace/app/MemoryPalaceApplication.kt:19-35`（启动流程不调 `schedule`）

`AlarmManager` 闹钟不跨重启存活。`schedule()` 的调用方只有三处：`card.ADD` 广播（`DataGraph.kt:57`）、`SCHEDULE` 调试广播、书房面板按钮（`StudyPanel.kt:84`）。

可复现序列：
1. 加卡后排上明晚 8 点的闹钟；设备夜间自动重启（头显固件更新常见）。
2. 闹钟蒸发。次日用户不会收到任何复习提醒；日志里也没有重排记录，直到下一次加卡或手点"重新调度"。
3. 同族变体：用户在系统设置手改时间/跨时区出行——RTC_WAKEUP 按新墙上时间解读旧 target，提醒提前/滞后数小时。

修法：
```xml
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED"/>
<receiver android:name=".notify.BootReceiver" android:exported="false">
  <intent-filter><action android:name="android.intent.action.BOOT_COMPLETED"/></intent-filter>
</receiver>
```
BootReceiver 与 `Action_TIME/TIMEZONE_CHANGED` 接收器里 `DueReminder.register` 同款 IO scope 启动后调 `schedule(ctx)`；`MemoryPalaceApplication.onCreate` 数据层就绪后也补一次 `scope.launch { DueReminder.schedule(this) }`。

---

### [高] Clock 偏移值同时污染"存储的 dueAt"和"AlarmManager 绝对时刻"，且偏移本身不落盘——时间机器快进后进程一死，调度语义整体错位

`app/src/main/java/com/memorypalace/data/Clock.kt:24-41`（`offsetMillis` 只在内存，重启归零，无持久化）；`notify/DueReminder.kt:115、155`（`nextFuture`/`target` 全部是 `Clock.now()` 语义的 epoch，喂给 `setExactAndAllowWhileIdle(RTC_WAKEUP, …)` 按真实墙上时钟解释）；`data/CardRepository.kt:19、27`（带偏移的时间直接写进 Room 的 `dueAt`）

可复现序列：
1. `am broadcast -a com.memorypalace.clock.SET_OFFSET --el days 7`，复习一张卡 → 库里 `dueAt = 真实now + 7d + interval`。
2. 排闹钟：`fireAt` 被 AlarmManager 当真实时刻 → 7 个真实天后才响（用户视角：提醒失踪一周）。
3. 进程被杀重启 → `offsetMillis=0`，但 DB 里那批 `dueAt` 仍带着 +7d → 到期队列/褪色标签突然全部"未到期"，与快进期间复习记录互相矛盾；`dueAt < 现在` 的老卡又瞬间集体爆到期。
4. Clock.setOffsetDays 不触发 DueReminder 重排（`Clock.kt:37-41`，无联动），偏移变更后的在途闹钟仍是旧时刻。

修法：把偏移视为纯 UI 演示态——`dueAt` 一律用未偏移的 `System.currentTimeMillis()` 存储；演示时 `Scheduler.dueQueue(cards, Clock.now())` 的注入点已经支持假 now，不需要写脏库。AlarmManager target 计算改用 `fireAt - offsetMillis` 还原真实时刻，且 `Clock.setOffsetDays` 里补发 `ACTION_SCHEDULE`。若要跨重启保留偏移，持久化到现有 prefs（与 anchor_store 同级），测试性见「单测清单」#12。

---

### [中] 复习会话完成只 cancel 不重排：完成后剩余未来卡的提醒永久消失

`app/src/main/java/com/memorypalace/review/ReviewSession.kt:146-147`

```kotlin
// M14：复习完了就撤掉"该复习了"的提醒，别让它继续挂着
appContext?.let { DueReminder.cancel(it) }
```

`cancel()` 同时 `am.cancel(pi)` 掉唯一 PendingIntent（`DueReminder.kt:197-208`），而队列里别的卡今天晚些时候才到期。今天全部到期卡复习完 ≠ 未来无到期卡。
可复现：3 张卡（2 张已逾期、1 张今晚 20:00）→ 早上复习完 2 张 → 20:00 无任何提醒；日志只有 `reminder cancelled`。
修法：把 `DueReminder.cancel(it)` 换成 `scope.launch { DueReminder.schedule(it) }`（schedule 内部对"无卡/全到期"本就有 cancel 分支，语义自洽）。

---

### [中] clear()：removeAnchor 失败也照删记录——系统侧锚点变成无主泄漏，且再无从应用内清理的路径

`app/src/main/java/com/memorypalace/anchor/AnchorScene.kt:307-318`

```kotlin
records.value.forEach { rec ->
    rec.uuid?.let {
        runCatching { WorldTrackingManager.removeAnchor(UUID.fromString(it)) }
            .onFailure { e -> Log.e(TAG, "removeAnchor($it) failed", e) }   // 只打日志
    }
}
records.value = emptyList()
store.save(emptyList())   // ← 不管删除成败，UUID 都从持久层消失
```

可复现：锚点服务瞬时不可用（Full Space 尚未进入、服务重启）时按 CLEAR → N 个 `removeAnchor` 全部失败 → 记录清空 → 世界地图里 N 个 `mp:xxxx` 锚点永久残留，占用用户系统锚点配额，UI 再也看不到它们。
修法：
```kotlin
val survivors = records.value.filter { rec ->
    rec.uuid == null || runCatching { WorldTrackingManager.removeAnchor(UUID.fromString(rec.uuid)) }
        .isSuccess.also { ok -> if (!ok) Log.w(TAG, "anchor ${rec.uuid} remove failed, keep record") }
}
records.value = survivors   // 失败记录保留，下次 clear 或 verifyRestored 再试
store.save(survivors)
```

---

### [中] 导入并发与确认双击：pendingPreview 读-清不原子，persistImported 查重-插入非事务，双通道同时入库产生重复卡

`app/src/main/java/com/memorypalace/data/ImportService.kt:159-165、203-215`；`app/src/main/java/com/memorypalace/ui/StudyPanel.kt:323`（确认按钮）

```kotlin
suspend fun confirmImport(): ImportStats? {
    val preview = pendingPreview.value ?: return null   // A、B 都能在这一行读到非空
    val stats = persistImported(preview.format, preview.cards)  // 长挂起：期间 B 已开始
    pendingPreview.value = null
...
val existing = DataGraph.repository.cards.first().map { it.front }.toHashSet()  // 快照查重
cards.forEach { c -> if (existing.add(c.front)) { DataGraph.repository.add(c.front, c.back) } }  // 逐条事务
```

可复现序列：
1. 预览就绪后用户双击"确认导入"（或确认页未置灰时手点 + `CONFIRM` 调试广播各来一发）。
2. 两个 `persistImported` 各自在对方插入前完成查重快照 → 同一批 front 全部插两遍（新 `UUID.randomUUID()` 主键，`OnConflictStrategy.REPLACE` 拦不住）。
3. 中途进程被杀 → 半批入库，无重试/回滚标记；下次确认再叠一层。
修法：
```kotlin
private val confirming = Mutex()
suspend fun confirmImport(): ImportStats? = confirming.withLock {
    val preview = pendingPreview.value ?: return@withLock null
    pendingPreview.value = null            // 先原子认领，再入库
    try { persistImported(preview.format, preview.cards) }
    catch (t: Throwable) { pendingPreview.value = preview; throw t }
}
```
去重下沉到 DAO：`@Insert(ignore) + INSERT OR IGNORE`（对 front 建唯一索引）或整批包 `@Transaction` 的 `upsertAll(List)`——顺带把 1 万卡 1 万事务的循环换掉（健壮性，见下）。

---

### [中] Room：version=1 + exportSchema=false + 零迁移 + 无 destructive 退路——schema 演进一 bump 版本就启动崩溃，且无法写迁移测试

`app/src/main/java/com/memorypalace/data/db/AppDatabase.kt:6`；`app/src/main/java/com/memorypalace/data/DataGraph.kt:36-38`

```kotlin
@Database(entities = [CardEntity::class], version = 1, exportSchema = false)
...
Room.databaseBuilder(..., "memory-palace.db").build()   // 无 addMigrations / fallbackToDestructiveMigration
```

可复现序列：M-next 给 `CardEntity` 加一列（比如 deckId）→ 老用户升级 → 校验 hash 不匹配 → `IllegalStateException: A migration from 1 to 2 was not provided` → Application.onCreate 崩，全 app 起不来。`exportSchema=false` 又让 `MigrationTestHelper` 无从验证。
修法：`exportSchema = true` + `room { schemaDirectory("$projectDir/schemas") }`，升版本必须提交 `Migration(1,2)`，并对"忘了加迁移"的开发期兜底保持显式抉择而非默认 destructive（destructive 会静默清空用户全部卡片，对本 app 不可接受）。

---

### [中] Application.onCreate 主线程同步做磁盘 IO：SharedPreferences×3 + JSON 全量解析 + Room builder 串行在启动关键路径

`app/src/main/java/com/memorypalace/anchor/AnchorScene.kt:78-79`（`AnchorStore(context)` 构造即 `getSharedPreferences`，首访同步读盘；`store.load()` 解析全部记录）；`app/src/main/java/com/memorypalace/data/PresetDecks.kt:29-30`（又一次同步 prefs 读）；`app/src/main/java/com/memorypalace/app/MemoryPalaceApplication.kt:22-31`（全链在 onCreate）；`app/build.gradle.kts:14-15`（minSdk/target=35，无 startup 延迟通道）

冷启动 `getSharedPreferences` 首次访问阻塞至文件读入（StrictMode DiskRead 违规），锚点记录多时 JSON 解析同线程执行；应用注释自述"启动 ANR 只能等真机验证"（`MemoryPalaceApplication.kt:33`）——这条链正是嫌疑。修法：`AnchorStore.load/save` 标 `suspend` 并 `withContext(Dispatchers.IO)`，`register()` 里 `scope.launch { records.value = store.load() }`；prefs 可先 `context.getSharedPreferences` 挪进 IO。UI 侧 `records` 本就是 StateFlow，多一帧空态无感。

---

### [中] FireReceiver 收闹钟后立刻 return，DB 查询+发通知跑在游离协程——进程被回收时提醒静默丢失

`app/src/main/java/com/memorypalace/notify/DueReminder.kt:57-62`

```kotlin
override fun onReceive(context: Context, intent: Intent) {
    Log.i(TAG, "alarm fired, posting due notification")
    scope.launch { postDue(context) }   // onReceive 立即返回；系统随时可回收此进程
}
```

可复现序列：Doze 下闹钟拉起进程 → onReceive 返回 → 进程在 `repository.cards.first()`（Room 冷开库，最坏数百 ms）完成前被杀 → 通知没发、也不会再触发（闹钟是一次性的）。
修法：
```kotlin
val pending = goAsync()
scope.launch { try { postDue(context) } finally { pending.finish() } }
```

---

### [中] SM-2 边界：逾期无上限、到期队列无封顶——时间机器快进/久未使用后一次会话爆全部卡；`intervalDays=0` 卡若经 `reviewByPrefix` 评 "REMEMBERED" 后 `(0*2.5).roundToLong()=0 → coerceAtLeast(1.0)` 行为对，但 `dueQueue` 排序对同 now 到期卡不稳定

`core/src/main/java/com/memorypalace/core/schedule/Scheduler.kt:54、66-68`；`review/ReviewSession.kt:87-99`（整队列直接进 `State.queue`，UI 逐张扛）

可复现：`SET_OFFSET days 365` 后 `review.START` → 数百张预设卡一次性全进队列，头显面板里要手动滑完；`dueQueue` 相同 `dueAt`（批量导入的新卡 due=now 同一毫秒级）无二级排序键，两次查询顺序可不同，无锚点位置感。
修法：`dueQueue(cards, now, cap = 50)` + `sortedWith(compareBy({ it.dueAt }, { it.id }))`；超出部分保留为"明日再学"（Anki 式新卡/复习日上限），至少给会话一个封顶常量。

---

### [低] reviewByPrefix 全表扫描 + `firstOrNull` 静默错配：前缀撞车时评到别人的卡

`app/src/main/java/com/memorypalace/data/CardRepository.kt:38-44`

```kotlin
val match = dao.all().firstOrNull { it.id.startsWith(idPrefix) }
```

调试链路虽仅 debug 导出（`DebugFlags.kt:14-19`），但 8 位前缀撞车在万卡库生日概率≈可测级别；命中即 `upsert` 改错卡且无告警。修法：SQL 侧 `LIKE idPrefix || '%'` 且 `LIMIT 2`，>1 条时打警告返回 null。

---

### [低] AnchorRecord.placedAt 用 System.currentTimeMillis() 而非 Clock.now()，与卡片时间线（带偏移）不同源

`app/src/main/java/com/memorypalace/anchor/AnchorScene.kt:278、291`。演示快进 7 天后新放锚点，卡片 `createdAt` 带 +7d、锚点 `placedAt` 真实时间——任何按时间排序/展示"放置于 X 天前"的 UI 会自相矛盾。修法：统一走 `Clock.now()`，或都用真实时间（与高severity Clock 条目一并定调）。

---

### [低] ImportPickerActivity 自建 scope 从不取消；Activity finish 后协程仍持 applicationContext 跑完整导入预览

`app/src/main/java/com/memorypalace/data/ImportPickerActivity.kt:20、37-42`

`onActivityResult` 里 `scope.launch { previewFromUri(...) }` 后立刻 `finish()`——意图是"活着跑完"，但 Job 永不 cancel，属无主协程（预览大文件期间用户已回到头显，无进度载体、失败只进 logcat）。修法：改 `ImportService.scope.launch`（对象级生命周期本就是它的语义），Activity 不留 scope。

---

### [低] previewFromUri 临时文件名 `import_${System.currentTimeMillis()}.ext`：并发选取同名毫秒碰撞 → 一方 `finally tmp.delete()` 删掉另一方正在解析的文件

`app/src/main/java/com/memorypalace/data/ImportService.kt:132、154`；`parseApkg` 同样按毫秒命名（`:185`）。另 `rawQuery` 抛异常时 `db.close()`（`:195`）被跳过，句柄泄漏到 GC。修法：`File.createTempFile("import_", ".$ext", context.cacheDir)`；`SQLiteDatabase` 用 `use` 语义（try/catch/finally close 或包一层 `runCatching`）。

---

### [健壮性] persistImported 逐条 upsert 无批事务：万卡 apkg = 一万次独立 WAL fsync，中途失败留下"半套牌组"且 ImportStats 不可恢复

`app/src/main/java/com/memorypalace/data/ImportService.kt:207-211`；`data/db/CardDao.kt:21-22`。修法：`@Upsert suspend fun upsertAll(cards: List<CardEntity>)` 包 `@Transaction`，一次 fsync；PresetDecks 的幂等已由 front 去重兜底（`PresetDecks.kt:42`），但大导入的耗时/原子性仍需批处理。

### [健壮性] AnchorStore 与 Room 是两套互不知晓的持久域：卡片被 `remove()` 后 AnchorRecord 无级联，RoomStage 会为不存在的卡建面板

`data/CardRepository.kt:46`（当前无 UI 调用方，属埋雷 API）。修法：`remove` 里同步 `AnchorScene.records`/`store` 过滤该 key 并尝试 `removeAnchor`，或给导入/删除建统一门面。

### [健壮性] CSV 未闭合引号行会把 EOF 前全部内容吞进单字段；`c == '"' && field.isEmpty()` 使非引号起始字段中的引号变字面量

`core/src/main/java/com/memorypalace/core/importer/CsvImporter.kt:48-57`（`inQuotes` 直到文本结束都不闭合 → `:61` 把残尾当一行）。畸形导出文件会"只剩 1 张卡"且无任何 warning 计数。修法：parseRows 返回 `Pair<List<List<String>>, List<Int>>`（失败行号），ImportStats 增加 `malformedRows` 上报 UI。

### [健壮性] TextImporter 分隔符按"行内是否存在"取先，英文正文含 ` - ` 时 front 被腰斩；`#` 注释规则吃掉合法以 # 开头的词条

`core/src/main/java/com/memorypalace/core/importer/TextImporter.kt:10-17、15`。属格式权衡，至少把 `#` 规则收紧为"行首且后随空白"。

### [健壮性] Clock 不可注入（object + private set + 仅天粒度），CardRepository 直连 Clock.now()，JVM 层无法测"到期边界恰在 now"

`data/Clock.kt:19-31`；`data/CardRepository.kt:19、27、49`。Scheduler 本体时间注入设计是好的（`core/schedule/Scheduler.kt:37`），但 app 装配层没留缝。修法：`CardRepository(dao, now: () -> Long = Clock::now)`，测试给假钟。

### [健壮性] backup_rules/data_extraction_rules 若默认全备，SharedPreferences 里的锚点 UUID 会跨设备还原成新设备上必然解析失败的孤儿记录

`app/src/main/AndroidManifest.xml:11-12`（引用了 `@xml/backup_rules`；依赖 verifyRestored 降级路径兜底——见上条高severity，降级不可逆，建议备份排除 `records` 键或在首启主动清 uuid 段）。

---

## 建议新增单测 / 插桩测试清单

core（JVM）：
1. `SchedulerTest`：`intervalDays=0 + repetitions=2` 的脏卡走 REMEMBERED——断言 interval≥1 天、dueAt>now，杜绝 0 间隔死循环。
2. `SchedulerTest`：逾期 10 年的卡（`dueAt = now - 3650d`）进 `dueQueue` 后位置与数量断言（为封顶改动做回归锚点）。
3. `SchedulerTest`：`Grade` quality 越界注入（直接调 `review` 前手工构造 `easiness=Double.NaN / 1.3 / 99.0`）——断言输出 dueAt 不为 0/负/溢出 Long（当前 `(days*DAY).toLong()` 对 NaN 会静默变 now）。
4. `CsvImporter`：未闭合引号、引号后跟杂字、仅空白行、`\r\n` 在引号内保留的表驱动用例。
5. `TextImporter`：含 `" - "` 的英文句子、行首 `#` 词条、多 TAB 行（第三个字段丢失）各一条失败即暴露的用例。
6. `ApkgExtractor`：构造"声明解压后 > 上限"的 zip bomb 条目——修复前该测试应 OOM/超时，修复后应抛 `IOException` 而非分配内存（小上限注入后测）。

app（JVM+Robolectric 或 androidTest）：
7. `AnchorStoreTest`：一条记录缺 `px`/带未知字段/整个 JSON 截断时，其余记录必须原样读出（回归本次阻断项）；`anchorLabel` 缺失读 null 的旧数据兼容用例锁死。
8. `AnchorStoreTest`：save→load round-trip 含 `uuid=null`、label 含中文/emoji/引号——防 JSONObject.NULL 语义漂移。
9. `AnchorSceneTest`（注入 fake WTM）：并发两次 `place()` → `records` 恰好 2 条（复现/验证锁修复）。
10. `AnchorSceneTest`：`loadAnchor` 首次返回瞬时 Error、第二次 Success 时记录**不得**降级；仅 NOT_FOUND 码才降级。
11. 插桩 `MigrationTest`（Room）：v1→v2 加列迁移 + `MigrationTestHelper`；先在 `exportSchema=true` 上提交 v1 schema JSON。
12. `DueReminderTest`（Robolectric + ShadowAlarmManager）：schedule 后模拟 BOOT_COMPLETED→断言重排；复习完成→断言剩余未来卡仍有闹钟；`Clock.setOffsetDays(7)` 后 alarm target 换算回真实时间断言（覆盖 Clock 污染修复）。
13. 插桩生命周期测试：`am kill` 进程→广播 `anchor.PLACE` 拉起→断言锚点+卡片都恢复；对拍 `card.ADD` 并发 `anchor.CLEAR` 不产生"有记录无卡/有卡无记录"孤儿组合。
