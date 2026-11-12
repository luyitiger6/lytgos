package io.lytgos.core.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lytgos.core.LytgosException;
import io.lytgos.core.chat.ChatModel;
import io.lytgos.core.chat.ChatRequest;
import io.lytgos.core.chat.ChatResult;
import io.lytgos.core.chat.Message;
import io.lytgos.core.chat.ToolCall;
import io.lytgos.core.chat.ToolSpec;
import io.lytgos.core.session.Session;
import io.lytgos.core.tool.Tool;
import io.lytgos.core.tool.ToolExecutor;
import io.lytgos.core.tool.ToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ReActLoop：工具往返、迭代上限与失败回填")
class ReActLoopTest {

    @TempDir
    Path workspace;

    @Test
    @DisplayName("第一轮返回工具调用、第二轮返回文本时，正好调用模型两次")
    void runsTwoModelCallsWhenFirstRoundHasToolCall() {
        RecordingTool tool = new RecordingTool("http_get");
        ToolRegistry registry = new ToolRegistry(List.of(tool));
        ScriptedModel model = new ScriptedModel(List.of(
                new ChatResult("", List.of(new ToolCall("c1", "http_get",
                        "{\"url\":\"https://restapi.amap.com/v3/weather/weatherInfo\"}"))),
                new ChatResult("北京今天晴，25 度", List.of())));

        Session session = new Session("cli:local:tester");
        String answer = loop(model, registry, 10).run(session, "北京今天天气怎么样");

        assertEquals("北京今天晴，25 度", answer);
        assertEquals(2, model.calls(), "第一轮工具调用、第二轮文本，模型应被调用两次");
        assertEquals(1, tool.invocations.size());
        assertEquals("https://restapi.amap.com/v3/weather/weatherInfo", tool.invocations.get(0).get("url"));

        List<Message> history = session.history();
        assertEquals(4, history.size(), "user → assistant(toolCalls) → tool → assistant(text)");
        assertEquals(Message.Role.USER, history.get(0).role());
        assertEquals(Message.Role.ASSISTANT, history.get(1).role());
        assertTrue(history.get(1).hasToolCalls(), "assistant 消息必须保留模型当时的工具调用");
        assertEquals(Message.Role.TOOL, history.get(2).role());
        assertEquals("c1", history.get(2).toolCallId());
        assertEquals("http_get-result", history.get(2).content());
        assertEquals(Message.Role.ASSISTANT, history.get(3).role());

        Message lastOfSecondRequest = model.requests().get(1).messages().getLast();
        assertEquals(Message.Role.TOOL, lastOfSecondRequest.role(),
                "第二轮请求里必须能看到上一轮的工具结果，否则模型无从继续");
    }

    @Test
    @DisplayName("模型一直要求调用工具时，在 maxIterations 处停止并返回已有文本")
    void stopsAtMaxIterations() {
        RecordingTool tool = new RecordingTool("http_get");
        ToolRegistry registry = new ToolRegistry(List.of(tool));
        ScriptedModel model = new ScriptedModel(List.of(
                new ChatResult("正在查询", List.of(new ToolCall("c1", "http_get", "{}"))),
                new ChatResult("还在查询", List.of(new ToolCall("c2", "http_get", "{}")))));

        Session session = new Session("cli:local:tester");
        String answer = loop(model, registry, 2).run(session, "问点什么");

        assertEquals(2, model.calls());
        assertEquals(2, tool.invocations.size());
        assertEquals("还在查询", answer, "上限内的最后一段非空文本应作为返回值");
        assertEquals(Message.Role.ASSISTANT, session.history().getLast().role());
        assertTrue(session.history().getLast().content().contains("还在查询"));
    }

