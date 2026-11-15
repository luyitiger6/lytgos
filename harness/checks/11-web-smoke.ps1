<#
    闸门 11-web-smoke：Web 地基冒烟

    因：Web 地基（虚拟线程 / 统一响应 / traceId / 探针 / OpenAPI）的单测证明不了
        「打包后的 jar 在真实端口上可用」。本闸门启动 lytgos-boot/target/lytgos.jar，
        用真实 HTTP 请求逐条核对 spec 的 Scenario，避免「测试绿了、进程起不来」。

    判据（9 条，全部 PASS 才通过）：
      1. /actuator/health 200 且 status=UP
      2. /actuator/health/liveness 200 且 status=UP
      3. /actuator/health/readiness 200 且 status=UP
      4. /actuator/prometheus 200 且含 jvm_ 指标行
      5. /api/v1/system/info 200、code=="0"、data.virtualThread==true
      6. /api/v1/no-such-endpoint 404、code=="LYTGOS_NOT_FOUND"
      7. /v3/api-docs 200 且含 openapi 与 /api/v1/system/info
      8. 带 X-Request-Id: abc123 的请求，响应头回写 abc123
      9. 不带 X-Request-Id 的请求，响应头生成非空 traceId

    三态：
      0  九条全部 PASS
      2  缺少 lytgos-boot/target/lytgos.jar（先 mvn package），或 PATH 上无 java
      1  启动失败、端口被占或任一断言 FAIL

    注意：
      - 端口用启动参数 --server.port=18080 覆盖，避开 8080（VMware 常占）、8081
        （lytgos-channel）与 9898（默认入口），不影响本机其他服务。
      - 用 PATH 上的 java（与 07/08/09 的调用点一致）；JDK 版本一致性由 run-all 的
        JdkGuard 负责，本闸门不重复判定。
      - 启动失败时保留临时日志并打印尾部，成功时删除；失败现场比干净磁盘重要。
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

$port = 18080
$base = "http://localhost:$port"
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$logFile = Join-Path $env:TEMP "lytgos-web-smoke-$stamp.log"
$errFile = Join-Path $env:TEMP "lytgos-web-smoke-$stamp.err"

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

# 非 2xx 在 PS 5.1 里是异常；这里统一成对象，让断言只关心状态码与正文
function Invoke-Http {
    param([string]$Path, [string]$RequestId)
    $headers = @{}
    if ($RequestId) { $headers['X-Request-Id'] = $RequestId }
    try {
        $response = Invoke-WebRequest -Uri ($script:base + $Path) -UseBasicParsing -Headers $headers -TimeoutSec 10
        # PS 5.1 对非 text/json 媒体类型（如 actuator 的 vnd.spring-boot.actuator.v3+json）
        # 会返回 byte[]，不统一解码会让正则断言对着一串十进制字节比对
        $content = $response.Content
        if ($content -is [byte[]]) { $content = [System.Text.Encoding]::UTF8.GetString($content) }
        return [pscustomobject]@{
            Status  = [int]$response.StatusCode
            Body    = [string]$content
            Headers = $response.Headers
            Error   = $null
        }
    }
    catch {
        $resp = $_.Exception.Response
        if ($resp -is [System.Net.HttpWebResponse]) {
            $reader = New-Object System.IO.StreamReader($resp.GetResponseStream())
            $body = $reader.ReadToEnd()
            $reader.Dispose()
            return [pscustomobject]@{
                Status  = [int]$resp.StatusCode
                Body    = $body
                Headers = $resp.Headers
                Error   = $null
            }
        }
        return [pscustomobject]@{
            Status  = 0
            Body    = ''
            Headers = @{}
            Error   = $_.Exception.Message
        }
    }
}

function ConvertFrom-JsonSafe {
    param([string]$Text)
    try { return ($Text | ConvertFrom-Json) } catch { return $null }
}

# 端口被占时直接失败：误杀别人的进程比不跑更糟
$occupant = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
if ($occupant) {
    Write-Host ("FAIL: 端口 {0} 已被占用（PID {1}），本闸门不杀进程，请先释放端口" -f $port, (($occupant | Select-Object -First 1).OwningProcess)) -ForegroundColor Red
    exit 1
}

