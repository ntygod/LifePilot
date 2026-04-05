package com.lifepilot.agent.context;

import com.lifepilot.llm.multimodal.MediaContent;
import org.springframework.ai.chat.messages.Message;
import org.springframework.lang.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 增强版上下文快照 — 携带检索元数据与多模态媒体内容。
 *
 * @param systemPrompt        System Prompt 文本
 * @param historyMessages     历史 transcript 构造出的有序消息流
 * @param userPrompt          User Prompt 文本
 * @param retrievedMemories   格式化后的记忆检索结果
 * @param tokenBudget         Token 预算分配与消耗
 * @param retrievalCount      检索返回的结果总数（截断前）
 * @param topRetrievalScore   最高 fusedScore
 * @param workingMemoryTokens WorkingMemory 注入的 Token 总数
 * @param degraded            是否发生降级
 * @param injectedEntityIds   本次注入的记忆实体 ID 列表
 * @param mediaContents       当前请求关联的多模态媒体内容（可空）
 *
 * @author zsg
 * @since 2026-07-20
 */
public record AssembledContext(
        String systemPrompt,
        List<Message> contextMessages,
        List<Message> historyMessages,
        String userPrompt,
        List<String> retrievedMemories,
        TokenBudget tokenBudget,
        int retrievalCount,
        float topRetrievalScore,
        int workingMemoryTokens,
        boolean degraded,
        List<String> injectedEntityIds,
        @Nullable List<MediaContent> mediaContents
) {
    /** 紧凑构造器 — 防御性拷贝。 */
    public AssembledContext {
        contextMessages = List.copyOf(contextMessages);
        historyMessages = List.copyOf(historyMessages);
        retrievedMemories = List.copyOf(retrievedMemories);
        injectedEntityIds = List.copyOf(injectedEntityIds);
        if (mediaContents != null) {
            mediaContents = List.copyOf(mediaContents);
        }
    }

    /** 返回 tokenBudget.totalConsumed()。 */
    public int totalTokens() {
        return tokenBudget.totalConsumed();
    }

    /** 基于当前上下文，仅替换 systemPrompt，返回新实例。 */
    public AssembledContext withSystemPrompt(String newSystemPrompt) {
        return new AssembledContext(
                newSystemPrompt,
                contextMessages(),
                historyMessages(),
                userPrompt(),
                retrievedMemories(),
                tokenBudget(),
                retrievalCount(),
                topRetrievalScore(),
                workingMemoryTokens(),
                degraded(),
                injectedEntityIds(),
                mediaContents()
        );
    }

    /** 基于当前上下文，注入媒体内容，返回新实例。 */
    public AssembledContext withMediaContents(@Nullable List<MediaContent> newMediaContents) {
        return new AssembledContext(
                systemPrompt(),
                contextMessages(),
                historyMessages(),
                userPrompt(),
                retrievedMemories(),
                tokenBudget(),
                retrievalCount(),
                topRetrievalScore(),
                workingMemoryTokens(),
                degraded(),
                injectedEntityIds(),
                newMediaContents
        );
    }

    /**
     * 基于当前上下文做增量降级，避免全量重组装。
     *
     * <p>根据降级级别裁剪相应部分：SKIP_MEMORY 清空上下文消息和记忆，
     * TRIM_TOOLS 和 COMPRESS_HISTORY 清空上下文消息（减少 token 占用），
     * 标记 degraded=true。</p>
     */
    public AssembledContext degrade(com.lifepilot.agent.model.Budget.DegradationLevel level) {
        return switch (level) {
            case SKIP_MEMORY -> new AssembledContext(
                    systemPrompt, List.of(), historyMessages, userPrompt,
                    List.of(), tokenBudget, 0, 0f, 0, true, injectedEntityIds, mediaContents);
            case TRIM_TOOLS, COMPRESS_HISTORY -> new AssembledContext(
                    systemPrompt, List.of(), historyMessages, userPrompt,
                    retrievedMemories, tokenBudget, retrievalCount, topRetrievalScore,
                    0, true, injectedEntityIds, mediaContents);
            default -> this;
        };
    }

    /** 基于当前上下文，追加一条上下文消息，返回新实例。 */
    public AssembledContext appendContextMessage(Message message) {
        var mergedMessages = new ArrayList<>(contextMessages());
        mergedMessages.add(message);
        return new AssembledContext(
                systemPrompt(),
                mergedMessages,
                historyMessages(),
                userPrompt(),
                retrievedMemories(),
                tokenBudget(),
                retrievalCount(),
                topRetrievalScore(),
                workingMemoryTokens(),
                degraded(),
                injectedEntityIds(),
                mediaContents()
        );
    }
}
