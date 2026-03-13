package com.lifepilot.workflow.model;

import org.springframework.lang.Nullable;

/**
 * DAG 节点。
 *
 * @param id       步骤 ID
 * @param stepType 步骤类型
 * @param name     步骤名称
 * @param level    拓扑层级
 * @author zsg
 * @since 2026-03-13
 */
public record DagNode(String id, String stepType, @Nullable String name, int level) {}
