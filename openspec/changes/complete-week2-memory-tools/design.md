# Design

## Context

见 proposal「Why」。当前实现约束（已核对代码）：

- 沙箱在 `CoreBeans` 构造：工作区根 + `config.yaml` 的 `allowed-commands` / `allowed-domains`，白名单不可变、为空即全拒；`checkCommand` 已能识别裸命令、带路径与带引号三种写法。
- 工具按 `Profile.tools` 筛选：空列表 = 不限制；列了未注册工具会启动失败。内置工具目前只有 `http_get` 与 `file_read`，逐个以构造参数注入 `AgentRuntimeFactory`（不走类型扫描，遵守 4.2）。
- `ToolExecutor` 的失败分类是既成契约：沙箱违规中断对话，其余失败回填文本继续。
- `PromptBuilder.from(profile, workspaceRoot, instructions)` 目前注入 identity、AGENT.md 正文与 `profile.bootstrap` 列出的文件。
- `Workspace.initialize()` 已创建空的 `memory/MEMORY.md`；`lytgos-memory` 只有 `package-info.java`。
- 命令白名单目前只被 `McpServerManager` 用于 stdio 子进程启动校验，尚无任何工具把它用于「执行用户/模型给出的命令」。

## Goals / Non-Goals

**Goals:**

- 第二周验收句可演示：记忆跨进程生效、文件可写可列、命令可执行。
- 所有新工具沿用同一条治理路径（`ToolExecutor` → `SandboxChecker`），不引入旁路。
- 新增 `10-week2-acceptance` 闸门，让第二周验收由机器判定而非口头声明。
- 零新增第三方依赖。

**Non-Goals:**

- 记忆的语义检索、自动抽取、情景记忆、多 Agent 记忆隔离（当前一个工作区共用一份 `MEMORY.md`）。
- Shell 的交互式会话、管道/重定向、PTY；只支持一次性命令。
- 放开 Agent 对自身 `AGENT.md` 与工作区 `config.yaml` 的改写（属治理决策，见 Open Questions）。
- Web Service、SQLite 持久化与审计写入（第四周范围）。

## Decisions

### D1 记忆载体与注入位置

`MEMORY.md` 是唯一载体；`PromptBuilder.from` 读取后整段注入，位置在 bootstrap 文件之后，标题固定为 `## 长期记忆（MEMORY.md）`。

- 理由：整文件注入是需求文档指定的极简方案，模型无需主动检索就能用上偏好；缺失/空文件跳过，不把「没有记忆」变成故障。
- 截断上限 8000 字符，与 `FileTool` 回填上限一致；截断必须带标注，避免模型把残缺记忆当完整事实。
- 备选：按需 `recall_memory` 注入（放弃——模型不知道要查，验收句就无法稳定成立）。

### D2 记忆写入只追加、超限整体拒绝

`save_memory` 追加 `- <content>` 到文件尾；若写入后总长超过 8000 字符，整体拒绝并返回上限与清理建议，绝不截断旧内容。

- 理由：旧记忆是用户资产，为容纳新条目悄悄淘汰是最容易埋雷的设计；拒绝让用户显式做取舍。
- 备选：环形缓冲/按时间淘汰（留给社区阶段）。

### D3 `write_file` 的保护范围

允许写工作区内任意路径，但 `agents/*/AGENT.md` 与根 `config.yaml` 必须拒绝。

- 理由：这两个文件决定 Agent 的身份/工具集与密钥配置，允许自改等于把「配置驱动」变成「模型驱动」，与 4.2 的治理精神冲突；其余 Bootstrap 文件（`AGENTS.md` / `SOUL.md` / `USER.md`）允许改写，保留可玩性。
- 备选：完全放开（拒绝——自我提权面）；完全只读（拒绝——第二周验收需要写入）。

### D4 `list_dir` 的形态

只列直接子项，目录名以 `/` 结尾，按名称排序，200 条上限并标注截断；不做递归、不返回大小与时间。

- 理由：递归列举会与 `shell` 能力重叠且输出难控；最小形状足以支撑「找文件再读」的常见链路。

### D5 Shell 执行模型

`ProcessBuilder` 直接执行，不经过系统 shell；命令字符串按空白切分，首个片段为可执行文件并交给 `checkCommand` 校验，其余为参数；工作目录为工作区根；继承当前环境变量。

