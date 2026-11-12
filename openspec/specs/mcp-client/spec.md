# mcp-client Specification

## Purpose

让 lytgos 以 MCP 客户端身份接入外部工具服务器：由控制面管理 server 生命周期、发现工具、转发调用，并让这些工具与内置工具走同一条治理路径。

支持两种接入形态，由 `Profile.McpServer` 的字段二选一决定：远程 `url`（Streamable HTTP）与本地 `command + args`（stdio）。

## Requirements

### Requirement: MCP server 生命周期管理

系统 SHALL 按 `Profile.mcpServers` 在 Agent 加载时建立 MCP 连接：

- 声明了 `url` 时 MUST 通过 Streamable HTTP 连接该地址并完成初始化握手；
- 声明了 `command` 时 MUST 拉起子进程并通过 stdio 完成初始化握手。

`url` 与 `command` MUST 有且仅有一个：同时为空或同时非空时 MUST 拒绝启动，错误消息写清二选一规则。

任何一种形态启动失败（域名被拒、命令不存在、握手超时、协议版本不兼容）时 MUST 使本次 Agent 加载失败，错误消息包含 server 名与失败原因，不得静默跳过。

Agent 会话结束时 MUST 关闭远程连接；由它启动的子进程 MUST 全部销毁。

#### Scenario: 远程形态启动并完成握手

- **WHEN** Profile 声明了 `url: https://mcp.example.com/mcp` 且该域名在白名单内
- **THEN** 通过 Streamable HTTP 建立连接，初始化握手完成，启动日志包含该 server 名与 URL

#### Scenario: 本地形态启动并完成握手

- **WHEN** Profile 声明了一个可执行的 MCP server command
- **THEN** 子进程被拉起，初始化握手完成，启动日志包含该 server 名

#### Scenario: 两种形态同时缺失或同时出现

- **WHEN** Profile 的某个 server 既没有 `url` 也没有 `command`，或两者都有
- **THEN** Agent 加载失败，错误消息说明必须且只能配置其一

#### Scenario: 命令不存在时拒绝启动

- **WHEN** Profile 声明的 command 在系统中不存在
- **THEN** Agent 加载失败，错误消息包含 server 名与 command，退出码非 0

#### Scenario: 退出时销毁子进程

- **WHEN** lytgos 进程正常退出
- **THEN** 由它启动的 MCP 子进程全部被终止，无残留；远程连接全部关闭

### Requirement: MCP 工具发现

系统 SHALL 在握手完成后请求工具清单，并把每个工具注册为一个 `Tool` 实现。

工具名 MUST 与 server 返回的名称一致，不得改写或加前缀。与其他已注册工具重名时 MUST 拒绝启动，错误消息包含重名工具名与两侧来源。

#### Scenario: 工具进入注册表

- **WHEN** server 返回 4 个工具
- **THEN** 这 4 个工具出现在 `ToolRegistry` 中，`lytgos tool list` 可见

#### Scenario: 与内置工具重名

- **WHEN** MCP server 提供的工具名为 `http_get`，与内置工具同名
- **THEN** 启动失败，错误消息指明重名工具与两侧来源

### Requirement: MCP 工具调用转发

MCP 工具 MUST 实现 `Tool` 接口，执行入口仍是 `ToolExecutor`；系统 MUST NOT 提供绕过 `ToolExecutor` 直接调用 MCP server 的路径。

调用时 MUST 把 `Tool` 收到的参数原样作为调用参数发出，并把返回内容转为文本回填给模型。

server 返回协议错误时 MUST 转为可回填的文本，由模型自行决定重试或改法，不得中断对话。

#### Scenario: 经 ToolExecutor 调用

- **WHEN** 模型请求 `maps_weather`
- **THEN** 日志出现 `tool_call: maps_weather`，请求被转发到 server，结果回填给模型

#### Scenario: 调用失败回填

- **WHEN** server 返回错误
- **THEN** 模型收到包含错误原因的文本，对话继续

### Requirement: 远程连接地址受沙箱约束

远程形态的连接地址 MUST 在建立连接之前经 `SandboxChecker` 的域名白名单校验，与内置 `http_get` 同级。

校验失败时 MUST 拒绝启动，错误消息包含 `sandbox`，且不得发出任何网络请求。

`url` 携带的 query（如 `?key=...`）MUST 原样保留用于连接，不参与域名匹配。

#### Scenario: 白名单内域名放行

- **WHEN** url 的 host 出现在 `sandbox.allowed-domains` 中
- **THEN** 连接建立，server 正常启动

#### Scenario: 白名单外域名拒绝

- **WHEN** url 的 host 不在白名单
- **THEN** 启动失败，消息包含 `sandbox`，无网络请求发出

#### Scenario: 非 http/https 协议拒绝

- **WHEN** url 使用 `file://` 等非 http/https 协议
- **THEN** 启动失败，判定为沙箱违规

### Requirement: 本地形态的启动命令受沙箱约束

本地形态的启动命令 MUST 先经 `SandboxChecker` 的命令白名单校验。

命令不在白名单时 MUST 拒绝启动，错误消息包含 `sandbox`，且子进程不得被创建。

#### Scenario: 白名单内命令放行

- **WHEN** command 出现在 `sandbox.allowed-commands` 中
- **THEN** server 正常启动

#### Scenario: 白名单外命令拒绝

- **WHEN** command 不在白名单
- **THEN** 启动失败，消息包含 `sandbox`，无子进程产生

### Requirement: 治理边界可观测

系统 SHALL 在启动时为每个 MCP server 输出一行提示，说明该形态的沙箱边界：

- 远程形态：说明连接地址已通过域名白名单校验；
- 本地形态：说明该 server 的网络访问不受域名白名单约束。

`lytgos tool list` MUST 在 MCP 工具的说明中标注其来源 server。

#### Scenario: 远程形态启动提示

- **WHEN** Agent 加载了远程 MCP server
- **THEN** 日志中出现该 server 的地址已通过域名白名单校验的说明

#### Scenario: 本地形态启动提示

- **WHEN** Agent 加载了本地 MCP server
- **THEN** 日志中出现「域名白名单不覆盖该 server」的提示

#### Scenario: 工具来源可见

- **WHEN** 执行 `lytgos tool list`
- **THEN** MCP 工具的说明中包含来源 server 名
