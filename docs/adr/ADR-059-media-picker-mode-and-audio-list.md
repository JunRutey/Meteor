# ADR-059 · 媒体选择器：系统 / 内置双模式、独立页面与音频列表

> 状态：已采纳。关联：[ADR-052](ADR-052-media-album-hub.md)（媒体导入与选择器）、
> [ADR-054](ADR-054-album-hub-and-sort-toggle.md)（相册与排序）、
> [ADR-058](ADR-058-large-media-key-cache-and-streaming-playback.md)（大文件性能）。

## 一、背景

ADR-052 落地后，媒体插入实际上有两条互不一致的路径：

- **日记编辑器**：把内置选择器 `MediaPickerScreen` **叠加**在编辑界面之上，
  它是编辑页内部的一个全屏 `Box`，不是独立页面。
- **人物详情 / 人物编辑**：走系统文件选择器（`OpenMultipleDocuments`），
  与日记的交互完全不同。

另外，内置选择器把音频也画成缩略图格子。音频没有可辨认的封面，用户只能靠
文件名区分录音，而格子里根本不显示文件名。

维护者要求：

1. 两套选择器都保留，在设置里让用户二选一；
2. 内置选择器必须使用**独立页面**，不叠加在编辑界面上；
3. 内置选择器的音频条目像播放列表一样**显示名称**。

## 二、决策

### 1. 设置项「媒体 / 文件选择器」

- 新增偏好 `AppSettings.mediaPickerMode: MediaPickerMode`，取值 `IN_APP` / `SYSTEM`，
  DataStore 键 `media_picker_mode`，**默认 `IN_APP`**。
- 默认内置的理由：内置选择器会读源文件元数据（EXIF 拍摄时间、MediaStore 时长），
  而相册「按源数据排序」（ADR-054）依赖这些数据；系统选择器拿不到同样的保证。
- 设置页新增「媒体」分组，两个分段按钮「内置 / 系统」，并附一行说明。
- 该偏好随加密设置快照一起备份/恢复（与 `albumSortMode` 同一条路径），
  旧快照没有该字段时回退默认值。

### 2. 内置选择器改为独立导航目的地

新增路由 `media-picker?person={personId}&kinds={image,video,audio}`：

- 全屏渲染 `MediaPickerScreen`，**不再叠加**在编辑页上；编辑页保持自身状态，
  从选择器返回后继续编辑。
- 选择器页面自己完成「选择 + 导入」：用 `MediaImporter` 把文件加密写入媒体库，
  导入期间仍显示进行中状态。导入归属由 `person` 参数决定（缺省 = `_self`）。
- 结果通过**上一个返回栈条目的 `savedStateHandle`** 回传：键 `media_picker_result`，
  值为 `ArrayList<String>` 的媒体 id，然后 `popBackStack()`。
- 宿主（日记编辑、人物编辑、人物详情）读取该结果：
  - 日记：把 id 追加为 `![...](meteor://media/<id>)`；
  - 人物编辑：同样追加到正文；
  - 人物详情：只需刷新媒体区（文件已经由选择器页面导入）。

选「系统」模式时，宿主改用平台 `OpenMultipleDocuments`（`image/*`、`video/*`、
`audio/*`），拿到 URI 后走各自的导入路径（日记 `importMediaUris`、人物编辑
`insertMedia`、人物详情 `importMedia`）。

### 3. 音频条目改为列表行

`PickedMedia` 新增 `durationMs`（选择器从 MediaStore 的 `duration` 列读取，
仅 Video/Audio 集合有该列）。内置选择器的网格中：

- 图片 / 视频仍为缩略图格子；
- 音频改为**跨整行的列表行**：音频图标 + 文件名（`_display_name`）+ 时长 + 文件大小，
  多选与选中态与格子一致。

这样录音类文件终于能按名称辨认，而不是一堆相同的音符图标。

## 三、R1 四问

1. **模块**：偏好并入 `:feature-settings`；选择器 UI 与音频列表并入 `:feature-media`；
   独立页面与宿主分流在 `:app` 的导航层（`MeteorNavHost`、`ui/media`）。不新增模块。
2. **依赖**：无新增模块边。`:app → :feature-media` 与 `:feature-media → :core-storage`
   均为 ADR-052 已登记的边。
3. **接口**：`MediaPickerScreen` 签名不变（仍由调用方处理确认/取消）；
   `PickedMedia` 新增带默认值的 `durationMs`；`AppSettings` 新增带默认值的字段。
   旧调用点无需改动即可编译。
4. **数据格式**：档案与媒体格式不变。DataStore 新增一个键，缺省即 `IN_APP`，
   向后兼容；加密设置快照新增一个可选字段，旧快照导入时自动回退默认。

## 四、后果

- 两个入口（日记 / 人物）行为一致，都由同一个设置决定用哪种选择器。
- 内置选择器不再覆盖编辑界面，编辑页的输入状态与光标不会因为打开选择器而丢失。
- 选择器页面承担导入，编辑页只接收媒体 id，职责更单一；导入失败的文件被跳过并记日志。
- 音频在相册和选择器里都以名称为主，符合录音类文件的实际使用方式。
