package io.lytgos.cli;

import io.lytgos.core.chat.ToolSpec;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.List;

/**
 * {@code lytgos tool}：查看某个 Agent 装配完成后模型实际能看到的工具。
 *
 * <p>打印的是 {@link AgentRuntime#tools()} 里注册表的快照，而不是某个清单常量——
 * 这样「列出来的」与「对话时真能调的」只有一份真相。</p>
 *
 * <p>第 2 周起要按 Agent 看：不同 Agent 可以声明不同的 MCP server，
 * 全局列一遍反而会显示一个它调不到的工具。为此这个命令会真的把 MCP 子进程拉起来，
 * 列完立刻关掉——代价是启动开销，换来的是不会骗人。</p>
 */
@Command(
        name = "tool",
        description = "查看已注册的工具",
        subcommands = { ToolCommand.ListAll.class })
public final class ToolCommand implements Runnable {

    private final LazyContext context;

    @Option(names = {"-a", "--agent"}, defaultValue = "default", description = "Agent 名称（默认 default）")
    private String agent = "default";

    public ToolCommand(LazyContext context) {
        this.context = context;
    }

    @Override
    public void run() {
        list(agent);
    }

    static void list(String agentName) {
        try (AgentRuntime runtime = CliContext.instance().toolsFor(agentName)) {
            List<ToolSpec> specs = runtime.tools().specs();

            if (specs.isEmpty()) {
                System.out.println("（该 Agent 没有可用的工具）");
                return;
            }

            System.out.printf("%-24s %s%n", "NAME", "DESCRIPTION");
            for (ToolSpec spec : specs) {
                System.out.printf("%-24s %s%n", spec.name(),
                        spec.description() == null ? "" : spec.description());
            }
        }
    }

    @Command(name = "list", description = "列出所有已注册的工具")
    static final class ListAll implements Runnable {

        private final LazyContext context;

        ListAll(LazyContext context) {
            this.context = context;
        }

        @Option(names = {"-a", "--agent"}, defaultValue = "default", description = "Agent 名称（默认 default）")
        private String agent = "default";

        @Override
        public void run() {
            list(agent);
        }
    }
}
