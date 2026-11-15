package io.lytgos.core.trace;

import org.slf4j.MDC;

/**
 * 日志 MDC 中 traceId 的唯一读写入口。
 *
 * <p>Web 入口的请求级值由 {@code TraceIdFilter} 写入；命令行与将来的其他入口由 {@link #ensure(String)}
 * 兜底，保证「一次可追踪的执行」始终有值。键名只在这里定义一次——散落在各模块的字符串一旦拼错不会编译失败，只会让审计行静默变空。
 */
public final class TraceContext {

  /** MDC 中 traceId 的键名。 */
  public static final String MDC_KEY = "traceId";

  private TraceContext() {}

  /** 当前执行上下文的 traceId；没有时返回 {@code null}。 */
  public static String current() {
    return MDC.get(MDC_KEY);
  }

  /**
   * 确保本次执行有 traceId。
   *
   * <p>已有值时直接沿用——外层（如 HTTP 请求）的值才是这段执行的真实来源；没有值时写入 {@code fallback}，并在关闭作用域时移除，避免线程复用时串到下一轮。
   *
   * @param fallback 没有外层上下文时使用的值，通常为本轮对话的 turnId
   * @return 作用域；MDC 已有值时为无副作用实例
   * @throws IllegalArgumentException fallback 为空且 MDC 也没有值。这是调用方 bug：静默降级会让这段执行的审计行永久缺 trace
   */
  public static Scope ensure(String fallback) {
    String existing = MDC.get(MDC_KEY);
    if (existing != null && !existing.isBlank()) {
      return Scope.NOOP;
    }
    if (fallback == null || fallback.isBlank()) {
      throw new IllegalArgumentException("没有外层 traceId 时 fallback 不能为空");
    }
    MDC.put(MDC_KEY, fallback);
    return new Scope(true);
  }

  /** {@link #ensure(String)} 的作用域：只有自己写入过才在关闭时清理，外层值不动。 */
  public static final class Scope implements AutoCloseable {

    private static final Scope NOOP = new Scope(false);

    private final boolean ownsValue;

    private Scope(boolean ownsValue) {
      this.ownsValue = ownsValue;
    }

    @Override
    public void close() {
      if (ownsValue) {
        MDC.remove(MDC_KEY);
      }
    }
  }
}
