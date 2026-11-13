package io.lytgos.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lytgos.core.session.FileSessionStore;
import io.lytgos.core.session.SessionStore;
import io.lytgos.core.tool.SandboxChecker;
import io.lytgos.core.workspace.WorkspaceLocator;
import io.lytgos.persona.AgentLoader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * 无状态能力类的 Bean 声明：序列化、工作区定位、沙箱、会话存储、Agent 加载。
 *
 * <p>能力类本身保持纯 POJO、不写 {@code @Component}——容器不做类路径扫描，
 * 装配关系集中在这里才好查；既有单测直接 {@code new} 也不受影响。</p>
 *
 * <p>{@code WorkspaceConfig} 也在这里登记：它由 {@link WorkspaceLocator} 决定路径，
 * 沙箱白名单又依赖它的内容。三个 Bean 的先后顺序由构造参数表达，不靠初始化顺序碰运气。</p>
 */
@Configuration(proxyBeanMethods = false)
public class CoreBeans {

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    @Bean
    public WorkspaceLocator workspaceLocator() {
        return new WorkspaceLocator(Path.of(System.getProperty("user.dir")));
    }

    /**
     * 宽松加载：缺失的环境变量原样保留为 {@code ${VAR}} 占位，严格性在
     * {@code ProviderRegistry} 首次构造 {@code ChatModel} 时才生效。
     */
    @Bean
    public WorkspaceConfig workspaceConfig(WorkspaceLocator locator) {
        return WorkspaceConfig.load(locator.get().configFile(), false);
    }

    @Bean
    public SandboxChecker sandboxChecker(WorkspaceLocator locator, WorkspaceConfig config) {
        return new SandboxChecker(
                locator.get().root(), config.allowedCommands(), config.allowedDomains());
    }

    @Bean
    public SessionStore sessionStore(WorkspaceLocator locator, ObjectMapper mapper) {
        return new FileSessionStore(locator.get().sessions(), mapper);
    }

    @Bean
    public AgentLoader agentLoader() {
        return new AgentLoader();
    }
}
