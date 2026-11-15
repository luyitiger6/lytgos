# Spec Delta

## MODIFIED Requirements

### Requirement: Agent 定义加载

系统 SHALL 从 `.lytgos/agents/<name>/AGENT.md` 加载 Agent 定义。文件由 `---` 分隔的 YAML frontmatter 与正文组成，frontmatter 解析为 `Profile`，正文作为任务指令注入系统提示词。

`provider.name` 与 `provider.model` 为必填项；`provider.temperature` 可选，取值 MUST 在 0.0 到 2.0 之间，缺省时不向上游传温度；`skills` 可选，每项 MUST 是技能目录名。字段缺失或取值非法时系统 MUST 拒绝启动并指出具体字段。

#### Scenario: 加载合法定义

- **WHEN** `AGENT.md` 的 frontmatter 含 `provider.name`、`provider.model` 与 `tools` 列表
- **THEN** 生成对应 `Profile`，`tools` 中声明的工具被引用

#### Scenario: 拒绝缺字段的定义

- **WHEN** `AGENT.md` 缺少 `provider.model`
- **THEN** 抛出异常，消息包含字面量 `provider.model`，进程退出码非 0

#### Scenario: 加载可选字段

- **WHEN** frontmatter 声明 `provider.temperature: 0.3` 与 `skills: [weather]`
- **THEN** `Profile` 携带温度 0.3 与技能列表 `[weather]`

#### Scenario: 拒绝越界温度

- **WHEN** frontmatter 声明 `provider.temperature: 2.5`
- **THEN** 抛出异常，消息包含字面量 `temperature`，进程退出码非 0

### Requirement: 工具执行与沙箱

系统 SHALL 通过 `ToolExecutor` 作为工具执行的唯一入口，所有工具调用 MUST 先经过 `SandboxChecker` 校验。`SandboxChecker` MUST 提供三类校验：文件路径、Shell 命令、HTTP 域名；路径校验 MUST 在 `canonicalize()` 之后比较，禁止仅依赖字符串匹配 `..`。

#### Scenario: 白名单内域名放行

- **WHEN** `http_get` 请求白名单内的域名
- **THEN** 请求执行并返回结果

#### Scenario: 白名单外域名拒绝

- **WHEN** `http_get` 请求未在白名单的域名
- **THEN** 抛出异常，消息包含 `sandbox`，请求未发出

#### Scenario: URL 格式非法不中断对话

- **WHEN** `http_get` 的 `url` 参数格式非法，例如 query 里出现未转义的 `%`
- **THEN** 抛出的异常不是沙箱违规，错误文本回填给模型，本轮对话继续，模型可修正后重试

#### Scenario: 路径穿越拒绝

- **WHEN** 工具参数传入 `../../etc/passwd` 形式的路径
- **THEN** `canonicalize()` 后判定越界，请求被拒

#### Scenario: 远程 MCP 连接受域名白名单约束

- **WHEN** Agent 声明的 MCP server 使用 `url` 形态
- **THEN** 连接地址的域名 MUST 先经白名单校验，校验失败时不发出任何网络请求

#### Scenario: 本地 MCP 子进程不受域名白名单约束

- **WHEN** MCP 工具在其子进程内向任意域名发起请求
- **THEN** 请求不经过域名白名单校验，`SandboxChecker` 只在该 server 启动时校验过 command

#### Scenario: 空白名单全部拒绝

- **WHEN** 域名白名单为空，`http_get` 与远程 MCP 连接任一尝试发起请求
- **THEN** 全部被拒绝，不发生任何网络请求

#### Scenario: 两类失败处理相反

- **WHEN** 一次 `http_get` 因域名不在白名单被拒，另一次因缺少 `url` 参数被拒
- **THEN** 前者作为安全事件抛出并中断本轮，不把错误文本交给模型重试；后者转成文本回填，循环继续

### Requirement: 会话上下文

系统 SHALL 按 `channel + user + agent` 三元组维护会话上下文，同一三元组内的多轮对话 MUST 共享历史。

会话 MUST 持久化到工作区 SQLite 数据库的 `sessions` 表，进程重启后仍可恢复；`--message` 单条模式与交互模式 MUST 共用同一 Session。`SessionStore` 接口 MUST NOT 因存储实现变化而改变。

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

## ADDED Requirements

### Requirement: 系统提示词组装

系统 MUST 在每次模型调用前重新组装系统提示词，顺序固定为：身份（`identity`）→ 任务指令（`AGENT.md` 正文）→ 技能 → Bootstrap 文件 → 长期记忆 → 当前日期。

技能 MUST 逐个读取 `.lytgos/agents/<name>/skills/<skill>/SKILL.md`，取其 frontmatter 的 `name` 与 `description` 注入，并给出文件路径提示按需读取全文；声明了但文件缺失 MUST 报错。Bootstrap 文件缺失 MUST 记 WARN 后跳过，不阻断对话。当前日期 MUST 以 `yyyy-MM-dd` 注入到系统提示词末尾。

所有输入文件 MUST 在每次组装时重新读取，MUST NOT 缓存启动时的快照——用户改了文件，下一条消息就要生效。

#### Scenario: 修改 bootstrap 无需重启

- **WHEN** 对话进程中修改 `.lytgos/AGENTS.md`，随后发送下一条消息
- **THEN** 新消息的系统提示词包含修改后的内容

#### Scenario: 注入技能摘要

- **WHEN** `AGENT.md` 声明 `skills: [weather]` 且对应 `SKILL.md` 存在
- **THEN** 系统提示词包含该技能的 `name` 与 `description`，并给出其文件路径

#### Scenario: 技能文件缺失报错

- **WHEN** `skills` 声明了 `weather` 但对应 `SKILL.md` 不存在
- **THEN** 本轮组装抛出异常，消息包含技能名与期望路径

#### Scenario: 注入当前日期

- **WHEN** 任一消息被组装
- **THEN** 系统提示词末尾包含当天的 `yyyy-MM-dd` 日期
