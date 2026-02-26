package com.lifepilot.sync.engine;

import com.lifepilot.sync.model.*;
import com.lifepilot.sync.model.LocalChangeSet.LocalEntity;
import com.lifepilot.sync.model.RemoteChangeSet.RemoteEntity;
import com.lifepilot.sync.model.SyncConflict.ConflictStatus;
import com.lifepilot.sync.repository.SyncConflictRepository;
import com.lifepilot.sync.repository.SyncRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * ConflictResolver 单元测试。
 *
 * @author zsg
 * @since 2026-02-26
 */
class ConflictResolverTest {

    private SyncRecordRepository syncRecordRepository;
    private SyncConflictRepository syncConflictRepository;
    private ConflictResolver conflictResolver;

    private static final String PROFILE_ID = "profile-1";
    private static final String LOCAL_ID = "local-1";
    private static final String REMOTE_ID = "remote-1";

    @BeforeEach
    void setUp() {
        syncRecordRepository = mock(SyncRecordRepository.class);
        syncConflictRepository = mock(SyncConflictRepository.class);
        conflictResolver = new ConflictResolver(syncRecordRepository, syncConflictRepository);
    }

    @Test
    void 无冲突时_所有变更直接透传() {
        // 远程有一个 updated，本地有一个不同的 updated，无映射关系 → 无冲突
        when(syncRecordRepository.findByProfileId(PROFILE_ID)).thenReturn(List.of());

        var remoteUpdated = new RemoteEntity("r-1", "TodoItem", Map.of("title", "远程任务"), null, null);
        var localUpdated = new LocalEntity("l-1", "TodoItem", new StubEntity("本地任务", "2026-01-01T00:00:00Z"));
        var remoteCreated = new RemoteEntity("r-2", "TodoItem", Map.of("title", "远程新增"), null, null);
        var localCreated = new LocalEntity("l-2", "TodoItem", new StubEntity("本地新增", "2026-01-01T00:00:00Z"));

        var remote = new RemoteChangeSet(List.of(remoteCreated), List.of(remoteUpdated), List.of(), null);
        var local = new LocalChangeSet(List.of(localCreated), List.of(localUpdated), List.of());

        ConflictResolution result = conflictResolver.detectAndResolve(remote, local, ConflictPolicy.REMOTE_WINS, PROFILE_ID);

        // 所有变更透传
        assertEquals(2, result.resolvedRemoteChanges().size(), "远程 updated + created 均透传");
        assertEquals(2, result.resolvedLocalChanges().size(), "本地 updated + created 均透传");
        assertTrue(result.unresolvedConflicts().isEmpty(), "无未解决冲突");
        verify(syncConflictRepository, never()).create(any());
    }

    @Test
    void REMOTE_WINS策略_远程版本胜出() {
        // 构建映射：remote-1 ↔ local-1
        var syncRecord = createSyncRecord(LOCAL_ID, REMOTE_ID);
        when(syncRecordRepository.findByProfileId(PROFILE_ID)).thenReturn(List.of(syncRecord));

        var remoteEntity = new RemoteEntity(REMOTE_ID, "TodoItem", Map.of("title", "远程版本"), "etag-1", "2026-03-01T12:00:00Z");
        var localEntity = new LocalEntity(LOCAL_ID, "TodoItem", new StubEntity("本地版本", "2026-03-01T10:00:00Z"));

        var remote = new RemoteChangeSet(List.of(), List.of(remoteEntity), List.of(), null);
        var local = new LocalChangeSet(List.of(), List.of(localEntity), List.of());

        ConflictResolution result = conflictResolver.detectAndResolve(remote, local, ConflictPolicy.REMOTE_WINS, PROFILE_ID);

        // 远程版本胜出 → 出现在 resolvedRemoteChanges
        assertTrue(result.resolvedRemoteChanges().stream()
                .anyMatch(e -> e.remoteId().equals(REMOTE_ID)), "远程版本应在 resolvedRemoteChanges 中");
        assertTrue(result.resolvedLocalChanges().isEmpty(), "本地冲突实体不应在 resolvedLocalChanges 中");
        assertTrue(result.unresolvedConflicts().isEmpty());

        // 快照已保存
        verify(syncConflictRepository).create(any(SyncConflict.class));
        // 冲突已标记为已解决
        verify(syncConflictRepository).resolve(anyString());
    }

