# ADR-014 · 人物目录用 `person_id` 而不是 `display_name`

- 状态：已接受（v0.1）
- 影响：`:core-storage`

## 背景

档案 §4 写的是「人物档案子目录按 `display_name` 建，内部文件统一带 `person_id`」。

但档案 §8 冻结了存储接口：

```kotlin
fun route(personId: String?, type: RecordType, date: LocalDate): File
```

签名里只有 `personId`，没有 `display_name`。而 `display_name` 按 §5.1 必须进密文
（它属于通讯录级别的敏感信息，明文放进路径等于把人际名单暴露在文件系统上）。

## 决策

人物档案目录以 `person_id` 命名：

```
记忆档案/人际关系/<person_id>/profile.md
数字遗产/聊天记录/<person_id>/2025-03-14.md
数字遗产/图片/<person_id>/*.enc
```

`display_name` 只存在于密文与索引层。展示层要「按人名看」时，通过 `:core-index`
把 `person_id → display_name` 映射出来，这本来就是总览页要做的事。

## 后果

- 冻结接口无需修改，符合 R2。
- 文件系统不泄露任何姓名，符合 §5.1 的意图（原文字面要求反而会泄露）。
- `person_id` 直接作为路径段，必须在 `StorageLayout` 内做白名单校验，
  否则 `../` 可以穿越目录——已实现并单测覆盖。
- v0.5 做人物总览页时，需要索引层提供 `person_id → display_name` 的批量解析。
