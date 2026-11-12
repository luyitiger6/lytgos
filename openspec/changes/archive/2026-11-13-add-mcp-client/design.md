# Design

## Context

第 1 周已有的接口正好够用，不需要改动：

- `Tool`：`spec()` + `execute(Map<String,Object>)`，实现方只负责「怎么干」
- `ToolExecutor`：唯一执行入口，沙箱违规抛出、工具失败回填
- `ToolRegistry`：构造后不可变，重名拒绝启动
- `Profile.McpServer`：第 1 周已占位，本 change 扩展为双形态

约束来自 `AGENTS.md` 4.1：模型只能「提出」调用，执行必须由 `ToolExecutor` 发起。MCP 工具如果绕过它，治理就断了。

动机见 `proposal.md` - Why；行为契约见 `specs/mcp-client/spec.md` 与 `specs/react-loop/spec.md`。

## Goals / Non-Goals

**Goals:**

- MCP 工具以 `Tool` 实现的身份进入 `ToolRegistry`，与内置工具共用同一条执行路径
- 支持两种接入形态：远程 URL（Streamable HTTP）与本地命令（stdio），由配置二选一
- 远程形态的连接地址受 `SandboxChecker` 域名白名单约束，与内置 `http_get` 同级
- server 生命周期由控制面管理，远程形态由 lytgos 自己发 HTTP，不依赖任何外部运行时
- 沙箱深度差异在启动时就能被看到，而不是藏在文档里

**Non-Goals:**

- 不做 MCP server 的分发、安装或版本管理——本地形态由用户自己准备可执行文件
- 不做 MCP resources / prompts / sampling，只做 tools
- 不做远程形态的 OAuth 交互式认证流程；静态 key / token 直接写在 URL 或请求头里是支持的

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
- Spring AI 的 MCP Starter 是「应用启动时按静态配置装配若干连接」的自动装配模式，连接在容器启动期固定；lytgos 的 MCP 连接跟着 **Agent 定义**走，需要在加载任意 Agent 时按它的声明建立连接，并在 Agent 结束时回收。自动装配模型对不上这个生命周期
- SDK 提供 `listTools()` / `callTool()` 这种「发起并返回」的语义，不自动执行工具，符合 4.1

**备选：自实现 JSON-RPC over stdio（约 300 行）**。零依赖、完全可控，但要在分帧、编码、能力协商、超时、进程回收上重复踩一遍坑，而这些不是本项目的差异化价值。只有在 SDK 引入不可接受的传递依赖时才回退到这条路。

### 2. 远程为主、本地为辅，由配置二选一

`Profile.McpServer` 支持两种形态：

| 形态 | 配置字段 | 连什么 | 平台依赖 | 沙箱校验对象 |
|---|---|---|---|---|
| 远程 | `url` | 对端托管的 MCP 服务 | 无 | 域名白名单 |
| 本地 | `command` + `args` | 本机子进程 | 需要对应平台的可执行文件 | 命令白名单 |

**远程是推荐的默认形态。** 产品交付是一个 fat jar，用户 `java -jar` 就能跑；远程形态不需要 Docker、WSL、Node、Python 或任何额外运行时——这正是「普通用户装上就能用」的前提。

本地形态保留，是因为企业现场确有一些服务只能本地起（访问本机文件、内网 CLI）。它属于可选形态，不是默认路径。

配置示例：

```yaml
mcp_servers:
  - name: amap
    url: https://mcp.amap.com/mcp?key=YOUR_KEY
  - name: local-files
    command: /opt/tools/file-server
    args: []
```

`url` 与 `command` 都为空或都非空时拒绝启动，错误消息写清二选一规则。

**用于验收的真实服务：高德官方在线 MCP。** `https://mcp.amap.com/mcp`，15 个工具，含 `maps_weather`（按城市查实时天气与预报）。选它的理由：国内直连可达、官方托管、key 走配置。

不造假的 HTTP 服务端做验收：外部服务的可用性不是本项目要解决的问题，验收要证明的是「lytgos 能通过 HTTP 连上真实的 MCP 服务并按协议调用」。

