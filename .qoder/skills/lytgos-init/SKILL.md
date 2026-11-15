---
name: lytgos-init
description: >-
  初始化 lytgos（或同类 JDK 21 + Spring Boot 4.x 企业级单体）的工程地基：Maven 多模块骨架、
  结构化日志、Actuator + Prometheus 监控、Spring MVC + 虚拟线程、springdoc OpenAPI、
  统一响应体与全局异常/错误码、Google 格式 + 阿里编码规约（Spotless + 阿里 P3C + Checkstyle）、
  代码安全检查（SpotBugs + Find Security Bugs + PMD + OWASP Dependency-Check），以及 CI 与 pre-commit。
  当用户要「初始化项目 / 搭工程骨架 / 起脚手架 / 加日志监控 / 加开发规范 / 加代码安全检查」时使用。
---

# lytgos 项目初始化 Skill

把"工程地基"一次性、标准化地装好——业务逻辑（五大核心能力）不在本 skill 范围内。

> **落地状态（对照本仓库实际，2026-11-15）**
>
> - **已落地**：§1–§2 骨架与版本管理、§3 结构化日志（console / prod JSON 双 profile）、§4 Actuator + Prometheus（health / liveness / readiness / prometheus 最小暴露面）、§5 MVC + 虚拟线程（`GET /api/v1/system/info` 返回 `virtualThread=true`）、§6 OpenAPI + 统一响应体 + 全局异常（springdoc 3.2.0；prod 关 Swagger UI、保留 api-docs）、§7 格式 + 阿里规约、§8 安全四件套，插件坐标见根 `pom.xml`
> - **尚未落地**：§9 的 pre-commit（CI 已有 `.github/workflows/verify.yml`）
> - 进度以 `README.md` 的「路线图」为准：`CLI、REST API 与定时触发` 仍未勾选——REST 地基已就位，业务端点与定时触发属于后续 change
> - 照着本 skill 初始化新仓库时，§3–§6 在本仓库已验证过一遍；新仓库仍按需取舍

## 什么时候用

- 新建 lytgos 仓库、或给空仓库起工程骨架时
- 要给项目补齐日志 / 监控 / API 规范 / 开发规范 / 安全检查时
- 任何 JDK 21 + Spring Boot 4.x 的企业级单体，想一次到位地装好工程地基时

## 不做什么（边界）

- 不实现五大核心能力（Provider / ReAct / Memory / Tool / Web）——那是业务模块，走 OpenSpec 的 change 流程
- 不硬编码任何密钥 / token / API key——一律用环境变量占位（`${ENV_VAR}`）
- 不替换已存在的业务代码；只新增基础设施与配置

## 前置约定（来自 AGENTS.md 硬规则与 engineering-baseline spec）

实施前确认这些硬约束，写进配置：
- JDK 21、Spring Boot 4.1.2 + Spring AI 2.0.2、Maven 多模块、单二进制（fat JAR）部署
- HTTP 层用 Spring MVC + Java 21 虚拟线程（不引入响应式）
- 持久化 SQLite + Spring Data JPA；长期记忆走 MEMORY.md（本 skill 只配数据源，不建业务表）
- 审计表 `tool_invocations` / `llm_calls` 的建表脚本预留位（day one 落库）
- 代码必须过 Google 格式 + 阿里编码规约 + 安全扫描，才能合并

---

## 初始化步骤（按顺序执行，每步完成后 `git commit`）

### 0. 确认参数
向用户确认：`groupId`、根 `artifactId`、模块清单（默认 lytgos 12 模块）、端口（默认 8080）、JDK（21）。

### 1. Maven 多模块骨架
建父 `pom.xml`（packaging=pom，统一版本管理）+ 12 个子模块（本仓库现状）：
`lytgos-core`、`lytgos-persona`、`lytgos-provider`、`lytgos-memory`、`lytgos-knowledge`、
`lytgos-tool`、`lytgos-storage`、`lytgos-channel`、`lytgos-web`、`lytgos-cli`、`lytgos-boot`，
外加只承载横切测试的 `lytgos-test`（ArchUnit 依赖方向检查落在这里）。
`lytgos-boot` 为启动模块（含 `main`），打 fat JAR；`lytgos-cli-boot` 与 `lytgos-channel-boot` 是 CLI / 渠道入口。

