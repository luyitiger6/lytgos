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
import io.lytgos.core.tool.ToolExecutor;
import io.lytgos.core.tool.ToolRegistry;
import io.lytgos.core.workspace.Workspace;
import io.lytgos.persona.AgentLoader;
import io.lytgos.provider.ChatModelFactory;
import io.lytgos.provider.ProviderConfig;
import io.lytgos.provider.ProviderRegistry;
import io.lytgos.tool.FileTool;
import io.lytgos.tool.HttpTool;

import java.nio.file.Path;
import java.util.List;

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
    private ProviderRegistry providers;
    private ToolRegistry tools;
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

    public synchronized ToolRegistry tools() {
        if (tools == null) {
            SandboxChecker sandbox = new SandboxChecker(
                    workspace().root(), config().allowedCommands(), config().allowedDomains());
            tools = new ToolRegistry(List.of(
                    new HttpTool(sandbox),
                    new FileTool(sandbox, workspace().root())));
        }
        return tools;
    }

    public synchronized SessionStore sessions() {
        if (sessions == null) {
            sessions = new FileSessionStore(workspace().sessions(), mapper);
        }
        return sessions;
    }

    /** 为指定 Agent 组装一条 ReAct 链路。每次调用都重建，单次 CLI 进程内用不了几条。 */
    public ReActLoop loopFor(String agentName) {
        AgentLoader.AgentDefinition definition = new AgentLoader().load(workspace().agents(), agentName);
        Profile profile = definition.profile();
        ChatModel model = providers().require(profile.provider().name());
        PromptBuilder promptBuilder =
                PromptBuilder.from(profile, workspace().root(), definition.instructions());
        ToolRegistry registry = tools();
        return new ReActLoop(model, promptBuilder, registry,
                new ToolExecutor(registry, mapper), profile.maxIterations());
    }

    /** 会话键：channel + user + agent 三元组。第 1 周 user 固定为 local。 */
    public String sessionKey(String agentName) {
        return "cli:local:" + agentName;
    }
}