    @Test
    void LOCAL_WINS策略_本地版本胜出() {
        var syncRecord = createSyncRecord(LOCAL_ID, REMOTE_ID);
        when(syncRecordRepository.findByProfileId(PROFILE_ID)).thenReturn(List.of(syncRecord));

        var remoteEntity = new RemoteEntity(REMOTE_ID, "TodoItem", Map.of("title", "远程版本"), null, "2026-03-01T12:00:00Z");
        var localEntity = new LocalEntity(LOCAL_ID, "TodoItem", new StubEntity("本地版本", "2026-03-01T14:00:00Z"));

        var remote = new RemoteChangeSet(List.of(), List.of(remoteEntity), List.of(), null);
        var local = new LocalChangeSet(List.of(), List.of(localEntity), List.of());

        ConflictResolution result = conflictResolver.detectAndResolve(remote, local, ConflictPolicy.LOCAL_WINS, PROFILE_ID);

        // 本地版本胜出 → 出现在 resolvedLocalChanges
        assertTrue(result.resolvedLocalChanges().stream()
                .anyMatch(e -> e.localId().equals(LOCAL_ID)), "本地版本应在 resolvedLocalChanges 中");
        assertTrue(result.resolvedRemoteChanges().isEmpty(), "远程冲突实体不应在 resolvedRemoteChanges 中");
        assertTrue(result.unresolvedConflicts().isEmpty());

        verify(syncConflictRepository).create(any(SyncConflict.class));
        verify(syncConflictRepository).resolve(anyString());
    }

    @Test
    void LAST_WRITE_WINS策略_远程较新时远程胜出() {
        var syncRecord = createSyncRecord(LOCAL_ID, REMOTE_ID);
        when(syncRecordRepository.findByProfileId(PROFILE_ID)).thenReturn(List.of(syncRecord));

        // 远程时间较晚
        var remoteEntity = new RemoteEntity(REMOTE_ID, "TodoItem", Map.of("title", "远程版本"), null, "2026-03-01T14:00:00Z");
        var localEntity = new LocalEntity(LOCAL_ID, "TodoItem", new StubEntity("本地版本", "2026-03-01T10:00:00Z"));

        var remote = new RemoteChangeSet(List.of(), List.of(remoteEntity), List.of(), null);
        var local = new LocalChangeSet(List.of(), List.of(localEntity), List.of());

        ConflictResolution result = conflictResolver.detectAndResolve(remote, local, ConflictPolicy.LAST_WRITE_WINS, PROFILE_ID);

        assertTrue(result.resolvedRemoteChanges().stream()
                .anyMatch(e -> e.remoteId().equals(REMOTE_ID)), "远程较新应胜出");
        assertTrue(result.resolvedLocalChanges().isEmpty());
        verify(syncConflictRepository).resolve(anyString());
    }

    @Test
    void LAST_WRITE_WINS策略_本地较新时本地胜出() {
        var syncRecord = createSyncRecord(LOCAL_ID, REMOTE_ID);
        when(syncRecordRepository.findByProfileId(PROFILE_ID)).thenReturn(List.of(syncRecord));

        // 本地时间较晚
        var remoteEntity = new RemoteEntity(REMOTE_ID, "TodoItem", Map.of("title", "远程版本"), null, "2026-03-01T10:00:00Z");
        var localEntity = new LocalEntity(LOCAL_ID, "TodoItem", new StubEntity("本地版本", "2026-03-01T14:00:00Z"));

        var remote = new RemoteChangeSet(List.of(), List.of(remoteEntity), List.of(), null);
        var local = new LocalChangeSet(List.of(), List.of(localEntity), List.of());

        ConflictResolution result = conflictResolver.detectAndResolve(remote, local, ConflictPolicy.LAST_WRITE_WINS, PROFILE_ID);

        assertTrue(result.resolvedLocalChanges().stream()
                .anyMatch(e -> e.localId().equals(LOCAL_ID)), "本地较新应胜出");
        assertTrue(result.resolvedRemoteChanges().isEmpty());
        verify(syncConflictRepository).resolve(anyString());
    }

    @Test
    void USER_CONFIRM策略_冲突加入未解决列表() {
        var syncRecord = createSyncRecord(LOCAL_ID, REMOTE_ID);
        when(syncRecordRepository.findByProfileId(PROFILE_ID)).thenReturn(List.of(syncRecord));

        var remoteEntity = new RemoteEntity(REMOTE_ID, "TodoItem", Map.of("title", "远程版本"), null, "2026-03-01T12:00:00Z");
        var localEntity = new LocalEntity(LOCAL_ID, "TodoItem", new StubEntity("本地版本", "2026-03-01T10:00:00Z"));

        var remote = new RemoteChangeSet(List.of(), List.of(remoteEntity), List.of(), null);
        var local = new LocalChangeSet(List.of(), List.of(localEntity), List.of());

        ConflictResolution result = conflictResolver.detectAndResolve(remote, local, ConflictPolicy.USER_CONFIRM, PROFILE_ID);

        assertEquals(1, result.unresolvedConflicts().size(), "应有一个未解决冲突");
        assertEquals(ConflictStatus.UNRESOLVED, result.unresolvedConflicts().getFirst().status());
        assertTrue(result.resolvedRemoteChanges().isEmpty(), "冲突实体不应在 resolvedRemoteChanges 中");
        assertTrue(result.resolvedLocalChanges().isEmpty(), "冲突实体不应在 resolvedLocalChanges 中");

        // 快照已保存但未标记为已解决
        verify(syncConflictRepository).create(any(SyncConflict.class));
        verify(syncConflictRepository, never()).resolve(anyString());
    }

