# Design

## Context

见 `proposal.md` 的 Why。设计上有三条现状约束必须先摆出来，它们决定了方案形态：

1. **工作区在运行期才存在**。`lytgos init` 要在没有 `.lytgos/` 的目录里跑通，而 `lytgos chat` 要求工作区已存在。Spring 的 `@ConfigurationProperties` 在容器刷新时绑定，与这个先后关系直接冲突。
2. **`ReActLoop` / `ToolRegistry` / `ToolExecutor` / `McpServerManager` 都是按 Agent 实例化的**。它们依赖具体 Agent 的 `Profile`（工具白名单、MCP server 声明、迭代上限），不能做成单例。
3. **07/08 两条验收靠匹配 stderr 文本判定**（`tool_call: xxx`、`sandbox`、`DEEPSEEK_API_KEY`、`[mcp:amap]`、`地址已通过域名白名单校验`），stdout 只允许有业务输出。`lytgos-cli/src/main/resources/logback.xml` 已把日志全部导向 `System.err`，且早已把 `org.springframework` 设为 WARN。
4. **启动耗时是用户可感知的硬指标，且现状并非百毫秒级**。实测（`C:\jdk21` 21.0.6，本机，`lytgos-cli-boot.jar`，`BOOT-INF/lib` 66 个嵌套 jar）：`java -version` 90ms；`lytgos --version` 650-790ms；同一套 class 走 plain classpath 312-375ms（fat jar 嵌套索引约 350ms）；`AnnotationConfigApplicationContext` 首次 `refresh()` 815ms、稳态 9-19ms。结论是硬的：**任何命令只要让容器刷新，启动必然进入秒级**；只有不碰容器的命令能保住百毫秒级。

## Goals / Non-Goals

**Goals:**

- 运行时对象由 Spring 容器创建与注入，`CliContext` 的手工装配逻辑消失。
- 启动不退化：`--version` / `--help` 这类纯解析命令不加载容器，耗时保持百毫秒级（验收上限 900ms，现状 650-790ms）。
- 配置文件由 Spring 的 YAML 加载器与占位符解析处理，手写 snakeyaml 与 `Pattern` 替换消失。
- 04 闸门与 `AGENTS.md` 4.1 的表述一致，且由 ArchUnit 实际执行。
- 07/08 的判据一条不改，仍然全 PASS。

**Non-Goals:**

- 不改变模型的抽象方式（`io.lytgos.core.chat.*` 与 `SpringAiChatModel` 原样保留）。
- 不改 `lytgos-boot` / `lytgos-channel` / `lytgos-web` 三个空壳模块。
- 不引入 Web 入口、不做 SQLite 迁移。

## Decisions

### D1 容器形态：`AnnotationConfigApplicationContext` + 懒刷新，不用 `SpringApplication`

原稿用的是 `SpringApplicationBuilder(...).web(NONE).run(args)`。实测表明这条路在 CLI 场景不成立：`SpringApplication` 的整套启动链（`EnvironmentPostProcessor` 扫描 `application.yaml`、`AutoConfigurationImportSelector` 过滤上百个自动配置类、`ApplicationContextInitializer`、事件广播、banner）全是不产生价值的净开销；而本项目的 Provider 映射由配置显式构造（`AGENTS.md` 4.2 禁止类型扫描），根本不需要自动配置——Spring AI 的 `ChatClient` 自动装配恰恰是 4.1 要禁止的东西。

改为显式注册 + 懒刷新：

```java
@Configuration(proxyBeanMethods = false)
@Import({CoreBeans.class, ProviderBeans.class, ToolBeans.class, CliBeans.class})
public class CliConfiguration {
}
```

```java
public final class LytgosCli {
    public static void main(String[] args) {
        LazyContext ctx = new LazyContext(CliConfiguration.class);
        CommandLine cl = new CommandLine(new LytgosCommand(), new SpringCommandFactory(ctx));
        System.exit(cl.execute(args));
    }
}
```

