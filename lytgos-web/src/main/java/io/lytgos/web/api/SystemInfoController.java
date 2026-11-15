package io.lytgos.web.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 只读系统信息端点：用运行时事实证明虚拟线程已启用、构建 JDK 符合基线。 */
@RestController
public class SystemInfoController {

  private final String appName;

  public SystemInfoController(@Value("${spring.application.name:lytgos}") String appName) {
    this.appName = appName;
  }

  /** 返回应用名、版本、构建 JDK 与当前请求线程是否为虚拟线程。 */
  @GetMapping("/api/v1/system/info")
  public ApiResponse<SystemInfo> info() {
    return ApiResponse.ok(
        new SystemInfo(
            appName,
            version(),
            System.getProperty("java.version"),
            Thread.currentThread().isVirtual()));
  }

  /** 版本号来自 jar 清单的 Implementation-Version；直接跑 classes 时退化为 dev。 */
  private static String version() {
    String version = SystemInfoController.class.getPackage().getImplementationVersion();
    return version == null ? "dev" : version;
  }

  /** 系统信息的对外视图。 */
  public record SystemInfo(
      String appName, String version, String javaVersion, boolean virtualThread) {}
}
