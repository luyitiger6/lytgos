package io.lytgos.core.tool;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lytgos.core.LytgosException;
import io.lytgos.core.audit.ToolInvocationAuditor;
import io.lytgos.core.audit.ToolInvocationRecord;
import io.lytgos.core.chat.ToolCall;
import io.lytgos.core.chat.ToolSpec;
import io.lytgos.core.trace.TraceContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

@DisplayName("ToolExecutor：失败按归属分两条路")
class ToolExecutorTest {

  private static final String SESSION = "cli:local:tester";
  private static final String TURN = "turn-1";

  @FunctionalInterface
  private interface Body {
    String run(Map<String, Object> arguments);
  }

  /** 记录每一次审计写入，用来断言「成功、失败、被拦下都留一行」。 */
  private static final class RecordingAuditor implements ToolInvocationAuditor {

    private final List<ToolInvocationRecord> records = new ArrayList<>();

    @Override
    public void record(ToolInvocationRecord record) {
      records.add(record);
    }
  }

  private static ToolExecutor executorWith(Tool tool, ToolInvocationAuditor auditor) {
    return new ToolExecutor(new ToolRegistry(List.of(tool)), new ObjectMapper(), auditor);
  }

  private static Tool probe(Body body) {
    return new Tool() {
      @Override
      public ToolSpec spec() {
        return new ToolSpec("probe", "测试用工具", "{\"type\":\"object\"}");
      }

      @Override
      public String execute(Map<String, Object> arguments) {
        return body.run(arguments);
      }
    };
  }

  private static ToolCall call(String json) {
    return new ToolCall("call_1", "probe", json);
  }

  private static ToolInvocationRecord onlyRecord(RecordingAuditor auditor) {
    assertEquals(1, auditor.records.size(), "一次执行必须且只能写一行 tool_invocations");
    return auditor.records.get(0);
  }

  @Test
  @DisplayName("成功执行也写一行，成功标记与错误信息符合契约")
  void recordsSuccessfulInvocation() {
    RecordingAuditor auditor = new RecordingAuditor();
    ToolExecutor executor = executorWith(probe(a -> "ok"), auditor);

    String output = executor.execute(SESSION, TURN, call("{}"));

    assertEquals("ok", output);
    ToolInvocationRecord row = onlyRecord(auditor);
    assertEquals(SESSION, row.sessionId());
    assertEquals(TURN, row.turnId());
    assertEquals("probe", row.toolName());
    assertTrue(row.success(), "成功调用必须留痕且标记成功");
    assertNull(row.errorMessage());
  }

  @Test
  @DisplayName("参数不合法回填给模型，不抛异常")
  void recoversFromInvalidArgument() {
    RecordingAuditor auditor = new RecordingAuditor();
    ToolExecutor executor =
        executorWith(
            probe(
                a -> {
                  throw new LytgosException.InvalidToolArgumentException(
                      "URL 格式非法（query 里的 % 要写成 %25）");
                }),
            auditor);

    String output =
        assertDoesNotThrow(() -> executor.execute(SESSION, TURN, call("{\"url\":\"x\"}")));
    assertTrue(output.contains("工具执行失败"), "应包装成工具结果回填: " + output);
    assertTrue(output.contains("格式非法"), "应把原始原因带给模型: " + output);
    ToolInvocationRecord row = onlyRecord(auditor);
    assertFalse(row.success());
    assertTrue(row.errorMessage().contains("格式非法"), row.errorMessage());
  }

  @Test
  @DisplayName("沙箱违规向外抛，不包装成普通失败")
  void propagatesSandboxViolation() {
    RecordingAuditor auditor = new RecordingAuditor();
    ToolExecutor executor =
        executorWith(
            probe(
                a -> {
                  throw new LytgosException.SandboxViolationException("域名 evil.example.com 不在白名单内");
                }),
            auditor);

    LytgosException.SandboxViolationException violation =
        assertThrows(
            LytgosException.SandboxViolationException.class,
            () -> executor.execute(SESSION, TURN, call("{}")));
    assertTrue(
        violation.getMessage().contains("sandbox"),
        "验收脚本靠 sandbox 字面量判定拦截生效: " + violation.getMessage());
    ToolInvocationRecord row = onlyRecord(auditor);
    assertFalse(row.success(), "被拦下的调用同样要留痕，且标记失败");
    assertTrue(row.errorMessage().contains("白名单"), row.errorMessage());
  }

  @Test
  @DisplayName("工具自身运行期异常也回填")
  void recoversFromRuntimeFailure() {
    RecordingAuditor auditor = new RecordingAuditor();
    ToolExecutor executor =
        executorWith(
            probe(
                a -> {
                  throw new IllegalStateException("上游返回 502");
                }),
            auditor);

    String output = assertDoesNotThrow(() -> executor.execute(SESSION, TURN, call("{}")));
    assertTrue(output.contains("上游返回 502"), output);
    ToolInvocationRecord row = onlyRecord(auditor);
    assertFalse(row.success());
    assertTrue(row.errorMessage().contains("502"), row.errorMessage());
  }

  @Test
  @DisplayName("参数不是合法 JSON 也回填，且不执行工具")
  void recoversFromMalformedJson() {
    int[] invoked = {0};
    RecordingAuditor auditor = new RecordingAuditor();
    ToolExecutor executor =
        executorWith(
            probe(
                a -> {
                  invoked[0]++;
                  return "不该执行到这里";
                }),
            auditor);

    String output = assertDoesNotThrow(() -> executor.execute(SESSION, TURN, call("{not-json")));
    assertTrue(output.contains("不是合法 JSON"), output);
    assertEquals(0, invoked[0], "参数解析失败时不得触碰工具实现");
    ToolInvocationRecord row = onlyRecord(auditor);
    assertFalse(row.success());
    assertTrue(row.errorMessage().contains("不是合法 JSON"), row.errorMessage());
  }

  @Test
  @DisplayName("MDC 里有 traceId 时，工具审计行携带同一个值")
  void carriesTraceIdFromMdc() {
    RecordingAuditor auditor = new RecordingAuditor();
    ToolExecutor executor = executorWith(probe(a -> "ok"), auditor);

    MDC.put(TraceContext.MDC_KEY, "req-42");
    try {
      executor.execute(SESSION, TURN, call("{}"));
    } finally {
      MDC.remove(TraceContext.MDC_KEY);
    }

    assertEquals("req-42", onlyRecord(auditor).traceId());
  }

  @Test
  @DisplayName("没有请求上下文时 traceId 写 null，审计照常落库")
  void missingTraceIdDoesNotBlockAudit() {
    RecordingAuditor auditor = new RecordingAuditor();
    ToolExecutor executor = executorWith(probe(a -> "ok"), auditor);

    executor.execute(SESSION, TURN, call("{}"));

    ToolInvocationRecord row = onlyRecord(auditor);
    assertNull(row.traceId(), "无请求上下文时 traceId 合法为空");
  }

  @Test
  @DisplayName("未注册的工具属于配置错误，直接抛且留痕")
  void unregisteredToolIsConfigurationError() {
    RecordingAuditor auditor = new RecordingAuditor();
    ToolExecutor executor = executorWith(probe(a -> "ok"), auditor);

    assertThrows(
        LytgosException.InvalidConfigurationException.class,
        () -> executor.execute(SESSION, TURN, new ToolCall("call_2", "no_such_tool", "{}")));
    ToolInvocationRecord row = onlyRecord(auditor);
    assertFalse(row.success());
    assertTrue(row.errorMessage().contains("未注册"), row.errorMessage());
  }
}
