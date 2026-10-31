# ADR-017 · 心情作为独立记录，不进 frontmatter；日历先手写

- 状态：已接受（v0.3）
- 影响：`:core-storage`、`:feature-calendar`

## 一、心情存哪里

档案 §13（v0.3）写「心情写入当天日记 frontmatter」，但 §5.1 又把 frontmatter
冻结为**恰好五个字段**（`meteor-enc` / `person_id` / `type` / `timestamp` / `source`），
并明确「仅含索引所需元数据，不含正文敏感内容」。

两者冲突。若往 frontmatter 加 `mood`：

- 破坏 §5.1 的冻结约定，旧版本读到未知字段需要兼容分支；
- 心情属于用户表达，放在明文元数据里等于泄出一部分内容。

**决策**：心情是独立记录 `type: mood`，落在 `时光轴/心情/<date>.md`，
这与 §4 的目录树本来就一致（`时光轴/心情/` 与 `时光轴/心情/日记/` 是两个位置）。
frontmatter 五字段保持不变，`StorageRouter` / `Document` 冻结签名不动。

代价：读某天心情需要多读一个文件。在个人规模下可忽略，且 `MoodQueries`
内部只扫 `时光轴/心情/` 顶层，不会误入 `日记/`。

## 二、日历为什么先手写

档案 §11 选定 kizitonwose/Calendar。核查后：该库 Compose 版本最新为 `2.7.0`，
且 2.5 → 2.6 → 2.7 之间 Composable 入口有变动。

本仓库的约束是**本地禁止构建、shell 无外网**，我无法验证某个版本的 API 签名，
选错一次就是一轮 CI 失败。而 v0.3 需要的只是：一个月网格、每日格子里一个 emoji、
点击弹出卡片——几十个格子，手写约 150 行。

**决策**：v0.3 用手写网格（`CalendarScreen`），不引入日历库。

后续若需要横向无限翻页、HeatMap、日期范围选择等能力，可以把 `CalendarScreen`
内部实现换成 kizitonwose，对外 Composable 签名不变——这与 `EditorModule`
预留第二实现位是同一个思路。
