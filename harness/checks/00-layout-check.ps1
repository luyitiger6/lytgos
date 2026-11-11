<#
    闸门 00-layout-check：骨架完整性

    因：骨架缺一件，后续闸门会以「找不到文件」的方式失败，
        报错位置与真实原因无关，排查成本全落在人身上。

    判据：
      1. 必需骨架路径（文件与目录）存在
      2. AGENTS.md 的机器可读闸门清单与 harness/checks/*.ps1 的实际文件集合一致
         （新增闸门却忘了更新清单，是本项目最容易发生的文档腐化）
#>
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Set-Location $projectRoot

$required = @(
    'README.md'
    'AGENTS.md'
    '.gitignore'
    'openspec/config.yaml'
    'openspec/specs'
    'openspec/changes/archive'
    '.agents/skills/systematic-debugging/SKILL.md'
    '.agents/skills/verification-before-completion/SKILL.md'
)

$missing = @($required | Where-Object { -not (Test-Path -LiteralPath $_) })
if ($missing.Count -gt 0) {
    Write-Host "骨架缺失 - $($missing.Count) 项：" -ForegroundColor Red
    foreach ($m in $missing) { Write-Host "  - $m" -ForegroundColor Red }
    exit 1
}

$markerLine = (Select-String -LiteralPath 'AGENTS.md' -Pattern 'harness-checks:' | Select-Object -First 1).Line
if (-not $markerLine) {
    Write-Host 'AGENTS.md 缺少 harness-checks 标记行' -ForegroundColor Red
    exit 1
}

$declaredText = $markerLine -replace '^.*harness-checks:\s*', ''
$declaredText = $declaredText -replace '\s*-->.*$', ''
$declared = @($declaredText.Trim() -split '\s+' | Where-Object { $_ })

$actual = @(Get-ChildItem (Join-Path $PSScriptRoot '*.ps1') -File |
    Sort-Object Name |
    ForEach-Object { $_.BaseName })

$diff = @(Compare-Object -ReferenceObject $declared -DifferenceObject $actual)
if ($diff.Count -gt 0) {
    Write-Host 'AGENTS.md 闸门清单与 harness/checks 实际文件不一致：' -ForegroundColor Red
    foreach ($d in $diff) {
        $side = if ($d.SideIndicator -eq '<=') { '仅声明、未实现' } else { '已实现、未声明' }
        Write-Host ("  - [{0}] {1}" -f $side, $d.InputObject) -ForegroundColor Red
    }
    exit 1
}

Write-Host ("骨架完整：{0} 项；闸门清单一致：{1} 道" -f $required.Count, $actual.Count) -ForegroundColor Green
exit 0
