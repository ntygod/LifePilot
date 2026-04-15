package com.lifepilot.interaction.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lifepilot.conversation.transcript.TranscriptStore;
import com.lifepilot.interaction.web.model.PermissionApprovalRequest;
import com.lifepilot.interaction.web.model.PermissionApprovalResponse;
import com.lifepilot.interaction.web.sse.SseEventType;
import com.lifepilot.interaction.web.sse.SseSessionManager;
import com.lifepilot.permission.model.ExecutionGrant;
import com.lifepilot.permission.model.PermissionRequest;
import com.lifepilot.permission.model.PermissionSubjectType;
import com.lifepilot.permission.model.PermissionActionType;
import com.lifepilot.permission.model.ExecutionGrantScope;
import com.lifepilot.permission.service.PermissionApprovalService;
import com.lifepilot.permission.service.PermissionService;
import com.lifepilot.tool.ToolContract;
import com.lifepilot.observability.guardrail.RiskLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Web 权限审批服务。
 *
 * <p>通过 SSE 发起审批请求，用户选择授权范围后创建正式的执行授权。</p>
 *
 * @author zsg
 * @since 2026-03-25
 */
public class WebPermissionApprovalService implements PermissionApprovalService {

    private static final Logger log = LoggerFactory.getLogger(WebPermissionApprovalService.class);

    private final SseSessionManager sseSessionManager;
    private final TranscriptStore transcriptStore;
    private final PermissionService permissionService;
    private final ObjectMapper objectMapper;
    private final long approvalTimeoutSeconds;
    private final ConcurrentHashMap<String, CompletableFuture<PermissionApprovalResponse>> pendingApprovals
            = new ConcurrentHashMap<>();

    public WebPermissionApprovalService(SseSessionManager sseSessionManager,
                                        TranscriptStore transcriptStore,
                                        PermissionService permissionService,
                                        ObjectMapper objectMapper,
                                        long approvalTimeoutSeconds) {
        this.sseSessionManager = sseSessionManager;
        this.transcriptStore = transcriptStore;
        this.permissionService = permissionService;
        this.objectMapper = objectMapper;
        this.approvalTimeoutSeconds = approvalTimeoutSeconds;
    }

    @Override
    @Nullable
    public ExecutionGrant requestApproval(ToolContract tool, PermissionRequest request,
                                          @Nullable Map<String, String> approvalContext) {
        String streamId = approvalContext != null ? approvalContext.get("streamId") : null;
        if (streamId == null || streamId.isBlank()) {
            log.debug("审批上下文中无 streamId，Web 审批不适用: toolId={}", tool.id());
            return null;
        }

        String requestId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        var future = new CompletableFuture<PermissionApprovalResponse>();
        pendingApprovals.put(requestId, future);

        var payload = new PermissionApprovalRequest(
                requestId,
                tool.id(),
                tool.name(),
                request.actionType().name(),
                request.riskLevel().name(),
                buildApprovalMessage(tool, request),
                resolveAvailableSubjectTypes(request),
                resolveRecommendedSubjectType(request).name(),
                request.resourceScope().values(),
                streamId,
                now.toString()
        );
        sseSessionManager.sendEvent(streamId, SseEventType.PERMISSION_APPROVAL_REQUEST, payload);
        log.info("权限审批请求已推送: requestId={}, toolId={}, streamId={}", requestId, tool.id(), streamId);

        try {
            PermissionApprovalResponse response = future.get(approvalTimeoutSeconds, TimeUnit.SECONDS);
            ExecutionGrant grant = response.approved()
                    ? createGrant(requestId, request, tool, response, now)
                    : null;
            persistApprovalRecord(requestId, request, tool, response, grant);
            return grant;
        } catch (TimeoutException e) {
            log.info("权限审批超时: requestId={}, toolId={}", requestId, tool.id());
            persistApprovalRecord(requestId, request, tool,
                    new PermissionApprovalResponse(requestId, false, null, "审批超时"), null);
            return null;
        } catch (Exception e) {
            log.error("权限审批异常: requestId={}, toolId={}", requestId, tool.id(), e);
            persistApprovalRecord(requestId, request, tool,
                    new PermissionApprovalResponse(requestId, false, null, "审批异常"), null);
            return null;
        } finally {
            pendingApprovals.remove(requestId);
        }
    }

