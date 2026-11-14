# Spec Delta

## Purpose

定义 lytgos 内置文件与命令工具的行为与安全边界：`file_read` / `write_file` / `list_dir` / `shell` 全部经 `SandboxChecker` 校验，为 Agent 提供受控的工作区读写与命令执行能力。

## ADDED Requirements

### Requirement: 文件读取

系统 SHALL 提供 `file_read`，读取工作区内的文本文件并返回内容；路径 MUST 经工作区沙箱校验；内容超过回填上限时 MUST 截断并标注原始长度。

#### Scenario: 读取工作区文件
- **WHEN** 模型调用 `file_read`，路径为 `MEMORY.md` 或 `memory/MEMORY.md`
- **THEN** 返回文件全文，超出 8000 字符时截断并标注原始长度

#### Scenario: 读取越界路径被拒绝
- **WHEN** 路径为 `../../etc/passwd` 或指向工作区外的符号链接
- **THEN** 沙箱判定越界，调用被拒绝

### Requirement: 文件写入

系统 SHALL 提供 `write_file`，接收相对路径与内容，在沙箱校验通过后创建或覆盖文件；父目录不存在时 MUST 在工作区内创建；成功时 MUST 返回路径与写入字节数。对 `agents/*/AGENT.md` 与根 `config.yaml` 的写入 MUST 被拒绝；内容超过 64KB MUST 拒绝。

#### Scenario: 写入后可回读
- **WHEN** 调用 `write_file` 写入 `output/notes.txt`，内容为 `hello`
- **THEN** 返回成功文本含路径与 5 字节，随后 `file_read` 能读回 `hello`

#### Scenario: 越界写入被拒绝
- **WHEN** `write_file` 的路径指向工作区外
- **THEN** 沙箱拒绝，文件未被创建

#### Scenario: 受保护文件不可改写
- **WHEN** 路径为 `agents/default/AGENT.md` 或 `config.yaml`
- **THEN** 拒绝写入并说明受保护原因，原文件字节不变

#### Scenario: 超长内容被拒绝
- **WHEN** 内容超过 64KB
- **THEN** 拒绝写入并返回上限说明，文件未发生变化

### Requirement: 目录列举

系统 SHALL 提供 `list_dir`，接收相对路径（缺省为工作区根），返回直接子项并按名称排序，目录名以 `/` 结尾；条目超过 200 条时 MUST 截断并标注。路径 MUST 经沙箱校验；目标不是目录时 MUST 返回可读的失败文本。

#### Scenario: 列出目录内容
- **WHEN** 调用 `list_dir`，路径为工作区根
- **THEN** 返回条目包含 `agents/`、`memory/`、`output/`，目录名以 `/` 结尾

#### Scenario: 非目录路径返回失败文本
- **WHEN** `list_dir` 的路径指向一个普通文件
- **THEN** 返回「不是目录」类文本，本轮对话继续

### Requirement: Shell 命令执行

系统 SHALL 提供 `shell` 工具，接收 `command` 字符串；执行前 MUST 用命令白名单校验可执行文件名，白名单为空时 MUST 拒绝，并向模型展示当前允许的命令列表。命令 MUST 由 `ProcessBuilder` 直接执行、不经过系统 shell；超过 15 秒 MUST 终止进程及其子进程并返回超时文本；合并输出超过 8000 字符 MUST 截断并标注。

#### Scenario: 白名单内命令执行
- **WHEN** 白名单含 `java`，调用 `shell` 执行 `java -version`
- **THEN** 返回文本包含 `version`，退出信息可读

#### Scenario: 白名单外命令被拒绝
- **WHEN** 白名单不含 `rm`，调用 `shell` 执行 `rm -rf /`
- **THEN** 沙箱违规抛出，本轮对话中断，命令未执行

#### Scenario: 超时终止进程树
- **WHEN** 白名单内的命令持续运行超过 15 秒
- **THEN** 进程及其子进程被终止，返回超时文本，无残留进程

#### Scenario: 不经过系统 shell
- **WHEN** 白名单含 `java`，执行 `java -version; echo injected`
- **THEN** `;` 与后续片段按普通参数传给 `java`，`injected` 不会作为独立命令执行

### Requirement: 工具注册与白名单筛选

新工具 MUST 与既有内置工具走同一注册路径：出现在 `lytgos tool list` 输出中；`Profile.tools` 非空时只保留列出的工具，声明了未注册工具时 MUST 启动失败并列出可用工具。

#### Scenario: 工具清单可见
- **WHEN** 执行 `lytgos tool list`
- **THEN** 输出包含 `file_read`、`write_file`、`list_dir`、`shell`、`http_get`

#### Scenario: 按 Agent 白名单筛选
- **WHEN** `AGENT.md` 的 `tools` 为 `[file_read, save_memory]`
- **THEN** 该 Agent 的注册表只含这两个工具，声明未注册工具时启动失败
