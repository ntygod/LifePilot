package com.lifepilot.observability.guardrail;

import com.lifepilot.observability.config.ObservabilityProperties;
import com.lifepilot.observability.trace.GuardrailStep;
import com.lifepilot.observability.trace.TraceContextPropagator;
import com.lifepilot.tool.ToolContract;
import com.lifepilot.tool.config.ToolConfigProperties;
import com.lifepilot.tool.model.ToolInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * 护栏策略执行引擎 — 管理策略注册表，执行工具调用/输入/输出检查。
 *
 * <p>核心职责：
 * <ul>
 *   <li>策略注册表管理（ConcurrentHashMap，支持动态注册/注销）</li>
 *   <li>工具白名单管理（白名单内的工具跳过检查）</li>
 *   <li>按优先级排序遍历启用的策略，短路逻辑（Blocked/NeedsConfirmation 立即返回）</li>
 *   <li>检查结果记录为 GuardrailStep 到当前 TraceContext</li>
 *   <li>Blocked/NeedsConfirmation 结果写入 guardrail_logs 审计日志表</li>
 *   <li>策略执行异常时 fail-open（记录 ERROR 日志，视为 Passed）</li>
 * </ul>
 *
 * @author zsg
 * @since 2026-02-27
 */
public class GuardrailEngine {

    private static final Logger log = LoggerFactory.getLogger(GuardrailEngine.class);

    private final JdbcTemplate jdbcTemplate;
    private final TraceContextPropagator propagator;
    private final ObservabilityProperties properties;
    private final ToolConfigProperties toolConfigProperties;
    private final ConcurrentHashMap<String, GuardrailPolicy> policies = new ConcurrentHashMap<>();
    private final Set<String> allowedTools = ConcurrentHashMap.newKeySet();

    // 信任工作区降级白名单工具
    private static final Set<String> TRUSTED_WORKSPACE_TOOLS = Set.of(
            "builtin.shell.exec", "builtin.code.execute",
            "builtin.file.read", "builtin.file.write", "builtin.file.list",
            "builtin.file.manage");

    // 自主模式通道 — 这些通道下 MEDIUM 及以下风险自动通过
    private static final Set<String> AUTONOMOUS_CHANNELS = Set.of("cron", "heartbeat");

    // 速率限制计数器（简化实现：分钟级滑动窗口）
    private final AtomicInteger minuteCallCount = new AtomicInteger(0);
    private volatile long minuteWindowStart = System.currentTimeMillis();

    public GuardrailEngine(JdbcTemplate jdbcTemplate,
                           TraceContextPropagator propagator,
                           ObservabilityProperties properties,
                           ToolConfigProperties toolConfigProperties) {
        this.jdbcTemplate = jdbcTemplate;
        this.propagator = propagator;
        this.properties = properties;
        this.toolConfigProperties = toolConfigProperties;
    }

    // ─── 策略管理 ───

    /**
     * 注册护栏策略。
     *
     * @param policy 护栏策略
     */
    public void registerPolicy(GuardrailPolicy policy) {
        policies.put(policy.policyId(), policy);
        log.info("护栏策略注册: policyId={}, type={}, priority={}",
                policy.policyId(), policy.getClass().getSimpleName(), policy.priority());
    }

    /**
     * 注销护栏策略。
     *
     * @param policyId 策略 ID
     */
    public void unregisterPolicy(String policyId) {
        var removed = policies.remove(policyId);
        if (removed != null) {
            log.info("护栏策略注销: policyId={}", policyId);
        }
    }

    /**
     * 添加工具白名单。
     *
     * @param toolIds 工具 ID 列表
     */
    public void addAllowedTools(List<String> toolIds) {
        allowedTools.addAll(toolIds);
        log.info("工具白名单添加: toolIds={}", toolIds);
    }

