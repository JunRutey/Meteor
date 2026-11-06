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
