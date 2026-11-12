<#
    闸门 08-mcp-acceptance：MCP 工具与内置工具走同一条治理路径

    因：specs/mcp-client 里最关键的一条是「MCP 工具 MUST 实现 Tool 接口，
        执行入口仍是 ToolExecutor」。这句话对不对不用看代码——
        `tool_call: ` 这行日志只有 ToolExecutor 会打。两条路径都出现它，
        就说明 MCP 工具没能绕过执行器。

    五条：
      1. 路径 A（内置）：tools: [http_get]，问天气 → stdout 含温度，stderr 有 tool_call: http_get
      2. 路径 B（MCP）：mcp_servers 配一个 stdio server，tools: [get_current_weather]
         → stdout 含 server 返回的温度，stderr 有 tool_call: get_current_weather，
         且没有任何 tool_call: http_get
      3. 工具来源可见：tool list -a mcp-agent 的描述里带 [mcp:openmeteo]
      4. 边界提示：启动日志出现「域名白名单不覆盖该 server」
      5. 沙箱：allowed-commands 清空后启动同一个 Agent → 退出码非 0，消息含 sandbox
    另有一条独立断言：退出后没有残留的 MCP 子进程。

    MCP server 用的是 lytgos-tool 测试夹具里的 FakeMcpServer（纯 JDK，只实现协议够用的一段）。
    为什么不用现成的开源 server：gbrigandi/mcp-server-openmeteo 只发了 linux / macos 二进制，
    Windows 没有可执行文件；换第三方 Node 包又要赌 npm 仓库可用。夹具反而更适合做闸门——
    它不联网、输出固定，断言不会因为外部服务抽风而红。

    第 1、2 条需要真实模型。没有 DEEPSEEK_API_KEY 时记 SKIP 并返回 2。

    三态：
      0        全 PASS
      2        有检查因缺 API key 或缺少夹具未执行
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

