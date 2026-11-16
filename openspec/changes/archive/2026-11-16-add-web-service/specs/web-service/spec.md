# Spec Delta

## Purpose

定义 lytgos 对外提供 HTTP 服务的能力：外部系统通过 `lytgos serve` 启动服务，用 REST 端点管理会话、调用 Agent、读取 Profile / 工具 / 记忆与运行信息，并通过只读管理台观察服务状态。

## ADDED Requirements

### Requirement: serve 命令启动 HTTP 服务

`lytgos serve` MUST 启动与 `java -jar lytgos.jar` 相同的 Spring Web 容器与业务装配，接受 `--port` 与 `--root` 两个可选参数；`--port` 缺省为 8989，`--root` 缺省为当前目录，并且 MUST 识别环境变量 `LYTGOS_ROOT` 作为工作区根的默认来源。

#### Scenario: 默认参数启动

- **WHEN** 在工作区内执行 `lytgos serve`
- **THEN** 服务在 8989 端口就绪，`GET /api/v1/health` 返回 HTTP 200

#### Scenario: 端口覆盖

- **WHEN** 执行 `lytgos serve --port 18084`
- **THEN** 服务只在 18084 端口响应，8989 端口无监听

#### Scenario: 工作区根由参数指定

- **WHEN** 在任意目录执行 `lytgos serve --root <某工作区路径>`
- **THEN** 服务读取该工作区的配置、Agent 定义与数据库，而不是启动目录的内容

#### Scenario: 不在工作区时给出可读错误

- **WHEN** 在工作区外执行 `lytgos serve` 且未指定 `--root`
- **THEN** 进程以非 0 退出码结束，错误信息说明「当前目录不在 lytgos 工作区内」，不打印堆栈

### Requirement: 服务默认只监听回环地址

HTTP 服务 MUST 默认绑定回环地址（127.0.0.1）；把服务暴露到非回环地址不属于本能力，MUST NOT 在未显式配置时监听其他网卡。

#### Scenario: 默认仅本地可达

- **WHEN** 执行 `lytgos serve` 后从本机访问 `http://127.0.0.1:8989/api/v1/health`
- **THEN** 返回 HTTP 200；同机其他网络接口上无该端口的监听

### Requirement: 会话创建与查询

系统 MUST 提供 `POST /api/v1/sessions` 创建会话（请求体含 agent 名与可选 user），返回会话标识；MUST 提供 `GET /api/v1/sessions/{id}` 返回该会话的消息历史；MUST 提供 `GET /api/v1/sessions` 返回全部会话的标识、消息数与最后更新时间。

#### Scenario: 创建会话返回标识

- **WHEN** `POST /api/v1/sessions`，请求体为 `{"agent":"default"}`
- **THEN** 返回 HTTP 200，`data.sessionId` 非空，随后 `GET /api/v1/sessions` 的结果包含该标识

#### Scenario: 同参数重复创建幂等

- **WHEN** 用相同 agent 与 user 连续两次 `POST /api/v1/sessions`
- **THEN** 两次返回同一个会话标识，会话数不增加

#### Scenario: 查询历史

- **WHEN** `GET /api/v1/sessions/{id}`，该会话已有 2 条消息
- **THEN** 返回 HTTP 200，`data.messages` 长度为 2，每条含 role 与 content

#### Scenario: 未知会话

- **WHEN** `GET /api/v1/sessions/{不存在的标识}`
- **THEN** 返回 HTTP 404，`code` 为 `LYTGOS_NOT_FOUND`

### Requirement: 发消息触发对话循环

`POST /api/v1/sessions/{id}/messages` MUST 把请求体中的消息追加到该会话并跑完一次完整对话循环，返回模型最终文本；本轮结束后会话历史 MUST 落盘，使下一次请求能接上上下文。

#### Scenario: 单轮消息返回回复

- **WHEN** 向已创建会话 `POST /api/v1/sessions/{id}/messages`，请求体为 `{"message":"你好"}`
- **THEN** 返回 HTTP 200，`data.reply` 非空，`GET /api/v1/sessions/{id}` 的历史包含该用户消息与助手回复

#### Scenario: 跨请求保留上下文

- **WHEN** 先发送「我的名字是张三」，再在另一请求中发送「我叫什么」
- **THEN** 第二次回复包含「张三」

#### Scenario: 缺少模型凭据时给出可读失败

- **WHEN** 未设置 provider 所需的环境变量时发消息
- **THEN** 返回 HTTP 500 且 `code` 为 `LYTGOS_INVALID_CONFIGURATION`（与既有受控异常映射一致），`message` 指出缺失的环境变量名

### Requirement: 会话归档

`DELETE /api/v1/sessions/{id}` MUST 归档该会话：之后的查询返回 404，该会话不再出现在会话列表中；归档不存在的会话 MUST 返回 404。

#### Scenario: 归档后不可查

- **WHEN** `DELETE /api/v1/sessions/{id}` 成功后再 `GET /api/v1/sessions/{id}`
- **THEN** 返回 HTTP 404

#### Scenario: 归档后不在列表

- **WHEN** 归档某会话后 `GET /api/v1/sessions`
- **THEN** 结果中不含该标识

### Requirement: 无状态 Agent 调用

