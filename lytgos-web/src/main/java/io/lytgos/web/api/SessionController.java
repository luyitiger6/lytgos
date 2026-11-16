package io.lytgos.web.api;

import io.lytgos.core.chat.Message;
import io.lytgos.core.session.Session;
import io.lytgos.core.session.SessionStore;
import io.lytgos.core.session.SessionSummary;
import io.lytgos.runtime.SessionKeys;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 会话资源的创建、查询与归档。
 *
 * <p>标识由 {@link SessionKeys} 拼装：同一 {@code (user, agent)} 重复创建命中同一会话，幂等是规格要求， 不是实现副作用。存在性必须走 {@link
 * SessionStore#exists(String)}——{@code get} 对缺失的 key 返回空历史， 无法区分「刚建的空会话」与「不存在」。
 */
@RestController
@RequestMapping("/api/v1/sessions")
public class SessionController {

  private final SessionStore sessions;

  public SessionController(SessionStore sessions) {
    this.sessions = sessions;
  }

  /** 创建（或命中已有）会话。 */
  @PostMapping
  public ApiResponse<CreatedSession> create(
      @RequestBody(required = false) CreateSessionRequest request) {
    String agent = request == null ? null : request.agent();
    String user = userOf(request == null ? null : request.user());
    String key = SessionKeys.of(user, agent);

    boolean created = !sessions.exists(key);
    if (created) {
      sessions.save(new Session(key));
    }
    return ApiResponse.ok(new CreatedSession(key, agent, user, created));
  }

  /** 全部会话的标识、消息数与最后更新时间。 */
  @GetMapping
  public ApiResponse<SessionList> list() {
    List<SessionSummary> summaries = sessions.list();
    return ApiResponse.ok(
        new SessionList(
            summaries.stream()
                .map(
                    summary ->
                        new SessionItem(summary.key(), summary.messageCount(), summary.updatedAt()))
                .toList(),
            summaries.size()));
  }

  /** 单个会话的完整消息历史。 */
  @GetMapping("/{id}")
  public ResponseEntity<ApiResponse<SessionDetail>> get(@PathVariable String id) {
    if (!sessions.exists(id)) {
      return notFound(id);
    }
    List<MessageView> messages = sessions.get(id).history().stream().map(MessageView::of).toList();
    return ResponseEntity.ok(ApiResponse.ok(new SessionDetail(id, messages)));
  }

  /** 归档会话：删除后查询 404、列表不再出现。 */
  @DeleteMapping("/{id}")
  public ResponseEntity<ApiResponse<ArchivedSession>> archive(@PathVariable String id) {
    if (!sessions.exists(id)) {
      return notFound(id);
    }
    sessions.delete(id);
    return ResponseEntity.ok(ApiResponse.ok(new ArchivedSession(id, true)));
  }

  private static String userOf(String user) {
    return user == null || user.isBlank() ? SessionKeys.DEFAULT_USER : user.strip();
  }

  private static <T> ResponseEntity<ApiResponse<T>> notFound(String id) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(ApiResponse.error("LYTGOS_NOT_FOUND", "会话不存在：" + id));
  }

  /** 创建会话的请求体；{@code user} 可省略。 */
  public record CreateSessionRequest(String agent, String user) {}

  /** 创建会话的结果；{@code created=false} 表示命中了已存在的会话。 */
  public record CreatedSession(String sessionId, String agent, String user, boolean created) {}

  /** 会话列表。 */
  public record SessionList(List<SessionItem> items, int total) {

    public SessionList {
      items = List.copyOf(items);
    }
  }

  /** 列表中的一行：不含消息正文。 */
  public record SessionItem(String sessionId, int messageCount, String updatedAt) {}

  /** 单个会话的消息历史。 */
  public record SessionDetail(String sessionId, List<MessageView> messages) {

    public SessionDetail {
      messages = List.copyOf(messages);
    }
  }

  /** 一条消息；{@code toolCalls} / {@code toolCallId} 只在工具往返消息里出现。 */
  public record MessageView(
      String role,
      String content,
      List<ToolCallView> toolCalls,
      String toolCallId,
      String toolName) {

    public MessageView {
      toolCalls = List.copyOf(toolCalls);
    }

    static MessageView of(Message message) {
      List<ToolCallView> calls =
          message.toolCalls().stream()
              .map(call -> new ToolCallView(call.id(), call.name(), call.arguments()))
              .toList();
      return new MessageView(
          message.role().name().toLowerCase(Locale.ROOT),
          message.content(),
          calls,
          message.toolCallId(),
          message.toolName());
    }
  }

  /** 模型发起的一次工具调用。 */
  public record ToolCallView(String id, String name, String arguments) {}

  /** 归档结果。 */
  public record ArchivedSession(String sessionId, boolean archived) {}
}
