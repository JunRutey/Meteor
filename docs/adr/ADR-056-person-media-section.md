# ADR-056 · 人物档案的媒体区（图片 / 视频 / 音频）

- 状态：已接受
- 日期：2026-11-21
- 关联：ADR-052（媒体存储与相册总入口，本流是其中 §5「E」）、ADR-014（person_id 目录）、
  ADR-020（人物图片存储与删除时机）、ADR-034（人物删除与详情页重做）、
  ADR-037（图片查看器删除语义）、ADR-049（编辑器图片块与描述行）、ADR-023（备注与正文分离）

## 背景

ADR-052 把图片、视频、音频统一成 `数字遗产/媒体/<owner>/` 下的一套加密存储，并新建
`feature-media` 承载播放器、缩略图与内置选择器。人物档案在 ADR-020 里就已经能把图片存进
`数字遗产/图片/<person_id>/`，但详情页只能**间接**看到它们：正文里的 `meteor://image/<token>`
渲染成图片块，没有引用的图片就完全不可见，视频和音频更是根本没有入口。

人物页因此是「相册总入口能看见、人物档案看不见」的半成品状态：给某个人存的照片，只有作者
还记得自己把它插进过正文时才找得回来。

本 ADR 决定人物档案如何拥有、展示、添加和删除媒体，以及**删除人物时媒体是否级联**。

## R1 四问

### 1. 属于哪个模块？

全部改动落在既有模块，不新增模块：

- 媒体区的展示组件（`PersonMediaSection`、`PersonMediaRow`、时长格式化）在既有的
  `:feature-people`；
- 人物详情/编辑两个 `ViewModel` 的映射、导入、删除在既有的 `:app`（`ui/people`）；
- 存储与相册聚合沿用 ADR-052 §3 的 `:core-storage`（本流不写一行存储代码）；
- 缩略图、内置选择器、导入管线沿用 ADR-052 §4 的 `:feature-media`（本流不改它）。

### 2. 依赖谁？谁依赖它？是否单向？是否成环？

只新增**一条**依赖边，已在 ADR-052 §1 登记、由 T2 流写入 `tools/check-module-graph.sh`：

```
feature-people → feature-media
```

理由：人物详情页的缩略图必须由 `feature-media` 的 `MediaThumb` 画。自己再写一份「解密 +
缩放 + 解码」会在两个模块里各有一份缓存与降级策略，且媒体格式细节（边车缩略图、EXIF 方向）
属于 `feature-media` 的内部知识。方向单向、无环：

```
app → feature-people → { feature-editor, feature-media } → core-storage
```

`feature-media` 不反向依赖 `feature-people`，`core-storage` 不依赖任何 feature。

### 3. 对外接口是什么？是否足够窄？

`feature-people` 只新增**一个内部组件**与**两个数据载体**，公开签名全部是增量（R2 允许）：

```kotlin
// :feature-people
data class PersonMediaRow(val ref: String, val kind: MediaKind, val title: String?, val durationMs: Long?)
data class PersonMediaImportSummary(val imported: Int, val failed: Int)
internal fun PersonMediaSection(personId, items, onOpenMedia, onAddMedia, onDeleteMedia, …)

// PersonDetailScreen 新增参数（全部带默认值，旧调用不变）
personId: String = "",
media: List<PersonMediaRow> = emptyList(),
onOpenMedia: (String) -> Unit = {},
onAddMedia: (String) -> Unit = {},
onDeleteMedia: (String) -> Unit = {},
mediaImporting: Boolean = false,
mediaImportSummary: PersonMediaImportSummary? = null,
mediaLoadFailed: Boolean = false,
mediaDeleteFailed: Boolean = false,

// PersonEditScreen 新增参数（全部带默认值，旧调用不变）
onPickMedia: ((Set<MediaKind>) -> Unit)? = null,
insertingMedia: Boolean = false,
```

界面不接触 `AlbumEntry` / `MediaStore`：它只拿到一个**不透明**的 `ref`
（`meteor://media/<32hex>` 或旧 `meteor://image/<token>`）。`ref` 怎么变成画面，由
`MediaThumb` 解析、由宿主决定路由，人物页不需要知道任何一种 scheme 的含义。

