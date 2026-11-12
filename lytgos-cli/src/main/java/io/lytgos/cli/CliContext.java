package io.lytgos.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lytgos.core.LytgosException;
import io.lytgos.core.agent.Profile;
import io.lytgos.core.agent.PromptBuilder;
import io.lytgos.core.agent.ReActLoop;
import io.lytgos.core.chat.ChatModel;
import io.lytgos.core.session.FileSessionStore;
import io.lytgos.core.session.SessionStore;
import io.lytgos.core.tool.SandboxChecker;
import io.lytgos.core.tool.Tool;
import io.lytgos.core.tool.ToolExecutor;
import io.lytgos.core.tool.ToolRegistry;
import io.lytgos.core.workspace.Workspace;
import io.lytgos.persona.AgentLoader;
import io.lytgos.provider.ChatModelFactory;
import io.lytgos.provider.ProviderConfig;
import io.lytgos.provider.ProviderRegistry;
import io.lytgos.tool.FileTool;
import io.lytgos.tool.HttpTool;
import io.lytgos.tool.mcp.McpServerManager;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * CLI 的装配点：把工作区、配置、provider、工具、会话、Agent 装成一条可跑的链路。
 *
 * <p>全部懒加载。「{@code lytgos init} 时还没有工作区」「{@code lytgos chat} 时才有配置」
 * 这两个场景必须共存——构造时就强依赖工作区，init 命令自己就跑不起来。</p>
 *
 * <p>第 1 周的装配放在 cli 模块：入口是 picocli 的 fat jar，还没有 Web 容器。
 * 第 3 周 Web Service 落地后，装配迁到 {@code lytgos-boot}，本类退化为参数拼装。</p>
 */
public final class CliContext {

    private static final CliContext PROCESS_WIDE = new CliContext(
            Path.of(System.getProperty("user.dir")));

    private final Path startDir;
    private final ObjectMapper mapper = new ObjectMapper();

    private Workspace workspace;
    private WorkspaceConfig config;
    private SandboxChecker sandbox;
    private ProviderRegistry providers;
    private List<Tool> builtinTools;
    private SessionStore sessions;

    private CliContext(Path startDir) {
        this.startDir = startDir;
    }

    /**
     * 进程级装配根。
     *
     * <p>子命令由 picocli 反射实例化，拿不到构造参数，而它们又必须共用同一份
     * 工作区与注册表——否则 {@code tool list} 显示的工具集与 {@code chat}
     * 实际能调的工具集可能不一致。CLI 进程本来就是单命令一次性的，
     * 这里用进程级实例而不是把装配代码复制到每个命令里。</p>
     */
    public static CliContext instance() {
        return PROCESS_WIDE;
    }

    /** 指定起始目录的实例，供测试用。 */
    public static CliContext at(Path startDir) {
        return new CliContext(startDir);
    }

    public synchronized Workspace workspace() {
        if (workspace == null) {
            workspace = Workspace.discover(startDir);
        }
        return workspace;
    }

    /**
     * 宽松加载：缺失的环境变量保留为 {@code ${missing:VAR}} 占位。
     *
     * <p>严格性推迟到 {@link #providers()}——只有真要连模型时才必须拿到 key。
     * 否则 {@code provider list} / {@code tool list} 这类不碰网络的命令，
     * 也会因为一个没设的环境变量而跑不起来。</p>
     */
    public synchronized WorkspaceConfig config() {
        if (config == null) {
            config = WorkspaceConfig.load(workspace().configFile(), false);
        }
        return config;
    }

    public synchronized ProviderRegistry providers() {
        if (providers == null) {
            List<ProviderConfig> configs = config().providers();
            if (configs.isEmpty()) {
                throw new LytgosException.InvalidConfiguration(
                        "配置文件里没有任何 provider: " + workspace().configFile());
            }
            for (ProviderConfig provider : configs) {
                String missing = WorkspaceConfig.missingEnvVariable(provider.apiKey());
                if (missing != null) {
                    throw new LytgosException.InvalidConfiguration(
                            "环境变量未设置: " + missing + "（provider " + provider.name() + " 需要）");
                }
            }
            providers = new ProviderRegistry(ChatModelFactory.createAll(configs));
        }
        return providers;
    }

    /**
     * 沙箱只建一次：域名与命令白名单来自配置，全进程不会变。
     *
     * <p>第 2 周起 {@code allowed-commands} 有了第一个真实用途——MCP server 的启动命令。
     * 白名单为空时 MCP 一律起不来，这是刻意的默认拒绝。</p>
     */
    public synchronized SandboxChecker sandbox() {
        if (sandbox == null) {
            sandbox = new SandboxChecker(
                    workspace().root(), config().allowedCommands(), config().allowedDomains());
        }
        return sandbox;
    }

