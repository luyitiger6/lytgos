# observability Specification

## Purpose

定义 lytgos 运行时的可观测性契约：日志按环境分层输出、每个请求带可追踪的 traceId、健康与指标端点能被编排系统与监控系统稳定消费。

## Requirements

### Requirement: 日志按环境分层

开发环境 MUST 输出人类可读的彩色单行日志；prod profile MUST 输出单行 JSON，至少含 timestamp、level、logger、message 与 traceId 五个字段。请求处理期间产生的日志 MUST 携带该请求的 traceId；尚无请求上下文的启动期日志 MUST 仍为单行 JSON，traceId 字段可缺省。

#### Scenario: 开发环境日志可读

- **WHEN** 以默认 profile 启动并处理一次请求
- **THEN** 控制台日志为彩色可读文本，不含 JSON 花括号

#### Scenario: 生产环境日志为 JSON

- **WHEN** 以 prod profile 启动并处理一次请求
- **THEN** 每条日志是一行合法 JSON，含 timestamp、level、logger、message、traceId 字段

#### Scenario: 生产环境启动期日志仍可被机器解析

- **WHEN** 以 prod profile 启动且尚未收到任何请求
- **THEN** 每条日志仍是一行合法 JSON，含 timestamp、level、logger、message 字段

### Requirement: traceId 贯穿请求

每个进入的 HTTP 请求 MUST 获得一个 traceId：请求头 `X-Request-Id` 存在时复用其值，不存在时生成；该值 MUST 写入日志 MDC，并在响应头 `X-Request-Id` 中回写。

#### Scenario: 复用调用方传入的 traceId

- **WHEN** 请求携带 `X-Request-Id: abc123`
- **THEN** 该请求产生的日志 traceId 为 `abc123`，响应头 `X-Request-Id` 为 `abc123`

#### Scenario: 未传时生成并回写

- **WHEN** 请求未携带 `X-Request-Id`
- **THEN** 系统生成非空 traceId，响应头返回该值，且同一请求内的日志使用同一值

### Requirement: 健康探针

`/actuator/health` MUST 返回应用健康状态，并分别提供 liveness 与 readiness 探针组，供编排系统区分「进程活着」与「可以接流量」。

#### Scenario: 服务正常时探针为 UP

- **WHEN** 请求 `/actuator/health`、`/actuator/health/liveness` 与 `/actuator/health/readiness`
- **THEN** 三者均返回 HTTP 200 且状态为 UP

### Requirement: 指标与信息端点

`/actuator/prometheus` MUST 以 Prometheus 文本格式输出 JVM 与 HTTP 指标；`/actuator/info` MUST 返回应用名与版本。除 health、info、prometheus 之外的管理端点 MUST NOT 默认暴露。

#### Scenario: 抓取指标

- **WHEN** 请求 `/actuator/prometheus`
- **THEN** 返回 HTTP 200 且内容包含 `jvm_` 前缀的指标行

#### Scenario: 未列出的端点不暴露

- **WHEN** 请求 `/actuator/env`
- **THEN** 返回 404

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
