package io.lytgos.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.lytgos.core.LytgosException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Profile.McpServer：url 与 command 二选一")
class ProfileTest {

  @Test
  @DisplayName("只有 url 时是远程形态，args 缺省归一成空列表")
  void acceptsUrlOnly() {
    Profile.McpServer server =
        new Profile.McpServer("amap", "https://mcp.amap.com/mcp?key=k", null, null, Map.of());

    assertEquals("https://mcp.amap.com/mcp?key=k", server.url(), "url 必须原样保留，query 里的 key 不能丢");
    assertNull(server.command());
    assertEquals(List.of(), server.args());
    assertEquals(Map.of(), server.env());
  }

  @Test
  @DisplayName("只有 command 时是本地形态")
  void acceptsCommandOnly() {
    Profile.McpServer server =
        new Profile.McpServer("local", null, "java", List.of("-cp", "x"), Map.of("K", "v"));

    assertNull(server.url());
    assertEquals("java", server.command());
    assertEquals(List.of("-cp", "x"), server.args());
    assertEquals(Map.of("K", "v"), server.env());
  }

  @Test
  @DisplayName("两者都缺时拒绝，消息写清二选一规则")
  void rejectsBothMissing() {
    LytgosException.InvalidAgentDefinitionException failure =
        assertThrows(
            LytgosException.InvalidAgentDefinitionException.class,
            () -> new Profile.McpServer("x", null, "   ", List.of(), Map.of()));

    assertTrue(failure.getMessage().contains("url"), failure.getMessage());
    assertTrue(failure.getMessage().contains("command"), failure.getMessage());
    assertTrue(failure.getMessage().contains("x"), "必须指出是哪个 server 配错了: " + failure.getMessage());
  }

  @Test
  @DisplayName("两者都有时拒绝——同时配置会让启动阶段的形态判断变成猜")
  void rejectsBothPresent() {
    LytgosException.InvalidAgentDefinitionException failure =
        assertThrows(
            LytgosException.InvalidAgentDefinitionException.class,
            () ->
                new Profile.McpServer(
                    "x", "https://a.example.com/mcp", "java", List.of(), Map.of()));

    assertTrue(failure.getMessage().contains("url"), failure.getMessage());
    assertTrue(failure.getMessage().contains("command"), failure.getMessage());
  }

  @Test
  @DisplayName("provider.temperature 越界时拒绝，消息写清字段名与合法区间")
  void rejectsOutOfRangeTemperature() {
    LytgosException.InvalidAgentDefinitionException tooHigh =
        assertThrows(
            LytgosException.InvalidAgentDefinitionException.class,
            () -> new Profile.ProviderRef("deepseek", "deepseek-chat", 2.5));
    assertTrue(tooHigh.getMessage().contains("provider.temperature"), tooHigh.getMessage());

    assertThrows(
        LytgosException.InvalidAgentDefinitionException.class,
        () -> new Profile.ProviderRef("deepseek", "deepseek-chat", -0.1),
        "低于下界同样拒绝");
  }

  @Test
  @DisplayName("集合字段做防御性拷贝，外部改动不影响已加载的声明")
  void copiesCollections() {
    List<String> args = new ArrayList<>(List.of("a"));
    Profile.McpServer server = new Profile.McpServer("x", null, "java", args, Map.of());

    args.add("b");

    assertEquals(List.of("a"), server.args());
  }

  @Test
  @DisplayName("缺 name 时不在这里报，交给启动阶段——那里能把错误上下文说完整")
  void allowsNamelessDeclaration() {
    Profile.McpServer nameless =
        new Profile.McpServer(null, "https://a.example.com/mcp", null, List.of(), Map.of());

    assertNull(nameless.name());
    assertEquals("https://a.example.com/mcp", nameless.url());
  }
}
