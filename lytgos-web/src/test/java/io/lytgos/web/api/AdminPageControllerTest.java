package io.lytgos.web.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("管理台：/admin 与 /admin/ 都能打开，且页面不依赖外部资源")
class AdminPageControllerTest {

  @Autowired private MockMvc mockMvc;

  @Test
  @DisplayName("/admin/ 返回 HTML 且含页面标题")
  void servesHtmlPage() throws Exception {
    String body =
        mockMvc
            .perform(get("/admin/"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);

    assertThat(body).contains("<title>lytgos 控制台</title>");
    assertThat(body).contains("只读管理台");
  }

  @Test
  @DisplayName("不带尾斜杠的 /admin 也返回同一页")
  void servesWithoutTrailingSlash() throws Exception {
    mockMvc
        .perform(get("/admin"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("lytgos 控制台")));
  }

  @Test
  @DisplayName("页面源码不含任何外部脚本或样式引用")
  void pageHasNoExternalReferences() throws Exception {
    String body =
        mockMvc
            .perform(get("/admin/"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);

    assertThat(body).doesNotContain("http://").doesNotContain("https://");
    assertThat(body).doesNotContain("<link");
  }
}
