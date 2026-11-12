package io.lytgos.core.tool;

import io.lytgos.core.chat.ToolSpec;

import java.util.Map;

/**
 * 工具契约：一个可被模型调用的能力。
 *
 * <p>实现方只负责“怎么干”，不负责“能不能干”——权限、白名单、超时
 * 全部由 {@link ToolExecutor} 在调用前后施加。这样新增一个工具时，
 * 不可能因为忘记加校验而绕过治理。</p>
 */
public interface Tool {

    /** 交给模型的声明，含名称、说明与 JSON Schema。 */
    ToolSpec spec();

    /**
     * 执行工具。
     *
     * @param arguments 已解析的参数，键与 {@link #spec()} 里的 schema 对应
     * @return 工具结果，作为 {@code TOOL} 角色的消息回填给模型
     */
    String execute(Map<String, Object> arguments);
}
