package io.lytgos.web.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.lytgos.web.support.InMemorySessionStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("AgentController：无状态调用不落会话")
class AgentControllerTest {

  @Autowired private MockMvc mockMvc;

  @Autowired private InMemorySessionStore sessions;

  @BeforeEach
  void resetStore() {
    sessions.reset();
  }

  @Test
  @DisplayName("单次调用返回文本且会话数不变")
  void invokeReturnsReplyWithoutCreatingSession() throws Exception {
    assertEquals(0, sessions.list().size());

    mockMvc
        .perform(
            post("/api/v1/agents/default/invoke")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"input\":\"用一句话介绍你自己\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("0"))
        .andExpect(jsonPath("$.data.agent").value("default"))
        .andExpect(jsonPath("$.data.reply").value("echo:用一句话介绍你自己"));

    assertEquals(0, sessions.list().size(), "无状态调用不得写入会话存储");
  }

  @Test
  @DisplayName("未知 agent 返回 400 + LYTGOS_INVALID_AGENT")
  void unknownAgentReturns400() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/agents/no-such-agent/invoke")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"input\":\"hi\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("LYTGOS_INVALID_AGENT"));
  }

  @Test
  @DisplayName("空输入返回 400 + LYTGOS_INVALID_REQUEST")
  void blankInputReturns400() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/agents/default/invoke")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"input\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("LYTGOS_INVALID_REQUEST"));
  }
}
