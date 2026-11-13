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
| `02-test` | `mvn test` | 生效 |
| `03-static-guard` | 禁 `TODO`/`FIXME`/`XXX`、`System.out.print`、`@Disabled` | 生效 |
| `04-dependency-direction` | ArchUnit 四条规则：禁 `ChatClient`、禁 `ToolCallingManager`、禁条件装配注解、禁类型扫描（`lytgos-test` 模块承载） | 生效 |
| `05-audit-invariant` | 审计不变量 I1 / I2 / I3 | 未接入（storage 未落地） |
| `06-change-contract` | 每个 change 必须有 proposal，且含 `## Why` 与 `## What Changes` | 生效 |
| `07-week1-acceptance` | 第 1 周端到端：init → 幂等 → 对话+工具往返 → 反例 → 多轮上下文 | 生效（需 jar + `DEEPSEEK_API_KEY`；六条已全 PASS） |
| `08-mcp-acceptance` | MCP 两种形态走同一条治理路径：三条路径（内置 / 远程 URL / 本地 stdio）各留 `tool_call` 日志、来源标注、按形态的边界提示、命令白名单与域名白名单、无残留子进程 | 生效（需 jar + 测试夹具；第 1/2/3 条需 `DEEPSEEK_API_KEY`，第 2/4/5 条的远程部分需 `AMAP_MCP_KEY`） |
| `09-startup-budget` | L0 `--version` 30 次采样最小值 ≤ 900ms 且 `-verbose:class` 不含 `org.springframework.context.*`；L1 `provider list`（无工作区快速失败）10 次采样最小值 ≤ 2500ms | 生效（需 `lytgos-cli/target/lytgos-cli-boot.jar`） |

`05` 是**空壳**，只有一句 `NOT_READY` 和一段计划说明。空壳比没有更诚实：它让每次跑 harness 都能看见「这道还没接」，而不是在 README 里被忘掉。

`03` 对 `lytgos-cli` 与 `lytgos-channel` 豁免「禁 System.out」那一条：这两个模块的 stdout 就是产品输出通道（命令结果、模型回复），不是拿 println 顶替日志。豁免只覆盖这一条，`TODO` / `@Disabled` / `skipTests` 仍然全场生效。

`04` 用 ArchUnit 扫编译产物，四条规则定义在 `lytgos-test/src/test/java/io/lytgos/arch/ArchitectureTest.java`：禁依赖 `org.springframework.ai.chat.client..`（`ChatClient` 及其 Advisor）；禁依赖 `ToolCallingManager`；禁 `@ConditionalOnBean` / `ConditionalOnMissingBean`；禁 `getBeansOfType` / `getBeanProvider` 调用与 `ObjectProvider` 注入（后两者会让 provider 集合从配置驱动退化成 classpath 驱动）。脚本实际执行 `mvn -B -pl lytgos-test -am test -Dtest=ArchitectureTest -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false`；`-am` 不可省（本模块无生产代码，扫的是其余模块的编译产物，单独构建会扫不到类而静默通过）。成功后脚本还会核对 surefire 报告里的 `Tests run` 行：少于 4 条即判 FAIL，防止 `@ArchTest` 被删后假绿。

旧的「`lytgos-provider` 之外禁止 import Spring AI」与「`lytgos-core` 不得依赖 Spring / starter」两条是自造规则，不在 `AGENTS.md` 契约里，已随本次改造删除。

`07` 需要真实模型才能跑完第 3/5/6 条（对话、沙箱反例、多轮上下文）。没有 `DEEPSEEK_API_KEY` 时这三条记 SKIP 并返回 2——没跑过的检查不能算通过。

`08` 的三条路径：A 内置 `http_get`、B 远程高德 `maps_weather`（`url`）、C 本地 stdio 夹具（`command`）。缺对应 key 时该条记 SKIP 并返回 2；两条沙箱断言不依赖任何 key，因为拒绝发生在建连接之前。

`09` 测的是 `lytgos-cli/target/lytgos-cli-boot.jar`（CLI 入口），**不是** `lytgos-boot/target/lytgos.jar`（后者是 Web 服务入口，`@SpringBootApplication` 直接起 Tomcat，不接 CLI 参数，拿它测 `--version` 会得到 3.8s 的假回归）。判据取 30 次采样的**最小值**而非均值/p95：本机 `--version` 采样呈双峰（600-900ms 与 1.0-1.3s，差值来自杀软扫描与页缓存回收，与代码路径无关），最小值才能稳定反映启动成本。L0 还额外用 `-verbose:class` 断言不出现 `org.springframework.context.*`——耗时会被机器噪声淹没，类加载记录不会。

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
