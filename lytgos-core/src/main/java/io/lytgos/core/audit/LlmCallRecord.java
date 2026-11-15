package io.lytgos.core.audit;

import java.time.Instant;

/**
 * 一次模型调用的审计行。
 *
 * <p>{@code sessionId} 与 {@code turnId} 是审计不变量 I1/I3 的关联键：前者对应 {@code sessions.key}，
 * 后者在同一轮对话内保持不变，让「模型调用」与「工具执行」能按一次用户输入聚到一起。
 *
 * <p>截断放在构造器里而不是存储实现里：审计行是唯一证据，任何写入路径都不该拿到超长原文—— 否则换一个存储实现就悄悄换了口径。
 */
public record LlmCallRecord(
    String sessionId,
    String turnId,
    String provider,
    String model,
    int promptTokens,
    int completionTokens,
    int totalTokens,
    long durationMs,
    boolean success,
    String errorMessage,
    Instant createdAt) {

  /** 错误摘要上限。完整堆栈在日志里，审计表只留可读摘要。 */
  public static final int MAX_ERROR_CHARS = 1000;

  public LlmCallRecord {
    sessionId = requireText(sessionId, "sessionId");
    turnId = requireText(turnId, "turnId");
    provider = requireText(provider, "provider");
    model = requireText(model, "model");
    errorMessage = truncate(errorMessage, MAX_ERROR_CHARS);
    createdAt = createdAt == null ? Instant.now() : createdAt;
    if (durationMs < 0) {
      throw new IllegalArgumentException("durationMs 不能为负: " + durationMs);
    }
  }

  static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " 不能为空");
    }
    return value;
  }

  static String truncate(String value, int max) {
    if (value == null) {
      return null;
    }
    return value.length() <= max ? value : value.substring(0, max);
  }
}
