# Proposal

## Why

上一轮逐模块核查发现：可观测性只在 Web 入口成立，链路在另外三处断开。

- **prod JSON 日志没有任何自动化验收**。`observability` spec 要求生产环境输出含 traceId 的单行 JSON，但 11-web-smoke 的 9 条判据不含日志格式。`logging.config: classpath:logback-spring.xml` 一旦被误删，日志会静默退回明文，所有闸门仍是绿的。
- **traceId 停在日志里，进不了审计表**。`llm_calls` / `tool_invocations` 只有 `session_id` / `turn_id`，从一次 HTTP 请求的 traceId 反查它触发了哪些模型调用与工具执行，只能人工对时序。
- **CLI 日志没有会话标识**。多会话并行排查只能靠时间排序；CLI 路径产生的审计行也天然缺 trace 维度。
- **persona / provider / storage / memory 四个业务模块零 Logger**。错误路径靠异常上抛兜底，但启动期装载（agent 加载、provider 登记、库结构就绪、记忆追加）没有任何可见事件。

## What Changes

- 新增 harness 闸门 `12-log-trace.ps1`：分别以 dev 与 prod profile 启动真实 jar，核对「dev 为人类可读文本」「prod 每行可解析为 JSON 且启动期字段完整」「请求期日志带 X-Request-Id 对应的 traceId」三组判据，三态退出。
- core 新增 `TraceContext`，作为 MDC 中 traceId 的唯一读写入口；`ReActLoop.run()` 保证每轮对话都有 traceId——外层（Web 请求）已有时沿用，没有时用本轮 `turnId`。
- `LlmCallRecord` / `ToolInvocationRecord` 增加 `traceId` 字段；`llm_calls` / `tool_invocations` 加列并建索引；新增 `SqliteSchemaMigrator` 为已有库补列，不重建表、不丢已有审计行。
- CLI 的 logback pattern 加 traceId 位；`lytgos-cli` 独立 jar 的 stdout/stderr 分离保持不变。
- persona / provider / storage / memory 补关键事件日志：启动装载与结构就绪用 INFO，运行期高频事件用 DEBUG，外部输入一律经 `LogSafe`。
- `observability` spec 细化日志分层场景，新增「traceId 进入审计记录」与「命令行会话可追踪」两条要求。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

- `observability`: 日志分层场景补充启动期与请求期的字段差异；新增两条要求——审计行 MUST 携带 traceId、CLI 每轮对话 MUST 拥有与审计一致的 traceId。

## Impact

- 代码：
  - `lytgos-core`：新增 `trace/TraceContext`，改 `ReActLoop`、`LlmCallRecord`、`ToolInvocationRecord`、`ToolExecutor`
  - `lytgos-provider`：`AuditedChatModel` 读取当前 traceId
  - `lytgos-storage`：`lytgos-schema.sql`、`AuditMapper` / `AuditMapper.xml`、新增 `SqliteSchemaMigrator`、`SqliteAuditStore`
  - `lytgos-persona` / `lytgos-memory` / `lytgos-provider` / `lytgos-storage`：补日志
  - `lytgos-cli`：`logback.xml` pattern、`CoreBeans` 注册 migrator
  - `harness`：新增 `checks/12-log-trace.ps1`，同步 `README.md` 与 `AGENTS.md` 的闸门清单
- 数据：`llm_calls`、`tool_invocations` 各新增一列并建索引；已有 `lytgos.db` 由 migrator 原地补列，不删不重建
- 测试：`AuditedChatModelTest`、`AuditInvariantTest`、`SqliteStoreTest` 跟进新字段；新增 `TraceContextTest` 与 `SqliteSchemaMigratorTest`
- 不涉及依赖变更，不改变对外 HTTP 契约，不新增管理端点
