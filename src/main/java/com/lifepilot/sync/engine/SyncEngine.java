package com.lifepilot.sync.engine;

import com.lifepilot.memory.episodic.CompressionLevel;
import com.lifepilot.memory.episodic.ConversationRecord;
import com.lifepilot.memory.episodic.EpisodicMemory;
import com.lifepilot.memory.episodic.MessageRecord;
import com.lifepilot.skill.builtin.habit.HabitItem;
import com.lifepilot.skill.builtin.habit.HabitRepository;
import com.lifepilot.skill.builtin.schedule.ScheduleItem;
import com.lifepilot.skill.builtin.schedule.ScheduleRepository;
import com.lifepilot.skill.builtin.todo.TodoItem;
import com.lifepilot.skill.builtin.todo.TodoRepository;
import com.lifepilot.sync.config.SyncProperties;
import com.lifepilot.sync.connector.SyncConnector;
import com.lifepilot.sync.model.*;
import com.lifepilot.sync.model.LocalChangeSet.LocalEntity;
import com.lifepilot.sync.model.RemoteChangeSet.RemoteEntity;
import com.lifepilot.sync.repository.SyncConflictRepository;
import com.lifepilot.sync.repository.SyncRecordRepository;
import com.lifepilot.sync.repository.SyncStateRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;

/**
 * 同步引擎 — 协调本地数据与远程数据的双向同步流程。
 *
 * <p>完整双向流程：fetchRemote → detectLocal → resolveConflicts → applyRemote → pushLocal → updateState。
 * 按 {@link SyncDirection} 分支执行不同阶段组合：
 * <ul>
 *   <li>{@code BIDIRECTIONAL} — 完整双向流程</li>
 *   <li>{@code PULL_ONLY} — 跳过 pushLocal 阶段</li>
 *   <li>{@code PUSH_ONLY} — 跳过 fetchRemote 阶段</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-02-26
 */
public class SyncEngine {

    private static final Logger log = LoggerFactory.getLogger(SyncEngine.class);

    private static final String TYPE_TODO = "TodoItem";
    private static final String TYPE_SCHEDULE = "ScheduleItem";
    private static final String TYPE_HABIT = "HabitItem";

    private final Map<String, SyncConnector> connectors;
    private final ChangeDetector changeDetector;
    private final ConflictResolver conflictResolver;
    private final SyncRecordRepository syncRecordRepository;
    private final SyncStateRepository syncStateRepository;
    private final SyncConflictRepository syncConflictRepository;
    private final TodoRepository todoRepository;
    private final ScheduleRepository scheduleRepository;
    private final HabitRepository habitRepository;
    private final EpisodicMemory episodicMemory;
    private final SyncProperties properties;

    public SyncEngine(Map<String, SyncConnector> connectors,
                      ChangeDetector changeDetector,
                      ConflictResolver conflictResolver,
                      SyncRecordRepository syncRecordRepository,
                      SyncStateRepository syncStateRepository,
                      SyncConflictRepository syncConflictRepository,
                      TodoRepository todoRepository,
                      ScheduleRepository scheduleRepository,
                      HabitRepository habitRepository,
                      EpisodicMemory episodicMemory,
                      SyncProperties properties) {
        this.connectors = Map.copyOf(connectors);
        this.changeDetector = changeDetector;
        this.conflictResolver = conflictResolver;
        this.syncRecordRepository = syncRecordRepository;
        this.syncStateRepository = syncStateRepository;
        this.syncConflictRepository = syncConflictRepository;
        this.todoRepository = todoRepository;
        this.scheduleRepository = scheduleRepository;
        this.habitRepository = habitRepository;
        this.episodicMemory = episodicMemory;
        this.properties = properties;
    }

