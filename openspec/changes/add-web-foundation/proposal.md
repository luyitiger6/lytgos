# Proposal

## Why

前两周交付的是运行时内核（ReAct 循环、长期记忆、内置工具、MCP），对外只有 CLI 一个入口：`lytgos-web` 至今只有一个 `package-info.java`，`lytgos-boot` 没有 `src/main/resources`。企业接入离不开 HTTP——没有健康探针无法上 K8s，没有指标接不进 Prometheus，没有 OpenAPI 就没有可交付的接口契约，没有统一响应体与全局异常处理，每个端点都会各自编一套错误格式。这是「工程地基」的 Web 半边，README 路线图中「CLI、REST API 与定时触发」仍是未勾选项。

## What Changes

- 新增结构化日志 `logback-spring.xml`：dev 彩色 console，prod JSON（`LogstashEncoder`），MDC 携带 `traceId`，请求入口生成或透传
- 接入 Actuator + Micrometer + Prometheus：暴露 `/actuator/health`、`/actuator/info`、`/actuator/prometheus`，health 带 liveness / readiness 探针
- 开启 Spring MVC + JDK 21 虚拟线程（`spring.threads.virtual.enabled=true`），不引入响应式栈
- 接入 springdoc：`/v3/api-docs` 与 `/swagger-ui.html`；prod profile 默认关闭 UI
- 新增统一响应体 `ApiResponse<T>`（code / message / data / timestamp）与全局异常处理 `GlobalExceptionHandler`，把 core 的 `LytgosException` 体系映射为 403 / 400 / 500 与稳定错误码；未知 `/api/**` 路径由通配处理器统一返回 404
- 新增 11 号闸门 `web-smoke`：用真实进程验证健康端点、OpenAPI 文档、异常映射三件事

**BREAKING**：无。CLI 行为不变；`lytgos-web` 由空模块变为有实现。

## Capabilities

### New Capabilities

- `observability`: 结构化日志与运行时可观测性——日志格式分层（dev 彩色 / prod JSON）、`traceId` 贯穿请求、健康与指标端点暴露
- `http-api`: HTTP 接入契约——虚拟线程承载、OpenAPI 文档、统一响应体、`LytgosException` 到 HTTP 状态与错误码的全局映射

### Modified Capabilities

（无。本 change 不改变 `react-loop` / `mcp-client` / `engineering-baseline` 的既有 requirement）

## Impact

- 代码：`lytgos-web` 新增 API 契约与异常映射实现；`lytgos-boot` 新建 `src/main/resources`；`lytgos-channel` 的 `application.yaml` 补齐公共配置
- 依赖：新增 `spring-boot-starter-actuator`、`micrometer-registry-prometheus`、`logstash-logback-encoder`、`springdoc-openapi-starter-webmvc-ui`（版本在实施第一步查兼容矩阵后锁定）
- 闸门：新增 `harness/checks/11-web-smoke.ps1`，同步 `AGENTS.md` 机器可读清单与 `harness/README.md`
- 文档：`lytgos-init` SKILL 的「落地状态」声明由「§3–§6 未落地」改为已落地
- 端口：`lytgos.jar`（8080 默认）与 `lytgos-channel-boot.jar`（8081）同时运行时需明确区分，见 design 的 Open Questions
- 已知欠账（不在本 change 范围）：`engineering-baseline` 第 6 条要求 MUST 提供 pre-commit hook，仓库目前没有 `.pre-commit-config.yaml`，单独立项处理
