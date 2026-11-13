package io.lytgos.cli;

import io.lytgos.core.workspace.Workspace;
import picocli.CommandLine.Command;

import java.nio.file.Path;

/**
 * {@code lytgos init}：在当前目录创建 {@code .lytgos/} 工作区。
 *
 * <p>幂等是硬要求——重复执行不能覆盖用户改过的 {@code AGENTS.md}。
 * 这条约束的实现落在 {@link Workspace#initialize()}，命令本身只负责报告结果。</p>
 */
@Command(name = "init", mixinStandardHelpOptions = true, description = "在当前目录创建 .lytgos 工作区")
public final class InitCommand implements Runnable {

    /**
     * 参数刻意不保存：{@code init} 自己必须不碰容器（此刻工作区还不存在，
     * refresh 必然失败）。但构造器签名必须保留——{@link SpringCommandFactory}
     * 优先走 {@code (LazyContext)} 分支；若改成无参构造，工厂会退到
     * {@code createBean}，{@code init} 就会被自己的容器启动拦死。
     */
    public InitCommand(LazyContext context) {
    }

    @Override
    public void run() {
        // 刻意不碰容器：这一步工作区还不存在，容器刷新会在工作区发现处失败。
        Workspace workspace = Workspace.projectAt(Path.of(System.getProperty("user.dir")));
        int created = workspace.initialize();

        System.out.println("工作区已就绪: " + workspace.root());
        System.out.println("本次新建 " + created + " 项");
        System.out.println();
        System.out.println("下一步：设置 API key 环境变量，然后执行 lytgos chat");
        System.out.println("  PowerShell:  $env:DEEPSEEK_API_KEY = \"sk-...\"");
        System.out.println("  Bash:        export DEEPSEEK_API_KEY=sk-...");
    }
}
