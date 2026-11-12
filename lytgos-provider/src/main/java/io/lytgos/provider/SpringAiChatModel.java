package io.lytgos.provider;

import io.lytgos.core.LytgosException;
import io.lytgos.core.chat.ChatModel;
import io.lytgos.core.chat.ChatRequest;
import io.lytgos.core.chat.ChatResult;
import io.lytgos.core.chat.Message;
import io.lytgos.core.chat.ToolCall;
import io.lytgos.core.chat.ToolSpec;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.List;

/**
 * 用 Spring AI 做协议转换的 {@link ChatModel} 实现。
 *
 * <p>只借 Spring AI 的两件事：消息与工具声明的序列化、SSE/HTTP 的传输细节。
 * <b>绝不让它执行工具</b>——所以每个 {@link ToolCallback} 的 {@code call} 都抛异常。
 * 这不是防御性编程，是 {@code AGENTS.md} 4.1 的硬要求：工具执行必须 100% 由
 * lytgos 的 {@code ToolExecutor} 发起，配额、白名单、沙箱、审计才有地方挂。</p>
 *
 * <p>传 {@code toolCallbacks} 是必须的——模型得先看到有哪些工具才可能发起调用。
 * Spring AI 的 {@code ChatModel.call()} 只把定义序列化进请求、把 {@code tool_calls}
 * 原样返回，不驱动执行循环；驱动循环的是 {@code ChatClient} 的 Advisor，这里不用它。</p>
 */
public final class SpringAiChatModel implements ChatModel {

    private final OpenAiChatModel delegate;
    private final OpenAiChatOptions baseOptions;

    public SpringAiChatModel(OpenAiChatModel delegate, OpenAiChatOptions baseOptions) {
        this.delegate = delegate;
        this.baseOptions = baseOptions;
    }

    @Override
    public ChatResult call(ChatRequest request) {
        Prompt prompt = new Prompt(toSpringMessages(request.messages()), options(request.tools()));
        ChatResponse response = delegate.call(prompt);

        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            throw new LytgosException("模型返回了空响应");
        }

        AssistantMessage output = response.getResult().getOutput();
        String content = output.getText();
        List<ToolCall> toolCalls = output.getToolCalls() == null
                ? List.of()
                : output.getToolCalls().stream()
                        .map(call -> new ToolCall(call.id(), call.name(), call.arguments()))
                        .toList();

        return new ChatResult(content, toolCalls);
    }

    private OpenAiChatOptions options(List<ToolSpec> specs) {
        if (specs.isEmpty()) {
            return baseOptions;
        }
        List<ToolCallback> callbacks = specs.stream().map(SpringAiChatModel::declarationOnly).toList();
        return baseOptions.mutate().toolCallbacks(callbacks).build();
    }

    /**
     * 把工具声明包装成 {@link ToolCallback}，但执行一律拒绝。
     *
     * <p>之所以还要提供 {@code call} 的实现：接口是抽象的，不实现编译不过。
     * 抛异常而不是返回错误字符串——如果哪天有代码路径真调到这里，说明有人绕过了
     * {@code ToolExecutor}，此时静默返回字符串会让这个越权**看起来正常**。</p>
     */
    private static ToolCallback declarationOnly(ToolSpec spec) {
        ToolDefinition definition = ToolDefinition.builder()
                .name(spec.name())
                .description(spec.description() == null ? "" : spec.description())
                .inputSchema(spec.inputSchema() == null ? "{}" : spec.inputSchema())
                .build();

        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                throw new UnsupportedOperationException(
                        "工具执行必须经过 lytgos ToolExecutor，不允许由 Spring AI 直接执行: " + definition.name());
            }
        };
    }

    private static List<org.springframework.ai.chat.messages.Message> toSpringMessages(List<Message> messages) {
        return messages.stream().map(SpringAiChatModel::toSpringMessage).toList();
    }

    private static org.springframework.ai.chat.messages.Message toSpringMessage(Message message) {
        return switch (message.role()) {
            case SYSTEM -> new SystemMessage(message.content() == null ? "" : message.content());
            case USER -> new UserMessage(message.content() == null ? "" : message.content());
            case ASSISTANT -> toAssistantMessage(message);
            case TOOL -> ToolResponseMessage.builder()
                    .responses(List.of(new ToolResponseMessage.ToolResponse(
                            message.toolCallId(),
                            message.toolName() == null ? "" : message.toolName(),
                            message.content() == null ? "" : message.content())))
                    .build();
        };
    }

    private static AssistantMessage toAssistantMessage(Message message) {
        AssistantMessage.Builder<?> builder = AssistantMessage.builder();
        builder.content(message.content() == null ? "" : message.content());
        if (message.hasToolCalls()) {
            builder.toolCalls(message.toolCalls().stream()
                    .map(call -> new AssistantMessage.ToolCall(
                            call.id(), "function", call.name(), call.arguments()))
                    .toList());
        }
        return builder.build();
    }
}
