# ADR-075 · AI 工具补齐每日一问的读写能力

> 状态：已采纳。关联：[ADR-074](ADR-074-daily-question-search-and-ai.md)（问答进入检索）、
> [ADR-030](ADR-030-ai-tool-permissions.md)（工具权限细分）、
> [ADR-032](ADR-032-ai-write-confirmation-and-scoped-deletion.md)（写操作确认）。

## 一、问题

AI 助手的 `ArchiveTools` 覆盖了日记、心情、人物、附件，却看不到每日一问。用户问
「我最近在反复纠结什么」「帮我把刚才说的记成一次回答」时，模型既无法读取那些
自问自答，也不能把口述写成一条回答。ADR-074 确立了问答可作为档案内容被检索，
本 ADR 把相应的工具能力补进 `ArchiveTools`。

## 二、决定

### 二.1 工具

读工具（随 `READ_ARCHIVE` always-on）：

- `list_questions`：列出问题（id、文本、回答数、是否回答、归档日），按创建时间倒序，条数有上限。
- `read_question`：按 question id 读取问题文本与全部回答；回答带时间。

写工具（需 `WRITE_QUESTION` 权限，且**每次调用都要用户确认**）：

- `add_question`：新建问题（≤500 字），可带 ISO-8601 提醒时刻。
- `add_answer`：给指定问题追加一条回答（≤20000 字）。

**不做删除工具**：问答的删除（问题与单条回答）保持在应用 UI 内完成（ADR-073），
不给模型开放不可逆的删除能力，降低误写风险，也让本 ADR 不依赖 ADR-073。

### 二.2 权限：独立分类，跟随「写日记」授权

新增 `AiToolCapability.WRITE_QUESTION` 与 `AiToolPermissions.writeQuestion` 字段，
分类独立，但取值跟随 `writeDiary`：

```
writeQuestion = writeEnabled && writeDiary
```

理由：写问答与写日记同属「把用户口述写成个人文字」，用户既然授权模型写日记，
就没有理由认为写问答需要另一套开关；这样**不必新增设置项、不触碰
`AppSettings` 的持久化与备份格式**，同时保留独立 capability 以便将来拆开。
写工具全部列入 `APPROVAL_REQUIRED_TOOLS`，每次调用前应用弹确认（ADR-032）。

### 二.3 标注主观性质

所有问答工具返回内容都带 `type=question` 字段，提示词明确告诉模型：这是用户的
**自问自答、主观想法**，不是客观事实，不要据此断言用户的经历。这延续 ADR-068
的担忧，但以「标注」而非「排除」化解（ADR-074）。

### 二.4 作用域与上限

- 问答是本人记录，不按 `personId` 分片；persona 对话（`allowedPersonId != null`）
  下问答读写工具一律拒绝——自问自答不属于任何人物。
- 所有字符串参数校验长度，问题 ≤500 字、回答 ≤20000 字（沿用 `QuestionStore` 上限），
  返回条数与正文长度都有上限，避免一次工具调用灌满上下文。
- `QuestionStore` 是可选依赖：未注入时问答工具从 `specs()` 隐藏、`execute()` 返回
  `tool-unavailable`，与附件工具的处理一致。

## 三、R1 四问

1. **属于哪个模块？** `ArchiveTools` 在 `feature-ai`（非冻结）；`QuestionStore` 在
   `core-storage`。`:app` 的 `AppModule` 负责注入。不新增模块。
2. **依赖谁？是否成环？** `feature-ai → core-storage` 已存在（已用 `DiaryQueries` 等），
   加 `QuestionStore` 不新增依赖边；模块图无环。
3. **对外接口是否够窄？** 四个工具各自只收发 id/文本/时间；权限分类进单一分类表。
4. **数据格式变更？** 无。问答 JSON 不变；`AiToolPermissions` 新增字段有默认值，
   `AiConfig.toolPermissions` 是派生属性不落盘。冻结接口 `AiModule`（§8）未动，
   新能力走 `ArchiveTools` 与带默认值的权限字段。

## 四、后果

- AI 能读取并引用用户的自我追问（带主观标注），也能经确认后写入问答。
- 写问答需要「写日记」授权 + 单次确认双闸门，误写风险与写日记同级。
- 新增 4 个工具会进入 `specs()`，提示词随权限列出已授权写工具。

## 五、不变量

- 没有服务端；工具只在本机读写加密档案。
- AI 默认关闭；主开关关闭时 `AiToolPermissions` 恒为 `READ_ONLY`，写工具不可用。
- 面向用户的文案全部走 `strings.xml`。
