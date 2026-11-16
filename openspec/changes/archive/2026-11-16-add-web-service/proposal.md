# Proposal

## Why

第 3 周的交付口径是「外部系统能通过 10 个 REST 端点调用 lytgos」。当前 `lytgos-web` 只有 Web 地基：唯一业务端点 `GET /api/v1/system/info`、统一 404 兜底与异常映射；设计 §8 的 10 个端点缺 9 个，`lytgos serve` 命令完全不存在，`AGENTS.md` 因此明确写着「不得表述为第 3 周完成」。

这是产品定位的关键一步：lytgos 从「只能在本机用 CLI 驱动」变成「可被外部系统集成的 Agent 控制面」。同时它也是第 4 周（多 Agent 同实例、`session list`、`gateway`）的前置——没有一条稳定的 HTTP 入口，多 Agent 演示只能靠开多个终端。

## What Changes

- **新增 `lytgos-runtime` 模块**：把业务装配（工作区定位、沙箱、持久化、Provider 注册表、内置工具、`AgentRuntimeFactory`）从 `lytgos-cli` 下沉到独立模块，让 CLI 与 Web 两个入口共享同一套装配，而不是让 `lytgos-web` 反向依赖 `lytgos-cli`。
- **实现 `lytgos-web` 的 10 个业务端点**（设计 §8）：会话创建 / 发消息 / 查历史 / 归档、无状态 Agent 调用、Profile 列表、长期记忆查询、工具列表、健康检查、运行信息。
- **补充 `GET /api/v1/sessions`（列表）**：设计 §8 之外的第 11 个端点。管理台与第 4 周 `lytgos session list` 都需要「列出全部会话」，而 `SessionStore` 当前只有取与存。**BREAKING**：`SessionStore` 接口新增 `list()` / `delete()`，`SqliteSessionStore` 与 `FileSessionStore` 同步实现。
- **新增 `lytgos serve` 命令**：`--port`（默认 **8989**）、`--root`（工作区根，兼容 `LYTGOS_ROOT`）；启动与 `java -jar lytgos.jar` 相同的 Spring Web 容器与业务装配。
- **新增 `/admin/` 只读管理台**：单个静态 HTML 页面（无构建链、不引前端框架），调用上述 REST 端点展示服务信息、Profile、工具、记忆与会话。
- **新增闸门 `14-web-api-acceptance.ps1`**：启动 `lytgos-cli-boot.jar serve`，走真实 HTTP 链路逐条核对本次 spec 的 Scenario；同步 `AGENTS.md` 的 `harness-checks:` 清单行与 `lytgos-test` 的模块登记。
- **端口重排（18080 让位给 Nacos）**：全仓端口一次性收进一张表——`serve` 默认 8989；11 号闸门从 18080 迁到 18090；14 号闸门用 18083。修正原打算让 14 号用 18081 与 12 号闸门（18081/18082）撞车的问题。
- **实现完成后回调文档口径**：`site/` 与 `docs/design/architecture.md` 中已经把 `serve`、`/admin/`、REST 端点写成「已完成」的内容，实现后由假变真；届时仍未实现的能力（Provider 注册表 CRUD、通知渠道、定时任务、文件浏览、审计 UI）从「已完成/可用」降级。

**不在本 change 范围**（第 4 周或更后，需要时另立 change）：`gateway` / `status` / `session list` 命令、多 Agent 同实例并存演示、`/admin/` 的写操作与管理功能、REST 鉴权（设计 012）、SSE 流式（设计 019）、限流。

## Capabilities

### New Capabilities

- `web-service`: lytgos 对外 HTTP 服务面的完整契约——`serve` 启动、会话生命周期、Agent 调用、配置与运行信息查询、管理台页面，以及「默认只绑定回环地址」的安全边界。

### Modified Capabilities

（无。`http-api` 定义的统一响应体、受控异常映射、未知路径 404、OpenAPI 文档四项契约不变，新增端点全部遵守它们。）

## Impact

- **模块**：新增 `lytgos-runtime`（`WorkspaceConfig`、`WorkspaceConfigProvider`、`CoreBeans`、`ProviderBeans`、`ToolBeans`、`CliBeans`、`CliConfiguration`、`AgentRuntimeFactory`、`AgentRuntime` 迁入）；`lytgos-cli` 与 `lytgos-web` 改为依赖它；`lytgos-boot` 的启动类导入 runtime 装配；根 pom 与 `lytgos-test` 登记新模块。
- **API**：`/api/v1` 下新增 10 个业务端点与 1 个会话列表端点；新增 `/admin/` 静态页面。已有 `GET /api/v1/system/info` 保留，语义不变。
- **存储**：`SessionStore` 接口扩展（`list` / `delete`），`SessionMapper` 新增查询全部与删除两条语句；`sessions` 表结构不变。
- **依赖**：`lytgos-cli` 新增 `lytgos-web` 与 `spring-boot-starter-web` 依赖（`serve` 需要 Web 容器），`lytgos-cli-boot.jar` 体积增大；`serve` 之外的命令仍走懒启动路径，`--version` / `--help` 启动时间不受影响。
- **闸门**：新增 14 号闸门；`AGENTS.md` 的闸门清单行、`harness/README.md` 闸门清单同步；`run-all` 的通过门槛从 13 道变 14 道。缺 `DEEPSEEK_API_KEY` 时端点不依赖模型的断言全部执行，依赖模型的断言记 SKIP 并返回 2（与 07/08/10 同口径）。
- **文档**：`docs/design/architecture.md` 的端口口径（9898 vs 8989）与 `site/` 的相关页面在实现后更新。
