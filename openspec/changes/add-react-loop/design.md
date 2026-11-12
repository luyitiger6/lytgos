# 设计：add-react-loop

## 目标

跑通一条最小链路，并让这条链路上的每个治理点都有位置可挂。

```
CliChannel.receive(text)
  └─ Session 取出/创建历史
     └─ ReActLoop.run(session, text)
        ├─ PromptBuilder.build(system prompt + history + tool schema)
        ├─ ChatModel.call(prompt)                    ← 唯一 LLM 出口
        ├─ 无 tool_call → 返回文本
        └─ 有 tool_call → ToolExecutor.execute()      ← 唯一工具出口
             └─ SandboxChecker.check()                ← 拦截点
                └─ 结果回填历史，继续循环
```

三个「唯一出口」是这次设计的核心：**LLM 只有一个出口、工具只有一个出口、沙箱挂在工具出口前面**。第 4 周要加的审计，就挂在这两个出口上，不需要回头改流程。

## 接口边界

### `lytgos-core`

| 类型 | 职责 | 关键方法 |
|---|---|---|
| `Profile` | 一个 Agent 的配置快照，不可变 | — |
| `Session` | 一次对话的上下文容器 | `append(Message)`、`history()`、`id()` |
| `SessionStore` | Session 存取，第 1 周文件实现 | `get(key)`、`save(session)` |
| `ReActLoop` | 循环本体 | `run(Session, String) → String` |
| `PromptBuilder` | 把 Profile / 历史 / 工具 schema 拼成 prompt | `build(...)` |
| `ToolExecutor` | 工具执行的唯一入口 | `execute(ToolCall) → String` |
| `SandboxChecker` | 三处校验：路径、命令、域名 | `checkPath` / `checkCommand` / `checkDomain` |

`core` 不依赖 Spring AI，只定义自己的 `ChatModel` 抽象接口（`ChatResult call(ChatRequest)`）。这样 `core` 的依赖方向干净，04 号闸门好写规则。

### `lytgos-provider`

`ProviderRegistry` 持有 `Map<String, ChatModel>`，启动时从配置构造：

```yaml
lytgos:
  providers:
    deepseek:
      model: deepseek-chat
      api-key: ${DEEPSEEK_API_KEY}
      base-url: https://api.deepseek.com
```

构造逻辑：遍历配置节 → 用 Spring AI 的 OpenAI 兼容 connector 构造 `ChatModel` → 放进不可变 Map。找不到时抛 `UnknownProviderException`，消息里列出已注册的名字。

**没有类型扫描，没有 `@ConditionalOnBean`，没有 `ObjectProvider`。**

### `lytgos-persona`

`AgentLoader.load(Path agentDir) → Profile`：读 `AGENT.md`，切 `---` 分隔的 frontmatter 与正文，解析 YAML，映射到 `Profile`。frontmatter 缺 `provider.name` 或 `provider.model` 时抛异常，消息指明缺哪个字段。

### `lytgos-tool`

`ToolRegistry` 持有 `Map<String, Tool>`，`Tool` 两个方法：`spec()`（返回 `ToolSpec`，含名称、说明、JSON Schema）、`execute(Map<String,Object>)`。

第 1 周注册两个工具：`http_get` 与只读的 `file_read`。

`HttpTool` 执行前必须调 `SandboxChecker.checkDomain()`，白名单只含两个域名：`geocoding-api.open-meteo.com`、`api.open-meteo.com`。白名单为空时**全部拒绝**——默认拒绝而不是默认放行。

加 `FileTool` 而不是只留 `http_get` 的理由有两个：一是让 `SandboxChecker.checkPath()` 有真实调用点（路径校验写在服务里但没人调用，等于没写）；二是 `AGENTS.md` 4.3 本就点名 `FileTool` 必须过沙箱。本周只做只读：写文件要等 memory 的写入策略定稿——「什么时候允许 Agent 改自己的 AGENT.md」是治理问题，不是 IO 问题。

### `lytgos-channel`

`CliChannel` 两个职责：交互循环（读一行 → 调 `ReActLoop` → 打印）和 `--message` 单条模式。不做流式输出，第 1 周直接等完整响应。

## 已定口径

| 事项 | 结论 |
|---|---|
| 首个 Provider | DeepSeek，模型 `deepseek-chat` |
| API key 存放 | 环境变量 `DEEPSEEK_API_KEY`；`.lytgos/` 与 `.env` 已在 `.gitignore`，不入库 |
| 工作区条目 | 10 个顶层条目 = 6 个目录 + 3 个 Bootstrap 文件 + `config.yaml`（递归 12 项，含 `memory/MEMORY.md` 与 `agents/default/AGENT.md`） |
| YAML 解析 | SnakeYAML |
| 工具演示场景 | 查天气，走 Open-Meteo 两个公开接口：`geocoding-api.open-meteo.com`（查经纬度）+ `api.open-meteo.com`（查天气），均无需 API key；一次对话触发两次工具调用，正好验证 ReAct 往返 |
| Session 存储 | 方案 A：内存 + JSON 落盘到 `.lytgos/sessions/<key>.json`；`--message` 与交互模式共用同一 Session |

以上口径全部落定，无待决项。

## 不做什么

- 不做流式输出（第 3 周 Web Service 也不做 SSE）
- 不做 Tool 调用并行
- 不做上下文压缩，超长直接截断早期轮次
- 不做 fallback / hedge racing，provider 故障直接报错
- 不碰 `lytgos-memory`、`lytgos-knowledge`、`lytgos-web`、`lytgos-storage`

