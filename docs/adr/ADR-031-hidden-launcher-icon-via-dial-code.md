# ADR-031 · 隐藏桌面图标，用拨号暗号打开应用

- 状态：已接受
- 日期：2026-11-06
- 影响：`:app`、`:feature-settings`
- 关联：ADR-024（`MainActivity` 入口与 inset）、ADR-027（隐私边界）

## 一、需求

设置 → 安全里增加「隐藏桌面图标」。打开后：

- 桌面与应用列表都不再出现 Meteor；
- 在系统拨号盘输入 **`*#*#314159#*#*`** 可直接**打开应用**（不是打开应用设置）；
- 回到应用后可以随时把开关关掉，图标重新出现。

## 二、机制

| 部分 | 做法 |
|---|---|
| 桌面入口 | `AndroidManifest` 里的 `activity-alias`：`.LauncherEntry`，`targetActivity=".MainActivity"`，带 `MAIN`/`LAUNCHER` |
| 隐藏 / 显示 | `PackageManager.setComponentEnabledSetting(LauncherEntry, DISABLED/ENABLED, DONT_KILL_APP)` |
| 暗号入口 | `SecretCodeReceiver` 接收系统派发的 `android.provider.Telephony.SECRET_CODE`（data `android_secret_code://314159`），显式启动 `MainActivity` |
| 偏好 | `AppSettings.hideLauncherIcon`（DataStore，默认 `false`） |

`MainActivity` 本身**不再带** `MAIN`/`LAUNCHER`。原因：如果直接把 `MainActivity`
禁用，暗号接收器也启动不了它，用户就真的进不来了。桌面图标只是别名，
主 Activity 必须保持可用。这与 AOSP Settings 处理 `*#*#4636#*#*` 的方式一致
（`TestingSettingsBroadcastReceiver` → 启动 `TestingSettingsActivity`）。

`MainActivity` 保留 `android:exported="true"`：升级前创建的桌面快捷方式指向的是
这个组件，保留下旧快捷方式仍能点开，不会因为换入口而全部失效。

## 三、R1 五问

1. **属于哪个模块？** 并入现有模块：开关与偏好进 `:feature-settings`，
   manifest 组件、`PackageManager` 调用、暗号接收器进 `:app`。不新增模块。
2. **依赖谁？是否单向、成环？** 没有新增依赖边。`:app → :feature-settings` 已登记；
   `:feature-settings` 仍只依赖 `:core-crypto` / `:core-backup`。无环。
3. **对外接口？** `AppSettings.hideLauncherIcon`、`SettingsRepository.setHideLauncherIcon`、
   `SettingsScreen(onHideLauncherIconChanged)`；`LauncherIconController` 只存在于 `:app`。
   接口足够窄，仍是「一个布尔偏好 + 一个回调」。
4. **移除模块后？** 移除 `:feature-settings` 后没有任何界面能改这个偏好，
   默认 `false` = 图标始终显示；`:app` 里的别名与接收器仍在但不会被触发。
   其余模块照常编译，已有数据照常读取。
5. **数据格式变更？** 新增一个 boolean 偏好键 `hide_launcher_icon`，缺省 `false`。
   旧安装升级后行为不变；旧版本读到新键会忽略。向后兼容。

## 四、唯一写入者

偏好由 `SettingsViewModel` 写入，**组件状态只由 `RootViewModel` 写**：
它像镜像 `AppLock` 一样，在每次进程启动时把 `hideLauncherIcon` 同步到别名
（值相同时不写，避免无意义的 `PackageManager` 调用）。这样：

- 冷启动后开关与真实图标状态不会漂移（数据恢复、个别启动器在包更新后
  重置组件状态都能被纠正）；
- 设置页与组件状态之间只有一个写入点，不需要两套状态机。

## 五、代价与已知限制

- **不是所有拨号盘都派发暗号**。AOSP / 多数国产 ROM 支持；部分 OEM（如部分三星
  机型、部分深度定制拨号器）会拦截。隐藏后若暗号不生效，用户只能重装应用，
  而 `allowBackup=false` 且密钥绑定设备，**本地数据会一起丢失**。因此打开开关
  前有确认弹窗，明确写出暗号并要求先导出备份。
- **不要「强制停止」Meteor**。应用处于 stopped 状态时不会收到 manifest 广播
  （系统广播默认排除已停止的包），暗号唤不醒它。`MainActivity` 保持
  `exported="true"`，所以升级前留下的桌面快捷方式、第三方快捷方式工具或
  `adb shell am start -n <包名>/com.meteor.memory.MainActivity` 仍能**显式启动**
  应用并清除该状态——这是本方案保留的后门。
- 组件启用状态是设备级的，不参与备份导出；换机/重装后默认显示图标。
- 部分启动器在组件被禁用后要等下一次刷新才移除图标（通常立即生效）。
- 暗号由系统以受保护广播派发，应用侧只能被动接收；没有额外权限可以「保证」
  任何一台设备都支持。

