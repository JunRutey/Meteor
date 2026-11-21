# ADR-050 · AI 回复按 Markdown 只读渲染，降级安全

- 状态：已接受
- 日期：2026-11-21
- 影响：`:feature-ai`（新增 `AiReplyMarkdown`，`AiScreen` 一处渲染改为调用它）
- 关联：ADR-021（编辑器退回纯文本）、ADR-018（Markdown 方案定稿）、ADR-027（AI 走用户自有服务商）

## 一、问题

AI 回复气泡此前是**裸 `Text(bubble.text)`**：

```kotlin
SelectionContainer {
    Text(text = bubble.text, style = ..., color = ...)
}
```

而模型返回的是 Markdown。用户看到的是 `**重点**`、`### 小标题`、`- 第一条`、
`` `config.json` ``、`[文档](https://…)`、`<span style="color:#E53935">红</span>`
这样的原始标记——**星号、井号、反引号直接出现在气泡里**，读起来比排版收益更吵。

用户原话：「注意到 AI 功能回复会 `*`，比较影响观感，修一下」。

这不是编辑器的排版问题：编辑器侧早已定稿为「纯文本编辑 + 只读预览」（ADR-021、ADR-018），
AI 回复则**根本没有预览这一步**，标记没有任何地方被解释。

## 二、决定

**在 `:feature-ai` 内新增只读渲染器 `AiReplyMarkdown`，把常见的行内与行级标记
渲染成 `AnnotatedString`，交给 `Text` 直接绘制。**

对外接口只有两项，且都是新增：

```kotlin
fun AiReplyMarkdown.render(text: String, style: AiReplyStyle = AiReplyStyle()): AnnotatedString

data class AiReplyStyle(
    val headingFontSizes: List<TextUnit> = emptyList(),
    val codeBackground: Color = Color.Unspecified,
    val linkColor: Color = Color.Unspecified,
    val mutedColor: Color = Color.Unspecified,
)
```

`AiReplyStyle` 让渲染器保持**纯 Kotlin**：它不读 `MaterialTheme`，颜色与字号由
`AiScreen` 里的 `AiReplyText` 从 Compose 主题取出后传入，因此整个解析器可以在普通
JVM 单测里跑，不需要 `ComposeTestRule`、不需要模拟器。默认值全是「未指定」，
含义是「交给外层 `Text` 决定」。

## 三、为什么在 `:feature-ai` 里重新实现，而不是复用 `:feature-editor` 的 `MarkdownStyles`

这是本次唯一需要解释的架构选择。

1. **依赖方向不允许。** `tools/check-module-graph.sh` 的允许边里没有
   `feature-ai → feature-editor`（现有边只有 `feature-people → feature-editor`）。
   `MarkdownStyles` 是 `:feature-editor` 的 `internal` 实现细节，不是跨模块接口；
   跨模块直接摸另一个 feature 的内部实现，正是 R1 与档案 §3 明令禁止的做法。
2. **新增依赖边 = 改架构。** AGENTS.md §3：新增依赖边必须先登记档案 §3 并写 ADR。
   为了复用约 130 行解析器而把一个数据展示模块接到编辑器上，方向也是错的——
   AI 回复不关心编辑器的图片占位符、图床路径或点击回调。
3. **两者的输出目标不同。** `MarkdownStyles` 为**交互式预览**服务：它输出
   `AnnotatedString` 并附带 `meteor-target` 字符串注解，由 `ClickableText` 处理点击跳转，
   调用方逐行调用、自己拼 `Column`。AI 回复不需要跳转，需要的是一次性把**整段回复**
   （含标题、列表、代码块、分隔线）渲染成**一个** `AnnotatedString`。
4. **复用也需要搬运。** 即便只搬行内解析，仍要补齐行级结构；两边的取舍（例如
   AI 回复要不要显示 ` ``` ` 的语言标签、图片是否显示占位文案）也不同。

因此接受两份实现并存，并明确**将来若收敛，应上移到 `:core-*`**（例如新建
`core-markdown`，或并入现有 `core-*` 的窄接口），而不是让一个 feature 依赖另一个
feature。届时两边都改成调用同一份实现，本 ADR 与 ADR-021 的结论都不受影响。

## 四、为什么是只读渲染，而不是可编辑

- **AI 回复不可编辑。** 用户能编辑的是自己的提问（`ai_message_edit` 会把它放回输入框），
  回复只能重答或继续，没有编辑入口。可编辑意味着要做光标偏移映射、IME 组合态与
  增量重解析——ADR-021 已经用一次崩溃和一串光标跳位换来了「不做这件事」的结论。
- **没有写回格式。** 回复不落盘为可编辑正文，只作为加密的对话历史保存原文；
  渲染层不改变数据，回退到旧版本不会损坏任何内容。
- **不引入「隐藏字符」。** 只读渲染不需要 `OffsetMapping`，因此不存在
  编辑区那类「渲染文本比原文短」的 bug 类别（ADR-021 的核心教训）。

## 五、降级安全：任何看不懂的标记都原样显示

这是本次实现的硬约束，也是测试的主要目标。

1. **标记只有在完整时才被消费。** `**abc`、`*abc`、`` `abc ``、`[abc](https://e`
   全部原样输出；正则不匹配就等于没看见，而「没看见」的路径永远是
   `append(text)` 而不是丢弃。
