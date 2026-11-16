# Design

## Context

- 现状：`lytgos-web` 只有地基（`SystemInfoController`、`ApiNotFoundController`、`GlobalExceptionHandler`、`TraceIdFilter`、统一响应体），唯一业务端点是 `GET /api/v1/system/info`。
- 业务装配全部住在 `lytgos-cli`：`CliConfiguration` + `CoreBeans` + `ProviderBeans` + `ToolBeans` + `CliBeans` + `AgentRuntimeFactory` + `AgentRuntime` + `WorkspaceConfig` + `WorkspaceConfigProvider`。`lytgos-boot` 已经依赖所有业务模块与 `lytgos-cli`，但它的 `LytgosApplication` 只做组件扫描，没有业务 Bean。
- 两个可执行产物：`lytgos-cli/target/lytgos-cli-boot.jar`（main = `LytgosCli`）、`lytgos-boot/target/lytgos.jar`（main = `LytgosApplication`，默认端口 9898）。
- 存储层已就绪：SQLite 三表、`SqliteSessionStore`、审计写入；`SessionStore` 接口只有 `get` / `save`。
- 约束：`AGENTS.md` 4.2 禁止条件装配与类型扫描；04 号闸门用 ArchUnit 守四条禁令，并要求每个业务模块登记到 `lytgos-test`。

动机见 `proposal.md`，行为契约见 `specs/web-service/spec.md`，此处不重复。

## Goals / Non-Goals

**Goals:**

- CLI 与 Web 两个入口共享**同一份**业务装配，不出现第二套 Bean 定义。
- 端点实现不复制 ReAct / 工具 / 记忆逻辑，只做协议适配（HTTP ⇄ 内核对象）。
- `lytgos serve` 与 `java -jar lytgos.jar` 行为一致（同一配置、同一工作区、同一数据库）。
- 新增能力全部可被 14 号闸门用真实 HTTP 链路证明。

**Non-Goals:**

- 不做 REST 鉴权、限流、SSE、多用户隔离（设计 012 / 019）。
- 不做 `/admin/` 的写操作与审计 UI（第 4 周）。
- 不改 `sessions` 表结构，不做会话分页。
- 不把 `lytgos-web` 做成独立可部署服务，仍是嵌进两个入口的库。

## Decisions

### D1 新增 `lytgos-runtime` 模块，下沉业务装配

**决策**：新建 Maven 模块 `lytgos-runtime`（包 `io.lytgos.runtime`），迁入：`WorkspaceConfig`、`WorkspaceConfigProvider`、`CoreBeans`、`ProviderBeans`、`ToolBeans`、`CliBeans`（改名 `RuntimeBeans`）、`CliConfiguration`（改名 `RuntimeConfiguration`）、`AgentRuntimeFactory`、`AgentRuntime`，以及 `WorkspaceConfigProviderTest`。

**理由**：`lytgos-web` 的控制器需要 `AgentRuntimeFactory` / `SessionStore` / `MemoryStore` / `AgentLoader` / `ProviderRegistry`。若这些类型继续住在 `lytgos-cli`，`lytgos-web` 就必须依赖 `lytgos-cli`；而 `serve` 又要让 `lytgos-cli` 依赖 `lytgos-web`——形成循环，且把入口层当成库用。

**备选与否决理由**：

- 让 `lytgos-web` 依赖 `lytgos-cli`：循环依赖，且入口模块被库引用，分层倒置。
- 在 `lytgos-boot` 里复制一套 Bean：两份装配必然漂移，违反「唯一装配入口」的既有注释约定。
- 直接把控制器写进 `lytgos-cli`：会让 CLI 包变成 HTTP 层，`lytgos-web` 名存实亡。

**依赖方向**：`lytgos-runtime` → core / persona / provider / memory / tool / storage；`lytgos-cli`、`lytgos-web`、`lytgos-boot` → runtime。`lytgos-cli` → `lytgos-web`（仅 `serve` 需要），`lytgos-web` 不依赖 `lytgos-cli`。

