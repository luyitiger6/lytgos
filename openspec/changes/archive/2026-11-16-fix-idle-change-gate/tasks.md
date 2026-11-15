# Tasks

## 1. 闸门语义修正

- [x] 1.1 `06-change-contract.ps1`：`openspec/changes/` 存在但没有进行中 change 时返回 0，打印「无进行中 change，本维度无需判定」
  - 保留 `openspec/changes/` 目录不存在时返回 2 的未就绪语义
  - 同步脚本头注释的三态说明
  - **验收**：临时目录（有 `openspec/changes/archive/`、无进行中目录）实跑 exit 0；删除 `openspec/changes/` 后实跑 exit 2；仓库内实跑 exit 0
  - **实测**：A_IDLE_EXIT=0（空闲期通过）/ B_NO_DIR_EXIT=2（目录缺失仍未就绪）/ C_HOLLOW_EXIT=1（空壳 change 仍拦截）/ REPO06_EXIT=0（仓库内 4 个 change 契约完整）
- [x] 1.2 `harness/README.md` 中 `06` 的三态说明与新行为对齐

## 2. 验收

- [x] 2.1 `openspec validate --changes` 与 06 闸门在仓库内实跑均通过
  - **实测**：`openspec validate --changes` 4/4 通过（本 change 以 `skip_specs: true` 声明零 delta）；06 闸门「change 契约完整：4 个进行中的 change 均有 proposal 说明工作内容」，exit 0
- [x] 2.2 `harness\run-all.ps1` 退出码 0
  - **实测**：14 道闸门全 PASS，`RESULT: ALL CHECKS PASSED`，`RUNALL_EXIT=0`（02-test 220.5s / 06 闸门 0.6s / 13 依赖扫描 144.9s）
- [x] 2.3 合并归档 PR #20 后，在「零进行中 change」状态下复跑 `harness\run-all.ps1`，退出码 0
  - **实测**：PR #20 合并后 main=95758ba，changes 根目录仅剩 `archive/`；06 闸门 exit 0 并打印「无进行中 change，本维度无需判定」；完整 `harness\run-all.ps1` 14 道全 PASS，`RESULT: ALL CHECKS PASSED`，`RUNALL_EXIT=0`（02-test 206.8s / 06 0.5s / 13 130.2s）。验证后目录移回，工作树干净
