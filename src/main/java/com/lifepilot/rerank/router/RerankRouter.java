package com.lifepilot.rerank.router;

import com.lifepilot.knowledge.model.DocumentSearchResult;
import com.lifepilot.knowledge.model.ScoreBreakdown;
import com.lifepilot.knowledge.rerank.RerankCandidate;
import com.lifepilot.llm.LlmUnavailableException;
import com.lifepilot.modelservice.model.ModelServiceEntity;
import com.lifepilot.modelservice.model.ModelServiceKind;
import com.lifepilot.modelservice.model.RerankExecutionMode;
import com.lifepilot.modelservice.model.RerankSettingsEntity;
import com.lifepilot.modelservice.registry.ModelServiceRegistry;
import com.lifepilot.modelservice.repository.RerankSettingsRepository;
import com.lifepilot.rerank.client.RerankClientFactory;
import com.lifepilot.rerank.client.RerankServiceClient;
import com.lifepilot.rerank.strategy.LlmListwiseRerankStrategy;
import com.lifepilot.rerank.strategy.LlmPointwiseRerankStrategy;
import org.springframework.lang.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 精排路由器。
 *
 * <p>统一调度原生精排与 LLM 精排策略。
 *
 * @author zsg
 * @since 2026-03-24
 */
public class RerankRouter {

    private final ModelServiceRegistry registry;
    private final RerankSettingsRepository settingsRepository;
    private final RerankClientFactory rerankClientFactory;
    private final LlmPointwiseRerankStrategy pointwiseStrategy;
    private final LlmListwiseRerankStrategy listwiseStrategy;

    public RerankRouter(ModelServiceRegistry registry,
                        RerankSettingsRepository settingsRepository,
                        RerankClientFactory rerankClientFactory,
                        LlmPointwiseRerankStrategy pointwiseStrategy,
                        LlmListwiseRerankStrategy listwiseStrategy) {
        this.registry = registry;
        this.settingsRepository = settingsRepository;
        this.rerankClientFactory = rerankClientFactory;
        this.pointwiseStrategy = pointwiseStrategy;
        this.listwiseStrategy = listwiseStrategy;
    }

    /**
     * 知识精排是否启用。
     *
     * @return true 表示启用
     */
    public boolean isKnowledgeRerankEnabled() {
        return settings().enabled() && settings().mode() != RerankExecutionMode.DISABLED;
    }

    /**
     * 记忆精排是否启用。
     *
     * @return true 表示启用
     */
    public boolean isMemoryRerankEnabled() {
        RerankSettingsEntity settings = settings();
        return settings.enabled() && settings.memoryEnabled() && settings.mode() != RerankExecutionMode.DISABLED;
    }

    /**
     * 解析知识精排 TopK。
     */
    public int resolveKnowledgeTopK(int requestedTopK) {
        int configured = settings().knowledgeTopK();
        return requestedTopK > 0 ? Math.min(requestedTopK, configured) : configured;
    }

    /**
     * 获取记忆精排 TopK。
     */
    public int memoryTopK() {
        return settings().memoryTopK();
    }

    /**
     * 对文档执行知识精排。
     */
    public List<DocumentSearchResult> rerankDocuments(String query,
                                                      List<DocumentSearchResult> candidates,
                                                      int requestedTopK,
                                                      @Nullable String modelName) {
        if (!isKnowledgeRerankEnabled() || candidates.isEmpty()) {
            return candidates.stream().limit(requestedTopK).toList();
        }
        int effectiveTopK = resolveKnowledgeTopK(requestedTopK);
        return switch (settings().mode()) {
            case NATIVE -> rerankDocumentsNative(query, candidates, effectiveTopK, modelName);
            case LLM_POINTWISE -> pointwiseStrategy.rerankDocuments(
                    query, candidates, effectiveTopK, modelName, settings().llmServiceId());
            case LLM_LISTWISE -> listwiseStrategy.rerankDocuments(
                    query, candidates, effectiveTopK, modelName, settings().llmServiceId());
            case DISABLED -> candidates.stream().limit(effectiveTopK).toList();
        };
    }

