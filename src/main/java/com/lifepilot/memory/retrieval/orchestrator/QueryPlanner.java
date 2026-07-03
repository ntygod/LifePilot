package com.lifepilot.memory.retrieval.orchestrator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 检索规划器 — 根据 {@link RetrievalIntent} 选择参与的 {@link SourceAdapter} 集合。
 *
 * <p>规则（简单映射，后续可扩展为基于 query 语义的动态选择）：
 * <ul>
 *   <li>{@link RetrievalIntent#FACT}：hybrid + knowledge-base</li>
 *   <li>{@link RetrievalIntent#EXPERIENCE}：experience + hybrid</li>
 *   <li>{@link RetrievalIntent#GENERAL}：三路全走</li>
 * </ul>
 * 开启编排层时三路 source 均为必需依赖，缺失应在启动期暴露。</p>
 *
 * @author zsg
 * @since 2026-05-09
 */
public class QueryPlanner {

    private static final Logger log = LoggerFactory.getLogger(QueryPlanner.class);

    private final HybridRetrievalSource hybridSource;
    private final ExperienceRetrievalSource experienceSource;
    private final KnowledgeBaseSource knowledgeBaseSource;

    public QueryPlanner(HybridRetrievalSource hybridSource,
                        ExperienceRetrievalSource experienceSource,
                        KnowledgeBaseSource knowledgeBaseSource) {
        this.hybridSource = Objects.requireNonNull(hybridSource, "hybridSource 不能为空");
        this.experienceSource = Objects.requireNonNull(experienceSource, "experienceSource 不能为空");
        this.knowledgeBaseSource = Objects.requireNonNull(knowledgeBaseSource, "knowledgeBaseSource 不能为空");
    }

    public List<SourceAdapter> plan(String query, @Nullable RetrievalIntent intent) {
        RetrievalIntent effective = intent != null ? intent : RetrievalIntent.GENERAL;
        List<SourceAdapter> out = new ArrayList<>(3);
        switch (effective) {
            case FACT -> {
                out.add(hybridSource);
                out.add(knowledgeBaseSource);
            }
            case EXPERIENCE -> {
                out.add(experienceSource);
                out.add(hybridSource);
            }
            case GENERAL -> {
                out.add(hybridSource);
                out.add(experienceSource);
                out.add(knowledgeBaseSource);
            }
        }
        log.debug("QueryPlanner: intent={}, selected={}", effective,
                out.stream().map(SourceAdapter::name).toList());
        return out;
    }
}
