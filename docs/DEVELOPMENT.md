# Meteor 开发手册

本文是 Meteor 的**唯一规范文档**。代码注释里出现的 `Meteor archive §N`、`档案 §N`、
`archive R1/R2` 全部指向本文的对应章节。若注释与本文冲突，以本文为准，并修正注释。

面向读者的分层：

| 你想做什么 | 读哪几节 |
|---|---|
| 第一次跑起来 / 提交代码 | §1、§2、§17、§18 |
| 新增一个功能 | §3、§8、§13、§14 |
| 改动磁盘格式 | §4、§5、§6、§7 |
| 改 UI / 动效 | §15 |
| 改 AI | §9、§16、`docs/AI.md` |

> 本文替代此前散落在各处的架构说明。历史决策仍在 [`docs/adr/`](adr/)，
> 但**ADR 是"当时为什么这么选"，本文是"现在必须怎么做"**。两者冲突时以本文为准。

---

## §1 项目定位与不变量

Meteor 是本地优先的个人记忆档案。以下不变量**任何改动都不得破坏**：

1. **没有服务端**。没有账号、没有自建后端、没有埋点、没有崩溃上报。
2. **档案内容静态加密**。正文 AES-256-GCM，密钥由 Android Keystore 保管、不可导出。
3. **密钥绑定设备**。换机后旧密钥不可恢复——这是设计，不是缺陷；换机唯一路径是明文备份（§7）。
4. **AI 默认关闭**，且只有用户显式开启并同意后才联网（`docs/AI.md`）。
5. **`allowBackup=false`**，不参与系统云备份。
6. **面向用户的文案一律中文**，且全部走 `strings.xml`（§17.1）。

改动若触及以上任一条，必须先写 ADR 并更新本文。

---

## §2 环境与验证闭环

### §2.1 本仓库不含 Gradle Wrapper

`gradle/` 下只有 `libs.versions.toml`，**没有** `gradlew`、`gradlew.bat`、`gradle/wrapper/`。
这是有意的：CI 通过 `gradle/actions/setup-gradle` 注入固定版本。

- CI 使用的 Gradle 版本：**8.11.1**（`.github/workflows/ci.yml`、`codeql.yml`、`release.yml` 三处一致）。
- AGP 8.9.3 的最低要求正是 Gradle 8.11.1，升级 AGP 时必须同步核对三个 workflow。
- 本地想构建的话，自行安装 ≥8.11.1 的 Gradle 并直接执行 `gradle <task>`。

### §2.2 验证以 CI 为准

**合并前的唯一验收标准是 GitHub Actions 绿灯。**

```bash
gh run list --limit 5
gh run view <run-id> --log-failed
```

- CI 未绿**不允许**声称改动完成。
- 本地构建只是开发期辅助手段，不能替代 CI 结论。
- 维护者本机（JDK 17 / 仅 android-35）与 CI 环境不一致，因此**在那台机器上不启动 Gradle**；
  这条是个人环境限制，不是仓库级规范，其他开发者可自由本地构建。

### §2.3 CI 做什么

`CI` 工作流两个 job，串行：

1. `module-graph` — 只跑 `tools/check-module-graph.sh`，不启动 Gradle。校验 §3 的依赖边。
2. `build` — 一条 Gradle 命令：

```bash
gradle :app:assembleDebug :app:assembleRelease testDebugUnitTest :app:lintDebug --stacktrace --no-daemon
```

`:app:assembleRelease` **必须在 CI 里跑**：release 变体开了 R8 + 资源压缩，只在发 tag 时
才第一次执行收缩规则的话，规则写错会表现为"发版失败"而不是"PR 失败"。

`CodeQL` 工作流用同一 Gradle 版本做手动构建追踪。`Release` 工作流只在推 `v*` 标签时触发（§18）。

---

## §3 模块图与依赖方向（R1）

### §3.1 允许的依赖边

**这张表是唯一的真相来源**，由 `tools/check-module-graph.sh` 在 CI 第一步强制。
新增任何一条边 = 改架构 = 先写 ADR 并更新本节。

```
app                 → feature-settings, feature-calendar, feature-editor, feature-people,
                      feature-gallery, feature-media, feature-question, feature-ai,
                      core-backup, core-storage, core-crypto, core-time, core-notify,
                      core-log, core-index
feature-settings    → core-crypto, core-backup
feature-calendar    → core-storage
feature-editor      → core-storage, feature-media
feature-people      → core-storage, feature-editor, feature-media
feature-question    → feature-editor
feature-ai          → core-storage
feature-media       → core-storage
feature-gallery     → feature-media
core-backup         → core-storage, core-crypto
core-index          → core-storage
core-storage        → core-crypto, core-time
core-crypto         → （叶子）
core-time           → （叶子）
core-notify         → （叶子）
core-log            → （叶子）
```

### §3.2 准入四问

新增任何功能前，先书面回答；答不上来就不写代码：

1. 属于哪个模块？新增模块还是并入现有模块？
2. 依赖谁？谁依赖它？是否单向？是否成环？
3. 对外接口是什么？是否足够窄？
4. 是否有数据格式变更？是否向后兼容？

**目标不是"每个模块都能被整体摘掉"，而是"单个功能的改动不要把整个应用弄坏"。**

判据：依赖单向、无环；跨模块只走窄接口；不出现一个 feature 直接摸另一个 feature 的内部实现。

### §3.3 已知的边界瑕疵

这些是**现存**问题，改动相关代码时不要扩大它们：

- `feature-media` 事实上是共享 UI 工具包（`MediaThumb`、`VideoPlayerCard`、
  `AudioPlayerCard`、`MediaGlyph` 被 `feature-gallery`/`feature-editor`/`feature-people`
  共同依赖），但仍叫 `feature-*`。它的 Composable 签名改动会同时影响三个模块。
- `feature-people` 直接 import `feature-editor` 的 `MarkdownPreview`/`MediaBlockState`，
  这是渲染实现细节而非窄接口。
- `feature-question` 同样为了「回答像记日记一样有编辑 / 预览」而 import `feature-editor`
  的 `MarkdownField`/`MarkdownPreview`，性质与上一条相同，是既有瑕疵的又一例而非新问题。
- `feature-settings` 在 `build.gradle.kts` 里以 `api(...)` 声明了 `core-crypto` 与
  `core-backup`，但模块内**没有任何** `AppLock`/`BackupModule`/`FileCrypto` 类型引用
  （它只存了一个 `appLockEnabled: Boolean` 偏好）。这两条边是声明了却没用的，
  删掉它们是安全的（`check-module-graph.sh` 只校验"用到的边必须已声明"，
  不校验"声明的边必须被用到"），但属于改架构，要单独一次提交。
