<#
    闸门 14-web-api-acceptance：Web 服务端点的真实链路验收

    因：控制器单测证明的是「协议映射对」，证明不了「打包后的 lytgos serve 在同一份
        配置下真能建会话、跑对话、归档，并把管理台与 OpenAPI 一起端出来」。
        本闸门用临时工作区 + 真实进程，把 web-service spec 的 Scenario 逐条对账。

    判据：
      不依赖模型（始终执行，11 条）：
        1.  GET /api/v1/health            200 code=0 status=UP
        2.  GET /api/v1/info              200 code=0 virtualThread=true，providers 非空且无密钥字段
        3.  GET /api/v1/profiles          200 items 含 name=default
        4.  GET /api/v1/tools             200 工具数为 7（内置注册顺序）
        5.  GET /api/v1/memory            200 content 为空、chars=0、maxChars=8000
        6.  POST /api/v1/sessions         200 data.sessionId 非空
        7.  GET /api/v1/sessions          200 items 含刚建的会话标识
        8.  GET /api/v1/sessions/{id}     200 messages 长度为 0
        9.  DELETE /api/v1/sessions/{id}  200 archived=true，随后 GET 返回 404（归档后不可查）
        10. GET /api/v1/no-such-endpoint  404 code=LYTGOS_NOT_FOUND
        11. GET /admin/                   200 text/html，含页面标题，源码无 http(s) 外部引用
        12. GET /v3/api-docs              200 含 /api/v1/sessions 与 /api/v1/profiles

      依赖模型（缺 DEEPSEEK_API_KEY 时记 SKIP）：
        M1. 发消息返回非空 reply，历史落盘且首条为 user、末条为 assistant
            （不锁死条数：模型可能插入工具往返，固定 2 条会随模型行为假红）
        M2. 跨请求上下文：先报名字，再问名字，回复含该名字
        M3. 无状态 invoke 返回非空 reply，且调用前后会话数不变

    三态：
      0  全部执行且通过
      2  有检查因缺 API key 未执行（NOT_READY，不算通过）
      1  启动失败、端口被占或任一断言 FAIL

    注意：
      - 端口固定 18083（design.md D9 的端口分配表）；被占时直接 FAIL，不杀别人的进程。
      - 工作区用 `lytgos-cli-boot.jar init` 在临时目录建出：serve 要求工作区已存在。
      - 收尸按「端口 + 命令行」双重校验：serve 的命令行里是 `serve --port 18083`，
        不是 `--server.port` 参数（那个由 ServeCommand 内部拼给 Spring）。
      - 启动失败时保留日志与临时工作区并打印尾部；成功时清理。
#>
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Set-Location $projectRoot

$jar = Join-Path $projectRoot 'lytgos-cli/target/lytgos-cli-boot.jar'
if (-not (Test-Path -LiteralPath $jar)) {
    Write-Host 'NOT_READY: 缺少 lytgos-cli/target/lytgos-cli-boot.jar，先执行 mvn package' -ForegroundColor DarkYellow
    exit 2
}
$java = Get-Command java -ErrorAction SilentlyContinue
if (-not $java) {
    Write-Host 'NOT_READY: PATH 上找不到 java' -ForegroundColor DarkYellow
    exit 2
}

$port = 18083
$base = "http://127.0.0.1:$port"
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$workRoot = Join-Path $env:TEMP "lytgos-web-api-$stamp"
$logFile = Join-Path $env:TEMP "lytgos-web-api-$stamp.log"
$errFile = Join-Path $env:TEMP "lytgos-web-api-$stamp.err"

$passes = @()
$skips = @()
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

function Add-Skip {
    param([string]$Name, [string]$Detail)
    $script:skips += ("{0} — {1}" -f $Name, $Detail)
    Write-Host ("  [SKIP] {0} — {1}" -f $Name, $Detail) -ForegroundColor DarkYellow
}

