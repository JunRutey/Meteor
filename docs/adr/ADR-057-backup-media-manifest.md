# ADR-057 · 备份覆盖新媒体与元数据清单

- 状态：已接受
- 日期：2026-11-21
- 关联：ADR-004（明文 zip 备份）、ADR-040（备份覆盖出生日期与设置）、ADR-052（媒体存储与相册总入口，§3 接口契约、§5 F 备份规格）、ADR-020（图片存储）、ADR-029（attachment 记录类型）

## 背景

ADR-052 新增了 `数字遗产/媒体/`：新媒体以 `MediaStore` 分块加密落盘，目录形如
`数字遗产/媒体/<owner>/<id>.data.enc | .meta.enc | .thumb.enc`，正文用
`![alt](meteor://media/<32hex id>)` 引用，排序依赖元数据里的 `sourceDate`。

备份仍由 `PlaintextBackupModule` 经冻结的 `StorageRouter.exportTo/importFrom` 完成，它只做
「遍历档案目录 → 能解密就写明文，否则原样写」：

- 媒体数据是**分块加密**的，`plaintextOf` 的整文件解密对它无效，导出的是**原始密文**；
- 密文只有本机 Keystore 能解，换机后这些字节**完全无用**；
- 即使能解，`<id>.meta.enc` 里的 `fileName / mimeType / personId / sourceDate / 时长 / 宽高`
  也在密文里，新设备没有这些就既排不了序、也认不出类型；
- 恢复路径同样没有媒体概念，导入只会把明文当密文再加密一遍。

结果：换机恢复后，所有视频/音频与全部新媒体图片丢失（或变成无法读取的加密块）。

## 决定

### 1. 不新增模块、不改冻结签名（R1/R2）

全部改动落在 `:core-backup` 内，依赖边仍只有 `core-backup → core-storage`、`core-backup → core-crypto`，
不新增任何边。

`BackupModule`（FROZEN）一字未改。`PlaintextBackupModule` 的构造函数**追加一个可选参数**
（放在 `mirror` 之后，参数名固定为 `mediaStore`，命名调用兼容）：

```kotlin
class PlaintextBackupModule(
    private val context: Context,
    private val router: StorageRouter,
    private val retentionProvider: BackupRetentionProvider,
    private val clock: () -> Instant = Instant::now,
    private val mirror: ExternalBackupMirror = NoExternalBackupMirror,
    private val mediaStore: MediaStore? = null,   // 新增，默认 null
) : BackupModule
```

`mediaStore == null` 时**行为与改动前完全一致**（同样的导出/导入代码路径、同样的
`BackupResult` 计数）。接线在 `:app` 的 `AppModule.provideBackupModule`（主会话 task-7 范围），
媒体存储由 `EncryptedMediaStore(filesDir/我的人生, FileCrypto)` 提供。

`MediaStore` 侧只用到 ADR-052 §3 的既有方法，外加 media-core 在 §3 缺口上补充的
`restore(id, meta, input, thumbnailPng): StoredMedia?`（带默认实现，默认返回 null）。

### 2. 导出：解密原文 + 清单（仅当有媒体时）

**触发条件**：`mediaStore != null` **且** `list()` 非空。没有媒体时**不写清单、不进新路径** ——
否则空档案也会凭空多一个 `设置/media-manifest.json`，改变旧 zip 的结构。

有媒体时导出流程：

1. 先让 router 把完整档案（含密文媒体）导出到 `cacheDir/backup-scratch/export-staging.zip`；
   router 的冻结行为不变，第一遍产物一定是完整且自洽的；
2. `MediaArchiveExporter` 重写这份 zip 到用户选定的目标流：
   - 非媒体条目**逐条原样复制**（条目名、字节都不变）；
   - 丢弃 `数字遗产/媒体/**` 与 `设置/media-manifest.json`（旧的密文与旧清单）；
   - 为每条 `StoredMedia` 写入解密原文与可选缩略图；
   - 最后写清单。

zip 内路径（`ROOT_NAME` 前缀由 `StorageLayout.ROOT_NAME` 拼出，**代码里不出现中文常量**）：

```
我的人生/数字遗产/媒体/<owner>/<id>.<ext>          解密原文（原始字节）
我人生/数字遗产/媒体/<owner>/<id>.thumb.png        解密后的 PNG 缩略图（有才写）
我人生/设置/media-manifest.json                    清单
我人生/...                                          其余条目，与旧备份完全一致
```

