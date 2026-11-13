package io.lytgos.provider;

import io.lytgos.core.LytgosException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ProviderRegistry} 的登记与查找行为。
 *
 * <p>这些用例守的是「报错必须自带答案」：找不到 provider 时要列出有哪些，
 * 没配 provider 时要说明是配置为空而不是名字写错。</p>
 */
class ProviderRegistryTest {

    private static ProviderConfig config(String name) {
        return new ProviderConfig(name, "test-model", "sk-test", "https://example.invalid");
    }

    @Test
    void rejectsDuplicateNames() {
        LytgosException.InvalidConfiguration error = assertThrows(
                LytgosException.InvalidConfiguration.class,
                () -> new ProviderRegistry(List.of(config("deepseek"), config("deepseek"))));

        assertTrue(error.getMessage().contains("重名"), error.getMessage());
        assertTrue(error.getMessage().contains("deepseek"), error.getMessage());
    }

    @Test
    void unknownProviderListsRegisteredNames() {
        ProviderRegistry registry = new ProviderRegistry(List.of(config("deepseek"), config("qwen")));

        LytgosException.UnknownProvider error = assertThrows(
                LytgosException.UnknownProvider.class, () -> registry.require("dpseek"));

        assertTrue(error.getMessage().contains("dpseek"), error.getMessage());
        assertTrue(error.getMessage().contains("deepseek"), error.getMessage());
        assertTrue(error.getMessage().contains("qwen"), error.getMessage());
    }

    @Test
    void emptyConfigExplainsThatNothingIsConfigured() {
        ProviderRegistry registry = new ProviderRegistry(List.of());

        LytgosException.InvalidConfiguration error = assertThrows(
                LytgosException.InvalidConfiguration.class, () -> registry.require("deepseek"));

        // 空列表时最该说清的是「你还没配」，而不是让人以为自己把名字拼错了
        assertTrue(error.getMessage().contains("没有任何 provider"), error.getMessage());
        assertTrue(error.getMessage().contains("deepseek"), error.getMessage());
    }

    @Test
    void namesDoesNotRequireResolvableKeys() {
        // api-key 是未设置的占位：names() 是只读操作，不该因环境变量缺失而失败
        ProviderConfig unresolved = new ProviderConfig(
                "deepseek", "test-model", "${LYTGOS_TEST_REGISTRY_MISSING_5C1A}", "https://example.invalid");
        ProviderRegistry registry = new ProviderRegistry(List.of(unresolved));

        assertEquals(Set.of("deepseek"), registry.names());
        assertEquals(1, registry.size());

        // 同一条配置在真正取用时才报缺失变量名
        LytgosException.InvalidConfiguration error = assertThrows(
                LytgosException.InvalidConfiguration.class, () -> registry.require("deepseek"));
        assertTrue(error.getMessage().contains("LYTGOS_TEST_REGISTRY_MISSING_5C1A"), error.getMessage());
    }
}
