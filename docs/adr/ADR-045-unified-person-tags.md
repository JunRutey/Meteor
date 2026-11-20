# ADR-045 · 人物关系标签与自定义标签统一

- 状态：已接受
- 日期：2026-11-20
- 关联：ADR-014（person_id 目录）、ADR-020（人物档案可编辑）、ADR-023（备注与正文）、
  ADR-034（人物删除与自定义标签）、ADR-036（自定义标签删除）

## 一、背景

ADR-034 把三个内置关系（家人 / 朋友 / 绝交的朋友）和自定义标签放进了同一行芯片，
ADR-036 又给自定义标签加了 × 删除。但两档标签的操作仍然不一致：

- `Relation.entries` 被渲染成三个永远存在、没有删除入口的特殊 `TagChip`，点一下
  选中、再点一下取消；
- 自定义标签是 `CustomTagChip`，带 ×，确认后从标签库和当前选择里删除；
- 同一行里于是出现「未分类 / 家人 / 朋友 / 绝交的朋友 / 自定义标签」三种交互语言，
  用户无法理解为什么「家人」只能取消、不能删除，也无法清掉自己根本不用的关系档；
- 三个关系标签没有任何初始化机制，新装的标签库是空的，不符合「打开就能看到常用
  标签」的预期。

数据结构上，`relation:` 仍写在 `profile.md` 头部，AI 工具（`ArchiveTools`）、
`PersonaEngine` 与旧读取器都依赖这个字段。因此不能简单地删掉 `Relation` 枚举。

## 二、决定

### 2.1 UI：统一成可删除的标签芯片

`PersonTagEditor` 不再渲染特殊的 `relationChoices` 芯片。当前选中集合与可见集合里
的每个标签（包括「家人 / 朋友 / 绝交的朋友」）都用同一种 `CustomTagChip`：

- 点标签本体：切换选中；
- 点 ×：弹确认框，确认后从**记住的标签库**和**当前人物的选择**里移除；
- 未分类：仍然是特殊 `TagChip`（单选语义，只清空选择），没有 ×，不进入标签库。

详情页和列表页继续调用 `personTagLabels(relation, tags)`。当前 `relation` 仍可高亮，
但不再有任何不可删除的特殊芯片。

### 2.2 数据：保留 `Relation` 与 `relation:`，只作为派生字段

- `PersonProfile`、`PersonProfileCodec`、`Relation` 的格式和签名都不变；
- 选中内置标签时，`PersonTagEditor` 仍从选中集合派生第一个内置关系，写入
  `relation: family|friend|former_friend`；
- 删除全部内置标签后 `relation` 为 null，保存时 `profile.md` 不再写 `relation:`；
- 旧读取器仍能按 `relation:` 理解一个人的关系，AI 工具的参数格式也不变；
- 反向兼容：旧文件只有 `relation:`、`tags:` 里没有对应标签时，`PeopleTagLibrary.selectedTags`
  把该关系标签折叠进当前选中集合，编辑页照常显示且可删除。

### 2.3 一次性默认标签种子

为了让标签行第一次打开就有内容：

- `SettingsRepository` 新增 `people_tags_seeded` 布尔标记（DataStore）；
- 新增 `seedPeopleTags(defaultTags: List<String>)`：在**同一次** `DataStore.edit` 里
  合并默认标签并置位标记；标记已存在时直接返回，因此重复调用幂等；
- 标记与标签一起提交，进程在两步之间被杀也不会出现「标签已写入但标记没写」而被
  二次种回的情况；
- `PeopleViewModel.refresh()` 与 `PersonEditViewModel.load()` 在读取标签库前调用它；
  人物列表页和编辑页谁先打开都能完成种子；
- 用户之后删除某个默认标签只会走既有的 `removePeopleTag`，标记仍在，**不会被再次
  种回来**；升级前已经记住的自定义标签保留，默认标签追加在后面；
- 默认标签文案必须来自 `:feature-people` 的 `strings.xml`。`:feature-settings`
  不能硬编码中文，也不能为了 `getString` 新增 `feature-settings → feature-people`
  依赖边；因此由既依赖两者的 `:app` 解析后传入：`PeopleTagDefaults.defaultPeopleTags(context)`
  读取 `R.string.people_relation_family/friend/former`。

### 2.4 新建人物默认未分类

新建对话框不再预选「朋友」：

```kotlin
PeopleTagLibrary.NEW_PERSON_TAGS      // emptyList()
PeopleTagLibrary.NEW_PERSON_RELATION  // null
```

