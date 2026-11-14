# Tasks

## 1. 格式门禁与全量重排

- [x] 1.1 根目录新增 `.editorconfig`（Java 2 空格缩进、UTF-8、LF、文件末尾换行），验证：`mvn spotless:check` 对缩进不一致的文件报错且与 .editorconfig 口径一致
- [x] 1.2 父 pom 配置 `spotless-maven-plugin`（google-java-format GOOGLE、removeUnusedImports、importOrder、lineEndings=GIT_ATTRIBUTES），锁定 google-java-format 版本（≥1.17），验证：对现有未格式化代码执行 `mvn -q spotless:check` 失败并列出文件（预期红）
- [x] 1.3 执行 `mvn spotless:apply` 全量重排，验证：`mvn -q spotless:check` 通过
- [x] 1.4 行为未变验证：`mvn -B test` 退出码 0，surefire 用例总数与格式化前一致（16 个测试类）
- [x] 1.5 以单独的「纯格式」提交落库，并新建 `.git-blame-ignore-revs` 记录该提交哈希，验证：`git show --stat` 该提交只含空白/换行改动，`git config blame.ignoreRevsFile` 生效后 blame 跳过该提交

## 2. 编码规约门禁（Checkstyle + P3C）

- [x] 2.1 父 pom 配置 `maven-checkstyle-plugin`（内置 `google_checks.xml`、`failOnViolation=true`、控制台输出违规明细），验证：`mvn -B verify` 在 checkstyle 阶段执行；临时新增一行超长行号即失败，删除后恢复绿
- [x] 2.2 父 pom 配置 `maven-pmd-plugin`，显式依赖 `p3c-pmd:2.1.1` 与 `pmd-java:6.55.0`，引用 `ali-pmd` / `ali-concurrent` / `ali-exception` 三个 ruleset，验证：`mvn -B verify` 在 PMD 阶段执行并输出 P3C 规则报告（实现偏差：jar 内无 `ali-pmd.xml`，实际由 `config/pmd/p3c-ruleset.xml` 聚合 10 组规则；另需覆盖 `asm:9.7.1`，否则 JDK21 字节码全部解析失败，见 design D4）
- [x] 2.3 按 Q1 默认倾向处理首次运行的全部存量违规（修代码优先；误报用行内注解豁免并在提交说明写明理由），验证：`mvn -B verify` 退出码 0，违规清单与处理方式写入提交说明（实际：74 条 → 58 条规则级排除、11 条修代码、5 个异常类改名、1 条行内豁免，见 design D8）
- [x] 2.4 提交本组改动，验证：提交后 `mvn -B test` 仍全绿（确认规约修复未改变行为）

## 3. 静态缺陷与安全扫描

- [x] 3.1 父 pom 配置 `spotbugs-maven-plugin`（effort Max、threshold Low、`findsecbugs-plugin`）+ 根目录 `spotbugs-exclude.xml` 骨架（含豁免书写规范的注释），验证：`mvn -B verify` 在 spotbugs 阶段执行；临时引入一处未关闭资源即失败，恢复后变绿（实测：插件 4.10.5.0 + findsecbugs 1.14.0；临时 `LeakProbe` 报 `OS_OPEN_STREAM` 等 3 条且 BUILD FAILURE，删除后 `BugInstance size is 0`）
- [x] 3.2 按 Q2 默认倾向处理存量报告（含 `lytgos-test` 模块；修代码优先，误报进 exclude 并带理由），验证：`mvn -B verify` 退出码 0 且报告无未豁免缺陷（实际：41 条 → 修 27、类+模式豁免 14，见 design D9；13 模块 spotbugsXml 均为 0）
- [x] 3.3 提交本组改动，验证：提交后 `mvn -B test` 仍全绿

## 4. 依赖漏洞门禁

- [ ] 4.1 父 pom 配置 `security` profile：`dependency-check-maven`、`failBuildOnCVSS=7`、`dependency-check-suppressions.xml` 骨架、data directory 属性化，验证：`mvn -B verify -Psecurity` 能启动扫描并记录首次 NVD 下载耗时
- [ ] 4.2 处理扫描结果（高危依赖升级；无法升级的 CVE 进 suppressions 并写明理由与复查日期），验证：`mvn -B verify -Psecurity` 退出码 0
- [ ] 4.3 验证默认构建不受影响：`mvn -B verify`（不带 profile）不执行 dependency-check，耗时与 3.3 提交时相当
- [ ] 4.4 提交本组改动

## 5. CI 强制门禁

- [ ] 5.1 新增 `.github/workflows/verify.yml`：JDK 21 temurin + Maven 缓存 + `mvn -B verify`（job 1）与 `mvn -B verify -Psecurity` + NVD 缓存（job 2），触发条件按 Q4 默认倾向（`pull_request` 与 `push main`），验证：推送本分支后在 Actions 页面两个 job 均为绿（security job 首次因 NVD 下载较慢属正常）
- [ ] 5.2 验证阻断行为：临时在分支上提交一行未格式化代码并推送，确认 verify job 变红；revert 后重新推送变绿
- [ ] 5.3 提交本组改动

## 6. 本地提交前校验

- [ ] 6.1 新增 `scripts/hooks/pre-commit`（sh 薄脚本，只执行 `mvn -q spotless:check`，失败时提示 `mvn spotless:apply`）与 `scripts/install-hooks.ps1`（复制到 `.git/hooks/` 并说明如何卸载），按 Q3 默认倾向不跑编译，验证：安装后提交未格式化文件被拒绝，且工作区文件字节未变
- [ ] 6.2 验证放行路径：格式化后同一提交正常完成；记录 hook 单次耗时（目标 10 秒内）
- [ ] 6.3 提交本组改动

## 7. 集成验收与文档

- [ ] 7.1 README 新增「工程质量门禁」小节：`mvn spotless:apply`、`mvn verify`、`mvn verify -Psecurity`、安装 hook、豁免流程与查阅位置，验证：文档中的每条命令在本仓库逐条执行通过
- [ ] 7.2 验证 harness 判定口径未变：`powershell -File harness\checks\01-compile.ps1` 与 `02-test.ps1` 退出码均为 0，确认绑定在 verify 的插件不拖慢也不改变 compile/test 阶段
- [ ] 7.3 集成验收：`mvn clean verify` 退出码 0；`mvn verify -Psecurity` 退出码 0；记录两者耗时写入提交说明
- [ ] 7.4 提交本组改动

## Workflow follow-up

- 推送分支 `feat/quality-gates-20261114` 并开 PR，等待人工审阅放行。
- 审阅通过后合并 main，再归档 change（同步 `engineering-baseline` 主 spec）。