    /**
     * 执行一次完整同步。
     *
     * @param profile 同步配置
     * @return 同步结果
     */
    public SyncResult sync(SyncProfile profile) {
        log.info("开始同步: profileId={}, name={}, direction={}, connector={}",
                profile.id(), profile.name(), profile.syncDirection(), profile.connectorType());

        Instant syncStartTime = Instant.now();

        // 查找连接器
        SyncConnector connector = connectors.get(profile.connectorType());
        if (connector == null) {
            log.error("未找到连接器: type={}", profile.connectorType());
            return buildFailedResult(profile, syncStartTime, "未找到连接器: " + profile.connectorType());
        }

        // 获取当前同步状态（syncToken）
        Optional<SyncState> currentState = syncStateRepository.findByProfileId(profile.id());
        String syncToken = currentState.map(SyncState::syncToken).orElse(null);

        return switch (profile.syncDirection()) {
            case BIDIRECTIONAL -> executeBidirectional(profile, connector, syncToken, syncStartTime);
            case PULL_ONLY -> executePullOnly(profile, connector, syncToken, syncStartTime);
            case PUSH_ONLY -> executePushOnly(profile, connector, syncStartTime);
        };
    }

    // ---- 按方向分支的执行方法 ----

    /**
     * 双向同步：fetchRemote → detectLocal → resolveConflicts → applyRemote → pushLocal → updateState。
     */
    private SyncResult executeBidirectional(SyncProfile profile, SyncConnector connector,
                                            String syncToken, Instant syncStartTime) {
        // 1. fetchRemote
        RemoteChangeSet remoteChanges;
        try {
            remoteChanges = connector.fetchChanges(profile, syncToken);
            log.debug("远程变更拉取完成: profileId={}, created={}, updated={}, deleted={}",
                    profile.id(), remoteChanges.created().size(),
                    remoteChanges.updated().size(), remoteChanges.deletedRemoteIds().size());
        } catch (Exception e) {
            log.error("远程变更拉取失败: profileId={}, 错误={}", profile.id(), e.getMessage(), e);
            return handleFetchFailure(profile, syncStartTime, e);
        }

        // 2. detectLocal
        LocalChangeSet localChanges = changeDetector.detectLocalChanges(profile);

        // 3. resolveConflicts
        ConflictResolution resolution = conflictResolver.detectAndResolve(
                remoteChanges, localChanges, profile.conflictPolicy(), profile.id());

        int conflictsDetected = countConflicts(remoteChanges, localChanges, profile.id());
        int conflictsResolved = conflictsDetected - resolution.unresolvedConflicts().size();

        // 4. applyRemote
        int pulledCount = applyRemoteChanges(profile, resolution.resolvedRemoteChanges(),
                remoteChanges.deletedRemoteIds(), syncStartTime);

        // 5. pushLocal
        PushResult pushResult;
        try {
            List<SyncOperation> operations = buildPushOperations(
                    profile, resolution.resolvedLocalChanges(), localChanges.deleted());
            if (!operations.isEmpty()) {
                pushResult = connector.pushChanges(profile, operations);
                updateSyncRecordsAfterPush(profile, resolution.resolvedLocalChanges(),
                        pushResult, syncStartTime);
            } else {
                pushResult = new PushResult(0, 0, List.of());
            }
        } catch (Exception e) {
            log.error("本地变更推送失败: profileId={}, 错误={}", profile.id(), e.getMessage(), e);
            SyncResult partialResult = new SyncResult(profile.id(), pulledCount, 0,
                    conflictsDetected, conflictsResolved, SyncStatus.PARTIAL,
                    e.getMessage(), syncStartTime.toString());
            updateSyncState(profile, remoteChanges.newSyncToken(), syncStartTime,
                    SyncStatus.PARTIAL, e.getMessage());
            writeSyncEvent(profile, partialResult);
            return partialResult;
        }

        // 6. updateState
        SyncStatus status = pushResult.failureCount() > 0 ? SyncStatus.PARTIAL : SyncStatus.SUCCESS;
        String errorMsg = pushResult.failureCount() > 0
                ? "推送部分失败: 成功=%d, 失败=%d".formatted(pushResult.successCount(), pushResult.failureCount())
                : null;

        updateSyncState(profile, remoteChanges.newSyncToken(), syncStartTime, status, errorMsg);

        SyncResult result = new SyncResult(profile.id(), pulledCount, pushResult.successCount(),
                conflictsDetected, conflictsResolved, status, errorMsg, syncStartTime.toString());

        writeSyncEvent(profile, result);
        log.info("同步完成: profileId={}, pulled={}, pushed={}, conflicts={}, status={}",
                profile.id(), pulledCount, pushResult.successCount(), conflictsDetected, status);
        return result;
    }