# 非 2xx 在 PS 5.1 里是异常；统一成对象，让断言只关心状态码与正文。
# 解码必须走 RawContentStream 的原始字节：Spring 返回的 Content-Type 是
# application/json（按 RFC 8259 不写 charset），PS 5.1 会按 ISO-8859-1 解码，
# 中文全部变乱码——M2 「回复含张三」就是被这个解码坑判成 FAIL 的。
function Invoke-Api {
    param([string]$Method, [string]$Path, [object]$Body, [int]$TimeoutSec = 15)
    $params = @{
        Uri            = ($script:base + $Path)
        UseBasicParsing = $true
        Method         = $Method
        TimeoutSec     = $TimeoutSec
    }
    if ($null -ne $Body) {
        $json = $Body | ConvertTo-Json -Compress
        $params['ContentType'] = 'application/json; charset=utf-8'
        $params['Body'] = [System.Text.Encoding]::UTF8.GetBytes($json)
    }
    try {
        $response = Invoke-WebRequest @params
        $raw = $response.RawContentStream
        if ($null -ne $raw) {
            $content = [System.Text.Encoding]::UTF8.GetString($raw.ToArray())
        }
        else {
            $content = $response.Content
            if ($content -is [byte[]]) { $content = [System.Text.Encoding]::UTF8.GetString($content) }
        }
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
            $reader = New-Object System.IO.StreamReader($resp.GetResponseStream(), [System.Text.Encoding]::UTF8)
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

New-Item -ItemType Directory -Force -Path $workRoot | Out-Null
Push-Location $workRoot
try {
    & java -jar $jar init | Out-Null
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath (Join-Path $workRoot '.lytgos'))) {
        Write-Host 'FAIL: 临时工作区 init 失败' -ForegroundColor Red
        exit 1
    }
}
finally {
    Pop-Location
}
Write-Host ("临时工作区: {0}" -f $workRoot) -ForegroundColor DarkGray

