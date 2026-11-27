# ADR-067 · 二进制载荷统一加密通道（媒体 / 附件 / 图片）

> 状态：已采纳。关联：[ADR-052](ADR-052-media-album-hub.md)（媒体存储总纲）、
> [ADR-058](ADR-058-large-media-key-cache-and-streaming-playback.md)（密钥缓存与流式播放）、
> [ADR-062](ADR-062-media-chunk-size-10mb.md)（10 MiB 分块）、
> [ADR-063](ADR-063-media-secondary-key.md)（媒体二级密钥与并行加解密）、
> [ADR-065](ADR-065-delete-records-page-and-merge-files-into-album.md)（记录页并入相册）。

## 一、问题

到 v1.5.2 为止，应用里有**三套**二进制载荷加密方式并存，同一个「用户内容」在磁盘上走三条路：

| 内容 | 分块 | 密钥 | 并行 |
|---|---|---|---|
| 媒体（图/视频/音频，ADR-052/062/063） | v3，10 MiB | 媒体数据密钥（MK） | 是（2 线程） |
| 附件（导入的文件，ADR-029/065） | v1，1 MiB | Keystore 主密钥 | 否 |
| 旧版图片（`ImageStore`，编辑器插图/人物头像） | 无，整文件 AES-GCM | Keystore 主密钥 | 否 |

ADR-063 已经把「为什么媒体要换密钥」讲清楚了：Keystore 守护进程串行执行
`Cipher.init` / `doFinal`，密钥不出守护进程时多线程没有收益；只有把 AES 搬回应用进程
（用 Keystore 只包裹一把随机数据密钥 MK）才能并行。附件和图片面对的是**完全相同**的瓶颈，
却因为「ADR-063 限定只用于媒体」而留在旧路径上。

代价是明确的，而且不只在性能上：

1. **同一份代码有三份实现。** `EncryptedAttachmentStore` 与 `EncryptedMediaStore` 各有一套
   分块写入、原子改名、边车编解码与错误处理；`EncryptedImageStore` 又是第三种（整文件）。
   一处修好（例如原子改名、长度校验）另外两处不会跟着修好。
2. **体验不一致。** 导入一个 200 MB 的聊天导出（附件）走 1 MiB 串行 + 每次 Keystore 往返，
   比同样大小的视频（媒体）慢一个数量级；编辑器插图同样受整文件 Keystore 加密限制。
3. **备份语义按内容种类分裂。** `StorageRouter` 的导出路径要单独识别附件（走
   `copyDecrypted`），而图片只能整文件解密；一旦图片换成新格式，导出就必须再认一次。
   这正是 ADR-052 §5 F 里「媒体密文进了 zip 就是废字节」那类问题的温床。

ADR-063 当初把范围限定为媒体，是为了**把一次安全降级的影响面压到最小**，并在同一次
变更里验证「MK + 并行」这条路真的能跑通。这一目标已经达成（v1.4.0 起媒体一直在用），
所以现在到了消除三套实现的时候。

## 二、R1 四问

1. **属于哪个模块？** 全部并入现有模块，**不新增模块**：
   - `core-storage`：`EncryptedAttachmentStore` 与 `EncryptedImageStore` 新增带默认值的
     `mediaKeyCrypto` 参数并改写载荷读写；`ImageStore` 接口新增带默认实现的方法；
     `ChunkedEncryptedFile` 的读缓冲改为按需增长；`PrivateStorageRouter` 识别图片载荷。
   - `:app`：`AppModule` 把 `@MediaKeyCrypto` 接到图片、附件与路由三处。
   不涉及 `core-crypto`：`MediaKeyStore` 与 `FileCrypto` 一行不改。
2. **依赖谁？谁依赖它？** **没有新增依赖边**。`core-storage → core-crypto` 是既有单向边，
   这次只是多用一个已注入的 `FileCrypto`；`app → core-storage` 同样是既有边。仍然无环，
   `tools/check-module-graph.sh` 的边表不需要改。
