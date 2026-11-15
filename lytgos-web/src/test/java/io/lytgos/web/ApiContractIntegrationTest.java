package io.lytgos.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** 真实上下文下的 HTTP 契约：统一 404 与 actuator 响应不被改写。 */
@SpringBootTest
@AutoConfigureMockMvc
class ApiContractIntegrationTest {

  @Autowired private MockMvc mockMvc;

  @Test
  void unknownApiPathReturnsUnified404() throws Exception {
    mockMvc
        .perform(get("/api/v1/no-such-endpoint"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("LYTGOS_NOT_FOUND"))
        .andExpect(jsonPath("$.message").value(Matchers.containsString("/api/v1/no-such-endpoint")))
        .andExpect(jsonPath("$.data").value(Matchers.nullValue()))
        .andExpect(jsonPath("$.timestamp").exists());
  }

  @Test
  void bareApiRootReturnsUnified404() throws Exception {
    mockMvc
        .perform(get("/api"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("LYTGOS_NOT_FOUND"));
  }

  @Test
  void actuatorHealthIsNotRewrittenByApiAdvice() throws Exception {
    mockMvc
        .perform(get("/actuator/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"))
        .andExpect(jsonPath("$.code").doesNotExist())
        .andExpect(content().string(Matchers.not(Matchers.containsString("LYTGOS_"))));
  }

  @Test
  void nonApiUnknownPathKeepsFrameworkDefault() throws Exception {
    mockMvc.perform(get("/no-such-page")).andExpect(status().isNotFound());
  }
}
