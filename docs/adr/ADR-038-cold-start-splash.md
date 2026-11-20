# ADR-038 · 冷启动用系统 Splash 显示应用图标，根状态就绪后再进入内容

- 状态：已接受
- 日期：2026-11-20
- 关联：ADR-024（edge-to-edge 与 insets）、ADR-031（隐藏桌面图标）、
  ADR-044（冷启动预热农历生日）

## 背景

Meteor 的冷启动需要先异步读两件事才能决定首屏：

1. `SettingsRepository` 里的应用锁开关（`RootViewModel.locked` 在解析完成前是 `null`）；
2. 首次引导是否完成（`OnboardingGate.LOADING` 到 `REQUIRED` / `COMPLETE` / `FAILED`）。

在这段时间里 `MeteorRoot` 渲染 `StartupPlaceholder`——只有一行「Meteor」。用户看到的
是：桌面图标 → 短暂白屏 / 一行应用名 → 锁屏或首页。这个过程既不好看，也容易让人以为
应用卡住了；如果平台先绘制了 Activity 的窗口背景，还可能露出一帧未解析的界面。

## 决定

### 1. 引入 `androidx.core:core-splashscreen:1.0.1`

`gradle/libs.versions.toml` 新增 `splashscreen = "1.0.1"` 与
`androidx-core-splashscreen`，`:app` 通过 `implementation(libs.androidx.core.splashscreen)`
依赖。这是**新增依赖**，不涉及 AGP / Kotlin / Gradle / Compose BOM 升级。

选择它而不是手写 `windowBackground` 或 Android 12 专属 API：

- 一套代码覆盖 API 26–35（minSdk 26），Android 12 以下由库回填；
- 使用系统 splash 的居中图标与背景，和 InstallerX-Revived 等应用的做法一致；
- 不引入 Activity 重建或额外的启动 Activity。

### 2. 主题与 Manifest

`app/src/main/res/values/themes.xml` 新增：

```xml
<style name="Theme.Meteor.Splash" parent="Theme.SplashScreen">
    <item name="windowSplashScreenBackground">@color/splash_background</item>
    <item name="windowSplashScreenAnimatedIcon">@drawable/splash_icon</item>
    <item name="postSplashScreenTheme">@style/Theme.Meteor</item>
</style>
```

- `splash_background` 在 `values/colors.xml` 为 `#F4F8FC`（与 `MeteorLight.background`
  一致），在 `values-night/colors.xml` 为 `#0F1720`（与 `MeteorDark.background` 一致），
  避免启动时闪一下相反的颜色；
- 图标使用**专用的高分辨率启动图标** `@drawable/splash_icon`，不再复用
  `@mipmap/ic_launcher`（理由见 2.1）；
- `postSplashScreenTheme` 指回 `Theme.Meteor`，系统 splash 退出后应用恢复正常主题。

### 2.1 专用启动图标：分辨率、透明背景与分工

最初直接用自适应启动图标 `@mipmap/ic_launcher`，实际效果模糊且对比不足：

- 自适应图标的前景 `ic_launcher_foreground.png` 在 xxxhdpi 只有 432×432，
  legacy `ic_launcher.png` 只有 192×192。系统 Splash 按 288dp 画布绘制、再裁剪到
  192dp 圆，xxxhdpi 下需要约 1152px 的源图，放大 2.7 倍后边缘与星形明显发糊。
- 前景 PNG 虽然四角透明，但把一块白色圆角方底烤进了像素里（约 87% 像素 alpha=255，
  背景接近纯白）。如果直接放大，深色纸张背景上的 Splash 会顶着一块白色方卡，
  浅色背景下又几乎和底色融在一起。

因此单独生成 `app/src/main/res/drawable-xxxhdpi/splash_icon.png`：

- **尺寸 1152×1152（= 288dp @xxxhdpi）**，正好对应系统 Splash 的图标画布；
  可见内容控制在中心 **192dp 圆（768px 直径）** 的安全区内，四周留出
  ≥262px 的透明边距，避免被系统圆形蒙版裁掉。
- **真正透明**：四角与边距 alpha=0，没有把白色圆角方底带进来；
  流星与两颗星光来自启动图标前景，按饱和度从白底中分离，并保留流星内部的
  白色星光（作为被流星轮廓包住的内部细节），再 LANCZOS 等比放大。
- 生成后以 light（`#F4F8FC`）与 dark（`#0F1720`）两种纸张背景合成检查：
  蓝色→紫色流星在两种底色上都清晰，深色背景下不再出现白色方卡。
- 不设置 `windowSplashScreenIconBackgroundColor`：图标本身透明，
  再加一个实心圆底只会多一层不必要的色块。

