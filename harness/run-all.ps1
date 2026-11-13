<#
.SYNOPSIS
    Harness 总闸门：按 checks/ 文件名顺序执行，任一 FAIL 或有闸门未就绪即返回非 0。

.DESCRIPTION
    这套脚本存在的唯一目的是「拥有否决权」。
    AI 可以声称自己做完了，但只有这里返回 0 才算真的做完。
    人类审阅与 CI 都只认退出码，不看日志措辞。

    退出码约定（三个状态，不是两个）：
      0  PASS        闸门真实通过
      2  NOT_READY   前置条件不存在（如尚无 pom.xml），既不算通过也不算失败
      1  FAIL        闸门跑过且不通过；或有闸门未就绪，总闸门拒绝给出通过结论

    为什么未就绪也让总闸门返回 1：
    没有代码时「全部通过」是一个假绿灯——它会让后来的人以为验收过了。

.EXAMPLE
    powershell -File harness\run-all.ps1
#>
[CmdletBinding()]
param(
    # 默认遇错即停；排查问题时可用 -ContinueOnError 跑完全部检查
    [switch]$ContinueOnError
)

$ErrorActionPreference = 'Stop'

# 子进程宿主按当前 PowerShell 版本选择：Windows PowerShell 5.1 只有 powershell.exe，
# PowerShell 7（含 Linux runner 上的 pwsh）用 pwsh。-ExecutionPolicy 只有 Windows 支持，
# 非 Windows 传了会直接报错，所以按平台拼参数。
$hostArgs = @('-NoProfile', '-File')
if ($PSVersionTable.PSEdition -eq 'Desktop') {
    $hostExe = 'powershell.exe'
    $hostArgs = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File')
}
else {
    $hostExe = 'pwsh'
    if ($IsWindows) {
        $hostArgs = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File')
    }
}

$harnessDir = $PSScriptRoot
$projectRoot = Split-Path -Parent $harnessDir
Set-Location $projectRoot

Write-Host "Harness root : $projectRoot" -ForegroundColor DarkGray
Write-Host "Started at   : $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')" -ForegroundColor DarkGray

$checks = Get-ChildItem (Join-Path $harnessDir 'checks') -Filter '*.ps1' -File | Sort-Object Name
if (-not $checks) {
    Write-Host 'No checks found under harness\checks' -ForegroundColor Red
    exit 1
}

$results = @()
foreach ($check in $checks) {
    Write-Host ''
    Write-Host "==> $($check.Name)" -ForegroundColor Cyan

    $sw = [Diagnostics.Stopwatch]::StartNew()
    # 用独立进程执行，确保每个检查的退出码互不污染
    $invokeArgs = $hostArgs + $check.FullName
    & $hostExe @invokeArgs
    $code = $LASTEXITCODE
    $sw.Stop()

    # 拿不到子进程退出码 = 无法证明通过；默认判 PASS 会把「没跑起来」变成绿灯。
    if ($null -eq $code) { $code = 1 }

    $results += [pscustomobject]@{
        Check    = $check.BaseName
        ExitCode = $code
        Seconds  = [math]::Round($sw.Elapsed.TotalSeconds, 1)
    }

    # 只有 FAIL 才中断；NOT_READY 是「无判定对象」，继续跑后面的闸门才有意义
    if ($code -ne 0 -and $code -ne 2 -and -not $ContinueOnError) { break }
}

Write-Host ''
Write-Host '===== Harness Summary =====' -ForegroundColor Cyan
foreach ($r in $results) {
    switch ($r.ExitCode) {
        0 { Write-Host ("  [PASS]      {0} ({1}s)" -f $r.Check, $r.Seconds) -ForegroundColor Green }
        2 { Write-Host ("  [NOT_READY] {0} ({1}s)" -f $r.Check, $r.Seconds) -ForegroundColor DarkYellow }
        default { Write-Host ("  [FAIL]      {0} (exit {1}, {2}s)" -f $r.Check, $r.ExitCode, $r.Seconds) -ForegroundColor Red }
    }
}

$failed = @($results | Where-Object { $_.ExitCode -ne 0 -and $_.ExitCode -ne 2 })
$notReady = @($results | Where-Object { $_.ExitCode -eq 2 })
$notRun = @($checks | Where-Object { $c = $_.BaseName; -not ($results | Where-Object { $_.Check -eq $c }) })

if ($failed.Count -gt 0) {
    Write-Host ''
    Write-Host ("RESULT: BLOCKED - $($failed.Count) check(s) failed") -ForegroundColor Red
    if ($notRun.Count -gt 0) {
        Write-Host ("        ($($notRun.Count) check(s) not run because of early stop)") -ForegroundColor DarkYellow
    }
    exit 1
}

if ($notReady.Count -gt 0) {
    Write-Host ''
    Write-Host ("RESULT: NOT READY - $($notReady.Count) check(s) 前置条件不存在，拒绝给出通过结论") -ForegroundColor Yellow
    Write-Host ("        未就绪：$(($notReady | ForEach-Object { $_.Check }) -join ', ')") -ForegroundColor DarkYellow
    if ($notRun.Count -gt 0) {
        Write-Host ("        ($($notRun.Count) check(s) not run)") -ForegroundColor DarkYellow
    }
    exit 1
}

Write-Host ''
Write-Host 'RESULT: ALL CHECKS PASSED' -ForegroundColor Green
exit 0
