# Proposal

## Why

第 1 周用内置 `http_get` 打通了「模型自主提出工具调用 → ToolExecutor 统一执行 → 沙箱校验 → 结果回填」这条链路。但企业现场的能力大多以 MCP server 的形式现成存在，如果 lytgos 只接内置工具，每接一个能力都要重写一遍工具实现，控制面的价值就被摊薄了。

第 2 周要回答两个问题：

1. **外部 MCP 工具能不能走进同一条治理路径。** 不是「能不能调通」，而是「执行是否仍然只能由 `ToolExecutor` 发起」。
2. **走进同一条路径之后，治理深度是否一致。** 这是双路径对照真正要暴露的东西——内置工具的域名白名单在进程内生效，MCP server 是独立进程，它的网络访问 lytgos 看不到。

第二个问题不回答清楚，产品就会对外宣称一个不存在的安全边界。

## What Changes

- 新增 MCP stdio 客户端：按 `Profile.mcpServers` 拉起子进程，完成 `initialize` 握手，用 `tools/list` 拉取工具清单
- 新增 `McpTool implements Tool`：把 MCP 工具定义转成 `ToolSpec`，`execute()` 转发为 `tools/call`
- MCP 工具与内置工具进同一个 `ToolRegistry`，重名在启动期拒绝，不做覆盖
- MCP server 的启动命令必须过 `SandboxChecker` 的命令白名单——只允许配置中显式声明的可执行文件
- 生命周期：Agent 加载时启动 server 并发现工具，进程退出时销毁子进程
- 演示场景换成双路径对照：同一句「北京今天天气怎么样」，路径 A 走内置 `http_get`，路径 B 走 `mcp-server-openmeteo`，两者都要经过 `ToolExecutor` 并留下 `tool_call` 日志
- **明确记录治理边界**：MCP 工具的网络访问发生在子进程内，`SandboxChecker` 的域名白名单对它无效。MCP 路径的沙箱深度只到「启动命令白名单」这一层

## Capabilities

### New Capabilities

- `mcp-client`: MCP server 的生命周期管理、工具发现、调用转发，以及 MCP 工具作为 `Tool` 实现接入 `ToolExecutor` 的方式与边界

### Modified Capabilities

- `react-loop`: 「工具执行与沙箱」一条需要补充 MCP 工具的治理深度差异——校验对象从「HTTP 域名」退到「启动命令」，域名白名单对子进程无效。原需求「所有工具调用 MUST 先经过 SandboxChecker 校验」在 MCP 场景下仍是事实，但校验内容不同，必须写清

## 实施中发现的既有缺陷

写 `08-mcp-acceptance` 时复现了第 1 周遗留的一个问题：问「北京今天天气怎么样」，模型选了白名单外的 `wttr.in` 并拼了一个带裸 `%` 的 query，`SandboxChecker.checkDomain` 抛 `SandboxViolation`，`ToolExecutor` 按「安全事件」处理，整轮对话中止。

这条路径上有两处错，第一处已在本 change 的重读代码中修掉（错误消息改成了「格式非法 + 正确写法」的提示，另有回归测试），第二处仍未解决：

**`checkDomain` 把「输入格式错」和「策略拒绝」混成了同一个异常类型。** 而 `ToolExecutor` 对这两类的处理策略是相反的——沙箱违规中断对话（安全事件，不能让模型靠试错探测），参数不合法回填给模型（可纠正错误，正是 ReAct 纠错能力该发挥作用的地方）。类型混同导致格式错也被当作安全事件掐掉，模型失去自我修正的机会，用户白问一次。

修复方式是给「参数不合法」一个独立异常类型，让两类失败各走各的路。**这不是新增能力，是把既有 Requirement 里没写清的一条补上**，所以并入本 change 的 `react-loop` delta。

## Impact

| 面 | 影响 |
|---|---|
| 依赖 | `lytgos-tool` 新增 MCP Java SDK（具体坐标与版本在 design 定稿） |
| 代码 | `lytgos-tool` 新增 MCP 客户端实现；`lytgos-boot` / `lytgos-cli` 的装配路径需要按 Profile 启动 server |
| 契约 | `Profile.McpServer` 第 1 周已占位，结构不变 |
| 验收 | 需要新增 MCP 双路径对照的端到端检查，与 `07-week1-acceptance` 分开 |
| 风险 | MCP server 是外部进程，版本、可用性、退出行为都不由 lytgos 控制；`mcp-server-openmeteo` 为 Rust 预编译 exe，需确认 Windows 下可执行 |
