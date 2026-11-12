package io.lytgos.core.tool;

import io.lytgos.core.LytgosException;
import io.lytgos.core.chat.ToolSpec;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 工具注册表。构造后不可变，重名在启动期直接失败。
 *
 * <p>重名不是合并、不是后者覆盖前者——那会让「Agent 到底调到了哪个实现」变成运行期谜题。
 * 重名只有一个处理方式：拒绝启动。</p>
 *
 * <p>保持注册顺序：工具 schema 每次都以同样的顺序交给模型，
 * 否则同样的输入可能得到不同的工具排序，排查问题时无从对比。</p>
 */
public final class ToolRegistry {

    private final Map<String, Tool> tools;

    public ToolRegistry(Collection<Tool> tools) {
        Map<String, Tool> ordered = new LinkedHashMap<>();
        for (Tool tool : tools) {
            ToolSpec spec = tool.spec();
            if (spec == null || spec.name() == null || spec.name().isBlank()) {
                throw new LytgosException.InvalidConfiguration("工具缺少 name: " + tool.getClass().getName());
            }
            if (ordered.putIfAbsent(spec.name(), tool) != null) {
                throw new LytgosException.InvalidConfiguration(
                        "工具重名: " + spec.name()
                                + "（已注册来源 " + ordered.get(spec.name()).source()
                                + "，本次来源 " + tool.source() + "）");
            }
        }
        this.tools = Collections.unmodifiableMap(ordered);
    }

    public Optional<Tool> find(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(tools.get(name));
    }

    /** 交给模型的工具声明，顺序与注册顺序一致。 */
    public List<ToolSpec> specs() {
        return tools.values().stream().map(Tool::spec).toList();
    }

    /** 已注册的工具本体，顺序与注册顺序一致。供需要按白名单再筛一遍的装配层使用。 */
    public List<Tool> all() {
        return List.copyOf(tools.values());
    }

    public Set<String> names() {
        return tools.keySet();
    }

    public int size() {
        return tools.size();
    }
}