    /**
     * 仅拉取：fetchRemote → applyRemote → updateState。
     */
    private SyncResult executePullOnly(SyncProfile profile, SyncConnector connector,
                                       String syncToken, Instant syncStartTime) {
        // 1. fetchRemote
        RemoteChangeSet remoteChanges;
        try {
            remoteChanges = connector.fetchChanges(profile, syncToken);
        } catch (Exception e) {
            log.error("远程变更拉取失败: profileId={}, 错误={}", profile.id(), e.getMessage(), e);
            return handleFetchFailure(profile, syncStartTime, e);
        }

        // 2. applyRemote（无冲突解决，直接应用所有远程变更）
        List<RemoteEntity> allRemoteChanges = new ArrayList<>();
        allRemoteChanges.addAll(remoteChanges.created());
        allRemoteChanges.addAll(remoteChanges.updated());
        int pulledCount = applyRemoteChanges(profile, allRemoteChanges,
                remoteChanges.deletedRemoteIds(), syncStartTime);

        // 3. updateState
        updateSyncState(profile, remoteChanges.newSyncToken(), syncStartTime, SyncStatus.SUCCESS, null);

        SyncResult result = new SyncResult(profile.id(), pulledCount, 0,
                0, 0, SyncStatus.SUCCESS, null, syncStartTime.toString());

        writeSyncEvent(profile, result);
        log.info("拉取同步完成: profileId={}, pulled={}", profile.id(), pulledCount);
        return result;
    }

    /**
     * 仅推送：detectLocal → pushLocal → updateState。
     */
    private SyncResult executePushOnly(SyncProfile profile, SyncConnector connector,
                                       Instant syncStartTime) {
        // 1. detectLocal
        LocalChangeSet localChanges = changeDetector.detectLocalChanges(profile);

        // 2. pushLocal
        PushResult pushResult;
        try {
            List<SyncOperation> operations = buildPushOperationsFromLocalChangeSet(
                    profile, localChanges);
            if (!operations.isEmpty()) {
                pushResult = connector.pushChanges(profile, operations);
                updateSyncRecordsAfterPush(profile, localChanges.created(),
                        localChanges.updated(), pushResult, syncStartTime);
            } else {
                pushResult = new PushResult(0, 0, List.of());
            }
        } catch (Exception e) {
            log.error("本地变更推送失败: profileId={}, 错误={}", profile.id(), e.getMessage(), e);
            SyncResult failedResult = buildFailedResult(profile, syncStartTime, e.getMessage());
            updateSyncState(profile, null, syncStartTime, SyncStatus.FAILED, e.getMessage());
            writeSyncEvent(profile, failedResult);
            return failedResult;
        }

        // 3. updateState
        SyncStatus status = pushResult.failureCount() > 0 ? SyncStatus.PARTIAL : SyncStatus.SUCCESS;
        String errorMsg = pushResult.failureCount() > 0
                ? "推送部分失败: 成功=%d, 失败=%d".formatted(pushResult.successCount(), pushResult.failureCount())
                : null;

        updateSyncState(profile, null, syncStartTime, status, errorMsg);

        SyncResult result = new SyncResult(profile.id(), 0, pushResult.successCount(),
                0, 0, status, errorMsg, syncStartTime.toString());

        writeSyncEvent(profile, result);
        log.info("推送同步完成: profileId={}, pushed={}, status={}",
                profile.id(), pushResult.successCount(), status);
        return result;
    }

    // ---- 远程变更应用 ----

    /**
     * 将远程变更应用到本地数据库，并更新 SyncRecord。
     *
     * @param profile        同步配置
     * @param remoteEntities 需要应用的远程实体列表（新增 + 更新）
     * @param deletedIds     远程已删除的实体 ID 列表
     * @param syncTime       同步时间
     * @return 成功应用的实体数量
     */
    private int applyRemoteChanges(SyncProfile profile,
                                    List<RemoteEntity> remoteEntities,
                                    List<String> deletedIds,
                                    Instant syncTime) {
        int appliedCount = 0;
        String now = syncTime.toString();

        // 应用新增和更新
        for (RemoteEntity remote : remoteEntities) {
            try {
                applyRemoteEntity(profile, remote, now);
                appliedCount++;
            } catch (Exception e) {
                log.warn("远程实体应用失败: profileId={}, remoteId={}, 错误={}",
                        profile.id(), remote.remoteId(), e.getMessage());
            }
        }

        // 应用删除
        for (String deletedRemoteId : deletedIds) {
            try {
                applyRemoteDeletion(profile, deletedRemoteId);
                appliedCount++;
            } catch (Exception e) {
                log.warn("远程删除应用失败: profileId={}, remoteId={}, 错误={}",
                        profile.id(), deletedRemoteId, e.getMessage());
            }
        }

        return appliedCount;
    }

