package com.lifepilot.tool.pipeline;

import com.lifepilot.observability.guardrail.GuardrailEngine;
import com.lifepilot.observability.guardrail.GuardrailResult;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.permission.model.ExecutionGrantScope;
import com.lifepilot.permission.model.PermissionActionType;
import com.lifepilot.permission.model.PermissionDecisionEntry;
import com.lifepilot.permission.model.PermissionDecisionType;
import com.lifepilot.permission.model.PermissionRequest;
import com.lifepilot.permission.service.PermissionApprovalService;
import com.lifepilot.permission.service.PermissionRequestFactory;
import com.lifepilot.permission.service.PermissionService;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.model.ToolBudget;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import net.jqwik.api.*;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 工具重试策略属性测试。
 *
 * @author zsg
 * @since 2026-03-05
 */
class ToolRetryPolicyPropertyTest {

    private static final List<String> NORMAL_TAG_POOL = List.of(
            "builtin", "data", "query", "multi-agent", "schedule",
            "habit", "memory", "search", "mcp", "yaml"
    );

    private static final List<String> RETRYABLE_ERROR_KEYWORDS = List.of(
            "执行超时: 30秒", "连接失败: host unreachable",
            "临时性错误: service unavailable",
            "timeout: 30s", "connection refused",
            "temporary failure"
    );

    @Property(tries = 50)
    void 普通工具重试保持_任意普通tags时按策略重试(
            @ForAll("normalTagSets") List<String> tags,
            @ForAll("retryableErrors") String errorMessage) {

        var infra = buildPipelineInfra();

        var callCount = new AtomicInteger(0);
        String toolId = "builtin.test.normal";
        int maxRetries = 2;
        var tool = BuiltinTool.builder()
                .id(toolId)
                .name("test-normal")
                .description("测试普通工具")
                .tags(tags)
                .idempotent(false)
                .budget(ToolBudget.of(Duration.ofSeconds(5), maxRetries, Integer.MAX_VALUE))
                .executor(input -> {
                    callCount.incrementAndGet();
                    return ToolResult.error(errorMessage);
                })
                .build();
        infra.registry.registerBuiltinTool(tool);

        infra.pipeline.execute(toolId, Map.of(), "trace-pbt", null);

        assertEquals(1 + maxRetries, callCount.get(),
                "普通工具（tags=" + tags + "）应执行 " + (1 + maxRetries)
                        + " 次（1 初始 + " + maxRetries + " 重试），但实际执行了 "
                        + callCount.get() + " 次（错误消息: " + errorMessage + "）");
    }

    @Property(tries = 30)
    void 普通工具不可重试错误_不触发重试(
            @ForAll("normalTagSets") List<String> tags,
            @ForAll("nonRetryableErrors") String errorMessage) {

        var infra = buildPipelineInfra();

        var callCount = new AtomicInteger(0);
        String toolId = "builtin.test.nonretry";
        var tool = BuiltinTool.builder()
                .id(toolId)
                .name("test-nonretry")
                .description("测试不可重试错误")
                .tags(tags)
                .idempotent(false)
                .budget(ToolBudget.of(Duration.ofSeconds(5), 2, Integer.MAX_VALUE))
                .executor(input -> {
                    callCount.incrementAndGet();
                    return ToolResult.error(errorMessage);
                })
                .build();
        infra.registry.registerBuiltinTool(tool);

        infra.pipeline.execute(toolId, Map.of(), "trace-pbt", null);

        assertEquals(1, callCount.get(),
                "普通工具遇到不可重试错误应仅执行 1 次，但实际执行了 " + callCount.get() + " 次");
    }

    @Provide
    Arbitrary<List<String>> normalTagSets() {
        return Arbitraries.of(NORMAL_TAG_POOL)
                .list().ofMinSize(0).ofMaxSize(5)
                .uniqueElements()
                .map(List::copyOf);
    }

    @Provide
    Arbitrary<String> retryableErrors() {
        return Arbitraries.of(RETRYABLE_ERROR_KEYWORDS);
    }

    @Provide
    Arbitrary<String> nonRetryableErrors() {
        return Arbitraries.of(
                "参数校验失败: name 不能为空",
                "护栏拦截: 操作被阻止",
                "权限不足: 无法访问资源",
                "数据不存在: id=abc-123",
                "格式错误: JSON 解析失败",
                "未知错误: unexpected state"
        );
    }

    record PipelineInfra(DynamicToolRegistry registry, ToolExecutionPipeline pipeline) {}

    private PipelineInfra buildPipelineInfra() {
        var guardrailEngine = mock(GuardrailEngine.class);
        when(guardrailEngine.checkToolCall(any(), any()))
                .thenReturn(new GuardrailResult.Passed("test"));

        var permissionService = mock(PermissionService.class);
        when(permissionService.evaluateAndRecord(any())).thenReturn(passedDecision());

        var permissionRequestFactory = mock(PermissionRequestFactory.class);
        when(permissionRequestFactory.create(any(), any(), any())).thenReturn(defaultPermissionRequest());

        var approvalService = mock(PermissionApprovalService.class);
        var eventPublisher = mock(ApplicationEventPublisher.class);
        var registry = new DynamicToolRegistry(eventPublisher);
        var pipeline = new ToolExecutionPipeline(
                registry,
                guardrailEngine,
                new IdempotencyManager(),
                permissionService,
                permissionRequestFactory,
                approvalService,
                10,
                2.0,
                50
        );
        return new PipelineInfra(registry, pipeline);
    }

    private PermissionRequest defaultPermissionRequest() {
        return new PermissionRequest(
                "builtin.test.normal",
                PermissionActionType.GENERIC_TOOL_OPERATION,
                RiskLevel.LOW,
                "web",
                ExecutionGrantScope.EMPTY,
                "session-1",
                null,
                null,
                "user-1",
                "trace-pbt"
        );
    }

    private PermissionDecisionEntry passedDecision() {
        return new PermissionDecisionEntry(
                "decision-1",
                "session-1",
                "trace-pbt",
                null,
                null,
                "user-1",
                "builtin.test.normal",
                PermissionActionType.GENERIC_TOOL_OPERATION,
                RiskLevel.LOW,
                "web",
                ExecutionGrantScope.EMPTY,
                PermissionDecisionType.PASSED,
                null,
                null,
                null,
                "允许执行",
                Instant.now()
        );
    }
}
