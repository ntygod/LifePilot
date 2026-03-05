package com.lifepilot.interaction.web.controller;

import com.lifepilot.interaction.web.model.ErrorResponse;
import com.lifepilot.llm.config.LlmProviderEntity;
import com.lifepilot.llm.config.ProviderCapability;
import com.lifepilot.llm.config.ProviderType;
import com.lifepilot.llm.registry.ProviderRegistry;
import com.lifepilot.llm.service.LlmProviderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * LLM Provider 管理 REST Controller。
 *
 * <p>提供 Provider 配置的 CRUD 操作，以及预设置供应商的查询。
 *
 * @author zsg
 * @since 2026-02-27
 */
@RestController
@RequestMapping("/api/llm-providers")
public class LlmProviderController {

    private static final Logger log = LoggerFactory.getLogger(LlmProviderController.class);

    private final LlmProviderService providerService;
    private final ProviderRegistry providerRegistry;

    public LlmProviderController(LlmProviderService providerService,
                                  @Autowired(required = false) ProviderRegistry providerRegistry) {
        this.providerService = providerService;
        this.providerRegistry = providerRegistry;
    }

    /**
     * 获取所有 Provider（包括已禁用）。
     *
     * @return Provider 列表
     */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listProviders() {
        log.debug("查询所有 LLM Provider");
        List<LlmProviderEntity> providers = providerService.findAll();
        // 列表查询不包含健康状态，避免阻塞响应
        List<Map<String, Object>> result = providers.stream()
                .map(entity -> toMap(entity, false))
                .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    /**
     * 获取所有已启用的 Provider。
     *
     * @return 已启用的 Provider 列表
     */
    @GetMapping("/enabled")
    public ResponseEntity<List<Map<String, Object>>> listEnabledProviders() {
        log.debug("查询已启用的 LLM Provider");
        List<LlmProviderEntity> providers = providerService.findAllEnabled();
        // 列表查询不包含健康状态，避免阻塞响应（健康状态通过 /api/settings/providers/health 单独获取）
        List<Map<String, Object>> result = providers.stream()
                .map(entity -> toMap(entity, false))
                .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    /**
     * 获取所有预设置的 Provider。
     *
     * @return 预设置 Provider 列表
     */
    @GetMapping("/presets")
    public ResponseEntity<List<Map<String, Object>>> listPresets() {
        log.debug("查询预设置的 LLM Provider");
        List<LlmProviderEntity> presets = providerService.findPresets();
        // 列表查询不包含健康状态，避免阻塞响应
        List<Map<String, Object>> result = presets.stream()
                .map(entity -> toMap(entity, false))
                .collect(Collectors.toList());
        return ResponseEntity.ok(result);
    }

    /**
     * 根据 ID 获取 Provider。
     *
     * @param id Provider ID
     * @return Provider 详情，不存在返回 404
     */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getProvider(@PathVariable String id) {
        log.debug("查询 LLM Provider: id={}", id);
        // 单个 Provider 查询也不包含健康状态，避免阻塞响应（健康状态通过专门的接口获取）
        return providerService.findById(id)
                .<ResponseEntity<Map<String, Object>>>map(entity ->
                        ResponseEntity.ok(toMap(entity, false)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    /**
     * 创建或更新 Provider。
     *
     * @param request Provider 创建/更新请求
     * @return 保存后的 Provider
     */
    @PostMapping
    public ResponseEntity<?> saveProvider(@RequestBody CreateProviderRequest request) {
        log.debug("保存 LLM Provider: id={}", request.id());
        try {
            LlmProviderEntity entity = toEntity(request);
            LlmProviderEntity saved = providerService.save(entity);
            // 保存时不检查健康状态，避免阻塞响应
            return ResponseEntity.ok(toMap(saved, false));
        } catch (IllegalArgumentException e) {
            log.warn("保存 Provider 失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(
                    new ErrorResponse(400, e.getMessage(), Instant.now()));
        } catch (Exception e) {
            log.error("保存 Provider 异常", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                    new ErrorResponse(500, "保存 Provider 失败: " + e.getMessage(), Instant.now()));
        }
    }

    /**
     * 更新 Provider（部分更新）。
     *
     * @param id      Provider ID
     * @param request 更新请求
     * @return 更新后的 Provider
     */
    @PutMapping("/{id}")
    public ResponseEntity<?> updateProvider(@PathVariable String id,
                                           @RequestBody UpdateProviderRequest request) {
        log.debug("更新 LLM Provider: id={}", id);
        return providerService.findById(id)
                .map(existing -> {
                    try {
                        LlmProviderEntity updated = mergeEntity(existing, request);
                        LlmProviderEntity saved = providerService.save(updated);
                        // 更新时不检查健康状态，避免阻塞响应
                        return ResponseEntity.ok(toMap(saved, false));
                    } catch (IllegalArgumentException e) {
                        log.warn("更新 Provider 失败: {}", e.getMessage());
                        return ResponseEntity.badRequest().<Map<String, Object>>body(
                                Map.of("error", e.getMessage()));
                    }
                })
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    /**
     * 删除 Provider（仅删除非预设置的）。
     *
     * @param id Provider ID
     * @return 204 成功，400 预设置不可删除，404 不存在
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteProvider(@PathVariable String id) {
        log.debug("删除 LLM Provider: id={}", id);
        try {
            boolean deleted = providerService.deleteById(id);
            if (deleted) {
                return ResponseEntity.noContent().build();
            } else {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                        new ErrorResponse(404, "Provider 不存在: id=" + id, Instant.now()));
            }
        } catch (IllegalArgumentException e) {
            log.warn("删除 Provider 失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(
                    new ErrorResponse(400, e.getMessage(), Instant.now()));
        }
    }

    /**
     * 获取 Provider 健康状态。
     *
     * @param id Provider ID
     * @return 健康状态
     */
    @GetMapping("/{id}/health")
    public ResponseEntity<Map<String, Object>> getProviderHealth(@PathVariable String id) {
        log.debug("查询 Provider 健康状态: id={}", id);
        if (providerRegistry == null) {
            return ResponseEntity.ok(Map.of("healthy", false, "message", "ProviderRegistry 不可用"));
        }
        boolean healthy = providerRegistry.healthCheck(id);
        return ResponseEntity.ok(Map.of("healthy", healthy));
    }

    /**
     * 将 LlmProviderEntity 转换为 Map（用于 JSON 响应）。
     *
     * @param entity Provider 实体
     * @param includeHealth 是否包含健康状态（健康检查可能较慢，保存时建议设为 false）
     */
    private Map<String, Object> toMap(LlmProviderEntity entity, boolean includeHealth) {
        Map<String, Object> map = new java.util.HashMap<>();
        map.put("id", entity.id());
        map.put("type", entity.type().name());
        map.put("apiUrl", entity.apiUrl());
        // API Key 不返回，前端需要时单独填写
        map.put("modelName", entity.modelName());
        map.put("timeoutSeconds", entity.timeoutSeconds());
        map.put("priority", entity.priority());
        map.put("scenes", entity.scenes());
        map.put("capabilities", entity.capabilities().stream()
                .map(Enum::name)
                .collect(Collectors.toList()));
        map.put("enabled", entity.enabled());
        map.put("costPerInputToken", entity.costPerInputToken());
        map.put("costPerOutputToken", entity.costPerOutputToken());
        map.put("maxContextWindow", entity.maxContextWindow());
        map.put("embeddingDimension", entity.embeddingDimension());
        map.put("supportsStreaming", entity.supportsStreaming());
        map.put("isPreset", entity.isPreset());
        map.put("displayName", entity.displayName());
        map.put("description", entity.description());
        // 仅在需要时检查健康状态（健康检查可能较慢）
        if (includeHealth && providerRegistry != null) {
            Map<String, Boolean> healthStatus = providerRegistry.healthCheckAll();
            map.put("healthy", healthStatus.getOrDefault(entity.id(), false));
        }
        return map;
    }


    /**
     * 从创建请求创建实体。
     */
    private LlmProviderEntity toEntity(CreateProviderRequest request) {
        // 如果场景为空，默认包含 chat 场景（至少保证基本可用）
        List<String> scenes = request.scenes() != null && !request.scenes().isEmpty()
                ? request.scenes()
                : List.of("chat");
        
        return new LlmProviderEntity(
                request.id(),
                ProviderType.valueOf(request.type()),
                request.apiUrl(),
                request.apiKey(),
                request.modelName(),
                request.timeoutSeconds() != null ? request.timeoutSeconds() : 30,
                request.priority() != null ? request.priority() : 0,
                scenes,
                request.capabilities() != null
                        ? request.capabilities().stream()
                        .map(ProviderCapability::valueOf)
                        .collect(Collectors.toSet())
                        : java.util.Set.of(ProviderCapability.CHAT),
                request.enabled() != null ? request.enabled() : true,
                request.costPerInputToken() != null ? request.costPerInputToken() : 0,
                request.costPerOutputToken() != null ? request.costPerOutputToken() : 0,
                request.maxContextWindow() != null ? request.maxContextWindow() : 4096,
                request.embeddingDimension(),
                request.supportsStreaming() != null ? request.supportsStreaming() : false,
                false, // 自定义 Provider 不是预设置
                request.displayName(),
                request.description()
        );
    }

    /**
     * 合并更新请求到现有实体。
     */
    private LlmProviderEntity mergeEntity(LlmProviderEntity existing,
                                           UpdateProviderRequest request) {
        return new LlmProviderEntity(
                existing.id(),
                request.type() != null ? ProviderType.valueOf(request.type()) : existing.type(),
                request.apiUrl() != null ? request.apiUrl() : existing.apiUrl(),
                // 如果 apiKey 为 null 或空字符串，使用现有的 apiKey（表示未修改）
                (request.apiKey() != null && !request.apiKey().isBlank())
                        ? request.apiKey() : existing.apiKey(),
                request.modelName() != null ? request.modelName() : existing.modelName(),
                request.timeoutSeconds() != null ? request.timeoutSeconds() : existing.timeoutSeconds(),
                request.priority() != null ? request.priority() : existing.priority(),
                request.scenes() != null ? request.scenes() : existing.scenes(),
                request.capabilities() != null
                        ? request.capabilities().stream()
                        .map(ProviderCapability::valueOf)
                        .collect(Collectors.toSet())
                        : existing.capabilities(),
                request.enabled() != null ? request.enabled() : existing.enabled(),
                request.costPerInputToken() != null ? request.costPerInputToken() : existing.costPerInputToken(),
                request.costPerOutputToken() != null ? request.costPerOutputToken() : existing.costPerOutputToken(),
                request.maxContextWindow() != null ? request.maxContextWindow() : existing.maxContextWindow(),
                request.embeddingDimension() != null ? request.embeddingDimension() : existing.embeddingDimension(),
                request.supportsStreaming() != null ? request.supportsStreaming() : existing.supportsStreaming(),
                existing.isPreset(), // 预设置标志不可修改
                request.displayName() != null ? request.displayName() : existing.displayName(),
                request.description() != null ? request.description() : existing.description()
        );
    }

    /**
     * 创建 Provider 请求 DTO。
     */
    public record CreateProviderRequest(
            String id,
            String type,
            String apiUrl,
            String apiKey,
            String modelName,
            Integer timeoutSeconds,
            Integer priority,
            List<String> scenes,
            List<String> capabilities,
            Boolean enabled,
            Integer costPerInputToken,
            Integer costPerOutputToken,
            Integer maxContextWindow,
            Integer embeddingDimension,
            Boolean supportsStreaming,
            String displayName,
            String description
    ) {
    }

    /**
     * 更新 Provider 请求 DTO（所有字段可选）。
     */
    public record UpdateProviderRequest(
            String type,
            String apiUrl,
            String apiKey,
            String modelName,
            Integer timeoutSeconds,
            Integer priority,
            List<String> scenes,
            List<String> capabilities,
            Boolean enabled,
            Integer costPerInputToken,
            Integer costPerOutputToken,
            Integer maxContextWindow,
            Integer embeddingDimension,
            Boolean supportsStreaming,
            String displayName,
            String description
    ) {
    }
}
