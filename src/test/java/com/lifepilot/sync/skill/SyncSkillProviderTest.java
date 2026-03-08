package com.lifepilot.sync.skill;

import com.lifepilot.skill.model.SkillDefinition;
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
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.model.ToolInput;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.lifepilot.tool.schema.JsonSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * SyncSkillProvider 单元测试。
 *
 * @author zsg
 * @since 2026-02-26
 */
@ExtendWith(MockitoExtension.class)
class SyncSkillProviderTest {

    @Mock private SyncEngine syncEngine;
    @Mock private SyncScheduler syncScheduler;
    @Mock private SyncProfileRepository profileRepository;
    @Mock private SyncStateRepository stateRepository;
    @Mock private SyncConflictRepository conflictRepository;
    @Mock private SyncRecordRepository recordRepository;
    @Mock private CredentialStore credentialStore;
    @Mock private SyncConnector caldavConnector;
    @Mock private DynamicToolRegistry toolRegistry;
    @Mock private com.lifepilot.prompt.PromptRegistry promptRegistry;

    private SyncSkillProvider provider;
    private SyncProperties properties;

    @BeforeEach
    void setUp() {
        properties = new SyncProperties();
        when(promptRegistry.render("skill/sync")).thenReturn("同步系统提示词");
        provider = new SyncSkillProvider(
                syncEngine, syncScheduler, profileRepository, stateRepository,
                conflictRepository, recordRepository, credentialStore,
                Map.of("caldav", caldavConnector), properties, promptRegistry);
    }

    // ---- provide() 测试 ----

    @Test
    void provide_返回正确的SkillDefinition() {
        SkillDefinition def = provider.provide();

        assertEquals("sync", def.id());
        assertEquals("数据同步", def.name());
        assertEquals("1.0.0", def.version());
        assertEquals(4, def.suggestedTools().size());
        assertTrue(def.suggestedTools().contains("builtin.sync.trigger"));
        assertTrue(def.suggestedTools().contains("builtin.sync.status"));
        assertTrue(def.suggestedTools().contains("builtin.sync.config"));
        assertTrue(def.suggestedTools().contains("builtin.sync.conflicts"));
    }

    // ---- registerTools() 测试 ----

    @Test
    void registerTools_注册4个工具() {
        provider.registerTools(toolRegistry);

        ArgumentCaptor<BuiltinTool> captor = ArgumentCaptor.forClass(BuiltinTool.class);
        verify(toolRegistry, times(4)).registerBuiltinTool(captor.capture());

        List<String> toolIds = captor.getAllValues().stream()
                .map(BuiltinTool::id)
                .toList();
        assertTrue(toolIds.contains("builtin.sync.trigger"));
        assertTrue(toolIds.contains("builtin.sync.status"));
        assertTrue(toolIds.contains("builtin.sync.config"));
        assertTrue(toolIds.contains("builtin.sync.conflicts"));
    }

    // ---- sync-trigger 测试 ----

    @Test
    void syncTrigger_成功触发同步() {
        SyncProfile profile = buildTestProfile("p1");
        SyncResult result = new SyncResult("p1", 3, 2, 1, 1,
                SyncStatus.SUCCESS, null, Instant.now().toString());

        when(profileRepository.findById("p1")).thenReturn(Optional.of(profile));
        when(syncEngine.sync(profile)).thenReturn(result);

        ToolResult toolResult = executeTool("builtin.sync.trigger",
                Map.of("profileId", "p1"));

        assertTrue(toolResult.ok());
        assertEquals("p1", toolResult.getData("profileId"));
        assertEquals(3, (int) toolResult.getData("pulledCount"));
        assertEquals(2, (int) toolResult.getData("pushedCount"));
        assertEquals("SUCCESS", toolResult.getData("status"));
    }

    @Test
    void syncTrigger_配置不存在返回错误() {
        when(profileRepository.findById("nonexistent")).thenReturn(Optional.empty());

        ToolResult toolResult = executeTool("builtin.sync.trigger",
                Map.of("profileId", "nonexistent"));

        assertFalse(toolResult.ok());
        assertTrue(toolResult.error().contains("同步配置不存在"));
    }

    // ---- sync-status 测试 ----

