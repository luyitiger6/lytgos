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
| `SessionStore` | Session 存取，第 1 周内存实现 | `getOrCreate(key)`、`save(session)` |
| `ReActLoop` | 循环本体 | `run(Session, String) → String` |
| `PromptBuilder` | 把 Profile / 历史 / 工具 schema 拼成 prompt | `build(...)` |
| `ToolExecutor` | 工具执行的唯一入口 | `execute(ToolCall) → ToolResult` |
| `SandboxChecker` | 三处校验：路径、命令、域名 | `checkPath` / `checkCommand` / `checkDomain` |

`core` 不依赖 Spring AI，只定义自己的 `ChatModel` 抽象接口（`String call(String prompt)`）。这样 `core` 的依赖方向干净，04 号闸门好写规则。

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

`ToolRegistry` 持有 `Map<String, Tool>`，`Tool` 三个方法：`name()`、`schema()`（JSON Schema 字符串，交给 LLM）、`execute(Map<String,Object>)`。

第 1 周只注册一个 `http_get`。`HttpTool` 执行前必须调 `SandboxChecker.checkDomain()`，白名单为空时**全部拒绝**——默认拒绝而不是默认放行。

### `lytgos-channel`

`CliChannel` 两个职责：交互循环（读一行 → 调 `ReActLoop` → 打印）和 `--message` 单条模式。不做流式输出，第 1 周直接等完整响应。

## 已定口径

| 事项 | 结论 |
|---|---|
| 首个 Provider | DeepSeek，模型 `deepseek-chat` |
| API key 存放 | 环境变量 `DEEPSEEK_API_KEY`；`.lytgos/` 与 `.env` 已在 `.gitignore`，不入库 |
| 工作区条目 | 9 项 = 6 个目录 + 3 个 Bootstrap 文件 |
| YAML 解析 | SnakeYAML |
| 工具演示场景 | 查 GitHub 仓库信息（`api.github.com`），**不做天气查询** |

## 待决项

| 问题 | 级别 | 影响任务 | 默认倾向 |
|---|---|---|---|
| 单条模式与交互模式是否共用同一 Session | 一般 | 6 | 共用；`--message` 只影响是否进入交互循环 |

级别为「阻塞」的条目必须在开工前答复；剩余的「一般」条目按默认倾向推进，实施到对应任务时如与预期不符再停。

## 不做什么

- 不做流式输出（第 3 周 Web Service 也不做 SSE）
- 不做 Tool 调用并行
- 不做上下文压缩，超长直接截断早期轮次
- 不做 fallback / hedge racing，provider 故障直接报错
- 不碰 `lytgos-memory`、`lytgos-knowledge`、`lytgos-web`、`lytgos-storage`

## 验收脚本

`harness/checks/07-week1-acceptance.ps1` 覆盖六条：

1. `lytgos init` 在临时目录建出 9 项，退出码 0
2. 重复 `init` 不覆盖已有 `AGENTS.md`（改一行再跑，内容不变）
3. `lytgos chat --message "查一下 luyitiger6/lytgos 这个仓库"` 输出含 star 数或主语言，日志出现 `tool_call: http_get`
4. 无 API key 时启动失败，错误消息含 `DEEPSEEK_API_KEY` 字样
5. HTTP Tool 访问非白名单域名被拒，错误消息含 `sandbox` 字样
6. 两轮对话第二轮能引用第一轮内容

其中 4、5 是反例，必须先通过——**默认拒绝比默认放行重要**。
