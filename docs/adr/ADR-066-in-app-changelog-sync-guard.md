# ADR-066 · 应用内更新日志的同步校验

- 状态：已接受
- 影响：`.github/workflows/release.yml`、`app/src/main/assets/changelog.md`、
  `docs/releases/README.md`（发布流程文档）；不涉及任何 Kotlin 代码与模块依赖

## 一、问题

v1.5.0 正式版发布后，应用内「关于 → 更新日志」里看不到 1.5.0。

仓库里有两份**手写**的发布文档，用途不同：

| 文件 | 读者 | 由谁读取 |
| --- | --- | --- |
| `docs/releases/<tag>.md` | 外部读者，GitHub Release 页面 | `Release` 工作流 → Release 正文 |
| `app/src/main/assets/changelog.md` | 应用内用户，「关于 → 更新日志」 | `ChangelogParser` → 打包进 APK |

比对两次发布提交可以看出漏在哪一步：

- v1.4.0：`2870a2f docs(release): 写 v1.4.0 发布说明与更新日志`
  → 同时改了 `docs/releases/v1.4.0.md` **与** `app/src/main/assets/changelog.md`；
- v1.5.0：`8c19a82 docs(release): 写 v1.5.0 发布说明`
  → **只**改了 `docs/releases/v1.5.0.md`。

`app/build.gradle.kts` 里 `versionName = "1.5.0"`（`versionCode 34`），而
`changelog.md` 的最上面一行仍是 `## v1.4.0`。`ChangelogParser` 按 `## vX.Y.Z` 切分、
按文件顺序展示，因此应用内最新版本停在 v1.4.0 —— 用户看到的就是「这一版没有更新日志」。

根本原因不是解析器或界面出错，而是**发布流程里「同步 `assets/changelog.md`」这一步
只写在文档里（`docs/releases/README.md` 第 3 步），没有任何机器校验**。Release 正文
读的是 `docs/releases/<tag>.md`，那个文件存在，所以 CI 全绿、发布照常成功，漏同步
完全静默。这正是「文档约定的步骤」与「CI 强制的步骤」之间的落差。

## 二、决定

### 2.1 两份文档各自保留，`assets/changelog.md` 仍是应用内唯一数据源

应用内「关于 → 更新日志」只读 `app/src/main/assets/changelog.md`，**不引入第二份来源**。
理由：它是随 APK 打包的离线资源，离线应用不能为了看更新日志去联网；解析器是纯 Kotlin
（见 [ADR-042](ADR-042-about-feedback-and-versioned-changelog.md)），格式约定不变。

### 2.2 CI 交叉校验，缺一版就让发布失败

在 `Release` 工作流里，紧接「Read release notes」之后、`Publish GitHub Release` **之前**
新增一步 `Verify in-app changelog has this version`：

- tag 解析与既有步骤完全一致（`${{ inputs.tag || github.ref_name }}`），
  因此 tag push 与 `workflow_dispatch` 两条路径都会走到；
- 用 `grep -Fxq -- "## ${TAG}"` 做**整行精确匹配**，与 `ChangelogParser` 的
  `startsWith("## ")` + `trimEnd()` 行为对齐：`### v1.5.0`、行首带空格的写法都不算通过；
- 不匹配时输出中文 `::error::` 并 `exit 1`，发布任务直接失败；
- 另外用 `grep -m1 '^## '` 取文件第一个版本号，若它不是本次 tag，只发 `::warning::`
  而不失败 —— 重新发布历史 tag（`workflow_dispatch` 重建旧版本）时不应该被拦，
  但「新版本没排在最前」这个真实疏漏仍然会出现在日志里。

这一步放在 APK 构建与签名校验之后、发布之前：构建失败时不会白跑，构建成功时则
**不可能被跳过**（同一 job 内顺序执行，没有 `if:` 条件）。

### 2.3 流程文档同步说明「现在是 CI 强制」

`docs/releases/README.md` 的「约定」新增一条，指明缺失 `## <tag>` 段落会让发布失败，
把「应该做」变成「不做就发不出去」。

## 三、被否掉的方案

- **构建时用 `docs/releases/<tag>.md` 自动生成 `changelog.md`**：两份文档的读者不同，
  篇幅也不同。Release 正文有导语、分组的粗体小标题、逐条技术细节（例如「加密分块
  提升到 10 MiB」「Keystore 包裹的二级密钥」），是给关心版本的读者看的完整说明；
  应用内更新日志是**累计的历史**，每一版都要求同样的「新增 / 修复 / 变更 / 已知限制」
  四段、同样的长度与口吻（见 v1.4.0 那一块的写法），用户在一页里横向比较多个版本。
  自动生成会同时毁掉两边：Release 正文被迫压成流水账，或者应用内被灌进大量内部实现
  细节。而且生成只能覆盖**当前版本**，历史版本的措辞不会因此变好。
- **只改文档、加强流程约定**：本次问题的直接成因就是「只写在文档里」。没有校验的约定
  已经失效过一次，再加一句提醒不改变任何机器行为。
- **在 `:app` 里运行时回退（例如拿 `versionName` 与 changelog 比对，缺失时提示）**：
  运行时才发现已经太晚 —— APK 已经发出去，用户已经装上，只能等下一版修。校验必须
  发生在发布之前。
- **用第三方 action 或脚本生成校验**：一条 `grep` 就够，不需要新依赖，也不需要维护
  额外的 Python/Node 环境。

## 四、后果与已知限制

- v1.5.0 的应用内更新日志已补齐（`## v1.5.0` 放在文件最前），内容由
  `docs/releases/v1.5.0.md` 压缩为与 v1.4.0 同风格的四段，只写用户能感知的变化，
  不写内部实现。
- 校验只检查**版本标题是否存在**，不比对正文内容。`## v1.5.0` 存在但正文写得敷衍，
  CI 不会拦 —— 文案质量仍靠人工评审，机器只负责「别再漏掉整版」。
- 校验读的是**工作区**里的 `app/src/main/assets/changelog.md`。tag 指向的提交必须
  已经包含该段落，因此补写 changelog 的那次提交要在打 tag 之前进 `main`
  （与 `docs/releases/README.md` 的既有步骤一致）。
- 重新发布历史 tag 时，若 `changelog.md` 里该版本不是最前，只出 warning；这是有意的
  宽松处理，避免旧版本重发被拦。
