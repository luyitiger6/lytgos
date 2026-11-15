<#
    闸门 12-log-trace：日志格式与 traceId 端到端

    因：observability spec 的两条日志契约（dev 人类可读 / prod 单行 JSON / traceId 贯穿
        请求期日志）只有「打包后的 jar + 真实 profile」能验证。单测跑的是 test 专用
        logback 配置，证明不了 logback-spring.xml 里两个 springProfile 分支的真实行为。

    判据（6 条，全部 PASS 才通过）：
      dev（默认 profile，不加任何 --logging 覆盖）：
      1. 日志出现业务 DEBUG 行（TraceIdFilter 的 trace attached 探针）
      2. 日志中不存在以 { 开头的行（人类可读，不是 JSON）
      3. 带 X-Request-Id 请求后，探针行携带同一个值
      prod（--spring.profiles.active=prod --logging.level.io.lytgos=DEBUG）：
      4. 每个以 { 开头的行都能 ConvertFrom-Json
      5. 第一条 JSON 行（启动期）含 timestamp / level / logger / message 四字段
      6. 请求期存在 traceId 等于请求头 X-Request-Id 的 JSON 行

    三态：
      0  六条全部 PASS
      2  缺少 lytgos-boot/target/lytgos.jar（先 mvn package），或 PATH 上无 java
      1  端口被占、启动失败或任一断言 FAIL

    注意：
      - dev 与 prod 必须分两个进程：logback 的 springProfile 只在日志上下文初始化时求值，
        一个 JVM 只有一组 active profile，进程内无法切换格式。
      - prod 判据只检查以 { 开头的行：第三方库可能往 stdout 打非 JSON 文本，把它算作
        「日志」，闸门就会因为别人的输出而假红。
      - dev 判据依赖 D8 的「dev 默认把 io.lytgos 开到 DEBUG」；有人删掉那两条 logger
        覆盖时，本闸门会在判据 1 上报 FAIL，而不是静默跳过。
      - 端口用 18081 / 18082，避开 11 号闸门的 18080 与本机常见端口；收尸按
        「端口 + 命令行双重校验」，只终止本闸门的 jar 进程。
#>
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Set-Location $projectRoot

$jar = Join-Path $projectRoot 'lytgos-boot/target/lytgos.jar'
if (-not (Test-Path -LiteralPath $jar)) {
    Write-Host 'NOT_READY: 缺少 lytgos-boot/target/lytgos.jar，先执行 mvn package' -ForegroundColor DarkYellow
    exit 2
}
$java = Get-Command java -ErrorAction SilentlyContinue
if (-not $java) {
    Write-Host 'NOT_READY: PATH 上找不到 java' -ForegroundColor DarkYellow
    exit 2
}

$devPort = 18081
$prodPort = 18082
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$devLog = Join-Path $env:TEMP "lytgos-log-trace-dev-$stamp.log"
$devErr = Join-Path $env:TEMP "lytgos-log-trace-dev-$stamp.err"
$prodLog = Join-Path $env:TEMP "lytgos-log-trace-prod-$stamp.log"
$prodErr = Join-Path $env:TEMP "lytgos-log-trace-prod-$stamp.err"

$passes = @()
$failures = @()

function Add-Check {
    param([string]$Name, [bool]$Ok, [string]$Detail)
    if ($Ok) {
        $script:passes += $Name
        Write-Host ("  [PASS] {0} — {1}" -f $Name, $Detail) -ForegroundColor Green
    }
    else {
        $script:failures += ("{0} — {1}" -f $Name, $Detail)
        Write-Host ("  [FAIL] {0} — {1}" -f $Name, $Detail) -ForegroundColor Red
    }
}

function Invoke-Health {
    param([int]$Port)
    try {
        $r = Invoke-WebRequest -Uri "http://localhost:$Port/actuator/health" -UseBasicParsing -TimeoutSec 5
        return ([int]$r.StatusCode -eq 200)
    }
    catch {
        return $false
    }
}

function ConvertFrom-JsonSafe {
    param([string]$Text)
    try { return ($Text | ConvertFrom-Json) } catch { return $null }
}

function Start-App {
    param([int]$Port, [string[]]$ExtraArgs, [string]$LogPath, [string]$ErrPath)
    $argList = @('-jar', "`"$jar`"", "--server.port=$Port") + $ExtraArgs
    return Start-Process -FilePath $java.Source -ArgumentList $argList `
        -RedirectStandardOutput $LogPath -RedirectStandardError $ErrPath -PassThru
}

function Stop-App {
    param([int]$Port, $Proc)
    # Start-Process 的 PID 可能只是启动壳，按「端口 + 命令行双重校验」收尸
    foreach ($conn in @(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)) {
        $candidate = [int]$conn.OwningProcess
        $cmdline = (Get-CimInstance Win32_Process -Filter "ProcessId=$candidate" -ErrorAction SilentlyContinue).CommandLine
        if ($cmdline -and ($cmdline -match [regex]::Escape($jar)) -and ($cmdline -match "--server\.port=$Port")) {
            try { Stop-Process -Id $candidate -Force -ErrorAction SilentlyContinue } catch { }
        }
    }
    if ($Proc -and -not $Proc.HasExited) {
        try { Stop-Process -Id $Proc.Id -Force -ErrorAction SilentlyContinue } catch { }
        try { $null = $Proc.WaitForExit(5000) } catch { }
    }
}

function Wait-Ready {
    param([int]$Port, $Proc)
    for ($i = 0; $i -lt 60; $i++) {
        if ($Proc.HasExited) { return $false }
        Start-Sleep -Milliseconds 500
        if (Invoke-Health -Port $Port) { return $true }
    }
    return $false
}

function Get-LogTail {
    param([string]$Path, [int]$Lines)
    if (Test-Path -LiteralPath $Path) {
        Get-Content -LiteralPath $Path -Tail $Lines | ForEach-Object { Write-Host $_ -ForegroundColor DarkYellow }
    }
}

foreach ($p in @($devPort, $prodPort)) {
    $occupant = Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue
    if ($occupant) {
        Write-Host ("FAIL: 端口 {0} 已被占用（PID {1}），本闸门不杀进程，请先释放端口" -f $p, (($occupant | Select-Object -First 1).OwningProcess)) -ForegroundColor Red
        exit 1
    }
}

$probe = 'probe-' + [guid]::NewGuid().ToString('N').Substring(0, 8)

# ---------- dev：默认 profile ----------
Write-Host ''
Write-Host ("dev 阶段（端口 {0}，默认 profile）" -f $devPort) -ForegroundColor Cyan
$devProc = $null
try {
    $devProc = Start-App -Port $devPort -ExtraArgs @() -LogPath $devLog -ErrPath $devErr
    if (-not (Wait-Ready -Port $devPort -Proc $devProc)) {
        Write-Host 'FAIL: dev 未在 30s 内就绪' -ForegroundColor Red
        Get-LogTail -Path $devLog -Lines 30
        Write-Host ("日志保留在 {0}" -f $devLog) -ForegroundColor DarkYellow
        exit 1
    }
    Invoke-WebRequest -Uri "http://localhost:$devPort/api/v1/system/info" `
        -Headers @{ 'X-Request-Id' = $probe } -UseBasicParsing -TimeoutSec 10 | Out-Null
    Start-Sleep -Milliseconds 800

    $devLines = @()
    if (Test-Path -LiteralPath $devLog) { $devLines = @(Get-Content -LiteralPath $devLog) }
    $probeLines = @($devLines | Where-Object { $_ -match 'trace attached:' })
    Add-Check -Name 'dev 业务 DEBUG 可见' -Ok ($probeLines.Count -gt 0) `
        -Detail ("trace attached 行={0}" -f $probeLines.Count)

    $jsonLike = @($devLines | Where-Object { $_ -match '^\s*\{' })
    Add-Check -Name 'dev 日志非 JSON' -Ok ($jsonLike.Count -eq 0) `
        -Detail ("以 {{ 开头的行={0}" -f $jsonLike.Count)

    $withProbe = @($probeLines | Where-Object { $_ -match [regex]::Escape($probe) })
    Add-Check -Name 'dev 探针携带请求 traceId' -Ok ($withProbe.Count -gt 0) `
        -Detail ("含 {0} 的探针行={1}" -f $probe, $withProbe.Count)
}
finally {
    Stop-App -Port $devPort -Proc $devProc
}

# ---------- prod：JSON 格式 + traceId 字段 ----------
Write-Host ''
Write-Host ("prod 阶段（端口 {0}，prod profile + 业务 DEBUG）" -f $prodPort) -ForegroundColor Cyan
$prodProc = $null
try {
    $prodProc = Start-App -Port $prodPort `
        -ExtraArgs @('--spring.profiles.active=prod', '--logging.level.io.lytgos=DEBUG') `
        -LogPath $prodLog -ErrPath $prodErr
    if (-not (Wait-Ready -Port $prodPort -Proc $prodProc)) {
        Write-Host 'FAIL: prod 未在 30s 内就绪' -ForegroundColor Red
        Get-LogTail -Path $prodLog -Lines 30
        Write-Host ("日志保留在 {0}" -f $prodLog) -ForegroundColor DarkYellow
        exit 1
    }
    Invoke-WebRequest -Uri "http://localhost:$prodPort/api/v1/system/info" `
        -Headers @{ 'X-Request-Id' = $probe } -UseBasicParsing -TimeoutSec 10 | Out-Null
    Start-Sleep -Milliseconds 800

    $prodLines = @()
    if (Test-Path -LiteralPath $prodLog) { $prodLines = @(Get-Content -LiteralPath $prodLog) }
    $jsonLines = @($prodLines | Where-Object { $_ -match '^\s*\{' })

    $bad = @()
    foreach ($line in $jsonLines) {
        if ($null -eq (ConvertFrom-JsonSafe $line)) { $bad += $line; break }
    }
    Add-Check -Name 'prod 日志为 JSON' -Ok (($jsonLines.Count -gt 0) -and ($bad.Count -eq 0)) `
        -Detail ("JSON 行={0} 解析失败={1}" -f $jsonLines.Count, $bad.Count)

    $first = $null
    if ($jsonLines.Count -gt 0) { $first = ConvertFrom-JsonSafe $jsonLines[0] }
    $hasFields = ($null -ne $first) -and ($null -ne $first.timestamp) -and ($null -ne $first.level) `
        -and ($null -ne $first.logger) -and ($null -ne $first.message)
    Add-Check -Name 'prod 启动行含四字段' -Ok $hasFields `
        -Detail ("timestamp={0} level={1} logger={2} message={3}" -f `
            ($first | ForEach-Object { $_.timestamp }), ($first | ForEach-Object { $_.level }), `
            ($first | ForEach-Object { $_.logger }), ($first | ForEach-Object { $_.message }))

    $traceMatched = $false
    foreach ($line in $jsonLines) {
        $obj = ConvertFrom-JsonSafe $line
        if ($obj -and ($obj.traceId -eq $probe)) { $traceMatched = $true; break }
    }
    Add-Check -Name 'prod 请求行 traceId 一致' -Ok $traceMatched `
        -Detail ("期望 traceId={0}" -f $probe)
}
finally {
    Stop-App -Port $prodPort -Proc $prodProc
}

Write-Host ''
Write-Host '===== 日志契约汇总 =====' -ForegroundColor Cyan
foreach ($p in $passes) { Write-Host ("  [PASS] {0}" -f $p) -ForegroundColor Green }
foreach ($f in $failures) { Write-Host ("  [FAIL] {0}" -f $f) -ForegroundColor Red }

if ($failures.Count -gt 0) {
    Write-Host ("RESULT: FAIL - {0} 条不通过（dev 日志: {1}；prod 日志: {2}）" -f $failures.Count, $devLog, $prodLog) -ForegroundColor Red
    exit 1
}

Remove-Item -LiteralPath $devLog -Force -ErrorAction SilentlyContinue
Remove-Item -LiteralPath $devErr -Force -ErrorAction SilentlyContinue
Remove-Item -LiteralPath $prodLog -Force -ErrorAction SilentlyContinue
Remove-Item -LiteralPath $prodErr -Force -ErrorAction SilentlyContinue
Write-Host ("RESULT: 六条全 PASS（端口 {0} / {1} 已释放）" -f $devPort, $prodPort) -ForegroundColor Green
exit 0
