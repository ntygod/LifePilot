package com.lifepilot.workflow.model;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 工作流变量上下文，存储输入参数、步骤输出和中间变量。
 *
 * <p>支持嵌套路径访问（点号分隔），如 {@code steps.step1.output.result}。
 * 完整实现（JSON 序列化/反序列化、LoopStep 变量绑定等）将在后续任务中补充。
 *
 * @author zsg
 * @since 2026-02-26
 */
public class WorkflowContext {

    private final Map<String, Object> data;

    public WorkflowContext() {
        this.data = new HashMap<>();
    }

    public WorkflowContext(Map<String, Object> data) {
        this.data = new HashMap<>(data);
    }

    /**
     * 获取嵌套路径的值，如 "steps.step1.output.result"。
     *
     * @param path 点号分隔的路径
     * @return 路径对应的值，不存在时返回 empty
     */
    public Optional<Object> get(String path) {
        if (path == null || path.isBlank()) {
            return Optional.empty();
        }
        String[] segments = path.split("\\.");
        Object current = data;
        for (String segment : segments) {
            if (current instanceof Map<?, ?> map) {
                current = map.get(segment);
                if (current == null) {
                    return Optional.empty();
                }
            } else {
                return Optional.empty();
            }
        }
        return Optional.ofNullable(current);
    }

    /**
     * 设置嵌套路径的值。
     *
     * @param path  点号分隔的路径
     * @param value 要设置的值
     */
    @SuppressWarnings("unchecked")
    public void set(String path, Object value) {
        if (path == null || path.isBlank()) {
            return;
        }
        String[] segments = path.split("\\.");
        Map<String, Object> current = data;
        for (int i = 0; i < segments.length - 1; i++) {
            Object next = current.get(segments[i]);
            if (next instanceof Map<?, ?> map) {
                current = (Map<String, Object>) map;
            } else {
                Map<String, Object> newMap = new HashMap<>();
                current.put(segments[i], newMap);
                current = newMap;
            }
        }
        current.put(segments[segments.length - 1], value);
    }
}