    /**
     * 移除工具白名单。
     *
     * @param toolIds 工具 ID 列表
     */
    public void removeAllowedTools(List<String> toolIds) {
        toolIds.forEach(allowedTools::remove);
        log.info("工具白名单移除: toolIds={}", toolIds);
    }

    // ─── 检查方法 ───

    /**
     * 检查工具调用是否允许。
     *
     * @param tool  工具契约
     * @param input 工具输入
     * @return 检查结果
     */
    public GuardrailResult checkToolCall(ToolContract tool, ToolInput input) {
        // 白名单检查：不在白名单内的工具直接拦截（访问控制）
        if (!allowedTools.contains(tool.id())) {
            return new GuardrailResult.Blocked("access-control",
                    "工具 %s 不在白名单中，禁止调用".formatted(tool.id()),
                    null);
        }

        // infrastructure 低风险工具跳过策略评估和审计日志
        if (tool.tags().contains("infrastructure") && tool.riskLevel() == RiskLevel.LOW) {
            return new GuardrailResult.Passed("infrastructure-low-risk");
        }

        var sortedPolicies = enabledPoliciesSorted();
        for (GuardrailPolicy policy : sortedPolicies) {
            try {
                GuardrailResult result = evaluateToolPolicy(policy, tool, input);
                if (result instanceof GuardrailResult.Blocked || result instanceof GuardrailResult.NeedsConfirmation) {
                    recordGuardrailStep(policy.policyId(), "tool_call", result);
                    writeAuditLog(null, tool.id(), policy.policyId(), result);
                    return result;
                }
            } catch (Exception e) {
                // fail-open：策略执行异常视为 Passed
                log.error("护栏策略执行异常（fail-open）: policyId={}, error={}",
                        policy.policyId(), e.getMessage());
            }
        }

        return new GuardrailResult.Passed("all_policies");
    }

    /**
     * 检查 LLM 输入内容安全。
     *
     * @param content 输入内容
     * @return 检查结果
     */
    public GuardrailResult checkInput(String content) {
        return checkContent(content, "input");
    }

    /**
     * 检查 LLM 输出内容合规。
     *
     * @param content 输出内容
     * @return 检查结果
     */
    public GuardrailResult checkOutput(String content) {
        return checkContent(content, "output");
    }

    // ─── 内部方法 ───

    /**
     * 检查内容安全（输入或输出）。
     */
    private GuardrailResult checkContent(String content, String checkType) {
        var sortedPolicies = enabledPoliciesSorted();
        for (GuardrailPolicy policy : sortedPolicies) {
            if (policy instanceof ContentSafetyPolicy csp) {
                try {
                    GuardrailResult result = evaluateContentSafety(csp, content);
                    if (result instanceof GuardrailResult.Blocked || result instanceof GuardrailResult.NeedsConfirmation) {
                        recordGuardrailStep(policy.policyId(), checkType, result);
                        writeAuditLog(null, null, policy.policyId(), result);
                        return result;
                    }
                } catch (Exception e) {
                    log.error("内容安全策略执行异常（fail-open）: policyId={}, error={}",
                            policy.policyId(), e.getMessage());
                }
            }
        }
        return new GuardrailResult.Passed("content_safety");
    }

    /**
     * 评估单个策略对工具调用的检查结果。
     */
    private GuardrailResult evaluateToolPolicy(GuardrailPolicy policy, ToolContract tool, ToolInput input) {
        return switch (policy) {
            case ToolRiskPolicy trp -> evaluateToolRisk(trp, tool, input);
            case BudgetLimitPolicy blp -> evaluateBudgetLimit(blp);
            case ContentSafetyPolicy csp -> evaluateContentSafety(csp, input.parameters().toString());
            case RateLimitPolicy rlp -> evaluateRateLimit(rlp);
            case DataRedactionPolicy _ -> new GuardrailResult.Passed(policy.policyId());
        };
    }

