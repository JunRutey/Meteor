# ADR-068 · 用「每日一问」替换「存张记忆」，图片能力并入相册

> 状态：已采纳。关联：[ADR-052](ADR-052-media-album-hub.md)（媒体存储总纲）、
> [ADR-065](ADR-065-delete-records-page-and-merge-files-into-album.md)（记录页并入相册）、
> [ADR-067](ADR-067-unified-binary-payload-encryption.md)（二进制载荷统一加密）、
> [ADR-039](ADR-039-notification-master-random-window.md)（通知总开关与每日提醒）、
> [ADR-033](ADR-033-daily-reminder-and-notification-channels.md)（通知渠道）。

## 一、问题

首页日历的「选中日」卡片左下角有一个按钮：当天没有图片时叫**「存张记忆」**，直接从系统
相册挑一张图存进档案；当天有图片时叫**「查看记忆图片」**，跳到该天的相册过滤视图。

这个按钮是 v0.7.0 的产品：那时相册还没有统一入口，而「往某天塞一张照片」是当时唯一
能表达「这天有东西」的轻量动作。此后发生了三件事，让它失去了存在理由：

1. **ADR-052 / ADR-065 把图片、视频、音频与导入文件合并进了一个相册**，相册自己就能
   导入、按日期分组、按类型筛选。首页那条「挑一张图」只是同一件事的一个更窄的入口。
2. **ADR-067 统一了二进制载荷的加密通道**：图片、媒体、附件现在走同一套分块帧与同一把
   媒体数据密钥。首页那条路径却是 `ImageStore` 的一次整文件读 + 压缩 + 整文件加密，
   是**唯一**还在走旧语义的写入点（`CalendarViewModel.saveImage` 甚至要用
   `File.setLastModified` 手动伪造日期）。
3. **它只覆盖「图」**。视频、音频、文档要么去相册，要么去日记编辑器插图，动作分散在
   三个地方，用户得先判断「我要放的东西属于哪个入口」。

同时，首页缺少一个**能长期使用**的轻量功能：写下一点东西，之后被提醒回来。日历卡片
左下角这个位置曝光最高、成本最低，正好可以承载它。

## 二、决定

### 二.1 删除「存张记忆」，日卡片左按钮改为「每日一问」

- `CalendarScreen` 删除 `onPickImage`、`imageDates`，以及只服务于「跳到某天相册」的
  `onOpenImages`；`DayCard` 的左按钮改为**「每日一问」**（`onOpenQuestions`），不再有
  「当天有没有图」的分支。
- `CalendarViewModel` 删除 `saveImage` 与 `imageDates` 状态，`refresh()` 不再扫描
  `ImageStore`。
- `:app` 里那段 `pendingImageDate` + `rememberImagePicker` 的接线一并删除。
  `rememberImagePicker` 这个 Composable **保留**：日记与人物编辑页仍在用它插图。
- 底部快捷入口**保持**「相册 / 人际关系」两格（ADR-060），不新增第三格。

**图片能力没有减少，只是入口收敛到相册**：相册新增「添加媒体」入口，走
`MediaImporter` → `MediaStore`，即 ADR-052/063/067 那条统一底层，支持图片、视频、音频，
并有内容哈希去重（ADR-064）。首页那条 `ImageStore` 路径本来就不支持视频/音频，也不算
「统一底层」。

### 二.2 新增「每日一问」（Daily Question）

核心循环：**今天想到一个问题 → 写下 → 在设定的时间被提醒 → 点通知回来回答**。

| 能力 | 规则 |
|---|---|
| 提问 | 一段文字（≤500 字）+ 一个或多个提醒时间 |
| 默认提醒 | **明天同一时刻**（不配置也能用） |
| 自定义提醒 | 日期 + 时间，必须晚于现在 |
| 多个提醒 | 最多 8 条，同一问题可设多个不同时刻 |
| 进入详情 | **未回答 → 直接编辑**；**已回答 → 直接阅读**（ADR-069 起统一为先阅读） |
| 回答 | 像日记一样「编辑 / 预览」两个界面，Markdown 正文 |
| 回答时间 | **精确到分钟**显示（`M月d日 HH:mm`） |
| 多次回答 | 允许，每次是一条新的回答，旧的永不覆盖 |
| 通知按钮 | **延迟 1 小时** / **明天再提醒**；点完通知消失，到点再发一次 |
| 通知点击 | 打开该问题的详情页 |
| 回答之后 | 该问题的待提醒清空（已经答过，不必再催） |

**默认提醒选「明天同一时刻」而不是固定早上 9 点**：写下问题的时刻本身就是信息——22:40
想到的事属于夜猫子，第二天早上 9 点弹出来更像一个无关的闹钟；同一时刻回来则是「昨天这个
时候你在想这个」。

