package com.lifepilot.interaction.web.controller;

import com.lifepilot.interaction.web.model.ApiResponse;
import com.lifepilot.interaction.web.model.CreateModelServiceRequest;
import com.lifepilot.interaction.web.model.ErrorResponse;
import com.lifepilot.interaction.web.model.ModelServiceResponse;
import com.lifepilot.interaction.web.model.ModelServiceTemplateModelResponse;
import com.lifepilot.interaction.web.model.ModelServiceTemplateResponse;
import com.lifepilot.interaction.web.model.UpdateModelServiceRequest;
import com.lifepilot.modelservice.model.GenerationCapability;
import com.lifepilot.modelservice.model.GenerationSettingsEntity;
import com.lifepilot.modelservice.model.ModelServiceEntity;
import com.lifepilot.modelservice.model.ModelServiceKind;
import com.lifepilot.modelservice.model.RerankSettingsEntity;
import com.lifepilot.modelservice.repository.EmbeddingSettingsRepository;
import com.lifepilot.modelservice.repository.GenerationSettingsRepository;
import com.lifepilot.modelservice.repository.ModelServiceRepository;
import com.lifepilot.modelservice.repository.ModelServiceTemplateRepository;
import com.lifepilot.modelservice.repository.RerankSettingsRepository;
import com.lifepilot.modelservice.service.ModelServiceRegistrationService;
import com.lifepilot.llm.config.ProviderType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 模型服务管理接口。
 *
 * @author zsg
 * @since 2026-03-24
 */
@RestController
@RequestMapping("/api/model-services")
@ConditionalOnProperty(name = "lifepilot.gateway.channels.web.enabled", havingValue = "true")
public class ModelServiceController {

    private static final Logger log = LoggerFactory.getLogger(ModelServiceController.class);

    private final ModelServiceRepository modelServiceRepository;
    private final GenerationSettingsRepository generationSettingsRepository;
    private final EmbeddingSettingsRepository embeddingSettingsRepository;
    private final RerankSettingsRepository rerankSettingsRepository;
    private final ModelServiceTemplateRepository modelServiceTemplateRepository;
    private final ModelServiceRegistrationService registrationService;

    public ModelServiceController(ModelServiceRepository modelServiceRepository,
                                  GenerationSettingsRepository generationSettingsRepository,
                                  EmbeddingSettingsRepository embeddingSettingsRepository,
                                  RerankSettingsRepository rerankSettingsRepository,
                                  ModelServiceTemplateRepository modelServiceTemplateRepository,
                                  ModelServiceRegistrationService registrationService) {
        this.modelServiceRepository = modelServiceRepository;
        this.generationSettingsRepository = generationSettingsRepository;
        this.embeddingSettingsRepository = embeddingSettingsRepository;
        this.rerankSettingsRepository = rerankSettingsRepository;
        this.modelServiceTemplateRepository = modelServiceTemplateRepository;
        this.registrationService = registrationService;
    }

    @GetMapping
    public ApiResponse<List<ModelServiceResponse>> listServices(@RequestParam(required = false) String kind) {
        List<ModelServiceEntity> services = resolveServices(kind, false);
        return ApiResponse.ok(services.stream().map(this::toResponse).toList());
    }

    @GetMapping("/enabled")
    public ApiResponse<List<ModelServiceResponse>> listEnabledServices(@RequestParam(required = false) String kind) {
        List<ModelServiceEntity> services = resolveServices(kind, true);
        return ApiResponse.ok(services.stream().map(this::toResponse).toList());
    }

    @GetMapping("/templates")
    public ApiResponse<List<ModelServiceTemplateResponse>> listTemplates() {
        return ApiResponse.ok(modelServiceTemplateRepository.findAll().stream()
                .map(template -> new ModelServiceTemplateResponse(
                        template.vendorKey(),
                        template.displayName(),
                        template.providerType().name(),
                        template.description(),
                        template.defaultApiUrl(),
                        template.supportedKinds().stream().map(Enum::name).toList(),
                        template.defaultTimeoutSeconds(),
                        template.defaultCapabilities(),
                        template.defaultScenes(),
                        template.defaultSupportsStreaming(),
                        template.defaultMaxContextWindow(),
                        template.modelOptions().stream()
                                .map(option -> new ModelServiceTemplateModelResponse(
                                        option.kind().name(),
                                        option.value(),
                                        option.label(),
                                        option.recommended(),
                                        option.capabilities(),
                                        option.scenes(),
                                        option.supportsStreaming(),
                                        option.maxContextWindow(),
                                        option.embeddingDimension()))
                                .toList()))
                .toList());
    }

