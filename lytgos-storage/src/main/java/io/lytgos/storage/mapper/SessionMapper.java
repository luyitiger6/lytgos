package io.lytgos.storage.mapper;

import org.apache.ibatis.annotations.Param;

/** 会话表的读写：{@code messages} 列存 {@code Message} 列表的 JSON。 */
public interface SessionMapper {

  /** 未命中的会话返回 {@code null}，由调用方决定「空历史」的语义。 */
  String selectMessages(@Param("key") String key);

  /**
   * 同键覆盖写。
   *
   * <p>{@code ON CONFLICT DO UPDATE} 而不是先删后插：跨进程并发保存时只有一条语句， 不会出现「删了还没插」的空窗。
   */
  int upsert(
      @Param("key") String key,
      @Param("messages") String messages,
      @Param("updatedAt") String updatedAt);
}
