package com.lifepilot.memory.workspace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.memory.store.workspace.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SessionWorkspaceServiceTest {

    private SingleConnectionDataSource dataSource;
    private SessionWorkspaceService workspaceService;

    @BeforeEach
    void setUp() {
        dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", true);
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
                CREATE TABLE session_workspace_items (
                    id TEXT PRIMARY KEY,
                    session_id TEXT NOT NULL,
                    kind TEXT NOT NULL,
                    title TEXT NOT NULL,
                    summary TEXT NOT NULL,
                    payload_json TEXT,
                    status TEXT NOT NULL,
                    priority INTEGER NOT NULL DEFAULT 0,
                    task_id TEXT,
                    source_trace_id TEXT,
                    expires_at TEXT,
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                )
                """);

        WorkspaceProperties properties = new WorkspaceProperties();
        properties.setPendingDecisionTtlHours(1);
        properties.setTaskStateTtlHours(1);
        properties.setWorkingSetTtlHours(1);
        properties.setTerminalRetentionHours(0);
        workspaceService = new SessionWorkspaceService(jdbcTemplate, new ObjectMapper(), properties);
    }

    @Test
    void 保存待确认条目后可按优先级读取() {
        workspaceService.savePendingDecision("s1", new PendingDecisionItem(
                "确认删除",
                "等待用户批准删除操作",
                Map.of("toolId", "file.delete"),
                80,
                "task-1",
                "trace-1",
                Instant.now().plusSeconds(3600)));

        workspaceService.saveTaskState("s1", new TaskStateItem(
                "等待远端任务",
                "等待远端代理返回结果",
                Map.of("remoteTaskId", "r-1"),
                20,
                "task-2",
                "trace-2",
                Instant.now().plusSeconds(3600)));

        var items = workspaceService.listActive("s1");
        assertThat(items).hasSize(2);
        assertThat(items.getFirst().kind()).isEqualTo(WorkspaceItemKind.PENDING_DECISION);
        assertThat(items.getFirst().summary()).contains("批准");
    }

    @Test
    void 相同taskId会更新而不是新增() {
        WorkspaceItem first = workspaceService.saveTaskState("s1", new TaskStateItem(
                "任务继续中",
                "第一版摘要",
                Map.of("step", 1),
                30,
                "task-1",
                "trace-1",
                Instant.now().plusSeconds(3600)));

        WorkspaceItem updated = workspaceService.saveTaskState("s1", new TaskStateItem(
                "任务继续中",
                "第二版摘要",
                Map.of("step", 2),
                40,
                "task-1",
                "trace-1",
                Instant.now().plusSeconds(3600)));

        var items = workspaceService.listActive("s1");
        assertThat(items).hasSize(1);
        assertThat(updated.id()).isEqualTo(first.id());
        assertThat(items.getFirst().summary()).isEqualTo("第二版摘要");
        assertThat(items.getFirst().priority()).isEqualTo(40);
    }

    @Test
    void payload无法序列化时保存应失败() {
        Map<String, Object> payload = new HashMap<>();
        payload.put("self", payload);

        assertThatThrownBy(() -> workspaceService.saveTaskState("s1", new TaskStateItem(
                "任务继续中",
                "包含无法序列化的 payload",
                payload,
                30,
                "task-broken-payload",
                "trace-broken-payload",
                Instant.now().plusSeconds(3600))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("工作区 payload 序列化失败");
    }

    @Test
    void 过期与解决状态会被清理掉() {
        workspaceService.saveWorkingSet("s1", new WorkingSetItem(
                "候选集合",
                "待用户下一轮选择",
                Map.of("count", 3),
                10,
                "task-3",
                "trace-3",
                Instant.now().minusSeconds(30)));

        workspaceService.saveTaskState("s1", new TaskStateItem(
                "等待任务",
                "仍在进行",
                Map.of(),
                20,
                "task-4",
                "trace-4",
                Instant.now().plusSeconds(3600)));

        int expired = workspaceService.expireDueItems();
        int resolved = workspaceService.resolveByTaskId("s1", "task-4");
        int purged = workspaceService.purgeTerminalItems();

        assertThat(expired).isEqualTo(1);
        assertThat(resolved).isEqualTo(1);
        assertThat(purged).isEqualTo(2);
        assertThat(workspaceService.listActive("s1")).isEmpty();
    }
}
