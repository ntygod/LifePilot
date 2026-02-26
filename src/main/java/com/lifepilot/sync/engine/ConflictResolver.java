package com.lifepilot.sync.engine;

import com.lifepilot.sync.model.*;
import com.lifepilot.sync.model.LocalChangeSet.LocalEntity;
import com.lifepilot.sync.model.RemoteChangeSet.RemoteEntity;
import com.lifepilot.sync.model.SyncConflict.ConflictStatus;
import com.lifepilot.sync.repository.SyncConflictRepository;
import com.lifepilot.sync.repository.SyncRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;

/**
 * 冲突解决器 — 检测并解决本地与远程变更之间的冲突。
 *
 * <p>冲突条件：同一实体（通过 SyncRecord 映射匹配）同时出现在
 * {@link RemoteChangeSet#updated()} 和 {@link LocalChangeSet#updated()} 中。</p>
 *
 * <p>支持四种冲突策略：
 * <ul>
 *   <li>{@link ConflictPolicy#LAST_WRITE_WINS} — 比较时间戳，保留较新版本</li>
 *   <li>{@link ConflictPolicy#REMOTE_WINS} — 始终保留远程版本</li>
 *   <li>{@link ConflictPolicy#LOCAL_WINS} — 始终保留本地版本</li>
 *   <li>{@link ConflictPolicy#USER_CONFIRM} — 标记为未解决，等待用户确认</li>
 * </ul>
 *
 * <p>无论采用何种策略，所有冲突均保存双方版本快照到 {@code sync_conflicts} 表。</p>
 *
 * @author zsg
 * @since 2026-02-26
 */
public class ConflictResolver {

    private static final Logger log = LoggerFactory.getLogger(ConflictResolver.class);

    private final SyncRecordRepository syncRecordRepository;
    private final SyncConflictRepository syncConflictRepository;

    public ConflictResolver(SyncRecordRepository syncRecordRepository,
                            SyncConflictRepository syncConflictRepository) {
        this.syncRecordRepository = syncRecordRepository;
        this.syncConflictRepository = syncConflictRepository;
    }

    /**
     * 检测并解决冲突。
     *
     * <p>流程：
     * <ol>
     *   <li>获取 profileId 下所有 SyncRecord，构建 remoteId → localId 映射</li>
     *   <li>找出同时出现在远程 updated 和本地 updated 中的冲突实体</li>
     *   <li>对每个冲突保存双方版本快照，并按策略解决</li>
     *   <li>非冲突变更直接透传</li>
     * </ol>
     *
     * @param remote    远程变更集
     * @param local     本地变更集
     * @param policy    冲突解决策略
     * @param profileId 同步配置 ID
     * @return 冲突解决结果
     */
    public ConflictResolution detectAndResolve(RemoteChangeSet remote,
                                               LocalChangeSet local,
                                               ConflictPolicy policy,
                                               String profileId) {
        // 获取所有 SyncRecord，构建 remoteId → localId 映射
        List<SyncRecord> syncRecords = syncRecordRepository.findByProfileId(profileId);
        Map<String, String> remoteIdToLocalId = new HashMap<>();
        for (SyncRecord record : syncRecords) {
            remoteIdToLocalId.put(record.remoteEntityId(), record.localEntityId());
        }

        // 构建本地 updated 实体的 localId → LocalEntity 索引
        Map<String, LocalEntity> localUpdatedIndex = new HashMap<>();
        for (LocalEntity localEntity : local.updated()) {
            localUpdatedIndex.put(localEntity.localId(), localEntity);
        }

        // 检测冲突：远程 updated 中 remoteId 映射到的 localId 也在本地 updated 中
        Set<String> conflictLocalIds = new HashSet<>();
        Set<String> conflictRemoteIds = new HashSet<>();
        List<SyncConflict> unresolvedConflicts = new ArrayList<>();
        List<RemoteEntity> resolvedRemoteChanges = new ArrayList<>();
        List<LocalEntity> resolvedLocalChanges = new ArrayList<>();

        for (RemoteEntity remoteEntity : remote.updated()) {
            String mappedLocalId = remoteIdToLocalId.get(remoteEntity.remoteId());
            if (mappedLocalId != null && localUpdatedIndex.containsKey(mappedLocalId)) {
                // 检测到冲突
                LocalEntity localEntity = localUpdatedIndex.get(mappedLocalId);
                conflictLocalIds.add(mappedLocalId);
                conflictRemoteIds.add(remoteEntity.remoteId());

                log.info("检测到冲突: profileId={}, localId={}, remoteId={}, 策略={}",
                        profileId, mappedLocalId, remoteEntity.remoteId(), policy);

                // 保存双方版本快照（无论策略如何）
                SyncConflict conflict = createConflictSnapshot(
                        profileId, localEntity, remoteEntity);
                syncConflictRepository.create(conflict);

                // 按策略解决冲突
                resolveByPolicy(policy, remoteEntity, localEntity, conflict,
                        resolvedRemoteChanges, resolvedLocalChanges, unresolvedConflicts);
            }
        }

        // 非冲突的远程 updated → resolvedRemoteChanges
        for (RemoteEntity remoteEntity : remote.updated()) {
            if (!conflictRemoteIds.contains(remoteEntity.remoteId())) {
                resolvedRemoteChanges.add(remoteEntity);
            }
        }

        // 非冲突的本地 updated → resolvedLocalChanges
        for (LocalEntity localEntity : local.updated()) {
            if (!conflictLocalIds.contains(localEntity.localId())) {
                resolvedLocalChanges.add(localEntity);
            }
        }

        // 透传：remote.created → resolvedRemoteChanges
        resolvedRemoteChanges.addAll(remote.created());

        // 透传：local.created → resolvedLocalChanges
        resolvedLocalChanges.addAll(local.created());

        log.info("冲突解决完成: profileId={}, 冲突数={}, 已解决={}, 未解决={}",
                profileId, conflictLocalIds.size(),
                conflictLocalIds.size() - unresolvedConflicts.size(),
                unresolvedConflicts.size());

        return new ConflictResolution(
                resolvedRemoteChanges,
                resolvedLocalChanges,
                unresolvedConflicts
        );
    }

