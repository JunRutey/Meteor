# ADR-063 · 媒体二级密钥与并行加解密

> 状态：已采纳；**适用范围已由 [ADR-067](ADR-067-unified-binary-payload-encryption.md)
> 扩展到附件与图片**（见文末「修订」）。关联：[ADR-052](ADR-052-media-album-hub.md)（媒体存储）、
> [ADR-058](ADR-058-large-media-key-cache-and-streaming-playback.md)（密钥缓存与流式播放）、
> [ADR-062](ADR-062-media-chunk-size-10mb.md)（媒体 10 MiB 分块）、
> [ADR-040](ADR-040-backup-coverage.md)（备份范围）。

## 一、问题

ADR-062 把媒体分块从 1 MiB 提高到 10 MiB，去掉了 90% 的 Keystore 往返，并留下了明确结论：
**只要密钥还在 Android Keystore 里，多线程并行没有收益**——keystore 守护进程基本串行执行
`Cipher.init` / `doFinal`，多线程只是把请求排进同一个队列。

于是大视频（数百 MB）导入/导出仍受限于「逐块串行 + 每块一次跨进程密码学调用」。要再快一档，
必须把 AES 执行搬回应用进程：Keystore 只保护一把随机数据密钥（MK，media key），媒体分块用
MK 的 `SecretKeySpec` 在本进程内加解密，从而可以开线程池并行。

代价是明确的**安全降级**：MK 会以明文形式出现在应用内存中。维护者已确认接受该权衡，
且限定只用于媒体，并要求：

- 仅**新媒体文件**用 MK 加密；日记、心情、人物档案、附件、旧图片继续用 Keystore 密钥；
- 不提供用户可见的密钥恢复口令；
- 密钥不显示、不写日志、不进备份。

## 二、R1 四问

1. **属于哪个模块？** `core-crypto` 新增 `MediaKeyStore`（密钥生成/包裹/缓存）；
   `core-storage` 内部扩展 `ChunkedEncryptedFile` 与 `EncryptedMediaStore`（格式版本 3、
   并行参数、按版本选密钥）；`:app` 的 `AppModule` / `RootViewModel` 负责接线与预热。
   不新增模块。
2. **依赖谁？谁依赖它？** 无新增依赖边：`core-crypto` 只用 `javax.crypto`/`java.io`；
   `core-storage` 本就依赖 `core-crypto`；`:app` 本就依赖两者。依赖方向仍单向、无环。
3. **对外接口是什么？** `FileCrypto` 冻结接口**签名不变**；`MediaKeyStore` 只有
   `key(): SecretKey` 与 `ensureGenerated()` 两个入口；`EncryptedMediaStore` 构造函数新增
   **带默认值**的 `mediaKeyCrypto = crypto`；`ChunkedEncryptedFile` 是 `internal`，新增参数
   均带默认值。既有调用方不修改也能编译并通过既有测试。
4. **是否有数据格式变更？** 有，但只新增媒体 payload 的版本 3（10 MiB 分块 + MK），
   读取端同时支持 v1/v2/v3；附件仍写 v1；元数据边车、缩略图、备份 zip、Markdown 引用格式
   全部不变。旧媒体无需迁移。

## 三、决策

### 1. 密钥生命周期

- **生成**：首次调用 `MediaKeyStore.key()`（或启动预热 `ensureGenerated()`）时用
  `SecureRandom` 生成 32 字节（AES-256）密钥。
- **保存**：用现有 Android Keystore 的 `FileCrypto`（AES-GCM，密钥不可导出）把 MK 包裹后写入
  `filesDir/media_key.bin`。先写同目录 `.tmp`、`fsync`，再 `rename` 到正式文件名：崩溃时
  要么旧状态完好，要么完整的新文件可见，不会留下半把密钥。
- **复用**：文件存在则解包复用；同一进程内缓存 `SecretKey` 句柄，后续调用不再读盘、不再走
  Keystore 解密，并发首次调用只会生成一把密钥。