### 2. 基础依赖与版本管理（父 pom `dependencyManagement` / `pluginManagement`）
固定 Spring Boot BOM（本仓库 4.1.2）、Spring AI BOM（2.0.2）、SQLite JDBC、Picocli、SnakeYAML、
logstash-logback-encoder、springdoc 等版本。**具体版本号以实施时最新稳定版为准，先锁定再开发。**

### 3. 日志（结构化）
`lytgos-boot/src/main/resources/logback-spring.xml`：
- 开发环境：彩色 console pattern
- 生产环境（profile=prod）：JSON 输出（`LogstashEncoder`），带 `traceId`（MDC）
- 统一通过 SLF4J 打日志，禁止 `System.out`

### 4. 监控（Actuator + Micrometer + Prometheus）
依赖：`spring-boot-starter-actuator`、`micrometer-registry-prometheus`。
`application.yaml`：
```yaml
management:
  endpoints.web.exposure.include: health,info,prometheus,metrics
  endpoint.health.probes.enabled: true
  metrics.tags.application: lytgos
```
暴露 `/actuator/health`、`/actuator/info`、`/actuator/prometheus`。

### 5. HTTP Server（Spring MVC + 虚拟线程）
依赖：`spring-boot-starter-web`。`application.yaml`：
```yaml
server.port: 8080
spring.threads.virtual.enabled: true   # JDK 21 虚拟线程，单机扛高并发
```

### 6. API 规范（OpenAPI + 统一响应 / 错误码）
- 依赖：`springdoc-openapi-starter-webmvc-ui`（Swagger UI 在 `/swagger-ui.html`，spec 在 `/v3/api-docs`）
- 在 `lytgos-web` 建：
  - `ApiResponse<T>`：统一响应体（`code` / `message` / `data` / `timestamp`）
  - `GlobalExceptionHandler`（`@RestControllerAdvice`）：异常 → 标准 JSON 错误（`errorCode` / `message` / `timestamp`），覆盖 400 / 404 / 500 / 503
  - REST 约定：资源名词复数、`/api/v1` 前缀、合理 HTTP 状态码

### 7. 开发规范（Google 格式 + 阿里编码规约，两层互补）

职责分开，互不冲突：
- **格式层 — Google**：Spotless + google-java-format，管缩进、import 顺序、空白、换行——`apply` 一键自动修。
- **编码规约层 — 阿里巴巴 Java 开发手册**：通过 **P3C（p3c-pmd ruleset）** 落地，管命名、并发、异常处理、集合、OOP、日志、SQL 等"怎么写才对"的规约。
- **兜底 — Checkstyle**（`google_checks.xml`）+ 根目录 `.editorconfig`。

**格式：Spotless + google-java-format**
```xml
<plugin>
  <groupId>com.diffplug.spotless</groupId>
  <artifactId>spotless-maven-plugin</artifactId>
  <version>${spotless.version}</version>
  <configuration>
    <java>
      <googleJavaFormat><style>GOOGLE</style></googleJavaFormat>
      <removeUnusedImports/>
      <importOrder/>
    </java>
  </configuration>
  <executions><execution><goals><goal>check</goal></goals></execution></executions>
</plugin>
```

**编码规约：阿里 P3C（挂在 PMD 上）**
```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-pmd-plugin</artifactId>
  <version>${pmd.version}</version>
  <configuration>
    <rulesets>
      <ruleset>rulesets/java/ali-pmd.xml</ruleset>     <!-- 阿里 P3C 规约 -->
      <ruleset>rulesets/java/ali-concurrent.xml</ruleset>
      <ruleset>rulesets/java/ali-exception.xml</ruleset>
    </rulesets>
  </configuration>
  <dependencies>
    <dependency>
      <groupId>com.alibaba.p3c</groupId>
      <artifactId>p3c-pmd</artifactId>
      <version>${p3c.version}</version>
    </dependency>
  </dependencies>
  <executions><execution><goals><goal>check</goal></goals></execution></executions>
</plugin>
```

