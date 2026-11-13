# Tasks

## 1. 容器骨架与依赖

- [x] 1.1 根 pom 增加 `lytgos-test` 模块声明与 `archunit-junit5` 版本管理；`lytgos-cli/pom.xml` 增加 `spring-boot`（核心 artifact，**不是** `spring-boot-starter`），保留 `spring-boot-maven-plugin`。验证：`mvn -o -q clean package` 退出码 0；`lytgos-cli-boot.jar` 的 `BOOT-INF/lib` 从 66 个 jar 增至 67 个（只多一个 `spring-boot-4.1.2.jar`），`spring-context` 等不重复
- [x] 1.2 新增 `CliConfiguration`（`@Configuration(proxyBeanMethods = false)` + `@Import` 显式登记）与 `LazyContext`（首次 `getBean` 才 `refresh()`，实现 `AutoCloseable`），新增 `io.lytgos.cli.LytgosCli` 作为 `main` 入口，**暂不接 `SpringCommandFactory`**（1.3 再接）。**不使用 `SpringApplication` / `@SpringBootApplication` / `@ComponentScan` / 自动配置 / banner**。验证：`java -jar lytgos-cli-boot.jar --version` 退出码 0，stdout 只有版本号一行，stderr 不含 Spring 日志；`-verbose:class` 中不出现 `org.springframework.context.*`；连续 30 次最小值 ≤ 900ms（最小值反映真实启动成本，p95 受杀软与页面回收干扰可达 1400ms，不作为硬阈值）
- [x] 1.3 新增 `SpringCommandFactory implements CommandLine.IFactory`：优先用命令类的 `LazyContext` 构造器创建（不触发容器），没有该构造器时才退回 `AutowireCapableBeanFactory.createBean`。五个子命令加 `LazyContext` 构造器并删掉无参构造，**不加 `@Component`**；内部仍暂用 `CliContext.instance()` 取依赖，4.1 再改。**注意 picocli 在 `CommandLine` 构造阶段就实例化全部子命令**，所以命令类只能持有 `LazyContext` 句柄。验证：`--version` 的 `-verbose:class` 里不出现 `org.springframework.context.*` 且 30 次最小值 ≤ 900ms；`provider list` 报错与改造前一致。（实测补记：`versionProvider` 同样在构造阶段经工厂实例化，`VersionProvider` 也需补 `(LazyContext)` 构造器，否则退回 `createBean` 把 `--version` 拉回秒级）
- [x] 1.4 根命令 `LytgosCommand` 保持无参构造、不注入任何依赖，只承载 `@Command(subcommands = ...)` 与版本信息。验证：`--version` 连续 5 次 p95 与改造前持平（±100ms）；临时给根命令加一个注入字段，耗时升到 1.5s 级别，确认分层生效后撤销。（按 D9 修订口径改用最小值：撤销后 10 次 min=631ms；反证实验 5 次 1671-2355ms）

## 2. 配置绑定迁移

- [x] 2.1 新增 `WorkspaceConfigProvider`：用 `YamlPropertySourceLoader` 把 `.lytgos/config.yaml` 载入 `Environment`，用宽松解析处理 `${VAR}`，用 `Binder` 绑定 `providers` 与 `sandbox` 两段。验证：新增 `WorkspaceConfigProviderTest`，覆盖「占位符可解析」「占位符缺失时原样保留」「YAML 非法时报错含文件路径」三种情况并全绿（3/3）
  - 实现记录：宽松解析没有手写 `resolvePlaceholders`，而是由 `Binder.get(environment)` 自带的 `PropertySourcesPlaceholdersResolver` 在绑定时完成（同一套解析器，无法解析时原样保留）。另：Spring Boot 4 的 `YamlPropertySourceLoader` 遇到语法错误时直接让 SnakeYAML 的 `YAMLException`（RuntimeException）冒出，不包 `IOException`，已一并捕获并附文件路径
