# 侧栏功能抽屉拖动排序逻辑分析

分析对象：`app/src/main/kotlin/com/meteor/memory/ui/home/HomeDrawer.kt`、`DrawerItems.kt`，以及设置读写链路
（`RootViewModel.setDrawerConfig` → `SettingsRepository.setDrawerConfig`）。
相关历史提交：`a1ca640`（面板宽度 + 向下拖动回跳）、`6c0ab9e`（双向拖动排序）。

## 一、修复前的逻辑

1. 进入排序模式：长按任意行 → 弹出菜单 → 选「排序」，`sortMode = true`。
2. 手势挂在 `LazyColumn` 的容器级 `pointerInput(Unit)` 上（不挂在单行上），避免行重组取消手势。
3. `detectDrawerSortGestures` 在 `onDragStart` 时按 Y 坐标命中某一行，取 `hit.index - 1` 作为 `dragStartIndex`，
   记录 `grabY = start.y`；之后 `onDrag` 把手指绝对位移 `dy = change.position.y - grabY` 交给 `handleSortDrag`。
4. `handleSortDrag` 用 `target = dragStartIndex + round(dy / rowHeightPx)` 计算目标槽位，
   在**完整 order 列表**（含隐藏项、AI 不可用时被过滤掉的项）上把被拖项 `moved(fullFrom, fullTo)`。
5. 被拖行的视觉偏移 `dragDelta = dy - (target - dragStartIndex) * rowHeightPx`（若发生换位），
   配合 `animateItem` 让其它行平滑让位。
6. 拖动结束/按返回/点完成 → `onDrawerConfigChanged(order, visibleIds)` → DataStore 一次事务写入。

## 二、发现的问题

### P0 拖动补偿口径不一致（残留「拖动回跳/跳行」的根源）

`dragDelta` 的补偿用 `(target - dragStartIndex) * rowHeightPx`，但 `order` 更新后被拖项的
实际位移是 `(target - from) * fullRowSteps`，其中 `fullRowSteps` 是被拖项与目标之间跨越的
**完整列表行数**（包括隐藏项）。只有显示项连续、没有隐藏项且方向单一时，二者才相等。

- **跨隐藏项向下拖**（完整列表 `[A, H(隐藏), B, C, D]`，显示 `[A, B, C, D]`）：
  长按 A（`dragStartIndex=0`）向下拖两行到 `target=2`（显示项 C）。
  `moved(0,3)` 实际把 A 移到完整 index 3（A 的真实位移是 3 行），
  但 `dragDelta` 只补偿 `2 * rowHeightPx`，行瞬间多跳 1 行。
- **反向拖动**：`dragStartIndex` 是抓取时固定的起点，`from` 会随每次换位改变。
  当 `target` 回到起点附近时补偿符号按 `target - dragStartIndex` 算、真实位移按 `target - from` 算，
  符号可能相反，行会瞬移。
- **不换位但槽位已变**：`target == fromSlot` 时原代码直接 `dragDelta = dy`，
  忽略行已不在抓取槽位的事实，同样导致整行跳动。

### P1 命中行快照竞态

`onDragStart` 里 `hit.index` 来自手势开始时的布局信息，而 `itemAtListIndex` 读的是回调执行时的
最新 `latestDisplayedIds`；两者之间若发生重组（例如上一次排序落盘回流），`hit.index` 对应的行
可能已不是同一 id，抓错行。

### P1 取消即保存 + 重复写入

`onDragCancel` 与 `onDragEnd` 同样调用 `onDragFinished` 落盘，来电/切后台取消手势也会改变顺序；
完成按钮与 `onDragEnd` 还存在重复写入。

### P2 外部状态回流重置拖动

`HomeDrawer` 的 `remember(drawerOrder)` 以列表实例为 key，而 DataStore 每次 emit 都会构造新
`AppSettings`（新 List 实例）。抽屉自己的写入回流时本地 `order` 会被重置，覆盖正在进行的拖动。
另外缺少排序算法的单元测试，多次修复只能靠手工验证。

## 三、已实施的修复

1. **统一显示槽位口径**：在 `DrawerItems.kt` 抽出纯函数 `displayedDrawerIds()` 与
   `moveDisplayedDrawerId()`。UI 列表与拖拽目标共用同一份显示列表；隐藏项与 AI 不可用时被过滤，
   不再参与显示槽位计算。
2. **绝对位移补偿**：`dragDelta = dy - (target - grabSlot) * rowHeightPx`。
   `target` 由固定抓取槽位 `grabSlot` 加手指绝对位移推导；无论是否发生换位、无论往返多少次、
   无论一次跨几行，行始终跟手。这同时消除了反向拖动与跨隐藏项的跳变。
3. **命中快照一致**：`onDragStart` 先快照显示 id 列表，再用同一次快照解析 `hit.index`。
4. **保存语义**：`onDragEnd` 才落盘；`onDragCancel` 回滚到抓取前的 order；
   完成按钮是唯一其它保存入口。
5. **隔离外部回流**：改用 `LaunchedEffect` 按内容同步外部 `order`/`visibleIds`，
   排序期间完全忽略回流，避免自写自读打断手势。
6. **单元测试**：新增 `DrawerSortTest`，覆盖隐藏项过滤、AI 不可用、跨隐藏项上下拖、
   拖出边界再拖回、快速跨行、非法目标忽略等场景。

## 四、验证状态

按仓库 `AGENTS.md` 硬约束，本机不执行 Gradle 构建与测试，最终验收以 GitHub Actions 为准。

[![](https://img.shields.io/badge/powered_by-dsh-4D6BFE?style=flat-square&logo=deepseek&logoColor=white)](https://github.com/deepseek-ai/deepseek-harness)
