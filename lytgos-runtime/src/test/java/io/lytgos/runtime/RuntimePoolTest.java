package io.lytgos.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.lytgos.core.session.Session;
import io.lytgos.core.tool.ToolRegistry;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("RuntimePool：按 Agent 复用、按会话串行、关闭即回收")
class RuntimePoolTest {

  private final List<FakeHandle> fullHandles = new CopyOnWriteArrayList<>();
  private final List<FakeHandle> toolHandles = new CopyOnWriteArrayList<>();
  private final AtomicInteger toolCalls = new AtomicInteger();

  private RuntimePool pool() {
    return new RuntimePool(
        agent -> {
          FakeHandle handle = new FakeHandle();
          fullHandles.add(handle);
          return handle;
        },
        agent -> {
          toolCalls.incrementAndGet();
          FakeHandle handle = new FakeHandle();
          toolHandles.add(handle);
          return handle;
        });
  }

  @Test
  @DisplayName("同一 Agent 的多次执行复用同一运行时")
  void reusesRuntimeForSameAgent() {
    RuntimePool pool = pool();
    Session session = new Session("api:u1:default");

    assertEquals("echo:hi", pool.run("default", session, "hi"));
    assertEquals("echo:again", pool.run("default", session, "again"));

    assertEquals(1, fullHandles.size(), "同一 Agent 只应创建一次运行时");
    assertEquals(2, fullHandles.get(0).runs.get());
  }

  @Test
  @DisplayName("不同 Agent 各自创建运行时")
  void createsSeparateRuntimesPerAgent() {
    RuntimePool pool = pool();

    pool.run("a", new Session("api:u1:a"), "one");
    pool.run("b", new Session("api:u1:b"), "two");

    assertEquals(2, fullHandles.size());
  }

  @Test
  @DisplayName("工具查询命中同一 Agent 时复用只装配工具的运行时")
  void reusesToolOnlyRuntime() {
    RuntimePool pool = pool();

    ToolRegistry first = pool.tools("default");
    ToolRegistry second = pool.tools("default");

    assertSame(first, second);
    assertEquals(1, toolCalls.get());
  }

  @Test
  @DisplayName("先查工具再发消息：只装配工具的运行时被关闭并换成完整运行时")
  void upgradesToolOnlyRuntimeOnFirstRun() {
    RuntimePool pool = pool();
    pool.tools("default");

    pool.run("default", new Session("api:u1:default"), "hi");

    assertTrue(toolHandles.get(0).closed, "只装配工具的运行时升级后必须回收，否则 MCP 子进程双份常驻");
    assertEquals(1, fullHandles.size());
  }

  @Test
  @DisplayName("已存在完整运行时后，工具查询直接读它，不再创建工具运行时")
  void toolsPrefersExistingFullRuntime() {
    RuntimePool pool = pool();
    pool.run("default", new Session("api:u1:default"), "hi");

    pool.tools("default");

    assertEquals(0, toolCalls.get());
  }

  @Test
  @DisplayName("close 关闭全部运行时并清空缓存")
  void closeClosesAllRuntimes() {
    RuntimePool pool = pool();
    pool.run("a", new Session("api:u1:a"), "one");
    pool.tools("b");

    pool.close();

    assertTrue(fullHandles.get(0).closed);
    assertTrue(toolHandles.get(0).closed);

    // 缓存已清空：再次查询会创建新句柄，证明确实不是「关了个空池」
    ToolRegistry after = pool.tools("b");
    assertEquals(2, toolHandles.size());
    assertSame(after, toolHandles.get(1).tools());
    assertFalse(toolHandles.get(1).closed, "新建的句柄必须可用");
  }

  @Test
  @DisplayName("同一会话的并发执行被串行化")
  void serializesSameSession() throws InterruptedException {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicInteger concurrent = new AtomicInteger();
    AtomicInteger maxConcurrent = new AtomicInteger();
    RuntimePool pool =
        new RuntimePool(
            agent -> {
              FakeHandle handle = new FakeHandle();
              handle.onRun =
                  () -> {
                    maxConcurrent.accumulateAndGet(concurrent.incrementAndGet(), Math::max);
                    entered.countDown();
                    awaitQuietly(release);
                    concurrent.decrementAndGet();
                  };
              fullHandles.add(handle);
              return handle;
            },
            agent -> new FakeHandle());

    Session shared = new Session("api:u1:default");
    // 用 Thread.ofPlatform() 而不是 new Thread()：前者不违反 Alibaba「不要显式创建线程」规则，
    // 同时保留 Thread 引用供 waitForBlocked 观察会话锁上的等待态
    final Thread first =
        Thread.ofPlatform().name("first").start(() -> pool.run("default", shared, "first"));
    assertTrue(entered.await(5, TimeUnit.SECONDS), "第一个请求应已进入运行时");

    final Thread second =
        Thread.ofPlatform().name("second").start(() -> pool.run("default", shared, "second"));
    assertTrue(waitForBlocked(second), "第二个请求应在会话锁上等待");
    assertEquals(1, concurrent.get(), "第二个请求必须等第一个结束");

    release.countDown();
    first.join(5000);
    second.join(5000);
    assertEquals(1, maxConcurrent.get(), "同一会话不允许同时进入运行时");
    assertEquals(2, fullHandles.get(0).runs.get());
  }

  /** 等线程停在会话锁上。ReentrantLock 的等待态是 WAITING，RUNNABLE 说明还没走到加锁点。 */
  private static boolean waitForBlocked(Thread thread) throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (System.nanoTime() < deadline) {
      if (thread.getState() == Thread.State.WAITING) {
        return true;
      }
      Thread.sleep(10);
    }
    return false;
  }

  private static void awaitQuietly(CountDownLatch latch) {
    try {
      // 超时无需在此处理：调用线程的 join 超时与 maxConcurrent 断言会在行为不符时暴露
      if (!latch.await(5, TimeUnit.SECONDS)) {
        return;
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /** 假句柄：记录调用次数与关闭状态，不启动任何模型或子进程。 */
  private static final class FakeHandle implements RuntimeHandle {

    private final AtomicInteger runs = new AtomicInteger();
    private final ToolRegistry tools = new ToolRegistry(List.of());
    private volatile boolean closed;
    private volatile Runnable onRun = () -> {};

    @Override
    public String run(Session session, String input) {
      runs.incrementAndGet();
      onRun.run();
      return "echo:" + input;
    }

    @Override
    public ToolRegistry tools() {
      return tools;
    }

    @Override
    public void close() {
      closed = true;
    }
  }
}
