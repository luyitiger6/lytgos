package io.lytgos.core.agent;

import io.lytgos.core.chat.ChatModel;
import io.lytgos.core.chat.ChatRequest;
import io.lytgos.core.chat.ChatResult;
import io.lytgos.core.chat.Message;
import io.lytgos.core.chat.ToolCall;
import io.lytgos.core.session.Session;
import io.lytgos.core.tool.ToolExecutor;
import io.lytgos.core.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * ReAct 循环本体：组装 → 调用模型 → 有工具调用就执行并回填 → 直到模型给出文本。
 *
 * <p>这个类是整个产品的价值落点。所有治理能力都挂在它的两个出口上：
 * 模型调用只能走 {@link ChatModel}，工具执行只能走 {@link ToolExecutor}。
 * 循环本身不做任何「智能」判断——不猜、不重试、不压缩，只按协议推进。</p>
 *
 * <p>每一轮都把 assistant 的原始工具调用写进历史，再写对应的 tool 结果。
 * 这两条消息必须成对出现：只有调用没有结果，下一次请求会被上游以 400 拒绝。</p>
 */
public final class ReActLoop {

    private static final Logger LOG = LoggerFactory.getLogger(ReActLoop.class);

    private final ChatModel model;
    private final PromptBuilder promptBuilder;
    private final ToolRegistry toolRegistry;
    private final ToolExecutor toolExecutor;
    private final int maxIterations;

    public ReActLoop(ChatModel model,
                     PromptBuilder promptBuilder,
                     ToolRegistry toolRegistry,
                     ToolExecutor toolExecutor,
                     int maxIterations) {
        this.model = model;
        this.promptBuilder = promptBuilder;
        this.toolRegistry = toolRegistry;
        this.toolExecutor = toolExecutor;
        this.maxIterations = maxIterations;
    }

    /**
     * 跑完一次完整对话轮次，返回给用户的最终文本。
     *
     * @throws io.lytgos.core.LytgosException.SandboxViolation 工具被沙箱拒绝时向外抛，
     *         但历史里已补上对应的失败结果，下一轮对话不会因历史残缺而失败
     */
    public String run(Session session, String userInput) {
        session.append(Message.user(userInput));

        String lastText = "";
        for (int iteration = 1; iteration <= maxIterations; iteration++) {
            ChatResult result = model.call(new ChatRequest(promptBuilder.build(session), toolRegistry.specs()));

            if (!result.hasToolCalls()) {
                String text = result.content() == null ? "" : result.content();
                session.append(Message.assistant(text));
                return text;
            }

            String content = result.content() == null ? "" : result.content();
            session.append(Message.assistant(content, result.toolCalls()));
            if (!content.isBlank()) {
                lastText = content;
            }
            LOG.debug("第 {} 轮：模型请求 {} 个工具调用", iteration, result.toolCalls().size());

            executeAll(session, result.toolCalls());
        }

        LOG.warn("达到最大迭代次数 {}，返回已有文本", maxIterations);
        String fallback = lastText.isBlank()
                ? "（已达到最大迭代次数 " + maxIterations + "，未能给出最终答复）"
                : lastText;
        session.append(Message.assistant(fallback));
        return fallback;
    }

    private void executeAll(Session session, Iterable<ToolCall> calls) {
        for (ToolCall call : calls) {
            try {
                String output = toolExecutor.execute(call);
                session.append(Message.tool(call.id(), call.name(), output));
            } catch (RuntimeException e) {
                // 失败也必须回填：否则历史里留下「有调用、无结果」的残局，
                // 下一次请求会被上游直接拒绝，问题从一个工具蔓延成整段对话不可用
                session.append(Message.tool(call.id(), call.name(), "执行失败: " + e.getMessage()));
                throw e;
            }
        }
    }
}
