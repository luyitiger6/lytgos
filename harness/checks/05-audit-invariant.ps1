<#
    闸门 05-audit-invariant：审计不变量

    因：I1/I2/I3 是产品卖点，也是最容易为了「先跑通」而被注释掉的东西。
        混在几百个测试里，它消失了没人看得见；单独成闸，消失就是一次 FAIL。

    判据：执行 lytgos-test 的 AuditInvariantTest（AuditedChatModel + ReActLoop +
        ToolExecutor + SQLite 的真实链路），三条：
      I1  每次模型调用必有一行 llm_calls —— 成功 / 失败都算
      I2  每次工具执行必有一行 tool_invocations —— 成功 / 异常 / 沙箱拒绝都算
      I3  同一 turn_id 在两张表必能关联到同一 session，不存在跨域

    为什么不并进 02-test：见上。02 管「行为对不对」，05 管「审计有没有断」。

    三态：
      0    I1/I2/I3 全部通过
      2    尚无判定对象（无 pom.xml，或 AuditInvariantTest 尚未落地）
      1    测试失败、未完整执行，或缺 mvn 工具链
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
$invariantTest = Join-Path $testModule 'src/test/java/io/lytgos/audit/AuditInvariantTest.java'

if (-not (Test-Path -LiteralPath $invariantTest)) {
    Write-Host 'NOT_READY: 缺 lytgos-test 的 AuditInvariantTest —— 无判定对象' -ForegroundColor DarkYellow
    exit 2
}

Write-Host '执行审计不变量测试（I1/I2/I3）...' -ForegroundColor DarkGray

# 旧报告绝不得复用：上一轮成功留下的 .txt 会让「本轮一条测试都没跑」
# （测试类改名、三条断言被删）误判为 PASS。02/04 踩过同类坑。
$report = Join-Path $testModule 'target/surefire-reports/io.lytgos.audit.AuditInvariantTest.txt'
Remove-Item -LiteralPath $report -Force -ErrorAction SilentlyContinue

# -am 不可省：lytgos-test 没有生产代码，测试依赖其余模块的编译产物。
& mvn -B -pl lytgos-test -am test "-Dtest=AuditInvariantTest" `
    "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false"
if ($LASTEXITCODE -ne 0) {
    Write-Host "审计不变量 FAILED（AuditInvariantTest 失败，mvn exit $LASTEXITCODE）" -ForegroundColor Red
    exit 1
}

# mvn 成功也可能「一条断言都没跑」：测试类被改名、@Test 被删都会静默变绿。
# 报告里有 Tests run 行才算真的执行过；少于三条说明断言集被削减。
$ran = 0
if (Test-Path -LiteralPath $report) {
    $m = Select-String -LiteralPath $report -Pattern 'Tests run: (\d+), Failures: 0, Errors: 0, Skipped: 0' |
        Select-Object -First 1
    if ($m) { $ran = [int]$m.Matches[0].Groups[1].Value }
}
if ($ran -lt 3) {
    Write-Host "FAIL: AuditInvariantTest 未完整执行（Tests run 为 $ran，预期至少 3 条）——拒绝给出通过结论" -ForegroundColor Red
    exit 1
}

Write-Host "审计不变量确认（AuditInvariantTest $ran 条全过）：" -ForegroundColor Green
Write-Host '  [OK] I1 每次模型调用一行 llm_calls（成功 / 失败都记）' -ForegroundColor Green
Write-Host '  [OK] I2 每次工具执行一行 tool_invocations（成功 / 异常都记）' -ForegroundColor Green
Write-Host '  [OK] I3 同一 turn_id 在两张表关联到同一 session' -ForegroundColor Green
exit 0
