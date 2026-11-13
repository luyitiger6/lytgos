package io.lytgos.provider;

import io.lytgos.core.chat.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * 按配置显式构造 {@link ChatModel}。
 *
 * <p>这里没有 Spring 容器参与：不注入、不扫描、不按类型查找，就是 new。
 * 什么时候构造多少个 provider，完全由 {@link ProviderRegistry} 按配置决定——
 * 多引一个 starter 不会凭空多出一个 provider，这正是 {@code AGENTS.md} 4.2 要的效果。</p>
 */
public final class ChatModelFactory {

    private ChatModelFactory() {
    }

    public static ChatModel create(ProviderConfig config) {
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .baseUrl(config.baseUrl())
                .apiKey(config.apiKey())
                .model(config.model())
                .build();

        OpenAiChatModel delegate = OpenAiChatModel.builder()
                .options(options)
                .build();

        return new SpringAiChatModel(delegate, options);
    }
}
