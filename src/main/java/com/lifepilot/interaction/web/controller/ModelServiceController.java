package com.lifepilot.interaction.web.controller;

import com.lifepilot.interaction.web.model.CreateModelServiceRequest;
import com.lifepilot.interaction.web.model.ErrorResponse;
import com.lifepilot.interaction.web.model.ModelServiceResponse;
import com.lifepilot.interaction.web.model.UpdateModelServiceRequest;
import com.lifepilot.modelservice.model.GenerationCapability;
import com.lifepilot.modelservice.model.GenerationSettingsEntity;
import com.lifepilot.modelservice.model.ModelServiceEntity;
import com.lifepilot.modelservice.model.ModelServiceKind;
import com.lifepilot.modelservice.model.RerankSettingsEntity;
import com.lifepilot.modelservice.repository.EmbeddingSettingsRepository;
import com.lifepilot.modelservice.repository.GenerationSettingsRepository;
import com.lifepilot.modelservice.repository.ModelServiceRepository;
import com.lifepilot.modelservice.repository.RerankSettingsRepository;
import com.lifepilot.modelservice.service.ModelServiceRegistrationService;
import com.lifepilot.llm.config.ProviderType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
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
public class ModelServiceController {

    private static final Logger log = LoggerFactory.getLogger(ModelServiceController.class);

    private final ModelServiceRepository modelServiceRepository;
    private final GenerationSettingsRepository generationSettingsRepository;
    private final EmbeddingSettingsRepository embeddingSettingsRepository;
    private final RerankSettingsRepository rerankSettingsRepository;
    private final ModelServiceRegistrationService registrationService;

    public ModelServiceController(ModelServiceRepository modelServiceRepository,
                                  GenerationSettingsRepository generationSettingsRepository,
                                  EmbeddingSettingsRepository embeddingSettingsRepository,
                                  RerankSettingsRepository rerankSettingsRepository,
                                  ModelServiceRegistrationService registrationService) {
        this.modelServiceRepository = modelServiceRepository;
        this.generationSettingsRepository = generationSettingsRepository;
        this.embeddingSettingsRepository = embeddingSettingsRepository;
        this.rerankSettingsRepository = rerankSettingsRepository;
        this.registrationService = registrationService;
    }

    @GetMapping
    public ResponseEntity<List<ModelServiceResponse>> listServices(@RequestParam(required = false) String kind) {
        List<ModelServiceEntity> services = resolveServices(kind, false);
        return ResponseEntity.ok(services.stream().map(this::toResponse).toList());
    }

    @GetMapping("/enabled")
    public ResponseEntity<List<ModelServiceResponse>> listEnabledServices(@RequestParam(required = false) String kind) {
        List<ModelServiceEntity> services = resolveServices(kind, true);
        return ResponseEntity.ok(services.stream().map(this::toResponse).toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<ModelServiceResponse> getService(@PathVariable String id) {
        return modelServiceRepository.findById(id)
                .map(entity -> ResponseEntity.ok(toResponse(entity)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<?> createService(@RequestBody CreateModelServiceRequest request) {
        try {
            validateCreateRequest(request);
            ModelServiceEntity entity = toEntity(request);
            modelServiceRepository.save(entity);
            registrationService.registerService(entity);
            return ResponseEntity.ok(toResponse(entity));
        } catch (IllegalArgumentException e) {
            log.warn("创建模型服务失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(new ErrorResponse(400, e.getMessage(), Instant.now()));
        } catch (Exception e) {
            log.error("创建模型服务异常", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ErrorResponse(500, "创建模型服务失败: " + e.getMessage(), Instant.now()));
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateService(@PathVariable String id,
                                           @RequestBody UpdateModelServiceRequest request) {
        return modelServiceRepository.findById(id)
                .map(existing -> {
                    try {
                        ModelServiceEntity merged = merge(existing, request);
                        modelServiceRepository.save(merged);
                        registrationService.registerService(merged);
                        return ResponseEntity.ok(toResponse(merged));
                    } catch (IllegalArgumentException e) {
                        log.warn("更新模型服务失败: {}", e.getMessage());
                        return ResponseEntity.badRequest()
                                .body(new ErrorResponse(400, e.getMessage(), Instant.now()));
                    }
                })
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(new ErrorResponse(404, "模型服务不存在: id=" + id, Instant.now())));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteService(@PathVariable String id) {
        if (modelServiceRepository.findById(id).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(new ErrorResponse(404, "模型服务不存在: id=" + id, Instant.now()));
        }
        clearRoutingReferences(id);
        registrationService.deregisterService(id);
        int deleted = modelServiceRepository.deleteById(id);
        if (deleted > 0) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse(500, "删除模型服务失败: id=" + id, Instant.now()));
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
                        Map.of()
                ),
                emptyToNull(request.displayName()),
                emptyToNull(request.description())
        );
    }

    private ModelServiceEntity merge(ModelServiceEntity existing, UpdateModelServiceRequest request) {
        ModelServiceKind kind = request.kind() != null ? parseKind(request.kind()) : existing.kind();
        ProviderType providerType = request.type() != null ? parseProviderType(request.type()) : existing.providerType();
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
                                              Map<String, Object> base) {
        Map<String, Object> metadata = new LinkedHashMap<>(base);
        putOrRemove(metadata, "costPerInputToken", costPerInputToken);
        putOrRemove(metadata, "costPerOutputToken", costPerOutputToken);
        putOrRemove(metadata, "maxContextWindow", maxContextWindow);
        putOrRemove(metadata, "embeddingDimension", embeddingDimension);
        putOrRemove(metadata, "supportsStreaming", supportsStreaming);
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
                false,
                entity.displayName(),
                entity.description()
        );
    }

    private List<String> toCapabilities(ModelServiceEntity entity) {
        return switch (entity.kind()) {
            case GENERATION -> entity.generationCapabilities().stream().map(Enum::name).sorted().toList();
            case EMBEDDING -> List.of("EMBEDDING");
            case RERANK -> List.of("RERANK");
        };
    }

    private Integer getIntegerMetadata(ModelServiceEntity entity, String key) {
        Object value = entity.metadata().get(key);
        return value instanceof Number number ? number.intValue() : null;
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

    private @Nullable String emptyToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
