package com.lifepilot.agent.initiative.model;

import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.List;

/**
 * 想法 — 主动引擎的核心抽象。代表系统"想对用户说的一件事"。
 *
 * <p>Thought 从产生到表达经历完整的生命周期（BREWING → READY → EXPRESSED → ABSORBED/DISMISSED）。
 * 同一 intentKey 在活跃状态下只能有一个想法，防止重复表达。</p>
 *
 * @author zsg
 * @since 2026-06-01
 */
public record Thought(
    String id,
    /** 意图唯一键（同一意图不重复表达） */
    String intentKey,
    ThoughtKind kind,
    /** 一句话概要（人类可读） */
    String summary,
    /** 支撑证据 */
    List<Evidence> evidence,
    /** 置信度 [0,1] */
    float confidence,
    /** 成熟度 [0,1]（越高越值得表达） */
    float maturity,
    Instant createdAt,
    /** 预计成熟时间（可选） */
    @Nullable Instant matureAt,
    ThoughtState state,
    /** 表达后关联的对话 session ID */
    @Nullable String conversationId,
    /** 上次强化时间（成熟度衰减计时基准）；新建想法等于 createdAt。 */
    @Nullable Instant lastReinforcedAt
) {
    public boolean isReady() {
        return state == ThoughtState.READY && maturity >= 0.6f;
    }

    public boolean isExpired(java.time.Duration maxBrewingTtl, java.time.Duration maxReadyTtl) {
        Instant now = Instant.now();
        if (state == ThoughtState.BREWING) {
            return java.time.Duration.between(createdAt, now).compareTo(maxBrewingTtl) > 0;
        }
        if (state == ThoughtState.READY && matureAt != null) {
            return java.time.Duration.between(matureAt, now).compareTo(maxReadyTtl) > 0;
        }
        return false;
    }

    public Thought withState(ThoughtState newState) {
        return new Thought(id, intentKey, kind, summary, evidence, confidence, maturity,
                createdAt, matureAt, newState, conversationId, lastReinforcedAt);
    }

    public Thought withExpression(String newConversationId) {
        return new Thought(id, intentKey, kind, summary, evidence, confidence, maturity,
                createdAt, matureAt, ThoughtState.EXPRESSED, newConversationId, lastReinforcedAt);
    }

    public Thought withMaturity(float newMaturity) {
        ThoughtState newState = newMaturity >= 0.6f && state == ThoughtState.BREWING
                ? ThoughtState.READY : state;
        return new Thought(id, intentKey, kind, summary, evidence, confidence, newMaturity,
                createdAt, matureAt, newState, conversationId, lastReinforcedAt);
    }

    /**
     * 应用一次演化结果（成熟度 + 状态），不改强化时间。
     */
    public Thought withEvolution(float newMaturity, ThoughtState newState) {
        return new Thought(id, intentKey, kind, summary, evidence, confidence, newMaturity,
                createdAt, matureAt, newState, conversationId, lastReinforcedAt);
    }

    /**
     * 应用一次证据强化：合并证据、更新成熟度/置信度/状态，并刷新强化时间。
     */
    public Thought reinforcedWith(List<Evidence> mergedEvidence, float newMaturity,
                                  float newConfidence, ThoughtState newState, Instant reinforcedAt) {
        return new Thought(id, intentKey, kind, summary, mergedEvidence, newConfidence, newMaturity,
                createdAt, matureAt, newState, conversationId, reinforcedAt);
    }
}
