# ADR-052 · 媒体存储与相册总入口（总纲与接口契约）

> 状态：已采纳（实施中）。本文件同时是各实施流的**接口契约**，任何实现流不得擅自改动本文
> 公开签名；确需改动先在团队任务里说明并由主会话更新本文。
>
> 关联：ADR-014（person_id 目录）、ADR-020（图片存储）、ADR-029（attachment 记录类型）、
> ADR-037（图片查看器）、ADR-040（备份覆盖）、ADR-049（编辑器图片块）。

## 0. 背景与目标

维护者要求（本次授权）：

1. 「图片总览」更名**相册**，除图片外支持**视频**；编辑器同步支持。
2. **音频**可存储与播放，编辑器同样支持。
3. **人际关系**条目支持图片 / 视频 / 音频的显示与播放。
4. **记录页与相册合并**为一个总入口，内部做功能细分。
5. 启用**应用内置文件选择器**，保留源文件元数据（如 EXIF 拍摄时间）；相册排序提供
   **「按源数据排序 / 按存入日期排序」**开关。
6. 实现中积极使用开源项目（如 FFmpeg），**视情况而定、按需**。
7. **本次明确授权绕过 R2 冻结接口限制**；R1（模块单向无环）与 AGENTS 其余约束仍然有效。
   实现上仍以「不改旧签名、只做增量」为首选。

## 1. R1 四问（总）

1. **属于哪个模块？** 数据层并入 `core-storage`（新增类型，不动旧签名）；新建共享媒体 UI 模块
   `feature-media`（播放器、缩略图、内置选择器、导入管线）；相册/总入口在 `feature-gallery` +
   `app`；编辑器并入 `feature-editor`；人物媒体并入 `feature-people`；备份覆盖并入 `core-backup`。
2. **依赖谁？谁依赖它？** 新边（全部单向、无环）：
   `feature-media → core-storage`；`app → feature-media`；`feature-gallery → feature-media`；
   `feature-editor → feature-media`；`feature-people → feature-media`。
   需同步登记 `tools/check-module-graph.sh` 的 `ALLOWED_EDGES` 与 README 模块树（档案 §3 由
   维护者另行同步）。
3. **对外接口是什么？** 见 §3–§5 的精确签名；其余均为模块内部实现。
4. **数据格式变更？** 只新增 `数字遗产/媒体/` 目录（新数据），旧 `数字遗产/图片/`、日记、
   人物档案、备份格式**不变**；旧图片继续可读可显。备份格式新增媒体清单（可选字段，旧备份
   仍可导入）。

## 2. 总体设计决策

- **不引入 FFmpeg**。播放用 AndroidX **Media3 / ExoPlayer**（Apache-2.0，Android 官方开源
  播放器），EXIF 用 **androidx.exifinterface**，视频/音频元数据与缩略帧用平台
  `MediaMetadataRetriever`。理由：ffmpeg-kit 已于 2025 年归档停止维护、每 ABI 体积增量约
  30–100MB，而本场景（播放、缩略图、读元数据）平台与 Media3 已完整覆盖。「按需」结论是
  当前不需要；如未来需要转码/滤镜再单独立 ADR 引入。
- **新媒体统一加密落盘**，目录布局仿照 `AttachmentStore` 的分块加密 + 元数据边车：
  `数字遗产/媒体/<owner>/<id>.data.enc`（分块密文）、`<id>.meta.enc`（加密元数据）、
  `<id>.thumb.enc`（可选加密 PNG 缩略图）。`owner` 为 `_self` 或 person_id。
- **旧图片零迁移**：`数字遗产/图片/**` 与 `meteor://image/<token>` 原样兼容，进相册时按
  文件 mtime 兜底排序、无缩略图时懒生成。
- **Markdown 引用**：新媒体统一 `![alt](meteor://media/<32hex id>)`，渲染端按存储的 kind
  决定图片 / 视频 / 音频块；旧 `meteor://image/...` 照旧。

## 3. core-storage 新增接口（契约，实施流 A）

包名 `com.meteor.memory.core.storage`，全部为**新增**类型（R2 授权下也保持不动旧签名）。

