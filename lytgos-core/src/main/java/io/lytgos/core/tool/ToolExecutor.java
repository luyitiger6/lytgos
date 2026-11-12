package io.lytgos.core.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.lytgos.core.LytgosException;
import io.lytgos.core.chat.ToolCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 工具执行的唯一合法入口。
 *
 * <p>{@code AGENTS.md} 4.1 的另一半：模型只能「提出」工具调用，真正执行必须经过这里。
 * 配额、白名单、沙箱、审计全部挂在这个方法上，所以任何绕过它的直接调用 {@link Tool#execute}
 * 都视为越权——04 号闸门会扫。</p>
 *
 * <p>失败处理分两类，这个区分是刻意的：</p>
 * <ul>
 *   <li><b>沙箱违规</b>直接抛给上层，中断本次对话。这是安全事件，不是「模型试错」，
 *       不能让模型看到错误后再换个域名继续试。</li>
 *   <li><b>工具自身失败</b>（超时、上游 5xx、参数不合法）转成文本回填历史，
 *       让模型自己决定是改参数重试还是换个做法——这正是 ReAct 的纠错能力所在。</li>
 * </ul>
 */
public final class ToolExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(ToolExecutor.class);

    private static final TypeReference<Map<String, Object>> ARGUMENT_TYPE = new TypeReference<>() {
    };

    private final ToolRegistry registry;
    private final ObjectMapper mapper;

    public ToolExecutor(ToolRegistry registry, ObjectMapper mapper) {
        this.registry = registry;
        this.mapper = mapper;
    }

    /**
     * 执行一次工具调用。
     *
     * @return 回填给模型的结果文本，成功与「可恢复的失败」都走返回值
     * @throws LytgosException.SandboxViolation 沙箱拒绝时抛出，不返回
     */
    public String execute(ToolCall call) {
        Tool tool = registry.find(call.name())
                .orElseThrow(() -> new LytgosException.InvalidConfiguration(
                        "模型请求了未注册的工具: " + call.name() + "；已注册: " + registry.names()));

        Map<String, Object> arguments;
        try {
            arguments = parseArguments(call);
        } catch (JsonProcessingException e) {
            LOG.warn("工具参数不是合法 JSON，回填给模型: {}", call.arguments());
            return "工具参数不是合法 JSON，请修正后重试。收到的原文: " + call.arguments();
        }
        LOG.info("tool_call: {} args={}", call.name(), arguments);

        try {
            return tool.execute(arguments);
        } catch (LytgosException.SandboxViolation e) {
            throw e;
        } catch (RuntimeException e) {
            LOG.warn("工具 {} 执行失败，回填给模型: {}", call.name(), e.getMessage());
            return "工具执行失败: " + e.getMessage();
        }
    }

    private Map<String, Object> parseArguments(ToolCall call) throws JsonProcessingException {
        String raw = call.arguments();
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        return mapper.readValue(raw, ARGUMENT_TYPE);
    }
}
