# 任务：add-react-loop

> 每条任务的「验收」都是可执行的：一条命令 + 一个判定条件。做不到这一点的任务不要列进来。

## 1. 工作区与 Profile

- [ ] 1.1 `lytgos init` 建出 `.lytgos/` 工作区
  - 9 个条目：`agents/` `skills/` `output/` `memory/` `sessions/` `logs/` `AGENTS.md` `SOUL.md` `USER.md`
  - 幂等：已存在的文件不覆盖
  - **验收**：空目录执行两次，`Get-ChildItem .lytgos -Recurse | Measure-Object` 计数两次相同

- [ ] 1.2 `Profile` 记录类型（不可变）
  - 字段：`name` `description` `identity` `provider` `tools` `mcpServers` `channels` `bootstrap` `settings`
  - **验收**：`ProfileTest` 构造后尝试改字段，编译期报错（record 或 final + 无 setter）

- [ ] 1.3 `AgentLoader` 解析 `AGENT.md` frontmatter
  - 按 `---` 切分 frontmatter 与正文，YAML 解析，映射到 `Profile`
  - 缺 `provider.name` 或 `provider.model` 抛 `InvalidAgentDefinitionException`，消息含缺失字段名
  - **验收**：给一份缺 `provider.model` 的 AGENT.md，异常消息含字面量 `provider.model`

- [ ] 1.4 `lytgos profile create / list / show / delete` 四个子命令
  - `create` 生成最小可用的 `AGENT.md` 模板
  - `delete` 删除整个 Agent 目录，先二次确认
  - **验收**：`create demo` → `list` 含 `demo` → `show demo` 输出 frontmatter → `delete demo` → `list` 不含 `demo`

**组验收**：`lytgos init` + `lytgos profile create demo` + `lytgos profile show demo` 三步全通，退出码均 0。

## 2. Provider

- [ ] 2.1 `ProviderRegistry` 从配置构造不可变 `Map<String, ChatModel>`
  - 配置节 `lytgos.providers.<name>.{model, api-key, base-url}`
  - 支持 `${ENV_VAR}` 占位，启动时解析
  - **验收**：配置两个 provider，`lytgos provider list` 输出两行

- [ ] 2.2 找不到 provider 时启动失败
  - `UnknownProviderException`，消息列出已注册的名字
  - **验收**：AGENT.md 写 `provider.name: not-exist`，启动退出码非 0，stderr 含 `not-exist` 和已注册 provider 名

- [ ] 2.3 环境变量缺失时启动失败
  - 消息指明缺哪个变量
  - **验收**：清空 `DEEPSEEK_API_KEY` 后启动，退出码非 0，stderr 含 `DEEPSEEK_API_KEY`

- [ ] 2.4 04 号闸门加一条规则：`lytgos-core` 不得依赖 Spring AI
  - **验收**：`harness/checks/04-dependency-direction.ps1` 退出码 0，且规则表里出现该条

**组验收**：`lytgos provider list` 正常输出；反例 2.2、2.3 均返回非 0 且错误消息可读。

## 3. ReAct 与 Tool

- [ ] 3.1 `Message` / `ToolCall` / `ToolResult` 三个记录类型
  - `Message` 带 `role`（system/user/assistant/tool）与 `content`

- [ ] 3.2 `PromptBuilder` 组装 prompt
  - system prompt（Profile identity + Bootstrap 文件）+ 历史 + 工具 schema
  - **验收**：单测断言输出包含 identity 文本与每个工具的 `name`

- [ ] 3.3 `ToolRegistry` + `Tool` 接口
  - `Tool`：`name()` / `schema()` / `execute(Map)`
  - **验收**：注册两个 Tool，`lytgos tool list` 输出两行含各自名字

- [ ] 3.4 `SandboxChecker` 实现三处校验
  - `checkPath`：`canonicalize()` 后比较前缀，禁止仅靠字符串 `contains("..")`
  - `checkCommand`：白名单
  - `checkDomain`：白名单，**空名单 = 全部拒绝**
  - **验收**：单测覆盖 `../` 穿越、绝对路径越界、非白名单域名三种拒绝

