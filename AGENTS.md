# AGENTS.md

本文件是 lytgos 仓库对 AI 的**唯一入口契约**。任何 agent 进入本仓库，先读完这里再动手；与本文件冲突的即时指令，以本文件为准。

---

## 1. 这个项目是什么

**lytgos** 是 Java 生态的**企业级 Agent OS 控制面**。

它不解决「agent 怎么跑起来」——LangChain4j、Spring AI、Embabel、AgentScope 已经够多。它解决框架不管的那一半：**谁能跑、跑多少、花多少、出过什么事**。

| 项 | 取值 | 状态 |
|---|---|---|
| JDK | 21 | 已定 |
| 框架 | Spring Boot 4.1.2 + Spring AI 2.0.2 | 已定（补丁版本随首个 change 锁定） |
| 构建 | Maven 多模块，单仓库，**不使用 git submodule** | 已定 |
| 部署 | 单进程 fat jar：`mvn clean package` → `java -jar` | 已定 |
| 存储 | SQLite 三张表（sessions / tool_invocations / llm_calls） | 已定 |
| 模型接入 | Spring AI 只取协议转换 + schema 生成，**禁用自动工具执行** | 已定 |

**当前状态：第 2 周能力已落地，审计链路已接入。** 12 个 Maven 模块已就位（11 个业务模块 + `lytgos-test`），`mvn clean package` 产出 `lytgos-cli-boot.jar` 与 `lytgos.jar`；`lytgos init` / `chat` / `profile` / `provider` / `tool` 五个命令可用。第 1 周打通 ReAct 循环、Prompt 组装、会话落盘与沙箱；第 2 周补齐长期记忆（`memory/MEMORY.md` 启动注入 + `save_memory` / `recall_memory`）、`write_file` / `list_dir` 与受白名单约束的 `shell`，内置工具共七个，`Profile.tools` 白名单筛选对它们全部生效。`07-week1-acceptance.ps1` 六条与 `10-week2-acceptance.ps1` 六条均以真实操作链路为准。

`add-audit-trail-and-context` 把会话存储切到 SQLite（`.lytgos/lytgos.db`），并接入 `llm_calls` / `tool_invocations` 审计（`05-audit-invariant` 已从空壳改为真实执行）。**第 3 周 Web 仍只有地基**：统一响应体、异常映射、未知路径统一 404、虚拟线程、OpenAPI 已就位，但设计 §7.2 的 10 个业务端点与 `lytgos serve` 尚未实现，不得表述为「第 3 周完成」。

`harness\run-all.ps1` 的结论以实际退出码为准：07/08/10 需要真实 `DEEPSEEK_API_KEY`（08 另需 `AMAP_MCP_KEY`），缺 key 时对应条目记 NOT_READY/SKIP，总闸门返回 1；按硬规则 3，此时**不得报告「完整验收通过」**。运行构建与闸门必须让 `JAVA_HOME` 指向 JDK 21：本机 Machine 级为 `C:\jdk-17`，不覆盖时 02-test 会报 `class file version 65.0 ... up to 61.0`（JDK 17 fork 加载 JDK 21 字节码）；`02-test` 与 `run-all` 已接入 `harness/lib/JdkGuard.ps1` 前置校验，会在跑 mvn 前直接指出 JDK 不足，同一条命令内注入 `$env:JAVA_HOME='C:\jdk21'` 即可。注意：`AMAP_MCP_KEY` 等变量若在 IDE 启动后才 setx，子终端进程读不到，需在同一命令内从 User 作用域注入后再跑闸门。

## 2. 三层分工

| 层 | 目录 | 回答的问题 | 违背后果 |
|---|---|---|---|
| **工件层**（Spec） | `openspec/` | 要做什么、为什么做 | 需求漂移、无法审计 |
| **能力层**（Harness） | `harness/` | 合不合格 | 无人拦截，错误直接流入主干 |
| **行为层**（Skills） | `.agents/skills/` | 怎么干才专业 | 靠猜、靠试、靠嘴硬 |

三层是**乘法关系**：任何一层失效，整体归零。

## 3. 硬规则

