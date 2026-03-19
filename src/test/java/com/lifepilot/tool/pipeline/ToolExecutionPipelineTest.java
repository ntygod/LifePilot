package com.lifepilot.tool.pipeline;

import com.lifepilot.observability.guardrail.GuardrailEngine;
import com.lifepilot.observability.guardrail.GuardrailResult;
import com.lifepilot.interaction.UserConfirmationService;
import com.lifepilot.tool.BuiltinTool;
import com.lifepilot.observability.guardrail.RiskLevel;
import com.lifepilot.tool.model.ToolBudget;
import com.lifepilot.tool.model.ToolResult;
import com.lifepilot.tool.registry.DynamicToolRegistry;
import com.lifepilot.tool.schema.JsonSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * ToolExecutionPipeline 单元测试。
 *
 * @author zsg
 * @since 2026-02-24
 */
class ToolExecutionPipelineTest {

    private DynamicToolRegistry registry;
    private GuardrailEngine guardrailEngine;
    private IdempotencyManager idempotencyManager;
    private ToolExecutionPipeline pipeline;

    @BeforeEach
    void setUp() {
        guardrailEngine = mock(GuardrailEngine.class);
        // 默认所有工具调用通过护栏
        when(guardrailEngine.checkToolCall(any(), any()))
                .thenReturn(new GuardrailResult.Passed("test"));
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        registry = new DynamicToolRegistry(guardrailEngine, publisher);
        idempotencyManager = new IdempotencyManager();
        UserConfirmationService confirmationService = (tool, input, message, streamId) -> true;
        pipeline = new ToolExecutionPipeline(
                registry, guardrailEngine, idempotencyManager, confirmationService,
                100, 2.0, 1000);
    }

    @Test
    void 工具未找到_返回错误() {
        ToolResult result = pipeline.execute("nonexistent", Map.of(), "trace-1", null);
        assertFalse(result.ok());
        assertTrue(result.error().contains("工具未找到"));
    }

    @Test
    void 参数校验失败_返回错误() {
        JsonSchema schema = JsonSchema.of(Map.of(
                "required", List.of("name"),
                "properties", Map.of("name", Map.of("type", "string"))
        ));
        registerTool("test.tool", schema, input -> ToolResult.success(Map.of()));

        // 缺少 required 参数
        ToolResult result = pipeline.execute("test.tool", Map.of(), "trace-1", null);
        assertFalse(result.ok());
        assertTrue(result.error().contains("缺少必需参数"));
    }

    @Test
    void 护栏拦截_黑名单工具() {
        registerTool("test.blocked", JsonSchema.empty(),
                input -> ToolResult.success(Map.of()));
        // Mock 护栏引擎返回 Blocked
        when(guardrailEngine.checkToolCall(any(), any()))
                .thenReturn(new GuardrailResult.Blocked("test-policy", "工具被阻止", RiskLevel.HIGH));

        ToolResult result = pipeline.execute("test.blocked", Map.of(), "trace-1", null);
        assertFalse(result.ok());
        assertTrue(result.error().contains("护栏拦截"));
    }

    @Test
    void 正常执行_返回成功() {
        registerTool("test.echo", JsonSchema.empty(),
                input -> ToolResult.success(Map.of("msg", "hello")));

        ToolResult result = pipeline.execute("test.echo", Map.of(), "trace-1", null);
        assertTrue(result.ok());
        assertEquals("hello", result.getData("msg"));
    }

    @Test
    void 幂等缓存命中_不重复执行() {
        var counter = new java.util.concurrent.atomic.AtomicInteger(0);
        registerToolIdempotent("test.idem", JsonSchema.empty(), input -> {
            counter.incrementAndGet();
            return ToolResult.success(Map.of("count", counter.get()));
        });

        // 第一次执行
        ToolResult r1 = pipeline.execute("test.idem", Map.of(), "t1", "key-1");
        assertTrue(r1.ok());
        assertEquals(1, counter.get());

        // 第二次执行，幂等命中
        ToolResult r2 = pipeline.execute("test.idem", Map.of(), "t2", "key-1");
        assertTrue(r2.ok());
        assertTrue(r2.meta().cacheHit());
        assertEquals(1, counter.get()); // 没有再次执行
    }

    @Test
    void 执行超时_返回错误() {
        BuiltinTool tool = BuiltinTool.builder()
                .id("test.slow").name("Slow").description("慢工具")
                .inputSchema(JsonSchema.empty()).outputSchema(JsonSchema.empty())
                .riskLevel(RiskLevel.LOW).idempotent(false)
                .budget(ToolBudget.of(Duration.ofMillis(100), 0, Integer.MAX_VALUE))
                .tags(List.of())
                .executor(input -> {
                    try { Thread.sleep(5000); } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return ToolResult.success(Map.of());
                })
                .build();
        registry.registerBuiltinTool(tool);

        ToolResult result = pipeline.execute("test.slow", Map.of(), "trace-1", null);
        assertFalse(result.ok());
        assertTrue(result.error().contains("超时"));
    }

    // ─── 辅助方法 ───

    private void registerTool(String id, JsonSchema inputSchema,
                               com.lifepilot.tool.ToolExecutor executor) {
        BuiltinTool tool = BuiltinTool.builder()
                .id(id).name(id).description("测试工具")
                .inputSchema(inputSchema).outputSchema(JsonSchema.empty())
                .riskLevel(RiskLevel.LOW).idempotent(false)
                .budget(ToolBudget.DEFAULT).tags(List.of())
                .executor(executor).build();
        registry.registerBuiltinTool(tool);
    }

    private void registerToolIdempotent(String id, JsonSchema inputSchema,
                                         com.lifepilot.tool.ToolExecutor executor) {
        BuiltinTool tool = BuiltinTool.builder()
                .id(id).name(id).description("测试幂等工具")
                .inputSchema(inputSchema).outputSchema(JsonSchema.empty())
                .riskLevel(RiskLevel.LOW).idempotent(true)
                .budget(ToolBudget.DEFAULT).tags(List.of())
                .executor(executor).build();
        registry.registerBuiltinTool(tool);
    }
}