**分工**：`@mipmap/ic_launcher` 仍是桌面/最近任务/系统设置里的应用图标，
带自己的白色自适应底、由系统做遮罩；`@drawable/splash_icon` 只服务冷启动，
是为 Splash 画布和安全区单独准备的透明高分辨率版本。两者视觉同源
（同一颗流星与星光），但不共享同一个位图资源，避免为了启动清晰度去改桌面图标，
也避免桌面图标的自适应底再次被烤进 Splash。

`AndroidManifest.xml` 只把 `MainActivity` 的 `android:theme` 指向
`@style/Theme.Meteor.Splash`；`application` 的默认主题保持 `Theme.Meteor`，其它组件
（provider、receiver）不受影响。

### 3. `MainActivity` 在 `super.onCreate` 前安装，ready 标志控制退出

```kotlin
override fun onCreate(savedInstanceState: Bundle?) {
    installSplashScreen().setKeepOnScreenCondition { !contentReady }
    super.onCreate(savedInstanceState)
    ...
    setContent {
        MeteorRoot(versionName = BuildConfig.VERSION_NAME, onContentReady = { contentReady = true })
    }
}
```

- `installSplashScreen()` 必须在 `super.onCreate` 之前调用，库才会接管当前启动窗口；
- `MainActivity` 本身是 `FragmentActivity`（冻结的 `AppLock.unlock(activity:
  FragmentActivity)` 要求），`FragmentActivity` 继承 `ComponentActivity`，
  core-splashscreen 的扩展函数可直接使用；
- `contentReady` 是 Activity 内的 `@Volatile` 标志，默认 `false`；
- 另外加一个 5 秒安全阀：若根状态因为异常始终不解析，也放行 splash，避免永久卡在
  启动画面。放行只代表「可以显示 Shell」，**不绕过应用锁**：`RootViewModel.locked`
  仍决定渲染锁屏、未解锁时不会出现任何明文内容。
- ADR-044 之后，这个条件还额外等待 `lifePrewarm.isReady`，并用
  `splashTimedOut` 让安全阀同时放行预热等待。完整的
  `setKeepOnScreenCondition` 与预热细节见 [ADR-044](ADR-044-startup-life-prewarm.md)；
  上面保留的是引入预热前的版本。

### 4. `MeteorRoot` 在根状态解析后回调一次

新增参数：

```kotlin
fun MeteorRoot(versionName: String, onContentReady: () -> Unit = {})
```

在收集到 `locked` 与 `onboardingGate` 后用 `LaunchedEffect` 判断：

```kotlin
if (!contentReadyReported && locked != null && onboardingGate != OnboardingGate.LOADING) {
    contentReadyReported = true
    onContentReady()
}
```

- `locked != null` 表示应用锁偏好已解析；
- `onboardingGate` 离开 `LOADING` 表示引导状态已解析（`REQUIRED` / `COMPLETE` / `FAILED`
  都算解析完成，失败页也是明确的界面）；
- 只回调一次；配置变更后 splash 不会重新出现（系统只在冷启动绘制 splash）。

### 5. 为什么不用「锁定态就绪」之外的信号

没有用 `setContent` 或 `onResume` 作为 ready 信号：这两个时机早于 DataStore 解析，
会重新露出 `StartupPlaceholder`。也没有用固定的延时，因为低端机和首次安装（引导迁移）
的耗时不固定。

## R1 模块准入

1. **模块归属**：主题、Manifest、`MainActivity`、`MeteorRoot` 全部在既有的 `:app`。
   不新增模块。
2. **依赖方向**：`:app` 新增一个 AndroidX 库依赖，不新增模块间依赖边，不改变
   `tools/check-module-graph.sh` 的允许边集合。
3. **对外接口**：只给 `MeteorRoot` 增加带默认值的 `onContentReady` 回调；没有改
   `RootViewModel`、`AppLock` 或任何冻结接口。
4. **数据格式**：没有数据格式变化；不写 DataStore，不读新 key。

## 已知限制

- 启动图标的形状/裁剪由系统 splash 决定；Android 12 以下由 core-splashscreen 尽力
  回填，效果与 Android 12+ 不保证逐像素一致。
- 专用图标是静态位图，不会随 `@mipmap/ic_launcher` 自动更新；品牌标志若改版，
  需要重新生成 `drawable-xxxhdpi/splash_icon.png`（尺寸与安全区见 2.1）。
- 5 秒安全阀是兜底：如果 DataStore 真的损坏，用户最多看 5 秒图标，然后看到已有的
  引导加载失败页，而不是永久停在启动画面。
- splash 期间窗口仍设置 `FLAG_SECURE`（`MainActivity` 在 `super.onCreate` 后立即加），
  启动画面不含任何档案内容，应用锁逻辑不变。
- 该方案只覆盖冷启动；从最近任务恢复时由系统直接显示既有快照，不重新播 splash。
