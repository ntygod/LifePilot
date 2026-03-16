package com.lifepilot.scheduler.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.notification.Urgency;

import java.util.HashMap;
import java.util.Map;

/**
 * {@link TaskAction} 与 JSON 之间的序列化/反序列化工具类。
 *
 * <p>使用 {@code @type} 字段区分子类型，JSON 格式示例：
 * <pre>{@code
 * {"@type": "SendNotification", "content": "该吃药了", "urgency": "HIGH"}
 * {"@type": "InvokeAgent", "message": "帮我总结今天的待办完成情况"}
 * {"@type": "ExecuteTool", "toolId": "builtin.todo.list", "params": {}}
 * }</pre>
 *
 * @author zsg
 * @since 2026-03-16
 */
public final class TaskActionCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TYPE_FIELD = "@type";

    private TaskActionCodec() {
        // 工具类禁止实例化
    }

    /**
     * 将 {@link TaskAction} 序列化为 JSON 字符串。
     *
     * @param action 任务动作
     * @return JSON 字符串
     */
    public static String toJson(TaskAction action) {
        try {
            Map<String, Object> map = new HashMap<>();
            switch (action) {
                case TaskAction.SendNotification sn -> {
                    map.put(TYPE_FIELD, "SendNotification");
                    map.put("content", sn.content());
                    map.put("urgency", sn.urgency().name());
                }
                case TaskAction.InvokeAgent ia -> {
                    map.put(TYPE_FIELD, "InvokeAgent");
                    map.put("message", ia.message());
                }
                case TaskAction.ExecuteTool et -> {
                    map.put(TYPE_FIELD, "ExecuteTool");
                    map.put("toolId", et.toolId());
                    map.put("params", et.params());
                }
            }
            return MAPPER.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("TaskAction 序列化失败", e);
        }
    }

    /**
     * 将 JSON 字符串反序列化为 {@link TaskAction}。
     *
     * @param json JSON 字符串
     * @return 对应的 TaskAction 子类型实例
     * @throws IllegalArgumentException 当 JSON 格式无效或 {@code @type} 未知时
     */
    @SuppressWarnings("unchecked")
    public static TaskAction fromJson(String json) {
        try {
            Map<String, Object> map = MAPPER.readValue(json,
                    new TypeReference<Map<String, Object>>() {});
            String type = (String) map.get(TYPE_FIELD);
            if (type == null) {
                throw new IllegalArgumentException("TaskAction JSON 缺少 @type 字段");
            }
            return switch (type) {
                case "SendNotification" -> new TaskAction.SendNotification(
                        (String) map.get("content"),
                        Urgency.valueOf((String) map.get("urgency"))
                );
                case "InvokeAgent" -> new TaskAction.InvokeAgent(
                        (String) map.get("message")
                );
                case "ExecuteTool" -> new TaskAction.ExecuteTool(
                        (String) map.get("toolId"),
                        map.get("params") != null
                                ? (Map<String, Object>) map.get("params")
                                : Map.of()
                );
                default -> throw new IllegalArgumentException(
                        "未知的 TaskAction 类型: " + type);
            };
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("TaskAction JSON 解析失败: " + json, e);
        }
    }
}