- [x] 2.2 删除 `WorkspaceConfig` 中手写的 snakeyaml 解析与 `Pattern ENV_PLACEHOLDER`，改为对绑定结果的薄访问器，公开方法签名保持不变。验证：`mvn -o test -pl lytgos-cli -am` 退出码 0；`java -jar lytgos-cli-boot.jar provider list` 在缺 `DEEPSEEK_API_KEY` 时仍能列出 provider 并返回 0（实测输出 `deepseek … 未配置（环境变量 DEEPSEEK_API_KEY）`，EXIT=0）
- [x] 2.3 `ProviderRegistry` 在构造 `ChatModel` 前检查残留 `${` 占位，抛出「环境变量未设置: <VAR>（provider <name> 需要）」。验证：未设 `DEEPSEEK_API_KEY` 时跑 `chat`，stderr 含字面量 `DEEPSEEK_API_KEY`、退出码 1（实测 stderr：`环境变量未设置: DEEPSEEK_API_KEY（provider deepseek 需要）`，EXIT=1）；同一构建 `--version` 30 次 min=529ms、`org.springframework.{context,beans,core,annotation}` 类加载计数 0

## 3. 能力类 Bean 化

- [x] 3.1 `ObjectMapper`、`WorkspaceLocator`、`SandboxChecker`、`SessionStore`、`AgentLoader`、`ProviderRegistry` 改为容器管理，构造函数注入。验证：`mvn -o test` 退出码 0，11 个既有测试类不因构造方式变化而失败
  - 实现记录：新增 `WorkspaceLocator`（core/workspace，构造不碰磁盘、首次 `get()` 才 discover 并缓存）；新增 `CoreBeans`（ObjectMapper、WorkspaceLocator、WorkspaceConfig、SandboxChecker、SessionStore、AgentLoader）与 `ProviderBeans`（ProviderRegistry），`CliConfiguration` 用 `@Import` 显式登记；能力类保持纯 POJO 不加 `@Component`。验证：`mvn -o -B clean test -pl lytgos-cli -am` 退出码 0，11 个测试类全绿
- [x] 3.2 `ProviderRegistry` 改为内部懒创建 `ChatModel`：首次 `require(name)` 时构造并缓存，不使用 `ObjectProvider`、`getBeansOfType` 或类型扫描。验证：`provider list` 在缺 key 时成功；`chat` 在缺 key 时退出码 1 且消息含变量名
  - 实现记录：构造器只登记 `ProviderConfig`（重名仍报错），`require` 先在 `ConcurrentHashMap` 里查缓存，未缓存则检查残留 `${` 再 `ChatModelFactory.create` 并缓存；`names()`/`size()` 不再隐含任何模型构造。同时删除已无调用方的 `ChatModelFactory.createAll`。验证：缺 `DEEPSEEK_API_KEY` 时 `provider list` EXIT=0；`chat -m hello` stderr「环境变量未设置: DEEPSEEK_API_KEY（provider deepseek 需要）」、EXIT=1；容器探针输出 `providers=[deepseek] (models not created)`
- [x] 3.3 新增 `AgentRuntimeFactory` 承接 `CliContext.runtimeFor()` / `toolsFor()`：按 Agent 名组装 `McpServerManager`、`ToolRegistry`、`ToolExecutor`、`ReActLoop`、`PromptBuilder`，失败路径先回收已拉起的 MCP 子进程。验证：`McpServerManagerTest`、`ReActLoopTest` 全绿
  - 实现记录：工厂构造注入 WorkspaceLocator/SandboxChecker/AgentLoader/ProviderRegistry/ObjectMapper/HttpTool/FileTool；per-Agent 对象不进容器；新增 `ToolBeans`（HttpTool、FileTool）与 `CliBeans`（AgentRuntimeFactory），`CliConfiguration` 一并 `@Import`。验证：`McpServerManagerTest` 11/11、`ReActLoopTest` 4/4 全绿；用 PropertiesLauncher 探针在真实工作区实测容器刷新后 `toolsFor("default")` 返回 `[http_get, file_read]`，`PROBE_OK`

