# ADR-064 · 媒体：预览载入修复、音频名称、横屏全屏与内容哈希去重

> 状态：已采纳。关联：[ADR-052](ADR-052-media-album-hub.md)（媒体总纲）、
> [ADR-055](ADR-055-editor-video-audio-blocks.md)（编辑器视频/音频块）、
> [ADR-058](ADR-058-large-media-key-cache-and-streaming-playback.md)（流式播放）、
> [ADR-061](ADR-061-media-import-placeholder.md)（导入占位）、
> [ADR-062](ADR-062-media-chunk-size-10mb.md) / [ADR-063](ADR-063-media-secondary-key.md)（分块格式与媒体密钥）。

本 ADR 记录同一轮「媒体体验」改动的四项内容：日记预览页媒体一直显示「正在载入」的修复、
音频卡显示音频名称、视频横向全屏，以及按内容哈希做全局去重。

## 一、R1 四问

1. **属于哪个模块？** 全部并入现有模块，不新增模块：
   - `core-storage`：`MediaMetaInput` / `StoredMedia` 增加内容哈希字段，`MediaStore` 增加
     `findByHash`，`EncryptedMediaStore` 的元数据 sidecar 升到 v2；
   - `feature-media`：导入前的 SHA-256 计算与查重、视频全屏 UI；
   - `feature-editor`：`MediaBlockState.Audio` 携带音频标题；
   - `:app` UI：预览页 resolver 接线、音频标题来源、人物详情正文的媒体解析；
   - `:app` 清单：`MainActivity` 声明 `configChanges`（原因见 §四，是写范围之外唯一改动）。
2. **依赖谁？谁依赖它？** **没有新增模块依赖边**。`:feature-media → :core-storage`、
   `:feature-editor → :feature-media`、`:app → 两者` 都是既有单向边，仍然无环。
   `AndroidManifest.xml` 的 `configChanges` 不引入任何依赖，只改变配置变化时的生命周期行为。
3. **对外接口是什么？** 只做加法，且新增项全部带默认值，符合 R2「只能新增带默认实现的方法」：
   - `MediaMetaInput.contentHash: String? = null`
   - `StoredMedia.contentHash: String? = null`
   - `MediaStore.findByHash(hash: String): StoredMedia? = null`（默认实现返回 null）
   - `MediaBlockState.Audio(title: String?)`（由 `data object` 变为 `data class`）
   `FileCrypto`、`StorageRouter`、`EditorModule` 等 `FROZEN INTERFACE` **一个字符未动**。
   对 [ADR-052](ADR-052-media-album-hub.md) §3 的 `MediaStore` 契约是**加法扩展**（字段位于末尾
   且带默认值、方法带默认实现），既有字段顺序与语义不变；按 ADR-052 的约定，契约文档的同步由
   主会话负责。
4. **是否有数据格式变更？** 有，但只涉及**媒体元数据 sidecar 的版本号**：v1 → v2，v2 追加
   一个可空的 `contentHash` 字符串。读取端同时接受 v1 与 v2，旧媒体照常可读、可播放、可备份、
   可删除。媒体 payload 的分块格式（v1/v2/v3）、Markdown 引用、备份 zip 与
   `media-manifest.json` 全部不变。

## 二、预览页媒体一直「正在载入」：根因与修复

### 现象

日记预览页里每条 `![描述](meteor://media/<id>)` 都停在「正在载入」中性框，
音频/视频卡不出现，图片也不显示。

### 根因

`MeteorNavHost` 调用 `DiaryPreviewScreen(...)` 时**没有传 `resolveMedia`**。该参数默认是
`{ MediaBlockState.Pending }`，而 `MarkdownPreview` 把 `Pending` 画成中性的「正在载入」框，
所以无论数据是否已经读完，每张卡都永远是 Pending。

数据侧其实已经就绪：`DiaryPreviewViewModel.refreshMedia()` 会通过
`decodeMediaStates(mediaStore, ids)` 把 kind（图片的 bitmap 也一并解码）写进
`DiaryPreviewState.mediaBlocks`，并暴露同步查询 `mediaState(id)`。缺的只是把这条查询接到
渲染树上。

