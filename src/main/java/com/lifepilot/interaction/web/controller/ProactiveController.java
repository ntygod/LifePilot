package com.lifepilot.interaction.web.controller;

import com.lifepilot.agent.config.AgentConfigProperties;
import com.lifepilot.agent.task.proactive.AutonomyConfig;
import com.lifepilot.agent.task.proactive.AutonomyLevel;
import com.lifepilot.agent.task.proactive.AutonomyRepository;
import com.lifepilot.agent.task.proactive.QueuedActionRepository;
import com.lifepilot.agent.task.proactive.TrustUpgradeService;
import com.lifepilot.interaction.web.model.ApiResponse;
import com.lifepilot.interaction.web.model.ProactiveConfigDto;
import com.lifepilot.interaction.web.model.ProactiveConfigUpdateRequest;
import com.lifepilot.interaction.web.model.QueuedActionDto;
import com.lifepilot.interaction.web.model.TrustStatusDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 主动引擎队列 REST Controller — 提供排队动作查询、信任升级、配置管理等端点。
 *
 * @author zsg
 * @since 2026-04-15
 */
@RestController
@RequestMapping("/api/proactive")
public class ProactiveController {

    private static final Logger log = LoggerFactory.getLogger(ProactiveController.class);

    /** 行为名称 → 中文标签映射。 */
    private static final Map<String, String> BEHAVIOR_LABELS = Map.of(
            "follow-up", "追问进展",
            "insight", "关联洞察",
            "clipboard", "剪贴板识别",
            "report", "日报周报",
            "info-supplement", "信息补充",
            "context-prep", "情境准备",
            "task-execution", "任务代行",
            "reminder", "定时提醒"
    );

    private final QueuedActionRepository queuedActionRepository;

    @Nullable
    private final TrustUpgradeService trustUpgradeService;

    @Nullable
    private final AutonomyRepository autonomyRepository;

    @Nullable
    private final AgentConfigProperties agentConfig;

    public ProactiveController(QueuedActionRepository queuedActionRepository) {
        this(queuedActionRepository, null, null, null);
    }

    @Autowired
    public ProactiveController(QueuedActionRepository queuedActionRepository,
                               @Autowired(required = false) @Nullable TrustUpgradeService trustUpgradeService,
                               @Autowired(required = false) @Nullable AutonomyRepository autonomyRepository,
                               @Autowired(required = false) @Nullable AgentConfigProperties agentConfig) {
        this.queuedActionRepository = queuedActionRepository;
        this.trustUpgradeService = trustUpgradeService;
        this.autonomyRepository = autonomyRepository;
        this.agentConfig = agentConfig;
    }

    // ── 排队动作端点 ──

    /**
     * 查询用户未展示的排队动作列表。
     *
     * @param userId 用户 ID（默认 "default"）
     * @param limit  返回上限（默认 20，最大 50）
     * @return 排队动作列表
     */
    @GetMapping("/queue")
    public ApiResponse<List<QueuedActionDto>> listQueue(
            @RequestParam(defaultValue = "default") String userId,
            @RequestParam(defaultValue = "20") int limit) {

        int effectiveLimit = Math.clamp(limit, 1, 50);
        var records = queuedActionRepository.findPendingByUserId(userId, effectiveLimit);
        var dtos = records.stream().map(QueuedActionDto::from).toList();
        log.debug("查询排队动作: userId={}, limit={}, count={}", userId, effectiveLimit, dtos.size());
        return ApiResponse.ok(dtos);
    }

    /**
     * 标记排队动作为已展示。
     *
     * @param id 排队动作 ID
     * @return 空响应
     */
    @PutMapping("/queue/{id}/shown")
    public ApiResponse<Void> markShown(@PathVariable String id) {
        queuedActionRepository.markShown(id);
        log.info("标记排队动作已展示: id={}", id);
        return ApiResponse.ok();
    }

