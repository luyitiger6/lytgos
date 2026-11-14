# Design

## Context

动机与范围见 `proposal.md`。本设计只补充影响实现方式的现状约束：

- 父 pom 当前没有任何 `pluginManagement` / `build/plugins`，12 个子模块各自声明依赖；模块清单为 core / persona / provider / memory / knowledge / tool / storage / channel / web / cli / boot / test，与 SKILL 里的旧 9 模块口径不同，以实际为准。
- Spring Boot 版本为 4.1.2（SKILL 写 3.x），所有插件版本需按 Boot 4 / JDK 21 兼容性选取。
- 现有 76 个 Java 文件统一 4 空格缩进；切换 GOOGLE 风格（2 空格、100 列）是一次性全量重排。
- harness 01/02 闸门分别执行 `mvn -B -q -DskipTests compile` 与 `mvn -B test`，因此质量插件只要绑定在 `verify` 阶段就不会拖慢 harness。
- 现有 `p3c-pmd:2.1.1` 编译于 PMD 6 API，与 PMD 7 不兼容，这是版本锁定的硬约束。

## Goals / Non-Goals

**Goals:**

- 六层门禁（格式 / 规约 / 静态缺陷 / 依赖漏洞 / CI / pre-commit）以最小配置面接入，全部由父 pom 统一下发、子模块零配置继承。
- 日常开发回路不受拖累：`mvn package`、`mvn test` 保持现状，重量级检查只在 `verify` 或 `security` profile 执行。
- 全量格式化的 diff 与业务改动彻底隔离，可独立审阅、独立回滚。

**Non-Goals:**

- 不做日志、监控、虚拟线程、API 骨架（下一个 change）。
- 不引入 SonarQube、CodeQL 等需要服务端或额外账户的检查。
- 不改动任何运行时行为与业务代码逻辑。

## Decisions

### D1 格式风格用 GOOGLE（2 空格），全量重排单独提交

- 选定 `googleJavaFormat` + `<style>GOOGLE</style>`，缩进 2 空格、行宽 100。
- 备选：AOSP（4 空格，与现有代码一致、diff 最小）。放弃原因：偏离 SKILL 明确要求，且 google-java-format 默认产出即 GOOGLE 风格，改 AOSP 属于人为增加配置项。
- 执行顺序强制：先格式化并单独提交，再接入其余插件。否则后续插件提交会掺杂数千行格式噪音。
- 配套生成 `.git-blame-ignore-revs` 记录格式化提交哈希，`git blame` 可直接跳过。

### D2 质量插件绑定 `verify`，父 pom 单点配置

- 全部插件配置在父 pom 的 `build/plugins` 中（非仅 `pluginManagement`），子模块零声明自动继承，避免漏配。
- Spotless / Checkstyle / PMD / SpotBugs 的 `check` 目标默认绑定 `verify`，不改绑定阶段：`package` 与 `test` 不受影响，harness 01/02 行为不变。
- 备选：绑定 `package`。放弃原因：会让日常打包变慢，且 harness 01 会受影响。

### D3 OWASP Dependency-Check 独立 profile `security`

- 配置在父 pom 的 `<profiles>` 中，默认不激活；`mvn verify -Psecurity` 本地按需，CI 必跑（带 `NVD_API_KEY` secret）。
- `failBuildOnCVSS` 设为 7。
- NVD 数据目录通过属性 `dependency-check.dataDirectory` 暴露，CI 用 actions/cache 缓存。
- 备选：直接挂 verify。放弃原因：每次构建多等数分钟，开发回路不可接受。
- **版本与首次数据实测（2026-11-15）**：
  - dependency-check-maven **13.0.0 在无 NVD API key 环境直接失败**：`NvdApiException: Invalid API Key, length of 0 too short to provided a masked partial key`，重试 22 次后 BUILD FAILURE。故锁 **12.2.2**（12.x 允许无 key 走限速通道）。
  - 12.2.2 无 key 时走 NVD API 2.0：官方全量 **425,749 条**，按 2000 条/页分页。默认 6 秒间隔在数分钟内即被 **HTTP 429** 限流打断；调到 15 秒间隔后实测约 2000 条/分钟，全量约 **3.5 小时**。
  - 结论：首次全量 NVD 下载**后置到 CI security job**（`NVD_API_KEY` secret + dataDirectory 缓存），不在本地跑完。无数据时插件以 `NoDataException` 直接 BUILD FAILURE，不会假绿。

