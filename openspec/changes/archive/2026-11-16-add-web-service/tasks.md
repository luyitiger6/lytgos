# Tasks

## 1. lytgos-runtime 模块（纯重构，行为不变）

- [x] 1.1 创建 `lytgos-runtime` 模块骨架：`pom.xml`（依赖 core / persona / provider / memory / tool / storage / spring-context / spring-jdbc / mybatis-spring / HikariCP / snakeyaml / jackson / 测试依赖）、`package-info.java`；根 `pom.xml` 加 `<module>`；`lytgos-test/pom.xml` 登记。**验证**：`mvn -q -pl lytgos-runtime -am compile` 成功；`powershell -File harness\checks\04-dependency-direction.ps1` 退出码 0 且不再报「模块未登记」。
- [x] 1.2 迁移 9 个类到 `io.lytgos.runtime`：`WorkspaceConfig`、`WorkspaceConfigProvider`、`CoreBeans`、`ProviderBeans`、`ToolBeans`、`CliBeans`→`RuntimeBeans`、`CliConfiguration`→`RuntimeConfiguration`、`AgentRuntimeFactory`、`AgentRuntime`，并迁移 `WorkspaceConfigProviderTest`；`lytgos-cli` 改 pom 依赖与 import（含 `LazyContext` 里配置类的字符串常量），`lytgos-web` 与 `lytgos-boot` 增加对 runtime 的依赖。**验证**：`mvn clean package` 全绿；`07-week1-acceptance.ps1` 与 `08` 的状态与迁移前一致（有 key 时全 PASS，缺 key 时 SKIP 集合相同）。
- [x] 1.3 `RuntimeBeans.workspaceLocator` 改为读取 `lytgos.root` 属性（`@Value("${lytgos.root:}")`，空值回退 `user.dir`），并新增单测覆盖「属性为空用启动目录」「属性非空按该目录向上发现」。**验证**：新增单测通过；`07` 闸门仍通过（CLI 默认行为未变）。
- [x] 1.4 `lytgos-boot` 的 `LytgosApplication` 导入 `RuntimeConfiguration`，使 `java -jar lytgos.jar` 具备业务 Bean。**验证**：`powershell -File harness\checks\11-web-smoke.ps1` 九条全 PASS（启动过程不因缺 Bean 失败）。

## 2. 会话存储扩展

- [x] 2.1 `io.lytgos.core.session` 新增 `SessionSummary(String key, int messageCount, String updatedAt)`；`SessionStore` 增加 `boolean exists(String key)`、`List<SessionSummary> list()`、`void delete(String key)`。**验证**：接口编译通过，`Session` 既有用法（`get`/`save`）零改动。
- [x] 2.2 `SqliteSessionStore` 与 `SessionMapper` 实现三个新方法（`exists` 用 `SELECT COUNT(1)`、`selectAll` 返回 key/messages/updated_at、`delete` 删行并清缓存）；在 `SqliteStoreTest` 增补「空库 list 为空」「save 后 list 含正确消息数」「delete 后 exists=false 且 list 不含」。**验证**：`mvn -pl lytgos-storage test` 全绿。
- [x] 2.3 `FileSessionStore` 实现三个新方法（文件存在性、遍历反序列化 `Snapshot` 取消息数、删文件并清缓存），新增 `FileSessionStoreTest` 覆盖同样三条。**验证**：`mvn -pl lytgos-core test` 全绿。

## 3. 运行时端口与 Web 端点

