# CLI 参考

`lytgos` 命令行工具当前实现 6 个子命令：`init` / `chat` / `profile` / `provider` / `tool` / `serve`，用于管理工作区与 Agent、查看 Provider 与工具、运行 Agent，以及对外提供 HTTP 服务。

所有命令都支持 `--help`（`-h`）查看用法、`--version`（`-V`）打印版本及 JVM/OS 信息。

> 本文中标注「未实现」的章节属于路线图——`status` / `session` / `gateway` / `user` 等子命令在当前版本中不存在，`--help` 里也不会出现，不要据此操作。

## 工作区根目录

默认在当前目录读写 `.lytgos` 工作区，根目录可自定义：

| 方式 | 作用范围 | 说明 |
| --- | --- | --- |
| `LYTGOS_ROOT`（环境变量） | 所有命令 | 轻命令直接读取；`serve` 经 Spring relaxed binding 也认 |
| `-Dlytgos.root=<path>`（JVM 系统属性） | 所有命令 | 轻命令优先级最高 |
| `application.yml` 的 `lytgos.root` | 仅 `serve` | 轻命令（`init` / `profile`）不启动 Spring、不读 yaml，要给它们自定义根请用环境变量或系统属性 |

轻命令解析顺序：`-Dlytgos.root` → `LYTGOS_ROOT` → 默认 `.lytgos`。配置的根目录会在运行时自动加入文件沙箱白名单，换根不会破坏文件工具。

```bash
# 让所有命令指向自定义工作区
LYTGOS_ROOT=/data/ws lytgos init
LYTGOS_ROOT=/data/ws lytgos status
```

## 初始化和状态

### lytgos init

初始化工作区（默认 `.lytgos/`）。在当前目录创建目录结构和默认 Bootstrap 文件。重复运行会跳过已有文件、绝不覆盖。这是轻命令：不启动 Spring，认 `LYTGOS_ROOT` / `-Dlytgos.root`。

```bash
lytgos init
# 或指定自定义工作区根：
LYTGOS_ROOT=/data/ws lytgos init
```

创建的内容：

```text
.lytgos/
├── agents/       # 每个子目录 = 一个 Agent（AGENT.md + 可选 skills/ scripts/ REFERENCE.md）
├── memory/       # 全局长期记忆（每个 Agent 自己的 MEMORY.md 在 agents/<name>/ 下）
├── sessions/     # 备用（会话状态已入 SQLite）
├── logs/
├── AGENTS.md     # Bootstrap：项目级 agent 行为说明
├── SOUL.md       # Bootstrap：agent 人格定义
└── USER.md       # Bootstrap：用户偏好（只读）
```

`lytgos.db` 在首次运行重命令（`serve` / `chat`）时创建，`init` 本身不建库。

### lytgos status（未实现）

> **未实现（路线图）**：当前版本没有 `status` 子命令。工作区是否可用看 `lytgos init` 的输出，服务是否起来看 `lytgos serve` 的启动日志与 `GET /api/v1/health`。

## 对话

### lytgos chat

启动对话。不带 `--message` 时为交互式多轮对话，每轮经过完整的 ReAct Loop，Agent 可调用工具后再回复，使用 `/quit` 或 `Ctrl-D` 退出；带 `--message` 时只跑一轮然后退出。

```bash
lytgos chat [--agent <name>] [--message <text>]
```

| 选项 | 说明 |
| --- | --- |
| `-a`, `--agent <name>` | 指定使用的 Agent，默认为 `default` |
| `-m`, `--message <text>` | 单条消息模式：发送后打印回复并退出 |

示例：

```bash
lytgos chat --agent ops-agent
lytgos chat -m 你好
```

## 服务

### lytgos serve

启动 HTTP API 服务：对外暴露 `/api/v1` 下的 REST API，并在 `/admin/` 提供**只读** Web 管理台（服务信息、Profile、工具、记忆、会话列表与会话详情）。作为重命令，`serve` 会启动 Spring；工作区根依次取 `--root` 参数、`LYTGOS_ROOT` 环境变量、启动目录。服务默认只绑定回环地址 `127.0.0.1`，把服务暴露到其他网卡不属于当前能力。

```bash
lytgos serve [--port <port>] [--root <path>]
```

| 选项 | 说明 |
| --- | --- |
| `-p`, `--port <port>` | 监听端口，默认 `8989`（避开 8080 / 8081 / 9898） |
| `-r`, `--root <path>` | 工作区根目录，默认当前目录（也认 `LYTGOS_ROOT`） |

示例：

```bash
lytgos serve --port 9090
# REST API:    http://127.0.0.1:9090/api/v1
# 管理台:      http://127.0.0.1:9090/admin/
```

在非工作区目录启动且未指定 `--root` 时，进程以非 0 退出码结束，stderr 只有一行「当前目录不在 lytgos 工作区内，请先执行 lytgos init」，不打印堆栈。

### lytgos gateway（未实现）

> **未实现（路线图）**：当前版本没有 `gateway` 子命令。多渠道常驻进程、定时任务调度随后续 change 交付。

## Agent（Profile）管理