    public boolean resolveApproval(String requestId, PermissionApprovalResponse response) {
        var future = pendingApprovals.remove(requestId);
        if (future == null) {
            log.debug("权限审批请求不存在或已过期: requestId={}", requestId);
            return false;
        }
        future.complete(response);
        return true;
    }

    private ExecutionGrant createGrant(String requestId,
                                       PermissionRequest request,
                                       ToolContract tool,
                                       PermissionApprovalResponse response,
                                       Instant now) {
        PermissionSubjectType subjectType = PermissionSubjectType.valueOf(response.subjectType());
        String subjectId = request.subjectId(subjectType);
        if (subjectId == null || subjectId.isBlank()) {
            throw new IllegalArgumentException("所选授权范围缺少主体标识: " + subjectType);
        }

        if (request.actionType() == PermissionActionType.CREATE_SCHEDULE) {
            return createTaskPreAuthorization(requestId, request, tool, response, now, subjectType, subjectId);
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("toolId", tool.id());
        metadata.put("toolName", tool.name());
        metadata.put("requestId", requestId);

        return permissionService.saveGrant(new ExecutionGrant(
                null,
                subjectType,
                subjectId,
                request.actionType(),
                request.riskLevel(),
                request.resourceScope(),
                resolveGrantChannels(request, subjectType),
                subjectType == PermissionSubjectType.TASK,
                null,
                null,
                null,
                null,
                request.userId(),
                null,
                response.reason(),
                metadata,
                now,
                now
        ));
    }

    private ExecutionGrant createTaskPreAuthorization(String requestId,
                                                      PermissionRequest request,
                                                      ToolContract tool,
                                                      PermissionApprovalResponse response,
                                                      Instant now,
                                                      PermissionSubjectType subjectType,
                                                      String subjectId) {
        if (subjectType != PermissionSubjectType.TASK) {
            throw new IllegalArgumentException("定时任务预授权仅支持任务范围");
        }
        if (!request.requiresAutonomousPreAuthorization()) {
            throw new IllegalArgumentException("定时任务未标记高风险预授权，无法创建预授权");
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("toolId", tool.id());
        metadata.put("toolName", tool.name());
        metadata.put("requestId", requestId);
        metadata.put("grantKind", "AUTONOMOUS_TASK");

        return permissionService.saveGrant(new ExecutionGrant(
                null,
                PermissionSubjectType.TASK,
                subjectId,
                PermissionActionType.GENERIC_TOOL_OPERATION,
                RiskLevel.CRITICAL,
                ExecutionGrantScope.EMPTY,
                List.of("cron", "heartbeat", "workflow"),
                true,
                null,
                null,
                null,
                null,
                request.userId(),
                null,
                response.reason(),
                metadata,
                now,
                now
        ));
    }

    private List<String> resolveGrantChannels(PermissionRequest request, PermissionSubjectType subjectType) {
        if (subjectType == PermissionSubjectType.TASK) {
            return List.of("cron", "heartbeat", "workflow");
        }
        return request.channelAliases();
    }

    private List<String> resolveAvailableSubjectTypes(PermissionRequest request) {
        if (request.actionType() == PermissionActionType.CREATE_SCHEDULE
                && request.requiresAutonomousPreAuthorization()) {
            return List.of(PermissionSubjectType.TASK.name());
        }
        List<String> subjectTypes = new ArrayList<>();
        if (request.taskId() != null && !request.taskId().isBlank()) {
            subjectTypes.add(PermissionSubjectType.TASK.name());
        }
        if (request.workspaceId() != null && !request.workspaceId().isBlank()) {
            subjectTypes.add(PermissionSubjectType.WORKSPACE.name());
        }
        if (request.sessionId() != null && !request.sessionId().isBlank()) {
            subjectTypes.add(PermissionSubjectType.SESSION.name());
        }
        if (request.userId() != null && !request.userId().isBlank()) {
            subjectTypes.add(PermissionSubjectType.USER.name());
        }
        return List.copyOf(subjectTypes);
    }

    private PermissionSubjectType resolveRecommendedSubjectType(PermissionRequest request) {
        if (request.taskId() != null && !request.taskId().isBlank()
                && request.actionType() == PermissionActionType.CREATE_SCHEDULE) {
            return PermissionSubjectType.TASK;
        }
        if (request.workspaceId() != null && !request.workspaceId().isBlank()) {
            return PermissionSubjectType.WORKSPACE;
        }
        if (request.sessionId() != null && !request.sessionId().isBlank()) {
            return PermissionSubjectType.SESSION;
        }
        return PermissionSubjectType.USER;
    }

    private String buildApprovalMessage(ToolContract tool, PermissionRequest request) {
        if (request.actionType() == PermissionActionType.CREATE_SCHEDULE
                && request.requiresAutonomousPreAuthorization()) {
            return "这个定时任务后续会自主执行高风险操作。若允许，本任务后续运行时将不再重复弹出审批。";
        }
        String intentText = describeToolIntent(tool);
        String actionFamily = describeActionFamily(request);
        if (request.taskId() != null && !request.taskId().isBlank()) {
            return "%s。授权后仅会覆盖“%s”，并可直接复用到当前任务的 Cron、心跳和工作流。"
                    .formatted(intentText, actionFamily);
        }
        return "%s。授权后仅会覆盖“%s”，不会自动放开其他高风险操作。"
                .formatted(intentText, actionFamily);
    }

    private String describeToolIntent(ToolContract tool) {
        return switch (tool.id()) {
            case "code.execute" -> "本次需要运行本地代码";
            case "shell.exec" -> "本次需要执行本地命令";
            case "shell.process" -> "本次需要管理后台进程或会话";
            case "file.write", "file.edit", "file.manage" -> "本次需要修改文件";
            case "web.fetch", "web.search" -> "本次需要访问外部网络";
            default -> "本次需要%s".formatted(tool.name());
        };
    }

    private String describeActionFamily(PermissionRequest request) {
        return switch (request.actionType()) {
            case READ_FILE -> "读取文件";
            case WRITE_FILE -> "修改文件";
            case DELETE_FILE -> "删除文件";
            case EXECUTE_SHELL -> "命令 / 代码执行";
            case BROWSER_AUTOMATION -> "浏览器自动化";
            case HTTP_REQUEST -> "外部网络访问";
            case WRITE_MEMORY -> "长期记忆写入";
            case MODIFY_DATASTORE -> "数据存储修改";
            case CREATE_SCHEDULE -> "定时任务创建或修改";
            case GENERIC_TOOL_OPERATION -> "工具操作";
        };
    }

    private void persistApprovalRecord(String requestId,
                                       PermissionRequest request,
                                       ToolContract tool,
                                       PermissionApprovalResponse response,
                                       @Nullable ExecutionGrant grant) {
        if (request.sessionId() == null || request.sessionId().isBlank()) {
            return;
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("requestId", requestId);
            payload.put("toolId", tool.id());
            payload.put("toolName", tool.name());
            payload.put("actionType", request.actionType().name());
            payload.put("riskLevel", request.riskLevel().name());
            payload.put("approved", response.approved());
            payload.put("subjectType", response.subjectType());
            payload.put("reason", response.reason());
            payload.put("grantId", grant != null ? grant.id() : null);
            payload.put("resourceScope", request.resourceScope().values());
            transcriptStore.appendCustomMessage(
                    request.sessionId(),
                    request.turnId(),
                    "permission-approval",
                    objectMapper.writeValueAsString(payload),
                    request.traceId(),
                    false,
                    true,
                    Instant.now()
            );
        } catch (Exception e) {
            log.warn("权限审批 transcript 持久化失败: requestId={}, error={}", requestId, e.getMessage());
        }
    }
}
