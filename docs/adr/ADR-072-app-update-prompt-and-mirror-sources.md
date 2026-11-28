# ADR-072 · 应用更新：弹窗确认、镜像择优与 SHA-256 校验

> 状态：已采纳。关联：[ADR-040](ADR-040-backup-coverage.md)（备份覆盖范围：本次新增的键不进备份）。
>
> 本 ADR 只改「检查更新 → 下载 APK」这条链路；档案格式、加密密钥、备份快照与
> `NotificationFeature` 等冻结接口一个字节未动。

## 一、问题

1. **静默后台下载**。启动时查询到新版本就直接把几 MB 的 APK 下完，用户事先没有选择权，
   也拿不到「这个版本先不管」的说法；在移动网络或流量紧张时尤其难受。
2. **直连经常不通**。更新检查只走 `api.github.com` / `github.com` 直连，网络受限时更新
   功能整体失败，用户只能自己去网页下载，等于把「检查更新」做成了摆设。
3. **完整性校验偏弱**。原先只用 `Content-Length` 加 ZIP 魔数判断文件是否像 APK；
   GitHub 自 2025 起为 Release 资产提供 SHA-256 `digest`，却没有被用上。

## 二、决定

### 二.1 检查与下载拆成两段，下载必须由用户确认

- `UpdateService.checkForUpdate()` 只查版本：查到新版进入新状态 `UpdateState.Available`，
  **不再自动下载**。`checkOnLaunch()` 入口不变，效果同样止于 `Available`。
- `UpdateService.startDownload()` 是唯一的下载入口，由弹窗的「立即更新」与设置页的
  「下载更新」调用；检查本身、定时器、通知都不会触发下载。
- 弹窗 `UpdatePromptDialog` 挂在 shell（`MeteorRoot`）的 `OnboardingGate.COMPLETE` 分支里，
  与 `MeteorNavHost` 平级，因此它永远不会盖住锁屏或首启引导。
- 三个回答：
  - **立即更新**：关闭弹窗，交给 `startDownload()`；
  - **稍后再说**（含点外部关闭）：本次进程内不再追问同一版本，等待下一次检查；
  - **忽略此版本**：写 DataStore 独立键 `update_skipped_version`，此后检查到同一版本不再
    弹窗。安装新版之后该字符串自然失配，于是过期作废，不需要清理逻辑。
- 该键刻意**不进** `AppSettings`、**不进**设置备份快照（ADR-040）与快照编解码器：它是
  设备本地的界面偏好，换机后重新询问一次是正确行为。

### 二.2 内置三个前缀式镜像，按探测延迟择优

- 清单是内置常量，不联网拉取（没有可信的公共镜像目录）：`gh-proxy.com`、`ghfast.top`、
  `ghproxy.net`，三者都是开源 `hunshcn/gh-proxy` 一系的「前缀拼接」格式，即
  `https://<镜像>/https://github.com/...`。
- 下载前对「直连 + 三个镜像」**并发探测**：`Range: bytes=0-0` 的 GET（一字节），
  connect / read 各 4 秒；2xx–3xx 记为可用的毫秒数，超时或失败记 null。
  - 为什么不用 HEAD：部分中转服务对 HEAD 会返回与代理错误页相同的响应，
    `Range: bytes=0-0` 在清单里所有主机上都成立，代价只有一个字节。
- 排序规则：延迟升序，null 排最后；排序是**稳定**的，因此平手时保持候选原序 ——
  直连永远在一个平手里排最前，镜像按清单顺序依次回退。
- 下载按排序依次尝试，任一源失败就换下一个源；每次尝试各自校验 digest，所以一个坏镜像
  最多只损失一次重试。
- 元数据（`api.github.com`）直连失败时，经 `gh-proxy.com` 前缀重试一次。实测只有它同时
  转发 API 请求，另外两个镜像对 `api.github.com` 返回 403，因此元数据兜底只挂它一个。

### 二.3 镜像地址本地派生，绝不采信 JSON 里的地址

- 镜像 URL 一律由本地对**已校验**的 `browser_download_url` 拼前缀得到；该校验仍要求
  host 为 `github.com`、路径以 `/JunRutey/Meteor/releases/download/` 开头，
  既有「拒绝非 GitHub URL」用例继续生效。