    /**
     * 删除排队动作。
     *
     * @param id 排队动作 ID
     * @return 空响应
     */
    @DeleteMapping("/queue/{id}")
    public ApiResponse<Void> deleteAction(@PathVariable String id) {
        int deleted = queuedActionRepository.deleteById(id);
        log.info("删除排队动作: id={}, affected={}", id, deleted);
        return ApiResponse.ok();
    }

    // ── 信任升级端点 ──

    /**
     * 查询用户所有行为的信任状态。
     *
     * @param userId 用户 ID（默认 "default"）
     * @return 信任状态列表
     */
    @GetMapping("/trust")
    public ApiResponse<List<TrustStatusDto>> listTrust(
            @RequestParam(defaultValue = "default") String userId) {

        if (autonomyRepository == null) {
            return ApiResponse.error(503, "信任升级服务不可用");
        }

        var configs = autonomyRepository.findAllByUserId(userId);
        // 以数据库记录为索引，方便后续合并默认行为
        var configMap = configs.stream()
                .collect(Collectors.toMap(AutonomyConfig::behaviorName, Function.identity()));

        List<TrustStatusDto> result = new ArrayList<>();
        for (var entry : TrustUpgradeService.DEFAULT_LEVELS.entrySet()) {
            String name = entry.getKey();
            AutonomyLevel defaultLevel = entry.getValue();
            var config = configMap.get(name);

            if (config != null) {
                result.add(toTrustStatusDto(config));
            } else {
                // 没有数据库记录，用默认值生成 DTO
                result.add(new TrustStatusDto(
                        name,
                        BEHAVIOR_LABELS.getOrDefault(name, name),
                        defaultLevel.name(),
                        nextLevel(defaultLevel),
                        false, 0, 0, null, Instant.now()));
            }
        }

        log.debug("查询信任状态: userId={}, count={}", userId, result.size());
        return ApiResponse.ok(result);
    }

    /**
     * 确认行为信任升级。
     *
     * @param behavior 行为插件名称
     * @param userId   用户 ID（默认 "default"）
     * @return 升级后的信任状态
     */
    @PostMapping("/trust/{behavior}/confirm")
    public ApiResponse<TrustStatusDto> confirmTrustUpgrade(
            @PathVariable String behavior,
            @RequestParam(defaultValue = "default") String userId) {

        if (trustUpgradeService == null || autonomyRepository == null) {
            return ApiResponse.error(503, "信任升级服务不可用");
        }

        boolean success = trustUpgradeService.confirmUpgrade(userId, behavior);
        if (!success) {
            log.info("信任升级确认失败: userId={}, behavior={}", userId, behavior);
            return ApiResponse.error(400, "升级条件不满足或当前无升级建议");
        }

        // 查询升级后的最新状态
        var config = autonomyRepository.findByUserAndBehavior(userId, behavior);
        if (config == null) {
            return ApiResponse.error(500, "升级成功但无法读取最新状态");
        }

        log.info("信任升级确认成功: userId={}, behavior={}, level={}", userId, behavior, config.autonomyLevel());
        return ApiResponse.ok(toTrustStatusDto(config));
    }

    // ── 配置端点 ──

    /**
     * 查询主动引擎配置。
     *
     * @param userId 用户 ID（默认 "default"）
     * @return 当前配置
     */
    @GetMapping("/config")
    public ApiResponse<ProactiveConfigDto> getConfig(
            @RequestParam(defaultValue = "default") String userId) {

        if (agentConfig == null) {
            return ApiResponse.error(503, "配置服务不可用");
        }

        log.debug("查询主动引擎配置: userId={}", userId);
        return ApiResponse.ok(buildConfigDto(userId));
    }

