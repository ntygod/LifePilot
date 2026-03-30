package com.lifepilot.permission.service;

import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.permission.model.ExecutionGrant;
import com.lifepilot.permission.model.PermissionDecision;
import com.lifepilot.permission.model.PermissionActionType;
import com.lifepilot.permission.model.PermissionRequest;
import com.lifepilot.permission.repository.ExecutionGrantRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * 权限判定器。
 *
 * @author zsg
 * @since 2026-03-25
 */
@Service
public class PermissionEvaluator {

    private final ExecutionGrantRepository executionGrantRepository;

    public PermissionEvaluator(ExecutionGrantRepository executionGrantRepository) {
        this.executionGrantRepository = executionGrantRepository;
    }

    public PermissionDecision evaluate(PermissionRequest request) {
        if (request.actionType() == PermissionActionType.CREATE_SCHEDULE
                && request.requiresAutonomousPreAuthorization()) {
            return fallbackDecision(request);
        }

        if (request.riskLevel().ordinal() <= RiskLevel.MEDIUM.ordinal()) {
            return PermissionDecision.passed(null, "低风险或中风险操作允许直接执行");
        }

        Instant now = Instant.now();
        return findCandidateGrants(request, now).stream()
                .filter(grant -> grant.isActiveAt(now))
                .filter(grant -> grant.matchesSubject(request))
                .filter(grant -> grant.supportsRisk(request.riskLevel()))
                .filter(grant -> grant.supportsChannel(request))
                .filter(grant -> grant.supportsAutonomous(request))
                .filter(grant -> PermissionScopeMatcher.matches(grant.scope(), request.resourceScope()))
                .sorted(Comparator
                        .comparingInt(ExecutionGrant::subjectSpecificity).reversed()
                        .thenComparing(ExecutionGrant::createdAt, Comparator.reverseOrder()))
                .findFirst()
                .<PermissionDecision>map(grant -> PermissionDecision.passed(grant, "命中已有授权"))
                .orElseGet(() -> fallbackDecision(request));
    }

    private List<ExecutionGrant> findCandidateGrants(PermissionRequest request, Instant now) {
        if (request.isAutonomousSource()
                && request.actionType() != PermissionActionType.GENERIC_TOOL_OPERATION
                && request.actionType() != PermissionActionType.CREATE_SCHEDULE) {
            return executionGrantRepository.findActiveByActionTypes(
                    List.of(request.actionType(), PermissionActionType.GENERIC_TOOL_OPERATION),
                    now
            );
        }
        return executionGrantRepository.findActiveByActionType(request.actionType(), now);
    }

    private PermissionDecision fallbackDecision(PermissionRequest request) {
        if (request.isAutonomousSource()) {
            return PermissionDecision.blocked("自主执行缺少有效预授权");
        }
        return PermissionDecision.needsApproval("高风险操作需要用户授权");
    }
}