`app` 侧新增两个 ViewModel 方法，签名同样窄：

```kotlin
PersonDetailViewModel.importMedia(uris: List<Uri>)   // MediaImporter，personId 归属
PersonDetailViewModel.deleteMedia(ref: String)       // AlbumRepository.delete(ref)
PersonEditViewModel.insertMedia(uris: List<Uri>)     // 正文插入，personId 归属
```

### 4. 是否有数据格式变更？是否向后兼容？

**没有新格式。** 人物媒体复用 ADR-052 已经定义的 `数字遗产/媒体/<person_id>/`
（`<id>.data.enc` / `.meta.enc` / `.thumb.enc`），`owner` 就是 `person_id`，与 ADR-014
的「人物目录按 `person_id` 命名」一致。旧 `数字遗产/图片/<person_id>/` **原样保留、零迁移**：
`AlbumRepository.entries(MediaOwner.Person(id))` 把新旧两个目录合并成一个列表，旧图片按文件
mtime 兜底排序、没有缩略图时懒生成。

正文里新增的引用形态是 `![描述](meteor://media/<id>)`；旧 `meteor://image/<token>` 继续有效。
人物 `profile.md` 的 header、加密方式、备份格式都没有变化（媒体清单的备份由 ADR-052 §5「F」
负责）。因此旧存档可直接打开，旧版本读到含新引用的正文时只是不认识该行——与 ADR-049 引入
图片块时的兼容姿态相同。

## 决定

### 1. 详情页新增「媒体」区，位置在正文之后、相关记录之前

人物页本来就**整页一个 `LazyColumn`**（ADR-034 §4），所以媒体区是一个 `item`，位于「正文」
卡片与「相关记录」标题之间：先看这个人是谁、写过什么，再看他有哪些照片/视频/录音，最后是
导入的文件记录。这个顺序把「人」放在「资料」前面，与详情页现有的信息层级一致。

布局用**横向卡片流**（`LazyRow`，96dp 圆角格子）而不是 `LazyVerticalGrid`：详情页已经有一条
垂直滚动，再嵌一个垂直网格会为滚动权打架，且人物通常只有几十个媒体，横向流更能容纳「以后
还会更多」而不把页面拉得很长。格子沿用详情页的 14dp 圆角与 8dp 间距语言。

每个格子：

- 缩略图：`MediaThumb(ref)` 自己解析 ref、解密、缩放解码；视频有抽帧边车缩略图，音频显示
  中性占位图标，解密中显示进度圈，读不出来显示缺图图标——**任何一格坏了都不抛异常**；
- 左下角 `kind` 角标（图片 / 视频 / 音频），右下角时长角标（仅视频/音频，`m:ss` / `h:mm:ss`，
  纯数字与冒号，不需要翻译）；
- 空态「这个人还没有媒体」与加载失败各占一条状态行，错误不进格子。

### 2. 点击只看，不做第二个查看器

点格子的回调是 `onOpenMedia(ref: String)`：人物页**不实现查看器**，也不认识 `ref` 的类型，
由 `:app` 宿主导航到 ADR-052 §5「C」实现的 `media/{ref}` 统一路由（图片缩放/分享/删除、视频
全屏播放、音频播放卡都在那里）。理由：查看器已经存在，复制一份就是两处维护；而且人物页一旦
能打开查看器，就必须一并决定「从查看器返回后刷新什么」，把这条链路留在宿主更简单。

### 3. 添加媒体：宿主开 `MediaPickerScreen`，`personId` 归属在 VM 里定

「添加媒体」按钮的回调是 `onAddMedia(personId: String)`。宿主打开
`MediaPickerScreen(kinds = { IMAGE, VIDEO, AUDIO }, onConfirm = …, onBack = …)`，把
`PickedMedia.uri` 列表交给 `PersonDetailViewModel.importMedia`，VM 用
`MediaImporter(context.contentResolver, mediaStore).import(uri, personId)` 落盘。

