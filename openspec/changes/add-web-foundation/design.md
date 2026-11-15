# Design

## Context

- 入口现状：`lytgos-channel` 自带 `spring-boot-starter-web` 与 `application.yaml`（端口 8081），`CliChannelApplication` 用 `scanBasePackages = "io.lytgos.channel"` 独立启动；`lytgos-boot` 只依赖 `spring-boot-starter`，靠 `lytgos-channel` 传递获得 web 栈，`LytgosApplication` 默认扫 `io.lytgos.boot`。
- `lytgos-web` 目前只有 `package-info.java` 与 `lytgos-core` 依赖；`lytgos-boot` 没有 `src/main/resources`。
- `lytgos-cli` 不依赖 web 栈（只有核心能力模块 + picocli + logback-classic），因此 09 启动预算测的 `lytgos-cli-boot.jar` 不会被本 change 拖慢。
- 异常体系在 `lytgos-core` 的 `LytgosException`：5 个子类（沙箱违规 / Agent 定义非法 / 工具参数非法 / 未知 provider / 配置非法）。
- 04 闸门（ArchUnit）禁止依赖 `ChatClient` / `ToolCallingManager` / 条件装配 / 类型扫描，新增 Web 代码同样受约束。

## Goals / Non-Goals

**Goals:**

- 一份基础设施代码同时服务 `lytgos.jar` 与 `lytgos-channel-boot.jar`，不复制粘贴。
- 每个 spec scenario 都有可执行的判据：能单测的进单测，需要真实进程的进 11 号闸门。
- 不改变 CLI 行为与启动预算。

**Non-Goals:**

- 不实现业务 REST 端点（会话 / Agent / 记忆 / 工具查询），那是后续 change。
- 不引入响应式栈、不引入 Micrometer Tracing / OpenTelemetry（traceId 用 MDC 轻量方案）。
- 不做鉴权、限流、CORS 策略（属于后续 Web 安全 change）。
- 不动 `engineering-baseline` 的 pre-commit 欠账。

## Decisions

### D1 基础设施代码放 `lytgos-web`，配置用 `spring.config.import` 共享

`ApiResponse` / `GlobalExceptionHandler` / traceId Filter / OpenAPI 配置类 / `logback-spring.xml` 全部放 `lytgos-web`；它被 `lytgos-boot` 与 `lytgos-channel` 同时依赖，classpath 合并后两处生效。

`application.yaml` 不能共享（同名文件在 classpath 上互相遮蔽），改为 `lytgos-web/src/main/resources/lytgos-web-defaults.yaml`，两个入口各自的 `application.yaml` 写一行 `spring.config.import: classpath:lytgos-web-defaults.yaml`。

组件注册走 `LytgosWebAutoConfiguration` + `META-INF/spring/...AutoConfiguration.imports`：两个入口的组件扫描只覆盖各自包，依赖 jar 里的组件不会被自动发现。用 `@AutoConfiguration` + `@Import` 而不是条件装配（`@ConditionalOnBean` 等），一是遵守 AGENTS.md 4.2 的显式装配约定，二是 04 闸门的 ArchUnit 规则会直接拦下条件注解。

- 备选 A：每个入口复制一份配置 → 两份漂移不可避免，排除。
- 备选 B：把 `CliChannelApplication` 合并进 `lytgos-boot` → 破坏渠道独立调试能力，排除。

### D2 traceId 用 Servlet Filter，不引链路追踪框架

`OncePerRequestFilter`：读 `X-Request-Id`，缺省用 UUID；写 MDC key `traceId`，响应头回写，`finally` 清理。

- 备选：Micrometer Tracing + OpenTelemetry → 采样语义、exporter 配置对一个还没有多服务调用的单体没有收益，排除；将来上链路追踪时 Filter 抽象不变。

### D3 日志分层：`logback-spring.xml` + 两个 springProfile

- 默认：彩色 console pattern，`traceId` 进 pattern。
- `prod`：`LogstashEncoder` 输出单行 JSON，MDC 默认并入 JSON 字段。
- 注意 `cli` 模块不依赖 `lytgos-web`，拿不到这份配置，CLI 日志行为保持不变。

