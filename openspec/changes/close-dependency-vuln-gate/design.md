# Design

## Context

见 proposal.md - Why。现状要点：

- 根 pom 的 `dependency-check-maven` 配置 `failBuildOnCVSS=7`，`skip` 由 `${dependency-check.skip}` 控制，默认 `true`，`security` profile 只翻转它为 `false`。
- 12 个模块 pom 各自声明该插件，`check` 目标绑定在 `verify` 阶段。
- `harness/checks/02-test.ps1` 跑 `mvn -B verify`（不含 `-Psecurity`）；run-all 按文件名排序枚举 checks 目录，新增文件自动纳入，无需改总闸门。
- 本地 NVD 数据目录 `~/.m2/dependency-check-data` 已有缓存（内含 `odc.mv.db` 与 jsrepository）。

## Goals / Non-Goals

**Goals:**

- 清掉 CI 的 CVSS 10.0 红灯，且不靠豁免。
- 让本地一键验收与 CI 的 security job 口径一致：同一门禁、同一阈值、同一份 suppressions。

**Non-Goals:**

- 不改 `.github/workflows/verify.yml`（security job 已按 spec 执行）。
- 不改 `failBuildOnCVSS` 阈值，不引入 OSS Index 凭据（该数据源只是可选的补充情报，缺它时插件继续用 NVD）。
- 不把 dependency-check 绑进默认构建（spec 明确要求默认构建不执行）。

## Decisions

### D1. swagger-ui 覆盖到 5.35.0，而不是升级 springdoc 或加豁免

- 依据链：`springdoc-openapi 3.2.0` → `org.webjars:swagger-ui:5.34.0` → 内嵌 `DOMPurify 3.4.16` → CVE-2026-116803 / 116804（均 10.0）。
- CVE-2026-116803 在 DOMPurify **3.4.17** 修复；本地解包 `swagger-ui-5.35.0.jar` 实测 `swagger-ui-bundle.js` 内为 `DOMPurify.version="3.4.17"`。
- springdoc 3.2.0 是 Boot 4 当前 release，其官方依赖仍是 5.34.0，升级 springdoc 解决不了；swagger-ui 是纯前端静态资源，覆盖 webjar 版本不影响生成 API 文档的服务端逻辑。
- **实施中发现的前提修正**：springdoc 3.2.0 的资源处理器并非按 classpath 探测目录，而是硬编码指向 webjar 的 5.34.0（启动后 `/actuator/mappings` 实测：`ResourceHttpRequestHandler [classpath [META-INF/resources/webjars/swagger-ui/5.34.0/]]`）。只换 webjar 会让 `/swagger-ui/**` 全部 404，`/swagger-ui.html` 重定向后落到 404。因此 `lytgos-web-defaults.yaml` 同步声明 `springdoc.swagger-ui.version: 5.35.0`；实测声明后 `/swagger-ui.html` 200（final=`/swagger-ui/index.html`）、页面引用的 5 个资源与 `/v3/api-docs/swagger-config` 全部 200。
- 在根 pom `dependencyManagement` 锁定 `org.webjars:swagger-ui` 一处生效，12 个模块共享；资源版本在 `lytgos-web-defaults.yaml` 声明，两处必须同步。
- 备选「写 suppressions 豁免」被否决：有可用修复版本时必须升级（见 spec delta 的「豁免前先尝试升级」场景）。

### D2. 闸门 13 只执行 dependency-check 目标，不重跑完整生命周期

- `mvn -B -Psecurity org.owasp:dependency-check-maven:check`：直接调用插件目标，不做 compile/test/静态检查——那些由 01/02 闸门负责，重复执行只是把 run-all 从 ~8 分钟拉到 ~11 分钟。
- 依赖分析需要上一次构建的 `target/`（run-all 中 02-test 已产出）；单独执行本闸门时若缺产物，exit 2 并提示先跑 02 或 `mvn package`。
- 闸门自带 `-Psecurity`：不依赖调用方记得加 profile，避免「跑了但扫描被 skip」的假绿。

### D3. 编号取 13，不与未合并的 12 抢号

- 12 已被 `feat/trace-logging-closure-20261115` 占用（该分支独立评审中）。本分支从 main 切出，取 13 保持文件名唯一。
- 合并顺序：先合 trace 分支（12），再合本分支（13）；`AGENTS.md` 清单行与 `harness/README.md` 闸门表会有一次文本冲突，合并为 00–13 连续即可。
- 本分支内部一致性不受影响：00-layout-check 比对的是「清单 vs 实际文件」，本分支两侧均为 00–11 + 13。