- [x] 3.1 新增 `AgentInvoker` 接口（`run(agent, session, input)` / `tools(agent)`）与 `RuntimePool` 实现（懒创建缓存、失败不缓存、按会话 key 加锁、`@PreDestroy` 关闭全部）；`RuntimePoolTest` 覆盖「同一 agent 复用同一运行时」「不同 agent 各自创建」「close 关闭全部」。**验证**：`mvn -pl lytgos-runtime test` 全绿。
- [x] 3.2 新增 `SessionKeys`（`of(user, agent)` / `parse(key)` / 名称校验，非法抛 `InvalidAgentDefinitionException`）与单测覆盖合法、含 `:`、含 `..` 三类输入。**验证**：单测通过。
- [x] 3.3 `AgentLoader` 新增 `catalog(Path agentsRoot)` 返回 `items` 与 `errors` 两段结构，单测覆盖「正常目录」「坏 frontmatter 进 errors」「空目录返回空 items」。**验证**：`mvn -pl lytgos-persona test` 全绿。
- [x] 3.4 新增 `SessionController`：`POST /api/v1/sessions`（校验 agent/user、拼 key、落库空会话、重复创建返回 `created=false`）、`GET /api/v1/sessions`、`GET /api/v1/sessions/{id}`（不存在返回 404 + `LYTGOS_NOT_FOUND`）、`DELETE /api/v1/sessions/{id}`（不存在返回 404）。**验证**：`SessionControllerTest`（`@SpringBootTest` + MockMvc）覆盖四个端点与两个 404 场景。
- [x] 3.5 新增 `MessageController`：`POST /api/v1/sessions/{id}/messages`，从 key 解析 agent、`AgentInvoker.run` 执行、`finally` 落盘、返回 reply 与历史长度。**验证**：`MessageControllerTest` 用假 `AgentInvoker` 覆盖「返回 reply 且历史增长」「会话不存在 404」「agent 名非法 400」。
- [x] 3.6 新增 `AgentController`：`POST /api/v1/agents/{name}/invoke`，使用一次性 `Session`（`invoke:<uuid>`）且不调用 `SessionStore.save`。**验证**：测试断言调用前后会话数不变；未知 agent 返回 400 + `LYTGOS_INVALID_AGENT`。
- [x] 3.7 新增 `ProfileController`（`GET /api/v1/profiles`，返回 items + errors）与 `ToolController`（`GET /api/v1/tools?agent=`，缺省 default）。**验证**：测试覆盖「含 default 条目」「坏 Agent 进 errors 且整体 200」「工具数量与注册顺序」「未知 agent 400」。
- [x] 3.8 新增 `MemoryController`（`GET /api/v1/memory`）、`HealthController`（`GET /api/v1/health`）、`InfoController`（`GET /api/v1/info`，含 workspaceRoot 与 providers，不含密钥）。**验证**：测试覆盖「记忆为空返回 0 字符」「health 为 UP」「info 的 providers 无 api-key 字段」。
- [x] 3.9 把八个控制器注册进 `LytgosWebAutoConfiguration` 的 `@Import`；测试上下文 `WebTestApplication` 增加一个 `WebTestBeans` 配置，提供内存 `SessionStore`、假 `AgentInvoker`、临时工作区上的 `WorkspaceLocator`/`MemoryStore`/`AgentLoader`/`ProviderRegistry`。**验证**：`mvn -pl lytgos-web test` 全绿；`11-web-smoke.ps1` 九条仍全 PASS。

## 4. serve 命令与入口装配

- [x] 4.1 `lytgos-cli` 增加 `lytgos-web` 与 `spring-boot-starter-web` 依赖；新增 `ServeApplication`（`@SpringBootConfiguration + @EnableAutoConfiguration + @Import(RuntimeConfiguration.class)`）与 `ServeCommand`（`--port` 默认 **8989**、`--root` 可选，转成 `--server.port` / `--server.address` / `--lytgos.root` 命令行参数后 `SpringApplicationBuilder.run`）；在 `LytgosCommand.subcommands` 注册。**验证**：`java -jar lytgos-cli/target/lytgos-cli-boot.jar serve --port 18084 --root <临时工作区>` 启动后 `GET /api/v1/health` 返回 200；不带 `--port` 时监听 8989；`--help` 输出含 `serve`。
- [x] 4.2 `lytgos-web-defaults.yaml` 增加 `server.address: 127.0.0.1`。**验证**：启动 `serve` 后 `Get-NetTCPConnection -LocalPort 18084` 只显示 `127.0.0.1`；`11` 闸门经 localhost 访问仍九条 PASS。
- [x] 4.3 `serve` 在工作区外启动时给出可读错误并以非 0 退出。**验证**：在 `%TEMP%` 下执行不带 `--root` 的 `serve`，stderr 含「当前目录不在 lytgos 工作区内」，无堆栈，退出码非 0。

## 5. 管理台

- [x] 5.1 新增 `lytgos-web/src/main/resources/admin/index.html`：自包含 CSS/JS，展示健康、运行信息、Profile、工具、记忆、会话列表与会话详情，全部动态内容用 `textContent` 渲染。**验证**：页面源码不含 `http://` / `https://` 外部引用。
- [x] 5.2 新增 `AdminPageController`（`GET /admin` 与 `/admin/`，读 classpath 资源返回 `text/html; charset=UTF-8`）并注册进自动配置。**验证**：测试断言 200、`Content-Type` 含 `text/html`、响应体含页面标题。

## 6. 闸门与文档口径