    @Test
    @DisplayName("工具自身失败时转成文本回填，循环继续而不是中断")
    void fillsRecoverableFailureAndContinues() {
        Tool failing = new Tool() {
            @Override
            public ToolSpec spec() {
                return new ToolSpec("http_get", "总会失败", "{\"type\":\"object\"}");
            }

            @Override
            public String execute(Map<String, Object> arguments) {
                throw new IllegalStateException("上游 503");
            }
        };
        ToolRegistry registry = new ToolRegistry(List.of(failing));
        ScriptedModel model = new ScriptedModel(List.of(
                new ChatResult("", List.of(new ToolCall("c1", "http_get", "{}"))),
                new ChatResult("上游暂时不可用，稍后再试", List.of())));

        Session session = new Session("cli:local:tester");
        String answer = loop(model, registry, 10).run(session, "查询天气");

        assertEquals("上游暂时不可用，稍后再试", answer);
        Message toolMessage = session.history().get(2);
        assertEquals(Message.Role.TOOL, toolMessage.role());
        assertTrue(toolMessage.content().contains("上游 503"), "失败原因必须回填给模型，让它自己纠错");
    }

    @Test
    @DisplayName("沙箱拒绝时先补上失败结果再抛，历史不留「有调用无结果」的残局")
    void sandboxViolationPropagatesAfterHistoryIsCompleted() {
        Tool blocked = new Tool() {
            @Override
            public ToolSpec spec() {
                return new ToolSpec("http_get", "被沙箱拦截", "{\"type\":\"object\"}");
            }

            @Override
            public String execute(Map<String, Object> arguments) {
                throw new LytgosException.SandboxViolation("域名 example.com 不在白名单内");
            }
        };
        ToolRegistry registry = new ToolRegistry(List.of(blocked));
        ScriptedModel model = new ScriptedModel(List.of(
                new ChatResult("", List.of(new ToolCall("c1", "http_get", "{}")))));

        Session session = new Session("cli:local:tester");
        LytgosException.SandboxViolation thrown = assertThrows(
                LytgosException.SandboxViolation.class,
                () -> loop(model, registry, 10).run(session, "访问 example.com"));

        assertTrue(thrown.getMessage().contains("sandbox"));
        Message last = session.history().getLast();
        assertEquals(Message.Role.TOOL, last.role(), "抛异常前必须补上对应的 tool 消息");
        assertEquals("c1", last.toolCallId());
        assertTrue(last.content().contains("sandbox"));
    }

    private ReActLoop loop(ChatModel model, ToolRegistry registry, int maxIterations) {
        Profile profile = new Profile(
                "tester", null, "你是一个测试助手。",
                new Profile.ProviderRef("deepseek", "deepseek-chat"),
                List.of(), List.of(), List.of("cli"), List.of(), Map.of());
        return new ReActLoop(
                model,
                PromptBuilder.from(profile, workspace, ""),
                registry,
                new ToolExecutor(registry, new ObjectMapper()),
                maxIterations);
    }

    /** 按脚本返回结果，并记录每次请求的完整入参。 */
    private static final class ScriptedModel implements ChatModel {

        private final List<ChatResult> script;
        private final List<ChatRequest> requests = new ArrayList<>();
        private int index;

        private ScriptedModel(List<ChatResult> script) {
            this.script = script;
        }

        @Override
        public ChatResult call(ChatRequest request) {
            requests.add(request);
            if (index >= script.size()) {
                throw new AssertionError(
                        "模型被调用了 " + (index + 1) + " 次，超出脚本预期 " + script.size() + " 次");
            }
            return script.get(index++);
        }

        private int calls() {
            return index;
        }

        private List<ChatRequest> requests() {
            return requests;
        }
    }

    /** 记录收到的参数，返回可识别的固定结果。 */
    private static final class RecordingTool implements Tool {

        private final String name;
        private final List<Map<String, Object>> invocations = new ArrayList<>();

        private RecordingTool(String name) {
            this.name = name;
        }

        @Override
        public ToolSpec spec() {
            return new ToolSpec(name, "测试工具", "{\"type\":\"object\"}");
        }

        @Override
        public String execute(Map<String, Object> arguments) {
            invocations.add(arguments);
            return name + "-result";
        }
    }
}
