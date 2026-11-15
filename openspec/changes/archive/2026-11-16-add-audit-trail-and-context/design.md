# Design

## Context

现状（详见 proposal.md - Why）：

- `lytgos-core` 的聊天契约只有 `ChatRequest(messages, tools)` 与 `ChatResult(content, toolCalls)`；上游 token 用量在 `SpringAiChatModel` 里被丢弃。
- `PromptBuilder.from(...)` 在构造时一次性拼好 system 提示词，Bootstrap / 记忆只读一次；没有日期与技能。
- `ProviderRegistry.require(name)` 直接返回 `SpringAiChatModel`；`ToolExecutor` 只打日志。
- `lytgos-storage` 只有 `package-info.java`；`FileSessionStore` 写 `.lytgos/sessions/<key>.json`。
- `harness/checks/05-audit-invariant.ps1` 是空壳，计划判据 I1/I2/I3 已写在脚本注释里。

约束：`AGENTS.md` 4.1（工具执行只走 `ToolExecutor`、禁 `ChatClient`）、4.2（Provider 显式映射）、4.3（沙箱不可关闭）、第 3 条（审计不许后置）；04 号闸门的 ArchUnit 规则不得新增绕过。

## Goals / Non-Goals

**Goals**

- 模型调用与工具执行 100% 落库，按 session + turn 可关联（I1/I2/I3）。
- 审计入口不新增 Provider 抽象层，不改变 `ChatModel.call()` 语义。
- 系统提示词补齐日期、技能、每次重读；Profile 补 `temperature` 与 `skills`。
- 会话落 SQLite，`SessionStore` 接口不变。

**Non-Goals**

- 不做旧 `sessions/*.json` 迁移（文件保留原样，新会话写库）。
- 不做审计保留期清理、脱敏策略、成本看板、fallback/hedge/熔断。
- 不做多 Agent 并存演示、定时任务、Skill 渐进式披露的 L3 加载工具。
- 不引入 JPA / 连接池 / Flyway。

## Decisions

### D1 审计入口用 `ChatModel` 装饰器，不新增 `ProviderService`

`AuditedChatModel implements io.lytgos.core.chat.ChatModel`，在 `ProviderRegistry.require()` 构造 `SpringAiChatModel` 后包一层，装饰器持有 provider 名、model id 与 `LlmCallAuditor`。

- 备选 A：课件式 `ProviderService.chat(sessionId, profile, prompt)` 统管。被拒：多一层与 `ChatModel` 语义重复的抽象，且会让「模型调用只能走 `ChatModel`」的 ArchUnit 约束出现两个入口。
- 备选 B：改 `SpringAiChatModel` 内部写库。被拒：provider 模块被迫依赖存储端口，测试要拖库。
- 装饰器让审计与协议转换分离：一个只管「谁花了多少」，一个只管「怎么发请求」。

### D2 持久化：纯 JDBC + `sqlite-jdbc`，不用 JPA、不用连接池

`lytgos-storage` 新增 `SqliteDatabase`（库路径 + 幂等执行 `lytgos-schema.sql`）、`SqliteAuditStore`、`SqliteSessionStore`。每次操作 `DriverManager.getConnection()` 打开短连接，连接参数设 `busy_timeout=3000`、`journal_mode=WAL`。

- 备选 A：Spring Data JPA（课件选型）。被拒：审计写入是三条固定 SQL，ORM 只带来启动扫描与字节码增强风险，与「启动即用、单二进制」冲突。
- 备选 B：`spring-boot-starter-jdbc` + HikariCP。被拒：CLI 多条命令不连库，池化会在每个进程里常驻线程；SQLite 单写者模型下连接池没有收益。
- 库路径固定 `.lytgos/lytgos.db`，不做配置项——当前没有第二个值。

### D3 契约扩展

```java
// ChatRequest：新增 sessionId / turnId / temperature（temperature 可空 = 用上游默认）
record ChatRequest(List<Message> messages, List<ToolSpec> tools,
                   String sessionId, String turnId, Double temperature)

// ChatResult：新增用量；保留两参便捷构造器，既有测试与调用点不用全改
record ChatResult(String content, List<ToolCall> toolCalls, Usage usage) {
  record Usage(int promptTokens, int completionTokens, int totalTokens) { /* ZERO 常量 */ }
}

// Profile.ProviderRef 增加 temperature；Profile 增加 skills
```

`ReActLoop.run()` 每轮生成 `turnId = UUID.randomUUID().toString()`，与 `session.key()`、`profile.provider().temperature()` 一起传给 `ChatRequest`；工具执行同样携带 `sessionId + turnId`。

`ChatRequest` 不提供两参便捷构造器：`sessionId` 是审计主键的一部分，缺省值会让「忘传」变成静默的空 session 记录。只有 1 处生产代码与 1 个测试类需要改。

### D4 端口与装配

`io.lytgos.core.audit` 放端口与记录：

- `LlmCallRecord(sessionId, turnId, provider, model, promptTokens, completionTokens, totalTokens, durationMs, success, errorMessage, createdAt)`
- `ToolInvocationRecord(sessionId, turnId, toolName, arguments, durationMs, success, errorMessage, createdAt)`
- `LlmCallAuditor.record(LlmCallRecord)`、`ToolInvocationAuditor.record(ToolInvocationRecord)`