```java
/** 首次 getBean 才 refresh。--version / --help 走不到这里，因此不付容器成本。 */
public final class LazyContext implements AutoCloseable {
    private final AnnotationConfigApplicationContext delegate = new AnnotationConfigApplicationContext();
    private final Class<?> config;
    private boolean refreshed;

    public <T> T getBean(Class<T> type) {
        if (!refreshed) {
            delegate.register(config);
            delegate.refresh();
            refreshed = true;
        }
        return delegate.getBean(type);
    }

    @Override
    public void close() {
        if (refreshed) {
            delegate.close();
        }
    }
}
```

`--version` / `--help` 由 picocli 在解析阶段直接处理，不实例化任何子命令，因而不触发 `IFactory.create`，容器不刷新。容器首次刷新实测 815ms，所以这一分层不是性能优化，是保住百毫秒级的**唯一**手段。

配套约束：**根命令 `LytgosCommand` 保持无参构造、不注入任何依赖**，只承载 `@Command(subcommands = ...)` 与版本信息；五个子命令才由 `SpringCommandFactory` 创建。根命令一旦注入依赖，`--version` 就会连带刷新容器，L0 立刻退化到 1.5s。

`@Configuration(proxyBeanMethods = false)` 消掉 CGLIB 子类生成；不写 `@ComponentScan`，所有 Bean 走 `@Bean` 方法或 `@Import` 显式登记——类路径扫描在 63 个类上实测要几十毫秒，且会让「多引一个包就凭空多出 Bean」的风险回来。

### D1a 依赖选型：只加 `spring-boot` 核心，不引 `spring-boot-starter`

`spring-context` / `spring-beans` / `spring-core` / `spring-aop` / `spring-expression` **已在 fat jar 的 `BOOT-INF/lib` 里**（由 `spring-ai-model` 传递引入），用 Spring 容器零新增 jar。

需要额外引入的只有 `spring-boot`（核心 artifact），提供 `Binder`、`@ConfigurationProperties`、`YamlPropertySourceLoader`。不引 `spring-boot-starter`：它会多带 `spring-boot-autoconfigure`（只服务自动配置，本项目不用）与 `spring-boot-starter-logging`（与既有 `logback-classic` 重复）。

jar 数从 66 增到 67，fat jar 索引开销的变化在测量噪声内。

### D2 工作区懒发现：`WorkspaceLocator` 单例 + 首次访问才 `discover()`

```java
public class WorkspaceLocator {
    private final Path startDir;
    private volatile Workspace cached;
    public Workspace get() { /* 首次调 Workspace.discover(startDir) 并缓存 */ }
}
```

备选是 `@ConfigurationProperties` 启动期绑定：排除，`init` 时会因工作区不存在而启动失败。

### D3 配置绑定：`YamlPropertySourceLoader` + `Binder`，占位符用 `resolvePlaceholders`

删除 `WorkspaceConfig` 里手写的 snakeyaml 与 `Pattern ENV_PLACEHOLDER`，改为三步：

1. `YamlPropertySourceLoader` 把 `.lytgos/config.yaml` 读成 `PropertySource`，`environment.getPropertySources().addLast(...)`；
2. 遍历该 `PropertySource` 的值，用 `environment.resolvePlaceholders(raw)` 做**宽松**解析——缺失的 `${VAR}` 原样保留，正好对应现有 `isMissingEnv` 语义；
3. `Binder.get(environment).bind("providers", Bindable.mapOf(String.class, ProviderProperties.class))` 拿到结构化配置。

严格模式不再单独实现：`ProviderRegistry` 在真正构造 `ChatModel` 前检查值里是否残留 `${`，残留则抛出原本那条「环境变量未设置: X」的消息。

备选是自定义 `PlaceholdersResolver` 传给 `Binder`：可行但绕，且在宽松/严格之间来回切换时要重建 `Binder`。手动先解析再绑定更直观。

### D4 `ProviderRegistry` 保持单例，内部懒创建 `ChatModel`

`AGENTS.md` 4.2 禁止类型扫描，04 闸门禁止 `ObjectProvider<ChatModel>` 与 `getBeansOfType`。因此 provider 不能逐个注册成 Bean，改为：

```java
public class ProviderRegistry {
    private final Map<String, ProviderConfig> configs;   // 配置顺序，配置驱动
    private final Map<String, ChatModel> created = new ConcurrentHashMap<>();
    public ChatModel require(String name) { /* 首次创建并缓存 */ }
}
```