- `:app` 承担了约三分之一的 Kotlin 代码量，其中 `ui/life/ChineseLunarCalendar.kt` 与
  `ui/life/LunarYearTable.kt` 是纯历法算法，按职责应属 `core-time`。

### §3.4 已废止：R3「模块必须可整体移除」

不再要求任何模块能被摘掉，CI 也不验证。为它服务的 `-Pmeteor.excludeModules` 开关、
条件源码集与替身实现已全部删除，模块一律无条件参与构建。将来若确实需要按需裁剪的构建，
另写 ADR 重新引入。

---

## §4 档案布局

物理布局由 `StorageLayout`（`core-storage`）定义，纯路径运算、无 IO。

```
filesDir/
├── 我的人生/                          ← StorageLayout.ROOT_NAME
│   ├── 记忆档案/                      ← ARCHIVE
│   │   ├── 自己/                      ← SELF
│   │   │   ├── profile.md
│   │   │   └── birth_profile.bin      ← 出生日期密文（ADR-040）
│   │   └── 人际关系/<person_id>/       ← RELATIONS
│   │       └── profile.md
│   ├── 时光轴/心情/                    ← TIMELINE / MOOD
│   │   ├── <yyyy-MM-dd>.md            ← 当天心情
│   │   └── 日记/<yyyy>/<yyyy-MM-dd>.md ← 日记正文
│   ├── 每日一问/<id>.bin              ← QUESTIONS（ADR-068，加密 JSON，非记录）
│   ├── 数字遗产/                      ← LEGACY
│   │   ├── 聊天记录/<person_id>/<yyyy-MM-dd>.md
│   │   ├── 图片/<owner>/<uuid>.enc     ← 编辑器插图；v3 分块帧（ADR-067），旧整文件密文仍可读
│   │   ├── 附件/<owner>/<id>.{data,meta,text}.enc
│   │   ├── 媒体/<owner>/<id>.{data,meta,thumb}.enc  ← ADR-052 起的新媒体
│   │   ├── 数字人格/<person_id>/persona.md
│   │   └── AI对话/                     ← sessions/<id>.bin、旧版 <scope>.bin
│   └── 设置/                          ← SETTINGS
│       ├── backup-settings.json       ← 加密设置快照（ADR-040）
│       └── media-manifest.json        ← 仅存在于导出 zip 内（§7）
├── logs/meteor-<yyyy-MM-dd>.log
├── media_key.bin                      ← 媒体数据密钥的 Keystore 包裹体（ADR-063）
└── bak/meteor-<yyyyMMdd-HHmmss>.zip   ← 滚动快照
```

### §4.1 路径安全

- `person_id` 必须是 `^[A-Za-z0-9_-]{1,64}$`，否则 `require` 失败。这是防目录穿越的唯一关口，
  `StorageLayout.requirePerson` 是唯一实现。
- 媒体/附件 id 必须是 32 位小写十六进制。
- `owner` 目录名为 `_self` 表示不属于任何人物。

### §4.2 为什么目录用随机 id

ADR-014：冻结的 `StorageRouter.route(personId, type, date)` 没有"显示名"参数，
所以人物目录只能用 `person_id`。`display_name` 只存在于密文里——这是唯一不把通讯录
泄漏到文件系统的放法。

---

## §5 数据格式

### §5.1 记录文件与 frontmatter

记录文件 = `---` 围栏 + **恰好五个明文键** + `---` 围栏 + payload。实现：`Frontmatter`、`DocumentCodec`。

```
---
meteor-enc: aes-gcm-v1
person_id: 3f2a9c1e8b7d4a5f6c0e9d8b7a6f5e4d
type: diary
timestamp: 2025-03-14T13:30:07Z
source: user
---
<base64 的 AES-GCM 密文>
```

键：

| 键 | 必填 | 说明 |
|---|---|---|
| `meteor-enc` | 是 | `aes-gcm-v1`（应用内）或 `plaintext`（备份 zip 内） |
| `person_id` | 否 | 空串解析为 `null`；非空必须是合法 id |
| `type` | 是 | `profile` / `chat_log` / `diary` / `persona` / `mood` |
| `timestamp` | 是 | `ISO_OFFSET_DATE_TIME`，**归一化到 UTC** |
| `source` | 是 | `user` / `meteor-ai` |

**除这五个键以外，任何字段都不得进入 frontmatter。** 显示名、关系、正文一律进密文。

- `Frontmatter.split` 对"不是我们的文件"返回 `null`（缺围栏、缺必填键、未知 scheme、
  时间戳无法解析），调用方把 `null` 当作"不是我们的"处理，**不做修复尝试**。
- `type`/`source` 存的是 `wire` 值（英文），不是枚举名——枚举名会被 R8 重命名（§8.3）。
- `DocumentCodec.encode` **每次写入都重新打时间水印**，所以相同正文不会产生相同密文。

### §5.2 不带 frontmatter 的二进制

图片、媒体、附件、AI 会话、出生日期、设置快照都**没有** frontmatter——它们的归属由目录决定，
文件名是随机 token，路径本身不泄漏内容。

**图片**（`ImageStore`，`meteor://image/<token>`，ADR-067）：
`数字遗产/图片/<owner>/<uuid>.enc`，**v3 分块帧**（magic `0x494D4147`（`"IMAG"`）），
无元数据侧车。读取先嗅探前 4 字节：`IMAG` 走分块帧并按版本选密钥，否则按旧版整文件
AES-GCM 密文用 Keystore 主密钥解。写入用同目录 `.part` 临时文件后原子改名。

**媒体**（`MediaStore`，ADR-052，`meteor://media/<id>`）：
`数字遗产/媒体/<owner>/` 下三件套

| 文件 | 内容 |
|---|---|
| `<id>.data.enc` | 分块加密的原始字节，magic `0x4D454449`（`"MEDI"`） |
| `<id>.meta.enc` | 元数据，magic `0x4D4D4554`（`"MMET"`），version 2 |
| `<id>.thumb.enc` | 可选 PNG 缩略图 |

写入用 `.data.part` / `.meta.part` / `.thumb.part` 临时文件后 rename。

**附件**（`AttachmentStore`，`meteor://file/<id>`，ADR-067）：
`数字遗产/附件/<owner>/` 下 `<id>.data.enc`（magic `"MATT"`，**v3 分块帧**）、
`<id>.meta.enc`（magic `"MAMD"`）、`<id>.text.enc`（导入时抽取的文本，上限 2 MiB）。

**载荷与派生文件的密钥边界**（ADR-067）：三类载荷（`data.enc` 与图片 `.enc`）都用
**媒体数据密钥** + v3 分块帧；**派生小文件**（`.meta.enc`、`.thumb.enc`、`.text.enc`）
与全部文本内容继续用 **Keystore 主密钥**。理由见 [ADR-067 §三.2](adr/ADR-067-unified-binary-payload-encryption.md)。

