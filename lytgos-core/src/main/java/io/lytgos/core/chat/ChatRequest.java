package io.lytgos.core.chat;

import java.util.List;

/**
 * 一次模型调用的入参。
 *
 * @param messages 完整对话历史，首条约定为 {@code SYSTEM}
 * @param tools    本次可见的工具声明，可以为空
 */
public record ChatRequest(List<Message> messages, List<ToolSpec> tools) {

    public ChatRequest {
        messages = List.copyOf(messages);
        tools = tools == null ? List.of() : List.copyOf(tools);
    }
}