```kotlin
enum class MediaKind { IMAGE, VIDEO, AUDIO }

data class MediaMetaInput(
    val kind: MediaKind,
    val fileName: String,          // 原始文件名（展示用，加密存放）
    val mimeType: String,
    val personId: String?,         // null = 自己（_self）
    val sourceDate: Instant?,      // 源数据日期（EXIF DateTimeOriginal / MediaStore DATE_TAKEN 等）；null = 未知
    val durationMs: Long? = null,  // 视频/音频
    val width: Int? = null,
    val height: Int? = null,
)

data class StoredMedia(
    val id: String,                // 32 位小写 hex
    val kind: MediaKind,
    val fileName: String,
    val mimeType: String,
    val personId: String?,
    val sizeBytes: Long,
    val importedAt: Instant,       // 存入日期
    val sourceDate: Instant?,
    val durationMs: Long?,
    val width: Int?,
    val height: Int?,
    val hasThumbnail: Boolean,
)

interface MediaStore {
    fun save(input: InputStream, meta: MediaMetaInput, thumbnailPng: ByteArray? = null): StoredMedia
    fun find(id: String): StoredMedia?
    fun list(personId: String? = null): List<StoredMedia>   // 按 importedAt 降序；null = 全部 owner
    fun load(id: String): ByteArray?
    fun copyDecrypted(id: String, output: OutputStream): Long
    fun loadThumbnail(id: String): ByteArray?
    fun delete(id: String): Boolean

    companion object {
        const val MEDIA_SCHEME = "meteor://media/"
        fun markdownFor(id: String, alt: String = ""): String = "![$alt]($MEDIA_SCHEME$id)"
    }
}
```

引用解析（新旧两种 scheme 统一入口）：

```kotlin
sealed interface MediaRef {
    data class Media(val id: String) : MediaRef
    data class LegacyImage(val token: String) : MediaRef   // 旧 ImageStore token（路径形）
    companion object {
        fun parse(urlOrToken: String): MediaRef?           // "meteor://media/<id>" | "meteor://image/<token>" | 旧裸 token
        fun markdown(id: String, alt: String = ""): String
    }
}
```

相册聚合（新旧合并 + 排序）：

```kotlin
enum class AlbumSortMode { SOURCE_DATE, IMPORT_DATE }

sealed interface MediaOwner {
    data object All : MediaOwner
    data object Self : MediaOwner
    data class Person(val id: String) : MediaOwner
}

data class AlbumEntry(
    val ref: String,               // 完整 markdown URL（meteor://media/<id> 或 meteor://image/<token>）
    val kind: MediaKind,           // 旧图片 = IMAGE
    val personId: String?,         // null = 自己
    val fileName: String?,         // 旧图片为 null
    val mimeType: String?,
    val sourceDate: Instant?,      // 旧图片为 null
    val importedAt: Instant,       // 旧图片取文件 mtime
    val width: Int?, val height: Int?, val durationMs: Long?,
    val hasThumbnail: Boolean,
)

class AlbumRepository(media: MediaStore, images: ImageStore) {
    fun entries(owner: MediaOwner = MediaOwner.All, kinds: Set<MediaKind>? = null): List<AlbumEntry>
    fun sorted(entries: List<AlbumEntry>, mode: AlbumSortMode): List<AlbumEntry>
    fun entry(ref: String): AlbumEntry?
    fun loadBytes(ref: String): ByteArray?                 // 旧图片走 ImageStore.load
    fun copyDecrypted(ref: String, output: OutputStream): Long?
    fun delete(ref: String): Boolean
}
```

排序语义（唯一实现处）：
- `SOURCE_DATE`：`sourceDate ?: importedAt` 降序（旧图片用 mtime 兜底）。
- `IMPORT_DATE`：`importedAt` 降序（即现有行为）。

## 4. feature-media 新模块（契约，实施流 B）

目录 `feature/media`，模块名 `:feature-media`。依赖：`:core-storage`、Compose、
Media3（exoplayer + ui）、androidx.exifinterface、协程、lifecycle。**禁止 Hilt**（Hilt 只在 :app）。
app/src/main/AndroidManifest.xml 由本流独占修改（仅加媒体读取权限）。

```kotlin
package com.meteor.memory.feature.media

@Composable fun MediaThumb(ref: String, modifier: Modifier = Modifier, contentDescription: String? = null)

@Composable fun VideoPlayerCard(ref: String, modifier: Modifier = Modifier)   // 封面 + 内联播放（ExoPlayer）

@Composable fun AudioPlayerCard(ref: String, modifier: Modifier = Modifier, title: String? = null)

@Composable fun MediaPickerScreen(
    kinds: Set<MediaKind>,
    onConfirm: (List<PickedMedia>) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
)

data class PickedMedia(
    val uri: android.net.Uri,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val kind: MediaKind,
    val sourceDate: java.time.Instant?,
)

class MediaImporter(
    private val contentResolver: android.content.ContentResolver,
    private val mediaStore: MediaStore,
) {
    /** 抽取元数据 + 缩略图并落盘；调用方负责在 Dispatchers.IO 执行。 */
    fun import(uri: android.net.Uri, personId: String?): StoredMedia?
}
```

要点：
- 播放大文件必须 `copyDecrypted` 到 cacheDir 临时文件喂 ExoPlayer，禁止整读进内存播放；
  `load(id)` 仅用于小文件（图片、缩略图）。临时文件用完删除。
- `MediaImporter.import`：按 MIME 定 kind；图片用 ExifInterface 读
  `DateTimeOriginal`→`DateTime`；视频/音频用 `MediaMetadataRetriever` 读
  `METADATA_KEY_DATE`/时长/宽高；视频抽帧、图片缩放（≤512px）生成 PNG 缩略图；全部写入
  `MediaMetaInput`。EXIF 方向需在缩略图生成时纠正。
