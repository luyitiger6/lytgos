package io.lytgos.cli;

import io.lytgos.core.LytgosException;
import picocli.CommandLine;

/**
 * 进程入口。
 *
 * <p>这里只做三件事：建一个尚未启动的容器句柄、把 picocli 与容器桥接起来、把退出码交给 JVM。
 * 容器要等第一个子命令被创建时才真正 {@code refresh()}，见 {@link LazyContext}。</p>
 *
 * <p>根命令 {@link LytgosCommand} 用无参构造直接 new，不经过容器：一旦它带上注入依赖，
 * {@code --version} 与 {@code --help} 就会连带着启动容器，CLI 启动会从百毫秒级掉到秒级。</p>
 */
public final class LytgosCli {

    private LytgosCli() {
    }

    public static void main(String[] args) {
        int exitCode;
        try (LazyContext context = new LazyContext()) {
            CommandLine commandLine = new CommandLine(new LytgosCommand(), new SpringCommandFactory(context));
            commandLine.setExecutionExceptionHandler((ex, command, parseResult) -> {
                // picocli 会把 Runnable 抛出的运行时异常再包一层；容器 Bean 创建失败时
                // Spring 还会把领域异常包进 BeanCreationException。沿 cause 链找最深的
                // LytgosException，把它的 message 原样交给用户——「当前目录不在 lytgos
                // 工作区内」不该被埋在一串 Spring 内部类名后面。
                Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                for (Throwable t = ex; t != null; t = t.getCause()) {
                    if (t instanceof LytgosException) {
                        cause = t;
                    }
                }
                String message = cause.getMessage();
                System.err.println(message == null || message.isBlank() ? cause.toString() : message);
                return 1;
            });
            exitCode = commandLine.execute(args);
        }
        System.exit(exitCode);
    }
}
