package io.lytgos.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lytgos.core.tool.SandboxChecker;
import io.lytgos.core.workspace.WorkspaceLocator;
import io.lytgos.persona.AgentLoader;
import io.lytgos.provider.ProviderRegistry;
import io.lytgos.tool.FileTool;
import io.lytgos.tool.HttpTool;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * CLI 侧的装配 Bean。
 *
 * <p>{@link AgentRuntimeFactory} 是 per-Agent 对象的创建入口：它本身无状态，
 * 单例登记；每次调用产出一份新的运行时。</p>
 */
@Configuration(proxyBeanMethods = false)
public class CliBeans {

    @Bean
    public AgentRuntimeFactory agentRuntimeFactory(WorkspaceLocator locator,
                                                   SandboxChecker sandbox,
                                                   AgentLoader agentLoader,
                                                   ProviderRegistry providers,
                                                   ObjectMapper mapper,
                                                   HttpTool httpTool,
                                                   FileTool fileTool) {
        return new AgentRuntimeFactory(
                locator, sandbox, agentLoader, providers, mapper, httpTool, fileTool);
    }
}
