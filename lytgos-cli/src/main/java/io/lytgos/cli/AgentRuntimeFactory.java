package io.lytgos.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lytgos.core.LytgosException;
import io.lytgos.core.agent.Profile;
import io.lytgos.core.agent.PromptBuilder;
import io.lytgos.core.agent.ReActLoop;
import io.lytgos.core.chat.ChatModel;
import io.lytgos.core.tool.SandboxChecker;
import io.lytgos.core.tool.Tool;
import io.lytgos.core.tool.ToolExecutor;
import io.lytgos.core.tool.ToolRegistry;
import io.lytgos.core.workspace.WorkspaceLocator;
import io.lytgos.persona.AgentLoader;
import io.lytgos.provider.ProviderRegistry;
import io.lytgos.tool.FileTool;
import io.lytgos.tool.HttpTool;
import io.lytgos.tool.mcp.McpServerManager;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 按 Agent 组装运行时：工具注册表 + 已启动的 MCP 子进程（+ 对话循环）。
 *
 * <p>替代原 {@code CliContext.runtimeFor()} / {@code toolsFor()}。单例依赖由容器注入；
 * {@link ReActLoop}、{@link ToolRegistry}、{@link ToolExecutor}、{@link McpServerManager}
 * 依赖具体 Agent 的 {@code Profile}，每个 Agent 各一份，不进容器。</p>
 *
 * <p>调用方必须 close 返回的运行时，否则 MCP 子进程会残留。失败路径也要回收：
 * MCP 起了一半、或工具装配时发现重名，都会先把已拉起的子进程关掉再抛。</p>
 */
public final class AgentRuntimeFactory {

    private final WorkspaceLocator locator;
    private final SandboxChecker sandbox;
    private final AgentLoader agentLoader;
    private final ProviderRegistry providers;
    private final ObjectMapper mapper;
    private final List<Tool> builtinTools;

    public AgentRuntimeFactory(WorkspaceLocator locator,
                               SandboxChecker sandbox,
                               AgentLoader agentLoader,
                               ProviderRegistry providers,
                               ObjectMapper mapper,
                               HttpTool httpTool,
                               FileTool fileTool) {
        this.locator = locator;
        this.sandbox = sandbox;
        this.agentLoader = agentLoader;
        this.providers = providers;
        this.mapper = mapper;
        this.builtinTools = List.of(httpTool, fileTool);
    }

    /**
     * 只装配工具，不碰模型。{@code tool list} 这类只读命令用它——
     * 列出工具不需要 API key，不该因为没设 key 就连看都看不到。
     */
    public AgentRuntime toolsFor(String agentName) {
        AgentLoader.AgentDefinition definition =
                agentLoader.load(locator.get().agents(), agentName);
        return openTools(definition.profile());
    }

    /**
     * 按 Agent 组装一条完整的运行时：对话循环 + 工具注册表 + 已启动的 MCP 子进程。
     *
     * <p>模型的创建推迟到 {@link ProviderRegistry#require(String)}——走到这里
     * 说明真要连模型了，缺 key 的报错会带变量名。</p>
     */
    public AgentRuntime runtimeFor(String agentName) {
        AgentLoader.AgentDefinition definition =
                agentLoader.load(locator.get().agents(), agentName);
        Profile profile = definition.profile();

        AgentRuntime runtime = openTools(profile);
        try {
            ChatModel model = providers.require(profile.provider().name());
            PromptBuilder promptBuilder =
                    PromptBuilder.from(profile, locator.get().root(), definition.instructions());
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
        McpServerManager mcp = McpServerManager.start(profile.mcpServers(), sandbox, mapper);
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
        List<Tool> merged = new ArrayList<>(builtinTools);
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
}
