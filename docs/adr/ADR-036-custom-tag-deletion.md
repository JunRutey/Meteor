# ADR-036 · 自定义人物标签支持从标签库删除

- 状态：已接受
- 日期：2026-11-20
- 关联：ADR-014（person_id 目录）、ADR-034（人物删除与自定义标签）、
  ADR-020（人物档案可编辑）

## 背景

ADR-034 把三个内置关系标签和自定义标签合并成同一行芯片，并让「＋」芯片把新标签
记进 `SettingsRepository.peopleTags`。但芯片行只有增、没有减：

- 自定义标签一旦添加，就永久占着标签库，建错一个错别字也删不掉；
- 标签库的聚合方式是「记住的标签 ＋ 所有人档案里的 tags」。这意味着即使从设置里
  删掉某个标签，只要还有一个人物档案保存着它，它下一次刷新就会从别人的档案里
  重新冒出来，删除等于没删；
- 创建对话框和编辑页复用 `PersonTagEditor`，两处都需要删除入口。

## 决定

### 1. 只给自定义标签删除入口

`PersonTagEditor` 的每个自定义标签芯片右侧增加一个小 ×（选中与未选中都可见）。
三个内置关系标签、以及「未分类」芯片**没有**删除入口：它们是 `Relation` 枚举与
档案文件格式的一部分，删除会破坏 AI 工具和旧文件兼容性。

点 × 先弹确认框，明确说明只从标签库移除该标签，不会删除任何人物的档案；确认后：

- 调用 `PersonTagEditor` 新增的 `onDeleteTag` 回调；
- 如果该标签正在当前选择里，立即从当前选择中移除（创建对话框和编辑页都生效）；
- 关闭确认框。

删除图标沿用 `:feature-people` 的手绘 `PersonIcon` 语言（新增 `Close` 形状），
不引入 Material 图标包。

### 2. 标签库改成「记住的标签 ＋ 当前人物标签」

聚合规则从「设置里的标签 ＋ 所有人物 tags」改为 `PeopleTagLibrary`：

```kotlin
PeopleTagLibrary.available(remembered, currentPersonTags)
```

- **编辑页**：`remembered + 当前 profile.tags`；
- **创建对话框**：只有 `remembered`，因为此时还没有「当前人物」；
- 别人档案里保存的同名标签不再进入芯片库，所以删除后不会立刻被别人的档案
  「复活」；那个人的详情页、编辑页仍然显示他自己的标签，不受影响。

**取舍**：升级到本版本后，只存在于某个人物档案、却从未写进 DataStore 的旧标签
不会出现在「新建人物」的公共标签库里；打开那个人物的编辑页仍能看到它。这是为了
让删除真正生效而接受的行为变化，旧数据没有丢失，也没有做破坏性迁移。

### 3. 删除只改设置，不级联改档案

`SettingsRepository` 新增：

```kotlin
suspend fun removePeopleTag(tag: String)
```

- 单次 `DataStore.edit`，幂等：标签不在集合里时什么都不写；
- 只动 `KEY_PEOPLE_TAGS`，不读、不写任何人物的 `profile.md`；
- 当前人物档案里已经保存的标签保留原样；用户在编辑页删除标签后，需照常点保存，
  `PersonEditViewModel.save()` 才会把去掉该标签的 `tags:` 写回档案。若直接离开
  编辑页，下次打开该人物时标签仍会从档案里读回来——这符合「未保存即未修改」的
  既有编辑语义。
- `PeopleViewModel` 与 `PersonEditViewModel` 各自持有 UI 状态，新增
  `removeTag(raw)`：更新内存列表、更新当前选择，并在后台调用 `removePeopleTag`；
  持久化失败只记日志，不阻塞 UI。

### 4. 测试

- `PeopleTagLibraryTest`：合并、去重、去空白、创建对话框只看 remembered；
- `SettingsRepositoryTest`：新增 / 重复新增、删除、删除不存在的标签（幂等）。

两个测试都不依赖 Android 或真实 DataStore，没有引入新依赖。

## R1 模块准入

1. **模块归属**：数据方法在既有 `:feature-settings`；标签聚合规则、芯片与确认框在既有
   `:feature-people`；两个 ViewModel 与导航接线在既有的 `:app`。不新增模块。
2. **依赖方向**：没有新增依赖边。`:app → :feature-people → :core-storage`、
   `:app → :feature-settings`、`:feature-people → :feature-editor` 保持不变且无环。
3. **对外接口**：只新增 `SettingsRepository.removePeopleTag(tag)` 和
   `PersonTagEditor` 的内部 `onDeleteTag` 回调；都只接触标签字符串，不暴露路径或文件。
4. **数据格式**：不改变 `profile.md` 格式、目录结构或 DataStore key；`people_tags`
   仍是同一个字符串集合，只是允许删除成员。向后兼容，无需迁移。

## 已知限制

- 删除标签不会改写其他人物档案里已经保存的同名标签；这是有意为之，避免一次确认
  静默修改多份档案。
- 当前人物编辑页里删除标签后不保存，标签会从档案重新读回；这与编辑页其它字段的
  未保存行为一致。
- 标签库仍是本机 DataStore 数据，不进入档案备份；重装后公共标签库丢失，但档案里
  已保存的标签在对应人物编辑页仍可看到并重新加回。