`POST /api/v1/agents/{name}/invoke` MUST 在不读写任何已有会话的前提下执行一次完整对话，返回模型最终文本；未知 Agent 名 MUST 返回 HTTP 400 且 `code` 为 `LYTGOS_INVALID_AGENT`。

#### Scenario: 单次调用返回文本

- **WHEN** `POST /api/v1/agents/default/invoke`，请求体为 `{"input":"用一句话介绍你自己"}`
- **THEN** 返回 HTTP 200，`data.reply` 非空，且调用前后 `GET /api/v1/sessions` 的会话数不变

#### Scenario: 未知 Agent

- **WHEN** `POST /api/v1/agents/no-such-agent/invoke`
- **THEN** 返回 HTTP 400，`code` 为 `LYTGOS_INVALID_AGENT`

### Requirement: Profile 与工具查询

`GET /api/v1/profiles` MUST 返回工作区内全部 Agent 的 name、description、provider 名与模型 id、工具白名单，解析失败的 Agent 以错误条目列出而不中断整体响应；`GET /api/v1/tools` MUST 返回指定 Agent（查询参数 `agent`，缺省 `default`）实际可用的工具，每项含 name 与 description。

#### Scenario: 列出 Profile

- **WHEN** 工作区内存在 `default` Agent 时 `GET /api/v1/profiles`
- **THEN** 返回 HTTP 200，结果包含 name 为 `default` 的条目，且条目含 provider 与 model 字段

#### Scenario: 列出工具

- **WHEN** `GET /api/v1/tools?agent=default`
- **THEN** 返回 HTTP 200，结果包含 7 个内置工具的 name，顺序与注册顺序一致

#### Scenario: 未知 Agent 的工具查询

- **WHEN** `GET /api/v1/tools?agent=no-such-agent`
- **THEN** 返回 HTTP 400，`code` 为 `LYTGOS_INVALID_AGENT`

#### Scenario: 坏 Agent 不拖垮列表

- **WHEN** `agents/` 下存在一个 frontmatter 非法的 Agent 时 `GET /api/v1/profiles`
- **THEN** 返回 HTTP 200，合法 Agent 出现在 `data.items`，非法 Agent 以 name 与错误原因出现在 `data.errors`

### Requirement: 长期记忆查询

`GET /api/v1/memory` MUST 返回工作区 `memory/MEMORY.md` 的当前内容、字符数与上限；文件不存在时 MUST 返回空内容与零字符数，而不是错误。

#### Scenario: 有记忆时返回全文

- **WHEN** `MEMORY.md` 含一条记忆时 `GET /api/v1/memory`
- **THEN** 返回 HTTP 200，`data.content` 包含该条记忆，`data.maxChars` 为 8000

#### Scenario: 无记忆不算故障

- **WHEN** `MEMORY.md` 不存在时 `GET /api/v1/memory`
- **THEN** 返回 HTTP 200，`data.content` 为空串，`data.chars` 为 0

### Requirement: 健康检查与运行信息

`GET /api/v1/health` MUST 返回服务存活状态；`GET /api/v1/info` MUST 返回应用名、版本、构建 JDK、当前请求线程是否为虚拟线程、工作区根绝对路径、已登记的 provider 名单；provider 名单 MUST NOT 包含任何密钥值。

#### Scenario: 健康检查

- **WHEN** `GET /api/v1/health`
- **THEN** 返回 HTTP 200，`data.status` 为 `UP`

#### Scenario: 运行信息暴露运行时事实

- **WHEN** `GET /api/v1/info`
- **THEN** 返回 HTTP 200，`data.virtualThread` 为 true，`data.javaVersion` 以 `21` 开头，`data.workspaceRoot` 是绝对路径，`data.providers` 列出配置中的 provider 名

#### Scenario: 运行信息不含密钥

- **WHEN** 配置中 provider 的 api-key 引用环境变量时 `GET /api/v1/info`
- **THEN** 响应体不含该环境变量的值，也不含 `apiKey` 或 `api-key` 字段

### Requirement: 只读管理台

系统 MUST 在 `/admin/` 提供可直接访问的 HTML 管理台页面，展示服务信息、Profile、工具、记忆与会话列表；页面 MUST NOT 依赖外部 CDN 或构建链，断网时仍可打开。

#### Scenario: 管理台可访问

- **WHEN** `GET /admin/`
- **THEN** 返回 HTTP 200 且 `Content-Type` 为 `text/html`，响应体含页面标题

#### Scenario: 页面不引用外部资源

- **WHEN** 检查 `/admin/` 页面源码
- **THEN** 不含 `http://` 或 `https://` 形式的外部脚本与样式引用

### Requirement: 端点全部遵守既有 HTTP 契约

新增的 `/api/v1` 端点 MUST 使用统一响应体、受控异常映射与未知路径 404 三项既有契约，并 MUST 出现在 OpenAPI 文档的 `paths` 中。

#### Scenario: 新端点在 OpenAPI 文档中可见

- **WHEN** 请求 `/v3/api-docs`
- **THEN** 200，`paths` 至少包含 5 条本次新增路径，且含 `/api/v1/sessions` 与 `/api/v1/profiles`

#### Scenario: 新端点的未知子路径仍为统一 404

- **WHEN** `GET /api/v1/sessions/x/y/z`
- **THEN** 返回 HTTP 404，`code` 为 `LYTGOS_NOT_FOUND`
