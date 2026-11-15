package io.lytgos.tool;

import io.lytgos.core.LytgosException;
import io.lytgos.core.chat.ToolSpec;
import io.lytgos.core.tool.SandboxChecker;
import io.lytgos.core.tool.Tool;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 内置 {@code write_file}：在工作区内创建或覆盖文本文件。
 *
 * <p>允许模型写工作区内的任意路径，但三类文件永远拒绝：{@code agents/*}{@code /AGENT.md} 决定 Agent 的身份与工具集，根 {@code
 * config.yaml} 决定 provider 与沙箱白名单，{@code memory/MEMORY.md} 是用户资产且只能经 {@code save_memory}
 * 追加。让模型改写它们等于把 「配置驱动」变成「模型驱动」——自改工具集之后，下一次启动的治理边界已经和用户设定无关；整体覆写记忆则会绕过 8000 字符上限与「旧条目不淘汰」的承诺。
 *
 * <p>路径校验仍交给 {@link SandboxChecker#checkPath}：先规范化再比前缀，本类不做字符串层面的越界判断。
 */
public final class WriteFileTool implements Tool {

  /** 单次写入的内容上限（UTF-8 字节）。与「一个源文件」的量级对齐，避免模型把上下文倒进磁盘。 */
  private static final int MAX_CONTENT_BYTES = 64 * 1024;

  private static final String CONFIG_FILE = "config.yaml";
  private static final String AGENTS_DIR = "agents";
  private static final String AGENT_FILE = "AGENT.md";
  private static final String MEMORY_DIR = "memory";
  private static final String MEMORY_FILE = "MEMORY.md";

  private final SandboxChecker sandbox;
  private final Path workspaceRoot;

  public WriteFileTool(SandboxChecker sandbox, Path workspaceRoot) {
    this.sandbox = sandbox;
    this.workspaceRoot = sandbox.checkPath(workspaceRoot);
  }

  @Override
  public ToolSpec spec() {
    return new ToolSpec(
        "write_file",
        "在工作区内创建或覆盖文本文件，父目录不存在时自动创建。"
            + "受保护文件（agents/*/AGENT.md、config.yaml 与 memory/MEMORY.md）不可改写；单次内容上限 64KB。",
        """
        {
          "type": "object",
          "properties": {
            "path": {
              "type": "string",
              "description": "相对工作区根的路径，例如 output/notes.txt"
            },
            "content": {
              "type": "string",
              "description": "要写入的完整文本内容"
            }
          },
          "required": ["path", "content"]
        }
        """);
  }

  @Override
  public String execute(Map<String, Object> arguments) {
    Object rawPath = arguments.get("path");
    if (rawPath == null || String.valueOf(rawPath).isBlank()) {
      throw new IllegalArgumentException("缺少参数 path");
    }
    Object rawContent = arguments.get("content");
    if (rawContent == null) {
      throw new IllegalArgumentException("缺少参数 content");
    }
    String content = String.valueOf(rawContent);
    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    if (bytes.length > MAX_CONTENT_BYTES) {
      throw new IllegalStateException("内容超过 64KB 上限（当前 " + bytes.length + " 字节），拒绝写入: " + rawPath);
    }

    Path target = sandbox.checkPath(workspaceRoot.resolve(String.valueOf(rawPath)));
    requireWritable(target);
    try {
      Path parent = target.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.write(target, bytes);
    } catch (IOException e) {
      throw new IllegalStateException("写入失败: " + target + " → " + e.getMessage(), e);
    }
    return "已写入 " + workspaceRoot.relativize(target) + "（" + bytes.length + " 字节）。";
  }

  /**
   * 拒绝改写治理文件。
   *
   * <p>按规范化后的相对路径判断，不做「字符串里有没有 AGENT.md」的匹配：后者会误伤 {@code output/AGENT.md} 这类普通文件，也挡不住 {@code
   * agents/./default/AGENT.md}。 大小写按 Windows 语义归一——同一份文件不能因为换了大小写就绕开保护。
   */
  private void requireWritable(Path target) {
    Path relative = workspaceRoot.relativize(target);
    int count = relative.getNameCount();
    String first = count >= 1 ? relative.getName(0).toString() : "";
    boolean rootConfig = count == 1 && sameName(first, CONFIG_FILE);
    boolean agentDefinition =
        count == 3
            && sameName(first, AGENTS_DIR)
            && sameName(relative.getName(2).toString(), AGENT_FILE);
    boolean memoryFile =
        count == 2
            && sameName(first, MEMORY_DIR)
            && sameName(relative.getName(1).toString(), MEMORY_FILE);
    if (memoryFile) {
      // 记忆是用户资产：整体覆写会绕过 8000 字符上限与「旧条目不淘汰」，必须走 save_memory 的追加路径
      throw new LytgosException.SandboxViolationException(
          "受保护文件不可改写，记忆只能经 save_memory 追加: " + relative);
    }
    if (rootConfig || agentDefinition) {
      throw new LytgosException.SandboxViolationException("受保护文件不可改写，请手工修改: " + relative);
    }
  }

  /** 大小写不敏感的文件名比较；不用 equalsIgnoreCase，避免 Unicode 大小写映射的歧义。 */
  private static boolean sameName(String actual, String expected) {
    return actual.length() == expected.length()
        && actual.regionMatches(true, 0, expected, 0, expected.length());
  }
}
