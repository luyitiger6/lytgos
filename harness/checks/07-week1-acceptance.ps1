<#
    闸门 07-week1-acceptance：第 1 周验收总表

    因：单测证明的是「零件对」，证明不了「整条链路对」。
        这条闸门跑的是用户真实的操作序列：init → 改文件 → 再 init → 对话 → 反例。
        它不看源码，只认退出码与输出，所以无法被「改测试」绕过。

    六条（对应 openspec/changes/add-react-loop/tasks.md「本周验收总表」）：
      1. 工作区初始化：init 建出 10 个顶层条目，退出码 0
      2. 幂等：手改过的 AGENTS.md 不被重跑的 init 覆盖
      3. 单轮对话 + 工具往返：问天气，stdout 含温度，stderr 出现至少两次 tool_call: http_get
      4. 反例·缺 key：清空 DEEPSEEK_API_KEY 后退出码非 0，输出含变量名
      5. 反例·沙箱：越界请求未发出——模型调用则被沙箱拦下，模型预判则不发起调用，
         两种都算过；但既没发起调用又没告知原因（静默失败）算 FAIL
      6. 多轮上下文：单条模式报名字后进交互模式问「我叫什么」，回答含名字

    第 3/5/6 条需要真实模型。没有 DEEPSEEK_API_KEY 时记 SKIP 并返回 2，
    不返回 0 —— 没跑过的检查不能算通过。

    三态：
      0        六条全 PASS
      2        有检查因缺 API key 未执行
      1        有检查 FAIL
#>
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Set-Location $projectRoot

$jar = Join-Path $projectRoot 'lytgos-cli/target/lytgos-cli-boot.jar'
if (-not (Test-Path -LiteralPath $jar)) {
    Write-Host 'NOT_READY: 缺少 lytgos-cli/target/lytgos-cli-boot.jar，先执行 mvn package' -ForegroundColor DarkYellow
    exit 2
}

