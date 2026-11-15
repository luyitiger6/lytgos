<#
    闸门 13-dependency-vuln：依赖漏洞

    因：CI 的 security job 以 -Psecurity 执行 OWASP Dependency-Check，但 02-test 只跑
        `mvn verify`（dependency-check 默认 skip）。结果是「本地一键验收全绿、CI 挂在
        CVSS 10.0 的传递依赖上」——本地看不见的高危漏洞，等于这道门禁不存在。
        本闸门把同一门禁、同一阈值、同一份 suppressions 补进本地一键验收。

    做法：只执行 dependency-check 插件目标，不重跑 compile / test / 静态检查——
        那些由 01-compile 与 02-test 负责。扫描需要上一次构建的 class 产物，
        缺产物时按「未就绪」返回 2，既不冒充通过也不冒充失败。

    三态：
      0  全部模块无 CVSS≥7 的依赖漏洞
      2  无 pom.xml，或没有任何模块产出 target\classes（先跑 02-test 或 mvn package）
      1  检出 CVSS≥7 的依赖，或扫描执行本身失败

    注意：
      - 命令自带 -Psecurity：不依赖调用方记得加 profile，否则扫描被 skip 而假绿。
      - NVD 数据目录为 ~/.m2/dependency-check-data；无缓存时首次要下载年度包（分钟级）。
      - 豁免只能写进 dependency-check-suppressions.xml（CVE + 依赖坐标 + 理由 + 复查日期），
        本闸门不提供任何「跳过」开关。
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

# JDK 版本不够时 dependency-check 与编译产物可能不匹配，提前拦下并说明真实原因
. (Join-Path $PSScriptRoot '..\lib\JdkGuard.ps1')
Assert-JdkVersion $projectRoot

# 依赖分析以 target\classes 为输入：没有产物时扫出来的结论不可信
$builtModules = @()
foreach ($dir in (Get-ChildItem -Path $projectRoot -Directory -Force)) {
    if (Test-Path -LiteralPath (Join-Path $dir.FullName 'target\classes')) {
        $builtModules += $dir.Name
    }
}
if ($builtModules.Count -eq 0) {
    Write-Host 'NOT_READY: 没有任何模块产出 target\classes —— 先跑 02-test 或 mvn package 再扫描' -ForegroundColor DarkYellow
    exit 2
}

$nvdDir = Join-Path $env:USERPROFILE '.m2\dependency-check-data'
if (-not (Test-Path -LiteralPath $nvdDir)) {
    Write-Host "提示：NVD 数据目录不存在（$nvdDir），首次扫描需下载年度包，耗时以分钟计" -ForegroundColor DarkYellow
}

$start = Get-Date
& mvn -B -Psecurity org.owasp:dependency-check-maven:check
$scanExit = $LASTEXITCODE
$elapsed = [int]((New-TimeSpan -Start $start).TotalSeconds)

if ($scanExit -ne 0) {
    Write-Host ("依赖漏洞 FAILED（mvn exit {0}，耗时 {1}s）：存在 CVSS≥7 的依赖或扫描器初始化失败；报告见各模块 target/dependency-check-report.html" -f $scanExit, $elapsed) -ForegroundColor Red
    exit 1
}

Write-Host ("依赖漏洞扫描通过（{0} 个模块有构建产物，耗时 {1}s；数据目录 {2}）" -f $builtModules.Count, $elapsed, $nvdDir) -ForegroundColor Green
exit 0