3. **对外接口是什么？** 只做加法，且新增项全部带默认值，符合 R2：
   - `ImageStore.copyDecrypted(token, output): Long`（默认抛 `UnsupportedOperationException`）
   - `ImageStore.restore(token, bytes): String?`（默认返回 null）
   - `AttachmentStore.openDecrypted(id, offsetBytes): InputStream`（默认抛
     `UnsupportedOperationException`），与 `MediaStore.openDecrypted` 对齐
   - `EncryptedAttachmentStore(rootDir, crypto, mediaKeyCrypto = crypto)`
   - `EncryptedImageStore(rootDir, crypto, mediaKeyCrypto = crypto)`
   - `PrivateStorageRouter(context, crypto, time, mediaKeyCrypto = crypto)`

   `FileCrypto`、`AppLock`、`StorageRouter`、`BackupModule`、`IndexModule`、`EditorModule`、
   `AiModule` 这七个 `FROZEN INTERFACE` **签名一个字符未动**，既有调用方不改也能编译。
4. **是否有数据格式变更？** 有，但只发生在**载荷容器**上，且只增不改：
   - 附件载荷由 v1（1 MiB、Keystore 密钥）改为 **v3**（10 MiB、MK）；
   - 旧版图片由「整文件 AES-GCM 密文」改为 **v3 分块帧**（magic `IMAG`）；
   - 读取端按格式版本 / 魔数分流，**旧文件零迁移继续可读**；
   - 元数据边车、抽取文本、缩略图、备份 zip 结构、`media-manifest.json`、Markdown 引用
     格式**全部不变**。

## 三、决策

### 1. 一条通道：v3 分块帧 + 媒体数据密钥

「用户内容」（图、视频、音频、导入的文件、编辑器插图）的**载荷**一律写成
`ChunkedEncryptedFile` 的 **格式版本 3**：10 MiB 明文分块、每块独立 AES-256-GCM、
写入时 2 个线程并行加密、按序落盘。

密钥一律是 ADR-063 引入的**媒体数据密钥 MK**（`filesDir/media_key.bin`，由 Keystore 主密钥
包裹）。不再区分「媒体用 MK、附件用 Keystore」。

各内容只需在**容器**上不同，而容器是既有事实、本次不动：

| 内容 | 容器 | magic | 边车 |
|---|---|---|---|
| 媒体 | `数字遗产/媒体/<owner>/<id>.data.enc` | `MEDI` | `.meta.enc` + `.thumb.enc` |
| 附件 | `数字遗产/附件/<owner>/<id>.data.enc` | `MATT` | `.meta.enc` + `.text.enc` |
| 图片 | `数字遗产/图片/<owner>/<uuid>.enc` | `IMAG` | 无 |

magic 不同，所以同一段读取代码永远不会把一种载荷当成另一种解析（这是 ADR-052 就定下的
约定，本次沿用）。

### 2. 派生小文件继续用 Keystore 主密钥

以下内容**不**改密钥，继续走 `crypto`（Keystore 主密钥，整文件 AES-GCM）：

- 记录正文（`profile.md` / 日记 / 心情 / 聊天记录 / `persona.md`）——`DocumentCodec`；
- 媒体与附件的**元数据边车**（`.meta.enc`）、**缩略图**（`.thumb.enc`）、
  **抽取文本**（`.text.enc`）；
- 出生日期、设置快照、AI 会话、AI 凭据。

理由有两条，缺一不可：

1. **这些文件都很小**（边车通常几 KB，缩略图 ≤ 512 px，抽取文本上限 2 MiB），
   并行与分块粒度对它们没有意义，换密钥换不来任何性能。
2. **它们是「索引」而不是「内容」。** 相册要列出条目就得先解开边车；如果边车也用 MK，
   一旦 `media_key.bin` 损坏，**整个相册连条目都列不出来**，用户看到的是一片空白而不是
   「这些文件打不开」。让边车留在 Keystore 密钥上，损坏时至少还能显示「有哪些内容、
   但读不出内容」，恢复路径（导入明文备份）依然可用。

这条边界也决定了本次的**最小改动面**：载荷换通道，边车不动，于是 `EncryptedMediaStore`
**完全不需要修改**（它本来就是这个形状，是这次要统一的样板）。

### 3. 旧版图片：容器换成 v3 分块帧

`EncryptedImageStore` 从「整文件 `crypto.encrypt(bytes)`」改为写 v3 分块帧：

- 新文件以 magic `IMAG`（`0x494D4147`）开头，后接版本号与分块帧；
- 读取时只读前 4 字节做**魔数嗅探**：
  - `IMAG` → 分块帧，按头部 version 通过 `cryptoFor` 选密钥（v3 = MK）；
  - 其它 → 旧版整文件密文，用 Keystore 主密钥 `crypto.decrypt`。