### §5.3 分块加密帧

`ChunkedEncryptedFile`（`internal`，但格式必须稳定）：

```
int magic | int version
repeat: int encryptedSize | AES-GCM(int chunkIndex | chunkBytes)
int 0                     ← 结束标记
```

| version | 分块大小 | 密钥 | 写入方 |
|---|---|---|---|
| 1 | 1 MiB | Keystore 主密钥 | 旧附件（只读） |
| 2 | 10 MiB | Keystore 主密钥 | 旧媒体（只读） |
| 3 | 10 MiB | 媒体二级密钥（§6.1） | 媒体、附件、图片 |

`MIN_ENCRYPTED_CHUNK_SIZE = 29`（1 字节 nonce 长度 + 12 nonce + 16 tag）。
读取时必须同时支持 v1/v2/v3——**新增版本号可以，改旧版本语义不行**。

**读缓冲按需增长**（ADR-067）：逻辑分块大小由版本决定，但写入侧缓冲从 256 KiB 起翻倍，
最多到逻辑大小。小载荷（一张插图）不会预留整块 10 MiB，而大文件的分块边界与固定缓冲完全一致。

### §5.4 密文信封

`AesGcmFileCrypto`：`[ivLength:1][iv:12][ciphertext‖tag]`。GCM tag 128 位，
读取时接受 12–16 字节 nonce。`transformation = "AES/GCM/NoPadding"`。

### §5.5 其他格式

| 内容 | 位置 | 格式 |
|---|---|---|
| 人物档案正文 | `profile.md` 密文内 | `name:` / `relation:` / `note:` / `tags:` 四行，`---` 后是 Markdown 正文 |
| 心情 | `时光轴/心情/<date>.md` | 一行一个 code，最多 3 个 |
| 时间水印 | 正文密文内 | `<!-- meteor-sig: <ISO_OFFSET_DATE_TIME> -->` |
| AI 会话 | `AI对话/sessions/<id>.bin` | 加密 JSON，`{"version":2,...}` |
| AI 旧会话 | `AI对话/<scope>.bin` | `v1 \t u\|a \t <epochMillis> \t <Base64(UTF-8)>` |
| 每日一问 | `每日一问/<id>.bin` | 加密 JSON，`{"version":1,"id","text","createdAt","askedOn","reminders","answers"}`（ADR-068 / ADR-070） |
| 索引库 | `databases/meteor_index.db` | §9.3 |

**每日一问（`QuestionStore`，ADR-068）**：一问题一文件，随机 32 位十六进制 id，
用 **Keystore 主密钥**（不是媒体数据密钥——它是几百字节文本，没有并行收益）。
`reminders` 只存**待触发**的时刻：闹钟投递即移除，通知按钮追加新的。`.bin` 后缀让它
不进 `RecordScanner`（只认 `.md`），所以问题与回答**不进搜索索引，也不成为 AI 档案证据**。

`askedOn` 是问题**所属的日期**（ADR-070）：日历日卡片按这一天决定打开已有问题还是
带日期的新建（可补写过去某天），与 `createdAt`（真正写下的时刻）分开。它是 JSON 里
的可选字段；旧文件没有它，读取时按 `createdAt` 在设备时区派生，写入时补上，**格式版本
仍是 1**。同一天可以有多条问题：日历入口取最后创建的一条，其余在侧边栏的总览里。

---

## §6 密钥与凭据

### §6.1 密钥层级

```
Android Keystore（不可导出）
└── meteor_file_key（AES-256-GCM）      ← KeystoreKeyStore.DEFAULT_ALIAS
    ├── 直接加密：记录正文、AI 会话、出生日期、设置快照
    ├── 直接加密：媒体/附件的派生小文件（.meta.enc、.thumb.enc、.text.enc）
    └── 包裹 media_key.bin（32 字节随机密钥）  ← MediaKeyStore
        └── 加密：媒体、附件、图片的载荷（v3 分块帧，ADR-067）
```

二级媒体密钥（ADR-063）的意义：二进制载荷量大、分块多，用非 Keystore 密钥可并行加解密，
避免每次分块都走 Keystore 往返。[ADR-067](adr/ADR-067-unified-binary-payload-encryption.md)
把它的适用范围从「仅媒体」扩展到**全部二进制载荷**（媒体、附件、旧版图片）。

**派生小文件与文本内容不走 MK**：边车和缩略图很小，并行无收益；更重要的是，相册要列条目
就得先解边车，若边车也用 MK，`media_key.bin` 损坏会让整个相册连条目都列不出来。

**`MediaKeyStore.key()` 遇到损坏的密钥文件会抛 `IllegalStateException`，绝不重新生成**——
重新生成会让所有既有载荷永久无法解密。

### §6.2 命名空间隔离

不同用途的凭据必须放在**不同的** `EncryptedSharedPreferences` 命名空间，
否则清理一处会误删另一处：

| 命名空间 | 常量 | 用途 |
|---|---|---|
| `meteor_ai_credentials_v2` | `PrefsStringStore.DEFAULT_PREFS_NAME` | AI API Key（当前） |
| `meteor_ai_credentials` | 旧版 | AI API Key（仅迁移用） |
| `meteor_app_lock` | `EncryptedPrefsAppLock.PREFS_NAME` | 应用锁开关 `app_lock_enabled` |

新增凭据类型必须新建命名空间，不得复用上面任何一个。

### §6.3 线程与错误约定

| API | 线程 | 失败行为 |
|---|---|---|
| `FileCrypto.encrypt/decrypt` | 调用方决定，阻塞 | `decrypt` 抛 `CryptoException`，绝不返回部分数据 |
| `KeyStoreSecretStore.get` | 任意 | 返回 `null`，**从不抛** |
| `AppLock.unlock` | **必须主线程** | 返回 `false`，绝不静默放行 |
| `MediaKeyStore.key` | 任意，线程安全 | 损坏时抛异常，绝不重生成 |
| 媒体/附件/图片的 `copyDecrypted` | 调用方决定，阻塞 | 找不到载荷时抛异常；`load` 类方法返回 `null` |

**应用锁是身份门禁，不是加密边界**：文件密钥不绑定生物识别，应用锁只决定谁能看到界面。
没有可用生物识别时回退到锁屏 PIN/图案/密码；两者都没有时应用锁无法开启。

---

## §7 备份

### §7.1 为什么是明文

密钥绑定设备、不可导出，所以跨设备恢复只能靠明文 zip。UI **必须**在导出前警告
（`backup_warning_body`），这是硬要求。

### §7.2 冻结契约

`BackupModule` 是冻结接口（§8）。三个 suspend 方法都在内部切到 `Dispatchers.IO`，
**返回 `Failure` 而不是抛异常**。`listBackups()` 是阻塞的非 suspend 方法，调用方自己挪到 IO。

