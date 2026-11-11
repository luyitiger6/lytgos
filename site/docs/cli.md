# CLI Reference

The `lytgos` command provides sub-commands for workspace management, profile operations, interactive chat, and server control. All commands are implemented with Picocli and run on Java 21.

```bash
lytgos <command> [options]
```

**Global flags** (accepted by all commands):

| Flag | Description |
|------|-------------|
| `--help`, `-h` | Show usage for the command |
| `--version`, `-V` | Print version plus JVM/OS info |

---

## Workspace root

By default lytgos reads and writes the `.lytgos` workspace in the current directory. The root is configurable:

| Mechanism | Applies to | Notes |
|-----------|-----------|-------|
| `LYTGOS_ROOT` (env var) | all commands | Light commands read it directly; `serve`/`gateway` pick it up through Spring relaxed binding |
| `-Dlytgos.root=<path>` (JVM system property) | all commands | Highest precedence for light commands |
| `lytgos.root` in `application.yml` | `serve` / `gateway` only | Not read by the light commands (`init`/`status`/`profile`) — they never boot Spring, so use the env var or system property for them |

Resolution order for the light commands is: `-Dlytgos.root` → `LYTGOS_ROOT` → default `.lytgos`. The configured root is auto-added to the file sandbox whitelist at runtime, so relocating the workspace never breaks file tools.

```bash
# Point every command at a custom workspace
LYTGOS_ROOT=/data/ws lytgos init
LYTGOS_ROOT=/data/ws lytgos status
```

---

## Getting started

### init

Initialize the workspace (default `.lytgos/`) in the current directory. Creates the directory structure and the default bootstrap files. Safe to run multiple times — existing files are never overwritten. This is a light command: it does not boot Spring, and it honors `LYTGOS_ROOT` / `-Dlytgos.root`.

```bash
lytgos init
# Or into a custom workspace root:
LYTGOS_ROOT=/data/ws lytgos init
```

What gets created:

```text
.lytgos/
├── agents/       # one sub-directory per agent (AGENT.md + optional skills/ scripts/ REFERENCE.md)
├── memory/       # global long-term memory (per-agent MEMORY.md lives under agents/<name>/)
├── sessions/     # reserved (session state lives in SQLite)
├── logs/
├── AGENTS.md     # bootstrap: project-level agent behavior
├── SOUL.md       # bootstrap: agent persona
└── USER.md       # bootstrap: user preferences (read-only)
```

The `lytgos.db` SQLite database is created on first run of a heavy command (`serve`/`gateway`/`chat`), not by `init`.

### status

Print the workspace and data-file state: whether the workspace is initialized, how many agents live under `agents/`, and whether the SQLite database exists. Like `init`, this is a light command — it does not boot Spring and honors `LYTGOS_ROOT` / `-Dlytgos.root`.

```bash
lytgos status
```

```text
Workspace .lytgos/  : initialized
Agent directory     : 3
SQLite database     : lytgos.db present
```

---

## Chat

### chat

Start an interactive multi-turn conversation with an agent. Runs a ReAct Loop on each message and prints the final response to the terminal. Type `/quit` or press `Ctrl-D` to end the session.

```bash
lytgos chat [--profile <name>]
```

| Flag | Default | Description |
|------|---------|-------------|
| `--profile <name>` | `default` | Profile to use for this session |

Example:

```bash
lytgos chat --profile ops-agent
```

```text
[ops-agent] > check disk usage
[tool: shell] df -h
/dev/sda1  200G  84G  116G  42%  /
Disk usage on /dev/sda1 is 42% (84G of 200G used). You have 116G free.

[ops-agent] > /quit
Session archived. Goodbye.
```

The session is persisted to SQLite and restored on subsequent `chat` invocations with the same profile and user context.

---

## Server

### serve

Start the HTTP API server. It exposes the REST API under `/api/v1` and serves the web admin console at `/admin/`. Runs synchronously with Java 21 virtual threads — no separate thread pool tuning required. As a heavy command, `serve` boots Spring and reads its workspace root from `lytgos.root` in `application.yml` (Spring relaxed binding also honors the `LYTGOS_ROOT` env var).

```bash
lytgos serve [--port <port>]
```

| Flag | Default | Description |
|------|---------|-------------|
| `--port <port>` | `8080` | TCP port to bind |

```bash
lytgos serve --port 9090
```

```text
lytgos API server starting...
Listening on http://0.0.0.0:9090
Admin console: http://0.0.0.0:9090/admin/
```

Press `Ctrl-C` to stop.

### gateway

Start lytgos in daemon mode (full runtime, resident). Intended for production multi-agent deployments; multi-channel mounting is an extension-phase feature. Like `serve`, it boots Spring and reads its workspace root from `lytgos.root` (env `LYTGOS_ROOT` honored via relaxed binding). Press `Ctrl-C` to stop.

```bash
lytgos gateway
```

---

## Agent (profile) management

