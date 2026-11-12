## Why

仓库现在有 11 个模块和 15 个 Java 文件，全部是启动类与 `package-info`，没有一条能从输入走到输出的链路。核心阶段 4 周的第 1 周要解决的就是这一条：**CLI 输入 → ReAct 循环 → LLM → 工具调用 → 返回**。

这条链路跑通之前，Memory、Web Service、SQLite 审计都没有落脚点——它们都是挂在这条链路上的能力，而不是先建好再往里接。

第 1 周的可演示成果是：`lytgos chat "北京今天天气怎么样"`，Agent 自己调 HTTP Tool——先查经纬度再查天气——给出温度与穿衣建议，并能连续多轮。

## What Changes

- `lytgos init`：创建 `.lytgos/` 工作区，共 10 个顶层条目——6 个目录（`agents/`、`skills/`、`output/`、`memory/`、`sessions/`、`logs/`）+ 3 个 Bootstrap 文件（`AGENTS.md`、`SOUL.md`、`USER.md`）+ `config.yaml`；幂等，已存在的不覆盖
- `AGENT.md` frontmatter 解析：`.lytgos/agents/<name>/AGENT.md` → `Profile` 对象；`lytgos profile create / list / show / delete` 四个命令
- Provider 显式映射：配置文件声明 `provider name → 连接参数`，启动时构造 `Map<String, ChatModel>`；先接一个 provider（DeepSeek 或 Kimi）
- ReAct 循环：`ReActLoop` + `PromptBuilder` + `ToolExecutor`，最大迭代次数可配（默认 10）
- 内置 `http_get` Tool，域名白名单校验；演示场景为查天气，走 Open-Meteo 的两个公开接口（`geocoding-api.open-meteo.com` 查经纬度、`api.open-meteo.com` 查天气），均无需 API key
- `CliChannel` + `lytgos chat`，支持多轮与 `--message` 单条模式
- Session 内存 + JSON 落盘到 `.lytgos/sessions/<key>.json`（第 4 周切 SQLite，只换实现类）
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

### Session 用 JSON 落盘而非纯内存

设计文档写的是「Session 内存版，第四周加 SQLite」，但纯内存 Session 活不过进程，而 `--message` 单条模式发一条就退出——两次 `--message` 之间物理上不可能共享上下文。

因此第 1 周的 `SessionStore` 采用「内存 + JSON 落盘」：键为 `channel + user + agent`，落盘到 `.lytgos/sessions/<key>.json`，读写封在同一实现类里。第 4 周换成 SQLite 时只换这一个类，`Session` 接口不动。

这与设计文档「内存版」的表述有出入，是有意为之：不这么做，「单条模式与交互模式共用 Session」这条口径无法成立。

### 工作区顶层条目 9 → 10：补上 `config.yaml`

初版写的是 9 项（6 目录 + 3 Bootstrap），漏了 `config.yaml`——而 `provider name → ChatModel` 的显式映射必须从一份配置文件读，两处口径互相矛盾：没有配置文件，`lytgos.providers` 配置节就没有载体。

实施时按「配置驱动」这条硬规则补齐了 `config.yaml`，所以顶层条目为 10，加上嵌套的 `memory/MEMORY.md` 与 `agents/default/AGENT.md`，`initialize()` 共创建 12 个条目。`spec.md` 的 Requirement 与 Scenario 已同步修正。

### 第 1 周装配在 `lytgos-cli`，不在 `lytgos-boot`

任务 4.4 原写「`lytgos-boot` 装配」，实际装配根是 `lytgos-cli` 的 `CliContext`。

理由：`lytgos-boot` 是 Spring Boot 可执行 jar 的宿主，它的启动路径是 `LytgosApplication`；而第 1 周的可演示入口是 `lytgos chat` 命令行，它不走 Spring 容器。把装配写在 `lytgos-cli`，`java -jar lytgos-cli-boot.jar chat` 和 `java -jar lytgos.jar chat` 共用同一份装配代码，不会出现两套不一致的注册表。

第 3 周 Web Service 接入时，`CliContext` 的装配逻辑上提到 `lytgos-boot`，`lytgos-cli` 改为从容器取——那时才需要真正的容器装配。

### 演示场景改回天气，推翻 `340747b` 的 GitHub 口径

提交 `340747b` 曾把演示场景定为「查 GitHub 仓库信息（`api.github.com`），不做天气查询」，`dddddee` 又清理了 proposal 里的残留。本 change 把场景改回天气，**这是有意推翻前者，不是遗漏**。

理由两条：

1. **MCP 衔接需要同能力双路径对照。** 第 2 周要把同一个演示换成 `gbrigandi/mcp-server-openmeteo`，形成「同一能力，内置工具 / MCP 两条路径」的对照。若场景是 GitHub，第 2 周需要另找一套 GitHub MCP，对照关系就断了。
2. **无认证限额。** Open-Meteo 免费且无需 API key，GitHub 未认证 API 限 60 次/小时——`07` 每次验收要跑多轮对话，很容易碰到限额，把「代码问题」与「配额问题」混在一起，诊断成本高。

代价与约束：为了验证 ReAct 往返，需要两次工具调用（geocoding 查经纬度 → forecast 查天气）。`07` 第 3 条因此断言「至少两次 `http_get` + 输出含温度数值」。这条断言验的不只是「能调工具」，而是**工具结果回填后还能再调一次**——这是本 change 的核心能力，不能降到一次。代价是依赖模型选择「先查经纬度」的路径；若将来模型直接用已知坐标一次到位，应重新讨论断言写法，而不是放宽次数。

域名白名单只为这一个场景填值：`geocoding-api.open-meteo.com` 与 `api.open-meteo.com`。换场景仅改 `.lytgos/config.yaml`，`SandboxChecker` 不动。

## Impact

新增：

- `lytgos-core`：`Session`、`Profile`、`ReActLoop`、`PromptBuilder`、`ToolExecutor`、`SandboxChecker`
- `lytgos-persona`：`AgentLoader`（frontmatter 解析）
- `lytgos-provider`：`ProviderRegistry`、`ChatModelFactory`
- `lytgos-tool`：`HttpTool`、`FileTool`（只读 `file_read`）、`ToolRegistry`
- `lytgos-channel`：`CliChannel`
- `lytgos-cli`：`init` / `chat` / `profile` / `provider` / `tool` 五个命令组，以及装配根 `CliContext`
- `harness/checks/07-week1-acceptance.ps1`

修改：

- 根 `pom.xml`：`picocli`、`snakeyaml`、`slf4j-api` 统一版本
- `harness/checks/03-static-guard.ps1`：`lytgos-cli` / `lytgos-channel` 豁免「禁 `System.out`」一条（它们的 stdout 就是产品输出通道）
- `harness/checks/04-dependency-direction.ps1`：由空壳改为 6 条实规则
- `AGENTS.md` 与 `harness/README.md`：同步闸门清单

不影响：`lytgos-web`、`lytgos-storage`、`lytgos-memory`、`lytgos-knowledge` 本 change 不动。
