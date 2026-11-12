package io.lytgos.core.workspace;

import io.lytgos.core.LytgosException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code .lytgos/} 工作区的布局与初始化。
 *
 * <p>布局只有 10 个顶层条目：6 个目录 + 3 个 Bootstrap 文件 + {@code config.yaml}。
 * 数目固定是有意的——工作区是用户每天要看的东西，每加一个顶层条目都在增加认知负担，
 * 所以 {@code MEMORY.md} 放在 {@code memory/} 里面，{@code default} Agent 放在
 * {@code agents/} 里面，都不占顶层。</p>
 *
 * <p>初始化全程幂等：已存在的目录与文件一律不碰。用户改过的 {@code AGENTS.md}
 * 被重新 init 覆盖，是这个命令最不可接受的失败方式。</p>
 */
public final class Workspace {

    public static final String DIRECTORY_NAME = ".lytgos";

    /** 6 个顶层目录。 */
    public static final List<String> DIRECTORIES =
            List.of("agents", "skills", "output", "memory", "sessions", "logs");

    /** 3 个 Bootstrap 文件，内容会拼进 system prompt。 */
    public static final List<String> BOOTSTRAP_FILES = List.of("AGENTS.md", "SOUL.md", "USER.md");

    /** 工作区配置：provider 连接参数与沙箱白名单。 */
    public static final String CONFIG_FILE = "config.yaml";

    private static final String DEFAULT_AGENT_FILE = "default/AGENT.md";

    private final Path root;

    private Workspace(Path root) {
        this.root = root;
    }

    /** 直接指定工作区根（即 {@code .lytgos} 目录本身）。 */
    public static Workspace at(Path root) {
        return new Workspace(root.toAbsolutePath().normalize());
    }

    /**
     * 从「项目目录」创建工作区：根是 {@code <projectDir>/.lytgos}。
     *
     * <p>{@code init} 必须用这个而不是 {@link #at(Path)}——后者把传入目录当作工作区根，
     * 传项目目录会把 6 个目录铺在用户的项目根上，而 {@link #discover(Path)}
     * 随后找不到 {@code .lytgos}，init 与 chat 直接对不上。</p>
     */
    public static Workspace projectAt(Path projectDir) {
        return new Workspace(
                projectDir.toAbsolutePath().normalize().resolve(DIRECTORY_NAME));
    }

    /**
     * 从给定目录向上查找工作区。
     *
     * <p>允许在子目录里直接跑 {@code lytgos chat}——用户不该被迫先 {@code cd} 回工作区根目录。</p>
     */
    public static Workspace discover(Path start) {
        Path current = start.toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve(DIRECTORY_NAME);
            if (Files.isDirectory(candidate)) {
                return at(candidate);
            }
            current = current.getParent();
        }
        throw new LytgosException.InvalidConfiguration(
                "当前目录不在 lytgos 工作区内，请先执行 lytgos init");
    }

    public Path root() {
        return root;
    }

    public Path agents() {
        return root.resolve("agents");
    }

    public Path sessions() {
        return root.resolve("sessions");
    }

    public Path memory() {
        return root.resolve("memory");
    }

    public Path configFile() {
        return root.resolve(CONFIG_FILE);
    }

    /**
     * 创建工作区，返回本次新建的条目数。
     *
     * <p>除了 10 个顶层条目，还会在 {@code memory/} 下建空的 {@code MEMORY.md}，
     * 在 {@code agents/default/} 下建一份可直接使用的 {@code AGENT.md}——
     * 后者让 {@code init} 之后能立刻 {@code chat}，不必再手工创建 Agent。</p>
     */
    public int initialize() {
        int created = 0;
        try {
            Files.createDirectories(root);

            for (String name : DIRECTORIES) {
                Path dir = root.resolve(name);
                if (!Files.isDirectory(dir)) {
                    Files.createDirectories(dir);
                    created++;
                }
            }

            for (String name : BOOTSTRAP_FILES) {
                Path file = root.resolve(name);
                if (!Files.exists(file)) {
                    Files.writeString(file, bootstrapTemplate(name), StandardCharsets.UTF_8);
                    created++;
                }
            }

            Path memoryIndex = memory().resolve("MEMORY.md");
            if (!Files.exists(memoryIndex)) {
                Files.writeString(memoryIndex, "# 长期记忆\n\n<!-- 第 2 周起由系统写入 -->\n",
                        StandardCharsets.UTF_8);
                created++;
            }

            Path config = configFile();
            if (!Files.exists(config)) {
                Files.writeString(config, CONFIG_TEMPLATE, StandardCharsets.UTF_8);
                created++;
            }

            Path defaultAgent = agents().resolve(DEFAULT_AGENT_FILE);
            if (!Files.exists(defaultAgent)) {
                Files.createDirectories(defaultAgent.getParent());
                Files.writeString(defaultAgent, DEFAULT_AGENT, StandardCharsets.UTF_8);
                created++;
            }
        } catch (IOException e) {
            throw new LytgosException("工作区初始化失败: " + root, e);
        }
        return created;
    }

    private static String bootstrapTemplate(String fileName) {
        return switch (fileName) {
            case "AGENTS.md" -> """
                    # 工作区说明

                    这个文件描述本工作区的用途与约束，每次对话都会作为上下文加载。

                    <!-- 可自由编辑 -->
                    """;
            case "SOUL.md" -> """
                    # 人格与语气

                    描述 Agent 应当保持的语气、风格与边界。

                    <!-- 可自由编辑 -->
                    """;
            case "USER.md" -> """
                    # 用户信息

                    记录用户偏好、常用称呼、关注领域，让回答更贴合使用者。

                    <!-- 可自由编辑 -->
                    """;
            default -> "# " + fileName + "\n";
        };
    }

    /**
     * 工作区配置模板。
     *
     * <p>{@code api-key} 只写环境变量占位，不写真实值——这个文件虽然在工作区内、
     * 已经 gitignore，但用户很容易拷给别人排错，真实密钥不该出现在文件里。</p>
     */
    private static final String CONFIG_TEMPLATE = """
            # lytgos 工作区配置
            providers:
              deepseek:
                model: deepseek-chat
                api-key: ${DEEPSEEK_API_KEY}
                base-url: https://api.deepseek.com

            sandbox:
              # 域名白名单：为空时全部拒绝。
              # 它只覆盖 lytgos 进程内发起的请求；MCP server 是独立子进程，
              # 它自己的网络访问不受这里约束（启动时会打一行提示）。
              allowed-domains:
                - geocoding-api.open-meteo.com
                - api.open-meteo.com
              # 命令白名单：MCP server 的启动命令必须先在这里出现，否则拒绝启动。
              # 例如允许用 node 起 server 就写 - node。留空 = 任何 MCP server 都起不来。
              allowed-commands: []
            """;

    /**
     * 默认 Agent 定义。
     *
     * <p>{@code provider} 固定写 {@code deepseek}，与第 1 周的 provider 配置对齐；
     * 换 provider 时用户改这一处即可，不必动代码。</p>
     */
    private static final String DEFAULT_AGENT = """
            ---
            name: default
            description: 默认助手
            identity: |
              你是一个能调用工具完成任务的助手。需要事实性信息时先调用工具确认，不要凭记忆回答。
            provider:
              name: deepseek
              model: deepseek-chat
            tools:
              - http_get
              - file_read
            channels:
              - cli
            bootstrap:
              - AGENTS.md
              - SOUL.md
              - USER.md
            settings:
              maxIterations: 10
            ---

            回答用户问题；需要实时数据时调用 http_get。
            """;
}