归属**不在 UI、也不在 `feature-media` 里决定**：`MediaImporter.import` 的第二个参数就是
`personId`，传谁就是谁的。这与 ADR-052 §5「D」对日记（`_self`）与人物正文（该 `personId`）的
划分完全一致——同一份导入管线，归属由调用方给。

导入是同步阻塞的（解密 + 缩略图），整批在 `Dispatchers.IO` 上跑，期间按钮文案变「导入中…」
且禁用；完成后刷新媒体区并显示「导入成功 N 个，失败 M 个」。单个文件失败只记日志并计数，
不中断整批——用户一次选了 20 张，不能因为一张坏图全部作废。

### 4. 删除：长按 + 二次确认，只删密文，不改写正文

长按任一格子弹出确认框（与相册一致的手势语言；详情页没有多选态，所以不做批量删除）。
确认文案必须**明确说出**：文件被永久删除，引用过这个媒体的正文不会自动改写，那些地方会显示
「媒体无法显示」。这段话是 ADR-037 §3 的同一语义搬到人物页——用户看到的是「删掉一张照片」，
实际发生后果的是「某篇日记里的图变成破图」，不写清楚就是欺骗。

确认后 `PersonDetailViewModel.deleteMedia(ref)` → `AlbumRepository.delete(ref)`：
`meteor://media/<id>` 走 `MediaStore.delete`（payload + meta + thumb 三个文件），旧
`meteor://image/<token>` 走 `ImageStore.delete`。整个 `AlbumRepository.delete` 是同步的，放
`Dispatchers.IO`，失败保留原状态并显示「媒体删除失败」。

这里**不做**写时延迟删除：ADR-020 §3 的「保存成功后才删文件」是为了保护「删了图又放弃编辑」
的正文，而媒体区的删除是**独立动作，不依附任何一次保存**，不存在「放弃后正文还引用着」的
中间态，所以立即删、立即刷新。

### 5. 人物编辑正文插入媒体：编辑器只报 kinds，VM 决定归属

`PersonEditScreen` 把 `onPickMedia: ((Set<MediaKind>) -> Unit)?` 与 `insertingMedia` 透传给
`MarkdownField`（ADR-052 §5「D」的签名）。编辑器**只**回调用户点了哪一类媒体，不导入、不写
正文；`PersonEditViewModel.insertMedia(uris)` 用 `personId` 调 `MediaImporter`，再把返回的
`StoredMedia.id` 通过 `MarkdownEditing.appendMedia(notes, id)` 追加成
`![描述](meteor://media/<id>)`。

为什么归属必须在 VM：同一个 `MarkdownField` 同时服务日记（`_self`）和人物（`personId`），
编辑器根本没有 person 概念；把归属塞进编辑器会把「谁拥有这条记录」这一存储知识漏进 UI 模块。

`onPickMedia` 默认为 `null`：传 `null` 时工具栏保持旧的单个「图片」按钮（走
`onPickImage` / `ImageStore`），视频/音频入口不出现。这样既有调用点（包括日记页）在没接线前
行为完全不变，接线后自动多出视频/音频。

### 6. 删除人物**不级联**删除该人物的媒体

**决策：与现有图片行为完全一致——删除人物档案时，`数字遗产/媒体/<person_id>/` 与
`数字遗产/图片/<person_id>/` 都保留。**

核查现有行为（本 ADR 的依据）：

- `PersonQueries.delete(personId)` 只做 `记忆档案/人际关系/<person_id>/` 的
  `deleteRecursively()`，接口注释明确写「只删通讯录条目，日记与附件属于别的存储，不在这里碰」
  （ADR-034 §1）；
- `ImageStore.delete(token)` 只删 `数字遗产/图片/<owner>/` 下的一个密文文件，不级联改写任何
  Markdown（ADR-020 §2、ADR-037 §3）；
- ADR-034「已知限制」逐条列明：删除人物**不会**清理该人物名下的附件、图片与数字人格快照，
  它们仍在各自的管理入口可见。这是**有意的窄删除**——宁可留下可单独清理的产物，也不让一次
  确认静默删掉可能被其它记录引用的文件。

