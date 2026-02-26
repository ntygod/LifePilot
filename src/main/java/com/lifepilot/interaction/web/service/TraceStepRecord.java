package com.lifepilot.interaction.web.service;

import org.springframework.lang.Nullable;

import java.time.Instant;

/**
 * 轨迹步骤查询结果。
 *
 * @param id            步骤 ID
 * @param traceId       所属轨迹 ID
 * @param stepIndex     步骤索引
 * @param phaseBefore   执行前阶段
 * @param phaseAfter    执行后阶段
 * @param actionType    动作类型
 * @param actionJson    动作 JSON
 * @param toolId        工具 ID
 * @param toolInputJson 工具输入 JSON
 * @param toolOutput    工具输出
 * @param success       是否成功
 * @param blocked       是否被护栏拦截
 * @param blockReason   拦截原因
 * @param tokensUsed    Token 消耗
 * @param latencyMs     延迟（毫秒）
 * @param createdAt     创建时间
 * @author zsg
 * @since 2026-02-27
 */
public record TraceStepRecord(
        String id,
        String traceId,
        int stepIndex,
        String phaseBefore,
        String phaseAfter,
        String actionType,
        @Nullable String actionJson,
        @Nullable String toolId,
        @Nullable String toolInputJson,
        @Nullable String toolOutput,
        boolean success,
        boolean blocked,
        @Nullable String blockReason,
        int tokensUsed,
        long latencyMs,
        Instant createdAt
) {
}
