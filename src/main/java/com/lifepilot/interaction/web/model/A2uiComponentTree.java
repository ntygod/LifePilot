package com.lifepilot.interaction.web.model;

import java.util.List;

/**
 * A2UI 组件树，邻接表表示。
 *
 * @param components 组件节点列表（扁平数组，通过 children 引用子节点 ID）
 * @author zsg
 * @since 2026-02-27
 */
public record A2uiComponentTree(
        List<A2uiComponent> components
) {
    public A2uiComponentTree {
        components = components != null ? List.copyOf(components) : List.of();
    }
}
