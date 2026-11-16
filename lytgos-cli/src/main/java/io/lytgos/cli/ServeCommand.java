package io.lytgos.cli;

import java.nio.file.Path;
import java.util.Objects;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * {@code lytgos serve}：把 REST 端点与管理台跑起来。
 *
 * <p>与 {@code java -jar lytgos.jar} 的差别只在启动参数：两者都从 {@link ServeApplication} / {@code
 * LytgosApplication} 导入同一份 {@code RuntimeConfiguration}、打开同一个工作区的同一个数据库。
 *
 * <p>参数走命令行而不是 {@code SpringApplicationBuilder.properties()}：后者是默认属性，优先级最低， 会被 {@code LYTGOS_ROOT}
 * 之类的环境变量盖掉；命令行参数优先级最高，能保证 {@code --root} 一定生效。
 *
 * <p>本类刻意不出现任何 Spring 类型（容器启动在 {@link ServeBootstrap}）：picocli 在 {@code CommandLine}
 * 构造阶段就实例化全部子命令，方法签名里的容器类型会连带加载 spring-context，令 {@code --version} 触犯 09 号闸门的 L0 硬判据。
 */
@Command(name = "serve", mixinStandardHelpOptions = true, description = "启动 HTTP 服务（REST 端点与只读管理台）")
public final class ServeCommand implements Runnable {

  /** 默认端口：避开 8080（VMware vmnat 常占）、8081（lytgos-channel）、9898（lytgos.jar）。 */
  private static final int DEFAULT_PORT = 8989;

  @Option(
      names = {"-p", "--port"},
      defaultValue = "" + DEFAULT_PORT,
      description = "监听端口（默认 ${DEFAULT-VALUE}）")
  private int port;

  @Option(
      names = {"-r", "--root"},
      description = "工作区根目录（默认当前目录；也可用环境变量 LYTGOS_ROOT）")
  private Path root;

  /**
   * 只用于满足 {@link SpringCommandFactory} 的轻量构造器约定：有本构造器时 picocli 创建子命令 不会触发容器 refresh， {@code
   * --version} / {@code --help} 的启动预算才守得住。serve 自己不使用这个句柄 —— 它的容器由 Spring Boot 按 ServeApplication
   * 独立创建。
   */
  public ServeCommand(LazyContext context) {
    Objects.requireNonNull(context, "context");
  }

  @Override
  public void run() {
    ServeBootstrap.run(port, root, startDir());
  }

  /** 发现起点：--root > LYTGOS_ROOT > 当前目录（与 CoreBeans 的发现顺序一致）。 */
  private Path startDir() {
    if (root != null) {
      return root;
    }
    String fromEnv = System.getenv("LYTGOS_ROOT");
    if (fromEnv == null || fromEnv.isBlank()) {
      return Path.of(System.getProperty("user.dir"));
    }
    return Path.of(fromEnv);
  }
}