- `<owner>` = `_self` 或 person_id（与 `EncryptedMediaStore.SELF_DIR_NAME` 一致）；
- `<ext>` 取原文件名后缀（小写、`^[a-z0-9]{1,8}$`），不可用时按 MIME 映射，最后兜底 `bin`；
- 缩略图与清单放在固定路径，和 ADR-040 的 `设置/backup-settings.json` 风格一致：
  它们都是「档案根目录内的普通文件条目」，因此**旧客户端**导入时只会把它们当普通文件
  重新加密，不会崩，也不会破坏数据。

**清单 schema**（`MediaManifest`，纯 JVM + `org.json`）：

```json
{
  "version": 1,
  "media": [
    {
      "id": "0123456789abcdef0123456789abcdef",
      "kind": "VIDEO",
      "fileName": "trip.mp4",
      "mimeType": "video/mp4",
      "personId": "person_123",
      "sourceDate": "2019-08-07T06:05:04Z",
      "durationMs": 12345,
      "width": 1920,
      "height": 1080,
      "path": "数字遗产/媒体/person_123/0123456789abcdef0123456789abcdef.mp4",
      "thumbnailPath": "数字遗产/媒体/person_123/0123456789abcdef0123456789abcdef.thumb.png"
    }
  ]
}
```

- `id`：32 位小写 hex（`MediaStore.MEDIA_ID_PATTERN` 同规则）；
- `kind`：`IMAGE` / `VIDEO` / `AUDIO`（枚举 name）；
- `sourceDate`：ISO-8601 UTC 文本（`Instant.toString()`），未知写 `null`；
- `personId`：`null` = 自己（`_self`）；
- `path` / `thumbnailPath`：**档案根相对**路径（不带 `我的人生/`），与 `设置/backup-settings.json`
  的约定一致；zip 条目名 = `我的人生/` + 该路径；
- 可选字段缺失、未知字段、更高 `version` 一律忽略（向前兼容，同 `SettingsSnapshotCodec`）。

导出计数：`fileCount` / `bytes` 由导出器按**实际写入**统计（含清单本身），比旧实现「按目录
文件数统计」更准确；无媒体路径仍沿用旧统计口径。

### 3. 导入：按清单重建，恢复 `sourceDate`，id 保持不变

**唯一的门**：zip 内存在 `我人生/设置/media-manifest.json`。没有清单 = 旧备份 = 走原来那行
`router.importFrom(src)`，**行为一字未变**（也就不会去碰任何媒体逻辑）。

有清单时：

1. `MediaArchiveImporter.readManifest` 读出清单，`decodeLenient` 解析（逐条校验，坏条目丢弃，
   一条坏数据不能拖垮整个媒体库）；
2. `stripMedia` 把除 `数字遗产/媒体/**` 与清单之外的所有条目复制成 `cacheDir/.../import-stripped.zip`；
   **必须先剥掉媒体条目**——否则 router 会把已解密的明文再当密文加密一遍，产生读不出来的垃圾文件；
3. `router.importFrom(stripped)` 导入记录（日记、人物、图片、附件、设置快照等），完全走旧路径；
4. `restore` 按清单逐条把解密的原文喂给 `MediaStore.restore(id, meta, input, thumbnailPng)`：
   - **id 保持不变**，因此正文里的 `meteor://media/<id>` 引用**不需要改写**；
   - `MediaMetaInput` 带上 `kind / fileName / mimeType / personId / sourceDate / durationMs / width / height`，
     **`sourceDate` 被恢复**，相册「按源数据排序」在换机后依然正确；
   - 缩略图按清单的 `thumbnailPath` 取字节一并传入；缺失或损坏就传 null（懒生成兜底）；
   - 目标已存在同 id 的条目则跳过（重复导入幂等）；
   - 单条失败（缺文件、读失败）只计 `skipped`，不中断其余条目。

**导入顺序**：先记录后媒体。正文先落盘，媒体随后补齐；中途失败时记录仍在，只是部分媒体
缺失，比反过来更容易排查。

### 4. id 不变与「重新加密」的边界

