package io.lytgos.memory;

import io.lytgos.core.chat.ToolSpec;
import io.lytgos.core.tool.Tool;
import java.util.Map;

/**
 * 内置 {@code recall_memory}：读回 {@code memory/MEMORY.md} 当前内容。
 *
 * <p>与启动注入用同一份文件、同一个上限。文件不存在或为空时返回可读文本而不是异常—— 「还没记忆」是正常状态，不是故障。
 */
public final class RecallMemoryTool implements Tool {

  private static final String EMPTY_TEXT = "暂无记忆。可以用 save_memory 保存需要长期记住的偏好或事实。";

  private final MemoryStore store;

  public RecallMemoryTool(MemoryStore store) {
    this.store = store;
  }

  @Override
  public ToolSpec spec() {
    return new ToolSpec(
        "recall_memory",
        "读取长期记忆 memory/MEMORY.md 的当前内容。需要确认用户以前留下的偏好或事实时调用。",
        """
        {
          "type": "object",
          "properties": {},
          "required": []
        }
        """);
  }

  @Override
  public String execute(Map<String, Object> arguments) {
    String content = store.read();
    if (content.isBlank()) {
      return EMPTY_TEXT;
    }
    if (content.length() <= MemoryStore.MAX_CHARS) {
      return content;
    }
    return content.substring(0, MemoryStore.MAX_CHARS)
        + "\n\n[记忆已截断，原始长度 "
        + content.length()
        + " 字符]";
  }
}
