# ADR-044 · 冷启动阶段预热农历生日，Splash 等待预热完成

- 状态：已废止（被 ADR-047 取代，方案已从代码中移除）
- 日期：2026-11-20
- 关联：ADR-038（冷启动 Splash）、ADR-033（生辰页与提醒）

## 一、问题

装了出生日期、且选择农历的用户，冷启动后第一次切到生辰页会先看到一圈
`CircularProgressIndicator`，几百毫秒到数秒不等；进入设置改生日、再从生辰页
回来也可能再闪一次。普通公历用户则几乎无感。

复核代码后根因是明确的：

1. `LifeViewModel.refresh()` 先把 `LifePageState` 置为 `Loading`，再到 IO 线程
   读 `BirthdayProfileStore`，然后对 `BirthProfile.Lunar` 调
   `BirthdayAgeCalculator.nextBirthday` → `ChineseLunarCalendar.nextBirthday`。
2. `ChineseLunarCalendar.daysInYear()` 为每个公历年枚举 **约 14 个农历月、逐日**
   读 ICU 的 `ChineseCalendar` 字段（`ERA / YEAR / MONTH / DAY_OF_MONTH /
   IS_LEAP_MONTH`），一年 400+ 天就是 400+ 次 ICU 读取。
3. `nextBirthday` 覆盖今天前后 3–4 个公历年（`today.year - 1 .. today.year + 2`，
   有些年份还要再走 `toGregorian`），首次合计约 1500+ 次 ICU 读取，明显慢于
   普通资料读取。
4. `ChineseLunarCalendar` 的年份缓存当时是**实例字段**。`LifeViewModel` 每次新建
   自己的实例，`BirthDatePicker` 也 `remember { ChineseLunarCalendar() }`，
   冷启动没有任何一处提前做这份工作；谁先用到谁付全价。
5. Splash 的 `contentReady` 只等 `locked` 与 `onboardingGate` 解析，不等任何
   生辰数据；于是这段 ICU 时间直接落在生辰页的可见加载态上。

这段工作无法“做快”，但可以**提前**到用户本来就在看 Splash 的时间窗口里，
并让多个调用点共享同一份结果。

## 二、决定

### 1. 年份缓存改为进程级共享

`ChineseLunarCalendar` 的 `yearCache` 从实例字段移入 companion
`ConcurrentHashMap`，所有实例共享；保留 `MAX_CACHED_YEARS = 8` 的容量上限，
只在同步块里做“检查容量 → 清空 → 写入”，避免并发下把别的线程刚算好的年份清掉，
也避免重复枚举。类仍可无参构造，现有调用点、测试与构造方式不变。

这样 `LifeStartupPrewarm`、`LifeViewModel`、`BirthDatePicker` 即使各自持有一个
`ChineseLunarCalendar` 实例，也共享同一年份的枚举结果；`BirthDatePicker` 打开
农历滚轮时，当前年、前后几年通常已经在预热中算好。

### 2. 新增 `:app` 内的启动预热单例 `LifeStartupPrewarm`

位置：`app/src/main/kotlin/com/meteor/memory/ui/life/LifeStartupPrewarm.kt`，
`@Singleton`，由 Hilt 注入 `BirthdayProfileStore` 与 `AppLog`（`ChineseLunarCalendar`
在内部构造，共享的是上面的进程级缓存）。

- `start()` 幂等，在 `MeteorApp.onCreate()` 调用，尽量早于 `MainActivity` 的
  Splash 绘制；启动后立刻把活交给应用级 `CoroutineScope(SupervisorJob() +
  Dispatchers.Default)`，不挂在 Activity 生命周期上，Splash 退出不会取消它。
- 在 `Dispatchers.IO` 读 `BirthdayProfileStore`；若是农历，在
  `Dispatchers.Default` 调一次 `nextBirthday` 填充共享缓存。主线程不碰 ICU。
- 结果写入只读 `snapshot`（`@Volatile`）：`profile`、`nextBirthday`、计算时的
  `zone` 与 `capturedAt`。有出生资料就同时缓存公历/农历的 `nextBirthday`
  （公历分支很便宜，也一并缓存，逻辑更统一）。
- 对外暴露 `start()`、`ready: StateFlow<Boolean>` / `isReady`、`snapshot`。
  **失败、没有出生资料、读取出错也都会把 `ready` 置为 `true`**，`finally` 中
  记录一条耗时日志（`LifePrewarm`，如
  `life prewarm finished in 812ms result=lunar`），不输出生日、年份、日期。
- `updateAfterSave(profile)`：设置页/引导页保存新生日后调用，后台重算并替换
  快照；与本进程内“用户刚改生日”的路径对齐。

### 3. Splash 等待“根状态 + 预热 ready”

`MainActivity`：

```kotlin
installSplashScreen().setKeepOnScreenCondition {
    !contentReady || (!prewarmReady && !splashTimedOut)
}

// 在 super.onCreate() 与 Hilt 注入完成后镜像 ready：
lifecycleScope.launch {
    lifePrewarm.ready.collect { prewarmReady = it }
}
```

- `contentReady` 仍是 `MeteorRoot` 在 `locked != null && onboardingGate != LOADING`
  后置位（ADR-038），语义是“根状态已解析”，不是“已解锁”；