    /**
     * 应用单个远程实体到本地（新增或更新）。
     */
    private void applyRemoteEntity(SyncProfile profile, RemoteEntity remote, String syncTime) {
        Optional<SyncRecord> existingRecord = syncRecordRepository.findByRemoteEntity(
                profile.id(), remote.remoteId());

        if (existingRecord.isPresent()) {
            // 更新已有本地实体
            SyncRecord record = existingRecord.get();
            updateLocalEntity(record.localEntityType(), record.localEntityId(), remote.fields());
            upsertSyncRecord(profile.id(), record.localEntityType(), record.localEntityId(),
                    remote.remoteId(), remote.etag(), remote.updatedAt(), syncTime);
        } else {
            // 创建新本地实体
            String localId = createLocalEntity(remote.entityType(), remote.fields());
            if (localId != null) {
                upsertSyncRecord(profile.id(), remote.entityType(), localId,
                        remote.remoteId(), remote.etag(), remote.updatedAt(), syncTime);
            }
        }
    }

    /**
     * 应用远程删除：找到 SyncRecord，删除本地实体，删除该条 SyncRecord。
     */
    private void applyRemoteDeletion(SyncProfile profile, String deletedRemoteId) {
        Optional<SyncRecord> record = syncRecordRepository.findByRemoteEntity(
                profile.id(), deletedRemoteId);
        if (record.isPresent()) {
            SyncRecord sr = record.get();
            deleteLocalEntity(sr.localEntityType(), sr.localEntityId());
            syncRecordRepository.deleteById(sr.id());
            log.debug("远程删除已应用: profileId={}, remoteId={}, localType={}, localId={}",
                    profile.id(), deletedRemoteId, sr.localEntityType(), sr.localEntityId());
        }
    }

    // ---- 本地实体 CRUD 辅助方法 ----

    /**
     * 从远程字段 Map 创建本地实体，返回生成的本地 ID。
     */
    private String createLocalEntity(String entityType, Map<String, Object> fields) {
        return switch (entityType) {
            case TYPE_TODO -> {
                TodoItem item = buildTodoItemFromFields(fields);
                yield todoRepository.create(item);
            }
            case TYPE_SCHEDULE -> {
                ScheduleItem item = buildScheduleItemFromFields(fields);
                yield scheduleRepository.create(item);
            }
            case TYPE_HABIT -> {
                HabitItem item = buildHabitItemFromFields(fields);
                yield habitRepository.create(item);
            }
            default -> {
                log.warn("不支持的实体类型: {}", entityType);
                yield null;
            }
        };
    }

    /**
     * 根据远程字段 Map 更新本地实体。
     */
    private void updateLocalEntity(String entityType, String localId, Map<String, Object> fields) {
        switch (entityType) {
            case TYPE_TODO -> {
                TodoItem item = buildTodoItemFromFields(fields);
                todoRepository.update(localId, item);
            }
            case TYPE_SCHEDULE -> {
                ScheduleItem item = buildScheduleItemFromFields(fields);
                scheduleRepository.update(localId, item);
            }
            case TYPE_HABIT -> {
                HabitItem item = buildHabitItemFromFields(fields);
                habitRepository.update(localId, item);
            }
            default -> log.warn("不支持的实体类型: {}", entityType);
        }
    }

    /**
     * 删除本地实体。
     */
    private void deleteLocalEntity(String entityType, String localId) {
        switch (entityType) {
            case TYPE_TODO -> todoRepository.delete(localId);
            case TYPE_SCHEDULE -> scheduleRepository.delete(localId);
            // HabitRepository 没有 delete 方法，跳过
            case TYPE_HABIT -> log.warn("HabitItem 不支持删除: localId={}", localId);
            default -> log.warn("不支持的实体类型: {}", entityType);
        }
    }

