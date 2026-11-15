package io.lytgos.core.audit;

import java.time.Instant;

/**
 * 一次工具执行的审计行。
 *
 * <p>允许、沙箱拒绝、工具异常、参数不合法都写一行：审计要回答的是「模型让它做了什么」， 而不是「哪些成功了」——被拦下的调用才是安全事件里最需要的那几行。
 *
 * <p>{@code arguments} 保留调用原文并截断到 4000 字符：它是复现问题的唯一输入证据。 脱敏留待后续 Tool Policy（见 change 的 Open
 * Questions）。
 */
public record ToolInvocationRecord(
    String sessionId,
    String turnId,
    String toolName,
    String arguments,
    long durationMs,
    boolean success,
    String errorMessage,
    Instant createdAt) {

  /** 参数原文上限。超长参数（如整份文件内容）只留前缀，避免单行撑爆审计库。 */
  public static final int MAX_ARGUMENT_CHARS = 4000;

  public ToolInvocationRecord {
    sessionId = LlmCallRecord.requireText(sessionId, "sessionId");
    turnId = LlmCallRecord.requireText(turnId, "turnId");
    toolName = LlmCallRecord.requireText(toolName, "toolName");
    arguments = LlmCallRecord.truncate(arguments, MAX_ARGUMENT_CHARS);
    errorMessage = LlmCallRecord.truncate(errorMessage, LlmCallRecord.MAX_ERROR_CHARS);
    createdAt = createdAt == null ? Instant.now() : createdAt;
    if (durationMs < 0) {
      throw new IllegalArgumentException("durationMs 不能为负: " + durationMs);
    }
  }
}
