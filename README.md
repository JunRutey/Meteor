# Meteor · 流星

本地优先、端到端加密的个人记忆档案。Android · Kotlin · Jetpack Compose。

日记、人物档案、聊天记录与导入的附件都以 **AES-256-GCM 加密文件**存放在应用私有目录，
密钥由设备 Keystore 保管，不可导出、不上传、无云端同步。AI 是可选能力，默认关闭，
仅接入官方 DeepSeek。

> 当前版本 **v1.5.1**。功能与隐私的细节见 [AI 功能与隐私](docs/AI.md) 与
> [加密与安全](docs/SECURITY.md)，设计取舍见 [ADR](docs/adr)，
> 历次发布见 [发布说明](docs/releases)。
>
> **要改代码，先读 [开发手册](docs/DEVELOPMENT.md)**——模块边界、数据格式、
> 冻结接口、编码规范与发布流程都在那里，它是本仓库唯一的规范文档。

## 功能概览

- **日记与编辑**：Markdown 加粗、图片、链接。
  详见 [ADR-019](docs/adr/ADR-019-editor-keeps-bold-images-links.md)、
  [ADR-021](docs/adr/ADR-021-editor-is-plain-text.md)
- **图片与相册**：图片按天分组浏览，单张查看器居中显示，支持分享与删除。详见
  [ADR-037](docs/adr/ADR-037-image-viewer-share-delete.md)
- **冷启动**：专用高分辨率图标居中显示，再进入锁屏、引导或首页。详见
  [ADR-038](docs/adr/ADR-038-cold-start-splash.md)；农历换算已改为权威数据表、
  冷启动预热已移除，见 [ADR-047](docs/adr/ADR-047-lunar-table-over-icu.md)。
- **全文搜索**：CJK 分词，支持单字与心情标签检索，FTS5 → FTS4 → LIKE 运行时降级。
  详见 [ADR-015](docs/adr/ADR-015-cjk-bigram-tokenizer.md)、
  [ADR-016](docs/adr/ADR-016-fts-runtime-backend-fallback.md)
- **心情日历**：心情按天记录，一天最多三个，可点选与取消，日历着色。详见
  [ADR-017](docs/adr/ADR-017-mood-as-record-and-hand-rolled-calendar.md)、
  [ADR-035](docs/adr/ADR-035-multi-mood-per-day.md)
- **人物档案**：加密 `profile.md`，家人/朋友/绝交的朋友与自定义标签统一为可删除标签，
  支持添加、选择与删除标签，以及编辑与删除人物。详见
  [ADR-014](docs/adr/ADR-014-person-directory-uses-person-id.md)、
  [ADR-034](docs/adr/ADR-034-person-deletion-custom-tags-and-detail-redesign.md)、
  [ADR-036](docs/adr/ADR-036-custom-tag-deletion.md)、
  [ADR-045](docs/adr/ADR-045-unified-person-tags.md)
- **生辰与提醒**：实时年龄、公历/农历生日倒计时；每日 20:00–21:00 随机写日记提醒，
  由通知总开关统一控制下载通知与提醒。详见
  [ADR-033](docs/adr/ADR-033-daily-reminder-and-notification-channels.md)、
  [ADR-039](docs/adr/ADR-039-notification-master-random-window.md)
- **AI 助手**（默认关闭）：DeepSeek 官方 API、本机档案工具、联网搜索、文件输入、
  朗读回答、加密多会话；工具调用不设固定步数上限（单条结果仍截断）。详见
  [AI 功能与隐私](docs/AI.md)、[ADR-041](docs/adr/ADR-041-unlimited-ai-tool-calls.md)
- **关于与反馈**：更新日志按版本两级浏览；项目仓库、讨论、GitHub 议题与邮箱反馈入口；
  打赏页支持支付宝与微信。详见 [ADR-042](docs/adr/ADR-042-about-feedback-and-versioned-changelog.md)、
  [ADR-043](docs/adr/ADR-043-donate-page-and-alipay.md)
- **备份与恢复**：明文 zip 导出，覆盖日记、心情、人物、图片、附件、AI 会话、出生日期与
  非密钥设置；按新设备密钥重新加密导入。API Key、应用锁与禁止截屏不进入备份。详见
  [加密与安全](docs/SECURITY.md)、[ADR-040](docs/adr/ADR-040-backup-coverage.md)
- **诊断与更新**：诊断日志可导出；启动时检查 GitHub Release，通知总开关开启时在通知栏
  显示下载进度。详见 [加密与安全](docs/SECURITY.md)

## 构建

本仓库**不包含 Gradle Wrapper，也不在本地构建**。

所有编译、单元测试、Lint 与 APK 产物均由 GitHub Actions 产出：

- 每次推送到 `main` 或提交 PR → 触发 [`CI`](.github/workflows/ci.yml)
- 在 Actions 页面下载 `meteor-debug-apk` 构件即可安装
- 打 `v*` 标签 → 触发 [`Release`](.github/workflows/release.yml)，生成签名 APK 并发布