## 4. 装配切换

- [x] 4.1 五个 CLI 命令（`init`、`chat`、`profile`、`provider`、`tool`）改为从容器注入依赖，删除对 `CliContext.instance()` 的调用。验证：五个子命令 `--help` 均退出码 0
  - 实现记录：命令不再持有装配对象，`run()` 里从 `LazyContext.getBean(...)` 取依赖（`chat` 取 `AgentRuntimeFactory`/`SessionStore`，`provider list` 取 `WorkspaceConfig`/`WorkspaceLocator`，`tool list` 取 `AgentRuntimeFactory.toolsFor`，`profile` 取 `WorkspaceLocator`/`AgentLoader`）；`init` 刻意保持不碰容器。子命令原本未注册 help（`--help` 报 Unknown option、退出码 2），一并补 `mixinStandardHelpOptions = true`。验证：五个 `--help` 全 EXIT=0；容器化后实测 `provider list`、`tool list`（http_get/file_read）、`profile list`（default 默认助手）EXIT=0，`chat -m hello` 缺 key EXIT=1 且 stderr 含 `DEEPSEEK_API_KEY`
- [x] 4.2 删除 `CliContext` 的手工装配，把「懒加载」语义迁到 `WorkspaceLocator`。验证：全仓不再有 `CliContext` 引用；`mvn -o clean package` 退出码 0
  - 实现记录：删除 `CliContext.java`（227 行），懒加载语义已由 3.1 的 `WorkspaceLocator` 双重检查锁承接，`AgentRuntimeFactory` 新增 `sessionKey(agent)` 补上原 `CliContext.sessionKey` 的职责。验证：全仓 grep `CliContext` 零命中（NO_CLI_CONTEXT_REFS）；`mvn -o clean package` EXIT=0
- [x] 4.3 跑 `harness\checks\07-week1-acceptance.ps1`，六条全 PASS 且退出码 0；重点确认第 3 条 stderr 仍有 `tool_call: http_get`、第 4 条仍能识别缺 key 文案
  - 实现记录：`mvn -o -B clean package` BUILD SUCCESS 后实跑，六条全 PASS、EXIT=0。第 3 条真实高德天气接口：「含温度数值，http_get 调用 1 次」；第 4 条：「退出码 1，stderr 指明 DEEPSEEK_API_KEY」；第 5 条沙箱在建立连接前拦截；第 6 条跨进程读到「张三」。另：`ToolBeans.java` 曾出现 IDE 并行写入造成的尾部重复空壳（未提交），已恢复为 `cbcd8c8` 内容，无净变更

## 5. 架构闸门

- [x] 5.1 新建 `lytgos-test` 模块，`test` scope 依赖全部业务模块与 `archunit-junit5`；`ArchitectureTest` 实现四条规则：禁 `org.springframework.ai.chat.client..`、禁 `ToolCallingManager`、禁 `@ConditionalOnBean`/`@ConditionalOnMissingBean`、禁 `getBeansOfType` 与 `ObjectProvider<ChatModel>`。验证：`mvn -o -pl lytgos-test -am test -Dtest=ArchitectureTest` 退出码 0；临时在任一业务模块加一行 `import org.springframework.ai.chat.client.ChatClient` 时该测试必须失败，验完删除
  - 实现记录：模块 pom 在 1.1 已就位，本次补 `io.lytgos.arch.ArchitectureTest`（ArchUnit 1.4.1，四条 @ArchTest）。条件注解与 `getBeansOfType/getBeanProvider` 按类名/方法名用 `DescribedPredicate` 匹配（`spring-boot-autoconfigure` 不在测试 classpath，且 `ObjectProvider<ChatModel>` 泛型被擦除，只能整类禁用）；`getBeansOfType` 与 `ObjectProvider` 用 `CompositeArchRule.and` 组合。验证：4 条全跑、`Tests run: 4, Failures: 0`、EXIT=0；反证：临时给 provider 加 `spring-ai-client-chat` 依赖 + `ChatClient.class` 探针，测试报 `noChatClient` 违规（`ProviderRegistry.<clinit> references ChatClient`、Failures: 1、EXIT=1），还原后复绿。注意：`-Dtest=ArchitectureTest` 必须带 `-DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false`，否则其他模块无匹配测试会报错