`onCreate(displayName, relation, tags)` 的接口保持不变；用户显式选中内置标签后，
`relation` 仍由选中集合派生。这样「新建」不会替用户编造一个关系，也不会让新建档案
一出生就带着删不掉的默认标签。

### 2.5 纯逻辑与测试

新增 `PeopleTagLibrary` 辅助函数，全部是纯 Kotlin、不依赖 Android：

| 函数 | 作用 |
| --- | --- |
| `selectedTags(tags, legacyRelationLabel)` | 折叠旧 `relation:` 标签，去重去空白 |
| `visibleTags(available, selected, excluded)` | 芯片可见集合 = 可用 + 当前选中 − 不可删除项（未分类） |
| `relationFor(selectedTags, labels)` | 从选中的内置标签派生 `Relation`，全删则 null |
| `NEW_PERSON_TAGS` / `NEW_PERSON_RELATION` | 新建人物的默认选择 |

测试覆盖：

- `PeopleTagLibraryTest`：合并/去重/空白、旧 relation 折叠、可见集合排除未分类、
  只有选中内置标签才派生关系、新建默认无标签；
- `SettingsRepositoryTest`：种子只执行一次、删除默认标签后再调用种子不复活、
  升级前已记住的标签与默认标签并存、种子会去空白。

### 2.6 删除语义不变

删除仍然「只忘记标签库 + 当前人物选择」，不改写任何人物的 `profile.md`，也不级联
删除别人档案里的同名标签。当前人物档案要照常点保存才会写回；其他人物详情页/编辑页
仍显示他们自己档案里的标签。内置标签也遵守同一语义，这是本次统一的核心。

## 三、R1 模块准入四问

1. **模块归属**：DataStore 标记与种子方法在既有 `:feature-settings`；芯片、纯规则与
   页面在既有 `:feature-people`；资源解析与 ViewModel 接线在既有 `:app`。不新增模块。
2. **依赖方向**：没有新增依赖边。`:app → :feature-people`、`:app → :feature-settings`、
   `:feature-people → :core-storage / :feature-editor` 保持原样；`:feature-settings`
   仍然不依赖 `:feature-people`。
3. **对外接口**：新增 `SettingsRepository.seedPeopleTags(defaultTags)`、
   `PeopleTagLibrary` 的纯函数、`PersonTagEditor` 内部实现变化；接口只接触标签字符串，
   不暴露路径或文件。没有修改任何 `FROZEN INTERFACE`。
4. **数据格式**：`profile.md` 格式、`Relation` 编码、目录结构都不变；DataStore 只新增
   `people_tags_seeded` 键。旧档案与旧备份可直接读取。

## 四、被否掉的方案

- **直接删除 `Relation` 与 `relation:`**：会破坏 AI 工具、`PersonaCodec`、旧档案兼容，
  且要改冻结面；UI 统一不需要动存储格式。
- **保留特殊芯片、只增加 ×**：两档交互仍然不同，用户仍会困惑；且「家人」这类标签
  的 × 与自定义标签的 × 行为一致才符合直觉。
- **在 `:feature-settings` 里写默认中文标签**：违反零硬编码中文；让它依赖
  `:feature-people` 又会新增模块边并可能成环。
- **每次打开页面都补齐默认标签**：用户删掉之后会被种回来，等于无法删除。
- **数据库迁移时一次性写入**：没有迁移入口，且冷启动 DataStore 写入需要额外线程与
  失败重试；放在页面首次加载更简单、可重入。
- **新建默认仍然预选朋友**：默认值会被当成用户真实选择，还会让内置标签在创建前
  无法移除。

## 五、已知限制

- 默认标签文案在首次种子时取当时语言的字符串并存入 DataStore；切换系统语言后已写入
  的标签不会自动翻译（与用户自定义标签一致）。
- 备份恢复会整体覆盖 `peopleTags`，但不会重置 `people_tags_seeded`；在全新安装上恢复
  旧备份后，首次打开人物页仍会补一次默认标签。这是有意的：恢复的标签库优先，缺失的
  常用标签仍可见。
- 只派生第一个内置关系写入 `relation:`；同时选中多个内置标签时，`relation:` 只是
  兼容字段，完整信息在 `tags:` 里。
- 删除内置标签不会改写其他人物档案；这是 ADR-036 已确立的语义，本次沿用。
- 本次未运行 Gradle（本机约束），编译/单测/lint 以 CI 为准。
