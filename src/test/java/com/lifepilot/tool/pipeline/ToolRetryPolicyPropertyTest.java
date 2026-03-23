package com.lifepilot.tool.pipeline;

import com.lifepilot.interaction.UserConfirmationService;
import com.lifepilot.observability.guardrail.GuardrailEngine;
import com.lifepilot.observability.guardrail.GuardrailResult;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.tool.model.ToolBudget;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import net.jqwik.api.*;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 工具重试策略属性测试。
 *
 * <p>由于 {@code isRetryable()} 是 private 方法，通过 {@code ToolExecutionPipeline.execute()}
 * 间接测试：注册一个计数工具，始终返回错误，验证调用次数。</p>
 *
 * <p><b>Validates: Property 5, Requirements 3.3</b></p>
 *
 * @author zsg
 * @since 2026-03-05
 */
class ToolRetryPolicyPropertyTest {

    /** 普通工具标签候选池。 */
    private static final List<String> NORMAL_TAG_POOL = List.of(
            "builtin", "data", "query", "multi-agent", "schedule",
            "habit", "memory", "search", "mcp", "yaml"
    );

    /** 可重试的错误消息关键字。 */
    private static final List<String> RETRYABLE_ERROR_KEYWORDS = List.of(
            "执行超时: 30秒", "连接失败: host unreachable",
            "临时性错误: service unavailable",
            "timeout: 30s", "connection refused",
            "temporary failure"
    );

    // ─────────────────────────────────────────────
    //  Property 5 — 普通工具重试保持
    // ─────────────────────────────────────────────

    /**
     * <b>Validates: Requirements 3.3</b>
     *
     * <p>对任意普通 ToolContract，当工具执行返回可重试错误时，
     * ToolExecutionPipeline 应按策略重试 — 工具被调用 1 + maxRetries 次。</p>
     */
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

        // 断言：普通工具应被调用 1 + maxRetries = 3 次
        assertEquals(1 + maxRetries, callCount.get(),
                "普通工具（tags=" + tags + "）应执行 " + (1 + maxRetries)
                        + " 次（1 初始 + " + maxRetries + " 重试），但实际执行了 "
                        + callCount.get() + " 次（错误消息: " + errorMessage + "）");
    }

    /**
     * <b>Validates: Requirements 3.3</b>
     *
     * <p>对任意普通 ToolContract，当工具执行返回不可重试错误时，
     * ToolExecutionPipeline 不应重试 — 工具仅被调用 1 次。</p>
     */
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

        // 断言：不可重试错误不触发重试
        assertEquals(1, callCount.get(),
                "普通工具遇到不可重试错误应仅执行 1 次，但实际执行了 " + callCount.get() + " 次");
    }

    // ─────────────────────────────────────────────
    //  数据生成器
    // ─────────────────────────────────────────────

    /** 生成普通工具随机 tags 集合（0~5 个标签）。 */
    @Provide
    Arbitrary<List<String>> normalTagSets() {
        return Arbitraries.of(NORMAL_TAG_POOL)
                .list().ofMinSize(0).ofMaxSize(5)
                .uniqueElements()
                .map(List::copyOf);
    }

    /** 生成可重试的错误消息（包含超时/连接/临时关键字）。 */
    @Provide
    Arbitrary<String> retryableErrors() {
        return Arbitraries.of(RETRYABLE_ERROR_KEYWORDS);
    }

    /** 生成不可重试的错误消息（不包含超时/连接/临时关键字）。 */
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

    // ─────────────────────────────────────────────
    //  辅助方法
    // ─────────────────────────────────────────────

    /** Pipeline 基础设施封装。 */
    record PipelineInfra(DynamicToolRegistry registry, ToolExecutionPipeline pipeline) {}

    /** 构建 Pipeline 基础设施（短重试延迟，加速测试）。 */
    private PipelineInfra buildPipelineInfra() {
        var guardrailEngine = mock(GuardrailEngine.class);
        when(guardrailEngine.checkToolCall(any(), any()))
                .thenReturn(new GuardrailResult.Passed("test"));
        var eventPublisher = mock(ApplicationEventPublisher.class);
        var registry = new DynamicToolRegistry(guardrailEngine, eventPublisher);
        var pipeline = new ToolExecutionPipeline(
                registry, guardrailEngine,
                new IdempotencyManager(),
                (UserConfirmationService) (tool, input, message, streamId) -> true,
                10, 2.0, 50);  // 短延迟加速测试
        return new PipelineInfra(registry, pipeline);
    }
}
