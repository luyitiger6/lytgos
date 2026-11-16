package io.lytgos.web;

import io.lytgos.core.audit.LlmCallAuditor;
import io.lytgos.core.workspace.Workspace;
import io.lytgos.core.workspace.WorkspaceLocator;
import io.lytgos.memory.MemoryStore;
import io.lytgos.persona.AgentLoader;
import io.lytgos.provider.ProviderRegistry;
import io.lytgos.runtime.AgentInvoker;
import io.lytgos.runtime.WorkspaceConfig;
import io.lytgos.web.support.FakeAgentInvoker;
import io.lytgos.web.support.InMemorySessionStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 测试上下文的业务依赖装配：临时工作区 + 内存会话 + 假 AgentInvoker。
 *
 * <p>不引 {@code RuntimeConfiguration}：那会拉起 SQLite、审计表与带真实凭据的 provider 注册表。 控制器测试只需要「依赖齐全且类型正确」，任何真实
 * IO 由对应模块自己的测试覆盖。
 *
 * <p>工作区用模板 {@code initialize()} 建在系统临时目录里，含可直接对话的 {@code default} Agent；
 * 全部测试类共享同一份，需要隔离的用例自行清理写入的内容。
 */
@Configuration(proxyBeanMethods = false)
public class WebTestBeans {

  private static final Path PROJECT = createProject();

  private static Path createProject() {
    try {
      Path project = Files.createTempDirectory("lytgos-web-test");
      Workspace.projectAt(project).initialize();
      return project;
    } catch (IOException e) {
      throw new UncheckedIOException("测试工作区创建失败", e);
    }
  }

  /** 测试工作区的项目目录（其下是 {@code .lytgos/}）。 */
  public static Path project() {
    return PROJECT;
  }

  @Bean
  public WorkspaceLocator workspaceLocator() {
    return new WorkspaceLocator(PROJECT);
  }

  @Bean
  public WorkspaceConfig workspaceConfig(WorkspaceLocator locator) {
    return WorkspaceConfig.load(locator.get().configFile());
  }

  @Bean
  public AgentLoader agentLoader() {
    return new AgentLoader();
  }

  @Bean
  public MemoryStore memoryStore(WorkspaceLocator locator) {
    return new MemoryStore(locator.get().root());
  }

  @Bean
  public InMemorySessionStore sessionStore() {
    return new InMemorySessionStore();
  }

  /** 空名单、空审计：ProviderRegistry 只要求 auditor 非空，测试不会构造任何模型。 */
  @Bean
  public ProviderRegistry providerRegistry() {
    LlmCallAuditor noop = record -> {};
    return new ProviderRegistry(List.of(), noop);
  }

  @Bean
  public AgentInvoker agentInvoker(AgentLoader loader, WorkspaceLocator locator) {
    return new FakeAgentInvoker(loader, locator);
  }
}
