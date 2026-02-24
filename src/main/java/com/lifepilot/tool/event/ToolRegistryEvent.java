package com.lifepilot.tool.event;

import com.lifepilot.tool.model.ToolLayer;

import java.util.List;

/**
 * 工具注册中心事件 — sealed interface 穷举所有事件类型。
 *
 * <p>DynamicToolRegistry 在工具注册/注销/冲突时发布事件，
 * 其他组件通过 Spring ApplicationEvent 机制监听。</p>
 *
 * @author zsg
 * @since 2026-02-24
 */
public sealed interface ToolRegistryEvent
        permits ToolRegistryEvent.ToolsRegistered,
                ToolRegistryEvent.ToolsUnregistered,
                ToolRegistryEvent.ToolConflictDetected {

    /**
     * 工具注册事件。
     *
     * @param toolIds 注册的工具 ID 列表
     * @param layer 工具层次
     * @param source 来源标识
     */
    record ToolsRegistered(
            List<String> toolIds,
            ToolLayer layer,
            String source
    ) implements ToolRegistryEvent {
        public ToolsRegistered {
            toolIds = List.copyOf(toolIds);
        }
    }

    /**
     * 工具注销事件。
     *
     * @param toolIds 注销的工具 ID 列表
     * @param source 来源标识
     */
    record ToolsUnregistered(
            List<String> toolIds,
            String source
    ) implements ToolRegistryEvent {
        public ToolsUnregistered {
            toolIds = List.copyOf(toolIds);
        }
    }

    /**
     * 工具冲突检测事件。
     *
     * @param toolId 冲突的工具 ID
     * @param existingLayer 已存在工具的层次
     * @param newLayer 新工具的层次
     * @param resolution 冲突解析结果
     */
    record ToolConflictDetected(
            String toolId,
            ToolLayer existingLayer,
            ToolLayer newLayer,
            String resolution
    ) implements ToolRegistryEvent {}
}
