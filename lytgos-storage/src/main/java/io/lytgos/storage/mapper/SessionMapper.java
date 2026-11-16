package io.lytgos.storage.mapper;

import java.util.List;
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

  /** 该 key 是否有行。用 {@code COUNT(1)} 而不是取正文：判断存在不需要把历史读出来。 */
  int exists(@Param("key") String key);

  /** 全部会话的原始行，按最后更新时间倒序（最近活跃的会话排在最前）。 */
  List<SessionRow> selectAll();

  /** 删除一行；没有该行时返回 0。 */
  int delete(@Param("key") String key);
}
