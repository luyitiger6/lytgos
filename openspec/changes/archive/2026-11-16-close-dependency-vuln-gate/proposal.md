# Proposal

## Why

CI 的 `security` job 在 `lytgos-web` 上以 CVSS 10.0 失败：`swagger-ui-5.34.0` 内嵌的 DOMPurify 3.4.16 存在 CVE-2026-116803 / CVE-2026-116804，超过根 pom 的 `failBuildOnCVSS=7` 阈值，reactor 后续模块全部 SKIPPED。

而本地 `harness\run-all.ps1` 刚刚全绿——因为 02-test 只跑 `mvn verify`，`dependency-check.skip=true` 是默认值，只有 `-Psecurity` 才启用。**本地验收口径缺了依赖漏洞这一层，导致「完整验收通过」与 CI 结论系统性不一致**。这不是偶发：只要引入一个未来被收录高危 CVE 的依赖，本地都会继续绿。

## What Changes

- 依赖升级：根 pom 的 `dependencyManagement` 锁定 `org.webjars:swagger-ui` 到 `5.35.0`（其 bundle 内为 DOMPurify 3.4.17，CVE 修复版本），覆盖 `springdoc-openapi 3.2.0` 传递的 5.34.0。
- 运行时配套：`lytgos-web-defaults.yaml` 声明 `springdoc.swagger-ui.version: 5.35.0`。实施中发现 springdoc 3.2.0 的资源处理器硬编码指向 webjar 的 5.34.0 目录（启动后的 handler 映射为 `ResourceHttpRequestHandler [classpath [META-INF/resources/webjars/swagger-ui/5.34.0/]]`，并非按 classpath 探测），只换 webjar 会让 `/swagger-ui/**` 全部 404。
- 新增闸门 `harness/checks/13-dependency-vuln.ps1`：以 `-Psecurity` 执行 OWASP Dependency-Check，CVSS≥7 即 FAIL；run-all 自动枚举，无需改总闸门。
- 扫描数据源：`dependency-check.retireJsUrl` 改走 jsDelivr 的 GitHub 镜像。默认源 raw.githubusercontent.com 在国内网络经常无法解析，RetireJS 缓存 24 小时过期后更新失败会让扫描器初始化失败、闸门随机变红（实施中实测连红两次）。NVD feeds 仍走官方源。
- 同步闸门清单三处：`AGENTS.md` 机器可读清单与目录地图、`harness/README.md` 闸门表与说明。
- 不修改 CI：`.github/workflows/verify.yml` 的 `security` job 已按 spec 执行 `mvn -B verify -Psecurity`，本次是把同一门禁补进本地一键验收。

## Capabilities

### New Capabilities

（无）

### Modified Capabilities

- `engineering-baseline`: 「依赖漏洞门禁」Requirement 增加两个约束——本地一键验收 MUST 覆盖该门禁；检出高危时 MUST 优先升级依赖，确实无法升级才允许豁免。新增对应场景。

## Impact

- `pom.xml`（根）：`dependencyManagement` 新增 `org.webjars:swagger-ui` 版本锁定
- `harness/checks/13-dependency-vuln.ps1`（新增）、`harness/README.md`、`AGENTS.md`
- 依赖树：`swagger-ui` 5.34.0 → 5.35.0（纯前端静态资源，不涉及服务端逻辑）
- `lytgos-web/src/main/resources/lytgos-web-defaults.yaml`：新增 `springdoc.swagger-ui.version`（与根 pom 版本同步维护）
- 运行时行为：`/v3/api-docs` 与业务 API 不变；`/swagger-ui/**` 资源目录随版本声明切到 5.35.0，页面与 5 个引用资源保持可达
- `run-all.ps1` 总耗时会增加一次依赖扫描（有 NVD 缓存时约 1–2 分钟）
