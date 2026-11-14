# Proposal

## Why

工程地基缺一大半。对照 `lytgos-init` SKILL 的 10 步初始化清单，本项目只达标 2 项（多模块骨架、敏感配置 ENV 占位），格式、编码规约、静态缺陷、依赖漏洞、CI、pre-commit 六层门禁全部缺失。

现在是补的最后窗口：当前 76 个 Java 文件、4665 行、16 个测试类，全量格式化的 diff 规模还在一屏可审的范围内。第 3 周 Web Service、第 4 周 SQLite 与审计落库会成倍放大代码量；拖到第 11 周收口再补，一次性重排将覆盖上千文件，且期间所有新增代码都会成为待返工存量。

另一个直接动机是 AGENTS 原则 4「禁止伪造绿灯」目前只有 03 静态闸门（grep `@Disabled` / `TODO`）一层保障。格式漂移、规约违规、空指针与资源泄漏、依赖 CVE 全部没有机器拦截，只能靠人审——随着模块变多，这层人力审查必然失守。

## What Changes

- 父 pom 引入统一质量插件链，全部绑定 `verify`，任一失败即构建失败：
  - **Spotless** + google-java-format（GOOGLE 风格，2 空格缩进）管格式
  - **Checkstyle**（`google_checks.xml`）兜底格式之外的检查
  - **PMD** + 阿里 P3C ruleset（`ali-pmd` / `ali-concurrent` / `ali-exception`）管编码规约
  - **SpotBugs**（effort Max / threshold Low）+ **Find Security Bugs** 管静态缺陷与安全
- **OWASP Dependency-Check** 独立 profile `security`（CVSS ≥ 7 失败）：本地按需、CI 必跑
- **全量格式化**：76 个 Java 文件按 google-java-format 重排，单独一个纯格式提交；业务逻辑、测试断言零改动，全量测试保持绿
- 根目录新增 `.editorconfig`（2 空格、UTF-8、LF）
- 新增 GitHub Actions `verify.yml`：JDK 21 + Maven 缓存 + `mvn -B verify`，security job 单独执行并缓存 NVD 数据
- 新增可一键安装的 pre-commit hook：提交前跑格式校验，只检查不改写
- 豁免机制：`spotbugs-exclude.xml` 与 `dependency-check-suppressions.xml` 骨架，任何豁免条目必须写明理由
- **非目标**（留给下一个 change `add-observability-baseline`）：结构化日志、actuator/micrometer、虚拟线程开关、springdoc 与统一响应骨架；不改 AGENTS.md 硬规则，不改 `openspec/config.yaml`

## Capabilities

### New Capabilities

- `engineering-baseline`: 合并准入的工程门禁契约——格式、编码规约、静态缺陷与安全、依赖漏洞、CI 强制、本地提交前校验六项要求，任何一项失败即阻断合入。

### Modified Capabilities

（无。本 change 不改变任何运行时系统行为。）

## Impact

- **构建**：父 pom 与全部 12 个模块（插件由父级继承）；`mvn verify` 耗时显著增加，`mvn package` 不受影响（插件绑定在 verify 阶段）
- **代码**：76 个 Java 文件被一次性纯格式重排，这是本 change 唯一大面积 diff，单独提交便于 `git blame --ignore-rev` 跳过
- **新增文件**：`.editorconfig`、`.github/workflows/verify.yml`、`scripts/hooks/pre-commit`、`scripts/install-hooks.ps1`、`spotbugs-exclude.xml`、`dependency-check-suppressions.xml`
- **开发者体验**：新增「提交前格式校验」约定；本地首次执行 `-Psecurity` 需下载 NVD 数据库（仅按需）
- **harness**：01/02 闸门使用 `compile` / `test` 阶段，不触发绑定在 `verify` 的插件，行为不变
- **风险**：P3C 与 Checkstyle 首次运行可能报出较多存量违规，处理方式是修代码优先；确属误报的条目进豁免文件并写理由，不允许靠改规则集放水
