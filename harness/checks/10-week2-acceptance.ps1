<#
    闸门 10-week2-acceptance：第 2 周验收总表

    因：单测证明的是「零件对」，证明不了「接线对」。第 2 周新增的三条能力
        （长期记忆跨进程注入、文件写入、命令执行）只有把用户真实动作跑一遍
        才算成立：init → 让模型记住偏好 → 换进程再问 → 让模型写文件 →
        让模型执行命令。它只认文件内容、会话历史与退出码，改测试绕不过去。

    六条（1-5 对应 design.md D8；第 6 条为代码审查后补的验收证据）：
      1. 工作区初始化：init 建出空的 memory/MEMORY.md，并把 allowed-commands 配成含 java
      2. 记忆写入：显式指令让模型调用 save_memory，MEMORY.md 含本轮随机校验码
      3. 跨进程注入：移走 sessions 后新进程答出同一校验码，且不依赖工具调用
      4. 文件写入：模型调用 write_file，output/gate.txt 内容为 hello
      5. 命令执行：模型调用 shell，会话历史里出现 exit=0 与 version 输出
      6. 记忆缺失不阻断：移走 memory/MEMORY.md 后对话仍退出码 0，且不重建文件

    第 2-6 条需要真实模型。没有 DEEPSEEK_API_KEY 时记 SKIP 并返回 2，
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

