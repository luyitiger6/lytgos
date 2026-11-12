# Tasks

## 1. 前置验证

- [x] 1.1 确认可用的 MCP server 与工具清单
  - `gbrigandi/mcp-server-openmeteo` 的 Release 只发了 linux / macos 二进制，Windows 上没有可执行文件；换第三方 Node 包又要赌 npm 仓库可用，且闸门断言会被外部服务抽风带红
  - 改用手写夹具 `FakeMcpServer`：纯 JDK，只实现 stdio 协议够用的一段，不联网、输出固定，暴露 `get_current_weather` 与 `get_forecast` 两个工具
  - **验收**：夹具能被 SDK 客户端握手成功并列出 2 个工具（由 2.1 的 `discoversTools` 覆盖）

- [x] 1.2 引入 MCP SDK 并跑通最小探针
  - `lytgos-tool/pom.xml` 增加 `io.modelcontextprotocol.sdk:mcp:2.1.0`
  - 探针没有单独建类：2.1 的 `McpServerManagerTest` 直接用它拉起夹具 server，握手 + `tools/list` 全跑通了
  - **验收**：`mvn -pl lytgos-tool -am test` 通过，含 `McpServerManagerTest` 6 个用例
  - SDK 实际 API 与 design 预期一致，无需回退到自实现 JSON-RPC

## 2. MCP 客户端核心

- [x] 2.1 `McpServerManager`：启动、握手、工具发现
  - 启动命令先过 `SandboxChecker` 命令白名单
  - 按 `Profile.mcpServers` 顺序启动，握手超时 10 秒，请求超时 30 秒
  - `tools/list` 跟随分页游标，不只看第一页
  - **验收**：`McpServerManagerTest` 覆盖——正常启动并发现工具、命令不在白名单时拒绝且消息含 `sandbox`、缺 name / 命令不存在时报告具体 server

- [x] 2.2 `McpTool implements Tool`
  - `spec()`：名称保持 server 返回的原样，description 前加 `[mcp:<server>]`，inputSchema 原样透传
  - `execute()`：转发调用并把结果转文本；协议错误转成可回填文本，不抛异常
  - **验收**：`McpToolTest` 3 个用例，覆盖 spec 三字段与成功/失败两条路径

- [x] 2.3 子进程生命周期回收
  - shutdown hook + 显式 `close()`，启动中途失败时回收已起的进程
  - **验收**：`McpServerManagerTest.closeStopsChildProcess` 断言 `close()` 后子进程 `isAlive()` 为 false（轮询最多 10 秒）

- [x] 2.4 工具重名拒绝启动
  - MCP 工具与内置工具同名时抛 `InvalidConfiguration`，消息含工具名与两侧来源
  - **验收**：`McpServerManagerTest.rejectsDuplicateNameWithBuiltin` 构造同名内置工具，断言消息含 `get_current_weather` 与 `McpTool[fake]`

## 3. 装配与配置

- [x] 3.1 CLI 装配接入 MCP 启动流程
  - `AgentRuntime` 统一装配：Agent 加载后启动 server，逐 server 打印「域名白名单不覆盖该 server」
  - 失败路径也回收：MCP 起了一半、或工具装配时发现重名，已起的进程都会被关掉
  - **验收**：`08-mcp-acceptance` 第 2/3/4 条实测通过

- [x] 3.2 `lytgos init` 的配置模板补 `allowed-commands` 示例
  - `config.yaml` 模板已有 `allowed-commands: []`，注释里写清了「MCP server 的启动命令必须先在这里出现」与「域名白名单不覆盖子进程」
  - **验收**：`08-mcp-acceptance` 从 `init` 产物改配置后 5 条沙箱断言通过；`07-week1-acceptance` 第 1 条（10 个顶层条目）仍通过

- [x] 3.3 README 补 MCP 配置与治理边界说明
  - Agent 定义章节加「接入 MCP server」小节，含 `mcp_servers` 示例、三个前提、边界段
  - 顺手修正了同章节里与实际不符的旧字段（`read_file` → `file_read`、`max_iterations` → `maxIterations`、去掉未实现的工具名）
  - **验收**：字段名逐个对照 `Profile.McpServer`（`name` / `command` / `args` / `env`）与 `AgentLoader`（`mcp_servers`）核对一致

## 4. 双路径对照验收

- [x] 4.1 新增 `harness/checks/08-mcp-acceptance.ps1`
  - 路径 A：`tools: [http_get]`，断言日志含 `tool_call: http_get` 且输出含温度数值
  - 路径 B：`tools: [get_current_weather]` + `mcp_servers: [openmeteo]`，断言日志含 `tool_call: get_current_weather`、输出含 server 返回的气温、且无越权调用 `http_get`
  - 另有工具来源标注、边界提示、命令白名单、退出后无残留子进程四条
  - **验收**：脚本输出六条全 PASS，退出码 0

- [x] 4.2 同步闸门清单
  - `AGENTS.md` 的 `harness-checks` 标记行与目录地图、`harness/README.md` 的闸门表都加上 `08-mcp-acceptance`
  - **验收**：`powershell -File harness\checks\00-layout-check.ps1` 退出码 0

## 5. 实施中发现的既有缺陷

- [x] 5.1 参数错误与沙箱违规分家
  - `LytgosException` 新增 `InvalidToolArgument`；`SandboxChecker.checkDomain` 的格式非法 / URL 为空 / 缺 host、`HttpTool` 缺参都改抛它；协议限制（如 `file://`）仍是 `SandboxViolation`
  - `ToolExecutor` 不加分支——新类型自然落入 `catch (RuntimeException)` 回填路径
  - **验收**：`SandboxCheckerTest` 断言格式错抛的是 `InvalidToolArgument`；新增 `ToolExecutorTest` 5 个用例断言参数错 / 运行期错 / JSON 错都回填、只有沙箱违规向外抛

## Workflow follow-up

- 合并 PR 后在远程 main 上打 tag `v0.2.0`
- 归档本 change，把 `specs/mcp-client` 落为主 spec，并把 `react-loop` 的 MODIFIED 合并进基线
