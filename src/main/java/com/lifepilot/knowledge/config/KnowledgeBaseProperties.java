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
        Reranker reranker,
        QueryEnhancer queryEnhancer,
        Tokenizer tokenizer
) {

    public KnowledgeBaseProperties {
        if (dataDir == null) dataDir = System.getProperty("user.home") + "/.zhiwei/data";
        if (maxFileSize <= 0) maxFileSize = 104857600L;
        if (chunking == null) chunking = new Chunking(null, null, null, null, null, null, null, null, null);
        if (vectorIndexer == null) vectorIndexer = new VectorIndexer(0, 0, 0);
        if (retrieval == null) retrieval = new Retrieval(0, 0.0, 0.0, 0, 0.0, 0.0, 0, 0.0, true, 0.0, false, 0.0, 0);
        if (contextEnricher == null) contextEnricher = new ContextEnricher(true, 0, false, 0, 0);
        if (extraction == null) extraction = new Extraction(true, 0);
        if (reranker == null) reranker = new Reranker(false, null, null, 0, null, 0, null, null, null, 0);
        if (queryEnhancer == null) queryEnhancer = new QueryEnhancer(null, 0, 0);
        if (tokenizer == null) tokenizer = new Tokenizer(null);
    }

    /**
     * 分块配置。
     */
    public record Chunking(
            String defaultStrategy,
            FixedSize fixedSize,
            Recursive recursive,
            Heading heading,
            SmartChunker smartChunker,
            SemanticChunking semanticChunking,
            ParentChild parentChild,
            StructureAnalysis structureAnalysis,
            RegionRouting regionRouting
    ) {
        public Chunking {
            if (defaultStrategy == null) defaultStrategy = "smart";
            if (fixedSize == null) fixedSize = new FixedSize(0, 0, 0, 0, true);
            if (recursive == null) recursive = new Recursive(null, 0, 0, 0);
            if (heading == null) heading = new Heading(0, 0);
            if (smartChunker == null) smartChunker = new SmartChunker(0.0, 0, 0.0, 0);
            if (semanticChunking == null) semanticChunking = new SemanticChunking(false, 0.0, 0, 0, 0);
            if (parentChild == null) parentChild = new ParentChild(true, 0, 0, 0);
            if (structureAnalysis == null) structureAnalysis = new StructureAnalysis(0, 0, 0);
            if (regionRouting == null) regionRouting = new RegionRouting(0, 0, 0, 0);
        }

        /** 固定大小分块配置。 */
        public record FixedSize(
                int chunkSize, int minChunkSize, int overlapSize, int maxChunkTokens, boolean respectSentences
        ) {
            public FixedSize {
                if (chunkSize <= 0) chunkSize = 1024;
                if (minChunkSize <= 0) minChunkSize = 100;
                if (overlapSize <= 0) overlapSize = 128;
                if (maxChunkTokens <= 0) maxChunkTokens = 512;
            }
        }

        /** 递归分块配置。 */
        public record Recursive(
                java.util.List<String> separators, int maxChunkSize, int minChunkSize, int overlapSize
        ) {
            public Recursive {
                if (separators == null || separators.isEmpty()) {
                    separators = java.util.List.of("\n\n", "\n", "。", "！", "？", "；", "，", ".", "!", "?", " ");
                }
                if (maxChunkSize <= 0) maxChunkSize = 1024;
                if (minChunkSize <= 0) minChunkSize = 100;
                if (overlapSize <= 0) overlapSize = 128;
            }
        }

        /** 标题分块配置。 */
        public record Heading(int maxHeadingLevel, int maxChunkSize) {
            public Heading {
                if (maxHeadingLevel <= 0) maxHeadingLevel = 3;
                if (maxChunkSize <= 0) maxChunkSize = 2048;
            }
        }

        /** 智能分块策略选择配置。 */
        public record SmartChunker(
                double headingDensityThreshold,
                int shortDocumentThreshold,
                double codeBlockDensityThreshold,
                int semanticChunkingThreshold
        ) {
            public SmartChunker {
                if (headingDensityThreshold <= 0.0) headingDensityThreshold = 0.1;
                if (shortDocumentThreshold <= 0) shortDocumentThreshold = 2000;
                if (codeBlockDensityThreshold <= 0.0) codeBlockDensityThreshold = 0.3;
                if (semanticChunkingThreshold <= 0) semanticChunkingThreshold = 5000;
            }
        }

        /** 语义分块配置。 */
        public record SemanticChunking(
                boolean enabled,
                double breakpointThreshold,
                int bufferSize,
                int minChunkSize,
                int maxChunkSize
        ) {
            public SemanticChunking {
                if (breakpointThreshold <= 0.0) breakpointThreshold = 0.5;
                if (bufferSize <= 0) bufferSize = 1;
                if (minChunkSize <= 0) minChunkSize = 100;
                if (maxChunkSize <= 0) maxChunkSize = 1024;
            }
        }

        /** Parent-Child 分块配置。 */
        public record ParentChild(boolean enabled, int parentMaxTokens, int childMaxTokens, int childOverlap) {
            public ParentChild {
                if (parentMaxTokens <= 0) parentMaxTokens = 1024;
                if (childMaxTokens <= 0) childMaxTokens = 256;
                if (childOverlap < 0) childOverlap = 64;
            }
        }

        /** 文档结构分析配置。 */
        public record StructureAnalysis(
                int maxHeadingLength,
                int minCodeIndent,
                int minTableColumns
        ) {
            public StructureAnalysis {
                if (maxHeadingLength <= 0) maxHeadingLength = 80;
                if (minCodeIndent <= 0) minCodeIndent = 4;
                if (minTableColumns <= 0) minTableColumns = 2;
            }
        }

        /** 区域分块路由配置。 */
        public record RegionRouting(
                int maxIntactCodeSize,
                int maxIntactTableSize,
                int maxIntactListSize,
                int paragraphMinForRecursive
        ) {
            public RegionRouting {
                if (maxIntactCodeSize <= 0) maxIntactCodeSize = 4096;
                if (maxIntactTableSize <= 0) maxIntactTableSize = 8192;
                if (maxIntactListSize <= 0) maxIntactListSize = 4096;
                if (paragraphMinForRecursive <= 0) paragraphMinForRecursive = 200;
            }
        }
    }

    /** 向量索引配置。 */
    public record VectorIndexer(int batchSize, int maxRetries, int embeddingDimension) {
        public VectorIndexer {
            if (batchSize <= 0) batchSize = 32;
            if (maxRetries <= 0) maxRetries = 2;
            if (embeddingDimension <= 0) embeddingDimension = 1024;
        }
    }

    /** 检索配置。 */
    public record Retrieval(
            int defaultTopK,
            double vectorWeight,
            double ftsWeight,
            int rrfK,
            double lowConfidenceThreshold,
            double minRelevanceScore,
            int contextWindowSize,
            double graphWeight,
            boolean graphEnabled,
            double deduplicationThreshold,
            boolean correctionEnabled,
            double correctionHighThreshold,
            int correctionTimeoutMs
    ) {
        public Retrieval {
            if (defaultTopK <= 0) defaultTopK = 10;
            if (vectorWeight <= 0.0) vectorWeight = 0.6;
            if (ftsWeight <= 0.0) ftsWeight = 0.4;
            if (rrfK <= 0) rrfK = 60;
            if (lowConfidenceThreshold <= 0.0) lowConfidenceThreshold = 0.5;
            if (minRelevanceScore < 0.0) minRelevanceScore = 0.0;
            if (contextWindowSize < 0) contextWindowSize = 0;
            if (graphWeight <= 0.0) graphWeight = 0.2;
            if (deduplicationThreshold <= 0.0) deduplicationThreshold = 0.85;
            if (correctionHighThreshold <= 0.0) correctionHighThreshold = 0.7;
            if (correctionTimeoutMs <= 0) correctionTimeoutMs = 3000;
        }
    }

    /** 上下文增强配置。 */
    public record ContextEnricher(
            boolean enabled,
            int maxPrefixTokens,
            boolean batchEnabled,
            int batchSize,
            int maxPromptTokens
    ) {
        public ContextEnricher {
            if (maxPrefixTokens <= 0) maxPrefixTokens = 100;
            if (batchSize <= 0) batchSize = 5;
            if (maxPromptTokens <= 0) maxPromptTokens = 4000;
        }
    }

    /** 知识提取配置。 */
    public record Extraction(boolean enabled, int batchSize) {
        public Extraction {
            if (batchSize <= 0) batchSize = 5;
        }
    }

    /** Reranker 配置。 */
    public record Reranker(
            boolean enabled,
            String type,
            String model,
            int topK,
            String llmMode,
            int listwiseMaxCandidates,
            String apiProvider,
            String apiKey,
            String apiEndpoint,
            int apiTimeoutMs
    ) {
        public Reranker {
            if (type == null) type = "llm";
            if (model == null) model = "";
            if (topK <= 0) topK = 5;
            if (llmMode == null) llmMode = "pointwise";
            if (listwiseMaxCandidates <= 0) listwiseMaxCandidates = 20;
            if (apiProvider == null) apiProvider = "jina";
            if (apiKey == null) apiKey = "";
            if (apiEndpoint == null) apiEndpoint = "";
            if (apiTimeoutMs <= 0) apiTimeoutMs = 5000;
        }
    }

    /** 查询增强配置。 */
    public record QueryEnhancer(
            String mode,
            int timeoutMs,
            int maxRewrites
    ) {
        public QueryEnhancer {
            if (mode == null) mode = "none";
            if (timeoutMs <= 0) timeoutMs = 3000;
            if (maxRewrites <= 0) maxRewrites = 3;
        }
    }

    /** Token 计数器配置。 */
    public record Tokenizer(String encoding) {
        public Tokenizer {
            if (encoding == null || encoding.isBlank()) encoding = "cl100k_base";
        }
    }
}
