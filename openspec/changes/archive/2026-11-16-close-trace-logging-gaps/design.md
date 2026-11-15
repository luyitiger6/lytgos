# Design

## Context

动机见 `proposal.md`。当前状态与本次设计相关的约束：

- `TraceIdFilter`（lytgos-web）与 `ReActLoop`（lytgos-core）目前各自独立：前者向 MDC 写 `traceId`，后者只生成 `turnId` 且不碰 MDC。CLI 路径没有外层 filter，MDC 为空。
- 审计写入有两处调用点：`AuditedChatModel`（模型调用）与 `ToolExecutor`（工具执行），二者都在执行线程上同步运行，MDC 对它们可见。
- 建表由 `DataSourceInitializer` 执行 `lytgos-schema.sql`，全部 `IF NOT EXISTS`。SQLite 没有 `ADD COLUMN IF NOT EXISTS`，已有库不会被 `CREATE TABLE IF NOT EXISTS` 补列。
- `harness/run-all.ps1` 按文件名顺序自动枚举 `checks/*.ps1`；`00-layout-check.ps1` 会把 `AGENTS.md` 的机器可读闸门清单与实际文件比对。
- CLI 与 Web 各有一份日志配置：`lytgos-cli/logback.xml`（stderr）与 `lytgos-web/logback-spring.xml`（由 `logging.config` 显式指定）。CLI 独立 fat jar 里只有前者，构成互相隔离的两套输出通道。

## Goals / Non-Goals

**Goals:**

- 一个 traceId 从入口到审计行全程不变：Web 请求用请求级值，CLI 用本轮对话值。
- 已有 `lytgos.db` 原地补列，不重建、不丢审计行。
- 日志分层与 traceId 对齐由闸门自动验收，不再依赖手工核对。
- 四个业务模块在启动期有可见事件，且不制造高频噪音。

**Non-Goals:**

- 不引入分布式链路追踪（OpenTelemetry / span / 跨进程传播）。当前是单进程，traceId 只服务于「日志 ↔ 审计」互查。
- 不给 `sessions` 表加 traceId：会话是跨多个 trace 的容器，加一列只会产生「存哪个 trace」的歧义。
- 不改审计的 I1/I2/I3 不变量判定口径。
- 不做历史审计行的 traceId 回填——旧行本来就没有该值。

## Decisions

### D1. `TraceContext` 作为 MDC 的唯一读写入口

在 `lytgos-core` 新增 `io.lytgos.core.trace.TraceContext`：

- `MDC_KEY = "traceId"`（与现有 `TraceIdFilter.MDC_KEY` 同值）
- `current()` 返回当前值或 `null`
- `Scope ensure(String fallback)`：MDC 已有值时返回空作用域（close 无副作用）；为空时写入 `fallback`，`close()` 时移除

`TraceIdFilter` 改为引用 `TraceContext.MDC_KEY`，不再自带常量。

**为什么不是各调用点直接 `MDC.get("traceId")`**：字符串键会散落在 core / provider / web / cli 四处，任何一处拼错都不会编译失败，只会让审计行静默变空。收敛到一个类后，键名只有一处定义，`ensure` 的「已有则不覆盖」语义也只有一处实现。

**备选**：把常量放在 `TraceIdFilter` 由其他模块引用。否决——`lytgos-provider` / `lytgos-core` 反向依赖 `lytgos-web` 会颠倒依赖方向（04 号闸门的 ArchUnit 规则会直接拦截）。

### D2. traceId 的生成点在 `ReActLoop.run()`

`ReActLoop.run()` 在生成 `turnId` 后立刻 `TraceContext.ensure(turnId)`，整轮包在同一作用域内。

- Web 路径：filter 已写入请求 traceId → `ensure` 不覆盖 → 审计行与请求 traceId 一致。
- CLI 路径：MDC 为空 → 用 `turnId` → 该轮日志与审计行天然对齐，且轮次结束自动清理。

**为什么不放在 `ChatCommand` / `CliChannel`**：这两个入口只有 CLI 一份；将来 Web 若增加对话端点，traceId 会由 filter 提供而不是每个调用方各写一遍。把保证放在 `ReActLoop` 这个所有路径的必经点上，才能做到「无论谁调，审计行一定有 trace」。

**备选**：CLI 每轮额外生成独立 UUID。否决——与 `turnId` 语义完全重合，两个值一一对应只会让排查时多一次换算。

