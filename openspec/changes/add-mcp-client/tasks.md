# Tasks

## 1. 前置验证

- [x] 1.1 确认 MCP SDK 与可用服务
  - `lytgos-tool/pom.xml` 引入 `io.modelcontextprotocol.sdk:mcp:2.1.0`
  - 客户端三种传输都在 SDK 内（`StdioClientTransport` / `HttpClientStreamableHttpTransport` / `HttpClientSseClientTransport`），运行时零平台依赖
  - 用于验收的真实在线服务：高德官方 MCP `https://mcp.amap.com/mcp`，`initialize` / `tools/list`（15 个工具，含 `maps_weather`）/ `tools/call`（北京 4 天预报）均已实测通过

- [x] 1.2 引入 MCP SDK 并跑通最小探针
  - 探针没有单独建类：`McpServerManagerTest` 直接用它拉起本地夹具 server，握手 + `tools/list` 全跑通
  - **验收**：`mvn -pl lytgos-tool -am test` 通过

- [x] 1.3 本地形态夹具 `FakeMcpServer`
  - 纯 JDK，只实现 stdio 协议够用的一段，不联网、输出固定，暴露 `get_current_weather` 与 `get_forecast`
  - **用途限定**：只服务离线单元测试与本地形态闸门，不用于证明「能连真实外部服务」——那条由高德在线服务证明

## 2. 配置双形态

- [x] 2.1 `Profile.McpServer` 加 `url` 字段与二选一校验
  - 字段变为 `(name, url, command, args, env)`
  - 紧凑构造器校验：`url` 与 `command` 必须且只能有一个，错误消息说明规则
  - **验收**：新建 `ProfileTest`，覆盖「两者都空 / 两者都有 / 只有 url / 只有 command」四种输入 —— 6 个用例全绿

- [x] 2.2 `AgentLoader` 解析 `url`
  - `asMcpServers` 读取 `url` 字段并传入 `Profile.McpServer`
  - **验收**：`AgentLoaderTest` 增加一条「mcp_servers 里带 url 的 Agent 能加载成功，且 url 原样保留」——`tool list -a amap` 实测 url 原样传到连接层（日志脱敏后仍可看出 path 与 query 存在）

## 3. 远程形态实现

- [x] 3.1 `McpServerManager` 分远程 / 本地两条启动路径
  - `url` 非空 → 远程路径；否则走现有 stdio 路径
  - 共用同一套握手超时、请求超时、`listAllTools` 分页、失败即整体失败逻辑
  - **不能直接 `builder(url)`**：SDK 的 endpoint 解析会丢弃 base 的 query（详见 design.md 坑一），必须先经 `splitRemoteUrl` 拆成 base + `path?query`
  - **验收**：远程连接失败时错误消息含 server 名、脱敏 URL 与 cause 链

- [x] 3.2 远程地址在建立连接前过域名白名单
  - 顺序写死：`sandbox.checkDomain(url)` → 建连接 → 握手
  - 校验抛出的 `SandboxViolation` 包装为 `InvalidConfiguration`，消息保留 `sandbox` 字样与原始原因
  - **验收**：`McpServerManagerTest.rejectsRemoteUrlOutsideWhitelist` —— 白名单内不含该域名时启动失败、消息含 `sandbox` 与域名，且不产生任何连接

- [x] 3.3 启动日志按形态说明边界
  - 远程：`MCP server <name> 已就绪: url=... 工具 N 个`，并说明连接地址已通过域名白名单校验
  - 本地：保留现有「域名白名单不覆盖该 server」提示
  - **验收**：`tool list -a amap` 实测出现「地址已通过域名白名单校验」

## 4. 真实链路验证

- [x] 4.1 用高德在线 MCP 跑通一次完整工具调用
  - 临时工作区：`allowed-domains` 含 `mcp.amap.com`，Agent 的 `mcp_servers` 配 `url: https://mcp.amap.com/mcp?key=...`，`tools: [maps_weather]`
  - **已跑通**：`tool list -a amap` → 15 个工具、`maps_weather` 带 `[mcp:amap]` 来源、日志 `url=https://mcp.amap.com/mcp?***`（脱敏生效）、EXIT=0
  - **已跑通**：`maps_weather(city=北京)` 真实返回北京市 2026-11-13 至 11-16 四天预报，耗时 330ms
  - 完整对话链（模型选工具 → `ToolExecutor` 执行）需 `DEEPSEEK_API_KEY`，本机未设置，留给 6.1 的闸门按三态记 SKIP
  - **key 不进仓库**：验证用的工作区在系统临时目录，仓库内的示例统一写 `YOUR_KEY`

## 5. 装配与配置模板

