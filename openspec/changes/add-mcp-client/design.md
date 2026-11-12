# Design

## Context

第 1 周已有的接口正好够用，不需要改动：

- `Tool`：`spec()` + `execute(Map<String,Object>)`，实现方只负责「怎么干」
- `ToolExecutor`：唯一执行入口，沙箱违规抛出、工具失败回填
- `ToolRegistry`：构造后不可变，重名拒绝启动
- `Profile.McpServer(name, command, args, env)`：第 1 周已占位，只解析不启动

约束来自 `AGENTS.md` 4.1：模型只能「提出」调用，执行必须由 `ToolExecutor` 发起。MCP 工具如果绕过它，治理就断了。

动机见 `proposal.md` - Why；行为契约见 `specs/mcp-client/spec.md` 与 `specs/react-loop/spec.md`。

## Goals / Non-Goals

**Goals:**

- MCP 工具以 `Tool` 实现的身份进入 `ToolRegistry`，与内置工具共用同一条执行路径
- server 生命周期由控制面管理，不让用户手动起进程
- 沙箱深度差异在启动时就能被看到，而不是藏在文档里

**Non-Goals:**

- 不支持 SSE / Streamable HTTP 传输，第 2 周只做 stdio
- 不做 MCP server 的分发、安装或版本管理——用户自己准备可执行文件
- 不做 MCP resources / prompts / sampling，只做 tools
- 不改 `Profile` 结构

## Decisions

### 1. 用官方 Java SDK，不用 Spring AI 的包装层

依赖坐标：

```xml
<dependency>
    <groupId>io.modelcontextprotocol.sdk</groupId>
    <artifactId>mcp</artifactId>
    <version>2.1.0</version>
</dependency>
```

理由：

- `spring-ai-mcp:2.0.2` 内部依赖的正是 `io.modelcontextprotocol.sdk:mcp:2.1.0`（已核对其 pom），直接引 SDK 与未来升级对齐
- 不引 `spring-ai-mcp` 是因为它依赖 `spring-ai-model`，而 `lytgos-tool` 现在不依赖任何 Spring AI，`04` 闸门也只允许 `lytgos-provider` 引 Spring AI
- SDK 提供 `listTools()` / `callTool()` 这种「发起并返回」的语义，不自动执行工具，符合 4.1

**备选：自实现 JSON-RPC over stdio（约 300 行）**。零依赖、完全可控，但要在分帧、编码、能力协商、超时、进程回收上重复踩一遍坑，而这些不是本项目的差异化价值。只有在 SDK 引入不可接受的传递依赖时才回退到这条路。

### 2. 只做 stdio 传输

选型已定 `gbrigandi/mcp-server-openmeteo`（Rust，Windows 有预编译 exe，stdio）。SSE / HTTP 留到有真实需求时再加。

### 3. 顺序启动，失败即整体失败

`Profile.mcpServers` 是 List，但第 2 周只有一个 server。按顺序启动，任何一个失败就让 Agent 加载失败。

不做「跳过失败的 server 继续启动」——少一个工具会让模型在对话中反复尝试不存在的工具，比直接启动失败更难诊断。

**备选：并发启动**。多个 server 时能省启动时间，但第 2 周不需要，且并发下的错误归因更复杂。

### 4. 工具名保持原样，重名拒绝启动

MCP 工具名不加 `server__` 前缀，`ToolRegistry` 检测到重名直接拒绝启动，错误消息要写清「哪个 server 的哪个工具」与「和谁重名」。

**备选：加前缀避免冲突**。前缀会让模型看到的工具名变长、语义变模糊；第 2 周只有一个 server，没有实际冲突。等真出现多 server 同名工具时再加配置项。

### 5. 生命周期挂在 Agent 加载与进程退出上

```
AgentLoader 解析 Profile
  → McpServerManager.startAll(profile.mcpServers)   // 校验命令白名单 → 起进程 → 握手 → listTools
  → ToolRegistry(内置工具 + MCP 工具)               // 重名在这里炸
  → 对话
  → JVM shutdown hook / close()                      // 销毁子进程
```

用 shutdown hook 兜底：正常退出、异常退出都要回收，避免留下孤儿进程。

### 6. 工具来源写进 `ToolSpec.description`

`lytgos tool list` 展示的是 `ToolSpec`，所以在 MCP 工具的 description 前加 `[mcp:<server>]`，用户和模型都能看出这个能力来自哪个外部进程，不需要额外查询命令。

### 7. 新增 `08-mcp-acceptance.ps1`，与 07 分开

`07` 是第 1 周的验收，改动它会污染既有基线。双路径对照单独成闸门：

| 路径 | 配置 | 断言 |
|---|---|---|
| A 内置 | `tools: [http_get]`，白名单含 open-meteo | 日志出现 `tool_call: http_get`，输出含温度数值 |
| B MCP | `mcpServers: [openmeteo]`，`tools: []` | 日志出现 `tool_call: <MCP 工具名>`，输出含温度数值 |

两条路径都必须出现 `tool_call` 日志——这证明执行确实经过了 `ToolExecutor`，而不是别的什么通道。

## Risks / Trade-offs

| 风险 | 缓解 |
|---|---|
| MCP SDK 2.1.0 的实际 API 与文档不一致，编译不过 | tasks 第一步就加依赖写一个最小探针（起进程 + listTools），跑通再往下做 |
| `mcp-server-openmeteo.exe` 在 Windows 被杀软拦截或缺少运行库 | tasks 里先手工跑一次 exe 确认能启动；跑不起来就换备选 `cmer81/open-mcp-mcp`（Node）或直接降级为「MCP 用任何可用的 server 验证」 |
| MCP server 握手慢导致启动卡住 | 握手设超时（默认 10s），超时即失败并打印 server 名，不无限等 |
| 孤儿进程残留 | shutdown hook + `close()` 双重回收；08 闸门增加一条「退出后无残留进程」的断言 |
| 用户误以为 MCP 工具也受域名白名单保护 | 启动日志逐 server 打印「域名白名单不覆盖该 server」；`tool list` 标注来源；`react-loop` 基线里写明边界 |

## Migration Plan

无破坏性变更：不配置 `mcpServers` 的 Agent 行为与第 1 周完全一致，内置工具路径不受影响。

回滚方式：移除 `mcpServers` 配置即可，不需要回退代码。

## Open Questions

- MCP server 的启动日志是否要单独落到 `.lytgos/logs/mcp-<server>.log`？——第 2 周先混在主日志里，真需要排查时再拆
