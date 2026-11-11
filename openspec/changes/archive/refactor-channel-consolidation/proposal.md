# refactor-channel-consolidation

## Why

四个渠道模块（`channel-cli` / `-feishu` / `-wecom` / `-dingtalk`）是照着模块表实现的，拆分的真实收益只有一个：依赖隔离。但只有当渠道使用官方 SDK、且 SDK 之间存在依赖冲突时，这个收益才成立。

实际路线是渠道全部走 HTTP API（飞书、企微、钉钉都提供开放接口），签名与消息格式差异由渠道自己的包内代码吸收，不引入官方 SDK。依赖冲突不存在，拆模块就只剩成本：

- 接一个新 IM 要建模块、写 pom、改聚合、进 boot 依赖
- 重试、限流、签名工具等公共逻辑每个模块各写一遍
- 模块数量随渠道线性膨胀

## What Changes

14 个模块 → 11 个：

| 动作 | 对象 |
|---|---|
| 删除 | `lytgos-channel-feishu`、`lytgos-channel-wecom`、`lytgos-channel-dingtalk` |
| 改名 | `lytgos-channel-cli` → `lytgos-channel` |
| 包根 | `io.lytgos.channel`，渠道实现各自下沉到 `feishu/` `wecom/` `dingtalk/` `cli/` 子包 |

`Channel` 接口定义在 `lytgos-core` 的 `port` 包，各渠道是实现，`lytgos-boot` 按配置里启用的渠道列表实例化——**按需启动取代按需打包**。

口径说明：这是适配器模式（统一接口 + 各渠道自行适配）加一张按 name 查找的注册表，不是策略模式。

## 拆分条件（写进文档，避免以后又拍脑袋拆）

出现以下任意一条才重新拆模块：

1. 某渠道必须使用官方 SDK，且与其他 SDK 依赖冲突
2. 单模块编译时间明显变长（渠道 > 15 个）
3. 单渠道代码超过 2000 行且只有它需要那块依赖

## Impact

- 根 `pom.xml` 的 `<modules>`、`lytgos-boot/pom.xml` 的依赖清单
- `AGENTS.md` 目录地图与依赖数量描述
- `docs/architecture/module-dependencies.dot` 与生成的 `.svg`（硬规则 8）
- 无对外行为变化，不涉及 `specs/` delta

## 不做什么

- 不写任何渠道实现代码，只调结构
- 不引入任何 IM SDK
