# Spec Delta

## MODIFIED Requirements

### Requirement: 依赖漏洞门禁

OWASP Dependency-Check MUST 以独立 profile `security` 提供，默认构建不执行。启用时 CVSS 分值达到或超过 7 的依赖 MUST 导致构建失败；误报豁免 MUST 写入 suppressions 文件并注明理由。CI MUST 在每次合并请求上执行该 profile。本地一键验收 `harness\run-all.ps1` MUST 同样覆盖该门禁。检出高危依赖时 MUST 优先升级到修复版本；确实无法升级才允许豁免，且豁免 MUST 限定到 CVE 编号与依赖坐标，并写明无法升级的原因与复查日期。

#### Scenario: 引入高危依赖被拦截
- **WHEN** 以 `security` profile 构建且依赖树中存在 CVSS 7 及以上的已知漏洞
- **THEN** 构建失败并输出漏洞编号、依赖坐标与 CVSS 分值

#### Scenario: 默认构建不受影响
- **WHEN** 开发者执行 `mvn package` 或 `mvn verify` 且未启用 `security` profile
- **THEN** 依赖漏洞扫描不执行，构建耗时与未接入该门禁时一致

#### Scenario: 本地一键验收覆盖依赖漏洞
- **WHEN** 开发者执行 `harness\run-all.ps1`
- **THEN** 其中包含一道依赖漏洞闸门，以 `security` profile 执行 Dependency-Check 并纳入总闸门的三态判定
- **THEN** 存在 CVSS 7 及以上依赖时总闸门返回失败，而不是只报「其余闸门全绿」

#### Scenario: 豁免前先尝试升级
- **WHEN** 某个 transitive 依赖被检出 CVSS 7 及以上漏洞且上游尚未跟进修复版本
- **THEN** 先在依赖管理段锁定上游已修复的可用版本
- **THEN** 只有在没有可用修复版本时才写入豁免，并附 CVE 编号、依赖坐标、无法升级的原因与复查日期
