package io.lytgos.tool;

import io.lytgos.core.chat.ToolSpec;
import io.lytgos.core.tool.SandboxChecker;
import io.lytgos.core.tool.Tool;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 内置 {@code shell}：执行命令白名单内的一次性命令。
 *
 * <p>命令由 {@link ProcessBuilder} 直接执行，不经过系统 shell：{@code ;}、{@code |}、{@code >}
 * 都会作为普通参数传给目标程序，命令注入面就此消失。代价是不支持管道与重定向，这是刻意的取舍。
 *
 * <p>可执行文件名先交给 {@link SandboxChecker#checkCommand} 校验；切分规则与它内部的 {@code executableOf} 保持一致——校验按 A
 * 解析、执行按 B 解析，是最经典的沙箱绕过。
 */
public final class ShellTool implements Tool {

  /** 单次命令的输出上限（字符）。与文件读取、记忆注入同一量级。 */
  private static final int MAX_OUTPUT_CHARS = 8000;

  private static final int DEFAULT_TIMEOUT_SECONDS = 15;

  private static final int READER_JOIN_MILLIS = 2000;

  private final SandboxChecker sandbox;
  private final Path workspaceRoot;
  private final Set<String> allowedCommands;
  private final int timeoutSeconds;

  public ShellTool(SandboxChecker sandbox, Path workspaceRoot, Set<String> allowedCommands) {
    this(sandbox, workspaceRoot, allowedCommands, DEFAULT_TIMEOUT_SECONDS);
  }

  /** 超时可注入，供单测用短超时验证进程树回收；生产装配走默认 15 秒。 */
  public ShellTool(
      SandboxChecker sandbox, Path workspaceRoot, Set<String> allowedCommands, int timeoutSeconds) {
    this.sandbox = sandbox;
    this.workspaceRoot = sandbox.checkPath(workspaceRoot);
    this.allowedCommands = Set.copyOf(allowedCommands);
    this.timeoutSeconds = timeoutSeconds;
  }

  @Override
  public ToolSpec spec() {
    return new ToolSpec(
        "shell",
        "执行白名单内的一次性命令，返回退出码与合并后的输出；不经过系统 shell，"
            + "不支持管道与重定向。超时 "
            + timeoutSeconds
            + " 秒会被终止，输出上限 "
            + MAX_OUTPUT_CHARS
            + " 字符。"
            + whitelistHint(),
        """
        {
          "type": "object",
          "properties": {
            "command": {
              "type": "string",
              "description": "完整命令行，例如 java -version；可执行文件名必须在白名单内"
            }
          },
          "required": ["command"]
        }
        """);
  }

  @Override
  public String execute(Map<String, Object> arguments) {
    Object raw = arguments.get("command");
    if (raw == null || String.valueOf(raw).isBlank()) {
      throw new IllegalArgumentException("缺少参数 command");
    }
    String command = String.valueOf(raw).trim();

    // 先校验再切分：校验失败属于安全事件，由 ToolExecutor 抛给上层中断对话
    sandbox.checkCommand(command);
    List<String> parts = splitCommand(command);

    Process process;
    try {
      ProcessBuilder builder = new ProcessBuilder(parts);
      builder.directory(workspaceRoot.toFile());
      builder.redirectErrorStream(true);
      process = builder.start();
      // 本工具只支持一次性命令，不喂 stdin：立刻给子进程 EOF，避免读 stdin 的命令阻塞到超时
      process.getOutputStream().close();
    } catch (IOException e) {
      throw new IllegalStateException("命令启动失败: " + command + " → " + e.getMessage(), e);
    }

    AtomicReference<Captured> captured = new AtomicReference<>();
    Thread reader =
        Thread.ofVirtual().start(() -> captured.set(readOutput(process.getInputStream())));

    boolean finished;
    try {
      finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      terminate(process);
      Thread.currentThread().interrupt();
      throw new IllegalStateException("命令执行被中断: " + command, e);
    }
    if (!finished) {
      terminate(process);
      awaitQuietly(process);
      joinQuietly(reader);
      return "命令超时（" + timeoutSeconds + " 秒）已被终止: " + command;
    }

    joinQuietly(reader);
    Captured output = captured.get();
    String body = output == null ? "（输出读取未完成）" : output.text();
    StringBuilder result = new StringBuilder();
    result.append("exit=").append(process.exitValue()).append('\n');
    result.append(body.isBlank() ? "（无输出）" : body.strip());
    if (output != null && output.totalChars() > MAX_OUTPUT_CHARS) {
      result.append("\n\n[输出已截断，原始长度 ").append(output.totalChars()).append(" 字符]");
    }
    return result.toString();
  }

  /** 白名单展示给模型：看不到规则就只能瞎猜，猜错一次浪费一轮对话。 */
  private String whitelistHint() {
    if (allowedCommands.isEmpty()) {
      return "当前命令白名单为空：所有命令都会被拒绝。";
    }
    return "当前允许的命令: " + new TreeSet<>(allowedCommands) + "。";
  }

  /**
   * 按与 {@link SandboxChecker} 相同的规则切出可执行文件与参数。
   *
   * <p>三种写法：引号包裹的可执行文件、整串已在白名单里的裸命令、常规「命令 参数...」。 引号只允许包住可执行文件本身——这是 {@code checkCommand} 能识别的全部形态。
   */
  private List<String> splitCommand(String command) {
    List<String> parts = new ArrayList<>();
    if (command.startsWith("\"")) {
      int closing = command.indexOf('"', 1);
      if (closing < 0) {
        parts.add(command.substring(1));
        return parts;
      }
      parts.add(command.substring(1, closing));
      String rest = command.substring(closing + 1).trim();
      if (!rest.isEmpty()) {
        parts.addAll(List.of(rest.split("\\s+")));
      }
      return parts;
    }
    if (allowedCommands.contains(executableName(command))) {
      parts.add(command);
      return parts;
    }
    String[] tokens = command.split("\\s+");
    parts.add(tokens[0]);
    for (int i = 1; i < tokens.length; i++) {
      parts.add(tokens[i]);
    }
    return parts;
  }

  /** 与 {@code SandboxChecker.normalizeName} 同一规则：取路径最后一段并去掉 {@code .exe}。 */
  private static String executableName(String executable) {
    int lastSeparator = Math.max(executable.lastIndexOf('/'), executable.lastIndexOf('\\'));
    String name = lastSeparator >= 0 ? executable.substring(lastSeparator + 1) : executable;
    if (name.toLowerCase(Locale.ROOT).endsWith(".exe")) {
      return name.substring(0, name.length() - 4);
    }
    return name;
  }

  /**
   * 读取合并输出，只保留前 {@link #MAX_OUTPUT_CHARS} 个字符，同时统计原始长度。
   *
   * <p>截断后仍然继续读：不排空管道会让子进程在写满缓冲区后阻塞，超时机制也就跟着失效。
   */
  private static Captured readOutput(InputStream stream) {
    StringBuilder text = new StringBuilder();
    int total = 0;
    char[] buffer = new char[2048];
    try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
      int read;
      while ((read = reader.read(buffer)) != -1) {
        if (text.length() < MAX_OUTPUT_CHARS) {
          int take = Math.min(read, MAX_OUTPUT_CHARS - text.length());
          text.append(buffer, 0, take);
        }
        total += read;
      }
    } catch (IOException e) {
      return new Captured("读取输出失败: " + e.getMessage(), total);
    }
    return new Captured(text.toString(), total);
  }

  /** 先杀子进程再杀手进程：只杀父进程会留下孤儿进程继续占用端口或文件。 */
  private static void terminate(Process process) {
    process.descendants().forEach(ProcessHandle::destroyForcibly);
    process.destroyForcibly();
  }

  private static void awaitQuietly(Process process) {
    try {
      process.waitFor(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static void joinQuietly(Thread thread) {
    try {
      thread.join(READER_JOIN_MILLIS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private record Captured(String text, int totalChars) {}
}
