# ADR-040 · 备份覆盖出生日期与设置

- 状态：已接受
- 日期：2026-11-20
- 关联：ADR-004（明文 zip 备份）、ADR-039（通知总开关）、ADR-033（通知渠道）

## 背景

备份由 `PlaintextBackupModule` 通过冻结的 `StorageRouter.exportTo/importFrom` 完成，只遍历
`filesDir/我的人生`。该目录已经覆盖日记、心情、人物 profile、聊天记录、数字人格、图片、
附件和 AI 对话，但有两类数据落在目录之外：

1. 出生日期：`BirthdayProfileStore` 把 AES-GCM 密文写在 `filesDir/meteor_birth_profile`；
2. 设置：DataStore `meteor_settings` 保存自定义人物标签、主题、通知总开关、AI 非密钥配置、
   引导完成状态等。

结果是换机恢复后生日与偏好都会丢失。API Key 在 `SecretStore`，绝不能进入明文备份。

## 决定

### 出生日期

- 新路径：`我的人生/记忆档案/自己/birth_profile.bin`，与「自己」的 `profile.md` 同目录。
  路径常量集中在 `StorageLayout.birthProfileFile()`。
- 一次性迁移：
  - 新文件不存在且旧 `filesDir/meteor_birth_profile` 存在时，先复制密文到新位置；
  - 复制后校验长度与字节完全一致，才删除旧文件；
  - 任一步失败都保留旧文件；`read()` 在新文件缺失时回退读取旧文件，因此出生日期始终可用；
  - `save()` 成功后删除旧文件；`read()`、`save()` 和导出前都会尝试迁移。
- 导出时，`StorageRouter.plaintextOf` 对非 `.md` 文件执行 `FileCrypto.decrypt`，因此 zip 内
  是出生日期明文 JSON；导入时 router 用新设备密钥重新加密，旧机制不变。
- `RecordScanner` 只扫描 `.md`，`.bin` 不会被当作日记/档案记录解析。
- 新安装首次保存时先创建 `记忆档案/自己` 目录；`read()` 在没有 legacy 且没有新文件时
  只返回 `null`，不会误建文件。
- DI 中 `BirthdayProfileStore` 的路径已改为新位置，旧路径只作为迁移来源传入。

### 设置快照

- 新路径：`我的人生/设置/backup-settings.json`，用 `FileCrypto` 加密后写入；路径常量集中在
  `StorageLayout.settingsSnapshotFile()`。扩展名是 `.json`，但磁盘内容是密文；router 在导出
  时解密为明文 JSON，导入时重新加密。
- 快照包含的本机非密钥偏好：
  - 外观：暗色模式、动态取色、界面动画、字体大小；
  - 备份：自动备份开关、保留数量；
  - 更新：自动检查更新；
  - 通知：总开关（ADR-039）；
  - 人物：已记住的自定义标签；
  - AI：开关、provider、model、reasoning、写权限细分（总开关/日记/人物）、TTS 音色、
    累计 token/耗时/请求数；
  - 引导完成状态；
  - 诊断：调试日志开关（同类非密钥偏好，一并恢复）。
- 快照**排除**：
  - AI API Key（在 `SecretStore`，不在 DataStore，也绝不写入备份）；
  - 应用锁开关、禁止截屏开关（设备安全/凭据相关，恢复时保留本机设置）；
  - AI 同意状态（换机后由用户在本机重新确认；恢复后 `AiGate` 会要求同意）；
  - 日志文件与备份 zip 自身；
  - 每日提醒的“最近发送日期”内部键（换机后应重新按新设备的当天窗口调度）。
- JSON 编解码在 `:app` 的 `SettingsSnapshotCodec`，纯 Kotlin + `org.json`：
  - 顶层 `version = 1`；
  - 缺省字段使用 `AppSettings` 默认值；
  - 未知字段与更高版本被忽略，已知字段仍尽量恢复；
  - 不写任何 key 字段；即使旧快照被塞入 `apiKey`，重新编码时也不会带出。
