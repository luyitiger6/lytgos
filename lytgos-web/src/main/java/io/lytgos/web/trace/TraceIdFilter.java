package io.lytgos.web.trace;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 请求级 traceId：复用调用方传入的 {@code X-Request-Id}，缺省生成一个，写入日志 MDC 并在响应头回写，{@code finally} 中清理避免线程复用串味。
 *
 * <p>传入值先做净化（剔除控制字符、截断到 128）：header 是外部输入，原样进日志会带来 日志注入风险。
 */
public class TraceIdFilter extends OncePerRequestFilter {

  /** 请求与响应共用的头名。 */
  public static final String HEADER = "X-Request-Id";

  /** 日志 MDC 键名。 */
  public static final String MDC_KEY = "traceId";

  private static final int MAX_LENGTH = 128;

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String traceId = sanitize(request.getHeader(HEADER));
    if (traceId == null) {
      traceId = UUID.randomUUID().toString();
    }
    MDC.put(MDC_KEY, traceId);
    response.setHeader(HEADER, traceId);
    try {
      chain.doFilter(request, response);
    } finally {
      MDC.remove(MDC_KEY);
    }
  }

  private static String sanitize(String value) {
    if (value == null) {
      return null;
    }
    String clean = value.strip().replaceAll("\\p{Cntrl}", "");
    if (clean.isEmpty()) {
      return null;
    }
    return clean.length() > MAX_LENGTH ? clean.substring(0, MAX_LENGTH) : clean;
  }
}
