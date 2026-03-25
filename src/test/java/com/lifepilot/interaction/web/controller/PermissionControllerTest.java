package com.lifepilot.interaction.web.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.interaction.web.model.PermissionApprovalResponse;
import com.lifepilot.interaction.web.model.PermissionGrantCreateRequest;
import com.lifepilot.permission.model.ExecutionGrant;
import com.lifepilot.permission.model.ExecutionGrantScope;
import com.lifepilot.permission.model.PermissionActionType;
import com.lifepilot.permission.model.PermissionSubjectType;
import com.lifepilot.permission.service.PermissionService;
import com.lifepilot.interaction.web.service.WebPermissionApprovalService;
import com.lifepilot.observability.guardrail.RiskLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PermissionController 单元测试。
 *
 * @author zsg
 * @since 2026-03-25
 */
@ExtendWith(MockitoExtension.class)
class PermissionControllerTest {

    @Mock
    private PermissionService permissionService;

    @Mock
    private WebPermissionApprovalService webPermissionApprovalService;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        var controller = new PermissionController(permissionService, webPermissionApprovalService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void 查询有效授权列表_返回授权详情() throws Exception {
        var now = Instant.parse("2026-03-25T12:00:00Z");
        when(permissionService.findActiveGrants()).thenReturn(List.of(new ExecutionGrant(
                "grant-1",
                PermissionSubjectType.WORKSPACE,
                "D:/WorkSpace/Project/News",
                PermissionActionType.WRITE_FILE,
                RiskLevel.HIGH,
                ExecutionGrantScope.of(Map.of("workspacePath", "D:/WorkSpace/Project/News")),
                List.of("web"),
                false,
                now.plusSeconds(3600),
                null,
                null,
                null,
                "user-1",
                "entry-1",
                "允许修改当前项目文件",
                Map.of("source", "manual"),
                now,
                now
        )));

        mockMvc.perform(get("/api/permissions/grants"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("grant-1"))
                .andExpect(jsonPath("$[0].subjectType").value("WORKSPACE"))
                .andExpect(jsonPath("$[0].actionType").value("WRITE_FILE"))
                .andExpect(jsonPath("$[0].scope.workspacePath").value("D:/WorkSpace/Project/News"))
                .andExpect(jsonPath("$[0].autonomousAllowed").value(false));
    }

    @Test
    void 创建授权_返回保存后的授权() throws Exception {
        var now = Instant.parse("2026-03-25T12:00:00Z");
        var saved = new ExecutionGrant(
                "grant-2",
                PermissionSubjectType.TASK,
                "task-1",
                PermissionActionType.EXECUTE_SHELL,
                RiskLevel.HIGH,
                ExecutionGrantScope.of(Map.of("workspacePath", "D:/WorkSpace/Project/News")),
                List.of("cron", "heartbeat", "workflow"),
                true,
                now.plusSeconds(7200),
                null,
                null,
                null,
                "user-1",
                null,
                "允许任务自动执行 Shell",
                Map.of("source", "manual"),
                now,
                now
        );
        when(permissionService.saveGrant(any())).thenReturn(saved);

        var request = new PermissionGrantCreateRequest(
                "TASK",
                "task-1",
                "EXECUTE_SHELL",
                "HIGH",
                Map.of("workspacePath", "D:/WorkSpace/Project/News"),
                List.of("cron", "heartbeat", "workflow"),
                true,
                now.plusSeconds(7200),
                "user-1",
                null,
                "允许任务自动执行 Shell",
                Map.of("source", "manual")
        );

        mockMvc.perform(post("/api/permissions/grants")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("grant-2"))
                .andExpect(jsonPath("$.subjectType").value("TASK"))
                .andExpect(jsonPath("$.autonomousAllowed").value(true))
                .andExpect(jsonPath("$.channels[0]").value("cron"));
    }

    @Test
    void 撤销授权_存在时返回204() throws Exception {
        when(permissionService.revokeGrant("grant-3", "user-1", "撤销测试")).thenReturn(true);

        mockMvc.perform(delete("/api/permissions/grants/grant-3")
                        .param("revokedBy", "user-1")
                        .param("reason", "撤销测试"))
                .andExpect(status().isNoContent());

        verify(permissionService).revokeGrant("grant-3", "user-1", "撤销测试");
    }

    @Test
    void 审批请求存在时_提交审批返回200() throws Exception {
        when(webPermissionApprovalService.resolveApproval("req-1",
                new PermissionApprovalResponse("req-1", true, "SESSION", null)))
                .thenReturn(true);

        mockMvc.perform(post("/api/permissions/approvals/req-1")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(
                                new PermissionApprovalResponse("req-1", true, "SESSION", null))))
                .andExpect(status().isOk());
    }
}
