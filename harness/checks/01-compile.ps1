<#
    闸门 01-compile：能不能编译

    因：编译不过的项目没有讨论价值，越早暴露成本越低。
        「应该能编过」不是证据，退出码才是。

    三态：
      0        编译通过
      2        没有 pom.xml —— 技术选型未定稿，无判定对象
      1        编译失败，或 pom 存在但工具链缺失（缺 mvn 是环境坏了，不是未就绪）
#>
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Set-Location $projectRoot

if (-not (Test-Path -LiteralPath 'pom.xml')) {
    Write-Host 'NOT_READY: 尚无 pom.xml —— 模块与构建基线未定稿前，本闸门无判定对象' -ForegroundColor DarkYellow
    exit 2
}

if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
    Write-Host 'FAIL: 找不到 mvn 命令（pom.xml 已存在，说明工具链缺失，不是未就绪）' -ForegroundColor Red
    exit 1
}

& mvn -B -q -DskipTests compile
if ($LASTEXITCODE -ne 0) {
    Write-Host "编译失败（mvn exit $LASTEXITCODE）" -ForegroundColor Red
    exit 1
}

Write-Host '编译通过' -ForegroundColor Green
exit 0
