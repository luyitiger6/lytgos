package io.lytgos.tool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * {@link ShellToolTest} 的超时夹具：启动后先写下自己的 pid，再挂住进程等沙箱终止。
 *
 * <p>不依赖任何平台特有命令（Windows 没有 {@code sleep.exe}），用当前 JDK 自身即可复现「长跑进程」。 pid
 * 文件让测试能在超时返回后确认子进程真的死了——只看返回文本证明不了回收。
 */
public final class SleepyMain {

  private SleepyMain() {}

  public static void main(String[] args) throws IOException, InterruptedException {
    if (args.length > 0) {
      Files.writeString(
          Path.of(args[0]), Long.toString(ProcessHandle.current().pid()), StandardCharsets.UTF_8);
    }
    Thread.sleep(60_000);
  }
}
