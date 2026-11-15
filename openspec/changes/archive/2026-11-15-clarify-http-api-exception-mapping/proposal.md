# Proposal

## Why

`openspec validate --specs` 对 `http-api` 的「受控异常映射」需求报出告警：需求描述超过 500 字符，`--strict` 会直接失败。该描述把六条映射塞进一句话，虽然语义正确，但违反「一条需求只陈述一种行为」，也让后续按错误码改动时难以定位差异。现在处理，是为了在归档第二周 change、开启审计 change 之前把规格库恢复到零告警状态。

## What Changes

- 只改规格文本，不改任何代码、接口或运行时行为。
- `受控异常映射` 的描述收敛为一条行为：`LytgosException` 子类 MUST 映射为固定状态与错误码、统一响应体且 `data` 为 null。
- 原描述中未被既有场景覆盖的四条映射（`InvalidAgentDefinitionException`、`UnknownProviderException`、`InvalidConfigurationException`、其余 `LytgosException`）移入新的 `#### Scenario: 其余受控异常按固定映射返回`，映射结果与现状逐条对应。
- 既有两个场景（沙箱违规 403、参数类 400）原样保留。

## Capabilities

### New Capabilities

无。

### Modified Capabilities

- `http-api`: 「受控异常映射」需求描述超出长度上限，拆分描述与场景，行为语义不变。

## Impact

- 仅影响 `openspec/specs/http-api/spec.md`（通过 delta 同步）。
- 不涉及代码、配置、依赖、部署。
- 验收口径：`openspec validate --specs` 零告警，且映射关系与现行为逐条一致。
