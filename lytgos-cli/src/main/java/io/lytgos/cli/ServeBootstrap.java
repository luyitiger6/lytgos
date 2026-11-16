package io.lytgos.cli;

import io.lytgos.core.workspace.Workspace;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextClosedEvent;

/**
 * {@code lytgos serve} 的容器启动与关闭等待。
 *
 * <p>Spring 类型集中在本类，不放进 {@link ServeCommand}：picocli 在 {@code CommandLine} 构造阶段就会实例化全部子命令，
 * 类验证会解析方法签名与局部变量里的类型。若 {@code ServeCommand} 的方法签名直接出现 {@code ConfigurableApplicationContext}（及其父接口
 * {@code MessageSource} 等），{@code --version} 的启动路径就会 连带加载 6 个 spring-context 类，触犯 09 号闸门的 L0
 * 硬判据。本类只在 serve 真正执行时才首次使用。
 */
final class ServeBootstrap {

  private ServeBootstrap() {}

  /**
   * 启动 Spring Boot 容器并阻塞到关闭。
   *
   * @param port 监听端口
   * @param explicitRoot 命令行显式给出的工作区根；为 null 时不传 {@code --lytgos.root}，交给环境变量/当前目录解析
   * @param startDir 用于发现工作区的起点（已按 --root &gt; LYTGOS_ROOT &gt; 当前目录算好）
   */
  static void run(int port, Path explicitRoot, Path startDir) {
    // 先于 Spring 启动校验工作区：容器 refresh 失败会先被 Spring 打出一整段堆栈，
    // 而这里要的是「一行可读错误 + 非 0 退出」。发现逻辑与 CoreBeans 保持一致：
    // --root > LYTGOS_ROOT > 当前目录。
    Workspace.discover(startDir);

    List<String> args = new ArrayList<>();
    args.add("--server.port=" + port);
    args.add("--server.address=127.0.0.1");
    // CLI 模块没有自己的 application.yaml（它会被 lytgos-boot 的 fat jar 同时带上，
    // 两个同名文件在 classpath 上互相遮蔽）；公共配置以命令行导入的方式取得。
    args.add("--spring.config.import=classpath:lytgos-web-defaults.yaml");
    if (explicitRoot != null) {
      args.add("--lytgos.root=" + explicitRoot.toAbsolutePath().normalize());
    }

    ConfigurableApplicationContext context =
        new SpringApplicationBuilder(ServeApplication.class).run(args.toArray(String[]::new));
    awaitShutdown(context);
  }

  /**
   * 阻塞到容器关闭。不能直接让 {@code run()} 返回：{@link LytgosCli#main} 在命令结束后会 {@code System.exit}，
   * 服务会被立刻带走。容器关闭（Ctrl+C 触发的 shutdown hook）后放行，进程以 0 退出。
   */
  private static void awaitShutdown(ConfigurableApplicationContext context) {
    CountDownLatch stopped = new CountDownLatch(1);
    context.addApplicationListener(
        event -> {
          if (event instanceof ContextClosedEvent) {
            stopped.countDown();
          }
        });
    if (!context.isActive()) {
      return;
    }
    try {
      stopped.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
