package com.lifepilot.modelservice.registry;

import com.lifepilot.modelservice.model.GenerationCapability;
import com.lifepilot.modelservice.model.ModelServiceEntity;
import com.lifepilot.modelservice.model.ModelServiceKind;
import com.lifepilot.modelservice.repository.ModelServiceRepository;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 模型服务注册表。
 *
 * <p>统一封装 {@code model_services} 的查询语义，按服务类型、场景和模型名筛选可用服务。
 *
 * @author zsg
 * @since 2026-03-24
 */
@Component
public class ModelServiceRegistry {

    private final ModelServiceRepository repository;

    public ModelServiceRegistry(ModelServiceRepository repository) {
        this.repository = repository;
    }

    /**
     * 查询全部模型服务。
     *
     * @return 服务列表
     */
    public List<ModelServiceEntity> findAll() {
        return repository.findAll();
    }

    /**
     * 查询指定类型、已启用的模型服务。
     *
     * @param kind 服务类型
     * @return 已启用服务
     */
    public List<ModelServiceEntity> findEnabledByKind(ModelServiceKind kind) {
        return repository.findEnabledByKind(kind);
    }

    /**
     * 按 ID 查询已启用服务，并校验类型。
     *
     * @param kind 服务类型
     * @param serviceId 服务 ID
     * @return 服务实体
     */
    public Optional<ModelServiceEntity> findEnabledById(ModelServiceKind kind, @Nullable String serviceId) {
        if (serviceId == null || serviceId.isBlank()) {
            return Optional.empty();
        }
        return repository.findById(serviceId)
                .filter(ModelServiceEntity::enabled)
                .filter(service -> service.kind() == kind);
    }

    /**
     * 按模型名查询指定类型、已启用的服务。
     *
     * <p>匹配顺序为：精确匹配 -> 双向包含模糊匹配。
     *
     * @param kind 服务类型
     * @param modelName 模型名
     * @return 候选服务列表
     */
    public List<ModelServiceEntity> findEnabledByModelName(ModelServiceKind kind, @Nullable String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return List.of();
        }
        List<ModelServiceEntity> enabled = repository.findEnabledByKind(kind);
        List<ModelServiceEntity> exact = enabled.stream()
                .filter(service -> service.modelName().equals(modelName))
                .sorted(Comparator.comparingInt(ModelServiceEntity::priority))
                .toList();
        if (!exact.isEmpty()) {
            return exact;
        }
        String query = modelName.toLowerCase();
        return enabled.stream()
                .filter(service -> {
                    String registered = service.modelName().toLowerCase();
                    return registered.contains(query) || query.contains(registered);
                })
                .sorted(Comparator.comparingInt(ModelServiceEntity::priority))
                .toList();
    }

    /**
     * 查询场景匹配的生成服务。
     *
     * <p>返回顺序固定为：显式声明支持该场景的服务在前，未声明场景的通用服务在后。
     *
     * @param scene 场景名
     * @param requiredCapability 所需能力
     * @return 候选服务
     */
    public List<ModelServiceEntity> findGenerationCandidates(@Nullable String scene,
                                                             GenerationCapability requiredCapability) {
        List<ModelServiceEntity> enabled = repository.findEnabledByKind(ModelServiceKind.GENERATION).stream()
                .filter(service -> service.generationCapabilities().contains(requiredCapability))
                .toList();
        if (scene == null || scene.isBlank()) {
            return enabled;
        }
        String normalizedScene = scene.trim();
        List<ModelServiceEntity> matched = enabled.stream()
                .filter(service -> service.supportedScenes().contains(normalizedScene))
                .toList();
        List<ModelServiceEntity> generic = enabled.stream()
                .filter(service -> service.supportedScenes().isEmpty())
                .toList();
        ArrayList<ModelServiceEntity> ordered = new ArrayList<>(matched.size() + generic.size());
        ordered.addAll(matched);
        ordered.addAll(generic);
        return List.copyOf(ordered);
    }
}
