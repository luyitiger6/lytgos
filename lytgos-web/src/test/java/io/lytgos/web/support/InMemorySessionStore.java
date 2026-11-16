package io.lytgos.web.support;

import io.lytgos.core.session.Session;
import io.lytgos.core.session.SessionStore;
import io.lytgos.core.session.SessionSummary;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 进程内会话存储：控制器测试不碰 SQLite，也不依赖任何文件。 */
public final class InMemorySessionStore implements SessionStore {

  private final Map<String, Session> sessions = new ConcurrentHashMap<>();

  @Override
  public Session get(String key) {
    Session stored = sessions.get(key);
    return stored == null ? new Session(key) : stored;
  }

  @Override
  public void save(Session session) {
    sessions.put(session.key(), session);
  }

  @Override
  public boolean exists(String key) {
    return sessions.containsKey(key);
  }

  @Override
  public List<SessionSummary> list() {
    return sessions.values().stream()
        .map(session -> new SessionSummary(session.key(), session.size(), Instant.now().toString()))
        .sorted(Comparator.comparing(SessionSummary::key))
        .toList();
  }

  @Override
  public void delete(String key) {
    sessions.remove(key);
  }

  /** 用例之间隔离：同一上下文被多个测试类复用。 */
  public void reset() {
    sessions.clear();
  }
}
