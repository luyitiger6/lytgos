# Harness

给 AI 编码装否决权的地方。**退出码是唯一判定，日志措辞没有任何效力。**

## 三个退出状态

| 退出码 | 含义 | 什么时候用 |
|---|---|---|
| `0` | PASS，闸门真实跑过且通过 | 有判定对象，且判定通过 |
| `2` | NOT_READY，前置条件不存在 | 例如尚无 `pom.xml`、尚无 Java 源码。**这不是通过** |
| 其他非 0 | FAIL，跑过且不通过 | 真的有问题 |

`run-all.ps1` 在有 NOT_READY 时返回 1 —— 因为它无法给出「验收通过」的结论。没有代码时的「全绿」是假绿灯。

## 当前闸门

| 闸门 | 判定 | 现状 |
|---|---|---|
| `00-layout-check` | 骨架文件齐全 + `AGENTS.md` 的 `harness-checks` 标记与实际文件一致 | 生效 |
| `01-compile` | `mvn -DskipTests compile` | 生效 |
| `02-test` | JDK 前置校验（`mvn -v` 主版本 < 根 pom `<java.version>` 即 FAIL）+ `mvn verify`（含 spotless / checkstyle / pmd / spotbugs 四个静态门禁）+ 核对 surefire 报告里实际执行的用例数（0 即 FAIL） | 生效 |
| `03-static-guard` | 禁 `TODO`/`FIXME`/`XXX`、`System.out.print`、`@Disabled`、任意 pom 的 `skipTests` | 生效 |
| `04-dependency-direction` | ArchUnit 四条规则：禁 `ChatClient`、禁 `ToolCallingManager`、禁条件装配注解、禁类型扫描（`lytgos-test` 模块承载） | 生效 |
| `05-audit-invariant` | 审计不变量 I1 / I2 / I3，执行 `AuditInvariantTest` 并核对实际用例数 | 生效（3 条全过） |
| `06-change-contract` | 每个 change 必须有 proposal，且含 `## Why` 与 `## What Changes`；无进行中 change 时视为通过（空闲期不是未就绪） | 生效 |
| `07-week1-acceptance` | 第 1 周端到端：init → 幂等 → 对话+工具往返 → 反例 → 多轮上下文 | 生效（需 jar + `DEEPSEEK_API_KEY`；六条已全 PASS） |
| `08-mcp-acceptance` | MCP 两种形态走同一条治理路径：三条路径（内置 / 远程 URL / 本地 stdio）各留 `tool_call` 日志、来源标注、按形态的边界提示、命令白名单与域名白名单、无残留子进程 | 生效（需 jar + 测试夹具；第 1/2/3 条需 `DEEPSEEK_API_KEY`，第 2/4/5 条的远程部分需 `AMAP_MCP_KEY`） |
| `09-startup-budget` | L0 `--version` 30 次采样最小值 ≤ 900ms 且 `-verbose:class` 不含 `org.springframework.context.*`；L1 `provider list`（无工作区快速失败）10 次采样最小值 ≤ 2500ms | 生效（需 `lytgos-cli/target/lytgos-cli-boot.jar`） |
| `10-week2-acceptance` | 第 2 周端到端：init+空 `MEMORY.md` → `save_memory` 写入 → 移走 sessions 后新进程答出注入内容 → `write_file` 回读 → `shell java -version` → 删 `MEMORY.md` 后对话仍退出码 0 | 生效（需 jar + `DEEPSEEK_API_KEY`） |
| `11-web-smoke` | Web 地基冒烟：启动 `lytgos-boot/target/lytgos.jar`（`--server.port=18080`），九条断言 health 三组探针、prometheus `jvm_` 指标、`system/info`、未知 `/api/**` 统一 404、`/v3/api-docs`、`X-Request-Id` 复用与生成 | 生效（需 jar；不依赖任何外部 key） |
| `12-log-trace` | 日志契约端到端：dev 默认 profile 下断言日志为人类可读文本、业务 DEBUG 可见、探针行携带请求 `X-Request-Id`；prod profile 下断言每个 JSON 行可解析、启动行含 timestamp/level/logger/message、请求行 `traceId` 等于请求头 | 生效（需 jar；不依赖任何外部 key） |
| `13-dependency-vuln` | `mvn -B -Psecurity org.owasp:dependency-check-maven:check`：依赖树中存在 CVSS≥7 的已知漏洞即 FAIL；缺构建产物时 NOT_READY | 生效（需 mvn + 上一次构建的 `target/`；首次扫描需联网下载 NVD 数据） |

