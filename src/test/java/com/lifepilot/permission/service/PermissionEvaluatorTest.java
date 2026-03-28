package com.lifepilot.permission.service;

import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.permission.model.ExecutionGrant;
import com.lifepilot.permission.model.ExecutionGrantScope;
import com.lifepilot.permission.model.PermissionActionType;
import com.lifepilot.permission.model.PermissionDecisionType;
import com.lifepilot.permission.model.PermissionRequest;
import com.lifepilot.permission.model.PermissionSubjectType;
import com.lifepilot.permission.repository.ExecutionGrantRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 权限判定器测试。
 *
 * @author zsg
 * @since 2026-03-25
 */
@ExtendWith(MockitoExtension.class)
class PermissionEvaluatorTest {

    @Mock
    private ExecutionGrantRepository executionGrantRepository;

    @InjectMocks
    private PermissionEvaluator permissionEvaluator;

    @Test
    void lowRisk_直接放行且不查授权仓储() {
        var request = new PermissionRequest(
                "memory.search",
                PermissionActionType.GENERIC_TOOL_OPERATION,
                RiskLevel.LOW,
                "web",
                ExecutionGrantScope.EMPTY,
                "session-1",
                "workspace-1",
                null,
                "user-1",
                null,
                "trace-1"
        );

        var decision = permissionEvaluator.evaluate(request);

        assertThat(decision.type()).isEqualTo(PermissionDecisionType.PASSED);
        verify(executionGrantRepository, never()).findActiveByActionType(any(), any());
    }

    @Test
    void highRisk_命中会话级授权时放行() {
        var request = new PermissionRequest(
                "file.write",
                PermissionActionType.WRITE_FILE,
                RiskLevel.HIGH,
                "web",
                ExecutionGrantScope.of(Map.of("workspacePath", "D:/WorkSpace/Project/News/docs")),
                "session-1",
                "workspace-1",
                null,
                "user-1",
                null,
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
                Instant.now().plus(1, ChronoUnit.DAYS),
                null,
                null,
                null,
                "user-1",
                null,
                "允许修改当前工作区",
                Map.of(),
                Instant.now(),
                Instant.now()
        );
        when(executionGrantRepository.findActiveByActionType(eq(PermissionActionType.WRITE_FILE), any()))
                .thenReturn(List.of(grant));

        var decision = permissionEvaluator.evaluate(request);

        assertThat(decision.type()).isEqualTo(PermissionDecisionType.PASSED);
        assertThat(decision.matchedGrant()).isNotNull();
        assertThat(decision.matchedGrantId()).isEqualTo(grant.id());
    }

    @Test
    void autonomousCritical_无预授权时阻断() {
        var request = new PermissionRequest(
                "shell.exec",
                PermissionActionType.EXECUTE_SHELL,
                RiskLevel.CRITICAL,
                "cron",
                ExecutionGrantScope.of(Map.of("workspacePath", "D:/WorkSpace/Project/News")),
                "cron:task-1",
                "workspace-1",
                "task-1",
                "user-1",
                null,
                "trace-2"
        );
        when(executionGrantRepository.findActiveByActionTypes(
                eq(List.of(PermissionActionType.EXECUTE_SHELL, PermissionActionType.GENERIC_TOOL_OPERATION)),
                any()))
                .thenReturn(List.of());

        var decision = permissionEvaluator.evaluate(request);

        assertThat(decision.type()).isEqualTo(PermissionDecisionType.BLOCKED);
        assertThat(decision.reason()).isEqualTo("自主执行缺少有效预授权");
    }

    @Test
    void autonomousHigh_无预授权时阻断() {
        var request = new PermissionRequest(
                "http.request",
                PermissionActionType.HTTP_REQUEST,
                RiskLevel.HIGH,
                "heartbeat",
                ExecutionGrantScope.of(Map.of("origin", "https://api.github.com")),
                "heartbeat:main",
                null,
                "task-1",
                "user-1",
                null,
                "trace-3"
        );
        when(executionGrantRepository.findActiveByActionTypes(
                eq(List.of(PermissionActionType.HTTP_REQUEST, PermissionActionType.GENERIC_TOOL_OPERATION)),
                any()))
                .thenReturn(List.of());

        var decision = permissionEvaluator.evaluate(request);

        assertThat(decision.type()).isEqualTo(PermissionDecisionType.BLOCKED);
        assertThat(decision.reason()).isEqualTo("自主执行缺少有效预授权");
    }

