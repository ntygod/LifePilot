package com.lifepilot.permission.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.permission.model.ExecutionGrant;
import com.lifepilot.permission.model.ExecutionGrantScope;
import com.lifepilot.permission.model.PermissionActionType;
import com.lifepilot.permission.model.PermissionDecision;
import com.lifepilot.permission.model.PermissionDecisionType;
import com.lifepilot.permission.model.PermissionRequest;
import com.lifepilot.permission.model.PermissionSubjectType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 权限判定仓储集成测试。
 *
 * @author zsg
 * @since 2026-03-25
 */
@SpringBootTest(classes = PermissionDecisionRepositoryTest.TestApp.class)
@ActiveProfiles("test")
class PermissionDecisionRepositoryTest {

    @Configuration
    @EnableAutoConfiguration(exclude = {
            org.springframework.boot.autoconfigure.data.jdbc.JdbcRepositoriesAutoConfiguration.class,
            org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration.class
    })
    @Import(com.lifepilot.config.DataSourceConfig.class)
    static class TestApp {
    }

    private static final String DB_ID = UUID.randomUUID().toString().substring(0, 8);

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry) {
        var tmpDir = System.getProperty("java.io.tmpdir");
        var dbPath = Path.of(tmpDir, "lifepilot-permission-decision-test-" + DB_ID)
                .toString().replace("\\", "/") + ".db";
        var vecDbPath = Path.of(tmpDir, "lifepilot-permission-decision-vec-test-" + DB_ID)
                .toString().replace("\\", "/") + ".db";
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + dbPath);
        registry.add("lifepilot.memory.vector-db-url", () -> "jdbc:sqlite:" + vecDbPath);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private PermissionDecisionRepository repository;

    @BeforeEach
    void setUp() {
        repository = new PermissionDecisionRepository(jdbcTemplate, objectMapper);
    }

    @Test
    void saveAndFindBySessionId_能记录命中授权的判定() {
        var request = new PermissionRequest(
                "builtin.file.write",
                PermissionActionType.WRITE_FILE,
                RiskLevel.HIGH,
                "web",
                ExecutionGrantScope.of(Map.of("workspacePath", "D:/WorkSpace/Project/News")),
                "session-1",
                "workspace-1",
                null,
                "user-1",
                "trace-1"
        );
        var grant = new ExecutionGrant(
                "grant-" + UUID.randomUUID(),
                PermissionSubjectType.SESSION,
                "session-1",
                PermissionActionType.WRITE_FILE,
                RiskLevel.HIGH,
                ExecutionGrantScope.of(Map.of("workspacePath", "D:/WorkSpace/Project/News")),
                List.of("web"),
                false,
                Instant.now().plusSeconds(3600),
                null,
                null,
                null,
                "user-1",
                "entry-1",
                "允许写文件",
                Map.of(),
                Instant.now(),
                Instant.now()
        );

        repository.save(request, PermissionDecision.passed(grant, "命中有效授权"));

        var entries = repository.findBySessionId("session-1");
        assertThat(entries).hasSize(1);
        assertThat(entries.getFirst().decisionType()).isEqualTo(PermissionDecisionType.PASSED);
        assertThat(entries.getFirst().matchedGrantId()).isEqualTo(grant.id());
        assertThat(entries.getFirst().matchedSubjectType()).isEqualTo(PermissionSubjectType.SESSION);
        assertThat(entries.getFirst().resourceScope().values())
                .containsEntry("workspacePath", "D:/WorkSpace/Project/News");
    }

    @Test
    void findByTaskId_能读取任务级阻断记录() {
        var request = new PermissionRequest(
                "builtin.shell.exec",
                PermissionActionType.EXECUTE_SHELL,
                RiskLevel.CRITICAL,
                "cron",
                ExecutionGrantScope.of(Map.of("workspacePath", "D:/WorkSpace/Project/News")),
                "cron:task-1",
                "workspace-1",
                "task-1",
                "user-1",
                "trace-2"
        );

        repository.save(request, PermissionDecision.blocked("自主执行缺少有效预授权"));

        var entries = repository.findByTaskId("task-1");
        assertThat(entries).hasSize(1);
        assertThat(entries.getFirst().decisionType()).isEqualTo(PermissionDecisionType.BLOCKED);
        assertThat(entries.getFirst().matchedGrantId()).isNull();
        assertThat(entries.getFirst().reason()).isEqualTo("自主执行缺少有效预授权");
    }
}
