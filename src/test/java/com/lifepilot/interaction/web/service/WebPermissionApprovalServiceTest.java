package com.lifepilot.interaction.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.conversation.transcript.TranscriptStore;
import com.lifepilot.interaction.web.model.PermissionApprovalRequest;
import com.lifepilot.interaction.web.model.PermissionApprovalResponse;
import com.lifepilot.interaction.web.sse.SseEventType;
import com.lifepilot.interaction.web.sse.SseSessionManager;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.permission.model.ExecutionGrant;
import com.lifepilot.permission.model.ExecutionGrantScope;
import com.lifepilot.permission.model.PermissionActionType;
import com.lifepilot.permission.model.PermissionRequest;
import com.lifepilot.permission.model.PermissionSubjectType;
import com.lifepilot.permission.service.PermissionService;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.model.ToolBudget;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.schema.JsonSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * Web 权限审批服务测试。
 *
 * @author zsg
 * @since 2026-03-26
 */
@ExtendWith(MockitoExtension.class)
class WebPermissionApprovalServiceTest {

    @Mock
    private SseSessionManager sseSessionManager;

    @Mock
    private TranscriptStore transcriptStore;

    @Mock
    private PermissionService permissionService;

    @Test
    void 定时任务预授权应落为任务级通用高风险授权() throws Exception {
        var service = new WebPermissionApprovalService(
                sseSessionManager,
                transcriptStore,
                permissionService,
                new ObjectMapper(),
                5
        );
        var requestIdRef = new String[1];
        doAnswer(invocation -> {
            PermissionApprovalRequest payload = invocation.getArgument(2);
            requestIdRef[0] = payload.requestId();
            return null;
        }).when(sseSessionManager).sendEvent(eq("stream-1"), eq(SseEventType.PERMISSION_APPROVAL_REQUEST), any());
        when(permissionService.saveGrant(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var tool = BuiltinTool.builder()
                .id("builtin.cron.create")
                .name("创建定时任务")
                .description("创建 Cron 定时任务")
                .inputSchema(JsonSchema.empty())
                .riskLevel(RiskLevel.LOW)
                .budget(ToolBudget.DEFAULT)
                .executor(_ -> ToolResult.success(Map.of()))
                .build();
        var request = new PermissionRequest(
                "builtin.cron.create",
                PermissionActionType.CREATE_SCHEDULE,
                RiskLevel.LOW,
                "web",
                ExecutionGrantScope.of(Map.of("taskId", "task-1")),
                "session-1",
                null,
                "task-1",
                "user-1",
                "turn-1",
                "trace-1",
                true
        );

        CompletableFuture<ExecutionGrant> future = CompletableFuture.supplyAsync(
                () -> service.requestApproval(tool, request, "stream-1")
        );
        while (requestIdRef[0] == null) {
            TimeUnit.MILLISECONDS.sleep(10);
        }
        service.resolveApproval(
                requestIdRef[0],
                new PermissionApprovalResponse(requestIdRef[0], true, PermissionSubjectType.TASK.name(), "允许")
        );

        ExecutionGrant grant = future.get(2, TimeUnit.SECONDS);

        assertThat(grant).isNotNull();
        assertThat(grant.subjectType()).isEqualTo(PermissionSubjectType.TASK);
        assertThat(grant.subjectId()).isEqualTo("task-1");
        assertThat(grant.actionType()).isEqualTo(PermissionActionType.GENERIC_TOOL_OPERATION);
        assertThat(grant.riskCeiling()).isEqualTo(RiskLevel.CRITICAL);
        assertThat(grant.autonomousAllowed()).isTrue();
        assertThat(grant.channels()).containsExactly("cron", "heartbeat", "workflow");
    }
}
