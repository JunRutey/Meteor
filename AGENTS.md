# AGENTS.md — Meteor 开发约定

本文件是**硬约束**。任何自动化代理或协作者在本仓库工作前必须读完。

---

## 1. 环境说明：这台机器不构建（仅限当前维护者环境）

> **适用范围**：本条只约束**当前维护者的这台开发机**，以及在这台机器上工作的自动化代理。
> 它不是仓库级的通用规则，其他开发者可以按各自习惯在本地构建，不受此条限制。

- 在这台机器上**禁止**执行 `gradle`、`gradlew`、`./gradlew assemble*`、`test*`、`lint*`、`build`、`install*`。
- 在这台机器上**禁止**执行 `gradle --dry-run`、`gradle tasks`、`gradle help` 等任何会启动 Gradle 守护进程的命令。
- 在这台机器上只做：读写源码、读写配置、`git` 操作、阅读文档。
- 在这台机器上，构建与验证一律交给 **GitHub Actions**。

原因：这台机器 JDK/SDK 版本与 CI 不一致（本机 JDK 17、仅 android-35），本地构建结果不可信，且会污染工作区与耗时。这是个人环境限制，不是对项目构建方式的规范。

**对其他开发者**：欢迎使用本机 Gradle / IDE 进行迭代。无论是否本地构建过，合并前都必须以 CI 结果为准。

## 2. 验证闭环：以 CI 为准

**合并前的最终验收标准是 GitHub Actions 通过**。本地构建只是开发期的辅助手段（是否执行由各开发者自行决定），不能替代 CI。

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
