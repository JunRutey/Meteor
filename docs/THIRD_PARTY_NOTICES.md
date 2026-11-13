# 第三方开源致谢

Meteor 的实现和发布流程依赖以下开源项目。这里列出直接依赖、测试依赖与主要参考项目；
传递依赖和构建产物的完整许可证以各上游仓库及 `gradle/libs.versions.toml` 为准。

## 运行时依赖

| 项目 | 用途 | 许可证 |
|---|---|---|
| [AndroidX / Jetpack](https://github.com/androidx/androidx)（Core KTX、Lifecycle、Activity Compose、Navigation Compose、DataStore、Security Crypto、Biometric） | Android 基础能力、状态管理、导航、偏好与生物识别 | Apache-2.0 |
| [Jetpack Compose](https://github.com/androidx/androidx)（Compose BOM、UI、Material 3、Animation、Material Icons） | 全应用 UI 与 Material 3 主题 | Apache-2.0 |
| [Kotlin](https://github.com/JetBrains/kotlin) | 应用与测试代码语言 | Apache-2.0 |
| [kotlinx.coroutines](https://github.com/Kotlin/kotlinx.coroutines) | 异步任务、流式读取与 IO 调度 | Apache-2.0 |
| [Hilt / Dagger](https://github.com/google/dagger) | 依赖注入，仅限 `:app` 组装边界 | Apache-2.0 |

## 测试依赖

| 项目 | 用途 | 许可证 |
|---|---|---|
| [JUnit 4](https://github.com/junit-team/junit4) | JVM 单元测试 | EPL-1.0 |
| [AndroidX Test](https://github.com/androidx/androidx) / [Espresso](https://github.com/androidx/androidx) | Android instrumentation 测试 | Apache-2.0 |
| [org.json](https://github.com/stleary/JSON-java) | JVM 测试中的 JSON 实现（Android 运行时使用系统 `org.json`） | JSON License |

## 构建工具

| 项目 | 用途 | 许可证 |
|---|---|---|
| [Gradle](https://github.com/gradle/gradle) | 构建与任务编排 | Apache-2.0 |
| [Kotlin Symbol Processing (KSP)](https://github.com/google/ksp) | Hilt 与编译期代码生成 | Apache-2.0 |

## 参考项目

以下项目用于 AI 技能的格式与交互设计参考，不是运行时依赖：

| 项目 | 参考内容 | 许可证 |
|---|---|---|
| [nuwa-skill](https://github.com/alchaincyf/nuwa-skill) | 人格蒸馏技能格式 | MIT |
| [distilly](https://github.com/titanwings/distilly) | 人格蒸馏与档案问答技能格式 | MIT |
| [ex-skill](https://github.com/therealXiaomanChu/ex-skill) | 人物对话技能格式 | MIT |

## 说明

- Android、Android SDK、Google Play 与 Material Design 是 Google LLC 的商标或作品；
  Android SDK 不属于上述开源许可范围。
- DeepSeek、OpenAI 等 AI 服务由各自提供方运营，不包含在本仓库的开源许可中。
- 如果发现遗漏或许可证标注有误，请提交 Issue 或 PR 更正。