    /**
     * 从远程字段 Map 构建 TodoItem。
     */
    private TodoItem buildTodoItemFromFields(Map<String, Object> fields) {
        String now = Instant.now().toString();
        return new TodoItem(
                UUID.randomUUID().toString(),
                getStringField(fields, "title", ""),
                getStringField(fields, "description", null),
                parseEnum(fields.get("priority"), TodoItem.Priority.class, TodoItem.Priority.MEDIUM),
                parseEnum(fields.get("status"), TodoItem.Status.class, TodoItem.Status.PENDING),
                getStringField(fields, "dueDate", null),
                parseStringList(fields.get("tags")),
                now, now
        );
    }

    /**
     * 从远程字段 Map 构建 ScheduleItem。
     */
    private ScheduleItem buildScheduleItemFromFields(Map<String, Object> fields) {
        String now = Instant.now().toString();
        return new ScheduleItem(
                UUID.randomUUID().toString(),
                getStringField(fields, "title", ""),
                getStringField(fields, "startTime", now),
                getStringField(fields, "endTime", now),
                getStringField(fields, "location", null),
                getStringField(fields, "notes", null),
                now, now
        );
    }

    /**
     * 从远程字段 Map 构建 HabitItem。
     */
    private HabitItem buildHabitItemFromFields(Map<String, Object> fields) {
        String now = Instant.now().toString();
        return new HabitItem(
                UUID.randomUUID().toString(),
                getStringField(fields, "name", ""),
                parseEnum(fields.get("frequency"), HabitItem.Frequency.class, HabitItem.Frequency.DAILY),
                getStringField(fields, "targetTime", null),
                0,
                now, now
        );
    }

    // ---- 推送操作构建 ----

    /**
     * 从冲突解决后的本地变更和删除列表构建推送操作（双向同步用）。
     */
    private List<SyncOperation> buildPushOperations(SyncProfile profile,
                                                     List<LocalEntity> resolvedLocalChanges,
                                                     List<LocalEntity> deletedEntities) {
        List<SyncOperation> operations = new ArrayList<>();

        for (LocalEntity local : resolvedLocalChanges) {
            Optional<SyncRecord> record = syncRecordRepository.findByLocalEntity(
                    profile.id(), local.entityType(), local.localId());
            if (record.isPresent()) {
                // 已有映射 → Update
                operations.add(new SyncOperation.Update(
                        local.entityType(), local.localId(),
                        record.get().remoteEntityId(), local.entity()));
            } else {
                // 无映射 → Create
                operations.add(new SyncOperation.Create(
                        local.entityType(), local.localId(), local.entity()));
            }
        }

        for (LocalEntity deleted : deletedEntities) {
            Optional<SyncRecord> record = syncRecordRepository.findByLocalEntity(
                    profile.id(), deleted.entityType(), deleted.localId());
            if (record.isPresent()) {
                operations.add(new SyncOperation.Delete(
                        deleted.entityType(), deleted.localId(),
                        record.get().remoteEntityId()));
            }
        }

        return operations;
    }

    /**
     * 从完整 LocalChangeSet 构建推送操作（仅推送模式用）。
     */
    private List<SyncOperation> buildPushOperationsFromLocalChangeSet(SyncProfile profile,
                                                                       LocalChangeSet localChanges) {
        List<SyncOperation> operations = new ArrayList<>();

        // created → Create
        for (LocalEntity created : localChanges.created()) {
            operations.add(new SyncOperation.Create(
                    created.entityType(), created.localId(), created.entity()));
        }

        // updated → Update
        for (LocalEntity updated : localChanges.updated()) {
            Optional<SyncRecord> record = syncRecordRepository.findByLocalEntity(
                    profile.id(), updated.entityType(), updated.localId());
            if (record.isPresent()) {
                operations.add(new SyncOperation.Update(
                        updated.entityType(), updated.localId(),
                        record.get().remoteEntityId(), updated.entity()));
            }
        }

        // deleted → Delete
        for (LocalEntity deleted : localChanges.deleted()) {
            Optional<SyncRecord> record = syncRecordRepository.findByLocalEntity(
                    profile.id(), deleted.entityType(), deleted.localId());
            if (record.isPresent()) {
                operations.add(new SyncOperation.Delete(
                        deleted.entityType(), deleted.localId(),
                        record.get().remoteEntityId()));
            }
        }

        return operations;
    }

