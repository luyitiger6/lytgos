# Spec Delta

## Purpose

定义 lytgos 的持久审计能力：每次模型调用与每次工具执行都必须留下可查询的一行记录，按 session 与 turn 关联，成功、失败、超时、沙箱拒绝一律不例外，让「谁跑了什么、花了多少、出过什么事」在被追问时拿得出证据。

## ADDED Requirements

### Requirement: 审计表由 schema 脚本创建

审计表 MUST 由随包发布的 SQL 脚本创建，应用启动时幂等执行；系统 MUST NOT 依赖 ORM 或运行时自动建表。

表结构 MUST 覆盖 `llm_calls`、`tool_invocations` 与 `sessions` 三张表；两张审计表 MUST 含 `session_id` 与 `turn_id` 两列，并对 `session_id`、`turn_id`、`created_at` 建索引。

#### Scenario: 首次启动建表

- **WHEN** 工作区首次启动且 `.lytgos/lytgos.db` 不存在
- **THEN** 库文件与三张表被创建，启动成功

#### Scenario: 重复启动不破坏数据

- **WHEN** 库中已有审计记录，再次启动
- **THEN** 建表语句幂等执行，已有记录条数与内容不变

### Requirement: 每次模型调用落 llm_calls

每一次模型调用 MUST 写入一行 `llm_calls`，无论成功、失败还是超时。行 MUST 记录 `session_id`、`turn_id`、`provider`、`model`、prompt/completion/total token 用量、耗时毫秒、`success` 与 `error_message`。

上游未返回用量时 token 列 MUST 记 0，不得留空或报错；`error_message` MUST 截断到 1000 字符以内。

#### Scenario: 成功调用记录用量

- **WHEN** 一次模型调用返回文本且上游给出 token 用量
- **THEN** `llm_calls` 新增一行，`success` 为真，三个 token 列与上游一致，耗时大于等于 0

#### Scenario: 失败调用同样落库

- **WHEN** 一次模型调用因上游 5xx 抛出异常
- **THEN** `llm_calls` 仍新增一行，`success` 为假，`error_message` 含异常摘要，异常继续向上抛出

### Requirement: 每次工具调用落 tool_invocations

每一次工具执行 MUST 写入一行 `tool_invocations`，允许、沙箱拒绝、超时、工具异常都算。行 MUST 记录 `session_id`、`turn_id`、`tool_name`、`arguments`、耗时毫秒、`success` 与 `error_message`。

`arguments` MUST 截断到 4000 字符；沙箱拒绝时 `error_message` MUST 标明是沙箱违规。

#### Scenario: 成功执行记录参数与耗时

- **WHEN** `file_read` 执行成功
- **THEN** `tool_invocations` 新增一行，`success` 为真，`arguments` 保留调用参数原文

#### Scenario: 沙箱拒绝也落库

- **WHEN** `http_get` 因域名不在白名单被 `SandboxChecker` 拒绝
- **THEN** 新增一行，`success` 为假，`error_message` 含沙箱违规标识，随后异常继续上抛中断本轮对话

### Requirement: 审计记录可按 session 与 turn 关联

同一轮对话产生的 `llm_calls` 与 `tool_invocations` MUST 共享同一 `turn_id`，且 `session_id` MUST 与该会话在 `sessions` 表中的键一致；不同轮次 MUST NOT 复用 `turn_id`。

#### Scenario: 一轮内模型与工具记录可互查

- **WHEN** 一轮对话中发生 2 次模型调用与 1 次工具执行
- **THEN** 三行记录 `turn_id` 相同、`session_id` 相同，用该 `turn_id` 一次查询可全部取出

#### Scenario: 不同轮次互不串扰

- **WHEN** 连续两轮对话
- **THEN** 两轮的 `turn_id` 不同，按任一 `turn_id` 查询只返回本轮记录

### Requirement: 审计写入失败不让本轮静默继续

审计写入失败 MUST 以异常中断本轮，不得降级为日志后继续对话，也不得被「工具执行失败回填文本」的路径吞掉。

#### Scenario: 审计库不可写

- **WHEN** 审计库所在磁盘只读，一次模型调用完成后写审计失败
- **THEN** 本轮对话以错误结束，日志包含失败的 SQL 操作与底层原因，用户可见明确的审计失败提示