`lytgos-storage` 实现两个端口；`lytgos-cli` 装配（cli 新增对 storage 的依赖）。端口必填，不提供 noop 默认实现——想跳过审计只能显式伪造测试替身，生产装配路径不存在「忘了注入就静默不审计」。

### D5 审计失败 fail-closed

写库异常包装为 `LytgosException.AuditWriteException` 向上抛：

- 模型侧：装饰器 `finally` 中写记录，写失败覆盖原异常继续上抛。
- 工具侧：`ToolExecutor` 先执行工具再写审计；审计异常在工具异常处理之外抛出，不会被「工具执行失败 → 回填文本」的 `catch (RuntimeException)` 吞掉。

理由：审计是产品卖点，静默丢弃等于卖点消失；备选 fail-open + 指标告警被拒，因为当前没有指标告警设施。

### D6 提示词每次重读、技能只注入摘要

`PromptBuilder` 从「构造时拼好字符串」改为「持有 Profile / 工作区 / 指令，`build()` 时重新拼」：身份 → 指令 → 技能 → Bootstrap → 记忆 → 日期。日期用注入的 `Clock`（默认系统时区）取 `yyyy-MM-dd`，便于测试。

技能读 `agents/<name>/skills/<skill>/SKILL.md` 的 frontmatter `name`/`description`，注入摘要 + 文件路径；缺失即抛 `InvalidAgentDefinitionException`（声明了就必须存在）。技能名沿用 `requirePlainFileName` 同款校验（禁 `/`、`\`、`..`）。

Bootstrap 缺失从「静默跳过」改为 WARN 后跳过——与课件的 `ContextLoader` 行为一致，且不再掩盖用户写错文件名。

### D7 会话落 SQLite，不做迁移

`sessions(key TEXT PRIMARY KEY, messages TEXT NOT NULL, updated_at TEXT NOT NULL)`，`messages` 存 `Message` 列表的 JSON（沿用 `FileSessionStore` 的 Jackson 形态）。`get` 未命中返回空历史，`save` 用 `INSERT ... ON CONFLICT(key) DO UPDATE`。旧 `.json` 不读不删：迁移会引入一次性双读分支，而当前没有需要保住的生产会话。

### D8 05 闸门落地的判定方式

新增 `lytgos-test` 中的 `AuditInvariantTest`：

- I1：用内存 SQLite 装配 `AuditedChatModel`，成功与失败各调一次，断言 `llm_calls` 各多一行。
- I2：`ToolExecutor` 成功、工具异常、沙箱拒绝各一次，断言 `tool_invocations` 三行且 sandbox 行标 `sandbox`。
- I3：同一 `turnId` 的模型行与工具行 `session_id` 相同；两个 turn 的 `turn_id` 不同。

`05-audit-invariant.ps1` 复用 02/04 的跑法：先删旧 surefire 报告，`mvn -pl lytgos-test -Dtest=AuditInvariantTest test`，核对报告里 `Tests run` 至少 3 且无失败。

### D9 规格债的处理

`react-loop`「工具执行与沙箱」描述收敛为「唯一入口 + 三类校验 + canonicalize」，被移出的两类失败策略与空白名单行为落成场景；`validate --strict` 必须转绿。这是本 change 顺带处理的唯一既有规格问题，不改其他 spec。

## Risks / Trade-offs

- [SQLite 并发写锁] 多进程同时写会 `SQLITE_BUSY` → `busy_timeout=3000` + fail-closed 让问题显性；单用户 CLI 场景不会触发。
- [审计写入放大时延] 每次模型调用多一次写库 → 短连接 + WAL，毫秒级；相比对话本身耗时可忽略。
- [提示词每次重读的 IO] 每轮多读几个小文件 → 文件均 < 64KB，且换来「改文件即生效」；不引入缓存失效逻辑。
- [审计参数含敏感内容] `arguments` 原样落库可能含密钥 → 截断 4000 字符，脱敏留待后续 change（记录在 Open Questions）。
- [旧会话不可见] 升级后 `.json` 历史不再加载 → 文档与变更说明中明示，不删除旧文件。

## Migration Plan

1. 启动时 `SqliteDatabase.initialize()` 建库建表；库文件不存在即创建，无数据迁移。
2. 装配切换：`CoreBeans` 的 `SessionStore` 换 `SqliteSessionStore`；`ProviderRegistry` / `ToolExecutor` 注入审计端口。
3. 回滚：把 `SessionStore` 换回 `FileSessionStore`、审计端口换回测试替身即可；库文件可留在磁盘。
4. 发布后验证：跑 `AuditInvariantTest`、`react-loop` 既有测试、`harness\run-all.ps1` 全绿。

## Open Questions

- 审计保留期与清理策略（按天滚动删除 or 手动 `lytgos audit prune`）：等有多日真实数据后再定，不影响本 change 的表结构与写入。
- `arguments` 的敏感字段脱敏规则：与未来的 Tool Policy 一起做。
