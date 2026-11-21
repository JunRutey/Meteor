# ADR-053 · 新媒体模块 `:feature-media` 与播放栈选型

- 状态：已接受（v1.4.0）
- 影响：新建模块 `:feature-media`；`:app`、`:feature-gallery`、`:feature-editor`、
  `:feature-people` 各新增一条对它的依赖边；`app/src/main/AndroidManifest.xml` 新增媒体读取权限
- 关联：ADR-052（媒体存储与相册总入口，**接口契约与总纲**）、ADR-020（图片存储）、
  ADR-037（图片查看器分享与删除）、ADR-049（编辑器图片块）
- 关系：本文只解释**为什么这样实现**；签名一律以 ADR-052 §4 为准，本文不重复也不改动契约。

## 一、R1 模块准入四问

1. **属于哪个模块？**
   播放器、缩略图、内置选择器、导入管线这四件事，会被**相册、编辑器、人物档案三处同时需要**。
   放进任何现有 `feature-*` 都会让另外两处去摸它的内部实现，因此单独建共享模块
   `:feature-media`（目录 `feature/media`，模块名 `:feature-media`）。
   数据层不在本模块：`MediaStore` / `AlbumRepository` / `MediaRef` 在 `:core-storage`（ADR-052 §3）。

2. **依赖谁？谁依赖它？**
   本模块只依赖 `:core-storage`（+ Compose、Media3、ExifInterface、协程、lifecycle）。
   依赖它的：`:app`、`:feature-gallery`、`:feature-editor`、`:feature-people`。
   五条边全部单向；`feature-*` 之间**没有**横向依赖，因为谁都不依赖别人，只共同依赖本模块。
   新增边全部登记在 `tools/check-module-graph.sh` 的 `ALLOWED_EDGES`，CI 第一个 job 强制。

3. **对外接口是什么？是否足够窄？**
   ADR-052 §4 的六个签名，别无其它公开类型：`MediaThumb`、`VideoPlayerCard`、
   `AudioPlayerCard`、`MediaPickerScreen`、`PickedMedia`、`MediaImporter`。
   内部实现（MIME 判定、EXIF 解析、位图缩放、临时文件、图标）一律 `internal`。

4. **数据格式变更？是否向后兼容？**
   本模块**不定义任何落盘格式**，写入全部经 `:core-storage` 的 `MediaStore`（ADR-052 §3）。
   旧 `数字遗产/图片/**` 与 `meteor://image/<token>` 无需迁移即可显示：
   `MediaThumb` 对无缩略图边车的旧图片**懒解码原图**并按 ≤512px 缩放，Markdown 一个字都不改。

## 二、播放栈：Media3 / ExoPlayer

**选型**：`androidx.media3:media3-exoplayer` + `androidx.media3:media3-ui`，版本集中登记在
`gradle/libs.versions.toml` 的 `media3 = "1.5.x"`，模块里不写版本号（AGENTS §5）。

理由：

- **Apache-2.0，Android 官方维护**，与项目现有 AndroidX 依赖同源，许可证清单不新增风险面。
- 一套 API 同时覆盖视频与音频（`ExoPlayer`），`VideoPlayerCard` / `AudioPlayerCard` 共用
  同一套「解密到临时文件 → 播放」路径，只有 UI 形态不同。
- `media3-ui` 的 `PlayerView` 自带进度/控制器，音频卡需要的进度条与时长我用
  `Player.currentPosition` / `duration` 自己绘制，不引入 `media3-session`（后台播放、通知栏
  媒体控制**不在本次范围**，需要时再单独引入并写 ADR）。
- 备选 `VideoView` / `MediaPlayer`：不是编译器保证的生命周期安全，且没有统一的
  音频+视频 API；`MediaPlayer` 的状态机是长期 errored 高发区。

