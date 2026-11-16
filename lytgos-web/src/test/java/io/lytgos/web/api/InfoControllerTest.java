package io.lytgos.web.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.lytgos.web.WebTestBeans;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 运行信息端点：在真实 Tomcat 下证明 JDK、虚拟线程、工作区与 provider 名单，且响应里不含任何密钥痕迹。
 *
 * <p>必须走 {@code RANDOM_PORT}——{@code virtualThread} 取自「当前处理请求的线程」， MockMvc 不经过 Tomcat
 * 的虚拟线程执行器，断言会失真。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InfoControllerTest {

  @LocalServerPort private int port;

  @Autowired private ObjectMapper objectMapper;

  @Test
  void infoExposesRuntimeFactsWithoutSecrets() {
    String body =
        RestClient.create()
            .get()
            .uri("http://localhost:" + port + "/api/v1/info")
            .retrieve()
            .body(String.class);

    JsonNode json = objectMapper.readTree(body);
    assertThat(json.get("code").asString()).isEqualTo("0");

    JsonNode data = json.get("data");
    assertThat(data.get("appName").asString()).isEqualTo("lytgos");
    assertThat(data.get("version").asString()).isNotBlank();
    assertThat(data.get("javaVersion").asString()).startsWith("21");
    assertThat(data.get("virtualThread").asBoolean()).isTrue();

    Path workspaceRoot = Path.of(data.get("workspaceRoot").asString());
    assertThat(workspaceRoot.isAbsolute()).isTrue();
    assertThat(workspaceRoot).isEqualTo(WebTestBeans.project().resolve(".lytgos"));

    JsonNode providers = data.get("providers");
    assertThat(providers.size()).isEqualTo(1);
    assertThat(providers.get(0).get("name").asString()).isEqualTo("deepseek");
    assertThat(providers.get(0).get("model").asString()).isNotBlank();
    assertThat(providers.get(0).has("apiKey")).isFalse();
    assertThat(providers.get(0).has("api-key")).isFalse();

    assertThat(data.has("sessions")).isTrue();
    assertThat(body).doesNotContain("apiKey", "api-key", "DEEPSEEK_API_KEY", "sk-");
  }
}
