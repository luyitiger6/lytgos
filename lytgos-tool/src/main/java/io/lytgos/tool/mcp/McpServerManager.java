package io.lytgos.tool.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lytgos.core.LytgosException;
import io.lytgos.core.agent.Profile;
import io.lytgos.core.tool.SandboxChecker;
import io.lytgos.core.tool.Tool;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MCP server 的生命周期管理者，支持远程与本地两种形态。
 *
 * <p>两种形态的启动顺序都是刻意写死的：<b>先过沙箱，再建连接，最后握手</b>。
 * 远程形态先校验 URL 的域名，本地形态先校验启动命令——顺序一颠倒，
 * 「不在白名单」就变成了「已经连上去/已经跑起来才发现不该跑」，
 * 那时候已经没法保证没造成影响。</p>
 *
 * <p>任何一步失败都让整体失败，不做「跳过失败的 server 继续启动」：
 * 少一个工具会让模型在对话里反复尝试一个不存在的名字，
 * 比启动时直接报错更难诊断。</p>
 */
public final class McpServerManager implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(McpServerManager.class);

    /** 握手超时。MCP server 起不来时最常见的原因就是卡在握手，不能无限等。 */
    private static final Duration INITIALIZATION_TIMEOUT = Duration.ofSeconds(10);

    /** 单次请求超时。工具本身可能有下游网络调用，给得比握手宽松。 */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final List<Running> servers = new ArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private Thread shutdownHook;

    private McpServerManager() {
    }

    /**
     * 按声明顺序启动全部 MCP server。任何一个失败则关闭已启动的并抛出。
     *
     * @param declarations Agent 定义里的 {@code mcp_servers}
     * @param sandbox      命令白名单校验器
     * @param mapper       给 {@link McpTool} 转 JSON Schema 用
     */
    public static McpServerManager start(List<Profile.McpServer> declarations,
                                         SandboxChecker sandbox,
                                         ObjectMapper mapper) {
        McpServerManager manager = new McpServerManager();
        if (declarations == null || declarations.isEmpty()) {
            return manager;
        }
        try {
            for (Profile.McpServer declaration : declarations) {
                manager.servers.add(manager.launch(declaration, sandbox, mapper));
            }
        } catch (RuntimeException e) {
            // 已经起来的进程必须收掉，否则这次失败的启动会留下孤儿
            manager.close();
            throw e;
        }
        manager.installShutdownHook();
        return manager;
    }

    /**
     * 全部 MCP 工具的 {@link Tool} 包装。顺序与启动顺序、server 返回顺序一致。
     */
    public List<Tool> tools() {
        List<Tool> all = new ArrayList<>();
        for (Running server : servers) {
            all.addAll(server.tools());
        }
        return List.copyOf(all);
    }

    /** 已启动的 server 名，按启动顺序。 */
    public List<String> serverNames() {
        return servers.stream().map(Running::name).toList();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        removeShutdownHook();
        for (Running server : servers) {
            try {
                server.client().close();
                LOG.info("MCP server 已关闭: {}", server.name());
            } catch (RuntimeException e) {
                LOG.warn("关闭 MCP server {} 时出错，继续关闭其余: {}", server.name(), e.getMessage());
            }
        }
    }

    private Running launch(Profile.McpServer declaration, SandboxChecker sandbox, ObjectMapper mapper) {
        String name = declaration.name();
        if (name == null || name.isBlank()) {
            throw new LytgosException.InvalidConfiguration(
                    "Agent 定义的 mcp_servers 里有一项缺少 name，无法区分是哪个 server");
        }

        // 形态由字段决定：Profile.McpServer 的构造器已保证 url / command 二选一
        boolean remoteMode = declaration.url() != null && !declaration.url().isBlank();
        McpSyncClient client = remoteMode
                ? connectRemote(declaration, sandbox)
                : launchLocal(declaration, sandbox);

        List<McpSchema.Tool> remoteTools;
        try {
            remoteTools = listAllTools(client);
        } catch (RuntimeException e) {
            closeQuietly(client, name);
            throw new LytgosException.InvalidConfiguration(
                    "MCP server " + name + " 获取工具清单失败: " + e.getMessage());
        }

        List<Tool> wrapped = new ArrayList<>(remoteTools.size());
        for (McpSchema.Tool remoteTool : remoteTools) {
            wrapped.add(new McpTool(name, client, remoteTool, mapper));
        }

        if (remoteMode) {
            // url 里通常带 key，日志只能打脱敏后的地址
            LOG.info("MCP server 已就绪: {} url={} 工具 {} 个: {}",
                    name, redact(declaration.url()), wrapped.size(),
                    remoteTools.stream().map(McpSchema.Tool::name).toList());
            // 治理边界说明，不是普通调试日志：远程形态由 lytgos 自己发 HTTP，域名白名单对它生效
            LOG.info("MCP server {} 通过远程 URL 连接，地址已通过域名白名单校验；"
                    + "它访问下游服务的行为由对端负责", name);
        } else {
            LOG.info("MCP server 已就绪: {} command={} 工具 {} 个: {}",
                    name, declaration.command(), wrapped.size(),
                    remoteTools.stream().map(McpSchema.Tool::name).toList());
            // 用户必须知道这个子进程的网络访问不在域名白名单的管辖范围内
            LOG.info("MCP server {} 的网络访问发生在独立子进程内，域名白名单不覆盖该 server；"
                    + "沙箱对它的约束只有启动命令 {}", name, declaration.command());
        }

        return new Running(name, client, List.copyOf(wrapped));
    }

    /**
     * 远程形态：域名白名单校验 → HTTP 连接 → 握手。
     *
     * <p>校验必须在建连接之前：这一步抛异常时，一个字节的网络请求都还没发出去。</p>
     */
    private static McpSyncClient connectRemote(Profile.McpServer declaration, SandboxChecker sandbox) {
        String name = declaration.name();
        String url = declaration.url().strip();
        try {
            sandbox.checkDomain(url);
        } catch (LytgosException e) {
            throw new LytgosException.InvalidConfiguration(
                    "MCP server " + name + " 的连接地址未通过沙箱校验（sandbox）: " + e.getMessage());
        }

        McpSyncClient client = null;
        try {
            RemoteTarget target = splitRemoteUrl(url);
            HttpClientStreamableHttpTransport.Builder builder =
                    HttpClientStreamableHttpTransport.builder(target.baseUri());
            if (target.endpoint() != null) {
                builder.endpoint(target.endpoint());
            }
            client = McpClient.sync(builder.build())
                    .initializationTimeout(INITIALIZATION_TIMEOUT)
                    .requestTimeout(REQUEST_TIMEOUT)
                    .build();
            client.initialize();
            return client;
        } catch (RuntimeException e) {
            closeQuietly(client, name);
            throw new LytgosException.InvalidConfiguration(
                    "MCP server " + name + " 远程连接失败（url=" + redact(url) + "）: " + describe(e, url));
        }
    }

    /**
     * 把配置里的完整地址拆成 SDK 需要的 baseUri 与 endpoint 两段。
     *
     * <p>SDK 会把 endpoint（默认 {@code /mcp}）解析到 baseUri 上，而 RFC 3986 的解析
     * 对以 {@code /} 开头的引用会<b>丢弃 baseUri 的 query</b>。MCP 服务的 key 通常就在
     * query 上，整条地址交给 SDK 等于把 key 抹掉——服务端只会回一句「key 无效」，
     * 看上去像鉴权问题而不是客户端拼错地址。所以这里把 path 与 query 一起放进 endpoint，
     * 让它们原样出现在最终请求的 URL 上。</p>
     *
     * <p>path 缺省（如 {@code https://example.com}）时 endpoint 返回 {@code null}，
     * 交给 SDK 的默认 {@code /mcp}，符合 MCP 服务的部署惯例。</p>
     */
    static RemoteTarget splitRemoteUrl(String url) {
        URI uri = URI.create(url);
        String origin = uri.getScheme() + "://" + uri.getAuthority();
        String endpoint = url.substring(origin.length());
        return new RemoteTarget(origin, endpoint.isEmpty() ? null : endpoint);
    }

    /** {@link #splitRemoteUrl} 的结果：baseUri 只含 scheme + authority，endpoint 含 path 与 query。 */
    record RemoteTarget(String baseUri, String endpoint) {
    }

    /**
     * 把异常链压成一行可读文本，并顺手把原文 URL 换成脱敏版本。
     *
     * <p>SDK 初始化失败时只抛出 {@code Client failed to initialize by explicit API call}，
     * 真正的原因（连接被拒、状态码、反序列化失败）全在 cause 里。只打外层消息等于把
     * 诊断信息扔掉，下一次排查还得从头猜。同时 cause 里可能带着完整 URL，必须替换，
     * 否则 key 会从这条路径漏进日志。</p>
     */
    static String describe(Throwable error, String rawUrl) {
        String redacted = redact(rawUrl);
        StringBuilder text = new StringBuilder();
        for (Throwable current = error; current != null; current = current.getCause()) {
            String message = current.getMessage();
            String part = (message == null || message.isBlank())
                    ? current.getClass().getSimpleName()
                    : message.replace(rawUrl, redacted);
            if (text.indexOf(part) < 0) {
                if (text.length() > 0) {
                    text.append(" <- ");
                }
                text.append(part);
            }
            if (current.getCause() == current) {
                // 自引用的 cause 链会让 for 循环永远转下去
                break;
            }
        }
        return text.toString();
    }

    /**
     * 本地形态：命令白名单校验 → 起子进程 → 握手。
     *
     * <p>白名单校验必须在建进程之前：这一步抛异常时，系统里什么都还没发生。</p>
     */
    private static McpSyncClient launchLocal(Profile.McpServer declaration, SandboxChecker sandbox) {
        String name = declaration.name();
        String command = declaration.command();

        sandbox.checkCommand(command);

        McpSyncClient client = null;
        try {
            ServerParameters parameters = ServerParameters.builder(command)
                    .args(declaration.args())
                    .env(declaration.env())
                    .build();
            StdioClientTransport transport =
                    new StdioClientTransport(parameters, McpJsonDefaults.getMapper());
            client = McpClient.sync(transport)
                    .initializationTimeout(INITIALIZATION_TIMEOUT)
                    .requestTimeout(REQUEST_TIMEOUT)
                    .build();
            client.initialize();
            return client;
        } catch (RuntimeException e) {
            closeQuietly(client, name);
            throw new LytgosException.InvalidConfiguration(
                    "MCP server " + name + " 启动失败（command=" + command + "）: " + e.getMessage());
        }
    }

    /**
     * 日志脱敏：只保留 scheme / host / path，query 一律抹掉——query 里通常就是 key。
     */
    private static String redact(String url) {
        try {
            URI uri = URI.create(url);
            String port = uri.getPort() > 0 ? ":" + uri.getPort() : "";
            String path = uri.getPath() == null ? "" : uri.getPath();
            String query = uri.getQuery() == null ? "" : "?***";
            return uri.getScheme() + "://" + uri.getHost() + port + path + query;
        } catch (RuntimeException e) {
            return "(无法解析的 URL)";
        }
    }

    /**
     * 拉取全部工具，跟随分页游标。
     *
     * <p>第 2 周用的 server 工具很少，一页就够；但协议允许分页，
     * 只读第一页会在工具变多时悄悄少掉一部分，且没有任何提示。</p>
     */
    private static List<McpSchema.Tool> listAllTools(McpSyncClient client) {
        List<McpSchema.Tool> all = new ArrayList<>();
        String cursor = null;
        do {
            McpSchema.ListToolsResult page = cursor == null
                    ? client.listTools()
                    : client.listTools(cursor);
            all.addAll(page.tools());
            cursor = page.nextCursor();
        } while (cursor != null && !cursor.isBlank());
        return all;
    }

    private static void closeQuietly(McpSyncClient client, String serverName) {
        if (client == null) {
            return;
        }
        try {
            client.close();
        } catch (RuntimeException e) {
            LOG.warn("回收 MCP server {} 的半启动进程时出错: {}", serverName, e.getMessage());
        }
    }

    private void installShutdownHook() {
        Thread hook = new Thread(this::close, "lytgos-mcp-shutdown");
        try {
            Runtime.getRuntime().addShutdownHook(hook);
            this.shutdownHook = hook;
        } catch (IllegalStateException e) {
            // JVM 已在关闭流程中，此时注册 hook 没有意义，靠 try-with-resources 兜底
            LOG.debug("JVM 正在关闭，跳过注册 MCP shutdown hook");
        }
    }

    private void removeShutdownHook() {
        if (shutdownHook == null) {
            return;
        }
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException e) {
            // 正处于 shutdown 流程，hook 即将由 JVM 自己执行，无需移除
        }
        shutdownHook = null;
    }

    /** 一个已就绪的 server：名字、客户端、以及它提供的工具包装。 */
    private record Running(String name, McpSyncClient client, List<Tool> tools) {
    }
}
