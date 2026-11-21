# ADR-048 · 工具栏恢复加粗与彩色字体：只插入语法，不隐藏标记

- 状态：已接受
- 日期：2026-11-21
- 影响：`:feature-editor`（新增 `MarkdownInlineFormat`，改动 `EditorToolbar`）
- 关联：ADR-021（编辑区退回纯文本）、ADR-019（颜色与斜体下线）、
  ADR-018（不做所见即所得）
- 关系：**不推翻 ADR-021 的任何结论**，只是走它结尾写明的正确路径

## 一、背景

v0.4 之后编辑区是纯文本（ADR-021）：工具栏只剩「链接」「图片」，日记页还把
「预览」挪到了页头胶囊里。加粗与彩色字体当年被下线（ADR-019、ADR-021），
但用户的诉求一直没消失——「尝试实现以前放弃的粗体，多彩字体等」。

关键是分清当年被砍掉的到底是**哪一层**：

| 被砍的 | 没被砍的 |
| --- | --- |
| 编辑期**隐藏**标记（`VisualTransformation` + `OffsetMapping`） | 预览解析器 `MarkdownStyles` |
| 由隐藏标记导致的**光标映射**（崩溃、跳位） | 数据格式 `**…**` 与 `<span style="color:#RRGGBB">…</span>` |

`MarkdownStyles` 至今仍然渲染 `**粗**`、`*斜*`、
`<span style="color:#E53935">红</span>`（`MarkdownStylesTest` 仍在测这些分支）。
也就是说：**预览早就支持，缺的只是一个把语法写进正文的入口。**

ADR-021 结尾的原话是：

> 工具栏现在只有 3 个动作，未来加任何格式（标题、列表）都应该是**插入语法**，
> 而不是隐藏语法。

ADR-019 结尾也给了同一方向：

> 若将来重新需要颜色，正确的做法是**在预览里支持**、不在编辑区支持，
> 这样不引入新的光标映射分支。

本文就是把这两句落地。

## 二、决策

### 2.1 只插入语法，绝不隐藏标记

工具栏新增两个动作：

- `B`（加粗）：选中则包裹成 `**选中内容**`；空选区则插入 `****` 并把光标放在
  两对星号中间，接着打字就是粗体。
- 颜色：点开一排色点，选色后把选区包裹成
  `<span style="color:#RRGGBB">选中内容</span>`；空选区插入空标记并把光标放进中间。

**编辑区照旧逐字显示 `**` 和 `<span …>`**，没有任何 `VisualTransformation`、
没有偏移映射、没有隐藏字符。这条是硬红线：ADR-021 已经论证过，光标映射
正是当年崩溃和跳位的唯一来源，只要还隐藏一个字符，就得永远维护那套映射。

### 2.2 逻辑放在纯 Kotlin 里

新增 `MarkdownInlineFormat`（`feature-editor`，无 Android / Compose 依赖）：

```kotlin
data class InlineEdit(val text: String, val selectionStart: Int, val selectionEnd: Int)

object MarkdownInlineFormat {
    fun toggleBold(text: String, selectionStart: Int, selectionEnd: Int): InlineEdit
    fun applyColor(text: String, selectionStart: Int, selectionEnd: Int, hex: String): InlineEdit
    val COLORS: List<String>
}
```

它做的事就是「算出新文本 + 新选区」，由 `MarkdownField` 写回
`TextFieldValue`。这样：

- 逻辑可以在 JVM 上单测（`MarkdownInlineFormatTest`），不需要仪器测试；
- 选区越界/反向先 `coerceIn` 再处理，**不抛异常**——IME 与 Compose 在重组
  瞬间给出的偏移偶有越界，崩溃代价远高于「这次点击没生效」；
- 再点一次能取消：`**…**` 外层正好包围选区，或选区自身包含一对完整的
  `**…**`，都按「去标记」处理。

`applyColor` 额外保证**不嵌套**：预览的 `COLOR` 正则
（`<span style="color:(#[0-9A-Fa-f]{6})">(.*?)</span>`）不递归，嵌套的
`<span><span>` 会把内层标签当正文显示出来。因此按下面四条处理，优先级从上到下：

1. 选区正好是某段 span 的**全部内容**：同色再点＝**取消**（去掉标记），异色＝
   **改写那段 span 的 `open` 标签**；
2. 选区本身就**是一个完整的 span**（手工框选出来的）：同上，同色去标记、异色换色；
3. 选区落在一个 span **内部**：只改外层那一段的颜色，绝不套第二层；
4. 选区里**已经含有 span 标签**（跨边界选区，套一层必然产生嵌套）：**原样返回**，
   什么也不改。

这套规则由 `MarkdownInlineFormatTest` 逐条锁定。

### 2.3 工具栏布局：色点走第二行

日记页调用 `MarkdownField(showPreviewAction = false)`，工具栏只有一行，且
原注释明确要求**不能横向滚动**（所有动作都要在屏幕内）。四个动作
（链接、图片、加粗、颜色）× 44dp = 176dp 加上 4×4dp 内边距和 2dp 间隔，
在 360dp 宽度上仍放得下；色点如果塞进同一行就会挤出去，所以做成**点击颜色按钮后
在同一张卡片内展开第二行**的横向色条：5 个 44dp 触控区（26dp 圆点）× 5 = 220dp，
同样在一行之内，随时收起，永不横向滚动。

