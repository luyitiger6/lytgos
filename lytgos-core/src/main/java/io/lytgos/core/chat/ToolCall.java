package io.lytgos.core.chat;

/**
 * 模型发起的一次工具调用请求。
 *
 * <p>{@code arguments} 是模型给出的 JSON 字符串，未经解析；
 * 解析与校验由 {@link ToolExecutor} 负责，模型不直接接触 Java 对象。</p>
 */
public record ToolCall(String id, String name, String arguments) {
}