`harness/lib/` 放闸门共享的非闸门脚本：`JdkGuard.ps1` 被 `02-test` 与 `run-all.ps1` dot-source，比对根 `pom.xml` 的 `<java.version>` 与 `mvn -v` 报出的 Java 主版本，不足即 `exit 1` 并打印当前 `JAVA_HOME` 与修复命令。它不占闸门编号，也不进入 `checks/` 与机器可读清单。

`05` 执行 `lytgos-test` 的 `AuditInvariantTest`（`AuditedChatModel` + `ReActLoop` + `ToolExecutor` + SQLite 的真实链路），三条断言：I1 每次模型调用一行 `llm_calls`（成功 / 失败都记）；I2 每次工具执行一行 `tool_invocations`（成功 / 异常都记）；I3 同一 `turn_id` 在两张表关联到同一 session。脚本先删旧 surefire 报告，再执行 `mvn -B -pl lytgos-test -am test -Dtest=AuditInvariantTest -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false`，最后核对报告里的 `Tests run` ≥ 3——断言被删或测试类被改名时，mvn 仍会成功，只有用例数能揭穿假绿。

`03` 对 `lytgos-cli` 与 `lytgos-channel` 豁免「禁 System.out」那一条：这两个模块的 stdout 就是产品输出通道（命令结果、模型回复），不是拿 println 顶替日志。豁免只覆盖这一条，`TODO` / `@Disabled` / `skipTests` 仍然全场生效。

`04` 用 ArchUnit 扫编译产物，四条规则定义在 `lytgos-test/src/test/java/io/lytgos/arch/ArchitectureTest.java`：禁依赖 `org.springframework.ai.chat.client..`（`ChatClient` 及其 Advisor）；禁依赖 `ToolCallingManager`；禁 `@ConditionalOnBean` / `ConditionalOnMissingBean`；禁 `getBeansOfType` / `getBeanProvider` 调用与 `ObjectProvider` 注入（后两者会让 provider 集合从配置驱动退化成 classpath 驱动）。脚本实际执行 `mvn -B -pl lytgos-test -am test -Dtest=ArchitectureTest -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false`；`-am` 不可省（本模块无生产代码，扫的是其余模块的编译产物，单独构建会扫不到类而静默通过）。成功后脚本还会核对 surefire 报告里的 `Tests run` 行：少于 4 条即判 FAIL，防止 `@ArchTest` 被删后假绿。

旧的「`lytgos-provider` 之外禁止 import Spring AI」与「`lytgos-core` 不得依赖 Spring / starter」两条是自造规则，不在 `AGENTS.md` 契约里，已随本次改造删除。

`07` 需要真实模型才能跑完第 3/5/6 条（对话、沙箱反例、多轮上下文）。没有 `DEEPSEEK_API_KEY` 时这三条记 SKIP 并返回 2——没跑过的检查不能算通过。

`08` 的三条路径：A 内置 `http_get`、B 远程高德 `maps_weather`（`url`）、C 本地 stdio 夹具（`command`）。缺对应 key 时该条记 SKIP 并返回 2；两条沙箱断言不依赖任何 key，因为拒绝发生在建连接之前。

