package io.lytgos.memory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 长期记忆的唯一读写入口：工作区 {@code memory/MEMORY.md}。
 *
 * <p>写入只追加、不淘汰：旧条目是用户资产，为容纳新条目悄悄丢掉一条，是最难被发现的破坏。 超过容量上限时整体拒绝，让用户显式决定清理哪一条。
 *
 * <p>文件按 UTF-8 纯文本处理，不解析 Markdown 结构——记忆长什么样留给用户自己决定。
 */
public final class MemoryStore {

  /** 记忆文件的相对路径，启动注入与工具读写共用同一位置。 */
  public static final String RELATIVE_PATH = "memory/MEMORY.md";

  /** 容量上限（字符）。与工具回填上限同一量级，避免把整个上下文挤掉。 */
  public static final int MAX_CHARS = 8000;

  private static final String ENTRY_PREFIX = "- ";

  private final Path directory;
  private final Path file;

  public MemoryStore(Path workspaceRoot) {
    this.directory = workspaceRoot.toAbsolutePath().normalize().resolve("memory");
    this.file = directory.resolve("MEMORY.md");
  }

  /** 当前记忆文件路径。 */
  public Path file() {
    return file;
  }

  /** 读取全文；文件不存在时返回空串，不把「还没记忆」当成故障。 */
  public String read() {
    if (!Files.isRegularFile(file)) {
      return "";
    }
    try {
      return Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException("读取记忆失败: " + file + " → " + e.getMessage(), e);
    }
  }

  /**
   * 追加一条记忆，返回写入后的总字符数。
   *
   * <p>调用方不需要自带列表符号，格式由本方法统一加上。空内容直接拒绝：空条目只会让文件变长， 不会让 Agent 更懂用户。
   *
   * @throws CapacityExceededException 追加后超过 {@link #MAX_CHARS}，文件保持原样
   */
  public int append(String content) {
    String entry = content == null ? "" : content.strip();
    if (entry.isEmpty()) {
      throw new IllegalArgumentException("记忆内容为空");
    }
    String existing = read();
    StringBuilder block = new StringBuilder();
    if (!existing.isEmpty() && !existing.endsWith("\n")) {
      block.append('\n');
    }
    block.append(ENTRY_PREFIX).append(entry).append('\n');
    int projected = existing.length() + block.length();
    if (projected > MAX_CHARS) {
      throw new CapacityExceededException(
          "记忆已满：当前 "
              + existing.length()
              + " 字符，本条写入后 "
              + projected
              + " 字符，上限 "
              + MAX_CHARS
              + " 字符。请先手工清理 memory/MEMORY.md 中的旧条目再保存。");
    }
    try {
      Files.createDirectories(directory);
      // 只追加新增块，不做整文件截断重写：崩溃或并发时最坏多一截脏尾，旧条目仍是用户资产
      try (var writer =
          Files.newBufferedWriter(
              file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
        writer.write(block.toString());
      }
    } catch (IOException e) {
      throw new IllegalStateException("写入记忆失败: " + file + " → " + e.getMessage(), e);
    }
    return projected;
  }

  /** 追加会突破容量上限；抛出时文件一个字节都没改。 */
  public static final class CapacityExceededException extends IllegalStateException {
    public CapacityExceededException(String message) {
      super(message);
    }
  }
}
