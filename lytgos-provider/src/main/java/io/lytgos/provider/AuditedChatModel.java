package io.lytgos.provider;

import io.lytgos.core.LytgosException;
import io.lytgos.core.audit.LlmCallAuditor;
import io.lytgos.core.audit.LlmCallRecord;
import io.lytgos.core.chat.ChatModel;
import io.lytgos.core.chat.ChatRequest;
import io.lytgos.core.chat.ChatResult;
import io.lytgos.core.trace.TraceContext;
import java.time.Instant;
import java.util.Objects;

/**
 * 给任意 {@link ChatModel} 套一层审计的装饰器。
 *
 * <p>为什么是装饰器而不是新增 ProviderService / 改 SpringAiChatModel 内部：模型调用只能走 {@code ChatModel} 这一条约束（{@code
 * AGENTS.md} 4.1）不允许出现第二个入口；而 provider 模块 也不该为了写审计去依赖存储实现——这里只依赖 core 的审计端口。
 *
 * <p>成功与失败都写一行：失败时先落库再把原异常原样上抛，异常类型与消息不变， 调用方无需为了审计改变任何捕获逻辑。
 *
 * <p>写库失败包装成 {@link LytgosException.AuditWriteException} 并覆盖原异常继续上抛：
 * 审计不可写时，本轮对话必须显性失败，而不是「业务成功、审计丢行」。
 */
public final class AuditedChatModel implements ChatModel {

  private final ChatModel delegate;
  private final String provider;
  private final String model;
  private final LlmCallAuditor auditor;

  public AuditedChatModel(
      ChatModel delegate, String provider, String model, LlmCallAuditor auditor) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
    this.provider = Objects.requireNonNull(provider, "provider");
    this.model = Objects.requireNonNull(model, "model");
    this.auditor = Objects.requireNonNull(auditor, "auditor");
  }

  @Override
  public ChatResult call(ChatRequest request) {
    long startNanos = System.nanoTime();
    ChatResult result = null;
    RuntimeException failure = null;
    try {
      result = delegate.call(request);
    } catch (RuntimeException e) {
      failure = e;
    }
    long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
    ChatResult.Usage usage = result == null ? ChatResult.Usage.ZERO : result.usage();

    LlmCallRecord record =
        new LlmCallRecord(
            request.sessionId(),
            request.turnId(),
            // 与日志同一个 traceId：取自 MDC，无请求上下文时（后台任务、直接调用）合法为 null。
            TraceContext.current(),
            provider,
            model,
            usage.promptTokens(),
            usage.completionTokens(),
            usage.totalTokens(),
            durationMs,
            failure == null,
            failure == null ? null : summarize(failure),
            Instant.now());
    try {
      auditor.record(record);
    } catch (RuntimeException auditError) {
      throw new LytgosException.AuditWriteException(
          "审计写入失败（llm_calls），本轮已中止: " + auditError.getMessage(), auditError);
    }

    if (failure != null) {
      throw failure;
    }
    return result;
  }

  private static String summarize(RuntimeException failure) {
    String message = failure.getMessage();
    return failure.getClass().getSimpleName() + (message == null ? "" : ": " + message);
  }
}