- `SettingsRepository` 增加批量读取 `backupSnapshot()` 与单次 DataStore edit 的
  `applyBackupSnapshot()`。恢复时：
  - 不触碰 `app_lock_enabled`、`screenshot_protection_enabled`、AI 同意与 API Key；
  - 保留数量限制在 1–30、人物标签最多 32 个；
  - provider 强制收敛到本版本唯一支持的 `deepseek`（与 ADR 的 DeepSeek-only 收敛一致）。

### 备份装饰器

- 冻结的 `BackupModule` 接口不变。在 `:app` 新增
  `SettingsSnapshotBackupModule` 装饰器：
  - `exportPlaintext`：先迁移出生日期、写设置快照，再调用原 `PlaintextBackupModule`；
  - `autoBackup`：同样先准备，再委托；快照写入失败不阻断备份；
  - `importFrom`：委托导入成功后读取并应用设置快照；没有快照的旧备份是正常 no-op；
  - 导入前先删除本机旧快照，避免导入不含快照的旧备份时反过来套用本机早先导出的设置；
    快照只是偏好的派生文件，下一次导出会重新生成，删除不会丢失用户数据；
  - `listBackups`：纯透传。
- Hilt 的 `provideBackupModule` 改为返回该装饰器；`BackupViewModel` 仍只依赖
  `BackupModule` 接口。
- 装饰器写入的快照位于档案根目录内，因此既有 zip 路径结构不变，只是多了
  `我的人生/记忆档案/自己/birth_profile.bin` 与 `我的人生/设置/backup-settings.json`
  两个普通文件条目。

### 兼容性

- 旧备份（没有出生日期与设置快照）导入仍成功；装饰器找不到快照即跳过，出生日期不会被清空。
- 新备份导入旧版本客户端：
  - 旧 router 把额外文件当作普通文件导入并重新加密，不会破坏数据；
  - 旧客户端不识别 `设置/backup-settings.json`，会在档案目录里留下一个无法识别的文件；
  - 旧客户端仍只读旧路径 `filesDir/meteor_birth_profile`，因此备份中的
    `birth_profile.bin` 不会在旧版本显示出生日期。
- 不改变 zip 中既有条目的路径与格式；新条目只是追加。

### 关于 autoBackup 调用点

当前代码库没有任何 `autoBackup()` 调用点（只有接口与实现）。本 ADR 只保证“一旦被调用也
包含出生日期与设置快照”，不新增自动备份调度——那属于独立任务。

### R1 四问

1. **模块归属：** 设置快照编解码、存储与装饰器都在 `:app`；出生日期路径常量在
   `:core-storage` 的 `StorageLayout`；`SettingsRepository` 的批量方法在 `:feature-settings`。
2. **依赖方向：** 不新增模块，不新增依赖边。装饰器在 `:app` 组装，`core-backup` 仍只依赖
   `core-storage`/`core-crypto`，不知道设置或出生日期的存在。
3. **对外接口：** `BackupModule` 不变；`SettingsRepository` 只新增批量快照方法；
   `StorageLayout` 只新增两个路径函数与常量。
4. **数据格式：** zip 路径结构不变，只追加两个文件；快照 JSON 带版本号，缺省/未知字段兼容；
   出生日期文件格式不变（仍是 `FileCrypto` 密文）。

## 后果

- 换机恢复后，出生日期、主题、通知总开关、AI 非密钥配置、人物标签和引导状态都会恢复。
- 明文备份 zip 现在包含出生日期与设置明文；这与既有“备份是明文 zip”的告警一致，用户仍应
  妥善保管备份文件。API Key 仍然不会进入备份。
- 导入会覆盖本机对应的偏好（应用锁、禁止截屏、AI 同意与 API Key 除外）；这是“恢复”的
  预期语义。
- `SECURITY.md` 中“出生日期单独以 AES-256-GCM 密文保存在应用私有目录，不进入档案目录与
  明文备份”这句话已经不准确，需要主智能体在发版时改为：出生日期在档案内以 AES-GCM 密文
  保存，明文备份会解密导出。
