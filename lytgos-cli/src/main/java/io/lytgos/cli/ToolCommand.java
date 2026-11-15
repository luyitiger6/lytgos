package io.lytgos.cli;

import io.lytgos.core.chat.ToolSpec;
import java.util.List;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code lytgos tool}：查看某个 Agent 装配完成后模型实际能看到的工具。
 *
 * <p>打印的是 {@link AgentRuntime#tools()} 里注册表的快照，而不是某个清单常量—— 这样「列出来的」与「对话时真能调的」只有一份真相。
 *
 * <p>第 2 周起要按 Agent 看：不同 Agent 可以声明不同的 MCP server， 全局列一遍反而会显示一个它调不到的工具。为此这个命令会真的把 MCP 子进程拉起来，
 * 列完立刻关掉——代价是启动开销，换来的是不会骗人。
 */
@Command(
    name = "tool",
    mixinStandardHelpOptions = true,
    description = "查看已注册的工具",
    subcommands = {ToolCommand.ListAll.class})
public final class ToolCommand implements Runnable {

  private final LazyContext context;

  @Option(
      names = {"-a", "--agent"},
      defaultValue = "default",
      description = "Agent 名称（默认 default）")
  private String agent = "default";

  public ToolCommand(LazyContext context) {
    this.context = context;
  }

  @Override
  public void run() {
    list(context, agent);
  }

  private static void list(LazyContext context, String agentName) {
    AgentRuntimeFactory factory = context.getBean(AgentRuntimeFactory.class);
    try (AgentRuntime runtime = factory.toolsFor(agentName)) {
      System.out.print(render(runtime.tools().specs()));
    }
  }

  /**
   * 把工具快照渲染成两列表格。
   *
   * <p>抽成包内纯函数是为了让「列出来的内容」可被单测断言：走 CLI 得启动整个 Spring 容器与 MCP 子进程， 代价与收益不成比例，而模型看到的正是这张表。
   */
  static String render(List<ToolSpec> specs) {
    if (specs.isEmpty()) {
      return "（该 Agent 没有可用的工具）" + System.lineSeparator();
    }
    StringBuilder out = new StringBuilder();
    out.append(String.format("%-24s %s%n", "NAME", "DESCRIPTION"));
    for (ToolSpec spec : specs) {
      out.append(
          String.format(
              "%-24s %s%n", spec.name(), spec.description() == null ? "" : spec.description()));
    }
    return out.toString();
  }

  @Command(name = "list", description = "列出所有已注册的工具")
  static final class ListAll implements Runnable {

    private final LazyContext context;

    ListAll(LazyContext context) {
      this.context = context;
    }

    @Option(
        names = {"-a", "--agent"},
        defaultValue = "default",
        description = "Agent 名称（默认 default）")
    private String agent = "default";

    @Override
    public void run() {
      list(context, agent);
    }
  }
}
