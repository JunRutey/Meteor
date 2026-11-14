# Meteor · 流星

本地优先、端到端加密的个人记忆档案。Android / Kotlin / Jetpack Compose。

你的日记、人物档案、聊天记录及导入的文件附件以 **AES-256-GCM 加密文件**存放在应用私有目录，
密钥绑定设备 Keystore，不可导出、不上传、无云端同步。你可以接入 DeepSeek 官方 API，
使用本机档案工具、联网搜索/阅读链接、文档与图片输入、朗读回答，以及加密多会话。

> 状态：**v1.0.0**。日记、全文搜索、心情日历、人际关系（含自定义标签与删除）、
> Markdown 编辑器、AI 与数字人格均已可用。AI 对话界面重做为 DeepSeek 式布局，新增朗读与
> 累计用量统计；AI 仅接入官方 DeepSeek。AI 默认关闭，需自备 API Key；开启前会明确提示
> 「内容会通过 API 发往 DeepSeek」。
>
> 首页提供实时生辰年龄与公历/农历生日倒计时；首次启动可查看功能与隐私说明，
> 出生日期可以跳过，之后在「设置 → 个人资料」里修改。出生资料以设备 Keystore
> 密钥加密保存在本机。应用会在启动时检查 GitHub 更新，下载时在通知栏显示进度。

---

## 构建

本仓库**不包含 Gradle Wrapper，也不在本地构建**。

所有编译、单元测试、Lint 与 APK 产物均由 GitHub Actions 产出：

- 每次推送到 `main` 或提交 PR → 触发 [`CI`](.github/workflows/ci.yml)
- 在 Actions 页面下载 `meteor-debug-apk` 构件即可安装
- 打 `v*` 标签 → 触发 [`Release`](.github/workflows/release.yml)，生成签名 APK 并发布

详见 [AGENTS.md](AGENTS.md)。

## 模块结构

```
:app                    组装、DI、导航、主题
 ├── :feature-ai         AI 与数字人格（本机档案工具、DeepSeek 联网/文件输入、朗读、用量统计、加密多会话，ADR-027）
 ├── :feature-editor     Markdown 编辑与预览（图片、链接）
 ├── :feature-people     人际关系档案、自定义标签、删除与详情页
 ├── :feature-gallery    加密图片浏览（按天分组、可删除）
 ├── :feature-calendar   心情日历
 ├── :feature-settings   设置页（DataStore 偏好）
 ├── :core-backup        明文 zip 备份 / 按新设备密钥重新加密导入
 ├── :core-storage       目录路由、frontmatter、透明加解密、图片与文件附件存储
 ├── :core-index         FTS 全文索引（FTS5 → FTS4 → LIKE 运行时降级）
 ├── :core-crypto        Keystore AES-GCM、应用锁、AI 凭据加密存储
 ├── :core-log           按天落盘的诊断日志与导出
 ├── :core-time          时间水印、locale 格式化
 └── :core-notify        每日日记提醒调度与通知渠道
```

依赖严格单向、无环，由 `tools/check-module-graph.sh` 在 CI 中强制；
`:core-index` 可被 `-Pmeteor.excludeModules=core-index` 整体移除（CI 有专门任务验证）；
`:core-notify` 可被 `-Pmeteor.excludeModules=core-notify` 整体移除。
`:feature-ai` 刻意不依赖 `:core-index`：自己在解密后的记录上检索，索引被排除时 AI 仍可用。

关键取舍记录在 [`docs/adr`](docs/adr)：人物目录用 `person_id`、CJK 分词、
FTS 运行时降级、心情独立成记录、编辑器只保留加粗、人物档案可编辑与图片可删除、
人物档案可删除与自定义标签（ADR-034）、
AI 改为可关闭的联网提供商 + 技能驱动（ADR-027，取代 ADR-026）；v1.0 起 AI 只保留官方 DeepSeek 接入。

## 版本路线

| 版本 | 主题 | 状态 |
|---|---|---|
| v0.1 | 核心日记 + 基础设置 | ✅ 已发布 |
| v0.2 | 索引与搜索 | ✅ |
| v0.3 | 日历与心情 | ✅ 已发布 |
| v0.4 | Markdown 编辑器 + 人际关系档案 | ✅ 已发布 |
| v0.5 | （已并入 v0.4，不单独发版） | 已合并 |
| v0.6 | 数字人格与 AI（联网、可关闭、技能驱动） | ✅ |
| v0.7 | AI 工具开放与对话体验修复 | ✅ 已发布 |
| v0.8 | AI 写权限细分、附件只读、隐藏桌面图标 | ✅ 已发布 |
| v0.9 | 生辰页、首启引导、自动检查更新 | ✅ 已发布（0.9.0–0.9.5） |
| v0.10 | AI 联网搜索、文件输入、加密多会话 | ✅ 已发布 |
| v1.0 | AI 对话重做、朗读与累计用量、人物删除与自定义标签、DeepSeek 收敛 | ✅ 已发布 |
| v1.1 | 周 / 年档案代写 | 计划中 |