## 六、验证方式

CI 只能证明编译、单测与 lint。运行时行为需要装机复核：

1. 打开「隐藏桌面图标」→ 确认弹窗 → 隐藏：桌面与应用列表中的 Meteor 消失，
   当前会话不被杀掉（`DONT_KILL_APP`）。
2. 拨号盘输入 `*#*#314159#*#*` → Meteor 打开；若开了应用锁，仍先要求解锁。
3. 回到设置关掉开关 → 图标重新出现，无需重启应用。
4. `adb shell dumpsys package com.meteor.memory | grep LauncherEntry`
   应看到 disabled-components 里有该别名（debug 包为 `com.meteor.memory.debug`）。

## 七、修订 · 实机复核（v0.8.0 之后）

v0.8.0 上线后在小米澎湃 3（HyperOS，Android 16）复核：打开开关后隐藏正常，但拨号唤不起。
拆开是两个独立问题，都已修：

1. **暗号的 action 变了。** Android 10 起系统派发的是
   `android.telephony.action.SECRET_CODE`；`android.provider.Telephony.SECRET_CODE`
   只是旧值。v0.8.0 只声明了旧值，所以按新值派发的系统上接收器根本不会被调用。
   现在 manifest 两个 filter 都声明，`SecretCodeReceiver` 两个 action 都接受
   （与 InstallerX-Revived 的处理一致）。
2. **澎湃把「后台启动」做成了两个用户开关。** 实测：给 Meteor 打开「自启动」和
   「后台弹出界面」后，暗号能正常唤起；两项没开时，即使接收器收到广播，
   `startActivity` 也会被系统丢弃，而且**不抛异常**（只有 logcat 里
   `Background activity start ... blocked`）。因此：
   - 确认弹窗新增这两个开关的说明与跳转入口（`LauncherPermissionGuide`）：
     `miui.intent.action.APP_PERM_EDITOR`（带 `extra_pkgname`）与
     `miui.intent.action.OP_AUTO_START`，两者失败都回退到系统「应用详情」页；
   - `SecretCodeReceiver` 把「收到暗号 / 启动失败」写进应用日志，
     让「按了没反应」能区分「广播没来」与「广播到了但被系统拦下」。

这两项都不是公开 API，也不做权限值探测：MIUI 的两个开关查不到、AppOps 编号是
私有的，硬编码只会在下次系统更新时静默失效；能做的只有「识别 ROM + 把用户送到该去
的页面 + 留下日志」。

结论没有变化——图标仍然是别名、`MainActivity` 始终可被显式启动——但「能不能找回」
的前提现在写清楚了：**拨号盘派发暗号 + 系统允许后台启动**，缺一不可。

### R1 五问（本次修订）

1. 属于哪个模块？仍是 `:app`（`LauncherPermissionGuide`、接收器、manifest）
   与 `:feature-settings`（弹窗文案与两个回调），不新增模块。
2. 依赖谁？无新增依赖边：`:feature-settings` 只多两个 `() -> Unit` 参数，
   不认识 MIUI；ROM 判断与 Intent 都在 `:app`。
3. 对外接口？`SettingsScreen` 多三个带默认值的参数，旧调用点不需要改。
4. 移除该模块后？删除 `:app` 的 guide 与两个回调，弹窗退化为纯确认框，仍可编译。
5. 数据格式变更？没有，偏好键不变。


## 八、修订 · v0.9.0 之后（隐藏后暗号仍唤不起）

v0.9.0 发布后再次复现：用户已授权「自启动」等权限，隐藏图标后拨号仍无反应。
复核后确认，v0.8.1 的修复解决了「广播收不到」，但没有解决「收到了也起不来」。

### 8.1 根因：后台启动（BAL）被静默丢弃

`SecretCodeReceiver` 是后台广播接收器。Android 10 起，后台进程调用
`startActivity` 需要 BAL（background activity start）豁免；没有豁免时系统
**静默丢弃**这次启动——`startActivity` 不抛异常，接收器日志也只有
「launch requested」。所以「广播到了」不等于「界面起来了」。

AOSP 的公开豁免之一是 `SYSTEM_ALERT_WINDOW`（「显示在其他应用上层」）：
Android 15 / 16 的 `BackgroundActivityStartController` 仍以
`BAL_ALLOW_SAW_PERMISSION` 放行。v0.8.x / v0.9.0 从未声明它，因此在原生 /
类原生系统上暗号本来就是死路；在 MIUI / 澎湃上则由厂商的「后台弹出界面」
单独决定。

MIUI 的闸门是它自己的 AppOp（`10021`，即「后台弹出界面」），AOSP 没有这个
编号。v0.8.1 的结论「查不到、不要碰」过于悲观：AutoJs6、LinkSheet、Oolong
都用 `checkOpNoThrow` 读取这个 op 并深链到权限页。它仍然是私有实现，因此
本版**读取失败一律返回 `UNKNOWN`**，交给用户测试兜底，绝不猜。