### §7.3 导出 zip 结构

```
我的人生/<archive-relative path>              ← StorageRouter 写的记录，plaintext scheme
我的人生/数字遗产/媒体/<owner>/<id>.<ext>       ← 媒体解密为明文（有媒体时）
我的人生/数字遗产/媒体/<owner>/<id>.thumb.png
我的人生/设置/media-manifest.json              ← 有媒体时才写
```

**兼容性判据**：只有当 zip 内存在**精确名为** `设置/media-manifest.json` 的条目时，
才走媒体感知的恢复路径；否则完全走旧路径。没有媒体的旧备份结构一字不变。

### §7.4 恢复顺序

**先恢复记录，再恢复媒体**（从剥掉媒体的副本恢复记录）。顺序反了的话，冻结的
`StorageRouter` 会把解密后的 payload 重新加密一遍。

### §7.5 清单格式

```json
{"version": 1, "media": [{"id", "kind", "fileName", "mimeType", "personId",
                          "sourceDate", "durationMs", "width", "height",
                          "path", "thumbnailPath"}]}
```

`path` / `thumbnailPath` 是档案根相对路径，**必须以 `数字遗产/媒体/` 开头**，
否则拒绝（防穿越）。恢复上限：清单 16 MiB、单缩略图 4 MiB、缩略图总量 64 MiB。

### §7.6 不进备份的东西

API Key、应用锁、禁止截屏、AI 同意状态。它们要么是本机凭据，要么是设备绑定行为。

### §7.7 滚动快照

`filesDir/bak/meteor-yyyyMMdd-HHmmss.zip`，保留条数由 `BackupRetentionProvider` 提供
（`core-backup` 不能依赖 `feature-settings`，所以用注入的函数式接口）。

---

## §8 冻结接口（R2）

### §8.1 冻结清单

以下**签名不得修改**。新增能力只能：新增模块，或新增**带默认实现**的方法。改签名必须先写 ADR。

| 接口 | 文件 | 标记行 |
|---|---|---|
| `FileCrypto` | `core/crypto/.../CryptoContracts.kt` | §8 标记 |
| `AppLock` | `core/crypto/.../CryptoContracts.kt` | §8 标记 |
| `StorageRouter` | `core/storage/.../StorageRouter.kt:8` | §8 标记 |
| `BackupModule` | `core/backup/.../BackupModule.kt:26` | §8 标记 |
| `IndexModule` | `core/index/.../IndexModule.kt:9` | §8 标记 |
| `EditorModule` | `feature/editor/.../EditorModule.kt:8` | §8 标记 |
| `AiModule` | `feature/ai/.../AiModule.kt:8` | §8 标记 |

> **待办**：`BackupModule.kt:26` 的注释写的是 `§7`，与其余六处的 `§8` 不一致。
> 本节是权威编号，代码注释应统一为 `§8`。

### §8.2 合规的扩展方式

已经这样用过的先例：

- `AppLock.isAuthenticationAvailable()` 与 `AppLock.lock()` —— 带默认实现的新增方法。
- `AiModule.chatStreaming` —— 带默认体。
- `IndexUpdater` —— 新增独立接口，而非改 `IndexModule`。
- `MediaStore.findByHash` / `restore` / `openDecrypted` —— 带默认实现的新增方法。
- `ImageStore.copyDecrypted` / `restore`、`AttachmentStore.openDecrypted` —— 带默认实现的
  新增方法（ADR-067），七个冻结接口的签名一个字符未动。

### §8.3 R8 与持久化枚举名

**枚举名是数据格式，不是实现细节。** 以下枚举按 `valueOf(name)` 字符串读写磁盘：

`MediaKind`、`SearchBackend`、`DarkMode`、`FontScale`、`MediaPickerMode`。

因此 `app/proguard-rules.pro` **必须**保留：

```proguard
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    <fields>;
}
```

删掉这条 = 升级后所有旧档案与旧偏好静默读不回来。新增任何"按名字存盘"的枚举，
都必须确认这条规则仍在。

---

## §9 索引

### §9.1 定位

索引是**派生数据**，不是真相来源。删掉数据库永远不会丢数据，`rebuild()` 会重建。

`MeteorIndex` 通过 `RecordScanner` 拿 `Document`，**从不直接读文件、从不碰密钥**。

### §9.2 重建策略

- 重建在解锁后由 `IndexSearchService` 触发，串行化在 `Mutex` 上。
- 连续失败采用指数退避（`consecutiveFailures` / `blockedUntilMillis`）。
- `upsert`/`remove` 有 `Mutex`；`search`/`overview` 没有。

### §9.3 库格式

`meteor_index.db`，schema `VERSION = 2`。表：`records`、`meta`、`records_fts`（虚拟表）。
升级**和降级**都直接 drop 重建。

- `record_id` = `SHA-256(sourcePath)` 的前 32 位十六进制，稳定且幂等。
- FTS5 用 `unicode61`；FTS4 用 `notindexed=record_id, tokenize=unicode61`；
  实际后端记在 `meta` 表的 `backend` 键里。
- **排序在 Kotlin 里算**（`TextMatcher.countMatches`），不在 SQL 里，
  所以 FTS5/FTS4/LIKE 三种后端结果顺序一致。同分按时间倒序。

### §9.4 CJK 分词

`CjkTokenizer`：CJK 连续段切成**重叠二元组**，单字段保持整字，拉丁与数字小写，
其余字符作分隔。CJK 区间 `U+4E00..U+9FFF`、`U+3400..U+4DBF`。

**索引与查询必须用同一个函数**，否则互相搜不到。

**单字回退**：单个 CJK 字符无法被二元组索引命中，强制走 LIKE 路径。

### §9.5 心情的可搜索性

心情存的是稳定 code（`happy`），用户看到的是「开心」。所以索引时要把 code 替换成
本地化标签（`searchableDocument`），**只替换索引副本，档案记录本身永不改动**。

---

## §10 图片与媒体处理上限

### §10.1 图片压缩

`ImageCompressor`（`feature-editor`）：最长边 **1568**，JPEG 质量 **80**。
AI 图片路径与编辑器插图共用同一组上限，所以同一张照片在任何地方引用代价相同。

### §10.2 缩略图

`MediaBitmaps.THUMBNAIL_MAX_EDGE` ≤ 512 px（ADR-052 §4）。缩略图是 PNG 侧车，
导入时生成并加密存储；`MediaThumb` 用 16 MiB 按字节计量的 LRU 缓存，
**不要去掉这个缓存**——没有它每次滚动都会重新解密一屏文件。

### §10.3 禁止把大文件读进内存

