# Tasks

## 1. 记忆内核

- [x] 1.1 `MemoryStore` 实现：读写工作区 `memory/MEMORY.md`，追加条目，写入前做 8000 字符上限校验（超限整体拒绝），文件缺失时读取返回空、写入时创建
  - **验收**：`MemoryStoreTest` 覆盖「追加后可读回 / 超限拒绝且文件不变 / 缺失文件读取为空 / 首次写入自动创建」四条，全绿
  - **记录**：代码审查发现原实现是「整文件截断重写」，与 design「追加一次单行写入」不符，崩溃/并发会丢掉全部记忆；已改为 `StandardOpenOption.APPEND` 只写新增块，容量校验仍在写入前完成

## 2. 记忆工具与提示词注入

- [x] 2.1 `save_memory` 与 `recall_memory` 两个内置 Tool 实现，含 JSON Schema 与「暂无记忆」「超限清理建议」文本
  - **验收**：`MemoryToolsTest` 覆盖保存成功、超限拒绝、读回内容、空记忆文本四条，全绿
- [x] 2.2 `PromptBuilder` 注入 `memory/MEMORY.md`：位于 bootstrap 之后、标题 `## 长期记忆（MEMORY.md）`、超过 8000 字符截断并标注、缺失/空文件跳过
  - **验收**：`PromptBuilderTest` 新增「注入记忆 / 无记忆不注入 / 超长截断」三条全绿

## 3. 文件工具

- [x] 3.1 `write_file` 实现：沙箱校验、父目录创建、返回路径与字节数、64KB 上限；拒绝改写 `agents/*/AGENT.md` 与根 `config.yaml`（实施前按 design Q1 与用户确认最终口径）
  - **验收**：`WriteFileToolTest` 覆盖写入回读、越界拒绝、受保护文件拒绝且原文件不变、超长拒绝四条，全绿
  - **记录**：代码审查发现 `write_file` 可整体覆写 `memory/MEMORY.md`，绕过 8000 上限与 memory spec 的「记忆只由显式写入产生」不变量；已把记忆文件并入受保护集合，spec delta 同步补该路径与新提示，新增 `rejectsMemoryFile` 用例
- [x] 3.2 `list_dir` 实现：缺省工作区根、按名称排序、目录名 `/` 后缀、200 条截断标注、非目录返回失败文本
  - **验收**：`ListDirToolTest` 覆盖根目录列举、排序与后缀、截断标注、非目录文本四条，全绿

## 4. Shell 工具

- [x] 4.1 `ShellTool` 实现：`checkCommand` 校验、与沙箱一致的参数切分、`ProcessBuilder` 直接执行、15 秒超时并回收 descendants、8000 字符输出截断、spec 展示命令白名单
  - **验收**：`ShellToolTest` 覆盖白名单内执行 `java -version`、白名单外拒绝、超时终止夹具进程、分号按字面参数传递四条，全绿
  - **记录**：代码审查补充 `process.getOutputStream().close()`，读 stdin 的命令不再阻塞到误判超时；遗留两条建议未改（白名单按 basename 匹配任意同名可执行文件、切分逻辑与 `SandboxChecker` 各一份），记入合并后待办

## 5. 装配、配置模板与文档

- [x] 5.1 `CoreBeans` 声明 `MemoryStore`，`ToolBeans` 声明五个新工具，`AgentRuntimeFactory` 逐个显式接收并合并进内置工具列表
  - **验收**：`lytgos tool list` 输出包含 `file_read`、`write_file`、`list_dir`、`shell`、`save_memory`、`recall_memory`、`http_get`；`Profile.tools` 白名单筛选实测生效（列未注册工具启动失败）
  - **记录**：`tool list` 七个工具齐全；把 `not_a_real_tool` 写进 AGENT.md 的 `tools` 后启动退出码 1，提示「列了未注册的工具，当前可用: [...]」
- [x] 5.2 `lytgos init` 的 `config.yaml` 模板补充 `allowed-commands` 说明（Shell 依赖该白名单，为空全拒）
  - **验收**：临时目录 `lytgos init` 后模板含该说明；07 闸门仍全 PASS
  - **记录**：模板说明已生效；07 本次 5 条 PASS，第 3 条因未设置 `AMAP_MCP_KEY` 记 SKIP（退出码 2），与本次模板改动无关
- [x] 5.3 `AGENTS.md` 当前状态段与 `README.md` 快速上手同步第二周能力（记忆、文件写入/列举、Shell）与新闸门编号
  - **验收**：文档中可查到六条新工具与 `10-week2-acceptance`，无过期描述
  - **记录**：`AGENTS.md` 状态段与目录地图、`harness/README.md` 闸门表与说明、`README.md` 当前状态与路线图均已更新；路线图补勾 Provider/ReAct 与 Memory

