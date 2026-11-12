# Proposal

## Why

第 1 周用内置 `http_get` 打通了「模型自主提出工具调用 → ToolExecutor 统一执行 → 沙箱校验 → 结果回填」这条链路。但企业现场的能力大多以 MCP server 的形式现成存在，如果 lytgos 只接内置工具，每接一个能力都要重写一遍工具实现，控制面的价值就被摊薄了。

第 2 周要回答三个问题：

1. **外部 MCP 工具能不能走进同一条治理路径。** 不是「能不能调通」，而是「执行是否仍然只能由 `ToolExecutor` 发起」。
2. **普通用户装上就能用吗。** 交付物是一个 fat jar，用户机器上不该再要求 Docker、WSL、Node、Python。这意味着 MCP 接入必须以**远程在线服务**为主形态——配一个 URL 就能用；本地子进程形态保留给「只能在本机起」的场景。
3. **两种形态的治理深度差异在哪里。** 远程形态由 lytgos 自己发 HTTP，域名白名单对它生效；本地形态的网络访问发生在子进程内，lytgos 看不到，只剩命令白名单这一层。这个问题不回答清楚，产品就会对外宣称一个不存在的安全边界。

## What Changes

- `Profile.McpServer` 扩展为双形态：`url`（远程 Streamable HTTP）与 `command + args`（本地 stdio）二选一，同时为空或同时非空都拒绝启动
- 新增 MCP 远程客户端：按 `Profile.mcpServers` 的 `url` 建立 Streamable HTTP 连接，完成 `initialize` 握手，用 `tools/list` 拉取工具清单
- 保留 MCP 本地客户端：按 `command` 拉起子进程，行为与已实现版本一致
- 新增 `McpTool implements Tool`（已实现）：把 MCP 工具定义转成 `ToolSpec`，`execute()` 转发为 `tools/call`
- MCP 工具与内置工具进同一个 `ToolRegistry`，重名在启动期拒绝，不做覆盖
- 远程形态的连接地址必须过 `SandboxChecker` 的域名白名单（与 `http_get` 同级），且校验发生在建连接之前
- 本地形态的启动命令必须过命令白名单
- 生命周期：Agent 加载时建立连接并发现工具，进程退出时断连 / 销毁子进程
- 演示场景改为三路径对照：同一句天气提问，路径 A 走内置 `http_get`，路径 B 走**高德官方在线 MCP**（`https://mcp.amap.com/mcp`，15 个工具含 `maps_weather`），路径 C 走本地 stdio 夹具；三条都必须经过 `ToolExecutor` 并留下 `tool_call` 日志
- **按形态记录治理边界**：远程形态受域名白名单约束；本地形态只到「启动命令白名单」这一层

## Capabilities

### New Capabilities

- `mcp-client`: MCP server 的生命周期管理（远程与本地两种形态）、工具发现、调用转发，以及 MCP 工具作为 `Tool` 实现接入 `ToolExecutor` 的方式与边界

### Modified Capabilities

- `react-loop`: 「工具执行与沙箱」一条需要补充 MCP 工具的治理深度差异——远程形态的校验对象是连接地址（域名白名单），本地形态退到启动命令，域名白名单对子进程无效。原需求「所有工具调用 MUST 先经过 SandboxChecker 校验」在两种形态下仍是事实，但校验内容不同，必须写清

## 实施中发现的既有缺陷

写 `08-mcp-acceptance` 时复现了第 1 周遗留的一个问题：问「北京今天天气怎么样」，模型选了白名单外的 `wttr.in` 并拼了一个带裸 `%` 的 query，`SandboxChecker.checkDomain` 抛 `SandboxViolation`，`ToolExecutor` 按「安全事件」处理，整轮对话中止。

这条路径上有两处错，第一处已在本 change 的重读代码中修掉（错误消息改成了「格式非法 + 正确写法」的提示，另有回归测试），第二处仍未解决：

**`checkDomain` 把「输入格式错」和「策略拒绝」混成了同一个异常类型。** 而 `ToolExecutor` 对这两类的处理策略是相反的——沙箱违规中断对话（安全事件，不能让模型靠试错探测），参数不合法回填给模型（可纠正错误，正是 ReAct 纠错能力该发挥作用的地方）。类型混同导致格式错也被当作安全事件掐掉，模型失去自我修正的机会，用户白问一次。

修复方式是给「参数不合法」一个独立异常类型，让两类失败各走各的路。**这不是新增能力，是把既有 Requirement 里没写清的一条补上**，所以并入本 change 的 `react-loop` delta。

## Impact

| 面 | 影响 |
|---|---|
| 依赖 | `lytgos-tool` 新增 MCP Java SDK（`io.modelcontextprotocol.sdk:mcp:2.1.0`） |
| 代码 | `lytgos-core` 的 `Profile.McpServer` 加 `url` 字段；`lytgos-tool` 的 `McpServerManager` 分远程 / 本地两条启动路径；`lytgos-persona` 的解析加 `url` 字段 |
| 契约 | `Profile.McpServer` 由 4 字段变 5 字段，二选一校验在构造器里完成 |
| 配置 | `config.yaml` 的 `allowed-domains` 需包含远程 MCP 的域名（如 `mcp.amap.com`）；没有远程 server 的配置不受影响 |
| 验收 | `08-mcp-acceptance` 增加远程路径；远程路径需要 `AMAP_MCP_KEY`，缺失时记 SKIP |
| 风险 | 远程 MCP 服务可用性不由 lytgos 控制；连不上时按配置错误报出 server 名与 URL，不做重试 |
