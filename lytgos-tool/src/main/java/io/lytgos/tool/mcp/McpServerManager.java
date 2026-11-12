package io.lytgos.tool.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lytgos.core.LytgosException;
import io.lytgos.core.agent.Profile;
import io.lytgos.core.tool.SandboxChecker;
import io.lytgos.core.tool.Tool;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MCP server 子进程的生命周期管理者。
 *
 * <p>启动顺序是刻意写死的：<b>先过命令白名单，再起进程，最后握手</b>。
 * 顺序一颠倒，「命令不在白名单」就变成了「进程已经跑起来了才发现不该跑」——
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
        String command = declaration.command();
        if (command == null || command.isBlank()) {
            throw new LytgosException.InvalidConfiguration("MCP server " + name + " 缺少 command");
        }

        // 白名单校验必须在建进程之前：这一步抛异常时，系统里什么都还没发生
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
        } catch (RuntimeException e) {
            closeQuietly(client, name);
            throw new LytgosException.InvalidConfiguration(
                    "MCP server " + name + " 启动失败（command=" + command + "）: " + e.getMessage());
        }

        List<McpSchema.Tool> remoteTools;
        try {
            remoteTools = listAllTools(client);
        } catch (RuntimeException e) {
            closeQuietly(client, name);
            throw new LytgosException.InvalidConfiguration(
                    "MCP server " + name + " 获取工具清单失败: " + e.getMessage());
        }

        List<Tool> wrapped = new ArrayList<>(remoteTools.size());
        for (McpSchema.Tool remote : remoteTools) {
            wrapped.add(new McpTool(name, client, remote, mapper));
        }

        LOG.info("MCP server 已就绪: {} command={} 工具 {} 个: {}",
                name, command, wrapped.size(),
                remoteTools.stream().map(McpSchema.Tool::name).toList());
        // 下面这句是治理边界说明，不是普通调试日志：
        // 用户必须知道这个子进程的网络访问不在域名白名单的管辖范围内
        LOG.info("MCP server {} 的网络访问发生在独立子进程内，域名白名单不覆盖该 server；"
                + "沙箱对它的约束只有启动命令 {}", name, command);

        return new Running(name, client, List.copyOf(wrapped));
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
