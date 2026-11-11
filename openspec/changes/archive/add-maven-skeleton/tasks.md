# Tasks

## 1. 构建基线

- [x] 1.1 根 `pom.xml`：`groupId=io.lytgos`、`artifactId=lytgos`、`version=0.1.0-SNAPSHOT`、`packaging=pom`
- [x] 1.2 根 `pom.xml`：`<modules>` 按依赖顺序列出 14 个模块
- [x] 1.3 根 `pom.xml`：`properties` 锁定 `java.version=21`、`spring-boot.version=4.1.2`、`spring-ai.version=2.0.2`、UTF-8、LF
- [x] 1.4 根 `pom.xml`：`dependencyManagement` 导入 `spring-boot-dependencies` 与 `spring-ai-bom`；`pluginManagement` 锁定 compiler / surefire / spring-boot-maven-plugin

## 2. 模块 pom

- [x] 2.1 `lytgos-core`：不依赖任何同项目模块
- [x] 2.2 能力层五模块 `provider` / `memory` / `tool` / `knowledge` / `persona`：单向依赖 `lytgos-core`
- [x] 2.3 渠道层四模块 `channel-cli` / `channel-feishu` / `channel-wecom` / `channel-dingtalk`：单向依赖 `lytgos-core`
- [x] 2.4 接入层 `web` / `cli` 与支撑层 `storage`：单向依赖 `lytgos-core`
- [x] 2.5 `lytgos-boot`：依赖全部 13 个模块，配置 `spring-boot-maven-plugin` 与 `finalName=lytgos`

## 3. 最小源码

- [x] 3.1 每个模块一个 `package-info.java` 声明包职责，不含实现
- [x] 3.2 `lytgos-boot` 的 `LytgosApplication`：`@SpringBootApplication` + `main`
- [x] 3.3 确认全部源码无 `TODO` / `FIXME` / `XXX` / `System.out` / `@Disabled`

## 4. 验证与收尾

- [x] 4.1 `mvn clean package` 通过，产出 `lytgos-boot/target/lytgos.jar`
- [x] 4.2 `java -jar lytgos-boot/target/lytgos.jar` 能启动（`lytgos-web` 未接 web starter 时允许立即退出，以日志无异常为准）
- [x] 4.3 `harness/run-all.ps1`：`01-compile` / `02-test` / `03-static-guard` 由 `NOT_READY` 转为 `PASS`；`04` / `05` 维持空壳
- [x] 4.4 `AGENTS.md` §1 状态与 §7 目录地图更新为「骨架可编译」
- [x] 4.5 提交并推送分支，远端 ref 哈希核对
