package io.lytgos.cli;

import io.lytgos.core.LytgosException;
import io.lytgos.core.workspace.Workspace;
import io.lytgos.core.workspace.WorkspaceLocator;
import io.lytgos.persona.AgentLoader;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.Parameters;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * {@code lytgos profile}：Agent 定义的增删查。
 *
 * <p>工作区从容器的 {@link WorkspaceLocator} 取——首次访问才 discover，
 * 所以 {@code init} 之后工作区才存在的先后关系不受影响。</p>
 */
@Command(
        name = "profile",
        mixinStandardHelpOptions = true,
        description = "管理 Agent 定义",
        subcommands = {
                ProfileCommand.Create.class,
                ProfileCommand.ListAll.class,
                ProfileCommand.Show.class,
                ProfileCommand.Delete.class
        })
public final class ProfileCommand implements Runnable {

    private final LazyContext context;

    public ProfileCommand(LazyContext context) {
        this.context = context;
    }

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    private static Workspace workspace(LazyContext context) {
        return context.getBean(WorkspaceLocator.class).get();
    }

    private static void requireValidName(String name) {
        if (name == null || name.isBlank()
                || name.contains("/") || name.contains("\\") || name.contains("..")) {
            throw new LytgosException.InvalidAgentDefinition("Agent 名称不合法: " + name);
        }
    }

    /** {@code lytgos profile create <name>} */
    @Command(name = "create", description = "创建一个 Agent 定义模板")
    static final class Create implements Runnable {

        private final LazyContext context;

        Create(LazyContext context) {
            this.context = context;
        }

        @Parameters(index = "0", description = "Agent 名称")
        private String name;

        @Option(names = "--force", description = "已存在时覆盖")
        private boolean force;

        @Override
        public void run() {
            requireValidName(name);
            Path directory = workspace(context).agents().resolve(name);
            Path file = directory.resolve("AGENT.md");

            if (Files.exists(file) && !force) {
                throw new LytgosException.InvalidAgentDefinition(
                        "Agent 已存在: " + file + "（加 --force 覆盖）");
            }

            try {
                Files.createDirectories(directory);
                Files.writeString(file, template(name), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new LytgosException("创建 Agent 失败: " + file, e);
            }
            System.out.println("已创建: " + file);
        }

        private static String template(String name) {
            return """
                    ---
                    name: %s
                    description: 这个 Agent 做什么
                    identity: |
                      你是一个助手。
                    provider:
                      name: deepseek
                      model: deepseek-flash
                    tools:
                      - http_get
                    channels:
                      - cli
                    bootstrap:
                      - AGENTS.md
                      - SOUL.md
                      - USER.md
                    settings:
                      maxIterations: 10
                    ---

                    在这里写这个 Agent 的任务指令。
                    """.formatted(name);
        }
    }

    /** {@code lytgos profile list} */
    @Command(name = "list", description = "列出所有 Agent")
    static final class ListAll implements Runnable {

        private final LazyContext context;

        ListAll(LazyContext context) {
            this.context = context;
        }

        @Override
        public void run() {
            Path agentsRoot = workspace(context).agents();
            List<Path> files;
            try (Stream<Path> stream = Files.list(agentsRoot)) {
                files = stream
                        .filter(Files::isDirectory)
                        .map(dir -> dir.resolve("AGENT.md"))
                        .filter(Files::isRegularFile)
                        .sorted()
                        .toList();
            } catch (IOException e) {
                throw new LytgosException("无法读取 Agent 目录: " + agentsRoot, e);
            }

            if (files.isEmpty()) {
                System.out.println("（没有 Agent，执行 lytgos profile create <name> 创建）");
                return;
            }
            AgentLoader loader = context.getBean(AgentLoader.class);
            for (Path file : files) {
                var definition = loader.load(file);
                System.out.printf("%-16s %s%n",
                        definition.profile().name(),
                        definition.profile().description() == null ? "" : definition.profile().description());
            }
        }
    }

    /** {@code lytgos profile show <name>} */
    @Command(name = "show", description = "显示 Agent 定义原文")
    static final class Show implements Runnable {

        private final LazyContext context;

        Show(LazyContext context) {
            this.context = context;
        }

        @Parameters(index = "0", description = "Agent 名称")
        private String name;

        @Override
        public void run() {
            requireValidName(name);
            Path file = workspace(context).agents().resolve(name).resolve("AGENT.md");
            if (!Files.isRegularFile(file)) {
                throw new LytgosException.InvalidAgentDefinition("Agent 不存在: " + name);
            }
            try {
                System.out.print(Files.readString(file, StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new LytgosException("读取失败: " + file, e);
            }
        }
    }

    /** {@code lytgos profile delete <name>} */
    @Command(name = "delete", description = "删除一个 Agent（需二次确认）")
    static final class Delete implements Runnable {

        private final LazyContext context;

        Delete(LazyContext context) {
            this.context = context;
        }

        @Parameters(index = "0", description = "Agent 名称")
        private String name;

        @Option(names = {"-y", "--yes"}, description = "跳过确认")
        private boolean yes;

        @Override
        public void run() {
            requireValidName(name);
            Path directory = workspace(context).agents().resolve(name);
            if (!Files.isDirectory(directory)) {
                throw new LytgosException.InvalidAgentDefinition("Agent 不存在: " + name);
            }

            if (!yes && !confirm(directory)) {
                System.out.println("已取消");
                return;
            }

            try (Stream<Path> walk = Files.walk(directory)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException e) {
                        throw new LytgosException("删除失败: " + path, e);
                    }
                });
            } catch (IOException e) {
                throw new LytgosException("删除失败: " + directory, e);
            }
            System.out.println("已删除: " + directory);
        }

        private boolean confirm(Path directory) {
            System.out.println("将删除整个目录: " + directory);
            System.out.print("确认？输入 yes 继续: ");
            System.out.flush();
            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
                String line = reader.readLine();
                return line != null && "yes".equalsIgnoreCase(line.strip());
            } catch (IOException e) {
                throw new ParameterException(new CommandLine(this), "读取确认失败: " + e.getMessage());
            }
        }
    }
}

