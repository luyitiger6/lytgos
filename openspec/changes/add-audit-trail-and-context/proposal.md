# Proposal

## Why

`AGENTS.md` 第 3 条硬规则写明「审计优先于功能：`tool_invocations` 与 `llm_calls` 的写入不允许为了先跑通而注释掉或后置」，但当前仓库里审计链路完全不存在：`lytgos-storage` 只有一个 `package-info.java`，全仓没有 JDBC/SQLite 依赖，模型调用与工具执行只写 slf4j 日志，05 号闸门因「无判定对象」停在 NOT_READY。这是产品卖点与仓库红线之间的直接冲突，必须最先补上。

同一批对账还暴露出提示词与 Profile 的三处行为缺口：系统提示词没有当前日期（模型答时间类问题只能靠猜）、`Profile.skills` 字段不存在（技能无从注入）、Bootstrap/记忆只在启动时读一次（用户改了文件必须重启才生效）。

## What Changes

- **新增审计能力**：SQLite 三张表（`llm_calls` / `tool_invocations` / `sessions`）、随包 schema 脚本、启动幂等建表；每次模型调用与每次工具执行都落一行，成功、失败、超时、沙箱拒绝都算；写入失败不静默丢弃。
- **审计入口采用装饰器**：在 `ChatModel` 外包一层 `AuditedChatModel`，`ProviderRegistry` 按 provider 装配；不新增 `ProviderService` 类，也不改 `ChatModel.call()` 的语义。
- **契约扩展**：`ChatRequest` 增加 `sessionId`、`turnId`、`temperature`；`ChatResult` 增加 token 用量；`Profile.ProviderRef` 增加 `temperature`，`Profile` 增加 `skills`，两者保持 `AGENT.md` frontmatter 形态。
- **提示词组装补齐**：每次请求重新读取 Skill / Bootstrap / 记忆（不缓存）；系统提示词末尾注入当前日期；声明了但缺失的 `SKILL.md` 直接报错，Bootstrap 缺失只 WARN。
- **会话改存 SQLite**：`sessions` 表持久化，`SessionStore` 接口不变；旧的 `sessions/*.json` 不迁移也不删除。
- **规格债清理**：拆分 `react-loop`「工具执行与沙箱」超长描述（当前 `validate --strict` 失败项），把散落段落移入场景。
- **05 号闸门落地**：实现 I1/I2/I3 三条审计不变量断言类，闸门改为真实校验「断言类存在且被真实执行」。

## Capabilities

### New Capabilities

- `audit-trail`: 模型调用与工具执行的持久审计——表结构、写入时机、失败策略、按 session/turn 关联。

### Modified Capabilities

- `react-loop`: Agent 定义新增 `provider.temperature` 与 `skills` 字段；新增「系统提示词组装」需求（顺序、日期、技能、不缓存）；「会话上下文」改为 SQLite 持久化；「工具执行与沙箱」描述收敛为单行为并补场景。

## Impact

- 代码：`lytgos-core`（chat 契约、audit 端口、PromptBuilder、ReActLoop、ToolExecutor）、`lytgos-provider`（装饰器装配、usage 映射、temperature）、`lytgos-storage`（新实现）、`lytgos-persona`（字段解析）、`lytgos-cli`（会话存储与审计装配）、`lytgos-test`（架构与不变量测试）。
- 依赖：新增 `org.xerial:sqlite-jdbc`；不引入 JPA、不引入连接池。
- 数据：新增 `.lytgos/lytgos.db`；现有 `sessions/*.json` 保持原样。
- 闸门：`05-audit-invariant.ps1` 从 NOT_READY 变为真实执行；`harness/README.md` 与 `AGENTS.md` §7 的闸门说明同步。
- 文档：纠正 `docs/design/TechnicalSolution.md` 中「用 `ChatClient` 转 `ProviderService`」与「SQLite + Spring Data JPA」两处与现状不符的表述。
- 不在本 change：多 Agent 并存演示、定时任务、`lytgos-dev` SKILL 自身修正（未纳入版本库）。
