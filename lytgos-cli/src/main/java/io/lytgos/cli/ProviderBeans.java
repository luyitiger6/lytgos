package io.lytgos.cli;

import io.lytgos.provider.ProviderRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provider 的 Bean 声明。
 *
 * <p>只有一个 Bean：{@link ProviderRegistry}。provider 不逐个注册成 Bean——那会退化成
 * 按类型发现，多引一个 starter 就凭空多出一个 provider，与「配置驱动」的约定冲突。
 * 注册表内部按配置懒创建 {@code ChatModel}，见 {@code ProviderRegistry} 的说明。</p>
 */
@Configuration(proxyBeanMethods = false)
public class ProviderBeans {

    @Bean
    public ProviderRegistry providerRegistry(WorkspaceConfig config) {
        return new ProviderRegistry(config.providers());
    }
}