    // ---- 推送后 SyncRecord 更新 ----

    /**
     * 双向同步推送后更新 SyncRecord（resolvedLocalChanges 中的实体）。
     */
    private void updateSyncRecordsAfterPush(SyncProfile profile,
                                             List<LocalEntity> resolvedLocalChanges,
                                             PushResult pushResult,
                                             Instant syncTime) {
        // 收集推送失败的 localEntityId
        Set<String> failedIds = new HashSet<>();
        for (PushResult.PushError error : pushResult.errors()) {
            failedIds.add(error.localEntityId());
        }

        String now = syncTime.toString();
        for (LocalEntity local : resolvedLocalChanges) {
            if (!failedIds.contains(local.localId())) {
                // 推送成功，upsert SyncRecord（remoteEntityId 由连接器返回，此处暂用 localId 占位）
                upsertSyncRecord(profile.id(), local.entityType(), local.localId(),
                        "remote-" + local.localId(), null, null, now);
            }
        }
    }

    /**
     * 仅推送模式推送后更新 SyncRecord（created + updated 中的实体）。
     */
    private void updateSyncRecordsAfterPush(SyncProfile profile,
                                             List<LocalEntity> createdEntities,
                                             List<LocalEntity> updatedEntities,
                                             PushResult pushResult,
                                             Instant syncTime) {
        Set<String> failedIds = new HashSet<>();
        for (PushResult.PushError error : pushResult.errors()) {
            failedIds.add(error.localEntityId());
        }

        String now = syncTime.toString();
        for (LocalEntity local : createdEntities) {
            if (!failedIds.contains(local.localId())) {
                upsertSyncRecord(profile.id(), local.entityType(), local.localId(),
                        "remote-" + local.localId(), null, null, now);
            }
        }
        for (LocalEntity local : updatedEntities) {
            if (!failedIds.contains(local.localId())) {
                upsertSyncRecord(profile.id(), local.entityType(), local.localId(),
                        "remote-" + local.localId(), null, null, now);
            }
        }
    }

    /**
     * 插入或更新 SyncRecord。
     */
    private void upsertSyncRecord(String profileId, String entityType, String localId,
                                   String remoteId, String etag, String remoteUpdatedAt,
                                   String syncTime) {
        String now = Instant.now().toString();
        SyncRecord record = new SyncRecord(
                UUID.randomUUID().toString(), profileId, entityType, localId,
                remoteId, etag, remoteUpdatedAt, syncTime, now, now);
        syncRecordRepository.upsert(record);
    }

    // ---- 同步状态更新 ----

    /**
     * 更新 SyncState。
     */
    private void updateSyncState(SyncProfile profile, String newSyncToken,
                                  Instant syncTime, SyncStatus status, String errorMessage) {
        String now = Instant.now().toString();
        SyncState state = new SyncState(
                UUID.randomUUID().toString(), profile.id(), newSyncToken,
                syncTime.toString(), status, errorMessage, now, now);
        syncStateRepository.upsert(state);
    }

    // ---- 情景记忆事件写入 ----

    /**
     * 将同步事件写入情景记忆（L2），使 Agent 能在对话中回忆同步历史。
     */
    private void writeSyncEvent(SyncProfile profile, SyncResult result) {
        try {
            Instant now = Instant.now();
            String conversationId = UUID.randomUUID().toString();
            String sessionId = "sync-" + profile.id();

            String goal = switch (result.status()) {
                case SUCCESS -> "数据同步完成: " + profile.name();
                case PARTIAL -> "数据同步部分完成: " + profile.name();
                case FAILED -> "数据同步失败: " + profile.name();
            };

            String content = buildSyncEventContent(profile, result);

            MessageRecord message = new MessageRecord(
                    UUID.randomUUID().toString(), conversationId, "system",
                    content, null, CompressionLevel.ORIGINAL,
                    false, null, content.length() / 4, now);

            ConversationRecord conversation = new ConversationRecord(
                    conversationId, sessionId, goal, null,
                    List.of(message), now, now);

            episodicMemory.save(conversation);
            log.debug("同步事件已写入情景记忆: profileId={}, status={}", profile.id(), result.status());
        } catch (Exception e) {
            // 情景记忆写入失败不影响同步结果
            log.warn("同步事件写入情景记忆失败: profileId={}, 错误={}", profile.id(), e.getMessage());
        }
    }