### D4 虚拟线程用配置开启，用系统信息端点证明

`spring.threads.virtual.enabled: true` 写入共享配置；`GET /api/v1/system/info` 返回 `Thread.currentThread().isVirtual()` 的结果。没有这个端点就只能「相信配置」，无法验收。

### D5 统一响应体与错误码

`ApiResponse<T>` 用 record：`code`（String，成功为 `"0"`）、`message`、`data`、`timestamp`（`Instant`）。`GlobalExceptionHandler` 用 `@RestControllerAdvice(basePackages = "io.lytgos.web")` —— 必须限定作用域，否则会接管 actuator 与 springdoc 的异常，把它们的标准错误响应改成 `ApiResponse`。

受控异常映射见 `specs/http-api/spec.md`；未知异常记录完整堆栈，响应只给固定文案。

### D6 404 的判据来源

实现采用**显式通配控制器**：`ApiNotFoundController` 用 `@RequestMapping({"/api", "/api/**"})` 承接所有未匹配的 API 路径，返回统一 404。

- 不捕获 `NoResourceFoundException`：Spring Boot 4.x 已换过一次内部异常类型（早期是 `NoHandlerFoundException`），绑定具体类型等于把契约押在框架内部实现上；`@RestControllerAdvice(basePackages = "io.lytgos.web")` 的限定作用域也让它接不到资源处理器抛出的异常。
- 未匹配的非 `/api` 请求完全不经过本类，静态资源与 springdoc 的 404 保持框架默认行为。
- 精确路径的映射优先级高于通配，本类不会遮蔽任何真实端点。
- 代价：FindSecBugs 对此报 `SPRING_CSRF_UNRESTRICTED_REQUEST_MAPPING`——该处理器只返回 404、不读请求体、不写状态，CSRF 没有可触发的副作用，按 D10 豁免。

### D7 Actuator 最小暴露面

`management.endpoints.web.exposure.include: health,info,prometheus`；`management.endpoint.health.probes.enabled: true`；`show-details: never`（避免把内部依赖细节暴露给匿名调用方）。spec 中「`/actuator/env` 返回 404」由此配置保证。

### D8 受控异常的可测性：单测用桩控制器，闸门只验真实路径

- 单测：`MockMvcBuilders.standaloneSetup(stubController).setControllerAdvice(new GlobalExceptionHandler())`，桩控制器按需抛 5 个子类，逐个断言状态码与 code。
- 闸门 11：只验 `/actuator/health|liveness|readiness`、`/actuator/prometheus`、`/api/v1/system/info`、`/api/v1/no-such-endpoint`、`/v3/api-docs`、`X-Request-Id` 回写。**不为了测异常往产品里塞测试专用端点**（等于伪造）。
- 三态：缺 jar → 2；启动失败或任一断言失败 → 1；全过 → 0。端口用启动参数覆盖（`--server.port=18080`），避开 8080/8081 的本机占用。

### D9 OpenAPI 用 springdoc，prod 关 UI

依赖 `springdoc-openapi-starter-webmvc-ui`；`application-prod` 里 `springdoc.swagger-ui.enabled=false`。版本必须在实施第一步查与 Spring Boot 4.1.2 的兼容矩阵后锁定到根 pom 的 `dependencyManagement`（实施结论：springdoc 2.x 只兼容 Boot 3，选用 3.2.0；`logstash-logback-encoder` 9.0 因 Boot 4 BOM 不再管理而一并锁版本）。

### D10 本 change 新增的 SpotBugs 豁免（台账）

豁免书写规范见 `spotbugs-exclude.xml` 文件头（类 + 模式粒度、逐条注释、重审条件）。本 change 新增 6 条类级豁免，全部来自 Spring / FindSecBugs 的框架语义误报：

