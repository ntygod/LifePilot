package com.lifepilot.workflow.model;

/**
 * DAG 边（依赖关系）。
 *
 * @param from 源步骤 ID（被依赖方）
 * @param to   目标步骤 ID（依赖方）
 * @author zsg
 * @since 2026-03-13
 */
public record DagEdge(String from, String to) {}
