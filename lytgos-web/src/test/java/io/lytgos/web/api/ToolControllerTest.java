package io.lytgos.web.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.lytgos.web.support.FakeAgentInvoker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("ToolController：缺省 agent、注册顺序与未知 Agent")
class ToolControllerTest {

  @Autowired private MockMvc mockMvc;

  @Test
  @DisplayName("缺省查 default，顺序与注册顺序一致")
  void listsDefaultAgentToolsInOrder() throws Exception {
    mockMvc
        .perform(get("/api/v1/tools"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.agent").value("default"))
        .andExpect(jsonPath("$.data.tools.length()").value(FakeAgentInvoker.TOOL_NAMES.size()))
        .andExpect(jsonPath("$.data.tools[0].name").value("http_get"))
        .andExpect(jsonPath("$.data.tools[1].name").value("file_read"))
        .andExpect(jsonPath("$.data.tools[2].name").value("shell"))
        .andExpect(jsonPath("$.data.tools[0].description").exists());
  }

  @Test
  @DisplayName("未知 agent 返回 400 + LYTGOS_INVALID_AGENT")
  void unknownAgentReturns400() throws Exception {
    mockMvc
        .perform(get("/api/v1/tools").param("agent", "no-such-agent"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("LYTGOS_INVALID_AGENT"));
  }

  @Test
  @DisplayName("带路径穿越的名字在查询前就被拒绝")
  void illegalAgentNameReturns400() throws Exception {
    mockMvc
        .perform(get("/api/v1/tools").param("agent", "../etc"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("LYTGOS_INVALID_AGENT"));
  }
}