之所以可以这样嗅探：旧格式的首字节是 `AesGcmFileCrypto` 写的 IV 长度（12–16），
`0x49`（`I`）不在该范围内，两者不可能混淆。嗅探只读 4 字节，不会把大文件读进内存。

写入走「同目录 `.part` 临时文件 + 原子改名」，与媒体/附件的写入语义一致：崩溃时要么旧文件
完好，要么完整的新文件可见。

### 4. `ChunkedEncryptedFile` 的读缓冲按需增长

格式的**逻辑**分块大小由版本决定（v3 = 10 MiB），但绝大多数载荷远小于一块：一张 200 KB
的插图如果按 10 MiB 预留缓冲，每次插图都要多分配 10 MiB 并清零。

因此写入侧的读缓冲改为**从 256 KiB 起按需翻倍，最多到逻辑分块大小**：

- 缓冲填满且未达逻辑大小 → 翻倍后继续读**同一块**；
- 缓冲填满且已达逻辑大小 → 该块结束；
- 读到流尾 → 已读内容即最后一块。

这样磁盘上的分块边界与「固定 10 MiB 缓冲」完全一致（大文件仍然是 10 MiB 一块，
`openDecrypted` 的定位语义不变），而小文件只花小缓冲。这是一次纯内存优化，
**不改变任何已写出的文件格式**。

### 5. `StorageRouter` 的备份语义

`StorageRouter` 是冻结接口，导出/导入必须继续对调用方透明。图片换格式后：

- **导出**：`数字遗产/图片/<owner>/<file>.enc` 交给 `ImageStore.load` 解密后写进 zip
  （图片本身很小，编辑器压缩到最长边 1568 px，缓冲一份与旧的整文件读取代价相同）。
  单张图片读不出来时退回旧行为（原样写入原始字节），不让一张坏图毁掉整次导出。
- **导入**：zip 里该路径的字节**已经是明文**，直接交给 `ImageStore.restore(token, bytes)`
  原地重写为新格式，`meteor://image/<token>` 引用因此继续有效（不重新生成 token）。

旧备份（图片是明文）走同一条导入路径，结果一致。

**附件有一个必须一起处理的连带问题。** 附件的边车记录 `chunkCount`，而
`copyDecrypted` 会严格校验「边车里的块数 == 实际读到的块数」，用来发现载荷被截断。
ADR-067 把写入从 1 MiB 改成 10 MiB 后，**旧备份恢复出来的载荷块数变了，边车却没变**：
一个 3 MiB 的附件过去是 3 块，现在是 1 块，校验立刻失败——即「从备份恢复的附件永久读不出来」。

修法不是放宽校验（那会让真正的截断静默通过），而是让恢复路径在**载荷与边车都落盘之后**
重新对齐块数：

- `restorePayload` 返回它实际写出的块数；
- `PrivateStorageRouter.importFrom` 在整个 zip 读完后，对这些 id 逐个调用
  `rewriteChunkCount`，用当前块数重写边车（仍然用 Keystore 密钥加密，格式不变）；
- 边车缺失或损坏时**只跳过重写**，绝不动已经落盘的载荷——一份读不出来的附件也比
  一份被删掉的附件好。

之所以要等整包读完再改：边车在 zip 里是独立条目，导入时它与载荷是两次互不相关的
`importEntry` 调用，谁先谁后没有保证；只有整包读完后才能确保边车已经落盘。

### 6. 不做的事

- **不迁移旧文件。** 旧附件（v1）和旧图片（整文件）保持原样，读取端继续支持。
  迁移需要把全部历史载荷解密再加密一遍，收益只是磁盘布局整齐，风险却是「迁移中断 =
  内容损坏」，不做。
- **不合并媒体/附件/图片的目录。** 目录是引用格式（`meteor://media|image|file`）的一部分，
  合并会破坏所有历史 Markdown。
- **不引入用户可见的密钥恢复口令。** 与 ADR-063 §四一致。
- **不动 `EncryptedMediaStore`。** 它已经是目标形状；改它只会扩大回归面。

## 四、安全权衡

