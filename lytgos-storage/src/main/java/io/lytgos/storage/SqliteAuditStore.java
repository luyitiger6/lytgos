package io.lytgos.storage;

import io.lytgos.core.LytgosException;
import io.lytgos.core.audit.LlmCallAuditor;
import io.lytgos.core.audit.LlmCallRecord;
import io.lytgos.core.audit.ToolInvocationAuditor;
import io.lytgos.core.audit.ToolInvocationRecord;
import io.lytgos.storage.mapper.AuditMapper;
import org.apache.ibatis.exceptions.PersistenceException;

/**
 * 审计表写入的 SQLite 实现（MyBatis Mapper）。
 *
 * <p>模型调用与工具执行共用一个类、两张表：它们共享同一个库与同一个 Spring 管理的数据源， 拆成两个类只会多一次装配。端口仍是两个，按端口注入，调用方看不到这个细节。
 *
 * <p>这里只负责「写进去」，失败直接抛给上层；是否 fail-closed 由装配层的装饰器决定—— 存储不该猜测「写失败要不要中断对话」这种业务口径。
 */
public final class SqliteAuditStore implements LlmCallAuditor, ToolInvocationAuditor {

  private final AuditMapper mapper;

  public SqliteAuditStore(AuditMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public void record(LlmCallRecord record) {
    try {
      mapper.insertLlmCall(
          record.sessionId(),
          record.turnId(),
          record.provider(),
          record.model(),
          record.promptTokens(),
          record.completionTokens(),
          record.totalTokens(),
          record.durationMs(),
          record.success(),
          record.errorMessage(),
          record.createdAt().toString());
    } catch (PersistenceException e) {
      throw new LytgosException("写入 llm_calls 失败: " + e.getMessage(), e);
    }
  }

  @Override
  public void record(ToolInvocationRecord record) {
    try {
      mapper.insertToolInvocation(
          record.sessionId(),
          record.turnId(),
          record.toolName(),
          record.arguments(),
          record.durationMs(),
          record.success(),
          record.errorMessage(),
          record.createdAt().toString());
    } catch (PersistenceException e) {
      throw new LytgosException("写入 tool_invocations 失败: " + e.getMessage(), e);
    }
  }
}