- 媒体：走 `restore`（指定 id）→ **id 不变** → 正文引用不变；
- 若某个 `MediaStore` 实现不覆盖 `restore`（默认实现返回 null），导入会**兜底 `save()`**：
  媒体本体仍在，但 id 会变，正文里的 `meteor://media/<id>` 会指向失效 id。此时
  `RestoreStats.renamed` 会计数，调用方/日志可据此提示。`EncryptedMediaStore` 覆盖了该默认实现，
  所以本应用**不会**触发这条兜底。
- 无论哪条路径，**落盘都用本机密钥重新加密**（`restore` 内部同样是分块重加密），
  与 ADR-004「明文备份、导入重新加密」的语义一致。

与**旧图片**恢复语义对照（本次已核实）：`PrivateStorageRouter` 对
`数字遗产/图片/<owner>/<uuid>.enc` 导出解密原文、导入时**写回同一相对路径**，token 与
`meteor://image/<token>` 引用都不变。媒体用 `restore(id)` 达成同样的「引用不变」效果，
两种媒体因此在恢复语义上一致。

### 5. 兼容性

| 场景 | 行为 |
| --- | --- |
| 旧备份（无媒体、无清单）+ 新代码 | 完全走旧路径，逐字节不变 |
| 新备份（有媒体）+ 新代码 | 记录 + 媒体（id 与 sourceDate 均恢复） |
| 新备份（无媒体）+ 新代码 | **不写清单**，与旧备份结构一致 |
| 新备份（有媒体）+ 旧客户端 | 旧 router 把解密原文/缩略图/清单当普通文件重新加密，不崩不丢记录；旧客户端不识别 `设置/media-manifest.json`，会留一个无法识别的文件（同 ADR-040 的已知限制）；媒体在旧版本不可见，升级后由清单恢复 |
| 清单损坏/部分非法 | `decodeLenient` 丢坏条目，其余照常恢复；整体读不出则视为「无清单→旧路径」 |

### 6. 内存与临时文件

大视频不进内存：导出走 `MediaStore.copyDecrypted(id, zip)` 流式写入，导入走
`MediaStore.restore(..., InputStream)` 流式读。但 zip 的随机访问需要文件，所以导入先把
用户选的 Uri 复制到 `cacheDir/backup-scratch/import-source.zip`（导出也借用同目录暂存第一遍产物）；
这些临时文件在 `finally` 里删除。代价是**导入期间需要约 2 倍档案大小的临时空间**
（source + stripped），这是 zip 随机访问的固有代价，已在报告里列为遗留风险。

读取上限（防压缩炸弹）：清单 16 MiB、单张缩略图 4 MiB、缩略图合计 64 MiB，全部有界读取。

## R1 四问

1. **属于哪个模块？** `:core-backup`（新增 `MediaManifest` / `MediaArchiveExporter` /
   `MediaArchiveImporter`，`PlaintextBackupModule` 增量）。接线在 `:app`。
2. **依赖谁？谁依赖它？** 仍只有 `core-backup → core-storage`、`core-backup → core-crypto`
   （`MediaStore` 与 `StorageLayout.MEDIA` 都在 `core-storage` 已公开的类型里）。**不新增依赖边**，
   `tools/check-module-graph.sh` 无需改。
3. **对外接口是什么？** `BackupModule` 不变；`PlaintextBackupModule` 多一个默认 null 的可选参数；
   新增三个类型（`MediaManifest` / `MediaArchiveExporter` / `MediaArchiveImporter`）与
   `MediaManifestEntry`，均为新增而非改签名。
4. **数据格式变更？** zip 内新增 `数字遗产/媒体/`（解密原文 + 缩略图）与
   `设置/media-manifest.json` 两类条目；旧条目路径与格式不变；清单带 `version`、
   缺省/未知字段兼容；旧备份仍可导入。

## 后果

- 换机恢复后视频/音频/新媒体图片都在，`sourceDate` 保留，相册两种排序都正确；
- 明文备份 zip 现在包含媒体的**完整明文原文**（视频、音频、照片），体积与旧备份不可同日而语，
  安全提示与「妥善保管备份文件」的告警需按 ADR-052 的措辞保持一致（主智能体在发版说明里同步）；
- 无媒体的用户导出行为没有任何变化，包括文件数与字节数；
- `SECURITY.md` 若声称「媒体仅以密文存在、不进入明文备份」，需在发版时改为
  「媒体在档案内以分块 AES-GCM 密文保存，明文备份会解密导出原文及元数据清单」。
