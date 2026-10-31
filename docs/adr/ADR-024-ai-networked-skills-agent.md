# ADR-024 · AI 改为「可关闭的联网提供商 + 技能驱动」，取代 ADR-023 的纯离线引擎

- 状态：已接受（v0.6 修订）
- 取代：[ADR-023](ADR-023-ai-local-deterministic-persona.md)（本地确定性引擎）
- 影响：`:feature-ai`、`:feature-settings`、`:core-crypto`、`:app`；新增 `INTERNET` 权限

## 背景

ADR-023 把 v0.6 的 AI 限定为「本机确定性检索」，理由是仓库不允许入库 API key、
本机禁止构建无法验证模型 SDK、CI 无法验证联网行为。这个方案能跑、能测，但它不是
产品要的 AI：不能做真正的人格蒸馏，也不能自然对话。

v0.6 的目标是：**人格蒸馏（把一个人蒸馏成可对话的 Persona）与对话**。这需要模型。
因此改成「默认关闭、用户显式开启的联网提供商」，并把风险装进明确的产品边界里：

- 只有用户自己填的 API Key、自己选的提供商，Meteor 不与任何服务器通信；
- 作者（本项目）没有任何后端，**不接收、不收集、不记录**任何内容，也没有埋点/跟踪；
- 开启时必须弹窗说明「内容会通过 API 发送到 AI 提供商的服务器」，用户同意后才生效；
- 不做历史持久化，问答与对话不落盘、不进日志；
- 这个开关放在设置里，随时可关；关闭后 AI 入口消失，档案与其它功能不受影响。

## 决策

1. **联网但由用户配置**：只支持 OpenAI 兼容的 `POST {base}/chat/completions`
   （内置 DeepSeek / OpenAI 预设，另可自定义 base URL 与模型名）。API Key 存进
   `core-crypto` 的 EncryptedSharedPreferences 命名空间 `meteor_ai_credentials`
   （archive §6 早已预留该命名空间），不写进 DataStore、不进备份、不进日志。
2. **技能（Skill）驱动**：把「人格蒸馏」「人格对话」「档案问答」做成 Markdown 技能包
   （frontmatter + prompt 正文），随包内置在 `:feature-ai` 的 assets 里，可按技能名启用。
   格式参考并致谢 MIT 许可的
   [nuwa-skill](https://github.com/alchaincyf/nuwa-skill)、
   [distilly](https://github.com/titanwings/distilly)、
   [ex-skill](https://github.com/therealXiaomanChu/ex-skill)：分层 Persona
   （硬规则 → 身份 → 说话风格 → 情感模式 → 关系行为 → 关系记忆）、校正记录、
   以及「只提炼 HOW，不编造 WHAT」的诚实边界。
3. **受控 Agent（只读）**：模型可以在有限步数内调用**白名单只读工具**读取档案：
   列出人物、读人物档案、列某人相关记录、读单条记录、读心情区间、全档案检索。
   没有写、删、导出、发消息、联网抓取、执行命令等能力；蒸馏产物是唯一写操作，
   且写的是既有的 `数字遗产/数字人格/<person_id>/persona.md`（加密记录）。
4. **数据最小化**：每次请求只带当前任务需要的上下文；工具结果截断；请求与响应
   不写日志（错误只报 HTTP 状态码与错误类型，绝不带正文）。
5. **默认关闭 + 显式同意**：`aiEnabled` 默认 `true`（v0.6 起入口可见），但首次开启
   必须通过同意弹窗；同意状态单独持久化。未配置 Key 时 AI 页面只显示配置引导。

## R1 五问（AGENTS §3）

1. **属于哪个模块？** 仍是 `:feature-ai`（联网客户端、技能、Agent、Persona 都在这里）；
   唯一新增的跨模块能力是 `:core-crypto` 的 `SecretStore`（凭据加密存储）与
   `:feature-settings` 的几个偏好字段。
2. **依赖谁？谁依赖它？** `:feature-ai → :core-storage`（唯一新增边仍只有这一条；
   `SecretStore` 由 `:app` 注入接口，不新增模块边）。`:app → :feature-ai` 已有。
   无环，`tools/check-module-graph.sh` 已登记。
3. **对外接口？** 只有 `AiModule`（技能列表、人格读取/蒸馏、对话、本机检索）
   和 `SecretStore`。HTTP、JSON、工具循环、技能解析全在模块内部。
4. **移除后还能编译/读数据吗？** 能。删掉 `:feature-ai` 与 `:app` 入口即可；
   已蒸馏的 `persona.md` 是普通 `type: persona` 记录，其它模块照常读取，
   备份/导出不受影响。
5. **数据格式变了吗？** frontmatter 仍是冻结五字段。`persona.md` 头部扩展出
   `provider`、`skill` 等 ASCII 键（旧解析器忽略未知键），正文放模型产出的
   分层 Persona + 关系记忆。向后兼容，不需要迁移。

## 隐私边界（写进 UI 与 README 的承诺）

- Meteor 没有后端。作者**不收集、不记录、不跟踪**任何用户内容或使用行为，
  也不提供任何统计/崩溃上报 SDK。
- 开启 AI 后，用户输入、被选中的档案摘录与技能提示词会**通过 HTTPS 直接发往用户
  自己配置的 AI 提供商**；这些数据受该提供商的隐私条款约束，与作者无关。
- API Key 只在本机加密存储；聊天记录只在内存中，退出页面即丢；日志只记事件与错误码。
- 关闭开关后不再有任何网络请求（应用也只在 AI 开启时发请求）。

## 后果

- 引入了真实的隐私取舍：内容会离开设备。因此加入同意弹窗、默认关闭网络请求、
  最小上下文、只读工具白名单，并在 README/发布说明中直说。
- 应用重新需要 `INTERNET` 权限（此前 v0.1 的注释是「v0.6 AI 之前不联网」）。
- 需要用户自备 API Key 并承担调用费用；没有 Key 时 AI 功能不可用，其余功能不受影响。
- CI 只能验证编译、JSON 协议、工具循环、技能解析与同意门控；真实联网行为无法在 CI 验证，
  这一点在发布说明里明确标注。

## 增补（v0.6.1）

- **时间查询**：问题里的时间表达由本机 `TimePhrase` 解析成日期窗口，直接取该窗口的
  日记/聊天记录；同时新增 `read_diary`、`list_diary_dates`、`list_records(from,to)`、
  `read_moods(days)`，并把「当前日期 + 档案概况」写进系统提示。只读工具从 6 个增至 8 个，
  仍然没有任何写/删/导出能力。
- **用量透明**：协议层解析 `usage`（含 DeepSeek 与 OpenAI 两种缓存字段），Agent 跨工具轮
  累加；UI 展示耗时、tokens、tok/s 与缓存命中率。只展示计数，不记录内容。
- **思考档位**：`ThinkingMode` 仅在 DeepSeek 上发送 `thinking` / `reasoning_effort`；
  `auto` 不发送任何额外字段以保持兼容。带工具的思考请求会回传 `reasoning_content`
  （DeepSeek 的硬性要求）。
- **凭据加密**：API Key 改用档案同一把 Keystore AES-256-GCM 密钥加密后存成 Base64 密文，
  不再使用独立的 EncryptedSharedPreferences；旧值首次读取时迁移并删除。
