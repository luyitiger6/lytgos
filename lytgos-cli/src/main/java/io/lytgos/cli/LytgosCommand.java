package io.lytgos.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * lytgos 命令行入口。
 *
 * <p>子命令在这里一次性声明完毕。它们都是无参构造——由 picocli 反射实例化，
 * 装配统一走 {@link CliContext#instance()}。</p>
 *
 * <p>异常处理器把 {@code LytgosException} 的 message 原样打到 stderr 并返回 1。
 * 不用堆栈刷屏，是因为这些异常的消息本身就是给用户看的：缺哪个环境变量、
 * 哪个域名不在白名单、哪个 provider 没注册。堆栈只在需要时用 {@code --debug}
 * 之类的开关打开，那是后面的事。</p>
 */
@Command(
        name = "lytgos",
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class,
        description = "企业级 Agent OS 控制面",
        subcommands = {
                InitCommand.class,
                ChatCommand.class,
                ProfileCommand.class,
                ProviderCommand.class,
                ToolCommand.class
        })
public class LytgosCommand implements Runnable {

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    public static void main(String[] args) {
        CommandLine commandLine = new CommandLine(new LytgosCommand());
        commandLine.setExecutionExceptionHandler((ex, command, parseResult) -> {
            // picocli 会把 Runnable 抛出的运行时异常再包一层 ExecutionException
            Throwable cause = ex.getCause() == null ? ex : ex.getCause();
            String message = cause.getMessage();
            System.err.println(message == null || message.isBlank() ? cause.toString() : message);
            return 1;
        });
        System.exit(commandLine.execute(args));
    }
}