### D3. 审计 record 增加 `traceId`，允许为空

`LlmCallRecord` 与 `ToolInvocationRecord` 在 `turnId` 之后插入 `String traceId`，不做非空校验：历史行与将来的后台任务都可能没有请求上下文。写入侧由 `TraceContext.current()` 取值。

**为什么不做 `requireText`**：审计的首要约束是「不能因为取不到 traceId 就写不进去」。把可空性交给数据库列（`TEXT` 可空），把「应该非空」交给 spec 场景与闸门验证，比在构造器里抛异常更安全。

**migration 影响**：record 是位置参数，5 处构造点要同步改：生产 2 处（`AuditedChatModel`、`ToolExecutor`），测试 3 处（`SqliteStoreTest` 里一处直接构造，以及 `llmCall` / `toolCall` 两个辅助方法）。此改动一次到位，不留重载构造器——重载会让调用方继续用旧签名，字段最终写成 null。

**为什么必须逐点核对而不是只靠编译器**：`LlmCallRecord` 前四个字段与新增的 `traceId` 全是 `String`，插入后形成五个连续 `String` 参数。位置放错（例如 traceId 进了 `provider` 位）编译器不会报错，只会让审计行的 provider 列出现 UUID、trace_id 列出现 `deepseek`。缓解手段是让 `SqliteStoreTest` 读回时同时断言 `provider` 与 `trace_id` 两个值，任何错位立即变红。

### D4. 用 Java 迁移器补列，不引入 Flyway，不重建库

新增 `io.lytgos.storage.SqliteSchemaMigrator`：

1. `PRAGMA table_info(llm_calls)` / `PRAGMA table_info(tool_invocations)` 读取现有列
2. 缺 `trace_id` 时执行 `ALTER TABLE <name> ADD COLUMN trace_id TEXT`
3. `CREATE INDEX IF NOT EXISTS idx_<table>_trace ON <table> (trace_id)`

由 `CoreBeans` 注册为 bean，依赖 `DataSourceInitializer` 之后执行；每步幂等，重复启动无副作用；真正执行了 `ALTER` 时打一行 INFO，其余情况静默。

同时把 `trace_id TEXT` 写进 `lytgos-schema.sql` 的 `CREATE TABLE`，让新库一次建全。

**为什么不用 Flyway**：当前建表策略是「幂等脚本 + `DataSourceInitializer`」，没有版本表；为了两列引入迁移框架等于换掉整个建表机制。`docs/design/architecture.md` 里 Flyway 属 025 之后的路线图，不在本阶段。

**为什么不要求删库重建**：审计行是产品卖点，删库等于销毁证据。

**为什么索引也在迁移器里建**：`CREATE INDEX IF NOT EXISTS` 在列不存在时会失败。放在 `ALTER` 之后统一执行，才能保证老库不报错。实测证据：把索引写进 `lytgos-schema.sql` 时，旧结构库启动在脚本第 7 条语句 `CREATE INDEX ... ON llm_calls (trace_id)` 处直接失败（`no such column: trace_id`），而迁移器被 `@DependsOn("schemaInitializer")` 排在脚本之后，根本没有机会补列。

### D5. 日志闸门独立成 `12-log-trace.ps1`

三组判据，均对真实 jar 执行：

1. dev profile：启动并请求一次，日志不含以 `{` 开头的 JSON 行
2. prod profile：每个以 `{` 开头的行可 `ConvertFrom-Json`，且启动行含 timestamp / level / logger / message
3. 请求期对齐：带 `X-Request-Id: probe-<uuid>` 请求，dev 日志含 `[probe-<uuid>]`，prod 日志行 JSON 的 `traceId` 等于该值

三态：0 全过 / 2 缺 jar 或 java / 1 任一失败。端口用 18081、18082，避开 11 号闸门的 18080。

**为什么不扩展 11-web-smoke**：11 号已有 9 条 HTTP 契约判据，再加日志格式会让「HTTP 契约」与「日志契约」混在一个闸门里，失败时无法从闸门名判断坏在哪一层。

**请求期日志由 `TraceIdFilter` 自己的探针行提供，不依赖框架内部日志**：`TraceIdFilter` 在 `MDC.put` 之后、`chain.doFilter` 之前打一条 DEBUG（`trace attached: <method> <sanitized-uri>`）。否决「打开 `DispatcherServlet` DEBUG 来触发请求行」的理由有三：Spring 升级改级别或去掉该日志会让闸门假红；为绕开它放宽到 `org.springframework.web=DEBUG` 会让无关行也带上 traceId，可能假绿；探针行由本仓库代码保证存在，才谈得上「掐住自己的关键路径」。

