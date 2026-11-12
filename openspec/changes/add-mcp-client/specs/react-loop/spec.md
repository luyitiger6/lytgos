# Spec Delta

## MODIFIED Requirements

### Requirement: 工具执行与沙箱

系统 SHALL 通过 `ToolExecutor` 作为工具执行的唯一入口，所有工具调用 MUST 先经过 `SandboxChecker` 校验。

`SandboxChecker` MUST 提供三类校验：文件路径、Shell 命令、HTTP 域名。路径校验 MUST 在 `canonicalize()` 之后比较，禁止仅依赖字符串匹配 `..`。

域名白名单为空时 MUST 全部拒绝。该白名单只覆盖 lytgos 进程内发起的请求；MCP 工具的网络访问发生在独立子进程内，其沙箱深度止于启动命令白名单，系统 MUST NOT 宣称对 MCP 工具存在域名级约束。

工具失败 MUST 按归属分两类处理，两者的策略相反：

- **沙箱违规**（域名/命令/路径不在白名单、白名单为空）属于安全事件，MUST 抛出并中断本轮对话，不得让模型看到错误后换一个目标重试。
- **参数不合法**（URL 格式错、缺少必填参数、参数类型错）属于可纠正错误，MUST 转成文本回填给模型并继续循环，不得中断对话。

两类失败 MUST 使用不同的异常类型表达，禁止把参数错误包成沙箱违规。

#### Scenario: 白名单内域名放行

- **WHEN** `http_get` 请求白名单内的域名
- **THEN** 请求执行并返回结果

#### Scenario: 白名单外域名拒绝

- **WHEN** `http_get` 请求未在白名单的域名
- **THEN** 抛出异常，消息包含 `sandbox`，请求未发出

#### Scenario: URL 格式非法不中断对话

- **WHEN** `http_get` 的 `url` 参数格式非法，例如 query 里出现未转义的 `%`
- **THEN** 抛出的异常不是沙箱违规，错误文本回填给模型，本轮对话继续，模型可修正后重试

#### Scenario: 路径穿越拒绝

- **WHEN** 工具参数传入 `../../etc/passwd` 形式的路径
- **THEN** `canonicalize()` 后判定越界，请求被拒

#### Scenario: MCP 工具不受域名白名单约束

- **WHEN** MCP 工具在其子进程内向任意域名发起请求
- **THEN** 请求不经过域名白名单校验，`SandboxChecker` 只在该 server 启动时校验过 command
