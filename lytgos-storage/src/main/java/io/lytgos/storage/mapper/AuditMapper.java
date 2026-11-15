package io.lytgos.storage.mapper;

import org.apache.ibatis.annotations.Param;

/**
 * 审计两表的追加写。
 *
 * <p>审计只增不改：两条 INSERT 是全部写操作，没有查询接口——读审计是运营侧需求， 走 SQL 直查或后续独立 change，不在本轮范围。
 *
 * <p>时间字段以 ISO-8601 字符串传入（{@code Instant.toString()}），与首版纯 JDBC 写入的格式保持一致，已有库文件不需要迁移。
 */
public interface AuditMapper {

  int insertLlmCall(
      @Param("sessionId") String sessionId,
      @Param("turnId") String turnId,
      @Param("traceId") String traceId,
      @Param("provider") String provider,
      @Param("model") String model,
      @Param("promptTokens") int promptTokens,
      @Param("completionTokens") int completionTokens,
      @Param("totalTokens") int totalTokens,
      @Param("durationMs") long durationMs,
      @Param("success") boolean success,
      @Param("errorMessage") String errorMessage,
      @Param("createdAt") String createdAt);

  int insertToolInvocation(
      @Param("sessionId") String sessionId,
      @Param("turnId") String turnId,
      @Param("traceId") String traceId,
      @Param("toolName") String toolName,
      @Param("arguments") String arguments,
      @Param("durationMs") long durationMs,
      @Param("success") boolean success,
      @Param("errorMessage") String errorMessage,
      @Param("createdAt") String createdAt);
}
