package io.lytgos.cli;

import io.lytgos.core.LytgosException;
import io.lytgos.provider.ProviderConfig;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 把 {@code .lytgos/config.yaml} 载入 Spring {@code Environment}，再用 {@code Binder} 绑定。
 *
 * <p>三步：{@link YamlPropertySourceLoader} 读成 PropertySource 追加到 Environment；
 * {@code Binder} 绑 {@code providers} 与 {@code sandbox} 两段；{@code ${VAR}} 占位由
 * Environment 做宽松解析——变量存在就替换，缺失则原样保留，交给真正构造
 * {@code ChatModel} 的地方报错。</p>
 *
 * <p>不用 {@code @ConfigurationProperties}：那要求在启动期就知道配置文件路径，
 * 而 {@code lytgos init} 场景下工作区还不存在；也不做类路径扫描，绑定的始终是
 * 显式给出的那一个文件。</p>
 */
final class WorkspaceConfigProvider {

    private static final String PROVIDERS = "providers";
    private static final String SANDBOX = "sandbox";

    private WorkspaceConfigProvider() {
    }

    static WorkspaceConfig load(Path configFile) {
        return load(configFile, new StandardEnvironment());
    }

    /** 供测试注入自定义 {@code Environment}，替身系统属性与环境变量。 */
    static WorkspaceConfig load(Path configFile, ConfigurableEnvironment environment) {
        if (!Files.isRegularFile(configFile)) {
            throw new LytgosException.InvalidConfiguration("配置文件不存在: " + configFile);
        }
        addYamlSource(configFile, environment);

        Binder binder = Binder.get(environment);
        Map<String, ProviderProperties> providers = binder
                .bind(PROVIDERS, Bindable.mapOf(String.class, ProviderProperties.class))
                .orElse(Map.of());
        SandboxProperties sandbox = binder.bind(SANDBOX, SandboxProperties.class)
                .orElseGet(SandboxProperties::empty);

        List<ProviderConfig> configs = providers.entrySet().stream()
                .map(entry -> entry.getValue().toConfig(entry.getKey()))
                .toList();
        return new WorkspaceConfig(configs, sandbox.allowedDomains(), sandbox.allowedCommands());
    }

    /**
     * 把 YAML 读成 PropertySource。<p>重复键不需要在这里预检：
     * Spring Boot 4 的 {@code OriginTrackedYamlLoader} 内部已设
     * {@code LoaderOptions.setAllowDuplicateKeys(false)}，重复键会在这里抛出
     * 「found duplicate key ...」。将来升级 Boot 时若该默认值发生变化，
     * {@code WorkspaceConfigProviderTest.rejectsDuplicateProviderKeys} 会先红。
     */
    private static void addYamlSource(Path configFile, ConfigurableEnvironment environment) {
        List<PropertySource<?>> sources;
        try {
            sources = new YamlPropertySourceLoader()
                    .load(configFile.getFileName().toString(), new FileSystemResource(configFile));
        } catch (IOException | YAMLException e) {
            // Spring Boot 4 的 YamlPropertySourceLoader 对语法错误的处理是让 SnakeYAML 的
            // RuntimeException 直接冒出，不包成 IOException；这里统一收敛成配置错误。
            throw new LytgosException.InvalidConfiguration(
                    "配置文件不是合法 YAML: " + configFile + " → " + e.getMessage());
        }
        if (sources.isEmpty()) {
            throw new LytgosException.InvalidConfiguration("配置文件为空: " + configFile);
        }
        for (PropertySource<?> source : sources) {
            environment.getPropertySources().addLast(source);
        }
    }

    /** 绑定载体：组件名与 YAML 的 kebab-case 键宽松匹配，校验交给 {@link ProviderConfig}。 */
    record ProviderProperties(String model, String apiKey, String baseUrl) {

        ProviderConfig toConfig(String name) {
            return new ProviderConfig(name, model, apiKey, baseUrl);
        }
    }

    /** 集合缺失时绑定器给 {@code null}，用 {@code @DefaultValue} 归一成空集合。 */
    record SandboxProperties(@DefaultValue List<String> allowedDomains,
                             @DefaultValue List<String> allowedCommands) {

        static SandboxProperties empty() {
            return new SandboxProperties(List.of(), List.of());
        }
    }
}
