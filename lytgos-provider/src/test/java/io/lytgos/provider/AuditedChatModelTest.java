package io.lytgos.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.lytgos.core.LytgosException;
import io.lytgos.core.audit.LlmCallRecord;
import io.lytgos.core.chat.ChatModel;
import io.lytgos.core.chat.ChatRequest;
import io.lytgos.core.chat.ChatResult;
import io.lytgos.core.chat.Message;
import io.lytgos.core.trace.TraceContext;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

@DisplayName("AuditedChatModel：成功与失败都落审计")
class AuditedChatModelTest {

  private static final String SESSION = "cli:local:tester";
  private static final String TURN = "turn-1";

  @Test
  @DisplayName("成功调用落一行，token 用量与耗时来自结果")
  void recordsSuccessWithUsage() {
    List<LlmCallRecord> records = new ArrayList<>();
    ChatModel delegate =
        request -> new ChatResult("ok", List.of(), new ChatResult.Usage(100, 20, 120));
    AuditedChatModel model =
        new AuditedChatModel(delegate, "deepseek", "deepseek-flash", records::add);

    ChatResult result = model.call(request());

    assertEquals("ok", result.content());
    assertEquals(1, records.size());
    LlmCallRecord record = records.get(0);
    assertTrue(record.success());
    assertEquals(SESSION, record.sessionId());
    assertEquals(TURN, record.turnId());
    assertEquals("deepseek", record.provider());
    assertEquals(100, record.promptTokens());
    assertEquals(120, record.totalTokens());
    assertTrue(record.durationMs() >= 0);
  }

  @Test
  @DisplayName("上游未给用量时 token 记 0，不抛异常")
  void missingUsageFallsBackToZero() {
    List<LlmCallRecord> records = new ArrayList<>();
    AuditedChatModel model =
        new AuditedChatModel(
            request -> new ChatResult("ok", List.of()), "deepseek", "deepseek-flash", records::add);

    model.call(request());

    assertEquals(0, records.get(0).promptTokens());
    assertEquals(0, records.get(0).completionTokens());
    assertEquals(0, records.get(0).totalTokens());
  }

  @Test
  @DisplayName("失败调用先落库再把原异常原样上抛")
  void recordsFailureThenRethrows() {
    List<LlmCallRecord> records = new ArrayList<>();
    AuditedChatModel model =
        new AuditedChatModel(
            request -> {
              throw new IllegalStateException("上游 500");
            },
            "deepseek",
            "deepseek-flash",
            records::add);

    IllegalStateException error =
        assertThrows(IllegalStateException.class, () -> model.call(request()));

    assertEquals("上游 500", error.getMessage());
    assertEquals(1, records.size());
    assertFalse(records.get(0).success());
    assertTrue(records.get(0).errorMessage().contains("上游 500"));
  }

  @Test
  @DisplayName("审计写入失败抛 AuditWriteException，覆盖业务结果")
  void auditFailureBecomesAuditWriteException() {
    AuditedChatModel model =
        new AuditedChatModel(
            request -> new ChatResult("ok", List.of()),
            "deepseek",
            "deepseek-flash",
            record -> {
              throw new IllegalStateException("磁盘只读");
            });

    LytgosException.AuditWriteException error =
        assertThrows(LytgosException.AuditWriteException.class, () -> model.call(request()));

    assertTrue(error.getMessage().contains("磁盘只读"), error.getMessage());
  }

  @Test
  @DisplayName("MDC 里有 traceId 时，审计行携带同一个值")
  void carriesTraceIdFromMdc() {
    List<LlmCallRecord> records = new ArrayList<>();
    AuditedChatModel model =
        new AuditedChatModel(
            request -> new ChatResult("ok", List.of()), "deepseek", "deepseek-flash", records::add);

    MDC.put(TraceContext.MDC_KEY, "req-42");
    try {
      model.call(request());
    } finally {
      MDC.remove(TraceContext.MDC_KEY);
    }

    assertEquals("req-42", records.get(0).traceId(), "审计行必须能用 traceId 接回同一请求的日志");
  }

  @Test
  @DisplayName("没有请求上下文时 traceId 写 null，审计照常落库")
  void missingTraceIdDoesNotBlockAudit() {
    List<LlmCallRecord> records = new ArrayList<>();
    AuditedChatModel model =
        new AuditedChatModel(
            request -> new ChatResult("ok", List.of()), "deepseek", "deepseek-flash", records::add);

    model.call(request());

    assertEquals(1, records.size(), "取不到 trace 不能阻止审计写入");
    assertNull(records.get(0).traceId(), "无请求上下文时 traceId 合法为空");
  }

  private static ChatRequest request() {
    return new ChatRequest(List.of(Message.user("你好")), List.of(), SESSION, TURN, null);
  }
}