## 6. 第二周验收闸门

- [x] 6.1 新增 `harness/checks/10-week2-acceptance.ps1`：init → `save_memory`（显式指令）→ 新进程验证记忆注入 → `write_file` 回读 → `shell java -version`；缺 jar 或 `DEEPSEEK_API_KEY` 返回 2，失败返回 1，全过返回 0
  - **验收**：脚本可执行；同步 `AGENTS.md` 机器可读清单与 `harness/README.md` 后 `00-layout-check` 退出码 0
  - **记录**：脚本 UTF-8 BOM + 语法检查通过；清单同步后 `00-layout-check` 输出「骨架完整：8 项；闸门清单一致：11 道」。代码审查后强化两处弱断言：第 3 条从「模型可猜 Spring Boot」改为每轮随机校验码（ZX+5 位），第 5 条从恒真的 `version` 改为 java 版本号正则。第 5 条正则首版连续 3 次误红，根因是会话历史以 JSON 落盘、引号被转义成 `\"`（实测 `java version \"25\"`，PATH 里的 java 是 25，mvn 用的才是 JDK 21），正则从 `"?\d` 改为 `[\\"]*\d`
- [x] 6.2 用真实 `DEEPSEEK_API_KEY` 跑 `10-week2-acceptance` 至退出码 0（若 key 缺失，按三态记 SKIP 并在 tasks 中显式记录原因）
  - **验收**：退出码与各步骤结果记录在 tasks.md
  - **记录**：2026-11-15 真实 key 运行，五条全 PASS、退出码 0：`save_memory` 写入 Spring Boot → 移走 sessions 后新进程直接答出 Spring Boot（未调工具）→ `write_file` 回读 hello → `shell java -version` 会话历史含 exit=0 与 version。随机校验码与第 5 条正则修复后连跑 4 次全 PASS（单跑 ZX83297 / ZX48083 / ZX11305，全量内 ZX97628），无一 flaky

## 7. 集成验证

- [x] 7.1 `mvn -B verify` 退出码 0（含新增单测与静态门禁）
  - **验收**：构建日志 BUILD SUCCESS，测试总数较变更前增加
  - **记录**：`mvn -B verify` BUILD SUCCESS，13 个模块全部 SUCCESS（2:53 min），ArchUnit 4 条通过；单测 81 → 109（净增 28），按模块 cli 4 / core 41 / memory 9 / persona 6 / provider 4 / test 4 / tool 41（含代码审查后新增的 `rejectsMemoryFile`）。基线用独立 `git worktree` 跑 main（774cc3d）得 81，统计口径均为 surefire `tests` 属性之和。过程中修掉 Spotless 格式、6 类 SpotBugs 缺陷（NP_NULL / EI_EXPOSE_REP2 / COMMAND_INJECTION / IMPROPER_UNICODE / DM_CONVERT_CASE），豁免只落在 `spotbugs-exclude.xml` 并写明补偿措施与重审条件
- [x] 7.2 `powershell -File harness\run-all.ps1` 全量执行，逐闸门记录结果
  - **验收**：00–04、06–09 按三态记录；05 仍为 NOT_READY（第四周范围），不得宣称「完整验收通过」
  - **记录**：2026-11-15 全量执行四次。① 首轮（未注入 `AMAP_MCP_KEY`）EXIT=1：8 PASS + 05/07/08 NOT_READY；② 第二轮 10 号第 4 条 FAIL（模型只回「好」未调 `write_file`），根因是提示词「只回答「好」」与「调用工具」自相矛盾，改为「必须真实调用该工具，不要只回复文字」后连续 3 次单跑全 PASS；③ **终轮**（同一条命令内从 User 作用域注入 `AMAP_MCP_KEY` 后跑全量）EXIT=1，**仅 05 NOT_READY**，其余 10 道全 PASS：07 六条全 PASS（真实调高德天气）、08 八条全 PASS（含远程 MCP）、10 五条全 PASS、00/01/02/03/04/06/09 均 PASS。未变绿的只有 05（storage 审计未实现，第四周范围）。环境教训：`AMAP_MCP_KEY` 在 IDE 启动后才 setx 时，子终端进程读不到，需在同一条命令里 `[Environment]::GetEnvironmentVariable('AMAP_MCP_KEY','User')` 注入后再跑。④ **复核轮**（10 号第 5 条正则修复后）同样 EXIT=1、仅 05 NOT_READY，10 道全 PASS，surefire 实跑 109 条与 verify 一致。复核轮先遇到一次 02 号误红：本机 Machine 级 `JAVA_HOME=C:\jdk-17`，mvn 用 JDK 17 fork 加载 JDK 21 编译的测试类，报 `class file version 65.0 ... up to 61.0`；同命令注入 `JAVA_HOME=C:\jdk21` 后复绿。跑闸门的前置条件是 `JAVA_HOME` 指向 JDK 21

