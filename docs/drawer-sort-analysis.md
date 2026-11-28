# 侧栏功能抽屉拖动排序逻辑

实现：`app/src/main/kotlin/com/meteor/memory/ui/home/DrawerReorder.kt`（状态与几何）、
`DrawerItems.kt`（纯数据整理）。界面只负责画：`HomeDrawer.kt`。
单元测试：`app/src/test/kotlin/com/meteor/memory/ui/home/DrawerReorderTest.kt`。

## 一、数据

- 持久顺序 `order`：全部功能 id 的排列，**包含隐藏项**。
- 可见集合 `visibleItems`：勾选显示的功能 id。
- 显示顺序 = `order` 过滤掉隐藏项与「本版本没有 AI 入口」的 AI 行，即
  `displayedDrawerIds()`。**拖动的槽位一律按显示顺序计**，持久顺序里夹着的
  隐藏行不占槽位。

两种顺序分开是这里最关键的一条：只要拖动目标按显示槽位算，隐藏行就不可能
给一次拖动额外加一步。

## 二、一次拖动

1. 排序模式下，手势挂在 `LazyColumn` 容器上（不是行上）：行的下标一变，行的
   组合就会被销毁，挂在行上的 `pointerInput` 会被自己的第一次换位取消。
2. 起手由列表自己的布局信息命中，`item.key` 就是功能 id，所以分组标题、空
   状态这些项不会被误当成可拖的行；命中后还要求「该槽位确实停着这个 id」，
   两者不一致就不开始拖动。
3. 记录固定坐标系 `DrawerDragGeometry(anchorSlot, anchorPointerY, rowHeightPx)`：
   - 目标槽位 = 起手槽位 + `round(手指位移 / 行高)`，夹在列表范围内；
   - 被拖行的偏移 = `(起手槽位 * 行高 + 手指位移) - 目标槽位 * 行高`，夹在半行内。
4. 目标槽位变化时把 id 移到该槽位。新顺序里被拖行落在它越过的那一行的另一侧
   （向下落在其后、向上落在其前），因此向下再拖回来会回到原来的可见序列。
5. 偏移公式只依赖「起手点 + 手指当前位置」，与换过几次位无关，所以手势不会
   累积漂移；换位只改变「哪个槽位承载同一个屏幕位置」，越过行中点时位置连续，
   不会跳动。
6. 手指抬起：顺序立刻落盘；剩下的零头偏移用 `MotionTokens.state()` 动画回零，
   行滑进槽位。手势被系统取消（来电、切后台）则回滚到起手前的顺序，不落盘。

## 三、状态与落盘

`DrawerReorderState` 持有 `order` / `visibleItems` / `sorting` / 当前拖动。

- 每次真实变化调用一次 `onPersist`，内容没变的重复写入被吃掉。
- 外部回写（DataStore 每次 emit 都会造出新的 List/Set）按**内容**而不是实例
  同步；排序模式或拖动进行中一律不采纳，避免自己的写入回流打断手势。
- 自己刚写出的值会先记成 `awaitingWrite`，期间收到更旧的值直接忽略——否则
  落盘前的一次旧 emit 会把刚放下的行弹回原位。

## 四、性能

- 只有被拖的那一行在 `graphicsLayer` 的绘制阶段读 `dragOffsetPx`，手指移动
  不会触发重组，只让那一行重新摆放。
- 换位时列表用 item key 识别移动，配合 `Modifier.animateItem()` 让其它行平滑
  让位；被拖行的 placement 动画在拖动期间关闭（它由自己的偏移决定位置）。
- 一次拖动只写一次 DataStore（松手时），点「完成」不会重复写。

## 五、验证

- `DrawerReorderTest` 覆盖：显示顺序过滤、顺序整理、跨隐藏项上下拖、AI 不可用、
  拖出边界、快速跨行、往返可逆、偏移连续性、外抛半行停靠，以及模拟整段拖动。
- 按仓库 `AGENTS.md` 硬约束，本机不执行 Gradle 构建与测试，最终验收以
  GitHub Actions 为准。

[![](https://img.shields.io/badge/powered_by-dsh-4D6BFE?style=flat-square&logo=deepseek&logoColor=white)](https://github.com/deepseek-ai/deepseek-harness)
