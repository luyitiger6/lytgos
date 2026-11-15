package io.lytgos.core.trace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

@DisplayName("TraceContext：MDC 的唯一读写入口")
class TraceContextTest {

  @AfterEach
  void clear() {
    MDC.clear();
  }

  @Test
  @DisplayName("没有值时 current 返回 null")
  void currentIsNullWhenAbsent() {
    assertNull(TraceContext.current());
  }

  @Test
  @DisplayName("MDC 已有值时 ensure 不覆盖，关闭时也不回滚外层值")
  void ensureKeepsExistingValue() {
    MDC.put(TraceContext.MDC_KEY, "outer-1");
    try (TraceContext.Scope scope = TraceContext.ensure("inner-1")) {
      assertEquals("outer-1", TraceContext.current(), "外层 traceId 必须优先");
    }
    assertEquals("outer-1", TraceContext.current(), "关闭内层作用域不得清掉外层值");
  }

  @Test
  @DisplayName("MDC 为空时 ensure 写入 fallback，关闭后清理")
  void ensureWritesFallbackAndCleansUp() {
    try (TraceContext.Scope scope = TraceContext.ensure("round-1")) {
      assertEquals("round-1", TraceContext.current());
    }
    assertNull(TraceContext.current(), "轮次结束后必须清理，避免线程复用串味");
  }

  @Test
  @DisplayName("fallback 为空视为调用方 bug，立即失败而不是静默不带 trace")
  void blankFallbackFailsFast() {
    assertThrows(IllegalArgumentException.class, () -> TraceContext.ensure(" "));
    assertNull(TraceContext.current(), "失败不得留下半写入状态");
  }
}
