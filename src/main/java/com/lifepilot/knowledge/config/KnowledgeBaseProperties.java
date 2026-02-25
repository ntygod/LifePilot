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
        Chunking chunking,
        VectorIndexer vectorIndexer,
        Retrieval retrieval,
        ContextEnricher contextEnricher,
        Extraction extraction,
        Reranker reranker
) {

    /**
     * 提供默认值的构造器。
     */
    public KnowledgeBaseProperties {
        if (dataDir == null) dataDir = System.getProperty("user.home") + "/.lifepilot/data";
        if (maxFileSize <= 0) maxFileSize = 104857600L; // 100MB
        if (chunking == null) chunking = new Chunking(null, null, null, null, null);
        if (vectorIndexer == null) vectorIndexer = new VectorIndexer(0, 0, 0);
        if (retrieval == null) retrieval = new Retrieval(0, 0.0, 0.0, 0);
        if (contextEnricher == null) contextEnricher = new ContextEnricher(true, 0);
        if (extraction == null) extraction = new Extraction(true, 0);
        if (reranker == null) reranker = new Reranker(false, null, null, 0);
    }

    /**
     * 分块配置。
     */
    public record Chunking(
            String defaultStrategy,
            FixedSize fixedSize,
            Recursive recursive,
            Heading heading,
            SmartChunker smartChunker
    ) {
        public Chunking {
            if (defaultStrategy == null) defaultStrategy = "smart";
            if (fixedSize == null) fixedSize = new FixedSize(0, 0, 0, 0, true);
            if (recursive == null) recursive = new Recursive(null, 0, 0, 0);
            if (heading == null) heading = new Heading(0, 0);
            if (smartChunker == null) smartChunker = new SmartChunker(0.0, 0);
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

        /**
         * 递归分块配置。
         */
        public record Recursive(
                java.util.List<String> separators,
                int maxChunkSize,
                int minChunkSize,
                int overlapSize
        ) {
            public Recursive {
                if (separators == null || separators.isEmpty()) {
                    separators = java.util.List.of("\n\n", "\n", "。", "！", "？", ".", "!", "?", " ");
                }
                if (maxChunkSize <= 0) maxChunkSize = 1024;
                if (minChunkSize <= 0) minChunkSize = 100;
                if (overlapSize <= 0) overlapSize = 128;
            }
        }

        /**
         * 标题分块配置。
         */
        public record Heading(
                int maxHeadingLevel,
                int maxChunkSize
        ) {
            public Heading {
                if (maxHeadingLevel <= 0) maxHeadingLevel = 3;
                if (maxChunkSize <= 0) maxChunkSize = 2048;
            }
        }

        /**
         * 智能分块策略选择配置。
         */
        public record SmartChunker(
                double headingDensityThreshold,
                int shortDocumentThreshold
        ) {
            public SmartChunker {
                if (headingDensityThreshold <= 0.0) headingDensityThreshold = 0.1;
                if (shortDocumentThreshold <= 0) shortDocumentThreshold = 2000;
            }
        }
    }

    /**
     * 向量索引配置。
     */
    public record VectorIndexer(
            int batchSize,
            int maxRetries,
            int embeddingDimension
    ) {
        public VectorIndexer {
            if (batchSize <= 0) batchSize = 32;
            if (maxRetries <= 0) maxRetries = 2;
            if (embeddingDimension <= 0) embeddingDimension = 1536;
        }
    }

    /**
     * 检索配置。
     */
    public record Retrieval(
            int defaultTopK,
            double vectorWeight,
            double ftsWeight,
            int rrfK
    ) {
        public Retrieval {
            if (defaultTopK <= 0) defaultTopK = 10;
            if (vectorWeight <= 0.0) vectorWeight = 0.6;
            if (ftsWeight <= 0.0) ftsWeight = 0.4;
            if (rrfK <= 0) rrfK = 60;
        }
    }

    /**
     * 上下文增强配置。
     */
    public record ContextEnricher(
            boolean enabled,
            int maxPrefixTokens
    ) {
        public ContextEnricher {
            if (maxPrefixTokens <= 0) maxPrefixTokens = 100;
        }
    }

    /**
     * 知识提取配置。
     */
    public record Extraction(
            boolean enabled,
            int batchSize
    ) {
        public Extraction {
            if (batchSize <= 0) batchSize = 5;
        }
    }

    /**
     * Reranker 配置。
     */
    public record Reranker(
            boolean enabled,
            String type,
            String model,
            int topK
    ) {
        public Reranker {
            if (type == null) type = "llm";
            if (model == null) model = "";
            if (topK <= 0) topK = 5;
        }
    }
}
