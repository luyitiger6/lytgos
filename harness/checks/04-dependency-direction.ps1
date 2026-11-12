<#
    闸门 04-dependency-direction：依赖方向 + 禁止自动工具执行

    因：AGENTS.md 4.1 与 4.2 两条约束违反了都不会报错——
        编译通过、测试通过、界面正常，只是配额、白名单、沙箱、审计全部静默失效。
        这是本项目唯一一条「违反后没有任何症状」的约束，必须由机器守。

    判据（源码 + pom 两层）：
      1. org.springframework.ai.* 的 import 只允许出现在 lytgos-provider
      2. 全仓禁 import org.springframework.ai.chat.client.*（ChatClient 自动驱动工具循环）
      3. 全仓禁 import org.springframework.ai.model.tool.ToolCallingManager
      4. 全仓禁 @ConditionalOnBean / ObjectProvider<ChatModel / getBeansOfType(
         —— 类型扫描会让「配一个 provider」退化成「多放一个 jar 就多一个 provider」
      5. lytgos-core 的 pom 不得依赖 spring-ai 或 spring-boot-starter
      6. lytgos-core 不得 import 本项目其他模块（core 是最底层）

    注释行（* / // / /* 开头）不参与匹配：文档里点名禁止某样东西是合规且必要的，
    要拦的是真的 import 与真的注解。

    三态：
      0        规则全部满足
      2        尚无模块源码，无判定对象
      1        检出违规
#>
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
Set-Location $projectRoot

$modules = @(Get-ChildItem $projectRoot -Directory -Force | Where-Object {
        $_.Name -like 'lytgos-*' -and (Test-Path -LiteralPath (Join-Path $_.FullName 'src'))
    })

if ($modules.Count -eq 0) {
    Write-Host 'NOT_READY: 尚无 lytgos-* 模块源码，依赖方向与自动执行禁令无从判定' -ForegroundColor DarkYellow
    exit 2
}

$violations = New-Object System.Collections.ArrayList

# ---------- 规则 2/3/4：全仓源码 ----------
$forbidden = @(
    @{
        Pattern = 'import\s+org\.springframework\.ai\.chat\.client\.'
        Label   = '禁止 ChatClient（会自动驱动工具循环，工具执行必须只由 ToolExecutor 发起）'
    }
    @{
        Pattern = 'import\s+org\.springframework\.ai\.model\.tool\.ToolCallingManager'
        Label   = '禁止 ToolCallingManager（自动工具执行的入口）'
    }
    @{
        Pattern = '@ConditionalOnBean'
        Label   = '禁止 @ConditionalOnBean（provider 必须由配置显式声明，不接受类型扫描）'
    }
    @{
        Pattern = 'ObjectProvider\s*<\s*ChatModel'
        Label   = '禁止 ObjectProvider<ChatModel>（provider 必须由配置显式声明）'
    }
    @{
        Pattern = 'getBeansOfType\s*\('
        Label   = '禁止 getBeansOfType（provider 必须由配置显式声明）'
    }
)

$javaFiles = @($modules | ForEach-Object {
        Get-ChildItem (Join-Path $_.FullName 'src') -Recurse -File -Filter '*.java' -ErrorAction SilentlyContinue
    })

foreach ($file in $javaFiles) {
    $rel = $file.FullName.Substring($projectRoot.Length + 1)
    $module = ($rel -split '[\\/]')[0]
    $lines = [IO.File]::ReadAllLines($file.FullName)

    for ($i = 0; $i -lt $lines.Length; $i++) {
        $text = $lines[$i]
        $trimmed = $text.Trim()
        if ($trimmed.StartsWith('*') -or $trimmed.StartsWith('//') -or $trimmed.StartsWith('/*')) { continue }

        foreach ($rule in $forbidden) {
            if ($text -match $rule.Pattern) {
                [void]$violations.Add("${rel}:$($i + 1)  [$($rule.Label)] $trimmed")
            }
        }

        # 规则 1：Spring AI 只允许出现在 provider 模块
        if ($module -ne 'lytgos-provider' -and $text -match 'import\s+org\.springframework\.ai\.') {
            [void]$violations.Add("${rel}:$($i + 1)  [Spring AI 仅允许在 lytgos-provider 中出现] $trimmed")
        }

        # 规则 6：core 是最底层，不得反向引用其他模块
        if ($module -eq 'lytgos-core' -and $text -match 'import\s+io\.lytgos\.(?!core\.)') {
            [void]$violations.Add("${rel}:$($i + 1)  [lytgos-core 不得依赖其他模块] $trimmed")
        }
    }
}

# ---------- 规则 5：core 的 pom ----------
$corePom = Join-Path $projectRoot 'lytgos-core/pom.xml'
if (Test-Path -LiteralPath $corePom) {
    $pomText = Get-Content -LiteralPath $corePom -Raw -Encoding UTF8
    $pomWithoutComments = ($pomText -split "`n" | Where-Object { -not $_.TrimStart().StartsWith('<!--') }) -join "`n"

    if ($pomWithoutComments -match '<groupId>org\.springframework\.ai</groupId>') {
        [void]$violations.Add('lytgos-core/pom.xml  [core 不得依赖 Spring AI] 检出 org.springframework.ai 依赖')
    }
    if ($pomWithoutComments -match '<artifactId>spring-boot-starter') {
        [void]$violations.Add('lytgos-core/pom.xml  [core 不得依赖 Spring Boot starter] 检出 spring-boot-starter')
    }
}

if ($violations.Count -gt 0) {
    Write-Host "依赖方向 / 自动执行禁令 FAILED - $($violations.Count) 处违规：" -ForegroundColor Red
    foreach ($v in $violations) { Write-Host "  - $v" -ForegroundColor Red }
    exit 1
}

Write-Host "依赖方向与自动执行禁令确认（扫描 $($javaFiles.Count) 个 Java 文件）：" -ForegroundColor Green
Write-Host '  [OK] 1. org.springframework.ai.* 仅出现在 lytgos-provider' -ForegroundColor Green
Write-Host '  [OK] 2. 全仓未 import ChatClient' -ForegroundColor Green
Write-Host '  [OK] 3. 全仓未 import ToolCallingManager' -ForegroundColor Green
Write-Host '  [OK] 4. 全仓无 @ConditionalOnBean / ObjectProvider<ChatModel / getBeansOfType' -ForegroundColor Green
Write-Host '  [OK] 5. lytgos-core/pom.xml 不依赖 Spring AI 与 Spring Boot starter' -ForegroundColor Green
Write-Host '  [OK] 6. lytgos-core 不 import 本项目其他模块' -ForegroundColor Green
exit 0
