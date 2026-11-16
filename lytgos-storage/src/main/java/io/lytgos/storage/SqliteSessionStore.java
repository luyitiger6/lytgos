package io.lytgos.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lytgos.core.LytgosException;
import io.lytgos.core.chat.Message;
import io.lytgos.core.session.Session;
import io.lytgos.core.session.SessionStore;
import io.lytgos.core.session.SessionSummary;
import io.lytgos.core.util.LogSafe;
import io.lytgos.storage.mapper.SessionMapper;
import io.lytgos.storage.mapper.SessionRow;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.ibatis.exceptions.PersistenceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 会话的 SQLite 实现（MyBatis Mapper）。
 *
 * <p>{@code messages} 列存 {@link Message} 列表的 JSON，与 {@code FileSessionStore} 同一序列化
 * 形态：会话是「一段结构化历史」而不是可查询的关系数据，拆表只会让恢复逻辑变复杂。
 *
 * <p>内存里保留一份 {@link Session} 缓存：{@link Session} 是可变对象，同一进程内两次 {@code get} 若返回不同实例，第二次 {@code save}
 * 会覆盖第一次的追加。跨进程一致性由 {@code save} 的 {@code ON CONFLICT DO UPDATE} 保证——不读旧值、不合并，以最后一次写入为准。
 *
 * <p>旧 {@code sessions/*.json} 不读不删：迁移会引入一次性双读分支，而当前没有需要保住的 生产会话（见 change 的 Migration Plan）。
 */
public final class SqliteSessionStore implements SessionStore {

  private static final Logger LOG = LoggerFactory.getLogger(SqliteSessionStore.class);

  private final SessionMapper mapper;
  private final ObjectMapper objectMapper;
  private final Map<String, Session> cache = new ConcurrentHashMap<>();

  public SqliteSessionStore(SessionMapper mapper, ObjectMapper objectMapper) {
    this.mapper = mapper;
    this.objectMapper = objectMapper;
  }

  @Override
  public Session get(String key) {
    return cache.computeIfAbsent(key, this::load);
  }

  @Override
  public void save(Session session) {
    try {
      mapper.upsert(
          session.key(),
          objectMapper.writeValueAsString(session.history()),
          Instant.now().toString());
      // 消息数能直接区分「历史没恢复」与「历史恢复了但被覆盖」：两者看库都不报错。
      LOG.debug(
          "会话已保存: key={} messages={}", LogSafe.sanitize(session.key()), session.history().size());
    } catch (PersistenceException | IOException e) {
      throw new LytgosException("会话落盘失败: " + session.key(), e);
    }
  }

  @Override
  public boolean exists(String key) {
    try {
      return mapper.exists(key) > 0;
    } catch (PersistenceException e) {
      throw new LytgosException("会话查询失败: " + key, e);
    }
  }

  @Override
  public List<SessionSummary> list() {
    List<SessionRow> rows;
    try {
      rows = mapper.selectAll();
    } catch (PersistenceException e) {
      throw new LytgosException("会话列表读取失败", e);
    }
    List<SessionSummary> summaries = new ArrayList<>(rows.size());
    for (SessionRow row : rows) {
      summaries.add(new SessionSummary(row.key(), messageCount(row), row.updatedAt()));
    }
    return summaries;
  }

  @Override
  public void delete(String key) {
    try {
      mapper.delete(key);
      cache.remove(key);
      LOG.debug("会话已归档: key={}", LogSafe.sanitize(key));
    } catch (PersistenceException e) {
      throw new LytgosException("会话归档失败: " + key, e);
    }
  }

  /** 单行解析失败只让这条计为 0 条消息：一条写坏的记录不该把整个列表变成 500，但必须留日志以便定位。 */
  private int messageCount(SessionRow row) {
    if (row.messages() == null || row.messages().isBlank()) {
      return 0;
    }
    try {
      return objectMapper.readValue(row.messages(), Message[].class).length;
    } catch (IOException e) {
      LOG.warn("会话消息不是合法 JSON，列表按 0 条计: key={}", LogSafe.sanitize(row.key()));
      return 0;
    }
  }

  private Session load(String key) {
    Session session = new Session(key);
    try {
      String json = mapper.selectMessages(key);
      if (json == null) {
        return session;
      }
      Message[] messages = objectMapper.readValue(json, Message[].class);
      session.restore(List.of(messages));
    } catch (PersistenceException | IOException e) {
      throw new LytgosException("会话读取失败: " + key, e);
    }
    return session;
  }
}
