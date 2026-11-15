package io.lytgos.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("MemoryStore：MEMORY.md 的追加、上限与缺失处理")
class MemoryStoreTest {

  @TempDir Path root;

  private MemoryStore store() {
    return new MemoryStore(root);
  }

  @Test
  @DisplayName("追加后可读回，条目统一带列表符号")
  void appendsAndReadsBack() {
    MemoryStore store = store();

    int size = store.append("我用 Spring Boot");

    assertTrue(size > 0, "应返回写入后的字符数");
    String content = store.read();
    assertTrue(content.contains("- 我用 Spring Boot"), "条目应带统一列表符号");
    assertEquals(size, content.length(), "返回值应与文件实际长度一致");
  }

  @Test
  @DisplayName("超限时整体拒绝，文件字节不变")
  void rejectsOverLimitWithoutTouchingFile() throws IOException {
    Path directory = Files.createDirectories(root.resolve("memory"));
    Path file = directory.resolve("MEMORY.md");
    String full = "x".repeat(MemoryStore.MAX_CHARS);
    Files.writeString(file, full, StandardCharsets.UTF_8);

    MemoryStore.CapacityExceededException thrown =
        assertThrows(MemoryStore.CapacityExceededException.class, () -> store().append("再来一条"));

    assertTrue(thrown.getMessage().contains("8000"), "上限值是 spec 条款，不能只拿常量自证");
    assertTrue(thrown.getMessage().contains("清理"));
    assertEquals(8000, MemoryStore.MAX_CHARS, "上限值属于 spec（8000 字符），改动必须先改 spec");
    assertEquals(full, Files.readString(file, StandardCharsets.UTF_8), "拒绝时不得部分写入");
  }

  @Test
  @DisplayName("文件缺失时读取为空串")
  void readsEmptyWhenFileMissing() {
    assertFalse(Files.exists(root.resolve("memory").resolve("MEMORY.md")));
    assertEquals("", store().read());
  }

  @Test
  @DisplayName("首次写入自动创建目录与文件")
  void createsFileOnFirstWrite() {
    MemoryStore store = store();
    assertFalse(Files.exists(store.file()), "前置条件：文件尚不存在");

    store.append("第一条记忆");

    assertTrue(Files.isRegularFile(store.file()));
  }

  @Test
  @DisplayName("空白内容被拒绝")
  void rejectsBlankContent() {
    assertThrows(IllegalArgumentException.class, () -> store().append("   "));
  }
}
