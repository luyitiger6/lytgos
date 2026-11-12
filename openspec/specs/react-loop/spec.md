## Purpose

Agent 运行时内核：工作区初始化、Agent 定义加载、Provider 显式映射、ReAct 循环、工具执行与沙箱、会话上下文与命令行对话。本基线来自 `add-react-loop`，于 `v0.1.0` 落定。

## Requirements

### Requirement: 工作区初始化

系统 SHALL 提供 `lytgos init` 命令，在当前目录创建 `.lytgos/` 工作区，包含 `agents/`、`skills/`、`output/`、`memory/`、`sessions/`、`logs/` 六个目录，`AGENTS.md`、`SOUL.md`、`USER.md` 三个 Bootstrap 文件，以及 `config.yaml`（与「Provider 显式映射」一项所需的 `lytgos.providers` 配置节同属一份文件）；`memory/` 下同时创建空的 `MEMORY.md`。

该命令 MUST 幂等：已存在的目录与文件一律不覆盖。

#### Scenario: 在空目录初始化

- **WHEN** 在空目录执行 `lytgos init`
- **THEN** 生成 10 个顶层条目，退出码 0

#### Scenario: 重复初始化不覆盖已有文件

- **WHEN** 已存在 `.lytgos/AGENTS.md` 且内容被修改过，再次执行 `lytgos init`
- **THEN** 该文件内容保持不变，退出码 0

### Requirement: Agent 定义加载

系统 SHALL 从 `.lytgos/agents/<name>/AGENT.md` 加载 Agent 定义。文件由 `---` 分隔的 YAML frontmatter 与正文组成，frontmatter 解析为 `Profile`，正文作为任务指令注入系统提示词。

`provider.name` 与 `provider.model` 为必填项。缺失时系统 MUST 拒绝启动并指出缺失的字段。

#### Scenario: 加载合法定义

- **WHEN** `AGENT.md` 的 frontmatter 含 `provider.name`、`provider.model` 与 `tools` 列表
- **THEN** 生成对应 `Profile`，`tools` 中声明的工具被引用

#### Scenario: 拒绝缺字段的定义

- **WHEN** `AGENT.md` 缺少 `provider.model`
- **THEN** 抛出异常，消息包含字面量 `provider.model`，进程退出码非 0

### Requirement: Provider 显式映射

系统 SHALL 在启动时从配置节 `lytgos.providers` 构造 `provider name → ChatModel` 的不可变映射。配置中的 `${ENV_VAR}` 占位 MUST 在启动时从环境变量解析。

系统 MUST NOT 通过类型扫描发现 Provider，禁止 `@ConditionalOnBean`、`ObjectProvider<ChatModel>`、`getBeansOfType` 等机制。

#### Scenario: 按名取用

- **WHEN** 配置声明 `deepseek` 与 `kimi` 两个 provider，Agent 指定 `provider.name: deepseek`
- **THEN** 使用 deepseek 对应的 `ChatModel`，另一个不被实例化使用

#### Scenario: 未声明的 provider

- **WHEN** Agent 指定 `provider.name: not-exist`
- **THEN** 启动失败，退出码非 0，错误消息列出已注册的 provider 名

#### Scenario: 环境变量缺失

- **WHEN** 配置引用 `${DEEPSEEK_API_KEY}` 且该变量未设置
- **THEN** 启动失败，退出码非 0，错误消息包含 `DEEPSEEK_API_KEY`

### Requirement: ReAct 循环

系统 SHALL 以 ReAct 方式驱动对话：组装 prompt → 调用 `ChatModel` → 若无工具调用则返回文本，若有则执行工具并把结果回填历史继续循环。

迭代次数 MUST 有上限，默认 10，可由 `Profile.settings.maxIterations` 覆盖。达到上限时 MUST 返回已有文本并记录 WARN 日志，不得无限循环。

LLM 调用 MUST 只经由 `ChatModel.call()`，禁止使用 `ChatClient` 或 `ToolCallingManager`。

#### Scenario: 单轮无工具调用

- **WHEN** 模型响应不含工具调用
- **THEN** 直接返回文本，循环结束

#### Scenario: 一轮工具调用后返回

- **WHEN** 第一次响应含工具调用，执行后第二次响应为文本
- **THEN** 循环恰好执行 2 次 LLM 调用后返回

#### Scenario: 达到迭代上限

- **WHEN** 连续 10 次响应都含工具调用
- **THEN** 第 10 次后终止，返回已累积文本，日志出现 WARN

### Requirement: 工具执行与沙箱

系统 SHALL 通过 `ToolExecutor` 作为工具执行的唯一入口，所有工具调用 MUST 先经过 `SandboxChecker` 校验。

`SandboxChecker` MUST 提供三类校验：文件路径、Shell 命令、HTTP 域名。路径校验 MUST 在 `canonicalize()` 之后比较，禁止仅依赖字符串匹配 `..`。

域名白名单为空时 MUST 全部拒绝。

#### Scenario: 白名单内域名放行

- **WHEN** `http_get` 请求白名单内的域名
- **THEN** 请求执行并返回结果

#### Scenario: 白名单外域名拒绝

- **WHEN** `http_get` 请求未在白名单的域名
- **THEN** 抛出异常，消息包含 `sandbox`，请求未发出

#### Scenario: 路径穿越拒绝

- **WHEN** 工具参数传入 `../../etc/passwd` 形式的路径
- **THEN** `canonicalize()` 后判定越界，请求被拒

### Requirement: 会话上下文

系统 SHALL 按 `channel + user + agent` 三元组维护会话上下文，同一三元组内的多轮对话 MUST 共享历史。

会话 MUST 落盘到 `.lytgos/sessions/<key>.json`，进程重启后仍可恢复。`--message` 单条模式与交互模式 MUST 共用同一 Session。第 4 周换成 SQLite 时 `SessionStore` 接口不变。

#### Scenario: 同键共享历史

- **WHEN** 同一 channel、user、agent 连续发两条消息，第二条使用指代词
- **THEN** 第二轮能正确理解指代对象

#### Scenario: 单条模式与交互模式共用

- **WHEN** 先执行 `lytgos chat --message "我叫张三"`，再启动 `lytgos chat` 问「我叫什么」
- **THEN** 回答中包含「张三」

#### Scenario: 进程重启后恢复

- **WHEN** 结束进程，重启后对同一三元组继续发消息
- **THEN** 历史仍在，能引用重启前的内容

#### Scenario: 不同键互不影响

- **WHEN** 两个不同 user 在同一 channel 对话
- **THEN** 各自的历史互不可见

### Requirement: 命令行对话

系统 SHALL 提供 `lytgos chat` 命令，支持交互模式与 `--message` 单条模式。

交互模式下 MUST 能连续多轮并支持 `exit` 退出；单条模式 MUST 在打印响应后以退出码 0 结束。

#### Scenario: 单条模式

- **WHEN** 执行 `lytgos chat --message "北京今天天气怎么样"`
- **THEN** 依次调用 `http_get` 查询经纬度与天气，输出包含温度数值，退出码 0

#### Scenario: 交互模式退出

- **WHEN** 交互模式输入 `exit`
- **THEN** 进程正常退出，退出码 0
