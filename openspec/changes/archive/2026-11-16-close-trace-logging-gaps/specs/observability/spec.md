# Spec Delta

## ADDED Requirements

### Requirement: traceId 进入审计记录

每次写入 `llm_calls` 与 `tool_invocations` 的审计行 MUST 携带触发该次调用的 traceId，使日志、模型调用与工具执行能用同一个值互查；不存在外层请求上下文时 MUST 使用该轮对话的 turnId。历史行允许为空。

#### Scenario: Web 请求的审计行携带请求 traceId

- **WHEN** 一个携带 `X-Request-Id: abc123` 的 HTTP 请求最终触发一次模型调用与一次工具执行
- **THEN** 该请求写入的 `llm_calls` 与 `tool_invocations` 行的 trace_id 均为 `abc123`

#### Scenario: 命令行对话的审计行携带本轮 traceId

- **WHEN** 在命令行发起一轮对话并产生模型调用与工具执行
- **THEN** 两条审计行的 trace_id 相同、非空，且等于该轮日志中的 traceId

### Requirement: 命令行会话可追踪

命令行入口 MUST 为每一轮对话建立 traceId 并在诊断日志中输出该值；同一轮内的日志 MUST 使用同一个 traceId，轮次结束后 MUST 清理，不污染下一轮。

#### Scenario: 日志行使用本轮 traceId

- **WHEN** 在命令行交互模式提交一条会触发工具调用的消息
- **THEN** 该轮产生的每条诊断日志行都带同一个非空 traceId

#### Scenario: 轮次之间不残留

- **WHEN** 一轮对话结束
- **THEN** 本轮 traceId 从日志上下文清理，下一轮使用不同的值

## MODIFIED Requirements

### Requirement: 日志按环境分层

开发环境 MUST 输出人类可读的彩色单行日志；prod profile MUST 输出单行 JSON，至少含 timestamp、level、logger、message 与 traceId 五个字段。请求处理期间产生的日志 MUST 携带该请求的 traceId；尚无请求上下文的启动期日志 MUST 仍为单行 JSON，traceId 字段可缺省。

#### Scenario: 开发环境日志可读

- **WHEN** 以默认 profile 启动并处理一次请求
- **THEN** 控制台日志为彩色可读文本，不含 JSON 花括号

#### Scenario: 生产环境日志为 JSON

- **WHEN** 以 prod profile 启动并处理一次请求
- **THEN** 每条请求期日志是一行合法 JSON，含 timestamp、level、logger、message、traceId 字段

#### Scenario: 生产环境启动期日志仍可被机器解析

- **WHEN** 以 prod profile 启动且尚未收到任何请求
- **THEN** 每条日志仍是一行合法 JSON，含 timestamp、level、logger、message 字段