Each agent is one directory under `<workspace>/agents/<name>/`, holding an `AGENT.md` (YAML frontmatter = the agent's profile: identity, provider, tools, settings; body = task instructions) plus optional `skills/`, `scripts/`, and `REFERENCE.md`. One directory = one agent — there is no `.lytgos/profiles/` directory. The `profile` sub-commands are light (no Spring) and read/write these `AGENT.md` files directly, honoring `LYTGOS_ROOT` / `-Dlytgos.root`.

### profile list

List all agents found under `<workspace>/agents/` (one line per agent directory).

```bash
lytgos profile list
```

```text
default
ops-agent
reviewer
```

### profile create

Scaffold a new agent directory with a minimal `AGENT.md` template. Existing agents are not overwritten.

```bash
lytgos profile create <name>
```

```bash
lytgos profile create code-reviewer
# Created .lytgos/agents/code-reviewer/AGENT.md
```

### profile show

Print the full contents of an agent's `AGENT.md`.

```bash
lytgos profile show <name>
```

```bash
lytgos profile show ops-agent
```

### profile delete

Archive an agent's entire directory into `<workspace>/archive/` (nothing is physically deleted).

```bash
lytgos profile delete <name>
```

---

## Inspection

### provider list

List the provider registry this instance actually runs with. The light CLI command queries the same database as the runtime — the `providers` table in `lytgos.db` by default, or the datasource configured in `config/application.yml` — and prints each provider's name, derived default marker, base URL and description. The packaged `application.yml` only seeds providers missing from the database on first startup; after that the database is the single source of truth. Providers added or deleted in the admin console (`/api/v1/providers`) show up here accordingly.

```bash
lytgos provider list
```

```text
Provider 注册表（lytgos.db）：
NAME                 DEFAULT  BASE-URL                                             DESCRIPTION
deepseek             *        https://api.deepseek.com                             默认 DeepSeek
mock                          -                                                    内置假模型
qwen                          https://dashscope.aliyuncs.com/compatible-mode/v1    通义
default(*) 为运行时派生（注册表内按大小写无关排序的最小名），不是库字段。
```

The command exits non-zero when the database is unreachable, uninitialized, or missing the `providers` table; it never falls back to the packaged YAML.

### tool list

List the built-in tools. Once the `ToolRegistry` is wired at runtime this reflects the live registry (built-in plus MCP tools).

```bash
lytgos tool list
```

```text
Built-in tools:
  read_file
  write_file
  list_dir
  shell
  http_get
  http_post
  save_memory
  recall_memory
  notify
```

### session list

List sessions stored in the SQLite database (`lytgos.db` in the current directory), newest first. Columns: session id, profile name, status, last active time.

```bash
lytgos session list
```

```text
cli:user-001:ops-agent                   ops-agent        active    2025-06-01 10:05
cli:user-002:default                      default          active    2025-06-01 09:30
```

---

## User management

Manage admin accounts for the management console (`/admin/**`) Basic Auth. Accounts are stored in the `web_users` SQLite table; passwords are BCrypt-hashed and never stored in plaintext. Changes take effect immediately — no server restart is required for new accounts to work.

> Basic Auth is opt-in. It is disabled by default (`lytgos.web.auth.enabled=false`, assuming a trusted internal network). Enable it in `config/application.yml`, then create at least one account before starting `serve` — otherwise startup is blocked with a clear error.

### user add

Create an account. Prompts for a password twice (input is not echoed); the password must be at least 8 characters and the two entries must match.

```bash
lytgos user add <username>
```

```bash
lytgos user add admin
# Password (>= 8 chars): ********
# Confirm: ********
# Created user 'admin'
```

### user list

List all accounts. **Never prints passwords or hashes.**

```bash
lytgos user list
```

```text
USERNAME                 ENABLED  ROLE             CREATED_AT
admin                    true     VIEWER           2026-07-22T10:30:00Z
alice                    true     EDITOR           2026-07-22T11:00:00Z
```

The `ROLE` column (039 authorization) is the account's current role; an account holding several roles shows them comma-separated, and an empty role field shows `-`.

### user role

Set an account's role (039 authorization): `VIEWER` (read-only) / `EDITOR` (do work and manage assets) / `ADMIN` (manage members, channels, policies, workspace settings). They nest, and the value is case-insensitive. The command **replaces** the role — one role per invocation, never appended; an invalid role name fails with a clear error.

```bash
lytgos user role <username> <VIEWER|EDITOR|ADMIN>
```

```bash
lytgos user role admin ADMIN
# Role of 'admin' set to ADMIN
```

New accounts default to `VIEWER`, so doing real work requires an explicit promotion. Roles are re-resolved from the database on every request with no caching, so a change or revocation takes effect on the **very next request**. For how to turn authorization on, what each tier may do, and the permission ceiling on API keys, see the "Authorization (RBAC)" section of [Authentication](./auth.md).

### user passwd

Change an account's password. Prompts for the new password twice (not echoed).

```bash
lytgos user passwd <username>
```

### user disable

Disable an account without deleting it. A disabled account cannot log in (Basic Auth returns 401).

```bash
lytgos user disable <username>
```

### user enable

Re-enable a previously disabled account (symmetric to `disable`).

```bash
lytgos user enable <username>
```

### user delete

Delete an account permanently.

```bash
lytgos user delete <username>
```
