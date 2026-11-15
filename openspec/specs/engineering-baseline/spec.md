# engineering-baseline Specification

## Purpose

定义 lytgos 仓库的合并准入门禁：任何进入主干的代码必须通过格式、编码规约、静态缺陷与安全、依赖漏洞四层自动检查，并通过 CI 强制执行，使质量约束由机器把关而非人审自觉。

## Requirements

### Requirement: 格式门禁

所有 Java 源文件 MUST 通过 google-java-format（GOOGLE 风格）校验。`mvn verify` 发现未格式化文件时 MUST 构建失败，并给出可直接执行的修复命令。

#### Scenario: 未格式化文件导致构建失败
- **WHEN** 开发者提交了一个未经格式化的 Java 文件并执行 `mvn verify`
- **THEN** 构建在格式校验阶段失败，输出列出违规文件路径与 `mvn spotless:apply` 修复提示
- **THEN** 修复后重新执行 `mvn verify` 通过

### Requirement: 编码规约门禁

所有 Java 源码 MUST 通过阿里 P3C 规则集（ali-pmd / ali-concurrent / ali-exception）与 Checkstyle google_checks 校验。任一违规 MUST 导致 `mvn verify` 失败，违规输出 MUST 包含文件名、行号与规则名。

#### Scenario: 规约违规阻断构建
- **WHEN** 代码中出现 P3C 规则禁止的写法（如捕获异常后不处理）
- **THEN** `mvn verify` 在 PMD 阶段失败并定位到具体行
- **THEN** 按提示修正后构建恢复通过

### Requirement: 静态缺陷与安全门禁

所有模块 MUST 通过 SpotBugs（effort Max、threshold Low）与 Find Security Bugs 扫描。发现缺陷 MUST 导致构建失败。确属误报的条目 MUST 写入仓库内的豁免文件并注明理由，豁免之外不得放行。

#### Scenario: 潜在缺陷被拦截
- **WHEN** 代码引入一处 SpotBugs 可检出的缺陷（如未关闭的资源）
- **THEN** `mvn verify` 失败并在报告中给出缺陷类型与位置

#### Scenario: 豁免必须留痕
- **WHEN** 某条报告经核实为误报需要豁免
- **THEN** 该条目出现在 `spotbugs-exclude.xml` 中且带有注释说明理由
- **THEN** 未列入豁免的其他缺陷仍然导致构建失败

### Requirement: 依赖漏洞门禁

OWASP Dependency-Check MUST 以独立 profile `security` 提供，默认构建不执行。启用时 CVSS 分值达到或超过 7 的依赖 MUST 导致构建失败；误报豁免 MUST 写入 suppressions 文件并注明理由。CI MUST 在每次合并请求上执行该 profile。本地一键验收 `harness\run-all.ps1` MUST 同样覆盖该门禁。检出高危依赖时 MUST 优先升级到修复版本；确实无法升级才允许豁免，且豁免 MUST 限定到 CVE 编号与依赖坐标，并写明无法升级的原因与复查日期。

#### Scenario: 引入高危依赖被拦截
- **WHEN** 以 `security` profile 构建且依赖树中存在 CVSS 7 及以上的已知漏洞
- **THEN** 构建失败并输出漏洞编号、依赖坐标与 CVSS 分值

#### Scenario: 默认构建不受影响
- **WHEN** 开发者执行 `mvn package` 或 `mvn verify` 且未启用 `security` profile
- **THEN** 依赖漏洞扫描不执行，构建耗时与未接入该门禁时一致

#### Scenario: 本地一键验收覆盖依赖漏洞
- **WHEN** 开发者执行 `harness\run-all.ps1`
- **THEN** 其中包含一道依赖漏洞闸门，以 `security` profile 执行 Dependency-Check 并纳入总闸门的三态判定
- **THEN** 存在 CVSS 7 及以上依赖时总闸门返回失败，而不是只报「其余闸门全绿」

#### Scenario: 豁免前先尝试升级
- **WHEN** 某个 transitive 依赖被检出 CVSS 7 及以上漏洞且上游尚未跟进修复版本
- **THEN** 先在依赖管理段锁定上游已修复的可用版本
- **THEN** 只有在没有可用修复版本时才写入豁免，并附 CVE 编号、依赖坐标、无法升级的原因与复查日期

### Requirement: CI 强制门禁

每个合并请求 MUST 在 GitHub Actions 上执行完整校验：JDK 21 环境下的 `mvn -B verify`，以及独立的 `security` profile 扫描。任一门禁失败 MUST 使检查状态为失败，且该状态 MUST 被合并规则视为阻断条件。

#### Scenario: 门禁失败阻断合并
- **WHEN** 合并请求中存在格式或规约违规
- **THEN** CI 校验任务失败
- **THEN** 在修复并重新通过之前，该合并请求不满足合并条件

#### Scenario: 全绿允许合并
- **WHEN** 合并请求的全部校验任务通过
- **THEN** 检查状态为成功，可以进入人工审阅与合并

### Requirement: 本地提交前校验

仓库 MUST 提供可一键安装的 pre-commit hook。hook 在提交前 MUST 执行格式校验，发现未格式化文件时 MUST 拒绝提交并提示修复命令。hook MUST NOT 自动改写工作区文件。

#### Scenario: 提交未格式化代码被拒绝
- **WHEN** 开发者安装 hook 后在本地 `git commit` 包含未格式化文件
- **THEN** 提交被拒绝，提示运行 `mvn spotless:apply` 后重试
- **THEN** 工作区文件保持原样，不被 hook 自动改写

#### Scenario: 已格式化代码正常提交
- **WHEN** 待提交内容已通过格式校验
- **THEN** hook 校验通过，提交正常完成