- `prewarmReady` 是 Activity 内的 `@Volatile` 标志，镜像
  `LifeStartupPrewarm.ready`。谓词在 `installSplashScreen()` 时安装、在
  `super.onCreate()` 之前，此时 Hilt 还没注入 `lifePrewarm`；用普通字段可以
  避免谓词依赖注入顺序。`ready` 是 `StateFlow`，`collect` 会立刻回放当前值；
- 保留 5 秒安全阀：到点后 `splashTimedOut = true` 并置 `contentReady`，即使预热
  因异常未完，也不会永久停在启动画面。预热失败/超时后生辰页回到自己的
  `Loading` → `Failed`/`Missing` 路径。
- Splash 期间**不渲染任何生日内容**：系统 Splash 只显示 `@drawable/splash_icon`，
  预热结果只留在内存；应用锁仍是 UI 门禁，`locked == null/true` 时不会因为预热
  而出现锁屏后的任何内容。

### 4. `LifeViewModel` 优先用预热快照，不再首帧闪 Loading

- 构造时用快照生成初始状态：快照属于当前时区、`capturedAt` 是今天、
  `nextBirthday` 非空时，直接用
  `BirthdayAgeCalculator.calculate(profile, now, zone, nextBirthday)` 得到
  `Ready`（这一步只有时间运算，没有 ICU）；没有快照或快照过期才回 `Loading`。
  快照 `profile == null` 时直接 `Missing`。
- `refresh()` 保留，仍然每次重新读 `BirthdayProfileStore`：
  - 读到的 `profile` 与快照一致、且快照是今天的，直接复用
    `snapshot.nextBirthday`，不重复 ICU；
  - 不一致（用户在设置里改了生日）就丢弃快照重算；若当前已显示 `Ready`
    且资料已变化，先回到 `Loading`，避免把旧生日继续展示；
  - 日期跨天、时区变化或生日已过时，按原有逻辑重算。
- 每秒 tick 的指标计算本来就不贵，保持不变；`LifePageState` 结构不变。

### 5. 数据与加密格式不变

不新增存储文件、DataStore key、字段或版本号；`BirthdayProfileStore` 的
AES-GCM 格式、备份覆盖范围都不动。预热只读同一份资料，写回仍然只发生在
用户保存生日的原有路径。

## R1 模块准入

1. **模块归属**：全部在既有的 `:app`（`ui/life` 包）与 `:app` 资源；不新增模块。
2. **依赖方向**：`LifeStartupPrewarm` 只用 `:app` 已有依赖（`BirthdayProfileStore`、
   `AppLog`、协程），不新增 `build.gradle.kts` 依赖，不新增模块间依赖边；
   `tools/check-module-graph.sh` 的允许边集合不变。
3. **对外接口**：新增一个 `:app` 内部单例（`start / isReady / ready / snapshot /
   updateAfterSave`），以及 `LifeViewModel` / 两个保存 ViewModel 的构造注入。
   未改任何 `FROZEN INTERFACE` 签名。
4. **数据格式**：无变化；不写新 key，不写新文件，向后兼容。

## 三、被否决的方案

- **把农历换算改成自研算法 / 预生成 1900–2200 年表**：能摆脱 ICU，但会引入
  一张需要长期校验的数据表，授权、闰月规则与边界都比“用系统 ICU”更难保证；
  本次目标只是把已有计算提前，不改换算来源。
- **在 `LifeClockScreen` 里加骨架屏/占位**：治标不治本，用户仍要等 ICU；
  且首页 Tab 的切换会放大闪烁。
- **只做进程级缓存、不做预热**：首次进入生辰页仍然要等一轮枚举；只有用户先打开
  出生日期滚轮时才有收益，顺序不可控。
- **Splash 固定延时等预热**：低端机/极端情况下不可控，且与 ADR-038 拒绝固定
  延时的理由冲突；改为等待明确的 `isReady` + 5 秒兜底。
- **在 `Application` 或预热里提前渲染生日数据**：会绕过应用锁的 UI 边界，
  绝对不做；预热只准备内存数据。

## 四、风险与已知限制

- 预热读的是**当前设备密钥**加密的出生资料。若 Keystore 不可用（首次解锁前、
  密钥损坏），预热会失败并记录日志，但不阻塞启动；生辰页会走原有 `Failed` 路径。
- 预热结果只在**进程内**。每次冷启动都会重算一次（这正是设计目标：把成本挪到
  Splash），进程被杀后内存快照消失。
- 5 秒安全阀先于预热完成时，生辰页可能重新出现一次 `Loading`，随后由 `refresh()`
  完成；不会卡死，也不会显示错误数据。
- 共享缓存容量仍是 8 年：用户如果在设置页快速拖过很多年，会触发清空并重算；
  这是原有的内存上限，不是本次新增的行为。
- 日志只记录耗时与结果类别（`solar` / `lunar` / `no-profile` / `failed`），
  不记录生日、年份、日期或任何档案内容。

## 五、验证方式

- `ChineseLunarCalendarTest`（instrumentation）扩展：重复调用、以及两个独立实例
  共享缓存后结果一致；不新增测试依赖。
- 本机不运行 Gradle（AGENTS.md §1）；编译、单元测试、Lint 与 instrumentation
  测试交给 GitHub Actions，最终以 CI 结果为准。
- 可在日志导出中按 `LifePrewarm` 标签检查预热耗时与结果类别，确认冷启动路径。
