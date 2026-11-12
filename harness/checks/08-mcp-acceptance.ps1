<#
    闸门 08-mcp-acceptance：MCP 两种形态都走同一条治理路径

    因：specs/mcp-client 里最关键的一条是「MCP 工具 MUST 实现 Tool 接口，
        执行入口仍是 ToolExecutor」。这句话对不对不用看代码——
        `tool_call: ` 这行日志只有 ToolExecutor 会打。两条路径都出现它，
        就说明 MCP 工具没能绕过执行器。

    七条：
      1. 路径 A（内置）：tools: [http_get]，问天气 → stdout 含温度，stderr 有 tool_call: http_get
      2. 路径 B（远程 MCP）：mcp_servers 配高德在线服务，tools: [maps_weather]
         → stdout 含天气描述，stderr 有 tool_call: maps_weather，且没有任何 tool_call: http_get
      3. 路径 C（本地 MCP）：mcp_servers 配 stdio 夹具，tools: [get_current_weather]
         → stdout 含夹具返回的温度，stderr 有 tool_call: get_current_weather
      4. 工具来源可见：远程带 [mcp:amap]，本地带 [mcp:localweather]；白名单外的内置工具不混进来
      5. 边界提示按形态分：远程说「地址已通过域名白名单校验」，本地说「域名白名单不覆盖该 server」
      6. 沙箱两条：命令白名单为空 → 本地起不来；域名白名单缺 mcp.amap.com → 远程连不上，都要含 sandbox
      7. 退出后没有残留的 MCP 子进程

    路径 C 的 server 用 lytgos-tool 测试夹具里的 FakeMcpServer（纯 JDK，只实现协议够用的一段）。
    为什么不用现成的开源 server：能直接拿来跑的多数只发 linux / macos 二进制，Windows 没有可执行文件；
    换第三方 Node 包又要赌 npm 仓库可用。夹具不联网、输出固定，断言不会因为外部服务抽风而红。
    它只服务路径 C 与离线单测，不参与任何「能连真实外部服务」的断言。

    第 1、2、3 条需要真实模型（DEEPSEEK_API_KEY）；第 1 条的天气请求与第 2、4、5 条的远程部分还需要
    AMAP_MCP_KEY（路径 A 用高德 Web 服务的天气接口验内置 http_get，与远程 MCP 同一家供应商）。
    缺失时对应条目记 SKIP 并返回 2。第 6 条不需要任何 key：沙箱拒绝发生在建连接之前。

    三态：
      0        全 PASS
      2        有检查因缺 API key 未执行
      1        有检查 FAIL
#>
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Set-Location $projectRoot

$jar = Join-Path $projectRoot 'lytgos-cli/target/lytgos-cli-boot.jar'
$fixtureDir = Join-Path $projectRoot 'lytgos-tool/target/test-classes'
$fixtureClass = 'io.lytgos.tool.mcp.FakeMcpServer'
$javaExe = (Get-Command java).Source

if (-not (Test-Path -LiteralPath $jar)) {
    Write-Host 'NOT_READY: 缺少 lytgos-cli/target/lytgos-cli-boot.jar，先执行 mvn package' -ForegroundColor DarkYellow
    exit 2
}
if (-not (Test-Path -LiteralPath (Join-Path $fixtureDir 'io/lytgos/tool/mcp/FakeMcpServer.class'))) {
    Write-Host 'NOT_READY: 缺少 FakeMcpServer 测试夹具，先执行 mvn test-compile' -ForegroundColor DarkYellow
    exit 2
}

