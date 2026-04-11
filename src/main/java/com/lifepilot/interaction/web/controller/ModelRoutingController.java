package com.lifepilot.interaction.web.controller;

import com.lifepilot.interaction.web.model.ApiResponse;
import com.lifepilot.interaction.web.model.EmbeddingRoutingSettingsRequest;
import com.lifepilot.interaction.web.model.EmbeddingRoutingSettingsResponse;
import com.lifepilot.interaction.web.model.GenerationRoutingSettingsRequest;
import com.lifepilot.interaction.web.model.GenerationRoutingSettingsResponse;
import com.lifepilot.interaction.web.model.RerankRoutingSettingsRequest;
import com.lifepilot.interaction.web.model.RerankRoutingSettingsResponse;
import com.lifepilot.modelservice.model.EmbeddingSettingsEntity;
import com.lifepilot.modelservice.model.GenerationSettingsEntity;
import com.lifepilot.modelservice.model.RerankExecutionMode;
import com.lifepilot.modelservice.model.RerankSettingsEntity;
import com.lifepilot.modelservice.repository.EmbeddingSettingsRepository;
import com.lifepilot.modelservice.repository.GenerationSettingsRepository;
import com.lifepilot.modelservice.repository.RerankSettingsRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 模型路由设置接口。
 *
 * @author zsg
 * @since 2026-03-24
 */
@RestController
@RequestMapping("/api/model-routing")
@ConditionalOnProperty(name = "lifepilot.gateway.channels.web.enabled", havingValue = "true")
public class ModelRoutingController {

    private static final Logger log = LoggerFactory.getLogger(ModelRoutingController.class);

    private final GenerationSettingsRepository generationSettingsRepository;
    private final EmbeddingSettingsRepository embeddingSettingsRepository;
    private final RerankSettingsRepository rerankSettingsRepository;

    public ModelRoutingController(GenerationSettingsRepository generationSettingsRepository,
                                  EmbeddingSettingsRepository embeddingSettingsRepository,
                                  RerankSettingsRepository rerankSettingsRepository) {
        this.generationSettingsRepository = generationSettingsRepository;
        this.embeddingSettingsRepository = embeddingSettingsRepository;
        this.rerankSettingsRepository = rerankSettingsRepository;
    }

    @GetMapping("/generation")
    public ApiResponse<GenerationRoutingSettingsResponse> getGenerationSettings() {
        var settings = generationSettingsRepository.findDefault()
                .orElse(new GenerationSettingsEntity(GenerationSettingsRepository.DEFAULT_ID, null, Map.of()));
        return ApiResponse.ok(new GenerationRoutingSettingsResponse(
                settings.defaultServiceId(),
                settings.sceneServiceBindings()
        ));
    }

    @PutMapping("/generation")
    public ApiResponse<GenerationRoutingSettingsResponse> updateGenerationSettings(
            @RequestBody GenerationRoutingSettingsRequest request) {
        log.info("更新生成路由设置: defaultServiceId={}", request.defaultServiceId());
        GenerationSettingsEntity current = generationSettingsRepository.findDefault()
                .orElse(new GenerationSettingsEntity(GenerationSettingsRepository.DEFAULT_ID, null, Map.of()));
        GenerationSettingsEntity updated = new GenerationSettingsEntity(
                current.id(),
                request.defaultServiceId(),
                request.sceneServiceBindings() != null ? request.sceneServiceBindings() : Map.of()
        );
        generationSettingsRepository.save(updated);
        return getGenerationSettings();
    }

    @GetMapping("/embedding")
    public ApiResponse<EmbeddingRoutingSettingsResponse> getEmbeddingSettings() {
        var settings = embeddingSettingsRepository.findDefault()
                .orElse(new EmbeddingSettingsEntity(EmbeddingSettingsRepository.DEFAULT_ID, null, null, null));
        return ApiResponse.ok(new EmbeddingRoutingSettingsResponse(
                settings.defaultServiceId(),
                settings.knowledgeBaseServiceId(),
                settings.memoryServiceId()
        ));
    }

    @PutMapping("/embedding")
    public ApiResponse<EmbeddingRoutingSettingsResponse> updateEmbeddingSettings(
            @RequestBody EmbeddingRoutingSettingsRequest request) {
        log.info("更新向量路由设置: default={}, knowledgeBase={}, memory={}",
                request.defaultServiceId(), request.knowledgeBaseServiceId(), request.memoryServiceId());
        EmbeddingSettingsEntity current = embeddingSettingsRepository.findDefault()
                .orElse(new EmbeddingSettingsEntity(EmbeddingSettingsRepository.DEFAULT_ID, null, null, null));
        EmbeddingSettingsEntity updated = new EmbeddingSettingsEntity(
                current.id(),
                request.defaultServiceId(),
                request.knowledgeBaseServiceId(),
                request.memoryServiceId()
        );
        embeddingSettingsRepository.save(updated);
        return getEmbeddingSettings();
    }

    @GetMapping("/rerank")
    public ApiResponse<RerankRoutingSettingsResponse> getRerankSettings() {
        var settings = rerankSettingsRepository.findDefault()
                .orElse(new RerankSettingsEntity(
                        RerankSettingsRepository.DEFAULT_ID,
                        false,
                        RerankExecutionMode.DISABLED,
                        null,
                        null,
                        5,
                        false,
                        10
                ));
        return ApiResponse.ok(new RerankRoutingSettingsResponse(
                settings.enabled(),
                settings.mode().name(),
                settings.nativeServiceId(),
                settings.llmServiceId(),
                settings.knowledgeTopK(),
                settings.memoryEnabled(),
                settings.memoryTopK()
        ));
    }

    @PutMapping("/rerank")
    public ApiResponse<RerankRoutingSettingsResponse> updateRerankSettings(
            @RequestBody RerankRoutingSettingsRequest request) {
        log.info("更新精排路由设置: enabled={}, mode={}", request.enabled(), request.mode());
        RerankSettingsEntity current = rerankSettingsRepository.findDefault()
                .orElse(new RerankSettingsEntity(
                        RerankSettingsRepository.DEFAULT_ID,
                        false,
                        RerankExecutionMode.DISABLED,
                        null,
                        null,
                        5,
                        false,
                        10
                ));
        RerankSettingsEntity updated = new RerankSettingsEntity(
                current.id(),
                request.enabled() != null ? request.enabled() : current.enabled(),
                request.mode() != null ? RerankExecutionMode.valueOf(request.mode()) : current.mode(),
                request.nativeServiceId() != null ? request.nativeServiceId() : current.nativeServiceId(),
                request.llmServiceId() != null ? request.llmServiceId() : current.llmServiceId(),
                request.knowledgeTopK() != null ? request.knowledgeTopK() : current.knowledgeTopK(),
                request.memoryEnabled() != null ? request.memoryEnabled() : current.memoryEnabled(),
                request.memoryTopK() != null ? request.memoryTopK() : current.memoryTopK()
        );
        rerankSettingsRepository.save(updated);
        return getRerankSettings();
    }
}
