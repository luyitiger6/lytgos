# http-api Specification

## Purpose

定义 lytgos 对外 HTTP 接入的契约：请求由虚拟线程承载、接口文档可自动生成、成功与失败响应结构一致，受控异常按语义映射为稳定的 HTTP 状态与错误码。

## Requirements

### Requirement: 请求由虚拟线程承载

HTTP 服务器 MUST 使用 JDK 21 虚拟线程处理请求，不引入响应式栈。系统 MUST 提供只读端点 `GET /api/v1/system/info`，返回应用名、版本、构建 JDK 与当前请求线程是否为虚拟线程。

#### Scenario: 系统信息端点暴露运行时事实

- **WHEN** 请求 `GET /api/v1/system/info`
- **THEN** 返回 HTTP 200，`data.virtualThread` 为 true，`data.javaVersion` 以 `21` 开头

### Requirement: 统一响应体

所有 `/api/**` 端点的成功响应 MUST 使用统一结构：`code`（字符串，`"0"` 表示成功）、`message`、`data`、`timestamp`（ISO-8601 UTC）。

#### Scenario: 成功响应结构一致

- **WHEN** 请求 `GET /api/v1/system/info`
- **THEN** 响应体含 code、message、data、timestamp 四个字段，code 为 `"0"`

### Requirement: 受控异常映射

`LytgosException` 的每个子类 MUST 映射为固定的 HTTP 状态与错误码，响应体沿用统一结构且 `data` 为 null。

#### Scenario: 沙箱违规为 403

- **WHEN** 某个端点抛出 `SandboxViolationException`
- **THEN** 返回 HTTP 403，code 为 `LYTGOS_SANDBOX_VIOLATION`，message 保留原异常文案

#### Scenario: 参数类异常为 400

- **WHEN** 某个端点抛出 `InvalidToolArgumentException`
- **THEN** 返回 HTTP 400，code 为 `LYTGOS_INVALID_TOOL_ARGUMENT`

#### Scenario: 其余受控异常按固定映射返回

- **WHEN** 端点分别抛出 `InvalidAgentDefinitionException`、`UnknownProviderException`、`InvalidConfigurationException`、其余 `LytgosException`
- **THEN** 分别返回 HTTP 400 / `LYTGOS_INVALID_AGENT`、HTTP 400 / `LYTGOS_UNKNOWN_PROVIDER`、HTTP 500 / `LYTGOS_INVALID_CONFIGURATION`、HTTP 500 / `LYTGOS_INTERNAL`

### Requirement: 未知异常不泄露内部细节

未被显式映射的异常 MUST 返回 500 与 `LYTGOS_INTERNAL`；响应体 MUST NOT 包含堆栈、异常类名或文件路径，这些内容只进日志。

#### Scenario: 未知异常只给通用文案

- **WHEN** 某个端点抛出 `NullPointerException`
- **THEN** 返回 HTTP 500，响应 body 不含 `NullPointerException`、`at io.lytgos` 或文件路径

### Requirement: 不存在的路径返回统一 404

请求不存在的 `/api/**` 路径 MUST 返回 HTTP 404 与 `LYTGOS_NOT_FOUND`，响应体结构与受控异常一致。

#### Scenario: 未知路径为统一 404

- **WHEN** 请求 `GET /api/v1/no-such-endpoint`
- **THEN** 返回 404，code 为 `LYTGOS_NOT_FOUND`

### Requirement: OpenAPI 文档

系统 MUST 在非 prod 环境提供 `/v3/api-docs`（OpenAPI 3 JSON）与 `/swagger-ui.html`；prod 环境 MUST 关闭 Swagger UI。

#### Scenario: 开发环境文档可访问

- **WHEN** 以默认 profile 请求 `/v3/api-docs`
- **THEN** 返回 200，JSON 含 `openapi` 与 `paths` 字段，`paths` 包含 `/api/v1/system/info`

#### Scenario: 生产环境关闭 UI

- **WHEN** 以 prod profile 请求 `/swagger-ui.html`
- **THEN** 返回 404 或 401，不返回 Swagger UI 页面
