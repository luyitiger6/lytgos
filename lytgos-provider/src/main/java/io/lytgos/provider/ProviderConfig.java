package io.lytgos.provider;

import io.lytgos.core.LytgosException;

/**
 * 一个 provider 的连接参数。
 *
 * <p>四个字段全部必填，且全部来自配置文件——没有「默认走 OpenAI 官方」这种兜底。
 * 一个没写 base-url 的 provider 会悄悄指向公网上的另一家服务，这在企业场景里不是便利，
 * 是数据泄露。</p>
 */
public record ProviderConfig(String name, String model, String apiKey, String baseUrl) {

    public ProviderConfig {
        if (name == null || name.isBlank()) {
            throw new LytgosException.InvalidConfiguration("provider 缺少 name");
        }
        if (model == null || model.isBlank()) {
            throw new LytgosException.InvalidConfiguration("provider " + name + " 缺少 model");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new LytgosException.InvalidConfiguration(
                    "provider " + name + " 缺少 api-key（应通过环境变量注入）");
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new LytgosException.InvalidConfiguration("provider " + name + " 缺少 base-url");
        }
    }
}
