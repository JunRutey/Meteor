# ADR-065 · 删除「记录」页，导入文件并入相册

- 状态：已采纳（实施中）
- 日期：2026-11-21
- 关联：[ADR-054](ADR-054-album-hub-and-sort-toggle.md)（相册总入口）、
  [ADR-052](ADR-052-media-album-hub.md)（媒体与选择器）、
  [ADR-060](ADR-060-merged-bottom-quick-entries.md)（快捷入口合并）、
  [ADR-029](ADR-029-attachment-record-type.md)（附件记录类型，仍未落地）

## 一、问题

v1.5.0 按 ADR-054 把原「记录」页（`ChatsScreen`）原样塞进相册总入口的第二段。
上线后用户反馈：「相册里的记录页面到底有没有用？感觉没什么用。」

代码审计确认这不是错觉，而是四个叠加的设计缺陷：

1. **永久空页**：`AlbumHubScreen` 无条件渲染两个胶囊，不读任何附件状态，
   0 个文件时仍然显示「记录」段和空态文案。
2. **命名撞车**：「记录」在 App 里已经指日记 / 心情 / 相关记录，用户无法预期
   这里装的是「从电脑导入的文件」。
3. **点不开看不了**：列表行只有「导出 / 删除」，没有任何阅读入口；
   `readIndexedText` 的三个调用方全是给搜索索引和 AI 用的，没有一个给用户。
4. **搜到了点不进去**：附件命中在搜索页被标成「聊天」（ADR-029 未落地），
   且 `SearchScreen` 的 `onClick` 只处理日记与心情，其余 `else -> Unit`。

与此同时，这条链路的**数据层是有真实价值的**：附件正文会进入 AI 语料
（`AttachmentRecordScanner`）、AI 只读工具（`list_attachments` /
`read_attachment_text`）、内存搜索索引，并随明文备份导出。所以「删掉整条
功能」会损失真实能力，「原样保留」则继续损失用户信任。

## 二、决策

**删除「记录」页与它的两段式外壳，把它的四项能力分别并入已有页面。**

1. **相册成为唯一的文件入口**。`GalleryKind` 新增 `FILE`，`AlbumKind` 新增
   `FILE`，导入文件与图片 / 视频 / 音频进入**同一个按天网格**，用第五个筛选
   胶囊「文件」区分。网格顶部提供「导入文件」，导入进度与结果以横幅呈现。
2. **查看器支持阅读正文**。`MediaViewerScreen` 新增 `fileText`：文件条目打开
   后显示导入时抽取的纯文本；没有可抽取文本（PDF、图片、压缩包）时明确说明
   「这个文件没有可阅读的正文」，而不是显示空白。分享按钮在文件条目上改名为
   「导出」，走的仍是同一条解密到 `cacheDir` 再交给系统选择器的路径。
3. **搜索命中变成可点**。`SearchScreen` 新增 `onOpenAttachment`，附件命中
   跳 `media/meteor://file/<id>`；筛选胶囊新增「文件」。
4. **人物页收敛为一份列表**。原「相关记录」区里混排的附件卡片删除，
   导入文件改由 `AlbumRepository.entries(MediaOwner.Person(...))` 一并返回，
   与媒体共用「媒体」横向条；人物页的「导入文件」按钮保留，仍归该人物所有。

数据层完全保留：`AttachmentStore`、`AttachmentImporter`、
`AttachmentImportCoordinator`、`AttachmentTextIndex`、`AttachmentRecordScanner`、
备份路由、AI 工具全部不动。

## 三、R1 四问

1. **属于哪个模块？** 不新增模块。`core-storage` 扩 `AlbumKind` / `AlbumRepository`
   与 `MediaRef`；`feature-gallery` 扩网格、查看器与图标；`feature-people` 把
   `PersonMediaRow.kind` 换成 `AlbumKind`；`:app` 负责 `AlbumMapping`、导航、
   `GalleryViewModel` 的导入接线与 `MediaViewerViewModel` 的正文读取。
2. **依赖谁？谁依赖它？** **无新增模块边**，`tools/check-module-graph.sh` 的
   白名单不变。`feature-gallery` 仍不依赖 `:core-storage`（经 `:app` 的
   `AlbumMapping` 映射）；`feature-people` 已 `api` 依赖 `:core-storage`。
3. **对外接口是什么？** `AlbumRepository` 构造器新增**可选**参数
   `attachments: AttachmentStore? = null`（缺省即旧的纯媒体行为）；
   `AlbumEntry.kind` 类型由 `MediaKind` 改为新的 `AlbumKind`，
   `AlbumEntry` 新增默认值字段 `sizeBytes`；新增 `AlbumRepository.readFileText`；
   `MediaRef` 新增 `File` 变体与 `FILE_SCHEME`；
   `GalleryScreen` 新增导入相关可选参数与 `FileImportSummary`；
   `MediaViewerScreen` 新增 `fileText`。全部为新增或带默认值，无破坏性签名变更。
4. **数据格式变更？** **无**。附件仍是 v1 分块格式 + Keystore 主密钥 + 1 MiB +
   串行（ADR-062 / ADR-063 不变），目录仍是 `数字遗产/附件/<owner>/`；
   媒体格式不变；备份 zip 结构不变。唯一的新增是**引用语法**
   `meteor://file/<id>`，它只出现在内存中的 `AlbumEntry.ref` 与路由参数里，
   **不写入任何记录正文**，因此不影响旧版本读取。

## 四、为什么不是「并入相册成为媒体」而是「相册新增一种 kind」

附件与媒体是两套独立存储（目录、边车、密钥、分块大小、去重策略都不同，
见 ADR-063 §「`AttachmentStore` 保持 v1」）。把附件真的迁移进 `MediaStore`
需要新增第四种 `MediaKind`、给媒体加文本边车、处理两套密钥与格式版本，
并迁移或双读历史数据——代价远超收益。`AlbumKind` 是**读取侧的联合视图**，
只决定「网格里画什么、打开后走哪条读取路径」，两个存储各管各的。

## 五、代价与已知限制

- 删除了「按人物筛选全部附件」这个独立视图；人物页仍能看某个人的文件，
  但无法再「一次性浏览所有未归属文件并批量挑人」。这是有意取舍：
  归属在导入时决定，事后重新归类本来就没有实现。
- 网格里的文件块没有缩略图，只显示文件名与大小；`MediaThumb` 对
  `meteor://file/` 返回 `ThumbState.Document`（文件图标），不是「媒体缺失」。
- 搜索结果里附件仍显示为「聊天」标签，直到 ADR-029 落地。
- 相册的 `MAX_ITEMS = 120` 上限现在同时约束媒体与文件，导入很多小 txt
  的用户会比以前更早触及它。

## 六、删除清单

- 删除 `ui/chats/ChatsScreen.kt`（298 行）、`ui/chats/ChatsViewModel.kt`（171 行）
- 删除 `ui/home/AlbumHubScreen.kt`（125 行）
- 删除 `strings.xml` 的 `chats_*`（3 条）、`records_*`（22 条）、
  `album_hub_tab_*`（2 条）
- `ROUTE_CHATS` 保留为深链兼容，重定向到 `album`