这与改造前行为一致：`provider list` 只读 `configs`，不触发创建，所以缺 key 也不失败——正好满足 delta spec 的第四个场景。

### D5 Bean 划分：只有无状态且不依赖 Agent 的才做单例

| 单例 Bean | per-Agent（由 `AgentRuntimeFactory` 产出，不进容器） |
|---|---|
| `ObjectMapper`、`WorkspaceLocator`、`WorkspaceConfigProvider` | `PromptBuilder`（依赖 Profile 与 instructions） |
| `SandboxChecker`、`SessionStore`、`AgentLoader` | `McpServerManager`（每个 Agent 有自己的 mcpServers） |
| `ProviderRegistry`、`AgentRuntimeFactory` | `ToolRegistry`、`ToolExecutor`、`ReActLoop` |

`AgentRuntimeFactory` 是替代 `CliContext.runtimeFor()` 的落点：它接收 Agent 名，返回一个已装配好、可 `close()` 的 `AgentRuntime`。

**能力类本身不写 `@Component`**：`SandboxChecker`、`SessionStore`、`ProviderRegistry` 等保持纯 POJO，Bean 声明集中在 `CoreBeans` / `ProviderBeans` / `ToolBeans` / `CliBeans` 四个 `@Configuration` 里。理由有三：容器不做类路径扫描，`@Component` 本来就不会被识别；装配关系集中一处比散落满仓好查；11 个既有单测直接 `new` 不受影响。

### D6 picocli 与 Spring 的桥：自定义 `IFactory`

picocli 默认用反射 `new` 子命令，拿不到容器里的依赖。实现 `CommandLine.IFactory`：

```java
final class SpringCommandFactory implements CommandLine.IFactory {
    private final LazyContext context;

    @Override
    public <K> K create(Class<K> cls) {
        try {
            return cls.getDeclaredConstructor(LazyContext.class).newInstance(context);
        } catch (NoSuchMethodException e) {
            return context.createBean(cls);
        }
    }
}
```

**关键事实（已实测坐实）：picocli 在 `CommandLine` 构造阶段就会通过 `IFactory` 实例化全部子命令**，不是等到执行时。`-verbose:class` 的时间线是：`LytgosCommand`(0.906s) → `SpringCommandFactory`(0.907s) → `ChatCommand`(1.238s) → `AnnotationConfigApplicationContext`(1.318s) → `CliConfiguration`(2.592s)。也就是说，只要工厂调 `createBean`，`--version` 一样会 refresh 容器，启动照样进秒级。

同一个构造阶段还会经工厂实例化 `versionProvider` 指向的 `VersionProvider`：它原来只有无参构造，工厂退到 `createBean`，实测把 `--version` 最小值从 620ms 拉回 1502ms（`org.springframework.{context,beans,core}` 的类加载计数从 0 涨到 143/162/192）。因此 `VersionProvider` 也补了一个 `(LazyContext)` 构造器，只作为「不触发容器」的分支标记。

所以命令类**不能**让容器注入业务 Bean，只能接收 `LazyContext` 句柄：

```java
@Command(name = "chat")
public class ChatCommand implements Callable<Integer> {
    private final LazyContext context;

    public ChatCommand(LazyContext context) {
        this.context = context;
    }

    @Override
    public Integer call() {
        AgentRuntimeFactory factory = context.getBean(AgentRuntimeFactory.class);  // 这里才 refresh
        ...
    }
}
```

这样 `--version` 造出来的 5 个命令对象只是各自持有一个未启动的容器句柄，不加载任何 Spring 类型。工厂保留 `createBean` 兜底分支：将来有命令类确实需要容器构造器注入时，仍能走完整注入，但那个命令一被创建就会把容器拉起来。

命令类**不加 `@Component`**：它们不是单例，由 picocli 按需创建一个实例。加了 `@Component` 会在容器里多留一份没人用的实例，且掩盖「命令实例是否真由工厂创建」这一事实。

备选是给每个命令手写构造器调用——那就把手工装配换个地方写，没有意义。

### D7 04 闸门用 ArchUnit：新增 `lytgos-test` 模块

新建 `lytgos-test`，以 `test` scope 依赖全部业务模块加 `archunit-junit5`，放一个 `ArchitectureTest`：