CI 固定使用 Gradle 8.11.1，并且**同时构建 debug 与 release 变体**——release 才跑 R8
与资源压缩，只在发 tag 时才第一次执行收缩规则的话，规则写错会表现为发版失败而不是 PR 失败。

本地想构建的话自行安装 ≥8.11.1 的 Gradle；验证仍以 CI 为准。
详见 [开发手册 §2](docs/DEVELOPMENT.md#2-环境与验证闭环) 与 [AGENTS.md](AGENTS.md)。

## 模块结构

```
:app                    组装、DI、导航、主题
 ├── :feature-ai         AI 与数字人格（ADR-027）
 ├── :feature-editor     Markdown 编辑与预览
 ├── :feature-people     人物档案、自定义标签与详情页
 ├── :feature-gallery    加密图片浏览
 ├── :feature-media       共享媒体播放/缩略图/内置选择器（ADR-052、ADR-053）
 ├── :feature-calendar   心情日历
 ├── :feature-settings   设置页（DataStore 偏好）
 ├── :core-backup        明文 zip 备份 / 重新加密导入
 ├── :core-storage       目录路由、frontmatter、透明加解密、附件存储
 ├── :core-index         FTS 全文索引
 ├── :core-crypto        Keystore AES-GCM、应用锁、AI 凭据加密
 ├── :core-log           诊断日志、导出与体积上限
 ├── :core-time          时间水印、locale 格式化
 └── :core-notify        每日日记提醒调度与通知渠道
```

依赖严格单向、无环，由 `tools/check-module-graph.sh` 在 CI 中强制。
不要求模块能被整体摘掉，只要求单个功能的改动不要把整个应用弄坏。
`:feature-ai` 刻意不依赖 `:core-index`：自己在解密后的记录上检索，耦合面更小。

完整的依赖边表、各模块公开 API 与已知边界瑕疵见
[开发手册 §3](docs/DEVELOPMENT.md#3-模块图与依赖方向r1)。

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
| v1.1 | 诊断日志覆盖、系统文件选择器导出、出生日期滚轮打磨 | ✅ 已发布 |
| v1.2 | 自定义标签删除、图片查看器分享与删除、冷启动图标过渡 | ✅ 已发布 |
| v1.3 | 通知总开关与每日随机提醒、备份覆盖出生日期与设置、AI 工具无固定上限、关于/打赏页重做、Splash 与农历预热、人物标签统一、正文输入布局 | ✅ 已发布（1.3.0–1.3.2） |
| v1.4 | 编辑器加粗与彩色字体、图片内联显示与图片描述、AI 回复 Markdown 渲染 | ✅ 已发布（1.4.0） |
| v1.5 | 相册支持视频与音频、编辑器与人物档案的媒体播放、内置文件选择器与按源数据排序、选择器系统/内置切换、大文件流式解密播放、二级密钥并行加解密与内容去重、删除日记与返回确认 | ✅ 已发布（1.5.0） |
| v1.5.1 | 「记录」页并入相册（新增「文件」条目与筛选）、搜索命中可直接打开、动效统一、主线程与滚动性能修复、修复 1.5.0 发布包的枚举收缩缺陷 | ✅ 已发布（1.5.1） |

## 加密与隐私

- 正文 AES-256-GCM 加密，frontmatter 只存索引元数据；密钥绑定设备 Keystore，
  **换机后旧密钥不可恢复**，备份因此导出为明文 zip——这是换机恢复的唯一路径
- 应用默认 `allowBackup=false`，不参与系统云备份；没有自建后端，没有埋点、崩溃上报，
  也没有任何使用数据上报
- AI 默认关闭。开启后，问题与被选中的档案片段会经 HTTPS 直接发往官方 DeepSeek API；
  API Key 与档案共用本机 Keystore 密钥加密，不进 DataStore、不进备份、不写日志
- 更新检查只向 GitHub 查询版本号，不发送日记、档案、设备内容或账号凭据

细节见 [加密与安全](docs/SECURITY.md) 与 [AI 功能与隐私](docs/AI.md)。

## 致谢

Meteor 的实现依赖许多开源项目，主要直接依赖、参考项目及其许可证见
[第三方开源致谢](docs/THIRD_PARTY_NOTICES.md)。

特别感谢 AndroidX / Jetpack Compose、Kotlin 与 kotlinx.coroutines、Hilt / Dagger、
KSP、JUnit 与 AndroidX Test 的维护者。

AI 能力离不开 DeepSeek 的开放：

- 应用内**对话界面**的布局与交互，参考了 DeepSeek 官方开源的对话客户端
  [chat-demo](https://github.com/deepseek-ai/chat-demo)（MIT）
- **联网搜索、链接阅读、文件输入、语音合成**等能力，基于 DeepSeek 开放平台的
  「全家桶应用 API」，接口实现依据 [DeepSeek API 文档](https://api-docs.deepseek.com/)
- AI 技能的格式参考了 MIT 许可的
  [nuwa-skill](https://github.com/alchaincyf/nuwa-skill)、
  [distilly](https://github.com/titanwings/distilly)、
  [ex-skill](https://github.com/therealXiaomanChu/ex-skill)

## 许可

[MIT](LICENSE)