- 参数解析与 `SandboxChecker` 的 `executableOf` 保持同一规则（裸命令 / 带路径 / 引号包裹三种写法），避免「校验按 A 解析、执行按 B 解析」的经典绕过。
- 不经 shell 是有意取舍：`;` `|` `>` 变成普通字符，命令注入面消失；代价是不支持管道与重定向，属于 Non-Goals。
- 超时 15 秒，超时后 `destroyForcibly` 并回收 descendants；stdout/stderr 合并，超 8000 字符截断标注。
- `spec()` 的描述里 MUST 展示当前允许的命令列表——模型看不到白名单就只能瞎猜，猜错一次浪费一轮；告知规则不等于放宽规则。

### D6 装配方式

`MemoryStore` 在 `CoreBeans` 声明（依赖 `WorkspaceLocator`）；四个新工具在 `ToolBeans` 声明；`AgentRuntimeFactory` 构造函数逐个接收工具实例后合成列表。

- 理由：与现有显式装配风格一致；`List<Tool>` 注入或 `getBeansOfType` 会向「classpath 驱动」滑坡，04 闸门也会拦。
- 工具类保持纯 POJO，不标 `@Component`。

### D7 记忆与工具模块归属

`MemoryStore` 与两个记忆工具放在 `lytgos-memory`；文件/命令工具放在 `lytgos-tool`。两者都只依赖 `lytgos-core` 的接口，不互相依赖。

- 理由：与 12 模块的既有分工一致；`lytgos-memory` 从空模块变成有实现，模块图不变。

### D8 `10-week2-acceptance` 闸门

三态：缺 jar 或缺 `DEEPSEEK_API_KEY` → SKIP 返回 2；任一步失败 → 1；全过 → 0。步骤：

1. 临时目录 `init`，确认 `memory/MEMORY.md` 存在；把 `allowed-commands` 配成含 `java`（跨平台真实可执行文件，Windows 无 `echo.exe`，不能拿 `echo` 做验收）。
2. `chat --message "请调用 save_memory 记住：我的后端框架是 Spring Boot"` → 文件包含 `Spring Boot`。
3. 新进程 `chat --message "我的后端框架是什么？只回答框架名"` → 回答包含 `Spring Boot`（证明注入生效，不依赖第二次工具调用）。
4. `chat --message "请用 write_file 把 hello 写入 output/gate.txt"` → 文件内容为 `hello`。
5. `chat --message "请用 shell 执行 java -version"` → 输出包含 `version`。
6. 清理临时工作区，输出六条式小结，退出码 0。

- 模型是否按指令调用工具存在波动：失败时闸门输出具体步骤与最后一次模型回复，便于区分「实现坏了」与「模型没照做」；这与 07/08 的既有做法一致。
- 闸门编号紧跟 09；需同步 `AGENTS.md` 机器可读清单与 `harness/README.md`，否则 00 闸门直接红。

## Risks / Trade-offs

- [模型不主动调用 `save_memory`] → 闸门用明确指令；记忆注入验证放在第二步、不依赖再次调用工具。
- [Windows 与 Linux 可执行文件差异] → 验收与单测统一用 `java`；超时用例用测试内夹具进程。
- [多进程并发写 `MEMORY.md`] → 最小版本不加文件锁，追加一次单行写入；冲突概率低，接受并在文档标注。
- [注入增大首包 token] → 8000 字符上限兜底；09 启动预算不测 chat，不受影响。
- [`write_file` 保护规则挡住用户自己的合理修改] → 用户仍可手工编辑；错误文本说明「受保护文件请手工修改」。
- [Shell 的失败信息可能泄露环境] → 输出回填前经 `LogSafe.sanitize` 记录日志；回填文本只含命令输出，不含环境变量转储。

## Migration Plan

- 无数据迁移。旧工作区没有 `MEMORY.md` 时由 `save_memory` 首次写入时创建；`init` 模板保持不变（已建占位文件）。
- 回滚：删除四类新工具 Bean 与 `PromptBuilder` 的记忆注入即可回到当前行为；`MEMORY.md` 是纯文本，无 schema 绑定。

## Open Questions

| # | 问题 | 级别 | 影响任务 | 默认倾向 |
|---|---|---|---|---|
| Q1 | `write_file` 是否允许改写 `agents/*/AGENT.md` 与根 `config.yaml` | 阻塞 | 3.1 | 拒绝（spec 已按此写）；若用户要求放开，先改 spec/proposal 再实施 |
| Q2 | 记忆上限 8000 字符是否合适 | 一般 | 1.1 | 8000，与工具回填上限一致 |
| Q3 | Shell 超时 15 秒是否合适 | 一般 | 5.1 | 15 秒 |
