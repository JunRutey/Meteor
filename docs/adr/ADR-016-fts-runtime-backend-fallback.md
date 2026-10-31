# ADR-016 · 全文索引在运行时探测后端并降级

- 状态：已接受（v0.2.1）
- 影响：`:core-index`

## 背景

v0.2 初版直接假定 Android 系统 SQLite 带 FTS5。真机日志（Xiaomi 2406ERN9CC / API 36）显示：

```
android.database.sqlite.SQLiteException: no such module: fts5 (code 1 SQLITE_ERROR)
```

一分钟内连续失败 15 次，索引与搜索完全不可用，且每次重建都在做无用功。

根因：**Android 的 SQLite 由 OEM 编译**，`SQLITE_ENABLE_FTS5` 并非必开项。
应用无权替换系统库，因此在任何设备上「有 FTS5」都是不能假定的前提。

## 决策

打开数据库时按顺序探测，并把结果持久化到 `meta` 表：

| 顺序 | 后端 | 条件 | 排序 |
|---|---|---|---|
| 1 | `FTS5` | `CREATE VIRTUAL TABLE ... USING fts5` 成功 | Kotlin 计分 |
| 2 | `FTS4` | 退而求其次，要求 `tokenize=unicode61` | Kotlin 计分 |
| 3 | `LIKE` | 前两者都不可用 | Kotlin 计分 |

要点：

- **排序统一在 Kotlin 完成**（`TextMatcher`），不用 `bm25()`。FTS4 根本没有 bm25，
  若沿用 SQL 侧排序，同一份数据在不同设备上会得到不同顺序。
- FTS4 必须显式指定 `unicode61`：默认的 `simple` 分词器只认 ASCII，
  会把中文 bigram 全部丢弃。
- 探测结果写入 `meta`，避免每次启动都付一次失败 DDL 的代价。
- `LIKE` 模式退化为对 bigram 的子串匹配——对中文而言这与分词效果一致，
  只是失去索引加速；个人规模（数千条）下仍可用。

## 重试退避

`IndexBootstrap` 对自动重建做指数退避（5s 起，上限 5 分钟），且**同一进程只触发一次**。
手动触发（存储诊断页）不受限制，便于排查。

## 后果

- 任何设备上索引都不会因为缺少 FTS5 而完全失效。
- `IndexSchema.VERSION` 升到 2，旧库在升级时整体重建（索引是缓存，不需要迁移）。
- 若将来确实需要统一的检索性能，可以引入捆绑 SQLite（`sqlite-bundled`），
  届时只需替换 `MeteorIndexDatabase` 的探测逻辑，`IndexModule` 冻结接口不变。
