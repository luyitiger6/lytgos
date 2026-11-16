package io.lytgos.web.api;

import io.lytgos.core.session.SessionStore;
import io.lytgos.core.workspace.WorkspaceLocator;
import io.lytgos.runtime.WorkspaceConfig;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运行信息：证明服务跑在什么 JDK、指向哪个工作区、认了哪些 provider。
 *
 * <p>providers 只暴露 name 与 model。api-key 的值、甚至它引用哪个环境变量，都不出现在响应里—— 一旦返回「密钥来自哪个变量」，排错方便一分，攻击面就多一分。
 */
@RestController
public class InfoController {

  private final String appName;
  private final WorkspaceLocator locator;
  private final WorkspaceConfig config;
  private final SessionStore sessions;

  public InfoController(
      @Value("${spring.application.name:lytgos}") String appName,
      WorkspaceLocator locator,
      WorkspaceConfig config,
      SessionStore sessions) {
    this.appName = appName;
    this.locator = locator;
    this.config = config;
    this.sessions = sessions;
  }

  /** 应用名、版本、JDK、虚拟线程、工作区根、provider 名单与会话总数。 */
  @GetMapping("/api/v1/info")
  public ApiResponse<InfoView> info() {
    return ApiResponse.ok(
        new InfoView(
            appName,
            SystemInfoController.version(),
            System.getProperty("java.version"),
            Thread.currentThread().isVirtual(),
            locator.get().root().toString(),
            config.providers().stream()
                .map(provider -> new ProviderView(provider.name(), provider.model()))
                .toList(),
            sessions.list().size()));
  }

  /** 运行信息的对外视图。 */
  public record InfoView(
      String appName,
      String version,
      String javaVersion,
      boolean virtualThread,
      String workspaceRoot,
      List<ProviderView> providers,
      int sessions) {

    public InfoView {
      providers = List.copyOf(providers);
    }
  }

  /** provider 的公开摘要：不含任何凭据字段。 */
  public record ProviderView(String name, String model) {}
}
