package io.lytgos.memory;

import io.lytgos.core.chat.ToolSpec;
import io.lytgos.core.tool.Tool;
import java.util.Map;

/**
 * 内置 {@code save_memory}：把一条长期信息追加到 {@code memory/MEMORY.md}。
 *
 * <p>是否「值得记住」由模型判断、由用户指令驱动；本工具只负责按追加语义落盘。 超限时不抛异常而是返回拒绝文本：容量问题靠下一次对话解决不了， 但让模型看见原因比中断整轮对话更有用。
 */
public final class SaveMemoryTool implements Tool {

  private final MemoryStore store;

  public SaveMemoryTool(MemoryStore store) {
    this.store = store;
  }

  @Override
  public ToolSpec spec() {
    return new ToolSpec(
        "save_memory",
        "把一条需要跨对话记住的偏好或事实追加到长期记忆 memory/MEMORY.md。"
            + "仅在用户明确要求记住，或信息长期有用时调用；记忆上限 "
            + MemoryStore.MAX_CHARS
            + " 字符。",
        """
        {
          "type": "object",
          "properties": {
            "content": {
              "type": "string",
              "description": "要记住的内容，一句话，例如：我的后端框架是 Spring Boot"
            }
          },
          "required": ["content"]
        }
        """);
  }

  @Override
  public String execute(Map<String, Object> arguments) {
    Object raw = arguments.get("content");
    if (raw == null || String.valueOf(raw).isBlank()) {
      throw new IllegalArgumentException("缺少参数 content");
    }
    try {
      int size = store.append(String.valueOf(raw));
      return "已保存到 memory/MEMORY.md，当前 " + size + " 字符（上限 " + MemoryStore.MAX_CHARS + "）。";
    } catch (MemoryStore.CapacityExceededException e) {
      return "未保存：" + e.getMessage();
    }
  }
}
