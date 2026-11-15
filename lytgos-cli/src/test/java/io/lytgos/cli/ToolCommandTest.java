package io.lytgos.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.lytgos.core.chat.ToolSpec;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ToolCommand：工具列表渲染")
class ToolCommandTest {

  @Test
  @DisplayName("空注册表给出可读提示")
  void rendersEmptyHint() {
    assertEquals("（该 Agent 没有可用的工具）", ToolCommand.render(List.of()).strip());
  }

  @Test
  @DisplayName("表头、工具名与描述都在输出里，空描述不打印 null")
  void rendersNameAndDescription() {
    String out =
        ToolCommand.render(
            List.of(
                new ToolSpec("http_get", "发起 HTTP GET 请求", "{\"type\":\"object\"}"),
                new ToolSpec("recall_memory", null, "{\"type\":\"object\"}")));

    assertTrue(out.contains("NAME"), out);
    assertTrue(out.contains("DESCRIPTION"), out);
    assertTrue(out.contains("http_get"), out);
    assertTrue(out.contains("发起 HTTP GET 请求"), out);
    assertTrue(out.contains("recall_memory"), out);
    assertFalse(out.contains("null"), "空描述不应打印 null: " + out);
  }

  @Test
  @DisplayName("保持注册表给出的顺序")
  void keepsOrder() {
    String out =
        ToolCommand.render(
            List.of(
                new ToolSpec("write_file", "写入文件", "{}"), new ToolSpec("file_read", "读取文件", "{}")));

    assertTrue(out.indexOf("write_file") < out.indexOf("file_read"), "工具顺序应与注册顺序一致: " + out);
  }
}
