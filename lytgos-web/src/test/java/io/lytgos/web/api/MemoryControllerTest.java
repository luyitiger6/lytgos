package io.lytgos.web.api;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.lytgos.memory.MemoryStore;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("MemoryController：空记忆不是故障，有记忆返回全文")
class MemoryControllerTest {

  @Autowired private MockMvc mockMvc;

  @Autowired private MemoryStore memory;

  @Test
  @DisplayName("无记忆时返回空内容与 0 字符")
  void emptyMemoryIsNotAnError() throws Exception {
    clearMemory();

    mockMvc
        .perform(get("/api/v1/memory"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("0"))
        .andExpect(jsonPath("$.data.content").value(""))
        .andExpect(jsonPath("$.data.chars").value(0))
        .andExpect(jsonPath("$.data.maxChars").value(MemoryStore.MAX_CHARS));
  }

  @Test
  @DisplayName("有记忆时返回全文与字符数")
  void returnsMemoryContent() throws Exception {
    clearMemory();
    memory.append("喜欢深色模式");
    try {
      mockMvc
          .perform(get("/api/v1/memory"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.content").value(containsString("喜欢深色模式")))
          .andExpect(jsonPath("$.data.chars").value(memory.read().length()))
          .andExpect(jsonPath("$.data.maxChars").value(MemoryStore.MAX_CHARS));
    } finally {
      clearMemory();
    }
  }

  private void clearMemory() throws IOException {
    Files.writeString(memory.file(), "", StandardCharsets.UTF_8);
  }
}
