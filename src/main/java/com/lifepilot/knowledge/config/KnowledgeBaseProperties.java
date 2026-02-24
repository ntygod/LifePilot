package com.lifepilot.knowledge.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 知识库配置属性 — 映射 {@code lifepilot.knowledge} 前缀。
 *
 * @author zsg
 * @since 2026-02-25
 */
@ConfigurationProperties(prefix = "lifepilot.knowledge")
public record KnowledgeBaseProperties(
        String dataDir,
        long maxFileSize,
        boolean enabled,
        Chunking chunking
) {

    /**
     * 提供默认值的构造器。
     */
    public KnowledgeBaseProperties {
        if (dataDir == null) dataDir = System.getProperty("user.home") + "/.lifepilot/data";
        if (maxFileSize <= 0) maxFileSize = 104857600L; // 100MB
        if (chunking == null) chunking = new Chunking(null, null);
    }

    /**
     * 分块配置。
     */
    public record Chunking(
            String defaultStrategy,
            FixedSize fixedSize
    ) {
        public Chunking {
            if (defaultStrategy == null) defaultStrategy = "smart";
            if (fixedSize == null) fixedSize = new FixedSize(0, 0, 0, 0, true);
        }

        /**
         * 固定大小分块配置。
         */
        public record FixedSize(
                int chunkSize,
                int minChunkSize,
                int overlapSize,
                int maxChunkTokens,
                boolean respectSentences
        ) {
            public FixedSize {
                if (chunkSize <= 0) chunkSize = 1024;
                if (minChunkSize <= 0) minChunkSize = 100;
                if (overlapSize <= 0) overlapSize = 128;
                if (maxChunkTokens <= 0) maxChunkTokens = 512;
            }
        }
    }
}
