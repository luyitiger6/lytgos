package io.lytgos.web.api;

import io.lytgos.core.workspace.WorkspaceLocator;
import io.lytgos.persona.AgentCatalog;
import io.lytgos.persona.AgentLoader;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 列出工作区内全部 Agent 定义。
 *
 * <p>「条目 + 错误」两段结构：一个写坏的 AGENT.md 不让整个列表 500，但错误原因完整返回， 用户能直接定位是哪一份、哪一行。
 */
@RestController
public class ProfileController {

  private final WorkspaceLocator locator;
  private final AgentLoader loader;

  public ProfileController(WorkspaceLocator locator, AgentLoader loader) {
    this.locator = locator;
    this.loader = loader;
  }

  /** 全部 Agent 的配置摘要与解析错误。 */
  @GetMapping("/api/v1/profiles")
  public ApiResponse<ProfileList> list() {
    AgentCatalog catalog = loader.catalog(locator.get().agents());
    return ApiResponse.ok(
        new ProfileList(
            catalog.items().stream()
                .map(
                    entry ->
                        new ProfileView(
                            entry.name(),
                            entry.description(),
                            entry.provider(),
                            entry.model(),
                            entry.tools()))
                .toList(),
            catalog.errors().stream()
                .map(error -> new ProfileError(error.name(), error.message()))
                .toList()));
  }

  /** Agent 列表与解析错误。 */
  public record ProfileList(List<ProfileView> items, List<ProfileError> errors) {

    public ProfileList {
      items = List.copyOf(items);
      errors = List.copyOf(errors);
    }
  }

  /** 一份 Agent 的配置摘要；{@code name} 是目录名。 */
  public record ProfileView(
      String name, String description, String provider, String model, List<String> tools) {

    public ProfileView {
      tools = List.copyOf(tools);
    }
  }

  /** 一份解析失败的 Agent。 */
  public record ProfileError(String name, String message) {}
}
