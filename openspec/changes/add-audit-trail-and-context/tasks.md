# Tasks

## 1. 存储底座（lytgos-storage）

- [x] 1.1 `lytgos-storage/pom.xml` 新增 `org.xerial:sqlite-jdbc`，版本在根 `pom.xml` 锁定；不引入 starter-jdbc / JPA
- [x] 1.2 新增 `src/main/resources/lytgos-schema.sql`：`sessions`、`llm_calls`、`tool_invocations` 三表与 `session_id`/`turn_id`/`created_at` 索引，全部 `IF NOT EXISTS`
- [x] 1.3 `SqliteDatabase`：库路径 `.lytgos/lytgos.db`、启动幂等执行 schema、短连接参数 `busy_timeout=3000` 与 `journal_mode=WAL`
- [x] 1.4 `SqliteAuditStore` 实现 `LlmCallAuditor` 与 `ToolInvocationAuditor`，按设计 D4 写列，`error_message` 截 1000、`arguments` 截 4000
- [x] 1.5 `SqliteSessionStore` 实现 `SessionStore`：`Message` 列表 JSON、`get` 未命中返回空会话、`save` 用 `ON CONFLICT DO UPDATE`
- [x] 1.6 单测：建表幂等、成功/失败/沙箱三态审计行、会话保存读回与 upsert

## 2. 聊天契约与审计装饰器（core / provider）

- [x] 2.1 `ChatRequest` 扩展为 `(messages, tools, sessionId, turnId, temperature)`，`sessionId`/`turnId` 空值即拒绝
- [x] 2.2 `ChatResult` 增加 `Usage(promptTokens, completionTokens, totalTokens)`，保留两参便捷构造器
- [x] 2.3 `io.lytgos.core.audit` 新增 `LlmCallRecord`、`ToolInvocationRecord`、`LlmCallAuditor`、`ToolInvocationAuditor`
- [x] 2.4 `AuditedChatModel`：成功与失败都写 `llm_calls`，失败先记录再原样上抛；写库失败抛 `AuditWriteException`
- [x] 2.5 `LytgosException` 增加 `AuditWriteException`（错误码可供 HTTP 层复用）
- [x] 2.6 `SpringAiChatModel` 映射上游 `Usage`（缺失记 0）并应用请求级 `temperature`
- [x] 2.7 `ProviderRegistry` 构造时包一层 `AuditedChatModel`，构造参数加 `LlmCallAuditor`；`ProviderBeans` 同步装配
- [x] 2.8 `ReActLoop` 每轮生成 `turnId`，`ChatRequest` 携带 `session.key()`、`turnId`、`profile.provider().temperature()`
- [x] 2.9 单测：装饰器成功/失败两态、usage 缺失记 0、temperature 透传（`ReActLoopTest#passesProfileTemperatureToRequests`）、ReActLoop 回归

## 3. 工具审计（core）

- [x] 3.1 `ToolExecutor.execute(sessionId, turnId, call)`：成功、工具异常、沙箱拒绝三态写 `tool_invocations`；沙箱行 `error_message` 标 `sandbox`
- [x] 3.2 审计写入异常不得被「工具失败回填文本」吞掉，必须中断本轮
- [x] 3.3 单测：三态记录 + 审计失败未被捕获

## 4. Profile 与提示词（persona / core）

- [x] 4.1 `Profile.ProviderRef` 增加 `temperature`（0.0–2.0，越界报错）；`Profile` 增加 `skills`
- [x] 4.2 `AgentLoader` 解析 `provider.temperature` 与 `skills`，错误消息带字段名与文件路径
- [x] 4.3 `PromptBuilder` 改为每次 `build()` 重新读取：身份 → 指令 → 技能 → Bootstrap → 记忆 → 日期；`Clock` 可注入
- [x] 4.4 技能注入 `SKILL.md` 的 `name`/`description` 与文件路径，缺失抛 `InvalidAgentDefinitionException`；Bootstrap 缺失 WARN 后跳过
- [x] 4.5 单测：改文件后下一条消息生效、日期注入、技能摘要、技能缺失报错、越界 temperature（`ProfileTest#rejectsOutOfRangeTemperature`）

## 5. 装配与 CLI

