package io.lytgos.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * 根命令：只承载子命令声明与版本信息。
 *
 * <p>刻意保持无参构造、不注入任何依赖——它是 {@code --version} / {@code --help} 路径上
 * 唯一被实例化的命令，一旦它依赖容器，这两条命令就会连带启动容器，启动从百毫秒级掉到秒级。</p>
 *
 * <p>子命令由 {@link SpringCommandFactory} 向容器要实例，依赖走构造注入。
 * 异常处理器在 {@link LytgosCli#main} 里安装：把 {@code LytgosException} 的 message
 * 原样打到 stderr 并返回 1，不用堆栈刷屏——这些消息本身就是给用户看的：缺哪个环境变量、
 * 哪个域名不在白名单、哪个 provider 没注册。</p>
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
}