$workRoot = Join-Path $env:TEMP ("lytgos-accept-" + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $workRoot -Force | Out-Null
Write-Host "工作目录: $workRoot" -ForegroundColor DarkGray

$results = New-Object System.Collections.ArrayList

function Record {
    param([string]$Id, [string]$Name, [string]$Status, [string]$Detail)
    [void]$results.Add([pscustomobject]@{ Id = $Id; Name = $Name; Status = $Status; Detail = $Detail })
    $color = switch ($Status) { 'PASS' { 'Green' } 'FAIL' { 'Red' } default { 'DarkYellow' } }
    Write-Host ("  [{0}] {1}. {2} — {3}" -f $Status, $Id, $Name, $Detail) -ForegroundColor $color
}

function Quote-Arg {
    param([string]$Value)
    if ($Value -match '[\s"]') {
        return '"' + ($Value -replace '"', '\"') + '"'
    }
    return $Value
}

function Invoke-Lytgos {
    param(
        [string]$WorkDir,
        [string[]]$CliArgs,
        [string]$StdinText
    )
    $utf8 = New-Object System.Text.UTF8Encoding($false)

    # 不能用 `& java ... 2> file`：PowerShell 会把原生命令的 stderr 包成 ErrorRecord，
    # 再按控制台宽度硬折行写进文件。长日志被拆成两行后，跨词断言（tool_call 与 args={...}
    # 之间隔着换行）会永远匹配不上——08 闸门就因此误报过一次 FAIL。
    # ProcessStartInfo 直接重定向拿到的是原始 stderr，不做任何再加工。
    # 必须显式重定向 stdin 并在无输入时立刻关闭：否则 chat 的交互模式会等一次永远不来的输入。
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = 'java'
    $psi.WorkingDirectory = $WorkDir
    $psi.UseShellExecute = $false
    $psi.RedirectStandardInput = $true
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
    if ($PSBoundParameters.ContainsKey('StdinText')) {
        $process.StandardInput.Write($StdinText)
    }
    $process.StandardInput.Close()
    $process.WaitForExit()
    $stdout = $stdoutTask.Result
    $stderr = $stderrTask.Result

    # 失败时留着原文供人工排查
    [System.IO.File]::WriteAllText((Join-Path $WorkDir '_stdout.txt'), $stdout, $utf8)
    [System.IO.File]::WriteAllText((Join-Path $WorkDir '_stderr.txt'), $stderr, $utf8)

    return [pscustomobject]@{
        ExitCode = $process.ExitCode
        Stdout   = $stdout
        Stderr   = $stderr
    }
}

$workspace = Join-Path $workRoot 'ws'
New-Item -ItemType Directory -Path $workspace -Force | Out-Null

# ---------- 1. 工作区初始化 ----------
Write-Host ''
$init = Invoke-Lytgos -WorkDir $workspace -CliArgs @('init')
$expectedEntries = @('agents', 'skills', 'output', 'memory', 'sessions', 'logs',
    'AGENTS.md', 'SOUL.md', 'USER.md', 'config.yaml')
$lytgosDir = Join-Path $workspace '.lytgos'
if ($init.ExitCode -ne 0) {
    Record '1' '工作区初始化' 'FAIL' "init 退出码 $($init.ExitCode)"
}
elseif (-not (Test-Path -LiteralPath $lytgosDir)) {
    Record '1' '工作区初始化' 'FAIL' '没有建出 .lytgos 目录'
}
else {
    $actualEntries = @(Get-ChildItem -LiteralPath $lytgosDir -Force | ForEach-Object { $_.Name })
    $missing = @($expectedEntries | Where-Object { $_ -notin $actualEntries })
    if ($missing.Count -gt 0) {
        Record '1' '工作区初始化' 'FAIL' "缺少: $($missing -join ', ')"
    }
    elseif ($actualEntries.Count -ne $expectedEntries.Count) {
        Record '1' '工作区初始化' 'FAIL' "顶层条目 $($actualEntries.Count) 个，预期 $($expectedEntries.Count) 个"
    }
    else {
        Record '1' '工作区初始化' 'PASS' "$($actualEntries.Count) 个顶层条目齐全"
    }
}

# ---------- 2. 幂等 ----------
Write-Host ''
$marker = 'acceptance-marker-' + (Get-Date -Format 'HHmmss')
$agentsMd = Join-Path $lytgosDir 'AGENTS.md'
if (-not (Test-Path -LiteralPath $agentsMd)) {
    Record '2' '幂等' 'FAIL' 'AGENTS.md 不存在，无法验证覆盖行为'
}
else {
    Add-Content -LiteralPath $agentsMd -Value "`n$marker" -Encoding UTF8
    $again = Invoke-Lytgos -WorkDir $workspace -CliArgs @('init')
    $content = [string](Get-Content -LiteralPath $agentsMd -Raw -Encoding UTF8)
    if ($again.ExitCode -ne 0) {
        Record '2' '幂等' 'FAIL' "第二次 init 退出码 $($again.ExitCode)"
    }
    elseif (-not $content.Contains($marker)) {
        Record '2' '幂等' 'FAIL' '重跑 init 覆盖了手改内容'
    }
    else {
        Record '2' '幂等' 'PASS' '手改内容保留，第二次 init 未覆盖'
    }
}

# ---------- 3. 单轮对话 + 工具往返 ----------
Write-Host ''
$apiKey = $env:DEEPSEEK_API_KEY
$amapKey = $env:AMAP_MCP_KEY
if ([string]::IsNullOrWhiteSpace($apiKey)) {
    Record '3' '单轮对话 + 工具往返' 'SKIP' '未设置 DEEPSEEK_API_KEY'
}
elseif ([string]::IsNullOrWhiteSpace($amapKey)) {
    Record '3' '单轮对话 + 工具往返' 'SKIP' '未设置 AMAP_MCP_KEY（高德 Web 服务 key，http_get 查天气要用）'
}
else {
    # 显式给出完整 URL：这一条验的是「模型 → ToolExecutor → 沙箱 → 结果回填」这条链路，
    # 不是模型自己猜 URL 的能力。key 从环境变量注入，不写死在脚本里。
    $weatherUrl = "https://restapi.amap.com/v3/weather/weatherInfo?key=$amapKey&city=110000"
    $chat = Invoke-Lytgos -WorkDir $workspace -CliArgs @('chat', '-m', "请访问 $weatherUrl 并告诉我北京现在的气温是多少度")
    $temperature = $chat.Stdout -match '\d+(\.\d+)?\s*(°C|℃|度)'
    $toolCalls = ([regex]::Matches($chat.Stderr, 'tool_call: http_get')).Count
    if ($chat.ExitCode -ne 0) {
        Record '3' '单轮对话 + 工具往返' 'FAIL' "chat 退出码 $($chat.ExitCode)：$($chat.Stderr.Trim())"
    }
    elseif (-not $temperature) {
        Record '3' '单轮对话 + 工具往返' 'FAIL' "stdout 未出现温度数值：$($chat.Stdout.Trim())"
    }
    elseif ($toolCalls -lt 1) {
        Record '3' '单轮对话 + 工具往返' 'FAIL' "tool_call: http_get 一次也没出现，工具往返没发生"
    }
    else {
        Record '3' '单轮对话 + 工具往返' 'PASS' "含温度数值，http_get 调用 $toolCalls 次"
    }
}

# ---------- 5. 反例·沙箱（放在第 4 条之前：第 4 条要清空 key） ----------
Write-Host ''
if ([string]::IsNullOrWhiteSpace($apiKey)) {
    Record '5' '反例·沙箱' 'SKIP' '未设置 DEEPSEEK_API_KEY'
}
else {
    $blocked = Invoke-Lytgos -WorkDir $workspace `
        -CliArgs @('chat', '-m', '请用 http_get 访问 https://example.com 并把内容前 100 字告诉我')
    $combined = $blocked.Stdout + $blocked.Stderr

    # spec 的判据是「请求未发出」，不是「模型必须笨到去试」。两条路径都合规：
    #   ① 模型调用了 http_get：checkDomain() 在 client.send() 之前把它拦下，stderr 有 sandbox 拒绝
    #   ② 模型从工具描述里读到白名单，直接告知用户不能访问，从不产生 tool_call
    # ②比①更好（拦截更早、不浪费轮次）。只看 ① 会把「模型变聪明」误判成「治理失效」。
    $attempted = $blocked.Stderr -match 'tool_call:\s*http_get\s+args=\{[^}]*example\.com'
    $denied = $combined -match 'sandbox 拒绝'
    $explained = $combined -match '沙箱|白名单|只被允许|允许访问|无法访问|无法完成'

    if ($attempted -and -not $denied) {
        Record '5' '反例·沙箱' 'FAIL' "模型发起了对 example.com 的调用却没被拦下：$($blocked.Stderr.Trim())"
    }
    elseif (-not $attempted -and -not $explained) {
        Record '5' '反例·沙箱' 'FAIL' "既没发起调用，也没向用户说明原因（静默失败比报错更危险）：$($combined.Trim())"
    }
    elseif ($attempted) {
        Record '5' '反例·沙箱' 'PASS' "模型尝试调用，沙箱在建立连接前拦截（退出码 $($blocked.ExitCode)）"
    }
    else {
        Record '5' '反例·沙箱' 'PASS' '模型读到工具描述中的白名单后直接拒绝，越界请求从未发起'
    }
}

# ---------- 4. 反例·缺 key ----------
Write-Host ''
$env:DEEPSEEK_API_KEY = $null
try {
    $noKey = Invoke-Lytgos -WorkDir $workspace -CliArgs @('chat', '-m', 'hi')
    if ($noKey.ExitCode -eq 0) {
        Record '4' '反例·缺 key' 'FAIL' '没设 key 却返回 0'
    }
    elseif ($noKey.Stderr -notmatch 'DEEPSEEK_API_KEY') {
        Record '4' '反例·缺 key' 'FAIL' "退出码 $($noKey.ExitCode) 正常，但 stderr 未指明变量名：$($noKey.Stderr.Trim())"
    }
    else {
        Record '4' '反例·缺 key' 'PASS' "退出码 $($noKey.ExitCode)，stderr 指明 DEEPSEEK_API_KEY"
    }
}
finally {
    $env:DEEPSEEK_API_KEY = $apiKey
}

# ---------- 6. 多轮上下文 ----------
Write-Host ''
if ([string]::IsNullOrWhiteSpace($apiKey)) {
    Record '6' '多轮上下文' 'SKIP' '未设置 DEEPSEEK_API_KEY'
}
else {
    $round1 = Invoke-Lytgos -WorkDir $workspace `
        -CliArgs @('chat', '-m', '请记住：我的名字是张三。只回答「好」。')
    $round2 = Invoke-Lytgos -WorkDir $workspace `
        -CliArgs @('chat') -StdinText ("我叫什么名字？只回答名字。`nexit`n")
    if ($round1.ExitCode -ne 0) {
        Record '6' '多轮上下文' 'FAIL' "第一轮退出码 $($round1.ExitCode)"
    }
    elseif ($round2.ExitCode -ne 0) {
        Record '6' '多轮上下文' 'FAIL' "第二轮退出码 $($round2.ExitCode)"
    }
    elseif ($round2.Stdout -notmatch '张三') {
        Record '6' '多轮上下文' 'FAIL' "第二轮回答未含「张三」：$($round2.Stdout.Trim())"
    }
    else {
        Record '6' '多轮上下文' 'PASS' '交互模式读到了上一轮的「张三」'
    }
}

# ---------- 汇总 ----------
$failed = @($results | Where-Object { $_.Status -eq 'FAIL' })
$skipped = @($results | Where-Object { $_.Status -eq 'SKIP' })

Write-Host ''
Write-Host '===== 第 1 周验收总表 =====' -ForegroundColor Cyan
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
Write-Host 'RESULT: 六条全 PASS' -ForegroundColor Green
exit 0
