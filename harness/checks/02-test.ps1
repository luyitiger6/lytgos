<#
    闸门 02-test：行为对不对

    因：编译通过只证明语法正确，证明不了行为正确。
        这是唯一能回答「你说的和代码做的是不是一回事」的闸门。

    三态：
      0        全部测试通过
      2        没有 pom.xml —— 无判定对象
      1        测试失败，或缺 mvn

    注意：本闸门不接受 -DskipTests 之外的任何跳过参数；
    给 02 加跳过开关，等于把整个护栏体系作废。
#>
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Set-Location $projectRoot

if (-not (Test-Path -LiteralPath 'pom.xml')) {
    Write-Host 'NOT_READY: 尚无 pom.xml —— 无判定对象' -ForegroundColor DarkYellow
    exit 2
}

if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
    Write-Host 'FAIL: 找不到 mvn 命令（pom.xml 已存在，说明工具链缺失，不是未就绪）' -ForegroundColor Red
    exit 1
}

& mvn -B test
if ($LASTEXITCODE -ne 0) {
    Write-Host "测试失败（mvn exit $LASTEXITCODE）" -ForegroundColor Red
    exit 1
}

Write-Host '测试通过' -ForegroundColor Green
exit 0
