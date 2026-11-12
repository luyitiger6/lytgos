package io.lytgos.core.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lytgos.core.LytgosException;
import io.lytgos.core.chat.ToolCall;
import io.lytgos.core.chat.ToolSpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ToolExecutor：失败按归属分两条路")
class ToolExecutorTest {

    @FunctionalInterface
    private interface Body {
        String run(Map<String, Object> arguments);
    }

    private static ToolExecutor executorWith(Tool tool) {
        return new ToolExecutor(new ToolRegistry(List.of(tool)), new ObjectMapper());
    }

    private static Tool probe(Body body) {
        return new Tool() {
            @Override
            public ToolSpec spec() {
                return new ToolSpec("probe", "测试用工具", "{\"type\":\"object\"}");
            }

            @Override
            public String execute(Map<String, Object> arguments) {
                return body.run(arguments);
            }
        };
    }

    private static ToolCall call(String json) {
        return new ToolCall("call_1", "probe", json);
    }

    @Test
    @DisplayName("参数不合法回填给模型，不抛异常")
    void recoversFromInvalidArgument() {
        ToolExecutor executor = executorWith(probe(a -> {
            throw new LytgosException.InvalidToolArgument("URL 格式非法（query 里的 % 要写成 %25）");
        }));

        String output = assertDoesNotThrow(() -> executor.execute(call("{\"url\":\"x\"}")));
        assertTrue(output.contains("工具执行失败"), "应包装成工具结果回填: " + output);
        assertTrue(output.contains("格式非法"), "应把原始原因带给模型: " + output);
    }

    @Test
    @DisplayName("沙箱违规向外抛，不包装成普通失败")
    void propagatesSandboxViolation() {
        ToolExecutor executor = executorWith(probe(a -> {
            throw new LytgosException.SandboxViolation("域名 evil.example.com 不在白名单内");
        }));

        LytgosException.SandboxViolation violation = assertThrows(
                LytgosException.SandboxViolation.class,
                () -> executor.execute(call("{}")));
        assertTrue(violation.getMessage().contains("sandbox"),
                "验收脚本靠 sandbox 字面量判定拦截生效: " + violation.getMessage());
    }

    @Test
    @DisplayName("工具自身运行期异常也回填")
    void recoversFromRuntimeFailure() {
        ToolExecutor executor = executorWith(probe(a -> {
            throw new IllegalStateException("上游返回 502");
        }));

        String output = assertDoesNotThrow(() -> executor.execute(call("{}")));
        assertTrue(output.contains("上游返回 502"), output);
    }

    @Test
    @DisplayName("参数不是合法 JSON 也回填，且不执行工具")
    void recoversFromMalformedJson() {
        int[] invoked = {0};
        ToolExecutor executor = executorWith(probe(a -> {
            invoked[0]++;
            return "不该执行到这里";
        }));

        String output = assertDoesNotThrow(() -> executor.execute(call("{not-json")));
        assertTrue(output.contains("不是合法 JSON"), output);
        assertEquals(0, invoked[0], "参数解析失败时不得触碰工具实现");
    }

    @Test
    @DisplayName("未注册的工具属于配置错误，直接抛")
    void unregisteredToolIsConfigurationError() {
        ToolExecutor executor = executorWith(probe(a -> "ok"));

        assertThrows(LytgosException.InvalidConfiguration.class,
                () -> executor.execute(new ToolCall("call_2", "no_such_tool", "{}")));
    }
}
