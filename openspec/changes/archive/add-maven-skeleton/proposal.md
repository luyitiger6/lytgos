## Why

仓库当前只有文档与闸门，没有一行构建配置。`01-compile` / `02-test` / `03-static-guard` 长期停在 `NOT_READY`，原因都是「没有 pom.xml」。技术选型已经定稿（Java 21 / Spring Boot 4.x / Spring AI 2.x），继续没有 pom 的代价是：后续每个 change 都要先解释「为什么还编不了」，闸门也无法真正行使否决权。

本次 change 只解决一件事：让仓库具备可编译、可打包的多模块构建基线，给后续实现一个明确的落地位置。

## What Changes

- 新增根 `pom.xml`：`packaging=pom`，聚合 14 个模块，统一管理版本、编码、编译目标
- 新增 14 个子模块，按 `docs/design/architecture.md` §2 划分：

| 层 | 模块 |
|---|---|
| 内核 | `lytgos-core` |
| 能力层 | `lytgos-provider`、`lytgos-memory`、`lytgos-tool`、`lytgos-knowledge`、`lytgos-persona` |
| 渠道层 | `lytgos-channel-cli`、`lytgos-channel-feishu`、`lytgos-channel-wecom`、`lytgos-channel-dingtalk` |
| 接入层 | `lytgos-web`、`lytgos-cli` |
| 支撑层 | `lytgos-storage`、`lytgos-boot` |

- 依赖方向：所有模块单向依赖 `lytgos-core`；`lytgos-boot` 聚合全部模块；`lytgos-core` 不依赖任何同项目模块
- 新增 `lytgos-boot` 启动类 `LytgosApplication`，配置 `spring-boot-maven-plugin`，产物 `lytgos-boot/target/lytgos.jar` 可执行
- 版本基线：Spring Boot `4.1.2`、Spring AI BOM `2.0.2`、Java 21
- 各模块只放包声明（`package-info.java`），不含任何业务实现

## Impact

- 新增：根 `pom.xml`、14 个模块目录（各含 `pom.xml` 与最小源码）
- 修改：`AGENTS.md` 状态描述（「只有骨架，没有代码」→「骨架可编译，无业务实现」）
- 闸门：`01-compile` 转为可判定并应通过；`02-test` 有判定对象（无测试时为 0 用例通过）；`03-static-guard` 开始扫描；`04` / `05` 维持空壳不变
- 不影响已有文档内容、闸门逻辑、`.gitignore` 与依赖方向约束

## Open Questions

- `04-dependency-direction` 仍为空壳：是否在本次 change 一并落地 ArchUnit 规则，还是等 `lytgos-core` 有真实接口后再做。倾向后者——现在没有接口可约束，规则写了也无判定对象。
- Spring AI 依赖是否在本次引入：倾向只登记 BOM 版本、暂不添加具体 starter，避免与 Spring Boot 4.1.2 的兼容性在无业务代码时先暴露。
