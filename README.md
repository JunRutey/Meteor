# Meteor · 流星

本地优先、端到端加密的个人记忆档案。Android / Kotlin / Jetpack Compose。

你的日记、人物档案、聊天记录及导入的文件附件以 **AES-256-GCM 加密文件**存放在应用私有目录，
密钥绑定设备 Keystore，不可导出、不上传、无云端同步。

> 状态：**v0.6.0**。日记、全文搜索、心情日历、人际关系、Markdown 编辑器、
> AI 与数字人格均已可用。AI 默认关闭，需自备 API Key；开启前会明确提示
> 「内容会通过 API 发往你选择的 AI 提供商」。

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
 ├── :feature-ai         AI 与数字人格（技能 + 只读 Agent，可关闭的联网提供商，ADR-027）
 ├── :feature-editor     Markdown 编辑与预览（图片、链接）
 ├── :feature-people     人际关系档案、档案编辑
 ├── :feature-gallery    加密图片浏览（按天分组、可删除）
 ├── :feature-calendar   心情日历
 ├── :feature-settings   设置页（DataStore 偏好）
 ├── :core-backup        明文 zip 备份 / 按新设备密钥重新加密导入
 ├── :core-storage       目录路由、frontmatter、透明加解密、图片与文件附件存储
 ├── :core-index         FTS 全文索引（FTS5 → FTS4 → LIKE 运行时降级）
 ├── :core-crypto        Keystore AES-GCM、应用锁、AI 凭据加密存储
 ├── :core-log           按天落盘的诊断日志与导出
 └── :core-time          时间水印、locale 格式化
```

依赖严格单向、无环，由 `tools/check-module-graph.sh` 在 CI 中强制；
`:core-index` 可被 `-Pmeteor.excludeModules=core-index` 整体移除（CI 有专门任务验证）。
`:feature-ai` 刻意不依赖 `:core-index`：自己在解密后的记录上检索，索引被排除时 AI 仍可用。

关键取舍记录在 [`docs/adr`](docs/adr)：人物目录用 `person_id`、CJK 分词、
FTS 运行时降级、心情独立成记录、编辑器只保留加粗、人物档案可编辑与图片可删除、
AI 改为可关闭的联网提供商 + 技能驱动（ADR-027，取代 ADR-026）。

## 版本路线

| 版本 | 主题 | 状态 |
|---|---|---|
| v0.1 | 核心日记 + 基础设置 | ✅ 已发布 |
| v0.2 | 索引与搜索 | ✅ |
| v0.3 | 日历与心情 | ✅ 已发布 |
| v0.4 | Markdown 编辑器 + 人际关系档案 | ✅ 已发布 |
| v0.5 | （已并入 v0.4，不单独发版） | 已合并 |
| v0.6 | 数字人格与 AI（联网、可关闭、技能驱动） | ✅ |
| v0.7 | AI 工具全面开放、周/年档案代写 | 计划中 |
| v1.0 | 打磨发布 | 计划中 |

## 数据安全

- 文件正文 AES-256-GCM 加密，frontmatter 仅含索引所需元数据
- 导入附件及提取出的文本均加密保存；附件文本索引只驻留内存，照片和视频不发送给 AI
- 密钥由 Android Keystore 生成，绑定设备，**换机后旧密钥不可恢复**
- 因此备份导出为明文 zip —— 这是换机恢复的唯一路径，请妥善保管
- 应用默认 `allowBackup=false`，不参与系统云备份

## AI 与隐私

AI 是这个应用里**唯一会联网**的功能，默认关闭，随时可在设置里关掉。

- **没有后端**：Meteor 不与任何服务器通信。作者不接收、不收集、不记录任何内容，
  没有埋点、没有崩溃上报、没有使用统计。
- **内容会离开设备**：开启并在设置里填好 API Key 后，你输入的问题与被选中的档案片段
  （人物档案、相关日记、聊天记录、导入文件中的文本、心情）会通过 HTTPS 直接发往**你自己选择的** AI 提供商
  （DeepSeek / OpenAI / 自定义 OpenAI 兼容接口），并受该提供商的隐私条款约束。
  开启前会弹窗说明这一点，拒绝即保持关闭。
- **钥匙只在本机**：API Key 用与档案文件相同的 Keystore AES-256-GCM 密钥加密成密文，
  与日记共用同一套加密；不进 DataStore、不进备份、不写日志。
- **模型不用手输**：设置里一键从 `GET {base}/models` 拉取可用模型再选；
  获取失败时可手动填写。
- **对话加密落盘**：聊天记录按「技能 + 对话对象」保存到
  `数字遗产/AI对话/<scope>.bin`，同样是 AES-256-GCM 密文；该目录不参与档案检索，
  模型自己的回答不会变成「证据」。清空按钮只清当前会话。
- **能力受控**：默认只有**只读**工具——检索、列出人物、读人物档案、列相关记录、
  读单条记录、按日期读日记、列出有日记的日期、读心情区间（共 8 个）。
  打开设置里的「允许 AI 修改档案」后，额外获得 5 个写工具（写/追加日记、设心情、
  新建/更新人物）；删除、导出、导入、改 Key、关应用锁等不可逆操作**永不开放**。
  另有一个始终可用的写操作：「人格蒸馏」产物，必须由你确认后才会落到既有的加密记录
  `数字遗产/数字人格/<person_id>/persona.md`。
- **时间问题按日期查**：「今天 / 昨天 / 上周 / 上个月 / 最近 N 天 / 2026-10-31」这类说法
  会在本机解析成日期范围，直接取当天的日记与聊天记录，不再靠关键词碰运气。
- **用量透明**：每条回答显示耗时、tokens、tok/s 与缓存命中率，输入框上方显示本次累计；
  数据来自提供商返回的 `usage`。
- **流式回答**：请求带 `stream: true`，回答逐字出现；聊天列表自动跟到底部。
  开启思考档位时，思维链（`reasoning_content`）会实时显示在可折叠的「思考过程」里。
- **工具链可见**：模型调用档案工具时会显示工具名、参数摘要和执行状态，
  整个 Agent 循环（最多 6 步）在界面上是可见的。
- **思考档位**：可选「自动 / 关闭 / 低 / 高 / 最高」，仅对 DeepSeek 发送
  `thinking` 与 `reasoning_effort`；「自动」不发送额外参数，兼容性最好。
- **密钥与日记同一把密钥**：API Key 用与档案文件相同的 Keystore AES-256-GCM 密钥加密成
  密文保存（旧版加密存储会自动迁移），不明文、不进备份、不写日志。
- **先在本地检索，再发请求**：每次提问都会先用本机分词从你的问题里抽关键词，
  在解密后的档案里排序、截取少量片段（默认最多 4 段、约 1800 字）附在请求里；
  模型只在这几段上下文上回答，不够时才会再按 `record_id` 读更完整的原文。
  全量档案不会被整体发送。
- 技能（人格蒸馏 / 人格对话 / 档案问答）以 Markdown 形式内置，格式参考并致谢 MIT 许可的
  [nuwa-skill](https://github.com/alchaincyf/nuwa-skill)、
  [distilly](https://github.com/titanwings/distilly)、
  [ex-skill](https://github.com/therealXiaomanChu/ex-skill)。

细节见 [ADR-027](docs/adr/ADR-027-ai-networked-skills-agent.md)。

## 许可

[MIT](LICENSE)