    /**
     * 评估工具风险策略。
     *
     * <p>优先使用策略中的显式映射，其次使用工具自身声明的风险等级，
     * 最后才降级到策略默认等级。确定基础风险后，依次检查信任工作区降级和自主模式降级。</p>
     */
    private GuardrailResult evaluateToolRisk(ToolRiskPolicy policy, ToolContract tool, ToolInput input) {
        // 优先级：策略显式映射 > 工具自身声明 > 策略默认
        RiskLevel riskLevel;
        if (policy.toolRiskMapping().containsKey(tool.id())) {
            riskLevel = policy.toolRiskMapping().get(tool.id());
        } else if (tool.riskLevel() != null) {
            riskLevel = tool.riskLevel();
        } else {
            riskLevel = policy.defaultRiskLevel();
        }

        // 信任工作区降级
        riskLevel = applyTrustedWorkspaceDowngrade(tool.id(), riskLevel, input);

        // 自主模式降级：cron/heartbeat 通道下 MEDIUM 及以下自动通过，HIGH 降为 MEDIUM
        riskLevel = applyAutonomousChannelDowngrade(riskLevel, input);

        ApprovalMode mode = riskLevel.toApprovalMode();

        return switch (mode) {
            case AUTO -> new GuardrailResult.Passed(policy.policyId());
            case AUTO_WITH_AUDIT -> new GuardrailResult.Passed(policy.policyId());
            case USER_CONFIRM -> new GuardrailResult.NeedsConfirmation(
                    policy.policyId(),
                    "工具 %s 风险等级为 %s，需要用户确认".formatted(tool.id(), riskLevel),
                    mode);
            case USER_CONFIRM_WITH_VERIFICATION -> new GuardrailResult.NeedsConfirmation(
                    policy.policyId(),
                    "工具 %s 风险等级为 %s，需要用户确认并二次验证".formatted(tool.id(), riskLevel),
                    mode);
        };
    }

    /**
     * 自主模式降级 — 在 cron/heartbeat 通道下降低风险等级。
     *
     * <p>定时任务和心跳巡检执行时无人确认，因此：
     * <ul>
     *   <li>MEDIUM 及以下 → 保持不变（自动通过）</li>
     *   <li>HIGH → 降为 MEDIUM（自动通过 + 审计日志）</li>
     *   <li>CRITICAL → 保持 CRITICAL（仍然阻止，记录到审计日志事后审查）</li>
     * </ul>
     */
    private RiskLevel applyAutonomousChannelDowngrade(RiskLevel riskLevel, ToolInput input) {
        String channel = input.getContextValue("channel", String.class).orElse(null);
        if (channel == null || !AUTONOMOUS_CHANNELS.contains(channel)) {
            return riskLevel;
        }
        if (riskLevel == RiskLevel.HIGH) {
            log.info("自主模式降级: channel={}, 原等级=HIGH, 降级为=MEDIUM", channel);
            return RiskLevel.MEDIUM;
        }
        return riskLevel;
    }

    /**
     * 信任工作区降级 — 在信任目录下降低 shell/code 执行的风险等级。
     *
     * <p>仅对 {@link #TRUSTED_WORKSPACE_TOOLS} 白名单中的工具生效。
     * 从 ToolInput 参数中提取执行路径（workingDirectory 或 cwd），
     * 检查是否为信任路径的子目录。</p>
     */
    private RiskLevel applyTrustedWorkspaceDowngrade(String toolId, RiskLevel originalLevel, ToolInput input) {
        var trustedWorkspace = toolConfigProperties.getTrustedWorkspace();
        if (trustedWorkspace.getPaths().isEmpty()) {
            return originalLevel;
        }
        if (!TRUSTED_WORKSPACE_TOOLS.contains(toolId)) {
            return originalLevel;
        }

        // 从参数中提取执行路径
        String execPath = extractWorkingDirectory(input);
        if (execPath == null) {
            return originalLevel;
        }

        // 检查是否在信任目录下
        Path normalizedExecPath = Path.of(execPath).toAbsolutePath().normalize();
        boolean trusted = trustedWorkspace.getPaths().stream()
                .map(p -> Path.of(p).toAbsolutePath().normalize())
                .anyMatch(normalizedExecPath::startsWith);

        if (!trusted) {
            return originalLevel;
        }

        RiskLevel downgraded;
        try {
            downgraded = RiskLevel.valueOf(trustedWorkspace.getDowngradeLevel());
        } catch (IllegalArgumentException e) {
            log.warn("信任工作区降级等级配置无效: level={}", trustedWorkspace.getDowngradeLevel());
            return originalLevel;
        }

        if (downgraded.ordinal() < originalLevel.ordinal()) {
            log.info("信任工作区降级: tool={}, 原等级={}, 降级为={}, path={}",
                    toolId, originalLevel, downgraded, execPath);
            writeDowngradeAuditLog(toolId, originalLevel, downgraded, execPath);
            return downgraded;
        }

        return originalLevel;
    }

