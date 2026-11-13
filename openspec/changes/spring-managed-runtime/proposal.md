# Proposal

## Why

`AGENTS.md` 把 Spring Boot 4.x + Spring AI 2.x 定为技术栈，但全仓 63 个 Java 文件里只有 4 个碰 Spring：两个业务适配器，两个空的 `@SpringBootApplication`。真正的业务代码——ReAct 循环、工具执行、沙箱校验、会话存储——全部靠手工 `new`，装配由 `CliContext` 的 236 行懒加载单例完成，配置由手写 snakeyaml 解析加正则替换环境变量完成。

技术选型写在契约第一条，实现却按「纯 Java 手写框架 + 外挂一个 HTTP 客户端」的方式走。结果是选型成了装饰：Spring Boot 提供的容器、配置绑定、生命周期管理一条没用上，而这些能力恰恰是后续接审计、接 SQLite、接 Web 入口时需要的地基。

另外还有两处契约与实现不一致：

- `04-dependency-direction.ps1` 第 5 条「`lytgos-core` 不得依赖 Spring AI 与 spring-boot-starter」是实现者自己加的，`AGENTS.md` 里没有这条，它才是「Spring 被关在 boot/provider 里」的直接原因；
- `AGENTS.md` 4.1 写「04 号闸门用 ArchUnit 禁止依赖 `ChatClient` 与 `ToolCallingManager`」，实际闸门是 PowerShell 正则，从未引入 ArchUnit。

## What Changes

- **运行时装配交给 Spring 容器**：`SandboxChecker`、`ToolRegistry`、`ToolExecutor`、`FileSessionStore`、`McpServerManager` 等能力类改为 Bean 构造注入；`CliContext` 的 236 行手工装配被删除。按 Agent 实例化、不能共享的对象（`ReActLoop`、`AgentRuntime`）改为工厂方法产出，不使用单例。
- **工作区配置改为 Spring 绑定**：`.lytgos/config.yaml` 的 `providers` 与 `sandbox` 两段改用 Spring 的 `Binder` 与占位符解析，删除手写的 snakeyaml 解析和正则替换。工作区路径在运行期发现（`init` 时还不存在），因此绑定发生在首次需要配置的时刻，不在容器刷新时。
- **`lytgos-cli` 的运行期对象改由 Spring 容器创建**：复用 classpath 中已存在的 `spring-context`（由 spring-ai 传递引入，零新增 jar），只补一个 `spring-boot` 核心包以取得 `Binder` 与 `@ConfigurationProperties`。容器用 `AnnotationConfigApplicationContext` 显式注册 + 首次需要时才 `refresh()`，**不用 `SpringApplication`、不用自动配置、不用 `@ComponentScan`**。picocli 保留，只负责命令解析。
- **启动预算成为可验收项**：`--version` / `--help` 这类纯解析命令不触发容器刷新，保持百毫秒级；新增 `09-startup-budget.ps1` 守住这条线，防止后续改动把启动拖回秒级。
- **修订 `AGENTS.md` 4.1**：只禁止自动工具执行的具体机制（`ChatClient`、`ToolCallingManager`、`@Tool` 自动执行），删除「只允许 `lytgos-provider` import `ChatModel`」这句对被模型抽象的实现方式构成束缚的表述。
- **04 闸门改用 ArchUnit**：新增 `lytgos-test` 模块承载架构测试，替换 PowerShell 正则扫描；删除自造的「core 不得依赖 Spring」规则，保留「禁止 `ChatClient` / `ToolCallingManager`」与「禁止类型扫描发现 Provider」两条真规则。

**明确不做**（本次范围外，避免范围蔓延）：

- 不删除 `io.lytgos.core.chat.*` 这套自造模型抽象；
- 不删除 `SpringAiChatModel` 适配器；
- 不处理 `lytgos-boot` / `lytgos-channel` / `lytgos-web` 三个空壳模块的存废，另立 change。

## Capabilities

### New Capabilities

无。本次不引入新的对外能力。

### Modified Capabilities

- `react-loop`：修订「Provider 显式映射」。原文要求 `${ENV_VAR}` 占位「在启动时解析」，与现有实现（首次构造 `ChatModel` 时才解析，`provider list` / `tool list` 这类只读命令不受缺 key 影响）不一致。本次改造把配置绑定迁到 Spring，需要把解析时机写成符合实际且可验收的口径，并补充只读命令的豁免场景。

## Impact

- **代码**：`lytgos-core`（5 个能力类改为可注入）、`lytgos-tool`（内置工具与 MCP 管理器）、`lytgos-cli`（装配与配置）、`lytgos-provider`（工厂改 Bean）。
- **依赖**：`lytgos-cli` 只新增 `spring-boot`（核心 artifact）与 `spring-boot-starter-test`；`spring-context` / `spring-beans` / `spring-core` 已由 spring-ai 传递引入，不重复声明。新增 `lytgos-test` 模块承载 ArchUnit；`lytgos-core` 不新增 Spring 依赖（本次只改装配方式，不改模型抽象）。
- **构建**：`lytgos-cli-boot.jar` 的 `BOOT-INF/lib` 从 66 个 jar 增至 67 个，fat jar 体积与索引开销的变化在测量噪声内。
- **启动耗时**：`--version` / `--help` 保持现状 650-790ms（验收上限 900ms）；`provider list` / `chat` 这类需要业务对象的命令每次子进程多付约 815ms 的容器首刷成本，`chat` 被模型网络往返淹没。
- **闸门**：`04-dependency-direction.ps1` 由正则扫描改为调用 ArchUnit 测试；新增 `09-startup-budget.ps1`（同步进 `run-all` 与 AGENTS.md 闸门清单）；`07` / `08` 两条验收的判据不变，仍以真实链路跑通为准。
- **文档**：`AGENTS.md` 4.1 与 04 闸门描述需同步；`harness/README.md` 的闸门说明需更新。