2. **流式输出的半截文本不丢字。** `bubble.text` 在流式阶段是不断增长的半截字符串，
   会停在 `**` 中间、或 ``` 代码块未闭合。为此：
   - 代码块没等到闭合围栏时，**一直渲染到文本末尾**，不丢弃其后的任何一行；
   - 未闭合的围栏行本身不显示（它是标记，不是内容），但**后面每一行都照常显示**；
   - 行内解析按「先找最早匹配」推进，匹配不到就 `append` 剩余全文。
   单测 `no visible character is lost at any streamed prefix` 对一段包含全部
   标记的样例取**每一个前缀**，断言「原文中的 CJK 字符序列」与「渲染后可见的 CJK
   字符序列」完全相同——这是对「半截标记不丢字」最直接的暴力验证。
3. **不认识的结构不猜。** `__x__`、`~~y~~`、表格、任务列表、引用块都原样保留，
   不做半吊子解释。
4. **伪装的标记不当成标记。** `2 * 3 * 4`、`a * b * c` 里的星号不是斜体：
   强调类标记要求内容非空且首尾不是空白，否则整段保持字面。
5. **没有可见文字的引用保持字面。** `![](url)`、`[](url)` 若把标记吃掉就会得到空白，
   因此这类不识别为链接/图片，整段原样显示。

## 六、支持与不支持

**支持（行内）**：`**粗体**`、`*斜体*`、`***粗斜***`、`` `行内代码` ``（等宽 + 背景）、
`[文字](url)`（保留文字、下划线、主题色）、`<span style="color:#RRGGBB">…</span>`（上色）、
`![替代文字](url)`（只显示替代文字），且以上可任意嵌套。

**支持（行级）**：`# `~`### ` 标题（整行加粗并按正文尺寸放大，标题文字仍走行内解析）、
`- `/`* `/`+ ` 无序列表（渲染成 `• `，保留缩进）、`1. `/`1) ` 有序列表（保留编号）、
`---`/`***`/`___` 分隔线（渲染成一行固定长度的横线，因为 `Text` 内部无法画出
占满气泡宽度的 `HorizontalDivider`）、``` ``` ``` 围栏代码块（整块等宽、不解析块内标记）。

**明确不支持**：表格、任务列表 `- [ ]`、引用块 `>`、`~~删除线~~`、`__粗__`/`_斜_`、
图片的实际显示（回复气泡里没有图片资源可解）、链接点击跳转（只保留可见文字与下划线）、
代码块语法高亮、HTML 除颜色 `span` 外的任何标签。

## 七、R1 模块准入四问

1. **属于哪个模块？** 并入现有 `:feature-ai`，不新增模块。新增文件
   `AiReplyMarkdown.kt` 与对应单测。
2. **依赖谁？谁依赖它？是否单向？是否成环？** 无新增依赖边：只用
   `androidx.compose.ui.text` 与 `androidx.compose.ui.graphics`（Compose BOM 已在模块内），
   不依赖 `:feature-editor`。`:app` 通过既有 `AiScreen` 使用，方向不变，无环。
3. **对外接口是什么？是否足够窄？** 只有一个 `render` 函数与一个样式数据类（见上文），
   无状态、无副作用、不读主题、不持有资源。
4. **是否有数据格式变更？是否向后兼容？** 没有。渲染只发生在展示层，
   对话历史、加密存储与 `AiModule` 冻结接口（`chat` / `chatStreaming` /
   `AiChatListener`）均未改动。

## 八、后果与已知限制

- AI 回复不再显示 `*`、`#`、反引号等标记；模型输出结构越规整，观感收益越大。
- 渲染在 `remember(text, replyStyle)` 里按文本变化重算：一次回复一个 `AnnotatedString`，
  没有逐 token 的额外分配热点；但**每次流式增量都会整段重解析**，长回复（数千字）
  在最坏情况下有可感知的重算成本。当前按「气泡内容长度 + 128 个 token/s 级别」评估
  可接受，若将来成为问题，正确的做法是在流式期间退回纯文本、结束后再渲染一次。
- 两份解析器（本文件与 `feature-editor` 的 `MarkdownStyles`）行为不保证逐字一致：
  例如编辑器预览把图片换成 `[图片]` 占位，AI 回复则显示替代文字。这是有意的，
  但也是将来收敛到 `core-*` 时要统一下来的地方。
- 分隔线是固定长度的横线（约 18 个全角横线），在极窄或极宽的气泡里都不会
  正好撑满一行；这是 `Text` 内渲染的固有限制，可接受。
- **本节无法在本地编译验证**：本机 JDK 与 CI 不一致且 AGENTS.md 禁止本地构建，
  验收以 GitHub Actions 为准。
