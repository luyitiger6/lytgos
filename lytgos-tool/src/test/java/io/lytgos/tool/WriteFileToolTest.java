package io.lytgos.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.lytgos.core.LytgosException;
import io.lytgos.core.tool.SandboxChecker;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("WriteFileTool：工作区内写入与受保护文件")
class WriteFileToolTest {

  @TempDir Path root;

  private SandboxChecker sandbox() {
    return new SandboxChecker(root, Set.of(), Set.of());
  }

  private WriteFileTool tool() {
    return new WriteFileTool(sandbox(), root);
  }

  @Test
  @DisplayName("写入后可被 file_read 读回，返回字节数")
  void writesThenReadsBack() {
    String result = tool().execute(Map.of("path", "output/notes.txt", "content", "hello"));

    assertTrue(result.contains("notes.txt"), "应返回写入路径: " + result);
    assertTrue(result.contains("5 字节"), "应返回写入字节数: " + result);
    String readBack = new FileTool(sandbox(), root).execute(Map.of("path", "output/notes.txt"));
    assertEquals("hello", readBack);
  }

  @Test
  @DisplayName("越界写入被拒且文件未创建")
  void rejectsTraversal() {
    assertThrows(
        LytgosException.SandboxViolationException.class,
        () -> tool().execute(Map.of("path", "../outside.txt", "content", "x")));

    assertFalse(Files.exists(root.resolveSibling("outside.txt")), "越界文件不应被创建");
  }

  @Test
  @DisplayName("拒绝改写 agents/*/AGENT.md，原文件不变")
  void rejectsAgentDefinition() throws IOException {
    Path directory = Files.createDirectories(root.resolve("agents").resolve("default"));
    Path agentFile = directory.resolve("AGENT.md");
    Files.writeString(agentFile, "original", StandardCharsets.UTF_8);

    LytgosException.SandboxViolationException thrown =
        assertThrows(
            LytgosException.SandboxViolationException.class,
            () -> tool().execute(Map.of("path", "agents/default/AGENT.md", "content", "hacked")));

    assertTrue(thrown.getMessage().contains("受保护"), "错误应说明受保护原因");
    assertEquals("original", Files.readString(agentFile, StandardCharsets.UTF_8));
  }

  @Test
  @DisplayName("拒绝改写根 config.yaml，原文件不变")
  void rejectsWorkspaceConfig() throws IOException {
    Path config = root.resolve("config.yaml");
    Files.writeString(config, "providers: {}", StandardCharsets.UTF_8);

    assertThrows(
        LytgosException.SandboxViolationException.class,
        () -> tool().execute(Map.of("path", "config.yaml", "content", "providers: {}")));

    assertEquals("providers: {}", Files.readString(config, StandardCharsets.UTF_8));
  }

  @Test
  @DisplayName("拒绝整体覆写 memory/MEMORY.md，原文件不变")
  void rejectsMemoryFile() throws IOException {
    Path directory = Files.createDirectories(root.resolve("memory"));
    Path memoryFile = directory.resolve("MEMORY.md");
    Files.writeString(memoryFile, "- original", StandardCharsets.UTF_8);

    LytgosException.SandboxViolationException thrown =
        assertThrows(
            LytgosException.SandboxViolationException.class,
            () -> tool().execute(Map.of("path", "memory/MEMORY.md", "content", "hacked")));

    assertTrue(thrown.getMessage().contains("受保护"), "错误应说明受保护原因");
    assertTrue(thrown.getMessage().contains("save_memory"), "应引导改用 save_memory 追加");
    assertEquals("- original", Files.readString(memoryFile, StandardCharsets.UTF_8));
  }

  @Test
  @DisplayName("超过 64KB 拒绝写入")
  void rejectsOversizedContent() {
    String big = "x".repeat(64 * 1024 + 1);

    IllegalStateException thrown =
        assertThrows(
            IllegalStateException.class,
            () -> tool().execute(Map.of("path", "big.txt", "content", big)));

    assertTrue(thrown.getMessage().contains("64KB"), "应返回上限说明");
    assertFalse(Files.exists(root.resolve("big.txt")), "拒绝时不应创建文件");
  }
}
