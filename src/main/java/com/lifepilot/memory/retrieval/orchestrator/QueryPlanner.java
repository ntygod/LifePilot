package com.lifepilot.memory.retrieval.orchestrator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * 检索规划器 — 根据 {@link RetrievalIntent} 选择参与的 {@link SourceAdapter} 集合。
 *
 * <p>规则（简单映射，后续可扩展为基于 query 语义的动态选择）：
 * <ul>
 *   <li>{@link RetrievalIntent#FACT}：hybrid + knowledge-base</li>
 *   <li>{@link RetrievalIntent#EXPERIENCE}：experience + hybrid</li>
 *   <li>{@link RetrievalIntent#GENERAL}：三路全走</li>
 * </ul>
 * 不可用的 adapter（{@code isAvailable()=false}）会被过滤。</p>
 *
 * @author zsg
 * @since 2026-05-09
 */
public class QueryPlanner {

    private static final Logger log = LoggerFactory.getLogger(QueryPlanner.class);

    @Nullable private final HybridRetrievalSource hybridSource;
    @Nullable private final ExperienceRetrievalSource experienceSource;
    @Nullable private final KnowledgeBaseSource knowledgeBaseSource;

    public QueryPlanner(@Nullable HybridRetrievalSource hybridSource,
                        @Nullable ExperienceRetrievalSource experienceSource,
                        @Nullable KnowledgeBaseSource knowledgeBaseSource) {
        this.hybridSource = hybridSource;
        this.experienceSource = experienceSource;
        this.knowledgeBaseSource = knowledgeBaseSource;
    }

    public List<SourceAdapter> plan(String query, @Nullable RetrievalIntent intent) {
        RetrievalIntent effective = intent != null ? intent : RetrievalIntent.GENERAL;
        List<SourceAdapter> out = new ArrayList<>(3);
        switch (effective) {
            case FACT -> {
                addIfAvailable(out, hybridSource);
                addIfAvailable(out, knowledgeBaseSource);
            }
            case EXPERIENCE -> {
                addIfAvailable(out, experienceSource);
                addIfAvailable(out, hybridSource);
            }
            case GENERAL -> {
                addIfAvailable(out, hybridSource);
                addIfAvailable(out, experienceSource);
                addIfAvailable(out, knowledgeBaseSource);
            }
        }
        log.debug("QueryPlanner: intent={}, selected={}", effective,
                out.stream().map(SourceAdapter::name).toList());
        return out;
    }

    private static void addIfAvailable(List<SourceAdapter> out, @Nullable SourceAdapter a) {
        if (a != null && a.isAvailable()) out.add(a);
    }
}
