package io.lytgos.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("记忆工具：save_memory 与 recall_memory 的边界")
class MemoryToolsTest {

  @TempDir Path root;

  private MemoryStore store() {
    return new MemoryStore(root);
  }

  @Test
  @DisplayName("save_memory 保存成功并返回当前大小")
  void savesAndReportsSize() {
    MemoryStore store = store();

    String result = new SaveMemoryTool(store).execute(Map.of("content", "我用 Spring Boot"));

    assertTrue(result.contains("已保存"), "应返回成功文本");
    assertTrue(result.contains("8000"), "应带出上限值（spec 条款 8000 字符，不拿常量自证）");
    assertTrue(store.read().contains("我用 Spring Boot"));
  }

  @Test
  @DisplayName("超限时返回拒绝文本，文件不变")
  void rejectsWhenOverLimit() throws IOException {
    Path directory = Files.createDirectories(root.resolve("memory"));
    Path file = directory.resolve("MEMORY.md");
    String full = "x".repeat(MemoryStore.MAX_CHARS);
    Files.writeString(file, full, StandardCharsets.UTF_8);

    String result = new SaveMemoryTool(store()).execute(Map.of("content", "再来一条"));

    assertTrue(result.contains("未保存"), "应明确表示没有保存");
    assertTrue(result.contains("8000"), "拒绝文本应写出 spec 规定的 8000 上限");
    assertTrue(result.contains("清理"), "应给出清理建议");
    assertEquals(full, Files.readString(file, StandardCharsets.UTF_8));
  }

  @Test
  @DisplayName("recall_memory 读回已保存内容")
  void recallsSavedContent() {
    MemoryStore store = store();
    new SaveMemoryTool(store).execute(Map.of("content", "我用 Spring Boot"));

    String result = new RecallMemoryTool(store).execute(Map.of());

    assertTrue(result.contains("- 我用 Spring Boot"));
  }

  @Test
  @DisplayName("recall_memory 在空记忆时返回可读文本")
  void recallsEmptyMemory() {
    String result = new RecallMemoryTool(store()).execute(Map.of());

    assertTrue(result.contains("暂无记忆"));
  }
}
