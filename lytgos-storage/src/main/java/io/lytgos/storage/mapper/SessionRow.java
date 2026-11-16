package io.lytgos.storage.mapper;

/**
 * {@code sessions} 表的一行原始数据：{@code messages} 仍是 JSON 字符串，消息数由 Java 侧解析。
 *
 * <p>不在 SQL 里用 {@code json_array_length}：那依赖 SQLite JSON1 扩展与版本，老库有失败风险；会话量级小，解析放在 Java 更稳。
 */
public record SessionRow(String key, String messages, String updatedAt) {}