# 路径分隔符统一成正斜杠：YAML 里不用转义，SandboxChecker 取文件名也照样能识别
$javaForYaml = $javaExe.Replace('\', '/')
$fixtureForYaml = $fixtureDir.Replace('\', '/')

$workRoot = Join-Path $env:TEMP ("lytgos-mcp-accept-" + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $workRoot -Force | Out-Null
Write-Host "工作目录: $workRoot" -ForegroundColor DarkGray

$results = New-Object System.Collections.ArrayList

function Record {
    param([string]$Id, [string]$Name, [string]$Status, [string]$Detail)
    [void]$results.Add([pscustomobject]@{ Id = $Id; Name = $Name; Status = $Status; Detail = $Detail })
    $color = switch ($Status) { 'PASS' { 'Green' } 'FAIL' { 'Red' } default { 'DarkYellow' } }
    Write-Host ("  [{0}] {1}. {2} — {3}" -f $Status, $Id, $Name, $Detail) -ForegroundColor $color
}

function Write-Utf8NoBom {
    param([string]$Path, [string]$Text)
    [System.IO.File]::WriteAllText($Path, $Text, (New-Object System.Text.UTF8Encoding($false)))
}

function Quote-Arg {
    param([string]$Value)
    if ($Value -match '[\s"]') {
        return '"' + ($Value -replace '"', '\"') + '"'
    }
    return $Value
}

# 不能用 `& java ... 2> file`：PowerShell 会把原生命令的 stderr 包成 ErrorRecord，
# 再按控制台宽度硬折行写进文件。长日志被拆成两行后，跨词断言（如「地址已通过域名白名单校验」）
# 会永远匹配不上。ProcessStartInfo 直接重定向拿到的是原始 stderr，不做任何再加工。
function Invoke-Lytgos {
    param([string]$WorkDir, [string[]]$CliArgs)
    $utf8 = New-Object System.Text.UTF8Encoding($false)

    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = 'java'
    $psi.WorkingDirectory = $WorkDir
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.StandardOutputEncoding = $utf8
    $psi.StandardErrorEncoding = $utf8
    $psi.CreateNoWindow = $true
    $arguments = @('-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8', '-jar', $jar) + $CliArgs
    $psi.Arguments = ($arguments | ForEach-Object { Quote-Arg $_ }) -join ' '

    $process = [System.Diagnostics.Process]::Start($psi)
    $stdoutTask = $process.StandardOutput.ReadToEndAsync()
    $stderrTask = $process.StandardError.ReadToEndAsync()
    $process.WaitForExit()
    $stdout = $stdoutTask.Result
    $stderr = $stderrTask.Result

    # 失败时留着原文供人工排查
    Write-Utf8NoBom -Path (Join-Path $WorkDir '_stdout.txt') -Text $stdout
    Write-Utf8NoBom -Path (Join-Path $WorkDir '_stderr.txt') -Text $stderr

    return [pscustomobject]@{
        ExitCode = $process.ExitCode
        Stdout   = $stdout
        Stderr   = $stderr
    }
}

# ---------- 准备一个工作区 ----------
$workspace = Join-Path $workRoot 'ws'
New-Item -ItemType Directory -Path $workspace -Force | Out-Null
$init = Invoke-Lytgos -WorkDir $workspace -CliArgs @('init')
if ($init.ExitCode -ne 0) {
    Write-Host "工作区初始化失败，无法继续: $($init.Stderr)" -ForegroundColor Red
    exit 1
}

# 放行 java 作为本地 MCP server 的启动命令；远程形态走 url，不受命令白名单约束
$configFile = Join-Path $workspace '.lytgos/config.yaml'
$configText = [System.IO.File]::ReadAllText($configFile)
$configText = $configText -replace '(?m)^\s*allowed-commands:\s*\[\]\s*$', "  allowed-commands:`n    - java"
# init 模板里已经带这两个域名；这里兜一次底，防止模板回退后闸门莫名变红
$requiredDomains = @('restapi.amap.com', 'mcp.amap.com')
$missingDomains = @($requiredDomains | Where-Object { $configText -notmatch [regex]::Escape($_) })
if ($missingDomains.Count -gt 0) {
    $appendDomains = ($missingDomains | ForEach-Object { "`r`n    - $_" }) -join ''
    $configText = $configText -replace '(?m)^(\s*)allowed-domains:\s*$', "`$0$appendDomains"
}
Write-Utf8NoBom -Path $configFile -Text $configText

$agentsDir = Join-Path $workspace '.lytgos/agents'

# 路径 A：只用内置工具
$builtinAgent = @"
---
name: builtin-path
description: 只用内置 http_get
provider:
  name: deepseek
  model: deepseek-flash
tools:
  - http_get
---
查天气只能用 http_get，由用户给出完整 URL。
"@

# 路径 B：远程 MCP。tools 里不列 http_get，它就不该出现在模型面前
$amapKey = $env:AMAP_MCP_KEY
$remoteAgent = @"
---
name: remote-path
description: 远程 MCP 在线服务
provider:
  name: deepseek
  model: deepseek-flash
tools:
  - maps_weather
mcp_servers:
  - name: amap
    url: 'https://mcp.amap.com/mcp?key=$amapKey'
---
查天气只能调用 maps_weather，不要尝试其他工具。
"@

# 路径 C：本地 MCP（stdio 夹具）
$localAgent = @"
---
name: local-path
description: 本地 MCP server 提供的工具
provider:
  name: deepseek
  model: deepseek-flash
tools:
  - get_current_weather
mcp_servers:
  - name: localweather
    command: '$javaForYaml'
    args:
      - '-cp'
      - '$fixtureForYaml'
      - '$fixtureClass'
---
查天气只能调用 get_current_weather，不要尝试其他工具。
"@

foreach ($pair in @(@('builtin-path', $builtinAgent), @('remote-path', $remoteAgent), @('local-path', $localAgent))) {
    $dir = Join-Path $agentsDir $pair[0]
    New-Item -ItemType Directory -Path $dir -Force | Out-Null
    Write-Utf8NoBom -Path (Join-Path $dir 'AGENT.md') -Text $pair[1]
}

$apiKey = $env:DEEPSEEK_API_KEY

# ---------- 1. 路径 A：内置工具 ----------
Write-Host ''
if ([string]::IsNullOrWhiteSpace($apiKey)) {
    Record '1' '路径 A · 内置 http_get' 'SKIP' '未设置 DEEPSEEK_API_KEY'
}
elseif ([string]::IsNullOrWhiteSpace($amapKey)) {
    Record '1' '路径 A · 内置 http_get' 'SKIP' '未设置 AMAP_MCP_KEY（http_get 查天气要用）'
}
else {
    $builtinUrl = "https://restapi.amap.com/v3/weather/weatherInfo?key=$amapKey&city=110000"
    $a = Invoke-Lytgos -WorkDir $workspace -CliArgs @('chat', '-a', 'builtin-path', '-m', "请访问 $builtinUrl 并告诉我北京现在的气温是多少度")
    $temperature = $a.Stdout -match '\d+(\.\d+)?\s*(°C|℃|度)'
    $toolCalls = ([regex]::Matches($a.Stderr, 'tool_call: http_get')).Count
    if ($a.ExitCode -ne 0) {
        Record '1' '路径 A · 内置 http_get' 'FAIL' "退出码 $($a.ExitCode)：$($a.Stderr.Trim())"
    }
    elseif (-not $temperature) {
        Record '1' '路径 A · 内置 http_get' 'FAIL' "stdout 未出现温度数值：$($a.Stdout.Trim())"
    }
    elseif ($toolCalls -lt 1) {
        Record '1' '路径 A · 内置 http_get' 'FAIL' "stderr 没有 tool_call: http_get，执行没经过 ToolExecutor"
    }
    else {
        Record '1' '路径 A · 内置 http_get' 'PASS' "含温度，http_get 调用 $toolCalls 次"
    }
}

# ---------- 2. 路径 B：远程 MCP（高德在线） ----------
Write-Host ''
if ([string]::IsNullOrWhiteSpace($apiKey)) {
    Record '2' '路径 B · 远程 MCP（高德）' 'SKIP' '未设置 DEEPSEEK_API_KEY'
}
elseif ([string]::IsNullOrWhiteSpace($amapKey)) {
    Record '2' '路径 B · 远程 MCP（高德）' 'SKIP' '未设置 AMAP_MCP_KEY'
}
else {
    $b = Invoke-Lytgos -WorkDir $workspace -CliArgs @('chat', '-a', 'remote-path', '-m', '北京今天天气怎么样')
    $weather = $b.Stdout -match '(晴|多云|阴|雨|雪|°C|℃|度)'
    $mcpCalls = ([regex]::Matches($b.Stderr, 'tool_call: maps_weather')).Count
    $strayHttp = ([regex]::Matches($b.Stderr, 'tool_call: http_get')).Count
    if ($b.ExitCode -ne 0) {
        Record '2' '路径 B · 远程 MCP（高德）' 'FAIL' "退出码 $($b.ExitCode)：$($b.Stderr.Trim())"
    }
    elseif (-not $weather) {
        Record '2' '路径 B · 远程 MCP（高德）' 'FAIL' "stdout 未出现天气描述：$($b.Stdout.Trim())"
    }
    elseif ($mcpCalls -lt 1) {
        Record '2' '路径 B · 远程 MCP（高德）' 'FAIL' "stderr 没有 tool_call: maps_weather，MCP 工具没走 ToolExecutor"
    }
    elseif ($strayHttp -gt 0) {
        Record '2' '路径 B · 远程 MCP（高德）' 'FAIL' "tools 白名单里没有 http_get，它却仍被调用 $strayHttp 次"
    }
    else {
        Record '2' '路径 B · 远程 MCP（高德）' 'PASS' "含天气描述，maps_weather 调用 $mcpCalls 次，未越权调用内置工具"
    }
}

# ---------- 3. 路径 C：本地 MCP（stdio 夹具） ----------
Write-Host ''
if ([string]::IsNullOrWhiteSpace($apiKey)) {
    Record '3' '路径 C · 本地 MCP（stdio）' 'SKIP' '未设置 DEEPSEEK_API_KEY'
}
else {
    $c = Invoke-Lytgos -WorkDir $workspace -CliArgs @('chat', '-a', 'local-path', '-m', '上海今天天气怎么样')
    $temperature = $c.Stdout -match '4\.4|°C|℃|度'
    $mcpCalls = ([regex]::Matches($c.Stderr, 'tool_call: get_current_weather')).Count
    $strayHttp = ([regex]::Matches($c.Stderr, 'tool_call: http_get')).Count
    if ($c.ExitCode -ne 0) {
        Record '3' '路径 C · 本地 MCP（stdio）' 'FAIL' "退出码 $($c.ExitCode)：$($c.Stderr.Trim())"
    }
    elseif (-not $temperature) {
        Record '3' '路径 C · 本地 MCP（stdio）' 'FAIL' "stdout 未出现夹具返回的气温：$($c.Stdout.Trim())"
    }
    elseif ($mcpCalls -lt 1) {
        Record '3' '路径 C · 本地 MCP（stdio）' 'FAIL' "stderr 没有 tool_call: get_current_weather，MCP 工具没走 ToolExecutor"
    }
    elseif ($strayHttp -gt 0) {
        Record '3' '路径 C · 本地 MCP（stdio）' 'FAIL' "tools 白名单里没有 http_get，它却仍被调用 $strayHttp 次"
    }
    else {
        Record '3' '路径 C · 本地 MCP（stdio）' 'PASS' "含夹具返回的气温，get_current_weather 调用 $mcpCalls 次，未越权调用内置工具"
    }
}

# ---------- 4. 工具来源可见 ----------
Write-Host ''
$localList = Invoke-Lytgos -WorkDir $workspace -CliArgs @('tool', 'list', '-a', 'local-path')
if ($localList.ExitCode -ne 0) {
    Record '4' '工具来源可见' 'FAIL' "本地 tool list 退出码 $($localList.ExitCode)：$($localList.Stderr.Trim())"
}
elseif ($localList.Stdout -notmatch '\[mcp:localweather\]') {
    Record '4' '工具来源可见' 'FAIL' "工具描述里没有 [mcp:localweather] 来源标注：$($localList.Stdout.Trim())"
}
elseif ($localList.Stdout -match 'http_get') {
    Record '4' '工具来源可见' 'FAIL' "tools 白名单外的 http_get 出现在了列表里：$($localList.Stdout.Trim())"
}
elseif ([string]::IsNullOrWhiteSpace($amapKey)) {
    Record '4' '工具来源可见' 'SKIP' '本地来源已验；远程来源需要 AMAP_MCP_KEY'
}
else {
    $remoteList = Invoke-Lytgos -WorkDir $workspace -CliArgs @('tool', 'list', '-a', 'remote-path')
    if ($remoteList.ExitCode -ne 0) {
        Record '4' '工具来源可见' 'FAIL' "远程 tool list 退出码 $($remoteList.ExitCode)：$($remoteList.Stderr.Trim())"
    }
    elseif ($remoteList.Stdout -notmatch '\[mcp:amap\]') {
        Record '4' '工具来源可见' 'FAIL' "远程工具描述里没有 [mcp:amap]：$($remoteList.Stdout.Trim())"
    }
    elseif ($remoteList.Stdout -notmatch 'maps_weather') {
        Record '4' '工具来源可见' 'FAIL' "远程工具清单里没有 maps_weather：$($remoteList.Stdout.Trim())"
    }
    else {
        Record '4' '工具来源可见' 'PASS' '远程 [mcp:amap] 与本地 [mcp:localweather] 都带来源标注，白名单外的内置工具没有混进来'
    }
}

# ---------- 5. 边界提示按形态分 ----------
Write-Host ''
$localBoundary = "$($localList.Stderr)"
$localOk = $localBoundary -match '域名白名单不覆盖该 server'
$remoteOk = $true
$remoteDetail = ''
if ($localList.ExitCode -eq 0) {
    if ([string]::IsNullOrWhiteSpace($amapKey)) {
        $remoteOk = $null
        $remoteDetail = '；远程边界提示未验（需要 AMAP_MCP_KEY）'
    }
    else {
        $remoteList2 = Invoke-Lytgos -WorkDir $workspace -CliArgs @('tool', 'list', '-a', 'remote-path')
        $remoteOk = "$($remoteList2.Stderr)" -match '地址已通过域名白名单校验'
        if (-not $remoteOk) {
            $remoteDetail = "；远程日志没有「地址已通过域名白名单校验」：$("$($remoteList2.Stderr)".Trim())"
        }
    }
}
if ($localList.ExitCode -ne 0) {
    Record '5' '边界提示' 'FAIL' "本地 tool list 失败，无法核对边界提示：$($localList.Stderr.Trim())"
}
elseif (-not $localOk) {
    Record '5' '边界提示' 'FAIL' "本地启动日志没有「域名白名单不覆盖该 server」，用户会误以为子进程也受域名白名单保护：$($localBoundary.Trim())"
}
elseif ($remoteOk -eq $false) {
    Record '5' '边界提示' 'FAIL' $remoteDetail.TrimStart('；')
}
elseif ($null -eq $remoteOk) {
    Record '5' '边界提示' 'SKIP' "本地边界提示已验$remoteDetail"
}
else {
    Record '5' '边界提示' 'PASS' '远程说明了地址已过域名白名单，本地说明了域名白名单不覆盖子进程'
}

# ---------- 6. 沙箱两条 ----------
Write-Host ''

# 6a 命令白名单为空 → 本地 server 起不来
$noCommands = $configText -replace '(?m)^\s*allowed-commands:[\s\S]*?(?=^\S|\z)', "  allowed-commands: []`n"
Write-Utf8NoBom -Path $configFile -Text $noCommands
$blockedLocal = Invoke-Lytgos -WorkDir $workspace -CliArgs @('tool', 'list', '-a', 'local-path')
$blockedLocalText = $blockedLocal.Stdout + $blockedLocal.Stderr
if ($blockedLocal.ExitCode -eq 0) {
    Record '6a' '沙箱 · 命令白名单' 'FAIL' '命令白名单为空，本地 MCP server 却启动成功了'
}
elseif ($blockedLocalText -notmatch 'sandbox') {
    Record '6a' '沙箱 · 命令白名单' 'FAIL' "退出码 $($blockedLocal.ExitCode) 正常，但消息里没有 sandbox：$($blockedLocalText.Trim())"
}
else {
    Record '6a' '沙箱 · 命令白名单' 'PASS' "退出码 $($blockedLocal.ExitCode)，消息含 sandbox"
}

# 6b 域名白名单缺 mcp.amap.com → 远程连不上。校验在连接之前，所以不需要 AMAP_MCP_KEY
$noAmap = $configText -replace '(?m)^\s*- mcp\.amap\.com\s*\r?\n', ''
Write-Utf8NoBom -Path $configFile -Text $noAmap
$blockedRemote = Invoke-Lytgos -WorkDir $workspace -CliArgs @('tool', 'list', '-a', 'remote-path')
$blockedRemoteText = $blockedRemote.Stdout + $blockedRemote.Stderr
if ($blockedRemote.ExitCode -eq 0) {
    Record '6b' '沙箱 · 域名白名单' 'FAIL' '域名白名单里没有 mcp.amap.com，远程 MCP 却连接成功了'
}
elseif ($blockedRemoteText -notmatch 'sandbox') {
    Record '6b' '沙箱 · 域名白名单' 'FAIL' "退出码 $($blockedRemote.ExitCode) 正常，但消息里没有 sandbox：$($blockedRemoteText.Trim())"
}
else {
    Record '6b' '沙箱 · 域名白名单' 'PASS' "退出码 $($blockedRemote.ExitCode)，消息含 sandbox"
}

# 还原配置，避免影响后续手工排查
Write-Utf8NoBom -Path $configFile -Text $configText

# ---------- 7. 无残留子进程 ----------
Write-Host ''
$leftovers = @(Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -and $_.CommandLine.Contains($fixtureClass) })
if ($leftovers.Count -eq 0) {
    Record '7' '无残留子进程' 'PASS' '跑完后没有遗留的 FakeMcpServer 进程'
}
else {
    Record '7' '无残留子进程' 'FAIL' "残留 $($leftovers.Count) 个 MCP 子进程，PID: $($leftovers.ProcessId -join ', ')"
}

# ---------- 汇总 ----------
$failed = @($results | Where-Object { $_.Status -eq 'FAIL' })
$skipped = @($results | Where-Object { $_.Status -eq 'SKIP' })

Write-Host ''
Write-Host '===== MCP 双形态验收 =====' -ForegroundColor Cyan
foreach ($r in $results) {
    $color = switch ($r.Status) { 'PASS' { 'Green' } 'FAIL' { 'Red' } default { 'DarkYellow' } }
    Write-Host ("  [{0,-4}] {1}. {2}" -f $r.Status, $r.Id, $r.Name) -ForegroundColor $color
}

Write-Host ''
if ($failed.Count -gt 0) {
    Write-Host "RESULT: FAIL - $($failed.Count) 条不通过，证据留在 $workRoot" -ForegroundColor Red
    exit 1
}
if ($skipped.Count -gt 0) {
    Write-Host "RESULT: NOT READY - $($skipped.Count) 条未执行（需要 DEEPSEEK_API_KEY 或 AMAP_MCP_KEY）" -ForegroundColor Yellow
    exit 2
}
Write-Host 'RESULT: 全部 PASS' -ForegroundColor Green
exit 0
