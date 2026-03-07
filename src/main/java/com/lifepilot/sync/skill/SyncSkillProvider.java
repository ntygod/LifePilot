package com.lifepilot.sync.skill;

import com.lifepilot.skill.builtin.BuiltinSkill;
import com.lifepilot.skill.builtin.BuiltinSkillProvider;
import com.lifepilot.skill.model.*;
import com.lifepilot.sync.config.SyncProperties;
import com.lifepilot.sync.connector.SyncConnector;
import com.lifepilot.sync.credential.CredentialStore;
import com.lifepilot.sync.engine.SyncEngine;
import com.lifepilot.sync.model.*;
import com.lifepilot.sync.repository.SyncConflictRepository;
import com.lifepilot.sync.repository.SyncProfileRepository;
import com.lifepilot.sync.repository.SyncRecordRepository;
import com.lifepilot.sync.repository.SyncStateRepository;
import com.lifepilot.sync.scheduler.SyncScheduler;
import com.lifepilot.prompt.PromptRegistry;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.lifepilot.tool.schema.JsonSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;

/**
 * 数据同步内置 Skill 提供者。
 *
 * <p>注册 4 个同步管理工具到 DynamicToolRegistry：
 * <ul>
 *   <li>sync-trigger — 触发即时同步</li>
 *   <li>sync-status — 查询同步状态</li>
 *   <li>sync-config — 同步配置 CRUD</li>
 *   <li>sync-conflicts — 冲突查看与解决</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-02-26
 */
@BuiltinSkill(id = "sync", order = 5)
public class SyncSkillProvider implements BuiltinSkillProvider {

    private static final Logger log = LoggerFactory.getLogger(SyncSkillProvider.class);

    private final SyncEngine syncEngine;
    private final SyncScheduler syncScheduler;
    private final SyncProfileRepository profileRepository;
    private final SyncStateRepository stateRepository;
    private final SyncConflictRepository conflictRepository;
    private final SyncRecordRepository recordRepository;
    private final CredentialStore credentialStore;
    private final Map<String, SyncConnector> connectors;
    private final SyncProperties properties;
    private final PromptRegistry promptRegistry;

    public SyncSkillProvider(SyncEngine syncEngine,
                             SyncScheduler syncScheduler,
                             SyncProfileRepository profileRepository,
                             SyncStateRepository stateRepository,
                             SyncConflictRepository conflictRepository,
                             SyncRecordRepository recordRepository,
                             CredentialStore credentialStore,
                             Map<String, SyncConnector> connectors,
                             SyncProperties properties,
                             PromptRegistry promptRegistry) {
        this.syncEngine = syncEngine;
        this.syncScheduler = syncScheduler;
        this.profileRepository = profileRepository;
        this.stateRepository = stateRepository;
        this.conflictRepository = conflictRepository;
        this.recordRepository = recordRepository;
        this.credentialStore = credentialStore;
        this.connectors = Map.copyOf(connectors);
        this.properties = properties;
        this.promptRegistry = promptRegistry;
    }

    @Override
    public SkillDefinition provide() {
        return SkillDefinition.builder()
                .id("sync")
                .name("数据同步")
                .description("管理外部数据源同步：触发同步、查看状态、管理配置、解决冲突")
                .version("1.0.0")
                .source(new SkillSource.Builtin())
                .instructions(promptRegistry.render("skill/sync"))
                .suggestedTools(List.of(
                        "builtin.sync.trigger",
                        "builtin.sync.status",
                        "builtin.sync.config",
                        "builtin.sync.conflicts"
                ))
                .metadata(Map.of())
                .build();
    }

    @Override
    public void registerTools(DynamicToolRegistry toolRegistry) {
        toolRegistry.registerBuiltinTool(buildTriggerTool());
        toolRegistry.registerBuiltinTool(buildStatusTool());
        toolRegistry.registerBuiltinTool(buildConfigTool());
        toolRegistry.registerBuiltinTool(buildConflictsTool());
        log.info("同步 Skill 工具注册完成: count=4");
    }

    // ---- 工具构建方法 ----

