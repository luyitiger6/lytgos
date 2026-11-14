# Spec Delta

## Purpose

定义 lytgos 的长期记忆最小闭环：以工作区内 `MEMORY.md` 为唯一载体，启动时注入系统提示词，由 `save_memory` / `recall_memory` 两个工具显式读写，让 Agent 跨进程记住用户偏好。

## ADDED Requirements

### Requirement: 记忆文件与启动注入

系统 SHALL 以工作区 `memory/MEMORY.md` 作为长期记忆的唯一载体，并在每次启动组装系统提示词时读取全文注入。文件缺失或内容为空时 MUST 跳过注入且不阻断对话；内容超过 8000 字符时 MUST 截断并标注，不得导致失败。

#### Scenario: 记住偏好后新进程可用
- **WHEN** 先用 `save_memory` 保存「我用 Spring Boot」，结束进程后再启动新对话问「我用什么技术栈」
- **THEN** 回答包含「Spring Boot」，无需模型再次调用工具

#### Scenario: 记忆文件缺失不阻断对话
- **WHEN** 删除 `memory/MEMORY.md` 后执行 `lytgos chat --message "你好"`
- **THEN** 对话正常完成，退出码 0，日志无错误

#### Scenario: 超长记忆截断注入
- **WHEN** `MEMORY.md` 内容超过 8000 字符
- **THEN** 注入内容只包含前 8000 字符并带截断标注，对话不失败

### Requirement: save_memory 工具

系统 SHALL 提供 `save_memory` 内置工具，接收 `content` 参数并追加一条记录到 `memory/MEMORY.md`；文件不存在时 MUST 创建。写入前 MUST 校验结果长度不超过上限，超限时 MUST 整体拒绝并返回含上限值与清理建议的文本，不得部分写入。

#### Scenario: 保存一条记忆
- **WHEN** 模型调用 `save_memory`，`content` 为「我用 Spring Boot」
- **THEN** `MEMORY.md` 中出现该条目，工具返回包含成功与当前大小的文本

#### Scenario: 超过上限拒绝写入
- **WHEN** `MEMORY.md` 已接近上限，再保存一条会超限的内容
- **THEN** 工具返回拒绝文本（含 8000 上限与清理建议），文件字节数保持不变

### Requirement: recall_memory 工具

系统 SHALL 提供 `recall_memory` 内置工具，返回 `memory/MEMORY.md` 当前内容（受同一上限约束）。文件不存在或为空时 MUST 返回「暂无记忆」类文本，不得抛错中断对话。

#### Scenario: 读回已保存内容
- **WHEN** 已保存「我用 Spring Boot」后调用 `recall_memory`
- **THEN** 返回文本包含该条目

#### Scenario: 空记忆可读
- **WHEN** `memory/MEMORY.md` 不存在时调用 `recall_memory`
- **THEN** 返回「暂无记忆」文本，本轮对话继续

### Requirement: 记忆只由显式写入产生

系统 MUST NOT 自动把每轮用户消息或模型回复写入 `MEMORY.md`；只有 `save_memory` 或用户手工编辑会改变记忆内容。

#### Scenario: 普通对话不改写记忆
- **WHEN** 执行一轮不含工具调用的 `lytgos chat`
- **THEN** `MEMORY.md` 的字节内容在对话前后完全一致