- **降级面扩大，但降级的性质不变。** ADR-063 的降级点是「MK 以明文 `SecretKeySpec` 存在于
  应用进程内存」。本次把这一降级从「只覆盖媒体」扩展到「覆盖媒体、附件与图片」，
  也就是**全部二进制用户内容**。进程内存被转储时，攻击者能解密这些内容；
  Keystore 主密钥依然不可导出。
- **文本内容不受影响。** 记录正文、人物档案、心情、聊天记录、AI 会话、出生日期、设置快照
  继续由不可导出的 Keystore 密钥保护。也就是说，泄漏面是「照片/视频/音频/导入的文件」，
  不是「文字档案」。
- **磁盘上仍然没有明文 MK。** 只有 `media_key.bin`（Keystore 包裹体），不进备份、
  不进 SharedPreferences、不进日志。
- **完整性不变。** 每块仍是独立 AES-GCM，认证失败只影响该块；MK 文件损坏时解包失败并
  报错，绝不静默重建（`MediaKeyStore` 的既有行为）。
- **风险控制。** MK 只以 `SecretKey` 句柄交给 `AesGcmFileCrypto`；`MediaKeyStore` 不提供
  `encoded` 的日志或导出路径。本次没有新增任何密钥材料的落盘位置。

## 五、兼容性、备份与换机语义

- **旧内容零迁移**：附件按头部 version 分流（v1/v2 → Keystore 密钥，v3 → MK）；
  图片按魔数分流（`IMAG` → 分块帧，否则整文件密文）。旧附件可下载、旧图片可显示，
  都不需要任何一次性迁移动作。
- **新文件单向兼容**：旧版本客户端读到 v3 附件载荷会报「不支持的加密分块格式」，
  读到 `IMAG` 开头的图片会解密失败（表现为图片缺失）。这与 ADR-062 引入 v2、
  ADR-063 引入 v3 媒体时的方向一致：新版本读旧文件正常，旧版本读新文件明确失败。
- **备份**：导出仍是「解密后的明文 zip + `media-manifest.json`」，不含任何密钥。
  图片在 zip 里也是明文，导入时按当前设备密钥重新加密。
- **换机 / 卸载**：`media_key.bin` 在应用私有 `filesDir`，不在备份范围内；换机后 MK 与
  Keystore 密钥同时丢失，恢复路径仍然是导入明文备份。恢复出来的载荷由新设备新生成的
  MK 加密。
- **密钥文件损坏**：媒体、附件、图片的**载荷**读不出来（明确报错），但边车仍可解，
  所以相册/文件列表还能显示条目与文件名；文本档案完全不受影响。用户可通过导入明文备份恢复。

## 六、后果

- 导入大文件（聊天导出、压缩包、长音频）从「1 MiB 串行 + 每次 Keystore 往返」变为
  「10 MiB 分块 + 应用进程内双线程」，与视频导入同速；编辑器插图同理。
- 三套载荷读写收敛为一套：`ChunkedEncryptedFile` + `cryptoFor` 版本分流。今后修分块、
  原子改名或长度校验，一处生效于媒体、附件与图片。
- 备份导出不再需要「图片是整文件密文」这一特例之外的额外假设：二进制载荷一律解密成明文
  写进 zip，图片走 `ImageStore.load`，附件与媒体走 `copyDecrypted` 流式解密。
- 新增一个 magic 常量 `IMAG`，以及 `ImageStore.restore` / `copyDecrypted`、
  `AttachmentStore.openDecrypted` 三个带默认实现的方法。
- 内存：插图不再为单块预留 10 MiB（`ChunkedEncryptedFile` 读缓冲按需增长）；
  大文件仍是 10 MiB 一块、2 块在途，与 ADR-063 一致。
- 单测覆盖：`EncryptedAttachmentStoreTest`（新载荷为 v3 + MK、边车仍用 Keystore、
  错误密钥不可读、旧 v1 载荷仍可读、`openDecrypted` 偏移、**旧备份恢复后边车块数被重写
  且附件仍可读**）、`EncryptedImageStoreTest`（新文件为分块帧、旧整文件密文仍可读、
  `restore` 保留 token、`copyDecrypted`）、`ChunkedEncryptedFileTest`（小载荷不预留整块、
  分块边界与固定缓冲一致）、`AlbumRepositoryTest`（三者在相册里仍按引用分流）。
