<#
    闸门 04-dependency-direction：依赖方向 + 禁止自动工具执行

    当前状态：空壳。没有模块与 pom，任何「检查通过」都是假的。

    计划判据（第一个模块 lytgos-core 落地时实现，规则与实现同一次提交）：
      1. maven-enforcer bannedDependencies：core 不得依赖 web / boot / cli
      2. ArchUnit：core.. 不得被 web / boot / cli 反向引用
      3. ArchUnit：除 lytgos-provider 外，禁止依赖
           org.springframework.ai.chat.client.ChatClient
           org.springframework.ai.model.tool.ToolCallingManager
         —— 这是「禁止自动工具执行」的结构性保证。
            一旦有人把 ChatClient 接回来，工具执行就会脱离 ToolExecutor，
            配额、白名单、沙箱、审计全部失效，而界面上看不出任何异常。

    为什么值得单独成一道闸门：
    它是本项目唯一一条「违反了也不会报错、只会静默失去产品价值」的约束。
#>
Write-Host 'NOT_READY: 尚无模块与 pom.xml，依赖方向与自动执行禁令无从判定' -ForegroundColor DarkYellow
exit 2
