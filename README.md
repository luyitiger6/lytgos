# lytgos

Java 生态的企业级 **Agent OS 控制面**。

不解决「agent 怎么跑起来」（那是 LangChain4j / Spring AI / Embabel / AgentScope 的事），解决框架不管的另一半：**谁能跑、跑多少、花多少、出过什么事**。

---

## 当前状态

**只有骨架，没有代码。**

| 有 | 没有（刻意） |
|---|---|
| `AGENTS.md` 契约、`openspec/` 工件层配置 | `pom.xml` |
| `harness/` 能力层（6 道闸门，其中 4 道未就绪） | 任何 `lytgos-*` 模块目录 |
| `docs/design/architecture.md` 设计方案（草稿 v0.2） | 任何第三方依赖 |
| `.agents/skills/` 两个强制技能 | 前端 |

技术选型（Spring Boot 3.x 还是 4.x、模块划分、SQLite 细节）尚未定稿，定稿前不落地代码。

## 快速上手

```powershell
# 骨架自检：现在应该是 2 通过 + 4 未就绪
powershell -File harness\run-all.ps1
echo $LASTEXITCODE     # 预期 1，见下方说明
```

### 为什么现在跑总闸门是「红」的

`run-all.ps1` 有三个退出状态，不是一个：

| 退出码 | 含义 |
|---|---|
| `0` | 全部闸门真实通过 |
| `2` | 该闸门前置条件不存在（如尚无 `pom.xml`） |
| `1` | 有闸门跑过但不通过；或有闸门未就绪，总闸门**拒绝给出通过结论** |

没有代码时「全部通过」是一个假绿灯——它会让后来的人误以为验收过了。所以未就绪也让总闸门返回 1。

第一个模块落地后，01 / 02 / 04 / 05 会依次转为真实判定，`run-all.ps1` 才会返回 0。

## 设计稿

[`docs/design/architecture.md`](docs/design/architecture.md) —— 含 8 条已锁定决定、9 模块依赖图、核心抽象签名、三张表 DDL、10 个 REST 端点、12 个 CLI 命令、8 条故事 → Demo 对照，以及**两项待你拍板**（命名统一、`gateway` 语义）。

## 下一步

1. 拍板设计稿 §10 的两项（命名、`gateway`）
2. `/opsx:propose add-kernel-react-loop`（capabilities: `agent-runtime` + `model-gateway`）
3. 人类审阅放行 → `/opsx:apply`

## 许可

私有项目，未定。