    /**
     * 更新主动引擎配置（运行时生效，不持久化到文件）。
     *
     * @param userId  用户 ID（默认 "default"）
     * @param request 配置更新请求
     * @return 更新后的配置
     */
    @PutMapping("/config")
    public ApiResponse<ProactiveConfigDto> updateConfig(
            @RequestParam(defaultValue = "default") String userId,
            @RequestBody ProactiveConfigUpdateRequest request) {

        if (agentConfig == null) {
            return ApiResponse.error(503, "配置服务不可用");
        }

        var taskConfig = agentConfig.getTask();

        // 更新全局配置（非 null 字段才覆盖）
        if (request.enabled() != null) {
            taskConfig.setProactiveReminderEnabled(request.enabled());
        }
        if (request.dailyMaxReminders() != null) {
            taskConfig.setProactiveReminderDailyMaxReminders(request.dailyMaxReminders());
        }
        if (request.quietHoursStart() != null) {
            taskConfig.setProactiveReminderQuietHoursStart(request.quietHoursStart());
        }
        if (request.quietHoursEnd() != null) {
            taskConfig.setProactiveReminderQuietHoursEnd(request.quietHoursEnd());
        }

        // 更新行为自主度
        if (request.behaviorOverrides() != null && autonomyRepository != null) {
            for (var override : request.behaviorOverrides()) {
                if (override.autonomyLevel() != null) {
                    AutonomyLevel level;
                    try {
                        level = AutonomyLevel.valueOf(override.autonomyLevel());
                    } catch (IllegalArgumentException e) {
                        log.warn("无效的自主度级别: behavior={}, level={}", override.name(), override.autonomyLevel());
                        continue;
                    }
                    var existing = autonomyRepository.findByUserAndBehavior(userId, override.name());
                    if (existing != null) {
                        autonomyRepository.upsert(new AutonomyConfig(
                                userId, override.name(), level,
                                existing.consecutivePositive(), existing.consecutiveNegative(),
                                false, existing.cooldownUntil(), Instant.now()));
                    } else {
                        autonomyRepository.upsert(AutonomyConfig.defaultFor(userId, override.name(), level));
                    }
                }
            }
        }

        log.info("更新主动引擎配置: userId={}", userId);
        return ApiResponse.ok(buildConfigDto(userId));
    }

    // ── 私有辅助方法 ──

    /** 构建信任状态 DTO。 */
    private TrustStatusDto toTrustStatusDto(AutonomyConfig config) {
        return new TrustStatusDto(
                config.behaviorName(),
                BEHAVIOR_LABELS.getOrDefault(config.behaviorName(), config.behaviorName()),
                config.autonomyLevel().name(),
                nextLevel(config.autonomyLevel()),
                config.upgradeSuggested(),
                config.consecutivePositive(),
                config.consecutiveNegative(),
                config.cooldownUntil(),
                config.updatedAt());
    }

    /** 计算下一自主度级别（C 级返回 null）。 */
    @Nullable
    private static String nextLevel(AutonomyLevel current) {
        return switch (current) {
            case A -> "B";
            case B -> "C";
            case C -> null;
        };
    }

    /** 构建配置 DTO（合并全局配置和行为自主度）。 */
    private ProactiveConfigDto buildConfigDto(String userId) {
        var taskConfig = agentConfig.getTask();

        // 构建行为配置列表
        List<ProactiveConfigDto.BehaviorConfigDto> behaviors = new ArrayList<>();
        Map<String, AutonomyConfig> configMap = Map.of();
        if (autonomyRepository != null) {
            configMap = autonomyRepository.findAllByUserId(userId).stream()
                    .collect(Collectors.toMap(AutonomyConfig::behaviorName, Function.identity()));
        }

        for (var entry : TrustUpgradeService.DEFAULT_LEVELS.entrySet()) {
            String name = entry.getKey();
            var config = configMap.get(name);
            String level = config != null ? config.autonomyLevel().name() : entry.getValue().name();
            behaviors.add(new ProactiveConfigDto.BehaviorConfigDto(
                    name,
                    BEHAVIOR_LABELS.getOrDefault(name, name),
                    level));
        }

        return new ProactiveConfigDto(
                taskConfig.isProactiveReminderEnabled(),
                taskConfig.getProactiveReminderDailyMaxReminders(),
                taskConfig.getProactiveReminderQuietHoursStart(),
                taskConfig.getProactiveReminderQuietHoursEnd(),
                taskConfig.getProactiveEngineGate2Threshold(),
                behaviors);
    }
}