> 分工原则：**Google 管「长什么样」（格式），阿里管「怎么写才对」（规约）**，两者职责不同、可并存。
> 若个别风格规则冲突，以 google-java-format 为准（因为它能自动修，省争论）。开发者本地可装
> 「阿里巴巴 Java 编码规约」IDEA/VS Code 插件，写代码时即时提示。

### 8. 代码安全检查
父 pom 加四件套：
- **SpotBugs** + **Find Security Bugs**（findsecbugs 插件，覆盖 OWASP Top 10：SQL 注入、XSS、路径穿越、弱加密、XXE、不安全反序列化等）
- **PMD**（源码层规则）
- **OWASP Dependency-Check**（`dependency-check-maven`，扫第三方依赖已知 CVE，设 `failBuildOnCVSS`）

```xml
<plugin>
  <groupId>com.github.spotbugs</groupId>
  <artifactId>spotbugs-maven-plugin</artifactId>
  <version>${spotbugs.version}</version>
  <configuration>
    <effort>Max</effort><threshold>Low</threshold>
    <plugins><plugin>
      <groupId>com.h3xstream.findsecbugs</groupId>
      <artifactId>findsecbugs-plugin</artifactId>
      <version>${findsecbugs.version}</version>
    </plugin></plugins>
  </configuration>
</plugin>
```

### 9. CI + pre-commit
- **CI**（GitHub Actions）：`mvn verify` 串起 spotless:check → checkstyle → spotbugs → pmd → dependency-check；任一不过则红，禁止合并。本仓库已落地（`.github/workflows/verify.yml`）
- **pre-commit**（本地）：提交前跑 `mvn spotless:check`（或 `spotless:apply`）+ 快速 SpotBugs。**本仓库尚未落地**

### 10. 验证
- `mvn clean verify` 全绿 —— 本仓库可验
- `java -jar lytgos-boot/target/lytgos.jar` 起得来（默认端口 9898）—— 本仓库可验
- 访问 `/actuator/health`（UP）、`/actuator/prometheus`（有指标）、`/v3/api-docs`（有 `openapi` 与 `paths`）；非 prod 下 `/swagger-ui.html` 能打开，prod 下应为 404 —— 本仓库可验
- 故意写一行不规范代码 → `spotless:check` 报错；故意引一个有 CVE 的旧依赖 → depcheck 报警 —— 本仓库可验

---

## 检查清单（Definition of Done）

- [ ] 12 个 Maven 模块骨架建好，`mvn clean package` 出 fat JAR
- [ ] 结构化日志（prod 为 JSON，含 traceId），无 `System.out`
- [ ] `/actuator/health` `/info` `/prometheus` 可访问
- [ ] 虚拟线程开启（`spring.threads.virtual.enabled=true`）
- [ ] springdoc：`/swagger-ui.html` 可打开，统一 `ApiResponse` + `GlobalExceptionHandler` 就位
- [ ] Spotless（Google 格式）+ 阿里 P3C（编码规约）+ Checkstyle + `.editorconfig` 全部生效
- [ ] SpotBugs + Find Security Bugs + PMD + OWASP Dependency-Check 接入 `mvn verify`
- [ ] pre-commit + CI 跑通，任一检查失败即阻断
- [ ] 敏感配置全用 `${ENV_VAR}` 占位，无明文密钥

---

## 与 AGENTS.md / OpenSpec 的分工

- **本 skill**：把上面这套工程地基"装上"（一次性、可复用、跨模块）
- **`AGENTS.md`**：把硬约束"钉死"（JDK 21、Google 规范、必须过安全扫描、Spring AI 只用一半…），让 AI 每次都遵守；门禁契约落在 `openspec/specs/engineering-baseline/spec.md`
- **OpenSpec**：超过「改个拼写」的改动先在 `openspec/changes/<change-id>/` 立 proposal 与 tasks，人工放行后再实施
- **CI + pre-commit**：把检查"强制执行"（机器把关，不靠人自觉）
地基起好后，再按五大核心能力逐个开发

> 版本号、插件坐标、`google_checks.xml` 路径等以实施时官方文档为准；本 skill 给的是流程与配置骨架，不锁死具体版本。