- `MediaStore.load(id)` 读整个文件进内存，**只允许对图片调用**。
- 视频/音频必须走 `copyDecrypted(id, output)` 或 `openDecrypted(id, offset)`。
- 播放走 `MediaStreamDataSource`（流式解密），不落临时文件。

### §10.4 媒体导入

- 分块 10 MiB，支持并行加解密。
- 按内容哈希全局去重（`findByHash`），同一文件只存一份。
- 大文件先写占位卡片，不阻塞编辑。
- 文件格式升级后，旧客户端读新版媒体会**明确报错**，不会静默出错。
- 附件与图片的载荷用**同一套**分块帧与密钥（ADR-067），所以大文件导入的代价一致；
  派生小文件（边车、缩略图、抽取文本）仍是整文件 Keystore 加密。

---

## §11 可替换实现

冻结接口的意义是**内部实现可换、对外接口不变**。`EditorModule` 是范例：
v0.4 交付的是基于纯 Compose 的 Markdown 实现，将来换第三方富文本引擎只需换
`MarkdownEditorModule` 这一个类。

因此：

- 不要在 `:app` 里直接依赖某个 `*Module` 的具体实现类，只依赖接口。
- 具体实现只在 `di/AppModule.kt` 里绑定。
- 新实现必须通过该接口的全部既有测试语义。

---

## §12 依赖注入边界

**Hilt 只出现在 `:app`。** `core-*`、`feature-*` 保持纯 Kotlin 类型，由 `:app` 的
`di/AppModule.kt` 负责组装。

- `core-*`/`feature-*` 里**零** Hilt 注解、零 import。
- 需要"由宿主注入的能力"时，用函数式接口或 CompositionLocal，不要引入 DI 框架。
  先例：`BackupRetentionProvider`（`fun interface`）、`MediaHost`（CompositionLocal）。
- 换实现时只改 `AppModule.kt` 一处，不改整棵树。

### §12.1 组装顺序中真正要紧的约束

1. `SettingsRepository`（来自 `DataStore<Preferences>`）是最底层，多数 provider 依赖它。
2. `FileCrypto` → `MediaKeyStore` → `@MediaKeyCrypto FileCrypto` → `MediaStore`。
   `@MediaKeyCrypto` 限定符存在的唯一理由是让两个 `FileCrypto` 绑定不歧义。
3. `StorageRouter` 喂给 `DiaryQueries`/`MoodQueries`/`PersonQueries`/`ArchiveRecordDeleter`/
   `RecordScanner`/`EditorModule`/`AiModule`/`BackupModule`。
4. `AiModule` 需要存储层全部就绪；`BackupModule` 装饰 `PlaintextBackupModule`。

---

## §13 分层与导航

### §13.1 分层

| 层 | 位置 | 职责 |
|---|---|---|
| 存储 | `core-storage` | 路径、frontmatter、透明加解密、查询接口 |
| 领域查询 | `core-storage` 的 `*Queries` | `DiaryQueries`、`MoodQueries`、`PersonQueries` |
| 索引 | `core-index` | 派生全文索引，只消费 `Document` |
| 备份 | `core-backup` | 明文 zip 导出/导入 |
| UI 组件 | `feature-*` | 纯 Compose，无 DI、无导航、无持久化 |
| 组装 | `:app` | ViewModel、导航、DI、主题、系统集成 |

**ViewModel 只在 `:app`。** feature 模块通过参数与回调与宿主通信。

### §13.2 导航

导航全部在 `app/src/main/kotlin/com/meteor/memory/ui/MeteorNavHost.kt`，路由常量是该文件内的
`private const val`。起始目的地是 `calendar`。

- 参数名由各 ViewModel 的 `ARG_*` 常量定义，**不要手写字符串**。
- 旧深链（`gallery?date=`、`gallery/{token}`、`chats`）保留为**重定向**，只为兼容外部链接；
  内部导航不要指向它们。
- 重定向的 `LaunchedEffect` 必须**以参数为键**（`LaunchedEffect(date)` 而非 `LaunchedEffect(Unit)`），
  否则 destination 被复用时不会重跑。
- **通知不能直接导航**。系统通知可能在 Activity 已死时投递，所以它只写一个待打开的目标
  （`QuestionOpenRequest`，`@Singleton` + `StateFlow`），由导航图就绪后 `consume()` 一次。
  `consume` 是原子的，配置变更不会重放。ADR-068 是现成范例。

### §13.3 状态流约定

- ViewModel 暴露 `StateFlow`，Compose 侧用 `collectAsStateWithLifecycle()`。
- `SavedStateHandle` 只放**需要跨进程死亡存活**的少量状态（如 `homeTab`）。
- 耗时工作（IO、加解密、解析）一律 `withContext(Dispatchers.IO)`；
  **不要在 ViewModel 构造器里做 IO**——构造发生在主线程。
- `RefreshOnResume` 会让每次 `ON_RESUME` 重跑刷新逻辑，被它调用的 `refresh()`
  必须足够便宜或有去重。

---

## §14 扩展点

预留但尚未实现的接入点。实现它们时**不得**改动冻结接口。

| 扩展点 | 位置 | 现状 |
|---|---|---|
| `ExternalBackupMirror` | `core-backup` | 第二个备份目的地。默认实现 `NoExternalBackupMirror` 恒返回 `NotConfigured`；`MirrorResult.Copied` 目前无人返回。 |

新增扩展点必须在本文登记，并说明它为何不能直接加在既有接口上。

---

## §15 主题与动效

### §15.1 主题

`MeteorTheme` 提供两个 `staticCompositionLocalOf`：

- `LocalAnimationsEnabled`（默认 `true`）——**全局动效开关，每个屏幕都必须读它**。
- `LocalDynamicColorEnabled`（默认 `false`）——只有 Material You 壁纸色真正生效时才为 `true`；
  手绘纸张渐变的地方要检查它。

颜色定义在 `MeteorTheme.kt` 的 `MeteorDark` / `MeteorLight`，形状在 `MeteorShapes`。

### §15.2 动效 token

**不要硬编码时长。** 用 `ui/theme/MotionTokens.kt`：

| 语义 | 规格 | 用途 |
|---|---|---|
| `enter()` | 200 ms, Decelerate | 小面板/胶囊/遮罩出现 |
| `exit()` | 150 ms, Accelerate | 对应退场，**永远比进场短** |
| `pageIn()` | 350 ms, EmphasizedDecelerate | 整屏推入 |
| `pageOut()` | 200 ms, Accelerate | 整屏退出 |
| `slide()` | 300 ms, EmphasizedDecelerate | 设置详情页横滑 |
| `state()` | 200 ms, Standard | 留在屏上的控件改高度/颜色/偏移 |
| `item()` | 250 ms, Decelerate | 列表项增删移 |