### D2 `serve` 命令与 `LytgosApplication` 共享 `RuntimeConfiguration`

**决策**：`ServeCommand` 放在 `lytgos-cli`，实现为：

1. 解析 `--port`（默认 8989）、`--root`（空则不传）；
2. 组装命令行参数 `--server.port=<port>`、`--server.address=127.0.0.1`、`--lytgos.root=<root>`；
3. `new SpringApplicationBuilder(ServeApplication.class).run(args)`，`ServeApplication` 是 `lytgos-cli` 里的 `@SpringBootConfiguration + @EnableAutoConfiguration + @Import(RuntimeConfiguration.class)`。

`lytgos-boot` 的 `LytgosApplication` 同样补上 `@Import(RuntimeConfiguration.class)`。

**理由**：两个入口用同一个 `RuntimeConfiguration`，`java -jar lytgos.jar` 与 `lytgos serve` 只差启动参数。走命令行参数而不是 `SpringApplicationBuilder.properties()`：`.properties()` 是 default properties，优先级最低，会被环境变量盖掉；命令行参数优先级最高，能保证 `--root` 一定生效。

**备选与否决理由**：

- `serve` 起 `lytgos.jar` 子进程：多一层进程，Ctrl+C / 退出码 / 日志要人工转发，且开发期依赖 jar 路径。
- `serve` 放 `lytgos-boot`：`LytgosCommand` 在 `lytgos-cli`，编译期引用不到（`lytgos-boot` 依赖 `lytgos-cli`，反向不成立）。

### D3 工作区根由 `lytgos.root` 属性决定

**决策**：`RuntimeBeans.workspaceLocator(@Value("${lytgos.root:}") String root)`：属性非空时以它为起点做 `Workspace.discover`，为空时用 `System.getProperty("user.dir")`。Spring 的环境属性源会把 `LYTGOS_ROOT` 解析为 `lytgos.root`，因此环境变量与 `--root` 都生效，且命令行参数优先。

**理由**：CLI 的既有行为（按当前目录向上发现工作区）保持不变；Web 侧需要「从任意目录启动并指向指定工作区」的能力，这是 `serve` 的部署前提。

### D4 会话标识采用 `api:<user>:<agent>`

