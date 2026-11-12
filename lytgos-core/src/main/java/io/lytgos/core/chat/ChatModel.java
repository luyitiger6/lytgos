package io.lytgos.core.chat;

/**
 * 模型调用的唯一抽象。
 *
 * <p>实现方负责协议转换与请求发送，但 MUST NOT 执行工具：
 * 工具执行只允许由 {@link io.lytgos.core.tool.ToolExecutor} 发起。检测到工具调用时，
 * 实现方只负责把调用原样返回，由调用方决定执行顺序与治理策略。</p>
 *
 * <p>这是 {@code AGENTS.md} 4.1「禁止自动工具执行」的落点——
 * 换成任何会自行驱动工具循环的实现（例如 Spring AI 的 {@code ChatClient}），
 * 配额、白名单、沙箱、审计都会脱离执行路径。</p>
 */
public interface ChatModel {

    ChatResult call(ChatRequest request);
}

