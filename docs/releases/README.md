# 发布说明目录

本目录存放**正式发布**的中文发布说明。Release 工作流把它们作为 GitHub Release 正文。

## 约定

1. **文件名必须等于 git tag**：`v0.7.0.md` ↔ tag `v0.7.0`。
   Release 工作流按 `${tag}.md` 查找，找不到直接失败（不允许用 GitHub 自动生成的英文 PR 列表）。
2. **正文用中文手写**，包含「新增 / 修复 / 变更 / 已知限制」四节；
   写用户能感知的变化，不要把没进过正式版的内部分改动写成升级收益。
3. **内容要与应用内 `app/src/main/assets/changelog.md` 对齐**，
   那份是「关于」页里用户能看到的历史。

## 发布流程

1. 功能分支合进 `main`，CI 三项全绿。
2. 单独一次提交改 `app/build.gradle.kts` 的 `versionCode` 与 `versionName`。
3. 单独一次提交写 `docs/releases/<tag>.md`，并同步 `assets/changelog.md`。
4. 推 `main`，等 CI 绿。
5. 打附注标签（`git tag -a v0.x.y -m "v0.x.y: 一句话概括"`）并推送标签，触发 Release。

## 例外：v0.6.0 / v0.6.1 / v0.6.2

这三份是**调试期构建**的说明，当时没有打 tag、也没有发布 GitHub Release
（远端标签只有 v0.1.0 / v0.3.0 / v0.4.0 / v0.6.3 / v0.7.0）。
保留它们只是留个历史记录，**不要用它们对外发布**；
将来若要补发，先确认对应代码状态，再按上面的流程重新写。