**决策**：`POST /api/v1/sessions` 以 `api:<user>:<agent>` 作为 key，`user` 缺省 `api-default`；`user` 与 `agent` MUST NOT 含 `:`，`agent` 还要满足 `AgentLoader` 的名称规则（不含 `/`、`\`、`..`）。违反者返回 400 + `LYTGOS_INVALID_AGENT`。发消息端点从 key 反解 agent 名。

**理由**：与既有的 `cli:local:<agent>` 同构；同一 `(user, agent)` 天然幂等，不需要额外的索引表；从 key 就能定位运行时，不必为会话加元数据。

**备选与否决理由**：UUID + 元数据表 —— 需要新增表与迁移脚本，且 `SessionStore` 的 key 语义会被拆成两套。

### D5 `SessionStore` 扩展 `list()` / `delete()`，不新建查询接口

**决策**：接口新增 `boolean exists(String key)`、`List<SessionSummary> list()` 与 `void delete(String key)`；新增 `SessionSummary(String key, int messageCount, String updatedAt)`（放 `io.lytgos.core.session`）。`SqliteSessionStore` 用 `SessionMapper.exists(key)` / `selectAll()`（返回 key / messages / updated_at，Java 侧解析 JSON 得消息数）/ `delete(key)`；`FileSessionStore` 用文件存在性、遍历 `sessions/*.json` 反序列化 `Snapshot`、删文件并清缓存。

**理由**：`list` / `delete` / `exists` 与会话是同一种资源的读写，拆成两个接口会让调用方同时持有两个依赖；设计文档第 4 周的 `lytgos session list` 也要用同一能力。`exists` 不可省：`get` 对不存在的 key 返回空历史会话（既有契约），无法区分「刚建的空会话」与「不存在」。

**备选与否决理由**：在 SQL 里用 `json_array_length` —— 依赖 SQLite JSON1 扩展与版本，老库有风险；会话量级小，Java 解析更稳。

### D6 运行时按 Agent 缓存，会话级串行，对外暴露 `AgentInvoker` 端口

**决策**：

- 新增接口 `AgentInvoker`（`io.lytgos.runtime`）：`String run(String agent, Session session, String input)` 与 `ToolRegistry tools(String agent)`；
- 新增 `RuntimePool implements AgentInvoker`：`ConcurrentHashMap<String, AgentRuntime>` 懒创建并缓存 per-agent 运行时，创建失败不缓存（已拉起的 MCP 子进程由 `AgentRuntimeFactory` 既有逻辑回收），`@PreDestroy` 关闭全部运行时；
- 另用 `ConcurrentHashMap<String, ReentrantLock>` 按会话 key 加锁，保证同一会话的并发请求串行执行。

**理由**：`runtimeFor()` 会启动 MCP 子进程，按请求创建会让 HTTP 并发直接变成进程炸弹；`Session` 是可变对象，同一 key 的两个请求并发 `append` 会互相覆盖。引入 `AgentInvoker` 端口是为了让控制器不直接依赖 final 的 `AgentRuntime`（构造器包私有、带 MCP 进程），Web 层单测可用假实现替换，不启动任何模型或子进程。

**备选与否决理由**：每请求创建运行时 —— MCP 进程泄漏风险；全局一把锁 —— 不同会话之间也串行，吞吐退化；控制器直接用 `AgentRuntimeFactory` —— 单测必须拉起真实 MCP 才能覆盖发消息路径。

### D7 资源不存在由控制器返回 404，不扩全局异常映射

**决策**：会话不存在、归档目标不存在时，控制器直接返回 `ResponseEntity.status(404).body(ApiResponse.error("LYTGOS_NOT_FOUND", "..."))`。`GlobalExceptionHandler` 不动，`http-api` 的受控异常映射表保持不变。

**理由**：给 `LytgosException` 加一个「NotFound」子类并映射 404，需要同步修改 `http-api` 的映射契约与该 spec 的场景，扩大本次范围且与「未知路径 404」语义混用。

### D8 `/admin/` 是单个自包含 HTML，由控制器读 classpath 返回

**决策**：资源放 `lytgos-web/src/main/resources/admin/index.html`，`AdminPageController` 以 `@GetMapping({"/admin", "/admin/"})` 读取该 classpath 资源、以 `text/html; charset=UTF-8` 返回。页面内联 CSS 与 JS，用 `fetch` 调 `/api/v1/*`，渲染一律 `textContent`（不用 `innerHTML` 拼外部数据）。

**理由**：不引入前端构建链（没有 node/pnpm 依赖），断网可用，符合 spec 的「无外部资源」约束；显式控制器避免 `static/` 目录索引的映射不确定性。

### D9 端口分配与回环绑定

**决策**：全仓端口集中在一张表里，不再允许各闸门就近挑号：

| 用途 | 端口 | 性质 |
|---|---|---|
| `lytgos.jar`（boot 入口） | 9898 | 常驻，`application.yaml` |
| `lytgos-channel` | 8081 | 常驻 |
| `lytgos serve` | **8989** | 常驻，默认值 + `--port` 可覆盖 |
| 11 号闸门 `web-smoke` | **18090** | 测试期临时；**从 18080 迁入** |
| 12 号闸门 `log-trace` | 18081 / 18082 | 测试期临时（dev / prod） |
| 14 号闸门 `web-api-acceptance` | **18083** | 测试期临时 |
| 手工验证（tasks 4.x） | **18084** | 实施期临时 |
| **预留 Nacos** | **18080** | **不被任何本项目脚本占用** |
| 不推荐使用 | 8080 | VMware vmnat 常占 |

`lytgos-web-defaults.yaml` 增加 `server.address: 127.0.0.1`，并在 `serve` 的命令行参数里重复一次（命令行优先）。`server.port` 不进公共文件：`lytgos.jar` 保持 9898，`serve` 由默认值 / 参数给 8989。

**理由**：两个入口都引入 web-defaults，一处配置即覆盖两条启动路径；端口必须分开，否则 `serve` 与 `java -jar lytgos.jar` 无法同时运行。用 8989 而不是 8080：8080 被 VMware vmnat 占用在本机已两次造成误判（`application.yaml` 注释与 `add-web-foundation` 的 Open Question 都记了这条）。

**18080 让位**：18080 预留给后续 Nacos。11 号闸门当前占着它，本次一并迁到 18090，同步改 `harness/checks/11-web-smoke.ps1` 的 `$port`、脚本头注释与 `harness/README.md` 的闸门表。归档 change 里的 18080 是历史快照，不回改。

**备选与否决理由**：

- 把 `server.port` 也写进公共文件 —— 会覆盖 `application.yaml` 的 9898，破坏 11 号闸门与既有入口行为。
- 闸门继续就近取号（14 号原本打算用 18081）—— 18081/18082 已被 12 号闸门占用，会直接撞车；这种隐式分配本身就是缺陷。

### D10 `GET /profiles` 用「条目 + 错误」两段结构

**决策**：`AgentLoader` 新增 `catalog(Path agentsRoot)`，返回 `AgentCatalog(List<Entry> items, List<EntryError> errors)`；`Entry` 含 name（目录名）、description、provider、model、tools。单个 Agent 解析失败只进 `errors`，不影响其他条目。

**理由**：管理台与列端点面向「观察现状」，一个写坏的 `AGENT.md` 不该让整个列表 500；错误原因仍完整返回，用户能直接定位。

### D11 端点契约摘要

请求与响应体一律使用统一响应体 `ApiResponse`，`data` 结构如下：

| 方法 | 路径 | 请求 | `data` |
|---|---|---|---|
| POST | `/api/v1/sessions` | `{"agent":"default","user":"u1"?}` | `{sessionId, agent, user, created:true/false}` |
| GET | `/api/v1/sessions` | — | `{items:[{sessionId, messageCount, updatedAt}], total}` |
| GET | `/api/v1/sessions/{id}` | — | `{sessionId, messages:[{role,content,toolCalls?}]}` |
| DELETE | `/api/v1/sessions/{id}` | — | `{sessionId, archived:true}` |
| POST | `/api/v1/sessions/{id}/messages` | `{"message":"..."}` | `{sessionId, reply, messages}` |
| POST | `/api/v1/agents/{name}/invoke` | `{"input":"..."}` | `{agent, reply}` |
| GET | `/api/v1/profiles` | — | `{items:[{name,description,provider,model,tools}], errors:[]}` |
| GET | `/api/v1/tools?agent=default` | — | `{agent, tools:[{name,description}]}` |
| GET | `/api/v1/memory` | — | `{content, chars, maxChars}` |
| GET | `/api/v1/health` | — | `{status:"UP", version}` |
| GET | `/api/v1/info` | — | `{appName,version,javaVersion,virtualThread,workspaceRoot,providers:[{name,model}],sessions}` |

`POST /agents/{name}/invoke` 使用一次性 `Session`（key 形如 `invoke:<uuid>`），不写库、不进列表。

### D12 14 号闸门：`14-web-api-acceptance.ps1`

**决策**：结构照抄 11 号（启动 jar → 轮询就绪 → 逐条断言 → 端口+命令行双重收尸），端口用 **18083**（18081/18082 是 12 号的，18084 是手工验证的，18080 预留 Nacos）。工作区用 `lytgos-cli-boot.jar init` 建临时目录，并把 `providers` 配置指向 `DEEPSEEK_API_KEY`。断言分两组：

- **不依赖模型（始终执行）**：health、info、profiles、tools、memory、sessions 创建/列表/查询/归档、未知路径 404、`/admin/` HTML 且无外部引用、OpenAPI 含新路径。
- **依赖模型（缺 `DEEPSEEK_API_KEY` 时 SKIP）**：发消息往返、跨请求上下文、无状态 invoke。

最终：全部执行且通过 → 0；有 SKIP → 2（NOT_READY，与 07/08/10 同口径）；任一 FAIL → 1。

同步项：`AGENTS.md` 的 `harness-checks:` 标记行加 `14-web-api-acceptance`；`harness/README.md` 的闸门清单加一行并补端口分配表；`lytgos-test/pom.xml` 登记 `lytgos-runtime`（否则 04 闸门的「模块未登记」对账直接 FAIL）。

## Risks / Trade-offs

- [cli fat jar 体积与 `serve` 启动时间上升] → `--version` / `--help` 仍走懒启动、不加载 Spring；实测启动耗时写进 tasks 的验收记录，若 `--version` 明显变慢再评估拆分（例如把 `serve` 挪到独立入口）。
- [MCP 子进程随 RuntimePool 常驻] → `@PreDestroy` 关闭全部运行时；闸门收尸按「端口 + 命令行」双重校验，只杀本闸门启动的进程。
- [SQLite 连接池大小 1 在 HTTP 并发下成为串行点] → 会话读写是短事务；若 14 号闸门的并发探测出现明显阻塞，再评估把池调到 4（WAL 允许多读）。
- [管理台页面注入风险] → 全部动态内容经 `textContent` 渲染；页面不接收富文本。
- [缺 key 时返回 500 而非 400] → 与 `http-api` 既有映射一致，调用方按 `code` 分支；不改全局映射。
- [新增 `server.address` 与端口重排可能影响既有行为] → 11 号闸门迁到 18090 后单独复跑；07/08/10 不经 HTTP 访问，不受影响；重新跑全量闸门确认。
- [模块迁移带来的编译面变化] → 迁移阶段不改变任何类行为，只改包名与 pom；迁移完成即跑 07/08 验证 CLI 链路未变。

## Migration Plan

1. **阶段一（纯重构）**：新建 `lytgos-runtime`，迁移 9 个类与 1 个测试，改三个模块的 pom 与 import；编译 + 全量单测 + 07/08 CLI 闸门必须维持原状。
2. **阶段二（存储扩展）**：`SessionStore` 加 `list` / `delete`，两个实现与 `SessionMapper` 同步；单测覆盖「列出空表」「归档后不出现」。
3. **阶段三（端点与 serve）**：`lytgos-web` 八个控制器 + `RuntimePool` + `SessionKeys`；`ServeCommand` / `ServeApplication`；`RuntimeBeans` 的 `lytgos.root` 支持。
4. **阶段四（管理台与闸门）**：`/admin/` 页面与控制器；14 号闸门；`AGENTS.md` 与 `harness/README.md` 清单同步；`lytgos-test` 登记。
5. **回滚**：删除新增端点与 `ServeCommand` 不影响已有 CLI；`sessions` 表无结构变更，回滚不需要数据迁移；`lytgos-runtime` 回滚即把类迁回 `lytgos-cli`。

## Open Questions

- 会话列表是否需要分页：当前全量返回，会话数上百后响应体会变大；结论留给第 4 周的 `session list` 一并决定，不影响本次 spec（spec 只约定返回全部）。
- `/admin/` 是否要展示审计表：属第 4 周审计 UI，本次只读基础信息。

## 实施记录

按 `systematic-debugging` 的要求，实施与验收期出现的每个「红」都记下根因、影响面与回归位置；复现路径均已由闸门自身固化。

### R1 `--version` 加载 6 个 `org.springframework.context.*` 类（09 闸门）

- 现象：09 闸门 L0 判据失败，`java -jar ... --version -verbose:class` 出现 `ApplicationEvent` / `MessageSource` / `ApplicationEventPublisher` / `ApplicationContext` / `Lifecycle` / `ConfigurableApplicationContext`。
- 根因：picocli 在 `CommandLine` 构造阶段用 IFactory 实例化全部子命令；原 `ServeCommand` 的方法签名出现 `ConfigurableApplicationContext`，类验证（verification）解析方法签名时连锁加载其父接口与 `ContextClosedEvent` 的父类。不是 Spring 被启动了，是类型被解析了。
- 修复：新建 `ServeBootstrap`（package-private，Spring 类型全部集中于此），`ServeCommand` 只保留 picocli 字段与 `ServeBootstrap.run(port, root, startDir)` 调用；`--version` 路径回到零 Spring 类。
- 回归：09 闸门硬判据（`-verbose:class` 不含 `org.springframework.context.*`）；L0 min≤900ms、L1 min≤2500ms。

### R2 12 号闸门的 `serve` 起不来（工作区依赖）

- 现象：12-log-trace 在仓库根启动应用时报「当前目录不在 lytgos 工作区内」。
- 根因：web 自动配置引入 `InfoController` → `WorkspaceConfig`，`lytgos.jar` 自此需要工作区；11 号闸门早已用「临时 `.lytgos/config.yaml` + `--lytgos.root`」适配，12 号闸门是遗漏，不是产品缺陷。
- 修复：12 号闸门内建临时最小工作区（`providers: {}`、空白名单），启动参数加 `--lytgos.root`，收尾清理。
- 回归：12 号闸门六条（日志契约）全 PASS；`serve` 在工作区外启动的报错行为仍由 4.3 的验证口径覆盖。

### R3 `dependency-check:check` 解析不到 reactor 内新模块（13 闸门）

- 现象：`mvn -B -Psecurity org.owasp:dependency-check-maven:check` 报 `Could not find artifact io.lytgos:lytgos-runtime:jar:0.1.0-SNAPSHOT`；加 `-am` 无效。
- 根因：direct goal 调用只从本地仓库解析模块依赖，不进入 reactor 生命周期会话；`lytgos-runtime` 从未 `install`，只有 `verify` 生命周期才会把 reactor 内未安装模块按 `target/classes` 解析。
- 修复：13 号闸门改为 `mvn -B -Psecurity verify`，与 CI security job 完全一致；代价是重复 02-test 的编译与测试（实测 332s），换来「与 CI 同一命令」的确定性。
- 回归：13 号闸门单跑与 run-all 全量均 PASS；新增模块不再导致假红。

### R4 14 号闸门 M1 锁死历史条数导致假红（含 e2e 同类断言）

- 现象：`status=200 reply 非空=True 历史条数=4`，断言 `history -eq 2` 判 FAIL。
- 根因：模型本轮调用了工具，历史为 `user + assistant(toolCalls) + tool + assistant` 四条；条数随模型行为变化，不是一个稳定契约。
- 修复：断言改为「`history ≥ 2` 且首条 `role=user`、末条 `role=assistant`」，`14-web-api-acceptance.ps1` M1 与 `.scratch/e2e-check.ps1` 步骤 7 同口径。
- 回归：14 号闸门 15/15 PASS；e2e 八步 PASS。

### R5 web 模块 SpotBugs 33 条的处理口径

- `EI_EXPOSE_REP`（7 处）：DTO record 的可变 `List` 字段在紧凑构造器里 `List.copyOf`，不豁免。
- `CT_CONSTRUCTOR_THROW`（1 处）：`AdminPageController` 构造器读 classpath 失败会抛异常；改为 `private` 构造器 + `public static load()` 静态工厂，由 `LytgosWebAutoConfiguration` 的 `@Bean` 在装配期构造——失败发生在装配期，不在半成品对象上。
- `EI_EXPOSE_REP2`（新增 4 处）与 `SPRING_ENDPOINT`（13 条）：登记在 `spotbugs-exclude.xml` 并写明重审条件（引入 Cookie/Session 认证、表单入口、非回环绑定即重审），不提供无条件豁免。
- 回归：`mvn -B -pl lytgos-web -am verify` 与全量 `mvn -B verify` 均 BUILD SUCCESS（14 模块）。

### 验收快照

- 全量 `mvn -B verify`：14 个 reactor 模块全 SUCCESS。
- `powershell -File harness\run-all.ps1`：`RUNALL_EXIT=0`，15 道闸门全 PASS（耗时见 `tasks.md` 7.1 完成说明）。
- 端到端八步：PASS（见 `tasks.md` 7.2 完成说明）。