`MotionTokens` 在 `:app`，feature 模块**不能反向依赖**（§3.1）。
feature 内需要同样的时长时，用同值常量并在注释里说明来源，不要 import。

### §15.3 动效纪律

- 无限动画（spinner、shimmer）必须能"离屏即停"，否则是常驻耗电源。
- 列表用 `Modifier.animateItem()`（LazyItemScope 的成员，**不是可 import 的扩展**）。
- Lazy 列表的 `key` **不得包含下标**——插入一项会让其后所有 key 失效。
- 新增动画先问：它是否受 `LocalAnimationsEnabled` 约束？

---

## §16 AI

功能边界、工具权限、隐私细则见 [`docs/AI.md`](AI.md)。

编码层面的硬约束：

- `AiModule` 冻结（§8）。新增能力走带默认实现的方法或新接口。
- 工具必须在 `TOOL_CAPABILITIES` 显式登记，未登记的名字既不出现在提示里也不执行。
- 写工具受两级开关控制；覆盖日记、改/删人物、删记录必须应用内确认。
- 导出、导入、改 Key、关应用锁**永不开放**给模型。
- 附件抽取文本按**不可信输入**处理。
- API Key 只进 `SecretStore`，不进 DataStore、不进备份、不进日志。

---

## §17 代码规范

### §17.1 文案与本地化

- **代码里零硬编码中文**，全部走 `strings.xml`。
- 默认资源 `values/` 就是中文，新增语言靠 `values-xx/`。
- 模块各自拥有自己的 `strings.xml`；`core-*` 模块**没有资源目录**，
  所以它们不能产生面向用户的文案——需要文案时返回结构化结果或错误码，
  由 `:app` 翻译。`core-backup` 的 `Failure.message` 是诊断串，**不得直接上屏**。
- 例外（不算违规）：磁盘目录名（`我的人生` 等，改了会破坏既有档案）、
  发给模型的 prompt 文本、中文自然语言解析用的正则。

### §17.2 提交与分支

- `main` 始终可构建。功能走 `feat/<name>`。
- 提交信息用中文，一句话说清"做了什么"，例如 `feat(storage): 实现 frontmatter 解析`。
- **一次提交只做一件事。**
- **升级 AGP / Kotlin / Gradle / Compose BOM 必须单独成一次提交**，写明理由并等 CI 绿。
- 禁止一次提交里既加功能又升版本。

### §17.3 版本与依赖

- 所有版本集中在 `gradle/libs.versions.toml`，**禁止在模块里硬编码版本号**。
- 升级前先读目标 AAR 的 `META-INF/com/android/build/gradle/aar-metadata.properties`，
  确认 `minCompileSdk` 与 `minAndroidGradlePluginVersion` 不高于当前配置。
- 当前约束：`compileSdk = 35`。要求 36/37 的依赖（media3 1.10+、core-ktx 1.17+、
  lifecycle 2.11、navigation 2.10、hilt-navigation-compose 1.4、biometric 1.4）
  必须等一次独立的 compileSdk 升级。
- 显式声明你直接使用的东西，不要靠传递依赖编译。

### §17.4 禁止入库

密钥、keystore、`secrets.properties`、API key；构建产物（`build/`、`*.apk`）；
本机路径文件（`local.properties`）。

---

## §18 发布

### §18.1 流程

1. 功能合进 `main`，CI 全绿。
2. **单独一次提交**改 `app/build.gradle.kts` 的 `versionCode` 与 `versionName`。
3. **单独一次提交**写 `docs/releases/<tag>.md`，并同步 `app/src/main/assets/changelog.md`。
4. 推 `main`，确认 CI 绿灯。
5. 打附注标签并推送，触发 `Release`。

```bash
git tag -a v1.6.0 -m "v1.6.0: 一句话概括"
git push origin v1.6.0
```

### §18.2 发布说明

- 必须在 `docs/releases/<tag>.md` 中文手写，含「新增 / 修复 / 变更 / 已知限制」。
- **文件缺失会让 Release 任务直接失败**。
- 禁止用 GitHub 自动生成的英文 PR 列表作为发布说明。
- `Release` 还会交叉校验 `app/src/main/assets/changelog.md` 里有 `## <tag>` 段落（ADR-066）。

### §18.3 Release 工作流还做了什么

校验四个签名 secret 存在 → 解码 keystore → `:app:assembleRelease` →
**核对 APK 签名证书 SHA-256**（必须等于仓库内硬编码的期望值）→ 读发布说明 → 交叉校验更新日志。

---

## §19 API 速查

按模块列出公开 API。签名以源码为准，本节用于快速定位。

### §19.1 core-crypto

```kotlin
interface FileCrypto {                        // FROZEN
    fun encrypt(plaintext: ByteArray): ByteArray
    fun decrypt(ciphertext: ByteArray): ByteArray
}

interface AppLock {                           // FROZEN
    fun isEnabled(): Boolean
    fun setEnabled(enabled: Boolean)
    suspend fun unlock(activity: FragmentActivity): Boolean   // 必须主线程
    fun requireUnlocked(): Boolean
    fun isAuthenticationAvailable(): Boolean = true
    fun lock()
}

class KeystoreKeyStore { fun secretKey(alias: String = DEFAULT_ALIAS): SecretKey }
class MediaKeyStore(file: File, wrappingCrypto: FileCrypto) { fun key(): SecretKey; fun ensureGenerated() }
interface SecretStore { fun get(key: String): String?; fun put(key: String, value: String); fun remove(key: String) }
```

### §19.2 core-storage