    /**
     * 从 ToolInput 参数中提取工作目录路径。
     */
    private String extractWorkingDirectory(ToolInput input) {
        if (input == null || input.parameters() == null) {
            return null;
        }
        var params = input.parameters();
        // 优先 workingDirectory，其次 cwd
        Object wd = ((Map<?, ?>) params).get("workingDirectory");
        if (wd instanceof String s && !s.isBlank()) return s;
        Object cwd = ((Map<?, ?>) params).get("cwd");
        if (cwd instanceof String s && !s.isBlank()) return s;
        return null;
    }

    /**
     * 记录信任工作区降级审计日志。
     */
    private void writeDowngradeAuditLog(String toolId, RiskLevel original, RiskLevel downgraded, String path) {
        try {
            String traceId = propagator.current().map(ctx -> ctx.traceId()).orElse(null);
            jdbcTemplate.update("""
                    INSERT INTO guardrail_logs (trace_id, tool_id, policy_id, result_type,
                        reason, risk_level, approval_mode, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    traceId, toolId, "trusted-workspace-downgrade", "DOWNGRADED",
                    "信任工作区降级: %s → %s, path=%s".formatted(original, downgraded, path),
                    original.name(), downgraded.toApprovalMode().name(), Instant.now().toString());
        } catch (Exception e) {
            log.warn("降级审计日志写入失败: toolId={}, error={}", toolId, e.getMessage());
        }
    }

    /**
     * 评估预算限制策略。
     */
    private GuardrailResult evaluateBudgetLimit(BudgetLimitPolicy policy) {
        // 从当前 TraceContext 获取已消耗 Token
        var ctx = propagator.current().orElse(null);
        if (ctx != null) {
            int consumed = ctx.totalInputTokens() + ctx.totalOutputTokens();
            if (consumed >= policy.dailyTokenLimit()) {
                return new GuardrailResult.Blocked(
                        policy.policyId(),
                        "每日 Token 上限已达到: consumed=%d, limit=%d".formatted(consumed, policy.dailyTokenLimit()),
                        RiskLevel.HIGH);
            }
        }
        return new GuardrailResult.Passed(policy.policyId());
    }

    /**
     * 评估内容安全策略。
     */
    private GuardrailResult evaluateContentSafety(ContentSafetyPolicy policy, String content) {
        // 检查阻断正则模式
        for (String pattern : policy.blockedPatterns()) {
            try {
                if (Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(content).find()) {
                    return new GuardrailResult.Blocked(
                            policy.policyId(),
                            "内容匹配阻断模式: " + pattern,
                            RiskLevel.HIGH);
                }
            } catch (Exception e) {
                log.warn("阻断模式编译失败，跳过: pattern={}, error={}", pattern, e.getMessage());
            }
        }

        // 检查敏感话题
        for (String topic : policy.sensitiveTopics()) {
            if (content.toLowerCase().contains(topic.toLowerCase())) {
                return new GuardrailResult.Blocked(
                        policy.policyId(),
                        "内容包含敏感话题: " + topic,
                        RiskLevel.MEDIUM);
            }
        }

        return new GuardrailResult.Passed(policy.policyId());
    }

    /**
     * 评估速率限制策略。
     */
    private GuardrailResult evaluateRateLimit(RateLimitPolicy policy) {
        long now = System.currentTimeMillis();
        // 简化实现：分钟级滑动窗口
        if (now - minuteWindowStart > 60_000) {
            minuteCallCount.set(0);
            minuteWindowStart = now;
        }
        int count = minuteCallCount.incrementAndGet();
        if (count > policy.maxCallsPerMinute()) {
            return new GuardrailResult.Blocked(
                    policy.policyId(),
                    "每分钟调用次数超限: count=%d, limit=%d".formatted(count, policy.maxCallsPerMinute()),
                    RiskLevel.MEDIUM);
        }
        return new GuardrailResult.Passed(policy.policyId());
    }

    /**
     * 获取按优先级排序的启用策略列表。
     */
    private List<GuardrailPolicy> enabledPoliciesSorted() {
        return policies.values().stream()
                .filter(GuardrailPolicy::enabled)
                .sorted(Comparator.comparingInt(GuardrailPolicy::priority))
                .toList();
    }

    /**
     * 记录 GuardrailStep 到当前 TraceContext。
     */
    private void recordGuardrailStep(String policyId, String checkType, GuardrailResult result) {
        propagator.current().ifPresent(ctx -> {
            var step = switch (result) {
                case GuardrailResult.Passed _ -> new GuardrailStep(
                        ctx.steps().size(), Instant.now(), Duration.ZERO,
                        policyId, checkType, true, null,
                        RiskLevel.LOW, ApprovalMode.AUTO);
                case GuardrailResult.Blocked blocked -> new GuardrailStep(
                        ctx.steps().size(), Instant.now(), Duration.ZERO,
                        policyId, checkType, false, blocked.reason(),
                        blocked.riskLevel(), blocked.riskLevel().toApprovalMode());
                case GuardrailResult.NeedsConfirmation confirm -> new GuardrailStep(
                        ctx.steps().size(), Instant.now(), Duration.ZERO,
                        policyId, checkType, false, confirm.message(),
                        RiskLevel.HIGH, confirm.approvalMode());
            };
            ctx.addStep(step);
        });
    }

    /**
     * 写入审计日志到 guardrail_logs 表。
     */
    private void writeAuditLog(String traceId, String toolId, String policyId, GuardrailResult result) {
        try {
            // 从当前 TraceContext 获取 traceId
            String actualTraceId = traceId;
            if (actualTraceId == null) {
                actualTraceId = propagator.current().map(ctx -> ctx.traceId()).orElse(null);
            }

            String resultType = switch (result) {
                case GuardrailResult.Passed _ -> "PASSED";
                case GuardrailResult.Blocked _ -> "BLOCKED";
                case GuardrailResult.NeedsConfirmation _ -> "NEEDS_CONFIRMATION";
            };

            String reason = switch (result) {
                case GuardrailResult.Blocked blocked -> blocked.reason();
                case GuardrailResult.NeedsConfirmation confirm -> confirm.message();
                default -> null;
            };

            String riskLevel = switch (result) {
                case GuardrailResult.Blocked blocked -> blocked.riskLevel().name();
                default -> null;
            };

            String approvalMode = switch (result) {
                case GuardrailResult.NeedsConfirmation confirm -> confirm.approvalMode().name();
                default -> null;
            };

            jdbcTemplate.update("""
                    INSERT INTO guardrail_logs (trace_id, tool_id, policy_id, result_type,
                        reason, risk_level, approval_mode, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    actualTraceId, toolId, policyId, resultType,
                    reason, riskLevel, approvalMode, Instant.now().toString());

        } catch (Exception e) {
            log.warn("审计日志写入失败: policyId={}, error={}", policyId, e.getMessage());
        }
    }
}