- 因此 Release JSON 里即使出现第三方地址，也进不了下载链路：镜像只能决定快慢，
  不能决定信任。

### 二.4 SHA-256 是最后一道门

- `ReleaseMetadata` 新增解析 `digest`（剥掉 `sha256:` 前缀，要求 64 位十六进制）与 `size`；
  形状不符一律当作「没有校验和」，而不是拿一个可疑值去比对。
- 下载完成后先 `.part` → 原子改名，再 `verifyDigest()`；不匹配则删除文件并报
  `UpdateError.CHECKSUM`，绝不交给安装器。
- 复用上启动留下的缓存 APK 之前，同样先校验 digest；不匹配就重新下载。
- 老 Release 资产可能没有 digest：此时退回「大小 + ZIP 魔数」校验并写警告日志，
  不伪造校验和。
- 镜像有时不回 `Content-Length`：优先用服务器给的长度，缺失时回退到元数据里的 `size`，
  两者都驱动进度条与完整性判断。

## 三、R1 四问

1. **属于哪个模块？** 全部落在 `:app` 的 `update` 包（`ReleaseMetadata`、`UpdateService`、
   新增 `UpdateSources`）与 `ui/UpdatePromptDialog`，外加 `:feature-settings` 的
   `SettingsRepository` 一个 DataStore 键与 `SettingsScreen` 一行。不新增模块。
2. **依赖谁？谁依赖它？是否单向、是否成环？** 模块依赖边零变化：
   `:app → :feature-settings` 仍是唯一相关的一条，`check-module-graph.sh` 只统计
   `build.gradle.kts` 里的 `project(":x")`，本次没有任何构建脚本改动。`UpdateSources`
   只依赖 JDK 与已在 `:app` 的 kotlinx.coroutines，没有可被反向依赖的公开面。
3. **对外接口是什么？是否足够窄？** `UpdateService` 是 `:app` 内部类，不是冻结接口；
   新增 `UpdatePromptDialog` 为 `internal`。唯一的签名变化是
   `checkAndDownload()` 拆成 `checkForUpdate()` + `startDownload()`，调用点只有
   `RootViewModel` 与 `SettingsViewModel`，都在 `:app`。`NotificationFeature` 的七个冻结
   接口、`core-*` 与 `feature-*` 的任何冻结签名均未改动。
4. **数据格式变更？是否向后兼容？** DataStore 新增键 `update_skipped_version`（加法式，
   缺省即「没有忽略任何版本」）；GitHub JSON 解析新增可选字段 `digest` / `size`，
   缺失可容忍；档案文件、备份快照（ADR-040）、加密格式一律不变。ProGuard 规则零改动
   （没有新增按名字持久化的枚举）。

## 四、后果

- 更新不再静默：每次发现新版多一次点击，换来流量可控、以及「忽略此版本」这个说法。
- 下载前多 4 个探测请求（各一字节），最坏情况每个 4 秒超时；探测在 IO 协程里并发，
  不阻塞界面，最坏约 4 秒后才开始取字节。
- 镜像可用性不由我们控制：社区公益服务随时可能失效，症状只是「多一次回退」；全部失效时
  退化为与旧版一致的直连行为。内置清单要更新就得发版，这是刻意的取舍 ——
  宁可清单陈旧，也不从网络接受一份可被投毒的镜像列表。
- digest 校验失败会整份重下（换下一个源）；这是有意的严格，不做「跳过校验」的旁路。
- 「忽略此版本」是设备本地状态：换机或清除数据后会重新询问。
- 下载进度通知在取字节阶段才出现；`Available` 阶段不产生通知（此前检查即刻开始下载，
  通知几乎必然出现）。

## 五、不变量

- 更新只认 GitHub 正式 Release；host 校验与仓库下载路径前缀校验不变，PR 预发布不参与。
- 不新增权限、不改 Manifest、不新增持久化枚举（`app/proguard-rules.pro` 零改动）。
- 未解锁、未完成首启引导时不出现更新弹窗；锁屏与引导界面的安全语义不变。
- 更新检查与下载不上传任何用户内容：镜像只会看到公开的仓库地址。
- 新增与改写的用户可见文案全部走 `strings.xml`，默认资源即中文。
- 冻接口清单（`core-crypto`、`core-storage`、`core-backup`、`feature-editor`、
  `feature-ai`、`core-index`）不受影响。
