package io.lytgos.web.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.lytgos.core.trace.TraceContext;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** traceId 的复用、生成与请求后清理。 */
class TraceIdFilterTest {

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.standaloneSetup(new StubController())
            .addFilters(new TraceIdFilter())
            .build();
  }

  @AfterEach
  void tearDown() {
    MDC.clear();
  }

  @Test
  void reusesIncomingRequestId() throws Exception {
    MvcResult result =
        mockMvc.perform(get("/stub/trace").header(TraceIdFilter.HEADER, "abc123")).andReturn();

    assertThat(result.getResponse().getHeader(TraceIdFilter.HEADER)).isEqualTo("abc123");
    assertThat(result.getResponse().getContentAsString()).isEqualTo("abc123");
    assertThat(MDC.get(TraceContext.MDC_KEY)).isNull();
  }

  @Test
  void generatesRequestIdWhenAbsent() throws Exception {
    MvcResult result = mockMvc.perform(get("/stub/trace")).andReturn();

    String header = result.getResponse().getHeader(TraceIdFilter.HEADER);
    assertThat(header).isNotBlank();
    assertThat(result.getResponse().getContentAsString()).isEqualTo(header);
    assertThat(MDC.get(TraceContext.MDC_KEY)).isNull();
  }

  @Test
  @DisplayName("探针日志在 MDC 已写入的位置产生，且携带本次 traceId")
  void probeLogCarriesTraceId() throws Exception {
    Logger logger = (Logger) LoggerFactory.getLogger(TraceIdFilter.class);
    Level previous = logger.getLevel();
    List<String> observed = new ArrayList<>();
    ListAppender<ILoggingEvent> appender =
        new ListAppender<>() {
          @Override
          protected void append(ILoggingEvent event) {
            // 同步 append 发生在 doFilter 内，此刻 MDC 尚未清理；logback 的 MDC 快照是惰性的，
            // 留到断言时再读只会拿到空 map，所以必须在事件发生的当下取值。
            observed.add(MDC.get(TraceContext.MDC_KEY) + "|" + event.getFormattedMessage());
            super.append(event);
          }
        };
    appender.start();
    logger.addAppender(appender);
    logger.setLevel(Level.DEBUG);
    try {
      mockMvc.perform(get("/stub/trace").header(TraceIdFilter.HEADER, "probe-42")).andReturn();
    } finally {
      logger.setLevel(previous);
      logger.detachAppender(appender);
    }

    assertThat(observed).anyMatch(line -> line.startsWith("probe-42|trace attached:"));
  }

  /** 回显请求期间 MDC 中的 traceId，用于断言 Filter 写入值与响应头一致。 */
  @RestController
  static class StubController {

    @GetMapping("/stub/trace")
    String trace() {
      return String.valueOf(MDC.get(TraceContext.MDC_KEY));
    }
  }
}