- **暴露面**：MK 只以 `SecretKey` 句柄交给 `AesGcmFileCrypto` 使用；不写日志、不显示 UI、
  不进 SharedPreferences、不进备份、不进异常消息。
- **预热**：`RootViewModel.init` 在 `Dispatchers.IO` 上 `runCatching { ensureGenerated() }`，
  失败只记一条 warn 日志（不含密钥内容），让第一次媒体导入不必现生成。
- **损坏策略**：读文件失败、解包失败或解出的长度不是 32 字节时**抛 `IllegalStateException`，
  绝不静默重建**。重建会让已加密媒体永久不可读，因此宁可让媒体路径报错，也不能覆盖原文件。
  损坏只影响媒体载荷；日记、心情、人物档案、附件仍由 Keystore 密钥加密，不受影响。

### 2. 数据格式版本 3

`ChunkedEncryptedFile` 新增 `FORMAT_VERSION_MEDIA_KEY = 3`，分块大小与 v2 相同
（10 MiB，`LARGE_CHUNK_SIZE`），线格式不变：

```
int magic | int version(=3)
repeat: int encryptedSize | encrypted( int chunkIndex | chunkBytes )
int 0
```

差异只在加密密钥：v3 用 MK。读取端先读 version，再通过调用方传入的
`cryptoFor: (Int) -> FileCrypto` 选密钥：

- `version >= 3` → 媒体密钥（`MediaKeyCrypto`）；
- `version 1 / 2` → Keystore 密钥。

写入路径：

- `EncryptedMediaStore.save` / `restore` 一律写 v3 + MK（备份恢复后也是 v3）；
- 元数据边车 `.meta.enc` 与缩略图 `.thumb.enc` 继续用 Keystore `crypto`；
- `AttachmentStore` 保持 v1 + 1 MiB + 串行（附件不进入本次改动）；
- 日记 / 心情 / 人物档案 / 旧 `ImageStore` 图片完全不动。

### 3. 并行加解密

- `write(..., parallelism = 2)`：调用线程只做「顺序读块 + 顺序写盘」，把加密任务提交到
  `Executors.newFixedThreadPool(parallelism)`；用一个 **FIFO 的 `Future` 队列**保存未写结果，
  队列长度达到 `parallelism` 时先 `get` 最旧的任务并写盘。因此写盘顺序严格等于读入顺序，
  文件内的 `chunkIndex` 始终递增；`Future.get` 抛出的 `ExecutionException` 会解开重抛真实
  异常（如加密失败），线程池在 `finally` 中 `shutdown()`。
- `copyDecrypted(..., cryptoFor, parallelism = 2)`：同样的有界 FIFO 流水线，任务内做
  解密 + chunk index 校验 + payload 长度校验，主线程按序写出，并保留末尾
  `check(input.read() == -1)` 的尾部完整校验。
- `openDecrypted(..., cryptoFor, offsetBytes)`：保持**顺序流式**，不做并行——播放一次只需要
  一块明文；仍按 version 选密钥，行为与 ADR-058 一致。
- **内存上限**：块大小 10 MiB。读缓冲 1 块 + 队列中最多 `parallelism` 块的明文/密文，
  `parallelism = 2` 时峰值约几十 MiB（远小于 `copyDecrypted` 整读后的大小）。
- 线程安全前提：`AesGcmFileCrypto.encrypt/decrypt` 每次新建 `Cipher`，可以被多线程并发调用；
  `MediaKeyStore.key()` 返回缓存好的 `SecretKeySpec`，不涉及 Keystore 往返。

### 4. 为什么不做用户恢复口令 / 不给用户看密钥

- 产品未要求跨设备恢复密钥；档案的既有语义是「卸载或换机即失去 Keystore 密钥」，
  备份以明文 zip 导出（ADR-040）。MK 若再引入恢复口令，会新增一份用户可见的密钥材料和
  一致性风险，收益低于复杂度，因此明确不做。
- 不提供密钥展示 UI/文案，避免密钥进入截图、分享、日志。

## 四、安全权衡

