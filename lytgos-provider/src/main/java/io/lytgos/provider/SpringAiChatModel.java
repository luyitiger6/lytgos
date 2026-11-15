package io.lytgos.provider;

import io.lytgos.core.LytgosException;
import io.lytgos.core.chat.ChatModel;
import io.lytgos.core.chat.ChatRequest;
import io.lytgos.core.chat.ChatResult;
import io.lytgos.core.chat.Message;
import io.lytgos.core.chat.ToolCall;
import io.lytgos.core.chat.ToolSpec;
import java.util.List;
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

/**
 * 用 Spring AI 做协议转换的 {@link ChatModel} 实现。
 *
 * <p>只借 Spring AI 的两件事：消息与工具声明的序列化、SSE/HTTP 的传输细节。 <b>绝不让它执行工具</b>——所以每个 {@link ToolCallback} 的
 * {@code call} 都抛异常。 这不是防御性编程，是 {@code AGENTS.md} 4.1 的硬要求：工具执行必须 100% 由 lytgos 的 {@code
 * ToolExecutor} 发起，配额、白名单、沙箱、审计才有地方挂。
 *
 * <p>传 {@code toolCallbacks} 是必须的——模型得先看到有哪些工具才可能发起调用。 Spring AI 的 {@code ChatModel.call()}
 * 只把定义序列化进请求、把 {@code tool_calls} 原样返回，不驱动执行循环；驱动循环的是 {@code ChatClient} 的 Advisor，这里不用它。
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
    Prompt prompt = new Prompt(toSpringMessages(request.messages()), options(request));
    ChatResponse response = delegate.call(prompt);

    if (response == null) {
      throw new LytgosException("模型返回了空响应");
    }
    var result = response.getResult();
    if (result == null || result.getOutput() == null) {
      throw new LytgosException("模型返回了空响应");
    }

    AssistantMessage output = result.getOutput();
    String content = output.getText();
    List<ToolCall> toolCalls =
        output.getToolCalls() == null
            ? List.of()
            : output.getToolCalls().stream()
                .map(call -> new ToolCall(call.id(), call.name(), call.arguments()))
                .toList();

    return new ChatResult(content, toolCalls, usageOf(response));
  }

  /**
   * 取上游 token 用量；任何字段缺失一律记 0。
   *
   * <p>不因「没给用量」报错：兼容 OpenAI 协议的供应商并非都返回 usage，审计表的三列 又都是 NOT NULL——把缺失当成 0 是唯一能两边都成立的口径。
   */
  private static ChatResult.Usage usageOf(ChatResponse response) {
    if (response.getMetadata() == null || response.getMetadata().getUsage() == null) {
      return ChatResult.Usage.ZERO;
    }
    var usage = response.getMetadata().getUsage();
    return new ChatResult.Usage(
        zeroIfNull(usage.getPromptTokens()),
        zeroIfNull(usage.getCompletionTokens()),
        zeroIfNull(usage.getTotalTokens()));
  }

  private static int zeroIfNull(Integer value) {
    return value == null ? 0 : value;
  }

  /** 请求级 temperature 与工具声明一起落到选项副本上；两者都不存在时保持原选项。 */
  private OpenAiChatOptions options(ChatRequest request) {
    boolean hasTemperature = request.temperature() != null;
    boolean hasTools = !request.tools().isEmpty();
    if (!hasTemperature && !hasTools) {
      return baseOptions;
    }
    OpenAiChatOptions.Builder builder = baseOptions.mutate();
    if (hasTemperature) {
      builder.temperature(request.temperature());
    }
    if (hasTools) {
      List<ToolCallback> callbacks =
          request.tools().stream().map(SpringAiChatModel::declarationOnly).toList();
      builder.toolCallbacks(callbacks);
    }
    return builder.build();
  }

  /**
   * 把工具声明包装成 {@link ToolCallback}，但执行一律拒绝。
   *
   * <p>之所以还要提供 {@code call} 的实现：接口是抽象的，不实现编译不过。 抛异常而不是返回错误字符串——如果哪天有代码路径真调到这里，说明有人绕过了 {@code
   * ToolExecutor}，此时静默返回字符串会让这个越权**看起来正常**。
   */
  private static ToolCallback declarationOnly(ToolSpec spec) {
    ToolDefinition definition =
        ToolDefinition.builder()
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

  private static List<org.springframework.ai.chat.messages.Message> toSpringMessages(
      List<Message> messages) {
    return messages.stream().map(SpringAiChatModel::toSpringMessage).toList();
  }

  private static org.springframework.ai.chat.messages.Message toSpringMessage(Message message) {
    return switch (message.role()) {
      case SYSTEM -> new SystemMessage(message.content() == null ? "" : message.content());
      case USER -> new UserMessage(message.content() == null ? "" : message.content());
      case ASSISTANT -> toAssistantMessage(message);
      case TOOL ->
          ToolResponseMessage.builder()
              .responses(
                  List.of(
                      new ToolResponseMessage.ToolResponse(
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
      builder.toolCalls(
          message.toolCalls().stream()
              .map(
                  call ->
                      new AssistantMessage.ToolCall(
                          call.id(), "function", call.name(), call.arguments()))
              .toList());
    }
    return builder.build();
  }
}
