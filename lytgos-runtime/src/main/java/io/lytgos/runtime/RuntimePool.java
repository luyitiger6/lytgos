package io.lytgos.runtime;

import io.lytgos.core.session.Session;
import io.lytgos.core.tool.ToolRegistry;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * 按 Agent 缓存运行时，并按会话串行执行。
 *
 * <p>为什么必须缓存：{@link AgentRuntimeFactory#runtimeFor(String)} 会启动 Agent 声明的 MCP 子进程， 每个 HTTP
 * 请求创建一次就是进程炸弹；同时每次新建也会丢掉已建立的 MCP 会话。
 *
 * <p>为什么按会话加锁而不是全局一把锁：同一会话的两个请求并发写同一个 {@link Session} 会互相覆盖； 不同会话之间没有共享可变状态，全局锁只会白白拉低吞吐。
 *
 * <p>创建失败不缓存：{@link ConcurrentHashMap#computeIfAbsent} 的计算函数抛异常时不会写入映射， 下次请求会重新尝试——缺少 API key
 * 这类暂时性失败不该被永久钉住；已拉起的部分子进程由 {@link AgentRuntimeFactory} 的失败路径回收。
 */
public final class RuntimePool implements AgentInvoker, AutoCloseable {

  private final Function<String, RuntimeHandle> fullFactory;
  private final Function<String, RuntimeHandle> toolFactory;

  /** 带对话循环的运行时：需要模型凭据，按 Agent 懒创建。 */
  private final Map<String, RuntimeHandle> runtimes = new ConcurrentHashMap<>();

  /**
   * 只装配工具的运行时：{@code GET /tools} 不该因为没设 API key 就失败。
   *
   * <p>与 {@link #runtimes} 分开存是为了「先查工具、再发消息」时不重复启动 MCP 子进程： 发消息时若发现这里已有一份，会先关掉它再建完整运行时（见 {@link
   * #runtimeFor(String)}）。
   */
  private final Map<String, RuntimeHandle> toolOnlyRuntimes = new ConcurrentHashMap<>();

  private final Map<String, ReentrantLock> sessionLocks = new ConcurrentHashMap<>();

  public RuntimePool(AgentRuntimeFactory factory) {
    this(factory::runtimeFor, factory::toolsFor);
  }

  /** 测试接缝：生产只走 {@link #RuntimePool(AgentRuntimeFactory)}，这里让单测注入假句柄。 */
  RuntimePool(
      Function<String, RuntimeHandle> fullFactory, Function<String, RuntimeHandle> toolFactory) {
    this.fullFactory = fullFactory;
    this.toolFactory = toolFactory;
  }

  @Override
  public String run(String agent, Session session, String input) {
    ReentrantLock lock = sessionLocks.computeIfAbsent(session.key(), key -> new ReentrantLock());
    lock.lock();
    try {
      return runtimeFor(agent).run(session, input);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public ToolRegistry tools(String agent) {
    RuntimeHandle full = runtimes.get(agent);
    if (full != null) {
      return full.tools();
    }
    return toolOnlyRuntimes.computeIfAbsent(agent, toolFactory).tools();
  }

  private RuntimeHandle runtimeFor(String agent) {
    return runtimes.computeIfAbsent(
        agent,
        name -> {
          closeQuietly(toolOnlyRuntimes.remove(name));
          return fullFactory.apply(name);
        });
  }

  /** 关闭全部已创建的运行时；MCP 子进程不关就会在用户机器上留孤儿。 */
  @Override
  @PreDestroy
  public void close() {
    for (RuntimeHandle runtime : runtimes.values()) {
      closeQuietly(runtime);
    }
    for (RuntimeHandle runtime : toolOnlyRuntimes.values()) {
      closeQuietly(runtime);
    }
    runtimes.clear();
    toolOnlyRuntimes.clear();
    sessionLocks.clear();
  }

  private static void closeQuietly(RuntimeHandle runtime) {
    if (runtime != null) {
      runtime.close();
    }
  }
}
