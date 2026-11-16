package io.lytgos.core.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lytgos.core.LytgosException;
import io.lytgos.core.chat.Message;
import io.lytgos.core.util.LogSafe;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 会话的文件实现：内存缓存 + JSON 落盘。
 *
 * <p>为什么不是纯内存：{@code --message} 单条模式发一条就退出进程， 纯内存方案下两次单条调用物理上不可能共享上下文，「单条模式与交互模式共用同一 Session」
 * 这条口径就无法成立。
 *
 * <p>文件名用 key 的 Base64URL 编码而不是直接拼 key：{@code cli:local:default} 里的冒号 在 Windows
 * 文件名里非法，而把冒号替换成下划线又会让 {@code a:b} 与 {@code a_b} 撞名。
 *
 * <p>写入走「临时文件 + 原子改名」：直接覆盖原文件时若进程在写一半时退出， 留下的是半截 JSON，下次启动读不出来。
 */
public final class FileSessionStore implements SessionStore {

  private static final Logger LOG = LoggerFactory.getLogger(FileSessionStore.class);

  private final Path directory;
  private final ObjectMapper mapper;
  private final Map<String, Session> cache = new ConcurrentHashMap<>();

  public FileSessionStore(Path directory, ObjectMapper mapper) {
    this.directory = directory;
    this.mapper = mapper;
    try {
      Files.createDirectories(directory);
    } catch (IOException e) {
      throw new LytgosException("无法创建会话目录: " + directory, e);
    }
  }

  @Override
  public Session get(String key) {
    return cache.computeIfAbsent(key, this::load);
  }

  @Override
  public void save(Session session) {
    Path file = fileOf(session.key());
    Path temp = file.resolveSibling(file.getFileName() + ".tmp");
    try {
      Snapshot snapshot = new Snapshot(session.key(), session.history());
      Files.writeString(temp, mapper.writeValueAsString(snapshot), StandardCharsets.UTF_8);
      try {
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (IOException e) {
      throw new LytgosException("会话落盘失败: " + file, e);
    }
  }

  private Session load(String key) {
    Path file = fileOf(key);
    Session session = new Session(key);
    if (!Files.exists(file)) {
      return session;
    }
    try {
      Snapshot snapshot =
          mapper.readValue(Files.readString(file, StandardCharsets.UTF_8), Snapshot.class);
      session.restore(snapshot.messages() == null ? List.of() : snapshot.messages());
    } catch (IOException e) {
      // 坏文件不静默丢弃：改名留证，再从空历史开始，否则每次启动都会踩同一颗雷
      Path broken = file.resolveSibling(file.getFileName() + ".broken");
      try {
        Files.move(file, broken, StandardCopyOption.REPLACE_EXISTING);
      } catch (IOException ignored) {
        // 改不了名就保留原文件，异常信息里已带路径，人工可查
      }
      LOG.warn(
          "会话文件损坏，已备份为 {}: {}",
          LogSafe.sanitize(broken.getFileName()),
          LogSafe.sanitize(e.getMessage()));
    }
    return session;
  }

  @Override
  public boolean exists(String key) {
    return Files.isRegularFile(fileOf(key));
  }

  /**
   * 遍历 {@code sessions/*.json} 反序列化取消息数；最后更新时间取文件修改时间。
   *
   * <p>坏文件不中断列表：它与 {@link #load(String)} 同口径——一条写坏的记录不应让整个列表变 500， 但这条不计入结果（{@code load} 会把它改名留证）。
   */
  @Override
  public List<SessionSummary> list() {
    List<FileEntry> entries = new ArrayList<>();
    try (Stream<Path> files = Files.list(directory)) {
      for (Path file : files.filter(FileSessionStore::isSessionFile).toList()) {
        FileEntry entry = summarize(file);
        if (entry != null) {
          entries.add(entry);
        }
      }
    } catch (IOException e) {
      throw new LytgosException("会话列表读取失败: " + directory, e);
    }
    entries.sort(Comparator.comparingLong(FileEntry::lastModifiedMillis).reversed());
    return entries.stream().map(FileEntry::summary).toList();
  }

  @Override
  public void delete(String key) {
    try {
      Files.deleteIfExists(fileOf(key));
      cache.remove(key);
      LOG.debug("会话已归档: key={}", LogSafe.sanitize(key));
    } catch (IOException e) {
      throw new LytgosException("会话归档失败: " + key, e);
    }
  }

  private FileEntry summarize(Path file) {
    try {
      Snapshot snapshot =
          mapper.readValue(Files.readString(file, StandardCharsets.UTF_8), Snapshot.class);
      List<Message> messages = snapshot.messages() == null ? List.of() : snapshot.messages();
      FileTime modified = Files.getLastModifiedTime(file);
      String updatedAt = Instant.ofEpochMilli(modified.toMillis()).toString();
      return new FileEntry(
          new SessionSummary(snapshot.key(), messages.size(), updatedAt), modified.toMillis());
    } catch (IOException e) {
      LOG.warn("会话文件不可读，已从列表跳过: {}", LogSafe.sanitize(file.getFileName()));
      return null;
    }
  }

  private static boolean isSessionFile(Path file) {
    Path name = file.getFileName();
    // getFileName() 对无 name 元素的路径（如文件系统根）返回 null，先判空再解引用
    return name != null && name.toString().endsWith(".json") && Files.isRegularFile(file);
  }

  /** 摘要与排序键分开携带：摘要里的 ISO 字符串不适合作为毫秒级排序依据。 */
  private record FileEntry(SessionSummary summary, long lastModifiedMillis) {}

  private Path fileOf(String key) {
    String name =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(key.getBytes(StandardCharsets.UTF_8));
    return directory.resolve(name + ".json");
  }

  /**
   * 落盘结构。
   *
   * <p>刻意与 {@link Session} 分开：内核类型不该为了序列化去挂 Jackson 注解， 落盘格式变化也不该逼着内核类型跟着改。
   */
  record Snapshot(String key, List<Message> messages) {}
}
