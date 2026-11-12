package io.lytgos.tool.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lytgos.core.chat.ToolSpec;
import io.lytgos.core.tool.Tool;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.List;
import java.util.Map;

/**
 * 把一个 MCP server 提供的工具，包装成 lytgos 的 {@link Tool}。
 *
 * <p>这个类存在的全部意义就是「包装」：MCP 工具一旦实现了 {@code Tool}，
 * 它就自动落进 {@code ToolRegistry} 与 {@code ToolExecutor}，和内置工具走同一条
 * 治理路径，执行器一行都不用改。反过来说，任何绕过 {@code ToolExecutor} 直接
 * 调 {@link #execute} 的做法都是越权。</p>
 *
 * <p>名称原样保留、不加 server 前缀：前缀会让模型看到的工具名变长、语义变模糊。
 * 来源信息放在 {@link #source()} 与描述前缀里，重名冲突时 {@code ToolRegistry} 会报出来。</p>
 */
public final class McpTool implements Tool {

    /** 回填给模型的最大字符数。与内置 {@code http_get} 保持同一上限，避免上下文被单个工具挤爆。 */
    private static final int MAX_RESULT_CHARS = 4000;

    /** 非文本内容（图片、音频、嵌入资源）的占位说明。 */
    private static final String NON_TEXT_PLACEHOLDER = "[该部分不是文本内容，未转成文字]";

    private final String serverName;
    private final McpSyncClient client;
    private final McpSchema.Tool remote;
    private final String inputSchemaJson;

    /**
     * @param serverName 来源 server 名，用于描述前缀与冲突报错
     * @param client     已握手完成的同步客户端
     * @param remote     server 端返回的工具定义
     * @param mapper     把 {@code inputSchema} 转成 JSON 字符串
     */
    public McpTool(String serverName, McpSyncClient client, McpSchema.Tool remote, ObjectMapper mapper) {
        this.serverName = serverName;
        this.client = client;
        this.remote = remote;
        this.inputSchemaJson = toSchemaJson(remote, mapper);
    }

    @Override
    public ToolSpec spec() {
        String description = remote.description() == null ? "" : remote.description().strip();
        // 前缀既给模型看（知道这能力来自外部进程），也给 tool list 的用户看。
        return new ToolSpec(remote.name(), "[mcp:" + serverName + "] " + description, inputSchemaJson);
    }

    @Override
    public String execute(Map<String, Object> arguments) {
        McpSchema.CallToolResult result;
        try {
            result = client.callTool(new McpSchema.CallToolRequest(
                    remote.name(),
                    arguments == null ? Map.of() : arguments,
                    null));
        } catch (RuntimeException e) {
            // 协议错误、超时、子进程已退出都走这里。抛出去由 ToolExecutor 转成文本回填，
            // 模型能据此改参数重试，而不是整轮对话直接断掉。
            throw new IllegalStateException(
                    "调用 MCP server " + serverName + " 的工具 " + remote.name() + " 失败: " + e.getMessage(), e);
        }

        String text = toText(result);
        if (Boolean.TRUE.equals(result.isError())) {
            // server 明确报错：同样走「工具失败回填」路径，不升级成沙箱违规。
            throw new IllegalStateException(text);
        }
        return text;
    }

    @Override
    public String source() {
        return "McpTool[" + serverName + "]";
    }

    private static String toSchemaJson(McpSchema.Tool remote, ObjectMapper mapper) {
        Map<String, Object> schema = remote.inputSchema();
        if (schema == null || schema.isEmpty()) {
            return "{}";
        }
        try {
            return mapper.writeValueAsString(schema);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "MCP 工具 " + remote.name() + " 的 inputSchema 无法转成 JSON: " + e.getMessage(), e);
        }
    }

    /**
     * 把调用结果拼成一段文本。
     *
     * <p>非文本内容不静默丢弃——丢掉会让模型以为工具什么都没返回，
     * 转而重复调用。用占位说明告知「有内容但不是文字」更接近事实。</p>
     */
    private static String toText(McpSchema.CallToolResult result) {
        List<McpSchema.Content> content = result.content();
        if (content == null || content.isEmpty()) {
            return "（MCP server 返回了空内容）";
        }

        StringBuilder text = new StringBuilder();
        for (McpSchema.Content item : content) {
            if (item instanceof McpSchema.TextContent textContent) {
                text.append(textContent.text());
            } else {
                text.append(NON_TEXT_PLACEHOLDER);
            }
            text.append('\n');
        }

        String joined = text.toString().stripTrailing();
        if (joined.length() <= MAX_RESULT_CHARS) {
            return joined;
        }
        return joined.substring(0, MAX_RESULT_CHARS)
                + "\n\n[结果已截断，原始长度 " + joined.length() + " 字符]";
    }
}