每个 Agent 是 `<工作区>/agents/<name>/` 下的一个目录，内含 `AGENT.md`（YAML frontmatter = 这个 Agent 的 profile：identity/provider/tools/settings；正文 = 任务指令），外加可选的 `skills/`、`scripts/`、`REFERENCE.md`。一个目录 = 一个 Agent——**不再有 `.lytgos/profiles/` 目录**。`profile` 子命令都是轻命令（不启动 Spring），直接读写这些 `AGENT.md`，认 `LYTGOS_ROOT` / `-Dlytgos.root`。

### lytgos profile list

列出 `<工作区>/agents/` 下所有 Agent（每行一个 Agent 目录名）。

```bash
lytgos profile list
```

### lytgos profile create

创建新 Agent，在 `<工作区>/agents/<name>/` 下生成带默认字段的最小 `AGENT.md` 模板；已存在时默认不覆盖，加 `--force` 覆盖。

```bash
lytgos profile create <name>
```

示例：

```bash
lytgos profile create my-agent
# 生成 .lytgos/agents/my-agent/AGENT.md
```

### lytgos profile show

打印指定 Agent 的 `AGENT.md` 完整内容。

```bash
lytgos profile show <name>
```

### lytgos profile delete

删除指定 Agent：整个目录（含 `MEMORY.md`）**物理删除**，不可恢复。默认需要输入 `yes` 二次确认，加 `-y` / `--yes` 跳过确认。

```bash
lytgos profile delete <name>
```

## 查询

### lytgos provider list

列出 `config.yaml` 中声明的 Provider：名字、模型、base URL 与 API key 状态。命令只读配置、不建立任何连接——没设 key 也能看清「配了什么、缺哪个环境变量」。

```bash
lytgos provider list
```

示例输出：

```text
NAME           MODEL                  BASE-URL                               API-KEY
deepseek       deepseek-chat          https://api.deepseek.com               已配置
```

不打印 api-key 的值：终端输出会进 shell 历史、CI 日志与截图，只报「已配置 / 未配置（环境变量名）」。

### lytgos tool list

列出指定 Agent 装配完成后模型实际能看到的工具（`NAME` / `DESCRIPTION` 两列）。默认 Agent 为 `default`，可用 `-a` / `--agent` 指定。打印的是运行时注册表快照，包含该 Agent 声明的 MCP 工具；内置 7 个：`http_get` / `file_read` / `write_file` / `list_dir` / `shell` / `save_memory` / `recall_memory`。

```bash
lytgos tool list [--agent <name>]
```

### lytgos session list（未实现）

> **未实现（路线图）**：当前版本没有 `session` 子命令。会话列表可经 `serve` 的 `GET /api/v1/sessions` 或 `/admin/` 管理台查看。

---

## 用户管理（未实现）

> **未实现（路线图）**：当前版本没有任何 `user` 子命令，`/admin/**` 也没有 Basic Auth。REST 鉴权与 RBAC 随后续 change 交付；本阶段 `serve` 默认只监听 `127.0.0.1`，不对外暴露。

下列命令与行为属于路线图设计，当前 `--help` 中不存在：

### lytgos user add

创建账号。两次提示输入密码（不回显），密码须 ≥8 字符且两次一致。

```bash
lytgos user add <用户名>
```

```bash
lytgos user add admin
# Password (>= 8 chars): ********
# Confirm: ********
# Created user 'admin'
```

### lytgos user list

列出全部账号。**绝不打印密码或哈希。**

```bash
lytgos user list
```

```text
USERNAME                 ENABLED  ROLE             CREATED_AT
admin                    true     VIEWER           2026-07-22T10:30:00Z
alice                    true     EDITOR           2026-07-22T11:00:00Z
```

`ROLE` 列（039 授权）是账号当前角色;一个账号多角色时以逗号分隔,角色字段为空显示 `-`。

### lytgos user role

设置账号角色（039 资源授权）:`VIEWER`（只读）/ `EDITOR`（干活与管资产）/ `ADMIN`（管成员、渠道、策略、工作区设置）,逐级包含,大小写不敏感。**覆盖式**——每次设一个角色,不是追加;非法角色名清晰报错。

```bash
lytgos user role <用户名> <VIEWER|EDITOR|ADMIN>
```

```bash
lytgos user role admin ADMIN
# Role of 'admin' set to ADMIN
```

新建账号默认 `VIEWER`,要干活须显式提档;角色每请求从库重解析、不缓存,改档或撤权在**下一次请求即生效**。如何开启授权、三档各自的边界、以及 API Key 主体的权限上限,见[认证](./auth.md)页的「授权（RBAC）」一节。

### lytgos user passwd

修改账号密码。两次提示输入新密码（不回显）。

```bash
lytgos user passwd <用户名>
```

### lytgos user disable

禁用账号（不删除）。禁用账号无法登录（Basic Auth 返回 401）。

```bash
lytgos user disable <用户名>
```

### lytgos user enable

重新启用已禁用的账号（对称 `disable`）。

```bash
lytgos user enable <用户名>
```

### lytgos user delete

永久删除账号。

```bash
lytgos user delete <用户名>
```