## 数据安全

- 文件正文 AES-256-GCM 加密，frontmatter 仅含索引所需元数据
- 人物称呼、关系与自定义标签都在 `profile.md` 的加密内容里，目录只用随机 `person_id`
- 导入附件及提取出的文本均加密保存；附件文本索引只驻留内存，照片和视频不发送给 AI
- 密钥由 Android Keystore 生成，绑定设备，**换机后旧密钥不可恢复**
- 因此备份导出为明文 zip —— 这是换机恢复的唯一路径，请妥善保管
- 应用默认 `allowBackup=false`，不参与系统云备份
- 出生日期单独以 AES-256-GCM 密文保存在应用私有目录，不进入档案目录与明文备份

## 更新检查与隐私

设置里的自动检查默认开启，每次启动时向 GitHub 查询最新正式 Release；发现新版本后自动在后台下载，
并在通知栏显示实时进度（可随时关闭系统通知）。下载完成后到「设置 → 应用更新」点安装，
由 Android 系统安装器接管，可能需要允许 Meteor 安装未知来源应用，且必须由用户确认。
关闭设置项后不再自动查询，仍可手动检查。更新检查仅向 GitHub 发送版本查询请求，
不会发送日记、档案、设备内容或账号凭据。

Meteor 没有自建后端；作者不接收、不收集、不记录应用使用内容，没有埋点、崩溃上报，
也没有任何使用数据上报。AI 的累计用量只保存在本机，用于给你自己看。

## AI 与隐私

AI 默认关闭，随时可在设置里关闭。
- **内容会离开设备**：开启并在设置里填好 API Key 后，你输入的问题与被选中的档案片段
  （人物档案、相关日记、聊天记录、导入文件中的文本、心情）会通过 HTTPS 直接发往
  **官方 DeepSeek API**，并受 DeepSeek 的隐私条款约束。开启前会弹窗说明这一点，
  拒绝即保持关闭。
- **钥匙只在本机**：API Key 用与档案文件相同的 Keystore AES-256-GCM 密钥加密成密文，
  与日记共用同一套加密；不进 DataStore、不进备份、不写日志。
- **提供商只有 DeepSeek**：v1.0 起设置里不再提供 OpenAI 或自定义接口地址。升级时旧的
  非 DeepSeek 设置会在启动时重置为 DeepSeek；已保存的 API Key 不会被删除，但需要换成
  DeepSeek 的 Key 才能继续使用。
- **模型不用手输**：AI 设置页可一键从 `GET {base}/models` 拉取 DeepSeek 可用模型再选；
  获取失败时可手动填写。设置页顶部提供官方开放平台与「获取 DS API」入口。
- **对话工作区**：会话标题、对话记录、对话设置、技能与对话对象切换、思考强度与用量统计
  都在 AI 页内完成；回答可选中复制，工具与思考过程折叠显示，向上翻历史时不会被流式内容
  拽回底部。
- **对话加密落盘**：聊天记录按「技能 + 对话对象」分会话保存在
  `数字遗产/AI对话/sessions/`，采用 AES-256-GCM 加密；该目录不参与档案检索，
  模型自己的回答不会变成「证据」。旧版历史会在首次读取时迁移；可新建、切换、重命名、
  删除会话，也可编辑提问、重新生成或继续生成。会话写入使用临时文件原子替换；
  单个会话无法解密时只跳过该会话，不会影响其余对话。
- **能力受控**：默认只有**只读**工具——检索、列出人物、读人物档案、列相关记录、
  读单条记录、按日期读日记、列出有日记的日期、读心情区间、档案规模统计、
  列出附件（仅元数据）、读附件抽取文本（共 11 个）。每个工具在
  `TOOL_CAPABILITIES` 里显式登记能力，未登记的名字既不会出现也不会执行。
  打开设置里的「允许 AI 修改档案」后，再按两个细分开关分别授予 7 个写工具：
  「写日记与心情」（覆盖/追加日记、设心情、按日期删除日记或心情）与
  「新建/更新/删除人物」。
  覆盖日记、修改或删除人物、删除记录必须在应用内核对完整参数并明确确认；
  追加日记、设置心情、新建人物无需确认。删除记录只能指定类型与日期，
  删除人物只能指定 `person_id`，都不能删除任意文件；人物对话作用域内禁止删除人物。
  导出、导入、改 Key、关应用锁等能力**永不开放**。附件抽取文本按不可信输入处理。
  另有一个始终可用的写操作：「人格蒸馏」产物，必须由你确认后才会落到既有的加密记录
  `数字遗产/数字人格/<person_id>/persona.md`。
