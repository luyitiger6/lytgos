package io.lytgos.core.chat;

import java.util.List;

/**
 * 一次模型调用的入参。
 *
 * <p>{@code sessionId} 与 {@code turnId} 是审计主键的一部分，所以刻意不提供两参便捷构造器： 缺省值会把「调用方忘传」变成一条 session
 * 为空的审计记录，这比编译错误难查得多。
 *
 * @param messages 完整对话历史，首条约定为 {@code SYSTEM}
 * @param tools 本次可见的工具声明，可以为空
 * @param sessionId 会话键，与 {@code sessions.key} 一致
 * @param turnId 本轮对话标识，同一轮内的多次模型调用与工具执行共享
 * @param temperature 请求级温度；{@code null} 表示不向上游传，由 provider 默认值决定
 */
public record ChatRequest(
    List<Message> messages,
    List<ToolSpec> tools,
    String sessionId,
    String turnId,
    Double temperature) {

  public ChatRequest {
    messages = List.copyOf(messages);
    tools = tools == null ? List.of() : List.copyOf(tools);
    if (sessionId == null || sessionId.isBlank()) {
      throw new IllegalArgumentException("sessionId 不能为空");
    }
    if (turnId == null || turnId.isBlank()) {
      throw new IllegalArgumentException("turnId 不能为空");
    }
  }
}
