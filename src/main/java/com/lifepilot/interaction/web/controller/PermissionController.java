package com.lifepilot.interaction.web.controller;

import com.lifepilot.interaction.web.model.ApiResponse;
import com.lifepilot.interaction.web.model.PermissionApprovalResponse;
import com.lifepilot.interaction.web.model.PermissionGrantCreateRequest;
import com.lifepilot.interaction.web.model.PermissionGrantInfo;
import com.lifepilot.interaction.web.service.WebPermissionApprovalService;
import com.lifepilot.permission.model.ExecutionGrant;
import com.lifepilot.permission.model.ExecutionGrantScope;
import com.lifepilot.permission.model.PermissionActionType;
import com.lifepilot.permission.model.PermissionSubjectType;
import com.lifepilot.permission.service.PermissionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 权限审批接口。
 *
 * @author zsg
 * @since 2026-03-25
 */
@RestController
@RequestMapping("/api/permissions")
@ConditionalOnProperty(name = "lifepilot.gateway.channels.web.enabled", havingValue = "true")
public class PermissionController {

    private static final Logger log = LoggerFactory.getLogger(PermissionController.class);

    private final PermissionService permissionService;

    @Nullable
    private final WebPermissionApprovalService permissionApprovalService;

    public PermissionController(PermissionService permissionService,
                                @Nullable WebPermissionApprovalService permissionApprovalService) {
        this.permissionService = permissionService;
        this.permissionApprovalService = permissionApprovalService;
    }

    @GetMapping("/grants")
    public ApiResponse<List<PermissionGrantInfo>> listGrants(@RequestParam(defaultValue = "true") boolean activeOnly,
                                                @RequestParam(required = false) String subjectType,
                                                @RequestParam(required = false) String subjectId) {
        List<ExecutionGrant> grants;
        if (subjectType != null && !subjectType.isBlank() && subjectId != null && !subjectId.isBlank()) {
            grants = permissionService.findGrantsBySubject(
                    PermissionSubjectType.valueOf(subjectType.trim().toUpperCase()),
                    subjectId.trim()
            );
        } else {
            grants = activeOnly ? permissionService.findActiveGrants() : permissionService.findAllGrants();
        }
        return ApiResponse.ok(grants.stream().map(PermissionGrantInfo::from).toList());
    }

    @PostMapping("/grants")
    public ApiResponse<PermissionGrantInfo> createGrant(@RequestBody PermissionGrantCreateRequest request) {
        Instant now = Instant.now();
        ExecutionGrant grant = permissionService.saveGrant(new ExecutionGrant(
                null,
                PermissionSubjectType.valueOf(request.subjectType().trim().toUpperCase()),
                request.subjectId().trim(),
                PermissionActionType.valueOf(request.actionType().trim().toUpperCase()),
                com.lifepilot.observability.guardrail.RiskLevel.valueOf(request.riskCeiling().trim().toUpperCase()),
                ExecutionGrantScope.of(request.scope() != null ? request.scope() : Map.of()),
                request.channels() != null ? request.channels() : List.of(),
                request.autonomousAllowed(),
                request.expiresAt(),
                null,
                null,
                null,
                request.createdBy(),
                request.sourceEntryId(),
                request.reason(),
                request.metadata() != null ? request.metadata() : Map.of(),
                now,
                now
        ));
        return ApiResponse.ok(PermissionGrantInfo.from(grant));
    }

    @PostMapping("/approvals/{requestId}")
    public ApiResponse<Void> resolveApproval(@PathVariable String requestId,
                                             @RequestBody PermissionApprovalResponse response) {
        if (permissionApprovalService == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "审批端点不可用");
        }
        boolean resolved = permissionApprovalService.resolveApproval(requestId, response);
        if (!resolved) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "审批请求不存在: id=" + requestId);
        }
        return ApiResponse.ok();
    }

    @DeleteMapping("/grants/{grantId}")
    public ResponseEntity<Void> revokeGrant(@PathVariable String grantId,
                                         @RequestParam(required = false) String revokedBy,
                                         @RequestParam(required = false) String reason) {
        boolean revoked = permissionService.revokeGrant(grantId, revokedBy, reason);
        if (!revoked) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "授权不存在: id=" + grantId);
        }
        return ResponseEntity.noContent().build();
    }
}
