# Proposal

## Why

`06-change-contract` 把「没有进行中的 change」判定为 2（NOT_READY），而 `run-all.ps1` 的聚合规则是「出现 NOT_READY 即拒绝给出通过结论并退出 1」。两条规则组合后的后果是：任何一次归档把最后一个 change 移走之后，main 在「空闲期」永远无法通过完整验收，直到下一个 change 立项。

闸门应当回答的问题是「代码与工件有没有问题」，不是「当前有没有活干」。把「无事可判」当成「未就绪」，等于让一条本该是异常信号的红线变成常态；红线一旦常态，退出码就失去可信度，团队会开始习惯性忽略它。真正的未就绪只有一种情形：OpenSpec 根本还没在这个仓库初始化，即 `openspec/changes/` 目录本身不存在。

## What Changes

- `harness/checks/06-change-contract.ps1`：`openspec/changes/` 存在但没有任何进行中的 change 时，由 `exit 2` 改为 `exit 0`，并打印「无进行中 change，本维度无需判定」。
- 保留原有的 NOT_READY 语义：`openspec/changes/` 目录本身不存在时仍返回 2——那说明项目尚未初始化 OpenSpec，脚本确实没有判定对象。
- 同步更新脚本头注释里的三态说明，避免注释与实际行为不一致。
- 不改动 proposal 三小节的判据（`## Why` / `## What Changes` / 正文长度），不改动 `run-all.ps1` 的聚合逻辑，不改动其余闸门。

## Capabilities

无。这是闸门自身的语义修正，不涉及对外行为，也不改变任何既有 spec 中已写明的要求。

## Impact

- 代码：仅 `harness/checks/06-change-contract.ps1`，不涉及任何 Java 模块、依赖或数据。
- 文档：`harness/README.md` 中 `06` 的说明需要与新三态对齐。
- 验证：
  - 临时目录构造「有 `openspec/changes/archive/`、无进行中 change」→ 期望 exit 0；
  - 删除 `openspec/changes/` 后实跑 → 期望 exit 2（未就绪语义未被破坏）；
  - 仓库内实跑（当前有 4 个进行中 change）→ 期望 exit 0；
  - `harness\run-all.ps1` 退出码 0；
  - 合并归档 PR #20 之后，在真正的「零进行中 change」状态下复跑 `run-all.ps1`，退出码 0。