- 禁止任何类依赖 `org.springframework.ai.chat.client..`（`ChatClient` 及其 Advisor）
- 禁止依赖 `ToolCallingManager`
- 禁止使用 `@ConditionalOnBean`、`ConditionalOnMissingBean`
- 禁止调用 `getBeansOfType`、注入 `ObjectProvider<ChatModel>`

`04-dependency-direction.ps1` 改为 `mvn -pl lytgos-test -am test -Dtest=ArchitectureTest`，保留三态退出语义（无模块 → 2、违规 → 1、通过 → 0）。

备选是塞进 `lytgos-boot`：排除，boot 是待处理空壳，把架构测试放进去会让它更难拆。

**同时删除**自造的「`lytgos-core` 不得依赖 Spring AI 与 spring-boot-starter」与「`org.springframework.ai.*` 仅允许在 provider」两条规则——前者不在契约里，后者与本次修订后的 4.1 冲突。

### D8 `AGENTS.md` 4.1 新表述

保留「工具执行 100% 由 `ToolExecutor` 发起」这一核心，把约束落到可检查的机制上：禁止 `ChatClient`、禁止 `ToolCallingManager`、禁止 `@Tool` 自动执行；删除「只允许 `lytgos-provider` import `ChatModel`」。同时把「04 号闸门用 ArchUnit」这句与实际实现对齐。

删除那句的理由：它约束的是「模型抽象怎么实现」，不是「工具会不会被自动执行」。“工具执行必须经 `ToolExecutor`” 的强制点在 `SpringAiChatModel` 的 `ToolCallback.call()` 直接抛 `UnsupportedOperationException`，以及在 `ArchitectureTest` 里禁止 `ChatClient` / `ToolCallingManager`——跟 `ChatModel` 这个接口被谁 import 无关。留着它只会阻碍后续把模型抽象换回 Spring AI 原生类型。本条已由用户确认执行。

### D9 启动预算：三层口径 + 独立闸门

实测基线（`C:\jdk21` 21.0.6，本机 6 次取值）：

| 场景 | 耗时 |
|---|---|
| `java -version` | 90ms |
| `lytgos --version`（fat jar，改造前） | 650-790ms |
| 同 class 走 plain classpath | 312-375ms |
| fat jar 嵌套索引开销（差值） | ≈350ms |
| `AnnotationConfigApplicationContext` 首次 `refresh()` | 815ms |
| 同上，稳态第 2-6 次 | 9-19ms |

由此定三层口径：

| 层 | 命令 | 是否刷新容器 | 预算 |
|---|---|---|---|
| L0 | `--version`、`--help` | 否 | 30 次采样最小值 ≤ 900ms |
| L1 | `provider list`、`tool list`、`profile list` | 是 | 30 次采样最小值 ≤ 2500ms |
| L2 | `chat` | 是 | 不设启动预算（网络往返主导） |

**用最小值而非 p95 的实测依据**：本机 `--version` 的 30 次采样呈双峰（736-880ms 与 1089-1361ms），两簇之间没有中间值，差值来自杀软扫描与页面回收，与代码路径无关。最小值稳定复现 736ms（与改造前的 650-790ms 重合），能真实反映启动成本；p95 会把环境噪声当回归、把闸门变成随机红绿。

L0 的 900ms 相对现状留了约 20% 余量，用于吸收 jar 数变化与机器波动。它不是一个「Spring 化后的新目标」，而是**回归护栏**：任何把依赖注入到根命令、或改成 `SpringApplication` 的改动，都会把 L0 顶到 1.5s，闸门必须红。

**验证 L0 是否真的没碰容器，不能只看耗时**（机器抖动可达 600ms，会淹没 815ms 的差异）。必须同时检查：`java -verbose:class` 的输出里不出现 `org.springframework.context.*`。这是不会被噪声干扰的硬判据。

新增 `harness/checks/09-startup-budget.ps1`，纳入 `run-all.ps1`，同步更新 `AGENTS.md` 的 `<!-- harness-checks: -->` 清单行与 `harness/README.md`。

**明确不做**：不改 fat jar 布局、不上 AppCDS / AOT / native image。这三条是唯一能把 L0 从 700ms 压到 300ms 以下的手段，但都会改变 `java -jar lytgos-cli-boot.jar` 的调用契约（07/08 依赖它），与「Spring 化」是两个关注点。