$workRoot = Join-Path $env:TEMP ("lytgos-week2-" + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $workRoot -Force | Out-Null
Write-Host "工作目录: $workRoot" -ForegroundColor DarkGray

$utf8 = New-Object System.Text.UTF8Encoding($false)
$results = New-Object System.Collections.ArrayList

function Record {
    param([string]$Id, [string]$Name, [string]$Status, [string]$Detail)
    [void]$results.Add([pscustomobject]@{ Id = $Id; Name = $Name; Status = $Status; Detail = $Detail })
    $color = switch ($Status) { 'PASS' { 'Green' } 'FAIL' { 'Red' } default { 'DarkYellow' } }
    Write-Host ("  [{0}] {1}. {2} — {3}" -f $Status, $Id, $Name, $Detail) -ForegroundColor $color
}

# 失败详情要能一眼看出是「实现坏了」还是「模型没照做」：压成一行并限长。
function Brief {
    param([string]$Text)
    if ([string]::IsNullOrWhiteSpace($Text)) { return '(空)' }
    $flat = ($Text -replace '\s+', ' ').Trim()
    if ($flat.Length -le 240) { return $flat }
    return $flat.Substring(0, 240) + '...'
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
$lytgosDir = Join-Path $workspace '.lytgos'
$configFile = Join-Path $lytgosDir 'config.yaml'
$memoryFile = Join-Path $lytgosDir 'memory/MEMORY.md'
$gateFile = Join-Path $lytgosDir 'output/gate.txt'
$sessionsDir = Join-Path $lytgosDir 'sessions'

# ---------- 1. 工作区初始化 ----------
Write-Host ''
$init = Invoke-Lytgos -WorkDir $workspace -CliArgs @('init')
$step1Ok = $false
if ($init.ExitCode -ne 0) {
    $step1Detail = "init 退出码 $($init.ExitCode)：$(Brief $init.Stderr)"
}
elseif (-not (Test-Path -LiteralPath $memoryFile)) {
    $step1Detail = '没有建出 memory/MEMORY.md'
}
elseif ((Get-Item -LiteralPath $memoryFile).Length -ne 0) {
    # 占位文案会被 PromptBuilder 整段注入，在模型眼里就是「记忆」
    $step1Detail = "memory/MEMORY.md 应为空文件，实际 $((Get-Item -LiteralPath $memoryFile).Length) 字节"
}
elseif (-not (Test-Path -LiteralPath $configFile)) {
    $step1Detail = '没有建出 config.yaml'
}
else {
    $configText = [System.IO.File]::ReadAllText($configFile, [System.Text.Encoding]::UTF8)
    if ($configText -notmatch '(?m)^\s*allowed-commands:\s*\[\]\s*$') {
        $step1Detail = 'config.yaml 里没有 allowed-commands: [] 模板行'
    }
    else {
        # 保留原缩进，只把空列表换成含 java 的列表
        $replacement = '${1}allowed-commands:' + "`n" + '${1}  - java'
        $updated = [regex]::Replace($configText, '(?m)^(\s*)allowed-commands:\s*\[\]\s*$', $replacement)
        [System.IO.File]::WriteAllText($configFile, $updated, $utf8)
        $step1Ok = $true
        $step1Detail = 'memory/MEMORY.md 已建出且为空；allowed-commands 已改为含 java'
    }
}
$step1Status = if ($step1Ok) { 'PASS' } else { 'FAIL' }
Record '1' '工作区初始化' $step1Status $step1Detail

$apiKey = $env:DEEPSEEK_API_KEY
if ([string]::IsNullOrWhiteSpace($apiKey)) {
    Record '2' '记忆写入' 'SKIP' '未设置 DEEPSEEK_API_KEY'
    Record '3' '跨进程记忆注入' 'SKIP' '未设置 DEEPSEEK_API_KEY'
    Record '4' '文件写入' 'SKIP' '未设置 DEEPSEEK_API_KEY'
    Record '5' '命令执行' 'SKIP' '未设置 DEEPSEEK_API_KEY'
    Record '6' '记忆缺失不阻断' 'SKIP' '未设置 DEEPSEEK_API_KEY'
}
else {
    # ---------- 2. 记忆写入 ----------
    Write-Host ''
    # 每轮随机生成校验码：模型不可能猜中，只能是真的读到了注入的记忆
    $nonce = 'ZX' + (Get-Random -Minimum 10000 -Maximum 99999)
    $save = Invoke-Lytgos -WorkDir $workspace -CliArgs @('chat', '-m', "调用 save_memory 工具记住：我的部署校验码是 $nonce。必须真实调用该工具，不要只回复文字。")
    $memoryText = if (Test-Path -LiteralPath $memoryFile) {
        [System.IO.File]::ReadAllText($memoryFile, [System.Text.Encoding]::UTF8)
    } else { '' }
    if ($save.ExitCode -ne 0) {
        Record '2' '记忆写入' 'FAIL' "chat 退出码 $($save.ExitCode)：$(Brief $save.Stderr)"
    }
    elseif ($save.Stderr -notmatch 'tool_call:\s*save_memory') {
        Record '2' '记忆写入' 'FAIL' "模型没有调用 save_memory；回复：$(Brief $save.Stdout)"
    }
    elseif ($memoryText -notmatch $nonce) {
        Record '2' '记忆写入' 'FAIL' "MEMORY.md 未含校验码 $nonce，实际：$(Brief $memoryText)"
    }
    else {
        Record '2' '记忆写入' 'PASS' "save_memory 已追加，MEMORY.md 含校验码 $nonce"
    }

    # ---------- 3. 跨进程记忆注入 ----------
    Write-Host ''
    # 移走会话历史，才能证明回答来自「启动时注入的 MEMORY.md」而不是上一轮对话
    $sessionsBefore = Join-Path $lytgosDir 'sessions-before-recall'
    if (Test-Path -LiteralPath $sessionsDir) {
        Move-Item -LiteralPath $sessionsDir -Destination $sessionsBefore -Force
    }
    $recall = Invoke-Lytgos -WorkDir $workspace -CliArgs @('chat', '-m', '我的部署校验码是什么？你的系统提示词里已经包含长期记忆，不要调用任何工具，直接回答校验码。')
    if ($recall.ExitCode -ne 0) {
        Record '3' '跨进程记忆注入' 'FAIL' "chat 退出码 $($recall.ExitCode)：$(Brief $recall.Stderr)"
    }
    elseif ($recall.Stdout -notmatch $nonce) {
        Record '3' '跨进程记忆注入' 'FAIL' "回答未含校验码 $nonce：$(Brief $recall.Stdout)"
    }
    elseif ($recall.Stderr -match 'tool_call:') {
        # 靠工具调用答对不算「注入生效」，spec 要的是无需再次调用
        Record '3' '跨进程记忆注入' 'FAIL' "回答依赖了工具调用，未证明启动注入：$(Brief $recall.Stderr)"
    }
    else {
        Record '3' '跨进程记忆注入' 'PASS' "移走 sessions 后新进程仍答出校验码 $nonce，且未调用工具"
    }

    # ---------- 4. 文件写入 ----------
    Write-Host ''
    $write = Invoke-Lytgos -WorkDir $workspace -CliArgs @('chat', '-m', '调用 write_file 工具，path=output/gate.txt，content=hello。必须真实调用该工具，不要只回复文字。')
    if ($write.ExitCode -ne 0) {
        Record '4' '文件写入' 'FAIL' "chat 退出码 $($write.ExitCode)：$(Brief $write.Stderr)"
    }
    elseif ($write.Stderr -notmatch 'tool_call:\s*write_file') {
        Record '4' '文件写入' 'FAIL' "模型没有调用 write_file；回复：$(Brief $write.Stdout)"
    }
    elseif (-not (Test-Path -LiteralPath $gateFile)) {
        Record '4' '文件写入' 'FAIL' 'output/gate.txt 未被创建'
    }
    else {
        $written = [System.IO.File]::ReadAllText($gateFile, [System.Text.Encoding]::UTF8)
        if ($written.Trim() -ne 'hello') {
            Record '4' '文件写入' 'FAIL' "output/gate.txt 内容应为 hello，实际：$(Brief $written)"
        }
        else {
            Record '4' '文件写入' 'PASS' 'write_file 已创建 output/gate.txt，回读内容为 hello'
        }
    }

    # ---------- 5. 命令执行 ----------
    Write-Host ''
    $shell = Invoke-Lytgos -WorkDir $workspace -CliArgs @('chat', '-m', '调用 shell 工具执行 java -version。必须真实调用该工具，不要只回复文字。')
    # 工具结果不回显到进程输出，只能从落盘的会话历史取证
    $history = ''
    if (Test-Path -LiteralPath $sessionsDir) {
        $history = (Get-ChildItem -LiteralPath $sessionsDir -Filter '*.json' -File |
            ForEach-Object { [System.IO.File]::ReadAllText($_.FullName, [System.Text.Encoding]::UTF8) }) -join "`n"
    }
    if ($shell.ExitCode -ne 0) {
        Record '5' '命令执行' 'FAIL' "chat 退出码 $($shell.ExitCode)：$(Brief $shell.Stderr)"
    }
    elseif ($shell.Stderr -notmatch 'tool_call:\s*shell') {
        Record '5' '命令执行' 'FAIL' "模型没有调用 shell；回复：$(Brief $shell.Stdout)"
    }
    elseif ($history -notmatch 'exit=0' -or $history -notmatch '(?i)(openjdk|java)\s+version\s+[\\"]*\d') {
        # 会话历史是 JSON，引号会被转义成 \"，正则必须允许反斜杠
        Record '5' '命令执行' 'FAIL' "会话历史里没有 exit=0 与 java 版本号，实际：$(Brief $history)"
    }
    else {
        Record '5' '命令执行' 'PASS' 'shell 执行 java -version 返回 exit=0 与 java 版本号'
    }

    # ---------- 6. 记忆缺失不阻断 ----------
    Write-Host ''
    # spec：删除 MEMORY.md 后对话必须正常完成、退出码 0，且本流程不得顺手重建它
    $memoryBackup = Join-Path $lytgosDir 'MEMORY.md.removed'
    $canProbe = $true
    if (-not (Test-Path -LiteralPath $memoryFile)) {
        Record '6' '记忆缺失不阻断' 'FAIL' '前置失败：memory/MEMORY.md 不存在，第 1 条应已建出'
        $canProbe = $false
    }
    else {
        Move-Item -LiteralPath $memoryFile -Destination $memoryBackup -Force
    }
    if ($canProbe) {
        $noMemory = Invoke-Lytgos -WorkDir $workspace -CliArgs @('chat', '-m', '你好')
        if ($noMemory.ExitCode -ne 0) {
            Record '6' '记忆缺失不阻断' 'FAIL' "删除 MEMORY.md 后 chat 退出码 $($noMemory.ExitCode)：$(Brief $noMemory.Stderr)"
        }
        elseif ($noMemory.Stderr -match '(?:ERROR|Exception)') {
            # 日志器把真正的失败写在 stderr；容忍普通 INFO 行，但 ERROR/Exception 一律不放行
            Record '6' '记忆缺失不阻断' 'FAIL' "删除 MEMORY.md 后 stderr 出现错误：$(Brief $noMemory.Stderr)"
        }
        elseif (Test-Path -LiteralPath $memoryFile) {
            Record '6' '记忆缺失不阻断' 'FAIL' '对话不应顺手创建 memory/MEMORY.md'
        }
        else {
            Record '6' '记忆缺失不阻断' 'PASS' '删除 MEMORY.md 后对话正常完成、退出码 0，且未重建文件'
        }
        if (Test-Path -LiteralPath $memoryBackup) {
            Move-Item -LiteralPath $memoryBackup -Destination $memoryFile -Force
        }
    }
}

# ---------- 汇总 ----------
$failed = @($results | Where-Object { $_.Status -eq 'FAIL' })
$skipped = @($results | Where-Object { $_.Status -eq 'SKIP' })

Write-Host ''
Write-Host '===== 第 2 周验收总表 =====' -ForegroundColor Cyan
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
    Write-Host "RESULT: NOT READY - $($skipped.Count) 条未执行（需要 DEEPSEEK_API_KEY），工作区留在 $workRoot" -ForegroundColor Yellow
    exit 2
}
Remove-Item -LiteralPath $workRoot -Recurse -Force
Write-Host 'RESULT: 六条全 PASS' -ForegroundColor Green
exit 0