InstallerX-Revived 与 Amarok-Hider 使用与本项目相同的
`activity-alias` + `SecretCodeReceiver` 方案，也都只能提示用户先确认能回来，
无法从代码上保证；Amarok 的 issue #264 中同款失败日志正是
`MIUILOG- Permission Denied Activity` + `Abort background activity starts`。

### 8.2 改动

- manifest 声明 `SYSTEM_ALERT_WINDOW`。它只用于取得 BAL 豁免，应用不绘制
  任何悬浮窗。
- `LauncherPermissionGuide`：
  - `backgroundStartState()`：非 MIUI 用 `Settings.canDrawOverlays()`；MIUI 读
    AppOp `10021`；读取异常返回 `UNKNOWN`（不误报）。
  - `openBackgroundStartSettings()`：MIUI 优先用显式组件
    `PermissionsEditorActivity` / `AppPermissionsEditorActivity`（带
    `extra_pkgname` / `extra_package_name` / `extra_package_uid`），失败再回退
    到「显示在其他应用上层」与应用详情。
  - 自启动入口补 `package_name` 与显式组件，减少落到安全中心首页的概率。
- 设置页确认框：
  - 显示 `READY` / `BLOCKED` / `UNKNOWN`，`BLOCKED` 时给出对应授权入口；
  - **必须真的成功过一次**：`SecretCodeReceiver` 的启动 Intent 带
    `EXTRA_DIAL_CODE`，`MainActivity` 真正起来时调用
    `DialCodeLaunchRecorder.recordSuccess()`；设置页只有在本进程内 10 分钟内
    有成功记录时才允许隐藏。被系统丢弃的启动根本到不了 Activity，所以这条
    记录同时验证了「拨号盘派发」与「后台启动」；勾选框做不到这一点。
  - 设置页 `ON_RESUME` 重新读取状态，从系统页或拨号盘返回后立即生效。
- 暗号启动改用 `NEW_TASK | CLEAR_TOP | SINGLE_TOP`。应用只有一个 Activity，
  `CLEAR_TOP` 不会销毁正在看的界面，但能让已有任务收到 `onNewIntent`——否则
  系统只是把任务提到前台，`MainActivity` 根本不会收到 Intent，也就没有「成功」
  的可观测信号。
- `MainActivity` 收到暗号 Intent 时写一条「opened by dial code」日志。配合
  接收器的「launch requested」，日志能区分「广播没来」「广播来了但启动被丢弃」
  「启动成功」。
- `LauncherRecoveryGuard`（`MY_PACKAGE_REPLACED`）：更新可能重置厂商
  「后台弹出界面」。更新后若图标仍隐藏、而后台启动状态不是 `READY`，就恢复
  图标并把 `hideLauncherIcon` 写回 `false`，避免升级反而把用户锁在门外。
  `UNKNOWN` 也按「不可证明」处理：图标重新出现可以再藏，进不去则不可逆。

### 8.3 代价与限制

- 新增 `SYSTEM_ALERT_WINDOW` 特殊权限：它在这里仅作为 Android 的 BAL 豁免，
  不绘制悬浮窗；不授权时，非 MIUI 设备的隐藏入口会被拦下——因为那条路本来
  就唤不回。
- MIUI 的 `10021` 是私有实现，可能随 ROM 变化；读取失败只会退化为
  `UNKNOWN`，由用户测试兜底，不会误报 `READY`。
- 成功记录只存在于当前进程（10 分钟）；进程被回收后要重新测一次，避免旧记录
  在权限被重置后仍然放行。
- 拨号盘是否派发暗号仍不可控；执行「强制停止」后暗号仍无效。
- 反射只用于读取状态，不修改任何系统设置。
- 更新后若状态为 `UNKNOWN`，图标会重新出现一次；这是刻意选择的安全侧。

### 8.4 R1 五问（本次修订）

1. 属于哪个模块？仍是 `:app`（guide、manifest、receiver、activity）与
   `:feature-settings`（`LauncherRecovery`、确认框文案），不新增模块。
2. 依赖谁？`:app → :feature-settings` 已存在；`feature-settings` 不认识 AppOps，
   只接收一个枚举。无新增依赖边、无环。
3. 对外接口？`LauncherPermissionGuide.backgroundStartState()`、
   `SettingsViewModel.launcherRecovery`；`SettingsScreen` 新增两个带默认值的
   参数。接口仍然很窄。
4. 移除该模块后？`LauncherRecovery` 随 `:feature-settings` 消失，`:app` 的
   guide 与接收器仍在但不会被触发，图标始终显示；其余模块照常编译。
5. 数据格式变更？没有，不新增偏好键。
