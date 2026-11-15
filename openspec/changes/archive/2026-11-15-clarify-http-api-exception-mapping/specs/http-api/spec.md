# Spec Delta

## MODIFIED Requirements

### Requirement: 受控异常映射

`LytgosException` 的每个子类 MUST 映射为固定的 HTTP 状态与错误码，响应体沿用统一结构且 `data` 为 null。

#### Scenario: 沙箱违规为 403

- **WHEN** 某个端点抛出 `SandboxViolationException`
- **THEN** 返回 HTTP 403，code 为 `LYTGOS_SANDBOX_VIOLATION`，message 保留原异常文案

#### Scenario: 参数类异常为 400

- **WHEN** 某个端点抛出 `InvalidToolArgumentException`
- **THEN** 返回 HTTP 400，code 为 `LYTGOS_INVALID_TOOL_ARGUMENT`

#### Scenario: 其余受控异常按固定映射返回

- **WHEN** 端点分别抛出 `InvalidAgentDefinitionException`、`UnknownProviderException`、`InvalidConfigurationException`、其余 `LytgosException`
- **THEN** 分别返回 HTTP 400 / `LYTGOS_INVALID_AGENT`、HTTP 400 / `LYTGOS_UNKNOWN_PROVIDER`、HTTP 500 / `LYTGOS_INVALID_CONFIGURATION`、HTTP 500 / `LYTGOS_INTERNAL`