- `MediaPickerScreen`：应用内选择器（MediaStore 查询设备图片/视频/音频，按月分组、多选），
  需申请 `READ_MEDIA_IMAGES/VIDEO/AUDIO`（API 33+）或 `READ_EXTERNAL_STORAGE`（API ≤32），
  权限被拒时展示可点击的说明与降级提示（文案走 strings.xml）。
- 代码零硬编码中文；所有文案进 `feature/media/src/main/res/values/strings.xml`。

## 5. 各 UI 流契约

### C · 相册 + 总入口（feature-gallery、feature/settings、app/ui/{gallery,chats,home,RootViewModel,MeteorNavHost}、app strings.xml）

- `home_open_gallery` 等文案「图片」→「相册」；`feature-gallery` 标题/提示同步更名。
- 相册网格合并展示图片/视频/音频：kind 角标、视频时长角标、音频显示音频卡；
  筛选 chips「全部 / 图片 / 视频 / 音频」。
- 排序开关（二选一，DataStore 持久化，默认 `IMPORT_DATE` 保持现状）：
  「按源数据排序 / 按存入日期排序」。开关放相册顶栏；枚举与映射自定（feature-gallery 内定义
  UI 侧枚举即可，**不得**给 feature-gallery 增加 core-storage 依赖；它只依赖 :feature-media）。
- 总入口：原「记录」（ChatsScreen，导入文件记录）与「相册」合并为一个入口「相册」，
  内部细分两个段：**相册**（媒体网格）与**记录**（现有 ChatsScreen 内容）。
- 查看器泛化：图片全屏（保留现有缩放/分享/删除）、视频全屏播放、音频播放卡；
  路由统一 `media/{ref}`，同时兼容旧 `gallery/{token}` 深链与 `gallery?date=`（日历入口）。
- 删除语义沿用 ADR-037（二次确认；引用处不自动改写）。

### D · 编辑器（feature-editor、app/ui/diary）

- Markdown 块渲染：`meteor://media/<id>` 图片内联显示（沿用 ADR-049 的图片块与描述行）、
  视频渲染 `VideoPlayerCard`、音频渲染 `AudioPlayerCard`；旧 `meteor://image/` 不变。
- 插入入口：工具栏「插入」支持图片 / 视频 / 音频 → 回调 `onPickMedia(kinds)`（由 app 导航
  打开 MediaPickerScreen）→ 导入后写入 `![描述](meteor://media/<id>)`。
- 日记/人物正文插入的媒体归属：日记 = `_self`；人物正文 = 该 personId（host 传入）。
- 大文件插入需后台执行并有进行中文案（沿用 `diary_adding_image` 模式扩展）。

### E · 人物媒体（feature/people、app/ui/people）

- 人物详情页新增「媒体」区：该人物的图片/视频/音频网格（`AlbumRepository.entries(Person(id))`），
  点击进查看器/播放（复用 C 的 `media/{ref}` 路由，回调 `onOpenMedia(ref)`）；
  「添加媒体」按钮 → MediaPickerScreen（personId 归属）。
- 人物编辑正文插入媒体同 D（personId 归属）。

### F · 备份（core-backup）

- 明文 zip 导出新增：新媒体的解密原文 + `media-manifest.json`（id、kind、fileName、mimeType、
  personId、sourceDate、durationMs、width、height），保持旧 zip 结构不破坏、旧备份可导入。
- 导入恢复时按清单重建 `MediaStore` 条目（**必须恢复 sourceDate**，否则排序丢失）。
  旧格式备份导入行为不变。

## 6. 测试要求

- A：MediaStore 保存/读取/删除/分块大文件往返、元数据编解码、AlbumRepository 新旧合并与
  两种排序、MediaRef.parse 正反例（JVM 单测，参考 StorageLayoutTest/AttachmentStore 既有测试风格）。
- B：MediaImporter 元数据解析纯逻辑单测（可测部分）、kind 判定。
- C：排序映射与 VM 状态单测（参考现有 GalleryViewModel 测试风格，若有）。
- F：manifest 编解码往返单测、清单缺失时的旧备份兼容。
- 所有测试不依赖 Android 运行时（CI 只跑 JVM 单测）。

## 7. 工程纪律（所有实施流）

- **禁止**执行 gradle / gradlew 任何命令（本机不构建，验收以 GitHub Actions 为准）。
- **禁止**执行任何 git 写操作（add/commit/push/checkout 等）；提交由主会话统一做。
- 代码**零硬编码中文**，全部走本模块 `res/values/strings.xml`（values/ 即中文）。
- 版本号只进 `gradle/libs.versions.toml`（仅 B 流允许改）。
- 只改自己任务的写范围（见团队任务板），其它文件确需改动时在报告里列出并说明。
- 完成后报告：改动文件清单、公开 API 与契约的偏差、新增测试、遗留风险。