- **时间问题按日期查**：「今天 / 昨天 / 上周 / 上个月 / 最近 N 天 / 2026-10-31」这类说法
  会在本机解析成日期范围，直接取当天的日记与聊天记录，不再靠关键词碰运气。
- **用量透明**：每条回答显示耗时、tokens、tok/s 与缓存命中率，输入框上方显示本次累计；
  另有「用量统计」页显示本机累计 tokens、请求次数、累计用时与缓存命中率。数据来自提供商
  返回的 `usage`，只统计 AI 对话请求。
- **朗读回答**：每条回答可调用 DeepSeek 语音合成朗读，支持「海星（女声）」与
  「暗潮（男声）」；整段合成后播放，切换会话、编辑提问、重新生成或发送新问题时会停止。
  回答文本会发往 DeepSeek 语音接口，可能产生费用。
- **流式回答**：请求带 `stream: true`，回答逐字出现；停留在底部时聊天列表自动跟随，
  向上翻看历史时不会被拉回底部。开启思考档位时，思维链（`reasoning_content`）会实时
  显示在可折叠的「思考过程」里。
- **工具链可见**：模型调用档案工具时会显示工具名、参数摘要和执行状态，
  整个 Agent 循环（最多 6 步）在界面上是可见的。
- **联网搜索与链接阅读**：可单独切换到 DeepSeek 联网模式，使用服务端搜索和阅读链接，
  并显示搜索过程与引用来源。该模式与本机档案工具分开；只预取少量相关档案片段，
  不开放命令执行、任意文件、密钥或备份操作。思考档位开启时会使用深度思考搜索，
  产生额外工具调用与费用；需要普通联网搜索时请在设置中选择「关闭」。
- **文档与图片输入**：可主动选择支持的文档或图片，上传至官方 DeepSeek 文件接口解析；
  上传时先写入应用缓存临时文件并流式发送，完成后立即删除，避免大文件整体占用内存；
  目前仅支持官方 DeepSeek 接口的 `deepseek-flash`。上传可能产生费用，文件默认 30 天后过期，
  删除会话时会尝试远端删除；单次最多添加 10 个文件，历史请求只重发未过期的附件，
  并限制单次最多 20 个文档引用。DeepSeek 不提供原文件或解析结果下载。
- **思考档位**：可选「自动 / 关闭 / 低 / 高 / 最高」，仅对 DeepSeek 发送
  `thinking` 与 `reasoning_effort`；「自动」不发送额外参数，兼容性最好。
- **先在本地检索，再发请求**：每次提问都会先用本机分词从你的问题里抽关键词，
  在解密后的档案里排序、截取少量片段（默认最多 4 段、约 1800 字）附在请求里；
  模型只在这几段上下文上回答，不够时才会再按 `record_id` 读更完整的原文。
  全量档案不会被整体发送。
- 技能（人格蒸馏 / 人格对话 / 档案问答）以 Markdown 形式内置，格式参考并致谢 MIT 许可的
  [nuwa-skill](https://github.com/alchaincyf/nuwa-skill)、
  [distilly](https://github.com/titanwings/distilly)、
  [ex-skill](https://github.com/therealXiaomanChu/ex-skill)。

细节见 [ADR-027](docs/adr/ADR-027-ai-networked-skills-agent.md)。
工具权限边界见 [ADR-030](docs/adr/ADR-030-ai-tool-permissions.md) 与
[ADR-032](docs/adr/ADR-032-ai-write-confirmation-and-scoped-deletion.md)；
人物删除与自定义标签见
[ADR-034](docs/adr/ADR-034-person-deletion-custom-tags-and-detail-redesign.md)。

## 致谢

Meteor 的实现依赖许多开源项目。主要直接依赖、参考项目及其许可证见
[第三方开源致谢](docs/THIRD_PARTY_NOTICES.md)。

特别感谢 AndroidX / Jetpack Compose、Kotlin 与 kotlinx.coroutines、Hilt / Dagger、
KSP、JUnit 与 AndroidX Test 的维护者。AI 技能格式参考了 MIT 许可的
[nuwa-skill](https://github.com/alchaincyf/nuwa-skill)、
[distilly](https://github.com/titanwings/distilly)、
[ex-skill](https://github.com/therealXiaomanChu/ex-skill)。

## 许可

[MIT](LICENSE)
