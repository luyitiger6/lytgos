package io.lytgos.web.support;

import io.lytgos.core.chat.Message;
import io.lytgos.core.chat.ToolSpec;
import io.lytgos.core.session.Session;
import io.lytgos.core.tool.Tool;
import io.lytgos.core.tool.ToolRegistry;
import io.lytgos.core.workspace.WorkspaceLocator;
import io.lytgos.persona.AgentLoader;
import io.lytgos.runtime.AgentInvoker;
import java.util.List;
import java.util.Map;

/**
 * 不启动任何模型或子进程的 {@link AgentInvoker}。
 *
 * <p>「Agent 是否存在」仍走真实的 {@link AgentLoader}：未知 Agent 必须和真实实现一样抛 {@code
 * InvalidAgentDefinitionException}，否则 400 场景只能在带 MCP 的环境里验证。
 *
 * <p>工具清单用固定三个假工具，顺序写死——控制器测试只需证明「返回顺序 == 注册顺序」， 真实七个内置工具的装配由工具模块自己的测试覆盖。
 */
public final class FakeAgentInvoker implements AgentInvoker {

  /** 假工具清单，顺序即期望的返回顺序。 */
  public static final List<String> TOOL_NAMES = List.of("http_get", "file_read", "shell");

  private final AgentLoader loader;
  private final WorkspaceLocator locator;
  private final ToolRegistry tools;

  public FakeAgentInvoker(AgentLoader loader, WorkspaceLocator locator) {
    this.loader = loader;
    this.locator = locator;
    this.tools = new ToolRegistry(TOOL_NAMES.stream().<Tool>map(StubTool::new).toList());
  }

  @Override
  public String run(String agent, Session session, String input) {
    loader.load(locator.get().agents(), agent);
    session.append(Message.user(input));
    String reply = "echo:" + input;
    session.append(Message.assistant(reply));
    return reply;
  }

  @Override
  public ToolRegistry tools(String agent) {
    loader.load(locator.get().agents(), agent);
    return tools;
  }

  /** 只有声明的最小工具：执行永远不会被控制器调用。 */
  private record StubTool(String name) implements Tool {

    @Override
    public ToolSpec spec() {
      return new ToolSpec(name, name + " 工具", "{}");
    }

    @Override
    public String execute(Map<String, Object> arguments) {
      throw new UnsupportedOperationException("测试替身不执行工具");
    }
  }
}