### 修复

`MeteorNavHost` 的 `ROUTE_DIARY_VIEW` 补上：

```kotlin
DiaryPreviewScreen(
    ...
    resolveMedia = viewModel::mediaState,
)
```

同一路由的 `resolveImage` **不需要改**：`DiaryPreviewScreen` 内部用的是
`resolveImage = { token -> state.images[token] }`，而 `state.images` 已由
`DiaryPreviewViewModel.refreshImages()` 解码填充。

**MediaHost 覆盖范围核对**：`:feature-media` 的播放器从 `LocalMediaHost` 取
`MediaStore`；`MeteorRoot` 已经在整个 `NavHost` 外层包了
`ProvideMediaHost(viewModel.mediaHost) { ... }`，所以预览页、编辑页、人物页都在覆盖范围内，
无需额外包裹。

**同一类问题的连带修复**：`PersonDetailViewModel` 早就有 `loadMediaBlocks(notes)`，但
`load()` 只调用了 `loadNoteImages(notes)`，导致人物详情页正文里的媒体块也永远停在 Pending
（resolver 传了，但 map 从未填充）。本次在 `load()` 中补调用 `loadMediaBlocks(...)`。

## 三、音频卡显示音频名称

`MarkdownMediaBlock.StoredMediaSurface` 的 Audio 分支此前调用
`AudioPlayerCard(ref, modifier)`，没有标题，只能显示默认「音频」。

- `MediaBlockState.Audio` 改为 `data class Audio(val title: String?)`；
- 标题来源是 `StoredMedia.fileName`，即**导入时的原始文件名**（如 `voice memo.m4a`），
  由加密元数据 sidecar 提供；
- **为什么不用 Markdown caption（描述）**：caption 是用户可写、可为空、可被改写的 alt text，
  它不保证存在；而「这段录音是什么」在卡片上必须有答案。文件名是相册与统一查看器里已经在用的
  稳定标识，选择它让同一份媒体在不同页面说同一个名字。caption 为空时卡片仍显示文件名，
  caption 非空时也不冲突（编辑器在卡片下方单独显示描述行）；
- `AudioPlayerCard` 已有 `title: String?` 参数，空值时保持现有 `media_audio_title`（「音频」）
  默认文案，不新增兜底逻辑；
- 编辑页与预览页共用同一个 `StoredMediaSurface`，改一处两边同时生效；
- 构造点同步更新：`DiaryMedia.decodeMediaStates` 与 `PersonMediaBlocks.decodeMediaBlocks`
  在拿到 `StoredMedia` 后传 `stored.fileName`；`MediaBlockState.of(...)` 增加可选
  `audioTitle` 参数，没有文件名时传 null。

## 四、视频横向全屏

### 交互

- 播放器的右上角、与现有「停止播放」按钮同一角落，并排增加一个「全屏」按钮；
  按钮是自绘 `MediaGlyph`（`Fullscreen` / `FullscreenExit`），不引入图标依赖；
- 点击后 `(Activity).requestedOrientation = SCREEN_ORIENTATION_LANDSCAPE`，并用
  `Dialog(properties = DialogProperties(usePlatformDefaultWidth = false))` 承载全屏播放器，
  黑色背景铺满窗口；
- 全屏对话框内左上角提供「退出全屏」按钮：关闭 Dialog 并把
  `requestedOrientation` 恢复为 `SCREEN_ORIENTATION_UNSPECIFIED`；
- 全屏按钮/退出按钮的文案进 `feature/media/src/main/res/values/strings.xml`
  （`media_video_fullscreen` / `media_video_exit_fullscreen`），零硬编码中文。

### 同一个 ExoPlayer 在两个 PlayerView 之间切换

Media3 不允许一个 `ExoPlayer` 同时绑定两个 `PlayerView` 的视频 surface，否则会抛
`IllegalStateException`。因此：

