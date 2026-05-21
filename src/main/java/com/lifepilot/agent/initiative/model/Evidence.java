package com.lifepilot.agent.initiative.model;

import org.springframework.lang.Nullable;

import java.time.Instant;

/**
 * 支撑想法的证据 — 精确指向记忆系统中的实体。
 *
 * <p>当主动对话发起后，Evidence 列表会注入到 session 上下文中，
 * 使 Agent 在后续对话中能够回溯原始信息。</p>
 *
 * @author zsg
 * @since 2026-06-01
 */
public record Evidence(
    /** 来源类型：memory_entity / conversation / calendar / observation */
    String sourceType,
    /** 精确指向：L3 实体 ID / session ID / 日历事件 ID */
    String sourceId,
    /** 所属 MemorySpace（用于检索时的 scope 限定） */
    @Nullable String spaceId,
    /** 人类可读摘要 */
    String excerpt,
    /** 检索提示（Agent 追问时用于 memory.recall 的查询线索） */
    @Nullable String retrievalHint,
    /** 观察时间 */
    Instant observedAt,
    /** 相关度 [0,1] */
    float relevance
) {}
