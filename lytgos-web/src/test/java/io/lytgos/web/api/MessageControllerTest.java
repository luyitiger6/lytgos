package io.lytgos.web.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.lytgos.core.session.Session;
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
@DisplayName("MessageController：发消息、404 与非法 agent")
class MessageControllerTest {

  @Autowired private MockMvc mockMvc;

  @Autowired private InMemorySessionStore sessions;

  @BeforeEach
  void resetStore() {
    sessions.reset();
  }

  @Test
  @DisplayName("返回 reply 且历史增长并落盘")
  void sendReturnsReplyAndPersistsHistory() throws Exception {
    sessions.save(new Session("api:u1:default"));

    mockMvc
        .perform(
            post("/api/v1/sessions/api:u1:default/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"你好\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("0"))
        .andExpect(jsonPath("$.data.sessionId").value("api:u1:default"))
        .andExpect(jsonPath("$.data.reply").value("echo:你好"))
        .andExpect(jsonPath("$.data.messages").value(2));

    mockMvc
        .perform(get("/api/v1/sessions/api:u1:default"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.messages.length()").value(2))
        .andExpect(jsonPath("$.data.messages[0].content").value("你好"))
        .andExpect(jsonPath("$.data.messages[1].content").value("echo:你好"));
  }

  @Test
  @DisplayName("会话不存在返回 404")
  void unknownSessionReturns404() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/sessions/api:u1:nobody/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"hi\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("LYTGOS_NOT_FOUND"));
  }

  @Test
  @DisplayName("非 API 标识的会话发消息返回 400 + LYTGOS_INVALID_AGENT")
  void foreignSessionKeyReturns400() throws Exception {
    sessions.save(new Session("cli:local:default"));

    mockMvc
        .perform(
            post("/api/v1/sessions/cli:local:default/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"hi\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("LYTGOS_INVALID_AGENT"));
  }

  @Test
  @DisplayName("空消息返回 400 + LYTGOS_INVALID_REQUEST")
  void blankMessageReturns400() throws Exception {
    sessions.save(new Session("api:u1:default"));

    mockMvc
        .perform(
            post("/api/v1/sessions/api:u1:default/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"  \"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("LYTGOS_INVALID_REQUEST"));
  }
}
