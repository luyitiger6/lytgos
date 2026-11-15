package io.lytgos.tool;

import io.lytgos.core.chat.ToolSpec;
import io.lytgos.core.tool.SandboxChecker;
import io.lytgos.core.tool.Tool;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 内置 {@code list_dir}：列出工作区内某个目录的直接子项。
 *
 * <p>不递归、不返回大小与时间：递归列举与 {@code shell} 能力重叠，且输出长度不可控。 目录名以 {@code /} 结尾，让模型一眼分清「还能再进去」与「已经到底」。
 */
public final class ListDirTool implements Tool {

  /** 单次返回的条目上限。超出后截断并标注总数，避免大目录把上下文挤掉。 */
  private static final int MAX_ENTRIES = 200;

  private static final String EMPTY_TEXT = "（空目录）";

  private final SandboxChecker sandbox;
  private final Path workspaceRoot;

  public ListDirTool(SandboxChecker sandbox, Path workspaceRoot) {
    this.sandbox = sandbox;
    this.workspaceRoot = sandbox.checkPath(workspaceRoot);
  }

  @Override
  public ToolSpec spec() {
    return new ToolSpec(
        "list_dir",
        "列出工作区内某个目录的直接子项（按名称排序，目录以 / 结尾）。缺省路径为工作区根。",
        """
        {
          "type": "object",
          "properties": {
            "path": {
              "type": "string",
              "description": "相对工作区根的目录路径，缺省为工作区根（.）"
            }
          },
          "required": []
        }
        """);
  }

  @Override
  public String execute(Map<String, Object> arguments) {
    Object raw = arguments.get("path");
    String path = raw == null || String.valueOf(raw).isBlank() ? "." : String.valueOf(raw);
    Path target = sandbox.checkPath(workspaceRoot.resolve(path));
    if (!Files.isDirectory(target)) {
      throw new IllegalStateException("不是目录: " + workspaceRoot.relativize(target));
    }

    List<Path> children;
    try (Stream<Path> stream = Files.list(target)) {
      children =
          stream
              .sorted(Comparator.comparing(child -> String.valueOf(child.getFileName())))
              .toList();
    } catch (IOException e) {
      throw new IllegalStateException("列举失败: " + target + " → " + e.getMessage(), e);
    }
    if (children.isEmpty()) {
      return EMPTY_TEXT;
    }

    StringBuilder listing = new StringBuilder();
    int shown = Math.min(children.size(), MAX_ENTRIES);
    for (int i = 0; i < shown; i++) {
      Path child = children.get(i);
      listing.append(child.getFileName());
      if (Files.isDirectory(child)) {
        listing.append('/');
      }
      listing.append('\n');
    }
    if (children.size() > MAX_ENTRIES) {
      listing
          .append("[共 ")
          .append(children.size())
          .append(" 项，已截断，仅列出前 ")
          .append(MAX_ENTRIES)
          .append(" 项]");
    }
    return listing.toString().strip();
  }
}
