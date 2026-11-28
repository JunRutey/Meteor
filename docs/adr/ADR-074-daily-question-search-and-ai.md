# ADR-074 · 每日一问进入搜索索引与 AI 工具

> 状态：已采纳。**修订 [ADR-068](ADR-068-daily-question-replaces-save-memory.md) 的第四条后果**
> （「回答不进入搜索索引 / 不作为 AI 档案证据」）。关联：[ADR-073](ADR-073-daily-question-answer-deletion.md)。

## 一、问题

ADR-068 有意把每日一问排除在搜索与 AI 之外：`.bin` 不被 `RecordScanner` 扫描，
因此既搜不到，也不会成为 AI 的「档案证据」。理由是避免把一个人的自问自答当成事实陈述。

实际使用中这条限制带来了真实的反例：

- 「我上次是怎么回答那个问题的？」只能进每日一问逐条翻，没有全文检索；
- AI 助手看不到用户反复自问的主题，无法把这些长期追问纳入对话。

ADR-068 自己也写明「将来若要做，应作为独立 ADR 决定索引哪些字段」。本 ADR 就是那次决定。

## 二、决定

### 二.1 索引范围：问题文本 + 全部回答

每条问题产生一个检索单元，正文由**问题文本 + 该问题的所有回答正文**拼成。
一个问题一次命中，不按回答拆成多条——命中的是「这个问题及其思考」，不是某一条回答。

### 二.2 用内存附加索引，不碰冻结的 `IndexModule`

问答是加密 `.bin`，`RecordScanner` 只扫 `.md` 且不认识密钥。在 `:app` 新增
`QuestionTextIndex`：这是 `QuestionStore` 之上的一层薄适配器，每次搜索时从
`QuestionStore.list()` 读取已解密的问题与回答，用 `CjkTokenizer` 分词、
`TextMatcher` 打分（与主索引同一套 Kotlin 排名口径，单个汉字也能命中），
不写 SQLite。理由：

- `IndexModule` / `IndexSchema` 是冻结的派生索引（§8、R4），且它的唯一数据源是
  `RecordScanner`（R6）。把 `.bin` 塞进那条通道要改扫描器与密钥边界，代价大；
- 每次读取而非缓存：一条问答只有几百字节，重读很便宜，换来「刚写的回答、
  刚删的回答立即可搜」而无需在每条写入路径挂失效钩子，也不会有解密后正文
  常驻内存。`:app` 已依赖 `core-storage` 与 `core-index`，不新增依赖边。

### 二.3 命中项标注为独立类型，明确「这是主观自问」

新增 `RecordType.QUESTION`（wire=`question`）。搜索结果里它以「每日一问」标签出现，
与日记/心情等事实记录区分——这正是 ADR-068 担忧的化解方式：**不是不让它被看见，
而是让它以「这是我问自己的」身份被看见**，搜索与 AI 都不会把它误读成客观事实。

搜索命中点击后复用 `QuestionOpenRequest` 深链打开该问题详情。

### 二.4 AI 工具（见 ADR-075）

问答的读/写工具作为独立的 ADR-075 决定，本 ADR 只确立「问答可作为档案内容被检索」
这一前提。AI 工具里所有问答内容都带 `type=question` 标注。

## 三、R1 四问

1. **属于哪个模块？** 内存索引在 `:app`（与 `AttachmentTextIndex` 同级）；
   `RecordType` 是 `core-storage` 的既有枚举。不新增模块。
2. **依赖谁？是否成环？** 不新增依赖边：`:app → core-storage`（`QuestionStore`）已存在。
3. **对外接口是否够窄？** `QuestionTextIndex.search()` 只返回既有的
   `SearchMatch`（`recordId` 为 question id，`type=QUESTION`），`SearchService` 只多一个刷新方法。
4. **数据格式变更？** `RecordType.QUESTION` 的 wire 值是新增枚举项，不改动任何既有
   文件的 `type`；问答 JSON 格式不变。穷举 `RecordType` 的 `when` 分支全部补齐。
   冻结接口（§8）一个字符未动。

## 四、后果

- 「我上次怎么回答的」可全文检索；搜索结果「每日一问」标签明确其主观性质。
- 问答内容每次搜索现读现分词，不缓存、不落盘，写入后立即反映。
- `RecordType` 新增枚举项，所有穷举 `when` 必须补齐（编译期强制）。
- ADR-068 的第四条后果被本 ADR 取代；其余约束（本地、加密、提醒、深链）不变。

## 五、不变量

- 没有服务端；问答索引完全在本机内存，不上传。
- 正文静态加密不变；内存索引在应用后台时清理（与附件索引一致）。
- 面向用户的文案全部走 `strings.xml`。