### 二.3 存储

新文件：`我的人生/每日一问/<id>.bin`，**加密 JSON**，一把随机 32 位十六进制 id 一文件。

```json
{"version":1,"id":"<32 hex>","text":"…","createdAt":<epochMillis>,
 "reminders":[<epochMillis>,…],"answers":[{"id","text","answeredAt"},…]}
```

- 密钥：**Keystore 主密钥**（`FileCrypto`），**不是**媒体数据密钥。理由同 ADR-067 的
  「文本走主密钥、二进制走 MK」分界：这是几百字节的文本，没有并行的必要，而主密钥是
  「读出内容」的最低门槛。
- `.bin` 而不是 `.md`：`RecordScanner` 只把 `.md` 当记录，所以一个问题**不会**进入搜索
  索引，也**不会**成为 AI 工具的「档案证据」。这和 `AiChatStore` 的取舍一致。
- 写入沿用仓库既有的原子改名（`.tmp` → rename），单文件损坏不会让整个功能崩掉
  （`list()` 跳过读不出来的文件）。

### 二.4 提醒与调度

- `reminders` 只存**待触发的**时刻：闹钟一投递就从集合里移除，通知的两个按钮则追加新的
  时刻。这样文件本身就是「应该存在哪些闹钟」的唯一真相，任何一次同步都能只按它重建。
- 调度只在每个问题上报**最早的那条**（`nextPending`）。投递后再 arm 下一条，理由：
  闹钟表小、重启后不需要额外账本、删除问题时残留的闹钟会被「问题已不存在」静默忽略。
- **一个 (问题, 时刻) 一条闹钟**，靠 `PendingIntent` 的 **data URI**
  （`meteor://question-reminder/<id>/<epochMillis>`）区分，而不是靠 request code。
  request code 数量有限、取哈希必然碰撞，碰撞的结果是**静默覆盖另一个问题的闹钟**；
  `Intent.filterEquals` 包含 data，所以 URI 方案天然唯一。
- 复用 `DailyReminderScheduler` 的精确闹钟策略：优先
  `setExactAndAllowWhileIdle`，未授权则 `setAndAllowWhileIdle`，并记录精度。

### 二.5 通知与跳转

- 独立渠道 `meteor.questions`：静音「日记提醒」不应该连带静音用户**自己排的**问题提醒。
- 通知 id 与 contentIntent 的 request code 都按问题 id 派生，同一问题重发时覆盖同一条。
- 点通知→跳详情页走 `QuestionOpenRequest`（`@Singleton` + `StateFlow`）：通知是系统在
  Activity 可能已死的情况下投递的，它无法直接 `navController.navigate`；它只记下「要打开
  哪个问题」，由导航图在就绪后 `consume()` 一次。`consume` 是原子的，配置变更不会重放。

## 三、R1 四问

1. **属于哪个模块？**
   - 存储：`core-storage`（`QuestionStore` / `EncryptedQuestionStore`），与
     `DiaryQueries`/`MoodQueries`/`AiChatStore` 同级。
   - 提醒策略与调度：`core-notify`（`QuestionReminderPolicy` /
     `QuestionReminderScheduler`），与 `DailyReminderPolicy` 同级；**纯逻辑，不认识存储**。
   - 界面：**新增模块 `:feature-question`**（纯 Compose）。
   - 投递链路（闹钟接收器、通知、通知→导航）：`:app`。

   为什么新开一个模块而不是塞进 `:feature-calendar`：日历模块只依赖 `core-storage`，
   它的职责是「月份网格 + 当日心情」；每日一问有独立的存储、提醒模型、两个整屏页面。
   塞进去会让日历模块同时具备两种无关职责，违背「单个功能的改动不要把整个应用弄坏」。

2. **依赖谁？谁依赖它？是否单向、是否成环？**
   ```
   app → feature-question → feature-editor      （回答的 Markdown 编辑面）
   app → core-storage, core-notify              （已存在）
   ```
   两条边都是新增的、单向的，`tools/check-module-graph.sh` 已登记，无环。
   `feature-question` 不依赖 `core-storage`、不认识 `QuestionStore`：它只收
   `QuestionRow`/`QuestionDetailUi` 这类**已格式化**的展示模型，由 `:app` 的
   ViewModel 做转换。