## 验收脚本

`harness/checks/07-week1-acceptance.ps1` 覆盖六条：

1. `lytgos init` 在临时目录建出 10 个顶层条目，退出码 0
2. 重复 `init` 不覆盖已有 `AGENTS.md`（改一行再跑，内容不变）
3. `lytgos chat --message "北京今天天气怎么样"` 输出含温度数值，日志出现两次 `tool_call: http_get`
4. 无 API key 时启动失败，错误消息含 `DEEPSEEK_API_KEY` 字样
5. HTTP Tool 访问非白名单域名：越界请求未发出（模型调用则被 `sandbox` 拦下，模型预判则不发起调用），且用户被告知原因
6. 两轮对话第二轮能引用第一轮内容

其中 4、5 是反例，必须先通过——**默认拒绝比默认放行重要**。

## 第 2 周 MCP 衔接

第 1 周用内置 `http_get` 打通链路，第 2 周把同一个天气场景换成走 MCP，用于验证 MCP 客户端，并形成「同一能力两条路径」的对照。

| 事项 | 结论 |
|---|---|
| MCP server | `gbrigandi/mcp-server-openmeteo`（Rust，Windows 有预编译 exe，stdio 传输） |
| 底层数据 | Open-Meteo，免费、无需 API key |
| 工具数 | 4 个：`get_current_weather`、`get_weather_forecast`、`get_historical_weather`、`search_locations` |
| 备选 | `cmer81/open-meteo-mcp`（17 个工具，但需 Node 运行时，依赖链更长） |

本 change 不实现 MCP，仅登记选型，避免第 2 周重新讨论。

## 实施记录

以下为落地时与初版设计的实际差异，按 `AGENTS.md` 第 2 条登记理由，不悄悄改口径。

| 项 | 初版 | 实际 | 理由 |
|---|---|---|---|
| 工作区顶层条目 | 9 | 10 | 漏了 `config.yaml`，而 provider 映射必须从配置文件读；无此文件则 `lytgos.providers` 无载体 |
| 装配位置（任务 4.4） | `lytgos-boot` | `lytgos-cli` 的 `CliContext` | 第 1 周入口是 CLI，不走 Spring 容器；第 3 周 Web 接入时再上提到 boot |
| 工具 schema 的承载 | 拼进 prompt | `ChatRequest.tools` 单独承载 | Spring AI 的 `ChatModel` 接口要求把工具 schema 作为请求字段传，不能拼成文本；拼进去模型看不到结构化定义 |
| 内置工具数 | 1（`http_get`） | 2（+ `file_read`） | 让 `checkPath()` 有真实调用点；也是 `AGENTS.md` 4.3 点名的必过沙箱工具 |
| 日志 | 未定 | SLF4J 门面 + logback，输出到 stderr | `core` 只依赖 `slf4j-api`，不碰 Spring AI；stdout 留给命令的结构化输出 |
| 单测 | 未列 | 41 个（core 24 / tool 11 / persona 6） | 每条任务的「验收」都是可执行判定，单测是其中可自动化的那部分 |
| `03` 闸门的例外 | 无 | `lytgos-cli` / `lytgos-channel` 豁免「禁 `System.out`」 | CLI 的 stdout 就是产品输出通道，不是拿 println 顶替日志 |
| `04` 闸门 | 1 条规则 + 空壳 | 6 条规则 | 依赖方向、禁 `ChatClient` / `ToolCallingManager` / 三种类型扫描、`core` 不依赖 Spring、`core` 不 import 其他模块 |
| 演示场景 | 查 GitHub（`340747b`） | 查天气（Open-Meteo） | 有意推翻：第 2 周 MCP 需要同能力双路径对照；GitHub 未认证限 60 次/小时会干扰诊断。理由见 `proposal.md` |
| 工具描述 | 只写「只能访问白名单域名」 | 直接列出具体域名 | 实测问天气时模型猜了 `wttr.in`，被拒后整轮中止。模型不知道白名单就只能猜 |
| `07` 第 5 条判据 | 输出必须含 `sandbox 拒绝` | 看「越界请求是否真的发出」 | 白名单写进描述后模型提前拒绝而不调工具，沙箱未触发但同样满足 spec 的「请求未发出」 |
| URI 解析失败的消息 | 「URL 无法解析」 | 「URL 格式非法（`%` 要写成 `%25`）」 | 模型拼出的 `?format=%l:+%c` 不是合法转义；旧消息看起来像策略拦截，会引导调用方去换域名重试 |
| `07` 第 5 条能验到多远 | 假定「模型会去调工具→被拦」 | 只验「越界请求未发出」 | 实测 DeepSeek 会提前识破提示注入与域名伪装（`api.open-meteo.com.evil.com`），**端到端从未真正执行到 `checkDomain()` 的拒绝分支**。沙箱正确性由 `SandboxCheckerTest` 11 条 + `HttpToolTest` 6 条保证；端到端只保证「结果上没有越界请求发出去」。真正会触发拦截的是：换弱模型、工具返回内容里的注入、未来 MCP 工具与非模型调用方 |

**验收状态**：`07-week1-acceptance.ps1` 六条全 PASS（退出码 0）。`harness\run-all.ps1` 整体仍返回 1，因为 `05-audit-invariant` 未接入（storage 未落地，属第 4 周范围）。

