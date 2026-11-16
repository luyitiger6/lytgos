package io.lytgos.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.lytgos.core.LytgosException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SessionKeys：api:<user>:<agent> 的拼装与反解")
class SessionKeysTest {

  @Test
  @DisplayName("合法输入拼出标识并可反解")
  void buildsAndParsesValidKey() {
    String key = SessionKeys.of("u1", "default");

    assertEquals("api:u1:default", key);
    assertEquals("default", SessionKeys.parse(key));
  }

  @Test
  @DisplayName("缺省 user 取 api-default")
  void defaultsUser() {
    assertEquals("api:api-default:default", SessionKeys.of(null, "default"));
    assertEquals("api:api-default:default", SessionKeys.of("  ", "default"));
  }

  @Test
  @DisplayName("user 含冒号被拒绝")
  void rejectsUserWithSeparator() {
    LytgosException.InvalidAgentDefinitionException thrown =
        assertThrows(
            LytgosException.InvalidAgentDefinitionException.class,
            () -> SessionKeys.of("u:1", "default"));

    assertTrue(thrown.getMessage().contains("user"), "实际消息: " + thrown.getMessage());
  }

  @Test
  @DisplayName("agent 含冒号被拒绝")
  void rejectsAgentWithSeparator() {
    assertThrows(
        LytgosException.InvalidAgentDefinitionException.class,
        () -> SessionKeys.of("u1", "de:fault"));
  }

  @Test
  @DisplayName("agent 含路径穿越被拒绝")
  void rejectsAgentWithTraversal() {
    assertThrows(
        LytgosException.InvalidAgentDefinitionException.class,
        () -> SessionKeys.of("u1", "../etc"));
    assertThrows(
        LytgosException.InvalidAgentDefinitionException.class, () -> SessionKeys.of("u1", "a/b"));
  }

  @Test
  @DisplayName("非本格式的 key 反解失败")
  void rejectsForeignKeys() {
    for (String key :
        new String[] {"cli:local:default", "api:u1", "api:u1:", "api::default", "api:u1:a:b", ""}) {
      assertThrows(
          LytgosException.InvalidAgentDefinitionException.class,
          () -> SessionKeys.parse(key),
          "应拒绝: " + key);
    }
    assertThrows(
        LytgosException.InvalidAgentDefinitionException.class, () -> SessionKeys.parse(null));
  }
}
