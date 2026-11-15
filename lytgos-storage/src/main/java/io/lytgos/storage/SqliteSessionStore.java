package io.lytgos.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lytgos.core.LytgosException;
import io.lytgos.core.chat.Message;
import io.lytgos.core.session.Session;
import io.lytgos.core.session.SessionStore;
import io.lytgos.storage.mapper.SessionMapper;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.ibatis.exceptions.PersistenceException;

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
    } catch (PersistenceException | IOException e) {
      throw new LytgosException("会话落盘失败: " + session.key(), e);
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
