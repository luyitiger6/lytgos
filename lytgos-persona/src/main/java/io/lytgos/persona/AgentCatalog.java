package io.lytgos.persona;

import java.util.List;

/**
 * 工作区内全部 Agent 定义的目录快照。
 *
 * <p>拆成 {@code items} 与 {@code errors} 两段：列端点面向「观察现状」，一个写坏的 AGENT.md 不该让整个列表
 * 500；但错误原因必须完整返回，否则用户只能自己猜是哪一份、哪一行。
 */
public record AgentCatalog(List<Entry> items, List<EntryError> errors) {

  public AgentCatalog {
    items = items == null ? List.of() : List.copyOf(items);
    errors = errors == null ? List.of() : List.copyOf(errors);
  }

  public static AgentCatalog empty() {
    return new AgentCatalog(List.of(), List.of());
  }

  /** 一份成功解析的 Agent：{@code name} 是目录名，其余字段取自 frontmatter。 */
  public record Entry(
      String name, String description, String provider, String model, List<String> tools) {

    public Entry {
      tools = tools == null ? List.of() : List.copyOf(tools);
    }
  }

  /** 一份解析失败的 Agent：{@code name} 仍是目录名，{@code message} 是加载器给出的字段级原因。 */
  public record EntryError(String name, String message) {}
}