- [x] 6.1 新增 `harness/checks/14-web-api-acceptance.ps1`：建临时工作区并 `init`，启动 `lytgos-cli-boot.jar serve --port 18083`，断言不依赖模型的 11 条（health / info / profiles / tools / memory / sessions 四端点 / 未知路径 404 / admin / OpenAPI 新路径），依赖模型的 3 条在缺 `DEEPSEEK_API_KEY` 时记 SKIP；收尸按端口与命令行双重校验。**验证**：单跑脚本，有 key 时退出码 0，缺 key 时退出码 2 且 SKIP 集合正确。
- [x] 6.2 同步清单：`AGENTS.md` 的 `harness-checks:` 标记行加 `14-web-api-acceptance`；`harness/README.md` 闸门清单加一行。**验证**：`powershell -File harness\checks\00-layout-check.ps1` 退出码 0。
- [x] 6.3 校正文档口径：`site/zh/docs/cli.md` 的 `serve` 段落与实现一致（默认端口 8989、`--root`、管理台地址）；`site/zh/docs/roadmap.md` 中仍不存在的项（Provider 注册表 CRUD、通知渠道、定时任务、文件浏览、审计 UI）去掉「已完成/可用」；`docs/design/architecture.md` 补记端点实现状态与两个入口的端口差异（9898 / 8989）。**验证**：逐项对照实现的命令与端点清单，文档里不出现未实现却被标为可用的条目。
- [x] 6.4 18080 让位给 Nacos：`harness/checks/11-web-smoke.ps1` 的 `$port` 从 18080 改为 **18090**，并同步脚本头注释里「端口用启动参数覆盖」的说明；`harness/README.md` 的 11 号闸门行与正文提法同步改为 18090，并新增「端口分配」小节把 `design.md` D9 的表作为仓库唯一端口口径（包含「18080 预留 Nacos」「8080 不用」两条）。**验证**：`powershell -File harness\checks\11-web-smoke.ps1` 九条全 PASS 且端口为 18090；`Get-NetTCPConnection -LocalPort 18080` 无监听；全文 grep `18080` 只剩「预留 Nacos」一类说明性文字，不再出现在任何脚本的运行参数里。

## 7. 集成验收

- [x] 7.1 全量 `powershell -File harness\run-all.ps1` 退出码 0（15 道闸门全 PASS；`07`/`08`/`10`/`14` 需要真实 `DEEPSEEK_API_KEY`，`08` 另需 `AMAP_MCP_KEY`）。**验证**：记录 RUNALL_EXIT 与各闸门耗时。
  - 完成说明：`RUNALL_EXIT=0`，`RESULT: ALL CHECKS PASSED`。各闸门耗时（s）：
    00=0.4、01=7.4、02=216.5、03=0.6、04=18.1、05=15.2、06=0.4、07=47.5、08=64.5、
    09=48.7（L0 min=520ms / L1 min=1827ms）、10=53、11=21.4（端口 18090）、12=36.5、13=332.3、14=27.8（15/15 PASS，端口 18083）。
  - 日志：全量输出存于 `.scratch/runall4.log`（临时文件，验收后清理）；本轮无 SKIP、无 FAIL。
- [x] 7.2 端到端手工验收：`serve` 启动 → 浏览器打开 `/admin/` → 创建会话 → 发一条消息 → 刷新会话详情 → 归档 → 会话从列表消失。**验证**：逐步截图或命令输出记录在 tasks 的完成说明里。
  - 完成说明：用临时脚本（端口 18084，临时工作区 `%TEMP%\lytgos-e2e-<stamp>`；脚本验收后随 `.scratch` 清理，常驻等价物为 14 号闸门）跑完八步，`RESULT: 端到端八步全 PASS`，退出码 0：
    1) `init` 退出码 0；2) `serve` 健康检查 200；3) `GET /admin/` 200 且含「lytgos 控制台」标题；
    4) `/api/v1/info` `/profiles`（含 default）`/tools` `/memory` 全 200；5) `POST /api/v1/sessions` 返回 `sessionId=api:e2e:default`；
    6) 发消息 200，reply 非空（75 字符）；7) 刷新详情 `messages=2`，首=user 末=assistant；
    8) `DELETE` 归档 200 → 列表中不再出现 → 详情 404。
  - 脚本本身不做浏览器截图：第 3 步断言的就是浏览器实际请求的 `/admin/` HTML 与页面 fetch 的同一批端点，故以命令输出作为持续可复跑的证据；
    断言口径（模型写入历史条数不锁死、只校首末角色）与 14 号闸门 M1 一致。

## Workflow follow-up

- 人类审阅通过后提交 PR，合并到 main。
- 归档本 change 并同步 `web-service` 主 spec。
