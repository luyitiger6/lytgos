package io.lytgos.runtime;

import io.lytgos.core.session.Session;
import io.lytgos.core.tool.ToolRegistry;

/**
 * 执行一次对话轮次与查询工具清单的端口。
 *
 * <p>控制器依赖本接口而不是 {@link AgentRuntimeFactory}：后者每次调用都产出一份新运行时（含 MCP 子进程）， HTTP 并发下会直接变成进程炸弹；而 {@link
 * AgentRuntime} 构造器包私有、final，Web 层单测无法替换。 有了这层端口，单测用假实现就能覆盖控制器逻辑，不启动任何模型或子进程。
 */
public interface AgentInvoker {

  /**
   * 在给定会话上跑完一轮对话，返回模型最终文本。
   *
   * <p>同一会话的并发调用由实现方串行化：{@link Session} 是可变对象，两个请求并发 {@code append} 会互相覆盖。
   */
  String run(String agent, Session session, String input);

  /** 该 Agent 实际可用的工具（内置 + MCP，已按白名单筛过）。只读元数据，不需要模型凭据。 */
  ToolRegistry tools(String agent);
}