`09` 测的是 `lytgos-cli/target/lytgos-cli-boot.jar`（CLI 入口），**不是** `lytgos-boot/target/lytgos.jar`（后者是 Web 服务入口，`@SpringBootApplication` 直接起 Tomcat，不接 CLI 参数，拿它测 `--version` 会得到 3.8s 的假回归）。判据取 30 次采样的**最小值**而非均值/p95：本机 `--version` 采样呈双峰（600-900ms 与 1.0-1.3s，差值来自杀软扫描与页缓存回收，与代码路径无关），最小值才能稳定反映启动成本。L0 还额外用 `-verbose:class` 断言不出现 `org.springframework.context.*`——耗时会被机器噪声淹没，类加载记录不会。

`10` 跑的是第 2 周的用户真实动作：init 后把 `allowed-commands` 配成含 `java` → `chat` 显式调 `save_memory` → **移走 `sessions/`** 后新进程再问同一事实 → `chat` 调 `write_file` 写 `output/gate.txt` → `chat` 调 `shell` 执行 `java -version`。第 3 条移走会话是刻意的：会话还在，回答可能来自历史而不是 `MEMORY.md` 的启动注入；第 5 条的工具结果不回显到进程输出，判据取会话落盘 JSON 里的 `exit=0` 与 `version`。模型没按指令调工具时，失败详情会带上最后一次回复，用于区分「实现坏了」与「模型没照做」。第 6 条把 `memory/MEMORY.md` 移出后跑 `chat -m 你好`，要求退出码 0、stderr 无 `ERROR`/`Exception`、且不顺手重建文件——这是 memory spec「记忆文件缺失不阻断对话」的机器判据。

`11` 启动的是 Web 入口 `lytgos-boot/target/lytgos.jar`（`--server.port=18080` 覆盖默认端口），不依赖任何 API key：health 三组探针、`/actuator/prometheus` 的 `jvm_` 指标、`/api/v1/system/info`（`code=0` 且 `virtualThread=true`）、未知 `/api/**` 的 `LYTGOS_NOT_FOUND`、`/v3/api-docs` 含 `system/info`、`X-Request-Id` 复用与生成共九条。两个踩过的坑已固化在脚本里：PS 5.1 对 actuator 的 `vnd.spring-boot.actuator.v3+json` 返回 `byte[]` 需手动解码；`Start-Process` 返回的 PID 与真实 java 进程可能不一致，收尸按「端口 + 命令行双重校验」，只杀本闸门的 jar 进程。

`12` 必须起两个进程：logback 的 `<springProfile>` 只在日志上下文初始化时求值，一个 JVM 只有一组 active profile，进程内切不出另一套格式。dev 阶段用默认 profile + 不加任何 `--logging` 覆盖，判据 1 依赖 dev 默认把 `io.lytgos` 开到 DEBUG（有人删掉那条 logger 覆盖时这里会 FAIL 而不是静默跳过）；prod 阶段用 `--spring.profiles.active=prod --logging.level.io.lytgos=DEBUG`。prod 的「每行可 JSON」只校验以 `{` 开头的行——第三方库可能往 stdout 打非 JSON 文本，把它算作日志会让闸门因别人的输出而假红。`12` 的脚本首跑曾因无 BOM 被 PS 5.1 按 GBK 解码，大面积 ParserError，见下方编码约定。

