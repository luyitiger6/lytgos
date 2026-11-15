package io.lytgos.tool;

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

@DisplayName("ListDirTool：直接子项、排序与截断")
class ListDirToolTest {

  @TempDir Path root;

  private ListDirTool tool() {
    return new ListDirTool(new SandboxChecker(root, Set.of(), Set.of()), root);
  }

  @Test
  @DisplayName("缺省列出工作区根，目录带 / 后缀且按名称排序")
  void listsRootSorted() throws IOException {
    Files.createDirectories(root.resolve("memory"));
    Files.createDirectories(root.resolve("agents"));
    Files.writeString(root.resolve("config.yaml"), "providers: {}", StandardCharsets.UTF_8);
    Files.writeString(root.resolve("AGENTS.md"), "# 说明", StandardCharsets.UTF_8);

    String listing = tool().execute(Map.of());

    assertTrue(listing.contains("agents/"), "目录应带 / 后缀: " + listing);
    assertTrue(listing.contains("memory/"));
    assertTrue(listing.contains("config.yaml"));
    assertTrue(listing.indexOf("AGENTS.md") < listing.indexOf("config.yaml"), "应按名称排序");
  }

  @Test
  @DisplayName("显式路径只列直接子项")
  void listsSubdirectoryOnly() throws IOException {
    Files.createDirectories(root.resolve("memory").resolve("nested"));
    Files.writeString(root.resolve("memory").resolve("MEMORY.md"), "x", StandardCharsets.UTF_8);

    String listing = tool().execute(Map.of("path", "memory"));

    assertTrue(listing.contains("MEMORY.md"));
    assertTrue(listing.contains("nested/"));
    assertTrue(listing.indexOf("MEMORY.md") < listing.indexOf("nested/"));
  }

  @Test
  @DisplayName("超过 200 条时截断并标注总数")
  void truncatesLongListing() throws IOException {
    for (int i = 0; i < 205; i++) {
      Files.createFile(root.resolve(String.format("f%03d.txt", i)));
    }

    String listing = tool().execute(Map.of());

    assertTrue(listing.contains("共 205 项"), "应标注总数: " + listing.substring(listing.length() - 60));
    assertTrue(listing.contains("前 200 项"));
    long entries = listing.lines().filter(line -> line.startsWith("f")).count();
    assertTrue(entries == 200, "应只列出前 200 条，实际 " + entries);
  }

  @Test
  @DisplayName("非目录返回可读失败文本")
  void rejectsNonDirectory() throws IOException {
    Files.writeString(root.resolve("a.txt"), "x", StandardCharsets.UTF_8);

    IllegalStateException thrown =
        assertThrows(IllegalStateException.class, () -> tool().execute(Map.of("path", "a.txt")));

    assertTrue(thrown.getMessage().contains("不是目录"));
  }

  @Test
  @DisplayName("越界路径被拒")
  void rejectsTraversal() {
    assertThrows(
        LytgosException.SandboxViolationException.class,
        () -> tool().execute(Map.of("path", "..")));
  }
}
