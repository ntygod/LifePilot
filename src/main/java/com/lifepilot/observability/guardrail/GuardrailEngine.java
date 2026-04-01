package com.lifepilot.observability.guardrail;

import com.lifepilot.observability.config.ObservabilityProperties;
import com.lifepilot.observability.trace.GuardrailStep;
import com.lifepilot.observability.trace.TraceContextPropagator;
import com.lifepilot.tool.ToolContract;
import com.lifepilot.tool.model.ToolInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * 护栏策略执行引擎。
 *
 * <p>当前职责仅包含内容安全、速率限制和审计日志。
 * 工具权限、风险判定、用户授权和白名单控制均由权限系统负责，
 * 不再由护栏引擎承担。</p>
 *
 * @author zsg
 * @since 2026-02-27
 */
public class GuardrailEngine {

    private static final Logger log = LoggerFactory.getLogger(GuardrailEngine.class);

    private final JdbcTemplate jdbcTemplate;
    private final TraceContextPropagator propagator;
    private final ObservabilityProperties properties;
    private final ConcurrentHashMap<String, GuardrailPolicy> policies = new ConcurrentHashMap<>();

    // 速率限制计数器（简化实现：分钟级滑动窗口）
    private final AtomicInteger minuteCallCount = new AtomicInteger(0);
    private volatile long minuteWindowStart = System.currentTimeMillis();

    public GuardrailEngine(JdbcTemplate jdbcTemplate,
                           TraceContextPropagator propagator,
                           ObservabilityProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.propagator = propagator;
        this.properties = properties;
    }

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
     * 检查工具调用是否允许。
     *
     * @param tool  工具契约
     * @param input 工具输入
     * @return 检查结果
     */
    public GuardrailResult checkToolCall(ToolContract tool, ToolInput input) {
        var sortedPolicies = enabledPoliciesSorted();
        for (GuardrailPolicy policy : sortedPolicies) {
            try {
                GuardrailResult result = evaluateToolPolicy(policy, input);
                if (result instanceof GuardrailResult.Blocked || result instanceof GuardrailResult.NeedsConfirmation) {
                    recordGuardrailStep(policy.policyId(), "tool_call", result);
                    writeAuditLog(null, tool.id(), policy.policyId(), result);
                    return result;
                }
            } catch (Exception e) {
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

    private GuardrailResult evaluateToolPolicy(GuardrailPolicy policy, ToolInput input) {
        return switch (policy) {
            case ContentSafetyPolicy csp -> evaluateContentSafety(csp, input.parameters().toString());
            case RateLimitPolicy rlp -> evaluateRateLimit(rlp);
            case DataRedactionPolicy _ -> new GuardrailResult.Passed(policy.policyId());
        };
    }

    private GuardrailResult evaluateContentSafety(ContentSafetyPolicy policy, String content) {
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

    private GuardrailResult evaluateRateLimit(RateLimitPolicy policy) {
        long now = System.currentTimeMillis();
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

    private List<GuardrailPolicy> enabledPoliciesSorted() {
        return policies.values().stream()
                .filter(GuardrailPolicy::enabled)
                .sorted(Comparator.comparingInt(GuardrailPolicy::priority))
                .toList();
    }

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

    private void writeAuditLog(String traceId, String toolId, String policyId, GuardrailResult result) {
        try {
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
