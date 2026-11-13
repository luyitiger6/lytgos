# Spec Delta

## MODIFIED Requirements

### Requirement: Provider 显式映射

系统 SHALL 从工作区配置的 `providers` 配置节构造 `provider name → ChatModel` 的不可变映射，条目与顺序完全由配置决定。

`${ENV_VAR}` 占位 MUST 在首次构造 `ChatModel` 时解析；不触发模型连接的只读命令（如 `provider list`、`tool list`）MUST NOT 因占位符缺失而失败。

系统 MUST NOT 通过类型扫描发现 Provider，禁止 `@ConditionalOnBean`、`ObjectProvider<ChatModel>`、`getBeansOfType` 等机制。

#### Scenario: 按名取用

- **WHEN** 配置声明 `deepseek` 与 `kimi` 两个 provider，Agent 指定 `provider.name: deepseek`
- **THEN** 使用 deepseek 对应的 `ChatModel`，另一个不被实例化使用

#### Scenario: 未声明的 provider

- **WHEN** Agent 指定 `provider.name: not-exist`，执行需要连接模型的命令
- **THEN** 命令失败，退出码非 0，错误消息列出已注册的 provider 名

#### Scenario: 环境变量缺失

- **WHEN** 配置引用 `${DEEPSEEK_API_KEY}` 且该变量未设置，执行需要连接模型的命令
- **THEN** 命令失败，退出码非 0，错误消息包含 `DEEPSEEK_API_KEY`

#### Scenario: 只读命令不受缺失的环境变量影响

- **WHEN** 配置引用 `${DEEPSEEK_API_KEY}` 且该变量未设置，执行 `lytgos provider list`
- **THEN** 命令成功，退出码 0，配置中的 provider 名与模型 id 正常列出
