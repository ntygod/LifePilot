package com.lifepilot.sync.engine;

import com.lifepilot.memory.episodic.ConversationRecord;
import com.lifepilot.memory.episodic.EpisodicMemory;
import com.lifepilot.skill.builtin.habit.HabitRepository;
import com.lifepilot.skill.builtin.schedule.ScheduleRepository;
import com.lifepilot.skill.builtin.todo.TodoRepository;
import com.lifepilot.sync.config.SyncProperties;
import com.lifepilot.sync.connector.SyncConnector;
import com.lifepilot.sync.model.*;
import com.lifepilot.sync.model.LocalChangeSet.LocalEntity;
import com.lifepilot.sync.model.RemoteChangeSet.RemoteEntity;
import com.lifepilot.sync.repository.SyncConflictRepository;
import com.lifepilot.sync.repository.SyncRecordRepository;
import com.lifepilot.sync.repository.SyncStateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * SyncEngine 单元测试。
 *
 * @author zsg
 * @since 2026-02-26
 */
class SyncEngineTest {

    private SyncConnector connector;
    private ChangeDetector changeDetector;
    private ConflictResolver conflictResolver;
    private SyncRecordRepository syncRecordRepository;
    private SyncStateRepository syncStateRepository;
    private SyncConflictRepository syncConflictRepository;
    private TodoRepository todoRepository;
    private ScheduleRepository scheduleRepository;
    private HabitRepository habitRepository;
    private EpisodicMemory episodicMemory;
    private SyncProperties properties;
    private SyncEngine syncEngine;

    private static final String PROFILE_ID = "profile-1";
    private static final String CONNECTOR_TYPE = "todoist";

    @BeforeEach
    void setUp() {
        connector = mock(SyncConnector.class);
        when(connector.type()).thenReturn(CONNECTOR_TYPE);

        changeDetector = mock(ChangeDetector.class);
        conflictResolver = mock(ConflictResolver.class);
        syncRecordRepository = mock(SyncRecordRepository.class);
        syncStateRepository = mock(SyncStateRepository.class);
        syncConflictRepository = mock(SyncConflictRepository.class);
        todoRepository = mock(TodoRepository.class);
        scheduleRepository = mock(ScheduleRepository.class);
        habitRepository = mock(HabitRepository.class);
        episodicMemory = mock(EpisodicMemory.class);
        properties = new SyncProperties();

        syncEngine = new SyncEngine(
                Map.of(CONNECTOR_TYPE, connector),
                changeDetector, conflictResolver,
                syncRecordRepository, syncStateRepository, syncConflictRepository,
                todoRepository, scheduleRepository, habitRepository,
                episodicMemory, properties);
    }

    // ---- 辅助方法 ----

    private SyncProfile buildProfile(SyncDirection direction) {
        return SyncProfile.builder()
                .id(PROFILE_ID)
                .name("测试同步")
                .connectorType(CONNECTOR_TYPE)
                .connectionParamsJson("{}")
                .syncDirection(direction)
                .conflictPolicy(ConflictPolicy.LAST_WRITE_WINS)
                .cronExpression("0 */15 * * * *")
                .enabled(true)
                .dataTypeFilterJson("[\"TodoItem\"]")
                .createdAt(Instant.now().toString())
                .updatedAt(Instant.now().toString())
                .build();
    }

    private RemoteChangeSet emptyRemoteChangeSet(String newToken) {
        return new RemoteChangeSet(List.of(), List.of(), List.of(), newToken);
    }

    private LocalChangeSet emptyLocalChangeSet() {
        return new LocalChangeSet(List.of(), List.of(), List.of());
    }

    private ConflictResolution emptyResolution() {
        return new ConflictResolution(List.of(), List.of(), List.of());
    }

    private void assertContains(String actual, String expected) {
        assertNotNull(actual, "字符串不应为 null");
        assertTrue(actual.contains(expected), "期望包含 '%s'，实际为 '%s'".formatted(expected, actual));
    }

    // ---- BIDIRECTIONAL 同步测试 ----

