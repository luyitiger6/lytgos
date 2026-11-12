package io.lytgos.core.agent;

import io.lytgos.core.LytgosException;

import java.util.List;
import java.util.Map;

/**
 * 一个 Agent 的配置快照，加载后不可变。
 *
 * <p>来自 {@code .lytgos/agents/<name>/AGENT.md} 的 YAML frontmatter。
 * record 本身保证字段不可改，但集合字段仍要防御性拷贝——调用方传进来的可变列表
 * 如果被保留引用，之后在外部增删就等于改了 Profile。</p>
 *
 * <p>校验放在紧凑构造器里：不合法定义必须在加载时就失败，而不是等到第一次对话
 * 才因为 provider 为空抛 NPE。</p>
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
        Map<String, Object> settings
) {

    /** 迭代上限的默认值。 */
    public static final int DEFAULT_MAX_ITERATIONS = 10;

    public Profile {
        if (name == null || name.isBlank()) {
            throw new LytgosException.InvalidAgentDefinition("AGENT.md 缺少 name");
        }
        if (provider == null) {
            throw new LytgosException.InvalidAgentDefinition("AGENT.md 缺少 provider");
        }
        tools = tools == null ? List.of() : List.copyOf(tools);
        mcpServers = mcpServers == null ? List.of() : List.copyOf(mcpServers);
        channels = channels == null ? List.of("cli") : List.copyOf(channels);
        bootstrap = bootstrap == null ? List.of() : List.copyOf(bootstrap);
        settings = settings == null ? Map.of() : Map.copyOf(settings);
    }

    /**
     * 本次对话允许的最大循环次数。
     *
     * <p>优先级：{@code settings.maxIterations} > {@link #DEFAULT_MAX_ITERATIONS}。
     * 非法值（非数字、非正数）不静默回退——配置写错就应该报出来。</p>
     */
    public int maxIterations() {
        Object value = settings.get("maxIterations");
        if (value == null) {
            return DEFAULT_MAX_ITERATIONS;
        }
        if (value instanceof Number number && number.intValue() > 0) {
            return number.intValue();
        }
        throw new LytgosException.InvalidAgentDefinition(
                "settings.maxIterations 必须是正整数，当前为: " + value);
    }

    /** 指向某个已注册 provider 的引用。 */
    public record ProviderRef(String name, String model) {
        public ProviderRef {
            if (name == null || name.isBlank()) {
                throw new LytgosException.InvalidAgentDefinition("AGENT.md 缺少 provider.name");
            }
            if (model == null || model.isBlank()) {
                throw new LytgosException.InvalidAgentDefinition("AGENT.md 缺少 provider.model");
            }
        }
    }

    /**
     * 一个 MCP server 的接入声明，两种形态二选一。
     *
     * <p><b>远程</b>（推荐）：{@code url} 指向对方托管的 MCP 服务，lytgos 自己发
     * Streamable HTTP 请求，本机不需要任何额外运行时——这是「普通用户装上就能用」的前提。
     * <b>本地</b>：{@code command + args} 拉起本机子进程走 stdio，适合只能在本机起的
     * 服务（访问本机文件、内网 CLI），但需要对应平台的可执行文件。</p>
     *
     * <p>两者同时为空或同时非空都是配置错误：让「没配」和「配了两种」在加载 Agent 时
     * 立刻报出来，比等到连接阶段再猜用户想用哪个更省时间。</p>
     */
    public record McpServer(String name, String url, String command, List<String> args, Map<String, String> env) {
        public McpServer {
            args = args == null ? List.of() : List.copyOf(args);
            env = env == null ? Map.of() : Map.copyOf(env);
            boolean hasUrl = url != null && !url.isBlank();
            boolean hasCommand = command != null && !command.isBlank();
            if (hasUrl == hasCommand) {
                throw new LytgosException.InvalidAgentDefinition(
                        "MCP server " + (name == null || name.isBlank() ? "(未命名)" : name)
                                + " 必须且只能配置 url（远程）或 command（本地）其中之一");
            }
        }
    }
}
