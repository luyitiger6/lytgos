package io.lytgos.web.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 真实 Tomcat 下的系统信息端点：证明虚拟线程与 JDK 基线来自运行时事实。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SystemInfoIntegrationTest {

  @LocalServerPort private int port;

  @Autowired private ObjectMapper objectMapper;

  @Test
  void systemInfoExposesRuntimeFacts() throws Exception {
    String body =
        RestClient.create()
            .get()
            .uri("http://localhost:" + port + "/api/v1/system/info")
            .retrieve()
            .body(String.class);

    JsonNode json = objectMapper.readTree(body);
    assertThat(json.get("code").asString()).isEqualTo("0");
    assertThat(json.get("message").asString()).isEqualTo("ok");
    assertThat(json.get("timestamp").asString()).isNotBlank();

    JsonNode data = json.get("data");
    assertThat(data.get("appName").asString()).isEqualTo("lytgos");
    assertThat(data.get("version").asString()).isNotBlank();
    assertThat(data.get("javaVersion").asString()).startsWith("21");
    assertThat(data.get("virtualThread").asBoolean()).isTrue();
  }
}
