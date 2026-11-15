# Tasks

## 1. 依赖与配置基线

- [x] 1.1 查 springdoc 与 Spring Boot 4.1.2 的兼容矩阵，把选定版本写入根 `pom.xml` 的 `dependencyManagement`（design OQ3 的实施第一步）；验证：`mvn -B -pl lytgos-web dependency:tree` 能解析出 springdoc 坐标且无版本冲突
- [x] 1.2 `lytgos-web` 补足依赖（web 栈、actuator、micrometer-registry-prometheus、logstash-logback-encoder、springdoc），确认 `lytgos-cli` 的依赖树不含新增项；验证：`mvn -B -pl lytgos-cli dependency:tree` 无 actuator/servlet/springdoc 命中
- [x] 1.3 新增 `lytgos-web/src/main/resources/lytgos-web-defaults.yaml`（虚拟线程、actuator 暴露面、health 探针、show-details=never、springdoc 开关），`lytgos-boot` 新建 `application.yaml` 并 import，`lytgos-channel` 的 `application.yaml` 同样 import；端口按 design OQ1 向用户确认后写入；验证：两个入口分别 `java -jar` 启动，`/actuator/health` 返回 UP
- [x] 1.4 新增 `logback-spring.xml`：默认彩色 console（pattern 含 traceId），prod 用 `LogstashEncoder` 输出单行 JSON；验证：默认启动日志无 JSON 花括号，`--spring.profiles.active=prod` 下每条日志为合法 JSON 且含 timestamp/level/logger/message/traceId 字段

## 2. HTTP 契约实现

- [x] 2.1 `ApiResponse<T>` record（code/message/data/timestamp）+ `GlobalExceptionHandler`（`basePackages = "io.lytgos.web"` 限定作用域，映射 5 个 `LytgosException` 子类、未知异常兜底、`/api/**` 的 404）；验证：单测覆盖 5 类映射 + 未知异常不泄露 + 404，并有 1 条断言 actuator 响应未被改写
- [x] 2.2 traceId `OncePerRequestFilter`：读/生成 `X-Request-Id`，写 MDC、回写响应头、finally 清理；验证：单测 2 条（复用传入值 / 未传时生成）断言 MDC 与响应头一致
- [x] 2.3 `GET /api/v1/system/info` 端点：返回应用名、版本、构建 JDK、`virtualThread`；验证：`@SpringBootTest(webEnvironment = RANDOM_PORT)` 断言 HTTP 200、`code == "0"`、`data.virtualThread == true`、`data.javaVersion` 以 21 开头
- [x] 2.4 springdoc 接入与 prod 关闭 UI；验证：默认 profile 下 `/v3/api-docs` 返回 200 且 `paths` 含 `/api/v1/system/info`；`--spring.profiles.active=prod` 下 `/swagger-ui.html` 不返回 UI 页面

## 3. 闸门与文档

- [x] 3.1 新增 `harness/checks/11-web-smoke.ps1`：三态（缺 jar→2、失败→1、全过→0），覆盖 health/liveness/readiness、prometheus、system/info、未知 `/api/**` 的 404、api-docs、`X-Request-Id` 回写；端口用 `--server.port=18080` 覆盖；验证：`powershell -File harness\checks\11-web-smoke.ps1` 退出码 0 且各条 PASS，删掉 jar 后退出码 2
- [x] 3.2 同步 `AGENTS.md` 机器可读清单与目录地图、`harness/README.md` 闸门表；验证：`powershell -File harness\checks\00-layout-check.ps1` 退出码 0 且输出「闸门清单一致：12 道」
- [x] 3.3 更新 `lytgos-init` SKILL 的「落地状态」声明与 README「当前状态」段；验证：两处不再出现「§3–§6 未落地」，且 README 路线图中「CLI、REST API 与定时触发」不提前勾选（定时触发不在本 change 内）

## 4. 集成验证

- [x] 4.1 `mvn -B verify` EXIT=0，单测总数较 120 增加；验证：构建日志 BUILD SUCCESS 与 surefire 统计（实测：13 个模块全部 SUCCESS，2:57 min；单测 120 → 133，新增 13 条全在 `lytgos-web`；SpotBugs 首次扫描报 12 条，处置后 `BugInstance size is 0`，豁免台账见 design D10）
- [x] 4.2 全量 `harness\run-all.ps1`（注入 `JAVA_HOME=C:\jdk21` 与 User 作用域的 `AMAP_MCP_KEY`）；验证：12 道闸门中仅 `05-audit-invariant` 为 NOT_READY，其余 PASS，EXIT=1（实测：11 道 PASS + 05 NOT_READY，总闸门 EXIT=1；11-web-smoke 16.7s 九条全 PASS）
- [x] 4.3 双入口与 CLI 回归：`lytgos.jar`、`lytgos-channel-boot.jar` 各自 health UP；`09-startup-budget` 仍 PASS（CLI 未引入 web 栈）；验证：三条结论分别记录退出码与实测耗时（实测：`C:\jdk21\bin\java -jar` 起两个入口，/actuator/health 均为 status=UP 且含 liveness/readiness，JVM 到就绪 10.3s / 9.8s，system/info 的 javaVersion=21.0.6、virtualThread=true，停进程后端口均释放；09 PASS：L0 min=526ms（预算 900ms）、L1 min=1394ms（预算 2500ms））

## Workflow follow-up

- 交用户审阅后合并 `feat/web-foundation-<yyyyMMdd>`。
- 合并后同步主 specs 并归档本 change。
