package com.lifepilot.workflow.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工作流变量上下文，存储输入参数、步骤输出和中间变量。
 *
 * <p>支持嵌套路径访问（点号分隔），如 {@code steps.step1.output.result}。
 * 支持 JSON 序列化/反序列化，用于持久化和崩溃恢复。
 *
 * <h3>LoopStep 变量绑定</h3>
 * <p>当 LoopStep 执行时，引擎通过 {@link #set(String, Object)} 将当前迭代元素绑定到
 * {@code loopVar} 路径，将当前索引绑定到 {@code {loopVar}_index} 路径。例如：
 * <pre>
 *   context.set("item", currentElement);       // loopVar 绑定
 *   context.set("item_index", currentIndex);   // loopVar_index 绑定
 * </pre>
 *
 * @author zsg
 * @since 2026-02-26
 */
public class WorkflowContext {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ConcurrentHashMap<String, Object> data;

    public WorkflowContext() {
        this.data = new ConcurrentHashMap<>();
    }

    public WorkflowContext(Map<String, Object> data) {
        this.data = new ConcurrentHashMap<>(data);
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
                Map<String, Object> newMap = new ConcurrentHashMap<>();
                current.put(segments[i], newMap);
                current = newMap;
            }
        }
        current.put(segments[segments.length - 1], value);
    }

    /**
     * 获取内部数据的只读副本，用于序列化和持久化。
     *
     * @return 数据 Map 的不可变副本
     */
    public Map<String, Object> getData() {
        return Map.copyOf(data);
    }

    /**
     * 序列化为 JSON 字符串。
     *
     * @return JSON 字符串
     * @throws IllegalStateException 序列化失败时抛出
     */
    public String toJson() {
        try {
            return OBJECT_MAPPER.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("WorkflowContext 序列化为 JSON 失败", e);
        }
    }

    /**
     * 从 JSON 字符串反序列化为 WorkflowContext。
     *
     * @param json JSON 字符串
     * @return 反序列化后的 WorkflowContext
     * @throws IllegalArgumentException JSON 为 null 或空时抛出
     * @throws IllegalStateException    反序列化失败时抛出
     */
    public static WorkflowContext fromJson(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("JSON 字符串不能为空");
        }
        try {
            Map<String, Object> map = OBJECT_MAPPER.readValue(
                    json, new TypeReference<Map<String, Object>>() {});
            return new WorkflowContext(map);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("从 JSON 反序列化 WorkflowContext 失败", e);
        }
    }
}
