package io.lytgos.core.chat;

import java.util.List;

/**
 * 一次模型调用的结果。
 *
 * <p>{@code content} 与 {@code toolCalls} 可能同时存在：模型可以边给文本边发起调用。 只有 {@code toolCalls} 为空时，{@code
 * content} 才是最终答复。
 *
 * <p>{@code usage} 上游未给出时为零值而不是 null：审计表的三列 token 都是 NOT NULL， "没有数据"与"用了 0 个 token"对上游而言就是同一件事。
 */
public record ChatResult(String content, List<ToolCall> toolCalls, Usage usage) {

  public ChatResult {
    toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    usage = usage == null ? Usage.ZERO : usage;
  }

  /** 两参便捷构造器：仅用于测试替身与「没有用量信息」的旧调用点。 */
  public ChatResult(String content, List<ToolCall> toolCalls) {
    this(content, toolCalls, Usage.ZERO);
  }

  public boolean hasToolCalls() {
    return !toolCalls.isEmpty();
  }

  /** 上游报告的 token 用量。 */
  public record Usage(int promptTokens, int completionTokens, int totalTokens) {

    public static final Usage ZERO = new Usage(0, 0, 0);

    public Usage {
      if (promptTokens < 0 || completionTokens < 0 || totalTokens < 0) {
        throw new IllegalArgumentException("token 用量不能为负");
      }
    }
  }
}