    // ---- 内部方法 ----

    /**
     * 创建冲突快照记录，包含双方版本的 JSON 序列化。
     */
    private SyncConflict createConflictSnapshot(String profileId,
                                                 LocalEntity localEntity,
                                                 RemoteEntity remoteEntity) {
        String now = Instant.now().toString();
        return new SyncConflict(
                UUID.randomUUID().toString(),
                profileId,
                localEntity.entityType(),
                localEntity.localId(),
                serializeToJson(localEntity.entity()),
                serializeToJson(remoteEntity.fields()),
                ConflictStatus.UNRESOLVED,
                null,
                now
        );
    }

    /**
     * 按冲突策略解决单个冲突。
     */
    private void resolveByPolicy(ConflictPolicy policy,
                                  RemoteEntity remoteEntity,
                                  LocalEntity localEntity,
                                  SyncConflict conflict,
                                  List<RemoteEntity> resolvedRemoteChanges,
                                  List<LocalEntity> resolvedLocalChanges,
                                  List<SyncConflict> unresolvedConflicts) {
        switch (policy) {
            case REMOTE_WINS -> {
                // 远程版本胜出，应用到本地
                resolvedRemoteChanges.add(remoteEntity);
                syncConflictRepository.resolve(conflict.id());
                log.debug("冲突解决: REMOTE_WINS, localId={}", localEntity.localId());
            }
            case LOCAL_WINS -> {
                // 本地版本胜出，推送到远程
                resolvedLocalChanges.add(localEntity);
                syncConflictRepository.resolve(conflict.id());
                log.debug("冲突解决: LOCAL_WINS, localId={}", localEntity.localId());
            }
            case LAST_WRITE_WINS -> {
                resolveByTimestamp(remoteEntity, localEntity, conflict,
                        resolvedRemoteChanges, resolvedLocalChanges);
            }
            case USER_CONFIRM -> {
                // 标记为未解决，等待用户确认
                unresolvedConflicts.add(conflict);
                log.debug("冲突待确认: USER_CONFIRM, localId={}", localEntity.localId());
            }
        }
    }

    /**
     * LAST_WRITE_WINS 策略：比较时间戳，保留较新版本。
     *
     * <p>当远程 updatedAt 为 null 或本地时间戳较晚时，本地版本胜出；
     * 否则远程版本胜出。时间戳相等时默认远程版本胜出。</p>
     */
    private void resolveByTimestamp(RemoteEntity remoteEntity,
                                     LocalEntity localEntity,
                                     SyncConflict conflict,
                                     List<RemoteEntity> resolvedRemoteChanges,
                                     List<LocalEntity> resolvedLocalChanges) {
        Instant remoteTime = parseTimestamp(remoteEntity.updatedAt());
        Instant localTime = parseLocalEntityTimestamp(localEntity.entity());

        if (localTime != null && (remoteTime == null || localTime.isAfter(remoteTime))) {
            // 本地版本较新
            resolvedLocalChanges.add(localEntity);
            log.debug("冲突解决: LAST_WRITE_WINS → 本地胜出, localId={}", localEntity.localId());
        } else {
            // 远程版本较新或时间戳相等
            resolvedRemoteChanges.add(remoteEntity);
            log.debug("冲突解决: LAST_WRITE_WINS → 远程胜出, localId={}", localEntity.localId());
        }
        syncConflictRepository.resolve(conflict.id());
    }

    /**
     * 解析 ISO 8601 时间戳字符串为 Instant，解析失败返回 null。
     */
    private Instant parseTimestamp(String timestamp) {
        if (timestamp == null || timestamp.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(timestamp);
        } catch (Exception e) {
            log.warn("时间戳解析失败: value={}", timestamp);
            return null;
        }
    }

    /**
     * 从本地实体对象中提取 updatedAt 时间戳。
     *
     * <p>通过反射调用 updatedAt() 方法获取（TodoItem / ScheduleItem / HabitItem 均为 record，
     * 包含 updatedAt 字段）。</p>
     */
    private Instant parseLocalEntityTimestamp(Object entity) {
        try {
            var method = entity.getClass().getMethod("updatedAt");
            Object value = method.invoke(entity);
            if (value instanceof String s) {
                return parseTimestamp(s);
            }
        } catch (Exception e) {
            log.warn("无法提取本地实体 updatedAt: entityClass={}", entity.getClass().getSimpleName());
        }
        return null;
    }

    /**
     * 简单 JSON 序列化：将对象转为字符串表示。
     *
     * <p>对于 Map 类型直接使用 toString()，对于 record 类型使用 toString()。
     * 生产环境可替换为 Jackson ObjectMapper。</p>
     */
    private String serializeToJson(Object obj) {
        if (obj == null) {
            return "{}";
        }
        return obj.toString();
    }
}