- 进入全屏：先在按钮回调里 `inlineView?.player = null`，再置 `fullscreen = true`；
  内联 `AndroidView` 在全屏期间**离开组合**，其 `onRelease` 也会解绑；
- 退出全屏：先 `fullscreenView?.player = null`，再置 `fullscreen = false`；内联视图重新组合时
  在 `AndroidView.update` 里重新挂上同一个 player。

### 方向恢复

方向恢复放在 `DisposableEffect(fullscreen)` 的 `onDispose` 中，覆盖所有退出路径：

- 点「退出全屏」；
- 系统返回手势/返回键关闭 Dialog；
- 播放被停止或生命周期降到 STARTED 以下（此时 player 被释放，`LaunchedEffect(player)` 会
  主动关闭 Dialog）；
- 用户在全屏状态下直接离开该页面（组合被销毁）。

只有 `fullscreen == true` 的 effect 才会把方向复位，正常页面不会无谓地改写
`requestedOrientation`。

> **勘误（v1.5.1）**：上面这条实现有致命缺陷，已修复。`onDispose` 里写的是
> `if (fullscreen) { …复位… }`，但那个 lambda 捕获的是 `MutableState` 本身，`onDispose`
> 执行时 `fullscreen` 早已被写成 `false`，守卫恒假 —— **方向从来没有被复位过**，退出全屏后
> 整个 Activity 被永久锁在横屏。除「直接离开页面」外，ADR 声称覆盖的每条路径实际都失效。
> 修法：在 effect **创建时**把进入前的方向存成普通局部值，`onDispose` 里无条件写回，
> 不再读 `fullscreen`。
>
> 同批修正：
> - `SCREEN_ORIENTATION_LANDSCAPE` → `SCREEN_ORIENTATION_SENSOR_LANDSCAPE`。前者把方向钉死
>   在其中一个横向档位，用户反向握持时画面倒置且无法纠正；
> - 复位值改为「进入前的 `requestedOrientation`」而非硬写 `UNSPECIFIED`，避免踩掉调用方
>   本来就锁定的方向；
> - `DialogProperties` 补 `decorFitsSystemWindows = false`。Activity 是 edge-to-edge
>   （ADR-024），但 Dialog 是独立窗口，默认 fit system windows，黑色播放器只铺满安全区，
>   状态栏与横屏侧边导航栏位置露出浅色纸面 —— 这是「横屏后页面显示有问题」的直接来源；
> - 退出全屏按钮补 `WindowInsets.safeDrawing`，尺寸 36dp → 48dp（无障碍最小触控目标）。
>   横屏时挖孔与手势条在侧边，原来的 36dp 按钮常落在系统手势热区里，用户表现为「点不动」；
> - `MediaViewerScreen` 里 `VideoPlayerCard` 的修饰符由 `fillMaxSize()` 改为
>   `fillMaxWidth()`。卡片自带 16:9 `aspectRatio`，调用方同时钉死宽高时 `aspectRatio`
>   解析不出目标尺寸，卡片被拉成查看器区域的形状、视频在里面加黑边 —— 同一张卡在编辑器里
>   （`fillMaxWidth`）显示正常，正是这条差异的证据；
> - 补 `Player.Listener`：`STATE_READY` 才收起「正在准备」、`STATE_ENDED` 回卷并复位、
>   `onPlayerError` 置失败态。此前 `prepare()` 之后立即清 `preparing`，大文件会有黑屏空窗；
>   而解码失败时卡片只是一块没有文案的黑框，`media_video_failed` 永远不可达；
> - 补音频焦点与 `setHandleAudioBecomingNoisy(true)`：来电/其它 App 抢占音频时暂停，
>   拔耳机不再把私密录音切到外放。


### 为什么必须改 `MainActivity` 的 `configChanges`