    @Test
    void syncStatus_查询单个配置状态() {
        SyncProfile profile = buildTestProfile("p1");
        SyncState state = new SyncState("s1", "p1", "token-1",
                "2026-02-26T10:00:00Z", SyncStatus.SUCCESS, null,
                Instant.now().toString(), Instant.now().toString());

        when(profileRepository.findById("p1")).thenReturn(Optional.of(profile));
        when(stateRepository.findByProfileId("p1")).thenReturn(Optional.of(state));

        ToolResult toolResult = executeTool("builtin.sync.status",
                Map.of("profileId", "p1"));

        assertTrue(toolResult.ok());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> statuses = toolResult.getData("statuses");
        assertEquals(1, statuses.size());
        assertEquals("p1", statuses.getFirst().get("profileId"));
        assertEquals("SUCCESS", statuses.getFirst().get("lastSyncStatus"));
    }

    @Test
    void syncStatus_查询所有启用配置状态() {
        SyncProfile p1 = buildTestProfile("p1");
        SyncProfile p2 = buildTestProfile("p2");

        when(profileRepository.findAllEnabled()).thenReturn(List.of(p1, p2));
        when(stateRepository.findByProfileId("p1")).thenReturn(Optional.empty());
        when(stateRepository.findByProfileId("p2")).thenReturn(Optional.empty());

        ToolResult toolResult = executeTool("builtin.sync.status", Map.of());

        assertTrue(toolResult.ok());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> statuses = toolResult.getData("statuses");
        assertEquals(2, statuses.size());
        // 未同步过的 profile 应显示 NEVER_SYNCED
        assertEquals("NEVER_SYNCED", statuses.getFirst().get("lastSyncStatus"));
    }

    // ---- sync-config list 测试 ----

    @Test
    void syncConfig_list_返回所有配置() {
        SyncProfile p1 = buildTestProfile("p1");
        when(profileRepository.findAll()).thenReturn(List.of(p1));

        ToolResult toolResult = executeTool("builtin.sync.config",
                Map.of("action", "list"));

        assertTrue(toolResult.ok());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> profiles = toolResult.getData("profiles");
        assertEquals(1, profiles.size());
        assertEquals("p1", profiles.getFirst().get("id"));
    }

    // ---- sync-config create 测试 ----

    @Test
    void syncConfig_create_创建新配置() {
        ToolResult toolResult = executeTool("builtin.sync.config", Map.of(
                "action", "create",
                "name", "我的 CalDAV",
                "connectorType", "caldav",
                "connectionParamsJson", "{\"url\":\"https://cal.example.com\"}"
        ));

        assertTrue(toolResult.ok());
        assertNotNull(toolResult.getData("id"));

        // 验证 profileRepository.create 被调用
        ArgumentCaptor<SyncProfile> captor = ArgumentCaptor.forClass(SyncProfile.class);
        verify(profileRepository).create(captor.capture());
        SyncProfile created = captor.getValue();
        assertEquals("我的 CalDAV", created.name());
        assertEquals("caldav", created.connectorType());
        assertEquals(SyncDirection.BIDIRECTIONAL, created.syncDirection());

        // 验证 syncScheduler.refreshSchedule 被调用
        verify(syncScheduler).refreshSchedule(created.id());
    }

    // ---- sync-config delete 测试 ----

    @Test
    void syncConfig_delete_删除配置及关联数据() {
        SyncProfile profile = buildTestProfile("p1");
        when(profileRepository.findById("p1")).thenReturn(Optional.of(profile));

        ToolResult toolResult = executeTool("builtin.sync.config", Map.of(
                "action", "delete",
                "profileId", "p1"
        ));

        assertTrue(toolResult.ok());
        verify(credentialStore).deleteByProfileId("p1");
        verify(recordRepository).deleteByProfileId("p1");
        verify(profileRepository).delete("p1");
        verify(syncScheduler).refreshSchedule("p1");
    }

    @Test
    void syncConfig_delete_配置不存在返回错误() {
        when(profileRepository.findById("nonexistent")).thenReturn(Optional.empty());

        ToolResult toolResult = executeTool("builtin.sync.config", Map.of(
                "action", "delete",
                "profileId", "nonexistent"
        ));

        assertFalse(toolResult.ok());
        assertTrue(toolResult.error().contains("同步配置不存在"));
    }