### D4. 闸门三态与 02-test 对齐

- `0`：全部模块扫描通过
- `2`：无 pom.xml，或没有任何模块产出 `target\classes`（没有扫描对象，不冒充失败）
- `1`：检出 CVSS ≥ 7 的依赖，或扫描执行本身报错；NVD 数据目录缺失只提示不降级——首次扫描会自建目录并下载，下载失败按扫描报错计 1

### D5. RetireJS 数据源改走 jsDelivr 镜像

- 实测：13 号闸门在一次 run-all 中以 `UnknownHostException: raw.githubusercontent.com` 失败（`Failed to initialize the RetireJS repo`），而本地 jsrepository.json 刚更新过不到一分钟，说明该域名在本地网络是结构性不可达，不是偶发抖动。
- 方案：根 pom 属性 `dependency-check.retireJsUrl` 指向 `https://cdn.jsdelivr.net/gh/Retirejs/retire.js@master/repository/jsrepository.json`（与默认源同仓库同文件，仅换分发通道），plugin configuration 引用该属性。
- 否决「关闭 RetireJS 分析器」：RetireJS 覆盖 webjar 内嵌 JS 库（本例的 DOMPurify 即此类），关闭等于削弱检测面。否决「闸门失败时自动重试」：掩盖环境问题。
- 供应链：镜像内容来自同一 GitHub 仓库的 master 分支，风险与默认源同级；若镜像也不可达，闸门继续以 1 报「扫描器初始化失败」而不是静默放行。

## Risks / Trade-offs
- [NVD feeds 需联网增量更新；无缓存时首次以分钟计] → 闸门输出明确提示数据目录位置与首次耗时；CI 已有 dataDirectory 缓存。
- [直接调用 `dependency-check:check` 与 `verify -Psecurity` 在个别模块上行为不同（前者不触发 compile）] → 以「缺 target 目录即 exit 2」兜底；CI 仍走完整 `verify -Psecurity`，两者共用同一份插件配置与阈值。
- [swagger-ui 5.35.0 引入新的 UI 缺陷] → 只影响 `/swagger-ui/index.html` 静态页面，不影响 `/v3/api-docs` 与业务 API；验收含页面可达性实测。
- [根 pom 与 `lytgos-web-defaults.yaml` 两处版本可能漂移] → 两处注释互相指向；升级 swagger-ui 必须同时改两处。`11-web-smoke` 目前只断言 `/v3/api-docs`，未覆盖 UI 页面，漂移不会被自动拦截（已记入 tasks 的 Workflow follow-up）。
- [NVD 增量更新的 4 小时窗口过期后需要联网，网络抖动会让闸门变红] → 实测遇到一次 `UnknownHostException: nvd.nist.gov`，扫描器初始化失败被判 1；重试后 NVD modified 更新成功（72.8s）并全模块通过。这是「无法判定不能算通过」的有意取舍，不是假红；断网环境下重试即可，不要为了变绿关闭更新。
- [本分支 `run-all` 无法返回 0：`05-audit-invariant` 在 main 基线是空壳（无条件 NOT_READY），而总闸门的设计是「有 NOT_READY 即判 1」] → 不为让绿灯而改总闸门、也不跳过 05；本 change 的验收判据改为「13 号 PASS + 其余已就绪闸门全 PASS」，完整通过（退出码 0）后置到 05 实现分支合并后重跑，见 tasks 3.4。
- [suppression 路径用 `${project.basedir}/../` 会在根目录调用插件目标时越出仓库] → 实测（2026-11-16）合并审计分支后 13 号闸门 exit 1：`Failed to load D:\lytgos/../dependency-check-suppressions.xml`。CI 的 `verify -Psecurity` 在模块内执行、`../` 恰好是仓库根，所以长期看起来是绿的；13 号闸门在仓库根直接调用 `dependency-check:check`，`project.basedir` 就是根目录。统一用 `${maven.multiModuleProjectDirectory}/dependency-check-suppressions.xml`，两种调用方式都解析到仓库根。

## Migration Plan

1. 根 pom 锁定版本 → 单模块 `mvn -pl lytgos-web verify -Psecurity` 复扫
2. 新增闸门 13 → 清单三处同步 → 00-layout-check + 13 号闸门本地执行
3. 起 boot jar 验证 `/swagger-ui/index.html` 与 JS 资源可达
4. 回滚：还原根 pom 版本锁定并删除闸门文件（CI 会重新红，属预期）