### D4 P3C 版本组合锁死 PMD 6

- `maven-pmd-plugin` 显式声明 `com.alibaba.p3c:p3c-pmd:2.1.1` 与 `net.sourceforge.pmd:pmd-java:6.55.0` 依赖，覆盖插件默认的 PMD 7。
- p3c-pmd 2.1.1 的 jar 内没有 `ali-pmd.xml` 聚合规则集，实际是 10 个分组文件；仓库内 `config/pmd/p3c-ruleset.xml` 负责聚合，并集中排除 3 条与项目基线冲突的规则（见 D8）。
- **ASM 覆盖是硬要求**：PMD 6.55 自带的 ASM 9.4 只能读 Java 20 字节码，扫描 JDK 21 编译产物时每个文件都抛 `Unsupported class file major version 65`，报告里只有 `<error>` 没有 `<violation>`，`failOnViolation` 管不到——门禁表面绿色、实际一个字都没扫（AGENTS 原则 4 的典型伪造绿灯）。因此插件依赖显式覆盖 `org.ow2.asm:asm:9.7.1`，并在验收时核对 `target/pmd.xml` 无 `<error>` 节点。
- `targetJdk` 只能设 20（PMD 6.55 上限），不影响读取 21 字节码；PMD 6 尚未支持 21 语法，后续若用到高版本语法需加 `-Dpmd.skip` 的落盘例外。
- 备选：升级到 PMD 7 原生规则替代 P3C。放弃原因：P3C 是 SKILL 指定规约，且 PMD 7 无对等的阿里规则集。
- 已知代价：PMD 6 已停止主线维护，属于为满足规约要求接受的版本债，记录在此。

### D5 pre-commit 用原生 git hook，不引入额外运行时

- 提供 `scripts/hooks/pre-commit`（sh 薄脚本，只调 `mvn -q spotless:check`）与 `scripts/install-hooks.ps1`（把 hook 拷进 `.git/hooks/`）。
- 只检查不改写：失败时提示 `mvn spotless:apply`，由开发者自己执行。
- 备选：Python `pre-commit` 框架。放弃原因：为本仓库引入 Python 运行时依赖，收益不抵成本；备选 husky 需要 Node 同理。
- Windows 下 Git for Windows 自带 sh，hook 可直接运行；安装脚本用 PowerShell 适配本机主环境。

### D6 豁免必须留痕

- `config/checkstyle/suppressions.xml`：Checkstyle 规则级抑制，当前仅 `SummaryJavadoc` 与 `MissingJavadocMethod` 两条，理由写在文件头（中文注释句号与英文句点规则冲突、Javadoc 不强制全覆盖）。
- `config/pmd/p3c-ruleset.xml`：P3C 规则级排除，当前仅 3 条，理由写在文件头（见 D8）。新增豁免必须写清规则名与理由，不允许按文件批量放水。
- `spotbugs-exclude.xml`：按类 + 缺陷模式粒度豁免，每条带 XML 注释说明理由。
- `dependency-check-suppressions.xml`：按 CVE + 依赖坐标豁免，同样要求理由。
- 单点误报（只影响一两处）仍用行内 `@SuppressWarnings` 并在提交说明写明原因，避免为个案放宽整条规则；当前实例是 `McpServerManager.installShutdownHook` 的 `AvoidManuallyCreateThreadRule`（shutdown hook 必须是独立 Thread，线程池不适用）。

### D7 CI 结构：一个 verify job + 一个 security job