    // ---- sync-config test 测试 ----

    @Test
    void syncConfig_test_连接测试成功() {
        SyncProfile profile = buildTestProfile("p1");
        when(profileRepository.findById("p1")).thenReturn(Optional.of(profile));
        when(caldavConnector.testConnection(profile))
                .thenReturn(new ConnectionTestResult(true, 150, null));

        ToolResult toolResult = executeTool("builtin.sync.config", Map.of(
                "action", "test",
                "profileId", "p1"
        ));

        assertTrue(toolResult.ok());
        assertEquals(true, toolResult.getData("success"));
        assertEquals(150L, (long) toolResult.getData("responseTimeMs"));
    }

    @Test
    void syncConfig_test_连接测试失败() {
        SyncProfile profile = buildTestProfile("p1");
        when(profileRepository.findById("p1")).thenReturn(Optional.of(profile));
        when(caldavConnector.testConnection(profile))
                .thenReturn(new ConnectionTestResult(false, 0, "认证失败"));

        ToolResult toolResult = executeTool("builtin.sync.config", Map.of(
                "action", "test",
                "profileId", "p1"
        ));

        assertTrue(toolResult.ok()); // 工具执行成功，但连接测试失败
        assertEquals(false, toolResult.getData("success"));
        assertEquals("认证失败", toolResult.getData("errorMessage"));
    }

    // ---- sync-conflicts list 测试 ----

    @Test
    void syncConflicts_list_返回未解决冲突() {
        SyncConflict conflict = new SyncConflict(
                "c1", "p1", "TodoItem", "local-1",
                "{\"title\":\"本地版本\"}", "{\"title\":\"远程版本\"}",
                SyncConflict.ConflictStatus.UNRESOLVED, null, Instant.now().toString());

        when(conflictRepository.findUnresolvedByProfileId("p1")).thenReturn(List.of(conflict));

        ToolResult toolResult = executeTool("builtin.sync.conflicts", Map.of(
                "action", "list",
                "profileId", "p1"
        ));

        assertTrue(toolResult.ok());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> conflicts = toolResult.getData("conflicts");
        assertEquals(1, conflicts.size());
        assertEquals("c1", conflicts.getFirst().get("id"));
        assertEquals("UNRESOLVED", conflicts.getFirst().get("status"));
    }

    // ---- sync-conflicts resolve 测试 ----

    @Test
    void syncConflicts_resolve_解决冲突() {
        ToolResult toolResult = executeTool("builtin.sync.conflicts", Map.of(
                "action", "resolve",
                "conflictId", "c1"
        ));

        assertTrue(toolResult.ok());
        assertEquals(true, toolResult.getData("resolved"));
        verify(conflictRepository).resolve("c1");
    }

    // ---- 辅助方法 ----

    /** 构建测试用 SyncProfile。 */
    private SyncProfile buildTestProfile(String id) {
        String now = Instant.now().toString();
        return SyncProfile.builder()
                .id(id)
                .name("测试配置-" + id)
                .connectorType("caldav")
                .connectionParamsJson("{}")
                .syncDirection(SyncDirection.BIDIRECTIONAL)
                .conflictPolicy(ConflictPolicy.LAST_WRITE_WINS)
                .cronExpression("0 */15 * * * *")
                .enabled(true)
                .dataTypeFilterJson(null)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    /**
     * 通过 registerTools 注册工具后，按 ID 查找并执行。
     */
    private ToolResult executeTool(String toolId, Map<String, Object> params) {
        // 捕获注册的工具
        ArgumentCaptor<BuiltinTool> captor = ArgumentCaptor.forClass(BuiltinTool.class);
        DynamicToolRegistry registry = mock(DynamicToolRegistry.class);
        provider.registerTools(registry);
        verify(registry, atLeastOnce()).registerBuiltinTool(captor.capture());

        BuiltinTool tool = captor.getAllValues().stream()
                .filter(t -> t.id().equals(toolId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("工具未找到: " + toolId));

        ToolInput input = new ToolInput(toolId, params, JsonSchema.empty(), null);
        return tool.execute(input);
    }
}