- **降级点**：MK 以明文 `SecretKeySpec` 存在于应用进程内存中。一旦进程被攻破、内存被转储，
  攻击者可以提取 MK 并解密**媒体**。Keystore 密钥即使进程内存被读也无法导出。
- **限定泄漏面**：泄漏只覆盖媒体文件；日记、心情、人物档案、附件继续由不可导出的 Keystore
  密钥保护，不因 MK 泄漏而受影响。
- **持久化面**：磁盘上只有被 Keystore 包裹的 MK（`media_key.bin`），没有明文 MK；
  该文件不进备份、不进 SharedPreferences、不进日志。
- **完整性**：每个分块仍是独立 AES-GCM，认证失败只影响该块；MK 文件被篡改时解包失败并报错，
  不会用错误密钥继续加密（否则会写出永久不可读的新媒体）。
- **风险控制**：MK 生成后只在 `AesGcmFileCrypto` 的 key provider 中使用；`MediaKeyStore`
  不提供 `encoded` 的日志/导出路径，异常消息也只描述失败原因。

## 五、兼容性与备份 / 换机语义

- **旧媒体零迁移**：v1（1 MiB，Keystore）与 v2（10 MiB，Keystore）媒体按头部 version 分流读取，
  继续可播放、可分享、可备份、可删除。
- **新文件单向兼容**：旧版本客户端读到 v3 会报「不支持的加密分块格式」，与 ADR-062 引入 v2 时
  相同；新版本读旧文件正常。
- **备份**：导出仍是「解密后的明文 zip + `media-manifest.json`」，不含任何密钥；导入时
  `restore` 走与正常导入相同的写入路径，因此恢复出来的媒体是 v3（用当前设备的 MK 重新加密）。
  旧备份（含 v1/v2 媒体）仍可导入。
- **换机 / 卸载**：`media_key.bin` 位于应用私有 `filesDir`，不在备份范围内；换机后 MK 丢失，
  但 Keystore 密钥同样丢失，旧档案本来就需要通过明文备份重建。从备份恢复后，媒体由新设备
  新生成的 MK 加密。
- **密钥文件损坏**：媒体解密报错、不会静默换 key；其他数据类型不受影响。用户可通过导入
  明文备份恢复媒体。

## 六、后果

- 大视频导入/导出不再逐块跨 Binder 串行：AES 在应用进程内双线程执行，剩余瓶颈回到磁盘 IO。
- 播放路径不变（`openDecrypted` 仍顺序流式），并行只作用于整文件导入/导出/分享。
- 新增一个私有文件 `filesDir/media_key.bin`（Keystore 包裹后的 MK）。
- 内存峰值增加约几十 MiB（2 个 10 MiB 块的在途明文/密文），在手机可接受范围。
- 单测覆盖：`MediaKeyStoreTest`（首次生成/复用/幂等预热/损坏不重建/并发只生成一次）、
  `ChunkedEncryptedFileTest`（v1/v2/v3 读取、并行写入与并行解密顺序、异常解包、尾部校验）、
  `EncryptedMediaStoreTest`（媒体载荷用 MK、边车仍用 Keystore 密钥、默认参数兼容）。

## 七、修订（ADR-067）

本 ADR 原先把 MK 的适用范围**限定为媒体**，附件与旧版图片继续用 Keystore 主密钥。
[ADR-067](ADR-067-unified-binary-payload-encryption.md) 已把这一限制取消：

- 附件载荷改为 v3 分块帧 + MK（10 MiB、2 线程并行），不再是 v1 1 MiB 串行；
- 旧版图片由整文件密文改为 v3 分块帧 + MK（magic `IMAG`）；
- 媒体载荷的格式与实现**不变**，`EncryptedMediaStore` 一行未改。

本 ADR §二/§三 里「仅新媒体文件用 MK，日记、心情、人物档案、附件、旧图片继续用
Keystore 密钥」的表述以 ADR-067 为准。仍然不变的边界是：**派生小文件**
（元数据边车、缩略图、抽取文本）与**全部文本内容**（记录正文、AI 会话、凭据、
出生日期、设置快照）继续由 Keystore 主密钥保护，理由见 ADR-067 §三.2。