**大文件纪律（硬约束）**：播放前一律 `MediaStore.copyDecrypted(id, output)` **流式**解密到
`cacheDir/media-playback/` 的临时文件，再交给 ExoPlayer 以 `file://` 播放；
**禁止** `load(id)` 整读进内存播放。临时文件在播放器释放时删除，另外
`MediaPlaybackFiles.prune` 清理上次进程崩溃遗留的陈旧文件。`load(id)` 只用于图片与缩略图。

**生命周期**：`DisposableEffect` + `LifecycleEventObserver`，`ON_STOP` 停止播放；
离开组合时 `player.release()` 并删除临时文件。前台恢复不会自动续播（避免用户没注意到
就从外放开始响）。

## 三、按需评估后**不引入 FFmpeg**

维护者授权「积极使用开源项目（如 FFmpeg），视情况而定、按需」。评估结论是
**当前不引入**，理由按权重排列：

1. **上游已停维护。** `ffmpeg-kit`（arthenica/ffmpeg-kit）已于 2025 年归档，
   二进制包从 Maven Central 撤下，官方明确不再提供支持。引入一个没有维护者的
   原生库，等于把 ABI 兼容、NDK 升级、CVE 响应全部变成自己的长期负担；
   本项目是单人维护的加密个人档案，没有为此兜底的人力。
2. **体积增量与收益不成比例。** 即使只带 `min-gpl` 最小构建，每 ABI 仍约 30–100MB；
   `:app` 的 release 走 R8 + `shrinkResources`，原生 so 不受代码裁剪影响，
   APK 会直接膨胀数倍。而本次需求的全部收益是「播放、缩略图、读元数据」三件事。
3. **本场景已被平台与 Media3 完整覆盖。**
   - 播放：ExoPlayer（MP4/M4A/MP3/AAC/OGG/FLAC/WebM/MKV 等主流容器与编码）；
   - 缩略图：视频抽帧用平台 `MediaMetadataRetriever.getFrameAtTime`，
     图片缩放用 `BitmapFactory` + `Matrix`（EXIF 方向用 `androidx.exifinterface` 纠正）；
   - 元数据：`MediaMetadataRetriever` 提供 `METADATA_KEY_DATE` / 时长 / 宽高 / 旋转，
     `ExifInterface` 提供 `DateTimeOriginal` / `OffsetTimeOriginal` 等。
   没有一项需要转码、滤镜、重封装或协议扩展。
4. **加密档案的输入是「用户自己设备上的既有文件」。** 不需要从任意流式源转码；
   设备上装不进 ExoPlayer 的格式，Android 系统相册本身也放不出来，属于边缘场景。

**重新评估的触发条件**（满足任一即另写 ADR 引入原生多媒体栈）：
需要转码（统一码率/容器以便分享）、需要裁剪/滤镜/水印、需要读取平台解码器不支持的
音轨（如部分 DTS/AC3 影片）、或需要把音频导出为特定编码。届时优先考虑仍在维护的
`ffmpeg-kit` 继任者或 `Media3 Transformer`（后者的转码能力在 1.5 起已可用）。

## 四、EXIF 与元数据抽取

- 图片：`androidx.exifinterface`。`DateTimeOriginal`（其次 `DateTimeDigitized`、`DateTime`）
  解析成 `Instant` 作为 `sourceDate`；`OffsetTimeOriginal`（`±HH:mm`）缺失时按设备时区解释。
  **缩略图生成时应用 EXIF 方向**（`ORIENTATION_ROTATE_90/180/270`、三种 flip），
  否则竖拍照片在网格里是躺着的。
- 视频/音频：`MediaMetadataRetriever`。`METADATA_KEY_DATE` 形如 `20100214T153000.000Z`，
  按 **UTC** 解析；`METADATA_KEY_VIDEO_ROTATION` 为 90/270 时**交换宽高**，存的是用户看到的尺寸。