3. **对外接口是什么？是否足够窄？**
   - `core-storage.QuestionStore`：`list/find/create/addAnswer/updateReminders/delete`，
     六个方法，全部收发 `StoredQuestion`/`Instant`/`String`。没有分页、没有查询语言。
   - `core-notify.QuestionReminderPolicy`：六个纯函数。`QuestionReminderScheduler`：
     `schedule/cancel/canScheduleExactAlarms`。
   - `:app` 的 `QuestionReminderController`：`syncAll/sync/handleAlarm/snooze/cancelAll`。
   - `:feature-question`：`QuestionListScreen` / `QuestionDetailScreen` 两个 Composable，
     参数全部是数据与回调；**不导航、不读 DI、不持久化**。
   - `QuestionOpenRequest`：`request` / `consume` + 一个 `StateFlow`。
   七个冻结接口**一个字符未动**。

4. **数据格式变更？是否向后兼容？**
   - 新增一条目录 `我的人生/每日一问/` 与一种加密 JSON（`version:1`）。
   - **不改** frontmatter 五键、不改记录文件格式、不改媒体/附件/图片的载荷格式、
     不改索引 schema、不改备份 zip 结构。
   - 旧版本客户端读到新目录：`RecordScanner` 只看 `.md`，会**完全忽略**它；备份
     （`StorageRouter.exportTo` 逐文件遍历）会把 `.bin` 原样带出，只是旧客户端导入后
     读不到内容而不是损坏。属于「旧客户端不认识的新数据」，与 ADR-056 的取舍一致。
   - 删除「存张记忆」**不影响既有数据**：它写的是 `ImageStore` 的图片，那些图片仍在
     `数字遗产/图片/` 下、仍在相册里可见、仍能被日记引用。

## 四、后果

- 首页「按天塞图」的捷径换成了「每日一问」：日卡片左下角就是提问入口。看某天图片不再从
  日历直达，改从相册按日期查看；**添加**统一走相册，路径更长一次点击，换来图片/视频/音频
  同一个底层、同一套去重与加密。
- `ImageStore` 不再有「首页直接写入」这条调用路径，只剩编辑器插图与人物头像——
  这正是 ADR-067 想收敛的样子。
- 每日一问的提醒**不重排**成「每天一次」：它是一次性的待办。用户想要连续多天被提醒，
  就在提问时多设几个时间（≤8 条），或回答后用通知的「明天再提醒」。
- 通知按钮的两个动作会**改写**该问题的待提醒集合，因此同一个问题在多台设备上不同步
  （本来就没有同步）。
- 残留闹钟：删除一个问题时，`:app` 只能靠 `syncAll()` 重排已知问题，已删除问题留在系统
  闹钟表里的那一条要到点后才会被「问题已不存在」静默丢弃。这是可接受的代价——它不发
  通知、不改数据。彻底清理需要给 controller 增加 `cancel(questionId, instants)`，留待需要
  时再加（不改冻结接口，属于可用默认实现扩展的地方）。
- 回答不进入搜索索引（`.bin`）。因此「我上次怎么回答那个问题」目前只能在每日一问里翻。
  这是一条**有意的**限制：把自我追问排除在「档案证据」之外，避免 AI 与搜索把一个人的
  自问自答当成事实陈述。将来若要做，应作为独立 ADR 决定索引哪些字段。

## 五、不变量

- 没有服务端；每日一问全部在本机。
- 正文静态加密，密钥绑定设备，换机只能走明文备份（§7）。
- `allowBackup=false` 不变。
- 通知仍受总开关约束：总开关关闭时，每日一问的闹钟一并取消，不再投递。
- 面向用户的文案全部走 `strings.xml`（本功能在 `:feature-question` 与 `:app`）。

## 六、修订（2026-11-28，v1.6.0 发布后）

本 ADR 初稿把日卡片左按钮留作「查看当天图片」，把每日一问放到底部快捷入口的第三格，
v1.6.0 按初稿发布。发布后确认入口应落在日卡片左下角：那个位置原本就是「存张记忆」，
让给每日一问才是这次替换的本意；底部两宫格不应该被挤成三宫格。

- 日卡片左按钮改为「每日一问」，点击进问题列表（`ROUTE_QUESTIONS`）。
- 底部快捷入口恢复「相册 / 人际关系」两格；`QuestionsGlyph` 与「写下并回答」提示删除。
- 日历不再有「跳到某天相册」的按钮；按天看图片改从相册进入。旧深链
  （`gallery?date=` → `album?date=`）仍作为外部链接兼容保留。
- 不涉及数据格式、存储或提醒策略的改动。
- 界面随后在 [ADR-069](ADR-069-daily-question-ui-rework.md) 重做：列表只浏览、提问
  独立成页、详情分阅读与回答两种形态、时间选择改为与生辰页一致的滚轮；「未回答进入
  即编辑」也改为统一先阅读。本 ADR 的数据格式、提醒调度、通知与跳转链路仍是现状。
