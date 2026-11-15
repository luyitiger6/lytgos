package io.lytgos.web.error;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.lytgos.core.LytgosException;
import java.util.Set;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 受控异常到 HTTP 状态与错误码的映射。 */
class GlobalExceptionHandlerTest {

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc =
        MockMvcBuilders.standaloneSetup(new StubController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  @Test
  void sandboxViolationMapsTo403() throws Exception {
    mockMvc
        .perform(get("/stub/sandbox"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("LYTGOS_SANDBOX_VIOLATION"))
        .andExpect(jsonPath("$.message").value(containsString("sandbox 拒绝：")))
        .andExpect(jsonPath("$.data").value(nullValue()))
        .andExpect(jsonPath("$.timestamp").exists());
  }

  @Test
  void invalidAgentDefinitionMapsTo400() throws Exception {
    mockMvc
        .perform(get("/stub/agent"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("LYTGOS_INVALID_AGENT"))
        .andExpect(jsonPath("$.message").value("字段 name 不能为空"));
  }

  @Test
  void invalidToolArgumentMapsTo400() throws Exception {
    mockMvc
        .perform(get("/stub/tool"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("LYTGOS_INVALID_TOOL_ARGUMENT"))
        .andExpect(jsonPath("$.message").value("参数 path 缺失"));
  }

  @Test
  void unknownProviderMapsTo400() throws Exception {
    mockMvc
        .perform(get("/stub/provider"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("LYTGOS_UNKNOWN_PROVIDER"))
        .andExpect(jsonPath("$.message").value(containsString("未知 provider")));
  }

  @Test
  void invalidConfigurationMapsTo500WithoutLeakingDetail() throws Exception {
    mockMvc
        .perform(get("/stub/config"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value("LYTGOS_INVALID_CONFIGURATION"))
        .andExpect(jsonPath("$.message").value("服务器内部错误"))
        .andExpect(content().string(Matchers.not(containsString("缺少 api key"))));
  }

  @Test
  void unexpectedExceptionDoesNotLeakInternals() throws Exception {
    mockMvc
        .perform(get("/stub/unexpected"))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value("LYTGOS_INTERNAL"))
        .andExpect(jsonPath("$.message").value("服务器内部错误"))
        .andExpect(content().string(Matchers.not(containsString("NullPointerException"))))
        .andExpect(content().string(Matchers.not(containsString("io.lytgos.Sensitive"))))
        .andExpect(content().string(Matchers.not(containsString("at io.lytgos"))));
  }

  /** 按需抛出各类受控异常的桩控制器：只为验证 advice，不进入产品代码。 */
  @RestController
  static class StubController {

    @GetMapping("/stub/sandbox")
    void sandbox() {
      throw new LytgosException.SandboxViolationException("路径越界: /etc/passwd");
    }

    @GetMapping("/stub/agent")
    void agent() {
      throw new LytgosException.InvalidAgentDefinitionException("字段 name 不能为空");
    }

    @GetMapping("/stub/tool")
    void tool() {
      throw new LytgosException.InvalidToolArgumentException("参数 path 缺失");
    }

    @GetMapping("/stub/provider")
    void provider() {
      throw new LytgosException.UnknownProviderException("mystery", Set.of("openai"));
    }

    @GetMapping("/stub/config")
    void config() {
      throw new LytgosException.InvalidConfigurationException("缺少 api key");
    }

    @GetMapping("/stub/unexpected")
    void unexpected() {
      throw new NullPointerException("内部指针错误，泄露点：io.lytgos.Sensitive#method");
    }
  }
}