function Invoke-Lytgos {
    param([string]$WorkDir, [string[]]$CliArgs)
    $stdoutFile = Join-Path $WorkDir '_stdout.txt'
    $stderrFile = Join-Path $WorkDir '_stderr.txt'
    Push-Location $WorkDir
    $ErrorActionPreference = 'Continue'
    try {
        & java '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' -jar $jar @CliArgs 1> $stdoutFile 2> $stderrFile
        $code = $LASTEXITCODE
    }
    finally {
        $ErrorActionPreference = 'Stop'
        Pop-Location
    }
    return [pscustomobject]@{
        ExitCode = $code
        Stdout   = [string](Get-Content -LiteralPath $stdoutFile -Raw -Encoding UTF8)
        Stderr   = [string](Get-Content -LiteralPath $stderrFile -Raw -Encoding UTF8)
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

# 放行 java 作为 MCP server 的启动命令
$configFile = Join-Path $workspace '.lytgos/config.yaml'
$configText = [System.IO.File]::ReadAllText($configFile)
$configText = $configText -replace '(?m)^\s*allowed-commands:\s*\[\]\s*$', "  allowed-commands:`n    - java"
Write-Utf8NoBom -Path $configFile -Text $configText

$agentsDir = Join-Path $workspace '.lytgos/agents'

# 路径 A：只用内置工具
$builtinAgent = @"
---
name: builtin-path
description: 只用内置 http_get
provider:
  name: deepseek
  model: deepseek-chat
tools:
  - http_get
---
查天气只能用 http_get，请求 api.open-meteo.com。
"@

# 路径 B：只用 MCP 工具。tools 里不列 http_get，它就不该出现在模型面前
$mcpAgent = @"
---
name: mcp-path
description: 只用 MCP server 提供的工具
provider:
  name: deepseek
  model: deepseek-chat
tools:
  - get_current_weather
mcp_servers:
  - name: openmeteo
    command: '$javaForYaml'
    args:
      - '-cp'
      - '$fixtureForYaml'
      - '$fixtureClass'
---
查天气只能调用 get_current_weather，不要尝试其他工具。
"@

$builtinDir = Join-Path $agentsDir 'builtin-path'
$mcpDir = Join-Path $agentsDir 'mcp-path'
New-Item -ItemType Directory -Path $builtinDir -Force | Out-Null
New-Item -ItemType Directory -Path $mcpDir -Force | Out-Null
Write-Utf8NoBom -Path (Join-Path $builtinDir 'AGENT.md') -Text $builtinAgent
Write-Utf8NoBom -Path (Join-Path $mcpDir 'AGENT.md') -Text $mcpAgent

$apiKey = $env:DEEPSEEK_API_KEY

# ---------- 1. 路径 A：内置工具 ----------
Write-Host ''
if ([string]::IsNullOrWhiteSpace($apiKey)) {
    Record '1' '路径 A · 内置 http_get' 'SKIP' '未设置 DEEPSEEK_API_KEY'
}
else {
    $a = Invoke-Lytgos -WorkDir $workspace -CliArgs @('chat', '-a', 'builtin-path', '-m', '北京今天天气怎么样')
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

# ---------- 2. 路径 B：MCP 工具 ----------
Write-Host ''
if ([string]::IsNullOrWhiteSpace($apiKey)) {
    Record '2' '路径 B · MCP 工具' 'SKIP' '未设置 DEEPSEEK_API_KEY'
}
else {
    $b = Invoke-Lytgos -WorkDir $workspace -CliArgs @('chat', '-a', 'mcp-path', '-m', '上海今天天气怎么样')
    $temperature = $b.Stdout -match '4\.4|°C|℃|度'
    $mcpCalls = ([regex]::Matches($b.Stderr, 'tool_call: get_current_weather')).Count
    $strayHttp = ([regex]::Matches($b.Stderr, 'tool_call: http_get')).Count
    if ($b.ExitCode -ne 0) {
        Record '2' '路径 B · MCP 工具' 'FAIL' "退出码 $($b.ExitCode)：$($b.Stderr.Trim())"
    }
    elseif (-not $temperature) {
        Record '2' '路径 B · MCP 工具' 'FAIL' "stdout 未出现 server 返回的气温：$($b.Stdout.Trim())"
    }
    elseif ($mcpCalls -lt 1) {
        Record '2' '路径 B · MCP 工具' 'FAIL' "stderr 没有 tool_call: get_current_weather，MCP 工具没走 ToolExecutor"
    }
    elseif ($strayHttp -gt 0) {
        Record '2' '路径 B · MCP 工具' 'FAIL' "tools 白名单里没有 http_get，它却仍被调用 $strayHttp 次"
    }
    else {
        Record '2' '路径 B · MCP 工具' 'PASS' "含 server 返回的气温，get_current_weather 调用 $mcpCalls 次，未越权调用内置工具"
    }
}

# ---------- 3. 工具来源可见 ----------
Write-Host ''
$list = Invoke-Lytgos -WorkDir $workspace -CliArgs @('tool', 'list', '-a', 'mcp-path')
if ($list.ExitCode -ne 0) {
    Record '3' '工具来源可见' 'FAIL' "tool list 退出码 $($list.ExitCode)：$($list.Stderr.Trim())"
}
elseif ($list.Stdout -notmatch '\[mcp:openmeteo\]') {
    Record '3' '工具来源可见' 'FAIL' "工具描述里没有 [mcp:openmeteo] 来源标注：$($list.Stdout.Trim())"
}
elseif ($list.Stdout -match 'http_get') {
    Record '3' '工具来源可见' 'FAIL' "tools 白名单外的 http_get 出现在了列表里：$($list.Stdout.Trim())"
}
else {
    Record '3' '工具来源可见' 'PASS' 'MCP 工具带来源标注，且白名单外的内置工具没有混进来'
}

# ---------- 4. 边界提示 ----------
Write-Host ''
$boundary = "$($list.Stderr)"
if ($boundary -match '域名白名单不覆盖该 server') {
    Record '4' '边界提示' 'PASS' '启动日志说明了 MCP 子进程不受域名白名单约束'
}
else {
    Record '4' '边界提示' 'FAIL' "启动日志里没有边界提示，用户会误以为 MCP 工具也受域名白名单保护：$($boundary.Trim())"
}

# ---------- 5. 沙箱：命令白名单 ----------
Write-Host ''
$blockedConfig = $configText -replace '(?m)^\s*allowed-commands:[\s\S]*?(?=^\S|\z)', "  allowed-commands: []`n"
Write-Utf8NoBom -Path $configFile -Text $blockedConfig
$blocked = Invoke-Lytgos -WorkDir $workspace -CliArgs @('tool', 'list', '-a', 'mcp-path')
$blockedText = $blocked.Stdout + $blocked.Stderr
if ($blocked.ExitCode -eq 0) {
    Record '5' '沙箱 · 命令白名单' 'FAIL' '命令白名单为空，MCP server 却启动成功了'
}
elseif ($blockedText -notmatch 'sandbox') {
    Record '5' '沙箱 · 命令白名单' 'FAIL' "退出码 $($blocked.ExitCode) 正常，但消息里没有 sandbox：$($blockedText.Trim())"
}
else {
    Record '5' '沙箱 · 命令白名单' 'PASS' "退出码 $($blocked.ExitCode)，消息含 sandbox"
}
# 还原配置，避免影响后续手工排查
Write-Utf8NoBom -Path $configFile -Text $configText

# ---------- 6. 无残留子进程 ----------
Write-Host ''
$leftovers = @(Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" -ErrorAction SilentlyContinue |
    Where-Object { $_.CommandLine -and $_.CommandLine.Contains($fixtureClass) })
if ($leftovers.Count -eq 0) {
    Record '6' '无残留子进程' 'PASS' '跑完后没有遗留的 FakeMcpServer 进程'
}
else {
    Record '6' '无残留子进程' 'FAIL' "残留 $($leftovers.Count) 个 MCP 子进程，PID: $($leftovers.ProcessId -join ', ')"
}

# ---------- 汇总 ----------
$failed = @($results | Where-Object { $_.Status -eq 'FAIL' })
$skipped = @($results | Where-Object { $_.Status -eq 'SKIP' })

Write-Host ''
Write-Host '===== MCP 双路径验收 =====' -ForegroundColor Cyan
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
    Write-Host "RESULT: NOT READY - $($skipped.Count) 条未执行（需要 DEEPSEEK_API_KEY）" -ForegroundColor Yellow
    exit 2
}
Write-Host 'RESULT: 全部 PASS' -ForegroundColor Green
exit 0
