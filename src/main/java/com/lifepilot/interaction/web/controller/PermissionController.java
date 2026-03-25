package com.lifepilot.interaction.web.controller;

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
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
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
    public List<PermissionGrantInfo> listGrants(@RequestParam(defaultValue = "true") boolean activeOnly,
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
        return grants.stream().map(PermissionGrantInfo::from).toList();
    }

    @PostMapping("/grants")
    public PermissionGrantInfo createGrant(@RequestBody PermissionGrantCreateRequest request) {
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
        return PermissionGrantInfo.from(grant);
    }

    @PostMapping("/approvals/{requestId}")
    public ResponseEntity<?> resolveApproval(@PathVariable String requestId,
                                             @RequestBody PermissionApprovalResponse response) {
        if (permissionApprovalService == null) {
            log.debug("WebPermissionApprovalService 未注入，审批端点不可用");
            return ResponseEntity.notFound().build();
        }
        boolean resolved = permissionApprovalService.resolveApproval(requestId, response);
        return resolved ? ResponseEntity.ok().build() : ResponseEntity.notFound().build();
    }

    @DeleteMapping("/grants/{grantId}")
    public ResponseEntity<?> revokeGrant(@PathVariable String grantId,
                                         @RequestParam(required = false) String revokedBy,
                                         @RequestParam(required = false) String reason) {
        boolean revoked = permissionService.revokeGrant(grantId, revokedBy, reason);
        return revoked ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }
}