    @Test
    void 双向同步_正常流程_返回SUCCESS() {
        var profile = buildProfile(SyncDirection.BIDIRECTIONAL);
        String newToken = "new-sync-token";

        // Mock: 无已有同步状态
        when(syncStateRepository.findByProfileId(PROFILE_ID)).thenReturn(Optional.empty());

        // Mock: fetchChanges 返回一个新增实体
        var remoteEntity = new RemoteEntity("r-1", "TodoItem",
                Map.of("title", "远程任务", "priority", "HIGH", "status", "PENDING"),
                "etag-1", Instant.now().toString());
        var remoteChanges = new RemoteChangeSet(List.of(remoteEntity), List.of(), List.of(), newToken);
        when(connector.fetchChanges(profile, null)).thenReturn(remoteChanges);

        // Mock: detectLocalChanges 返回空
        when(changeDetector.detectLocalChanges(profile)).thenReturn(emptyLocalChangeSet());

        // Mock: resolveConflicts 透传远程变更
        var resolution = new ConflictResolution(List.of(remoteEntity), List.of(), List.of());
        when(conflictResolver.detectAndResolve(eq(remoteChanges), any(), eq(ConflictPolicy.LAST_WRITE_WINS), eq(PROFILE_ID)))
                .thenReturn(resolution);

        // Mock: syncRecordRepository 无已有映射
        when(syncRecordRepository.findByProfileId(PROFILE_ID)).thenReturn(List.of());
        when(syncRecordRepository.findByRemoteEntity(PROFILE_ID, "r-1")).thenReturn(Optional.empty());

        // Mock: todoRepository.create 返回 ID
        when(todoRepository.create(any())).thenReturn("local-1");

        SyncResult result = syncEngine.sync(profile);

        assertEquals(SyncStatus.SUCCESS, result.status());
        assertEquals(PROFILE_ID, result.profileId());
        assertEquals(1, result.pulledCount());
        assertEquals(0, result.pushedCount());
        assertNull(result.errorMessage());

        // 验证 SyncState 已更新
        verify(syncStateRepository).upsert(argThat(state ->
                state.profileId().equals(PROFILE_ID)
                        && newToken.equals(state.syncToken())
                        && state.lastSyncStatus() == SyncStatus.SUCCESS));

        // 验证 SyncRecord 已创建
        verify(syncRecordRepository).upsert(argThat(record ->
                record.profileId().equals(PROFILE_ID)
                        && record.localEntityId().equals("local-1")
                        && record.remoteEntityId().equals("r-1")));

        // 验证情景记忆已写入
        verify(episodicMemory).save(any(ConversationRecord.class));
    }

    // ---- PULL_ONLY 测试 ----

    @Test
    void 仅拉取_不调用pushChanges() {
        var profile = buildProfile(SyncDirection.PULL_ONLY);

        when(syncStateRepository.findByProfileId(PROFILE_ID)).thenReturn(Optional.empty());
        when(connector.fetchChanges(profile, null)).thenReturn(emptyRemoteChangeSet("token-1"));

        SyncResult result = syncEngine.sync(profile);

        assertEquals(SyncStatus.SUCCESS, result.status());
        assertEquals(0, result.pulledCount());
        assertEquals(0, result.pushedCount());

        // 验证不调用 pushChanges
        verify(connector, never()).pushChanges(any(), any());
        // 验证不调用 detectLocalChanges
        verify(changeDetector, never()).detectLocalChanges(any());
        // 验证情景记忆已写入
        verify(episodicMemory).save(any(ConversationRecord.class));
    }

    // ---- PUSH_ONLY 测试 ----

    @Test
    void 仅推送_不调用fetchChanges() {
        var profile = buildProfile(SyncDirection.PUSH_ONLY);

        when(syncStateRepository.findByProfileId(PROFILE_ID)).thenReturn(Optional.empty());
        when(changeDetector.detectLocalChanges(profile)).thenReturn(emptyLocalChangeSet());

        SyncResult result = syncEngine.sync(profile);

        assertEquals(SyncStatus.SUCCESS, result.status());
        assertEquals(0, result.pulledCount());
        assertEquals(0, result.pushedCount());

        // 验证不调用 fetchChanges
        verify(connector, never()).fetchChanges(any(), any());
        // 验证情景记忆已写入
        verify(episodicMemory).save(any(ConversationRecord.class));
    }

    // ---- Fetch 失败测试 ----

