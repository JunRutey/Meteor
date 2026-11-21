# ADR-058 · 大文件媒体：密钥缓存与流式解密播放

> 状态：已采纳。关联：[ADR-052](ADR-052-media-album-hub.md)（媒体存储与总入口）、
> [ADR-053](ADR-053-media-module-and-playback-stack.md)（播放栈）。

## 一、问题

维护者用 debug 版导入一段 600 MB 视频后发现两处明显的卡顿：

1. **导入处理很久**：选择文件后要经过很长的「正在处理媒体…」。
2. **点播放加载不出来**：点开视频后要等很久，甚至最终失败。

## 二、根因（实测阅读代码得出）

### 1. 导入：每 1 MiB 重新打开一次 Android Keystore

`MediaImporter` 把源文件流式写进 `MediaStore`，由 `ChunkedEncryptedFile` 按
**1 MiB 一块**做 AES-256-GCM 加密。问题在于 `AesGcmFileCrypto.encrypt` 每次
都调 `keyProvider()` → `KeystoreKeyStore.secretKey(alias)`，而后者每次都执行：

```kotlin
KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    .getEntry(alias, null)
```

`load(null)` + `getEntry` 是 **Binder 往返到 keystore 守护进程**，不是进程内查表。
600 MB ÷ 1 MiB = **600 次 Keystore 往返**，再叠 600 MB 读 + 600 MB 写盘。
加密计算本身（硬件 AES）只占很小一部分，瓶颈是密钥获取的往返。

### 2. 播放：先把整个文件解密成明文临时文件，再交给播放器

`VideoPlayerCard` / `AudioPlayerCard` 点播放时调用
`MediaPlaybackFiles.materialize()`，内部 `copyDecrypted()` **完整解密**
600 MB 写入 `cacheDir`，全部写完才 `ExoPlayer.prepare()`。
于是首帧之前要付出：600 次 Keystore 往返 + 解密 600 MB + 写 600 MB。
另外 `cacheDir` 会短暂存在一份 600 MB 明文副本。

## 三、决策

### 1. 进程内缓存 Keystore 密钥句柄

`KeystoreKeyStore` 用 `ConcurrentHashMap<String, SecretKey>` 缓存
`getEntry` 的结果。Keystore 返回的 `SecretKey` 是可重复用于 `Cipher.init` 的句柄，
缓存它不改变密钥的不可导出性，只是避免重复走 Binder。`deleteKey` 会同时清缓存。

### 2. 流式解密播放（自定义 ExoPlayer DataSource）

- `core-storage` 新增 `MediaStore.openDecrypted(id, offsetBytes)`（**带默认实现**的
  增量方法，符合 R2），由 `ChunkedEncryptedFile.openDecrypted` 实现：
  顺序扫描长度前缀跳到目标块（**只读 4 字节前缀 + skip，不解密中间块**），
  然后按需逐块解密，返回一个 `InputStream`。
- `:feature-media` 新增 `MediaStreamDataSource`（`BaseDataSource`）：
  `open(dataSpec)` 以 `dataSpec.position` 作为明文偏移打开流，`read()` 增量解密，
  `close()` 释放。`VideoPlayerCard` / `AudioPlayerCard` 改用
  `ProgressiveMediaSource.Factory(MediaStreamDataSource.Factory(mediaStore))`，
  不再生成明文临时文件。
- 播放器 seek 时 Media3 会用新的 position 重新 `open`，扫描代价与文件块数线性相关
  （600 MB 约 600 个长度前缀，可忽略）。

### 3. 分享仍走临时文件

系统分享必须给出一个真实文件（`FileProvider`），所以
`MediaViewerViewModel` 的分享继续用 `copyDecrypted` 写 `cacheDir/share/` 再分享。
这是用户主动操作，且文件在分享后被清理；不改变本 ADR 的播放路径。

### 4. 不取消加密

导出/分享/备份都不在本次优化范围内。取消媒体加密会破坏「档案自包含且加密」的产品前提
（AGENTS / archive §5–§6），因此只优化密钥获取与播放读取方式。

## 四、R1 四问

1. **模块**：`core-crypto`（密钥缓存）、`core-storage`（`openDecrypted` 增量接口）、
   `:feature-media`（`MediaStreamDataSource` + 播放器改造）。不新增模块。
2. **依赖**：无新增模块边；`:feature-media` 已依赖 `:core-storage`。
   新增依赖 `androidx.media3:media3-datasource`（与既有 media3 同版本，版本集中在
   `gradle/libs.versions.toml`）。
3. **接口**：`MediaStore.openDecrypted(id, offsetBytes): InputStream` 为**带默认实现**的
   新增方法，旧实现无需改动；`MediaStreamDataSource` 是模块内部类型。
4. **数据格式**：不改变。加密文件格式、meta 格式、Markdown 引用都保持原样；
   已有 600 MB 媒体无需迁移即可流式播放。

## 五、后果

- 导入 600 MB 视频不再有 600 次 Keystore 往返，耗时主要剩下纯 IO + AES 计算。
- 播放首帧只需解密开头若干块，不再等待整份明文落盘，也不再有 600 MB 明文临时文件。
- 备份导出/导入仍逐块解密/加密，但同样受益于密钥缓存。
- 旧版本写入的媒体（相同 v1 分块格式）可直接流式读取，向后兼容。
