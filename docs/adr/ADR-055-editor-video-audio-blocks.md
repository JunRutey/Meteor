# ADR-055 · 编辑器插入与渲染视频 / 音频

> 状态：已采纳。关联：[ADR-052](ADR-052-media-album-hub.md)（总纲）、
> [ADR-049](ADR-049-editor-renders-image-blocks-and-captions.md)（图片块与描述）、
> [ADR-018](ADR-018-drop-wysiwyg-rich-text.md)（Markdown 即真相源）。

## 一、R1 四问

1. **属于哪个模块？** 并入现有 `:feature-editor`（渲染与工具栏），不新增模块。
   插入的**落盘**不在编辑器里：`:app` 的 `DiaryEditViewModel` / `PersonEditViewModel`
   调 `:feature-media` 的 `MediaImporter`，编辑器只发请求、收文本。
2. **依赖谁？谁依赖它？** 新增单向边 `feature-editor → feature-media`
   （已在 `tools/check-module-graph.sh` 的 `ALLOWED_EDGES` 登记）。
   `feature-people → feature-editor` 既有边不变，因此人物正文天然复用同一套渲染。
3. **对外接口是什么？** 只加**带默认值**的可选参数，旧调用点零改动：
   `onPickMedia: ((Set<MediaKind>) -> Unit)? = null`、`resolveMedia: (String) -> MediaBlockState`、
   `onRemoveMedia`、`onMediaCaptionChanged`。`MediaBlockState` 是编辑器自有类型。
4. **数据格式变更？** 无。正文仍是 Markdown，只是新增一种图片语法（见下）；
   旧 `meteor://image/` 与纯文本不受影响，旧版本客户端读新正文时会把媒体引用当作普通图片链接。

## 二、语法决策

| 引用 | 含义 | 渲染 |
|---|---|---|
| `![描述](meteor://image/<token>)` | 旧图片（`ImageStore`） | 与 ADR-049 完全一致，**不变** |
| `![描述](meteor://media/<32hex>)` | 新图片 / 视频 / 音频 | 按存储里的 kind 决定图片块、视频卡或音频卡 |

沿用 Markdown 图片语法而不是新造 `meteor://` 块语法，理由：

- 正文仍是合法 Markdown，任何 Markdown 阅读器都能看到「描述 + 链接」而不是乱码；
- 旧客户端的图片渲染路径已经认识 `![alt](url)`，不会把媒体引用变成不可读文本；
- 描述位置复用 ADR-049 的图片描述行，用户不必学两套编辑方式。

**kind 不写进 URL**：`meteor://media/<id>` 只有 id，kind 由 `MediaStore` 的加密元数据决定。
这样 URL 不泄露「这是一段录音」这类信息，且不会出现「URL 说图片、文件其实是视频」的不一致。

## 三、插入链路（回调模式）

```
EditorToolbar「插入 → 图片/视频/音频」
  → onPickMedia(kinds)                    // 编辑器只声明想要什么
  → 宿主打开应用内选择器（:feature-media MediaPickerScreen）
  → 宿主用 MediaImporter 导入（归属与元数据在宿主侧决定）
  → MarkdownEditing.appendMedia(body, id) // 追加 ![](meteor://media/<id>)
```

- **归属由宿主决定**：日记 `personId = null`（`_self`），人物正文 `personId = <id>`。
  编辑器不知道 `person_id`，也不该知道。
- **导入异步、批量、失败跳过**：一个坏文件不会丢掉同批选中的其它文件；导入期间
  工具栏显示进行中文案并禁止重复触发。
- 选择器不占导航目的地：日记与人物编辑页各自在自身 `Box` 内渲染选择器，
  关闭选择器不会留下孤立的返回栈条目。

## 四、后果

- 编辑器新增对 `:feature-media` 的依赖；`:feature-media` 不反向依赖编辑器，无环。
- 未接入 `onPickMedia`（保持默认 `null`）的宿主，工具栏不出现媒体插入项——
  旧调用点行为完全不变。
- 媒体删除不会改写正文引用，缺失引用渲染为占位（与 ADR-037 的图片语义一致）。
