# ADR-054 · 相册总入口、媒体网格与排序开关

- 状态：已采纳（实施中）
- 日期：2026-11-21
- 关联：ADR-052（媒体相册总纲与契约）、ADR-022（相册按天分组、长按删除）、
  ADR-037（图片查看器）、ADR-049（编辑器图片块）、ADR-053（feature-media 选型）

## 背景

维护者要求把「图片总览」升级为**相册**，除图片外支持视频与音频，并把原「记录」
（导入文件记录）与「相册」合并为一个总入口。ADR-052 §5「C」把 UI 与导航侧的落点
分给了 feature-gallery、feature/settings 与 app 层。本文记录这一侧的架构决策。

## R1 四问

1. **属于哪个模块？** 不新增模块。网格 / 查看器 UI 在既有 `:feature-gallery`；
   排序偏好只给 `:feature-settings` 加一个新键；总入口与导航接线在 `:app`
   （`ui/home`、`ui/gallery`、`ui/chats`、`MeteorNavHost`）。
2. **依赖谁？谁依赖它？** 仅新增一条已登记的边：`feature-gallery → feature-media`
   （网格与查看器复用 `MediaThumb` / `VideoPlayerCard` / `AudioPlayerCard`）。
   `feature-gallery` 仍**不**依赖 `:core-storage`；`:app` 继续既依赖
   `:core-storage` 也依赖 `:feature-media`。方向单向、无环。
3. **对外接口是什么？** feature-gallery 暴露 `GalleryKind` / `GallerySortMode` /
   `GalleryFilter`（UI 侧枚举）、升级后的 `GalleryScreen`（新增 `sortMode` /
   `onSortModeChange` / `filter` / `onFilterChange` 参数，`GalleryItem` 由 token
   扩展为 `ref + kind + durationMs + fileName + hasThumbnail`）、新建
   `MediaViewerScreen`（`ref + kind` 分派）。app 层新增 `AlbumMapping`
   （`core-storage` 类型 ↔ UI 枚举的纯函数映射）、`MediaViewerViewModel`、
   `MediaShareFile`、`AlbumHubScreen`。其余为内部实现。
4. **数据格式变更？** 无存档格式变更。仅新增一个 DataStore 偏好键
   `album_sort_mode`（缺省即旧行为），旧 `gallery/{token}` 与 `gallery?date=`
   深链保留并内部重定向。

## 信息架构：相册做总入口，内部两段

原「图片」与「记录」是首页快捷区里两个并列入口，二者语义都是「媒体的存放地」——
一个是加密图片，一个是导入的加密文件记录。合并成一个「相册」入口后：

- **相册**段：媒体网格（图片 / 视频 / 音频，按天分组、多选删除）；
- **记录**段：原 `ChatsScreen` 内容原样迁入，`ChatsViewModel` 逻辑保留不动，
  仅新增 `showHeader` 参数隐藏其内部标题（标题改由总入口的分段顶栏承担）。

分段用顶部「相册 / 记录」两个等宽胶囊（`AlbumHubScreen`），不引入底部二级 Tab，
避免与首页底部 Tab 语义冲突。用户从原「图片」入口进到的是这个总入口；日历里的
「图片总览」与「记录」两个快捷 tile 也指向同一目的地。

## 排序开关：默认「按存入日期」

开关位于相册顶栏右侧（下拉菜单，手绘 `GalleryIconKind.Sort`），二选一：

- **按源数据排序**（`SOURCE_DATE`）：`sourceDate ?: importedAt` 降序；
- **按存入日期排序**（`IMPORT_DATE`）：`importedAt` 降序，即现有行为。

默认 `IMPORT_DATE`，理由：**零惊吓**——升级后打开相册看到的顺序与旧版完全一致；
想按拍摄时间看的人才主动切换。排序语义的**唯一实现**在 `core-storage`
`AlbumRepository.sorted()`（ADR-052 §3），UI 只负责选值和传值。

持久化：`:feature-settings` 新增 `SettingsRepository` 键 `album_sort_mode`
（字符串，取 `SOURCE_DATE` / `IMPORT_DATE`），缺省 `IMPORT_DATE`；未知值回退默认。
`AppSettings` 暴露 `albumSortMode: String`，`applyBackupSnapshot` 一并覆盖，恢复
后仍校验取值。

`feature-gallery` 内定义 `GallerySortMode` 枚举，app 层 `AlbumMapping` 做它与
`core-storage.AlbumSortMode` 的双向映射，保证 feature-gallery 不依赖 core-storage。

## 查看器路由兼容

- 新统一路由 `media/{ref}`，`ref` 为完整 URL（`meteor://media/<id>` 或旧
  `meteor://image/<token>`），经 `Uri.encode` 传输；`MediaViewerViewModel` 用
  `Uri.decode` 还原。
- 图片：沿用 ADR-037 的全屏 `ContentScale.Fit` 居中 + 分享 + 删除（二次确认、不
  改写引用）。视频：`VideoPlayerCard` 全屏播放；音频：`AudioPlayerCard` + 文件名。
- 旧深链 `gallery/{token}` 与 `gallery?date=`（日历入口）保留为独立 composable，
  内部 `LaunchedEffect` 重定向到 `media/{ref}` / `album?date=`，`popUpTo(ROUTE_CALENDAR)`
  避免回退栈堆积。
- 分享导出沿用 `ImageShareFile` 模式，扩展为 `MediaShareFile`：图片按旧文件头推断
  或条目 mimeType，视频/音频用条目 mimeType，解密到 `cacheDir/share` 再经
  FileProvider 分享；临时文件同样 24 小时主动清理。

## 删除语义

沿用 ADR-037：相册多选删除与查看器单条删除都先二次确认，文案微调为「媒体」，仍只
删密文文件、不级联改写 Markdown 引用（引用处显示「媒体无法显示」）。

## 测试

- `feature-settings` 单测新增：`album_sort_mode` 默认值、往返持久化、非法值拒绝。
- app 层 `AlbumMapping`（纯函数）后续补 JVM 单测，覆盖 `MediaKind`↔`GalleryKind`、
  `AlbumSortMode`↔`GallerySortMode`、`GalleryFilter`→`Set<MediaKind>` 与按天分组。

## 已知限制

- 视频/音频的缩略图与播放依赖队友的 `feature-media`（ADR-052 §4）；音频卡片只在
  网格显示图标 + 文件名，无内嵌缩略图。
- 排序开关改变的是列表顺序，不改变按天分组结构（仍按 `importedAt` 的日期分组）。
- 旧图片无 `sourceDate`，`SOURCE_DATE` 下按 `importedAt`（文件 mtime）兜底，与
  ADR-052 §3 语义一致。
