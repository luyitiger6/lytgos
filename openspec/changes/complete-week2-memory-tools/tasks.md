# Tasks

## 1. 记忆内核

- [ ] 1.1 `MemoryStore` 实现：读写工作区 `memory/MEMORY.md`，追加条目，写入前做 8000 字符上限校验（超限整体拒绝），文件缺失时读取返回空、写入时创建
  - **验收**：`MemoryStoreTest` 覆盖「追加后可读回 / 超限拒绝且文件不变 / 缺失文件读取为空 / 首次写入自动创建」四条，全绿

## 2. 记忆工具与提示词注入

- [ ] 2.1 `save_memory` 与 `recall_memory` 两个内置 Tool 实现，含 JSON Schema 与「暂无记忆」「超限清理建议」文本
  - **验收**：`MemoryToolsTest` 覆盖保存成功、超限拒绝、读回内容、空记忆文本四条，全绿
- [ ] 2.2 `PromptBuilder` 注入 `memory/MEMORY.md`：位于 bootstrap 之后、标题 `## 长期记忆（MEMORY.md）`、超过 8000 字符截断并标注、缺失/空文件跳过
  - **验收**：`PromptBuilderTest` 新增「注入记忆 / 无记忆不注入 / 超长截断」三条全绿

## 3. 文件工具

- [ ] 3.1 `write_file` 实现：沙箱校验、父目录创建、返回路径与字节数、64KB 上限；拒绝改写 `agents/*/AGENT.md` 与根 `config.yaml`（实施前按 design Q1 与用户确认最终口径）
  - **验收**：`WriteFileToolTest` 覆盖写入回读、越界拒绝、受保护文件拒绝且原文件不变、超长拒绝四条，全绿
- [ ] 3.2 `list_dir` 实现：缺省工作区根、按名称排序、目录名 `/` 后缀、200 条截断标注、非目录返回失败文本
  - **验收**：`ListDirToolTest` 覆盖根目录列举、排序与后缀、截断标注、非目录文本四条，全绿

## 4. Shell 工具

- [ ] 4.1 `ShellTool` 实现：`checkCommand` 校验、与沙箱一致的参数切分、`ProcessBuilder` 直接执行、15 秒超时并回收 descendants、8000 字符输出截断、spec 展示命令白名单
  - **验收**：`ShellToolTest` 覆盖白名单内执行 `java -version`、白名单外拒绝、超时终止夹具进程、分号按字面参数传递四条，全绿

## 5. 装配、配置模板与文档

- [ ] 5.1 `CoreBeans` 声明 `MemoryStore`，`ToolBeans` 声明五个新工具，`AgentRuntimeFactory` 逐个显式接收并合并进内置工具列表
  - **验收**：`lytgos tool list` 输出包含 `file_read`、`write_file`、`list_dir`、`shell`、`save_memory`、`recall_memory`、`http_get`；`Profile.tools` 白名单筛选实测生效（列未注册工具启动失败）
- [ ] 5.2 `lytgos init` 的 `config.yaml` 模板补充 `allowed-commands` 说明（Shell 依赖该白名单，为空全拒）
  - **验收**：临时目录 `lytgos init` 后模板含该说明；07 闸门仍全 PASS
- [ ] 5.3 `AGENTS.md` 当前状态段与 `README.md` 快速上手同步第二周能力（记忆、文件写入/列举、Shell）与新闸门编号
  - **验收**：文档中可查到六条新工具与 `10-week2-acceptance`，无过期描述

## 6. 第二周验收闸门

- [ ] 6.1 新增 `harness/checks/10-week2-acceptance.ps1`：init → `save_memory`（显式指令）→ 新进程验证记忆注入 → `write_file` 回读 → `shell java -version`；缺 jar 或 `DEEPSEEK_API_KEY` 返回 2，失败返回 1，全过返回 0
  - **验收**：脚本可执行；同步 `AGENTS.md` 机器可读清单与 `harness/README.md` 后 `00-layout-check` 退出码 0
- [ ] 6.2 用真实 `DEEPSEEK_API_KEY` 跑 `10-week2-acceptance` 至退出码 0（若 key 缺失，按三态记 SKIP 并在 tasks 中显式记录原因）
  - **验收**：退出码与各步骤结果记录在 tasks.md

## 7. 集成验证

- [ ] 7.1 `mvn -B verify` 退出码 0（含新增单测与静态门禁）
  - **验收**：构建日志 BUILD SUCCESS，测试总数较变更前增加
- [ ] 7.2 `powershell -File harness\run-all.ps1` 全量执行，逐闸门记录结果
  - **验收**：00–04、06–09 按三态记录；05 仍为 NOT_READY（第四周范围），不得宣称「完整验收通过」

## Workflow follow-up

- 交由用户审阅后合并 `feat/week2-memory-tools-20261115`。
- 合并后同步主 specs 并归档本 change。