- [ ] 5.1 `lytgos init` 的 `config.yaml` 模板补远程 MCP 说明
  - `allowed-domains` 示例中加入 `mcp.amap.com`
  - 注释补一句：远程 MCP 的地址必须在白名单里才连得上
  - **验收**：`08-mcp-acceptance` 从 `init` 产物改配置后沙箱断言通过；`07-week1-acceptance` 第 1 条（10 个顶层条目）仍通过

- [ ] 5.2 README 补远程 MCP 配置与两种形态的边界
  - 「接入 MCP server」小节改为双形态：远程 URL 示例（推荐）+ 本地 command 示例
  - 写清远程形态 URL 域名必须在 `allowed-domains` 里；本地形态命令必须在 `allowed-commands` 里
  - **验收**：字段名逐个对照 `Profile.McpServer` 与 `AgentLoader` 核对一致

## 6. 验收闸门

- [x] 6.1 `08-mcp-acceptance.ps1` 增加远程路径
  - 已扩为三条路径：A 内置 `http_get` / B 远程高德 `maps_weather` / C 本地 stdio 夹具
  - 断言：stderr 含 `tool_call: <工具名>`、stdout 含真实数据、未越权调用 `http_get`
  - 边界提示按形态分：远程断言「地址已通过域名白名单校验」，本地断言「域名白名单不覆盖该 server」
  - 沙箱两条都不需要 key：命令白名单为空 → 本地起不来；域名白名单缺 `mcp.amap.com` → 远程连不上，都要含 `sandbox`
  - **实测**（仅有 `AMAP_MCP_KEY`）：4 / 5 / 6a / 6b / 7 全 PASS，1 / 2 / 3 记 SKIP，退出码 2；无 key 时同样退出码 2，无 FAIL

- [x] 6.2 同步闸门清单
  - 闸门文件数量未变，`AGENTS.md` 的 `harness-checks` 标记行已含 00–08 九道；`harness/README.md` 的 08 描述改为三路径与两个 key
  - **验收**：`powershell -File harness\checks\00-layout-check.ps1` 退出码 0 —— 已跑，输出「骨架完整：8 项；闸门清单一致：9 道」

## 7. 实施中发现的既有缺陷

- [x] 7.1 参数错误与沙箱违规分家
  - `LytgosException` 新增 `InvalidToolArgument`；`SandboxChecker.checkDomain` 的格式非法 / URL 为空 / 缺 host、`HttpTool` 缺参都改抛它；协议限制（如 `file://`）仍是 `SandboxViolation`
  - `ToolExecutor` 不加分支——新类型自然落入 `catch (RuntimeException)` 回填路径
  - **验收**：`SandboxCheckerTest` 断言格式错抛的是 `InvalidToolArgument`；`ToolExecutorTest` 5 个用例断言参数错 / 运行期错 / JSON 错都回填、只有沙箱违规向外抛

- [x] 7.2 `SandboxChecker.checkCommand` 把带空格的路径截成 `Program`
  - **现象**：08 闸门报 `sandbox 拒绝：命令 Program 不在白名单 [java] 内`，而 `allowed-commands` 里明明写了 `java`
  - **根因**：旧实现一律 `split("\\s+", 2)[0]`，`C:\Program Files\...\java.exe` 被切成 `C:\Program`
  - **修复**：先按整串取文件名，命中白名单就用整串；否则才按第一个空格切。引号包裹时取引号内路径
  - **回归测试**：`SandboxCheckerTest.acceptsCommandPathWithSpaces`（四种写法），原 `checksCommandWhitelist` 保持不变以锁住第 1 周的内联参数行为

- [x] 7.3 08 闸门的 stderr 被 PowerShell 硬折行
  - **现象**：日志里明明有「地址已通过域名白名单校验」，断言仍 FAIL
  - **根因**：`& java ... 2> file` 会把原生命令的 stderr 包成 ErrorRecord 并按控制台宽度折行写入，长消息被拆成两行
  - **修复**：`Invoke-Lytgos` 改用 `ProcessStartInfo` 直接重定向（UTF-8），拿原始 stderr；顺带消除了满屏的红色错误噪音

## 8. 文档修正

- [x] 8.1 `docs/design/architecture.md` 补 Reactor 说明
  - 「原则七」补一段：约束的是业务代码的编程模型，不排斥 SDK 的传递依赖；只用 `McpClient.sync()`，业务代码不出现 `Mono` / `Flux`
  - **验收**：表述与实际依赖一致

## Workflow follow-up

- 合并 PR 后在远程 main 上打 tag `v0.2.0`
- 归档本 change，把 `specs/mcp-client` 落为主 spec，并把 `react-loop` 的 MODIFIED 合并进基线
