package io.lytgos.web.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.lytgos.core.chat.Message;
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
@DisplayName("SessionController：创建幂等、列表、历史与归档")
class SessionControllerTest {

  @Autowired private MockMvc mockMvc;

  @Autowired private InMemorySessionStore sessions;

  @BeforeEach
  void resetStore() {
    sessions.reset();
  }

  @Test
  @DisplayName("相同 agent/user 重复创建命中同一会话")
  void createIsIdempotent() throws Exception {
    String body = "{\"agent\":\"default\"}";

    mockMvc
        .perform(post("/api/v1/sessions").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("0"))
        .andExpect(jsonPath("$.data.sessionId").value("api:api-default:default"))
        .andExpect(jsonPath("$.data.agent").value("default"))
        .andExpect(jsonPath("$.data.user").value("api-default"))
        .andExpect(jsonPath("$.data.created").value(true));

    mockMvc
        .perform(post("/api/v1/sessions").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.sessionId").value("api:api-default:default"))
        .andExpect(jsonPath("$.data.created").value(false));

    mockMvc
        .perform(get("/api/v1/sessions"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(1))
        .andExpect(jsonPath("$.data.items[0].sessionId").value("api:api-default:default"))
        .andExpect(jsonPath("$.data.items[0].messageCount").value(0))
        .andExpect(jsonPath("$.data.items[0].updatedAt").exists());
  }

  @Test
  @DisplayName("user 参与会话标识")
  void createWithUser() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"agent\":\"default\",\"user\":\"u1\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.sessionId").value("api:u1:default"))
        .andExpect(jsonPath("$.data.user").value("u1"));
  }

  @Test
  @DisplayName("agent 名非法返回 400 + LYTGOS_INVALID_AGENT")
  void createRejectsIllegalAgent() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"agent\":\"../etc\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("LYTGOS_INVALID_AGENT"));
  }

  @Test
  @DisplayName("缺 agent 返回 400 + LYTGOS_INVALID_AGENT")
  void createRejectsMissingAgent() throws Exception {
    mockMvc
        .perform(post("/api/v1/sessions").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("LYTGOS_INVALID_AGENT"));
  }

  @Test
  @DisplayName("详情返回完整历史，role 小写")
  void detailReturnsHistory() throws Exception {
    Session session = new Session("api:u1:default");
    session.append(Message.user("你好"));
    session.append(Message.assistant("你好，有什么可以帮你？"));
    sessions.save(session);

    mockMvc
        .perform(get("/api/v1/sessions/api:u1:default"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.sessionId").value("api:u1:default"))
        .andExpect(jsonPath("$.data.messages.length()").value(2))
        .andExpect(jsonPath("$.data.messages[0].role").value("user"))
        .andExpect(jsonPath("$.data.messages[0].content").value("你好"))
        .andExpect(jsonPath("$.data.messages[1].role").value("assistant"));
  }

  @Test
  @DisplayName("不存在的会话详情返回 404")
  void detailUnknownSessionReturns404() throws Exception {
    mockMvc
        .perform(get("/api/v1/sessions/api:u1:nobody"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("LYTGOS_NOT_FOUND"));
  }

  @Test
  @DisplayName("归档后不可查且不在列表")
  void archiveRemovesSession() throws Exception {
    sessions.save(new Session("api:u1:default"));

    mockMvc
        .perform(delete("/api/v1/sessions/api:u1:default"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.sessionId").value("api:u1:default"))
        .andExpect(jsonPath("$.data.archived").value(true));

    mockMvc.perform(get("/api/v1/sessions/api:u1:default")).andExpect(status().isNotFound());
    mockMvc
        .perform(get("/api/v1/sessions"))
        .andExpect(jsonPath("$.data.total").value(0))
        .andExpect(jsonPath("$.data.items.length()").value(0));
  }

  @Test
  @DisplayName("归档不存在的会话返回 404")
  void archiveUnknownSessionReturns404() throws Exception {
    mockMvc
        .perform(delete("/api/v1/sessions/api:u1:nobody"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("LYTGOS_NOT_FOUND"));
  }
}
