package io.lytgos.tool;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.lytgos.core.LytgosException;
import io.lytgos.core.tool.SandboxChecker;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("ShellTool：白名单、超时与不做 shell 解析")
class ShellToolTest {

  @TempDir Path root;

  private ShellTool tool(Set<String> allowed) {
    return new ShellTool(new SandboxChecker(root, allowed, Set.of()), root, allowed);
  }

  @Test
  @DisplayName("白名单内命令可执行，spec 展示白名单")
  void runsWhitelistedCommand() {
    ShellTool tool = tool(Set.of("java"));

    String result = tool.execute(Map.of("command", "java -version"));

    assertTrue(result.contains("exit="), "应返回退出码: " + result);
    assertTrue(result.toLowerCase(Locale.ROOT).contains("version"), "应包含 java 版本信息: " + result);
    assertTrue(tool.spec().description().contains("java"), "spec 应展示当前白名单");
  }

  @Test
  @DisplayName("白名单外命令抛出沙箱违规")
  void rejectsCommandOutsideWhitelist() {
    LytgosException.SandboxViolationException thrown =
        assertThrows(
            LytgosException.SandboxViolationException.class,
            () -> tool(Set.of("java")).execute(Map.of("command", "rm -rf /")));

    assertTrue(thrown.getMessage().contains("sandbox"));
    assertTrue(thrown.getMessage().contains("rm"));
  }

  @Test
  @DisplayName("白名单为空时全部拒绝")
  void rejectsEverythingWhenWhitelistEmpty() {
    LytgosException.SandboxViolationException thrown =
        assertThrows(
            LytgosException.SandboxViolationException.class,
            () -> tool(Set.of()).execute(Map.of("command", "java -version")));

    assertTrue(thrown.getMessage().contains("白名单为空"));
  }

  @Test
  @DisplayName("超时终止进程并返回超时文本，子进程真正退出")
  void terminatesOnTimeout() throws IOException {
    String classpath =
        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
    // 相对路径：ShellTool 把工作目录设成 root，夹具子进程按同一 cwd 写 pid
    Path pidFile = root.resolve("sleepy.pid");
    Files.deleteIfExists(pidFile);
    ShellTool tool =
        new ShellTool(new SandboxChecker(root, Set.of("java"), Set.of()), root, Set.of("java"), 1);

    long started = System.nanoTime();
    String result =
        tool.execute(
            Map.of("command", "java -cp " + classpath + " io.lytgos.tool.SleepyMain sleepy.pid"));
    long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

    assertTrue(result.contains("超时"), "应返回超时文本: " + result);
    assertTrue(result.contains("已被终止"));
    assertTrue(elapsedMillis < 15_000, "不应等到夹具进程自然结束，耗时 " + elapsedMillis + "ms");

    long pid = Long.parseLong(Files.readString(pidFile, StandardCharsets.UTF_8).strip());
    assertFalse(
        ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false),
        "超时终止后子进程 " + pid + " 仍在运行：destroyForcibly 未生效或未等它退出");
  }

  @Test
  @DisplayName("分号与后续片段按字面参数传递，不经过 shell")
  void doesNotInterpretShellOperators() {
    String result =
        tool(Set.of("java")).execute(Map.of("command", "java -version; echo injected-marker"));

    assertTrue(result.contains("exit="), "命令应被真实执行: " + result);
    assertFalse(result.contains("injected-marker"), "分号后的片段不应被当作独立命令执行");
  }
}
