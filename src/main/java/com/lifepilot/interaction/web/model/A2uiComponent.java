package com.lifepilot.interaction.web.model;

import java.util.List;
import java.util.Map;

import org.springframework.lang.Nullable;

/**
 * A2UI 组件节点，邻接表中的单个节点。
 *
 * @param id         组件唯一标识
 * @param type       组件类型（对应 componentCatalog 中的注册名）
 * @param properties 组件属性（键值对）
 * @param children   子节点 ID 列表
 * @param signal     用户交互信号（可为 null）
 * @author zsg
 * @since 2026-02-27
 */
public record A2uiComponent(
        String id,
        String type,
        Map<String, Object> properties,
        List<String> children,
        @Nullable A2uiSignal signal
) {
    public A2uiComponent {
        properties = properties != null ? Map.copyOf(properties) : Map.of();
        children = children != null ? List.copyOf(children) : List.of();
    }
}