- dev 侧：`io.lytgos=DEBUG` 已在 `!prod` 分支配置（见 D8），闸门直接用默认配置启动，不传 level 参数——验证的就是开发者本机的真实行为。
- prod 侧：root 为 INFO，闸门显式传 `--logging.level.io.lytgos=DEBUG` 才能拿到探针行。

**prod 判据只校验以 `{` 开头的行**：Tomcat、sqlite-jdbc、HikariCP 等第三方库可能直接往 stdout 打非 JSON 文本，那不是我们的日志契约违约。把「每行可 JSON」降级为「每个 `{` 开头的行可 JSON」，既守住契约又不被第三方噪音误伤。

### D6. 四模块日志：启动期 INFO、运行期 DEBUG

| 模块 | 位置 | 级别 | 内容 |
|---|---|---|---|
| persona | `AgentLoader.load` | DEBUG | 已加载 Agent 名、provider、model |
| provider | `ProviderRegistry` 构造 | INFO | 已登记 provider 名与 model（一次性） |
| provider | `ProviderRegistry.require` 首次构造 | DEBUG | 首次构造 ChatModel |
| storage | `SqliteSchemaMigrator` | INFO | 仅在实际补列时输出 |
| storage | `SqliteSessionStore.save` | DEBUG | 会话键与消息数 |
| memory | `MemoryStore.append` | DEBUG | 写入后字符数 |

外部输入（agent 名、provider 名、会话键、文件路径）一律经 `LogSafe.sanitize`。

**为什么 provider 登记用 INFO**：启动期一次，且能回答「为什么模型跑到了 deepseek 而不是我配的另一个」——这是排查时第一个要确认的事实。**为什么 agent 加载用 DEBUG**：每次 `chat` 都会加载，INFO 会让 CLI 每次对话多一行噪音。

### D7. CLI pattern 增加 traceId 位

`lytgos-cli/logback.xml` 的 pattern 改为 `%d{HH:mm:ss.SSS} %-5level %logger{24} [%X{traceId:-}] - %msg%n`，与 web 的 dev 格式对齐。`-` 默认值保证轮次外的日志（如启动期）照常输出。

### D8. dev 环境默认开 `io.lytgos=DEBUG`，SQL 例外压回 INFO

在 `logback-spring.xml` 的 `<springProfile name="!prod">` 分支内加两条覆盖：

```xml
<logger name="io.lytgos.storage.mapper" level="INFO"/>
<logger name="io.lytgos" level="DEBUG"/>
```

prod 分支不加任何覆盖，维持 root INFO。

**为什么必须加**：现状下 dev 与 prod 的日志级别完全相同（都是 root INFO），差别只有 encoder。若不覆盖，D6 设计的四条 DEBUG 日志与 D5 的 traceId 探针在 dev 环境全部不可见——给开发排查用的诊断日志，开发本人看不到，只有单测与闸门能看到。这不是「日志太少」，而是设计与用途错位。

**为什么 mapper 单独压回 INFO**：MyBatis 的 statement logger 名为 mapper 接口全限定名（`io.lytgos.storage.mapper.AuditMapper`），落在 `io.lytgos` 之下。不设例外的话，每个请求的 SQL 与参数会盖过业务日志。logger 的显式级别优先于父级继承，所以这一条能在 `io.lytgos=DEBUG` 之下单独生效；需要看 SQL 时临时删掉这一行即可。

**为什么 prod 不加**：DEBUG 在生产意味着日志体积不可控，且探针行会随每个请求输出。prod 需要 DEBUG 时用启动参数临时开启，不改仓库配置。

### D9. SpotBugs CRLF 豁免：日志参数已净化但插件不识别

`spotbugs-exclude.xml` 新增五条 `CRLF_INJECTION_LOGS` 豁免（`AgentLoader` / `ProviderRegistry` / `MemoryStore` / `SqliteSessionStore` / `TraceIdFilter`）。依据与该文件既有的 `McpServerManager` 条目一致：这些日志参数来自文件 / 配置 / 工具入参 / 请求行，属于 FindSecBugs 的 taint source，但它们在写日志前都经过 `LogSafe.sanitize`（CR / LF / TAB 转义），而插件不识别自定义 sanitizer。