`AndroidManifest.xml` 原来没有声明 `configChanges`。`requestedOrientation` 改成横屏会让系统
**重建 Activity**：组合被销毁 → `ExoPlayer` 随 `remember` 一起释放 → 全屏播放立即中断，
而新 Activity 里的 `fullscreen` 状态已丢失，方向还可能停留/回弹，属于明显 bug。

因此给 `MainActivity` 增加：

```xml
android:configChanges="orientation|screenSize|keyboardHidden"
```

让 Activity 自己处理旋转，Compose 按配置变化重组（`LocalConfiguration`、资源、主题随配置更新），
播放器实例与 Dialog 都得以保留。这是本次**写范围之外唯一改动**，在此显式记录。
影响面：旋转不再重建任何页面（对所有页面生效，Compose 本就支持）；需人工回归旋转场景
（含 IME 与 edge-to-edge 布局）。

## 五、按内容哈希去重

### 语义

- 哈希 = 源文件字节的 SHA-256，64 个十六进制字符（小写）；
- **全局去重**：`MediaImporter.import` 先算源 URI 的哈希并查 `MediaStore.findByHash`；命中就
  **直接返回已有 `StoredMedia`**，不再 probe、不生成缩略图、不再读第二遍、不再加密写入；
- 哈希本身随元数据写进**加密的** `.meta.enc` sidecar，不会以明文留在磁盘或列表页面上；
  它只是一个用于比对的摘要，不泄露文件内容；
- 命中条目保持它**首次导入时的 owner（`_self` / person_id）、目录位置、文件名、日期**；调用方
  只是把已有的 `meteor://media/<id>` 写进正文。因此同一文件同时出现在日记与人物正文时，
  磁盘上只有一份密文；
- 由「人物媒体」区按 owner 过滤带来一个可见结果：如果某文件第一次是在日记里导入的（`_self`），
  之后再从人物正文插入同一文件，人物详情页的「媒体」区**可能不显示**这一条，但正文引用按 id
  查找、正常渲染与播放。人物详情页的「添加媒体」按钮同理：去重命中后不会在本人媒体网格里新增
  一格（文件仍归属首次导入处），但导入本身成功、引用可用。这是去重的既定语义，不视为 bug
  （如需按引用展示，应另开 ADR 改媒体归属模型）。
- 删除语义不变（ADR-037）：删掉首次归属的条目后，其它正文引用转为缺失占位，不会自动改写正文。

### 成本

- 命中：多读一遍源文件（流式 SHA-256，64 KiB 缓冲，不整读进内存），但省掉 probe、缩略图生成与
  整份加密写入——对大视频是净赚；
- 未命中：图片会读两遍（哈希 + EXIF/缩略图），音视频是「流式哈希 + 原有 probe/流式加密」，
  源文件总计被读两遍，内存峰值不增加；
- 哈希失败（provider 不可读、流异常等）→ 不查重，继续正常导入，`contentHash = null`；
- 单纯改名、重新编码、EXIF 被改写都会改变字节，因此不会命中——去重只按**内容字节**，
  这是刻意的确定性语义。

### 接口与实现

- `MediaMetaInput.contentHash: String? = null`（R2：新增带默认值字段）；
- `StoredMedia.contentHash: String? = null`（列表/查重共用；不进入相册投影展示）；
- `MediaStore.findByHash(hash): StoredMedia? = null`：默认返回 null，不能枚举元数据的实现自动
  退化为「不去重」，既有实现零改动；
- `EncryptedMediaStore.findByHash` 复用 `list()` 的遍历逻辑（`list()` 已跳过损坏 sidecar），
  返回第一条 `contentHash == hash` 的条目；
- 哈希计算抽成**纯函数** `MediaImportLogic.sha256Hex(InputStream)`（JVM 可测，不依赖 Android），
  `MediaImporter` 只负责打开/关闭源流。

### 元数据 v2 与兼容性

`.meta.enc` sidecar 的头部格式是 `magic | version`，版本从 1 升到 2：

