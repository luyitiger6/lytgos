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

- 配置在父 pom 的 `<profiles>` 中，默认不激活；`mvn verify -Psecurity` 本地按需，CI 必跑。
- `failBuildOnCVSS` 设为 7。
- NVD 数据目录通过属性暴露，CI 用 actions/cache 缓存，避免每次跑十几分钟的首次下载。
- 备选：直接挂 verify。放弃原因：每次构建多等数分钟，开发回路不可接受。

### D4 P3C 版本组合锁死 PMD 6

- `maven-pmd-plugin` 显式声明 `com.alibaba.p3c:p3c-pmd:2.1.1` 与 `net.sourceforge.pmd:pmd-java:6.55.0` 依赖，覆盖插件默认的 PMD 7。
- ruleset 引用 `rulesets/java/ali-pmd.xml`、`ali-concurrent.xml`、`ali-exception.xml`（来自 p3c-pmd jar 内资源）。
- 备选：升级到 PMD 7 原生规则替代 P3C。放弃原因：P3C 是 SKILL 指定规约，且 PMD 7 无对等的阿里规则集。
- 已知代价：PMD 6 已停止主线维护，属于为满足规约要求接受的版本债，记录在此。

### D5 pre-commit 用原生 git hook，不引入额外运行时

- 提供 `scripts/hooks/pre-commit`（sh 薄脚本，只调 `mvn -q spotless:check`）与 `scripts/install-hooks.ps1`（把 hook 拷进 `.git/hooks/`）。
- 只检查不改写：失败时提示 `mvn spotless:apply`，由开发者自己执行。
- 备选：Python `pre-commit` 框架。放弃原因：为本仓库引入 Python 运行时依赖，收益不抵成本；备选 husky 需要 Node 同理。
- Windows 下 Git for Windows 自带 sh，hook 可直接运行；安装脚本用 PowerShell 适配本机主环境。

### D6 豁免必须留痕

- `spotbugs-exclude.xml`：按类 + 缺陷模式粒度豁免，每条带 XML 注释说明理由。
- `dependency-check-suppressions.xml`：按 CVE + 依赖坐标豁免，同样要求理由。
- Checkstyle / PMD 不提供文件级豁免配置，确需豁免时使用行内注解（`@SuppressWarnings`）并在提交说明中写明原因，以保证豁免可被 grep 审计。

### D7 CI 结构：一个 verify job + 一个 security job

- `verify.yml` 触发条件：`pull_request` 与 `push` 到 `main`。
- job 1：JDK 21（temurin）+ Maven 缓存 + `mvn -B verify`。
- job 2：同上环境 + `mvn -B verify -Psecurity` + NVD 缓存目录。
- 两 job 并行，互不阻塞；只有全部通过才得到绿色检查状态。
- 依赖版本兼容底线：spotbugs-maven-plugin ≥ 4.8（JDK 21 支持）、google-java-format ≥ 1.17（JDK 21 支持）、dependency-check-maven 12.x。具体版本号以实施时官方最新稳定版为准，锁定后回填本节。

## Risks / Trade-offs

- [P3C 首次运行报出大量存量违规] → 修代码优先；单条确属误报的用行内注解 + 提交说明豁免。若同一规则在多个文件里普遍误报，暂停并在实施时向用户报告，不允许静默改规则集。
- [全量格式化让 `git blame` 失效] → 格式化单独提交 + `.git-blame-ignore-revs`；审阅该提交时只看 `--stat`。
- [SpotBugs threshold Low 在 Spring 代理/反射代码上误报（如 EI_EXPOSE_REP）] → 进 `spotbugs-exclude.xml` 并写明理由；不放宽 threshold。
- [dep-check 首次 NVD 下载失败或超时] → 只影响 security job，不阻塞 verify；CI 缓存 + 重试一次。
- [Windows 与 Linux 换行/编码差异导致 spotless 不一致] → `.editorconfig` 统一 UTF-8 / LF；Spotless 设 `lineEndings=GIT_ATTRIBUTES`，与现有 `.gitattributes` 对齐。
- [Checkstyle google_checks 与 google-java-format 规则重叠] → 由 gjf 先产出，Checkstyle 只做兜底，不另设冲突规则；若出现冲突以 gjf 为准并调整 Checkstyle 配置。

## Migration Plan

按可独立回滚的提交顺序推进：

1. 格式化（配置 + 全量重排 + `.git-blame-ignore-revs`）→ 回滚即 `git revert` 单提交。
2. Checkstyle + PMD/P3C（含存量违规修复）。
3. SpotBugs + FindSecBugs（含豁免骨架）。
4. dep-check profile + suppressions 骨架。
5. CI workflow。
6. pre-commit hook + 安装脚本。
7. 文档（README 开发章节、harness README 说明 verify 与 security 的关系）。

任何一步失败都不影响前一步的产物；1 之后的提交只增配置与违规修复，不改行为。

## Open Questions

| # | 级别 | 问题 | 影响任务 | 默认倾向 |
|---|---|---|---|---|
| Q1 | 一般 | P3C 存量违规若超过 50 条，本 change 内全修还是按文件豁免、后续 change 偿债 | 2.3 | 全修；单文件误报超 10 条时改为豁免并登记偿债 |
| Q2 | 一般 | `lytgos-test` 模块只有测试代码，是否同样参与 SpotBugs/PMD | 3.1 | 参与，规则与主代码一致 |
| Q3 | 一般 | pre-commit 除格式外是否也跑 `mvn -q compile` | 6.1 | 只跑格式，编译留给 CI，保证提交在 10 秒内完成 |
| Q4 | 一般 | CI 是否对任意分支 push 触发 | 5.1 | 仅 `pull_request` 与 `push main`，节省分钟数 |
