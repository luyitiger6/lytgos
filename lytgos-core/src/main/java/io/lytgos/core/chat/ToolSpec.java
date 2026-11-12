package io.lytgos.core.chat;

/**
 * 交给模型的工具声明。
 *
 * <p>{@code inputSchema} 是 JSON Schema 字符串，原样透传给模型；
 * 本类不做 schema 校验，也不关心工具如何执行。</p>
 */
public record ToolSpec(String name, String description, String inputSchema) {
}
