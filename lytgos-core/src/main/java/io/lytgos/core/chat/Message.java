package io.lytgos.core.chat;

import java.util.List;

/**
 * 对话中的一条消息。
 *
 * <p>两个字段专为工具调用而存在，缺一不可：</p>
 * <ul>
 *   <li>{@code toolCalls} 属于 {@code ASSISTANT} 消息——模型当时给出的完整调用（含参数）
 *       必须留在历史里。只记「调过 http_get」不够，下一轮它需要看到自己当时的参数
 *       才能判断上次为什么失败。</li>
 *   <li>{@code toolCallId} 属于 {@code TOOL} 消息——把结果与发起它的那条调用配对，
 *       上游协议靠它定位，配错一个 id 整个请求会被拒。</li>
 * </ul>
 */
public record Message(Role role, String content, List<ToolCall> toolCalls, String toolCallId, String toolName) {

    /** 消息角色。 */
    public enum Role {
        SYSTEM, USER, ASSISTANT, TOOL
    }

    public Message {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public static Message system(String content) {
        return new Message(Role.SYSTEM, content, List.of(), null, null);
    }

    public static Message user(String content) {
        return new Message(Role.USER, content, List.of(), null, null);
    }

    public static Message assistant(String content) {
        return new Message(Role.ASSISTANT, content, List.of(), null, null);
    }

    public static Message assistant(String content, List<ToolCall> toolCalls) {
        return new Message(Role.ASSISTANT, content, toolCalls, null, null);
    }

    public static Message tool(String toolCallId, String toolName, String content) {
        return new Message(Role.TOOL, content, List.of(), toolCallId, toolName);
    }

    public boolean hasToolCalls() {
        return !toolCalls.isEmpty();
    }
}

