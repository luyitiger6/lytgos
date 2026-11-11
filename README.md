<div align="center">

# lytgos

**企业级 Agent OS 控制面 · Java 原生 · 私有可审计**

框架解决 agent 怎么跑起来。<br>
lytgos 解决框架不管的另一半——谁能跑、跑多少、花多少、出过什么事。

[![License: Apache-2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.x-6DB33F.svg)](https://spring.io/projects/spring-boot)
[![Status](https://img.shields.io/badge/status-skeleton-yellow.svg)](#快速开始)

[为什么](#为什么) · [核心能力](#核心能力) · [架构](#架构) · [快速开始](#快速开始) · [文档](#文档) · [许可](#许可)

</div>

## 为什么

每个想在企业里落地 agent 的团队，都会撞上同样两个问题：

**① 治理挂不上执行路径。** 框架自己跑完整个工具调用循环，配额、白名单、沙箱、审计没有地方可插——只能在框架外面看日志，事后反解析。

**② 审计永远是补丁。** 先跑通再补，补的时候发现日志里没有 token 数、没有耗时、没有 trace 关联，只能改架构重来。

lytgos 把这两件事放进内核：工具执行 100% 由自己发起，审计表从第一天就落库。

| 常见做法 | lytgos |
|---|---|
| 框架自带工具执行循环，治理只能包在外面 | 只调 `ChatModel.call()`，工具执行自己发起 |
| 审计靠日志反解析，事后补 | `tool_invocations` / `llm_calls` day one 落库 |
| 多 Provider 靠类型扫描，多引一个 starter 就多一个 | 启动时构造显式 Map，配置驱动不退化成 classpath 驱动 |
| 沙箱靠 `contains("..")` 朴素判断 | `toRealPath()` 后比较，拒绝绝对路径与越界软连接 |

## 核心能力

### 对接 LLM（Provider 抽象）

Agent 不感知具体调哪家。基于 Spring AI Alibaba 复用 DeepSeek、通义、Kimi、智谱等主流 connector，`provider name → ChatModel` 是一张启动时构造的显式 Map——配置驱动，不退化成 classpath 驱动。

```yaml
provider:
  name: deepseek
  model: deepseek-chat
  api_key: ${DEEPSEEK_API_KEY}   # 从环境变量读取，不明文写死
```

### ReAct 循环

Agent 核心循环完全自实现：只调 `ChatModel.call()` 拿模型原样响应，工具调用由 `ToolExecutor` 自己发起——因为配额、白名单、沙箱、审计只能挂在执行路径上。

```text
用户消息 → 组装 Prompt → 调 LLM → 有 Tool 调用？→ 白名单校验 → 执行 → 写审计表 → 回灌继续（最多 max_iterations 轮）
```

### Memory 三层记忆

- 长期记忆：`MEMORY.md`，跨对话记住用户偏好
- 会话历史：`sessions` 表，按 `max_history_turns` 截断注入
- 按需召回：`save_memory` / `recall_memory` 两个 Tool 由 Agent 自己决定存取

### Plugin Tool

9 个内置 Tool 覆盖文件 / Shell / HTTP / 记忆 / 通知，统一 `LytgosTool` 接口。扩展分三档：

| 档位 | 方式 | 门槛 |
|---|---|---|
| 零代码 | 写 SKILL.md，复用社区 MCP server | 最低 |
| 轻代码 | 任意语言写 MCP server | 中 |
| 重代码 | Java `@Tool` 注解 Spring Bean | 高 |

所有调用经过 `SandboxChecker`：路径 `toRealPath()` 后比对、Shell 命令白名单、HTTP 域名白名单。

### Web Service

10 个 REST 端点，统一 `/api/v1`，同步阻塞 + Java 21 virtual thread：

```text
POST /sessions · POST /sessions/{id}/messages · GET /sessions/{id} · DELETE /sessions/{id}
POST /agents/{name}/invoke · GET /profiles · GET /memory · GET /tools · GET /health · GET /info
```

## 架构

```text
接入层    lytgos-cli   lytgos-web
          channel-cli  channel-feishu  channel-wecom  channel-dingtalk
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

14 个 Maven 模块。`lytgos-core` 不依赖任何内部模块；新增 Channel 或 Tool 只加新模块，不改内核。

## 快速开始

> **当前是骨架阶段：设计已锁定，代码为零。** 先规格后代码是刻意选择——每个模块的接口、表结构、验收标准先写清楚，再落实现。

```powershell
git clone https://github.com/luyitiger6/lytgos.git
cd lytgos

# 六道质量闸门：当前预期 1 通过 + 5 未就绪（退出码 1）
powershell -File harness\run-all.ps1
```

## 文档

| 文档 | 回答什么问题 |
|---|---|
| [architecture.md](docs/design/architecture.md) | 目标形态：14 个模块、8 条不可违背原则、工作区结构、数据模型 |
| [DemandAnalysis.md](docs/design/DemandAnalysis.md) | What：五大能力、三档功能分级、验收标准 |
| [TechnicalSolution.md](docs/design/TechnicalSolution.md) | How：7 个关键决策、四层架构、实施路径 |
| [AiProgrammingGuide.md](docs/design/AiProgrammingGuide.md) | 工程约束：怎么用 AI 按规格推进 |

## 许可

[Apache License 2.0](LICENSE)
