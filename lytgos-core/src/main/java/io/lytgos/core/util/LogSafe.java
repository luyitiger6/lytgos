package io.lytgos.core.util;

/**
 * 日志参数清洗：把控制字符转义成可见形式，防止外部输入伪造日志行。
 *
 * <p>工具名、模型参数、远端 MCP 返回的工具清单都可能带换行——原样写进日志就能伪造出一条 看起来来自系统的记录。转义后保留可读性，同时让一行日志永远只有一行。
 */
public final class LogSafe {

  private LogSafe() {}

  /** 把 CR / LF / TAB 转成 {@code \r} / {@code \n} / {@code \t} 的字面形式。 */
  public static String sanitize(Object value) {
    if (value == null) {
      return "null";
    }
    return value.toString().replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t");
  }
}
