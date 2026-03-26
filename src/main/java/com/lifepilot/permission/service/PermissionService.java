package com.lifepilot.permission.service;

import com.lifepilot.permission.model.ExecutionGrant;
import com.lifepilot.permission.model.PermissionDecision;
import com.lifepilot.permission.model.PermissionDecisionEntry;
import com.lifepilot.permission.model.PermissionRequest;
import com.lifepilot.permission.model.PermissionSubjectType;
import com.lifepilot.permission.repository.ExecutionGrantRepository;
import com.lifepilot.permission.repository.PermissionDecisionRepository;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 权限服务。
 *
 * @author zsg
 * @since 2026-03-25
 */
@Service
public class PermissionService {

    private final ExecutionGrantRepository executionGrantRepository;
    private final PermissionDecisionRepository permissionDecisionRepository;
    private final PermissionEvaluator permissionEvaluator;

    public PermissionService(ExecutionGrantRepository executionGrantRepository,
                             PermissionDecisionRepository permissionDecisionRepository,
                             PermissionEvaluator permissionEvaluator) {
        this.executionGrantRepository = executionGrantRepository;
        this.permissionDecisionRepository = permissionDecisionRepository;
        this.permissionEvaluator = permissionEvaluator;
    }

    public ExecutionGrant saveGrant(ExecutionGrant grant) {
        Instant now = Instant.now();
        ExecutionGrant normalized = new ExecutionGrant(
                grant.id() != null && !grant.id().isBlank() ? grant.id() : UUID.randomUUID().toString(),
                grant.subjectType(),
                grant.subjectId(),
                grant.actionType(),
                grant.riskCeiling(),
                grant.scope(),
                grant.channels(),
                grant.autonomousAllowed(),
                grant.expiresAt(),
                grant.revokedAt(),
                grant.revokedBy(),
                grant.revokedReason(),
                grant.createdBy(),
                grant.sourceEntryId(),
                grant.reason(),
                grant.metadata(),
                grant.createdAt() != null ? grant.createdAt() : now,
                now
        );
        executionGrantRepository.save(normalized);
        return normalized;
    }

    public boolean revokeGrant(String grantId, @Nullable String revokedBy, @Nullable String revokedReason) {
        return executionGrantRepository.revoke(grantId, revokedBy, revokedReason, Instant.now()) > 0;
    }

    public Optional<ExecutionGrant> findGrantById(String grantId) {
        return executionGrantRepository.findById(grantId);
    }

    public List<ExecutionGrant> findAllGrants() {
        return executionGrantRepository.findAll();
    }

    public List<ExecutionGrant> findActiveGrants() {
        return executionGrantRepository.findAllActive(Instant.now());
    }

    public List<ExecutionGrant> findGrantsBySubject(PermissionSubjectType subjectType, String subjectId) {
        return executionGrantRepository.findBySubject(subjectType, subjectId);
    }

    public PermissionDecision evaluate(PermissionRequest request) {
        return permissionEvaluator.evaluate(request);
    }

    public PermissionDecisionEntry evaluateAndRecord(PermissionRequest request) {
        PermissionDecision decision = permissionEvaluator.evaluate(request);
        return permissionDecisionRepository.save(request, decision);
    }
}
