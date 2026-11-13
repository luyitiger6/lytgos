<#
    闸门 04-dependency-direction：依赖方向 + 禁止自动工具执行

    因：AGENTS.md 4.1 与 4.2 两条约束违反了都不会报错——
        编译通过、测试通过、界面正常，只是配额、白名单、沙箱、审计全部静默失效。
        这是本项目唯一一条「违反后没有任何症状」的约束，必须由机器守。

    判据：执行 lytgos-test 模块里的 ArchitectureTest（ArchUnit 扫字节码），四条规则：
      1. 禁依赖 org.springframework.ai.chat.client..（ChatClient 及其 Advisor）
      2. 禁依赖 ToolCallingManager
      3. 禁 @ConditionalOnBean / @ConditionalOnMissingBean（类与方法级都算）
      4. 禁 getBeansOfType / getBeanProvider 调用与 ObjectProvider 注入

    为什么不再扫源码正则：import 写了不引用不构成依赖，正则会放过；反过来注释里
    点名某个类又可能被误伤（旧版靠「注释行跳过」补丁式处理）。ArchUnit 看的是编译
    产物里真实的引用，这才是「约束」该有的判定面。旧的「Spring AI 只允许在 provider」
    与「core 不得依赖 spring-boot-starter」两条是自造规则，不在契约里，已删除。

    三态：
      0        四条规则全部满足
      2        尚无判定对象（无 pom.xml，或无 lytgos-test / 业务模块源码）
      1        检出违规，或缺 mvn 工具链
#>
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Set-Location $projectRoot

if (-not (Test-Path -LiteralPath 'pom.xml')) {
    Write-Host 'NOT_READY: 尚无 pom.xml —— 无判定对象' -ForegroundColor DarkYellow
    exit 2
}

if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
    Write-Host 'FAIL: 找不到 mvn 命令（pom.xml 已存在，说明工具链缺失，不是未就绪）' -ForegroundColor Red
    exit 1
}

$testModule = Join-Path $projectRoot 'lytgos-test'
$architectureTest = Join-Path $testModule 'src/test/java/io/lytgos/arch/ArchitectureTest.java'
$businessModules = @(Get-ChildItem $projectRoot -Directory -Force | Where-Object {
        $_.Name -like 'lytgos-*' -and $_.Name -ne 'lytgos-test' -and
        (Test-Path -LiteralPath (Join-Path $_.FullName 'src'))
    })

if (-not (Test-Path -LiteralPath $architectureTest) -or $businessModules.Count -eq 0) {
    Write-Host 'NOT_READY: 缺 lytgos-test 的 ArchitectureTest，或尚无业务模块源码 —— 无判定对象' -ForegroundColor DarkYellow
    exit 2
}

Write-Host "执行 ArchUnit 架构测试（业务模块 $($businessModules.Count) 个）..." -ForegroundColor DarkGray

# 旧报告绝不得复用：上一轮成功留下的 .txt 会让「本轮一条测试都没跑」
# （测试类被改名、@ArchTest 被删、模块被临时摘除）误判为 PASS。
$report = Join-Path $testModule 'target/surefire-reports/io.lytgos.arch.ArchitectureTest.txt'
Remove-Item -LiteralPath $report -Force -ErrorAction SilentlyContinue

# -am 不可省：lytgos-test 没有生产代码，规则扫描的是其余模块的编译产物；
# 单独构建 lytgos-test 会扫不到类而静默通过——比失败更危险。
& mvn -B -pl lytgos-test -am test "-Dtest=ArchitectureTest" `
    "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"
if ($LASTEXITCODE -ne 0) {
    Write-Host "依赖方向 / 自动执行禁令 FAILED（ArchUnit 测试失败，mvn exit $LASTEXITCODE）" -ForegroundColor Red
    exit 1
}

# mvn 成功也可能「一条规则都没跑」：@ArchTest 被删、测试类被改名都会静默变绿。
# surefire 报告里有 Tests run 行才算真的执行过；少于四条说明规则集被削减。
# 报告不存在时 $ran 保持 0，同样判 FAIL。
$ran = 0
if (Test-Path -LiteralPath $report) {
    $m = Select-String -LiteralPath $report -Pattern 'Tests run: (\d+), Failures: 0, Errors: 0, Skipped: 0' |
        Select-Object -First 1
    if ($m) { $ran = [int]$m.Matches[0].Groups[1].Value }
}
if ($ran -lt 4) {
    Write-Host "FAIL: ArchitectureTest 未完整执行（Tests run 为 $ran，预期至少 4 条）——拒绝给出通过结论" -ForegroundColor Red
    exit 1
}

Write-Host "依赖方向与自动执行禁令确认（ArchUnit $ran 条规则全过）：" -ForegroundColor Green
Write-Host '  [OK] 1. 无类依赖 org.springframework.ai.chat.client..（ChatClient 及其 Advisor）' -ForegroundColor Green
Write-Host '  [OK] 2. 无类依赖 ToolCallingManager' -ForegroundColor Green
Write-Host '  [OK] 3. 无类/方法/字段使用 @ConditionalOnBean / @ConditionalOnMissingBean' -ForegroundColor Green
Write-Host '  [OK] 4. 无 getBeansOfType / getBeanProvider 调用与 ObjectProvider 注入' -ForegroundColor Green
exit 0
