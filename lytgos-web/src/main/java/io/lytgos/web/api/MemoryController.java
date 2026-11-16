package io.lytgos.web.api;

import io.lytgos.memory.MemoryStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 长期记忆查询。
 *
 * <p>只读：写入必须经 {@code save_memory} 工具或用户手工编辑，HTTP 侧不提供写入口， 避免「任何能访问本机端口的东西都能往记忆里塞内容」。
 */
@RestController
public class MemoryController {

  private final MemoryStore memory;

  public MemoryController(MemoryStore memory) {
    this.memory = memory;
  }

  /** 当前记忆全文、字符数与上限；文件不存在时返回空内容与 0。 */
  @GetMapping("/api/v1/memory")
  public ApiResponse<MemoryView> read() {
    String content = memory.read();
    return ApiResponse.ok(new MemoryView(content, content.length(), MemoryStore.MAX_CHARS));
  }

  /** 记忆的对外视图。 */
  public record MemoryView(String content, int chars, int maxChars) {}
}
