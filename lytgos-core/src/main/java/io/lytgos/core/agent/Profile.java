package io.lytgos.core.agent;

import io.lytgos.core.LytgosException;
import java.util.List;
import java.util.Map;

/**
 * 一个 Agent 的配置快照，加载后不可变。
 *
 * <p>来自 {@code .lytgos/agents/<name>/AGENT.md} 的 YAML frontmatter。 record
 * 本身保证字段不可改，但集合字段仍要防御性拷贝——调用方传进来的可变列表 如果被保留引用，之后在外部增删就等于改了 Profile。
 *
 * <p>校验放在紧凑构造器里：不合法定义必须在加载时就失败，而不是等到第一次对话 才因为 provider 为空抛 NPE。
 */
public record Profile(
    String name,
    String description,
    String identity,
    ProviderRef provider,
    List<String> tools,
    List<McpServer> mcpServers,
    List<String> channels,
    List<String> bootstrap,
    Map<String, Object> settings,
    List<String> skills) {

  /** 迭代上限的默认值。 */
  public static final int DEFAULT_MAX_ITERATIONS = 10;

  public Profile {
    if (name == null || name.isBlank()) {
      throw new LytgosException.InvalidAgentDefinitionException("AGENT.md 缺少 name");
    }
    if (provider == null) {
      throw new LytgosException.InvalidAgentDefinitionException("AGENT.md 缺少 provider");
    }
    tools = tools == null ? List.of() : List.copyOf(tools);
    mcpServers = mcpServers == null ? List.of() : List.copyOf(mcpServers);
    channels = channels == null ? List.of("cli") : List.copyOf(channels);
    bootstrap = bootstrap == null ? List.of() : List.copyOf(bootstrap);
    settings = settings == null ? Map.of() : Map.copyOf(settings);
    skills = skills == null ? List.of() : List.copyOf(skills);
  }

  /**
   * 不含技能声明的便捷构造器。
   *
   * <p>保留它而不是把所有既有调用点都改成十参：{@code skills} 与{@code settings} 在语义上 同属可选扩展，测试与早期调用点没必要为一个空列表多写一个参数。
   */
  public Profile(
      String name,
      String description,
      String identity,
      ProviderRef provider,
      List<String> tools,
      List<McpServer> mcpServers,
      List<String> channels,
      List<String> bootstrap,
      Map<String, Object> settings) {
    this(
        name,
        description,
        identity,
        provider,
        tools,
        mcpServers,
        channels,
        bootstrap,
        settings,
        List.of());
  }

  /**
   * 本次对话允许的最大循环次数。
   *
   * <p>优先级：{@code settings.maxIterations} > {@link #DEFAULT_MAX_ITERATIONS}。
   * 非法值（非数字、非正数）不静默回退——配置写错就应该报出来。
   */
  public int maxIterations() {
    Object value = settings.get("maxIterations");
    if (value == null) {
      return DEFAULT_MAX_ITERATIONS;
    }
    if (value instanceof Number number && number.intValue() > 0) {
      return number.intValue();
    }
    throw new LytgosException.InvalidAgentDefinitionException(
        "settings.maxIterations 必须是正整数，当前为: " + value);
  }

  /**
   * 指向某个已注册 provider 的引用。
   *
   * <p>{@code temperature} 可空：空表示不向上游传，由 provider 默认值决定； 非空时必须在 0.0–2.0 之间——越界不静默回退，配置写错就应该在加载
   * Agent 时报出来。
   */
  public record ProviderRef(String name, String model, Double temperature) {

    /** 温度下限（OpenAI 兼容接口的共同区间）。 */
    public static final double MIN_TEMPERATURE = 0.0;

    /** 温度上限。 */
    public static final double MAX_TEMPERATURE = 2.0;

    public ProviderRef {
      if (name == null || name.isBlank()) {
        throw new LytgosException.InvalidAgentDefinitionException("AGENT.md 缺少 provider.name");
      }
      if (model == null || model.isBlank()) {
        throw new LytgosException.InvalidAgentDefinitionException("AGENT.md 缺少 provider.model");
      }
      if (temperature != null && outsideRange(temperature)) {
        throw new LytgosException.InvalidAgentDefinitionException(
            "provider.temperature 必须在 "
                + MIN_TEMPERATURE
                + " 到 "
                + MAX_TEMPERATURE
                + " 之间，当前为: "
                + temperature);
      }
    }

    /** 温度是否落在合法区间之外；NaN 一律视为非法。条件单独成方法，避免构造器里的复杂布尔表达式。 */
    private static boolean outsideRange(double temperature) {
      return Double.isNaN(temperature)
          || temperature < MIN_TEMPERATURE
          || temperature > MAX_TEMPERATURE;
    }

    /** 不声明温度的便捷构造器。 */
    public ProviderRef(String name, String model) {
      this(name, model, null);
    }
  }

  /**
   * 一个 MCP server 的接入声明，两种形态二选一。
   *
   * <p><b>远程</b>（推荐）：{@code url} 指向对方托管的 MCP 服务，lytgos 自己发 Streamable HTTP
   * 请求，本机不需要任何额外运行时——这是「普通用户装上就能用」的前提。 <b>本地</b>：{@code command + args} 拉起本机子进程走 stdio，适合只能在本机起的
   * 服务（访问本机文件、内网 CLI），但需要对应平台的可执行文件。
   *
   * <p>两者同时为空或同时非空都是配置错误：让「没配」和「配了两种」在加载 Agent 时 立刻报出来，比等到连接阶段再猜用户想用哪个更省时间。
   */
  public record McpServer(
      String name, String url, String command, List<String> args, Map<String, String> env) {
    public McpServer {
      args = args == null ? List.of() : List.copyOf(args);
      env = env == null ? Map.of() : Map.copyOf(env);
      boolean hasUrl = url != null && !url.isBlank();
      boolean hasCommand = command != null && !command.isBlank();
      if (hasUrl == hasCommand) {
        throw new LytgosException.InvalidAgentDefinitionException(
            "MCP server "
                + (name == null || name.isBlank() ? "(未命名)" : name)
                + " 必须且只能配置 url（远程）或 command（本地）其中之一");
      }
    }
  }
}