所以人物媒体沿用同一条规则，**不做任何级联**，理由有三：

1. **一致性**：媒体和图片是同一类东西（同一个 `AlbumRepository` 聚合），如果删人物删媒体却
   不删图片，用户无法预测；如果两者都删，就与 ADR-034 已确立的窄删除冲突，需要先改 ADR-034。
2. **安全**：媒体可以被正文引用（`meteor://media/<id>` 已经写进 `profile.md`、日记、AI 对话
   记录）。删人物时正文文件本身也被删掉了，但**日记**里指向同一个 id 的引用不会——级联删除
   会让一篇与该人物无关的日记突然破图。ADR-020 §3 宁可留垃圾也不碎图的取舍在这里同样成立。
3. **可恢复性**：留在相册总入口里的媒体仍然可播、可导出、可单独删除，用户没有「数据静默
   蒸发」的体验；真正想要清理的人可以在相册里按 owner 找到它们。

因此删除人物的确认文案**不需要**再加「媒体也会被删除」这句话（它不会）；反而如果有人后来
引入级联，必须同步修改那句文案和 ADR-034、ADR-020、ADR-037 三份档案。

## 后果与代价

**得到的：**

- 人物页终于能看见「给这个人存过的所有照片/视频/录音」，包括没被正文引用的；
- 新旧图片在同一列表里，用户不需要知道 `meteor://image/` 与 `meteor://media/` 的区别；
- 视频/音频在人物页可直接看/听，走的是与相册、日记完全同一条查看器路由；
- 零数据迁移，旧存档打开即用，旧版本读新正文最多不认识一行。

**付出的：**

- `feature-people` 多一条到 `:feature-media` 的依赖边（已在 ADR-052 §1 登记）；
- 人物页多了一次 `AlbumRepository.entries(...)` 的 IO 调用（每次 `load()` 随附件列表一起读，
  人物媒体量级远小于全库相册，可接受）；
- 删除人物会留下媒体文件（**有意**，见决定 6）。这些文件的清理入口在相册，不在人物页；
- 与 ADR-020 §3 的「延迟删除」不同，媒体区删除立即生效且不可撤销（有二次确认兜底）。引用它的
  正文会显示缺图占位——与 ADR-037 删除图片时的表现一致。

## 已知限制

- 人物页没有媒体筛选（全部 / 图片 / 视频 / 音频），筛选用相册总入口；人物媒体通常量小，不加
  chips 是为了不让详情页的头部变重。
- 没有批量删除：详情页没有多选态，长按只删一格。批量在相册里做。
- 时长角标只显示元数据里记录的时长；导入时读不到时长（少数容器）的文件不显示角标。
- 旧图片没有缩略图边车，第一次进人物页会在 IO 线程解码一次原图（已限幅 ≤512px），之后仍会
  再次解码——`MediaThumb` 的缓存策略属于 `:feature-media`，不在本 ADR 范围。
- 人物媒体区不显示「源数据日期」；排序沿用 `AlbumRepository.entries` 的 `importedAt` 降序
  （相册的排序开关只作用于相册总入口，不进人物页）。
- `profile.md` 正文里同时存在 `meteor://media/` 与 `meteor://image/` 引用时，渲染由
  `:feature-editor` 负责（ADR-052 §5「D」），本 ADR 只负责详情页的媒体区。

## 测试

- `:feature-people` 新增 `PersonMediaFormatTest`（JVM，不依赖 Android 运行时）：时长角标的
  边界——未知/非正数不出角标、亚分钟补零、分钟秒补零、过一小时出现小时段、1 毫秒截断为
  `0:00`。
- `MediaThumb` 的解密/降级、`MediaImporter` 的元数据抽取、`AlbumRepository` 的新旧合并与
  排序分别由 ADR-052 的 T2 / T1 流覆盖，本流不重复测试。
- 不在本机跑 gradle；验收以 GitHub Actions 为准（AGENTS §1、§2）。
