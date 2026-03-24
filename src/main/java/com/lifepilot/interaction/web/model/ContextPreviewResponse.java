package com.lifepilot.interaction.web.model;

import java.util.Map;

/**
 * 上下文组装预览响应。
 *
 * @param segments    各段落内容及 token 数
 * @param tokenBudget Token 预算分配详情
 * @param totalTokens 实际使用的总 token 数
 * @param totalBudget 总预算上限
 * @param degraded    是否发生降级
 * @author zsg
 * @since 2026-03-07
 */
public record ContextPreviewResponse(
        Map<String, SegmentInfo> segments,
        TokenBudgetInfo tokenBudget,
        int totalTokens,
        int totalBudget,
        boolean degraded
) {

    public static final String SEGMENT_SYSTEM_PROMPT = "systemPrompt";
    public static final String SEGMENT_CONTEXT_MESSAGES = "contextMessages";
    public static final String SEGMENT_HISTORY_MESSAGES = "historyMessages";
    public static final String SEGMENT_CURRENT_USER_PROMPT = "currentUserPrompt";

    /**
     * 上下文段落信息。
     *
     * @param content 段落文本内容
     * @param tokens  段落 token 数
     */
    public record SegmentInfo(String content, int tokens) {}

    /**
     * Token 预算分配信息（解耦内部 TokenBudget 领域模型）。
     *
     * @param systemPromptBudget System Prompt 预算
     * @param historyBudget      历史消息预算
     * @param memoryBudget       记忆检索预算
     * @param toolSchemaBudget   工具 Schema 预算
     * @param toolResultBudget   工具结果预算
     * @param reservedBuffer     预留缓冲区
     * @param systemPromptUsed   System Prompt 实际使用量
     * @param historyUsed        历史消息实际使用量
     * @param memoryUsed         记忆检索实际使用量
     * @param toolSchemaUsed     工具 Schema 实际使用量
     * @param toolResultUsed     工具结果实际使用量
     */
    public record TokenBudgetInfo(
            int systemPromptBudget,
            int historyBudget,
            int memoryBudget,
            int toolSchemaBudget,
            int toolResultBudget,
            int reservedBuffer,
            int systemPromptUsed,
            int historyUsed,
            int memoryUsed,
            int toolSchemaUsed,
            int toolResultUsed
    ) {}
}
