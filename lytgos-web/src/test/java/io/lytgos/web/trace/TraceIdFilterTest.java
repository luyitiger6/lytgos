package io.lytgos.web.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
    assertThat(MDC.get(TraceIdFilter.MDC_KEY)).isNull();
  }

  @Test
  void generatesRequestIdWhenAbsent() throws Exception {
    MvcResult result = mockMvc.perform(get("/stub/trace")).andReturn();

    String header = result.getResponse().getHeader(TraceIdFilter.HEADER);
    assertThat(header).isNotBlank();
    assertThat(result.getResponse().getContentAsString()).isEqualTo(header);
    assertThat(MDC.get(TraceIdFilter.MDC_KEY)).isNull();
  }

  /** 回显请求期间 MDC 中的 traceId，用于断言 Filter 写入值与响应头一致。 */
  @RestController
  static class StubController {

    @GetMapping("/stub/trace")
    String trace() {
      return String.valueOf(MDC.get(TraceIdFilter.MDC_KEY));
    }
  }
}