```kotlin
interface StorageRouter {                     // FROZEN
    fun route(personId: String?, type: RecordType, date: LocalDate): File
    fun read(file: File): Document
    fun write(file: File, doc: Document)
    suspend fun exportTo(zipUri: Uri)
    suspend fun importFrom(zipUri: Uri)
}

interface MediaStore {                        // 冻结的既有签名 + 带默认值的新增方法
    fun save(input: InputStream, meta: MediaMetaInput, thumbnailPng: ByteArray? = null): StoredMedia
    fun find(id: String): StoredMedia?
    fun findByHash(hash: String): StoredMedia? = null
    fun list(personId: String? = null): List<StoredMedia>
    fun load(id: String): ByteArray?                       // 仅图片可用（§10.3）
    fun copyDecrypted(id: String, output: OutputStream): Long
    fun openDecrypted(id: String, offsetBytes: Long = 0L): InputStream
    fun loadThumbnail(id: String): ByteArray?
    fun delete(id: String): Boolean
    fun restore(id: String, meta: MediaMetaInput, input: InputStream, thumbnailPng: ByteArray? = null): StoredMedia? = null
}

interface ImageStore {                        // 带默认值的新增方法（ADR-067）
    fun save(bytes: ByteArray, personId: String?, at: Instant = Instant.now()): String
    fun load(token: String): ByteArray?
    fun copyDecrypted(token: String, output: OutputStream): Long   // 默认抛 UnsupportedOperationException
    fun list(): List<StoredImage>
    fun restore(token: String, bytes: ByteArray): String? = null
    fun delete(token: String): Boolean
}

interface AttachmentStore {                   // 带默认值的新增方法（ADR-067）
    fun save(input: InputStream, fileName: String, mimeType: String, personId: String?, indexedText: String? = null): StoredAttachment
    fun list(personId: String? = null): List<StoredAttachment>
    fun readIndexedText(id: String): String?
    fun copyDecrypted(id: String, output: OutputStream): Long
    fun openDecrypted(id: String, offsetBytes: Long = 0L): InputStream   // 默认抛 UnsupportedOperationException
    fun delete(id: String): Boolean
}

class AlbumRepository(media: MediaStore, images: ImageStore, attachments: AttachmentStore? = null) {
    fun entries(owner: MediaOwner = MediaOwner.All, kinds: Set<AlbumKind>? = null): List<AlbumEntry>
    fun sorted(entries: List<AlbumEntry>, mode: AlbumSortMode): List<AlbumEntry>
    fun entry(ref: String): AlbumEntry?
    fun loadBytes(ref: String): ByteArray?
    fun copyDecrypted(ref: String, output: OutputStream): Long?
    fun delete(ref: String): Boolean
    fun readFileText(ref: String): String?
}

interface DiaryQueries { fun dates(): List<LocalDate>; fun read(date: LocalDate): Document?; fun save(date: LocalDate, body: String) }
interface MoodQueries  { fun range(from: LocalDate, to: LocalDate): Map<LocalDate, List<Mood>>; fun read(date: LocalDate): List<Mood>; fun save(date: LocalDate, moods: List<Mood>) }
interface PersonQueries { fun list(): List<PersonEntry>; fun read(personId: String): PersonProfile?; fun create(...): String; fun update(...); fun delete(personId: String): PersonDeleteResult }

// 每日一问（ADR-068 / ADR-070）：阻塞式，调用方切 IO。一问题一加密 JSON 文件。
data class StoredAnswer(val id: String, val text: String, val answeredAt: Instant)
data class StoredQuestion(
    val id: String, val text: String, val createdAt: Instant,
    val askedOn: LocalDate = createdAt.atZone(ZoneId.systemDefault()).toLocalDate(), // 归档日
    val reminders: List<Instant> = emptyList(),      // 只存待触发的时刻
    val answers: List<StoredAnswer> = emptyList(),   // 追加式，旧回答永不覆盖
) { val isAnswered: Boolean; val lastAnsweredAt: Instant?
    fun upcomingReminders(now: Instant): List<Instant>; fun nextReminder(now: Instant): Instant? }

interface QuestionStore {
    fun list(): List<StoredQuestion>; fun find(id: String): StoredQuestion?
    fun create(text: String, reminders: List<Instant> = emptyList(), createdAt: Instant = Instant.now(),
               askedOn: LocalDate = createdAt.atZone(ZoneId.systemDefault()).toLocalDate()): StoredQuestion
    fun addAnswer(id: String, text: String, answeredAt: Instant = Instant.now()): StoredQuestion?
    fun deleteAnswer(id: String, answerId: String): StoredQuestion?  // ADR-073，带默认实现的新增方法
    fun updateReminders(id: String, reminders: List<Instant>): StoredQuestion?
    fun delete(id: String): Boolean
    // MAX_QUESTION_CHARS = 500, MAX_ANSWER_CHARS = 20000, MAX_REMINDERS = 8
}
```

`MediaRef` 三种引用：`meteor://media/<id>`、`meteor://image/<token>`、`meteor://file/<id>`。
用 `MediaRef.parse()` 解析，不要手写前缀匹配。

### §19.3 core-backup / core-index / core-time / core-log / core-notify

```kotlin
interface BackupModule {                      // FROZEN
    suspend fun exportPlaintext(dest: Uri): BackupResult
    suspend fun importFrom(src: Uri): ImportResult
    suspend fun autoBackup(): BackupResult
    fun listBackups(): List<BackupMeta>       // 阻塞，自己挪到 IO
}

interface IndexModule {                       // FROZEN
    suspend fun search(plan: QueryPlan): List<SearchHit>
    suspend fun overview(): IndexOverview
    suspend fun queryByPerson(personId: String): List<Record>
    suspend fun rebuild()
}

interface TimeModule {
    fun nowIso(zone: ZoneId = ZoneId.systemDefault()): String
    fun stamp(body: String, timestamp: Instant, zone: ZoneId = ZoneId.systemDefault()): String
    fun readStamp(body: String): Instant?
    fun strip(body: String): String
    fun formatDate(date: LocalDate, locale: Locale): String
}

interface AppLog {                            // 落盘异步，Logcat 同步
    fun debug(tag: String, message: String); fun info(tag: String, message: String)
    fun warn(tag: String, message: String, throwable: Throwable? = null)
    fun error(tag: String, message: String, throwable: Throwable? = null)
    fun setVerbose(enabled: Boolean); fun isVerbose(): Boolean; fun sizeBytes(): Long
}

object DailyReminderPolicy {                  // 纯函数，无时钟无随机源
    val WINDOW_START: LocalTime = LocalTime.of(20, 0)
    val WINDOW_END: LocalTime = LocalTime.of(21, 0)
    fun nextTrigger(now: ZonedDateTime, alreadySentToday: Boolean, randomMinute: (Int) -> Int): ZonedDateTime
    fun hoursUntilEndOfDay(at: ZonedDateTime): Int
}

object QuestionReminderPolicy {               // 纯函数（ADR-068）
    fun defaultReminder(now: ZonedDateTime): Instant        // 明天同一时刻
    fun snoozeOneHour(now: ZonedDateTime): Instant
    fun snoozeTomorrow(now: ZonedDateTime): Instant
    fun nextPending(reminders: List<Instant>, now: Instant): Instant?
    fun pruneExpired(reminders: List<Instant>, now: Instant): List<Instant>
    fun replace(reminders: List<Instant>, consumed: Instant, replacement: Instant?): List<Instant>
}

class QuestionReminderScheduler(context, receiverClass, action) {
    fun canScheduleExactAlarms(): Boolean
    fun schedule(questionId: String, trigger: Instant): ReminderSchedulePrecision
    fun cancel(questionId: String, trigger: Instant)
    // companion: questionUri(questionId, trigger) = meteor://question-reminder/<id>/<epochMillis>
}
```

### §19.4 feature 模块

