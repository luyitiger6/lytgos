package io.lytgos.core.session;

/**
 * 会话存取。
 *
 * <p>接口只有「取」与「存」两个动作，没有 {@code delete} 也没有 {@code list}——
 * 第 1 周没有删除会话的需求，多加一个方法就要多实现一个方法，
 * 而它是否该出现在第 4 周的 SQLite 实现里还没人知道。</p>
 *
 * <p>第 4 周换 SQLite 时只替换实现类，本接口不动。</p>
 */
public interface SessionStore {

    /** 取出会话；不存在时返回空历史的会话，而不是 {@code null}。 */
    Session get(String key);

    /** 覆盖落盘当前历史。 */
    void save(Session session);
}