- [ ] 3.5 内置 `http_get` Tool
  - 执行前强制 `SandboxChecker.checkDomain()`
  - 超时上限 10 秒
  - **验收**：请求 `https://wttr.in/Beijing?format=3` 返回文本；请求 `https://example.com` 抛 `SandboxViolationException`，消息含 `sandbox`

- [ ] 3.6 `ReActLoop` 主循环
  - 组装 → 调 `ChatModel` → 判断有无 tool_call → 执行并回填 → 计数上限
  - 上限默认 10，可被 `Profile.settings.maxIterations` 覆盖
  - 达到上限返回当前已有文本并记 WARN 日志
  - **验收**：单测用假 `ChatModel`（第一次返回 tool_call，第二次返回文本）断言循环执行 2 次后返回

- [ ] 3.7 `ToolExecutor` 作为唯一工具出口
  - 所有工具调用必须先过 `SandboxChecker` 再执行
  - **验收**：`grep` 全仓 `Tool.execute(` 的直接调用点，只允许出现在 `ToolExecutor` 内

**组验收**：3.5 与 3.6 的单测全绿，`Tool.execute` 调用点唯一。

## 4. Channel、CLI 与装配

- [ ] 4.1 `Session` 内存实现 + `SessionStore`
  - 键：`channel + user + agent` 三元组
  - **验收**：同一键两次取到同一实例，不同键互不影响

- [ ] 4.2 `CliChannel` 交互循环
  - 读一行 → 调 `ReActLoop` → 打印 → 循环；输入 `exit` 退出
  - **验收**：手工跑一次，连续两轮能接上上下文

- [ ] 4.3 `lytgos chat --message "..."` 单条模式
  - 打印响应后退出，退出码 0
  - **验收**：`lytgos chat --message "hi"` stdout 非空，`$LASTEXITCODE` 为 0

- [ ] 4.4 `lytgos-boot` 装配
  - 读配置 → 构造 `ProviderRegistry` → 构造 `ToolRegistry` → 加载默认 Agent → 交给 `CliChannel`
  - **验收**：`java -jar lytgos.jar chat --message "hi"` 能跑通

- [ ] 4.5 Spring AI 只用 `ChatModel`
  - 全仓无 `ChatClient` / `ToolCallingManager` / `@ConditionalOnBean` / `ObjectProvider` / `getBeansOfType`
  - **验收**：04 号闸门退出码 0

**组验收**：`lytgos chat --message "北京今天穿什么"` 输出含穿衣建议，控制台日志出现 `tool_call: http_get` 至少一次。

## 5. 验收脚本

- [ ] 5.1 新增 `harness/checks/07-week1-acceptance.ps1`
  - 六条检查，逐条打印 `PASS`/`FAIL`，任一 FAIL 时退出码 1
  - **验收**：脚本自身可跑，全绿时退出码 0

- [ ] 5.2 把 07 登记进闸门清单
  - `harness/README.md` 与 `AGENTS.md` 末行的 `harness-checks:` 注释同步
  - **验收**：`00-layout-check.ps1` 退出码 0（它会比对清单与实际文件）

## 本周验收总表

跑到这一步，下面六条必须全绿才算第 1 周完成。

| # | 检查 | 命令 | 通过条件 |
|---|---|---|---|
| 1 | 工作区初始化 | `lytgos init` | 9 个条目创建，退出码 0 |
| 2 | 幂等 | 改 `AGENTS.md` 一行后重跑 `lytgos init` | 改动仍在，未被覆盖 |
| 3 | 单轮对话 | `lytgos chat --message "北京今天穿什么"` | stdout 含温度或穿衣建议，退出码 0 |
| 4 | 反例·缺 key | 清空 `DEEPSEEK_API_KEY` 后启动 | 退出码非 0，stderr 含变量名 |
| 5 | 反例·沙箱 | 让 Agent 请求非白名单域名 | 被拒，消息含 `sandbox` |
| 6 | 多轮上下文 | 交互模式连问两轮，第二问用「那」指代 | 第二轮能理解指代对象 |

第 4、5 条是反例，**必须先通过再做其它**——默认拒绝比默认放行重要。

## 完成判定

- `mvn clean package` 退出码 0
- `07-week1-acceptance.ps1` 六条全 PASS
- `00-layout-check.ps1`、`03-static-guard.ps1`、`04-dependency-direction.ps1` 均退出码 0

全绿之前不 `archive`，不开下一条 change。