    @Test
    void Fetch阶段失败_中止同步返回FAILED() {
        var profile = buildProfile(SyncDirection.BIDIRECTIONAL);

        when(syncStateRepository.findByProfileId(PROFILE_ID)).thenReturn(Optional.empty());
        when(connector.fetchChanges(profile, null))
                .thenThrow(new SyncException.ConnectionException("网络连接超时"));

        SyncResult result = syncEngine.sync(profile);

        assertEquals(SyncStatus.FAILED, result.status());
        assertEquals(0, result.pulledCount());
        assertEquals(0, result.pushedCount());
        assertNotNull(result.errorMessage());
        assertContains(result.errorMessage(), "网络连接超时");

        // 验证不调用后续阶段
        verify(changeDetector, never()).detectLocalChanges(any());
        verify(conflictResolver, never()).detectAndResolve(any(), any(), any(), any());
        verify(connector, never()).pushChanges(any(), any());

        // 验证 SyncState 更新为 FAILED
        verify(syncStateRepository).upsert(argThat(state ->
                state.lastSyncStatus() == SyncStatus.FAILED));

        // 验证情景记忆已写入（失败事件）
        verify(episodicMemory).save(any(ConversationRecord.class));
    }

    // ---- Push 部分失败测试 ----

    @Test
    void Push阶段部分失败_返回PARTIAL() {
        var profile = buildProfile(SyncDirection.BIDIRECTIONAL);

        when(syncStateRepository.findByProfileId(PROFILE_ID)).thenReturn(Optional.empty());

        // Mock: fetchChanges 返回空
        when(connector.fetchChanges(profile, null)).thenReturn(emptyRemoteChangeSet("token-1"));

        // Mock: detectLocalChanges 返回两个新增实体
        var localEntity1 = new LocalEntity("l-1", "TodoItem", new StubTodoItem("任务1"));
        var localEntity2 = new LocalEntity("l-2", "TodoItem", new StubTodoItem("任务2"));
        var localChanges = new LocalChangeSet(List.of(localEntity1, localEntity2), List.of(), List.of());
        when(changeDetector.detectLocalChanges(profile)).thenReturn(localChanges);

        // Mock: resolveConflicts 透传本地变更
        var resolution = new ConflictResolution(List.of(), List.of(localEntity1, localEntity2), List.of());
        when(conflictResolver.detectAndResolve(any(), eq(localChanges), eq(ConflictPolicy.LAST_WRITE_WINS), eq(PROFILE_ID)))
                .thenReturn(resolution);

        // Mock: syncRecordRepository 无已有映射
        when(syncRecordRepository.findByProfileId(PROFILE_ID)).thenReturn(List.of());
        when(syncRecordRepository.findByLocalEntity(eq(PROFILE_ID), eq("TodoItem"), anyString()))
                .thenReturn(Optional.empty());

        // Mock: pushChanges 部分失败
        var pushResult = new PushResult(1, 1,
                List.of(new PushResult.PushError("l-2", "远程服务器错误")));
        when(connector.pushChanges(eq(profile), anyList())).thenReturn(pushResult);

        SyncResult result = syncEngine.sync(profile);

        assertEquals(SyncStatus.PARTIAL, result.status());
        assertEquals(1, result.pushedCount());
        assertNotNull(result.errorMessage());

        // 验证 SyncState 更新为 PARTIAL
        verify(syncStateRepository).upsert(argThat(state ->
                state.lastSyncStatus() == SyncStatus.PARTIAL));
    }

    // ---- SyncRecord 更新测试 ----