| 类 | 模式 | 理由 | 重审条件 |
|---|---|---|---|
| `io.lytgos.web.api.ApiNotFoundController` | `SPRING_ENDPOINT` | FindSecBugs 对每个控制器的信息性提示 | 引入状态变更端点时逐方法复核 |
| `io.lytgos.web.api.SystemInfoController` | `SPRING_ENDPOINT` | 同上；端点只读 | 同上 |
| `io.lytgos.web.error.GlobalExceptionHandlerTest$StubController` | `SPRING_ENDPOINT` | 测试桩，只经 standaloneSetup 装载 | 提取进生产类时重审 |
| `io.lytgos.web.trace.TraceIdFilterTest$StubController` | `SPRING_ENDPOINT` | 同上 | 同上 |
| `io.lytgos.web.api.ApiNotFoundController` | `SPRING_CSRF_UNRESTRICTED_REQUEST_MAPPING` | 通配 404 处理器无状态变更，CSRF 无副作用（见 D6） | 开始读写请求体 / 会话时重审 |
| `io.lytgos.web.trace.TraceIdFilter` | `SERVLET_HEADER` | 复用 `X-Request-Id` 是 spec 要求；值已净化后再进 MDC 与响应头 | traceId 用于鉴权或审计归属时重审 |

另有 1 条测试方法豁免：`SystemInfoIntegrationTest` 的 `THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION`（JUnit 方法 `throws Exception` 是标准写法），与上表一并登记。

## Risks / Trade-offs

- [springdoc 与 Spring Boot 4.1.2 不兼容] → 实施第一步先做依赖解析验证；不可用则降级为只出 `/v3/api-docs`（`springdoc-openapi-starter-webmvc-api`），仍不满足则回到本 change 修订 spec。
- [`@RestControllerAdvice` 误伤 actuator / springdoc] → 用 `basePackages` 限制范围，并以闸门断言 actuator 响应格式未被改写。
- [共享 YAML 用 `spring.config.import` 引入，缺失时启动直接失败] → 这是期望行为（fail fast），但要在两个入口都加，漏一处即启动报错，反而容易发现。
- [traceId 只在本进程内有效] → 单机单体阶段够用；跨服务时需换成 W3C traceparent，Filter 处替换成本低。
- [prod JSON 日志增大体积] → 只在 prod 生效，dev 保持可读。
- [虚拟线程与同步阻塞 JDBC 混用收益有限] → 当前只有健康检查与系统信息端点，收益在本 change 内不重要，价值是**地基就位**；SQLite 接上后再评估连接池配合。

## Migration Plan

1. 第一步只加依赖与配置（根 pom 版本管理、`lytgos-web` 依赖、`lytgos-web-defaults.yaml`、两个入口的 import、`logback-spring.xml`），跑 `mvn -B verify` 确认既有 120 条测试不受影响。
2. 第二步加代码（traceId Filter、`ApiResponse`、`GlobalExceptionHandler`、系统信息端点、OpenAPI 配置）与单测。
3. 第三步加 11 号闸门，同步 `AGENTS.md` 清单与 `harness/README.md`。
4. 回滚：删除 `lytgos-web` 新增实现与 pom 依赖、两个入口的 `spring.config.import` 行即可恢复；无数据迁移、无 schema 变更。

## Open Questions

| # | 问题 | 级别 | 影响任务 | 默认倾向 |
|---|---|---|---|---|
| 1 | `lytgos.jar` 监听端口：8080（框架默认，但本机常被 VMware 占用）还是固定 18080 | 一般 | boot 的 `application.yaml`、11 号闸门 | 8080；闸门用 `--server.port=18080` 覆盖 |
| 2 | prod 环境是否同时关闭 `/v3/api-docs`（spec 只要求关 UI） | 一般 | http-api 的 prod 配置 | 只关 UI，保留 api-docs 供网关导入 |
| 3 | springdoc 与 Spring Boot 4.1.2 的兼容版本 | 阻塞（实施第一步必验） | 依赖锁定、OpenAPI 任务 | 先解析兼容矩阵；不可用则降级并回改本设计 |
| 4 | `InvalidAgentDefinitionException` 同时表示「名称非法」与「Agent 不存在」，Web 层统一 400 还是区分 404 | 一般 | 异常映射表 | 统一 400；将来拆子类时再区分 |
