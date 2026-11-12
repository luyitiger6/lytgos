## Why

仓库现在有 11 个模块和 15 个 Java 文件，全部是启动类与 `package-info`，没有一条能从输入走到输出的链路。核心阶段 4 周的第 1 周要解决的就是这一条：**CLI 输入 → ReAct 循环 → LLM → 工具调用 → 返回**。

这条链路跑通之前，Memory、Web Service、SQLite 审计都没有落脚点——它们都是挂在这条链路上的能力，而不是先建好再往里接。

第 1 周的可演示成果是：`lytgos chat "查一下 luyitiger6/lytgos 这个仓库"`，Agent 自己调 HTTP Tool 拿到仓库信息，给出 star 数与主语言，并能连续多轮。

## What Changes

- `lytgos init`：创建 `.lytgos/` 工作区，共 9 项——6 个目录（`agents/`、`skills/`、`output/`、`memory/`、`sessions/`、`logs/`）+ 3 个 Bootstrap 文件（`AGENTS.md`、`SOUL.md`、`USER.md`）；幂等，已存在的不覆盖
- `AGENT.md` frontmatter 解析：`.lytgos/agents/<name>/AGENT.md` → `Profile` 对象；`lytgos profile create / list / show / delete` 四个命令
- Provider 显式映射：配置文件声明 `provider name → 连接参数`，启动时构造 `Map<String, ChatModel>`；先接一个 provider（DeepSeek 或 Kimi）
- ReAct 循环：`ReActLoop` + `PromptBuilder` + `ToolExecutor`，最大迭代次数可配（默认 10）
- 内置 `http_get` Tool，域名白名单校验；演示场景为查 GitHub 仓库信息（`api.github.com`），不做天气查询
- `CliChannel` + `lytgos chat`，支持多轮与 `--message` 单条模式
- Session 内存版（第 4 周切 SQLite）
- 新增验收脚本 `harness/checks/07-week1-acceptance.ps1`

## Decisions

### `ChatModel.call()` 而非 `ChatClient`

设计文档 `DemandAnalysis.md` 5.3 写的是「直接基于 Spring AI Alibaba 的 `ChatClient` 实现」，与 `AGENTS.md` 4.1 直接冲突。**以 `AGENTS.md` 为准，本 change 只用 `ChatModel.call(prompt)`。**

理由：`ChatClient` 会通过 `ToolCallingAdvisor` + `ToolCallingManager` 自动驱动工具调用循环，工具执行绕过了 lytgos 自己的 `ToolExecutor`，配额、白名单、沙箱、审计全部挂不上去。这不是实现偏好问题，是产品定位问题。

`AGENTS.md` 第 2 条要求实现者发现规格有误时修改 proposal 并说明理由——这里按那条办：**`DemandAnalysis.md` 5.3 的表述需要后续修订**，本 change 不直接改 `docs/design/`（该目录不入库）。

### Provider 映射用配置驱动的显式 Map

禁止 `@ConditionalOnBean`、`ObjectProvider<ChatModel>`、`getBeansOfType` 之类的类型扫描。多引一个 starter 就凭空多出一个 provider，配置驱动会退化成 classpath 驱动。

映射在启动时从 `lytgos.providers` 配置节构造，构造完不可变。找不到指定 provider 时启动即失败，报错列出已注册的 provider 名。

### 密钥只从环境变量注入

`.lytgos/` 工作区由 `lytgos init` 生成在用户目录下，含 Provider 配置与长期记忆，**该目录已加入 `.gitignore`，绝不入库**。

API key 只以 `${DEEPSEEK_API_KEY}` 占位形式出现在配置文件里，真实值由环境变量注入。`AGENT.md` 的 frontmatter 不承载密钥。

### Session 先内存后持久化

第 1 周 `Session` 只做内存实现（`ConcurrentHashMap` 按 `channel + user + agent` 联合键）。第 4 周把它换成 SQLite 实现时，`Session` 接口不变，只换 `SessionStore` 实现。

这样第 1 周的验收不依赖 SQLite，第 4 周的改造也不会推翻第 1 周的结构。

## Impact

新增：

- `lytgos-core`：`Session`、`Profile`、`ReActLoop`、`PromptBuilder`、`ToolExecutor`、`SandboxChecker`
- `lytgos-persona`：`AgentLoader`（frontmatter 解析）
- `lytgos-provider`：`ProviderRegistry`、`ChatModelFactory`
- `lytgos-tool`：`HttpTool`、`ToolRegistry`
- `lytgos-channel`：`CliChannel`
- `lytgos-cli`：`init` / `chat` / `profile` 三个命令组
- `harness/checks/07-week1-acceptance.ps1`

修改：

- `lytgos-boot`：装配 `ProviderRegistry`、`ToolRegistry`、`AgentLoader`
- 根 `pom.xml`：如引入 `picocli`、`snakeyaml` 则统一版本

不影响：`lytgos-web`、`lytgos-storage`、`lytgos-memory`、`lytgos-knowledge` 本 change 不动。