- [x] 5.1 `lytgos-cli/pom.xml` 增加 `lytgos-storage` 依赖
- [x] 5.2 `CoreBeans` 装配 `SqliteDatabase` / `SqliteAuditStore` / `SqliteSessionStore`；`AgentRuntimeFactory` 传入审计端口与 temperature
- [x] 5.3 适配既有测试：`FileSessionStore` 相关断言改为 SQLite；`ProviderRegistry`/`ToolExecutor` 构造点补审计替身
- [x] 5.4 真实链路冒烟：`lytgos chat --message` 后查库，`sessions`/`llm_calls`/`tool_invocations` 行数与预期一致
      （实测：init + 一次真实 chat（模型调用 `list_dir`）→ `sessions=1`、`llm_calls=2`、`tool_invocations=1`、
      `llm_calls ⋈ tool_invocations ON(session_id, turn_id)` 关联 2 行；turnId 按「用户轮」而非「迭代轮」生成，故同轮两次模型调用都与该轮工具行关联）

## 6. 闸门 05 与文档

- [x] 6.1 `lytgos-test` 新增 `AuditInvariantTest`：I1/I2/I3 三条断言（内存 SQLite）
      （偏离：实现改用 `@TempDir` 临时文件库 —— `SqliteDatabase` 是短连接、每次 `open()` 新建连接，
      `jdbc:sqlite::memory:` 无法在连接之间共享 schema，测不到建表后的真实读写）
- [x] 6.2 `05-audit-invariant.ps1` 从 NOT_READY 改为真实执行：清旧报告 → 跑 `AuditInvariantTest` → 校验 `Tests run` ≥ 3 且无失败
- [x] 6.3 同步 `harness/README.md` 与 `AGENTS.md` §7 的 05 号闸门说明
- [x] 6.4 纠正 `docs/design/TechnicalSolution.md`：§3 的 `ChatClient`→`ProviderService` 表述、§9 的 JPA 选型改为 JDBC
- [x] 6.5 `openspec validate --strict` 全绿（含 `react-loop` 超长描述拆分生效）
- [x] 6.6 `powershell -File harness\run-all.ps1` 退出码 0，记录实际输出
      （12 道全 PASS；额外改动：`10-week2-acceptance.ps1` 第 3/5 条从读 `.lytgos/sessions/*.json`
      改为读 SQLite `lytgos.db`，否则会话换存储后该闸门恒 FAIL —— 超出原 tasks 范围，见下方实施记录）

## 实施记录（偏离与额外改动）

1. **6.1 偏离「内存 SQLite」，改为 `@TempDir` 临时文件库。**
   `SqliteDatabase` 是短连接：每次 `open()` 新建一个连接，`jdbc:sqlite::memory:` 的库只活在单个连接里，
   建表后再开新连接就读不到 schema，测不出真实行为。改用临时文件库不降低校验强度（仍走真实建表 + 真实读写）。

2. **超出原 tasks： `10-week2-acceptance.ps1` 适配 SQLite 会话存储。**
   会话从 `FileSessionStore` 换到 `SqliteSessionStore` 后，10 号闸门第 3 条（移走会话以证明记忆注入）
   与第 5 条（从会话历史取 `exit=0` 与 java 版本号）在原实现上恒 FAIL。
   改法：第 3 条改为移走 `lytgos.db*`（含 `-wal`/`-shm`，否则未 checkpoint 的历史会残留）；
   第 5 条新增 `Read-SessionHistory`（从 m2 取 sqlite-jdbc，用 JDK 单文件源码读 `sessions.messages`），
   不引入 `sqlite3.exe` 依赖。闸门断言条数与语义未变。

3. **事后补测（在完成宣告前自查发现的两处缺口）。**
   - 2.9 的「temperature 透传」原先只在 `ReActLoop` 里实现、没有断言 → 补 `ReActLoopTest#passesProfileTemperatureToRequests`。
   - 4.5 的「越界 temperature」原先只在 `Profile.ProviderRef` 里实现、没有断言 → 补 `ProfileTest#rejectsOutOfRangeTemperature`。

4. **回滚路径保持可用。** `FileSessionStore` 及其单测按设计未删除，回滚只需把 `SessionStore` 换回它。
