# Meteor · 流星

本地优先、端到端加密的个人记忆档案。Android / Kotlin / Jetpack Compose。

你的日记、人物档案、聊天记录以 **AES-256-GCM 加密文件**存放在应用私有目录，
密钥绑定设备 Keystore，不可导出、不上传、无云端同步。

> 状态：**v0.3.0**。日记、全文搜索、心情日历可用；富文本、人物档案、AI 尚未实现。

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
 ├── :feature-settings  设置页（DataStore 偏好）
 ├── :core-backup       明文 zip 备份 / 按新设备密钥重新加密导入
 ├── :core-storage      目录路由、frontmatter、透明加解密
 ├── :core-index        FTS 全文索引（FTS5 → FTS4 → LIKE 运行时降级）
 ├── :core-crypto       Keystore AES-GCM、应用锁
 ├── :core-backup       明文 zip 备份 / 导入重加密
 ├── :core-log          按天落盘的诊断日志与导出
 └── :core-time         时间水印、locale 格式化
```

依赖严格单向、无环，由 `tools/check-module-graph.sh` 在 CI 中强制；
`:core-index` 可被 `-Pmeteor.excludeModules=core-index` 整体移除（CI 有专门任务验证）。

规划中的模块：`:feature-editor`、`:feature-persona`、`:feature-ai`。

## 版本路线

| 版本 | 主题 | 状态 |
|---|---|---|
| v0.1 | 核心日记 + 基础设置 | ✅ 已发布 |
| v0.2 | 索引与搜索 | ✅ |
| v0.3 | 日历与心情 | ✅ |
| v0.4 | 富文本编辑器 | 计划中 |
| v0.5 | 人际关系档案 | 计划中 |
| v0.6 | 数字人格与 AI | 计划中 |
| v1.0 | 打磨发布 | 计划中 |

## 数据安全

- 文件正文 AES-256-GCM 加密，frontmatter 仅含索引所需元数据
- 密钥由 Android Keystore 生成，绑定设备，**换机后旧密钥不可恢复**
- 因此备份导出为明文 zip —— 这是换机恢复的唯一路径，请妥善保管
- 应用默认 `allowBackup=false`，不参与系统云备份

## 许可

[MIT](LICENSE)
