package io.lytgos.core.session;

/**
 * 会话列表条目：只带「列表要展示什么」，不带历史正文。
 *
 * <p>列表端点与管理台只需要标识、消息数与最后更新时间；把整段历史塞进列表会随会话数线性放大响应体。要正文请按 key 单独取 {@link SessionStore#get(String)}。
 *
 * <p>{@code updatedAt} 保持存储层写入的原样字符串（ISO-8601），不在这一层做时区或格式转换——两种实现的格式要能被同一个调用方消费。
 */
public record SessionSummary(String key, int messageCount, String updatedAt) {}
