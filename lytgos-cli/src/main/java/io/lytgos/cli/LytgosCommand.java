package io.lytgos.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * lytgos 命令行入口。
 *
 * <p>当前只承载版本与帮助输出，子命令随功能落地逐步加入。</p>
 */
@Command(
        name = "lytgos",
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class,
        description = "企业级 Agent OS 控制面"
)
public class LytgosCommand implements Runnable {

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    public static void main(String[] args) {
        System.exit(new CommandLine(new LytgosCommand()).execute(args));
    }
}
