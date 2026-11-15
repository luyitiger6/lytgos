package io.lytgos.core.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.lytgos.core.LytgosException;
import io.lytgos.core.chat.ToolSpec;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ToolRegistry：未知工具为空、重名拒绝启动、顺序保持")
class ToolRegistryTest {

  private static Tool tool(String name) {
    return new NamedTool(name);
  }

  /** 静态命名类而非匿名类：匿名内部类会隐式捕获外部 this（SpotBugs SIC_INNER_SHOULD_BE_STATIC_ANON）。 */
  private static final class NamedTool implements Tool {

    private final String name;

    private NamedTool(String name) {
      this.name = name;
    }

    @Override
    public ToolSpec spec() {
      return new ToolSpec(name, name + " 的说明", "{\"type\":\"object\"}");
    }

    @Override
    public String execute(Map<String, Object> arguments) {
      return name;
    }
  }

  @Test
  @DisplayName("未注册的工具返回 empty，不抛异常——查询不是失败路径")
  void findUnknownReturnsEmpty() {
    ToolRegistry registry = new ToolRegistry(List.of(tool("http_get")));

    assertTrue(registry.find("no_such_tool").isEmpty());
    assertTrue(registry.find(null).isEmpty(), "null 名称同样应返回 empty");
    assertEquals("http_get", registry.find("http_get").orElseThrow().spec().name());
  }

  @Test
  @DisplayName("重名在启动期直接失败，消息带冲突名称")
  void rejectsDuplicateNames() {
    LytgosException.InvalidConfigurationException thrown =
        assertThrows(
            LytgosException.InvalidConfigurationException.class,
            () -> new ToolRegistry(List.of(tool("dup"), tool("dup"))));

    assertTrue(thrown.getMessage().contains("重名"), thrown.getMessage());
    assertTrue(thrown.getMessage().contains("dup"), thrown.getMessage());
  }

  @Test
  @DisplayName("specs 与 names 保持注册顺序")
  void keepsRegistrationOrder() {
    ToolRegistry registry = new ToolRegistry(List.of(tool("write_file"), tool("file_read")));

    assertEquals(
        List.of("write_file", "file_read"), registry.specs().stream().map(ToolSpec::name).toList());
    assertEquals(List.of("write_file", "file_read"), List.copyOf(registry.names()));
  }

  @Test
  @DisplayName("缺少 name 的工具体在启动期失败")
  void rejectsToolWithoutName() {
    assertThrows(
        LytgosException.InvalidConfigurationException.class,
        () -> new ToolRegistry(List.of(new NamedTool("  "))));
  }
}
