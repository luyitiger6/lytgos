package io.lytgos.runtime;

import io.lytgos.core.LytgosException;
import io.lytgos.persona.AgentLoader;

/**
 * API 会话标识的拼装与反解。
 *
 * <p>形如 {@code api:<user>:<agent>}，与 CLI 的 {@code cli:local:<agent>} 同构：从 key 就能定位 agent，
 * 不需要额外的元数据表；同一 {@code (user, agent)} 天然幂等，重复创建只会命中同一个会话。
 *
 * <p>{@code user} 与 {@code agent} 都不允许含 {@code :}——否则反解不出三元组，一个能创建却读不回来的会话 比直接拒绝更糟。{@code agent}
 * 还要满足 {@link AgentLoader#validateName(String)}，因为它最终会落到文件路径上。
 */
public final class SessionKeys {

  /** 未指定 user 时的缺省值：REST 一期不是多用户系统。 */
  public static final String DEFAULT_USER = "api-default";

  private static final String PREFIX = "api";
  private static final String SEPARATOR = ":";

  private SessionKeys() {}

  /**
   * 按 {@code (user, agent)} 拼出会话 key。
   *
   * @param user 为空时取 {@link #DEFAULT_USER}
   * @throws LytgosException.InvalidAgentDefinitionException user 或 agent 不合法
   */
  public static String of(String user, String agent) {
    AgentLoader.validateName(agent);
    requireNoSeparator(agent, "Agent 名称不合法: " + agent);
    String actualUser = user == null || user.isBlank() ? DEFAULT_USER : user.strip();
    requireNoSeparator(actualUser, "user 名称不合法: " + user);
    return PREFIX + SEPARATOR + actualUser + SEPARATOR + agent;
  }

  /**
   * 从会话 key 反解 agent 名。
   *
   * <p>只接受本类拼出的格式：多一段、少一段、前缀不符都当场拒绝，不尝试「尽量理解」。
   *
   * @throws LytgosException.InvalidAgentDefinitionException key 不是合法的 API 会话标识
   */
  public static String parse(String key) {
    if (key == null || !key.startsWith(PREFIX + SEPARATOR)) {
      throw invalid(key);
    }
    String rest = key.substring(PREFIX.length() + SEPARATOR.length());
    int separator = rest.indexOf(SEPARATOR);
    if (separator <= 0 || separator == rest.length() - 1) {
      throw invalid(key);
    }
    String user = rest.substring(0, separator);
    String agent = rest.substring(separator + 1);
    if (agent.contains(SEPARATOR) || user.isBlank()) {
      throw invalid(key);
    }
    AgentLoader.validateName(agent);
    return agent;
  }

  private static void requireNoSeparator(String value, String message) {
    if (value.contains(SEPARATOR)) {
      throw new LytgosException.InvalidAgentDefinitionException(message);
    }
  }

  private static LytgosException.InvalidAgentDefinitionException invalid(String key) {
    return new LytgosException.InvalidAgentDefinitionException("会话标识不合法: " + key);
  }
}