    @Test
    void autonomousHigh_命中任务级自主授权时放行() {
        var request = new PermissionRequest(
                "code.execute",
                PermissionActionType.EXECUTE_SHELL,
                RiskLevel.HIGH,
                "cron",
                ExecutionGrantScope.EMPTY,
                "cron:task-1",
                null,
                "task-1",
                "user-1",
                null,
                "trace-3"
        );
        var grant = new ExecutionGrant(
                "grant-" + UUID.randomUUID(),
                PermissionSubjectType.TASK,
                "task-1",
                PermissionActionType.GENERIC_TOOL_OPERATION,
                RiskLevel.HIGH,
                ExecutionGrantScope.EMPTY,
                List.of("cron", "heartbeat", "workflow"),
                true,
                Instant.now().plus(1, ChronoUnit.DAYS),
                null,
                null,
                null,
                "user-1",
                null,
                "允许任务执行高风险工具",
                Map.of(),
                Instant.now(),
                Instant.now()
        );
        when(executionGrantRepository.findActiveByActionTypes(
                eq(List.of(PermissionActionType.EXECUTE_SHELL, PermissionActionType.GENERIC_TOOL_OPERATION)),
                any()))
                .thenReturn(List.of(grant));

        var decision = permissionEvaluator.evaluate(request);

        assertThat(decision.type()).isEqualTo(PermissionDecisionType.PASSED);
        assertThat(decision.matchedGrantId()).isEqualTo(grant.id());
    }

    @Test
    void autonomousCritical_命中任务级自主授权时放行() {
        var request = new PermissionRequest(
                "shell.exec",
                PermissionActionType.EXECUTE_SHELL,
                RiskLevel.CRITICAL,
                "workflow",
                ExecutionGrantScope.EMPTY,
                "workflow:task-1",
                null,
                "task-1",
                "user-1",
                null,
                "trace-3"
        );
        var grant = new ExecutionGrant(
                "grant-" + UUID.randomUUID(),
                PermissionSubjectType.TASK,
                "task-1",
                PermissionActionType.GENERIC_TOOL_OPERATION,
                RiskLevel.CRITICAL,
                ExecutionGrantScope.EMPTY,
                List.of("cron", "heartbeat", "workflow"),
                true,
                Instant.now().plus(1, ChronoUnit.DAYS),
                null,
                null,
                null,
                "user-1",
                null,
                "允许任务执行高风险工具",
                Map.of(),
                Instant.now(),
                Instant.now()
        );
        when(executionGrantRepository.findActiveByActionTypes(
                eq(List.of(PermissionActionType.EXECUTE_SHELL, PermissionActionType.GENERIC_TOOL_OPERATION)),
                any()))
                .thenReturn(List.of(grant));

        var decision = permissionEvaluator.evaluate(request);

        assertThat(decision.type()).isEqualTo(PermissionDecisionType.PASSED);
        assertThat(decision.matchedGrantId()).isEqualTo(grant.id());
    }

    @Test
    void interactiveHigh_无授权时要求审批() {
        var request = new PermissionRequest(
                "browser.navigate",
                PermissionActionType.BROWSER_AUTOMATION,
                RiskLevel.HIGH,
                "web",
                ExecutionGrantScope.of(Map.of("origin", "https://github.com")),
                "session-1",
                null,
                null,
                "user-1",
                null,
                "trace-4"
        );
        when(executionGrantRepository.findActiveByActionType(eq(PermissionActionType.BROWSER_AUTOMATION), any()))
                .thenReturn(List.of());

        var decision = permissionEvaluator.evaluate(request);

        assertThat(decision.type()).isEqualTo(PermissionDecisionType.NEEDS_APPROVAL);
        assertThat(decision.reason()).isEqualTo("高风险操作需要用户授权");
    }

    @Test
    void interactiveScheduleWithAutonomousGrantRequirement_应要求审批() {
        var request = new PermissionRequest(
                "cron.create",
                PermissionActionType.CREATE_SCHEDULE,
                RiskLevel.LOW,
                "web",
                ExecutionGrantScope.of(Map.of("taskId", "task-1")),
                "session-1",
                null,
                "task-1",
                "user-1",
                null,
                "trace-5",
                true
        );

        var decision = permissionEvaluator.evaluate(request);

        assertThat(decision.type()).isEqualTo(PermissionDecisionType.NEEDS_APPROVAL);
        assertThat(decision.reason()).isEqualTo("高风险操作需要用户授权");
    }
}