- v2 在 `hasThumbnail` 之后、`chunkCount` 之前追加一个 nullable string `contentHash`；
- 读取端接受 `1..2`：v1 没有该字段，解码为 `null`，媒体继续可读、可播放、可备份、可删除，
  只是不参与去重；
- 写端一律写 v2；
- payload 的分块版本（v1/v2/v3，见 ADR-062/063）与 sidecar 版本互相独立，互不影响。

### 备份与换机的已知限制

`media-manifest.json` 本次**不新增** `contentHash` 字段（`:core-backup` 不在本任务写范围）。
因此从明文备份恢复出来的媒体 `contentHash` 为 null，不参与去重：换机后再次导入同一文件会多存
一份。将来扩展备份清单时（需升 manifest 版本）可补上该字段，让去重跨设备也能生效。

## 六、测试

- `core-storage`（`EncryptedMediaStoreTest`）：
  - 带 hash 保存后 `findByHash` 能找到；同一 hash 的第二条也能被找到；另一 hash 返回 null；
    **新实例从磁盘重读**后依然成立（不依赖内存缓存）；
  - 手工构造 v1 sidecar（magic + version 1 + 旧字段序列）后：`find`/`list` 正常、
    `contentHash` 为 null、`findByHash` 不命中；
  - 既有全套回归（往返、分块、并行、v1/v2/v3 payload、restore、非法 id 等）保持通过。
- `feature-media`（`MediaImportLogicTest`）：`sha256Hex` 的 NIST 标准向量（`abc`、空输入），
  以及 200 KB 数据跨 64 KiB 读缓冲时与一次性 digest 一致。
- 预览接线、全屏、去重是 UI/ContentResolver 行为，JVM 单测覆盖不到，验收依赖 CI 编译 +
  人工自测清单：
  1. 日记录入图片/视频/音频各一条 → 预览页三张卡正常显示（不再是「正在载入」）；
  2. 音频卡显示原始文件名；描述为空时也是文件名；
  3. 视频右上角全屏 → 横屏播放；退出、返回键、离开页面后方向均恢复，不把别的页面锁在横屏；
     **反向握持**时画面不倒置（`SENSOR_LANDSCAPE`）；
  4. 全屏黑色铺满整屏（状态栏与横屏侧边导航栏位置也是黑的），左上角退出按钮可点中；
  5. 同一文件先插入日记、再插入人物正文：存储目录只有一条媒体；人物「媒体」区可能不显示
     （首次归属 `_self`），但人物正文里正常播放；
  6. 重复导入同一文件（同一来源）不再产生第二份密文；
  7. 播放到结尾后卡片回到封面（可再点播放）；把文件改坏后播放显示「视频无法播放」而不是黑框；
     播放中来电或拔耳机时暂停。

## 七、后果与风险

- 修复了预览页（以及人物详情正文）媒体卡永久 Pending 的 bug；音频卡有了可辨识的名称。
- 视频获得横屏全屏；v1.5.1 修正了方向永不复位的问题（见 §四 勘误），现在任何退出路径都会
  把方向还给进入前的值。
- 去重让「同一文件多板块引用」不再复制密文，代价是导入时多一次流式读取；旧媒体零迁移。
- `configChanges` 是对全 App 生效的生命周期行为变更，需人工回归旋转场景；这是为了让全屏播放
  在方向切换时不销毁 ExoPlayer 的必要条件，已在 ADR 与任务报告中显式标注。
- 备份恢复后的媒体不带 hash（manifest 未扩展），换机后会重新去重失败、多存一份——已知限制，
  留待备份格式下一次版本升级。
- **仍存在的限制**：`configChanges` 只声明了 `orientation|screenSize|keyboardHidden`。深色模式
  切换、字体大小、语言、折叠屏姿态等未声明的配置变化仍会重建 Activity，此时全屏播放器会被
  中断回内联封面（状态全是 `remember`，没有 `rememberSaveable`）。全屏播放器本身仍是 Dialog
  这一独立窗口，`FLAG_SECURE` 依赖 `SecureFlagPolicy.Inherit` 继承，不要改成 `Exclude`。