`13` 补的是本地验收与 CI 的口径差：`.github/workflows/verify.yml` 的 `security` job 一直跑 `mvn -B verify -Psecurity`，而 02-test 只跑 `mvn verify`（`dependency-check.skip=true` 是默认值）—— 本地全绿、CI 却可能挂在 CVSS 10.0 的传递依赖上。只执行插件目标而不重跑完整生命周期：compile / test / 静态检查已由 01 / 02 负责，重复执行只会把 run-all 拉长几分钟；代价是它依赖上一次构建的 `target\`，缺产物时如实返回 NOT_READY。命令自带 `-Psecurity`，避免调用方忘加 profile 导致「扫描被 skip 却报通过」。闸门不提供跳过开关：确实无法升级的依赖只能写进 `dependency-check-suppressions.xml`，并附 CVE、坐标、理由与复查日期。

**测量方式有坑**：不能用 `Start-Process -RedirectStandardOutput` 计时，PowerShell 的流重定向会给每次测量注入约 350ms 开销（同一 jar 实测 1038ms vs 650ms），足以把 PASS 判成 FAIL。09 用 `System.Diagnostics.Process` 直接起进程计时，用 PATH 上的 `java`（与 07/08 验收调用点一致）。

`06` 的存在理由：`openspec/changes/` 下一旦出现只有目录、没有 proposal 的空壳，汇报时只能给出「0/0，无 tasks」这类状态——**状态不是工作内容**，读者看完仍不知道该 change 要交付什么。这条闸门把「必须能说清工作内容」变成可判定条件。

## 新增一道闸门

1. 在 `harness/checks/` 下新建 `NN-<name>.ps1`，编号顺延；
2. 头部注释写清**因**（为什么这道闸门存在）与**判据**；
3. 遵守三态约定：`exit 0` / `exit 2` / `exit 1`；
4. 同步更新 `AGENTS.md` 里的机器可读清单：

```
<!-- harness-checks: 00-layout-check 01-compile ... -->
```

漏了第 4 步，`00-layout-check` 会立刻红——清单与文件必须一致。

## 文件编码约定（踩过的坑）

**所有 `.ps1` 必须是 UTF-8 with BOM。**

不带 BOM 时，Windows PowerShell 5.1 会按系统 ANSI（中文环境即 GBK）解码文件。中文字符串尾部的双字节序列会把收尾的引号一起吞掉，报出与真实原因完全无关的错：

```
The string is missing the terminator: '.
```

骨架落地当天就因此挂过一次：`00-layout-check.ps1` 里 `'仅声明、未实现'` 把后面的引号吃掉，整份文件语法失效。

新建或编辑 `.ps1` 之后，用这条命令确认/补齐 BOM：

```powershell
Get-ChildItem . -Recurse -Filter *.ps1 -File | ForEach-Object {
    $t = [System.IO.File]::ReadAllText($_.FullName, [System.Text.Encoding]::UTF8)
    [System.IO.File]::WriteAllText($_.FullName, $t, (New-Object System.Text.UTF8Encoding($true)))
}
```

`Get-Content` 看不出区别，只有 `Format-Hex` 前三个字节（`EF BB BF`）或下面这行能确认：

```powershell
Get-ChildItem . -Recurse -Filter *.ps1 | ForEach-Object {
    $b = [System.IO.File]::ReadAllBytes($_.FullName)[0..2] -join ','
    "$($_.Name): $b"   # 期望 239,187,191
}
```

Markdown / YAML / Java 不受此约束（Maven 与 git 都按 UTF-8 处理），只对 `.ps1` 强制。

## 构建产物被 IDE 污染（踩过的坑）

**现象**：`mvn test` 报 `Unresolved compilation problem: <某类型> cannot be resolved to a type`，但源码里的 import 明明在，IDE 里也不报红。

**判据**：看报错指向的行号。若超出了文件的实际行数，说明 `target/classes` 里的那个 class 不是 Maven 编译的——IDE 的增量编译器（ecj）在 `mvn clean` 之后抢先写入了带错误标记的版本。Maven 增量编译按时间戳判新旧，class 比源码新就跳过重编译，于是坏 class 一路进到 surefire。

**清理**：`mvn clean package`。不要用 `mvn package`——它不会删掉已被写脏的 class。判断一个 class 是否被污染，直接搜里面有没有 `Unresolved compilation problem` 字符串。

## 两条不写进来的东西

- **不做「自动修复」**：闸门只判不改。改是人的事，闸门自己改会让证据失真。
- **不做「宽松模式开关」**：一旦有 `-SkipXxx`，它就会成为第一个被滥用的参数。要临时绕过，就单独跑某一道闸门，不要给总闸门开后门。