预览态下加粗/颜色与链接/图片一样 `disable`；进入预览时色板自动收起
（`LaunchedEffect(isPreviewing)`），避免留下点不动的色点。

### 2.4 样式与无障碍

- 图标沿用现有 `Canvas` 手绘风格（0–24 单位坐标系、1.8dp 描边、`secondary` 着色），
  **不引入 material-icons 依赖**；
- 每个动作都有 `contentDescription`，色点的读屏文案带 hex（`颜色 %1$s`）；
- 文案全部在新文件 `values/strings_format.xml` 里，前缀 `editor_fmt_`，
  代码零硬编码中文（AGENTS §8）。

## 三、数据格式与兼容

**没有引入任何新格式**，写进去的就是预览本来就能解析的两种标记：

- 加粗：`**文字**`
- 颜色：`<span style="color:#RRGGBB">文字</span>`，`#RRGGBB` 为 6 位十六进制
  （`MarkdownStyles.COLOR` 只认 6 位，超出这个形状的输入会被
  `MarkdownInlineFormat` 直接拒绝，绝不落盘）
- 斜体 `*文字*`：**只做数据侧支持**（预览已能渲染），不加按钮——中文没有
  真正的斜体字形，系统倾斜合成观感差（ADR-019 的结论仍然成立）

旧记录**不迁移**：

- 旧日记里的 `**…**`、`<span …>`、`*…*` 本来就由 `MarkdownStyles` 渲染，
  观感不变；
- 编辑区照旧按原文显示，用户随时可以手工删改；
- 备份文件、导出、索引、未来的 AI 读取全都只看 Markdown 原文，
  与改动前完全一致。

这也意味着**降级安全**：把带颜色的记录拿到旧版本打开，预览照样是彩色的。

## 四、已知限制

1. **编辑区能看到标记**（`**你好**`）。这是本 ADR 的刻意选择，不是缺陷：
   能看见才删得掉。代价是写长文时正文里有一段语法噪音。
2. **颜色只能选固定几色**（`MarkdownInlineFormat.COLORS`，当前 5 个）。
   没有取色器：`<span>` 已经是 Markdown 之外的噪音，再给无限色只会放大它。
   需要更多色时改这个列表即可，不涉及语法变化。
3. **中文斜体**：不加按钮，原因见上；老记录里的 `*…*` 仍会渲染成倾斜合成字。
4. **不含 undo**。点错了要自己删掉标记，应用层没有单独的格式撤销栈。
5. **嵌套限制**：`applyColor` 不给已含着色区间的选区再套一层（预览不支持嵌套），
   此时点击相当于改写外层颜色或不做改动，具体行为由单测锁定。
6. **加粗与颜色可以叠加**：`**<span style="color:#E53935">重点</span>**` 能同时
   渲染粗体与红色（`MarkdownStyles` 递归解析），但嵌套层数越多，正文越难读。
7. **未在本地编译/测试**（AGENTS §1：本机 JDK 与 CI 不一致，禁止运行 Gradle）。
   逻辑以 JVM 单测覆盖，Compose 部分只做了逐行静态检查，验收以 CI 为准。

## 五、R1 模块准入四问

1. **模块归属**：全部在既有 `:feature-editor`；不新增模块。
2. **依赖方向**：没有新增依赖边。`MarkdownInlineFormat` 是纯 Kotlin，
   连 `:core-storage` 都不依赖；`EditorToolbar` 只是多两个回调参数。
3. **对外接口**：`EditorToolbar` 的新参数**全部带默认值**
   （`onBold: () -> Unit = {}`、`onColor: (String) -> Unit = { }`、
   `colors: List<String> = MarkdownInlineFormat.COLORS`），既存调用方
   （`MarkdownField` 的两处宿主页面）无需改动；
   `EditorModule` 的 `FROZEN INTERFACE` 签名一个字符都没动，本轮也没有新增
   接口方法——插入逻辑在 UI 层算，不经过 `EditorModule`。
4. **数据格式**：无变更。写入的是 ADR-019 时代就存在的两种标记，
   向后（老记录）与向前（旧版本 app 打开新记录）都兼容。

## 六、验证

- `MarkdownInlineFormatTest` 覆盖：空选区插入、选区包裹、再次点击取消、
  越界与反向选区、空文本、颜色包裹、重复颜色不嵌套、异色改写、非法 hex 拒绝、
  调色板只含 6 位十六进制、加粗+颜色叠加。
- 未修改 `MarkdownEditing.kt` / `MarkdownStyles.kt` / `MarkdownField.kt`，
  既有 `MarkdownEditingTest`、`MarkdownStylesTest` 行为不变。
- 本机禁止构建，编译、lint 与单测的执行结果以 GitHub Actions 为唯一验收标准
  （AGENTS §2）。