- `verify.yml` 触发条件：`pull_request` 与 `push` 到 `main`。
- job 1：JDK 21（temurin）+ Maven 缓存 + `mvn -B verify`。
- job 2：同上环境 + `mvn -B verify -Psecurity` + NVD 缓存目录。
- 两 job 并行，互不阻塞；只有全部通过才得到绿色检查状态。
- 依赖版本兼容底线：spotbugs-maven-plugin ≥ 4.8（JDK 21 支持）、google-java-format ≥ 1.17（JDK 21 支持）、dependency-check-maven 12.x。具体版本号以实施时官方最新稳定版为准，锁定后回填本节。
- **已锁定（2026-11-15 回填）**：spotless 2.44.5 + google-java-format 1.27.0、maven-checkstyle-plugin 3.6.0、maven-pmd-plugin 3.21.2 + p3c-pmd 2.1.1 + pmd-java 6.55.0 + asm 9.7.1、spotbugs-maven-plugin 4.10.5.0 + findsecbugs 1.14.0、**dependency-check-maven 12.2.2**（13.0.0 无 NVD key 无法初始化，见 D3）。

### D8 P3C 与项目基线的三处冲突按规则级排除，异常类按规约改名

2026-11-14 用户拍板。首次全量运行报出 74 条违规，处置如下：

- 规则级排除 3 条（58 条违规）：`ClassMustHaveAuthorRule`（@author 与 git 提交记录重复，Google 风格不作要求）、`AbstractMethodOrInterfaceMethodMustUseJavadocRule`（与已拍板的 Javadoc 策略冲突，Checkstyle 侧已抑制同源规则）、`UndefineMagicConstantRule`（字符串字面量与测试断言误报）。排除集中写在 `config/pmd/p3c-ruleset.xml`，不逐处行内注解。
- 改名 5 个嵌套异常类（`SandboxViolation` → `SandboxViolationException` 等），同步更新 26 个引用文件，符合 P3C `ExceptionClassShouldEndWithExceptionRule`。异常消息字面量（如 `sandbox 拒绝：`）保持不变——验收脚本依赖它。
- 修 11 条真问题：`EqualsAvoidNullRule` 4 处、`ConstantFieldShouldBeUpperCaseRule` 4 处、`EnumConstantsMustHaveCommentRule` 1 处、`AvoidComplexConditionRule` 1 处；`AvoidManuallyCreateThreadRule` 1 处属误报，行内豁免。

### D9 SpotBugs 存量报告按「修代码优先、框架误报进 exclude」处置

2026-11-14 首次全量扫描（effort Max / threshold Low / includeTests=true）报 41 条：

- 修代码 27 条：日志注入 12 条统一引入 `io.lytgos.core.util.LogSafe.sanitize`（模型输出、远端 MCP 工具名、会话文件异常都会进日志，审计日志不能被伪造行污染）；Unicode 大小写 3 条改用 `regionMatches(true, ...)` / `Locale.ROOT`；可能空指针 7 条（提取局部变量或换成非空来源）；返回内部集合 1 条改 `Set.copyOf`；未读字段、基础异常声明、匿名类可静态化、`getResource` 继承风险各 1 条。
- 类 + 模式豁免 14 条（`spotbugs-exclude.xml` 逐条写理由与重审条件）：EI_EXPOSE_REP2 6 条（保存 Spring 容器共享单例）、THROWS_METHOD_THROWS_RUNTIMEEXCEPTION 5 条（catch 清理后原样 rethrow）、VA_FORMAT_STRING_USES_NEWLINE 2 条（text block 是文件内容模板，`%n` 会跟着平台变 CRLF）、NP_NULL 1 条（`Binder.bind` 契约非 null）。
- 派生豁免 1 条类级：McpServerManager 的 7 个日志点已全部清洗，但 FindSecBugs 的 taint 分析不识别自定义 sanitizer，对该类加 `CRLF_INJECTION_LOGS` 豁免；代价是新日志若引入未清洗参数会被同一条豁免放行，review 必须人工确认（已写入 exclude 注释）。
- 门禁有效性验证：临时植入一处未关闭 `FileInputStream`（`LeakProbe`）后 `spotbugs:check` 报 `OS_OPEN_STREAM` 等 3 条并 BUILD FAILURE；删除探针后 `BugInstance size is 0`、BUILD SUCCESS。
- **补扫修正（2026-11-15）**：组 3 清单漏报 2 条 `SIC_INNER_SHOULD_BE_STATIC_ANON`（`ReActLoopTest` 里两个一次性匿名 `Tool` 实现）。组 4 跑默认构建时 SpotBugs 重新分析 `lytgos-core` test-classes 并报出，已按同口径提取为静态内部类 `FailingTool` / `SandboxBlockedTool`，修复后 12 个模块 `spotbugsXml` 全部 0 条。核对 `FindBugsSummary total_classes=43`（31 主类 + 12 测试类），确认测试类确实在分析范围内。**教训**：单模块 `compile spotbugs:check` 不编译测试类，不能用来断言「整模块 0 条」；模块级计数必须以 `mvn verify` 后的报告为准。