$proc = $null
$succeeded = $false
try {
    $proc = Start-Process -FilePath $java.Source `
        -ArgumentList @('-jar', "`"$jar`"", 'serve', '--port', $port, '--root', "`"$workRoot`"") `
        -RedirectStandardOutput $logFile -RedirectStandardError $errFile -PassThru
    Write-Host ("启动 lytgos serve（PID {0}，端口 {1}），日志: {2}" -f $proc.Id, $port, $logFile) -ForegroundColor DarkGray

    $ready = $false
    for ($i = 0; $i -lt 120; $i++) {
        if ($proc.HasExited) { break }
        Start-Sleep -Milliseconds 500
        $probe = Invoke-Api -Method 'GET' -Path '/api/v1/health'
        if ($probe.Status -eq 200) { $ready = $true; break }
    }
    if (-not $ready) {
        Write-Host 'FAIL: 服务未在 60s 内就绪' -ForegroundColor Red
        if ($proc.HasExited) {
            Write-Host ("      进程已退出，退出码 {0}" -f $proc.ExitCode) -ForegroundColor Red
        }
        if (Test-Path -LiteralPath $logFile) {
            Write-Host '----- 启动日志尾部 -----' -ForegroundColor DarkYellow
            Get-Content -LiteralPath $logFile -Tail 40 | ForEach-Object { Write-Host $_ -ForegroundColor DarkYellow }
        }
        if ((Test-Path -LiteralPath $errFile) -and (Get-Item -LiteralPath $errFile).Length -gt 0) {
            Write-Host '----- 错误流尾部 -----' -ForegroundColor DarkYellow
            Get-Content -LiteralPath $errFile -Tail 40 | ForEach-Object { Write-Host $_ -ForegroundColor DarkYellow }
        }
        Write-Host ("日志保留在 {0}，临时工作区 {1}" -f $logFile, $workRoot) -ForegroundColor DarkYellow
        exit 1
    }

    Write-Host ''
    Write-Host '===== 不依赖模型的断言 =====' -ForegroundColor Cyan

    # 1 health
    $r = Invoke-Api -Method 'GET' -Path '/api/v1/health'
    $j = ConvertFrom-JsonSafe $r.Body
    $ok = ($r.Status -eq 200) -and $j -and ($j.code -eq '0') -and ($j.data.status -eq 'UP')
    Add-Check -Name 'GET /api/v1/health' -Ok $ok -Detail ("status={0} status={1}" -f $r.Status, ($j | ForEach-Object { $_.data.status }))

    # 2 info
    $r = Invoke-Api -Method 'GET' -Path '/api/v1/info'
    $j = ConvertFrom-JsonSafe $r.Body
    $providerCount = 0
    if ($j) { $providerCount = @($j.data.providers).Count }
    $ok = ($r.Status -eq 200) -and $j -and ($j.code -eq '0') -and ($j.data.virtualThread -eq $true) -and
        ($providerCount -ge 1) -and (-not ($r.Body -match 'apiKey|api-key|DEEPSEEK_API_KEY'))
    Add-Check -Name 'GET /api/v1/info' -Ok $ok -Detail ("status={0} virtualThread={1} providers={2}" -f $r.Status, ($j | ForEach-Object { $_.data.virtualThread }), $providerCount)

    # 3 profiles
    $r = Invoke-Api -Method 'GET' -Path '/api/v1/profiles'
    $j = ConvertFrom-JsonSafe $r.Body
    $hasDefault = $false
    if ($j -and $j.data) {
        $hasDefault = @($j.data.items | Where-Object { $_.name -eq 'default' }).Count -ge 1
    }
    $ok = ($r.Status -eq 200) -and $j -and ($j.code -eq '0') -and $hasDefault
    Add-Check -Name 'GET /api/v1/profiles' -Ok $ok -Detail ("status={0} 含 default={1}" -f $r.Status, $hasDefault)

    # 4 tools
    $r = Invoke-Api -Method 'GET' -Path '/api/v1/tools'
    $j = ConvertFrom-JsonSafe $r.Body
    $toolCount = 0
    if ($j -and $j.data) { $toolCount = @($j.data.tools).Count }
    $ok = ($r.Status -eq 200) -and $j -and ($j.code -eq '0') -and ($j.data.agent -eq 'default') -and ($toolCount -eq 7)
    Add-Check -Name 'GET /api/v1/tools' -Ok $ok -Detail ("status={0} agent={1} 工具数={2}（预期 7）" -f $r.Status, ($j | ForEach-Object { $_.data.agent }), $toolCount)

    # 5 memory
    $r = Invoke-Api -Method 'GET' -Path '/api/v1/memory'
    $j = ConvertFrom-JsonSafe $r.Body
    $ok = ($r.Status -eq 200) -and $j -and ($j.code -eq '0') -and
        ([string]::IsNullOrEmpty([string]$j.data.content)) -and ($j.data.chars -eq 0) -and ($j.data.maxChars -eq 8000)
    Add-Check -Name 'GET /api/v1/memory' -Ok $ok -Detail ("status={0} chars={1} maxChars={2}" -f $r.Status, ($j | ForEach-Object { $_.data.chars }), ($j | ForEach-Object { $_.data.maxChars }))

    # 6 建会话
    $sid = $null
    $r = Invoke-Api -Method 'POST' -Path '/api/v1/sessions' -Body @{ agent = 'default' }
    $j = ConvertFrom-JsonSafe $r.Body
    if ($j -and $j.data) { $sid = [string]$j.data.sessionId }
    $ok = ($r.Status -eq 200) -and $j -and ($j.code -eq '0') -and (-not [string]::IsNullOrWhiteSpace($sid))
    Add-Check -Name 'POST /api/v1/sessions' -Ok $ok -Detail ("status={0} sessionId={1}" -f $r.Status, $sid)

    # 7 会话列表
    $listed = $false
    if ($sid) {
        $r = Invoke-Api -Method 'GET' -Path '/api/v1/sessions'
        $j = ConvertFrom-JsonSafe $r.Body
        if ($j -and $j.data) {
            $listed = @($j.data.items | Where-Object { $_.sessionId -eq $sid }).Count -eq 1
        }
        $ok = ($r.Status -eq 200) -and $j -and ($j.code -eq '0') -and $listed
    }
    else {
        $r = [pscustomobject]@{ Status = 0 }
        $ok = $false
    }
    Add-Check -Name 'GET /api/v1/sessions' -Ok $ok -Detail ("status={0} 含新建会话={1}" -f $r.Status, $listed)

    # 8 会话详情（空历史）
    $messageCount = -1
    if ($sid) {
        $r = Invoke-Api -Method 'GET' -Path ("/api/v1/sessions/" + [uri]::EscapeDataString($sid))
        $j = ConvertFrom-JsonSafe $r.Body
        if ($j -and $j.data) { $messageCount = @($j.data.messages).Count }
        $ok = ($r.Status -eq 200) -and $j -and ($j.code -eq '0') -and ($messageCount -eq 0)
    }
    else {
        $r = [pscustomobject]@{ Status = 0 }
        $ok = $false
    }
    Add-Check -Name 'GET /api/v1/sessions/{id}' -Ok $ok -Detail ("status={0} messages={1}" -f $r.Status, $messageCount)

    # 9 归档 + 归档后不可查
    $archived = $false
    $afterStatus = 0
    $afterCode = ''
    if ($sid) {
        $r = Invoke-Api -Method 'DELETE' -Path ("/api/v1/sessions/" + [uri]::EscapeDataString($sid))
        $j = ConvertFrom-JsonSafe $r.Body
        if ($j -and $j.data) { $archived = ($j.data.archived -eq $true) }
        $after = Invoke-Api -Method 'GET' -Path ("/api/v1/sessions/" + [uri]::EscapeDataString($sid))
        $afterJson = ConvertFrom-JsonSafe $after.Body
        $afterStatus = $after.Status
        if ($afterJson) { $afterCode = [string]$afterJson.code }
        $ok = ($r.Status -eq 200) -and $archived -and ($afterStatus -eq 404) -and ($afterCode -eq 'LYTGOS_NOT_FOUND')
    }
    else {
        $ok = $false
    }
    Add-Check -Name 'DELETE /api/v1/sessions/{id}' -Ok $ok -Detail ("archived={0} 归档后 status={1} code={2}" -f $archived, $afterStatus, $afterCode)

    # 10 未知路径统一 404
    $r = Invoke-Api -Method 'GET' -Path '/api/v1/no-such-endpoint'
    $j = ConvertFrom-JsonSafe $r.Body
    $ok = ($r.Status -eq 404) -and $j -and ($j.code -eq 'LYTGOS_NOT_FOUND')
    Add-Check -Name 'GET /api/v1/no-such-endpoint' -Ok $ok -Detail ("status={0} code={1}" -f $r.Status, ($j | ForEach-Object { $_.code }))

    # 11 管理台
    $r = Invoke-Api -Method 'GET' -Path '/admin/'
    $contentType = ''
    if ($r.Headers -and $r.Headers['Content-Type']) { $contentType = [string]$r.Headers['Content-Type'] }
    $ok = ($r.Status -eq 200) -and ($contentType -match 'text/html') -and
        ($r.Body -match '<title>lytgos 控制台</title>') -and
        (-not ($r.Body -match 'http://')) -and (-not ($r.Body -match 'https://'))
    Add-Check -Name 'GET /admin/' -Ok $ok -Detail ("status={0} content-type={1} 无外部引用={2}" -f $r.Status, $contentType, (-not ($r.Body -match 'http://|https://')))

    # 12 OpenAPI 新路径
    $r = Invoke-Api -Method 'GET' -Path '/v3/api-docs' -TimeoutSec 30
    $ok = ($r.Status -eq 200) -and ($r.Body -match '"openapi"') -and
        ($r.Body -match '/api/v1/sessions') -and ($r.Body -match '/api/v1/profiles')
    Add-Check -Name 'GET /v3/api-docs' -Ok $ok -Detail ("status={0} 含 sessions={1} 含 profiles={2}" -f $r.Status, ($r.Body -match '/api/v1/sessions'), ($r.Body -match '/api/v1/profiles'))

    Write-Host ''
    Write-Host '===== 依赖模型的断言 =====' -ForegroundColor Cyan

    if ([string]::IsNullOrWhiteSpace($env:DEEPSEEK_API_KEY)) {
        Add-Skip -Name 'M1 发消息往返' -Detail '缺 DEEPSEEK_API_KEY'
        Add-Skip -Name 'M2 跨请求上下文' -Detail '缺 DEEPSEEK_API_KEY'
        Add-Skip -Name 'M3 无状态 invoke' -Detail '缺 DEEPSEEK_API_KEY'
    }
    else {
        # M1 发消息（新会话，user=gate，避免与已归档的默认会话混用）
        $chatId = $null
        $r = Invoke-Api -Method 'POST' -Path '/api/v1/sessions' -Body @{ agent = 'default'; user = 'gate' }
        $j = ConvertFrom-JsonSafe $r.Body
        if ($j -and $j.data) { $chatId = [string]$j.data.sessionId }

        $reply = ''
        $history = -1
        $firstRole = ''
        $lastRole = ''
        if ($chatId) {
            $r = Invoke-Api -Method 'POST' -Path ("/api/v1/sessions/" + [uri]::EscapeDataString($chatId) + '/messages') -Body @{ message = '你好' } -TimeoutSec 120
            $j = ConvertFrom-JsonSafe $r.Body
            if ($j -and $j.data) { $reply = [string]$j.data.reply }
            $detail = Invoke-Api -Method 'GET' -Path ("/api/v1/sessions/" + [uri]::EscapeDataString($chatId))
            $detailJson = ConvertFrom-JsonSafe $detail.Body
            if ($detailJson -and $detailJson.data) {
                # 不锁死条数：模型可能插入工具往返（user + assistant[toolCalls] + tool + assistant）
                $msgs = @($detailJson.data.messages)
                $history = $msgs.Count
                if ($history -ge 1) {
                    $firstRole = [string]$msgs[0].role
                    $lastRole = [string]$msgs[$history - 1].role
                }
            }
            $ok = ($r.Status -eq 200) -and (-not [string]::IsNullOrWhiteSpace($reply)) -and
                ($history -ge 2) -and ($firstRole -eq 'user') -and ($lastRole -eq 'assistant')
        }
        else {
            $r = [pscustomobject]@{ Status = 0 }
            $ok = $false
        }
        Add-Check -Name 'M1 发消息往返' -Ok $ok -Detail ("status={0} reply 非空={1} 历史条数={2}（首={3} 末={4}）" -f $r.Status, (-not [string]::IsNullOrWhiteSpace($reply)), $history, $firstRole, $lastRole)

        # M2 跨请求上下文
        $second = ''
        if ($chatId) {
            $null = Invoke-Api -Method 'POST' -Path ("/api/v1/sessions/" + [uri]::EscapeDataString($chatId) + '/messages') -Body @{ message = '我的名字是张三，请只回复“收到”两个字' } -TimeoutSec 120
            $r = Invoke-Api -Method 'POST' -Path ("/api/v1/sessions/" + [uri]::EscapeDataString($chatId) + '/messages') -Body @{ message = '我叫什么名字？请只回复名字本身' } -TimeoutSec 120
            $j = ConvertFrom-JsonSafe $r.Body
            if ($j -and $j.data) { $second = [string]$j.data.reply }
            $ok = ($r.Status -eq 200) -and ($second -match '张三')
        }
        else {
            $r = [pscustomobject]@{ Status = 0 }
            $ok = $false
        }
        Add-Check -Name 'M2 跨请求上下文' -Ok $ok -Detail ("status={0} 回复含张三={1}" -f $r.Status, ($second -match '张三'))

        # M3 无状态 invoke：调用前后会话数不变
        $before = -1
        $after = -1
        $invokeReply = ''
        $r = Invoke-Api -Method 'GET' -Path '/api/v1/sessions'
        $j = ConvertFrom-JsonSafe $r.Body
        if ($j -and $j.data) { $before = [int]$j.data.total }
        $r = Invoke-Api -Method 'POST' -Path '/api/v1/agents/default/invoke' -Body @{ input = '用一句话介绍你自己' } -TimeoutSec 120
        $j = ConvertFrom-JsonSafe $r.Body
        if ($j -and $j.data) { $invokeReply = [string]$j.data.reply }
        $afterResponse = Invoke-Api -Method 'GET' -Path '/api/v1/sessions'
        $afterJson = ConvertFrom-JsonSafe $afterResponse.Body
        if ($afterJson -and $afterJson.data) { $after = [int]$afterJson.data.total }
        $ok = ($r.Status -eq 200) -and (-not [string]::IsNullOrWhiteSpace($invokeReply)) -and ($before -eq $after)
        Add-Check -Name 'M3 无状态 invoke' -Ok $ok -Detail ("status={0} reply 非空={1} 会话数 {2}->{3}" -f $r.Status, (-not [string]::IsNullOrWhiteSpace($invokeReply)), $before, $after)
    }

    if ($failures.Count -eq 0 -and $skips.Count -eq 0) { $succeeded = $true }
}
finally {
    # 按「端口 + 命令行」双重校验收尸：只终止命令行确实是本闸门 serve 的进程
    foreach ($conn in @(Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue)) {
        $candidate = [int]$conn.OwningProcess
        $cmdline = (Get-CimInstance Win32_Process -Filter "ProcessId=$candidate" -ErrorAction SilentlyContinue).CommandLine
        if ($cmdline -and ($cmdline -match [regex]::Escape($jar)) -and ($cmdline -match '\bserve\b') -and ($cmdline -match "--port\s+$port")) {
            try { Stop-Process -Id $candidate -Force -ErrorAction SilentlyContinue } catch { }
        }
    }
    if ($proc -and -not $proc.HasExited) {
        try { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue } catch { }
        try { $null = $proc.WaitForExit(5000) } catch { }
    }
    if ($succeeded) {
        Remove-Item -LiteralPath $logFile -Force -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath $errFile -Force -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath $workRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
    else {
        Write-Host ("日志: {0}；临时工作区: {1}" -f $logFile, $workRoot) -ForegroundColor DarkYellow
    }
}

Write-Host ''
Write-Host '===== Web API 验收汇总 =====' -ForegroundColor Cyan
foreach ($p in $passes) { Write-Host ("  [PASS] {0}" -f $p) -ForegroundColor Green }
foreach ($s in $skips) { Write-Host ("  [SKIP] {0}" -f $s) -ForegroundColor DarkYellow }
foreach ($f in $failures) { Write-Host ("  [FAIL] {0}" -f $f) -ForegroundColor Red }
Write-Host ("PASS {0} / SKIP {1} / FAIL {2}" -f $passes.Count, $skips.Count, $failures.Count)

if ($failures.Count -gt 0) {
    Write-Host 'RESULT: FAIL' -ForegroundColor Red
    exit 1
}
if ($skips.Count -gt 0) {
    Write-Host 'RESULT: NOT_READY（依赖模型的检查未执行，不算通过）' -ForegroundColor DarkYellow
    exit 2
}
Write-Host ("RESULT: 全部 PASS（端口 {0} 已释放）" -f $port) -ForegroundColor Green
exit 0
