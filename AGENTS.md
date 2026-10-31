# AGENTS.md — Meteor 开发约定

本文件是**硬约束**。任何自动化代理或协作者在本仓库工作前必须读完。

---

## 1. 铁律：本地绝不构建

- **禁止**在本机执行 `gradle`、`gradlew`、`./gradlew assemble*`、`test*`、`lint*`、`build`、`install*`。
- **禁止**在本机执行 `gradle --dry-run`、`gradle tasks`、`gradle help` 等任何会启动 Gradle 守护进程的命令。
- 本地只做：读写源码、读写配置、`git` 操作、阅读文档。
- 唯一合法的构建与验证场所是 **GitHub Actions**。

原因：本机 JDK/SDK 版本与 CI 不一致（本机 JDK 17、仅 android-35），本地构建结果不可信，且会污染工作区与耗时。

## 2. 验证闭环：只看 CI

改动后唯一的验收方式是推送到 GitHub 并等待 Actions 结果。

- 失败时读取日志：`gh run list --limit 5` → `gh run view <run-id> --log-failed`
- 修复后再推，直到 `CI` 工作流绿灯。
- **不允许**在 CI 未绿的情况下声称某项改动「已完成」。

## 3. 模块化准入清单（R1）

新增任何功能前，必须先书面回答这五问，答不上来不写代码：

1. 属于哪个模块？新增模块还是并入现有模块？
2. 依赖谁？谁依赖它？是否单向？是否成环？
3. 对外接口是什么？是否足够窄？
4. 移除该模块后，其余模块能否编译、已有数据能否读取？
5. 是否有数据格式变更？是否向后兼容？

依赖方向由 `tools/check-module-graph.sh` 强制，CI 第一步就会跑。新增依赖边 = 改架构 = 必须先在档案 §3 登记。

## 4. 接口冻结（R2）

`core-crypto`、`core-storage`、`core-backup`、`feature-editor`、`feature-ai`、`core-index` 中标注 `FROZEN INTERFACE` 的签名**不得修改**。

新增能力只能：新增模块，或新增带默认实现的方法。改签名必须先写 ADR。

## 5. 版本与依赖

- 所有版本集中在 `gradle/libs.versions.toml`，禁止在模块里硬编码版本号。
- **升级 AGP / Kotlin / Gradle / Compose BOM 必须单独成一次提交**，提交信息写明理由，并等 CI 绿。
- 禁止一次提交里既加功能又升版本——出问题时无法定位。

## 6. 提交与分支

- 分支：`main` 始终可构建。功能走 `feat/<name>`。
- 提交信息用中文，一句话说清「做了什么」，例如 `feat(storage): 实现 frontmatter 解析`。
- 一次提交只做一件事。

## 7. 禁止入库的内容

- 任何密钥、keystore、`secrets.properties`、API key。
- 构建产物（`build/`、`*.apk`）。
- 本机路径文件（`local.properties`）。

## 8. 语言与文案

- 代码里**零硬编码中文**，全部走 `strings.xml`。
- 默认资源 `values/` 即中文，后续新增语言靠 `values-xx/`。

## 8.1 发布说明必须中文手写

- 每次发版前在 `docs/releases/<tag>.md` 写好中文发布说明，包含「新增 / 修复 / 变更 / 已知限制」。
- CI 的 `Release` 工作流读取该文件作为 Release 正文；**文件缺失会让发布任务直接失败**。
- 禁止使用 GitHub 自动生成的英文 PR 列表作为发布说明。
- 面向用户的文案（Release、README、提交信息、Issue 回复）一律中文。

## 9. 依赖注入边界

Hilt 只出现在 `:app`。`core-*`、`feature-*` 模块保持纯 Kotlin 类型，由 `:app` 的 `di/AppModule.kt` 负责组装。这样模块才能被移除而不带走 DI 图。