- `sourceDate` 的优先级（本模块统一实现，`MediaImportLogic`，有 JVM 单测）：
  `EXIF DateTimeOriginal`（图片）→ `MediaMetadataRetriever.METADATA_KEY_DATE`（音视频）→
  `MediaStore.DATE_TAKEN` → `MediaStore.DATE_ADDED`（注意是**秒**）。全都没有则为 `null`，
  上游排序按「存入日期」兜底（ADR-052 §3 排序语义），**绝不用 `Instant.now()` 顶替**——
  那会让「按源数据排序」退化成「按导入顺序排序」。

## 五、权限策略

清单**只加读取**，不加写入、不加 `MANAGE_EXTERNAL_STORAGE`：

| 版本 | 权限 |
| --- | --- |
| API 33+ | `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` / `READ_MEDIA_AUDIO`（**按需**，见下） |
| API ≤ 32 | `READ_EXTERNAL_STORAGE`（`maxSdkVersion="32"`） |

- **按需申请**：调用方传 `kinds`，选择器只申请对应类型的权限。只挑图片不给音频授权，
  最小化授权范围（`MediaPermissions.required(kinds, sdkInt)`，有单测）。
- **运行时申请**在 `MediaPickerScreen` 内部完成，不让每个调用方重复实现；
  被拒时展示说明卡 +「重新申请」，永久拒绝时额外给「去设置里授权」入口。
- 读到的文件**只进不出**：选择器只回传 `PickedMedia`，导入由 `MediaImporter` 完成，
  解码后立即加密落盘，源文件不复制到公共目录，不写 `MediaStore`。
- `allowBackup=false` 不变，媒体仍只存在于应用私有目录的密文里（ADR-040 / `docs/SECURITY.md`）。

## 六、宿主注入：为什么用 CompositionLocal

本模块**禁用 Hilt**（AGENTS §9：Hilt 只出现在 `:app`）。但 ADR-052 §4 的三个
`@Composable` 契约签名里没有 store 参数，而它们必须拿到 `MediaStore` / `ImageStore` /
`cacheDir`。因此：

- 模块公开 `MediaHost(mediaStore, imageStore, cacheDir)` 与
  `ProvideMediaHost(host) { ... }`（`staticCompositionLocalOf`）；
- `:app` 在自己的根组合里包一层，`MediaStore` 与 `ImageStore` 由既有的
  `di/AppModule.kt` 提供，**依赖注入框架没有传染给本模块**；
- 未包 `ProvideMediaHost` 时组件**降级而不崩溃**（缩略图显示占位图标），便于预览与单测。

这是对契约的唯一结构性补充：新增的类型是「装配用的把手」，不改变任何契约签名。

## 七、已知限制

1. **未在本机编译**（AGENTS §1 禁止本机 Gradle）。验收以 GitHub Actions 为准；
   纯逻辑部分（MIME→kind、EXIF/MediaStore 日期、旋转、权限矩阵、容器后缀）有 JVM 单测兜住。
2. **没有后台播放**：不引 `media3-session`，息屏/切后台即停。符合「查看器内播放」的定位。
3. **视频缩略图取第 1 秒的关键帧**（`OPTION_CLOSEST_SYNC`）。片头是黑场的视频封面会是黑的。
4. **选择器一次每种类型最多列 600 条**（`DeviceMediaQuery.PER_KIND_LIMIT`），
   避免超大图库一次性把缩略图解码进内存；需要时按分页再接。
5. **音频不生成波形图 / 不抽封面**，只存时长与元数据。
6. **图片导入会把原图整读进内存**（EXIF 与缩放都需要像素）；视频音频走流式。
   单张图片在设备上是可接受的量级（数 MB），但如果将来要支持超大 TIFF/RAW，需要改成
   分块 + `BitmapRegionDecoder`。
7. **临时明文文件在 `cacheDir`**：播放中被强杀进程时文件会残留，
   由下次 `MediaPlaybackFiles.prune` 清理（6 小时后视为废弃）。
