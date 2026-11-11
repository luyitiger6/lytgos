<#
    闸门 03-static-guard：静态红线

    因：留占位标记、用跳过语法、注释断断言，都能让测试变绿而护栏强度归零。
        这些做法的共同点是「看起来通过了，实际什么也没验」。

    拦截：
      1. TODO / FIXME / XXX 占位就宣布完成
      2. System.out.print* 代替日志（本地调试留下的噪音，且不可配置级别）
      3. @Disabled 让失败的测试消失
      4. pom.xml 里写死 skipTests / maven.test.skip

    三态：
      0        扫描通过
      2        尚无 Java 源码（模块未落地），无扫描对象
      1        检出违规
#>
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Set-Location $projectRoot

$violations = New-Object System.Collections.ArrayList

# 模块目录约定：<module>/src/**/*.java —— 不猜别的布局
$skipDirs = @('.git', 'harness', 'docs', 'openspec', 'target', 'node_modules', '.qoder')
$sources = @()
foreach ($dir in (Get-ChildItem $projectRoot -Directory -Force | Where-Object { $_.Name -notin $skipDirs })) {
    $srcDir = Join-Path $dir.FullName 'src'
    if (Test-Path -LiteralPath $srcDir) {
        $sources += @(Get-ChildItem $srcDir -Recurse -File -Filter '*.java' -ErrorAction SilentlyContinue)
    }
}

foreach ($file in $sources) {
    $rel = $file.FullName.Substring($projectRoot.Length + 1)
    $lines = [IO.File]::ReadAllLines($file.FullName)
    for ($i = 0; $i -lt $lines.Length; $i++) {
        $no = $i + 1
        $text = $lines[$i]
        if ($text -cmatch 'TODO|FIXME|XXX') {
            [void]$violations.Add("${rel}:${no}  [占位标记] $($text.Trim())")
        }
        if ($text -cmatch 'System\.out\.print') {
            [void]$violations.Add("${rel}:${no}  [禁用 System.out] $($text.Trim())")
        }
        if ($text -cmatch '@Disabled') {
            [void]$violations.Add("${rel}:${no}  [禁止跳过测试] $($text.Trim())")
        }
    }
}

$pomPath = Join-Path $projectRoot 'pom.xml'
if (Test-Path -LiteralPath $pomPath) {
    $pom = Get-Content -LiteralPath $pomPath -Raw -Encoding UTF8
    if ($pom -match '<skipTests>\s*true\s*</skipTests>' -or $pom -match 'maven\.test\.skip') {
        [void]$violations.Add('pom.xml  [禁止跳过测试] 检出 skipTests 配置')
    }
}

if ($violations.Count -gt 0) {
    Write-Host "静态红线 FAILED - $($violations.Count) 处违规：" -ForegroundColor Red
    foreach ($v in $violations) { Write-Host "  - $v" -ForegroundColor Red }
    exit 1
}

if ($sources.Count -eq 0) {
    Write-Host 'NOT_READY: 尚无 Java 源码（模块未落地），静态红线无扫描对象' -ForegroundColor DarkYellow
    exit 2
}

Write-Host "静态红线通过（扫描 $($sources.Count) 个 Java 文件）" -ForegroundColor Green
exit 0