    /** 构建触发同步工具。 */
    private BuiltinTool buildTriggerTool() {
        return BuiltinTool.builder()
                .id("builtin.sync.trigger")
                .name("触发同步")
                .description("触发指定同步配置的即时同步，返回同步结果")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("profileId"),
                        "properties", Map.of(
                                "profileId", Map.of("type", "string", "description", "同步配置 ID")
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .executor(input -> {
                    try {
                        String profileId = input.getParam("profileId", String.class);
                        Optional<SyncProfile> profileOpt = profileRepository.findById(profileId);
                        if (profileOpt.isEmpty()) {
                            return ToolResult.error("同步配置不存在: profileId=" + profileId);
                        }
                        SyncResult result = syncEngine.sync(profileOpt.get());
                        return ToolResult.success(syncResultToMap(result));
                    } catch (Exception e) {
                        log.error("触发同步失败: {}", e.getMessage(), e);
                        return ToolResult.error("触发同步失败: " + e.getMessage());
                    }
                })
                .build();
    }

    /** 构建查询同步状态工具。 */
    private BuiltinTool buildStatusTool() {
        return BuiltinTool.builder()
                .id("builtin.sync.status")
                .name("查询同步状态")
                .description("查询同步配置的同步状态，支持查询单个或所有启用的配置")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "profileId", Map.of("type", "string", "description", "同步配置 ID（可选，不提供则查询所有启用的配置）")
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .executor(input -> {
                    try {
                        Optional<String> profileIdOpt = input.getOptionalParam("profileId", String.class);
                        if (profileIdOpt.isPresent()) {
                            return querySingleProfileStatus(profileIdOpt.get());
                        }
                        return queryAllEnabledProfileStatus();
                    } catch (Exception e) {
                        log.error("查询同步状态失败: {}", e.getMessage(), e);
                        return ToolResult.error("查询同步状态失败: " + e.getMessage());
                    }
                })
                .build();
    }

    /** 构建同步配置管理工具。 */
    private BuiltinTool buildConfigTool() {
        return BuiltinTool.builder()
                .id("builtin.sync.config")
                .name("同步配置管理")
                .description("管理同步配置：list 查看所有 / create 创建 / update 更新 / delete 删除 / test 测试连接")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("action"),
                        "properties", Map.of(
                                "action", Map.of("type", "string", "description", "操作类型: list / create / update / delete / test"),
                                "profileId", Map.of("type", "string", "description", "配置 ID（update / delete / test 时必填）"),
                                "name", Map.of("type", "string", "description", "配置名称（create 时必填）"),
                                "connectorType", Map.of("type", "string", "description", "连接器类型: caldav / todoist / dida / obsidian（create 时必填）"),
                                "connectionParamsJson", Map.of("type", "string", "description", "连接参数 JSON"),
                                "syncDirection", Map.of("type", "string", "description", "同步方向: BIDIRECTIONAL / PULL_ONLY / PUSH_ONLY"),
                                "conflictPolicy", Map.of("type", "string", "description", "冲突策略: LAST_WRITE_WINS / REMOTE_WINS / LOCAL_WINS / USER_CONFIRM"),
                                "cronExpression", Map.of("type", "string", "description", "Cron 调度表达式"),
                                "enabled", Map.of("type", "boolean", "description", "是否启用"),
                                "dataTypeFilterJson", Map.of("type", "string", "description", "数据类型过滤 JSON 数组")
                        )
                )))
                .riskLevel(RiskLevel.MEDIUM)
                .executor(input -> {
                    try {
                        String action = input.getParam("action", String.class);
                        return switch (action.toLowerCase()) {
                            case "list" -> handleConfigList();
                            case "create" -> handleConfigCreate(input);
                            case "update" -> handleConfigUpdate(input);
                            case "delete" -> handleConfigDelete(input);
                            case "test" -> handleConfigTest(input);
                            default -> ToolResult.error("不支持的操作: " + action);
                        };
                    } catch (Exception e) {
                        log.error("同步配置操作失败: {}", e.getMessage(), e);
                        return ToolResult.error("同步配置操作失败: " + e.getMessage());
                    }
                })
                .build();
    }

    /** 构建冲突管理工具。 */
    private BuiltinTool buildConflictsTool() {
        return BuiltinTool.builder()
                .id("builtin.sync.conflicts")
                .name("同步冲突管理")
                .description("查看未解决的同步冲突或手动解决冲突")
                .inputSchema(JsonSchema.of(Map.of(
                        "type", "object",
                        "required", List.of("action"),
                        "properties", Map.of(
                                "action", Map.of("type", "string", "description", "操作类型: list / resolve"),
                                "profileId", Map.of("type", "string", "description", "同步配置 ID（list 时必填）"),
                                "conflictId", Map.of("type", "string", "description", "冲突 ID（resolve 时必填）")
                        )
                )))
                .riskLevel(RiskLevel.LOW)
                .executor(input -> {
                    try {
                        String action = input.getParam("action", String.class);
                        return switch (action.toLowerCase()) {
                            case "list" -> handleConflictsList(input);
                            case "resolve" -> handleConflictsResolve(input);
                            default -> ToolResult.error("不支持的操作: " + action);
                        };
                    } catch (Exception e) {
                        log.error("同步冲突操作失败: {}", e.getMessage(), e);
                        return ToolResult.error("同步冲突操作失败: " + e.getMessage());
                    }
                })
                .build();
    }

    // ---- sync-status 辅助方法 ----

    /** 查询单个 profile 的同步状态。 */
    private ToolResult querySingleProfileStatus(String profileId) {
        Optional<SyncProfile> profileOpt = profileRepository.findById(profileId);
        if (profileOpt.isEmpty()) {
            return ToolResult.error("同步配置不存在: profileId=" + profileId);
        }
        SyncProfile profile = profileOpt.get();
        Optional<SyncState> stateOpt = stateRepository.findByProfileId(profileId);
        Map<String, Object> statusMap = buildStatusMap(profile, stateOpt.orElse(null));
        return ToolResult.success(Map.of("statuses", List.of(statusMap)));
    }

    /** 查询所有启用 profile 的同步状态。 */
    private ToolResult queryAllEnabledProfileStatus() {
        List<SyncProfile> enabledProfiles = profileRepository.findAllEnabled();
        List<Map<String, Object>> statuses = enabledProfiles.stream()
                .map(profile -> {
                    Optional<SyncState> stateOpt = stateRepository.findByProfileId(profile.id());
                    return buildStatusMap(profile, stateOpt.orElse(null));
                })
                .toList();
        return ToolResult.success(Map.of("statuses", statuses));
    }

    /** 构建单个 profile 的状态 Map。 */
    private Map<String, Object> buildStatusMap(SyncProfile profile, SyncState state) {
        var map = new HashMap<String, Object>();
        map.put("profileId", profile.id());
        map.put("name", profile.name());
        map.put("connectorType", profile.connectorType());
        map.put("enabled", profile.enabled());
        if (state != null) {
            if (state.lastSyncAt() != null) map.put("lastSyncAt", state.lastSyncAt());
            map.put("lastSyncStatus", state.lastSyncStatus().name());
            if (state.lastErrorMessage() != null) map.put("lastErrorMessage", state.lastErrorMessage());
        } else {
            map.put("lastSyncStatus", "NEVER_SYNCED");
        }
        return Map.copyOf(map);
    }

    // ---- sync-config 辅助方法 ----

    /** 列出所有同步配置。 */
    private ToolResult handleConfigList() {
        List<SyncProfile> profiles = profileRepository.findAll();
        List<Map<String, Object>> profileMaps = profiles.stream()
                .map(this::profileToMap)
                .toList();
        return ToolResult.success(Map.of("profiles", profileMaps));
    }

    /** 创建同步配置。 */
    private ToolResult handleConfigCreate(com.lifepilot.tool.model.ToolInput input) {
        String name = input.getParam("name", String.class);
        String connectorType = input.getParam("connectorType", String.class);
        String connectionParamsJson = input.getOptionalParam("connectionParamsJson", String.class).orElse("{}");
        String syncDirectionStr = input.getOptionalParam("syncDirection", String.class)
                .orElse(SyncDirection.BIDIRECTIONAL.name());
        String conflictPolicyStr = input.getOptionalParam("conflictPolicy", String.class)
                .orElse(properties.getDefaultConflictPolicy().name());
        String cronExpression = input.getOptionalParam("cronExpression", String.class)
                .orElse(properties.getDefaultCron());
        // ToolInput 中 boolean 可能以 Boolean 或 String 形式传入
        boolean enabled = input.getOptionalParam("enabled", Boolean.class).orElse(true);
        String dataTypeFilterJson = input.getOptionalParam("dataTypeFilterJson", String.class).orElse(null);

        String id = UUID.randomUUID().toString();
        String now = Instant.now().toString();

        SyncProfile profile = SyncProfile.builder()
                .id(id)
                .name(name)
                .connectorType(connectorType)
                .connectionParamsJson(connectionParamsJson)
                .syncDirection(SyncDirection.valueOf(syncDirectionStr.toUpperCase()))
                .conflictPolicy(ConflictPolicy.valueOf(conflictPolicyStr.toUpperCase()))
                .cronExpression(cronExpression)
                .enabled(enabled)
                .dataTypeFilterJson(dataTypeFilterJson)
                .createdAt(now)
                .updatedAt(now)
                .build();

        profileRepository.create(profile);
        syncScheduler.refreshSchedule(id);
        log.info("同步配置创建成功: id={}, name={}", id, name);
        return ToolResult.success(Map.of("id", id));
    }

    /** 更新同步配置。 */
    private ToolResult handleConfigUpdate(com.lifepilot.tool.model.ToolInput input) {
        String profileId = input.getParam("profileId", String.class);
        Optional<SyncProfile> existingOpt = profileRepository.findById(profileId);
        if (existingOpt.isEmpty()) {
            return ToolResult.error("同步配置不存在: profileId=" + profileId);
        }
        SyncProfile existing = existingOpt.get();

        // 使用 toBuilder 模式，仅更新提供的字段
        var builder = existing.toBuilder();
        input.getOptionalParam("name", String.class).ifPresent(builder::name);
        input.getOptionalParam("connectorType", String.class).ifPresent(builder::connectorType);
        input.getOptionalParam("connectionParamsJson", String.class).ifPresent(builder::connectionParamsJson);
        input.getOptionalParam("syncDirection", String.class)
                .ifPresent(v -> builder.syncDirection(SyncDirection.valueOf(v.toUpperCase())));
        input.getOptionalParam("conflictPolicy", String.class)
                .ifPresent(v -> builder.conflictPolicy(ConflictPolicy.valueOf(v.toUpperCase())));
        input.getOptionalParam("cronExpression", String.class).ifPresent(builder::cronExpression);
        input.getOptionalParam("enabled", Boolean.class).ifPresent(builder::enabled);
        input.getOptionalParam("dataTypeFilterJson", String.class).ifPresent(builder::dataTypeFilterJson);
        builder.updatedAt(Instant.now().toString());

        SyncProfile updated = builder.build();
        profileRepository.update(updated);
        syncScheduler.refreshSchedule(profileId);
        log.info("同步配置更新成功: id={}", profileId);
        return ToolResult.success(Map.of("updated", true));
    }

    /** 删除同步配置及关联数据。 */
    private ToolResult handleConfigDelete(com.lifepilot.tool.model.ToolInput input) {
        String profileId = input.getParam("profileId", String.class);
        Optional<SyncProfile> existingOpt = profileRepository.findById(profileId);
        if (existingOpt.isEmpty()) {
            return ToolResult.error("同步配置不存在: profileId=" + profileId);
        }

        // 删除关联数据：凭证、同步记录，然后删除配置本身（sync_conflicts 通过 FK CASCADE 自动删除）
        credentialStore.deleteByProfileId(profileId);
        recordRepository.deleteByProfileId(profileId);
        profileRepository.delete(profileId);
        syncScheduler.refreshSchedule(profileId);
        log.info("同步配置及关联数据删除成功: id={}", profileId);
        return ToolResult.success(Map.of("deleted", true));
    }

    /** 测试同步配置的连接。 */
    private ToolResult handleConfigTest(com.lifepilot.tool.model.ToolInput input) {
        String profileId = input.getParam("profileId", String.class);
        Optional<SyncProfile> profileOpt = profileRepository.findById(profileId);
        if (profileOpt.isEmpty()) {
            return ToolResult.error("同步配置不存在: profileId=" + profileId);
        }
        SyncProfile profile = profileOpt.get();

        SyncConnector connector = connectors.get(profile.connectorType());
        if (connector == null) {
            return ToolResult.error("未找到连接器: type=" + profile.connectorType());
        }

        ConnectionTestResult testResult = connector.testConnection(profile);
        var map = new HashMap<String, Object>();
        map.put("success", testResult.success());
        map.put("responseTimeMs", testResult.responseTimeMs());
        if (testResult.errorMessage() != null) {
            map.put("errorMessage", testResult.errorMessage());
        }
        return ToolResult.success(Map.copyOf(map));
    }

    // ---- sync-conflicts 辅助方法 ----

    /** 列出指定 profile 的未解决冲突。 */
    private ToolResult handleConflictsList(com.lifepilot.tool.model.ToolInput input) {
        String profileId = input.getParam("profileId", String.class);
        List<SyncConflict> conflicts = conflictRepository.findUnresolvedByProfileId(profileId);
        List<Map<String, Object>> conflictMaps = conflicts.stream()
                .map(this::conflictToMap)
                .toList();
        return ToolResult.success(Map.of("conflicts", conflictMaps));
    }

    /** 解决指定冲突。 */
    private ToolResult handleConflictsResolve(com.lifepilot.tool.model.ToolInput input) {
        String conflictId = input.getParam("conflictId", String.class);
        conflictRepository.resolve(conflictId);
        log.info("同步冲突已解决: conflictId={}", conflictId);
        return ToolResult.success(Map.of("resolved", true));
    }

    // ---- 数据转换辅助方法 ----

    /** 将 SyncResult 转换为 Map。 */
    private Map<String, Object> syncResultToMap(SyncResult result) {
        var map = new HashMap<String, Object>();
        map.put("profileId", result.profileId());
        map.put("pulledCount", result.pulledCount());
        map.put("pushedCount", result.pushedCount());
        map.put("conflictsDetected", result.conflictsDetected());
        map.put("conflictsResolved", result.conflictsResolved());
        map.put("status", result.status().name());
        if (result.errorMessage() != null) map.put("errorMessage", result.errorMessage());
        map.put("syncedAt", result.syncedAt());
        return Map.copyOf(map);
    }

    /** 将 SyncProfile 转换为 Map。 */
    private Map<String, Object> profileToMap(SyncProfile profile) {
        var map = new HashMap<String, Object>();
        map.put("id", profile.id());
        map.put("name", profile.name());
        map.put("connectorType", profile.connectorType());
        map.put("syncDirection", profile.syncDirection().name());
        map.put("conflictPolicy", profile.conflictPolicy().name());
        map.put("cronExpression", profile.cronExpression());
        map.put("enabled", profile.enabled());
        if (profile.dataTypeFilterJson() != null) map.put("dataTypeFilterJson", profile.dataTypeFilterJson());
        map.put("createdAt", profile.createdAt());
        map.put("updatedAt", profile.updatedAt());
        return Map.copyOf(map);
    }

    /** 将 SyncConflict 转换为 Map。 */
    private Map<String, Object> conflictToMap(SyncConflict conflict) {
        var map = new HashMap<String, Object>();
        map.put("id", conflict.id());
        map.put("profileId", conflict.profileId());
        map.put("localEntityType", conflict.localEntityType());
        map.put("localEntityId", conflict.localEntityId());
        map.put("localSnapshotJson", conflict.localSnapshotJson());
        map.put("remoteSnapshotJson", conflict.remoteSnapshotJson());
        map.put("status", conflict.status().name());
        if (conflict.resolvedAt() != null) map.put("resolvedAt", conflict.resolvedAt());
        map.put("createdAt", conflict.createdAt());
        return Map.copyOf(map);
    }
}
