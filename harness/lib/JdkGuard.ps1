<#
    JDK 前置校验（由 02-test 与 run-all 共享）

    因：本机 JAVA_HOME 可能指向旧 JDK。mvn 在增量构建下会跳过编译，却在 surefire
        的 fork JVM 里加载高版本字节码，报「class file version 65.0 ... up to 61.0」——
        报错位置与真实原因无关，排查成本全落在人身上。

    判据：mvn -v 报出的 Java 主版本 >= 根 pom 的 <java.version>。
    无 pom、无 <java.version> 或缺 mvn 时不判定：那是各闸门自己的三态范围。

    用法：dot-source 本文件后调用 Assert-JdkVersion <项目根>。
    版本不足时直接 exit 1，把「装错 JDK」与「代码坏了」分开。
#>

function Assert-JdkVersion {
    param([Parameter(Mandatory = $true)][string]$ProjectRoot)

    $pom = Join-Path $ProjectRoot 'pom.xml'
    if (-not (Test-Path -LiteralPath $pom)) { return }

    $pomText = [System.IO.File]::ReadAllText($pom)
    $declared = [regex]::Match($pomText, '<java\.version>\s*(\d+)\s*</java\.version>')
    if (-not $declared.Success) { return }
    $required = [int]$declared.Groups[1].Value

    $mvn = Get-Command mvn -ErrorAction SilentlyContinue
    if (-not $mvn) { return }

    $output = (& $mvn.Source -v 2>&1 | Out-String)
    $actual = [regex]::Match($output, 'Java version:\s*(\d+)')
    if (-not $actual.Success) {
        Write-Host 'FAIL: 无法从 mvn -v 解析 Java 版本，无法确认构建 JDK 是否满足要求' -ForegroundColor Red
        exit 1
    }

    $running = [int]$actual.Groups[1].Value
    if ($running -lt $required) {
        Write-Host ("FAIL: mvn 运行在 Java {0}，项目要求 Java {1}" -f $running, $required) -ForegroundColor Red
        Write-Host ("      当前 JAVA_HOME = '{0}'" -f $env:JAVA_HOME) -ForegroundColor DarkYellow
        Write-Host ("      mvn -v：" + (($output -split "`n" | Where-Object { $_ -match 'Java version|runtime' }) -join ' ')) -ForegroundColor DarkYellow
        Write-Host ("      修复：在同一条命令里设置 `$env:JAVA_HOME 指向 JDK {0} 后重跑" -f $required) -ForegroundColor DarkYellow
        exit 1
    }

    Write-Host ("JDK 前置校验通过：mvn 运行在 Java {0}（要求 {1}）" -f $running, $required) -ForegroundColor DarkGray
}
