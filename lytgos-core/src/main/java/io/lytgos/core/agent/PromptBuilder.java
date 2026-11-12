package io.lytgos.core.agent;

import io.lytgos.core.LytgosException;
import io.lytgos.core.chat.Message;
import io.lytgos.core.session.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 把 Profile、Bootstrap 文件与历史拼成一次请求的消息序列。
 *
 * <p>拼的是结构化消息而不是一整段字符串文本：工具声明要走 {@code ChatRequest.tools}
 * 独立字段，历史要走 role/content 结构，塞进一个字符串会丢掉 role 语义，
 * 上游模型也就无从区分谁说的话。</p>
 */
public final class PromptBuilder {

    private static final Logger LOG = LoggerFactory.getLogger(PromptBuilder.class);

    /** 历史消息上限。超过后从最早的消息开始丢，但不切断 tool 调用与结果的配对。 */
    private static final int MAX_HISTORY_MESSAGES = 40;

    private final String systemPrompt;

    private PromptBuilder(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    /**
     * 从 Profile 构造。
     *
     * @param instructions {@code AGENT.md} 中 {@code ---} 之后的正文，作为任务指令
     */
    public static PromptBuilder from(Profile profile, Path workspaceRoot, String instructions) {
        StringBuilder prompt = new StringBuilder();

        if (profile.identity() != null && !profile.identity().isBlank()) {
            prompt.append(profile.identity().strip()).append("\n\n");
        }
        if (instructions != null && !instructions.isBlank()) {
            prompt.append(instructions.strip()).append("\n\n");
        }
        for (String fileName : profile.bootstrap()) {
            requirePlainFileName(fileName);
            Path file = workspaceRoot.resolve(fileName);
            if (!Files.isRegularFile(file)) {
                // init 已建好的文件可能被用户删掉，缺一个不影响对话，跳过即可
                continue;
            }
            try {
                String content = Files.readString(file, StandardCharsets.UTF_8).strip();
                if (!content.isEmpty()) {
                    prompt.append("## ").append(fileName).append("\n\n").append(content).append("\n\n");
                }
            } catch (IOException e) {
                LOG.warn("Bootstrap 文件读取失败，跳过: {}", file);
            }
        }

        return new PromptBuilder(prompt.toString().strip());
    }

    /**
     * 组装一次请求的消息序列：system + 历史。
     *
     * <p>本轮输入由调用方先写入 Session 再调用本方法——ReAct 循环要跑多轮，
     * 只有第一轮有「本轮输入」，后续轮次都是重放历史，把输入当作参数传会让多轮时重复拼接。</p>
     *
     * <p>历史裁剪按「用户消息」边界对齐。若从中间直接切，可能留下一条没有对应
     * assistant 调用的 tool 结果，上游会以 400 拒绝整个请求——裁断本身就成了故障源。</p>
     */
    public List<Message> build(Session session) {
        List<Message> messages = new ArrayList<>();
        if (!systemPrompt.isBlank()) {
            messages.add(Message.system(systemPrompt));
        }
        messages.addAll(trim(session.history()));
        return messages;
    }

    private static List<Message> trim(List<Message> history) {
        if (history.size() <= MAX_HISTORY_MESSAGES) {
            return history;
        }
        int start = history.size() - MAX_HISTORY_MESSAGES;
        while (start > 0 && history.get(start).role() != Message.Role.USER) {
            start--;
        }
        return List.copyOf(history.subList(start, history.size()));
    }

    /**
     * Bootstrap 文件只允许工作区根目录下的裸文件名。
     *
     * <p>这几个文件名来自 AGENT.md，属于用户可编辑内容；放行 {@code ../} 就等于
     * 让一份 AGENT.md 读到工作区外任意文件。</p>
     */
    private static void requirePlainFileName(String fileName) {
        if (fileName == null || fileName.isBlank()
                || fileName.contains("/") || fileName.contains("\\") || fileName.contains("..")) {
            throw new LytgosException.InvalidAgentDefinition(
                    "bootstrap 只允许工作区根目录下的文件名，当前为: " + fileName);
        }
    }
}
