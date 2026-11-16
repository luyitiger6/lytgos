package io.lytgos.runtime;

import io.lytgos.core.session.Session;
import io.lytgos.core.tool.ToolRegistry;

/**
 * 一个已就绪运行时的最小句柄：跑一轮、看工具、回收资源。
 *
 * <p>存在的理由和 {@link AgentInvoker} 相同——{@link RuntimePool} 的复用与回收必须有单测， 而 {@link AgentRuntime} 的构造会拉起
 * MCP 子进程。控制器不用这个类型，它只服务于池的生命周期； 因此保持包内可见，不扩大模块对外 API。
 */
interface RuntimeHandle extends AutoCloseable {

  /** 在给定会话上跑完一轮对话，返回模型最终文本。 */
  String run(Session session, String input);

  /** 该运行时实际可用的工具。 */
  ToolRegistry tools();

  @Override
  void close();
}
