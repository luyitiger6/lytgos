package io.lytgos.tool;

import io.lytgos.core.chat.ToolSpec;
import io.lytgos.core.tool.SandboxChecker;
import io.lytgos.core.tool.Tool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * 内置 {@code file_read}：读取工作区内的文本文件。
 *
 * <p>第 1 周只做只读。写文件要等 memory 的写入策略定稿——
 * 「什么时候允许 Agent 改自己的 AGENT.md」是治理问题，不是 IO 问题。</p>
 *
 * <p>参数是相对工作区根的路径，权限判定交给 {@link SandboxChecker#checkPath}：
 * 它先规范化再比前缀，{@code ../} 穿越与符号链接指向区外一并挡住。
 * 本类不做任何字符串层面的路径检查——那种检查总能被绕过。</p>
 */
public final class FileTool implements Tool {

    /** 回填给模型的最大字符数。与 {@link HttpTool} 同一量级，避免单个文件挤掉上下文。 */
    private static final int MAX_CONTENT_CHARS = 8000;

    private final SandboxChecker sandbox;
    private final Path workspaceRoot;

    public FileTool(SandboxChecker sandbox, Path workspaceRoot) {
        this.sandbox = sandbox;
        this.workspaceRoot = workspaceRoot;
    }

    @Override
    public ToolSpec spec() {
        return new ToolSpec(
                "file_read",
                "读取工作区内的文本文件。只能访问工作区目录之内。",
                """
                        {
                          "type": "object",
                          "properties": {
                            "path": {
                              "type": "string",
                              "description": "相对工作区根的路径，例如 AGENTS.md 或 memory/MEMORY.md"
                            }
                          },
                          "required": ["path"]
                        }
                        """);
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        Object raw = arguments.get("path");
        if (raw == null || String.valueOf(raw).isBlank()) {
            throw new IllegalArgumentException("缺少参数 path");
        }

        // resolve 对绝对路径会直接返回它——越界由 checkPath 拒绝，这里不做特判
        Path target = sandbox.checkPath(workspaceRoot.resolve(String.valueOf(raw)));
        if (!Files.isRegularFile(target)) {
            throw new IllegalStateException("不是普通文件: " + target);
        }

        String content;
        try {
            content = Files.readString(target, StandardCharsets.UTF_8);
        } catch (IOException e) {
            // 二进制文件会在这里以 MalformedInputException 失败，消息足以让模型换个路径
            throw new IllegalStateException("读取失败: " + target + " → " + e.getMessage(), e);
        }
        if (content.length() <= MAX_CONTENT_CHARS) {
            return content;
        }
        return content.substring(0, MAX_CONTENT_CHARS)
                + "\n\n[文件已截断，原始长度 " + content.length() + " 字符]";
    }
}