$proc = $null
try {
    $proc = Start-Process -FilePath $java.Source `
        -ArgumentList @('-jar', "`"$jar`"", "--server.port=$port") `
        -RedirectStandardOutput $logFile -RedirectStandardError $errFile -PassThru
    Write-Host ("启动 lytgos.jar（PID {0}，端口 {1}），日志: {2}" -f $proc.Id, $port, $logFile) -ForegroundColor DarkGray

    # 最长等 30s：启动含 JVM + Spring 上下文 + Tomcat，慢机器与杀软扫描足以吃掉 10s+
    $ready = $false
    for ($i = 0; $i -lt 60; $i++) {
        if ($proc.HasExited) { break }
        Start-Sleep -Milliseconds 500
        $probe = Invoke-Http -Path '/actuator/health'
        if ($probe.Status -eq 200) { $ready = $true; break }
    }
    if (-not $ready) {
        Write-Host 'FAIL: 服务未在 30s 内就绪' -ForegroundColor Red
        if ($proc.HasExited) {
            Write-Host ("      进程已退出，退出码 {0}" -f $proc.ExitCode) -ForegroundColor Red
        }
        if (Test-Path -LiteralPath $logFile) {
            Write-Host '----- 启动日志尾部 -----' -ForegroundColor DarkYellow
            Get-Content -LiteralPath $logFile -Tail 30 | ForEach-Object { Write-Host $_ -ForegroundColor DarkYellow }
        }
        if ((Test-Path -LiteralPath $errFile) -and (Get-Item -LiteralPath $errFile).Length -gt 0) {
            Write-Host '----- 错误流尾部 -----' -ForegroundColor DarkYellow
            Get-Content -LiteralPath $errFile -Tail 30 | ForEach-Object { Write-Host $_ -ForegroundColor DarkYellow }
        }
        Write-Host ("日志保留在 {0}" -f $logFile) -ForegroundColor DarkYellow
        exit 1
    }

    Write-Host ''
    Write-Host '已就绪，开始逐条断言：' -ForegroundColor Cyan

    # 1-3 探针三组
    foreach ($path in @('/actuator/health', '/actuator/health/liveness', '/actuator/health/readiness')) {
        $r = Invoke-Http -Path $path
        $ok = ($r.Status -eq 200) -and ($r.Body -match '"status"\s*:\s*"UP"')
        Add-Check -Name $path -Ok $ok -Detail ("status={0} body={1}" -f $r.Status, ($r.Body -replace '\s+', ' ').Trim())
    }

    # 4 Prometheus 指标
    $r = Invoke-Http -Path '/actuator/prometheus'
    $jvmLines = @(($r.Body -split "`r?`n") | Where-Object { $_ -match '^jvm_' })
    $ok = ($r.Status -eq 200) -and ($jvmLines.Count -gt 0)
    Add-Check -Name '/actuator/prometheus' -Ok $ok -Detail ("status={0} jvm_ 指标行={1}" -f $r.Status, $jvmLines.Count)

    # 5 系统信息端点
    $r = Invoke-Http -Path '/api/v1/system/info'
    $j = ConvertFrom-JsonSafe $r.Body
    $ok = ($r.Status -eq 200) -and $j -and ($j.code -eq '0') -and ($j.data.virtualThread -eq $true)
    Add-Check -Name '/api/v1/system/info' -Ok $ok -Detail ("status={0} code={1} virtualThread={2}" -f $r.Status, ($j | ForEach-Object { $_.code }), ($j | ForEach-Object { $_.data.virtualThread }))

    # 6 未知 /api/** 的统一 404
    $r = Invoke-Http -Path '/api/v1/no-such-endpoint'
    $j = ConvertFrom-JsonSafe $r.Body
    $ok = ($r.Status -eq 404) -and $j -and ($j.code -eq 'LYTGOS_NOT_FOUND')
    Add-Check -Name '/api/v1/no-such-endpoint' -Ok $ok -Detail ("status={0} code={1}" -f $r.Status, ($j | ForEach-Object { $_.code }))

    # 7 OpenAPI 文档
    $r = Invoke-Http -Path '/v3/api-docs'
    $ok = ($r.Status -eq 200) -and ($r.Body -match '"openapi"') -and ($r.Body -match '/api/v1/system/info')
    Add-Check -Name '/v3/api-docs' -Ok $ok -Detail ("status={0} 含 openapi={1} 含 system/info={2}" -f $r.Status, ($r.Body -match '"openapi"'), ($r.Body -match '/api/v1/system/info'))

    # 8-9 traceId 回写：复用传入值与缺省生成
    $r = Invoke-Http -Path '/api/v1/system/info' -RequestId 'abc123'
    $echoed = [string]$r.Headers['X-Request-Id']
    Add-Check -Name 'traceId 复用' -Ok ($echoed -eq 'abc123') -Detail ("响应头 X-Request-Id={0}" -f $echoed)

    $r = Invoke-Http -Path '/api/v1/system/info'
    $generated = [string]$r.Headers['X-Request-Id']
    Add-Check -Name 'traceId 生成' -Ok (-not [string]::IsNullOrWhiteSpace($generated)) -Detail ("响应头 X-Request-Id={0}" -f $generated)
}
finally {
    # Start-Process 返回的 PID 可能只是启动壳（实测与真实 java 进程不一致），
    # 因此按「端口 + 命令行双重校验」收尸：只终止命令行确实是本闸门 jar 的进程，
    # 避免误杀本机其他服务。
    foreach ($conn in @(Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue)) {
        $candidate = [int]$conn.OwningProcess
        $cmdline = (Get-CimInstance Win32_Process -Filter "ProcessId=$candidate" -ErrorAction SilentlyContinue).CommandLine
        if ($cmdline -and ($cmdline -match [regex]::Escape($jar)) -and ($cmdline -match "--server\.port=$port")) {
            try { Stop-Process -Id $candidate -Force -ErrorAction SilentlyContinue } catch { }
        }
    }
    if ($proc -and -not $proc.HasExited) {
        try { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue } catch { }
        try { $null = $proc.WaitForExit(5000) } catch { }
    }
}

Write-Host ''
Write-Host '===== Web 冒烟汇总 =====' -ForegroundColor Cyan
foreach ($p in $passes) { Write-Host ("  [PASS] {0}" -f $p) -ForegroundColor Green }
foreach ($f in $failures) { Write-Host ("  [FAIL] {0}" -f $f) -ForegroundColor Red }

if ($failures.Count -gt 0) {
    Write-Host ("RESULT: FAIL - {0} 条不通过（启动日志: {1}）" -f $failures.Count, $logFile) -ForegroundColor Red
    exit 1
}

Remove-Item -LiteralPath $logFile -Force -ErrorAction SilentlyContinue
Remove-Item -LiteralPath $errFile -Force -ErrorAction SilentlyContinue
Write-Host ("RESULT: 九条全 PASS（端口 {0} 已释放）" -f $port) -ForegroundColor Green
exit 0
