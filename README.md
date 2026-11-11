<h1 align="center">lytgos</h1>

<p align="center">
  <strong>Java 原生的企业级 Agent OS 控制面</strong><br/>
  <em>框架解决 agent 怎么跑起来，lytgos 解决谁能跑、跑多少、花多少、出过什么事</em>
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache%202.0-blue?style=flat-square" alt="Apache 2.0"/></a>
  <a href="https://openjdk.org/projects/jdk/21/"><img src="https://img.shields.io/badge/Java-21-orange?style=flat-square" alt="Java 21"/></a>
  <a href="https://spring.io/projects/spring-boot"><img src="https://img.shields.io/badge/Spring%20Boot-4.x-6DB33F?style=flat-square" alt="Spring Boot 4.x"/></a>
  <a href="#quick-start"><img src="https://img.shields.io/badge/status-skeleton-yellow?style=flat-square" alt="status"/></a>
</p>

---

## What is lytgos

**lytgos 是一个 Java 原生、私有可部署、可审计的企业级 Agent OS 控制面。** 它不解决「agent 怎么跑起来」——LangChain4j、Spring AI、Embabel、AgentScope 已经够多。它解决这些框架不管的另一半：多个业务 Agent 装在同一套基础设施上时，谁能跑、跑多少、花多少、出过什么事。

企业落地 agent 通常这样开始：用框架跑通一个 demo，然后想接第二个业务、第二个团队。问题从这里出现——

- **治理挂不上执行路径。** 框架自己跑完整个工具调用循环，配额、白名单、沙箱、审计没有地方可插，只能在框架外面看日志、事后反解析。
- **审计永远是补丁。** 先跑通再补，补的时候发现日志里没有 token 数、没有耗时、没有 trace 关联，只能改架构重来。

lytgos 把这两件事放进内核：工具执行 100% 由自己发起，审计表从第一天就落库。

> **核心原则：应用可以自由组合能力，但不能绕过治理。** 每一次模型调用与工具执行，都走同一条带白名单、沙箱、审计的路径。

```text
用户消息 → 组装 Prompt → 调 LLM → 有 Tool 调用？
        → 沙箱白名单校验 → 执行 → 写 tool_invocations / llm_calls → 回灌继续
```

一个目录就是一个 Agent：`.lytgos/agents/<name>/AGENT.md` 的 frontmatter 声明用哪个模型、哪些 Tool、接哪个渠道，正文就是任务指令。装进来即受控。

## Architecture

```text
接入层    lytgos-cli     lytgos-web
          channel-cli    channel-feishu    channel-wecom    channel-dingtalk
              │
              ▼
内核      lytgos-core
          ReActLoop · PromptBuilder · ToolExecutor · AgentService · LytgosTool
              ▲
              │  接口定义于此，实现落在下游（依赖倒置）
能力层    lytgos-provider   lytgos-memory   lytgos-tool   lytgos-knowledge   lytgos-persona
              ▲
支撑层    lytgos-storage    lytgos-boot
```

`lytgos-core` 是所有跨模块接口的唯一归宿，本身不依赖任何内部模块；能力由下游模块实现。新增渠道或 Tool 只加新模块，内核不动。14 个 Maven 模块：

| 层 | 模块 | 职责 |
|---|---|---|
| 接入层 | `lytgos-cli` | 命令行入口，12 个子命令，`chat` / `serve` / `gateway` 三种运行模式 |
| 接入层 | `lytgos-web` | REST 服务，10 个端点，统一 `/api/v1` |
| 接入层 | `lytgos-channel-cli` | CLI 交互渠道 |
| 接入层 | `lytgos-channel-feishu` / `-wecom` / `-dingtalk` | 飞书 / 企微 / 钉钉长连接入站渠道 |
| 内核 | `lytgos-core` | Session、Profile、ContextLoader、ReActLoop、PromptBuilder、ToolExecutor、AgentService |
| 能力层 | `lytgos-provider` | Provider 显式映射，Agent 不感知模型厂商 |
| 能力层 | `lytgos-memory` | 长期记忆、会话历史、按需召回 |
| 能力层 | `lytgos-tool` | 内置 Tool、MCP Client、SandboxChecker |
| 能力层 | `lytgos-knowledge` | 知识库：解析、切分、向量化索引、双路召回 |
| 能力层 | `lytgos-persona` | 人格库：12 只内置预设 + 自定义 CRUD |
| 支撑层 | `lytgos-storage` | SQLite 持久化：Session、Tool 调用、LLM 调用三张表 |
| 支撑层 | `lytgos-boot` | Spring Boot 启动装配与依赖聚合 |

