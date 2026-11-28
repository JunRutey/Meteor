# ADR-070 · 每日一问按天归档：日历入口与补写

> 状态：已采纳。关联：[ADR-068](ADR-068-daily-question-replaces-save-memory.md)（每日一问的数据与提醒）、
> [ADR-069](ADR-069-daily-question-ui-rework.md)（界面重做）。
>
> 本 ADR 只加一个归档日期字段与「按天进入」的入口逻辑；提醒、通知、删除、回答全部不变。

## 一、问题

ADR-069 之后，首页日卡片左下角的「每日一问」仍然打开**全部问题的总览**。对一个
「每日一问」功能来说，这个入口的语义不对：

- 它应该像「写日记」一样是**按天**的：今天的问题就是今天的问题，点进去看到的是它，
  而不是一列历史问题。
- 今天还没写问题时，应该像「补写日记」一样直接进编辑页，而不是先去列表里找「＋」。
- 过去某一天没写时，也应该可以补写，并归到那一天。
- 总览本身仍然有用（翻历史、批量管理），所以它不能消失，只是不该占着日卡片这个入口。

现有的 `StoredQuestion` 只有 `createdAt`（真正写下的时刻），没有「这个问题属于哪一天」。
今天写的问题两者相同，但补写过去某一天时不能相同：`createdAt` 是现在，归档日要写过去。
拿 `createdAt` 冒充归档日会让「日历上 11 月 20 日有问题」这个事实失真，也会把补写
混进今天的日期。

## 二、决定

### 二.1 增加归档日 `askedOn`

- `StoredQuestion` 增加 `askedOn: LocalDate`，默认值是 `createdAt` 在设备时区里的日期。
- `QuestionStore.create` 增加可选参数
  `askedOn: LocalDate = createdAt.atZone(ZoneId.systemDefault()).toLocalDate()`。
  现有调用点与单测不传它，行为不变。
- JSON 增加可选字段 `"askedOn":"yyyy-MM-dd"`，**格式版本仍是 1**：
  - 新客户端读旧文件：没有该字段 → 按 `createdAt` 派生，语义与旧版一致。
  - 旧客户端读新文件：`JSONObject` 不认识额外字段，照旧用 `createdAt` 显示；问题、
    提醒与回答仍能读，只是「归档日」这个新概念不可见。
  - 备份 zip 逐文件复制，结构不变。

### 二.2 日历日卡片按天进入

- `CalendarViewModel` 读取 `questionStore.list()`，构建
  `questionDays: Map<LocalDate, String>`（归档日 → 最新一条问题的 id）。同一天有多条
  问题时取最后创建的一条，其余仍在总览里。
- 日卡片左按钮的 `onOpenQuestions` 从 `() -> Unit` 改为 `(LocalDate) -> Unit`。
- `:app` 的回调：
  - 这一天在 `questionDays` 里 → 打开那条问题的详情（阅读页）。
  - 不在 → 打开带日期的提问页 `questions/new?date=<yyyy-MM-dd>`。
- 未来日期照旧没有日卡片按钮，因此不会出现「给未来补写」。
- 写日记的右按钮逻辑完全不动。

### 二.3 提问页接收归档日

- `ROUTE_QUESTION_NEW` 从 `questions/new` 改为 `questions/new?date={date}`，`date` 是
  可缺省的 String 参数；缺省 / 解析失败 = 今天。
- `QuestionComposeViewModel` 从 `SavedStateHandle` 读日期，保存时把 `askedOn` 传给
  `QuestionStore.create`。
- 当归档日不是今天时，提问页顶部显示一枚「为 11月20日 提问」的胶囊
  （`question_compose_for_day`）；今天不显示，避免多一行无信息的提示。
- 总览里的「＋」不带日期，仍然创建今天的问题。

### 二.4 总览的归属

- 总览页 `QuestionListScreen` 保持现状（列表 + 筛选 + 删除），只是入口从日卡片改成
  **侧边栏的「每日一问」**（ADR-071）。它仍然是全量列表，不按天过滤。

## 三、R1 四问

1. **属于哪个模块？** 字段与读写：`core-storage`（`QuestionStore` / `StoredQuestion`）。
   按天入口与路由：`:app`（`CalendarViewModel`、`MeteorNavHost`）。提问页的日期提示与
   日期格式化：`:feature-question`（`QuestionComposerState.dateLabel` /
   `QuestionFormat.dayLabel`）。日历模块仍只收一个 `LocalDate` 回调，不认识问题存储。
2. **依赖谁？谁依赖它？是否单向、是否成环？** 模块依赖边没有变化。`:app` 已经依赖
   `core-storage` 与 `:feature-question`，新增的是同一条边上的调用，不是新边。
3. **对外接口是什么？是否足够窄？** `QuestionStore.create` 只新增一个带默认值的参数；
   `StoredQuestion` 只新增一个带默认值的字段；`CalendarScreen` 的
   `onOpenQuestions` 从无参改为 `(LocalDate) -> Unit`，信息量只多一个日期。
   这些都不是冻结接口，也不需要新模块。
4. **数据格式变更？是否向后兼容？** JSON 新增可选 `askedOn`；版本仍为 1。旧文件按
   `createdAt` 派生，新文件写入明确日期；旧客户端忽略该字段。加密、目录、备份结构都不变。

## 四、后果

- 「日历上的一天」与「问题写下的时刻」第一次分开：补写的问题 `askedOn` 是过去，
  `createdAt` 是现在。详情页的「提问于 …」仍显示真实写下时刻；日历按 `askedOn` 归档。
- 同一天可以有多条问题（总览仍可继续提问）；日历入口只打开最新一条，其余靠总览。
  这是有意的取舍：日卡片是一个「入口」，不是列表。
- `questionDays` 在每次首页刷新时重读全部问题文件。问题数量是个人量级（几十到几百），
  读取已在 IO 线程上，可接受；若将来变慢，再给 `QuestionStore` 加按日期查询的窄方法。
- 时区：旧文件派生归档日时使用设备当前时区；跨时区旅行可能让旧问题落到相邻一天。
  新文件写入的是明确的 `yyyy-MM-dd`，不再受时区影响。
- 这个字段不影响提醒调度：闹钟仍只看 `reminders`，与归档日无关。

## 五、不变量

- 存储路径、加密密钥、原子写入、`.bin` 不进索引都不变。
- 提醒上限、通知渠道、延迟 / 明天再提醒、删除行为不变。
- 回答仍是追加式，回答时间仍精确到分钟。
- 没有服务端；数据仍只在本机。
