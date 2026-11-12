package io.lytgos.core.session;

import io.lytgos.core.chat.Message;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次对话的上下文容器。
 *
 * <p>只保存消息序列，不保存「当前在第几轮」「上次工具调用了什么」这类派生态——
 * 派生态一旦落盘就会与消息序列失去同步，恢复时无法判断哪个才是真的。</p>
 *
 * <p>{@link #history()} 返回只读快照：存储层需要序列化它，但任何调用方都不该能
 * 绕过 {@link #append(Message)} 往历史里塞东西。</p>
 */
public final class Session {

    private final String key;
    private final List<Message> messages = new ArrayList<>();

    public Session(String key) {
        this.key = key;
    }

    /** 会话标识，形如 {@code cli:local:default}。 */
    public String key() {
        return key;
    }

    public List<Message> history() {
        return List.copyOf(messages);
    }

    public void append(Message message) {
        messages.add(message);
    }

    /**
     * 从存储读回历史时整体替换。
     *
     * <p>用替换而不是逐条追加：同一次恢复被执行两遍时，追加会让历史翻倍，
     * 而替换是幂等的。</p>
     */
    public void restore(List<Message> history) {
        messages.clear();
        messages.addAll(history);
    }

    public int size() {
        return messages.size();
    }
}