## Risks / Trade-offs

- **容器首次刷新 815ms 是硬成本** → L1/L2 命令每次子进程调用都要付一次，07/08 会变慢。`WaitForExit()` 无超时，不会因此失败。L0 通过「不刷新容器」规避；一旦有人把依赖注入到根命令 `LytgosCommand`，或改回 `SpringApplication`，L0 立刻退化到 1.5s——这必须由 09 闸门守住，不能靠 review 时记得。
- **09 闸门阈值受机器性能影响** → 900ms / 2500ms 是本机实测加余量得出的，冷启动、杀软扫描、CI 低配机都可能推高。取 p95 而非单次，阈值定稿前先跑 30 次采样校准。
- **JVM 参数能再省 100-200ms，但只在闸门里用会失真** → `-XX:TieredStopAtLevel=1 -XX:+UseSerialGC` 能让首次 refresh 快一截，但写进 07/08 的子进程调用点会造成「闸门测的启动」与「用户实际启动」不是同一条路径。要么统一进启动脚本，要么两边都不用。
- **stderr 断言可能被容器异常栈干扰** → 新方案不引 `SpringApplication`，没有 `LogbackLoggingSystem` 接管，logback 仍只由 `logback.xml` 初始化，`org.springframework` 已是 WARN。但容器 refresh 失败时异常栈会进 stderr。07/08 的断言是正向匹配（要求出现某文本），多出的栈不会误伤；仍要在任务里实测一次，不能只看编译通过。
- **`mvn -pl lytgos-test test` 单独跑会拿不到其他模块的 class** → 必须带 `-am`，否则 ArchUnit 扫不到类而静默通过（比失败更危险）。
- **Bean 化破坏现有单测** → 能力类保持 POJO、不写 `@Component`，测试里 `new` 不受影响；但凡是要新增构造参数的对象（如 `SandboxChecker` 改为由配置 Bean 提供白名单），测试里的构造方式要跟着调。
- **配置解析从「自有实现」改为「Spring 实现」，错误消息可能变形** → 07 第 4 条要求 stderr 出现字面量 `DEEPSEEK_API_KEY`。严格模式必须保留原文案，不能透传 Spring 的 `IllegalArgumentException`。

## Migration Plan

按可独立验证的粒度推进，每步都能编译并跑通既有测试：

1. 加 `spring-boot` 核心依赖与 `lytgos-test` 模块，新增 `CliConfiguration` 与 `LazyContext`，把 `LytgosCommand.main` 改为 `LazyContext + SpringCommandFactory`；子命令暂时仍从 `CliContext` 取依赖。确认 `--version` / `--help` 不触发容器刷新、耗时与改造前持平。
2. 配置绑定迁移到 `Binder`，`WorkspaceConfig` 退化为薄封装，跑 07 第 4 条验证缺 key 文案不变。
3. 能力类逐个 Bean 化（先单例、后 per-Agent），每完成一个跑一次 `mvn test`。
4. 删除 `CliContext` 的手工装配，改为 `AgentRuntimeFactory` + `SpringCommandFactory`。
5. 写 `ArchitectureTest`，把 04 脚本切到 ArchUnit，删掉两条自造规则。
6. 改 `AGENTS.md` 4.1 与 `harness/README.md`，跑 07/08 全量验证。

回滚方式：本次全部改动在新分支内，`main` 上的 `v0.2.0` 不受影响；若中途放弃，直接丢弃分支即可。

## Open Questions

无阻塞项。以下两条已决定不做，登记备查：

| 级别 | 事项 | 默认倾向 |
|---|---|---|
| 一般 | 把 L0 启动从 ~700ms 压到 <300ms：改 fat jar 为 `-cp lib/*` 启动脚本（省 ≈350ms），或加 AppCDS（省 ≈150ms） | 不做。会改变 `java -jar lytgos-cli-boot.jar` 的调用契约，07/08 全要跟着改，另立 change |
| 一般 | JVM 参数 `-XX:TieredStopAtLevel=1 -XX:+UseSerialGC` 是否进正式启动路径 | 只在 09 闸门测量时使用，不写进用户命令 |
