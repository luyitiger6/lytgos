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
@Command(name = "init", description = "在当前目录创建 .lytgos 工作区")
public final class InitCommand implements Runnable {

    @Override
    public void run() {
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