## 8. 审查遗留项补强（第二轮）

> 触发：spec 合规审查提出 8 条「条款未被用例覆盖」的警告，加一次 02-test 误红暴露的环境前置缺失。本节只补证据与护栏，不改第二周 spec 条款。

- [x] 8.1 把 8 条 spec 警告逐条补成可执行用例
  - 记忆上限：`MemoryStoreTest` / `MemoryToolsTest` 的断言从 `String.valueOf(MAX_CHARS)` 改为硬编码 `8000`，并 `assertEquals(8000, MemoryStore.MAX_CHARS)` 钉住 spec 值——拿常量自证等于没断言
  - 普通对话不改写记忆：`ReActLoopTest.plainConversationDoesNotRewriteMemory`（字节级 `assertArrayEquals`）
  - 记忆缺失不阻断：`ReActLoopTest.completesWhenMemoryFileMissing` + 10 号闸门第 6 条（CLI 退出码）
  - 符号链接：`SandboxCheckerTest.rejectsSymlinkEscapingWorkspace`（建链失败时 `Assumptions.abort`；本机实测真跑，Skipped=0）
  - `output/` 与其余布局：`WorkspaceTest.createsFullLayout`（6 目录 + 3 bootstrap + config + 空 `MEMORY.md`）
  - 超时进程回收：`SleepyMain` 先写 pid，`ShellToolTest.terminatesOnTimeout` 用 `ProcessHandle.isAlive` 断言子进程已死
  - `tool list`：`ToolCommand` 抽出包内纯函数 `render(List<ToolSpec>)`，`ToolCommandTest` 三条（空表提示 / 名称与描述 / 顺序）
  - 未注册工具：原 `ToolExecutorTest` 已覆盖执行路径；另补 `ToolRegistryTest` 四条（未知返回 empty / 重名拒绝 / 顺序 / 缺名拒绝）
  - **验收**：`mvn -B verify` BUILD SUCCESS；单测 109 → 120（cli 7 / core 49 / memory 9 / persona 6 / provider 4 / test 4 / tool 41），surefire `skipped="0"`
- [x] 8.2 `10-week2-acceptance` 增第 6 条「记忆缺失不阻断」
  - **验收**：移走 `memory/MEMORY.md` 后 `chat -m 你好` 退出码 0、stderr 无 `ERROR`/`Exception`、文件未被重建；单跑连续 3 次六条全 PASS（EXIT=0），全量内同样 PASS
- [x] 8.3 新增 `harness/lib/JdkGuard.ps1`，接入 `02-test` 与 `run-all`
  - 判据：根 `pom.xml` 的 `<java.version>` 与 `mvn -v` 报出的 Java 主版本比对，不足即 FAIL 并给出 `JAVA_HOME` 修复提示；无 pom / 无 `java.version` / 缺 mvn 时静默跳过，不侵占各闸门的三态范围
  - **验收（先红后绿）**：`JAVA_HOME=C:\jdk-17` 时 02-test 直接 FAIL（「mvn 运行在 Java 17，项目要求 Java 21」）；`JAVA_HOME=C:\jdk21` 时 PASS 并打印「JDK 前置校验通过」。脚本首次创建缺 BOM 导致 3 处语法错误，按 `harness/README.md` 的编码约定补 BOM 后复验 0 错误
- [x] 8.4 复验与记录
  - **验收**：`mvn -B verify` EXIT=0；`10-week2-acceptance` 连跑 3 次 EXIT=0；全量 `run-all` 11 道 10 PASS + 仅 `05-audit-invariant` NOT_READY，EXIT=1
  - **记录**：本轮又修掉两次 SpotBugs 阻塞：`ToolRegistryTest` 匿名 `Tool` 触发 `SIC_INNER_SHOULD_BE_STATIC_ANON`（改静态命名类 `NamedTool`）；`ShellToolTest` / `SleepyMain` 的 `throws Exception` 触发 `THROWS_METHOD_THROWS_CLAUSE_BASIC_EXCEPTION`（改 `IOException` / `IOException, InterruptedException`）。按硬规则 3，05 未就绪时仍不得宣称「完整验收通过」；第二周范围内 120 条单测与 10 道闸门全绿。代码审查的两条 shell 建议（白名单按 basename 匹配、切分逻辑与 `SandboxChecker` 各一份）未改，仍列合并后待办

## Workflow follow-up

- 交由用户审阅后合并 `feat/week2-memory-tools-20261115`。
- 合并后同步主 specs 并归档本 change。