    @Test
    void 成功同步后_SyncRecord已更新() {
        var profile = buildProfile(SyncDirection.PULL_ONLY);
        String newToken = "token-2";

        when(syncStateRepository.findByProfileId(PROFILE_ID)).thenReturn(Optional.empty());

        var remoteEntity = new RemoteEntity("r-1", "TodoItem",
                Map.of("title", "远程任务", "priority", "LOW", "status", "PENDING"),
                "etag-2", "2026-03-01T12:00:00Z");
        var remoteChanges = new RemoteChangeSet(List.of(remoteEntity), List.of(), List.of(), newToken);
        when(connector.fetchChanges(profile, null)).thenReturn(remoteChanges);
        when(syncRecordRepository.findByRemoteEntity(PROFILE_ID, "r-1")).thenReturn(Optional.empty());
        when(todoRepository.create(any())).thenReturn("local-new");

        SyncResult result = syncEngine.sync(profile);

        assertEquals(SyncStatus.SUCCESS, result.status());
        assertEquals(1, result.pulledCount());

        // 验证 SyncRecord upsert 被调用，且包含正确的 remoteEntityId 和 etag
        ArgumentCaptor<SyncRecord> captor = ArgumentCaptor.forClass(SyncRecord.class);
        verify(syncRecordRepository).upsert(captor.capture());
        SyncRecord savedRecord = captor.getValue();
        assertEquals("r-1", savedRecord.remoteEntityId());
        assertEquals("local-new", savedRecord.localEntityId());
        assertEquals("etag-2", savedRecord.etag());
        assertEquals("2026-03-01T12:00:00Z", savedRecord.remoteUpdatedAt());
    }

    // ---- SyncState syncToken 更新测试 ----

    @Test
    void 成功同步后_SyncState包含新syncToken() {
        var profile = buildProfile(SyncDirection.PULL_ONLY);
        String newToken = "new-token-abc";

        when(syncStateRepository.findByProfileId(PROFILE_ID)).thenReturn(Optional.empty());
        when(connector.fetchChanges(profile, null)).thenReturn(emptyRemoteChangeSet(newToken));

        SyncResult result = syncEngine.sync(profile);

        assertEquals(SyncStatus.SUCCESS, result.status());

        ArgumentCaptor<SyncState> captor = ArgumentCaptor.forClass(SyncState.class);
        verify(syncStateRepository).upsert(captor.capture());
        assertEquals(newToken, captor.getValue().syncToken());
    }

    // ---- EpisodicMemory 事件写入测试 ----

    @Test
    void 同步成功时_写入情景记忆事件() {
        var profile = buildProfile(SyncDirection.PULL_ONLY);

        when(syncStateRepository.findByProfileId(PROFILE_ID)).thenReturn(Optional.empty());
        when(connector.fetchChanges(profile, null)).thenReturn(emptyRemoteChangeSet("t1"));

        syncEngine.sync(profile);

        ArgumentCaptor<ConversationRecord> captor = ArgumentCaptor.forClass(ConversationRecord.class);
        verify(episodicMemory).save(captor.capture());

        ConversationRecord saved = captor.getValue();
        assertEquals("sync-" + PROFILE_ID, saved.sessionId());
        assertTrue(saved.goal().contains("测试同步"));
        assertFalse(saved.messages().isEmpty());
        assertEquals("system", saved.messages().getFirst().role());
    }

    @Test
    void 同步失败时_也写入情景记忆事件() {
        var profile = buildProfile(SyncDirection.BIDIRECTIONAL);

        when(syncStateRepository.findByProfileId(PROFILE_ID)).thenReturn(Optional.empty());
        when(connector.fetchChanges(profile, null))
                .thenThrow(new SyncException.RemoteApiException("API 返回 500"));

        syncEngine.sync(profile);

        ArgumentCaptor<ConversationRecord> captor = ArgumentCaptor.forClass(ConversationRecord.class);
        verify(episodicMemory).save(captor.capture());

        ConversationRecord saved = captor.getValue();
        assertTrue(saved.goal().contains("失败"));
    }

    // ---- 连接器未找到测试 ----

    @Test
    void 连接器未找到_返回FAILED() {
        var profile = SyncProfile.builder()
                .id(PROFILE_ID)
                .name("测试")
                .connectorType("unknown-type")
                .connectionParamsJson("{}")
                .syncDirection(SyncDirection.BIDIRECTIONAL)
                .conflictPolicy(ConflictPolicy.LAST_WRITE_WINS)
                .cronExpression("0 */15 * * * *")
                .enabled(true)
                .createdAt(Instant.now().toString())
                .updatedAt(Instant.now().toString())
                .build();

        SyncResult result = syncEngine.sync(profile);

        assertEquals(SyncStatus.FAILED, result.status());
        assertContains(result.errorMessage(), "未找到连接器");
    }

    // ---- 辅助 stub 类 ----

    /**
     * 用于测试的 TodoItem 替身，提供 updatedAt 方法。
     */
    record StubTodoItem(String title) {
        public String updatedAt() {
            return Instant.now().toString();
        }
    }
}
