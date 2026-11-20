# ADR-046 · 人物编辑页正文输入时的布局收缩

- 状态：已接受
- 日期：2026-11-20
- 关联：ADR-020（人物档案可编辑）、ADR-023（备注与正文、可滚动编辑区）、
  ADR-024（WindowInsets 与 IME）

## 一、背景

`PersonEditScreen` 原来是三段式固定布局：

```text
[ 页头：返回 / 标题 / 保存 ]        ← pinned
[ 身份卡：称呼 / 标签 / 备注 ]      ← heightIn(max = maxHeight * 0.44f) + verticalScroll
[ 正文 MarkdownField ]             ← weight(1f)
```

`MeteorRoot` 已经在唯一一处用 `WindowInsets.safeDrawing` 处理系统栏、挖孔和键盘
（ADR-024），所以键盘弹出时**整棵页面**可用高度变小，而不是被键盘覆盖。问题在于
身份卡的比例是固定的 44%：

- 键盘打开后 `maxHeight` 已经缩水，身份卡仍拿走其中的 44%，而且「称呼 / 标签 / 备注」
  三块内容本身就把这 44% 填满；
- 剩下的 56% 再减去页头、正文标题与内边距，正文编辑框只剩很小一条；
- 用户点正文准备输入中文时，看到的是身份卡占满上方、正文被压扁，主观上就是
  「正文被顶到应用顶部/布局跳了一下」。

需求是：**正文获得焦点、键盘打开时，身份卡自动让位，把剩余高度尽量留给正文**，
同时不破坏称呼/标签/备注的编辑能力，也不破坏中文 IME 组合。

## 二、决定

### 2.1 触发条件：正文焦点 ∧ IME 可见

`feature-editor` 的 `MarkdownField` 新增可选参数：

```kotlin
onFocusChanged: (Boolean) -> Unit = {}
```

在 `OutlinedTextField` 上通过 `Modifier.onFocusChanged { onFocusChanged(it.isFocused) }`
上报焦点。默认空实现，日记编辑页等既有调用方无需改动。

`PersonEditScreen` 用两个信号共同驱动身份卡上限：

```kotlin
var bodyFocused by remember { mutableStateOf(false) }
val imeVisible = WindowInsets.isImeVisible
val collapseIdentityCard = bodyFocused && imeVisible

val fieldsCapFraction = if (collapseIdentityCard) {
    FIELDS_FOCUSED_FRACTION   // 0.18f
} else {
    FIELDS_MAX_FRACTION       // 0.44f
}
val fieldsMaxHeight by animateDpAsState(
    targetValue = maxHeight * fieldsCapFraction,
    animationSpec = tween(FIELDS_HEIGHT_ANIMATION_MS), // 180ms
    label = "personIdentityMaxHeight",
)
```

- 未聚焦 / 键盘收起：`0.44f`，与旧行为一致；
- 正文聚焦且键盘可见：`0.18f`（需求建议区间 0.16–0.22 内偏小的值），把绝大部分
  高度让给正文；
- `tween(180ms)`：比键盘动画略短，视觉上跟随键盘展开，不产生整页平移动画。

`WindowInsets.isImeVisible` 在 Compose Foundation 1.7 仍是 `@ExperimentalLayoutApi`，
所以 `PersonEditScreen` 加了 `@OptIn(ExperimentalLayoutApi::class)`；它读取的是
ADR-024 已经依赖的同一份 IME inset，没有新增权限、依赖或窗口配置。

### 2.2 为什么必须同时有「正文焦点」这个门

键盘可见不等于「正在写正文」：

- 用户点称呼、备注或标签行里的文本框时键盘也会打开，如果只看 `isImeVisible`，会把
  身份卡压到 18%，直接藏住正在输入的那个字段；
- 因此收缩条件是 `bodyFocused && imeVisible`：键盘出现但没有正文焦点时，身份卡保持
  44%，称呼/标签/备注完整可编辑；
- 身份卡里的文本框获得焦点时正文自然失焦，`bodyFocused` 变 false，身份卡立即回弹。

### 2.3 为什么还看 IME 可见性，而不只看焦点

Android 的返回键只收起键盘，**不一定会清掉文本框焦点**。如果只按 `bodyFocused`
收缩，用户按返回键收起键盘后，页面仍停在 18% 的身份卡，违背「键盘关闭时恢复 0.44」。

把 `isImeVisible` 纳入条件后：

- 收起键盘（无论是否仍持有焦点）→ 回弹到 44%；
- 再次点正文（焦点可能没变，但 IME 重新出现的状态变化会触发重组）→ 重新收缩；
- 外接硬件键盘、软键盘不弹出的场景保持 44%，此时窗口没有因 IME 变小，页面也不存在
  被压扁的问题。

