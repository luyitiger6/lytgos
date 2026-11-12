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
| `04-dependency-direction` | 依赖方向 + 禁 `ChatClient`/`ToolCallingManager` + core 不依赖 Spring | 生效 |
| `05-audit-invariant` | 审计不变量 I1 / I2 / I3 | 未接入（storage 未落地） |
| `06-change-contract` | 每个 change 必须有 proposal，且含 `## Why` 与 `## What Changes` | 生效 |
| `07-week1-acceptance` | 第 1 周端到端：init → 幂等 → 对话+工具往返 → 反例 → 多轮上下文 | 生效（需 jar + `DEEPSEEK_API_KEY`；六条已全 PASS） |
| `08-mcp-acceptance` | MCP 工具与内置工具走同一条治理路径：双路径对照各留 `tool_call` 日志、来源标注、边界提示、命令白名单、无残留子进程 | 生效（需 jar + `DEEPSEEK_API_KEY` + 测试夹具） |

`05` 是**空壳**，只有一句 `NOT_READY` 和一段计划说明。空壳比没有更诚实：它让每次跑 harness 都能看见「这道还没接」，而不是在 README 里被忘掉。

`03` 对 `lytgos-cli` 与 `lytgos-channel` 豁免「禁 System.out」那一条：这两个模块的 stdout 就是产品输出通道（命令结果、模型回复），不是拿 println 顶替日志。豁免只覆盖这一条，`TODO` / `@Disabled` / `skipTests` 仍然全场生效。

`04` 的六条规则：`lytgos-provider` 之外禁止 import Spring AI；禁 `ChatClient`；禁 `ToolCallingManager`；禁 `@ConditionalOnBean` / `ObjectProvider<ChatModel>` / `getBeansOfType`（这三者会让 provider 集合从配置驱动退化成 classpath 驱动）；`lytgos-core` 的 pom 不依赖 Spring；`lytgos-core` 不 import 其他模块。

`07` 需要真实模型才能跑完第 3/5/6 条（对话、沙箱反例、多轮上下文）。没有 `DEEPSEEK_API_KEY` 时这三条记 SKIP 并返回 2——没跑过的检查不能算通过。

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

## 两条不写进来的东西

- **不做「自动修复」**：闸门只判不改。改是人的事，闸门自己改会让证据失真。
- **不做「宽松模式开关」**：一旦有 `-SkipXxx`，它就会成为第一个被滥用的参数。要临时绕过，就单独跑某一道闸门，不要给总闸门开后门。
