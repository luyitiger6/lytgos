# tasks

- [x] 1.1 删除模块目录 `lytgos-channel-feishu`、`lytgos-channel-wecom`、`lytgos-channel-dingtalk`（含 pom 与 package-info）
- [x] 1.2 根 `pom.xml` 的 `<modules>` 移除三个渠道模块
- [x] 1.3 `lytgos-boot/pom.xml` 移除三个渠道依赖
- [x] 1.4 `AGENTS.md` 模块数量描述 14 → 11（两处）
- [x] 1.5 `mvn clean package` 通过，`lytgos.jar` 正常产出

## 未完成（依赖其他分支）

- [ ] 2.1 `lytgos-channel-cli` 改名为 `lytgos-channel`，包根收敛到 `io.lytgos.channel`
- [ ] 2.2 `docs/architecture/module-dependencies.dot` 与 `.svg` 同步（图在 `docs/architecture-diagrams-20261112` 分支，合并后再更新）
