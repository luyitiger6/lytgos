<#
.SYNOPSIS
安装或卸载本仓库自带的 git pre-commit hook。

.DESCRIPTION
把 scripts/hooks/pre-commit 复制到 .git/hooks/pre-commit。
hook 只执行 mvn -q spotless:check，不编译、不跑测试，目标 10 秒内完成。

.PARAMETER Uninstall
移除已安装的 hook。

.EXAMPLE
powershell -File scripts/install-hooks.ps1
powershell -File scripts/install-hooks.ps1 -Uninstall
#>
[CmdletBinding()]
param(
  [switch]$Uninstall
)

$ErrorActionPreference = 'Stop'

$repoRoot = Split-Path -Parent $PSScriptRoot
$hooksDir = Join-Path $repoRoot '.git/hooks'
$target = Join-Path $hooksDir 'pre-commit'

if (-not (Test-Path $hooksDir)) {
  throw "找不到 $hooksDir。请在 git 仓库根目录执行本脚本。"
}

if ($Uninstall) {
  if (Test-Path $target) {
    Remove-Item $target -Force
    Write-Host "已卸载 pre-commit hook：$target"
  } else {
    Write-Host "没有已安装的 pre-commit hook，无需卸载。"
  }
  return
}

$source = Join-Path $PSScriptRoot 'hooks/pre-commit'
if (-not (Test-Path $source)) {
  throw "找不到 hook 脚本源文件：$source"
}

Copy-Item $source $target -Force

Write-Host "已安装 pre-commit hook：$target"
Write-Host "行为：提交前执行 mvn -q spotless:check，未通过则拒绝提交。"
Write-Host "跳过单次校验：git commit --no-verify"
Write-Host "卸载：powershell -File scripts/install-hooks.ps1 -Uninstall"
