package io.lytgos.cli;

import io.lytgos.core.LytgosException;
import io.lytgos.provider.ProviderConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WorkspaceConfigProvider} 的绑定行为。
 *
 * <p>变量名刻意用一个真实环境里不可能存在的名字，避免测试结果被开发机的
 * 环境变量左右。</p>
 */
class WorkspaceConfigProviderTest {

    private static final String API_KEY_VAR = "LYTGOS_TEST_CONFIG_API_KEY_9E27";

    @TempDir
    Path tempDir;

    @Test
    void resolvesPlaceholderFromEnvironment() throws IOException {
        Path config = writeConfig("${" + API_KEY_VAR + "}");

        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(
                new MapPropertySource("test-env", Map.of(API_KEY_VAR, "sk-test")));

        WorkspaceConfig bound = WorkspaceConfigProvider.load(config, environment);

        ProviderConfig provider = bound.providers().get(0);
        assertEquals("deepseek", provider.name());
        assertEquals("deepseek-flash", provider.model());
        assertEquals("sk-test", provider.apiKey());
        assertEquals("https://api.deepseek.com", provider.baseUrl());
        assertFalse(WorkspaceConfig.isMissingEnv(provider.apiKey()));
        assertEquals(Set.of("restapi.amap.com"), bound.allowedDomains());
        assertEquals(Set.of("node"), bound.allowedCommands());
    }

    @Test
    void keepsPlaceholderWhenEnvironmentVariableMissing() throws IOException {
        Path config = writeConfig("${" + API_KEY_VAR + "}");

        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);

        ProviderConfig provider = WorkspaceConfigProvider.load(config, environment).providers().get(0);

        assertEquals("${" + API_KEY_VAR + "}", provider.apiKey());
        assertTrue(WorkspaceConfig.isMissingEnv(provider.apiKey()));
        assertEquals(API_KEY_VAR, WorkspaceConfig.missingEnvVariable(provider.apiKey()));
    }

    @Test
    void reportsFilePathWhenYamlIsInvalid() throws IOException {
        Path config = tempDir.resolve("config.yaml");
        Files.writeString(config, "providers: [unclosed", StandardCharsets.UTF_8);

        LytgosException.InvalidConfiguration error = assertThrows(
                LytgosException.InvalidConfiguration.class,
                () -> WorkspaceConfigProvider.load(config, new StandardEnvironment()));

        assertTrue(error.getMessage().contains(config.toString()), error.getMessage());
        assertTrue(error.getMessage().contains("YAML"), error.getMessage());
    }

    private Path writeConfig(String apiKey) throws IOException {
        Path config = tempDir.resolve("config.yaml");
        Files.writeString(config, """
                providers:
                  deepseek:
                    model: deepseek-flash
                    api-key: %s
                    base-url: https://api.deepseek.com
                sandbox:
                  allowed-domains:
                    - restapi.amap.com
                  allowed-commands:
                    - node
                """.formatted(apiKey), StandardCharsets.UTF_8);
        return config;
    }
}
