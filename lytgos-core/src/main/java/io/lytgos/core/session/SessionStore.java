package io.lytgos.core.session;

import java.util.List;

/**
 * 会话存取。
 *
 * <p>读写之外还有三件与会话同源的事：判断存在（{@link #exists}）、列出全部（{@link #list}）、归档（{@link
 * #delete}）。它们放在同一个接口而不是拆成查询接口，是因为调用方总是同时需要「按 key 读」与「知道有哪些 key」——拆开只会让调用方多持有一个依赖。
 *
 * <p>{@code exists} 不可省：{@link #get(String)} 对不存在的 key 返回空历史会话（既有契约），无法区分「刚建的空会话」与「不存在」。
 *
 * <p>第 4 周换 SQLite 时只替换实现类，本接口不动。
 */
public interface SessionStore {

  /** 取出会话；不存在时返回空历史的会话，而不是 {@code null}。 */
  Session get(String key);

  /** 覆盖落盘当前历史。 */
  void save(Session session);

  /** 该 key 是否存在于持久层。判断依据是存储而非进程缓存——缓存里的空会话也可能从未落盘。 */
  boolean exists(String key);

  /** 全部会话的摘要，不含历史正文；没有会话时返回空列表。 */
  List<SessionSummary> list();

  /** 归档会话：之后的 {@link #get(String)} 仍返回空历史，但 {@link #exists(String)} 为 false。不存在的 key 是 no-op。 */
  void delete(String key);
}