### 3. 远程形态的沙箱：连之前先过域名白名单

远程形态下 lytgos 自己发 HTTP，因此域名白名单**对它生效**，与内置 `http_get` 同级治理：

```
校验 URL（scheme / userinfo / host 在白名单内）→ 建连接 → 握手 → listTools
```

顺序与本地形态一致：**校验必须在建连接之前**，校验失败时一个字节的网络请求都还没发出去。

本地形态的边界不同：网络访问发生在子进程内，域名白名单看不到，只剩启动命令白名单这一层。两类形态的边界差异在启动日志里分别说明。

### 4. 顺序启动，失败即整体失败

`Profile.mcpServers` 是 List，按顺序启动，任何一个失败就让 Agent 加载失败。

不做「跳过失败的 server 继续启动」——少一个工具会让模型在对话中反复尝试不存在的工具，比直接启动失败更难诊断。

**备选：并发启动**。多个 server 时能省启动时间，但当前不需要，且并发下的错误归因更复杂。

### 5. 工具名保持原样，重名拒绝启动

MCP 工具名不加 `server__` 前缀，`ToolRegistry` 检测到重名直接拒绝启动，错误消息要写清「哪个 server 的哪个工具」与「和谁重名」。

**备选：加前缀避免冲突**。前缀会让模型看到的工具名变长、语义变模糊；当前没有实际冲突。等真出现多 server 同名工具时再加配置项。

### 6. 生命周期挂在 Agent 加载与进程退出上

```
AgentLoader 解析 Profile
  → McpServerManager.start(profile.mcpServers)
      远程：域名白名单 → HTTP 连接 → 握手 → listTools
      本地：命令白名单 → 起子进程 → 握手 → listTools
  → ToolRegistry(内置工具 + MCP 工具)               // 重名在这里炸
  → 对话
  → JVM shutdown hook / close()                      // 远程断连、本地销毁子进程
```

用 shutdown hook 兜底：正常退出、异常退出都要回收，避免留下孤儿进程或悬挂连接。

### 7. 工具来源写进 `ToolSpec.description`

`lytgos tool list` 展示的是 `ToolSpec`，所以在 MCP 工具的 description 前加 `[mcp:<server>]`，用户和模型都能看出这个能力来自哪个外部服务，不需要额外查询命令。

### 8. 新增 `08-mcp-acceptance.ps1`，与 07 分开

`07` 是第 1 周的验收，改动它会污染既有基线。MCP 路径单独成闸门：

| 路径 | 配置 | 断言 |
|---|---|---|
| A 内置 | `tools: [http_get]`，白名单含 `restapi.amap.com` | 日志出现 `tool_call: http_get`，输出含温度数值 |
| B 远程 MCP | `mcp_servers: [{url: 高德}]`，`tools: [maps_weather]` | 日志出现 `tool_call: maps_weather`，输出含天气描述 |
| C 本地 MCP | `mcp_servers: [{command: java, args: [FakeMcpServer]}]` | 日志出现 `tool_call: get_current_weather` |

三条路径都必须出现 `tool_call` 日志——这证明执行确实经过了 `ToolExecutor`，而不是别的什么通道。

路径 A 的天气请求走高德 Web 服务接口（显式给出带 key 的完整 URL），与路径 B 的远程 MCP 同一家供应商；路径 A 与路径 B 都需要 `AMAP_MCP_KEY`，缺失时记 SKIP。`FakeMcpServer` 继续用于路径 C 与单元测试：stdout/stdin 夹具是**离线单测的必需品**，与「不造假的 HTTP 服务端」不矛盾——区别在于它从不出现在需要证明「能连真实外部服务」的断言里。

## 实施记录：远程 HTTP 形态的两个坑

第一条真实链路（高德）从失败到跑通，暴露了两个共同特征为「看起来像鉴权问题」的坑，记在这里避免下次重踩。

### 坑一：SDK 会把地址里的 query 丢掉