    /** 内置工具：不随 Agent 变化，只建一次。 */
    public synchronized List<Tool> builtinTools() {
        if (builtinTools == null) {
            builtinTools = List.of(
                    new HttpTool(sandbox()),
                    new FileTool(sandbox(), workspace().root()));
        }
        return builtinTools;
    }

    public synchronized SessionStore sessions() {
        if (sessions == null) {
            sessions = new FileSessionStore(workspace().sessions(), mapper);
        }
        return sessions;
    }

    /**
     * 只装配工具，不碰模型。{@code tool list} 这类只读命令用它——
     * 列出工具不需要 API key，不该因为没设 key 就连看都看不到。
     *
     * <p>调用方必须 close，否则 MCP 子进程会残留。</p>
     */
    public AgentRuntime toolsFor(String agentName) {
        return openTools(new AgentLoader().load(workspace().agents(), agentName).profile());
    }

    /**
     * 按 Agent 组装一条完整的运行时：对话循环 + 工具注册表 + 已启动的 MCP 子进程。
     *
     * <p>调用方必须 close，否则 MCP 子进程会残留。无 MCP 时 close 是空操作。</p>
     *
     * <p>失败路径也要回收：MCP 起了一半、或工具装配时发现重名，
     * 都会先把已拉起的子进程关掉再抛。</p>
     */
    public AgentRuntime runtimeFor(String agentName) {
        AgentLoader.AgentDefinition definition = new AgentLoader().load(workspace().agents(), agentName);
        Profile profile = definition.profile();

        AgentRuntime runtime = openTools(profile);
        try {
            ChatModel model = providers().require(profile.provider().name());
            PromptBuilder promptBuilder =
                    PromptBuilder.from(profile, workspace().root(), definition.instructions());
            ToolRegistry registry = runtime.tools();
            runtime.attachLoop(new ReActLoop(model, promptBuilder, registry,
                    new ToolExecutor(registry, mapper), profile.maxIterations()));
            return runtime;
        } catch (RuntimeException e) {
            runtime.close();
            throw e;
        }
    }

    /** 开工具（含 MCP 子进程），失败时自己回收已拉起的那部分。 */
    private AgentRuntime openTools(Profile profile) {
        McpServerManager mcp = McpServerManager.start(profile.mcpServers(), sandbox(), mapper);
        try {
            return new AgentRuntime(registryFor(profile, mcp), mcp);
        } catch (RuntimeException e) {
            mcp.close();
            throw e;
        }
    }

    /**
     * 把内置工具与 MCP 工具合成一个注册表，再按 Agent 的 {@code tools} 白名单筛。
     *
     * <p>白名单为空视为不限制——第 1 周这个字段就被解析但从未生效，
     * 现在若改成「空 = 全禁」，已有工作区会突然一个工具都用不了。
     * 非空时只保留列出的工具；列了但没注册的当场报错，
     * 否则用户会以为配好了，实际只有模型知道工具不存在。</p>
     */
    private ToolRegistry registryFor(Profile profile, McpServerManager mcp) {
        List<Tool> merged = new ArrayList<>(builtinTools());
        merged.addAll(mcp.tools());

        // 先建一次完整注册表：重名检测在这里发生，哪怕它随后会被白名单筛掉
        ToolRegistry full = new ToolRegistry(merged);
        List<String> allowed = profile.tools();
        if (allowed.isEmpty()) {
            return full;
        }

        Set<String> registered = new LinkedHashSet<>(full.names());
        List<String> unknown = allowed.stream()
                .filter(name -> !registered.contains(name))
                .toList();
        if (!unknown.isEmpty()) {
            throw new LytgosException.InvalidConfiguration(
                    "Agent " + profile.name() + " 的 tools 里列了未注册的工具: " + unknown
                            + "；当前可用: " + registered);
        }

        Set<String> keep = Set.copyOf(allowed);
        List<Tool> selected = full.all().stream()
                .filter(tool -> keep.contains(tool.spec().name()))
                .collect(Collectors.toList());
        return new ToolRegistry(selected);
    }

    /** 会话键：channel + user + agent 三元组。第 1 周 user 固定为 local。 */
    public String sessionKey(String agentName) {
        return "cli:local:" + agentName;
    }
}