## Core capabilities

| 能力 | 提供什么 |
|---|---|
| **对接 LLM** | 统一 Provider 抽象，`provider name → ChatModel` 是启动时构造的显式 Map，来自配置文件；禁止类型扫描，多引一个 starter 不会凭空多出一个 provider。 |
| **ReAct 循环** | 完全自实现：只调 `ChatModel.call()` 拿模型原样响应，工具调用由 `ToolExecutor` 自己发起，Spring AI 仅做协议转换与 schema 生成。 |
| **Memory** | 三层记忆：长期记忆 `MEMORY.md` 跨对话留存偏好，会话历史按轮数截断注入，`save_memory` / `recall_memory` 由 Agent 自主存取。 |
| **Plugin Tool** | 9 个内置 Tool（文件 / Shell / HTTP / 记忆 / 通知）+ MCP Client + 三档扩展，统一 `LytgosTool` 接口，全部调用经 `SandboxChecker`。 |
| **Web Service** | 10 个 REST 端点覆盖会话、Agent 调用、记忆、工具与运行信息，同步阻塞 + virtual thread，是业务系统集成的唯一通道。 |

工具扩展分三档，按门槛递增：写 SKILL.md 复用社区 MCP server（零代码）→ 任意语言实现 MCP server（轻代码）→ Java `@Tool` Bean（重代码）。

## Tech stack

| 层 | 选型 |
|---|---|
| 语言 / 运行时 | Java 21（virtual thread 处理并发） |
| 框架 | Spring Boot 4.x |
| LLM 调用 | Spring AI Alibaba —— 只用协议转换与 `@Tool` schema 生成，禁用自动工具执行 |
| HTTP 服务 | Spring MVC + Java 21 virtual thread |
| 命令行 | Picocli |
| 持久化 | SQLite + Spring Data JPA，审计表 day one 写入 |
| MCP | MCP Java SDK |
| 日志 / 指标 | Logback + SLF4J / Micrometer + Prometheus（扩展阶段） |
| 构建与部署 | Maven 多模块，单进程 fat jar，`java -jar` 启动 |

## Quick start

> **当前是骨架阶段：设计已锁定，代码为零。** 先规格后代码是刻意选择——接口、表结构、验收标准先写清楚，再落实现。

```bash
git clone https://github.com/luyitiger6/lytgos.git
cd lytgos

# 六道质量闸门：1 通过 + 5 未就绪，退出码 1 是预期结果
powershell -File harness/run-all.ps1
```

模块落地后：

```bash
mvn clean package
java -jar lytgos-boot/target/lytgos.jar
```

## Documentation

| 文档 | 回答什么问题 |
|---|---|
| [architecture.md](docs/design/architecture.md) | 目标形态：14 个模块、8 条不可违背原则、工作区结构、数据模型 |
| [DemandAnalysis.md](docs/design/DemandAnalysis.md) | What：五大能力、三档功能分级、验收标准 |
| [TechnicalSolution.md](docs/design/TechnicalSolution.md) | How：7 个关键决策、四层架构、实施路径 |
| [AiProgrammingGuide.md](docs/design/AiProgrammingGuide.md) | 工程约束：怎么用 AI 按规格推进 |

## License

[Apache License 2.0](LICENSE)