**现象**：`tool list` 报 `MCP server amap 远程连接失败（url=https://mcp.amap.com/mcp?***）: Client failed to initialize by explicit API call`，而同一地址用 `curl` 手工发 `initialize` 完全正常。

**根因**：`HttpClientStreamableHttpTransport` 把配置的 baseUri 与 endpoint（默认 `/mcp`）交给 `URI.resolve`。按 RFC 3986，以 `/` 开头的引用会**替换 path 并丢弃 base 的 query**——于是请求变成 `https://mcp.amap.com/mcp`，key 没了。服务端对无 key 请求返回 HTTP 200 + `{"status":"0","info":"INVALID_USER_KEY"}`，SDK 把这段非 JSON-RPC 的 JSON 当响应体处理，`initialize` 等不到匹配 id 的结果而失败。

**修复**：`McpServerManager.splitRemoteUrl` 把配置地址拆成 `scheme://authority` 与 `path?query` 两段，query 跟着 endpoint 走，不再经过 base 解析；path 缺省时才交给 SDK 的默认 `/mcp`。

**回归测试**：`McpServerManagerTest.splitRemoteUrlKeepsQuery`（断言拆完拼回去与原地址逐字相等）、`splitRemoteUrlWithoutPath`。

### 坑二：SDK 把所有初始化异常包成一句话

**现象**：真正的失败原因（连接被拒、状态码、反序列化失败）在错误信息里完全看不到。

**根因**：`LifecycleInitializer.withInitialization` 用 `new RuntimeException("Client failed to initialize " + actionName, ex)` 包装所有异常，原因只存在于 `cause` 里。

**修复**：`McpServerManager.describe` 展开异常链拼成一行，并把原文 URL 替换成脱敏版本——cause 里可能带着完整地址，不替换就等于从错误路径泄露 key。

**回归测试**：`McpServerManagerTest.describeIncludesCauseAndRedactsUrl`。

## Risks / Trade-offs

| 风险 | 缓解 |
|---|---|
| 官方 SDK 传递引入 `reactor-core`，与 `architecture.md`「不引入 Reactor」的字面表述冲突 | 只用 `McpClient.sync()` 同步 API，业务代码不出现 `Mono` / `Flux`；该约束指编程模型，不含传递依赖。在 `architecture.md` 补一句说明 |
| 远程 MCP 服务不可用导致 Agent 加载失败 | 握手与请求设超时（10s / 30s），失败消息含 server 名与 URL；这是配置问题不是 bug，报清楚即可 |
| 远程 URL 里带 key，被误提交到仓库 | 示例统一写 `YOUR_KEY`；文档提示 key 属于环境信息，不应进版本库 |
| 用户误以为本地形态也受域名白名单保护 | 启动日志按形态分别说明边界；`tool list` 标注来源；`react-loop` 基线里写明差异 |
| MCP server 握手慢导致启动卡住 | 握手设超时（默认 10s），超时即失败并打印 server 名，不无限等 |
| 孤儿进程残留（仅本地形态） | shutdown hook + `close()` 双重回收；08 闸门增加「退出后无残留进程」断言 |

## Migration Plan

无破坏性变更：不配置 `mcpServers` 的 Agent 行为与第 1 周完全一致，内置工具路径不受影响。

已有 stdio 配置（`name` + `command` + `args` + `env`）继续有效，不需要迁移。

回滚方式：移除 `mcpServers` 配置即可，不需要回退代码。

## Open Questions

- **MCP 服务配置的归属**：当前写在 `AGENT.md` 的 `mcp_servers` 里（每个 Agent 各写一份）。Web 配置阶段需要「全局注册 MCP 服务、Agent 引用名字」，与 `providers` 的注册表模式对齐。级别：一般；影响：后续 Web 配置改造成本；默认倾向：Web 阶段引入工作区级 `mcp-servers` 注册表，Agent 侧保留名字引用。
- MCP server 的启动日志是否要单独落到 `.lytgos/logs/mcp-<server>.log`？——当前先混在主日志里，真需要排查时再拆