### 2.4 身份卡仍然可滚动，不新增页面滚动

身份卡容器继续是：

```kotlin
Modifier
    .fillMaxWidth()
    .heightIn(max = fieldsMaxHeight)
    .verticalScroll(rememberScrollState())
```

- 收缩时它变成一个可滚动的小窗口，而不是把内容裁掉；
- 页面根仍然是 `Column(fillMaxSize)`，页头固定，正文 `weight(1f)` 拿剩余高度；
- **不**在页面里再写 `imePadding()`，也不嵌套第二层 `verticalScroll` 到 `weight(1f)`
  的无限高度里；所有高度都由 `BoxWithConstraints` 的 `maxHeight` 与 `heightIn(max)`
  约束，测量有界，不会因动画把 cap 变小时触发无限高度测量。

### 2.5 预览态

`MarkdownField` 的预览会移除文本框，焦点不可能继续存在。进入预览时用
`LaunchedEffect(previewing) { if (previewing) onFocusChanged(false) }` 显式上报失焦，
避免宿主页面保留 `bodyFocused = true` 的陈旧状态；`previewing = true` 时没有文本框，
`imeVisible` 通常也随之回落，身份卡回到 44%。

### 2.6 不破坏中文 IME 组合

`MarkdownField` 的本地 `draft` 与 `LaunchedEffect(text)` 同步逻辑完全没有改动：

- 焦点监听只读取 `isFocused`，不读写文本、不触发 `onTextChanged`；
- 收缩的是身份卡的 `max` 高度，正文输入框仍然是同一个 composable 实例，不会因为
  布局参数变化被重组或重建；
- 中文输入法的 composing region 因此不会被外部 state 打断。

## 三、被否掉的方案

- **固定调小 44% 到 25%**：不写正文时身份卡也会长期偏小，称呼/标签/备注更难编辑。
- **只看键盘是否可见**：用户点称呼或备注时同样会弹键盘，会藏住正在编辑的字段。
- **只看正文焦点**：按返回键收起键盘后焦点可能仍在正文，身份卡会一直保持收缩，
  不满足「键盘关闭时恢复 0.44」。
- **整页 `verticalScroll`**：正文框会得到无限高度，光标定位和键盘避让都会出问题，
  也违背 ADR-023 的「页面不滚、编辑区内滚」。
- **再叠加一层 `imePadding()`**：`MeteorRoot` 的 `safeDrawing` 已经包含 IME，重复
  叠加会把内容顶出屏幕，正是之前要避免的「各算各的」。
- **用 `AnimatedVisibility` 把身份卡整体隐藏**：动画期间高度难以有界，且用户会失去
  称呼/标签/备注的入口；收缩到可滚动小窗更稳。
- **把页头也做成可滚动**：保存按钮会随键盘滚走，违背 ADR-023 的页头固定约定。

## 四、R1 模块准入四问

1. **模块归属**：焦点回调在既有 `:feature-editor`；页面布局与常数在既有
   `:feature-people`。不新增模块。
2. **依赖方向**：没有新增依赖边；`:feature-people → :feature-editor` 原本就存在。
3. **对外接口**：`MarkdownField` 只新增带默认值的可选参数，既存调用方无需修改；
   `PersonEditScreen` 的外部签名不变。
4. **数据格式**：不涉及任何存储格式、DataStore 键或导航参数；纯 UI 布局变化。

## 五、验证与已知限制

- 本机禁止运行 Gradle（AGENTS.md §1），无法在本地跑 Compose UI / instrumentation；
  静态检查结论是：身份卡 `heightIn(max)` 始终有界、正文 `weight(1f)` 在页头等固定
  高度之后测量，没有无限高度嵌套滚动。编译、lint 与仪器测试以 CI 为准。
- 收缩值是按常规手机竖屏估算的；屏幕很矮或横屏时 0.18 可能只剩几十 dp，但身份卡
  仍可滚动，点其中的文本框会让正文失焦、身份卡回弹，编辑能力不丢失。
- 键盘与身份卡动画并行时，视觉上会有一次高度过渡；动画只改变固定 cap，不会造成
  页面整体位移或从正文「弹回顶部」。
- 如果设备不报告 IME inset（本应用已按 ADR-024 配置 edge-to-edge + adjustResize，
  正常设备都会报告），页面会停留在旧的 44% 行为，不会比修改前更差。
- 如果系统在配置变更后重建页面，焦点状态 `remember` 会复位为未聚焦（44%），
  但不会错误保持隐藏状态。
