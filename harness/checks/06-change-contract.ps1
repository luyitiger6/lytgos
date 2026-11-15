<#
    闸门 06-change-contract：每个 change 必须说明「对应什么工作」

    因：openspec/changes/ 下若出现只有目录、没有 proposal 的空壳，
        汇报时只能给出「0/0，无 tasks」这类状态列——
        状态不是工作内容，读者看完仍不知道该 change 要交付什么。
        这条闸门把「必须能说清工作内容」变成可判定的条件，
        而不是依赖汇报者自觉。

    判据：
      1. openspec/changes/ 下每个目录（不含 archive）必须有 proposal.md
      2. proposal.md 必须同时含 "## Why" 与 "## What Changes" 两个小节
      3. proposal.md 正文不少于 200 字符（防止一句话占位）

    「没有进行中的 change」不算未就绪：真正缺判定对象的情形是
    openspec/changes/ 目录本身不存在（项目尚未初始化 OpenSpec）。
    空闲期返回 2 会让 run-all 在每次归档后永久变红，红线失去可信度。

    三态：
      0  全部 change 都有合格的 proposal；或 changes 目录存在但没有进行中的 change
      2  openspec/changes/ 目录不存在 —— 项目尚未初始化 OpenSpec，无判定对象
      1  存在空壳 change 或 proposal 不合格
#>
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Set-Location $projectRoot

$changesDir = 'openspec/changes'
if (-not (Test-Path -LiteralPath $changesDir)) {
    Write-Host "尚无 $changesDir 目录，无判定对象" -ForegroundColor DarkYellow
    exit 2
}

$dirs = @(Get-ChildItem -LiteralPath $changesDir -Directory |
    Where-Object { $_.Name -ne 'archive' })

if ($dirs.Count -eq 0) {
    # 空闲期是合法状态（例如刚归档完、下一个 change 尚未立项），不是「未就绪」：
    # 没有可判对象不等于判定不了，本维度直接通过。
    Write-Host '无进行中 change，本维度无需判定' -ForegroundColor Green
    exit 0
}

$problems = @()
foreach ($dir in $dirs) {
    $proposal = Join-Path $dir.FullName 'proposal.md'
    if (-not (Test-Path -LiteralPath $proposal)) {
        $problems += "$($dir.Name)：缺少 proposal.md，空壳 change 无法说明对应什么工作"
        continue
    }

    $text = Get-Content -LiteralPath $proposal -Raw -Encoding UTF8
    if ($text -notmatch '(?m)^##\s+Why\s*$') {
        $problems += "$($dir.Name)：proposal.md 缺少 '## Why' 小节"
    }
    if ($text -notmatch '(?m)^##\s+What Changes\s*$') {
        $problems += "$($dir.Name)：proposal.md 缺少 '## What Changes' 小节"
    }

    $length = $text.Trim().Length
    if ($length -lt 200) {
        $problems += "$($dir.Name)：proposal.md 正文仅 $length 字符，疑似占位"
    }
}

if ($problems.Count -gt 0) {
    Write-Host "change 契约不合格 - $($problems.Count) 项：" -ForegroundColor Red
    foreach ($p in $problems) { Write-Host "  - $p" -ForegroundColor Red }
    exit 1
}

Write-Host ("change 契约完整：{0} 个进行中的 change 均有 proposal 说明工作内容" -f $dirs.Count) -ForegroundColor Green
exit 0