    /**
     * 对通用候选执行记忆精排。
     */
    public List<RerankCandidate> rerankMemoryCandidates(String query,
                                                        List<RerankCandidate> candidates) {
        if (!isMemoryRerankEnabled() || candidates.isEmpty()) {
            return candidates.stream().limit(memoryTopK()).toList();
        }
        return switch (settings().mode()) {
            case NATIVE -> rerankGenericNative(query, candidates, memoryTopK(), null);
            case LLM_POINTWISE -> pointwiseStrategy.rerankCandidates(
                    query, candidates, memoryTopK(), null, settings().llmServiceId());
            case LLM_LISTWISE -> listwiseStrategy.rerankCandidates(
                    query, candidates, memoryTopK(), null, settings().llmServiceId());
            case DISABLED -> candidates.stream().limit(memoryTopK()).toList();
        };
    }

    private List<DocumentSearchResult> rerankDocumentsNative(String query,
                                                             List<DocumentSearchResult> candidates,
                                                             int topK,
                                                             @Nullable String modelName) {
        ModelServiceEntity service = resolveNativeService(modelName);
        List<String> documents = candidates.stream().map(DocumentSearchResult::content).toList();
        List<RerankServiceClient.RerankScore> scores = rerankClientFactory.getOrCreate(service)
                .rerank(query, documents, topK, null);
        if (scores.isEmpty()) {
            return candidates.stream().limit(topK).toList();
        }
        List<DocumentSearchResult> reranked = new ArrayList<>();
        for (RerankServiceClient.RerankScore score : scores) {
            if (score.index() < 0 || score.index() >= candidates.size()) {
                continue;
            }
            DocumentSearchResult original = candidates.get(score.index());
            ScoreBreakdown breakdown = original.scoreBreakdown()
                    .map(existing -> new ScoreBreakdown(
                            existing.vectorScore(), existing.ftsScore(), existing.rrfFusedScore(), Optional.of(score.score())))
                    .orElse(new ScoreBreakdown(0.0, 0.0, 0.0, Optional.of(score.score())));
            reranked.add(new DocumentSearchResult(
                    original.chunkId(), original.documentId(), original.knowledgeBaseId(),
                    original.content(), original.contextPrefix(), original.headingHierarchy(),
                    score.score(), "reranked", original.metadata(),
                    Optional.of(breakdown), original.expandedContent()));
        }
        return reranked.isEmpty() ? candidates.stream().limit(topK).toList() : reranked;
    }

    private List<RerankCandidate> rerankGenericNative(String query,
                                                      List<RerankCandidate> candidates,
                                                      int topK,
                                                      @Nullable String modelName) {
        ModelServiceEntity service = resolveNativeService(modelName);
        List<RerankServiceClient.RerankScore> scores = rerankClientFactory.getOrCreate(service)
                .rerank(query, candidates.stream().map(RerankCandidate::content).toList(), topK, null);
        if (scores.isEmpty()) {
            return candidates.stream().limit(topK).toList();
        }
        Map<Integer, RerankCandidate> indexed = new HashMap<>();
        for (int i = 0; i < candidates.size(); i++) {
            indexed.put(i, candidates.get(i));
        }
        return scores.stream()
                .filter(score -> indexed.containsKey(score.index()))
                .map(score -> {
                    RerankCandidate original = indexed.get(score.index());
                    return new RerankCandidate(original.id(), original.content(), score.score());
                })
                .toList();
    }

    private ModelServiceEntity resolveNativeService(@Nullable String modelName) {
        List<ModelServiceEntity> byModel = registry.findEnabledByModelName(ModelServiceKind.RERANK, modelName);
        if (!byModel.isEmpty()) {
            return byModel.getFirst();
        }
        return registry.findEnabledById(ModelServiceKind.RERANK, settings().nativeServiceId())
                .orElseThrow(() -> new LlmUnavailableException(
                        "无可用原生精排服务", "rerank", List.of(settings().nativeServiceId())));
    }

    private RerankSettingsEntity settings() {
        return settingsRepository.findDefault()
                .orElse(new RerankSettingsEntity(
                        RerankSettingsRepository.DEFAULT_ID,
                        false,
                        RerankExecutionMode.DISABLED,
                        null,
                        null,
                        5,
                        false,
                        10));
    }
}
