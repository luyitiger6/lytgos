<#
    闸门 09-startup-budget：启动预算回归护栏

    因：核心能力类进 Spring 容器后，最大的退化风险是 L0 命令（--version / --help）
        被迫初始化容器——每次 CLI 调用都要多付 800ms+。而单次耗时噪声可达 600ms
        （杀软扫描、页缓存回收），只看耗时淹没不了 815ms 的容器 refresh 差异，
        所以必须双判据：
          a) 多次采样取最小值 ≤ 900ms（最小值才是启动成本，p95 会把环境噪声当回归）
          b) `-verbose:class` 输出不含 org.springframework.context.*（不会被噪声干扰的硬判据）

    判据：
      L0  --version                        30 次采样最小值 ≤ 900ms
          -verbose:class                   不含 org.springframework.context.*
      L1  provider list（无工作区快速失败） 10 次采样最小值 ≤ 2500ms

    三态：
      0  两级均在预算内，且 L0 未加载容器类
      2  缺少 lytgos-cli/target/lytgos-cli-boot.jar（先 mvn package），或 PATH 上无 java
      1  超预算、L0 加载了容器类、或命令异常退出

    注意：
      - 测的是 lytgos-cli-boot.jar（CLI 入口），不是 lytgos-boot/target/lytgos.jar
        （后者是 Web 服务入口，@SpringBootApplication 直接起 Tomcat，不接 CLI 参数）。
      - 不能用 Start-Process -RedirectStandardOutput 计时：PowerShell 的流重定向会给
        每次测量注入约 350ms 开销（实测 1038ms vs 650ms），足以把 PASS 判成 FAIL。
        必须用 System.Diagnostics.Process 直接测。
      - 用 PATH 上的 java（与 07/08 验调用点一致），不强制 JAVA_HOME。
#>
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Set-Location $projectRoot

$jar = Join-Path $projectRoot 'lytgos-cli/target/lytgos-cli-boot.jar'
if (-not (Test-Path -LiteralPath $jar)) {
    Write-Host 'NOT_READY: 缺少 lytgos-cli/target/lytgos-cli-boot.jar，先执行 mvn package' -ForegroundColor DarkYellow
    exit 2
}
if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    Write-Host 'NOT_READY: PATH 上找不到 java' -ForegroundColor DarkYellow
    exit 2
}

$L0_BUDGET_MS = 900
$L0_SAMPLES = 30
$L1_BUDGET_MS = 2500
$L1_SAMPLES = 10

# 直接起进程计时；ReadToEnd 必须在 WaitForExit 之前，否则子进程写满管道会死锁
function Invoke-Measure {
    param([string]$CliArgs, [string]$WorkDir, [int]$Samples)
    $times = @()
    $lastExit = 0
    for ($i = 0; $i -lt $Samples; $i++) {
        $psi = [Diagnostics.ProcessStartInfo]::new('java', $CliArgs)
        $psi.UseShellExecute = $false
        $psi.RedirectStandardOutput = $true
        $psi.RedirectStandardError = $true
        if ($WorkDir) { $psi.WorkingDirectory = $WorkDir }
        $sw = [Diagnostics.Stopwatch]::StartNew()
        $p = [Diagnostics.Process]::Start($psi)
        $null = $p.StandardOutput.ReadToEnd()
        $null = $p.StandardError.ReadToEnd()
        $p.WaitForExit()
        $sw.Stop()
        $times += $sw.Elapsed.TotalMilliseconds
        $lastExit = $p.ExitCode
        $p.Dispose()
    }
    [pscustomobject]@{
        Min   = [math]::Round(($times | Measure-Object -Minimum).Minimum)
        Max   = [math]::Round(($times | Measure-Object -Maximum).Maximum)
        Times = $times
        Exit  = $lastExit
    }
}

$failures = @()

# ---- L0：--version 不刷新容器 ----
$l0 = Invoke-Measure -CliArgs ('-jar "' + $jar + '" --version') -Samples $L0_SAMPLES
$l0Samples = ($l0.Times | ForEach-Object { [math]::Round($_) }) -join ','
Write-Host ("[L0] --version {0} 次采样：min={1}ms max={2}ms  预算 {3}ms" -f $L0_SAMPLES, $l0.Min, $l0.Max, $L0_BUDGET_MS)
if ($l0.Exit -ne 0) {
    $failures += "--version 退出码 $($l0.Exit)，命令本身异常"
} elseif ($l0.Min -gt $L0_BUDGET_MS) {
    $failures += "--version 最小值 $($l0.Min)ms 超预算 $($L0_BUDGET_MS)ms（samples: $l0Samples）"
}

# L0 硬判据：-verbose:class 不出现容器类
$psi = [Diagnostics.ProcessStartInfo]::new('java', ('-verbose:class -jar "' + $jar + '" --version'))
$psi.UseShellExecute = $false
$psi.RedirectStandardOutput = $true
$psi.RedirectStandardError = $true
$p = [Diagnostics.Process]::Start($psi)
$verboseOut = $p.StandardOutput.ReadToEnd()
$verboseErr = $p.StandardError.ReadToEnd()
$p.WaitForExit()
$verboseExit = $p.ExitCode
$p.Dispose()
$ctxLoads = @(($verboseOut + "`n" + $verboseErr) -split "`r?`n" | Where-Object { $_ -like '*org.springframework.context.*' })
if ($verboseExit -ne 0) {
    $failures += "--version -verbose:class 退出码 $verboseExit"
} elseif ($ctxLoads.Count -gt 0) {
    $failures += "--version 加载了 $($ctxLoads.Count) 个 org.springframework.context.* 类（示例：$($ctxLoads[0].Trim())），L0 不得碰容器"
    Write-Host "[L0] -verbose:class：命中 $($ctxLoads.Count) 个容器类  -> FAIL" -ForegroundColor Red
} else {
    Write-Host '[L0] -verbose:class：未加载 org.springframework.context.*（硬判据）' -ForegroundColor Green
}

# ---- L1：provider list 无工作区快速失败 ----
$probeDir = Join-Path ([IO.Path]::GetTempPath()) ('lytgos-startup-budget-' + [DateTime]::Now.ToString('yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $probeDir -Force | Out-Null
try {
    $l1 = Invoke-Measure -CliArgs ('-jar "' + $jar + '" provider list') -WorkDir $probeDir -Samples $L1_SAMPLES
} finally {
    Remove-Item -LiteralPath $probeDir -Recurse -Force -ErrorAction SilentlyContinue
}
$l1Samples = ($l1.Times | ForEach-Object { [math]::Round($_) }) -join ','
Write-Host ("[L1] provider list {0} 次采样：min={1}ms max={2}ms  预算 {3}ms" -f $L1_SAMPLES, $l1.Min, $l1.Max, $L1_BUDGET_MS)
if ($l1.Exit -ne 0 -and $l1.Exit -ne 1) {
    $failures += "provider list 退出码 $($l1.Exit)（预期 0 或 1），命令异常"
} elseif ($l1.Min -gt $L1_BUDGET_MS) {
    $failures += "provider list 最小值 $($l1.Min)ms 超预算 $($L1_BUDGET_MS)ms（samples: $l1Samples）"
}

if ($failures.Count -gt 0) {
    Write-Host ''
    Write-Host "启动预算不合格 - $($failures.Count) 项：" -ForegroundColor Red
    foreach ($f in $failures) { Write-Host "  - $f" -ForegroundColor Red }
    exit 1
}

Write-Host ''
Write-Host ("RESULT: 启动预算全过（L0 min={0}ms / L1 min={1}ms）" -f $l0.Min, $l1.Min) -ForegroundColor Green
exit 0