## Risks / Trade-offs

- [P3C 首次运行报出大量存量违规] → 实测 74 条：58 条为规则与项目基线冲突（规则级排除并留痕），16 条为真问题（11 条修代码、5 条异常类改名，另 1 处 shutdown hook 行内豁免）；不允许静默改规则集。
- [全量格式化让 `git blame` 失效] → 格式化单独提交 + `.git-blame-ignore-revs`；审阅该提交时只看 `--stat`。
- [SpotBugs threshold Low 在 Spring 代理/反射代码上误报（如 EI_EXPOSE_REP）] →  实测存量 43 条已按 D9 处置完毕（修 29 条 + 豁免 14 条，含补扫出的 2 条匿名类），误报进 `spotbugs-exclude.xml` 并写明理由；不放宽 threshold。
- [McpServerManager 的类级 CRLF 豁免会一并放行未来新增的未清洗日志] → 豁免注释已写明 review 要求；新增日志必须走 `LogSafe.sanitize`。
- [dep-check 首次 NVD 下载依赖 NVD_API_KEY] → 实测：13.0.0 无 key 直接失败；12.2.2 默认 6 秒间隔被 429 限流，15 秒间隔全量约 3.5 小时。故首次下载放 CI（NVD_API_KEY secret + actions/cache 缓存 dataDirectory），本地不跑；无数据时插件 `NoDataException` BUILD FAILURE，不假绿。
- [Windows 与 Linux 换行/编码差异导致 spotless 不一致] → `.editorconfig` 统一 UTF-8 / LF；Spotless 设 `lineEndings=GIT_ATTRIBUTES`，与现有 `.gitattributes` 对齐。
- [Checkstyle google_checks 与 google-java-format 规则重叠] → 由 gjf 先产出，Checkstyle 只做兜底，不另设冲突规则；若出现冲突以 gjf 为准并调整 Checkstyle 配置。

## Migration Plan

按可独立回滚的提交顺序推进：

1. 格式化（配置 + 全量重排 + `.git-blame-ignore-revs`）→ 回滚即 `git revert` 单提交。
2. Checkstyle + PMD/P3C（含 ASM 覆盖、定制 ruleset、存量违规修复、异常类改名）。
3. SpotBugs + FindSecBugs（含豁免骨架）。
4. dep-check profile + suppressions 骨架。
5. CI workflow。
6. pre-commit hook + 安装脚本。
7. 文档（README 开发章节、harness README 说明 verify 与 security 的关系）。

任何一步失败都不影响前一步的产物；1 之后的提交只增配置与违规修复，不改行为。

## Open Questions

| # | 级别 | 问题 | 影响任务 | 默认倾向 |
|---|---|---|---|---|
| Q1 | ~~一般~~ 已定 | P3C 存量违规若超过 50 条，本 change 内全修还是按文件豁免、后续 change 偿债 | 2.3 | 已定（2026-11-14）：74 条中规则级排除 3 条规则（58 条），其余 16 条全部修完；不登记偿债 |
| Q2 | 一般 | `lytgos-test` 模块只有测试代码，是否同样参与 SpotBugs/PMD | 3.1 | 参与，规则与主代码一致 |
| Q3 | 一般 | pre-commit 除格式外是否也跑 `mvn -q compile` | 6.1 | 只跑格式，编译留给 CI，保证提交在 10 秒内完成 |
| Q4 | 一般 | CI 是否对任意分支 push 触发 | 5.1 | 仅 `pull_request` 与 `push main`，节省分钟数 |
