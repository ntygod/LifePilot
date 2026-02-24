package com.lifepilot.knowledge.chunking;

/**
 * 分块配置 — 控制文档分块行为的不可变配置。
 *
 * <p>包含最大/最小分块大小、重叠大小、Token 上限等参数，
 * compact constructor 在构造时验证参数合法性。</p>
 *
 * @author zsg
 * @since 2026-02-25
 */
public record ChunkingConfig(
        int maxChunkSize,
        int minChunkSize,
        int overlapSize,
        int maxChunkTokens,
        boolean respectSentences,
        boolean respectParagraphs,
        boolean enableContextPrefix
) {

    /** 默认分块配置：1024 字符分块，100 最小，128 重叠，512 Token 上限。 */
    public static final ChunkingConfig DEFAULT = new ChunkingConfig(1024, 100, 128, 512, true, true, true);

    /** 小分块配置：512 字符分块，50 最小，64 重叠，256 Token 上限。 */
    public static final ChunkingConfig SMALL = new ChunkingConfig(512, 50, 64, 256, true, true, true);

    /** 大分块配置：2048 字符分块，200 最小，256 重叠，1024 Token 上限。 */
    public static final ChunkingConfig LARGE = new ChunkingConfig(2048, 200, 256, 1024, true, true, true);

    /**
     * compact constructor — 验证参数合法性。
     */
    public ChunkingConfig {
        if (maxChunkSize <= 0) {
            throw new IllegalArgumentException("最大分块大小必须为正数: " + maxChunkSize);
        }
        if (minChunkSize < 0 || minChunkSize >= maxChunkSize) {
            throw new IllegalArgumentException("最小分块大小必须在 [0, maxChunkSize) 范围内");
        }
        if (overlapSize < 0 || overlapSize >= maxChunkSize) {
            throw new IllegalArgumentException("重叠大小必须在 [0, maxChunkSize) 范围内");
        }
    }
}
