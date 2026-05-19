package com.lifepilot.agent.initiative.execute;

import com.lifepilot.agent.initiative.model.Evidence;
import com.lifepilot.agent.initiative.model.Thought;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * <p>安全约束：
 * <ul>
 *   <li>maxRisk 最高只能是 MEDIUM，HIGH/CRITICAL 永远需要对话确认</li>
 *   <li>只能使用 permission.allowedTools 中的工具</li>
 *   <li>使用受限预算（步数/token/时间）</li>
 *   <li>每次执行结果都通知用户</li>
 * </ul></p>
 *
 * @author zsg
 * @since 2026-06-01
 */
public class ActionExecutor {

    private static final Logger log = LoggerFactory.getLogger(ActionExecutor.class);

    /** 授权存储（内存版，后续可持久化到 SQLite）。 */
    private final ConcurrentHashMap<String, ExecutionPermission> permissions = new ConcurrentHashMap<>();

    /**
     * 尝试执行想法。
     *
     * <p>检查授权 → 有授权则执行 → 无授权则降级为对话。
     * 当前实现为骨架，实际执行需要集成 AgentOrchestrator。</p>
     *
     * @param thought 要执行的想法
     * @return 执行结果
     */
    public ExecutionResult execute(Thought thought) {
        // 从想法中提取 actionPattern（通过 intentKey 前缀推断）
        String actionPattern = extractActionPattern(thought);
        if (actionPattern == null) {
            return ExecutionResult.degradedToConversation(thought);
        }

        // 检查授权
        var permission = findPermission(actionPattern);
        if (permission.isEmpty() || !permission.get().isValid()) {
            log.debug("主动执行: 无有效授权, actionPattern={}, thoughtId={}",
                    actionPattern, thought.id());
            return ExecutionResult.noPermission(thought);
        }

        var perm = permission.get();

        // 安全检查：HIGH/CRITICAL 风险永远需要对话确认
        if (!perm.maxRisk().allowsAutoExecution()) {
            log.debug("主动执行: 风险级别过高, actionPattern={}, maxRisk={}",
                    actionPattern, perm.maxRisk());
            return ExecutionResult.degradedToConversation(thought);
        }

        // 执行（当前为骨架，后续集成 AgentOrchestrator）
        try {
            log.info("主动执行: 开始执行, actionPattern={}, tools={}, thoughtId={}",
                    actionPattern, perm.allowedTools(), thought.id());

            // TODO: 构造受限 AgentRequest 并调用 AgentOrchestrator.run()
            // var request = buildRestrictedRequest(thought, perm);
            // var response = agentOrchestrator.run(request);
            // return ExecutionResult.completed(thought, response.summary(), response.traceId());

            return ExecutionResult.completed(thought,
                    "执行完成（骨架模式）: " + thought.summary(), null);
        } catch (Exception e) {
            log.warn("主动执行: 执行失败, actionPattern={}, error={}",
                    actionPattern, e.getMessage());
            return ExecutionResult.failed(thought, e.getMessage());
        }
    }

    /**
     * 注册执行授权。
     */
    public void grantPermission(ExecutionPermission permission) {
        permissions.put(permission.actionPattern(), permission);
        log.info("主动执行: 授权已注册, actionPattern={}, tools={}",
                permission.actionPattern(), permission.allowedTools());
    }

    /**
     * 撤销执行授权。
     */
    public void revokePermission(String actionPattern) {
        permissions.computeIfPresent(actionPattern, (k, v) ->
                new ExecutionPermission(v.id(), v.actionPattern(), v.description(),
                        v.allowedTools(), v.maxRisk(), false, v.grantedAt(),
                        java.time.Instant.now()));
        log.info("主动执行: 授权已撤销, actionPattern={}", actionPattern);
    }

    /**
     * 查询授权。
     */
    public Optional<ExecutionPermission> findPermission(String actionPattern) {
        var perm = permissions.get(actionPattern);
        return perm != null && perm.isValid() ? Optional.of(perm) : Optional.empty();
    }

    /**
     * 列出所有有效授权。
     */
    public List<ExecutionPermission> listActivePermissions() {
        return permissions.values().stream()
                .filter(ExecutionPermission::isValid)
                .collect(Collectors.toList());
    }

    /**
     * 从想法中提取 actionPattern。
     * intentKey 格式如 "reminder:daily_summary:xxx" → actionPattern = "daily_summary"
     */
    @Nullable
    private String extractActionPattern(Thought thought) {
        String key = thought.intentKey();
        if (key == null || key.isBlank()) return null;
        String[] parts = key.split(":");
        return parts.length >= 2 ? parts[1] : null;
    }
}
