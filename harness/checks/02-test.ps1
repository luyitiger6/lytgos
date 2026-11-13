<#
    闸门 02-test：行为对不对

    因：编译通过只证明语法正确，证明不了行为正确。
        这是唯一能回答「你说的和代码做的是不是一回事」的闸门。

    三态：
      0        全部测试通过，且报告里实际执行了测试
      2        没有 pom.xml —— 无判定对象
      1        测试失败、一个测试都没执行，或缺 mvn

    判据分两步：mvn 退出码为 0，且 surefire 报告里 Tests run 总数为正。
    只看退出码时，测试类被整文件删掉也能绿——那不是通过，是没跑。

    注意：本闸门不接受任何跳过参数；给 02 加跳过开关，等于把整个护栏体系作废。
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

# 先删旧报告：mvn 若在编译阶段就失败、根本没进测试阶段，旧的 surefire 报告还在，
# 后面统计到的会是上一次的结果——「没跑过」与「跑过且通过」就不可区分了。
$moduleDirs = @(Get-ChildItem -Path $projectRoot -Directory -Force)
foreach ($moduleDir in $moduleDirs) {
    $reportDir = Join-Path $moduleDir.FullName 'target\surefire-reports'
    if (Test-Path -LiteralPath $reportDir) {
        Remove-Item -LiteralPath $reportDir -Recurse -Force
    }
}

& mvn -B test
if ($LASTEXITCODE -ne 0) {
    Write-Host "测试失败（mvn exit $LASTEXITCODE）" -ForegroundColor Red
    exit 1
}

# 退出码 0 不等于「测试真的跑过」：测试类被整文件删掉、surefire 一个类都没匹配上，
# mvn 都会以 0 退出。核对报告里实际执行的用例数，0 即 FAIL。
$reports = @()
foreach ($moduleDir in $moduleDirs) {
    $reportDir = Join-Path $moduleDir.FullName 'target\surefire-reports'
    if (Test-Path -LiteralPath $reportDir) {
        $reports += @(Get-ChildItem -LiteralPath $reportDir -Filter '*.txt' -File -ErrorAction SilentlyContinue)
    }
}

$totalTests = 0
foreach ($report in $reports) {
    $text = [System.IO.File]::ReadAllText($report.FullName)
    foreach ($m in [regex]::Matches($text, 'Tests run:\s*(\d+)')) {
        $totalTests += [int]$m.Groups[1].Value
    }
}

if ($totalTests -eq 0) {
    Write-Host "FAIL: mvn 退出码 0，但 surefire 报告里 0 个测试被执行（$($reports.Count) 个报告文件）——测试可能被整文件删除，或根本没进测试阶段" -ForegroundColor Red
    exit 1
}

Write-Host "测试通过（$($reports.Count) 个报告文件，共执行 $totalTests 个测试）" -ForegroundColor Green
exit 0