```kotlin
interface EditorModule {                      // FROZEN
    fun open(file: File): Document
    suspend fun save(doc: Document)
    fun render(doc: Document): @Composable () -> Unit
    fun insertImage(doc: Document, imageBytes: ByteArray, personId: String?): Document
    fun insertLink(doc: Document, target: String, label: String?): Document
}

interface AiModule {                          // FROZEN
    suspend fun skills(): List<AiSkill>
    suspend fun persona(personId: String): PersonaSnapshot
    suspend fun distill(request: DistillRequest): DistillResult
    suspend fun chat(request: ChatRequest): ChatReply
    suspend fun chatStreaming(request: ChatRequest, listener: AiChatListener): ChatReply = chat(request)
    suspend fun ask(request: AskRequest): AskAnswer
    suspend fun probe(config: AiConfig): ProbeResult
}
```

feature 的 Composable 一律通过参数接收状态、通过回调向上报告，
不读 DI、不导航、不直接持久化。

| 模块 | 关键导出 | 宿主必须提供 |
|---|---|---|
| `feature-settings` | `SettingsRepository`、`AppSettings`、`DarkMode`/`FontScale`/`MediaPickerMode`、`SettingsScreen` | `DataStore<Preferences>`；调用三个 `migrate*` 与 `seedPeopleTags` |
| `feature-calendar` | `CalendarScreen`、`CalendarHeader`、`Mood.emoji()` | 全部状态（month/today/moods/…）与所有意图的持久化 |
| `feature-editor` | `EditorModule`、`MarkdownEditorModule`、`MarkdownField`、`MarkdownPreview`、`MarkdownEditing`、`MediaBlockState` | 插入/删除/图片解析回调；`LocalMediaHost` |
| `feature-media` | `MediaHost`、`ProvideMediaHost`、`MediaThumb`、`VideoPlayerCard`、`AudioPlayerCard`、`MediaPickerScreen`、`MediaStreamDataSource` | 一个 `MediaHost` 实例并包住整棵树 |
| `feature-gallery` | `GalleryScreen`、`MediaViewerScreen`、`GalleryKind`/`GallerySortMode`/`GalleryFilter`/`GalleryItem` | 已排好序的 `GalleryDay` 列表；删除只发意图；`onAddMedia` 由宿主打开媒体选择器 |
| `feature-people` | `PeopleListScreen`、`PersonDetailScreen`、`PersonEditScreen`、`PeopleTagLibrary` | `availableTags`、标签持久化、媒体路由 |
| `feature-question` | `QuestionListScreen`、`QuestionComposeScreen`、`QuestionDetailScreen`、`QuestionRow`/`QuestionDetailUi`/`ReminderRow`/`AnswerRow`/`ReminderPick`、`QuestionFormat`、`QuestionSchedule` | 已格式化的展示模型（时间标签由宿主算好）、`.delete` 只发意图、保存/删除须在写盘后才回调；提问草稿由 `:app` 的 `QuestionComposeViewModel` 持有 |
| `feature-ai` | `AiModule`、`MeteorAiModule`、`AiScreen`、`AiSettingsScreen`、`AiProtocol`、`ArchiveTools` | `AiConfig`、对话记录、朗读、工具批准决策 |

`feature-media` 的播放与缩略图组件通过 `LocalMediaHost` 拿档案句柄
（由 `:app` 的 `ProvideMediaHost` 提供）。**没装这个 local 时它们会降级而不是崩溃**，
但缩略图会显示"不可用"字形、播放器无法工作。

**`MediaHost` 的身份是缩略图缓存键的一部分**——在重组中重建 `MediaHost` 会静默丢掉整个缓存。

### §19.5 各模块测试覆盖

| 模块 | 测试文件 | 用例数（约） |
|---|---|---|
| core-storage | 15 | 最多（含 `EncryptedMediaStoreTest` 23 个） |
| core-log | 5 | 保留策略、行格式、导出、尾部读取 |
| core-backup | 4 | 保留、清单、媒体归档、兼容性 |
| core-crypto | 3 | 信封、凭据迁移、媒体密钥 |
| core-index | 2 | 分词、匹配 |
| core-notify | 2 | 提醒窗口策略、每日一问提醒策略 |
| feature-ai | 15 | 最多（协议、工具、人格、Markdown） |
| feature-editor | 7 | 块解析、内联格式、渲染 |
| feature-people | 2 | 标签库、媒体格式 |
| feature-media | 1 | 导入逻辑 |
| feature-settings | 1 | 偏好与迁移 |
| app | 15 | 日期、农历、编码器、搜索行 |
| **core-time** | **0** | 缺口（§20.2） |
| **feature-calendar** | **0** | 缺口 |
| **feature-gallery** | **0** | 缺口 |
| **feature-question** | **0** | 缺口（界面全在手写 Compose，纯函数已提到 `QuestionFormat`/`QuestionSchedule`） |

---

## §20 测试

### §20.1 原则

- 测试写在**拥有该逻辑的模块**的 `src/test` 下。
- 纯函数（格式、解析、分词、策略）优先测，它们最容易被无声改坏。
- 涉及 `org.json` 的模块需要 `testImplementation(libs.org.json)`——Android 的
  `org.json` 在测试 classpath 上是桩实现。
- **没有测试源集的模块不要声明 `testImplementation(libs.junit)`。**

### §20.2 现有覆盖与缺口

零测试模块：`core-time`、`feature-calendar`、`feature-gallery`、`feature-question`。

最值得补的（按风险）：

1. `core-time` 的 `stamp`/`strip`/`readStamp`——时间水印是**写进正文的数据格式**，
   出错会让水印漏进用户可见文本，或让所有"记录于"退化。
2. `AlbumMapping`——ADR-052 §6 明确要求过排序映射的单测。
3. `MediaStreamDataSource` 的 seek 与越界分支——流式播放是 v1.5 的性能核心。
4. `MediaShareFile`——文件名清洗是路径穿越防线。
5. `QuestionSchedule`——默认提醒「明天同一时刻」与「必须晚于现在」是每日一问的两个
   易错边界，目前只有 `EncryptedQuestionStoreTest` 覆盖了存储侧。

---

## 附录 A：代码注释里的 §N 对照

| 注释里出现 | 指向本文 |
|---|---|
| `archive §3` / `档案 §3` | §3 模块图 |
| `archive §4` | §4 档案布局 |
| `archive §5.1` | §5.1 frontmatter |
| `archive §5.2` | §5.2 无 frontmatter 的二进制 |
| `archive §6` | §6 密钥与凭据 |
| `archive §7` | §7 备份（`BackupModule` 冻结标记也应改指 §8） |
| `archive §8` | §8 冻结接口 |
| `archive §9` | §9 索引 |
| `archive §10` | §10 图片与媒体上限 |
| `archive §11` | §11 可替换实现 |
| `archive §14` | §14 扩展点 |
| `archive R1` | §3.2 准入四问 |
| `archive R2` | §8 冻结接口 |
