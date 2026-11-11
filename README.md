<h1 align="center">lytgos</h1>

<p align="center">
  <strong>Java 原生的企业级 Agent OS 控制面</strong><br/>
  <em>让每一个 Agent 都在治理之内运行</em>
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

- **治理挂不上执行路径。** 框架自己完成整个推理与工具调用过程，配额、白名单、沙箱、审计没有地方可插，只能在框架外面看日志。
- **审计永远是补丁。** 先跑通再补，补的时候发现记录里缺了用量、耗时与调用链路，只能改架构重来。

lytgos 把这两件事放进内核：推理与工具执行由自己掌控，审计记录从第一天就写入。

> **核心原则：应用可以自由组合能力，但不能绕过治理。** 每一次模型调用与工具执行，都走同一条带白名单、沙箱与审计的路径。

一个目录就是一个 Agent：声明式配置模型、工具与渠道，正文即任务指令。装进来即受控。

## Architecture

![模块依赖](docs/architecture/module-dependencies.svg)

所有模块单向依赖 `lytgos-core`，`lytgos-core` 不依赖任何同项目模块——跨模块契约定义在这里，实现落在下游。新增渠道或能力只加模块，内核不动。`lytgos-boot` 只负责装配，产物是单个可执行 jar。

## Core capabilities

| 能力 | 提供什么 |
|---|---|
| **Agent 运行时** | 一个目录定义一个 Agent，声明模型、工具与渠道；多个 Agent 在同一实例并存，互不干扰。 |
| **模型接入** | 统一的模型抽象，新增或切换模型只改配置，Agent 无需改动；配置驱动，不随依赖变化。 |
| **记忆与上下文** | 长期记忆跨对话留存偏好，会话历史按轮数管理，Agent 自主决定何时存、何时取。 |
| **工具与扩展** | 内置文件、命令、网络、记忆、通知能力；可接入 MCP 生态；三档扩展门槛按需选择。 |
| **多渠道接入** | 命令行、REST API、飞书、企微、钉钉；同一份配置，任意渠道可用。 |
| **治理与审计** | 沙箱白名单覆盖全部工具调用，每次模型调用与工具执行完整留痕；治理在执行路径上，不是事后补日志。 |

## Tech stack

| 层 | 选型 |
|---|---|
| 语言 / 运行时 | Java 21 |
| 框架 | Spring Boot 4.x |
| 模型接入 | Spring AI Alibaba |
| HTTP 服务 | Spring MVC + Java 21 virtual thread |
| 命令行 | Picocli |
| 持久化 | SQLite + Spring Data JPA |
| MCP | MCP Java SDK |
| 构建与部署 | Maven 多模块，单进程 fat jar |

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

## License

[Apache License 2.0](LICENSE)
