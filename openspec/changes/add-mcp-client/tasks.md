# Tasks

## 1. 前置验证

- [ ] 1.1 手工启动 `mcp-server-openmeteo.exe` 并记录工具清单
  - 从项目 Release 取 Windows 预编译 exe，手工运行一次
  - **验收**：exe 启动后不立即退出；按 stdio 发送初始化请求能收到响应；记录实际工具名（预期 4 个，与 design 的选型表一致）

- [ ] 1.2 引入 MCP SDK 并跑通最小探针
  - `lytgos-tool/pom.xml` 增加 `io.modelcontextprotocol.sdk:mcp:2.1.0`
  - 写 `McpSdkProbeTest`：用 SDK 起 stdio 客户端连 1.1 的 exe，拉取工具清单
  - **验收**：`mvn -pl lytgos-tool test '-Dtest=McpSdkProbeTest'` 通过并打印出工具名
  - 若 SDK API 与 design 预期不符，在此停下，按 design 的备选方案（自实现 JSON-RPC）重新评估后再继续

## 2. MCP 客户端核心

- [ ] 2.1 `McpServerManager`：启动、握手、工具发现
  - 启动命令先过 `SandboxChecker` 命令白名单
  - 按 `Profile.mcpServers` 顺序启动，握手超时 10 秒
  - **验收**：`McpServerManagerTest` 覆盖三条——正常启动并发现工具、命令不在白名单时拒绝且消息含 `sandbox`、握手超时时失败且消息含 server 名

- [ ] 2.2 `McpTool implements Tool`
  - `spec()`：名称保持 server 返回的原样，description 前加 `[mcp:<server>]`，inputSchema 原样透传
  - `execute()`：转发调用并把结果转文本；协议错误转成可回填文本，不抛异常
  - **验收**：`McpToolTest` 断言 spec 三个字段，并断言正常返回与错误回填两条路径

- [ ] 2.3 子进程生命周期回收
  - shutdown hook + 显式 `close()`
  - **验收**：`McpServerManagerTest` 增加用例——`close()` 之后子进程 `isAlive()` 为 false

- [ ] 2.4 工具重名拒绝启动
  - MCP 工具与内置工具同名时抛 `InvalidConfiguration`，消息含工具名与两侧来源
  - **验收**：单测构造一个与 `http_get` 同名的 MCP 工具，断言启动失败且消息含两者来源

## 3. 装配与配置

- [ ] 3.1 CLI 装配接入 MCP 启动流程
  - Agent 加载完成后启动 server，逐 server 打印「域名白名单不覆盖该 server」
  - **验收**：在临时工作区配置一个 server，`lytgos tool list` 出现 MCP 工具且说明含 `[mcp:<server>]`；启动日志出现边界提示

- [ ] 3.2 `lytgos init` 的配置模板补 `allowed-commands` 示例
  - `config.yaml` 里给出一条被注释的 MCP server 启动命令示例
  - **验收**：新目录执行 `lytgos init` 后查看生成的 `config.yaml`；原有 `07` 第 1 条（10 个顶层条目）仍通过

- [ ] 3.3 README 补 MCP 配置与治理边界说明
  - 在 Agent 定义章节加 `mcpServers` 示例，并写明「子进程的网络访问不受域名白名单约束」
  - **验收**：按 README 的示例字段逐项核对，字段名与 `Profile.McpServer` 一致

## 4. 双路径对照验收

- [ ] 4.1 新增 `harness/checks/08-mcp-acceptance.ps1`
  - 路径 A：`tools: [http_get]`，断言日志含 `tool_call: http_get` 且输出含温度数值
  - 路径 B：`mcpServers: [openmeteo]` 且 `tools: []`，断言日志含 `tool_call: <MCP 工具名>` 且输出含温度数值
  - 退出后断言无残留子进程
  - **验收**：脚本输出全部 PASS，退出码 0

- [ ] 4.2 同步闸门清单
  - `AGENTS.md` 的 `harness-checks` 标记行与 `harness/README.md` 的闸门表都加上 `08-mcp-acceptance`
  - **验收**：`powershell -File harness\checks\00-layout-check.ps1` 退出码 0（清单与实际文件一致）

## Workflow follow-up

- 合并 PR 后在远程 main 上打 tag `v0.2.0`
- 归档本 change，把 `specs/mcp-client` 落为主 spec，并把 `react-loop` 的 MODIFIED 合并进基线
