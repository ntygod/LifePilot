package com.lifepilot.agent.initiative.execute;

import com.lifepilot.agent.initiative.model.Evidence;
import com.lifepilot.agent.initiative.model.Thought;
import com.lifepilot.agent.model.AgentRequest;
import com.lifepilot.agent.model.AgentResponse;
import com.lifepilot.agent.model.Budget;
import com.lifepilot.agent.orchestration.AgentOrchestrator;
import com.lifepilot.interaction.model.InteractionSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 主动执行器 — 在用户授权范围内直接执行任务，事后通知结果。
 *
 * <p>与 ConversationInitiator 平行，在 Gatekeeper 之后分流：
 * <ul>
 *   <li>对话类想法 → ConversationInitiator（发起对话）</li>
 *   <li>执行类想法（有授权） → ActionExecutor（直接执行）</li>
 *   <li>执行类想法（无授权） → 降级为 ConversationInitiator</li>
 * </ul></p>
 *
 * @author zsg
 * @since 2026-06-01
 */
public class ActionExecutor {

    private static final Logger log = LoggerFactory.getLogger(ActionExecutor.class);

    @Nullable
    private final AgentOrchestrator agentOrchestrator;

    /** 授权存储（内存版，后续可持久化到 SQLite）。 */
    private final ConcurrentHashMap<String, ExecutionPermission> permissions = new ConcurrentHashMap<>();

    public ActionExecutor(@Nullable AgentOrchestrator agentOrchestrator) {
        this.agentOrchestrator = agentOrchestrator;
    }

    /**
     * 尝试执行想法。
     */
    public ExecutionResult execute(Thought thought) {
        String actionPattern = extractActionPattern(thought);
        if (actionPattern == null) {
            return ExecutionResult.degradedToConversation(thought);
        }

        var permission = findPermission(actionPattern);
        if (permission.isEmpty() || !permission.get().isValid()) {
            log.debug("主动执行: 无有效授权, actionPattern={}", actionPattern);
            return ExecutionResult.noPermission(thought);
        }

        var perm = permission.get();
        if (!perm.maxRisk().allowsAutoExecution()) {
            return ExecutionResult.degradedToConversation(thought);
        }

        if (agentOrchestrator == null) {
            log.warn("主动执行: AgentOrchestrator 不可用，降级为对话模式");
            return ExecutionResult.degradedToConversation(thought);
        }

        try {
            log.info("主动执行: 开始, actionPattern={}, tools={}", actionPattern, perm.allowedTools());

            var request = new AgentRequest(
                    "执行主动任务: " + thought.summary(),
                    "initiative-" + UUID.randomUUID().toString().substring(0, 8),
                    InteractionSource.system("initiative:" + thought.id()),
                    null, null, null, null,
                    null,
                    Budget.builder()
                            .maxSteps(10).stepsUsed(0)
                            .maxTokens(5000).tokensUsed(0).tokensReserved(0)
                            .maxDuration(java.time.Duration.ofSeconds(120))
                            .elapsed(java.time.Duration.ZERO)
                            .build(),
                    null, 0, null,
                    perm.allowedTools(),
                    null, null, null, null
            );

            AgentResponse response = agentOrchestrator.run(request);
            String summary = response.content() != null ? response.content() : "执行完成";
            if (summary.length() > 200) summary = summary.substring(0, 200) + "...";

            return ExecutionResult.completed(thought, summary, response.traceId());
        } catch (Exception e) {
            log.warn("主动执行: 失败, actionPattern={}, error={}", actionPattern, e.getMessage());
            return ExecutionResult.failed(thought, e.getMessage());
        }
    }

    public void grantPermission(ExecutionPermission permission) {
        permissions.put(permission.actionPattern(), permission);
        log.info("主动执行: 授权已注册, actionPattern={}", permission.actionPattern());
    }

    public void revokePermission(String actionPattern) {
        permissions.computeIfPresent(actionPattern, (k, v) ->
                new ExecutionPermission(v.id(), v.actionPattern(), v.description(),
                        v.allowedTools(), v.maxRisk(), false, v.grantedAt(),
                        java.time.Instant.now()));
        log.info("主动执行: 授权已撤销, actionPattern={}", actionPattern);
    }

    public Optional<ExecutionPermission> findPermission(String actionPattern) {
        var perm = permissions.get(actionPattern);
        return perm != null && perm.isValid() ? Optional.of(perm) : Optional.empty();
    }

    public List<ExecutionPermission> listActivePermissions() {
        return permissions.values().stream()
                .filter(ExecutionPermission::isValid)
                .collect(Collectors.toList());
    }

    @Nullable
    private String extractActionPattern(Thought thought) {
        String key = thought.intentKey();
        if (key == null || key.isBlank()) return null;
        String[] parts = key.split(":");
        return parts.length >= 2 ? parts[1] : null;
    }
}
