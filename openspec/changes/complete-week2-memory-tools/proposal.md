# Proposal

## Why

需求文档第 11 章的第二周规划（Memory + Tool 体系）目前只完成了一半：MCP Client 与 `file_read` 已落地，但长期记忆（`MEMORY.md` + `save_memory` / `recall_memory` + 启动注入）一行代码都没有，`write_file` / `list_dir` 与 Shell Tool 也不存在。结果是第二周的验收句「Agent 能记住偏好并在后续对话用到」无法演示，`lytgos-memory` 至今只有 `package-info.java`。

工程规范线（`add-quality-gates` 等）已经收口，功能主线需要按周补齐；这是继续第四周（SQLite + 审计写入）之前的必经一步。

## What Changes

- **Memory 极简版**：新增 `MemoryStore` 读写 `.lytgos/memory/MEMORY.md`；新增 `save_memory`（追加条目）与 `recall_memory`（返回当前记忆）两个内置 Tool；`PromptBuilder` 启动时把 MEMORY.md 全文注入 system prompt（超过上限截断并标注，文件缺失或为空则跳过）；系统不自动把每轮问答写入记忆。
- **内置工具补齐**：新增 `write_file` 与 `list_dir`（路径经 `SandboxChecker.checkPath` 校验，写操作拒绝改写 Agent 定义与工作区配置）；新增 `shell` 工具（命令名经 `SandboxChecker.checkCommand` 校验，`ProcessBuilder` 直接执行不经过 shell，带超时、输出上限与进程树回收）。
- **装配**：新工具注册进 `ToolBeans` / `AgentRuntimeFactory`，沿用 `Profile.tools` 白名单筛选；`lytgos tool list` 可见。
- **配置模板**：`lytgos init` 生成的 `config.yaml` 补充 `allowed-commands` 说明（Shell 工具依赖该白名单，为空时全部拒绝）。
- **验收闸门**：新增 `harness/checks/10-week2-acceptance.ps1`（记忆跨进程生效、文件写入回读、Shell 回显；缺 `DEEPSEEK_API_KEY` 时 SKIP 并返回 2），同步 `AGENTS.md` 机器可读清单与 `harness/README.md`。
- **文档**：`AGENTS.md` 当前状态段与 `README.md` 快速上手同步第二周能力。

## Capabilities

### New Capabilities

- `memory`: 长期记忆最小闭环——`MEMORY.md` 的读写、启动注入、`save_memory` / `recall_memory` 两个工具及其边界。
- `builtin-tools`: 内置文件与命令工具的可用范围与安全边界（`file_read` / `write_file` / `list_dir` / `shell`），全部沿用既有沙箱治理路径。

### Modified Capabilities

（无。`react-loop` 的「工具执行与沙箱」要求不变，新能力在其之上补充具体工具的行为。）

## Impact

- **模块**：`lytgos-memory`（新建实现）、`lytgos-tool`（新增工具类）、`lytgos-core`（`PromptBuilder` 注入记忆）、`lytgos-cli`（Bean 装配）、`harness/`（新闸门）、`AGENTS.md` / `README.md`（文档）。
- **不改动**：`ProviderRegistry`、`ReActLoop` 执行语义、`SandboxChecker` 对外接口（只复用 `checkPath` / `checkCommand`）、`ToolExecutor` 的失败分类。
- **依赖**：不新增第三方依赖；Shell 执行使用 JDK `ProcessBuilder`。
- **兼容性**：`Profile.tools` 为空仍表示不限制，新工具会出现在既有 Agent 的可选清单里；命令白名单默认仍为空，Shell 在未配置时一律拒绝，不构成行为放宽。
- **风险**：`write_file` 对 `agents/*/AGENT.md` 与 `config.yaml` 的保护范围需要在实施前确认（见 design 的 Open Questions）；Shell 工具新增命令执行面，安全边界完全依赖 `allowed-commands` 白名单。
