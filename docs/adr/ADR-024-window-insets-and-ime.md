# ADR-024 · 键盘（IME）与系统栏 inset 只在根部处理一次

- 状态：已接受（v0.4）
- 影响：`:app`
- 相关：[ADR-023](ADR-023-person-note-vs-body-and-scrollable-editor.md)

## 一、现象

录屏里点一下正文输入框，**整页被往上顶**：

1. 顶部「编辑人物 / 保存 / 返回」连同「称呼」「关系」一起滑出屏幕，
   想按保存得先把键盘收起来；
2. 页面不是平滑跟随，"咚"一下跳了约 200dp，看起来像卡顿；
3. 键盘弹出后正文编辑器还被键盘盖住一截。

## 二、原因

`windowSoftInputMode` 没声明（默认 `adjustUnspecified`），而 targetSdk 35
在 Android 15+ 上强制 edge-to-edge，窗口**没法再被缩小**。系统于是退回它最后的手段：
**平移整个窗口**，让获得焦点的光标露出来——被平移的是窗口本身，所以连固定表头一起走了。

同时各页面自己写的 `Modifier.imePadding()` 和根部的 `systemBars` 各算各的：
inset 分散在三处，谁也不知道键盘到底有没有被处理过。

## 三、决策

| 位置 | 做法 |
|---|---|
| `AndroidManifest` | 显式 `android:windowSoftInputMode="adjustResize"`：告诉系统"不要平移，按 resize 语义走" |
| `MainActivity` | `enableEdgeToEdge()`：应用明确接管系统栏，inset 才会以数据形式下发给 Compose |
| `MeteorRoot` | `windowInsetsPadding(WindowInsets.safeDrawing)`：**唯一**一处处理 inset（系统栏 + 挖孔 + 键盘） |
| 各页面 | **禁止**再写 `imePadding()` / `windowInsetsPadding()` |

`safeDrawing` = `systemBars ∪ displayCutout ∪ ime`，一次把"系统可能盖住我的地方"全占掉，
所以页面拿到的就是"真正可用的矩形"，不需要知道键盘在不在。

## 四、后果

- 键盘弹出时**页面不动**：固定表头留在原地，只有可伸缩的内容（编辑器）变矮。
- 键盘收起时反向恢复，不再有跳变。
- 状态栏/导航栏图标颜色跟随**应用主题**而不是系统主题（在 `MeteorTheme` 里设置
  `isAppearanceLightStatusBars`）：edge-to-edge 后背景是应用自己画的，
  浅色背景上的白色图标等于不存在。
- 新增页面默认就是"键盘安全"的，只要它不违反第四条。
- 代价：写在各页面里的 inset 逻辑（原本有 2 处）必须删掉，否则会双重留白。

## 五、验证方式

- CI 只能证明编译与单测；"键盘不再顶飞整窗"属于运行时行为，必须装机复核：
  点正文输入框 → 表头与「保存」仍在原位；收键盘 → 页面不跳。
