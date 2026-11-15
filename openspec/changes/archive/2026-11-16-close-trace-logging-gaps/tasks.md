# Tasks

## 1. trace 上下文基础

- [x] 1.1 在 `lytgos-core` 新增 `io.lytgos.core.trace.TraceContext`（`MDC_KEY`、`current()`、`ensure(String)` 返回可关闭作用域），单测覆盖三种情形：MDC 已有值时不覆盖且关闭无副作用、MDC 为空时写入并在关闭后清理、`current()` 在没有值时返回 null
- [x] 1.2 `ReActLoop.run()` 用 `TraceContext.ensure(turnId)` 包住整轮执行，单测断言「执行期间 MDC 的 traceId 等于本轮 turnId」与「run 返回后 MDC 已清理」；类注释写明本轮所有执行必须在调用线程上完成
- [x] 1.3 `TraceIdFilter` 改用 `TraceContext.MDC_KEY` 并删除本地常量，验证 `TraceIdFilterTest` 两个用例仍通过（复用与生成）
- [x] 1.4 `TraceIdFilter` 在 `MDC.put` 之后、`chain.doFilter` 之前打一条 DEBUG 探针（`trace attached: <method> <sanitized-uri>`，URI 经 `LogSafe.sanitize`），`TraceIdFilterTest` 增加断言「探针日志行含本次写入的 traceId」

## 2. 审计行落 traceId

- [x] 2.1 `LlmCallRecord` 与 `ToolInvocationRecord` 在 `turnId` 之后增加 `String traceId`（允许为空），同步更新 5 处构造点（生产：`AuditedChatModel`、`ToolExecutor`；测试：`SqliteStoreTest` 的直接构造与 `llmCall` / `toolCall` 两个辅助方法）且不留重载构造器，验证 `lytgos-core` 与 `lytgos-storage` 的测试通过
- [x] 2.2 `AuditedChatModel` 与 `ToolExecutor` 写入审计时取 `TraceContext.current()`，在 `AuditedChatModelTest` 与 `ToolExecutorTest` 中断言「MDC 有值时审计行 traceId 等于该值」「无值时写入 null 而不是抛异常」
- [x] 2.3 `lytgos-schema.sql` 的 `llm_calls` / `tool_invocations` 增加 `trace_id TEXT`（索引不写进脚本：脚本先于迁移器执行，对老库建索引会 `no such column` 直接启动失败，索引由 2.4 的迁移器在补列后统一建），`AuditMapper` 接口与 `AuditMapper.xml` 两条 INSERT 同步加列，验证 `SqliteStoreTest` 能写入并读回 trace_id
- [x] 2.4 新增 `io.lytgos.storage.SqliteSchemaMigrator`（`PRAGMA table_info` 判列 → 缺列则 `ALTER TABLE ... ADD COLUMN trace_id TEXT` → 建索引），单测覆盖：老结构库补列成功、已含新列的库不做 ALTER、连续执行两次不报错且只补一次
- [x] 2.5 `CoreBeans` 注册迁移器并保证在 `DataSourceInitializer` 之后执行；`AuditInvariantTest` 的快照与断言跟进新列，验证 `lytgos-test` 的 I1/I2/I3 全部通过且审计行 trace_id 非空

## 3. CLI 日志带 traceId

- [x] 3.1 `lytgos-cli/src/main/resources/logback.xml` 的 pattern 增加 `[%X{traceId:-}]`，验证方式：`java -jar lytgos-cli-boot.jar provider list` 的 stderr 行含 `[ ]` 位且不破坏 stdout 的结构化输出（stdout 与 stderr 仍分离）
- [x] 3.2 验证交互模式下每轮日志使用不同 traceId：在 `lytgos-core` 的单测里连续调用两次 `ReActLoop.run()`，断言两次执行期间的 MDC 值不同且每次结束后被清理

## 4. 业务模块关键事件日志

- [x] 4.1 `AgentLoader.load` 加载成功后 DEBUG 输出 agent 名、provider、model（经 `LogSafe`），验证 `lytgos-persona` 的 `mvn verify` 通过且 `lytgos provider list`/`chat` 未出现格式异常
- [x] 4.2 `ProviderRegistry` 构造时 INFO 输出已登记的 provider 名与 model；`require` 首次构造时 DEBUG 输出；验证 `ProviderRegistryTest` 通过且 `lytgos provider list` 的 stderr 出现一行登记日志
- [x] 4.3 `SqliteSessionStore.save` DEBUG 输出会话键与消息数，验证 `SqliteStoreTest` 通过
- [x] 4.4 `MemoryStore.append` DEBUG 输出写入后字符数（经 `LogSafe` 处理路径例外：数字无需净化），验证 `lytgos-memory` 的测试通过

## 5. 日志契约闸门与清单同步

- [x] 5.1 `logback-spring.xml` 的 `<springProfile name="!prod">` 内加 `io.lytgos.storage.mapper=INFO` 与 `io.lytgos=DEBUG` 两条覆盖，prod 分支不加；验证 dev 启动日志出现业务 DEBUG 行且不含 mapper 的 SQL 行
- [x] 5.2 新增 `harness/checks/12-log-trace.ps1`：dev 用默认配置启动，断言日志为人类可读文本且无 JSON 行；prod 用 `--logging.level.io.lytgos=DEBUG` 启动，断言每个以 `{` 开头的行可 `ConvertFrom-Json` 且启动行含四个字段；带 `X-Request-Id: probe-<uuid>` 请求后断言 dev 日志含该值且 prod 日志 JSON 的 `traceId` 等于该值；缺 jar 或 java 时 exit 2；本机执行 exit 0
- [x] 5.3 同步 `AGENTS.md` 的机器可读闸门清单与目录地图、`harness/README.md` 的闸门表；验证 `powershell -File harness\checks\00-layout-check.ps1` exit 0

## 6. 集成验收

- [x] 6.1 逐模块执行 `mvn -B clean verify`（12 个模块目录），全部 exit 0
- [x] 6.2 全量 `mvn -B clean install` 通过，测试总数不少于改动前（153）且无跳过
- [x] 6.3 `powershell -File harness\run-all.ps1` 退出码 0，13 个闸门全部 PASS
- [x] 6.4 用一份「旧结构」临时库（无 trace_id 列）启动一次 CLI，确认迁移日志出现且旧审计行保留、新审计行 trace_id 非空

## Workflow follow-up

- 规格与实现经用户审阅后，按项目流程归档本 change。
- 归档后确认 `openspec/specs/observability/spec.md` 已包含新增的两条 Requirement 与更新后的日志分层场景。