1. **先规格后代码**：任何超过「改个拼写」的改动，必须先在 `openspec/changes/<change-id>/` 里有 proposal 与 tasks。没有 change 的编码视为越权。
2. **Spec 不能由实现者单方面修改**：实现过程中发现规格有误，应修改 proposal 并说明理由，不能悄悄把 spec 改成代码的样子。
3. **按阶段判定完成**：以本次功能的真实操作链路跑通且结果可见作为「功能已调通」的依据。只有 `harness\run-all.ps1` 返回 0 才报告「完整验收通过」；未执行项必须显式后置，不将功能可用等同于全量测试通过。
4. **禁止伪造绿灯**：不许 `@Disabled`、不许 `it.skip`、不许注释掉断言、不许 `-DskipTests`、不许留 `TODO/FIXME` 交付。03 号闸门直接拦截。
5. **验收标准不能由实现者自己解释**：spec 里的 `Scenario` 就是验收标准，解释权归 spec，不归写代码的人。
6. **任何改动先走分支，合并与发布只在远程**：`main` 只承载合并与同步推送。新功能 `feat/<简述>-<yyyyMMdd>`，缺陷补丁 `fix/<简述>-<yyyyMMdd>`。
   流程是：分支内本地提交 → `git push` → 开 PR → 人工审阅通过 → **在远程合并**（PR 页面或 `gh pr merge`）。
   - **禁止在本地 `git merge` 到 `main`**：本地合并绕过了 PR 的审阅记录，事后无人能看出这次合入经过了谁的确认。
   - **本地 `main` 必须始终等于 `origin/main`**；`main` 上出现未推送提交即视为违规，下次改动前先修正。
   - **release tag 只能打在已合并到远程 `main` 的提交上**；PR 合并前不得打 tag，也不得在 feature 分支上打 release tag。
   - 「完成一样就先本地提交」与「合入 `main`」是两件事：前者随时可做，后者必须等远程 PR 流程。
7. **审计优先于功能**：`tool_invocations` 与 `llm_calls` 的写入不允许为了「先跑通」而注释掉或后置。这是产品卖点，不是可选项。
8. **汇报 change 必须说清工作内容**：只列「名称 / tasks 进度 / 是否跟踪」三列不算汇报。每个 change 至少说清它要交付什么、现在做到哪、还剩哪些活；说不清说明它还没有规划，应先补 proposal 或删除。06 号闸门强制每个 change 有合格的 `proposal.md`。

## 4. 本项目特有的三条不可越线

这三条是 lytgos 区别于「又一个 agent 框架」的地方，违反任何一条，产品价值归零。

### 4.1 禁止自动工具执行

Spring AI 的 `ChatClient` 会自动驱动工具调用循环（`ToolCallingAdvisor` + `ToolCallingManager`）。
lytgos 的工具执行必须 100% 由自己的 `ToolExecutor` 发起，因为**治理（配额、白名单、沙箱、审计）只能挂在执行路径上**。

- 只允许 `ChatModel.call(prompt)`——它返回模型原样响应，不执行工具；
- 禁止 `ChatClient`、`ToolCallingManager`，以及任何能让 `@Tool` 方法被框架自动执行的机制：`@Tool` 只作为工具声明的元数据来源，执行入口始终是 `ToolExecutor`；
- 04 号闸门由 `lytgos-test` 模块的 `ArchitectureTest`（ArchUnit）执行：禁依赖 `ChatClient` 与 `ToolCallingManager`，同时禁条件装配注解与类型扫描（后两条对应 4.2）。

### 4.2 Provider 显式映射

provider name → `ChatModel` 是一张**启动时构造的显式 Map**，来自配置文件。
禁止 `@ConditionalOnBean`、`ObjectProvider<ChatModel>`、`getBeansOfType` 之类的类型扫描——多引一个 starter 就凭空多出一个 provider，配置驱动会退化成 classpath 驱动。

### 4.3 沙箱是不可关闭的

`FileTool` / `ShellTool` / `HttpTool` 的所有调用必须经过 `SandboxChecker`。
路径校验必须 `canonicalize()` 后比较，禁止只做字符串 `contains("..")` 之类的朴素判断。

## 5. 标准工作流

```
① 探索（可选）  /opsx:explore
② 定规格        /opsx:propose <你的想法>
                ↓ 产出 openspec/changes/<change-id>/
                ↓ proposal.md / design.md / tasks.md / specs（视改动分级）
                ↓ ⚠️ 人类必须在此审阅放行，这是唯一的强制人工卡点
③ 实施          /opsx:apply
                ↓ 按 tasks.md 推进，先调通本次功能
④ 完整验收      用户需要时集中做：spec 合规 → 代码质量 → harness\run-all.ps1 退出码为 0
⑤ 推送与 PR     git push → gh pr create --base main
                ↓ CI 通过 + 人工审阅
                ↓ ⚠️ 合并与打 tag 都在远程，本地 main 不得产生未推送提交
⑥ 归档          /opsx:archive
```

改动分级：纯内部重构可只写 proposal + tasks；涉及对外行为变化必须带 `specs/` delta。

## 6. 技能触发规则

| 场景 | 触发技能 | 强制程度 |
|---|---|---|
| 测试失败 / 报错 / 现象异常 | `systematic-debugging` | 强制，禁止先改代码 |
| 想说「完成了」 | `verification-before-completion` | 强制，禁止无证据声明 |
| 需要写新规格 | OpenSpec 内置 `openspec-propose` | 强制 |

技能不是建议，是流程的一部分。验证规模以当前阶段为准：快速迭代期只需证实本次功能链路，完整验收后置。

## 7. 目录地图

