package com.lifepilot.embedding.router;

import com.lifepilot.embedding.client.EmbeddingClientFactory;
import com.lifepilot.llm.LlmScene;
import com.lifepilot.llm.LlmUnavailableException;
import com.lifepilot.llm.circuit.CircuitBreakerManager;
import com.lifepilot.modelservice.model.EmbeddingSettingsEntity;
import com.lifepilot.modelservice.model.ModelServiceEntity;
import com.lifepilot.modelservice.model.ModelServiceKind;
import com.lifepilot.modelservice.registry.ModelServiceRegistry;
import com.lifepilot.modelservice.repository.EmbeddingSettingsRepository;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 向量路由器。
 *
 * @author zsg
 * @since 2026-03-24
 */
@Component
public class EmbeddingRouter {

    private final ModelServiceRegistry registry;
    private final EmbeddingSettingsRepository settingsRepository;
    private final EmbeddingClientFactory clientFactory;
    private final CircuitBreakerManager circuitBreakerManager;

    public EmbeddingRouter(ModelServiceRegistry registry,
                           EmbeddingSettingsRepository settingsRepository,
                           EmbeddingClientFactory clientFactory,
                           CircuitBreakerManager circuitBreakerManager) {
        this.registry = registry;
        this.settingsRepository = settingsRepository;
        this.clientFactory = clientFactory;
        this.circuitBreakerManager = circuitBreakerManager;
    }

    /**
     * 计算单条文本向量。
     *
     * @param text 文本
     * @param useCase 用途
     * @param serviceId 显式服务 ID
     * @param modelName 显式模型名
     * @return 向量
     */
    public float[] embed(String text,
                         EmbeddingUseCase useCase,
                         @Nullable String serviceId,
                         @Nullable String modelName) {
        List<ModelServiceEntity> candidates = selectCandidates(useCase, serviceId, modelName);
        if (candidates.isEmpty()) {
            throw new LlmUnavailableException("无可用向量服务", LlmScene.EMBEDDING, List.of());
        }
        ArrayList<String> attempted = new ArrayList<>();
        Exception lastException = null;
        for (ModelServiceEntity candidate : candidates) {
            attempted.add(candidate.id());
            if (!circuitBreakerManager.isCallPermitted(candidate.id(), "EMBEDDING")) {
                continue;
            }
            try {
                float[] result = clientFactory.getOrCreate(candidate).embed(text);
                circuitBreakerManager.recordSuccess(candidate.id(), "EMBEDDING");
                return result;
            } catch (Exception e) {
                circuitBreakerManager.recordFailure(candidate.id(), "EMBEDDING");
                lastException = e;
            }
        }
        throw new LlmUnavailableException("所有向量服务调用失败", LlmScene.EMBEDDING, attempted, lastException);
    }

    /**
     * 批量计算文本向量。
     *
     * @param texts 文本列表
     * @param useCase 用途
     * @param serviceId 显式服务 ID
     * @param modelName 显式模型名
     * @return 向量数组
     */
    public float[][] embedBatch(List<String> texts,
                                EmbeddingUseCase useCase,
                                @Nullable String serviceId,
                                @Nullable String modelName) {
        List<ModelServiceEntity> candidates = selectCandidates(useCase, serviceId, modelName);
        if (candidates.isEmpty()) {
            throw new LlmUnavailableException("无可用向量服务", LlmScene.EMBEDDING, List.of());
        }
        ArrayList<String> attempted = new ArrayList<>();
        Exception lastException = null;
        for (ModelServiceEntity candidate : candidates) {
            attempted.add(candidate.id());
            if (!circuitBreakerManager.isCallPermitted(candidate.id(), "EMBEDDING")) {
                continue;
            }
            try {
                float[][] result = clientFactory.getOrCreate(candidate).embedBatch(texts);
                circuitBreakerManager.recordSuccess(candidate.id(), "EMBEDDING");
                return result;
            } catch (Exception e) {
                circuitBreakerManager.recordFailure(candidate.id(), "EMBEDDING");
                lastException = e;
            }
        }
        throw new LlmUnavailableException("所有向量服务调用失败", LlmScene.EMBEDDING, attempted, lastException);
    }

    private List<ModelServiceEntity> selectCandidates(EmbeddingUseCase useCase,
                                                      @Nullable String serviceId,
                                                      @Nullable String modelName) {
        LinkedHashSet<ModelServiceEntity> ordered = new LinkedHashSet<>();
        registry.findEnabledById(ModelServiceKind.EMBEDDING, serviceId).ifPresent(ordered::add);
        registry.findEnabledByModelName(ModelServiceKind.EMBEDDING, modelName).forEach(ordered::add);

        EmbeddingSettingsEntity settings = settingsRepository.findDefault()
                .orElse(new EmbeddingSettingsEntity(EmbeddingSettingsRepository.DEFAULT_ID, null, null, null));

        String boundServiceId = switch (useCase) {
            case KNOWLEDGE_BASE -> settings.knowledgeBaseServiceId();
            case MEMORY -> settings.memoryServiceId();
            case DEFAULT -> settings.defaultServiceId();
        };
        registry.findEnabledById(ModelServiceKind.EMBEDDING, boundServiceId).ifPresent(ordered::add);
        registry.findEnabledById(ModelServiceKind.EMBEDDING, settings.defaultServiceId()).ifPresent(ordered::add);
        registry.findEnabledByKind(ModelServiceKind.EMBEDDING).forEach(ordered::add);
        return List.copyOf(ordered);
    }
}