    @Test
    void 所有策略均保存双方版本快照() {
        var syncRecord = createSyncRecord(LOCAL_ID, REMOTE_ID);
        when(syncRecordRepository.findByProfileId(PROFILE_ID)).thenReturn(List.of(syncRecord));

        var remoteEntity = new RemoteEntity(REMOTE_ID, "TodoItem", Map.of("title", "远程版本"), null, "2026-03-01T12:00:00Z");
        var localEntity = new LocalEntity(LOCAL_ID, "TodoItem", new StubEntity("本地版本", "2026-03-01T10:00:00Z"));

        // 对每种策略都验证快照保存
        for (ConflictPolicy policy : ConflictPolicy.values()) {
            reset(syncConflictRepository);

            var remote = new RemoteChangeSet(List.of(), List.of(remoteEntity), List.of(), null);
            var local = new LocalChangeSet(List.of(), List.of(localEntity), List.of());

            conflictResolver.detectAndResolve(remote, local, policy, PROFILE_ID);

            ArgumentCaptor<SyncConflict> captor = ArgumentCaptor.forClass(SyncConflict.class);
            verify(syncConflictRepository).create(captor.capture());

            SyncConflict saved = captor.getValue();
            assertNotNull(saved.localSnapshotJson(), "策略 " + policy + ": 本地快照不应为 null");
            assertNotNull(saved.remoteSnapshotJson(), "策略 " + policy + ": 远程快照不应为 null");
            assertFalse(saved.localSnapshotJson().isBlank(), "策略 " + policy + ": 本地快照不应为空");
            assertFalse(saved.remoteSnapshotJson().isBlank(), "策略 " + policy + ": 远程快照不应为空");
        }
    }

    @Test
    void 非冲突变更不受影响() {
        // 两个映射：remote-1 ↔ local-1, remote-2 ↔ local-2
        var syncRecord1 = createSyncRecord(LOCAL_ID, REMOTE_ID);
        var syncRecord2 = createSyncRecord("local-2", "remote-2");
        when(syncRecordRepository.findByProfileId(PROFILE_ID)).thenReturn(List.of(syncRecord1, syncRecord2));

        // remote-1 和 local-1 冲突，remote-2 仅远程更新，local-2 无更新
        var conflictRemote = new RemoteEntity(REMOTE_ID, "TodoItem", Map.of("title", "冲突远程"), null, "2026-03-01T12:00:00Z");
        var nonConflictRemote = new RemoteEntity("remote-2", "TodoItem", Map.of("title", "非冲突远程"), null, "2026-03-01T12:00:00Z");
        var conflictLocal = new LocalEntity(LOCAL_ID, "TodoItem", new StubEntity("冲突本地", "2026-03-01T10:00:00Z"));
        var nonConflictLocal = new LocalEntity("local-3", "TodoItem", new StubEntity("非冲突本地", "2026-03-01T10:00:00Z"));

        var remote = new RemoteChangeSet(List.of(), List.of(conflictRemote, nonConflictRemote), List.of(), null);
        var local = new LocalChangeSet(List.of(), List.of(conflictLocal, nonConflictLocal), List.of());

        ConflictResolution result = conflictResolver.detectAndResolve(remote, local, ConflictPolicy.REMOTE_WINS, PROFILE_ID);

        // 非冲突远程变更透传
        assertTrue(result.resolvedRemoteChanges().stream()
                .anyMatch(e -> e.remoteId().equals("remote-2")), "非冲突远程变更应透传");
        // 非冲突本地变更透传
        assertTrue(result.resolvedLocalChanges().stream()
                .anyMatch(e -> e.localId().equals("local-3")), "非冲突本地变更应透传");
    }

    // ---- 辅助方法 ----

    private SyncRecord createSyncRecord(String localId, String remoteId) {
        String now = Instant.now().toString();
        return new SyncRecord(
                java.util.UUID.randomUUID().toString(), PROFILE_ID, "TodoItem",
                localId, remoteId, null, null, now, now, now
        );
    }

    /**
     * 测试用 stub 实体，模拟 TodoItem / ScheduleItem 等 record 的 updatedAt() 方法。
     */
    record StubEntity(String title, String updatedAt) {
    }
}
