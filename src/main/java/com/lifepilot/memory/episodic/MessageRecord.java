package com.lifepilot.memory.episodic;

import org.springframework.lang.Nullable;
import java.time.Instant;

/**
 * 消息记录 — L2 情景记忆的消息级不可变存储单元。
 *
 * <p>原始内容存储在 content 中，压缩后的内容存储在 compressedContent 中。
 * 检索时根据 compressionLevel 决定返回哪个版本。</p>
 *
 * @author zsg
 * @since 2026-02-24
 */
public record MessageRecord(
        String id,
        String conversationId,
        String role,
        String content,
        @Nullable String compressedContent,
        CompressionLevel compressionLevel,
        boolean isPinned,
        @Nullable String toolCallJson,
        int tokenCount,
        Instant createdAt
) {

    /**
     * 获取当前有效内容 — 优先返回压缩内容，否则返回原文。
     */
    public String effectiveContent() {
        return compressedContent != null ? compressedContent : content;
    }

    /**
     * 获取有效内容的估算 Token 数。
     */
    public int effectiveTokenCount() {
        return switch (compressionLevel) {
            case ORIGINAL  -> tokenCount;
            case SUMMARY   -> Math.round(tokenCount * 0.4f);
            case KEYPOINTS -> Math.round(tokenCount * 0.2f);
            case ARCHIVED  -> 0;
        };
    }

    /**
     * 判断消息是否已被压缩。
     */
    public boolean isCompressed() {
        return compressionLevel != CompressionLevel.ORIGINAL;
    }
}
