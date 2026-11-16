package io.lytgos.web.api;

import io.lytgos.core.tool.ToolRegistry;
import io.lytgos.persona.AgentLoader;
import io.lytgos.runtime.AgentInvoker;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 查询指定 Agent 实际可用的工具。
 *
 * <p>走 {@link AgentInvoker#tools(String)} 而不是直接读注册表：工具清单按 Agent 的 {@code tools}
 * 白名单筛过，并按与对话时完全相同的顺序返回——「看到的」必须就是「能调的」。
 */
@RestController
public class ToolController {

  private final AgentInvoker invoker;

  public ToolController(AgentInvoker invoker) {
    this.invoker = invoker;
  }

  /** 工具清单，顺序与注册顺序一致。 */
  @GetMapping("/api/v1/tools")
  public ApiResponse<ToolList> tools(
      @RequestParam(name = "agent", defaultValue = "default") String agent) {
    AgentLoader.validateName(agent);
    ToolRegistry registry = invoker.tools(agent);
    return ApiResponse.ok(
        new ToolList(
            agent,
            registry.specs().stream()
                .map(spec -> new ToolItem(spec.name(), spec.description()))
                .toList()));
  }

  /** 指定 Agent 的工具清单。 */
  public record ToolList(String agent, List<ToolItem> tools) {

    public ToolList {
      tools = List.copyOf(tools);
    }
  }

  /** 一个工具的对外视图。 */
  public record ToolItem(String name, String description) {}
}