- [x] 5.2 `04-dependency-direction.ps1` 改为调用 ArchUnit 测试，删除自造的「core 不得依赖 Spring AI / spring-boot-starter」与「Spring AI 仅允许在 provider」两条规则，保留三态退出语义。验证：`powershell -File harness\checks\04-dependency-direction.ps1` 退出码 0，输出四条规则全部 OK
  - 实现记录：脚本改为 `mvn -B -pl lytgos-test -am test -Dtest=ArchitectureTest -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false`（`-am` 不可省；不带 failIfNoTests=false 时其他模块会报「无匹配测试」）；成功后再核对 surefire 报告 `Tests run` 行，少于 4 条判 FAIL 防假绿；原三态保留（无 pom/无模块源码 → 2，无 mvn/测试失败 → 1）。验证：单独执行 EXIT=0，输出「ArchUnit 4 条规则全过」+ 四条 [OK]；另：本机默认 `JAVA_HOME=C:\jdk17` 时 surefire fork 报 class file 65.0 vs 61.0，带 `C:\jdk21` 重跑通过
- [x] 5.3 `harness/README.md` 的 04 闸门说明同步为 ArchUnit 实现与新的规则清单。验证：文档里的命令与实际脚本一致，手工执行一次通过
  - 实现记录：表格行与正文两处已改：四条规则清单、实际执行的 mvn 命令、`-am` 必要性、surefire 报告核对、两条自造规则已删除的说明。验证：文档里的命令与脚本 `& mvn -B -pl lytgos-test -am test ...` 逐字一致；04 手工执行 EXIT=0；`00-layout-check.ps1` EXIT=0（闸门清单未变）

## 6. 契约修订与集成验证

- [ ] 6.1 修订 `AGENTS.md` 4.1：只禁 `ChatClient`、`ToolCallingManager`、`@Tool` 自动执行；删除「只允许 `lytgos-provider` import `ChatModel`」；把 ArchUnit 那句与实际实现对齐。验证：该节每条约束都能在 `ArchitectureTest` 中找到对应规则
- [ ] 6.2 跑 `harness\checks\08-mcp-acceptance.ps1`，八条全 PASS 且退出码 0；重点确认路径 A/B/C 的 stderr 断言与「边界提示」不受 Spring 启动日志影响
- [ ] 6.3 跑 `mvn -o clean package` 与 `harness\run-all.ps1`，记录退出码与 05/06 的 NOT_READY 状态。验证：若 `run-all` 因 05/06 返回 1，汇报中如实标注，不得表述为「完整验收通过」

## 7. 启动预算闸门

- [ ] 7.1 新增 `harness/checks/09-startup-budget.ps1`：`--version` 跑 30 次取**最小值**断言 ≤ 900ms 且 `-verbose:class` 不含 `org.springframework.context.*`；`provider list`（无工作区，快速失败路径）跑 10 次取最小值断言 ≤ 2500ms；超预算退出码 1，缺 jar 退出码 2（沿用现有三态语义）。验证：脚本单独执行退出码 0，输出两级实测数值
- [ ] 7.2 `harness/run-all.ps1` 接入 09；`AGENTS.md` 的 `<!-- harness-checks: -->` 清单行与 `harness/README.md` 的闸门清单同步增加 `09-startup-budget`。验证：`00-layout-check.ps1` 退出码 0（清单与实际文件一致），`run-all.ps1` 输出里出现 09

## Workflow follow-up

- 分支 `feat/spring-managed-runtime-20261113` 跑通后交用户审阅，通过后合并 main。
- 归档 change，主 spec 同步 `react-loop` 的「Provider 显式映射」MODIFIED 块。
