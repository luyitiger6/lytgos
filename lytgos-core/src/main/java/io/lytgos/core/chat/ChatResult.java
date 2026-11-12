package io.lytgos.core.chat;

import java.util.List;

/**
 * 一次模型调用的结果。
 *
 * <p>{@code content} 与 {@code toolCalls} 可能同时存在：模型可以边给文本边发起调用。
 * 只有 {@code toolCalls} 为空时，{@code content} 才是最终答复。</p>
 */
public record ChatResult(String content, List<ToolCall> toolCalls) {

    public ChatResult {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }
}
