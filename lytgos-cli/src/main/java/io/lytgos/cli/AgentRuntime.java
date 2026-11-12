package io.lytgos.cli;

import io.lytgos.core.agent.ReActLoop;
import io.lytgos.core.tool.ToolRegistry;
import io.lytgos.tool.mcp.McpServerManager;

/**
 * 一个 Agent 跑起来之后的全部东西：对话循环、它实际能调的工具、以及背后的 MCP 子进程。
 *
 * <p>为什么必须能关：MCP server 是独立子进程，不关就会在用户机器上留下孤儿。
 * {@code chat} 和 {@code tool list} 都绕不过这个对象，所以关闭点只有一处，
 * 不会出现「某个命令忘了回收」的缺口。</p>
 */
public final class AgentRuntime implements AutoCloseable {

    private ReActLoop loop;
    private final ToolRegistry tools;
    private final McpServerManager mcp;

    AgentRuntime(ToolRegistry tools, McpServerManager mcp) {
        this.tools = tools;
        this.mcp = mcp;
    }

    /**
     * 补上对话循环。
     *
     * <p>分开设是因为 {@code tool list} 只需要工具注册表，不该因为没设 API key
     * 就连工具都列不出来。真要对话时再由装配点补进来。</p>
     */
    void attachLoop(ReActLoop loop) {
        this.loop = loop;
    }

    public ReActLoop loop() {
        if (loop == null) {
            throw new IllegalStateException("该运行时只装配了工具，没有对话循环");
        }
        return loop;
    }

    /** 该 Agent 实际能看到的工具：内置工具 + 它声明的 MCP 工具，已按 tools 白名单筛过。 */
    public ToolRegistry tools() {
        return tools;
    }

    @Override
    public void close() {
        mcp.close();
    }
}
