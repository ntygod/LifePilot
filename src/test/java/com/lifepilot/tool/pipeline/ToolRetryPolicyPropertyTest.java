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
 * 工具重试策略属性测试 — 验证 handoff 工具不可重试 + 普通工具重试保持。
 *
 * <p>由于 {@code isRetryable()} 是 private 方法，通过 {@code ToolExecutionPipeline.execute()}
 * 间接测试：注册一个计数工具，始终返回错误，验证调用次数。</p>
 *
 * <p><b>Validates: Property 4, 5, Requirements 2.5, 2.6, 3.3</b></p>
 *
 * @author zsg
 * @since 2026-03-05
 */
class ToolRetryPolicyPropertyTest {

    /** 普通工具标签候选池（不含 "handoff"）。 */
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
    //  Property 4 — handoff 工具不可重试
    // ─────────────────────────────────────────────

    /**
     * <b>Validates: Requirements 2.5, 2.6</b>
     *
     * <p>对任意 tags 包含 "handoff" 的 ToolContract，当工具执行返回可重试错误时，
     * ToolExecutionPipeline 不应对其进行重试 — 工具仅被调用 1 次。</p>
     */
    @Property(tries = 50)
    void handoff工具不可重试_任意tags含handoff时仅执行一次(
            @ForAll("handoffTagSets") List<String> tags,
            @ForAll("retryableErrors") String errorMessage) {

        // 构建 pipeline 基础设施
        var infra = buildPipelineInfra();

        // 注册 handoff 工具（tags 包含 "handoff"），始终返回错误
        var callCount = new AtomicInteger(0);
        String toolId = "handoff_to_test_agent";
        var tool = BuiltinTool.builder()
                .id(toolId)
                .name("test-handoff")
                .description("测试 handoff 工具")
                .tags(tags)
                .idempotent(false)
                .budget(ToolBudget.of(Duration.ofSeconds(5), 2, Integer.MAX_VALUE))
                .executor(input -> {
                    callCount.incrementAndGet();
                    return ToolResult.error(errorMessage);
                })
                .build();
        infra.registry.registerBuiltinTool(tool);

        // 执行
        infra.pipeline.execute(toolId, Map.of("task", "测试任务"), "trace-pbt", null);

        // 断言：handoff 工具仅执行 1 次（不重试）
        assertEquals(1, callCount.get(),
                "handoff 工具（tags=" + tags + "）应仅执行 1 次，但实际执行了 "
                        + callCount.get() + " 次（错误消息: " + errorMessage + "）");
    }

    /**
     * <b>Validates: Requirements 2.5</b>
     *
     * <p>对任意 tags 包含 "handoff" 的 ToolContract，即使工具执行返回超时错误，
     * isRetryable 也应返回 false — 通过验证工具仅被调用 1 次来间接确认。</p>
     */
    @Property(tries = 30)
    void handoff工具超时不重试_即使错误消息包含超时关键字(
            @ForAll("handoffTagSets") List<String> tags) {

        var infra = buildPipelineInfra();

        var callCount = new AtomicInteger(0);
        String toolId = "handoff_to_timeout_agent";
        var tool = BuiltinTool.builder()
                .id(toolId)
                .name("test-handoff-timeout")
                .description("测试 handoff 超时工具")
                .tags(tags)
                .idempotent(false)
                .budget(ToolBudget.of(Duration.ofSeconds(5), 2, Integer.MAX_VALUE))
                .executor(input -> {
                    callCount.incrementAndGet();
                    return ToolResult.error("执行超时: 30秒");
                })
                .build();
        infra.registry.registerBuiltinTool(tool);

        infra.pipeline.execute(toolId, Map.of("task", "超时测试"), "trace-pbt", null);

        assertEquals(1, callCount.get(),
                "handoff 工具超时后不应重试，但实际执行了 " + callCount.get() + " 次");
    }

    // ─────────────────────────────────────────────
    //  Property 5 — 普通工具重试保持
    // ─────────────────────────────────────────────

    /**
     * <b>Validates: Requirements 3.3</b>
     *
     * <p>对任意 tags 不含 "handoff" 的 ToolContract，当工具执行返回可重试错误时，
     * ToolExecutionPipeline 应按策略重试 — 工具被调用 1 + maxRetries 次。</p>
     */
    @Property(tries = 50)
    void 普通工具重试保持_任意tags不含handoff时按策略重试(
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
     * <p>对任意 tags 不含 "handoff" 的 ToolContract，当工具执行返回不可重试错误时，
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

    /** 生成包含 "handoff" 的随机 tags 集合（1~5 个标签，必含 "handoff"）。 */
    @Provide
    Arbitrary<List<String>> handoffTagSets() {
        return Arbitraries.of(NORMAL_TAG_POOL)
                .list().ofMinSize(0).ofMaxSize(4)
                .uniqueElements()
                .map(otherTags -> {
                    var tags = new ArrayList<>(otherTags);
                    tags.add("handoff");
                    return List.copyOf(tags);
                });
    }

    /** 生成不含 "handoff" 的随机 tags 集合（0~5 个标签）。 */
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