```
lytgos/
├── AGENTS.md                  ← 你正在读的文件
├── README.md                  人看的快速上手
├── .gitignore
├── openspec/                  【工件层】config.yaml 已就位；specs/ 与 changes/ 由第一个 change 创建
├── docs/design/               （本地资料，不入库）设计基准，以本节四份为准
│   ├── architecture.md        项目指南：目标形态、14 个模块、8 条不可违背原则、工程约束
│   ├── DemandAnalysis.md      需求文档（What）
│   ├── TechnicalSolution.md   技术方案（How）
│   └── AiProgrammingGuide.md  AI 编程指南（约束与任务拆解方式）
├── 项目相关文档/               原始资料（历史版本；与 docs/design/ 冲突时以 docs/design/ 为准）
│   ├── 项目调研.md             业界调研与立项论证
│   ├── 需求文档.md
│   └── 技术文档.docx
├── site/                      官网（VitePress，中英双语；node_modules 与构建产物不入库）
│   ├── .vitepress/config.mts   base / locales / 导航 / 侧边栏
│   ├── .vitepress/theme/       橙黑主题（custom.css）与首页 Home.vue
│   ├── index.md · zh/index.md  英文首页（根）与中文首页
│   └── docs/ · zh/docs/        文档页，中英一一镜像
├── harness/                   【能力层】
│   ├── run-all.ps1            总闸门：三个退出状态（0 通过 / 2 未就绪 / 其余失败）
│   ├── README.md              闸门清单与新增方法
│   ├── lib/JdkGuard.ps1       构建前 JDK 版本前置校验（02-test 与 run-all 共用）
│   └── checks/
│       ├── 00-layout-check.ps1          骨架完整性 + 闸门清单一致性
│       ├── 01-compile.ps1               能不能编译
│       ├── 02-test.ps1                  行为对不对
│       ├── 03-static-guard.ps1          有没有伪造绿灯
│       ├── 04-dependency-direction.ps1  依赖方向 + 禁止自动工具执行
│       ├── 05-audit-invariant.ps1       审计不变量 I1/I2/I3
│       ├── 06-change-contract.ps1       每个 change 必须有 proposal 说明工作内容
│       ├── 07-week1-acceptance.ps1      第 1 周端到端验收（init → 对话 → 反例）
│       ├── 08-mcp-acceptance.ps1        MCP 与内置工具双路径对照，证明共用 ToolExecutor
│       ├── 09-startup-budget.ps1        启动预算回归护栏（L0 --version / L1 provider list）
│       ├── 10-week2-acceptance.ps1      第 2 周端到端验收（记忆跨进程 → 写文件 → 执行命令 → 删记忆仍可对话）
│       └── 11-web-smoke.ps1             Web 地基冒烟（探针 / prometheus / system-info / 404 / api-docs / traceId）
└── .agents/skills/            【行为层】
    ├── systematic-debugging/          四阶段根因法
    ├── verification-before-completion/ 先证后宣布
    └── openspec-*/                     六个 OpenSpec 工作流技能（propose / apply / archive / explore / sync-specs / update-change）
```

12 个模块目录与根 `pom.xml` 已就位（含不含生产代码的 `lytgos-test`）：各模块含 `pom.xml` 与 `src/main/java`，依赖方向为「所有模块单向依赖 `lytgos-core`，`lytgos-boot` 聚合全部」；产物为 `lytgos-boot/target/lytgos.jar`。新增业务模块时必须在 `lytgos-test/pom.xml` 登记 test 依赖，否则 ArchUnit 扫不到它（04 闸门会拦）。

<!-- harness-checks: 00-layout-check 01-compile 02-test 03-static-guard 04-dependency-direction 05-audit-invariant 06-change-contract 07-week1-acceptance 08-mcp-acceptance 09-startup-budget 10-week2-acceptance 11-web-smoke -->

上面这行是**闸门清单的机器可读副本**：新增或删除闸门时同步更新，`00-layout-check.ps1` 会把它与 `harness/checks/*.ps1` 的实际文件比对，不一致直接退出码 1。

## 8. 命令速查

| 目的 | 命令 |
|---|---|
| 新建功能分支 | `git switch -c feat/<简述>-<yyyyMMdd> main` |
| 新建补丁分支 | `git switch -c fix/<简述>-<yyyyMMdd> main` |
| 一键验收 | `powershell -File harness\run-all.ps1` |
| 只查骨架 | `powershell -File harness\checks\00-layout-check.ps1` |
| 只查红线 | `powershell -File harness\checks\03-static-guard.ps1` |
| 编译 / 测试 | `mvn clean package` |
| 启动服务 | `java -jar lytgos-boot/target/lytgos.jar` |
| 推送分支 | `git push -u origin <分支>` |
| 开 PR | `gh pr create --base main --head <分支>` |
| 看 CI | `gh pr checks <PR 号>` |
| 合并 PR | `gh pr merge <PR 号> --merge`（合并在远程，不在本地） |
| 打发布标签 | `git tag -a v<x.y.z> -m "..."`，仅限已合并到远程 `main` 的提交 |
