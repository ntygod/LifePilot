package com.lifepilot.permission.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.permission.model.ExecutionGrant;
import com.lifepilot.permission.model.ExecutionGrantScope;
import com.lifepilot.permission.model.PermissionActionType;
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
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 执行授权仓储集成测试。
 *
 * @author zsg
 * @since 2026-03-25
 */
@SpringBootTest(classes = ExecutionGrantRepositoryTest.TestApp.class)
@ActiveProfiles("test")
class ExecutionGrantRepositoryTest {

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
        var dbPath = Path.of(tmpDir, "lifepilot-execution-grant-test-" + DB_ID)
                .toString().replace("\\", "/") + ".db";
        var vecDbPath = Path.of(tmpDir, "lifepilot-execution-grant-vec-test-" + DB_ID)
                .toString().replace("\\", "/") + ".db";
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + dbPath);
        registry.add("lifepilot.memory.store.vector-db-url", () -> "jdbc:sqlite:" + vecDbPath);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    private ExecutionGrantRepository repository;

    @BeforeEach
    void setUp() {
        repository = new ExecutionGrantRepository(jdbcTemplate, objectMapper);
    }

    @Test
    void saveAndFindById_可完整读写授权记录() {
        var createdAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        var grant = new ExecutionGrant(
                "grant-" + UUID.randomUUID(),
                PermissionSubjectType.SESSION,
                "session-1",
                PermissionActionType.WRITE_FILE,
                RiskLevel.HIGH,
                ExecutionGrantScope.of(Map.of("workspacePath", "D:/WorkSpace/Project/News")),
                List.of("web"),
                false,
                createdAt.plus(2, ChronoUnit.DAYS),
                null,
                null,
                null,
                "user-1",
                "entry-1",
                "允许当前会话修改工作区文件",
                Map.of("source", "manual"),
                createdAt,
                createdAt
        );

        repository.save(grant);

        var found = repository.findById(grant.id());
        assertThat(found).isPresent();
        assertThat(found.get().subjectType()).isEqualTo(PermissionSubjectType.SESSION);
        assertThat(found.get().actionType()).isEqualTo(PermissionActionType.WRITE_FILE);
        assertThat(found.get().riskCeiling()).isEqualTo(RiskLevel.HIGH);
        assertThat(found.get().scope().values()).containsEntry("workspacePath", "D:/WorkSpace/Project/News");
        assertThat(found.get().channels()).containsExactly("web");
        assertThat(found.get().metadata()).containsEntry("source", "manual");
    }

    @Test
    void findActiveByActionType_会过滤已撤销和已过期授权() {
        var now = Instant.now().truncatedTo(ChronoUnit.SECONDS);

        var activeGrant = new ExecutionGrant(
                "grant-active-" + UUID.randomUUID(),
                PermissionSubjectType.USER,
                "user-1",
                PermissionActionType.EXECUTE_SHELL,
                RiskLevel.HIGH,
                ExecutionGrantScope.EMPTY,
                List.of("*"),
                true,
                now.plus(1, ChronoUnit.DAYS),
                null,
                null,
                null,
                "user-1",
                null,
                "允许执行 shell",
                Map.of(),
                now,
                now
        );
        var expiredGrant = new ExecutionGrant(
                "grant-expired-" + UUID.randomUUID(),
                PermissionSubjectType.USER,
                "user-1",
                PermissionActionType.EXECUTE_SHELL,
                RiskLevel.HIGH,
                ExecutionGrantScope.EMPTY,
                List.of("*"),
                true,
                now.minus(1, ChronoUnit.DAYS),
                null,
                null,
                null,
                "user-1",
                null,
                "已过期授权",
                Map.of(),
                now.minus(2, ChronoUnit.DAYS),
                now.minus(2, ChronoUnit.DAYS)
        );
        var revokedGrant = new ExecutionGrant(
                "grant-revoked-" + UUID.randomUUID(),
                PermissionSubjectType.USER,
                "user-1",
                PermissionActionType.EXECUTE_SHELL,
                RiskLevel.HIGH,
                ExecutionGrantScope.EMPTY,
                List.of("*"),
                true,
                now.plus(1, ChronoUnit.DAYS),
                now.minus(1, ChronoUnit.HOURS),
                "user-1",
                "撤销",
                "user-1",
                null,
                "已撤销授权",
                Map.of(),
                now.minus(2, ChronoUnit.DAYS),
                now.minus(1, ChronoUnit.HOURS)
        );

        repository.save(activeGrant);
        repository.save(expiredGrant);
        repository.save(revokedGrant);

        var found = repository.findActiveByActionType(PermissionActionType.EXECUTE_SHELL, now);

        assertThat(found).extracting(ExecutionGrant::id).contains(activeGrant.id());
        assertThat(found).extracting(ExecutionGrant::id)
                .doesNotContain(expiredGrant.id(), revokedGrant.id());
    }
}
