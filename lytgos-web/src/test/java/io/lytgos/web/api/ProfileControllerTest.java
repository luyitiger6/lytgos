package io.lytgos.web.api;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.lytgos.core.workspace.WorkspaceLocator;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("ProfileController：条目与错误两段输出")
class ProfileControllerTest {

  @Autowired private MockMvc mockMvc;

  @Autowired private WorkspaceLocator locator;

  @Test
  @DisplayName("包含 default 条目及其 provider 与 model")
  void listsDefaultAgent() throws Exception {
    mockMvc
        .perform(get("/api/v1/profiles"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("0"))
        .andExpect(jsonPath("$.data.items[0].name").value("default"))
        .andExpect(jsonPath("$.data.items[0].description").value("默认助手"))
        .andExpect(jsonPath("$.data.items[0].provider").value("deepseek"))
        .andExpect(jsonPath("$.data.items[0].model").exists())
        .andExpect(jsonPath("$.data.items[0].tools").isArray());
  }

  @Test
  @DisplayName("坏 Agent 进 errors，整体仍 200")
  void brokenAgentGoesToErrors() throws Exception {
    Path broken = locator.get().agents().resolve("broken");
    Files.createDirectories(broken);
    Files.writeString(
        broken.resolve("AGENT.md"),
        """
            ---
            name: broken
            provider:
              name: deepseek
            ---
            指令
            """,
        StandardCharsets.UTF_8);
    try {
      mockMvc
          .perform(get("/api/v1/profiles"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.items[0].name").value("default"))
          .andExpect(jsonPath("$.data.errors[0].name").value("broken"))
          .andExpect(jsonPath("$.data.errors[0].message").value(containsString("provider.model")));
    } finally {
      Files.deleteIfExists(broken.resolve("AGENT.md"));
      Files.deleteIfExists(broken);
    }
  }
}