同时把 `SqliteSchemaMigrator` 的 SQL 从「常量字段 + 通用 execute」改为「字面量直接内联到每个 execute 调用」：FindSecBugs 的常量传播不穿过 record 字段读取，抽常量反而让 6 处语句全被判成非恒定 SQL（`SQL_INJECTION_JDBC` / `SQL_NONCONSTANT_STRING_PASSED_TO_EXECUTE`）；改为两表各写一遍后该模块零豁免、零拼接。

新增日志点如果引入未净化的外部字符串，豁免会一并放行，所以 code review 必须确认新日志参数也走 `LogSafe.sanitize`；每条豁免的「重审条件」已写入 exclude 文件注释。

**为什么不改成 `@SuppressFBWarnings`**：那需要每个模块引入 `spotbugs-annotations` 依赖，注解散落在业务代码里；exclude 文件把「为什么放宽」集中在一处，与既有台账风格一致。`includeTests=true` 意味着测试代码同一规则同样适用。

## Risks / Trade-offs

- [闸门 12 让全套 harness 多起两次 JVM] → 实测 11 号闸门完整周期（1 次启动 + 9 条断言 + 收尸）为 18.7 秒；12 号断言更少但启动两次，增量约 20~30 秒。全套 harness 中 web jar 启动次数从 1 次变为 3 次，这是「dev 与 prod 两套日志格式必须分别验证」的必然成本，不做压缩。
- [SQLite `ALTER TABLE` 会短暂持有写锁] → 迁移在容器启动、业务流量接入之前执行，且单进程单连接，实际冲突概率为零。
- [交互模式每轮清理 MDC，若将来 ReActLoop 内部引入异步执行，MDC 不会自动传递] → 在 `ReActLoop` 的类注释里写明「本轮所有执行必须在调用线程上完成」，异步化时需显式传递 traceId。
- [record 增加位置参数会让 5 处构造点编译失败] → 这是刻意的编译期拦截，一次改完且不留重载构造器。真正的风险不是编译失败，而是五个连续 `String` 参数的位置错位——编译器无法识别，靠 `SqliteStoreTest` 同时断言 `provider` 与 `trace_id` 的值兜住。
- [闸门 12 依赖 `TraceIdFilter` 自己的探针行] → 探针行是本仓库代码，与框架版本解耦；风险降级为「有人删掉或改了探针行」，而那种改动会同时让闸门 12 变红，正是期望行为。
- [dev 默认 `io.lytgos=DEBUG` 会让日志量上升] → mapper 已单独压回 INFO；仍可能出现单次请求数行 DEBUG，这是 dev 环境需要诊断信息的代价，prod 不受影响。

## Migration Plan

1. 代码内先落地 `TraceContext` 与 `ReActLoop.ensure`，此时审计字段尚未加列——按任务顺序先改 schema 与 record，再改写入侧，避免中间态编译失败。
2. 已有库：首次启动由 `SqliteSchemaMigrator` 补列，每张表一行 INFO：`schema 迁移：<表> 补列 trace_id 并建索引，历史行保持 NULL`。
3. 回滚：新列可空，旧版代码的 `INSERT` 不引用 `trace_id`，回退到旧 jar 后写入仍成功，只是新行的 trace_id 为空。
4. 闸门 12 上线后写入 `AGENTS.md` 的机器可读清单，`00-layout-check` 立即参与比对。

## Open Questions

| # | 问题 | 级别 | 影响任务 | 默认倾向 |
|---|---|---|---|---|
| 1 | CLI 是否提供开 DEBUG 的开关（`--verbose`），让用户现场排查时能看到 `io.lytgos` 的 DEBUG 日志？CLI 用纯 `logback.xml`，`<springProfile>` 不生效，需要另选机制（程序化设 level 或改 `logback-spring.xml`）。 | 一般 | 无（本次不做，已由用户确认） | 不加。CLI 的 stderr 是产品输出的一部分，默认保持 INFO 克制 |
| 2 | CLI 是否把本轮 traceId 打印到 `--message` 模式的 stderr 摘要里，便于用户报障时附上？ | 一般 | 3.1 | 不做，等真实诉求 |
| 3 | 后台任务（未来引入）的 traceId 命名规则（如 `job:<name>:<uuid>`）？ | 一般 | 无 | 留待该类任务立项时定义 |
