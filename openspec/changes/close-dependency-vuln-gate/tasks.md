# Tasks

## 1. 依赖升级清掉高危 CVE

- [x] 1.1 根 pom 增加 `swagger-ui.version` 属性并在 `dependencyManagement` 锁定 `org.webjars:swagger-ui`（注释写明 springdoc 3.2.0 传递 5.34.0、DOMPurify 3.4.16 → 3.4.17 的修复关系），`mvn -B -pl lytgos-web verify -Psecurity` 复扫无 CVSS≥7 报点
- [x] 1.2 `lytgos-web-defaults.yaml` 声明 `springdoc.swagger-ui.version: 5.35.0`（springdoc 3.2.0 硬编码 5.34.0 资源目录，只换 webjar 会让 `/swagger-ui/**` 404）；起 `lytgos-boot/target/lytgos.jar` 验证 `/swagger-ui.html` 与 `/swagger-ui/index.html` 返回 200，且页面引用的 5 个资源与 `/v3/api-docs/swagger-config` 全部可达

## 2. 依赖漏洞闸门

- [x] 2.1 新增 `harness/checks/13-dependency-vuln.ps1`：三态（0 通过 / 2 无 pom 或缺构建产物 / 1 检出高危或执行失败），执行 `mvn -B -Psecurity org.owasp:dependency-check-maven:check`；本机执行 exit 0
- [x] 2.2 同步 `AGENTS.md` 机器可读清单与目录地图、`harness/README.md` 闸门表；`powershell -File harness\checks\00-layout-check.ps1` exit 0
- [x] 2.3 根 pom 的 `dependency-check.retireJsUrl` 改走 jsDelivr 镜像（raw.githubusercontent.com 国内不可达，RetireJS 24 小时过期后更新失败会让闸门恒红）；强制更新验证镜像生效后 13 号闸门 exit 0

## 3. 验收

- [x] 3.1 全量 `mvn -B clean install -Psecurity` 通过，所有模块无 CVSS≥7 报点
- [x] 3.2 `powershell -File harness\run-all.ps1` 实跑：13 号闸门 PASS（109.5s），其余已就绪闸门全 PASS（00/01/02/03/04/06/07/08/09/10/11）。判据调整（原写「退出码 0」）：`05-audit-invariant` 在 main 基线是登记在册的空壳（无条件 NOT_READY，其实现依赖 storage 审计链路，不在本 change 范围），而 `run-all.ps1` 的设计是「有 NOT_READY 即返回 1」；本分支无法给出退出码 0——不为此改总闸门判定，也不谎报「完整验收通过」
- [x] 3.3 推送分支后确认 GitHub Actions 的 verify 与 security 两个 job 全绿：PR #15（run 43745394685）两个 job 均 success；合并后 main 的 push run 43747496264 同样双绿
- [ ] 3.4 【后置】05-audit-invariant 实现分支合并后重跑 `harness\run-all.ps1` 确认退出码 0：PR #16（`feat/week3-audit-context-20261115`）已合并，05 已从空壳变为真实校验并 PASS（14.2s）；当前被 3.5 的 suppression 路径回归阻塞，修复合并后重跑
- [x] 3.5 【回归修复】合并 `add-audit-trail-and-context` 后，13 号闸门在仓库根直接调用插件目标时 `${project.basedir}/../dependency-check-suppressions.xml` 越出仓库（`D:\lytgos/../`）导致 suppression 初始化失败、闸门 exit 1；根 pom 改回 `${maven.multiModuleProjectDirectory}`，闸门复测 exit 0（113s）。CI 的 `verify -Psecurity` 在模块内执行察觉不到这个差异，只有本地 run-all 会暴露

## Workflow follow-up

- 规格与实现经用户审阅后，按项目流程归档本 change（合并前先合 trace 分支，再把清单合并为 00–13 连续）。
- 归档后确认 `openspec/specs/engineering-baseline/spec.md` 的「依赖漏洞门禁」已包含本地验收覆盖与升级优先两个场景。
- 待办（另立 change）：「11-web-smoke」只断言 `/v3/api-docs`，未覆盖 spec `http-api` 已要求的 `/swagger-ui.html`；本次 1.2 为手工验证，建议后续把 UI 可达性纳入闸门，防根 pom 与 yaml 版本漂移。