    /**
     * 构建同步事件的文本内容。
     */
    private String buildSyncEventContent(SyncProfile profile, SyncResult result) {
        var sb = new StringBuilder();
        sb.append("同步配置: ").append(profile.name())
                .append(" (").append(profile.connectorType()).append(")\n");
        sb.append("同步方向: ").append(profile.syncDirection()).append("\n");
        sb.append("同步状态: ").append(result.status()).append("\n");
        sb.append("拉取数量: ").append(result.pulledCount()).append("\n");
        sb.append("推送数量: ").append(result.pushedCount()).append("\n");

        if (result.conflictsDetected() > 0) {
            sb.append("冲突检测: ").append(result.conflictsDetected())
                    .append(", 已解决: ").append(result.conflictsResolved()).append("\n");
        }

        if (result.errorMessage() != null) {
            sb.append("错误信息: ").append(result.errorMessage()).append("\n");
        }

        sb.append("同步时间: ").append(result.syncedAt());
        return sb.toString();
    }

    // ---- Fetch 失败处理 ----

    /**
     * 处理 fetch 阶段失败：中止同步，保留上次同步状态不变，返回 FAILED 结果。
     */
    private SyncResult handleFetchFailure(SyncProfile profile, Instant syncStartTime, Exception e) {
        // 更新 SyncState 为 FAILED，但不修改 syncToken（保留上次状态）
        Optional<SyncState> currentState = syncStateRepository.findByProfileId(profile.id());
        String existingSyncToken = currentState.map(SyncState::syncToken).orElse(null);
        updateSyncState(profile, existingSyncToken, syncStartTime, SyncStatus.FAILED, e.getMessage());

        SyncResult result = buildFailedResult(profile, syncStartTime, e.getMessage());
        writeSyncEvent(profile, result);
        return result;
    }

    // ---- 冲突计数 ----

    /**
     * 计算冲突数量：同一实体同时出现在远程 updated 和本地 updated 中。
     */
    private int countConflicts(RemoteChangeSet remote, LocalChangeSet local, String profileId) {
        List<SyncRecord> syncRecords = syncRecordRepository.findByProfileId(profileId);
        Map<String, String> remoteIdToLocalId = new HashMap<>();
        for (SyncRecord record : syncRecords) {
            remoteIdToLocalId.put(record.remoteEntityId(), record.localEntityId());
        }

        Set<String> localUpdatedIds = new HashSet<>();
        for (LocalEntity entity : local.updated()) {
            localUpdatedIds.add(entity.localId());
        }

        int count = 0;
        for (RemoteEntity remoteEntity : remote.updated()) {
            String mappedLocalId = remoteIdToLocalId.get(remoteEntity.remoteId());
            if (mappedLocalId != null && localUpdatedIds.contains(mappedLocalId)) {
                count++;
            }
        }
        return count;
    }

    // ---- 结果构建辅助 ----

    /**
     * 构建 FAILED 状态的 SyncResult。
     */
    private SyncResult buildFailedResult(SyncProfile profile, Instant syncTime, String errorMessage) {
        return new SyncResult(profile.id(), 0, 0, 0, 0,
                SyncStatus.FAILED, errorMessage, syncTime.toString());
    }

    // ---- 字段解析辅助 ----

    /**
     * 从 fields Map 中获取字符串字段值。
     */
    private String getStringField(Map<String, Object> fields, String key, String defaultValue) {
        Object value = fields.get(key);
        if (value == null) {
            return defaultValue;
        }
        return value.toString();
    }

    /**
     * 解析枚举值，解析失败返回默认值。
     */
    private <E extends Enum<E>> E parseEnum(Object value, Class<E> enumClass, E defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        try {
            return Enum.valueOf(enumClass, value.toString().toUpperCase());
        } catch (IllegalArgumentException e) {
            return defaultValue;
        }
    }

    /**
     * 解析字符串列表（从 List 或逗号分隔字符串）。
     */
    private List<String> parseStringList(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(Object::toString).toList();
        }
        // 逗号分隔字符串
        String str = value.toString().trim();
        if (str.isEmpty()) {
            return null;
        }
        return Arrays.stream(str.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
