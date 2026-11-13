<#
    闸门 05-audit-invariant：审计不变量

    当前状态：空壳。尚无审计写入实现，不变量无判定对象。

    计划判据（第一条审计写入链路落地时实现）：
      I1  每次模型调用必有一行 llm_calls —— 成功 / 失败 / 超时都算
      I2  每次工具调用必有一行 tool_invocations —— 允许 / 拒绝 / 超时 / 异常都算
      I3  tool_invocations 与 llm_calls 的 turn_id 必能关联到同一 session，不存在跨域

    实现方式：断言写在 JUnit 里，本闸门只负责保证「这些断言类存在且被真实执行」。
    实现时必须先删除旧的 surefire 报告再核对 Tests run 数——mvn 没跑到测试阶段时
    旧报告还在，会把上一次的结果当成这一次的（02/04 踩过同类坑）。
    为什么不并进 02-test：
    审计不变量是最容易为了「先跑通」而被注释掉的东西，
    混在几百个测试里，它消失了没人看得见；单独成闸，消失就是一次 FAIL。
#>
Write-Host 'NOT_READY: 尚无审计写入实现，不变量无判定对象' -ForegroundColor DarkYellow
exit 2