    @GetMapping("/{id}")
    public ApiResponse<ModelServiceResponse> getService(@PathVariable String id) {
        var entity = modelServiceRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "模型服务不存在: id=" + id));
        return ApiResponse.ok(toResponse(entity));
    }

    @PostMapping
    public ApiResponse<ModelServiceResponse> createService(@RequestBody CreateModelServiceRequest request) {
        validateCreateRequest(request);
        ModelServiceEntity entity = toEntity(request);
        modelServiceRepository.save(entity);
        registrationService.registerService(entity);
        return ApiResponse.ok(toResponse(entity));
    }

    @PutMapping("/{id}")
    public ApiResponse<ModelServiceResponse> updateService(@PathVariable String id,
                                           @RequestBody UpdateModelServiceRequest request) {
        var existing = modelServiceRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "模型服务不存在: id=" + id));
        ModelServiceEntity merged = merge(existing, request);
        modelServiceRepository.save(merged);
        registrationService.registerService(merged);
        return ApiResponse.ok(toResponse(merged));
    }

    /** 切换模型服务启用状态。 */
    @PostMapping("/{id}/toggle-enabled")
    public ApiResponse<ModelServiceResponse> toggleEnabled(@PathVariable String id) {
        var existing = modelServiceRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "模型服务不存在: id=" + id));
        var toggled = new ModelServiceEntity(
                existing.id(), existing.kind(), existing.providerType(),
                existing.apiUrl(), existing.apiKey(), existing.modelName(),
                existing.timeoutSeconds(), existing.priority(), !existing.enabled(),
                existing.supportedScenes(), existing.generationCapabilities(),
                existing.metadata(), existing.displayName(), existing.description()
        );
        modelServiceRepository.save(toggled);
        registrationService.registerService(toggled);
        log.info("模型服务启用状态已切换: id={}, enabled={}", id, toggled.enabled());
        return ApiResponse.ok(toResponse(toggled));
    }

    /** 测试模型服务连接。 */
    @PostMapping("/{id}/test")
    public ApiResponse<Map<String, Object>> testConnection(@PathVariable String id) {
        var existing = modelServiceRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "模型服务不存在: id=" + id));
        try {
            boolean healthy = registrationService.healthCheck(id);
            return ApiResponse.ok(Map.of("healthy", healthy, "serviceId", id, "modelName", existing.modelName()));
        } catch (Exception e) {
            log.warn("模型服务连接测试失败: id={}, error={}", id, e.getMessage());
            return ApiResponse.ok(Map.of("healthy", false, "serviceId", id, "error", e.getMessage() != null ? e.getMessage() : "连接失败"));
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteService(@PathVariable String id) {
        if (modelServiceRepository.findById(id).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "模型服务不存在: id=" + id);
        }
        clearRoutingReferences(id);
        registrationService.deregisterService(id);
        int deleted = modelServiceRepository.deleteById(id);
        if (deleted > 0) {
            return ResponseEntity.noContent().build();
        }
        throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "删除模型服务失败: id=" + id);
    }

    private List<ModelServiceEntity> resolveServices(@Nullable String kind, boolean enabledOnly) {
        if (kind == null || kind.isBlank()) {
            return enabledOnly ? modelServiceRepository.findAll().stream().filter(ModelServiceEntity::enabled).toList()
                    : modelServiceRepository.findAll();
        }
        ModelServiceKind resolvedKind = parseKind(kind);
        return enabledOnly
                ? modelServiceRepository.findEnabledByKind(resolvedKind)
                : modelServiceRepository.findByKind(resolvedKind);
    }

    private void validateCreateRequest(CreateModelServiceRequest request) {
        if (request.id() == null || request.id().isBlank()) {
            throw new IllegalArgumentException("模型服务 ID 不能为空");
        }
        if (request.apiUrl() == null || request.apiUrl().isBlank()) {
            throw new IllegalArgumentException("API 地址不能为空");
        }
        if (request.modelName() == null || request.modelName().isBlank()) {
            throw new IllegalArgumentException("模型名称不能为空");
        }
        parseKind(request.kind());
        parseProviderType(request.type());
        validateVendorKey(request.vendorKey());
    }

    private ModelServiceEntity toEntity(CreateModelServiceRequest request) {
        ModelServiceKind kind = parseKind(request.kind());
        return new ModelServiceEntity(
                request.id(),
                kind,
                parseProviderType(request.type()),
                request.apiUrl(),
                emptyToNull(request.apiKey()),
                request.modelName(),
                request.timeoutSeconds() != null ? request.timeoutSeconds() : 30,
                request.priority() != null ? request.priority() : 0,
                request.enabled() == null || request.enabled(),
                request.scenes() != null ? request.scenes() : List.of(),
                resolveGenerationCapabilities(kind, request.capabilities(), request.supportsStreaming()),
                buildMetadata(
                        request.costPerInputToken(),
                        request.costPerOutputToken(),
                        request.maxContextWindow(),
                        request.embeddingDimension(),
                        request.supportsStreaming(),
                        request.vendorKey(),
                        Map.of()
                ),
                emptyToNull(request.displayName()),
                emptyToNull(request.description())
        );
    }

    private ModelServiceEntity merge(ModelServiceEntity existing, UpdateModelServiceRequest request) {
        ModelServiceKind kind = request.kind() != null ? parseKind(request.kind()) : existing.kind();
        ProviderType providerType = request.type() != null ? parseProviderType(request.type()) : existing.providerType();
        validateVendorKey(request.vendorKey());
        return new ModelServiceEntity(
                existing.id(),
                kind,
                providerType,
                request.apiUrl() != null ? request.apiUrl() : existing.apiUrl(),
                request.apiKey() != null && !request.apiKey().isBlank() ? request.apiKey() : existing.apiKey(),
                request.modelName() != null ? request.modelName() : existing.modelName(),
                request.timeoutSeconds() != null ? request.timeoutSeconds() : existing.timeoutSeconds(),
                request.priority() != null ? request.priority() : existing.priority(),
                request.enabled() != null ? request.enabled() : existing.enabled(),
                request.scenes() != null ? request.scenes() : existing.supportedScenes(),
                request.capabilities() != null || request.supportsStreaming() != null
                        ? resolveGenerationCapabilities(kind, request.capabilities(), request.supportsStreaming())
                        : existing.generationCapabilities(),
                buildMetadata(
                        request.costPerInputToken(),
                        request.costPerOutputToken(),
                        request.maxContextWindow(),
                        request.embeddingDimension(),
                        request.supportsStreaming(),
                        request.vendorKey() != null ? request.vendorKey() : getStringMetadata(existing, "vendorKey"),
                        existing.metadata()
                ),
                request.displayName() != null ? emptyToNull(request.displayName()) : existing.displayName(),
                request.description() != null ? emptyToNull(request.description()) : existing.description()
        );
    }

    private Set<GenerationCapability> resolveGenerationCapabilities(ModelServiceKind kind,
                                                                    @Nullable List<String> capabilities,
                                                                    @Nullable Boolean supportsStreaming) {
        if (kind != ModelServiceKind.GENERATION) {
            return Set.of();
        }
        LinkedHashSet<GenerationCapability> resolved = new LinkedHashSet<>();
        if (capabilities != null) {
            for (String capability : capabilities) {
                if (capability == null || capability.isBlank()) {
                    continue;
                }
                try {
                    resolved.add(GenerationCapability.valueOf(capability));
                } catch (IllegalArgumentException ignored) {
                    // 忽略非生成能力，例如 EMBEDDING / RERANK。
                }
            }
        }
        if (Boolean.TRUE.equals(supportsStreaming)) {
            resolved.add(GenerationCapability.STREAMING);
        }
        return Set.copyOf(resolved);
    }

    private Map<String, Object> buildMetadata(@Nullable Integer costPerInputToken,
                                              @Nullable Integer costPerOutputToken,
                                              @Nullable Integer maxContextWindow,
                                              @Nullable Integer embeddingDimension,
                                              @Nullable Boolean supportsStreaming,
                                              @Nullable String vendorKey,
                                              Map<String, Object> base) {
        Map<String, Object> metadata = new LinkedHashMap<>(base);
        putOrRemove(metadata, "costPerInputToken", costPerInputToken);
        putOrRemove(metadata, "costPerOutputToken", costPerOutputToken);
        putOrRemove(metadata, "maxContextWindow", maxContextWindow);
        putOrRemove(metadata, "embeddingDimension", embeddingDimension);
        putOrRemove(metadata, "supportsStreaming", supportsStreaming);
        putOrRemove(metadata, "vendorKey", emptyToNull(vendorKey));
        return Map.copyOf(metadata);
    }

    private void putOrRemove(Map<String, Object> metadata, String key, @Nullable Object value) {
        if (value == null) {
            metadata.remove(key);
        } else {
            metadata.put(key, value);
        }
    }

    private ModelServiceResponse toResponse(ModelServiceEntity entity) {
        return new ModelServiceResponse(
                entity.id(),
                entity.kind().name(),
                entity.providerType().name(),
                getStringMetadata(entity, "vendorKey"),
                entity.apiUrl(),
                entity.modelName(),
                entity.timeoutSeconds(),
                entity.priority(),
                entity.enabled(),
                entity.supportedScenes(),
                toCapabilities(entity),
                getIntegerMetadata(entity, "costPerInputToken"),
                getIntegerMetadata(entity, "costPerOutputToken"),
                getIntegerMetadata(entity, "maxContextWindow"),
                getIntegerMetadata(entity, "embeddingDimension"),
                getBooleanMetadata(entity, "supportsStreaming")
                        || entity.generationCapabilities().contains(GenerationCapability.STREAMING),
                entity.displayName(),
                entity.description()
        );
    }

    private List<String> toCapabilities(ModelServiceEntity entity) {
        if (entity.kind() == ModelServiceKind.GENERATION) {
            return entity.generationCapabilities().stream().map(Enum::name).sorted().toList();
        }
        if (entity.kind() == ModelServiceKind.EMBEDDING) {
            return List.of("EMBEDDING");
        }
        return List.of("RERANK");
    }

    private Integer getIntegerMetadata(ModelServiceEntity entity, String key) {
        Object value = entity.metadata().get(key);
        return value instanceof Number number ? number.intValue() : null;
    }

    private @Nullable String getStringMetadata(ModelServiceEntity entity, String key) {
        Object value = entity.metadata().get(key);
        return value instanceof String stringValue && !stringValue.isBlank() ? stringValue : null;
    }

    private boolean getBooleanMetadata(ModelServiceEntity entity, String key) {
        Object value = entity.metadata().get(key);
        return value instanceof Boolean booleanValue && booleanValue;
    }

    private void clearRoutingReferences(String serviceId) {
        generationSettingsRepository.findDefault().ifPresent(settings -> {
            String defaultServiceId = serviceId.equals(settings.defaultServiceId()) ? null : settings.defaultServiceId();
            Map<String, String> sceneBindings = settings.sceneServiceBindings().entrySet().stream()
                    .filter(entry -> !serviceId.equals(entry.getValue()))
                    .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            if (!defaultEquals(defaultServiceId, settings.defaultServiceId())
                    || sceneBindings.size() != settings.sceneServiceBindings().size()) {
                generationSettingsRepository.save(new GenerationSettingsEntity(
                        settings.id(), defaultServiceId, sceneBindings
                ));
            }
        });

        embeddingSettingsRepository.findDefault().ifPresent(settings -> {
            String defaultServiceId = serviceId.equals(settings.defaultServiceId()) ? null : settings.defaultServiceId();
            String knowledgeBaseServiceId = serviceId.equals(settings.knowledgeBaseServiceId())
                    ? null : settings.knowledgeBaseServiceId();
            String memoryServiceId = serviceId.equals(settings.memoryServiceId()) ? null : settings.memoryServiceId();
            if (!defaultEquals(defaultServiceId, settings.defaultServiceId())
                    || !defaultEquals(knowledgeBaseServiceId, settings.knowledgeBaseServiceId())
                    || !defaultEquals(memoryServiceId, settings.memoryServiceId())) {
                embeddingSettingsRepository.save(new com.lifepilot.modelservice.model.EmbeddingSettingsEntity(
                        settings.id(), defaultServiceId, knowledgeBaseServiceId, memoryServiceId
                ));
            }
        });

        rerankSettingsRepository.findDefault().ifPresent(settings -> {
            String nativeServiceId = serviceId.equals(settings.nativeServiceId()) ? null : settings.nativeServiceId();
            String llmServiceId = serviceId.equals(settings.llmServiceId()) ? null : settings.llmServiceId();
            if (!defaultEquals(nativeServiceId, settings.nativeServiceId())
                    || !defaultEquals(llmServiceId, settings.llmServiceId())) {
                rerankSettingsRepository.save(new RerankSettingsEntity(
                        settings.id(),
                        settings.enabled(),
                        settings.mode(),
                        nativeServiceId,
                        llmServiceId,
                        settings.knowledgeTopK(),
                        settings.memoryEnabled(),
                        settings.memoryTopK()
                ));
            }
        });
    }

    private boolean defaultEquals(@Nullable String left, @Nullable String right) {
        return Optional.ofNullable(left).orElse("").equals(Optional.ofNullable(right).orElse(""));
    }

    private ModelServiceKind parseKind(String kind) {
        try {
            return ModelServiceKind.valueOf(kind);
        } catch (Exception e) {
            throw new IllegalArgumentException("不支持的模型服务类型: " + kind);
        }
    }

    private ProviderType parseProviderType(String type) {
        try {
            return ProviderType.valueOf(type);
        } catch (Exception e) {
            throw new IllegalArgumentException("不支持的 Provider 类型: " + type);
        }
    }

    private void validateVendorKey(@Nullable String vendorKey) {
        String normalizedVendorKey = emptyToNull(vendorKey);
        if (normalizedVendorKey == null) {
            return;
        }
        if (!modelServiceTemplateRepository.existsByVendorKey(normalizedVendorKey)) {
            throw new IllegalArgumentException("不支持的厂商模板: " + normalizedVendorKey);
        }
    }

    private @Nullable String emptyToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
